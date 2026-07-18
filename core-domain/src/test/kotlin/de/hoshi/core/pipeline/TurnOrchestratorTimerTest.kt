package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.InMemoryScheduledItemStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die Verdrahtung des TimerFastpath in den [TurnOrchestrator]:
 *  - **OFF (Default):** der Classifier erkennt keinen Timer ⇒ der Text fällt in den
 *    normalen Brain-Turn (kein Timer-Branch) — exakt heutiges Verhalten.
 *  - **ON:** ein Timer-Befehl läuft brain-frei über die ScheduledItemPort-Naht.
 */
class TurnOrchestratorTimerTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im Timer-Turn NICHT gerufen werden")
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
        timer: TimerFastpath,
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
            timer = timer,
        )
    }

    private fun fixedClock(): Clock {
        val now = ZonedDateTime.of(2024, 1, 1, 6, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        return Clock.fixed(now.toInstant(), ZoneId.of("Europe/Berlin"))
    }

    private fun run(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── OFF: Timer-Text fällt in den Brain-Turn (byte-neutral) ───────────────
    @Test
    fun `OFF erkennt keinen Timer und nutzt den Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val o = orchestrator(
            brain = brain,
            // timerEnabled=false (Default) ⇒ Classifier emittiert keinen Timer-Call.
            intent = DeterministicToolIntentClassifier(timerEnabled = false),
            timer = TimerFastpath.DISABLED,
        )
        val events = run(o, "stell einen Timer auf 10 Minuten")

        assertEquals(1, brain.callCount.get(), "OFF: der Timer-Text geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: SET läuft brain-frei über die ScheduledItemPort ──────────────────
    @Test
    fun `ON legt einen Timer brain-frei an`() {
        val brain = ThrowingBrain()
        val store = InMemoryScheduledItemStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(timerEnabled = true),
            timer = TimerFastpath(store = store, clock = fixedClock()),
        )
        val events = run(o, "stell einen Timer auf 10 Minuten")

        assertEquals(0, brain.callCount.get(), "Timer-Turn darf den Brain NIE rufen")
        assertEquals(1, store.query().size, "der Timer muss angelegt sein")
        assertTrue(text(events).contains("Timer für 10 Minuten"), "Quittung war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: ChatRequest.deviceId fließt als origin in den angelegten Timer ───
    @Test
    fun `ON traegt ChatRequest deviceId als origin in den Store`() {
        val brain = ThrowingBrain()
        val store = InMemoryScheduledItemStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(timerEnabled = true),
            timer = TimerFastpath(store = store, clock = fixedClock()),
        )
        o.handle(ChatRequest(text = "stell einen Timer auf 10 Minuten", deviceId = "voice-pe-1"))
            .collectList().block(Duration.ofSeconds(5))

        assertEquals(0, brain.callCount.get(), "Timer-Turn darf den Brain NIE rufen")
        assertEquals("voice-pe-1", store.query().single().origin, "deviceId wird als Wecker-origin durchgereicht")
    }

    // ── ON: alter Client ohne deviceId ⇒ origin=null (byte-neutral) ──────────
    @Test
    fun `ON ohne deviceId legt origin=null an`() {
        val brain = ThrowingBrain()
        val store = InMemoryScheduledItemStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(timerEnabled = true),
            timer = TimerFastpath(store = store, clock = fixedClock()),
        )
        // run() nutzt ChatRequest(text=...) ohne deviceId ⇒ der heutige Alt-Pfad.
        run(o, "stell einen Timer auf 10 Minuten")

        assertNull(store.query().single().origin, "alter Client ohne deviceId ⇒ origin=null (FE klingelt überall)")
    }

    // ── ON: QUERY läuft brain-frei ───────────────────────────────────────────
    @Test
    fun `ON beantwortet eine Query brain-frei`() {
        val brain = ThrowingBrain()
        val store = InMemoryScheduledItemStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(timerEnabled = true),
            timer = TimerFastpath(store = store, clock = fixedClock()),
        )
        run(o, "stell einen Timer auf 10 Minuten")
        val events = run(o, "wie lange läuft der Timer noch")

        assertEquals(0, brain.callCount.get(), "Query-Turn darf den Brain NIE rufen")
        assertTrue(text(events).contains("10 Minuten"), "Restzeit-Quittung war: ${text(events)}")
    }

    // ── ON: der Live-Befund (Andi 2026-07-06) — Status-Frage geht NIE ans Brain ──
    @Test
    fun `ON wie lange geht der Timer noch antwortet brain-frei und definitiv`() {
        val brain = ThrowingBrain()
        val store = InMemoryScheduledItemStore()
        val o = orchestrator(
            brain = brain,
            intent = DeterministicToolIntentClassifier(timerEnabled = true),
            timer = TimerFastpath(store = store, clock = fixedClock()),
        )
        // Exakt Andis Live-Formulierung (Cowork 20260706-1745) — fiel vorher ans Brain
        // („Welchen Timer meinst du genau…"). Timer-Status ist Store-Terrain.
        val events = run(o, "Wie lange geht der Timer noch?")

        assertEquals(0, brain.callCount.get(), "Status-Frage darf den Brain NIE rufen (Nachtwächter-Prinzip)")
        assertTrue(text(events).contains("Gerade läuft kein Timer"), "Antwort war: ${text(events)}")
        assertTrue(!text(events).contains("?"), "NIE eine Gegenfrage: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done)
    }
}
