package de.hoshi.web

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.ExistenceClaimSignal
import de.hoshi.core.pipeline.HeuristicLanguageDetector
import de.hoshi.core.pipeline.HonestyGate
import de.hoshi.core.pipeline.HonestySignal
import de.hoshi.core.pipeline.KeywordRouter
import de.hoshi.core.pipeline.LanguageResolver
import de.hoshi.core.pipeline.NamedEntitySignal
import de.hoshi.core.pipeline.OnlineRequestSignal
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.PersonaService
import de.hoshi.core.pipeline.ResponseFormatter
import de.hoshi.core.pipeline.RoutingPolicy
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.pipeline.TurnPromptAssembler
import de.hoshi.core.pipeline.WeakDomainSignal
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.EpisodicWriter
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import de.hoshi.core.port.WorkingSessionPort
import de.hoshi.core.port.WorkingSessionSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **Segment-Diary am ÄUSSERSTEN Rand (S2)** — die ganze Kette OHNE Spring-Boot-
 * Context: readSegment-Entscheidung → [de.hoshi.core.dto.ChatEvent.Start] →
 * Diary-Tap → [TurnTrace] trägt `segmentReset/resetReason/segmentLenTurns`
 * ehrlich (die S4-Kalibrier-Basis, Muster `groundingUsed`).
 */
class ChatStreamDiarySegmentTest {

    private class FakeBrainPort : BrainPort {
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
        ): Flux<LlmDelta> = Flux.just(LlmDelta("Wie wäre Curry?"))
    }

    private class RecordingTrace : TurnTracePort {
        val trace = AtomicReference<TurnTrace?>(null)
        val done = CountDownLatch(1)
        override fun record(trace: TurnTrace) {
            this.trace.set(trace)
            done.countDown()
        }
    }

    /** Fake-Session: fester Segment-Reset (Zeit-Lücke). */
    private class ResettingSession : WorkingSessionPort {
        override fun recentTurns(speakerId: String): List<ChatMessage> = emptyList()
        override fun readSegment(speakerId: String, utterance: String): WorkingSessionSegment =
            WorkingSessionSegment(
                turns = emptyList(),
                segmentReset = true,
                resetReason = WorkingSessionSegment.REASON_TIME_GAP,
                segmentLenTurns = 0,
            )
    }

    private fun orchestrator(): TurnOrchestrator {
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
            brain = FakeBrainPort(),
            workingSession = ResettingSession(),
        )
    }

    @Test
    fun `segment-grenz-felder reisen vom start-event ins diary`() {
        val recorder = RecordingTrace()
        val controller = ChatStreamController(
            orchestrator = orchestrator(),
            ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
            languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
            personaResolver = PersonaResolver(personaEnabled = false),
            memoryWriter = EntityMemoryWriter.NOOP,
            episodicWriter = EpisodicWriter.NOOP,
            admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
            turnTrace = recorder,
        )

        controller.stream(
            ChatRequest(
                text = "Was kochen wir heute?",
                speak = false,
                chatId = "segment-diary-test",
                speakerContext = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 1.0),
            ),
        ).collectList().block(Duration.ofSeconds(5))!!

        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "Diary-Trace muss geschrieben werden")
        val trace = recorder.trace.get()!!
        assertTrue(trace.segmentReset, "der Reset muss ehrlich im Diary landen")
        assertEquals(WorkingSessionSegment.REASON_TIME_GAP, trace.resetReason)
        assertEquals(0, trace.segmentLenTurns)
    }
}
