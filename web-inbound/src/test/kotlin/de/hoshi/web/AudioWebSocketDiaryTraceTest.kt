package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import de.hoshi.kernel.PerimeterPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **WS-Diary-Ehrlichkeit (2026-07-05)** — der Befund am dritten Rand: Turns über
 * `/ws/audio` (der Rand, über den der Voice-PE-Satellit spricht) landeten GAR
 * NICHT im Turn-Diary — Chat und Voice-HTTP schon. Dieser Test beweist den Fix
 * OHNE Socket/Brain, exakt im Muster von [VoiceInboundDiaryTraceTest]: der echte
 * [AudioWebSocketHandler] (Fake-STT, kanned Turn-Seam) mit dem geteilten
 * [TurnDiaryTap]:
 *
 *  1. WS-Turn ⇒ GENAU EINE Trace mit denselben ehrlichen Feldern wie an den
 *     HTTP-Rändern PLUS `source="ws"` — und PRIVACY: die WS-sessionId (eine
 *     Geräte-/Session-Kennung) steht NICHT in der Trace (`chatId=""`).
 *  2. Leeres Transkript (`no_input`-Frame) ⇒ NO_INPUT-Muster vom Voice-Rand
 *     gespiegelt: eigene Kategorie [TurnDiaryTap.CATEGORY_NO_INPUT] + `error="STT"`,
 *     während der Wire-Fluss UNVERÄNDERT bleibt (kein `llm_error`-Frame).
 *  3. NOOP-Default (Konstruktion OHNE turnTrace-Param) ⇒ Frame-für-Frame
 *     identischer Wire-Strom und NIE ein Write — byte-neutral, OFF = heute.
 *  4. Guard-Abbrüche (2026-07-05, die bewusst offene Lücke): Byte-Cap
 *     ([AudioWebSocketHandler.onBinary]) und Session-Guard-Dauer-Deckel
 *     ([AudioWebSocketHandler.enforceSessionGuard]) ⇒ GENAU EINE Trace mit
 *     eigener Kategorie [TurnDiaryTap.CATEGORY_ABORTED] (NICHT NO_INPUT) +
 *     Grund ([TurnDiaryTap.ABORT_AUDIO_CAP]/[TurnDiaryTap.ABORT_SESSION_GUARD])
 *     im error-Feld — per direktem record (an diesen Nähten existiert kein
 *     ChatEvent-Strom), Wire-Frames Frame-für-Frame unverändert.
 */
class AudioWebSocketDiaryTraceTest {

    private val mapper = ObjectMapper()
    private val noAudioTts = TtsPort { _, _ -> Mono.just(ByteArray(0)) } // keine Audio-Frames
    private val ttsStage = TtsStage(tts = noAudioTts)
    private val perimeter = PerimeterPort(enabled = true, configuredToken = "test-secret-token")

    /** Recording-Diary: zählt ALLE records (beweist „genau eine") + hält die letzte Trace. */
    private class RecordingTrace : TurnTracePort {
        val count = AtomicInteger(0)
        val last = AtomicReference<TurnTrace?>(null)
        val done = CountDownLatch(1)
        override fun record(trace: TurnTrace) {
            count.incrementAndGet()
            last.set(trace)
            done.countDown()
        }
    }

    /** Kanned Turn (Funktions-Seam statt Brain): Start → Delta → Done. */
    private val cannedTurn: (ChatRequest) -> Flux<ChatEvent> = {
        Flux.just(
            ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "brain", grounded = true),
            ChatEvent.TextDelta("Klar, mach ich."),
            ChatEvent.Done(provider = "LOCAL"),
        )
    }

    private fun handler(stt: SttPort, turnTrace: TurnTracePort): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = stt,
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = cannedTurn,
            turnTrace = turnTrace,
        )

    /** Konstruktion OHNE turnTrace-Param == NOOP-Default == exakt heutiges Verhalten. */
    private fun defaultHandler(stt: SttPort): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = stt,
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = cannedTurn,
        )

    /**
     * Treibt einen vollen WS-Turn über die internen Handler-Seams (start → binär →
     * stop) und sammelt die gesendeten Wire-Frames aus dem Session-Sink ein
     * (Fake-STT/-Turn sind synchron ⇒ alle Frames liegen gepuffert vor).
     */
    private fun runWsTurn(h: AudioWebSocketHandler, sessionId: String = "ws-diary-session"): List<String> {
        h.openSession(sessionId)
        h.onText(sessionId, """{"type":"start","turnId":"t1","language":"de"}""")
        h.onBinary(sessionId, ByteArray(1200) { 7 })
        h.onText(sessionId, """{"type":"stop"}""")
        val frames = mutableListOf<String>()
        h.sinks[sessionId]!!.asFlux().subscribe { frames.add(it) }
        return frames
    }

    private fun types(frames: List<String>): List<String> =
        frames.map { mapper.readTree(it)["type"].asText() }

    @Test
    fun `ws-turn schreibt genau EINE Trace - source=ws mit den Chat-Feldern, ohne sessionId`() {
        val recorder = RecordingTrace()
        val stt = SttPort { _, _ -> Mono.just("Mach das Licht an") }
        val frames = runWsTurn(handler(stt, recorder))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "Diary-Trace muss geschrieben werden")

        // Der Wire-Fluss selbst bleibt der heutige (Tap ist rein passiv).
        assertEquals(
            listOf("transcribing_started", "transcript", "llm_thinking", "llm_start", "llm_delta", "llm_done"),
            types(frames),
        )

        assertEquals(1, recorder.count.get(), "GENAU eine Trace pro WS-Turn")
        val trace = recorder.last.get()!!
        assertEquals(TurnDiaryTap.SOURCE_WS, trace.source, "die Trace muss den WS-Weg erkennbar machen")
        assertEquals("SMART_HOME", trace.category, "Routing-Kategorie aus dem Start-Event")
        assertEquals("LOCAL", trace.provider)
        assertEquals("STANDARD", trace.persona, "WS-Rand hat keine Persona-Wahl ⇒ Request-Default")
        assertEquals("DE", trace.language, "die per start-Frame gesetzte Session-Sprache")
        assertTrue(trace.speak, "ein WS-Turn spricht immer (TtsStage aktiv)")
        assertTrue(trace.groundingUsed, "grounded reist ehrlich aus dem Start-Event in die Trace")
        assertFalse(trace.deflected)
        assertNull(trace.error, "fehlerfreier Turn ⇒ error=null")
        assertNotNull(trace.ttftMs, "erste TextDelta wurde gemessen")
        assertTrue(trace.deltaChars > 0, "die Antwortlänge wurde gemessen")
        assertEquals(0, trace.audioChunks, "leere Fake-TTS ⇒ keine AudioChunks")
        assertEquals("", trace.chatId, "PRIVACY: die WS-sessionId gehört NICHT ins Diary")
    }

    @Test
    fun `leeres Transkript - Trace mit Kategorie NO_INPUT und error=STT, Wire unveraendert`() {
        // NO_INPUT-Muster vom Voice-Rand gespiegelt: auch der stumme WS-Turn ist
        // Betriebs-Wahrheit (der Satellit hat aufgenommen, Hoshi hat nichts
        // verstanden) und bekommt seine ehrliche Zeile.
        val recorder = RecordingTrace()
        val stt = SttPort { _, _ -> Mono.just("") }
        val frames = runWsTurn(handler(stt, recorder))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "auch der stumme Turn muss ins Diary")

        // Never-Silent-Wire UNVERÄNDERT: transcript("") + no_input — und
        // insbesondere KEIN llm_error-Frame aus dem trace-only Error-Event.
        assertEquals(listOf("transcribing_started", "transcript", "no_input"), types(frames))

        assertEquals(1, recorder.count.get(), "GENAU eine Trace auch für no_input")
        val trace = recorder.last.get()!!
        assertEquals(TurnDiaryTap.CATEGORY_NO_INPUT, trace.category, "eigene Kategorie statt leerer — filterbar")
        assertEquals("STT", trace.error, "die Fehler-Stage steht ehrlich in der Trace")
        assertEquals(TurnDiaryTap.SOURCE_WS, trace.source)
        assertEquals(0, trace.deltaChars, "keine Antwort ⇒ 0 Zeichen")
        assertNull(trace.ttftMs, "nie eine TextDelta gesehen ⇒ ttftMs=null")
        assertFalse(trace.deflected)
        assertEquals("", trace.chatId, "PRIVACY: auch der stumme Turn trägt keine sessionId")
    }

    // ── Guard-Abbrüche (2026-07-05): die bewusst offene Diary-Lücke geschlossen ─

    /** Handler mit aktivem Byte-Cap (1000 B) — [turnTrace] Recorder ODER Default (NOOP). */
    private fun cappedHandler(turnTrace: TurnTracePort? = null): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("darf nie laufen") },
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = cannedTurn,
            audioCapEnabled = true,
            maxAudioBytesPerTurn = 1000,
            turnTrace = turnTrace ?: TurnTracePort.NOOP,
        )

    /** Mutierbare Test-Uhr (Muster [AudioWebSocketHandlerTest]) — Tests treiben die Zeit selbst. */
    private class TestClock(
        private var now: java.time.Instant = java.time.Instant.parse("2026-07-05T12:00:00Z"),
    ) : java.time.Clock() {
        fun advance(d: java.time.Duration) { now = now.plus(d) }
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
        override fun instant(): java.time.Instant = now
    }

    /** Handler mit aktivem Session-Guard (30s Deckel, fake Clock, Sweep NUR manuell). */
    private fun guardedHandler(clock: java.time.Clock, turnTrace: TurnTracePort? = null): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("darf nie laufen") },
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = cannedTurn,
            sessionGuard = AudioSessionGuard(
                enabled = true,
                maxRecordingDuration = java.time.Duration.ofSeconds(30),
                silenceTimeout = java.time.Duration.ofSeconds(5),
                clock = clock,
            ),
            guardSweepInterval = java.time.Duration.ofHours(1), // Ticker praktisch aus ⇒ manueller Sweep
            turnTrace = turnTrace ?: TurnTracePort.NOOP,
        )

    /** Treibt einen Byte-Cap-Abbruch (start → zu großer binär-Frame → stop) und sammelt Frames. */
    private fun runCapAbort(h: AudioWebSocketHandler, sessionId: String = "ws-cap-session"): List<String> {
        h.openSession(sessionId)
        h.onText(sessionId, """{"type":"start","turnId":"tcap","language":"de"}""")
        h.onBinary(sessionId, ByteArray(1500) { 7 }) // > 1000 ⇒ Cap reißt
        h.onText(sessionId, """{"type":"stop"}""") // nach Cap-Abbruch ignoriert
        val frames = mutableListOf<String>()
        h.sinks[sessionId]!!.asFlux().subscribe { frames.add(it) }
        return frames
    }

    /** Treibt einen Dauer-Deckel-Abbruch (start → Frame → 31s → manueller Sweep). */
    private fun runGuardAbort(h: AudioWebSocketHandler, clock: TestClock, sessionId: String = "ws-guard-session"): List<String> {
        h.openSession(sessionId)
        h.onText(sessionId, """{"type":"start","turnId":"tguard","language":"de"}""")
        h.onBinary(sessionId, ByteArray(64) { 1 })
        clock.advance(java.time.Duration.ofSeconds(31)) // > 30s Dauer-Deckel
        h.enforceSessionGuard(sessionId)
        val frames = mutableListOf<String>()
        h.sinks[sessionId]!!.asFlux().subscribe { frames.add(it) }
        return frames
    }

    @Test
    fun `byte-cap-abbruch - genau EINE Trace ABORTED mit Grund AUDIO_CAP, Wire unveraendert`() {
        val recorder = RecordingTrace()
        val frames = runCapAbort(cappedHandler(recorder))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "auch der Cap-Abbruch muss ins Diary")

        // Never-Silent-Wire UNVERÄNDERT: llm_error(STT) + llm_done, kein STT-/Brain-Turn.
        assertEquals(listOf("llm_error", "llm_done"), types(frames))
        val err = mapper.readTree(frames.first())
        assertEquals("STT", err["stage"].asText(), "Wire-Stage bleibt STT wie heute")
        assertEquals(AudioWebSocketHandler.AUDIO_CAP_MESSAGE, err["message"].asText())

        assertEquals(1, recorder.count.get(), "GENAU eine Trace pro Cap-Abbruch (Folge-Frames gekappt)")
        val trace = recorder.last.get()!!
        assertEquals(TurnDiaryTap.CATEGORY_ABORTED, trace.category, "eigene Kategorie — NICHT NO_INPUT")
        assertEquals(TurnDiaryTap.ABORT_AUDIO_CAP, trace.error, "das error-Feld trägt den Abbruch-GRUND")
        assertEquals(TurnDiaryTap.SOURCE_WS, trace.source)
        assertEquals("STANDARD", trace.persona, "WS-Rand hat keine Persona-Wahl")
        assertEquals("DE", trace.language, "die per start-Frame gesetzte Session-Sprache")
        assertTrue(trace.speak, "ein WS-Turn spricht immer")
        assertEquals(0, trace.deltaChars)
        assertNull(trace.ttftMs, "kein Turn lief ⇒ ttftMs=null")
        assertEquals("", trace.chatId, "PRIVACY: keine sessionId im Diary")
    }

    @Test
    fun `session-guard-dauer-deckel - genau EINE Trace ABORTED mit Grund SESSION_GUARD, Wire unveraendert`() {
        val recorder = RecordingTrace()
        val clock = TestClock()
        val frames = runGuardAbort(guardedHandler(clock, recorder), clock)
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "auch der Deckel-Abbruch muss ins Diary")

        // Never-Silent-Wire UNVERÄNDERT: llm_error(STT) + llm_done, kein STT-/Brain-Turn.
        assertEquals(listOf("llm_error", "llm_done"), types(frames))
        val err = mapper.readTree(frames.first())
        assertEquals("STT", err["stage"].asText(), "Wire-Stage bleibt STT wie heute")
        assertEquals(AudioWebSocketHandler.SESSION_TOO_LONG_MESSAGE, err["message"].asText())

        assertEquals(1, recorder.count.get(), "GENAU eine Trace (expire hat Drain-Semantik)")
        val trace = recorder.last.get()!!
        assertEquals(TurnDiaryTap.CATEGORY_ABORTED, trace.category, "eigene Kategorie — NICHT NO_INPUT")
        assertEquals(TurnDiaryTap.ABORT_SESSION_GUARD, trace.error, "der Grund unterscheidet Cap und Guard")
        assertEquals(TurnDiaryTap.SOURCE_WS, trace.source)
        assertEquals("", trace.chatId, "PRIVACY: keine sessionId im Diary")
    }

    @Test
    fun `guard-abbrueche unter NOOP - Frame-Strom identisch, nie geschrieben - byte-neutral`() {
        // Byte-Cap: getracter Lauf vs. NOOP-Lauf Frame-für-Frame identisch.
        val capRecorder = RecordingTrace()
        val capTraced = runCapAbort(cappedHandler(capRecorder))
        assertTrue(capRecorder.done.await(5, TimeUnit.SECONDS))
        val capUntraced = runCapAbort(cappedHandler())
        assertEquals(capUntraced, capTraced, "Cap-Abbruch-Wire identisch mit und ohne Diary")
        assertEquals(1, capRecorder.count.get(), "nur der getracte Lauf schrieb — der NOOP-Lauf nie")

        // Dauer-Deckel: dito.
        val guardRecorder = RecordingTrace()
        val tracedClock = TestClock()
        val guardTraced = runGuardAbort(guardedHandler(tracedClock, guardRecorder), tracedClock)
        assertTrue(guardRecorder.done.await(5, TimeUnit.SECONDS))
        val untracedClock = TestClock()
        val guardUntraced = runGuardAbort(guardedHandler(untracedClock), untracedClock)
        assertEquals(guardUntraced, guardTraced, "Deckel-Abbruch-Wire identisch mit und ohne Diary")
        assertEquals(1, guardRecorder.count.get(), "nur der getracte Lauf schrieb — der NOOP-Lauf nie")
    }

    @Test
    fun `NOOP-Default (kein Wiring) - Frame-Strom identisch, nie geschrieben - byte-neutral`() {
        val recorder = RecordingTrace()
        val stt = SttPort { _, _ -> Mono.just("Mach das Licht an") }
        val traced = runWsTurn(handler(stt, recorder))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS))
        val untraced = runWsTurn(defaultHandler(stt))

        assertEquals(untraced, traced, "der Tap ist rein passiv: Frame-für-Frame identischer Wire-Strom")
        assertEquals(1, recorder.count.get(), "nur der getracte Lauf schrieb — der NOOP-Lauf nie")

        // Auch der no_input-Pfad (synthetisches trace-only Error-Event + Filter)
        // ist unter NOOP byte-identisch zum heutigen Verhalten.
        val sttBlank = SttPort { _, _ -> Mono.just("") }
        val tracedBlank = runWsTurn(handler(sttBlank, RecordingTrace()))
        val untracedBlank = runWsTurn(defaultHandler(sttBlank))
        assertEquals(untracedBlank, tracedBlank, "no_input-Wire identisch mit und ohne Diary")
        assertEquals(listOf("transcribing_started", "transcript", "no_input"), types(untracedBlank))
    }
}
