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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **Der KETTEN-Test des Spiel-Registers — der Vorfall „Kuh mit Hose" nachgestellt**
 * (Andi, 2026-07-25).
 *
 * Das echte Gespräch, Wort für Wort:
 * ```
 * Andi : „Stell dir vor, eine Kuh. Wie zieht sie ihre Hose an? Über die vorderen
 *         Pfoten oder über die hinteren Pfoten?"
 * Hoshi: „Ich glaube, die hinteren Pfoten sind dafür besser geeignet …"  [Wissen gedeckt]
 * Andi : „Die brauchst du schon um rum zu stehen, aber die hinteren ja auch …"
 * Hoshi: „Du meinst die Beine, oder? Die brauchen wir beide, um stabil zu stehen."
 * ```
 * Andis Urteil war „sie verliert immer den Context". Die Messung gegen Prod sagt: der
 * Verlauf KAM an und WURDE benutzt (`grounded=true`, `category=FACT_SHORT`). Es fehlte
 * das REGISTER, nicht das Gedächtnis — die Frage lief als Faktenfrage durch die
 * Grounding-Maschinerie, und „Du meinst die Beine, oder?" ist deren Faktenkorrektur.
 *
 * Aufbau wie im [TurnOrchestratorFactCoverageChainTest]: echte Policies, kein Spring,
 * ein Fake-[KeywordRouter], der — exakt wie der echte
 * [de.hoshi.web.routing.KeywordRouterImpl] es tut — JEDEN inhaltstragenden Satz als
 * FACT_SHORT ausgibt, plus ein Fake-[GroundingPort], der (wie die Wiki-Bridge live)
 * für „Kuh"/„Hose" einen non-blank Block liefert.
 *
 * Festgenagelt wird: **kein Grounding-Call, kein „Wissen gedeckt"-Chip
 * ([ChatEvent.Start.grounded]), ein Spiel-Hinweis im Prompt — und die Gegenprobe, dass
 * echte Wissensfragen unverändert geerdet bleiben.**
 */
class TurnOrchestratorPlayfulModeTest {

    // ── Der Vorfall, wörtlich ────────────────────────────────────────────────────
    private val deTurn1 =
        "Stell dir vor, eine Kuh. Wie zieht sie ihre Hose an? Über die vorderen Pfoten oder über die hinteren Pfoten?"
    private val deAnswer1 =
        "Ich glaube, die hinteren Pfoten sind dafür besser geeignet. Die vorderen sind doch eher zum Herumstehen da."
    private val deTurn2 =
        "Die brauchst du schon um rum zu stehen, aber die hinteren ja auch, sonst kann sie ja nicht stehen."

    // ── Das englische Gegenstück ─────────────────────────────────────────────────
    private val enTurn1 =
        "Imagine a cow. How does she put on her trousers — over the front hooves or the back hooves?"
    private val enAnswer1 = "The back hooves, I'd say. The front ones are more for leaning on the fence."
    private val enTurn2 = "She needs the front ones to stand around, but the back ones too, otherwise she'd tip over."

    /** Was die Wiki-Bridge live für „Kuh"/„Hose" lieferte: non-blank ⇒ „gedeckt" im laxen Check. */
    private val cowBlock =
        "\n\n---\nHINTERGRUND: • Hausrind: Das Hausrind ist ein Paarhufer und wird als Nutztier gehalten …\n"

    // ── Fakes ────────────────────────────────────────────────────────────────────

    private class FakeBrainPort(private val line: String) : BrainPort {
        val callCount = AtomicInteger(0)
        val lastSystemPrompt = AtomicReference("")
        val lastHistory = AtomicReference<List<ChatMessage>>(emptyList())
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
            lastSystemPrompt.set(systemPrompt)
            lastHistory.set(history)
            return Flux.just(LlmDelta(line))
        }
    }

    /** Zählt, ob (und mit welcher Kategorie) überhaupt gegroundet wurde. */
    private class CountingGroundingPort(private val block: String) : GroundingPort {
        val callCount = AtomicInteger(0)
        val lastCategory = AtomicReference<RouteCategory?>(null)
        override fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String> {
            lastCategory.set(category)
            // Kategorie-Gate wie in JEDEM echten Provider (Fts5/Weather/Nachgeschlagen):
            // Smalltalk/Smart-Home werden nie gegroundet.
            if (category != RouteCategory.FACT_SHORT &&
                category != RouteCategory.NEEDS_WEB &&
                category != RouteCategory.AMBIG
            ) {
                return Mono.just("")
            }
            callCount.incrementAndGet()
            return Mono.just(block)
        }
    }

    private fun orchestrator(
        brain: FakeBrainPort,
        grounding: CountingGroundingPort,
        playfulEnabled: Boolean,
        /**
         * Prod-Stand nachgebaut: Wand AN, `strict` AUS — genau die Konstellation, in der
         * Andis Messung `grounded=true` für die erfundene Kuh lieferte (ein non-blank
         * BM25-Treffer reicht dem laxen Check).
         */
        factCoverage: FactCoverageGate = FactCoverageGate(enabled = true, strict = false),
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                // Wie der echte KeywordRouterImpl: ein inhaltstragender Satz ⇒ FACT_SHORT.
                keywordRouter = { RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "fact") },
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
                entityMemory = { _, _ -> null },
                grounding = grounding,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            factCoverage = factCoverage,
            playfulMode = if (playfulEnabled) PlayfulModeDetector(enabled = true) else PlayfulModeDetector.DISABLED,
        )
    }

    private fun run(o: TurnOrchestrator, request: ChatRequest): List<ChatEvent> =
        o.handle(request).collectList().block(Duration.ofSeconds(5))!!

    private fun start(events: List<ChatEvent>): ChatEvent.Start =
        events.filterIsInstance<ChatEvent.Start>().first()

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── Turn 1 DE: das Gedankenspiel wird NICHT geerdet ──────────────────────────

    @Test
    fun `DE Turn 1 - die Kuh mit der Hose wird nicht gegroundet und traegt keinen Wissen-gedeckt-Chip`() {
        val brain = FakeBrainPort("Über die hinteren natürlich — vorne braucht sie ja die Hände zum Winken.")
        val grounding = CountingGroundingPort(cowBlock)
        val events = run(orchestrator(brain, grounding, playfulEnabled = true), ChatRequest(text = deTurn1))

        assertEquals(0, grounding.callCount.get(), "ein erfundenes Gedankenspiel darf NIE gegroundet werden")
        assertEquals(RouteCategory.SMALLTALK, grounding.lastCategory.get(), "die Route ist im Spiel SMALLTALK")
        assertFalse(
            start(events).grounded,
            "der „Wissen gedeckt\"-Chip über einer erfundenen Kuh war eine Falschaussage — er muss weg",
        )
        assertEquals(1, brain.callCount.get(), "der Turn läuft normal über den Brain (max. 1 Call)")
        assertEquals("brain", start(events).model, "kein Deflect: die Wand greift auf einer Spiel-Route nicht")
        assertTrue(
            brain.lastSystemPrompt.get().contains(PlayfulModeDetector.PLAY_HINT_DE),
            "das Prompt trägt den Spiel-Hinweis (mitspielen, Faden halten, nichts als Tatsache ausgeben)",
        )
        assertTrue(events.last() is ChatEvent.Done, "never-silent: Turn endet mit Done")
    }

    // ── Turn 2 DE: der Faden hält, OHNE eigenen Marker ───────────────────────────

    @Test
    fun `DE Turn 2 - der Folge-Turn haelt den Faden statt zu berichtigen`() {
        val brain = FakeBrainPort("Stimmt — dann zieht sie die Hose eben im Liegen an.")
        val grounding = CountingGroundingPort(cowBlock)
        val events = run(
            orchestrator(brain, grounding, playfulEnabled = true),
            ChatRequest(
                text = deTurn2,
                history = listOf(ChatMessage("user", deTurn1), ChatMessage("assistant", deAnswer1)),
            ),
        )

        assertEquals(0, grounding.callCount.get(), "der Folge-Turn bleibt im Spiel ⇒ kein Grounding")
        assertFalse(start(events).grounded, "kein „Wissen gedeckt\" auf dem Folge-Turn")
        assertTrue(
            brain.lastSystemPrompt.get().contains(PlayfulModeDetector.PLAY_HINT_DE),
            "der Spiel-Hinweis hält den Faden — er ist die Antwort auf „Du meinst die Beine, oder?\"",
        )
        assertEquals(
            2,
            brain.lastHistory.get().size,
            "der Verlauf reist unverändert mit (er war nie das Problem — das REGISTER war es)",
        )
    }

    // ── Das englische Gegenstück ─────────────────────────────────────────────────

    @Test
    fun `EN - imagine-a-cow laeuft in beiden Turns ungegroundet und mit englischem Spiel-Hinweis`() {
        val brain = FakeBrainPort("Back hooves first, obviously.")
        val grounding = CountingGroundingPort(cowBlock)
        val o = orchestrator(brain, grounding, playfulEnabled = true)

        val first = run(o, ChatRequest(text = enTurn1, language = Language.EN))
        assertEquals(0, grounding.callCount.get(), "an invented thought experiment is never grounded")
        assertFalse(start(first).grounded, "no „knowledge covered\" chip on an invented cow")
        assertTrue(brain.lastSystemPrompt.get().contains(PlayfulModeDetector.PLAY_HINT_EN))

        val second = run(
            o,
            ChatRequest(
                text = enTurn2,
                language = Language.EN,
                history = listOf(ChatMessage("user", enTurn1), ChatMessage("assistant", enAnswer1)),
            ),
        )
        assertEquals(0, grounding.callCount.get(), "the follow-up keeps the thread ⇒ still no grounding")
        assertFalse(start(second).grounded)
        assertTrue(brain.lastSystemPrompt.get().contains(PlayfulModeDetector.PLAY_HINT_EN))
    }

    // ── DIE GEGEN-TESTS: echte Wissensfragen bleiben unverändert geerdet ─────────

    @Test
    fun `Gegen-Test - eine echte Wissensfrage wird weiter gegroundet und traegt den Chip`() {
        val brain = FakeBrainPort("Der Eiffelturm ist 330 Meter hoch.")
        val grounding = CountingGroundingPort(
            "\n\n---\nHINTERGRUND: • Eiffelturm: Eisenfachwerkturm in Paris, 330 Meter …\n",
        )
        val events = run(
            orchestrator(brain, grounding, playfulEnabled = true),
            ChatRequest(text = "Wie hoch ist der Eiffelturm?"),
        )

        assertEquals(1, grounding.callCount.get(), "echte Wissensfrage ⇒ Grounding läuft wie bisher")
        assertEquals(RouteCategory.FACT_SHORT, grounding.lastCategory.get(), "die Route bleibt FACT_SHORT")
        assertTrue(start(events).grounded, "on-target Block ⇒ „Wissen gedeckt\" ist hier die WAHRHEIT")
        assertFalse(
            brain.lastSystemPrompt.get().contains("[SPIELMODUS]"),
            "kein Spiel-Hinweis auf einer echten Wissensfrage",
        )
    }

    @Test
    fun `Gegen-Test - eine Wetter-Frage bleibt eine Wetter-Frage`() {
        val brain = FakeBrainPort("Morgen wird es wechselhaft.")
        val grounding = CountingGroundingPort("\n\n---\nHINTERGRUND: • Wetter morgen: 14 °C, Regen …\n")
        val events = run(
            orchestrator(brain, grounding, playfulEnabled = true),
            ChatRequest(text = "Wie wird das Wetter morgen?"),
        )

        assertEquals(1, grounding.callCount.get(), "Wetter läuft über dieselbe Wissens-Route wie bisher")
        assertEquals(RouteCategory.FACT_SHORT, grounding.lastCategory.get())
        assertTrue(start(events).grounded)
    }

    @Test
    fun `Gegen-Test - nach dem Spiel kippt eine echte Frage zurueck in den Sachmodus`() {
        val brain = FakeBrainPort("Der Eiffelturm ist 330 Meter hoch.")
        val grounding = CountingGroundingPort(
            "\n\n---\nHINTERGRUND: • Eiffelturm: Eisenfachwerkturm in Paris, 330 Meter …\n",
        )
        val events = run(
            orchestrator(brain, grounding, playfulEnabled = true),
            ChatRequest(
                text = "Wie hoch ist der Eiffelturm?",
                history = listOf(ChatMessage("user", deTurn1), ChatMessage("assistant", deAnswer1)),
            ),
        )

        assertEquals(1, grounding.callCount.get(), "kein Token-Anker ⇒ das Spiel endet ⇒ die Frage wird geerdet")
        assertTrue(start(events).grounded, "der Chip sagt hier ehrlich „gedeckt\"")
    }

    @Test
    fun `Gegen-Test - die scharfe Wand deflektet weiter, aber niemals einen Spiel-Turn`() {
        val strict = FactCoverageGate(enabled = true, strict = true)

        // (a) Echte Wissensfrage, off-target Block ⇒ die Wand hält (ehrliche Deflection).
        val brainA = FakeBrainPort("Der Turm ist ziemlich hoch.")
        val offTarget = CountingGroundingPort("\n\n---\nHINTERGRUND: • Paris: Metropole an der Seine …\n")
        val a = run(
            orchestrator(brainA, offTarget, playfulEnabled = true, factCoverage = strict),
            ChatRequest(text = "Wie hoch ist der Eiffelturm?"),
        )
        assertEquals(0, brainA.callCount.get(), "die Wand bleibt für echte Fragen unverändert scharf")
        assertTrue(joinedText(a) in de.hoshi.core.pipeline.lang.LangDe.PACK.factCoverageDeflect)

        // (b) Spiel-Turn ⇒ NIE Deflect (die Wand bewacht Fakten, nicht Fantasie).
        val brainB = FakeBrainPort("Hinten zuerst, sonst verheddert sie sich.")
        val b = run(
            orchestrator(brainB, CountingGroundingPort(cowBlock), playfulEnabled = true, factCoverage = strict),
            ChatRequest(text = deTurn1),
        )
        assertEquals(1, brainB.callCount.get(), "ein Gedankenspiel wird nicht als „nicht gedeckt\" abgewürgt")
        assertEquals("brain", start(b).model)
        assertFalse(start(b).grounded)
    }

    // ── Byte-Neutralität: OFF verhält sich exakt wie vor der Scheibe ─────────────

    @Test
    fun `OFF - der Vorfall reproduziert das alte Verhalten (gegroundet plus Wissen-gedeckt-Chip)`() {
        val brain = FakeBrainPort("Ich glaube, die hinteren Pfoten sind dafür besser geeignet.")
        val grounding = CountingGroundingPort(cowBlock)
        val events = run(orchestrator(brain, grounding, playfulEnabled = false), ChatRequest(text = deTurn1))

        assertEquals(1, grounding.callCount.get(), "OFF ⇒ exakt der alte Pfad: die Kuh wird gegroundet")
        assertEquals(RouteCategory.FACT_SHORT, grounding.lastCategory.get())
        assertTrue(
            start(events).grounded,
            "OFF ⇒ der (falsche) „Wissen gedeckt\"-Chip von damals — der Beweis, dass NUR das Flag ihn entfernt",
        )
        assertFalse(brain.lastSystemPrompt.get().contains("[SPIELMODUS]"), "OFF ⇒ kein Spiel-Hinweis im Prompt")
        assertNotNull(joinedText(events).ifBlank { null }, "never-silent: es kommt Text")
    }
}
