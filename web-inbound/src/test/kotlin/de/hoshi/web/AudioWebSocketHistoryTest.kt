package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import de.hoshi.kernel.PerimeterPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * **AudioWebSocketHistoryTest** — beweist das Session-Gedächtnis am ws-Rand
 * (Andi-Befund 2026-07-21, "Coldplay"-Bug): Folgefragen über den Satelliten
 * verloren ihren Bezug, weil der [ChatRequest] am ws-Rand OHNE [ChatRequest.history]
 * gebaut wurde. Fix: [AudioWebSocketHandler] hält je Session (Muster
 * [AudioWebSocketDownlinkTest]/[AudioWebSocketDiaryTraceTest]: interne Test-Seams
 * [AudioWebSocketHandler.openSession]/[AudioWebSocketHandler.onText]/
 * [AudioWebSocketHandler.onBinary]/[AudioWebSocketHandler.closeSession] statt
 * eines vollen FakeWebSocketSession) den bisherigen Turn-Verlauf und reicht ihn
 * an den nächsten Turn durch.
 *
 * Geprüft:
 *  1. Der zweite Turn trägt den ersten (Transkript + Antwort) im `history`.
 *  2. Der ERSTE Turn einer Session bleibt byte-neutral leer.
 *  3. Ein Turn-Fehler ([ChatEvent.Error] im Strom) hängt nichts an.
 *  4. Die Obergrenze von [AudioWebSocketHandler.MAX_HISTORY_MESSAGES] (24 = 12 Turns) greift.
 *  5. Zwei parallele Sessions mischen sich nie; nach `closeSession` ist der Verlauf weg.
 */
class AudioWebSocketHistoryTest {

    private val mapper = ObjectMapper()
    private val noAudioTts = TtsPort { _, _ -> Mono.just(ByteArray(0)) } // keine Audio-Frames
    private val ttsStage = TtsStage(tts = noAudioTts)
    private val perimeter = PerimeterPort(enabled = true, configuredToken = "test-secret-token")

    /** STT, das nacheinander die übergebenen Transkripte ausliefert (ein Aufruf pro Turn). */
    private class QueueStt(private val transcripts: List<String>) : SttPort {
        private var call = 0
        override fun transcribe(audioWav: ByteArray, language: de.hoshi.core.dto.Language?): Mono<String> =
            Mono.just(transcripts[call++])
    }

    /** Treibt genau einen Turn (start → binär → stop) auf einer bereits offenen Session. */
    private fun driveTurn(h: AudioWebSocketHandler, sessionId: String, turnId: String) {
        h.onText(sessionId, """{"type":"start","turnId":"$turnId"}""")
        h.onBinary(sessionId, ByteArray(200) { 3 })
        h.onText(sessionId, """{"type":"stop"}""")
    }

    @Test
    fun `zweiter Turn traegt den ersten Turn im history`() {
        val requests = mutableListOf<ChatRequest>()
        val stt = QueueStt(listOf("Wer ist Coldplay?", "Was ist deren aktuelles Album?"))
        val runTurn: (ChatRequest) -> Flux<ChatEvent> = { req ->
            requests.add(req)
            Flux.just(
                ChatEvent.Start(provider = "LOCAL", category = "GENERAL", model = "brain"),
                ChatEvent.TextDelta("Antwort zu ${req.text}"),
                ChatEvent.Done(provider = "LOCAL"),
            )
        }
        val h = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper, runTurn = runTurn,
        )
        val sessionId = "sess-hist-1"
        h.openSession(sessionId)
        driveTurn(h, sessionId, "t1")
        driveTurn(h, sessionId, "t2")

        assertEquals(2, requests.size, "beide Turns müssen den Orchestrator erreicht haben")
        assertEquals(
            listOf(
                ChatMessage(role = "user", content = "Wer ist Coldplay?"),
                ChatMessage(role = "assistant", content = "Antwort zu Wer ist Coldplay?"),
            ),
            requests[1].history,
            "der zweite Turn trägt Transkript + Antwort des ersten Turns im history",
        )
        h.closeSession(sessionId)
    }

    @Test
    fun `erster Turn einer Session hat leere History - byte-neutral`() {
        val requests = mutableListOf<ChatRequest>()
        val stt = QueueStt(listOf("Wer ist Coldplay?"))
        val runTurn: (ChatRequest) -> Flux<ChatEvent> = { req ->
            requests.add(req)
            Flux.just(ChatEvent.Done(provider = "LOCAL"))
        }
        val h = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper, runTurn = runTurn,
        )
        val sessionId = "sess-hist-2"
        h.openSession(sessionId)
        driveTurn(h, sessionId, "t1")

        assertEquals(1, requests.size)
        assertTrue(requests[0].history.isEmpty(), "erster Turn ⇒ history=emptyList() wie vor diesem Fix")
        h.closeSession(sessionId)
    }

    @Test
    fun `Turn-Fehler haengt nichts an - der Folge-Turn sieht eine leere History`() {
        val requests = mutableListOf<ChatRequest>()
        var call = 0
        val stt = QueueStt(listOf("Frage eins geht schief", "Frage zwei"))
        val runTurn: (ChatRequest) -> Flux<ChatEvent> = { req ->
            requests.add(req)
            call++
            if (call == 1) {
                // Never-Silent-Fehlerpfad: der Strom trägt ein ChatEvent.Error, schließt aber
                // NORMAL ab (onComplete) — genau der Fall, den appendHistoryAfter erkennen muss.
                Flux.just(
                    ChatEvent.Start(provider = "LOCAL", category = "GENERAL", model = "brain"),
                    ChatEvent.TextDelta("Teil-Antwort, die NICHT zählen darf"),
                    ChatEvent.Error(message = "brain down", stage = ChatEvent.Stage.LLM),
                )
            } else {
                Flux.just(ChatEvent.Done(provider = "LOCAL"))
            }
        }
        val h = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper, runTurn = runTurn,
        )
        val sessionId = "sess-hist-err"
        h.openSession(sessionId)
        driveTurn(h, sessionId, "t1") // schlägt fehl (ChatEvent.Error im Strom)
        driveTurn(h, sessionId, "t2")

        assertEquals(2, requests.size)
        assertTrue(
            requests[1].history.isEmpty(),
            "ein Turn mit ChatEvent.Error hängt NICHTS an — der Folge-Turn sieht eine leere History",
        )
        h.closeSession(sessionId)
    }

    @Test
    fun `Obergrenze von 24 Nachrichten (12 Turns) greift`() {
        val turnCount = 14
        val transcripts = (0 until turnCount).map { "frage-$it" }
        val requests = mutableListOf<ChatRequest>()
        val stt = QueueStt(transcripts)
        val runTurn: (ChatRequest) -> Flux<ChatEvent> = { req ->
            requests.add(req)
            val idx = req.text.removePrefix("frage-")
            Flux.just(
                ChatEvent.Start(provider = "LOCAL", category = "GENERAL", model = "brain"),
                ChatEvent.TextDelta("antwort-$idx"),
                ChatEvent.Done(provider = "LOCAL"),
            )
        }
        val h = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper, runTurn = runTurn,
        )
        val sessionId = "sess-hist-cap"
        h.openSession(sessionId)
        repeat(turnCount) { i -> driveTurn(h, sessionId, "t$i") }

        assertEquals(turnCount, requests.size)
        // Der letzte Turn (Index 13) sieht den Verlauf der 13 vorangegangenen Turns (0..12) —
        // 26 Nachrichten träfen ohne Deckel ein, gekappt auf die letzten 24 (= die letzten 12
        // Turns, 1..12) — Turn 0 ist rausgefallen.
        val lastHistory = requests.last().history
        assertEquals(AudioWebSocketHandler.MAX_HISTORY_MESSAGES, lastHistory.size, "auf 24 gedeckelt")
        assertEquals(
            ChatMessage(role = "user", content = "frage-1"),
            lastHistory.first(),
            "der älteste Turn (frage-0) ist rausgefallen — frage-1 ist jetzt der älteste",
        )
        assertEquals(
            ChatMessage(role = "assistant", content = "antwort-12"),
            lastHistory.last(),
            "der jüngste im Verlauf enthaltene Turn ist der direkte Vorgänger (12)",
        )
        h.closeSession(sessionId)
    }

    @Test
    fun `zwei parallele Sessions mischen ihren Verlauf nie`() {
        val requestsA = mutableListOf<ChatRequest>()
        val requestsB = mutableListOf<ChatRequest>()
        val sttA = QueueStt(listOf("Frage von A"))
        val sttB = QueueStt(listOf("Frage von B"))
        // Zwei getrennte Handler-Instanzen wären trivial isoliert (getrennte Maps) — der
        // schärfere Beweis ist EIN Handler mit zwei verschiedenen sessionIds.
        var activeStt: SttPort = sttA
        val stt = SttPort { audio, lang -> activeStt.transcribe(audio, lang) }
        val runTurn: (ChatRequest) -> Flux<ChatEvent> = { req ->
            if (req.chatId == "sess-A") requestsA.add(req) else requestsB.add(req)
            Flux.just(ChatEvent.TextDelta("Antwort"), ChatEvent.Done(provider = "LOCAL"))
        }
        val h = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper, runTurn = runTurn,
        )
        h.openSession("sess-A")
        h.openSession("sess-B")
        driveTurn(h, "sess-A", "a1")
        activeStt = sttB
        driveTurn(h, "sess-B", "b1")

        assertEquals(1, requestsA.size)
        assertEquals(1, requestsB.size)
        assertTrue(requestsB.first().history.isEmpty(), "Session B sieht NICHTS vom Verlauf der Session A")
        h.closeSession("sess-A")
        h.closeSession("sess-B")
    }

    @Test
    fun `nach closeSession ist der Verlauf weg - kein Leck bei Wiederverwendung derselben Id`() {
        val requests = mutableListOf<ChatRequest>()
        var call = 0
        val transcripts = listOf("erste Session Frage", "neue Session Frage")
        val stt = SttPort { _, _ -> Mono.just(transcripts[call++]) }
        val runTurn: (ChatRequest) -> Flux<ChatEvent> = { req ->
            requests.add(req)
            Flux.just(ChatEvent.TextDelta("Antwort"), ChatEvent.Done(provider = "LOCAL"))
        }
        val h = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper, runTurn = runTurn,
        )
        val sessionId = "sess-hist-reuse"
        h.openSession(sessionId)
        driveTurn(h, sessionId, "t1")
        h.closeSession(sessionId) // Verbindung geht zu ⇒ der Verlauf muss verschwinden

        // Eine neue Verbindung bekommt (zufällig oder nicht) dieselbe sessionId zugewiesen —
        // sie darf NICHTS vom vorherigen Gespräch sehen.
        h.openSession(sessionId)
        driveTurn(h, sessionId, "t2")

        assertEquals(2, requests.size)
        assertTrue(
            requests[1].history.isEmpty(),
            "nach closeSession + erneutem openSession ist der Verlauf weg (kein Leck)",
        )
        h.closeSession(sessionId)
    }
}
