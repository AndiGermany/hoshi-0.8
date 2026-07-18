package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.CapabilityPort
import de.hoshi.core.port.ToolPort
import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den Tool-Pfad des [TurnOrchestrator]:
 *  (1) Intent ⇒ Grant ⇒ Executor.Ok ⇒ Quittung im Stream, Brain NIE gerufen.
 *  (2) Intent ⇒ Deny ⇒ warme Absage im Stream, Brain NIE gerufen.
 *  (3) Intent=null ⇒ der bestehende Brain-Pfad wird betreten (Brain liefert).
 */
class TurnOrchestratorToolTest {

    // ── Fake-Brain, der bei JEDEM Aufruf zählt UND wirft (darf im Tool-Turn nie laufen) ──
    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
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
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im Tool-Turn NICHT gerufen werden")
        }
    }

    // ── Fake-Brain für den Intent=null-Fall: zählt + liefert einen Delta ──
    private class DeltaBrain(private val delta: String) : BrainPort {
        val callCount = AtomicInteger(0)
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
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            return Flux.just(LlmDelta(delta))
        }
    }

    // ── Fake-Executor, der seine Aufrufe ZÄHLT (für die Exactly-Once-Gegenprobe) ──
    private class CountingTool(private val phrase: String) : ToolPort {
        val execCount = AtomicInteger(0)
        override fun execute(call: ToolCall): ToolResult {
            execCount.incrementAndGet()
            return ToolResult.Ok(phrase)
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
        keywordRouter = KeywordRouter { RouteDecision(RouteCategory.SMART_HOME, RouteProvider.LOCAL, "fake") },
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
        capability: CapabilityPort,
        tools: ToolPort = ToolPort.HONEST_PLACEHOLDER,
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
            capability = capability,
            tools = tools,
        )
    }

    private fun run(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── (1) Grant ⇒ Ok-Quittung, Brain NIE ───────────────────────────────────
    @Test
    fun `Grant fuehrt aus und streamt die Quittung ohne Brain`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier { _, _ -> ToolCall("light", "turn_on", "light.kuche") },
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> ToolResult.Ok("Licht an.") },
        )
        val events = run(o, "Licht in der Küche an")

        assertEquals(0, brain.callCount.get(), "Tool-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("Licht an."), "Quittung erwartet, war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (1b) Tat-Gate-Coverage: Grant ruft den Executor GENAU EINMAL ──────────
    // Strukturbeweis der Naht `capability.check → tools.execute`: bei Grant läuft
    // der Executor exakt 1× (kein Doppel-Effekt durch späteres Refactoring), und
    // der Brain bleibt unberührt (Tool-Turn = 0 Brain-Calls). Die Gegenrichtung
    // „bei Deny NIE" deckt Test (2) ab (ToolPort-Fake wirft, wenn gerufen).
    @Test
    fun `Grant ruft tools execute GENAU einmal`() {
        val brain = ThrowingBrain()
        val tool = CountingTool("Licht an.")
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier { _, _ -> ToolCall("light", "turn_on", "light.kuche") },
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = tool,
        )
        val events = run(o, "Licht in der Küche an")

        assertEquals(1, tool.execCount.get(), "Grant muss den Executor GENAU einmal rufen")
        assertEquals(0, brain.callCount.get(), "Tool-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("Licht an."), "Quittung erwartet, war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (1c) READ ⇒ Executor direkt, Schreib-Gate UND Brain NIE ───────────────
    // Ein Read-ToolCall (read=true) ist gate-frei: der Executor liefert den Wert, das
    // Schreib-Gate (capability) wird NIE gerufen (würde hier werfen) und der Brain auch nicht.
    @Test
    fun `Read fuehrt aus ohne Schreib-Gate und ohne Brain`() {
        val brain = ThrowingBrain()
        val tool = CountingTool("Im Wohnzimmer sind es gerade 21 Grad.")
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier { _, _ ->
                ToolCall("sensor", "read_temperature", data = mapOf("area_id" to "wohnzimmer"), read = true)
            },
            // Das Schreib-Gate DARF beim Read nicht angefasst werden → wirft, falls doch.
            capability = CapabilityPort { error("Schreib-Gate darf beim Read NICHT laufen") },
            tools = tool,
        )
        val events = run(o, "Wie warm ist es im Wohnzimmer?")

        assertEquals(1, tool.execCount.get(), "Read muss den Executor GENAU einmal rufen")
        assertEquals(0, brain.callCount.get(), "Read-Turn darf den Brain NIE rufen")
        assertTrue(
            text(events).contains("Im Wohnzimmer sind es gerade 21 Grad."),
            "Temperatur-Quittung erwartet, war: ${text(events)}",
        )
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (2) Deny ⇒ warme Absage, Brain NIE ───────────────────────────────────
    @Test
    fun `Deny streamt die warme Absage ohne Brain`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier { _, _ -> ToolCall("lock", "unlock", "lock.haustuer") },
            capability = CapabilityPort { GateDecision.Deny("nope", "Das darf ich nicht.") },
            tools = ToolPort { _ -> error("Executor darf bei Deny nicht laufen") },
        )
        val events = run(o, "schließ die Haustür auf")

        assertEquals(0, brain.callCount.get(), "Deny-Pfad darf den Brain NIE rufen")
        assertTrue(text(events).contains("Das darf ich nicht."), "Deny-Phrase erwartet, war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (P0 Event-Loop-Fix) tools.execute (HaToolPort) läuft OFF der Event-Loop ──
    // Strukturbeweis, dass der blockierende ToolPort-Call (live: synchroner HA-Call)
    // via `Mono.fromCallable{}.subscribeOn(boundedElastic)` ausgelagert ist — der
    // Executor wird auf einem boundedElastic-Worker gerufen, NICHT auf dem Reactor-
    // Netty-Event-Loop. Die Quittung fließt unverändert, der Brain bleibt unberührt.
    @Test
    fun `Grant fuehrt den Executor off der Event-Loop auf boundedElastic aus`() {
        val brain = ThrowingBrain()
        val execThread = AtomicReference<String?>(null)
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier { _, _ -> ToolCall("light", "turn_on", "light.kuche") },
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ ->
                execThread.set(Thread.currentThread().name)
                ToolResult.Ok("Licht an.")
            },
        )
        val events = run(o, "Licht in der Küche an")

        assertEquals(0, brain.callCount.get(), "Tool-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("Licht an."), "Quittung erwartet, war: ${text(events)}")
        val name = execThread.get()
        assertTrue(name != null, "tools.execute muss aufgerufen worden sein")
        assertTrue(
            name!!.contains("boundedElastic"),
            "tools.execute muss off der Event-Loop auf boundedElastic laufen, lief aber auf: $name",
        )
    }

    // ── (3) Intent=null ⇒ Brain-Pfad ─────────────────────────────────────────
    @Test
    fun `kein Intent betritt den Brain-Pfad`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier.DISABLED,
            capability = CapabilityPort.DENY_ALL,
        )
        val events = run(o, "Erzähl mir was.")

        assertEquals(1, brain.callCount.get(), "ohne Intent: genau 1 Brain-Call")
        assertTrue(text(events).contains("Brain-Antwort."), "Brain-Text erwartet, war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }
}
