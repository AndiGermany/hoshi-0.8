package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.RadioCallOutcome
import de.hoshi.core.port.RadioPort
import de.hoshi.core.port.RadioStation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die Verdrahtung des [RadioFastpath] in den [TurnOrchestrator]:
 *  - **OFF (Default):** KEIN `radio`-Parameter gesetzt ⇒ „spiel radio wdr 2"
 *    fällt in den normalen Brain-Turn — exakt heutiges Verhalten (byte-neutral).
 *  - **ON:** der Radio-Wunsch läuft brain-frei über die [RadioPort]-Naht
 *    (Suche + play auf dem Ziel), mit warmer Direkt-Antwort + Done.
 */
class TurnOrchestratorRadioTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im Radio-Turn NICHT gerufen werden")
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

    private class FakeRadioPort : RadioPort {
        val plays = mutableListOf<Pair<RadioStation, String>>()
        override fun search(name: String): RadioStation? =
            RadioStation(name = "WDR 2", streamUrl = "https://wdr2.example/stream").takeIf { name == "wdr 2" }

        override fun play(station: RadioStation, target: String): RadioCallOutcome {
            plays += station to target
            return RadioCallOutcome.VERIFIED
        }

        override fun stop(target: String): RadioCallOutcome = RadioCallOutcome.VERIFIED
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

    private fun orchestrator(brain: BrainPort, radio: RadioFastpath? = null): TurnOrchestrator {
        val persona = PersonaService()
        val base = TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
        )
        return if (radio == null) base else TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            radio = radio,
        )
    }

    private fun run(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── OFF (Default): byte-neutral, der Text geht den heutigen Brain-Weg ────

    @Test
    fun `OFF laesst den Radio-Text unveraendert zum Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val o = orchestrator(brain) // KEIN radio-Parameter ⇒ RadioFastpath.DISABLED
        val events = run(o, "spiel radio wdr 2")

        assertEquals(1, brain.callCount.get(), "OFF: der Radio-Text geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── ON: brain-freier Radio-Turn über die RadioPort-Naht ──────────────────

    @Test
    fun `ON startet Radio brain-frei mit warmer Quittung`() {
        val brain = ThrowingBrain()
        val port = FakeRadioPort()
        val o = orchestrator(
            brain,
            radio = RadioFastpath(radio = port, target = "media_player.rx_v6a", enabled = true),
        )
        val events = run(o, "spiel radio wdr 2")

        assertEquals(0, brain.callCount.get(), "Radio-Turn darf den Brain NIE rufen")
        assertEquals(1, port.plays.size, "genau ein play auf dem Ziel")
        assertEquals("media_player.rx_v6a", port.plays[0].second)
        assertTrue(text(events).contains("WDR 2 läuft — auf dem Receiver."), "Quittung war: ${text(events)}")
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `ON stoppt Radio brain-frei`() {
        val brain = ThrowingBrain()
        val o = orchestrator(
            brain,
            radio = RadioFastpath(radio = FakeRadioPort(), target = "media_player.rx_v6a", enabled = true),
        )
        val events = run(o, "radio aus")

        assertEquals(0, brain.callCount.get())
        assertTrue(text(events).contains("Radio ist aus."), "Quittung war: ${text(events)}")
    }

    // ── ON, aber NOT_FOUND: warm, brain-frei, nichts startet ─────────────────

    @Test
    fun `ON antwortet warm NOT_FOUND unter der Schwelle`() {
        val brain = ThrowingBrain()
        val port = FakeRadioPort()
        val o = orchestrator(
            brain,
            radio = RadioFastpath(radio = port, target = "media_player.rx_v6a", enabled = true),
        )
        val events = run(o, "spiel radio xyzzy")

        assertEquals(0, brain.callCount.get())
        assertTrue(port.plays.isEmpty(), "unter der Schwelle darf NICHTS starten")
        assertTrue(text(events).contains("kenn ich nicht sicher"), "Quittung war: ${text(events)}")
    }
}
