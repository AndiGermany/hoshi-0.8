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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **Räume-Nutzungs-Naht — der TurnOrchestrator schreibt die bereits bekannte
 * `ToolCall.data["area_id"]` additiv ins [ChatEvent.Start.targetAreaId]**
 * (Konzept-Pfad 1a, kartiert in Commit f049965 /
 * `frontend/src/components/roomsSort.ts`-KDoc: „die echte Nutzungs-Naht … ist
 * präzise kartiert … und wird die nächste Scheibe"): der ERSTE Baustein der
 * echten Raum-Nutzungs-Messung — bislang ging dieses Wissen an der
 * Turn-Grenze verloren (`LastAreaPort` merkt nur den ZULETZT geschalteten
 * Wert, kein Verlauf, s. `roomsSort.ts`-KDoc).
 *
 * Deckt (analog [TurnOrchestratorToolTest]): Grant (Write) mit Area, Deny
 * (Write) mit Area, Read mit Area — UND den Gegenbeweis: ein entity-
 * getargeteter Call OHNE `area_id` in `data` bleibt `null` (nie eine
 * erfundene Area).
 */
class TurnOrchestratorAreaTraceTest {

    // ── Fake-Brain, der bei JEDEM Aufruf wirft (darf im Tool-Turn nie laufen) ──
    private class ThrowingBrain : BrainPort {
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
        ): Flux<LlmDelta> = error("Brain darf im Tool-Turn NICHT gerufen werden")
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
        entityMemory = { _, _ -> null },
        grounding = GroundingPort.EMPTY,
        episodicMemory = null,
    )

    private fun orchestrator(
        intent: ToolIntentClassifier,
        capability: CapabilityPort,
        tools: ToolPort,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = ThrowingBrain(),
            intent = intent,
            capability = capability,
            tools = tools,
        )
    }

    private fun startOf(o: TurnOrchestrator, text: String): ChatEvent.Start =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Start>()
            .first()

    // ── (1) Grant-Write mit area_id ──────────────────────────────────────────
    @Test
    fun `Grant-Write mit area_id - targetAreaId reist ins Start`() {
        val o = orchestrator(
            // Echte HA-area_id: HA slugifiziert ü→u ⇒ „kuche" (NICHT „kueche").
            intent = ToolIntentClassifier { _, _ -> ToolCall("light", "turn_on", data = mapOf("area_id" to "kuche")) },
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> ToolResult.Ok("Licht an.") },
        )
        val start = startOf(o, "Licht in der Küche an")
        assertEquals("kuche", start.targetAreaId)
    }

    // ── (2) Deny-Write mit area_id — die Absage zaehlt genauso als angesteuerter Raum ──
    @Test
    fun `Deny-Write mit area_id - targetAreaId reist trotz Absage ins Start`() {
        val o = orchestrator(
            intent = ToolIntentClassifier { _, _ -> ToolCall("light", "turn_on", data = mapOf("area_id" to "buero")) },
            capability = CapabilityPort { GateDecision.Deny("nope", "Das darf ich nicht.") },
            tools = ToolPort { _ -> error("Executor darf bei Deny nicht laufen") },
        )
        val start = startOf(o, "Licht im Büro an")
        assertEquals("buero", start.targetAreaId)
    }

    // ── (3) Read mit area_id ──────────────────────────────────────────────────
    @Test
    fun `Read mit area_id - targetAreaId reist ins Start`() {
        val o = orchestrator(
            intent = ToolIntentClassifier { _, _ ->
                ToolCall("sensor", "read_temperature", data = mapOf("area_id" to "wohnzimmer"), read = true)
            },
            capability = CapabilityPort { error("Schreib-Gate darf beim Read NICHT laufen") },
            tools = ToolPort { _ -> ToolResult.Ok("21 Grad.") },
        )
        val start = startOf(o, "Wie warm ist es im Wohnzimmer?")
        assertEquals("wohnzimmer", start.targetAreaId)
    }

    // ── (4) Gegenbeweis: entity-getargeteter Call OHNE area_id ⇒ ehrlich null ──
    @Test
    fun `entity-getargeteter Call ohne area_id - targetAreaId bleibt ehrlich null`() {
        val o = orchestrator(
            intent = ToolIntentClassifier { _, _ -> ToolCall("light", "turn_on", "light.kuche") },
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> ToolResult.Ok("Licht an.") },
        )
        val start = startOf(o, "Küchenlicht an")
        assertNull(start.targetAreaId, "kein area_id im Call ⇒ nie eine erfundene Area")
    }

    // ── (5) Raumname-Naht (Andi 2026-08-22): der Name reist NEBEN dem Slug ────

    /**
     * Der Orchestrator löst den echten HA-Anzeigenamen auf und hängt ihn additiv
     * ans Start-Event — `kuche` ⇒ `Küche`. Der Slug bleibt unangetastet: er ist
     * die Matching-Wahrheit, der Name nur die lesbare Begleitung.
     */
    @Test
    fun `targetAreaName reist additiv neben dem Slug ins Start`() {
        val o = orchestrator(
            intent = ToolIntentClassifier { _, _ -> ToolCall("light", "turn_on", data = mapOf("area_id" to "kuche")) },
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> ToolResult.Ok("Licht an.") },
        )
        val start = startOf(o, "Licht in der Küche an")

        assertEquals("kuche", start.targetAreaId, "der Slug bleibt die Matching-Wahrheit")
        assertEquals("Küche", start.targetAreaName, "der echte HA-Anzeigename reist daneben")
    }

    /** Kein auffindbarer Name ⇒ ehrlich `null`, nie ein kapitalisierter Slug. */
    @Test
    fun `unbekannte Area - targetAreaName bleibt null statt verstuemmelt`() {
        val o = orchestrator(
            intent = ToolIntentClassifier { _, _ ->
                ToolCall("light", "turn_on", data = mapOf("area_id" to "dachboden"))
            },
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { _ -> ToolResult.Ok("Licht an.") },
        )
        val start = startOf(o, "Licht auf dem Dachboden an")

        assertEquals("dachboden", start.targetAreaId)
        assertNull(start.targetAreaName, "lieber kein Name als ein geratener, kapitalisierter Slug")
    }
}
