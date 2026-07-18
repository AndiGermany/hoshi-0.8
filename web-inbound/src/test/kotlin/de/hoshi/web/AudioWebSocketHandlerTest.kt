package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.EpisodicWriter
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.WorkingSessionWriter
import de.hoshi.kernel.PerimeterPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.util.concurrent.TimeUnit
import java.util.function.Function

/**
 * **AudioWebSocketHandlerTest** — beweist den `/ws/audio`-Turn ohne Brain/Socket:
 * eine hand-rollte [FakeWebSocketSession] treibt den echten reaktiven [handle]-Pfad,
 * ein **Fake-STT** liefert ein kanned Transkript, der Turn ist ein **Funktions-Seam**
 * (kanned `Flux<ChatEvent>` statt Orchestrator/Brain), und eine leere [TtsPort] hält
 * Audio raus (fokussierte Assertions auf die Steuer-/llm-Frames).
 *
 * Geprüft: start→binär→stop ⇒ `transcript` dann `llm_*` dann `llm_done`; leeres
 * Transkript ⇒ `no_input`; `abort` ⇒ `turn_aborted`; fehlender/falscher Token
 * (non-loopback) ⇒ Close 1008.
 */
class AudioWebSocketHandlerTest {

    private val mapper = ObjectMapper()
    private val noAudioTts = TtsPort { _, _ -> Mono.just(ByteArray(0)) } // KÜR aus ⇒ keine Audio-Frames
    private val ttsStage = TtsStage(tts = noAudioTts)
    private val perimeter = PerimeterPort(enabled = true, configuredToken = "test-secret-token")

    private fun handler(
        stt: SttPort,
        runTurn: (ChatRequest) -> Flux<ChatEvent> = { Flux.empty() },
    ): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = stt,
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = runTurn,
        )

    /** Handler mit aktivem Audio-Byte-Cap (Ticket #9), [maxBytes] pro Turn. */
    private fun cappedHandler(stt: SttPort, maxBytes: Int): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = stt,
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = { Flux.empty() },
            audioCapEnabled = true,
            maxAudioBytesPerTurn = maxBytes,
        )

    private fun types(frames: List<String>): List<String> =
        frames.map { mapper.readTree(it)["type"].asText() }

    @Test
    fun `start binaer stop ergibt transcript dann llm-Frames dann llm_done`() {
        val stt = SttPort { _, _ -> Mono.just("Mach das Licht an") }
        val turn: (ChatRequest) -> Flux<ChatEvent> = {
            Flux.just(
                ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "brain"),
                ChatEvent.TextDelta("Klar, mach ich."),
                ChatEvent.Done(provider = "LOCAL"),
            )
        }
        val session = FakeWebSocketSession(loopback = true)
        val sub = handler(stt, turn).handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"t1","language":"de","room":"buero"}""")
        session.pushBinary(ByteArray(2000) { 42 })
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(
            listOf("transcribing_started", "transcript", "llm_thinking", "llm_start", "llm_delta", "llm_done"),
            types(session.sent),
        )
        val transcript = session.sent.first { mapper.readTree(it)["type"].asText() == "transcript" }
        assertEquals("Mach das Licht an", mapper.readTree(transcript)["text"].asText())
        // turnId reist mit den llm-Frames (Satellit korreliert), nicht mit dem STT-Frame.
        val llmDone = session.sent.first { mapper.readTree(it)["type"].asText() == "llm_done" }
        assertEquals("t1", mapper.readTree(llmDone)["turnId"].asText())
        sub.dispose()
    }

    @Test
    fun `leeres Transkript ergibt no_input und KEIN llm_start`() {
        val stt = SttPort { _, _ -> Mono.just("") }
        val session = FakeWebSocketSession(loopback = true)
        val sub = handler(stt).handle(session).subscribe()

        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(800))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(listOf("transcribing_started", "transcript", "no_input"), types(session.sent))
        assertFalse(session.sent.any { mapper.readTree(it)["type"].asText() == "llm_start" }, "kein Brain-Turn auf Leere")
        sub.dispose()
    }

    @Test
    fun `STT-Fehler endet never-silent in no_input (kein Crash)`() {
        val stt = SttPort { _, _ -> Mono.error(RuntimeException("whisper :9001 weg")) }
        val session = FakeWebSocketSession(loopback = true)
        val sub = handler(stt).handle(session).subscribe()

        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(1200))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertTrue(session.sent.any { mapper.readTree(it)["type"].asText() == "no_input" }, "STT-Fehler ⇒ no_input")
        sub.dispose()
    }

    @Test
    fun `abort ergibt turn_aborted mit turnId`() {
        val stt = SttPort { _, _ -> Mono.just("egal") }
        val session = FakeWebSocketSession(loopback = true)
        val sub = handler(stt).handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"t7"}""")
        session.pushText("""{"type":"abort","turnId":"t7"}""")
        session.completeInbound()

        assertEquals(listOf("turn_aborted", "llm_done"), types(session.sent))
        val aborted = session.sent.first()
        assertEquals("t7", mapper.readTree(aborted)["turnId"].asText())
        sub.dispose()
    }

    // ── P1-Bug: activeTurns wird bei überlappendem Turn überschrieben statt disposed ──
    // (Doppel-Wake ohne vorheriges abort — Half-Duplex-AEC am Gerät ist tot). Ein echter
    // Cross-Thread-Race zwischen dem Terminate-Callback einer Turn-Subscription und einem
    // neuen `start`/`abort` lässt sich in diesem synchronen Test-Harness nicht verlässlich
    // erzwingen — die folgenden Tests beweisen die Invarianten deterministisch: per
    // Dispose-Zähler, per hängendem `Sinks.one` (Turn bleibt bis zur Assertion aktiv) und
    // per direkter `activeTurns`-Manipulation (Test-Seam, Muster [sinks]/[enforceSessionGuard]).

    @Test
    fun `ueberlappender start disposed einen noch aktiven Vorgaenger-Turn`() {
        val disposeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val staleTurn = Disposable { disposeCount.incrementAndGet() }
        val stt = SttPort { _, _ -> Mono.just("egal") }
        val session = FakeWebSocketSession(loopback = true)
        val h = handler(stt)
        val sub = h.handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"A"}""")
        // Simuliert einen noch laufenden Vorgänger-Turn (z.B. weil sein onStop bereits
        // lief und die Pipeline noch nicht fertig ist) — der Zustand, den ein
        // überlappender start OHNE abort vorfindet.
        h.activeTurns["fake-session"] = staleTurn

        session.pushText("""{"type":"start","turnId":"B"}""") // überlappender start, KEIN abort
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(1, disposeCount.get(), "der überholte Vorgänger-Turn wird beim neuen start hart disposed")
        sub.dispose()
    }

    @Test
    fun `ueberlappender start ohne abort verhindert Doppel-Sink-Write vom ueberholten Turn`() {
        val sinkA = Sinks.one<String>()
        var call = 0
        // Turn A hängt auf sinkA (STT nie beantwortet), Turn B bekommt sofort ein Ergebnis.
        val stt = SttPort { _, _ -> call++; if (call == 1) sinkA.asMono() else Mono.just("B-Text") }
        val session = FakeWebSocketSession(loopback = true)
        val sub = handler(stt).handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"A"}""")
        session.pushBinary(ByteArray(10))
        session.pushText("""{"type":"stop"}""") // Turn A aktiv, hängt auf sinkA

        session.pushText("""{"type":"start","turnId":"B"}""") // Doppel-Wake, KEIN abort
        session.pushBinary(ByteArray(10))
        session.pushText("""{"type":"stop"}""") // Turn B läuft synchron durch

        // Turn A "kommt spät zurück" — darf wegen des harten Dispose in onStart(B) KEINE
        // Frames mehr auf den Sink schreiben (sonst Wortsalat: zwei Turns, ein Sink).
        sinkA.tryEmitValue("STALE-A")
        session.completeInbound()

        assertFalse(
            session.sent.any { mapper.readTree(it).path("text").asText("").contains("STALE-A") },
            "der überholte Turn A darf nach dem Overlap keine Frames mehr auf den Sink schreiben",
        )
        val transcript = session.sent.first { mapper.readTree(it)["type"].asText() == "transcript" }
        assertEquals("B-Text", mapper.readTree(transcript)["text"].asText(), "Turn B läuft normal weiter")
        sub.dispose()
    }

    @Test
    fun `spaeter Terminate eines ueberholten Turns entfernt NICHT den frischeren Eintrag`() {
        val sinkA = Sinks.one<String>()
        var call = 0
        val stt = SttPort { _, _ -> call++; if (call == 1) sinkA.asMono() else Mono.just("B") }
        val session = FakeWebSocketSession(loopback = true)
        val h = handler(stt)
        val sub = h.handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"A"}""")
        session.pushBinary(ByteArray(10))
        session.pushText("""{"type":"stop"}""") // Turn A aktiv, hängt auf sinkA
        val turnHandleA = h.activeTurns["fake-session"]
        assertTrue(turnHandleA != null, "Turn A ist im Map aktiv")

        // Ein frischerer Turn hat den Eintrag längst überholt — simuliert genau das
        // Zeitfenster der realen Race: Turn As Terminate-Callback feuert „spät" auf einem
        // anderen Thread, NACHDEM ein Nachfolger schon seinen eigenen Handle gesetzt hat.
        val turnHandleB = Disposables.swap()
        h.activeTurns["fake-session"] = turnHandleB

        // Turn A completed jetzt (spät) — sein Terminate-Callback darf NUR versuchen, den
        // EIGENEN (nicht mehr aktuellen) Eintrag zu entfernen, nicht den von B.
        sinkA.tryEmitValue("stale")

        assertEquals(
            turnHandleB, h.activeTurns["fake-session"],
            "Bs frischerer Eintrag bleibt unangetastet (kein Cross-Turn-Stomp)",
        )
        assertTrue(
            session.sent.any {
                mapper.readTree(it)["type"].asText() == "transcript" && mapper.readTree(it)["text"].asText() == "stale"
            },
            "Sanity: Turn As Terminate-Callback lief wirklich (kein Vacuous Pass)",
        )
        sub.dispose()
    }

    @Test
    fun `abort nach Turn-Wechsel disposed den richtigen laufenden Turn, der ueberholte bleibt stumm`() {
        val sinkA = Sinks.one<String>()
        val sinkB = Sinks.one<String>()
        var call = 0
        val stt = SttPort { _, _ -> call++; if (call == 1) sinkA.asMono() else sinkB.asMono() }
        val session = FakeWebSocketSession(loopback = true)
        val sub = handler(stt).handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"A"}""")
        session.pushBinary(ByteArray(10))
        session.pushText("""{"type":"stop"}""") // Turn A aktiv, hängt auf sinkA

        session.pushText("""{"type":"start","turnId":"B"}""") // Doppel-Wake ⇒ A wird hart disposed (Fix #1)
        session.pushBinary(ByteArray(10))
        session.pushText("""{"type":"stop"}""") // Turn B aktiv, hängt auf sinkB

        session.pushText("""{"type":"abort","turnId":"B"}""") // Barge-in auf den AKTUELLEN Turn

        val abortFrames = session.sent.filter { mapper.readTree(it)["type"].asText() == "turn_aborted" }
        assertEquals(1, abortFrames.size, "genau ein turn_aborted")
        assertEquals("B", mapper.readTree(abortFrames.first())["turnId"].asText(), "abort trifft den aktuellen Turn B")

        // Weder der beim Overlap überholte Turn A (disposed in onStart) noch der per abort
        // beendete Turn B dürfen danach noch Frames erzeugen. WICHTIG: completeInbound()
        // erst NACH dieser Prüfung — sonst würde closeSession() den Outbound-Sink selbst
        // terminieren (tryEmitComplete) und jede spätere Emission maskieren, unabhängig
        // vom eigentlich zu beweisenden Dispose-Verhalten (false-negative-Falle).
        val sentBeforeLateCompletions = session.sent.size
        sinkA.tryEmitValue("late-A")
        sinkB.tryEmitValue("late-B")
        assertEquals(
            sentBeforeLateCompletions, session.sent.size,
            "weder der überholte noch der abgebrochene Turn schreiben danach noch auf den Sink",
        )
        session.completeInbound()
        sub.dispose()
    }

    @Test
    fun `fehlender Token non-loopback schliesst mit 1008`() {
        val stt = SttPort { _, _ -> Mono.just("egal") }
        val session = FakeWebSocketSession(loopback = false, query = null) // kein ?token=
        handler(stt).handle(session).block()

        assertEquals(CloseStatus.POLICY_VIOLATION.code, session.closedWith?.code)
        assertTrue(session.sent.isEmpty(), "abgewiesener Handshake sendet keine Frames")
    }

    @Test
    fun `falscher Token non-loopback schliesst mit 1008`() {
        val stt = SttPort { _, _ -> Mono.just("egal") }
        val session = FakeWebSocketSession(loopback = false, query = "token=falsch")
        handler(stt).handle(session).block()
        assertEquals(CloseStatus.POLICY_VIOLATION.code, session.closedWith?.code)
    }

    @Test
    fun `korrekter Token non-loopback wird akzeptiert (kein Close)`() {
        val stt = SttPort { _, _ -> Mono.just("") }
        val session = FakeWebSocketSession(loopback = false, query = "token=test-secret-token")
        val sub = handler(stt).handle(session).subscribe()
        session.pushText("""{"type":"start"}""")
        session.completeInbound()
        assertNull(session.closedWith, "gültiger Query-Token ⇒ Session bleibt offen")
        sub.dispose()
    }

    // ── Audio-Byte/Dauer-Cap (Ticket #9) ────────────────────────────────────────

    @Test
    fun `audio cap ueberschritten bricht den Turn never-silent ab (llm_error STT plus llm_done)`() {
        val sttCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val stt = SttPort { _, _ -> sttCalls.incrementAndGet(); Mono.just("darf nie laufen") }
        val session = FakeWebSocketSession(loopback = true)
        val sub = cappedHandler(stt, maxBytes = 1000).handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"tcap"}""")
        session.pushBinary(ByteArray(1500) { 7 }) // > 1000 ⇒ Cap reißt
        session.completeInbound()

        val sent = session.sent
        assertTrue(sent.any { mapper.readTree(it)["type"].asText() == "llm_error" }, "Cap ⇒ llm_error")
        val err = sent.first { mapper.readTree(it)["type"].asText() == "llm_error" }
        assertEquals("STT", mapper.readTree(err)["stage"].asText(), "Cap-Fehler ist Stage STT")
        assertEquals("llm_done", mapper.readTree(sent.last())["type"].asText(), "endet never-silent in llm_done")
        assertEquals("tcap", mapper.readTree(err)["turnId"].asText(), "turnId reist mit")
        assertEquals(0, sttCalls.get(), "kein STT/Brain-Turn nach Cap-Abbruch")
        sub.dispose()
    }

    @Test
    fun `nach Cap-Abbruch wird stop ignoriert (kein Doppel-Turn, kein weiteres Puffern)`() {
        val sttCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val stt = SttPort { _, _ -> sttCalls.incrementAndGet(); Mono.just("x") }
        val session = FakeWebSocketSession(loopback = true)
        val sub = cappedHandler(stt, maxBytes = 1000).handle(session).subscribe()

        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(1500))            // Cap reißt
        session.pushBinary(ByteArray(1500))            // weitere Frames ⇒ verworfen (kein Wachsen)
        session.pushText("""{"type":"stop"}""")        // stop nach Cap ⇒ ignoriert
        session.completeInbound()

        assertEquals(0, sttCalls.get(), "nach Cap kein STT-Turn (stop ignoriert)")
        // Genau ein Cap-Abschluss (ein llm_error + ein llm_done), kein zweiter Turn.
        assertEquals(1, session.sent.count { mapper.readTree(it)["type"].asText() == "llm_error" })
        assertEquals(1, session.sent.count { mapper.readTree(it)["type"].asText() == "llm_done" })
        sub.dispose()
    }

    @Test
    fun `unter dem Cap laeuft der Turn normal (Bytes unter Grenze)`() {
        val stt = SttPort { _, _ -> Mono.just("Mach das Licht an") }
        val turn: (ChatRequest) -> Flux<ChatEvent> = {
            Flux.just(ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "brain"))
        }
        val handler = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = turn, audioCapEnabled = true, maxAudioBytesPerTurn = 100_000,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = handler.handle(session).subscribe()

        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(2000) { 42 }) // < 100k ⇒ kein Cap
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertTrue(session.sent.any { mapper.readTree(it)["type"].asText() == "transcript" }, "normaler Turn")
        assertFalse(
            session.sent.any { mapper.readTree(it)["type"].asText() == "llm_error" },
            "unter dem Cap kein Cap-Fehler",
        )
        sub.dispose()
    }

    // ── Session-Guard: Zeit-Achse (Dauer-Deckel + Silence-Timeout), fake Clock ──

    /** Mutierbare Test-Uhr — Tests treiben die Zeit selbst (kein sleep). */
    private class TestClock(
        private var now: java.time.Instant = java.time.Instant.parse("2026-07-01T12:00:00Z"),
    ) : java.time.Clock() {
        fun advance(d: java.time.Duration) { now = now.plus(d) }
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
        override fun instant(): java.time.Instant = now
    }

    /** Handler mit aktivem Session-Guard (30s Deckel, 5s Silence), Sweep NUR manuell. */
    private fun guardedHandler(
        stt: SttPort,
        clock: java.time.Clock,
        runTurn: (ChatRequest) -> Flux<ChatEvent> = { Flux.empty() },
    ): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = stt,
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = runTurn,
            sessionGuard = AudioSessionGuard(
                enabled = true,
                maxRecordingDuration = java.time.Duration.ofSeconds(30),
                silenceTimeout = java.time.Duration.ofSeconds(5),
                clock = clock,
            ),
            guardSweepInterval = java.time.Duration.ofHours(1), // Ticker praktisch aus ⇒ manueller Sweep
        )

    @Test
    fun `silence-timeout finalisiert die Aufnahme und transkribiert was da ist`() {
        val clock = TestClock()
        val stt = SttPort { audio, _ -> Mono.just(if (audio.isNotEmpty()) "Licht an" else "") }
        val session = FakeWebSocketSession(loopback = true)
        val handler = guardedHandler(stt, clock)
        val sub = handler.handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"tg1"}""")
        session.pushBinary(ByteArray(600) { 3 })
        clock.advance(java.time.Duration.ofSeconds(6)) // > 5s keine Frames, KEIN stop
        handler.enforceSessionGuard("fake-session")

        assertEquals(listOf("transcribing_started", "transcript"), types(session.sent).take(2), "Silence ⇒ finalisiert statt ewig warten")
        val transcript = session.sent.first { mapper.readTree(it)["type"].asText() == "transcript" }
        assertEquals("Licht an", mapper.readTree(transcript)["text"].asText(), "transkribiert, was da ist")

        // Ein später doch noch eintreffender echter stop ⇒ idempotent verworfen (kein Doppel-Turn).
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()
        assertEquals(1, session.sent.count { mapper.readTree(it)["type"].asText() == "transcribing_started" }, "kein zweiter Turn nach spätem stop")
        sub.dispose()
    }

    @Test
    fun `dauer-deckel bricht zu lange Aufnahme never-silent ab (llm_error plus llm_done)`() {
        val clock = TestClock()
        val sttCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val stt = SttPort { _, _ -> sttCalls.incrementAndGet(); Mono.just("darf nie laufen") }
        val session = FakeWebSocketSession(loopback = true)
        val handler = guardedHandler(stt, clock)
        val sub = handler.handle(session).subscribe()

        session.pushText("""{"type":"start","turnId":"tg2"}""")
        repeat(16) { // Frames tröpfeln alle 2s (Silence feuert NIE) bis 32s > 30s Gesamt-Dauer
            session.pushBinary(ByteArray(64))
            clock.advance(java.time.Duration.ofSeconds(2))
        }
        handler.enforceSessionGuard("fake-session")

        val err = session.sent.first { mapper.readTree(it)["type"].asText() == "llm_error" }
        assertEquals("STT", mapper.readTree(err)["stage"].asText(), "Deckel-Fehler ist Stage STT")
        assertEquals(AudioWebSocketHandler.SESSION_TOO_LONG_MESSAGE, mapper.readTree(err)["message"].asText(), "warme ehrliche Absage")
        assertEquals("tg2", mapper.readTree(err)["turnId"].asText(), "turnId reist mit")
        assertEquals("llm_done", mapper.readTree(session.sent.last())["type"].asText(), "endet never-silent in llm_done")

        // stop nach Deckel-Abbruch ⇒ ignoriert, kein STT/Brain-Turn, genau EIN Abschluss.
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()
        assertEquals(0, sttCalls.get(), "kein STT-Turn nach Deckel-Abbruch")
        assertEquals(1, session.sent.count { mapper.readTree(it)["type"].asText() == "llm_error" })
        assertEquals(1, session.sent.count { mapper.readTree(it)["type"].asText() == "llm_done" })
        sub.dispose()
    }

    @Test
    fun `guard OFF ist byte-neutral — enforce ist no-op und der Turn laeuft exakt wie heute`() {
        val stt = SttPort { _, _ -> Mono.just("Mach das Licht an") }
        val session = FakeWebSocketSession(loopback = true)
        val h = handler(stt) // Default-Ctor ⇒ Guard OFF
        val sub = h.handle(session).subscribe()

        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(500))
        h.enforceSessionGuard("fake-session") // OFF ⇒ no-op, egal wieviel Echtzeit vergeht
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(listOf("transcribing_started", "transcript", "llm_thinking"), types(session.sent), "identischer Frame-Fluss wie ohne Guard")
        assertFalse(session.sent.any { mapper.readTree(it)["type"].asText() == "llm_error" }, "OFF ⇒ nie ein Guard-Fehler")
        sub.dispose()
    }

    @Test
    fun `normale Session unter den Grenzen bleibt vom aktiven Guard unbeeinflusst`() {
        val clock = TestClock()
        val stt = SttPort { _, _ -> Mono.just("Wie spät ist es") }
        val session = FakeWebSocketSession(loopback = true)
        val handler = guardedHandler(stt, clock)
        val sub = handler.handle(session).subscribe()

        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(400))
        clock.advance(java.time.Duration.ofSeconds(2)) // unter Silence(5s) und Deckel(30s)
        handler.enforceSessionGuard("fake-session") // Sweep mittendrin: darf nichts tun
        session.pushBinary(ByteArray(400))
        session.pushText("""{"type":"stop"}""")
        // Nach stop ist der Guard entschärft — auch viel spätere Sweeps feuern nicht mehr.
        clock.advance(java.time.Duration.ofMinutes(5))
        handler.enforceSessionGuard("fake-session")
        session.completeInbound()

        assertEquals(listOf("transcribing_started", "transcript", "llm_thinking"), types(session.sent), "normaler Turn, genau einmal")
        assertFalse(session.sent.any { mapper.readTree(it)["type"].asText() == "llm_error" }, "unter den Grenzen kein Guard-Eingriff")
        sub.dispose()
    }

    // ── ws-Rand: Persona-Server-Setting (B), Sprecher-Erkennung (C), Memory-Write (D) ──

    /** Mutierbarer Erkenner (Muster VoiceInboundRecognitionTest): enabled + Ergebnis + optionaler Wurf. */
    private class FakeWsIdentify(
        override val enabled: Boolean,
        private val result: Recognition = Recognition.GUEST,
        private val throwIt: Boolean = false,
    ) : SpeakerIdentifyService {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        override fun identify(audioBytes: ByteArray, mime: String): Recognition {
            calls.incrementAndGet()
            if (throwIt) throw RuntimeException("cam++ :9002 weg")
            return result
        }
    }

    /** Recording-Fake für alle drei Store-Hooks zugleich — hält jede benutzte speakerId fest. */
    private class RecWriter(count: Int = 3) : EntityMemoryWriter, EpisodicWriter, WorkingSessionWriter {
        val ids = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = java.util.concurrent.CountDownLatch(count)
        override fun remember(speakerId: String, turnText: String, answer: String) = rec(speakerId)
        override fun record(speakerId: String, userText: String, answer: String) = rec(speakerId)
        override fun append(speakerId: String, userText: String, answer: String) = rec(speakerId)
        private fun rec(id: String) { ids.add(id); done.countDown() }
    }

    private fun oneShotTurn(done: java.util.concurrent.CountDownLatch? = null): (ChatRequest) -> Flux<ChatEvent> = {
        Flux.just<ChatEvent>(ChatEvent.TextDelta("hi"), ChatEvent.Done(provider = "LOCAL"))
            .doOnComplete { done?.countDown() }
    }

    // ── B: Persona-Server-Setting als Fallback (kein Frame-Feld ⇒ Store gewinnt) ──

    @Test
    fun `ws ohne persona-Frame-Feld + Server-Store gesetzt ergibt die Store-Persona`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val onResolver = PersonaResolver(personaEnabled = true)
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { req -> captured.set(req); Flux.just(ChatEvent.Done(provider = "LOCAL")) },
            // Wiring wie in WebSocketConfig, Store liefert RUHIG:
            resolvePersona = { requested -> onResolver.resolve(requested, Persona.RUHIG) },
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start"}""") // KEIN persona-Feld
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(Persona.RUHIG, captured.get()!!.persona, "leeres Feld ⇒ Server-Store gewinnt")
        sub.dispose()
    }

    @Test
    fun `ws mit explizitem persona-Frame-Feld schlaegt den Server-Store`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val onResolver = PersonaResolver(personaEnabled = true)
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { req -> captured.set(req); Flux.just(ChatEvent.Done(provider = "LOCAL")) },
            resolvePersona = { requested -> onResolver.resolve(requested, Persona.RUHIG) },
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","persona":"Kumpel"}""") // explizit gewählt
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(Persona.KUMPEL, captured.get()!!.persona, "explizites Feld gewinnt über den Store")
        sub.dispose()
    }

    // ── Sprach-Settings-Default (Andi-Auftrag 2026-07-20, Sprachpaket-Kern) ──────

    @Test
    fun `ws ohne language-Frame-Feld nutzt den Sprach-Settings-Default - STT-Hint folgt ihm`() {
        val capturedLanguage = java.util.concurrent.atomic.AtomicReference<Language?>(null)
        val h = AudioWebSocketHandler(
            stt = SttPort { _, lang -> capturedLanguage.set(lang); Mono.just("hello") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { Flux.just(ChatEvent.Done(provider = "LOCAL")) },
            // Wiring wie in WebSocketConfig: Language.fromCode(languageStore.languageCode()).
            defaultLanguage = Language.EN,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start"}""") // KEIN language-Feld
        session.pushBinary(ByteArray(800))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(Language.EN, capturedLanguage.get(), "leeres Feld ⇒ der Sprach-Settings-Default gewinnt (STT-Hint)")
        sub.dispose()
    }

    @Test
    fun `ws mit explizitem language-Frame-Feld schlaegt den Sprach-Settings-Default`() {
        val capturedLanguage = java.util.concurrent.atomic.AtomicReference<Language?>(null)
        val h = AudioWebSocketHandler(
            stt = SttPort { _, lang -> capturedLanguage.set(lang); Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { Flux.just(ChatEvent.Done(provider = "LOCAL")) },
            defaultLanguage = Language.EN,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","language":"de"}""") // explizit gewählt
        session.pushBinary(ByteArray(800))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(Language.DE, capturedLanguage.get(), "explizites Frame-Feld gewinnt über den Settings-Default")
        sub.dispose()
    }

    @Test
    fun `ws Default-Handler ohne Wiring bleibt DE (byte-neutral fuer Bestandsgeraete)`() {
        val capturedLanguage = java.util.concurrent.atomic.AtomicReference<Language?>(null)
        val h = handler(SttPort { _, lang -> capturedLanguage.set(lang); Mono.just("hallo") })
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(800))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(Language.DE, capturedLanguage.get(), "kein Wiring ⇒ DE wie heute")
        sub.dispose()
    }

    @Test
    fun `ws Default-Handler ohne Wiring ergibt STANDARD (byte-neutral fuer Bestandsgeraete)`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val h = handler(SttPort { _, _ -> Mono.just("hallo") }) { req -> captured.set(req); Flux.just(ChatEvent.Done(provider = "LOCAL")) }
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(Persona.STANDARD, captured.get()!!.persona, "kein Wiring ⇒ STANDARD wie heute")
        sub.dispose()
    }

    // ── PREP-wecker-am-satelliten (Scheibe 1: Session→Satellit in den ChatRequest) ──

    @Test
    fun `start mit satelliteId - der Turn traegt sie als ChatRequest originSatelliteId`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val h = handler(SttPort { _, _ -> Mono.just("stell einen timer") }) { req -> captured.set(req); Flux.just(ChatEvent.Done(provider = "LOCAL")) }
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","satelliteId":"sat-kueche"}""")
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals("sat-kueche", captured.get()!!.originSatelliteId, "die satelliteId aus dem start-Frame reist im Turn mit")
        assertNull(captured.get()!!.deviceId, "originSatelliteId ist GETRENNT von deviceId (FE-Ursprungs-Bimmeln bleibt unberuehrt)")
        sub.dispose()
    }

    @Test
    fun `start ohne satelliteId - originSatelliteId bleibt null (byte-neutraler Alt-Pfad)`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val h = handler(SttPort { _, _ -> Mono.just("hallo") }) { req -> captured.set(req); Flux.just(ChatEvent.Done(provider = "LOCAL")) }
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start"}""") // KEIN satelliteId-Feld
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertNull(captured.get()!!.originSatelliteId, "kein Feld im Frame ⇒ originSatelliteId=null")
        sub.dispose()
    }

    // ── PREP-wecker-am-satelliten (Scheibe 2: timer_ack-Inbound-Frame) ───────────

    @Test
    fun `timer_ack-Frame reicht die id an den onTimerAck-Hook durch`() {
        val acked = mutableListOf<String>()
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { Flux.empty() },
            onTimerAck = { id -> acked.add(id) },
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"timer_ack","id":"t1"}""")
        session.completeInbound()

        assertEquals(listOf("t1"), acked, "die id aus dem timer_ack-Frame erreicht den Hook")
        sub.dispose()
    }

    @Test
    fun `timer_ack ohne id ruft den Hook nicht - kein Crash`() {
        val acked = mutableListOf<String>()
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { Flux.empty() },
            onTimerAck = { id -> acked.add(id) },
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"timer_ack"}""") // kein id-Feld
        session.completeInbound()

        assertTrue(acked.isEmpty(), "fehlende id ⇒ der Hook wird nicht gerufen")
        sub.dispose()
    }

    @Test
    fun `timer_ack ohne Wiring (No-op-Default) crasht nicht`() {
        val h = handler(SttPort { _, _ -> Mono.just("hallo") })
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"timer_ack","id":"t1"}""")
        session.completeInbound()
        sub.dispose() // kein Crash ist der Beweis
    }

    // ── C: Sprecher-Erkennung am ws-Rand ─────────────────────────────────────────

    @Test
    fun `Identify-Erfolg ergibt SpeakerContext(speakerId, score) und schlaegt den Client-Claim`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val turnDone = java.util.concurrent.CountDownLatch(1)
        val id = FakeWsIdentify(enabled = true, result = Recognition(name = "andi", confidence = 0.97, isGuest = false))
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { req -> captured.set(req); Flux.just<ChatEvent>(ChatEvent.Done(provider = "LOCAL")).doOnComplete { turnDone.countDown() } },
            speakerIdentify = id, wsSpeakerEnabled = true,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","speakerId":"claimguy"}""") // Client BEHAUPTET claimguy
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        // Erkennung läuft async (boundedElastic) — ZUERST auf den Turn warten, DANN inbound
        // schließen (completeInbound ⇒ closeSession ⇒ würde den in-flight Turn sonst disposen).
        assertTrue(turnDone.await(5, TimeUnit.SECONDS), "Turn muss durchlaufen")
        val ctx = captured.get()!!.speakerContext!!
        assertEquals("andi", ctx.speakerId, "Erkennung GEWINNT über den Client-Claim")
        assertEquals(0.97, ctx.score, "der Score reist mit (verifizierter Kontext)")
        assertEquals(1, id.calls.get(), "identify genau einmal gerufen")
        session.completeInbound()
        sub.dispose()
    }

    @Test
    fun `Identify-Fehler laesst den Turn unveraendert und never-silent (Claim bleibt)`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val turnDone = java.util.concurrent.CountDownLatch(1)
        val id = FakeWsIdentify(enabled = true, throwIt = true) // best-effort: wirft ⇒ Gast
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { req -> captured.set(req); Flux.just<ChatEvent>(ChatEvent.Done(provider = "LOCAL")).doOnComplete { turnDone.countDown() } },
            speakerIdentify = id, wsSpeakerEnabled = true,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","speakerId":"claimguy"}""")
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        // turnDone (der Turn emittierte Done + lief zu Ende) beweist never-silent OHNE das
        // nebenläufig noch befüllte session.sent zu lesen (thread-sicher); captured != null
        // beweist, dass der Turn den Identify-Fehler geschluckt hat und trotzdem gebaut wurde.
        assertTrue(turnDone.await(5, TimeUnit.SECONDS), "trotz Identify-Fehler läuft der Turn zu Ende (never-silent)")
        assertEquals("claimguy", captured.get()!!.speakerContext?.speakerId, "Fehler ⇒ heutiges Verhalten, Claim maßgeblich")
        session.completeInbound()
        sub.dispose()
    }

    // ── D: Gedächtnis-Write am ws-Turn-Ende (nur bei echt erkanntem Sprecher) ─────

    @Test
    fun `erkannter Sprecher schreibt alle drei Stores unter der verifizierten Id`() {
        val writer = RecWriter()
        val remember = RememberAfter(writer, writer, writer, speakerTrustEnforced = false, speakerTrustThreshold = 0.80)
        val id = FakeWsIdentify(enabled = true, result = Recognition(name = "andi", confidence = 0.97, isGuest = false))
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = oneShotTurn(),
            speakerIdentify = id, wsSpeakerEnabled = true,
            rememberAfter = remember::rememberAfter,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","speakerId":"claimguy"}""")
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        assertTrue(writer.done.await(5, TimeUnit.SECONDS), "alle drei Stores nach onComplete")
        assertEquals(listOf("andi", "andi", "andi"), writer.ids.toList(), "unter der ERKANNTEN Id, nie unter dem Claim")
        session.completeInbound()
        sub.dispose()
    }

    @Test
    fun `Gast (kein Treffer) schreibt NIE - roher Client-Claim vergiftet kein Memory`() {
        val writer = RecWriter()
        val turnDone = java.util.concurrent.CountDownLatch(1)
        val remember = RememberAfter(writer, writer, writer, speakerTrustEnforced = false, speakerTrustThreshold = 0.80)
        val id = FakeWsIdentify(enabled = true, result = Recognition.GUEST) // kein Treffer
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { _ -> Flux.just<ChatEvent>(ChatEvent.TextDelta("hi"), ChatEvent.Done(provider = "LOCAL")).doOnComplete { turnDone.countDown() } },
            speakerIdentify = id, wsSpeakerEnabled = true,
            rememberAfter = remember::rememberAfter,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","speakerId":"claimguy"}""") // Client behauptet eine Id
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        assertTrue(turnDone.await(5, TimeUnit.SECONDS), "der Turn lief wirklich (kein Vacuous Pass)")
        assertTrue(writer.ids.isEmpty(), "kein Treffer ⇒ KEIN Write (auch nicht unter dem behaupteten Claim)")
        session.completeInbound()
        sub.dispose()
    }

    // ── Flag OFF ⇒ byte-neutral (kein identify-Call, Claim maßgeblich, kein Write) ──

    @Test
    fun `Flag OFF - identify nie gerufen, Claim maßgeblich, kein Memory-Write (byte-neutral)`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val writer = RecWriter()
        val remember = RememberAfter(writer, writer, writer, speakerTrustEnforced = false, speakerTrustThreshold = 0.80)
        val id = FakeWsIdentify(enabled = true, result = Recognition(name = "andi", confidence = 0.97, isGuest = false))
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { req -> captured.set(req); Flux.just(ChatEvent.Done(provider = "LOCAL")) },
            speakerIdentify = id, wsSpeakerEnabled = false, // FLAG OFF
            rememberAfter = remember::rememberAfter,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","speakerId":"claimguy"}""")
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertEquals(0, id.calls.get(), "Flag OFF ⇒ identify wird NIE gerufen")
        assertEquals("claimguy", captured.get()!!.speakerContext?.speakerId, "OFF ⇒ Client-Claim maßgeblich wie heute")
        assertTrue(writer.ids.isEmpty(), "OFF ⇒ kein Memory-Write")
        sub.dispose()
    }

    // ── Capture-Tee am Speaker-Identify-Rand ──────────────────────────────────────

    /** Records-Fake: haelt alle capture()-Aufrufe fest (Kanal/Bytes/Mime). */
    private class RecordingCaptureTee : SpeakerCaptureTee {
        data class Call(val channel: String, val bytes: ByteArray, val mime: String)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())
        override fun capture(channel: String, audioBytes: ByteArray, mime: String) {
            calls.add(Call(channel, audioBytes.copyOf(), mime))
        }
    }

    @Test
    fun `Capture-Tee wird bei aktiver Erkennung genau einmal mit Kanal satellit und den identify-Bytes gerufen`() {
        val capture = RecordingCaptureTee()
        val turnDone = java.util.concurrent.CountDownLatch(1)
        val id = FakeWsIdentify(enabled = true, result = Recognition(name = "andi", confidence = 0.97, isGuest = false))
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { _ -> Flux.just<ChatEvent>(ChatEvent.Done(provider = "LOCAL")).doOnComplete { turnDone.countDown() } },
            speakerIdentify = id, wsSpeakerEnabled = true,
            speakerCapture = capture,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        val audioFrame = ByteArray(100) { 5 }
        session.pushText("""{"type":"start"}""")
        session.pushBinary(audioFrame)
        session.pushText("""{"type":"stop"}""")
        assertTrue(turnDone.await(5, TimeUnit.SECONDS), "Turn muss durchlaufen")
        assertEquals(1, capture.calls.size, "genau ein Capture-Aufruf pro identify-Aufruf")
        val call = capture.calls.single()
        assertEquals(SpeakerCaptureTee.CHANNEL_SATELLITE, call.channel, "Kanal-Kennung am ws-Rand ist 'satellit'")
        assertTrue(audioFrame.contentEquals(call.bytes), "capture() bekommt exakt die gepufferten Mic-Bytes (identisch zum identify-Input)")
        session.completeInbound()
        sub.dispose()
    }

    @Test
    fun `Flag OFF ruft den Capture-Tee nie (kein Extra-IO ohne aktive Erkennung)`() {
        val capture = RecordingCaptureTee()
        val id = FakeWsIdentify(enabled = true, result = Recognition(name = "andi", confidence = 0.97, isGuest = false))
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { Flux.just(ChatEvent.Done(provider = "LOCAL")) },
            speakerIdentify = id, wsSpeakerEnabled = false, // FLAG OFF
            speakerCapture = capture,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start"}""")
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        session.completeInbound()

        assertTrue(capture.calls.isEmpty(), "wsSpeakerEnabled=false ⇒ kein identify ⇒ kein Capture (byte-neutral)")
        sub.dispose()
    }

    @Test
    fun `IO-Fehler des echten Capture-Tees aendert das identify-Ergebnis nicht (never-silent)`(@TempDir dir: java.nio.file.Path) {
        // Realer FileSpeakerCaptureTee auf einem BLOCKIERTEN Zielpfad (eine Datei liegt
        // da, wo ein Verzeichnis hin soll) — jeder Schreibversuch scheitert best-effort.
        val blocked = dir.resolve("blocked")
        java.nio.file.Files.write(blocked, byteArrayOf(1))
        val capture = FileSpeakerCaptureTee(blocked)
        val captured = java.util.concurrent.atomic.AtomicReference<ChatRequest?>(null)
        val turnDone = java.util.concurrent.CountDownLatch(1)
        val id = FakeWsIdentify(enabled = true, result = Recognition(name = "andi", confidence = 0.97, isGuest = false))
        val h = AudioWebSocketHandler(
            stt = SttPort { _, _ -> Mono.just("hallo") },
            ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper,
            runTurn = { req -> captured.set(req); Flux.just<ChatEvent>(ChatEvent.Done(provider = "LOCAL")).doOnComplete { turnDone.countDown() } },
            speakerIdentify = id, wsSpeakerEnabled = true,
            speakerCapture = capture,
        )
        val session = FakeWebSocketSession(loopback = true)
        val sub = h.handle(session).subscribe()
        session.pushText("""{"type":"start","speakerId":"claimguy"}""")
        session.pushBinary(ByteArray(100))
        session.pushText("""{"type":"stop"}""")
        assertTrue(turnDone.await(5, TimeUnit.SECONDS), "Turn laeuft trotz kaputtem Capture-Zielpfad ungestoert durch")
        assertEquals("andi", captured.get()!!.speakerContext?.speakerId, "identify-Ergebnis bleibt unveraendert trotz Capture-IO-Fehler")
        session.completeInbound()
        sub.dispose()
    }

    // ── Hand-rollte WebSocketSession (kein mockk, kein echter Socket) ────────────

    private class FakeWebSocketSession(
        loopback: Boolean,
        query: String? = "token=test-secret-token",
    ) : WebSocketSession {

        val sent = mutableListOf<String>()
        var closedWith: CloseStatus? = null

        private val factory = DefaultDataBufferFactory()
        private val inbound = Sinks.many().unicast().onBackpressureBuffer<WebSocketMessage>()
        private val remote: InetSocketAddress =
            if (loopback) InetSocketAddress(InetAddress.getByName("127.0.0.1"), 51000)
            else InetSocketAddress(InetAddress.getByName("192.168.178.50"), 51000)
        private val handshake = HandshakeInfo(
            URI.create("http://hoshi.local/ws/audio" + (query?.let { "?$it" } ?: "")),
            HttpHeaders(),
            Mono.empty<Principal>(),
            null,
            remote,
            mutableMapOf<String, Any>(),
            "test",
        )

        fun pushText(json: String) {
            inbound.tryEmitNext(textMessage(json))
        }

        fun pushBinary(bytes: ByteArray) {
            inbound.tryEmitNext(WebSocketMessage(WebSocketMessage.Type.BINARY, factory.wrap(bytes)))
        }

        fun completeInbound() {
            inbound.tryEmitComplete()
        }

        override fun getId(): String = "fake-session"
        override fun getHandshakeInfo(): HandshakeInfo = handshake
        override fun bufferFactory(): DataBufferFactory = factory
        override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()
        override fun receive(): Flux<WebSocketMessage> = inbound.asFlux()
        override fun isOpen(): Boolean = closedWith == null

        override fun send(messages: Publisher<WebSocketMessage>): Mono<Void> =
            Flux.from(messages).doOnNext { sent.add(it.payloadAsText) }.then()

        override fun close(status: CloseStatus): Mono<Void> {
            closedWith = status
            return Mono.empty()
        }

        override fun closeStatus(): Mono<CloseStatus> = Mono.justOrEmpty(closedWith)

        override fun textMessage(payload: String): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.TEXT, factory.wrap(payload.toByteArray(StandardCharsets.UTF_8)))

        override fun binaryMessage(payloadFactory: Function<DataBufferFactory, DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(factory))

        override fun pingMessage(payloadFactory: Function<DataBufferFactory, DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(factory))

        override fun pongMessage(payloadFactory: Function<DataBufferFactory, DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(factory))
    }
}
