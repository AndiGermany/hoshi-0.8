package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.WorkingSessionPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **Working-Session-Rekonstruktion im [TurnOrchestrator] (räumliches Gedächtnis, S1)**
 * — beweist die EINE Naht `effectiveHistory` mit einem history-fangenden Fake-Brain:
 *
 *  - Flag OFF ([WorkingSessionPort.NONE], Default): der Brain sieht EXAKT die
 *    Client-history — leer bleibt leer, nicht-leer identisch (byte-neutral).
 *  - Leere Client-history + gefüllte Session ⇒ history wird rekonstruiert.
 *  - NICHT-leere Client-history ⇒ Session wird IGNORIERT (der Client ist im
 *    selben Tab autoritativ) — der Port wird nicht einmal gelesen.
 *  - Kein Speaker-Kontext ⇒ kein Schlüssel ⇒ kein Load.
 */
class TurnOrchestratorWorkingSessionTest {

    /** Fake-Brain, der die übergebene history festhält. */
    private class HistoryCapturingBrain : BrainPort {
        val captured = AtomicReference<List<ChatMessage>>(emptyList())
        override fun streamChat(
            prompt: String,
            systemPrompt: String,
            history: List<ChatMessage>,
            temperature: Double?,
            sessionId: String,
            userId: String,
            tools: List<Map<String, Any?>>,
            toolGrammar: Boolean,
            onPrefill: (Long) -> Unit,
        ): Flux<de.hoshi.core.dto.LlmDelta> {
            captured.set(history)
            return Flux.just(de.hoshi.core.dto.LlmDelta("Hallo!"))
        }
    }

    /** Zählender Fake-Session-Port mit fixen Turns. */
    private class FakeWorkingSession(private val turns: List<ChatMessage>) : WorkingSessionPort {
        val reads = AtomicInteger(0)
        val lastSpeaker = AtomicReference<String?>(null)
        override fun recentTurns(speakerId: String): List<ChatMessage> {
            reads.incrementAndGet()
            lastSpeaker.set(speakerId)
            return turns
        }
    }

    private fun orchestrator(brain: BrainPort, session: WorkingSessionPort? = null): TurnOrchestrator {
        val persona = PersonaService()
        val base = TurnPromptAssembler(
            persona = persona,
            entityMemory = { null },
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = null,
        )
        return if (session == null) {
            TurnOrchestrator(
                routing = routing(),
                honesty = honesty(),
                promptAssembler = base,
                persona = persona,
                formatter = ResponseFormatter(),
                brain = brain,
                // KEIN workingSession-Argument: der Default NONE ist der Flag-OFF-Pfad.
            )
        } else {
            TurnOrchestrator(
                routing = routing(),
                honesty = honesty(),
                promptAssembler = base,
                persona = persona,
                formatter = ResponseFormatter(),
                brain = brain,
                workingSession = session,
            )
        }
    }

    private fun routing() = RoutingPolicy(
        keywordRouter = KeywordRouter { RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "fake") },
        llmRefiner = { _, fb -> Mono.just(fb) },
        embeddingRefiner = { _, fb -> Mono.just(fb) },
        softRoutingEnabled = false,
        softRoutingMode = "embedding",
    )

    private fun honesty() = HonestyGate(
        weakDomain = WeakDomainSignal { false },
        onlineRequest = OnlineRequestSignal { false },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { false },
    )

    private fun run(o: TurnOrchestrator, request: ChatRequest) {
        o.handle(request).collectList().block(Duration.ofSeconds(5))!!
    }

    private val sessionTurns = listOf(
        ChatMessage("user", "Wie hoch ist der Tokyo Skytree?"),
        ChatMessage("assistant", "634 Meter."),
    )

    private val andi = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 1.0)

    // ── Flag OFF (Default NONE) = byte-identisch ────────────────────────────
    @Test
    fun `default NONE - leere client-history bleibt leer (flag-OFF byte-identisch)`() {
        val brain = HistoryCapturingBrain()
        run(orchestrator(brain), ChatRequest(text = "Und wie hoch ist ER?", speakerContext = andi, language = Language.DE))
        assertTrue(brain.captured.get().isEmpty(), "OFF ⇒ keine Rekonstruktion, history bleibt leer wie heute")
    }

    @Test
    fun `default NONE - nicht-leere client-history fliesst identisch durch`() {
        val brain = HistoryCapturingBrain()
        val clientHistory = listOf(ChatMessage("user", "hi"), ChatMessage("assistant", "hallo"))
        run(
            orchestrator(brain),
            ChatRequest(text = "weiter", history = clientHistory, speakerContext = andi, language = Language.DE),
        )
        assertSame(clientHistory, brain.captured.get(), "OFF ⇒ EXAKT die Client-Liste, keine Kopie")
    }

    // ── Flag ON: Rekonstruktion NUR wenn der Client keine history schickt ───
    @Test
    fun `leere client-history plus gefuellte session - history wird rekonstruiert`() {
        val brain = HistoryCapturingBrain()
        val session = FakeWorkingSession(sessionTurns)
        run(
            orchestrator(brain, session),
            ChatRequest(text = "Und wie hoch ist ER?", speakerContext = andi, language = Language.DE),
        )
        assertEquals(sessionTurns, brain.captured.get(), "Server rekonstruiert den Verlauf aus der Session")
        assertEquals("andi", session.lastSpeaker.get(), "Schlüssel ist die speakerId — nie Gerät/chatId")
    }

    @Test
    fun `nicht-leere client-history - session wird IGNORIERT, client gewinnt`() {
        val brain = HistoryCapturingBrain()
        val session = FakeWorkingSession(sessionTurns)
        val clientHistory = listOf(ChatMessage("user", "eigener Verlauf"), ChatMessage("assistant", "ok"))
        run(
            orchestrator(brain, session),
            ChatRequest(text = "weiter", history = clientHistory, speakerContext = andi, language = Language.DE),
        )
        assertSame(clientHistory, brain.captured.get(), "Client-history ist im selben Tab autoritativ")
        assertEquals(0, session.reads.get(), "die Session wird nicht einmal gelesen")
    }

    @Test
    fun `ohne speaker-kontext - kein schluessel, kein load, history bleibt leer`() {
        val brain = HistoryCapturingBrain()
        val session = FakeWorkingSession(sessionTurns)
        run(orchestrator(brain, session), ChatRequest(text = "Und wie hoch ist ER?", language = Language.DE))
        assertTrue(brain.captured.get().isEmpty(), "ohne speakerId keine Rekonstruktion")
        assertEquals(0, session.reads.get(), "ohne Schlüssel wird die Session nie angefasst")
    }
}
