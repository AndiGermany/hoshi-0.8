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
import de.hoshi.core.tools.AgenticToolRegistry
import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Sicherheits-Beweisführung** für den agentischen Brain-Pfad **unter PATH B**
 * ([TurnOrchestrator] mit gesetzter [AgenticToolRegistry]). Der Brain wird via
 * `tool_grammar=true` STRUKTURELL auf ein einzelnes JSON-Objekt `{tool,args}`
 * gezwungen ([de.hoshi.core.tools.ToolGrammarParser]). Der Tool-Pfad steuert echte
 * Geräte, der Brain ist UNTRUSTED — diese Tests verankern die Invarianten:
 *
 *  1. Der Kernel gatet ALLES (Deny ⇒ Executor NIE gerufen).
 *  2. Die rohe JSON wird NIE gestreamt (nur warme Quittung/Absage; Malformed ⇒ kein Leak).
 *  3. Max. 1 Brain-Call/Turn (der Brain-Fake zählt), mit `toolGrammar=true`.
 *  4. Feature default-OFF (agenticTools=null ⇒ unveränderter brainTurn-Pfad, KEIN tool_grammar).
 *
 * Die drei Result-Zweige des Parsers werden je geprüft: **Call** (→ resolve→gate→execute),
 * **None** (`{"tool":"none"}` → ehrliche Absage, KEINE Tat), **Malformed** (defekte JSON →
 * warmer Fallback, KEIN Roh-Leak).
 */
class TurnOrchestratorAgenticTest {

    // ── Brain-Fake: emittiert [raw] in Chunks (zwingt die collectList-Sammlung),
    //    zählt die Calls UND merkt sich tools-Größe, systemPrompt + tool_grammar-Flag.
    private class ToolEmittingBrain(private val raw: String) : BrainPort {
        val callCount = AtomicInteger(0)
        var lastToolsSize: Int = -1
        var lastSystemPrompt: String = ""
        var lastToolGrammar: Boolean = false
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
            lastToolsSize = tools.size
            lastSystemPrompt = systemPrompt
            lastToolGrammar = toolGrammar
            // In mehrere Deltas zerlegt → beweist, dass erst gesammelt, dann geparst wird.
            val mid = raw.length / 2
            return Flux.just(LlmDelta(raw.substring(0, mid)), LlmDelta(raw.substring(mid)))
        }
    }

    // ── Executor-Fake: fängt die Aufrufe ab (für Exactly-Once + richtiger ToolCall). ──
    private class CapturingTool(private val phrase: String) : ToolPort {
        val calls = ArrayList<ToolCall>()
        override fun execute(call: ToolCall): ToolResult {
            calls.add(call)
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
        capability: CapabilityPort,
        tools: ToolPort = ToolPort.HONEST_PLACEHOLDER,
        agentic: AgenticToolRegistry? = AgenticToolRegistry,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            intent = ToolIntentClassifier.DISABLED, // Fast-Path AUS ⇒ der agentische Pass-Pfad wird betreten
            capability = capability,
            tools = tools,
            agenticTools = agentic,
        )
    }

    private fun run(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // PATH-B-Wire-Format: ein EINZELNES JSON-Objekt {tool,args} (tool_grammar=true).
    private val LIGHT_ON = """{"tool":"light_set","args":{"area":"wohnzimmer","state":"on"}}"""

    // ── (1) Call+Grant ⇒ Tat ausgeführt, Quittung im Stream, KEINE rohe JSON, 1 Brain-Call (tool_grammar) ──
    @Test
    fun `Result_Call mit Grant fuehrt aus und quittiert warm ohne rohe JSON`() {
        val brain = ToolEmittingBrain(LIGHT_ON)
        val tool = CapturingTool("Wohnzimmer ist an.")
        val o = orchestrator(
            brain = brain,
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = tool,
        )
        val events = run(o, "es ist dunkel hier im wohnzimmer") // indirekte Phrase

        // Inv. 3: genau EIN Brain-Call (der mit den Tool-Schemas) UND tool_grammar=true.
        assertEquals(1, brain.callCount.get(), "agentischer Pfad: genau 1 Brain-Call/Turn")
        assertTrue(brain.lastToolsSize > 0, "die Tool-Schemas wurden an den Brain durchgereicht")
        assertTrue(brain.lastToolGrammar, "PATH B: tool_grammar=true wurde an den Brain durchgereicht")

        // Executor GENAU einmal, mit dem RICHTIGEN (resolveten) ToolCall.
        assertEquals(1, tool.calls.size, "Grant muss den Executor genau einmal rufen")
        val call = tool.calls.single()
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"], "Raum auf echte area_id aufgelöst")

        // Warme Quittung im Stream.
        assertTrue(text(events).contains("Wohnzimmer ist an."), "Quittung erwartet, war: ${text(events)}")
        // Inv. 2: NIE rohe JSON.
        assertFalse(text(events).contains("\"tool\""), "rohe Tool-JSON darf NIE gestreamt werden")
        assertFalse(text(events).contains("light_set"), "rohe Tool-Syntax darf NIE gestreamt werden")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (1, Inv. 1) Kernel DENY ⇒ Executor NIE gerufen, warme Absage, keine rohe JSON (Sicherheits-Kern) ──
    @Test
    fun `Result_Call mit Kernel-Deny fuehrt NICHTS aus und sagt warm ab`() {
        val brain = ToolEmittingBrain(LIGHT_ON)
        val o = orchestrator(
            brain = brain,
            capability = CapabilityPort { GateDecision.Deny("nope", "Das mache ich gerade nicht.") },
            tools = ToolPort { _ -> error("Executor darf bei Deny NIE laufen") },
        )
        val events = run(o, "es ist dunkel hier im wohnzimmer")

        assertEquals(1, brain.callCount.get(), "genau 1 Brain-Call/Turn")
        assertTrue(text(events).contains("Das mache ich gerade nicht."), "Deny-Phrase erwartet, war: ${text(events)}")
        assertFalse(text(events).contains("\"tool\""), "keine rohe Tool-JSON")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (1, resolve→null) Unbekanntes/unerlaubtes Tool ⇒ keine Ausführung, warme Absage ──
    @Test
    fun `unbekanntes Tool wird nie ausgefuehrt und sagt warm ab`() {
        // Strukturell valide JSON, aber kein Registry-Tool: resolve→null VOR jedem Gate/Executor.
        val raw = """{"tool":"unlock_door","args":{"area":"flur"}}"""
        val brain = ToolEmittingBrain(raw)
        val o = orchestrator(
            brain = brain,
            // Selbst ein „alles erlauben"-Gate darf nichts auslösen — resolve liefert ja null.
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> error("unbekanntes Tool darf NIE ausgeführt werden") },
        )
        val events = run(o, "schließ die haustür auf")

        assertEquals(1, brain.callCount.get(), "genau 1 Brain-Call/Turn")
        assertEquals(
            TurnOrchestrator.AGENTIC_REFUSAL_DE,
            text(events),
            "unbekanntes Tool → warme agentische Absage",
        )
        assertFalse(text(events).contains("unlock_door"), "keine rohe Tool-Syntax")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (2, Malformed) Defekte/abgeschnittene JSON ⇒ warmer Fallback, NIE Roh-Leak (Inv. 2 hart) ──
    @Test
    fun `Result_Malformed sagt warm ab und leakt nie die rohe JSON`() {
        // Abgeschnittene JSON: startet mit { aber readTree wirft → Malformed.
        val raw = """{"tool":"light_set","args":{"area":"wohnzimmer"""
        val brain = ToolEmittingBrain(raw)
        val o = orchestrator(
            brain = brain,
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> error("defekte JSON darf NIE ausgeführt werden") },
        )
        val events = run(o, "licht")

        assertEquals(1, brain.callCount.get(), "genau 1 Brain-Call/Turn")
        assertEquals(TurnOrchestrator.AGENTIC_REFUSAL_DE, text(events), "Malformed → warme Absage")
        // Inv. 2: NIE Roh-Leak — weder JSON-Klammer noch Tool-Name dürfen rausgehen.
        assertFalse(text(events).contains("{"), "ein defekter JSON-Block darf NIE roh rausgehen")
        assertFalse(text(events).contains("light_set"), "keine rohe Tool-Syntax")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (None) {"tool":"none"} ⇒ ehrliche Absage, KEINE Tat, kein Fake-Confirm ──
    @Test
    fun `Result_None lehnt ehrlich ab und fuehrt NICHTS aus`() {
        val brain = ToolEmittingBrain("""{"tool":"none","args":{}}""")
        val o = orchestrator(
            brain = brain,
            // Selbst ein „alles erlauben"-Gate darf nichts auslösen — None ⇒ kein resolve/gate/execute.
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> error("bei None darf NIE ein Executor laufen") },
        )
        val events = run(o, "wie ist das wetter morgen?")

        assertEquals(1, brain.callCount.get(), "genau 1 Brain-Call/Turn")
        assertEquals(
            TurnOrchestrator.AGENTIC_NONE_DE,
            text(events),
            "None → ehrliche „kann ich (noch) nicht\"-Absage, kein Fake-Confirm",
        )
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (K1, HONESTY) Aktiver agentischer Pfad ⇒ Tool-Mode-Direktive + tool_grammar im Request ──
    @Test
    fun `agentischer Pfad haengt die Tool-Mode-Direktive an und setzt tool_grammar`() {
        // None genügt — wir prüfen NUR den durchgereichten systemPrompt + die Flags.
        val brain = ToolEmittingBrain("""{"tool":"none","args":{}}""")
        val o = orchestrator(
            brain = brain,
            capability = CapabilityPort.DENY_ALL,
            tools = ToolPort { _ -> error("kein Tool in diesem Test") },
        )
        run(o, "wie geht's dir?") // Default-Sprache DE

        assertTrue(brain.lastToolsSize > 0, "agentischer Pfad: Tool-Schemas werden mitgeschickt")
        assertTrue(brain.lastToolGrammar, "agentischer Pfad: tool_grammar=true wird mitgeschickt")
        assertTrue(
            brain.lastSystemPrompt.contains(TurnOrchestrator.TOOL_MODE_DIRECTIVE_DE),
            "die DE-Tool-Mode-Direktive MUSS im an den Brain übergebenen systemPrompt stehen, war: ${brain.lastSystemPrompt}",
        )
    }

    // ── (4) Feature OFF (agenticTools=null) ⇒ exakt der bisherige brainTurn-Pfad, KEIN tool_grammar ──
    @Test
    fun `agenticTools null nutzt den unveraenderten brainTurn-Pfad ohne tool_grammar`() {
        val brain = ToolEmittingBrain("Hallo, schön dass du da bist!")
        val o = orchestrator(
            brain = brain,
            capability = CapabilityPort.DENY_ALL,
            agentic = null, // Feature AUS
        )
        val events = run(o, "sag hallo")

        assertEquals(1, brain.callCount.get(), "brainTurn: genau 1 Brain-Call")
        // Feature aus ⇒ KEINE Tool-Schemas, KEIN tool_grammar (heutiges Verhalten, byte-neutral).
        assertEquals(0, brain.lastToolsSize, "ohne Feature werden keine Tool-Schemas geschickt")
        assertFalse(brain.lastToolGrammar, "ohne Feature wird tool_grammar NICHT gesetzt (byte-neutral)")
        assertEquals("Hallo, schön dass du da bist!", text(events), "der Brain-Text fließt unverändert durch")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }
}
