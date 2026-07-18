package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **Grounding-Ehrlichkeit am ÄUSSERSTEN Rand (2026-07-02)** — beweist die ganze
 * Scheibe-1-Kette OHNE Spring-Boot-Context: ein echter Turn (realer
 * [TurnOrchestrator], Fake-Brain, Fake-Grounding) durch den echten
 * [ChatStreamController]-Diary-Tap schreibt `groundingUsed` EHRLICH aus dem
 * additiven [ChatEvent.Start.grounded]-Feld — nicht mehr hardcoded false:
 *
 *  - Fake-Grounding liefert einen NON-BLANK Block, Provider LOCAL ⇒ der Trace
 *    trägt `groundingUsed=true` (der vorher unmögliche Wert).
 *  - Kontrast: leeres Grounding ⇒ `groundingUsed=false` (ehrlich, wie zuvor).
 */
class ChatStreamDiaryGroundingTest {

    // ── Fake-Brain: eine Zeile, kein Netz ────────────────────────────────────────
    private class FakeBrainPort(private val line: String = "Der Eiffelturm ist 330 Meter hoch.") : BrainPort {
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
        ): Flux<LlmDelta> = Flux.just(LlmDelta(line))
    }

    /** Recording-Diary: hält den EINEN Trace fest und signalisiert den doFinally-Abschluss. */
    private class RecordingTrace : TurnTracePort {
        val trace = AtomicReference<TurnTrace?>(null)
        val done = CountDownLatch(1)
        override fun record(trace: TurnTrace) {
            this.trace.set(trace)
            done.countDown()
        }
    }

    /** Realer Orchestrator (FACT_SHORT/LOCAL-Route) mit injizierbarem Grounding-Block. */
    private fun orchestrator(groundBlock: String): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "fake")
                },
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
                grounding = { _, _ -> Mono.just(groundBlock) },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
            // Gate bewusst DISABLED (Default): Scheibe 1 ist REINE Instrumentierung —
            // grounded reist auch ohne scharfe Wand ehrlich an den Rand.
        )
    }

    private fun controller(orchestrator: TurnOrchestrator, recorder: RecordingTrace) = ChatStreamController(
        orchestrator = orchestrator,
        ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
        languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
        personaResolver = PersonaResolver(personaEnabled = false),
        memoryWriter = EntityMemoryWriter.NOOP,
        episodicWriter = EpisodicWriter.NOOP,
        admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
        turnTrace = recorder,
    )

    private fun runTurn(groundBlock: String): Pair<List<ChatEvent>, TurnTrace> {
        val recorder = RecordingTrace()
        val events = controller(orchestrator(groundBlock), recorder)
            .stream(ChatRequest(text = "Wie hoch ist der Eiffelturm?", speak = false, chatId = "diary-test"))
            .collectList()
            .block(Duration.ofSeconds(5))!!
        // doFinally (Diary-Tap) feuert NACH dem terminalen Signal, ggf. auf boundedElastic —
        // deterministisch auf den record()-Abschluss warten statt zu raten.
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "Diary-Trace muss geschrieben werden")
        return events to recorder.trace.get()!!
    }

    @Test
    fun `non-blank Fake-Grounding + Provider LOCAL - Diary traegt groundingUsed=true`() {
        val (events, trace) = runTurn(
            groundBlock = "\n\n---\nHINTERGRUND: • Eiffelturm: Eisenfachwerkturm in Paris, 330 Meter …\n",
        )

        val start = events.filterIsInstance<ChatEvent.Start>().first()
        assertTrue(start.grounded, "das additive Start-Feld muss den gedeckten Turn tragen")
        assertEquals("brain", start.model)
        assertTrue(trace.groundingUsed, "der Rand liest grounded ehrlich aus dem Start-Event — kein hardcoded false")
        assertEquals("FACT_SHORT", trace.category)
        assertEquals("LOCAL", trace.provider)
        assertFalse(trace.deflected, "gedeckter Turn ist keine Deflection")
        assertEquals(TurnDiaryTap.SOURCE_CHAT, trace.source, "der Chat-Rand markiert seinen Eingangs-Weg")
    }

    @Test
    fun `leeres Grounding - Diary traegt ehrlich groundingUsed=false`() {
        val (events, trace) = runTurn(groundBlock = "")

        assertFalse(events.filterIsInstance<ChatEvent.Start>().first().grounded)
        assertFalse(trace.groundingUsed, "leerer Block ⇒ ehrlich false (wie bisher, nur jetzt gemessen)")
    }
}
