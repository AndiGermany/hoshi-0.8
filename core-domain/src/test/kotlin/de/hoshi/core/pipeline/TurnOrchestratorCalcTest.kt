package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die Verdrahtung des [CalcFastpath] in den [TurnOrchestrator]:
 *  - **OFF (Default):** der Classifier erkennt keine Rechnung ⇒ der Text fällt in
 *    den normalen Brain-Turn — exakt heutiges Verhalten.
 *  - **ON:** eine Rechnung läuft brain-frei (0 Brain-Calls) mit warmer Quittung.
 */
class TurnOrchestratorCalcTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im Calc-Turn NICHT gerufen werden")
        }
    }

    private class DeltaBrain(private val delta: String) : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            return Flux.just(LlmDelta(delta))
        }
    }

    private fun passHonesty() = HonestyGate(
        weakDomain = WeakDomainSignal { false },
        onlineRequest = OnlineRequestSignal { false },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { false },
    )

    private fun routing() = RoutingPolicy(
        keywordRouter = KeywordRouter { RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "fake") },
        llmRefiner = { _, fb -> Mono.just(fb) },
        embeddingRefiner = { _, fb -> Mono.just(fb) },
        softRoutingEnabled = false,
        softRoutingMode = "embedding",
    )

    private fun assembler(persona: PersonaService) = TurnPromptAssembler(
        persona = persona,
        entityMemory = { null },
        grounding = { _, _ -> Mono.just("") },
        episodicMemory = null,
    )

    private fun orchestrator(
        brain: BrainPort,
        intent: ToolIntentClassifier,
        calculator: CalcFastpath,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            intent = intent,
            calculator = calculator,
        )
    }

    private fun run(o: TurnOrchestrator, text: String, language: Language = Language.DE): List<ChatEvent> =
        o.handle(ChatRequest(text = text, language = language)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── OFF: Rechen-Text fällt in den Brain-Turn (byte-neutral) ──────────────
    @Test
    fun `OFF erkennt keine Rechnung und nutzt den Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(calculatorEnabled = false),
            calculator = CalcFastpath.DISABLED,
        )
        val events = run(o, "was ist 5 mal 3")

        assertEquals(1, brain.callCount.get(), "OFF: der Rechen-Text geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: SET läuft brain-frei mit warmer Quittung ─────────────────────────
    @Test
    fun `ON rechnet brain-frei mit warmer Quittung DE`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(calculatorEnabled = true),
            calculator = CalcFastpath(),
        )
        val events = run(o, "was ist 5 mal 3")

        assertEquals(0, brain.callCount.get(), "Calc-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("15"), "Quittung war: ${text(events)}")
        assertTrue(text(events).contains("5 mal 3 ist 15"), "Quittung war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: englische Quittung ───────────────────────────────────────────────
    @Test
    fun `ON rechnet brain-frei mit warmer Quittung EN`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(calculatorEnabled = true),
            calculator = CalcFastpath(),
        )
        val events = run(o, "what is 8 times 9", Language.EN)

        assertEquals(0, brain.callCount.get())
        assertTrue(text(events).contains("72"), "Quittung war: ${text(events)}")
    }

    // ── ON: Division durch 0 ⇒ warme Phrase, brain-frei ──────────────────────
    @Test
    fun `ON Division durch null bleibt brain-frei und warm`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(calculatorEnabled = true),
            calculator = CalcFastpath(),
        )
        val events = run(o, "was ist 10 geteilt durch 0")

        assertEquals(0, brain.callCount.get())
        assertTrue(text(events).lowercase().contains("null"), "Quittung war: ${text(events)}")
    }
}
