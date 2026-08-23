package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.CapabilityPort
import de.hoshi.core.port.ToolPort
import de.hoshi.core.tools.AgenticToolRegistry
import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **The tool-executor seam of the diary** ([ChatEvent.Done.toolCallRan]) — the field
 * Codex' corpus asked for twice: FALSE_EXECUTION_CLAIM = "the answer claims completion
 * ∧ `toolCallRan == false`" is only decidable if the diary says whether
 * [ToolPort.execute] really ran. Before this seam `targetAreaId=null` conflated "no
 * tool" with "tool without a resolved area", and `claimGateFired` only proved the
 * PREVENTED claim on the tool-free brain path.
 *
 * The contract proven here (the CALL, never its outcome):
 *
 *  1. Deterministic write, kernel GRANT ⇒ `true`.
 *  2. Kernel DENY ⇒ absent (the executor never ran).
 *  3. Smart-home READ (gate-free) ⇒ `true` — same term as [ExecutionClaimGate.armed].
 *  4. Brain-only turn ⇒ absent.
 *  5. Room-clarify redemption (gate + executor) ⇒ `true`, next to `pendingClarify=resolved`.
 *  6. Agentic path: GRANT ⇒ `true`, DENY ⇒ absent.
 *  7. A FAILED execution still counts as `true` (the boundary of this slice).
 */
class TurnOrchestratorToolCallRanTest {

    private class RecordingTool(private val result: ToolResult = ToolResult.Ok("ok")) : ToolPort {
        val calls = mutableListOf<ToolCall>()
        override fun execute(call: ToolCall): ToolResult {
            calls.add(call)
            return result
        }
    }

    private class CountingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            return Flux.just(LlmDelta("Brain-Antwort."))
        }
    }

    /** Emits one PATH-B tool JSON — the agentic path's only input. */
    private class ToolEmittingBrain(private val raw: String) : BrainPort {
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> = Flux.just(LlmDelta(raw))
    }

    private fun passHonesty() = HonestyGate(
        weakDomain = WeakDomainSignal { false },
        onlineRequest = OnlineRequestSignal { false },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { false },
    )

    private fun routing() = RoutingPolicy(
        keywordRouter = KeywordRouter { RouteDecision(RouteCategory.SMART_HOME, RouteProvider.LOCAL, "fake") },
        llmRefiner = { _, fb -> Mono.just(fb) },
        embeddingRefiner = { _, fb -> Mono.just(fb) },
        softRoutingEnabled = false,
        softRoutingMode = "embedding",
    )

    private fun orchestrator(
        brain: BrainPort,
        tools: ToolPort,
        capability: CapabilityPort,
        intent: ToolIntentClassifier = DeterministicToolIntentClassifier(),
        agentic: AgenticToolRegistry? = null,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = TurnPromptAssembler(
                persona = persona,
                entityMemory = { _, _ -> null },
                grounding = GroundingPort.EMPTY,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            intent = intent,
            capability = capability,
            tools = tools,
            lastArea = InMemoryLastAreaStore(),
            pendingAreaClarify = InMemoryPendingAreaClarifyStore(),
            agenticTools = agentic,
        )
    }

    private fun grantAll() = CapabilityPort { call -> GateDecision.Grant(call.data) }

    private fun run(o: TurnOrchestrator, text: String, speaker: SpeakerContext? = andi): List<ChatEvent> =
        o.handle(ChatRequest(text = text, speakerContext = speaker)).collectList().block(Duration.ofSeconds(5))!!

    private fun done(events: List<ChatEvent>): ChatEvent.Done =
        events.filterIsInstance<ChatEvent.Done>().last()

    private val andi = SpeakerContext(speakerId = "andi")

    // ── (1) Deterministic write + GRANT ⇒ the executor ran ────────────────────
    @Test
    fun `Grant-Pfad - Executor lief ⇒ toolCallRan true`() {
        val tool = RecordingTool()
        val o = orchestrator(CountingBrain(), tool, grantAll())

        val events = run(o, "mach das Licht im Wohnzimmer an")

        assertEquals(1, tool.calls.size, "der Executor MUSS gelaufen sein")
        assertEquals(true, done(events).toolCallRan, "gelaufener Executor ⇒ true")
    }

    // ── (2) Kernel DENY ⇒ nothing ran, the field stays absent ─────────────────
    @Test
    fun `Kernel-DENY - kein Executor ⇒ toolCallRan bleibt absent`() {
        val o = orchestrator(
            CountingBrain(),
            ToolPort { _ -> error("Executor darf bei Deny NIE laufen") },
            CapabilityPort { GateDecision.Deny("nope", "Das mache ich gerade nicht.") },
        )

        val events = run(o, "mach das Licht im Wohnzimmer an")

        assertNull(done(events).toolCallRan, "DENY ⇒ nie ein erfundenes true, Done byte-identisch")
    }

    // ── (3) Smart-home READ is a tool call too ────────────────────────────────
    @Test
    fun `Lese-Turn - der Executor liest ⇒ toolCallRan true`() {
        val tool = RecordingTool(ToolResult.Ok("21 Grad."))
        val o = orchestrator(CountingBrain(), tool, grantAll())

        val events = run(o, "wie warm ist es im Wohnzimmer")

        assertTrue(tool.calls.single().read, "der Lese-Pfad ruft den Executor gate-frei")
        assertEquals(true, done(events).toolCallRan, "ein READ ist ein Tool-Call (ExecutionClaimGate-Begriff)")
    }

    // ── (4) Brain-only turn ⇒ structurally tool-free ──────────────────────────
    @Test
    fun `Brain-Turn ohne Tool ⇒ toolCallRan bleibt absent`() {
        val brain = CountingBrain()
        val o = orchestrator(brain, ToolPort { _ -> error("kein Tool in diesem Turn") }, grantAll())

        val events = run(o, "erzähl mir etwas über Kyoto")

        assertEquals(1, brain.callCount.get(), "der Turn lief über den Brain")
        assertNull(done(events).toolCallRan, "tool-freier Pfad ⇒ absent (die Hälfte von FALSE_EXECUTION_CLAIM)")
    }

    // ── (5) Room-clarify redemption runs through gate + executor ──────────────
    @Test
    fun `Clarify-Einloesung - Gate plus Executor ⇒ toolCallRan true`() {
        val tool = RecordingTool()
        val o = orchestrator(CountingBrain(), tool, grantAll())

        val ask = run(o, "Mach das Licht an")
        assertEquals(PendingAreaClarifyPort.OUTCOME_ASKED, done(ask).pendingClarify)
        assertNull(done(ask).toolCallRan, "die Rückfrage selbst ist keine Tat")

        val redeem = run(o, "Wohnzimmer")
        assertEquals("wohnzimmer", tool.calls.single().data["area_id"])
        assertEquals(PendingAreaClarifyPort.OUTCOME_RESOLVED, done(redeem).pendingClarify)
        assertEquals(true, done(redeem).toolCallRan, "die Einlösung lief durch Gate UND Executor")
    }

    // ── (6) Agentic path: same kernel rule, same seam ─────────────────────────
    @Test
    fun `Agentischer GRANT ⇒ toolCallRan true - agentischer DENY ⇒ absent`() {
        val raw = """{"tool":"light_set","args":{"area":"wohnzimmer","state":"on"}}"""
        val tool = RecordingTool(ToolResult.Ok("Wohnzimmer ist an."))
        val granted = orchestrator(
            ToolEmittingBrain(raw), tool, grantAll(),
            intent = ToolIntentClassifier.DISABLED, agentic = AgenticToolRegistry,
        )
        val grantEvents = run(granted, "es ist dunkel hier im wohnzimmer")
        assertEquals(1, tool.calls.size, "der agentische Grant ruft den Executor")
        assertEquals(true, done(grantEvents).toolCallRan)

        val denied = orchestrator(
            ToolEmittingBrain(raw),
            ToolPort { _ -> error("Executor darf bei Deny NIE laufen") },
            CapabilityPort { GateDecision.Deny("nope", "Das mache ich gerade nicht.") },
            intent = ToolIntentClassifier.DISABLED, agentic = AgenticToolRegistry,
        )
        assertNull(done(run(denied, "es ist dunkel hier im wohnzimmer")).toolCallRan)
    }

    // ── (7) Boundary: the CALL counts, not its success ────────────────────────
    @Test
    fun `gescheiterte Ausfuehrung zaehlt trotzdem als gelaufener Executor`() {
        val tool = RecordingTool(ToolResult.Failed("Das hat gerade nicht geklappt."))
        val o = orchestrator(CountingBrain(), tool, grantAll())

        val events = run(o, "mach das Licht im Wohnzimmer an")

        assertEquals(1, tool.calls.size)
        assertEquals(
            true,
            done(events).toolCallRan,
            "das Feld misst den AUFRUF — VERIFIED/FAILED ist eine spätere Scheibe",
        )
    }
}
