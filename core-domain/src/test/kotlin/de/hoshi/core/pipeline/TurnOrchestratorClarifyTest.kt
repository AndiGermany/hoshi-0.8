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
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.core.tools.AreaClarifyIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die Verdrahtung des [AreaClarifyIntent]-Fastpaths in den [TurnOrchestrator]
 * (Live-Befund 2026-07-15): ein `domain == AreaClarifyIntent.DOMAIN`-[de.hoshi.core.tools.ToolCall]
 * geht über [TurnOrchestrator]s `clarifyTurn` — strukturell brain-frei (Invariante 1b,
 * exakt wie Timer/Calc/List) UND HA-schreib-gate-frei (eine Rückfrage ist keine Tat).
 *
 * Fälle:
 *  - Domain `area_clarify` ⇒ die fertige Phrase aus `data[PHRASE]` fließt unverändert
 *    in den Chat-Stream, der Brain wird NIE gerufen, das Schreib-Gate ([CapabilityPort])
 *    wird NIE gerufen (würde hier werfen), der Executor ([ToolPort]) wird NIE gerufen.
 *  - Leere Phrase (theoretischer Rand) ⇒ never-silent (warmer Fallback statt Stille).
 *  - Volle End-zu-End-Kette über den ECHTEN [DeterministicToolIntentClassifier]:
 *    „schalte mal was an" (kein Ziel auflösbar) ⇒ Rückfrage-Turn, 0 Brain-Calls.
 */
class TurnOrchestratorClarifyTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im Clarify-Turn NICHT gerufen werden")
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

    private fun orchestrator(brain: BrainPort, intent: ToolIntentClassifier): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            intent = intent,
            // Deny-all + werfender Executor: der Clarify-Pfad darf BEIDE nie anfassen
            // (eine Rückfrage ist keine Tat, s. Klassen-KDoc).
            capability = CapabilityPort { error("Schreib-Gate darf im Clarify-Turn NICHT laufen") },
            tools = ToolPort { error("Executor darf im Clarify-Turn NICHT laufen") },
        )
    }

    private fun run(o: TurnOrchestrator, text: String, language: Language = Language.DEFAULT): List<ChatEvent> =
        o.handle(ChatRequest(text = text, language = language)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── Fake-Classifier: direkte Beweisführung des Dispatches ────────────────
    @Test
    fun `area_clarify ToolCall streamt die Phrase brain-frei und gate-frei`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier { _, _ ->
                de.hoshi.core.tools.ToolCall(
                    domain = AreaClarifyIntent.DOMAIN,
                    service = AreaClarifyIntent.ASK,
                    entityId = null,
                    data = mapOf(AreaClarifyIntent.PHRASE to "Welchen Raum meinst du — Wohnzimmer, Schlafzimmer…?"),
                )
            },
        )
        val events = run(o, "schalte mal was an")

        assertEquals(0, brain.callCount.get(), "Clarify-Turn darf den Brain NIE rufen")
        assertTrue(
            text(events).contains("Welchen Raum meinst du"),
            "Rückfrage-Phrase erwartet, war: ${text(events)}",
        )
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    @Test
    fun `leere Phrase faellt never-silent auf den warmen Fallback zurueck`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = ToolIntentClassifier { _, _ ->
                de.hoshi.core.tools.ToolCall(
                    domain = AreaClarifyIntent.DOMAIN,
                    service = AreaClarifyIntent.ASK,
                    entityId = null,
                    data = emptyMap(),
                )
            },
        )
        val events = run(o, "schalte mal was an")

        assertEquals(0, brain.callCount.get())
        assertTrue(text(events).isNotBlank(), "never-silent: nie eine leere Antwort")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── End-zu-End über den ECHTEN Classifier (kein Fake) ─────────────────────
    @Test
    fun `Live-Befund Ende-zu-Ende - schalte mal was an loest brain-frei eine Rueckfrage aus`() {
        val brain = ThrowingBrain()
        val skills = SkillStatePort.ofStatic(smartHome = true, scenes = false, timer = false, calculator = false)
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(skills = skills),
        )
        val events = run(o, "schalte mal was an")

        assertEquals(0, brain.callCount.get(), "Ende-zu-Ende: der Clarify-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("Raum"), "DE-Rückfrage erwartet, war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `Live-Befund Ende-zu-Ende EN - turn something on loest eine englische Rueckfrage aus`() {
        val brain = ThrowingBrain()
        val skills = SkillStatePort.ofStatic(smartHome = true, scenes = false, timer = false, calculator = false)
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(skills = skills),
        )
        val events = run(o, "turn something on", language = Language.EN)

        assertEquals(0, brain.callCount.get(), "Ende-zu-Ende: der Clarify-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("room"), "EN-Rückfrage erwartet, war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done)
    }
}
