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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * The room ask as a REAL state (F1-4, live find 2026-08-13: "Mach das Licht an"
 * -> "which room?" -> "Wohnzimmer" evaporated, worst case a fake completion).
 * Full-cycle contract with the REAL classifier + REAL pending store:
 *
 *  1. Golden: roomless command -> ask (parked) -> "Wohnzimmer" -> parked call
 *     runs through the capability gate + executor; claim gate does NOT fire.
 *  2. Prepositional answer "im Wohnzimmer" redeems too.
 *  3. TTL expiry discards silently (diary mark only, no execution).
 *  4. An interposed own intent abandons the pending and runs itself.
 *  5. Session isolation: a foreign session never redeems a foreign pending.
 *  6. Verb-only ask ("schalte mal was an", classifier branch 5) parks as well.
 */
class TurnOrchestratorAreaClarifyPendingTest {

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
    }

    private class RecordingTool : ToolPort {
        val calls = mutableListOf<ToolCall>()
        override fun execute(call: ToolCall): ToolResult {
            calls.add(call)
            return ToolResult.Ok("ok")
        }
    }

    /** Grant-all gate that COUNTS — proves the redeemed call went through the kernel seam. */
    private class CountingCapability : CapabilityPort {
        val checks = AtomicInteger(0)
        override fun check(call: ToolCall): GateDecision {
            checks.incrementAndGet()
            return GateDecision.Grant(call.data)
        }
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

    private fun orchestrator(
        brain: BrainPort,
        tools: ToolPort,
        capability: CapabilityPort,
        lastArea: LastAreaPort = InMemoryLastAreaStore(),
        pendingStore: PendingAreaClarifyPort = InMemoryPendingAreaClarifyStore(),
        pendingLookup: PendingLookupPort = PendingLookupPort.NONE,
        lookupIntentEnabled: Boolean = false,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
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
            capability = capability,
            tools = tools,
            lastArea = lastArea,
            pendingAreaClarify = pendingStore,
            pendingLookup = pendingLookup,
            lookupIntentEnabled = lookupIntentEnabled,
        )
    }

    private fun run(o: TurnOrchestrator, text: String, speaker: SpeakerContext?): List<ChatEvent> =
        o.handle(ChatRequest(text = text, speakerContext = speaker)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private fun done(events: List<ChatEvent>): ChatEvent.Done =
        events.filterIsInstance<ChatEvent.Done>().last()

    // Conversation-Key darf einen Sprecher erst NACH Trust-Gate verwenden.
    private val andi = SpeakerContext(speakerId = "andi", score = 0.95)
    private val gast = SpeakerContext(speakerId = "vera", score = 0.95)

    // ── (1) Golden: ask -> answer -> gated execution, latch silent ────────────
    @Test
    fun `Golden - Mach das Licht an dann Wohnzimmer schaltet gegatet das Wohnzimmer`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val gate = CountingCapability()
        val store = InMemoryLastAreaStore()
        val o = orchestrator(brain, tool, gate, lastArea = store)

        val ask = run(o, "Mach das Licht an", andi)
        assertTrue(text(ask).contains("Raum"), "Raum-Rueckfrage erwartet, war: ${text(ask)}")
        assertEquals(PendingAreaClarifyPort.OUTCOME_ASKED, done(ask).pendingClarify)
        assertTrue(tool.calls.isEmpty(), "die Rueckfrage selbst ist keine Tat")
        assertEquals(0, gate.checks.get(), "die Rueckfrage laeuft nicht durchs Schreib-Gate")

        val redeem = run(o, "Wohnzimmer", andi)
        assertEquals(1, gate.checks.get(), "der geparkte Call MUSS durchs Kernel-Gate")
        val call = tool.calls.single()
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
        assertEquals(0, brain.callCount.get(), "Einloesung ist brain-frei")
        assertEquals("ok", text(redeem), "Quittung ist das ECHTE Executor-Outcome (Kagami)")
        assertNull(done(redeem).claimGateFired, "der Vollzugs-Riegel feuert nicht faelschlich")
        assertEquals(PendingAreaClarifyPort.OUTCOME_RESOLVED, done(redeem).pendingClarify)
        assertEquals("wohnzimmer", store.lastArea("andi"), "die eingeloeste Area speist Last-Area")
    }

    // ── (2) Prepositional form ────────────────────────────────────────────────
    @Test
    fun `im Wohnzimmer loest die Rueckfrage genauso ein`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val gate = CountingCapability()
        val o = orchestrator(brain, tool, gate)

        run(o, "Mach das Licht an", andi)
        run(o, "im Wohnzimmer", andi)

        assertEquals("wohnzimmer", tool.calls.single().data["area_id"])
        assertEquals("turn_on", tool.calls.single().service)
        assertEquals(0, brain.callCount.get())
    }

    // ── (3) TTL expiry discards silently ──────────────────────────────────────
    @Test
    fun `TTL-Ablauf verwirft - Wohnzimmer von spaeter schaltet nichts`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val gate = CountingCapability()
        val clock = MutableClock(Instant.now())
        val o = orchestrator(brain, tool, gate, pendingStore = InMemoryPendingAreaClarifyStore(clock = clock))

        run(o, "Mach das Licht an", andi)
        clock.advanceSeconds(121)
        val events = run(o, "Wohnzimmer", andi)

        assertTrue(tool.calls.isEmpty(), "abgelaufen ⇒ NIE eine Tat aus altem Pending")
        assertEquals(0, gate.checks.get())
        assertEquals(1, brain.callCount.get(), "der Turn laeuft seinen normalen Weg")
        assertEquals(PendingAreaClarifyPort.OUTCOME_EXPIRED, done(events).pendingClarify)
    }

    // ── (4) Interposed own intent abandons and runs itself ────────────────────
    @Test
    fun `Zwischen-Intent verwirft das Pending und laeuft selbst normal`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val gate = CountingCapability()
        val o = orchestrator(brain, tool, gate)

        run(o, "Mach das Licht an", andi)
        val other = run(o, "mach das Licht in der Küche an", andi)

        assertEquals("kuche", tool.calls.single().data["area_id"], "der eigene Intent laeuft selbst")
        assertEquals(PendingAreaClarifyPort.OUTCOME_ABANDONED, done(other).pendingClarify)

        // The pending is gone (one-shot): a later "Wohnzimmer" redeems nothing.
        run(o, "Wohnzimmer", andi)
        assertEquals(1, tool.calls.size, "kein zweiter Call — das Pending war verworfen")
        assertEquals(1, brain.callCount.get(), "Wohnzimmer ohne Pending ist ein normaler Turn")
    }

    @Test
    fun `expliziter Lookup statt Raumantwort bilanziert das gezogene Area-Pending als abandoned`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val gate = CountingCapability()
        val o = orchestrator(
            brain,
            tool,
            gate,
            pendingLookup = InMemoryPendingLookupStore(),
            lookupIntentEnabled = true,
        )

        run(o, "Mach das Licht an", andi)
        val lookup = run(o, "schau online nach", andi)

        assertEquals(PendingAreaClarifyPort.OUTCOME_ABANDONED, done(lookup).pendingClarify)
        assertTrue(tool.calls.isEmpty(), "der Themenwechsel fuehrt die geparkte Tat nie aus")
    }

    // ── (5) Session isolation ─────────────────────────────────────────────────
    @Test
    fun `fremde Session erbt nichts - nur die fragende Session loest ein`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val gate = CountingCapability()
        val o = orchestrator(brain, tool, gate)

        run(o, "Mach das Licht an", andi)
        run(o, "Wohnzimmer", gast)
        assertTrue(tool.calls.isEmpty(), "fremde Session darf NIE ein fremdes Pending einloesen")

        run(o, "Wohnzimmer", andi)
        assertEquals("wohnzimmer", tool.calls.single().data["area_id"], "die fragende Session loest weiter ein")
    }

    // ── (6) Verb-only ask (classifier branch 5) parks too ─────────────────────
    @Test
    fun `schalte mal was an parkt ueber den Clarify-Fastpath und loest ein`() {
        val brain = CountingBrain()
        val tool = RecordingTool()
        val gate = CountingCapability()
        val o = orchestrator(brain, tool, gate)

        val ask = run(o, "schalte mal was an", andi)
        assertEquals(PendingAreaClarifyPort.OUTCOME_ASKED, done(ask).pendingClarify)

        run(o, "Wohnzimmer", andi)
        val call = tool.calls.single()
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
        assertEquals(0, brain.callCount.get())
    }
}
