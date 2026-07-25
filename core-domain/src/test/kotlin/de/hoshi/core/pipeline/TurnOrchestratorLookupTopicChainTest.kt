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
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * **Der Vorfall-Test der Themen-Rückfrage (Andi, 2026-07-25, englische Oberfläche).**
 * Wörtlich protokolliert:
 *
 * ```
 * Andi : "Take a look online for a recept of pizza."
 * Hoshi: "Sure — what exactly should I look up?"      ← fragte zurück statt zu suchen
 * Andi : "A Recept of Pizza"
 * Hoshi: "Pizza is wonderful. The dough needs to rest properly."  ← lokal, Kontext weg
 * ```
 *
 * ZWEI Löcher in einer Kette:
 *  1. die EN-Formulierung wurde von den Erkennern nicht als Online-Wunsch gelesen
 *     (s. [OnlineRequestDetectorTest] / [LookupIntentRecognizerTest]) — und ihr
 *     eingebettetes Thema („a recept of pizza") nicht extrahiert;
 *  2. die Rückfrage merkte sich NICHTS ([TurnOrchestrator.lookupIntentTurn] Fall 4
 *     legte kein [PendingLookup] an) — die nächste Nachricht traf auf leeren Zustand.
 *
 * Dieser Test fährt den Ablauf als ECHTE Turn-Kette (echter [TurnOrchestrator],
 * aufzeichnender [EscalationPort]) — auf ENGLISCH UND auf DEUTSCH — und sichert
 * zusätzlich die Missbrauchs-/Schleifen-Schutzplanken ab.
 */
class TurnOrchestratorLookupTopicChainTest {

    private val cloudAnswer = "Flour, water, yeast, salt — rest the dough for 24 hours."
    private val cloudSource = "example.com"

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
    }

    private class FakeBrainPort(private val line: String = "Pizza is wonderful.") : BrainPort {
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

    private class RecordingEscalationPort : EscalationPort {
        val queries = mutableListOf<String>()
        val snippets = mutableListOf<String>()
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
            queries += query
            snippets += groundingSnippets
            return Mono.just(EscalationResult.Answer(cloudAnswerStatic, cloudSourceStatic, costCents = 0.05))
        }

        companion object {
            const val cloudAnswerStatic = "Flour, water, yeast, salt — rest the dough for 24 hours."
            const val cloudSourceStatic = "example.com"
        }
    }

    private fun orchestrator(
        brain: FakeBrainPort,
        escalation: EscalationPort,
        pending: PendingLookupPort,
        lookupIntentEnabled: Boolean = true,
        factCoverage: FactCoverageGate = FactCoverageGate.DISABLED,
        intent: ToolIntentClassifier = ToolIntentClassifier.DISABLED,
        date: DateFastpath = DateFastpath.DISABLED,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter { RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "fake") },
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
                grounding = GroundingPort.fixed(""),
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            intent = intent,
            date = date,
            factCoverage = factCoverage,
            escalation = escalation,
            pendingLookup = pending,
            escalationMode = { EscalationMode.ERST_FRAGEN },
            lookupIntentEnabled = lookupIntentEnabled,
        )
    }

    private fun turn(o: TurnOrchestrator, text: String, language: Language = Language.DE): List<ChatEvent> =
        o.handle(ChatRequest(text = text, language = language)).collectList().block(Duration.ofSeconds(5))!!

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── (1) DER VORFALL, ENGLISCH, wörtlich ─────────────────────────────────────
    @Test
    fun `EN - Online-Bitte ohne Thema fragt zurueck, die NAECHSTE Nachricht ist das Thema und geht online`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore())

        // Turn 1: die Bitte OHNE Thema ⇒ ehrliche Rückfrage (wie im Protokoll).
        val ask = turn(o, "Take a look online.", Language.EN)
        assertEquals(TurnOrchestrator.LOOKUP_INTENT_CLARIFY_EN, joinedText(ask), "die englische Rückfrage")
        assertEquals(0, cloud.queries.size, "noch nichts nachzuschlagen")
        assertEquals(0, brain.callCount.get(), "brain-frei")

        // Turn 2: DIE NÄCHSTE NACHRICHT IST DAS THEMA ⇒ ONLINE, nicht lokal.
        val topic = turn(o, "A Recept of Pizza", Language.EN)
        assertEquals(
            listOf("A Recept of Pizza"), cloud.queries,
            "das Thema der Folge-Nachricht wird nachgeschlagen — genau das ging im Vorfall verloren",
        )
        assertEquals(listOf(""), cloud.snippets, "Egress-Gesetz: NUR das Thema, nie History/Memory")
        assertEquals(0, brain.callCount.get(), "der Einlöse-Turn ist brain-frei — KEINE lokale Pizzateig-Prosa")
        assertTrue(joinedText(topic).contains(cloudAnswer), "die attribuierte Online-Antwort kommt")
        val start = topic.first() as ChatEvent.Start
        assertTrue(start.escalated, "der Turn ist als eskaliert markiert (Chip: nachgeschlagen, nicht 'aus Wissen')")
    }

    // ── (2) DERSELBE ABLAUF AUF DEUTSCH ─────────────────────────────────────────
    @Test
    fun `DE - Online-Bitte ohne Thema fragt zurueck, die NAECHSTE Nachricht ist das Thema und geht online`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore())

        val ask = turn(o, "Schau mal online nach.")
        assertEquals(TurnOrchestrator.LOOKUP_INTENT_CLARIFY_DE, joinedText(ask), "die deutsche Rückfrage — unverändert")
        assertEquals(0, cloud.queries.size)

        val topic = turn(o, "Ein Pizzarezept")
        assertEquals(listOf("Ein Pizzarezept"), cloud.queries, "das Thema der Folge-Nachricht geht raus")
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertTrue(joinedText(topic).contains(cloudAnswer))
    }

    // ── (3) Bitte MIT Thema in EINEM Satz ⇒ direkt online, gar keine Rückfrage ───
    @Test
    fun `EN - Online-Bitte MIT Thema im selben Satz geht direkt online, ohne jede Rueckfrage`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore())

        val events = turn(o, "Take a look online for a pizza recipe", Language.EN)

        assertEquals(listOf("pizza recipe"), cloud.queries, "das EXTRAHIERTE Thema, nicht der ganze Satz")
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertFalse(
            joinedText(events).contains(TurnOrchestrator.LOOKUP_INTENT_CLARIFY_EN),
            "keine Rückfrage — das Thema stand ja im Satz",
        )
        assertTrue(joinedText(events).contains(cloudAnswer))
    }

    // ── (4) Abwinken ⇒ die Rückfrage verfällt STILL, nichts geht raus ───────────
    @Test
    fun `never mind laesst die Themen-Rueckfrage still verfallen - kein Lookup`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore())

        turn(o, "Take a look online.", Language.EN)
        val cancel = turn(o, "Never mind", Language.EN)

        assertEquals(0, cloud.queries.size, "ein Abwinken wird NIE als Thema nachgeschlagen")
        assertEquals(1, brain.callCount.get(), "der Turn läuft normal weiter (wie ohne offene Rückfrage)")
        assertTrue(cancel.last() is ChatEvent.Done, "never-silent")
    }

    // ── (5) Zustimmung ist KEIN Thema (semantischer Unterschied zu P7) ──────────
    @Test
    fun `ein blosses ja loest die Themen-Rueckfrage NICHT ein - es ist kein Thema`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore())

        turn(o, "Schau mal online nach.")
        turn(o, "ja")

        assertEquals(
            0, cloud.queries.size,
            "P7 löst mit 'ja' ein BEKANNTES Thema ein — hier fehlt das Thema, 'ja' trägt keines",
        )
        assertEquals(1, brain.callCount.get(), "normaler Turn")
    }

    // ── (6) Endlosschleifen-Bremse: zwei themenlose Bitten beenden die Kette ────
    @Test
    fun `zwei themenlose Bitten hintereinander merken sich nichts mehr - keine Endlosschleife`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore())

        assertEquals(TurnOrchestrator.LOOKUP_INTENT_CLARIFY_EN, joinedText(turn(o, "Take a look online.", Language.EN)))
        assertEquals(
            TurnOrchestrator.LOOKUP_INTENT_CLARIFY_EN, joinedText(turn(o, "look it up online", Language.EN)),
            "die zweite themenlose Bitte bekommt dieselbe ehrliche Rückfrage",
        )

        // ... aber sie hat sich NICHTS gemerkt ⇒ die dritte Nachricht ist ein
        // normaler Turn (die Kette endet, statt sich weiterzuschaukeln).
        turn(o, "A Recept of Pizza", Language.EN)
        assertEquals(0, cloud.queries.size, "kein Lookup — die Rückfrage-Kette wurde nach einer Runde beendet")
        assertEquals(1, brain.callCount.get(), "der dritte Turn läuft normal")
    }

    // ── (7) TTL: dieselbe kurze Frist wie das P7-Angebot ────────────────────────
    @Test
    fun `nach Ablauf der TTL ist das Thema kein Einloeser mehr`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val clock = MutableClock(Instant.parse("2026-07-25T20:00:00Z"))
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(clock = clock))

        turn(o, "Take a look online.", Language.EN)
        clock.advanceSeconds(PendingLookupPort.DEFAULT_TTL.seconds + 1)
        turn(o, "A Recept of Pizza", Language.EN)

        assertEquals(0, cloud.queries.size, "eine Rückfrage von vorhin löst kein Thema von jetzt ein")
        assertEquals(1, brain.callCount.get(), "normaler Turn")
    }

    // ── (8) Nur die UNMITTELBAR nächste Nachricht löst ein (one-shot) ───────────
    @Test
    fun `nur die unmittelbar naechste Nachricht loest ein - die uebernaechste nicht mehr`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore())

        turn(o, "Take a look online.", Language.EN)
        turn(o, "Never mind", Language.EN) // räumt die Rückfrage (one-shot) ohne Einlösung
        turn(o, "A Recept of Pizza", Language.EN)

        assertEquals(0, cloud.queries.size, "die übernächste Nachricht trifft auf einen leeren Store")
        assertEquals(2, brain.callCount.get(), "beide Folge-Turns liefen normal")
    }

    // ── (9) Offensichtlicher Themenwechsel per BEFEHL gewinnt ───────────────────
    //    Ein Schalt-Befehl direkt nach der Rückfrage ist kein Nachschlag-Thema —
    //    er läuft in den (gate-gesicherten) Tool-Pfad, NICHT nach draußen.
    @Test
    fun `ein Geraete-Befehl nach der Rueckfrage wird NIE als Thema nachgeschlagen`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(
            brain, cloud, InMemoryPendingLookupStore(),
            intent = DeterministicToolIntentClassifier(),
        )

        turn(o, "Schau mal online nach.")
        val command = turn(o, "Mach das Licht im Wohnzimmer an")

        assertEquals(0, cloud.queries.size, "ein Befehl ist kein Thema — nichts geht nach draußen")
        assertEquals(0, brain.callCount.get(), "der Tool-Pfad ist brain-frei")
        assertTrue(command.last() is ChatEvent.Done, "never-silent")
    }

    // ── (10) Auch ein Fastpath aus [routedTurn] gewinnt (Datum/Uhrzeit) ─────────
    @Test
    fun `eine Datums-Frage nach der Rueckfrage bekommt den Datums-Fastpath, keinen Lookup`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), date = DateFastpath())

        turn(o, "Schau mal online nach.")
        val dateTurn = turn(o, "Welcher Tag ist heute?")

        assertEquals(0, cloud.queries.size, "der deterministische Datums-Pfad gewinnt")
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertTrue(joinedText(dateTurn).isNotBlank(), "die Datums-Quittung kommt")
    }

    // ── (11) Flag OFF ⇒ die Rückfrage merkt sich nichts (byte-neutral) ──────────
    @Test
    fun `Flag OFF - kein Themen-Gedaechtnis, die Kette bleibt heutiges Verhalten`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort()
        val store = InMemoryPendingLookupStore()
        val o = orchestrator(brain, cloud, store, lookupIntentEnabled = false)

        turn(o, "Take a look online.", Language.EN)
        assertEquals(1, brain.callCount.get(), "ohne Naht C plaudert die Bitte wie heute zum Brain")
        turn(o, "A Recept of Pizza", Language.EN)
        assertEquals(0, cloud.queries.size, "nichts gemerkt, nichts eingelöst")
        assertEquals(2, brain.callCount.get())
    }
}
