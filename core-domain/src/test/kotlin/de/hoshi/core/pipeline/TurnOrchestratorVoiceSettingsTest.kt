package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.DailyNote
import de.hoshi.core.port.DailyNotePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die Verdrahtung der beiden Sprach-Intents im [TurnOrchestrator]
 * (Andi 2026-07-05: Stufen + Tagesnote „im chat und über die sprache"):
 *
 *  - **OFF (Default, Decke zu):** KEIN Fastpath-Parameter gesetzt ⇒ die Sätze
 *    fallen in den normalen Brain-Turn — exakt heutiges Verhalten (byte-neutral).
 *  - **ON:** beide Intents laufen VOR dem Routing, brain-frei, mit
 *    `model="policy"`-Start, exakter warmer Quittung + Done; die Stufe landet
 *    über die [EscalationModeSwitchPort]-Naht im Store, die Tagesnote über die
 *    [DailyNotePort]-Naht (source aus [ChatRequest.source], null ⇒ "chat").
 */
class TurnOrchestratorVoiceSettingsTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Brain darf im Fastpath-Turn NICHT gerufen werden")
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

    private class RecordingSwitch : EscalationModeSwitchPort {
        val switched = mutableListOf<EscalationMode>()
        override fun switchTo(mode: EscalationMode): Boolean {
            switched += mode
            return true
        }
    }

    private class RecordingNotes : DailyNotePort {
        val notes = mutableListOf<DailyNote>()
        override fun record(note: DailyNote): Boolean {
            notes += note
            return false
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
        modeSwitch: EscalationModeFastpath? = null,
        dailyNote: DailyNoteFastpath? = null,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routing(),
            honesty = passHonesty(),
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            escalationModeSwitch = modeSwitch ?: EscalationModeFastpath.DISABLED,
            dailyNote = dailyNote ?: DailyNoteFastpath.DISABLED,
        )
    }

    private fun run(o: TurnOrchestrator, request: ChatRequest): List<ChatEvent> =
        o.handle(request).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private val fixedClock = Clock.fixed(Instant.parse("2026-07-07T09:30:00Z"), ZoneId.of("Europe/Berlin"))

    // ── OFF (Default): byte-neutral, die Sätze gehen den heutigen Brain-Weg ──

    @Test
    fun `OFF laesst den Stufen-Satz unveraendert zum Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val events = run(orchestrator(brain), ChatRequest(text = "frag mich erst, bevor du online gehst"))

        assertEquals(1, brain.callCount.get(), "OFF: der Stufen-Satz geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `OFF laesst die Tagesnote unveraendert zum Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val events = run(orchestrator(brain), ChatRequest(text = "Tagesnote 4"))

        assertEquals(1, brain.callCount.get(), "OFF: die Tagesnote geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
    }

    // ── ON: brain-freie Policy-Turns VOR dem Routing ─────────────────────────

    @Test
    fun `ON schaltet die Stufe brain-frei um und quittiert mit Echo`() {
        val brain = ThrowingBrain()
        val port = RecordingSwitch()
        val o = orchestrator(brain, modeSwitch = EscalationModeFastpath(port))
        val events = run(o, ChatRequest(text = "Frag mich erst, bevor du online gehst."))

        assertEquals(0, brain.callCount.get(), "Stufen-Turn darf den Brain NIE rufen")
        assertEquals(listOf(EscalationMode.ERST_FRAGEN), port.switched, "die Stufe landet im Store")
        val start = events.first() as ChatEvent.Start
        assertEquals("policy", start.model, "Policy-Direktantwort, kein Brain")
        assertEquals(TurnOrchestrator.CATEGORY_SETTINGS, start.category)
        assertEquals(
            "Okay — ich frag dich ab jetzt erst, bevor ich online nachschaue.",
            text(events),
            "die Quittung ist deterministisch + exakt",
        )
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `ON speichert die Tagesnote brain-frei mit source chat als Default`() {
        val brain = ThrowingBrain()
        val notes = RecordingNotes()
        val o = orchestrator(brain, dailyNote = DailyNoteFastpath(notes, fixedClock))
        val events = run(o, ChatRequest(text = "Tagesnote: 3, zu langsam"))

        assertEquals(0, brain.callCount.get(), "Tagesnoten-Turn darf den Brain NIE rufen")
        val note = notes.notes.single()
        assertEquals(3, note.score)
        assertEquals("zu langsam", note.grund)
        assertEquals("chat", note.source, "alt-Client/Chat-Rand ohne source-Feld ⇒ chat")
        val start = events.first() as ChatEvent.Start
        assertEquals("policy", start.model)
        assertEquals(TurnOrchestrator.CATEGORY_NOTE, start.category)
        assertEquals("Notiert: heute eine 3. Danke dir!", text(events))
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `ON reicht den Voice-Rand als source durch`() {
        val notes = RecordingNotes()
        val o = orchestrator(ThrowingBrain(), dailyNote = DailyNoteFastpath(notes, fixedClock))
        run(o, ChatRequest(text = "heute war ein 4er Tag", source = "voice"))

        assertEquals("voice", notes.notes.single().source, "der Eingangs-Rand fließt in die Note")
    }
}
