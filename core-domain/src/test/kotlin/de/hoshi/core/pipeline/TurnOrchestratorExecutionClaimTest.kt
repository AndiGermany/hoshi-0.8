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
import de.hoshi.core.port.CapabilityPort
import de.hoshi.core.port.ToolPort
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

/**
 * **Golden regression of the four live turns of 2026-08-11**
 * (`vault/knowledge/BEFUND-brain-behauptet-vollzug-2026-08-11.md`):
 *
 *  | 21:07:03 | "Jetzt drübe das Licht im Flur ein." | no tool | "Flurlicht an."          | must be REPLACED  |
 *  | 21:07:22 | "das Licht im Flur ein."            | no tool | "Mach ich. Flurlicht ist an." | must be REPLACED |
 *  | 21:07:44 | "Schalte das Licht im Flur ein."    | turn_on | "Licht im flur ist an."  | must stay verbatim |
 *  | 21:07:54 | "Schalte das Licht im Flur aus."    | turn_off| "Licht im flur ist aus." | must stay verbatim |
 *
 * The two honest turns carry the exact same wording as the two invented ones —
 * only the tool call separates them. That is the point of the latch.
 */
class TurnOrchestratorExecutionClaimTest {

    /** Answers exactly the two brain turns; every other call is a contract breach. */
    private class ScriptedBrain(private val answers: Map<String, String>) : BrainPort {
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
            val answer = answers[prompt] ?: error("Brain darf für '$prompt' NICHT gerufen werden")
            // Split into two deltas: the claim must be caught across delta borders.
            val cut = answer.length / 2
            return Flux.just(LlmDelta(answer.substring(0, cut)), LlmDelta(answer.substring(cut)))
        }
    }

    private fun passHonesty() = HonestyGate(
        weakDomain = WeakDomainSignal { false },
        onlineRequest = OnlineRequestSignal { false },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { false },
    )

    private fun routing(category: RouteCategory) = RoutingPolicy(
        keywordRouter = KeywordRouter { RouteDecision(category, RouteProvider.LOCAL, "fake") },
        llmRefiner = { _, fb -> Mono.just(fb) },
        embeddingRefiner = { _, fb -> Mono.just(fb) },
        softRoutingEnabled = false,
        softRoutingMode = "embedding",
    )

    /** Only an explicit "schalte …" becomes a tool call — exactly like the live router. */
    private val liveLikeClassifier = ToolIntentClassifier { text, _ ->
        when {
            text.startsWith("Schalte", ignoreCase = true) && text.contains("ein") ->
                ToolCall("light", "turn_on", data = mapOf("area_id" to "flur"))
            text.startsWith("Schalte", ignoreCase = true) && text.contains("aus") ->
                ToolCall("light", "turn_off", data = mapOf("area_id" to "flur"))
            else -> null
        }
    }

    private fun orchestrator(category: RouteCategory, answers: Map<String, String>): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(category),
            honesty = passHonesty(),
            promptAssembler = TurnPromptAssembler(
                persona = persona,
                entityMemory = { _, _ -> null },
                grounding = GroundingPort.EMPTY,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = ScriptedBrain(answers),
            intent = liveLikeClassifier,
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = ToolPort { call ->
                ToolResult.Ok(if (call.service == "turn_on") "Licht im flur ist an." else "Licht im flur ist aus.")
            },
        )
    }

    private fun events(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun answerOf(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private fun doneOf(events: List<ChatEvent>): ChatEvent.Done =
        events.filterIsInstance<ChatEvent.Done>().last()

    private val askBack = ExecutionClaimGate().askBack(Language.DE)

    // ── (1) + (2) the two invented turns — replaced, and visible in the diary ──

    @Test
    fun `21-07-03 FACT_SHORT ohne ToolCall - Flurlicht an wird zur ehrlichen Rueckfrage`() {
        val text = "Jetzt drübe das Licht im Flur ein."
        val o = orchestrator(RouteCategory.FACT_SHORT, mapOf(text to "Flurlicht an."))
        val ev = events(o, text)
        assertEquals(askBack, answerOf(ev))
        assertEquals(true, doneOf(ev).claimGateFired, "der Riegel muss im Diary sichtbar sein")
    }

    @Test
    fun `21-07-22 FACT_SHORT ohne ToolCall - Mach ich Flurlicht ist an wird zur ehrlichen Rueckfrage`() {
        val text = "das Licht im Flur ein."
        val o = orchestrator(RouteCategory.FACT_SHORT, mapOf(text to "Mach ich. Flurlicht ist an."))
        val ev = events(o, text)
        assertEquals(askBack, answerOf(ev))
        assertEquals(true, doneOf(ev).claimGateFired)
    }

    // ── (3) + (4) the two REAL smart-home turns — byte-identical, latch silent ──

    @Test
    fun `21-07-44 SMART_HOME mit turn_on - die wahre Quittung bleibt unveraendert`() {
        val o = orchestrator(RouteCategory.SMART_HOME, emptyMap())
        val ev = events(o, "Schalte das Licht im Flur ein.")
        assertEquals("Licht im flur ist an.", answerOf(ev))
        assertNull(doneOf(ev).claimGateFired, "ein echter Tool-Turn feuert den Riegel nie")
    }

    @Test
    fun `21-07-54 SMART_HOME mit turn_off - die wahre Quittung bleibt unveraendert`() {
        val o = orchestrator(RouteCategory.SMART_HOME, emptyMap())
        val ev = events(o, "Schalte das Licht im Flur aus.")
        assertEquals("Licht im flur ist aus.", answerOf(ev))
        assertNull(doneOf(ev).claimGateFired)
    }

    // ── (5) no false positives on ordinary brain turns ──────────────────────

    @Test
    fun `Brain-Turn ohne Geraetewort - Antwort und Done bleiben unangetastet`() {
        val text = "Wie geht es dir?"
        val o = orchestrator(RouteCategory.SMALLTALK, mapOf(text to "Mir geht's gut, danke!"))
        val ev = events(o, text)
        assertEquals("Mir geht's gut, danke!", answerOf(ev))
        assertNull(doneOf(ev).claimGateFired)
    }

    @Test
    fun `Zustandsfrage ohne ToolCall - die Zustands-Auskunft ueberlebt`() {
        val text = "Ist das Licht im Flur an?"
        val o = orchestrator(RouteCategory.FACT_SHORT, mapOf(text to "Das Licht im Flur ist an."))
        val ev = events(o, text)
        assertEquals("Das Licht im Flur ist an.", answerOf(ev))
        assertNull(doneOf(ev).claimGateFired, "eine Zustandsfrage entwaffnet den Riegel")
    }

    @Test
    fun `ehrliche Absage bei Geraetewort - der Riegel greift nicht`() {
        val text = "das Licht im Flur ein."
        val o = orchestrator(RouteCategory.FACT_SHORT, mapOf(text to "Ich habe das Licht nicht geschaltet."))
        val ev = events(o, text)
        assertEquals("Ich habe das Licht nicht geschaltet.", answerOf(ev))
        assertNull(doneOf(ev).claimGateFired)
        assertTrue(ev.filterIsInstance<ChatEvent.TextDelta>().size >= 2, "Delta-Grenzen bleiben erhalten")
    }
}
