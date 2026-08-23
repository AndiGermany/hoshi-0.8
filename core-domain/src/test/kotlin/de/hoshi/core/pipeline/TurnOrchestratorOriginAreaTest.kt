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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **The room chain** (F2/Irori, "Der Raum reist mit" + "Kein Raum heißt kein Raum").
 * Golden cases against the REAL classifier, REAL last-area store and REAL pending
 * store — only gate (grant-all, counting) and executor are fakes:
 *
 *  1. Kitchen satellite + "Mach das Licht an" ⇒ kuche (NEVER the living room).
 *  1b. …even without a recognized speaker — presence needs no identity.
 *  2. Named room beats the satellite room.
 *  3. Anaphora ("wieder") beats the satellite room — memory wins when the sentence
 *     points back (Hand's rule 14.08.).
 *  4. Ballsaal: an unknown room is asked about, and the answer redeems gated.
 *  5. Anonymous chat, no context ⇒ ask instead of the old "wohnzimmer" hardcode.
 *  6. A satellite whose room the catalog does NOT know ⇒ ask (no raw guess into HA).
 *
 * Live origin of (4)/(5): on 14.08. "Mach das Licht im Ballsaal an" really switched
 * the living room — the classifier's `?: "wohnzimmer"` default is what this suite
 * nails shut (LL-2026-08-14-ballsaal-hardcode).
 */
class TurnOrchestratorOriginAreaTest {

    private class RecordingTool : ToolPort {
        val calls = mutableListOf<ToolCall>()
        override fun execute(call: ToolCall): ToolResult {
            calls.add(call)
            return ToolResult.Ok("ok")
        }
        fun lastArea(): String? = calls.lastOrNull()?.data?.get("area_id") as? String?
    }

    /** Grant-all gate that COUNTS — proves every tat really passed the kernel seam. */
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

    private val brain = CountingBrain()
    private val tool = RecordingTool()
    private val gate = CountingCapability()
    private val lastAreaStore = InMemoryLastAreaStore()

    private fun orchestrator(): TurnOrchestrator {
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
            capability = gate,
            tools = tool,
            lastArea = lastAreaStore,
        )
    }

    /** [room] = the RAW `start.room` string of the satellite ("kueche"), or null for chat/FE. */
    private fun run(o: TurnOrchestrator, text: String, speaker: SpeakerContext?, room: String? = null): List<ChatEvent> =
        o.handle(ChatRequest(text = text, speakerContext = speaker, originAreaId = room))
            .collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private fun done(events: List<ChatEvent>): ChatEvent.Done =
        events.filterIsInstance<ChatEvent.Done>().last()

    private val andi = SpeakerContext(speakerId = "andi")

    // ── (1) Der Küchen-Satellit schaltet die KÜCHE ────────────────────────────
    @Test
    fun `Golden - Kuechen-Satellit und Mach das Licht an schaltet die Kueche`() {
        val o = orchestrator()

        val events = run(o, "Mach das Licht an", andi, room = "kueche")

        val call = tool.calls.single()
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        // Das Geraet sagt "kueche", HA kennt "kuche" — der Katalog ist die eine Wahrheit.
        assertEquals("kuche", call.data["area_id"], "Satelliten-Raum ⇒ kuche, NIE wohnzimmer")
        assertEquals(1, gate.checks.get(), "die Tat laeuft durchs Kernel-Gate")
        assertEquals(0, brain.callCount.get(), "Tool-Turn ist brain-frei")
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── (1b) Anwesenheit braucht keine Identitaet ─────────────────────────────
    @Test
    fun `Golden - Satelliten-Raum greift auch ohne erkannten Sprecher`() {
        val o = orchestrator()

        run(o, "Mach das Licht an", null, room = "kueche")

        assertEquals("kuche", tool.lastArea(), "kein Sprecher noetig — der Raum kommt vom Geraet")
    }

    // ── (2) Genannter Raum schlaegt den Satelliten-Raum ───────────────────────
    @Test
    fun `Golden - genannter Raum gewinnt ueber den Satelliten-Raum`() {
        val o = orchestrator()

        run(o, "Mach das Licht im Schlafzimmer an", andi, room = "kueche")

        assertEquals("schlafzimmer", tool.lastArea(), "der GESAGTE Raum gewinnt immer")
    }

    // ── (3) Anapher schlaegt Anwesenheit ─────────────────────────────────────
    @Test
    fun `Golden - Mach das wieder aus zieht die gemerkte Area vor den Satelliten-Raum`() {
        val o = orchestrator()

        // Erst bewusst das Wohnzimmer geschaltet (vom Kuechen-Satelliten aus) …
        run(o, "mach das Licht im Wohnzimmer an", andi, room = "kueche")
        assertEquals("wohnzimmer", tool.lastArea())

        // … dann die Anapher: "wieder" verweist zurueck ⇒ Erinnerung schlaegt Anwesenheit.
        run(o, "Mach das wieder aus", andi, room = "kueche")

        val call = tool.calls.last()
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("wohnzimmer", call.data["area_id"], "Anapher ⇒ zuletzt geschaltete Area, nicht die Kueche")
        assertEquals(0, brain.callCount.get())
    }

    // ── (4) Ballsaal: unbekannter Raum ⇒ Rueckfrage, Antwort loest gegatet ein ─
    @Test
    fun `Golden - Ballsaal fragt nach dem Raum und Wohnzimmer loest gegatet ein`() {
        val o = orchestrator()

        val ask = run(o, "Mach das Licht im Ballsaal an", andi)

        assertTrue(tool.calls.isEmpty(), "unbekannter Raum ⇒ KEINE Tat (14.08. schaltete er das Wohnzimmer)")
        assertEquals(0, gate.checks.get(), "die Rueckfrage laeuft nicht durchs Schreib-Gate")
        assertTrue(text(ask).contains("Raum"), "Raum-Rueckfrage erwartet, war: ${text(ask)}")
        assertEquals(PendingAreaClarifyPort.OUTCOME_ASKED, done(ask).pendingClarify)

        val redeem = run(o, "Wohnzimmer", andi)

        assertEquals(PendingAreaClarifyPort.OUTCOME_RESOLVED, done(redeem).pendingClarify)
        assertEquals(1, gate.checks.get(), "der geparkte Call MUSS durchs Kernel-Gate")
        val call = tool.calls.single()
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
        assertEquals(0, brain.callCount.get(), "Frage wie Einloesung sind brain-frei")
    }

    // ── (5) Anonymer, kontextloser Chat ⇒ Rueckfrage statt Hardcode ───────────
    @Test
    fun `Golden - anonymer kontextloser Chat fragt nach dem Raum`() {
        val o = orchestrator()

        val events = run(o, "Licht an", null)

        assertTrue(tool.calls.isEmpty(), "kein Raum, kein Geraet, kein Gedaechtnis ⇒ keine Tat")
        assertEquals(0, gate.checks.get())
        assertTrue(text(events).contains("Raum"), "Raum-Rueckfrage erwartet, war: ${text(events)}")
        assertEquals(PendingAreaClarifyPort.OUTCOME_ASKED, done(events).pendingClarify)
    }

    // ── (6) Satellit in einem Raum, den HA nicht kennt ⇒ fragen statt raten ───
    @Test
    fun `Satelliten-Raum ausserhalb des Katalogs wird gefragt statt roh geschaltet`() {
        val o = orchestrator()

        val events = run(o, "Mach das Licht an", andi, room = "ballsaal")

        assertTrue(tool.calls.isEmpty(), "unbekannter Geraete-Raum wird NIE roh an HA gereicht")
        assertTrue(text(events).contains("Raum"), "Raum-Rueckfrage erwartet, war: ${text(events)}")
        assertEquals(PendingAreaClarifyPort.OUTCOME_ASKED, done(events).pendingClarify)
    }

    // ── (7) Der roomless Befehl OHNE Geraete-Wort ("mach an") vom Satelliten ──
    @Test
    fun `Verb-Partikel-Befehl vom Satelliten schaltet dessen Raum statt zu fragen`() {
        val o = orchestrator()

        run(o, "schalte mal was an", andi, room = "kueche")

        assertEquals("kuche", tool.lastArea(), "auch die Clarify-Kandidaten erben den Satelliten-Raum")
        assertEquals(1, gate.checks.get())
    }

    // ── (8) Nebenbefund F2: das Kompositum gewinnt jetzt ueber die Erinnerung ──
    // Bis F2 pruefte die Anaphern-Naht den Raum am TEXT (ToolAreas.mentionsRoom);
    // „wohnzimmerlicht" nennt token-genau KEINEN Raum ⇒ die gemerkte Kueche
    // ueberschrieb den im Kompositum steckenden Raum. Jetzt zaehlt, was der
    // Classifier wirklich aufgeloest hat.
    @Test
    fun `Kompositum-Raum schlaegt die gemerkte Area`() {
        val o = orchestrator()

        run(o, "mach das Licht in der Küche an", andi)
        assertEquals("kuche", tool.lastArea())

        run(o, "wohnzimmerlicht an", andi)

        assertEquals("wohnzimmer", tool.lastArea(), "der im Kompositum genannte Raum gewinnt")
    }
}
