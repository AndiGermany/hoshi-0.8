package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.InMemoryListStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die Verdrahtung des [ListFastpath] in den [TurnOrchestrator]
 * (Andi-JA 2026-07-08, „Listen auf die Ring-1-Karte"):
 *  - **OFF (Default):** der Classifier erkennt keinen Listen-Befehl ⇒ der Text
 *    fällt in den normalen Brain-Turn (kein List-Branch) — exakt heutiges Verhalten.
 *  - **ON:** ADD/READ/REMOVE laufen brain-frei über die ListPort-Naht.
 *  - **Wächter:** ein Satz wie „Mach mir eine Liste von Ideen für Papas
 *    Geburtstag" landet AUCH bei ON NIE im Listen-Store — der Brain wird gerufen.
 */
class TurnOrchestratorListTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im List-Turn NICHT gerufen werden")
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
        list: ListFastpath,
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
            list = list,
        )
    }

    private fun fixedClock(): Clock {
        val now = ZonedDateTime.of(2026, 7, 8, 9, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        return Clock.fixed(now.toInstant(), ZoneId.of("Europe/Berlin"))
    }

    private fun run(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── OFF: List-Text fällt in den Brain-Turn (byte-neutral) ────────────────
    @Test
    fun `OFF erkennt keine Liste und nutzt den Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(listEnabled = false),
            list = ListFastpath.DISABLED,
        )
        val events = run(o, "Setz Milch auf die Einkaufsliste.")

        assertEquals(1, brain.callCount.get(), "OFF: der List-Text geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: ADD läuft brain-frei über die ListPort ────────────────────────────
    @Test
    fun `ON legt Golden 13 - Setz Milch auf die Einkaufsliste - brain-frei an`() {
        val brain = ThrowingBrain()
        val store = InMemoryListStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(listEnabled = true),
            list = ListFastpath(store = store, clock = fixedClock()),
        )
        val events = run(o, "Setz Milch auf die Einkaufsliste.")

        assertEquals(0, brain.callCount.get(), "List-Turn darf den Brain NIE rufen")
        assertEquals(1, store.items().size, "Milch muss im Store stehen")
        assertEquals("Milch", store.items().single().text)
        assertTrue(text(events).contains("Milch"), "Read-back-Quittung war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: READ (Golden 14) läuft brain-frei ─────────────────────────────────
    @Test
    fun `ON beantwortet Golden 14 - Was steht auf der Liste - brain-frei`() {
        val brain = ThrowingBrain()
        val store = InMemoryListStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(listEnabled = true),
            list = ListFastpath(store = store, clock = fixedClock()),
        )
        run(o, "Setz Milch auf die Einkaufsliste.")
        val events = run(o, "Was steht auf der Liste?")

        assertEquals(0, brain.callCount.get(), "Read-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("Milch"), "Aufzählung war: ${text(events)}")
    }

    @Test
    fun `ON READ auf leerer Liste ist ehrlich leer und stellt NIE eine Gegenfrage`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(listEnabled = true),
            list = ListFastpath(store = InMemoryListStore(), clock = fixedClock()),
        )
        val events = run(o, "Was steht auf der Liste?")

        assertEquals(0, brain.callCount.get())
        assertTrue(text(events).contains("leer"), "Antwort war: ${text(events)}")
        assertTrue(!text(events).contains("?"), "NIE eine Gegenfrage: ${text(events)}")
    }

    // ── ON: REMOVE (Golden 15) hat Vorrang vor ADD und läuft brain-frei ───────
    @Test
    fun `ON vollzieht Golden 15 - Nimm die Milch von der Liste - brain-frei`() {
        val brain = ThrowingBrain()
        val store = InMemoryListStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(listEnabled = true),
            list = ListFastpath(store = store, clock = fixedClock()),
        )
        run(o, "Setz Milch auf die Einkaufsliste.")
        val events = run(o, "Nimm die Milch von der Liste.")

        assertEquals(0, brain.callCount.get(), "Remove-Turn darf den Brain NIE rufen")
        assertTrue(store.items().isEmpty(), "Milch muss entfernt sein (REMOVE, nicht ADD)")
        assertTrue(text(events).contains("Milch"), "Quittung war: ${text(events)}")
    }

    // ── Wächter: darf AUCH bei ON nie im Listen-Store landen ──────────────────
    @Test
    fun `Waechter - Mach mir eine Liste von Ideen fuer Papas Geburtstag geht AUCH bei ON an den Brain`() {
        val brain = DeltaBrain("Klar, hier sind ein paar Ideen...")
        val store = InMemoryListStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(listEnabled = true),
            list = ListFastpath(store = store, clock = fixedClock()),
        )
        val events = run(o, "Mach mir eine Liste von Ideen für Papas Geburtstag.")

        assertEquals(1, brain.callCount.get(), "der Wächter-Satz MUSS den Brain-Weg nehmen")
        assertTrue(store.items().isEmpty(), "der Wächter-Satz darf NIE im Listen-Store landen")
        assertTrue(text(events).contains("Ideen"), "Brain-Antwort war: ${text(events)}")
    }
}
