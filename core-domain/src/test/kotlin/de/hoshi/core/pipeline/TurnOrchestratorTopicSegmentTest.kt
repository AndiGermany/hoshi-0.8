package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.WorkingSessionPort
import de.hoshi.core.port.WorkingSessionSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **Themen-Segment im Orchestrator (S2)** — beweist die readSegment-Naht:
 *
 *  - Die AKTUELLE Äußerung reist als `utterance` in [WorkingSessionPort.readSegment]
 *    (Reset-Phrasen müssen schon DIESEN Turn schneiden können).
 *  - Die Grenz-Entscheidung reist EHRLICH im [ChatEvent.Start]
 *    (`segmentReset/resetReason/segmentLenTurns` — Diary/S4-Kalibrier-Basis).
 *  - Client-history ⇒ Session ungelesen, Start-Felder bleiben Defaults.
 */
class TurnOrchestratorTopicSegmentTest {

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

    /** Fake-Port mit fester Segment-Antwort; fängt die utterance. */
    private class FakeSegmentSession(private val segment: WorkingSessionSegment) : WorkingSessionPort {
        val reads = AtomicInteger(0)
        val lastUtterance = AtomicReference<String?>(null)
        override fun recentTurns(speakerId: String): List<ChatMessage> = segment.turns
        override fun readSegment(speakerId: String, utterance: String): WorkingSessionSegment {
            reads.incrementAndGet()
            lastUtterance.set(utterance)
            return segment
        }
    }

    private fun orchestrator(brain: BrainPort, session: WorkingSessionPort): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter { RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "fake") },
                llmRefiner = { _, fb -> Mono.just(fb) },
                embeddingRefiner = { _, fb -> Mono.just(fb) },
                softRoutingEnabled = false,
                softRoutingMode = "embedding",
            ),
            honesty = HonestyGate(
                weakDomain = WeakDomainSignal { false },
                onlineRequest = OnlineRequestSignal { false },
                existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
                namedEntity = NamedEntitySignal { HonestySignal.NONE },
                cloudEnabled = { false },
            ),
            promptAssembler = TurnPromptAssembler(
                persona = persona,
                entityMemory = { null },
                grounding = { _, _ -> Mono.just("") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            workingSession = session,
        )
    }

    private fun run(o: TurnOrchestrator, request: ChatRequest): List<ChatEvent> =
        o.handle(request).collectList().block(Duration.ofSeconds(5))!!

    private val andi = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 1.0)

    @Test
    fun `segment-reset reist ehrlich im start-event und die history bleibt leer`() {
        val brain = HistoryCapturingBrain()
        val session = FakeSegmentSession(
            WorkingSessionSegment(
                turns = emptyList(),
                segmentReset = true,
                resetReason = WorkingSessionSegment.REASON_TIME_GAP,
                segmentLenTurns = 0,
            ),
        )
        val events = run(
            orchestrator(brain, session),
            ChatRequest(text = "Was kochen wir heute?", speakerContext = andi, language = Language.DE),
        )

        assertTrue(brain.captured.get().isEmpty(), "Reset ⇒ der Brain sieht KEINE Alt-history")
        val start = events.filterIsInstance<ChatEvent.Start>().first()
        assertTrue(start.segmentReset, "die Grenz-Entscheidung muss im Start-Event reisen")
        assertEquals(WorkingSessionSegment.REASON_TIME_GAP, start.resetReason)
        assertEquals(0, start.segmentLenTurns)
        assertEquals("Was kochen wir heute?", session.lastUtterance.get(), "die AKTUELLE Äußerung reist mit")
    }

    @Test
    fun `verlaengertes segment - turns fliessen als history und die laenge reist mit`() {
        val brain = HistoryCapturingBrain()
        val turns = listOf(
            ChatMessage("user", "Wie hoch ist der Tokyo Skytree?"),
            ChatMessage("assistant", "634 Meter."),
        )
        val session = FakeSegmentSession(
            WorkingSessionSegment(turns = turns, segmentReset = false, segmentLenTurns = 1),
        )
        val events = run(
            orchestrator(brain, session),
            ChatRequest(text = "Und wie hoch ist ER?", speakerContext = andi, language = Language.DE),
        )

        assertEquals(turns, brain.captured.get(), "das Segment IST die rekonstruierte history")
        val start = events.filterIsInstance<ChatEvent.Start>().first()
        assertFalse(start.segmentReset)
        assertEquals(1, start.segmentLenTurns)
    }

    @Test
    fun `client-history gewinnt - session ungelesen, start-felder bleiben defaults`() {
        val brain = HistoryCapturingBrain()
        val session = FakeSegmentSession(
            WorkingSessionSegment(turns = emptyList(), segmentReset = true, resetReason = "marker"),
        )
        val clientHistory = listOf(ChatMessage("user", "hi"), ChatMessage("assistant", "hallo"))
        val events = run(
            orchestrator(brain, session),
            ChatRequest(text = "weiter", history = clientHistory, speakerContext = andi, language = Language.DE),
        )

        assertEquals(clientHistory, brain.captured.get())
        assertEquals(0, session.reads.get(), "Client-history ⇒ readSegment wird nie gerufen")
        val start = events.filterIsInstance<ChatEvent.Start>().first()
        assertFalse(start.segmentReset, "Client-history-Turn trägt neutrale Segment-Felder")
        assertEquals(WorkingSessionSegment.REASON_NONE, start.resetReason)
    }
}
