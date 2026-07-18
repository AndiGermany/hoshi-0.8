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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Der KETTEN-Test der Grounding-Ehrlichkeit (RCA §4, 2026-07-02)** — der bisher
 * fehlende Beweis, dass die Wand IM VERBUND wirkt: ein [TurnOrchestrator] mit echt
 * verdrahtetem [FactCoverageGate] (NICHT [FactCoverageGate.DISABLED]!) + einem
 * Fake-[GroundingPort], der einen **non-blank aber off-target** Block liefert
 * (tangentialer BM25-Treffer), für eine nicht-definitorische Zahl-Frage
 * („Wie hoch ist der Eiffelturm?").
 *
 * Live-Beweis des RCA: mit dem laxen `isNotBlank`-Check schwafelt das Brain die
 * Wissensfrage faktenfrei durch („Der Turm ist ziemlich hoch."). Hier festgenagelt:
 *
 *  - **strict ⇒ Deflect**: der off-target Block zählt NICHT als Deckung — der Brain
 *    wird NIE gerufen, die ehrliche Deflection-Phrase kommt (model=policy).
 *  - **lax ⇒ Proceed**: heutiges Verhalten als Regression festgehalten — 1 Brain-Call,
 *    der Schwafel-Text fließt durch (model=brain, `Start.grounded=true` — lax-Sicht).
 *
 * Konstruktion nach dem OfflineReplayHarnessTest-Muster (echte Policies, kein Spring),
 * aber mit fake [KeywordRouter] (deterministisch FACT_SHORT/LOCAL) und dem
 * [factCoverage]-Gate EXPLIZIT reingegeben. Prüft zugleich die Scheibe-1-Ehrlichkeit:
 * [ChatEvent.Start.grounded] trägt den echten Coverage-Wert an den Rand.
 */
class TurnOrchestratorFactCoverageChainTest {

    /** Nicht-definitorische Zahl-Frage — der Live-Beweis-Fall des RCA. */
    private val question = "Wie hoch ist der Eiffelturm?"

    /** Non-blank, aber OFF-TARGET (kein Content-Token der Frage): der tangentiale BM25-Treffer. */
    private val offTargetBlock =
        "\n\n---\nHINTERGRUND: • Paris: Metropole an der Seine, bekannt für Museen und Türme …\n"

    /** ON-TARGET-Kontrast: der Block trägt die Query-Substanz („Eiffelturm … 330"). */
    private val onTargetBlock =
        "\n\n---\nHINTERGRUND: • Eiffelturm: Eisenfachwerkturm in Paris, 330 Meter …\n"

    /** Der Schwafel-Satz, den das lax gedeckte Brain live produzierte. */
    private val waffle = "Der Turm ist ziemlich hoch."

    // ── Zählender Fake-Brain (liefert den Live-Schwafel-Satz) ────────────────────
    private class FakeBrainPort(private val line: String) : BrainPort {
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
            return Flux.just(LlmDelta(line))
        }
    }

    // ── Echte Pipeline-Nähte (Spring-frei), Gate EXPLIZIT reingegeben ────────────
    private fun orchestrator(
        brain: FakeBrainPort,
        factCoverage: FactCoverageGate,
        groundBlock: String,
    ): TurnOrchestrator {
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
            brain = brain,
            factCoverage = factCoverage,
        )
    }

    private fun run(o: TurnOrchestrator): List<ChatEvent> =
        o.handle(ChatRequest(text = question)).collectList().block(Duration.ofSeconds(5))!!

    private fun start(events: List<ChatEvent>): ChatEvent.Start =
        events.filterIsInstance<ChatEvent.Start>().first()

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── strict ⇒ Deflect: der off-target Block zählt nicht, der Brain schweigt ────
    @Test
    fun `strict deflektet den tangentialen Treffer - kein Brain-Call, ehrliche Phrase`() {
        val brain = FakeBrainPort(waffle)
        val events = run(orchestrator(brain, FactCoverageGate(enabled = true, strict = true), offTargetBlock))

        assertEquals(0, brain.callCount.get(), "strict: off-target Grounding darf den Brain NIE erreichen")
        assertEquals(
            FactCoverageGate.DEFLECT_DE,
            joinedText(events),
            "statt Schwafeln kommt die ehrliche Deflection",
        )
        assertEquals("policy", start(events).model, "Deflect ist eine Policy-Direktantwort")
        assertFalse(start(events).grounded, "strict-Sicht: dieser Turn war NICHT gedeckt")
        assertTrue(events.last() is ChatEvent.Done, "never-silent: Turn endet mit Done")
    }

    // ── lax ⇒ Proceed: heutiges Verhalten als Regression festgehalten ─────────────
    @Test
    fun `lax laesst den tangentialen Treffer durch - 1 Brain-Call, Schwafel-Text (heutiges Verhalten)`() {
        val brain = FakeBrainPort(waffle)
        val events = run(orchestrator(brain, FactCoverageGate(enabled = true, strict = false), offTargetBlock))

        assertEquals(1, brain.callCount.get(), "lax (heute): non-blank reicht ⇒ Brain wird gerufen")
        assertEquals(waffle, joinedText(events), "der faktenfreie Schwafel fließt durch — der RCA-Befund")
        assertEquals("brain", start(events).model)
        assertTrue(start(events).grounded, "lax-Sicht: non-blank Block gilt als gedeckt ⇒ Start.grounded=true")
    }

    // ── Kontrast: on-target Block proceeded auch strict (die Wand blockt nur off-target) ──
    @Test
    fun `strict laesst den on-target Block normal zum Brain durch`() {
        val brain = FakeBrainPort("Der Eiffelturm ist 330 Meter hoch.")
        val events = run(orchestrator(brain, FactCoverageGate(enabled = true, strict = true), onTargetBlock))

        assertEquals(1, brain.callCount.get(), "gedeckter Fact läuft auch strict normal zum Brain")
        assertEquals("brain", start(events).model)
        assertTrue(start(events).grounded, "on-target Block ⇒ Start.grounded=true (ehrliche Instrumentierung)")
    }

    // ── Scheibe-1-Ehrlichkeit: leeres Grounding ⇒ Start.grounded=false (Gate DISABLED) ──
    @Test
    fun `leeres Grounding traegt grounded=false im Start-Event auch bei DISABLED-Gate`() {
        val brain = FakeBrainPort("Irgendwas.")
        val events = run(orchestrator(brain, FactCoverageGate.DISABLED, groundBlock = ""))

        assertEquals(1, brain.callCount.get(), "DISABLED-Gate: byte-neutral, Brain läuft wie heute")
        assertFalse(start(events).grounded, "leerer Block ⇒ grounded=false — ehrlich, nicht hardcoded")
    }
}
