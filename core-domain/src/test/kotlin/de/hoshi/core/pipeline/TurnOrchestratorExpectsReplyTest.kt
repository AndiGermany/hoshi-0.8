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
 * **„Erwarte Folgeantwort" am terminalen Done** (Andi-Livetest 2026-08-21, wörtlich:
 * „Wenn Hoshi etwas nicht weiß und mich fragt, ob sie online schauen soll, soll sie
 * erstmal zuhören, ob ich okay oder ja sage.").
 *
 * Ein Turn, der mit einer OFFENEN Rückfrage endet, muss das am
 * [ChatEvent.Done] tragen — sonst kann der Satellit das Mikro nicht ohne neues
 * Wake-Word wieder öffnen. Bewiesen wird hier die SERVER-Hälfte an allen drei
 * Pending-Arten des [PendingTurnArbiter], plus die Gegenprobe:
 *
 *  1. Raum-Rückfrage    ⇒ `expectsReply=true`, `pendingKind="area"`
 *  2. Online-Consent    ⇒ `expectsReply=true`, `pendingKind="lookup"`
 *  3. Orts-Rückfrage    ⇒ `expectsReply=true`, `pendingKind="location"`
 *  4. Einlösung + ganz normaler Turn ⇒ BEIDE Felder `null` (Done byte-identisch)
 *
 * Der Wert kommt ausschließlich aus dem Arbiter-[PendingTurnArbiter.OfferResult]
 * bzw. dem `asked`-Lifecycle — nie geraten.
 */
class TurnOrchestratorExpectsReplyTest {

    private class RecordingTool : ToolPort {
        val calls = mutableListOf<ToolCall>()
        override fun execute(call: ToolCall): ToolResult {
            calls.add(call)
            return ToolResult.Ok("ok")
        }
    }

    private class GrantAll : CapabilityPort {
        override fun check(call: ToolCall): GateDecision = GateDecision.Grant(call.data)
    }

    private class CountingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            return Flux.just(LlmDelta("Brain-Antwort."))
        }
    }

    /** Wetter-Naht, die IMMER nach dem Ort fragt — der Orts-Zweig ohne echten Geo-Adapter. */
    private class AlwaysAsksLocation : WeatherLocationAskPort {
        override fun needsLocation(query: String, category: RouteCategory): Boolean = true
        override fun resolveAndStore(place: String): Mono<String> = Mono.just(place)
    }

    private fun honesty(weak: Boolean, cloud: Boolean) = HonestyGate(
        weakDomain = WeakDomainSignal { weak },
        onlineRequest = OnlineRequestSignal { false },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { cloud },
    )

    private fun routing(category: RouteCategory) = RoutingPolicy(
        keywordRouter = KeywordRouter { RouteDecision(category, RouteProvider.LOCAL, "fake") },
        llmRefiner = { _, fb -> Mono.just(fb) },
        embeddingRefiner = { _, fb -> Mono.just(fb) },
        softRoutingEnabled = false,
        softRoutingMode = "embedding",
    )

    private fun orchestrator(
        category: RouteCategory = RouteCategory.SMART_HOME,
        weak: Boolean = false,
        cloud: Boolean = false,
        brain: BrainPort = CountingBrain(),
        tools: ToolPort = RecordingTool(),
        pendingLookup: PendingLookupPort = InMemoryPendingLookupStore(),
        weatherAsk: WeatherLocationAskPort = WeatherLocationAskPort.NONE,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(category),
            honesty = honesty(weak, cloud),
            promptAssembler = TurnPromptAssembler(
                persona = persona,
                entityMemory = { _, _ -> null },
                grounding = GroundingPort.EMPTY,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            intent = DeterministicToolIntentClassifier(),
            capability = GrantAll(),
            tools = tools,
            lastArea = InMemoryLastAreaStore(),
            pendingAreaClarify = InMemoryPendingAreaClarifyStore(),
            pendingLookup = pendingLookup,
            weatherAsk = weatherAsk,
        )
    }

    private fun run(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text, speakerContext = andi)).collectList().block(Duration.ofSeconds(5))!!

    private fun done(events: List<ChatEvent>): ChatEvent.Done =
        events.filterIsInstance<ChatEvent.Done>().last()

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private val andi = SpeakerContext(speakerId = "andi", score = 0.95)

    // ── (1) Raum-Rückfrage ────────────────────────────────────────────────────
    @Test
    fun `Raum-Rueckfrage endet mit expectsReply und pendingKind area`() {
        val o = orchestrator()

        val ask = done(run(o, "Mach das Licht an"))

        assertEquals(true, ask.expectsReply, "die Raum-Rueckfrage ist eine OFFENE Frage")
        assertEquals(ChatEvent.PendingKind.AREA, ask.pendingKind)
        assertEquals(PendingAreaClarifyPort.OUTCOME_ASKED, ask.pendingClarify, "das Diary-Feld bleibt unberuehrt")
    }

    // ── (2) Online-Nachschau-Consent (Andis Fall) ─────────────────────────────
    @Test
    fun `Online-Consent-Rueckfrage endet mit expectsReply und pendingKind lookup`() {
        val o = orchestrator(category = RouteCategory.FACT_SHORT, weak = true, cloud = true)

        val events = run(o, "Wie hoch ist der Mount Everest wirklich?")
        val ask = done(events)

        assertTrue(text(events).isNotBlank(), "es wird wirklich gefragt, war: '${text(events)}'")
        assertEquals(true, ask.expectsReply, "nach 'soll ich nachschauen?' MUSS das Mikro wieder aufgehen")
        assertEquals(ChatEvent.PendingKind.LOOKUP, ask.pendingKind)
    }

    // ── (3) Orts-Rückfrage ────────────────────────────────────────────────────
    @Test
    fun `Orts-Rueckfrage endet mit expectsReply und pendingKind location`() {
        val o = orchestrator(category = RouteCategory.FACT_SHORT, weatherAsk = AlwaysAsksLocation())

        val ask = done(run(o, "Wie wird das Wetter?"))

        assertEquals(true, ask.expectsReply)
        assertEquals(ChatEvent.PendingKind.LOCATION, ask.pendingKind)
    }

    // ── (4) Gegenprobe: kein Pending ⇒ Done byte-identisch ────────────────────
    @Test
    fun `ohne offene Rueckfrage bleiben beide Felder null`() {
        val tool = RecordingTool()
        val o = orchestrator(tools = tool)

        // Vollstaendiger Schaltbefehl ⇒ Tat statt Rueckfrage.
        val acted = done(run(o, "Mach das Licht im Wohnzimmer an"))

        assertTrue(tool.calls.isNotEmpty(), "der Befehl war vollstaendig ⇒ echte Tat")
        assertNull(acted.expectsReply, "kein Pending ⇒ KEIN Wire-Key")
        assertNull(acted.pendingKind, "kein Pending ⇒ KEIN Wire-Key")
    }

    // ── (5) Die Einlösung selbst öffnet das Mikro NICHT erneut ────────────────
    @Test
    fun `die eingeloeste Raum-Antwort traegt kein expectsReply mehr`() {
        val tool = RecordingTool()
        val o = orchestrator(tools = tool)

        run(o, "Mach das Licht an")
        val redeem = done(run(o, "Wohnzimmer"))

        assertEquals("wohnzimmer", tool.calls.single().data["area_id"])
        assertNull(redeem.expectsReply, "die Frage ist beantwortet — das Mikro bleibt zu")
        assertNull(redeem.pendingKind)
        assertEquals(PendingAreaClarifyPort.OUTCOME_RESOLVED, redeem.pendingClarify)
    }
}
