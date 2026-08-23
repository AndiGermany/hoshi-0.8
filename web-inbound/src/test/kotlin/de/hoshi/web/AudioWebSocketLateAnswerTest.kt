package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **Die Eskalations-Antwort darf nicht verpuffen** (Andi-Livetest 2026-08-21, wörtlich:
 * „Nachdem sie online geschaut hat, wurde mir keine Antwort ausgegeben, nur das
 * ‚ich schaue nach'.").
 *
 * **Bewiesene Ursache (nicht vermutet):** `TurnOrchestrator.escalationTurn` emittiert
 * `Start` → `TextDelta("Klar, Moment — ich schau schnell.")` → *[bis 8 s Lookup-Lücke]* →
 * Antwort-`TextDelta` → `Done` in EINEM Flux. Ungestört kommt die Antwort auch am WS an
 * (Fall 1 unten). Trifft in der Lücke aber ein neues `start`-Frame ein, verdrängt
 * [AudioWebSocketHandler.onStart] den laufenden Turn per `dispose()`; Reactors
 * Cancel-Signal feuert WEDER `onComplete` NOCH `onError`, und nur diese beiden
 * Subscriber-Callbacks schreiben Frames ⇒ die Antwort verschwindet **spurlos**, das
 * Gerät hängt in einem nie beendeten Turn (Fall 2).
 *
 * Mit `expectReply` (Naht 1) wird genau dieses neue `start` zum REGELFALL, weil die
 * Firmware nach einer Rückfrage von selbst wieder aufnimmt — deshalb gehören die
 * beiden Nähte zusammen.
 *
 * Gepinnt:
 *  1. ungestört ⇒ die Antwort kommt (Regressionsgrenze, unverändert);
 *  2. Flag OFF ⇒ EXAKT der heutige Verlust, damit die Regression sichtbar bleibt;
 *  3. Flag ON ⇒ die späte Antwort wird als `speak_push` + TTS-Audio in DERSELBEN
 *     Session nachgereicht;
 *  4. Kagami: liefert der verdrängte Turn keinen Text, wird ehrlich gesprochen
 *     statt geschwiegen;
 *  5. ein ausdrückliches `abort` (Barge-in) loest NIE einen Push aus.
 */
class AudioWebSocketLateAnswerTest {

    private val mapper = ObjectMapper()
    private val ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.just(ByteArray(4)) })
    private val perimeter = de.hoshi.kernel.PerimeterPort(enabled = true, configuredToken = "t")
    private val stt = SttPort { _, _ -> Mono.just("ja") }

    private val interim = "Klar, Moment - ich schau schnell."
    private val answer = "Der Mount Everest ist 8849 Meter hoch."

    /**
     * Die ECHTE Form von `escalationTurn`: Kopf sofort, Ergebnis nach [gap].
     * [emptyAnswer] simuliert den Ausgang ohne sprechbaren Text.
     */
    private fun escalationLike(gap: Duration, emptyAnswer: Boolean = false): (ChatRequest) -> Flux<ChatEvent> = {
        Flux.concat(
            Flux.just<ChatEvent>(
                ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "policy", escalated = true),
                ChatEvent.TextDelta(interim, provider = "LOCAL"),
            ),
            Mono.delay(gap).flatMapMany {
                if (emptyAnswer) {
                    Flux.just<ChatEvent>(ChatEvent.Done(provider = "LOCAL"))
                } else {
                    Flux.just<ChatEvent>(
                        ChatEvent.TextDelta(answer, provider = "LOCAL"),
                        ChatEvent.Done(provider = "LOCAL"),
                    )
                }
            },
        )
    }

    private fun handler(
        gap: Duration,
        lateAnswerEnabled: Boolean = false,
        emptyAnswer: Boolean = false,
    ) = AudioWebSocketHandler(
        stt = stt,
        ttsStage = ttsStage,
        perimeter = perimeter,
        objectMapper = mapper,
        runTurn = escalationLike(gap, emptyAnswer),
        lateAnswerEnabled = lateAnswerEnabled,
    )

    private fun frames(h: AudioWebSocketHandler, sid: String): MutableList<String> {
        val got = java.util.Collections.synchronizedList(mutableListOf<String>())
        h.sinks[sid]!!.asFlux().subscribe { got.add(it) }
        return got as MutableList<String>
    }

    /** Ein Turn: start → ein Mic-Frame → stop. */
    private fun speak(h: AudioWebSocketHandler, sid: String, turnId: String) {
        h.onText(sid, """{"type":"start","turnId":"$turnId"}""")
        h.onBinary(sid, ByteArray(10))
        h.onText(sid, """{"type":"stop"}""")
    }

    private fun awaitFrame(got: List<String>, needle: String, timeout: Duration = Duration.ofSeconds(5)): Boolean {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            synchronized(got) { if (got.any { it.contains(needle) }) return true }
            Thread.sleep(20)
        }
        return false
    }

    private fun spoken(got: List<String>): String =
        synchronized(got) {
            got.filter { mapper.readTree(it)["type"].asText() == "llm_delta" }
                .joinToString(" ") { mapper.readTree(it)["text"].asText() }
        }

    // ── (1) Regressionsgrenze: ungestoert kommt die Antwort ───────────────────
    @Test
    fun `ungestoert kommt die Eskalations-Antwort wie bisher an`() {
        val h = handler(Duration.ofMillis(200))
        h.openSession("s")
        val got = frames(h, "s")

        speak(h, "s", "t1")

        assertTrue(awaitFrame(got, "llm_done"), "der Turn endet")
        assertTrue(spoken(got).contains(answer), "die Antwort wird gesprochen: '${spoken(got)}'")
    }

    // ── (2) Flag OFF: der heutige Verlust, unveraendert gepinnt ───────────────
    @Test
    fun `Flag OFF - ein start in der Luecke laesst die Antwort spurlos verpuffen`() {
        val h = handler(Duration.ofMillis(700))
        h.openSession("s")
        val got = frames(h, "s")

        speak(h, "s", "t1")
        assertTrue(awaitFrame(got, "llm_audio"), "die Bruecke wurde gesprochen")
        // Das Geraet nimmt (nach expectReply) von selbst wieder auf.
        h.onText("s", """{"type":"start","turnId":"t2"}""")
        Thread.sleep(1500)

        val text = spoken(got)
        assertTrue(text.contains(interim), "die Bruecke war da")
        assertFalse(text.contains(answer), "HEUTE: die Antwort verpufft — genau Andis Befund")
        assertFalse(
            synchronized(got) { got.any { it.contains("llm_done") || it.contains("llm_error") } },
            "und der Turn wird nie beendet — das Geraet haengt",
        )
    }

    // ── (3) Flag ON: die spaete Antwort wird nachgereicht ─────────────────────
    @Test
    fun `Flag ON - die verdraengte Antwort kommt als speak_push in derselben Session`() {
        val h = handler(Duration.ofMillis(700), lateAnswerEnabled = true)
        h.openSession("s")
        val got = frames(h, "s")

        speak(h, "s", "t1")
        assertTrue(awaitFrame(got, "llm_audio"), "die Bruecke wurde gesprochen")
        h.onText("s", """{"type":"start","turnId":"t2"}""")

        assertTrue(awaitFrame(got, "speak_push"), "der unaufgeforderte Sprech-Push kommt")
        val push = synchronized(got) { got.first { it.contains("\"speak_push\"") } }
        val node = mapper.readTree(push)
        assertEquals("speak_push", node["type"].asText())
        assertEquals("late_answer", node["reason"].asText())
        assertEquals("t1", node["turnId"].asText(), "der Push nennt den VERDRAENGTEN Turn")

        assertTrue(awaitFrame(got, "tts_audio_end"), "und echtes TTS-Audio im bestehenden Idiom")
        val afterPush = synchronized(got) { got.dropWhile { !it.contains("\"speak_push\"") } }
        assertTrue(
            afterPush.any { mapper.readTree(it)["type"].asText() == "llm_audio" },
            "nach der Ankuendigung kommen Audio-Frames: $afterPush",
        )
    }

    // ── (4) Kagami: lieber ehrlich sprechen als schweigen ─────────────────────
    @Test
    fun `Flag ON - ohne sprechbaren Text wird die ehrliche Absage gesprochen`() {
        val h = handler(Duration.ofMillis(700), lateAnswerEnabled = true, emptyAnswer = true)
        h.openSession("s")
        val got = frames(h, "s")

        speak(h, "s", "t1")
        assertTrue(awaitFrame(got, "llm_audio"))
        h.onText("s", """{"type":"start","turnId":"t2"}""")

        assertTrue(awaitFrame(got, "speak_push"), "auch die Absage wird angekuendigt")
        assertTrue(
            awaitFrame(got, "llm_delta"),
            "und als Text gesprochen statt still verworfen",
        )
        val text = spoken(got)
        assertTrue(
            text.contains(LangDe.PACK.escalationUnavailable),
            "die bestehende Ehrlichkeits-Phrase, war: '$text'",
        )
    }

    // ── (5) Barge-in bleibt ein harter Stopp ──────────────────────────────────
    @Test
    fun `Flag ON - ein ausdrueckliches abort loest KEINEN Push aus`() {
        val h = handler(Duration.ofMillis(700), lateAnswerEnabled = true)
        h.openSession("s")
        val got = frames(h, "s")

        speak(h, "s", "t1")
        assertTrue(awaitFrame(got, "llm_audio"))
        h.onText("s", """{"type":"abort","turnId":"t1"}""")
        Thread.sleep(1500)

        assertFalse(
            synchronized(got) { got.any { it.contains("speak_push") } },
            "wer unterbricht, will die Antwort nicht mehr hoeren",
        )
        assertTrue(
            synchronized(got) { got.any { it.contains("turn_aborted") } },
            "die Barge-in-Quittung bleibt",
        )
    }

    // ── (6) Sprache der Session bleibt gewahrt ────────────────────────────────
    @Test
    fun `Flag ON - die Absage spricht die Sprache der Session`() {
        val h = handler(Duration.ofMillis(700), lateAnswerEnabled = true, emptyAnswer = true)
        h.openSession("s")
        val got = frames(h, "s")

        h.onText("s", """{"type":"start","turnId":"t1","language":"en"}""")
        h.onBinary("s", ByteArray(10))
        h.onText("s", """{"type":"stop"}""")
        assertTrue(awaitFrame(got, "llm_audio"))
        h.onText("s", """{"type":"start","turnId":"t2","language":"en"}""")

        assertTrue(awaitFrame(got, "speak_push"))
        assertTrue(awaitFrame(got, "llm_delta"))
        val text = spoken(got)
        assertTrue(
            text.contains(de.hoshi.core.pipeline.lang.LanguagePackRegistry.forLanguage(Language.EN).escalationUnavailable),
            "ein englischer Satellit wird nicht deutsch abgewiesen, war: '$text'",
        )
    }
}
