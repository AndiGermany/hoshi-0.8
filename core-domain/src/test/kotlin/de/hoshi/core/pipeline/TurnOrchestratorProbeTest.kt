package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
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
 * Beweist die Verdrahtung des [ProbeFastpath] im [TurnOrchestrator] (Golden-
 * Utterance #20, Andis Selbsttest-Ritual „Hoshi, Probe."):
 *
 *  - **OFF (Default, Decke zu):** KEIN `probe`-Parameter gesetzt ⇒ „Probe."
 *    fällt in den normalen Brain-Turn — exakt heutiges Verhalten (byte-neutral).
 *  - **ON:** der Ruf läuft VOR dem Routing, strukturell brain-frei
 *    (`model="policy"`-Start), mit der exakten warmen Quittung + Done.
 */
class TurnOrchestratorProbeTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im Probe-Fastpath-Turn NICHT gerufen werden")
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

    private fun orchestrator(brain: BrainPort, probe: ProbeFastpath? = null): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            probe = probe ?: ProbeFastpath.DISABLED,
        )
    }

    private fun run(o: TurnOrchestrator, request: ChatRequest): List<ChatEvent> =
        o.handle(request).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── OFF (Default): byte-neutral, „Probe." geht den heutigen Brain-Weg ──

    @Test
    fun `OFF laesst Probe unveraendert zum Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val events = run(orchestrator(brain), ChatRequest(text = "Probe."))

        assertEquals(1, brain.callCount.get(), "OFF: 'Probe.' geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: brain-freier Policy-Turn VOR dem Routing ─────────────────────────

    @Test
    fun `ON quittiert Probe brain-frei mit model policy`() {
        val brain = ThrowingBrain()
        val o = orchestrator(brain, probe = ProbeFastpath())
        val events = run(o, ChatRequest(text = "Hoshi, Probe."))

        assertEquals(0, brain.callCount.get(), "Probe-Turn darf den Brain NIE rufen")
        val start = events.first() as ChatEvent.Start
        assertEquals("policy", start.model, "Policy-Direktantwort, kein Brain")
        assertEquals(TurnOrchestrator.CATEGORY_PROBE, start.category)
        assertEquals(ProbeFastpath.RECEIPT, text(events), "die Quittung ist deterministisch + exakt")
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `ON feuert nicht wenn Probe mitten im Satz steht`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val o = orchestrator(brain, probe = ProbeFastpath())
        val events = run(o, ChatRequest(text = "Ich mach noch eine Probe für die Band"))

        assertEquals(1, brain.callCount.get(), "kein eigenstaendiger Probe-Ruf ⇒ normaler Brain-Turn")
        assertTrue(text(events).contains("Brain-Antwort."))
    }
}
