package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die **deterministische, pro-Sprecher Anaphern-Auflösung** (Live-Befund:
 * „Licht in der Küche an" klappt → „schalt das Licht wieder aus" ohne Raum ⇒ Hoshi
 * wusste nicht welches Licht). Verdrahtet den ECHTEN
 * [DeterministicToolIntentClassifier] + den ECHTEN [InMemoryLastAreaStore] mit einem
 * Fake-Gate (Grant-all) und einem Fake-Executor (Ok), KEIN Brain/HA.
 *
 * Kern-Garantien:
 *  1. Ein expliziter Raum schreibt → merkt die Area; ein folgender roomless Befehl
 *     desselben Sprechers fällt darauf zurück (überschreibt die Classifier-Default-Area).
 *  2. Anderer Sprecher / keine Historie ⇒ KEIN Fallback ⇒ Brain (kein Raten).
 *  3. Genannter Raum gewinnt IMMER über die gemerkte Area.
 *  4. speakerId-los ⇒ byte-identisch (Classifier-Default-Area, kein Store, kein Brain).
 */
class TurnOrchestratorAnaphoraTest {

    // ── Fake-Executor: merkt sich den zuletzt ausgeführten Call, antwortet Ok ──
    private class RecordingTool : ToolPort {
        val calls = mutableListOf<ToolCall>()
        override fun execute(call: ToolCall): ToolResult {
            calls.add(call)
            return ToolResult.Ok("ok")
        }
        fun lastArea(): String? = calls.lastOrNull()?.data?.get("area_id") as? String?
    }

    // ── Fake-Brain: zählt nur (wird im Tool-Turn NIE, im Suppress-Fall 1× gerufen) ──
    private class CountingBrain : BrainPort {
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
            return Flux.just(LlmDelta("Brain-Antwort."))
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
        tools: ToolPort,
        lastArea: LastAreaPort,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            // Der ECHTE deterministische Classifier (nicht gefakt).
            intent = DeterministicToolIntentClassifier(),
            // Grant-all: die normalisierte data ist die Roh-data (area_id bleibt erhalten).
            capability = CapabilityPort { call -> GateDecision.Grant(call.data) },
            tools = tools,
            lastArea = lastArea,
        )
    }

    private fun run(o: TurnOrchestrator, text: String, speaker: SpeakerContext?): List<ChatEvent> =
        o.handle(ChatRequest(text = text, speakerContext = speaker))
            .collectList().block(Duration.ofSeconds(5))!!

    private val alice = SpeakerContext(speakerId = "alice")
    private val bob = SpeakerContext(speakerId = "bob")

    // ── (1) Expliziter Raum merkt; roomless fällt darauf zurück (überschreibt Default) ──
    @Test
    fun `roomless Befehl faellt auf die zuletzt geschaltete Area des Sprechers`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val o = orchestrator(brain, tool, InMemoryLastAreaStore())

        // Schritt 1: expliziter Raum KÜCHE (nicht der Classifier-Default wohnzimmer!).
        run(o, "mach das Licht in der Küche an", alice)
        assertEquals("kuche", tool.lastArea(), "expliziter Raum ⇒ kuche geschaltet + gemerkt")

        // Schritt 2: roomless ⇒ fällt auf die gemerkte kuche, NICHT auf die Default-Area.
        val events = run(o, "schalt das Licht wieder aus", alice)
        assertEquals("kuche", tool.lastArea(), "roomless ⇒ gemerkte Area kuche (nicht Default wohnzimmer)")
        assertEquals("turn_off", tool.calls.last().service)
        assertEquals(0, brain.callCount.get(), "Tool-Turn ruft den Brain NIE")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── (1b) Spec-Narrativ wohnzimmer: an → (roomless) aus ────────────────────
    @Test
    fun `Wohnzimmer an dann das Licht aus schaltet das Wohnzimmer aus`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val o = orchestrator(brain, tool, InMemoryLastAreaStore())

        run(o, "mach das Licht im Wohnzimmer an", alice)
        val events = run(o, "schalt das Licht aus", alice)

        val last = tool.calls.last()
        assertEquals("light", last.domain)
        assertEquals("turn_off", last.service)
        assertEquals("wohnzimmer", last.data["area_id"])
        assertEquals(0, brain.callCount.get())
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── (2) Anderer Sprecher OHNE Historie ⇒ KEIN Fallback ⇒ Brain ────────────
    @Test
    fun `anderer Sprecher ohne Historie faellt durch zum Brain`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val store = InMemoryLastAreaStore()
        val o = orchestrator(brain, tool, store)

        // alice merkt kuche …
        run(o, "mach das Licht in der Küche an", alice)
        tool.calls.clear()

        // … bob hat KEINE Historie ⇒ roomless wird NICHT geraten ⇒ Brain.
        val events = run(o, "schalt das Licht aus", bob)

        assertTrue(tool.calls.isEmpty(), "bob ohne Historie ⇒ keine Tat (kein Raten über Sprecher)")
        assertEquals(1, brain.callCount.get(), "roomless ohne Historie ⇒ genau 1 Brain-Call")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── (2b) Bekannter Sprecher, aber gar keine Historie ⇒ Brain (kein Guess) ──
    @Test
    fun `roomless ohne jede Historie geht zum Brain statt zu raten`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val o = orchestrator(brain, tool, InMemoryLastAreaStore())

        val events = run(o, "schalt das Licht aus", alice)

        assertTrue(tool.calls.isEmpty(), "keine Historie ⇒ keine Tat")
        assertEquals(1, brain.callCount.get(), "keine Historie ⇒ Brain übernimmt")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── (3) Genannter Raum gewinnt über die gemerkte Area ─────────────────────
    @Test
    fun `expliziter Raum gewinnt ueber die gemerkte Area`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val o = orchestrator(brain, tool, InMemoryLastAreaStore())

        // alice merkt kuche …
        run(o, "mach das Licht in der Küche an", alice)
        // … nennt dann aber explizit das Schlafzimmer ⇒ Schlafzimmer gewinnt.
        run(o, "mach das Licht im Schlafzimmer aus", alice)

        val last = tool.calls.last()
        assertEquals("turn_off", last.service)
        assertEquals("schlafzimmer", last.data["area_id"], "genannter Raum schlägt gemerkte kuche")
        assertEquals(0, brain.callCount.get())
    }

    // ── (4) speakerId-los ⇒ byte-identisch (Default-Area, kein Store, kein Brain) ──
    @Test
    fun `speakerId-loser Turn bleibt byte-identisch mit Default-Area`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val store = InMemoryLastAreaStore()
        val o = orchestrator(brain, tool, store)

        // KEIN speakerContext ⇒ keine Anaphern-Logik ⇒ Classifier-Default wohnzimmer.
        val events = run(o, "schalt das Licht aus", null)

        val last = tool.calls.last()
        assertEquals("turn_off", last.service)
        assertEquals("wohnzimmer", last.data["area_id"], "ohne Sprecher ⇒ Default-Area (unverändert)")
        assertEquals(0, brain.callCount.get(), "eindeutiger Befehl ⇒ Tool-Turn, kein Brain")
        // Der Store wurde NICHT befüllt (anonymer/abwesender Sprecher).
        assertNull(store.lastArea("unknown"))
        assertTrue(events.last() is ChatEvent.Done)
    }
}
