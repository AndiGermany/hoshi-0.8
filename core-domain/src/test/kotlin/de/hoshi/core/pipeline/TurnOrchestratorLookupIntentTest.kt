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
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Der KETTEN-Test der Lookup-Intent-Naht (Live-Fix 2026-07-16)** — echter
 * [TurnOrchestrator] (Muster [TurnOrchestratorExtendedThinkTest]), aufzeichnender
 * [EscalationPort]:
 *
 *  - **Naht C** ([LookupIntentRecognizer]): „schau online nach" löst ein offenes
 *    Angebot ein / eskaliert die vorherige Frage direkt (Egress-Fake beweist: NUR
 *    die Frage im Payload) / fragt ehrlich zurück, wenn nichts da ist; Modus AUS ⇒
 *    ehrlicher Setting-Hinweis. Flag OFF ⇒ byte-neutral (die Bitte plaudert wie
 *    heute zum Brain).
 *  - **Naht D** ([BrainAbstainRecognizer]): ein ehrliches Brain-Passen registriert
 *    ein Angebot, OHNE die Antwort-Bytes zu ändern — ein „ja" danach löst ein.
 *  - **Präzedenz**: ein bloßes „ja" OHNE offenes Angebot bleibt heutiges Verhalten
 *    (Fall 7); ein Stufen-Schaltbefehl („schalte online nachschauen aus") gewinnt
 *    IMMER über Naht C, obwohl der Satz selbst Verb+Scope trägt und isoliert ein
 *    [LookupIntentRecognizer]-Treffer wäre (Fall 8 — beweist die Guard-Klausel in
 *    [TurnOrchestrator.handle] ist kein totes Sicherheitsnetz).
 */
class TurnOrchestratorLookupIntentTest {

    private val question = "Wie hoch ist der Eiffelturm?"
    private val cloudAnswer = "Der Eiffelturm ist 330 Meter hoch."
    private val cloudSource = "Wikipedia"
    private val abstainLine = "Ehrlich, das weiß ich gerade nicht sicher."

    // ── Zählender Fake-Brain (konfigurierbare Antwort) ───────────────────────────
    private class FakeBrainPort(private val line: String = "Brain-Antwort.") : BrainPort {
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

    // ── Aufzeichnender Eskalations-Port: fängt Query + Schnipsel (Egress-Beweis) ──
    private class RecordingEscalationPort(
        private val result: () -> Mono<EscalationResult>,
    ) : EscalationPort {
        val queries = mutableListOf<String>()
        val snippets = mutableListOf<String>()
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
            queries += query
            snippets += groundingSnippets
            return result()
        }
    }

    private fun orchestrator(
        brain: FakeBrainPort,
        escalation: EscalationPort = EscalationPort.NONE,
        pending: PendingLookupPort = PendingLookupPort.NONE,
        mode: () -> EscalationMode = { EscalationMode.ERST_FRAGEN },
        factCoverage: FactCoverageGate = FactCoverageGate(enabled = true),
        lookupIntentEnabled: Boolean = true,
        grounding: String = "",
        escalationModeSwitch: EscalationModeFastpath = EscalationModeFastpath.DISABLED,
        // Recherche-Modell-Eskalation (Andi-Auftrag 2026-07-19) — Default NONE/leer
        // ⇒ byte-neutral (s. TurnOrchestratorResearchIntentTest für die Wahl-Tests).
        researchEscalation: EscalationPort = EscalationPort.NONE,
        researchEscalationProvider: String = "",
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "fake")
                },
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
                entityMemory = { null },
                grounding = { _, _ -> Mono.just(grounding) },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            factCoverage = factCoverage,
            escalation = escalation,
            pendingLookup = pending,
            escalationMode = mode,
            escalationModeSwitch = escalationModeSwitch,
            lookupIntentEnabled = lookupIntentEnabled,
            researchEscalation = researchEscalation,
            researchEscalationProvider = researchEscalationProvider,
        )
    }

    private fun turn(o: TurnOrchestrator, text: String, history: List<ChatMessage> = emptyList()): List<ChatEvent> =
        o.handle(ChatRequest(text = text, history = history)).collectList().block(Duration.ofSeconds(5))!!

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private fun answer() = RecordingEscalationPort {
        Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, costCents = 0.05))
    }

    // ── (1) Intent + offenes Pending ⇒ Einlösung wie Consent ─────────────────────
    @Test
    fun `Intent mit offenem Angebot loest es ein wie ein ja (Original-Query)`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        // Turn 1: die Wissensfrage ⇒ Deflect (offer setzt das Pending).
        turn(o, question)
        assertEquals(0, cloud.queries.size, "ERST_FRAGEN eskaliert nicht ungefragt")

        // Turn 2: „schau online nach" ⇒ löst das Angebot mit der ORIGINAL-Query ein.
        val intent = turn(o, "schau online nach")
        assertEquals(listOf(question), cloud.queries, "1 Lookup mit der Original-Query — nie mit der Bitte selbst")
        assertEquals(listOf(""), cloud.snippets, "Egress-Gesetz: NUR die Frage, Schnipsel leer")
        assertEquals(0, brain.callCount.get(), "der Intent-Turn ist brain-frei")
        assertTrue(
            joinedText(intent).contains(TurnOrchestrator.ESCALATION_FRAME_DE + cloudAnswer),
            "attribuierte verbatim-Antwort",
        )
    }

    // ── (2) Intent ohne Pending + History ⇒ Eskalation mit voriger Frage ─────────
    @Test
    fun `Intent ohne Angebot eskaliert die vorherige Frage direkt - Egress-Fake beweist NUR die Frage`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        // ERST_FRAGEN: die explizite Bitte IST der Consent — sie eskaliert trotzdem direkt.
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })
        val history = listOf(
            ChatMessage("user", question),
            ChatMessage("assistant", "Das weiß ich gerade nicht sicher."),
        )

        val events = turn(o, "schau online nach", history)

        assertEquals(listOf(question), cloud.queries, "die VORHERIGE User-Frage ist die Query")
        assertEquals(listOf(""), cloud.snippets, "Egress-Gesetz: NUR die Frage, nie History/Memory im Payload")
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertTrue(joinedText(events).contains(cloudAnswer), "die attribuierte Antwort kommt")
    }

    // ── (3) Intent ohne alles ⇒ ehrliche Rückfrage, 0 Brain-Calls ────────────────
    @Test
    fun `Intent ohne Angebot und ohne History fragt ehrlich zurueck, 0 Brain-Calls`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        val events = turn(o, "schau online nach")
        assertEquals(TurnOrchestrator.LOOKUP_INTENT_CLARIFY_DE, joinedText(events), "die ehrliche Rückfrage")
        assertEquals(0, brain.callCount.get(), "brain-frei — kein Raten")
        assertEquals(0, cloud.queries.size, "nichts zum Nachschlagen ⇒ kein Call")
        assertTrue(events.last() is ChatEvent.Done, "never-silent")
    }

    // ── (4) Modus AUS ⇒ ehrliche Aus-Phrase, kein Call ───────────────────────────
    @Test
    fun `Modus AUS - explizite Bitte bekommt den ehrlichen Setting-Hinweis, kein Call`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.AUS })
        val history = listOf(ChatMessage("user", question))

        val events = turn(o, "schau online nach", history)
        assertEquals(TurnOrchestrator.EXTENDED_THINK_OFF_HINT_DE, joinedText(events), "der ehrliche AUS-Hinweis")
        assertEquals(0, cloud.queries.size, "bei AUS geht NIE ein Call raus")
        assertEquals(0, brain.callCount.get(), "brain-freie Direktantwort")
    }

    // ── (5) Naht D: Brain-Abstain registriert ein Pending; die Abstain-ANTWORT
    //    bleibt byte-identisch (Naht D fasst sie nie an), aber (Andi-Auftrag
    //    2026-07-20, „das muss klappen") ERST_FRAGEN erlaubt Nachfragen ⇒ EIN
    //    hörbarer Angebots-Satz kommt als EIGENER TextDelta NACH der Antwort
    //    dazu — danach löst ein schlichtes „ja" ein. ─────────────────────────
    @Test
    fun `Brain-Abstain registriert ein Angebot, haengt das hoerbare Angebot an, danach loest ja ein`() {
        // Flag OFF als Referenz: dieselbe Kette, nur ohne Naht D — die Antwort-TEXTE müssen gleich sein.
        val brainOff = FakeBrainPort(abstainLine)
        val baseline = orchestrator(
            brainOff, answer(), InMemoryPendingLookupStore(),
            factCoverage = FactCoverageGate.DISABLED, lookupIntentEnabled = false,
        )
        val brainOn = FakeBrainPort(abstainLine)
        val cloud = answer()
        val o = orchestrator(
            brainOn, cloud, InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            factCoverage = FactCoverageGate.DISABLED, // proceed ⇒ Brain antwortet (und passt ehrlich)
            lookupIntentEnabled = true,
        )

        val abstainBaseline = turn(baseline, question)
        val abstain = turn(o, question)
        assertEquals(abstainLine, joinedText(abstainBaseline), "Baseline (Naht D aus): nur die Abstain-Zeile")
        assertTrue(
            joinedText(abstain).startsWith(abstainLine),
            "die Antwort selbst bleibt unangetastet — das Angebot kommt NACH ihr, nie in sie hinein gemischt",
        )
        assertEquals(
            4, abstain.size,
            "Start, Antwort-Delta, Angebots-Delta, Done — EIN zusätzliches Event ggü. der Baseline",
        )
        assertTrue(abstain[2] is ChatEvent.TextDelta, "das hörbare Angebot ist ein EIGENER TextDelta")
        assertTrue(abstain.last() is ChatEvent.Done, "never-silent")
        assertEquals(1, brainOn.callCount.get(), "der Fact-Turn lief normal zum Brain")
        assertEquals(0, cloud.queries.size, "das ehrliche Passen eskaliert selbst nicht")

        // Folge-Turn „ja" ⇒ das von Naht D registrierte Angebot löst mit der Original-Query ein.
        val ja = turn(o, "ja")
        assertEquals(listOf(question), cloud.queries, "das Abstain-Angebot war einlösbar — mit der Original-Frage")
        assertTrue(joinedText(ja).contains(cloudAnswer), "attribuierte Antwort nach dem ja")
    }

    // ── (5b) Naht D Hörbarkeit bei Modus AUS: exakt heutiges (stilles) Verhalten —
    //    das Pending wird weiterhin registriert (ein "ja" bekäme sonst den
    //    Setting-Hinweis statt still zu verhungern), aber OHNE Angebots-Satz: ein
    //    lautes Angebot wäre irreführend, wenn Nachfragen gar nicht erlaubt sind. ─
    @Test
    fun `Brain-Abstain bei Modus AUS bleibt still - kein Angebots-Satz, Pending trotzdem registriert`() {
        val brainOff = FakeBrainPort(abstainLine)
        val baseline = orchestrator(
            brainOff, answer(), InMemoryPendingLookupStore(),
            factCoverage = FactCoverageGate.DISABLED, lookupIntentEnabled = false,
        )
        val brainOn = FakeBrainPort(abstainLine)
        val o = orchestrator(
            brainOn, answer(), InMemoryPendingLookupStore(),
            mode = { EscalationMode.AUS },
            factCoverage = FactCoverageGate.DISABLED,
            lookupIntentEnabled = true,
        )

        val abstainBaseline = turn(baseline, question)
        val abstain = turn(o, question)
        assertEquals(abstainBaseline, abstain, "Modus AUS: die Events bleiben byte-identisch zu Naht-D-aus (still)")

        // Das Pending WURDE registriert (unabhängig vom Modus) — ein "ja" bekommt
        // den ehrlichen Setting-Hinweis statt still zu verhungern.
        val ja = turn(o, "ja")
        assertEquals(TurnOrchestrator.EXTENDED_THINK_OFF_HINT_DE, joinedText(ja), "der ehrliche AUS-Hinweis")
    }

    // ── (6) Flag OFF ⇒ byte-neutral (die Bitte plaudert wie heute zum Brain) ─────
    @Test
    fun `Flag OFF - schau online nach laeuft byte-neutral als normaler Turn zum Brain`() {
        val brainOn = FakeBrainPort()
        val cloudOn = answer()
        val on = orchestrator(brainOn, cloudOn, InMemoryPendingLookupStore(), lookupIntentEnabled = false)

        val brainRef = FakeBrainPort()
        val ref = orchestrator(brainRef, answer(), InMemoryPendingLookupStore(), lookupIntentEnabled = false)

        // Mit Flag OFF ist die explizite Bitte für die Pipeline nur Prosa ⇒ Routing → Brain.
        assertEquals(
            turn(ref, "schau online nach"),
            turn(on, "schau online nach"),
            "Flag OFF: identische Events zur Referenz",
        )
        assertEquals(1, brainOn.callCount.get(), "ohne Naht C läuft die Bitte zum Brain (heutiges Verhalten)")
        assertEquals(0, cloudOn.queries.size, "keine Eskalation, wenn die Naht aus ist")
    }

    // ── (7) Präzedenz: ein bloßes „ja" OHNE offenes Angebot bleibt heutiges
    //    Verhalten (normaler Turn zum Brain) — unabhängig vom Flag. Weder die
    //    Naht-B-Einlösung (kein pendingThink) noch Naht C ([LookupIntentRecognizer]
    //    matcht „ja" laut [LookupIntentRecognizerTest] NIE) greifen. ───────────────
    @Test
    fun `ja ohne offenes Angebot bleibt heutiges Verhalten - normaler Turn zum Brain`() {
        val brainOn = FakeBrainPort()
        val on = orchestrator(brainOn, answer(), InMemoryPendingLookupStore(), lookupIntentEnabled = true)

        val brainRef = FakeBrainPort()
        val ref = orchestrator(brainRef, answer(), InMemoryPendingLookupStore(), lookupIntentEnabled = false)

        assertEquals(
            turn(ref, "ja"),
            turn(on, "ja"),
            "ein 'ja' ohne offenes Angebot ist für BEIDE Nähte irrelevant — identische Events",
        )
        assertEquals(1, brainOn.callCount.get(), "'ja' ohne Pending läuft normal zum Brain, wie heute")
    }

    // ── (7b) Flag OFF ⇒ auch das hörbare Angebot bleibt aus (kein Puffer, kein
    //    Marker-Check, kein zusätzlicher TextDelta) — die Abstain-Antwort läuft
    //    exakt wie eine normale Brain-Antwort durch. ──────────────────────────
    @Test
    fun `Flag OFF - Brain-Abstain-Antwort bleibt still, kein Angebots-Satz`() {
        val brainOff = FakeBrainPort(abstainLine)
        val ref = orchestrator(
            brainOff, answer(), InMemoryPendingLookupStore(),
            factCoverage = FactCoverageGate.DISABLED, lookupIntentEnabled = false,
        )
        val brainOn = FakeBrainPort(abstainLine)
        val on = orchestrator(
            brainOn, answer(), InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            factCoverage = FactCoverageGate.DISABLED,
            lookupIntentEnabled = false,
        )

        assertEquals(turn(ref, question), turn(on, question), "Flag OFF: byte-identische Events, kein Angebot")
    }

    // ── (7c) ConsentRecognizer: „ja aber warum" ist KEIN Consent — bleibt normaler
    //    Turn zum Brain, obwohl ein Angebot offen ist (kein Fehl-Einlösen). ───────
    @Test
    fun `ja aber warum loest ein offenes Angebot NICHT ein - bleibt normaler Turn zum Brain`() {
        val brainOff = FakeBrainPort(abstainLine)
        val baseline = orchestrator(
            brainOff, answer(), InMemoryPendingLookupStore(),
            factCoverage = FactCoverageGate.DISABLED, lookupIntentEnabled = false,
        )
        val brainOn = FakeBrainPort(abstainLine)
        val cloud = answer()
        val o = orchestrator(
            brainOn, cloud, InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            factCoverage = FactCoverageGate.DISABLED,
            lookupIntentEnabled = true,
        )

        turn(o, question) // registriert das Pending (mit hörbarem Angebot).

        val brainFollowupOn = FakeBrainPort()
        val brainFollowupRef = FakeBrainPort()
        val oFollowup = orchestrator(
            brainFollowupOn, answer(), InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN }, factCoverage = FactCoverageGate.DISABLED, lookupIntentEnabled = true,
        )
        val refFollowup = orchestrator(
            brainFollowupRef, answer(), InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN }, factCoverage = FactCoverageGate.DISABLED, lookupIntentEnabled = true,
        )

        assertEquals(
            turn(refFollowup, "ja aber warum"),
            turn(oFollowup, "ja aber warum"),
            "'ja aber warum' ist kein Consent — läuft wie ein normaler Turn zum Brain",
        )
        assertEquals(0, cloud.queries.size, "kein Fehl-Einlösen des offenen Angebots")
    }

    // ── (7d) ConsentRecognizer an Naht C: „jo" kennt [AffirmationRecognizer] (Naht B)
    //    NICHT — löst das offene Abstain-Angebot trotzdem über Naht C ein. ─────────
    @Test
    fun `jo loest ein offenes Abstain-Angebot ueber Naht C ein (AffirmationRecognizer kennt jo nicht)`() {
        assertFalse(AffirmationRecognizer.matches("jo"), "Naht B kennt 'jo' nicht (Vorbedingung des Tests)")

        val brainOn = FakeBrainPort(abstainLine)
        val cloud = answer()
        val o = orchestrator(
            brainOn, cloud, InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            factCoverage = FactCoverageGate.DISABLED,
            lookupIntentEnabled = true,
        )

        turn(o, question) // registriert das Pending.
        val jo = turn(o, "jo")

        assertEquals(listOf(question), cloud.queries, "'jo' hat das offene Angebot über Naht C eingelöst")
        assertTrue(joinedText(jo).contains(cloudAnswer), "attribuierte Antwort nach dem jo")
    }

    // ── (7e) ConsentRecognizer OHNE offenes Angebot ⇒ exakt normaler Turn (kein
    //    Fehl-Eintritt in Naht C bei einem bloßen Zustimmungswort ohne Frage). ────
    @Test
    fun `jo ohne offenes Angebot bleibt normaler Turn zum Brain`() {
        val brainOn = FakeBrainPort()
        val on = orchestrator(brainOn, answer(), InMemoryPendingLookupStore(), lookupIntentEnabled = true)

        val brainRef = FakeBrainPort()
        val ref = orchestrator(brainRef, answer(), InMemoryPendingLookupStore(), lookupIntentEnabled = false)

        assertEquals(
            turn(ref, "jo"),
            turn(on, "jo"),
            "'jo' ohne offenes Angebot ist irrelevant für Naht C — identische Events",
        )
        assertEquals(1, brainOn.callCount.get(), "'jo' ohne Pending läuft normal zum Brain")
    }

    // ── (8) Präzedenz: ein Stufen-Schaltbefehl gewinnt IMMER über Naht C — auch
    //    wenn er selbst Verb+Scope trägt und den [LookupIntentRecognizer] träfe
    //    (die KDoc-Warnung am `handleTurn`-Guard, s. TurnOrchestrator.kt). Ohne
    //    die `escalationModeSwitch.match(...) == null`-Klausel würde „schalte
    //    online nachschauen aus" fälschlich als Nachschlag-Bitte durchgehen. ───
    @Test
    fun `Stufen-Schaltbefehl gewinnt ueber Lookup-Intent, obwohl der Satz selbst matcht`() {
        // Beweis, dass die Guard-Klausel kein totes Sicherheitsnetz ist: der Satz
        // TRÄGT Verb („nachschau") + Scope („online") — isoliert betrachtet ein
        // Lookup-Intent-Treffer.
        assertTrue(
            LookupIntentRecognizer.matches("Schalte Online-Nachschauen aus."),
            "der Satz träfe ohne die Orchestrator-Guard-Klausel Naht C",
        )

        val brain = FakeBrainPort()
        val cloud = answer()
        val switchStore = object : EscalationModeSwitchPort {
            override fun switchTo(mode: EscalationMode): Boolean = true
        }
        val o = orchestrator(
            brain, cloud, InMemoryPendingLookupStore(),
            lookupIntentEnabled = true,
            escalationModeSwitch = EscalationModeFastpath(switchStore, enabled = true),
        )

        val events = turn(o, "Schalte Online-Nachschauen aus.")

        assertEquals(
            "Okay — Online-Nachschauen ist aus. Ich bleib komplett lokal.",
            joinedText(events),
            "der Schaltbefehl gewinnt — keine Lookup-Rückfrage/-Eskalation",
        )
        assertEquals(0, brain.callCount.get(), "der Schaltbefehl ist brain-frei")
        assertEquals(0, cloud.queries.size, "kein Lookup — Naht C wurde NICHT betreten")
    }

    // ── (9)-(12) Recherche-Modell-Wahl (Andi-Auftrag 2026-07-19) ─────────────────

    // ── (9) Recherche-Phrase + konfiguriertes Recherche-Modell ⇒ der Recherche-
    //    Port läuft — NICHT der Standard-Port —, und das Start-Event trägt das
    //    ehrliche Label (keine Nano-Beschriftung auf einer Sol-Antwort). Consent
    //    ist übersprungen (0 Brain-Calls, direkte Eskalation der vorigen Frage). ──
    @Test
    fun `Recherche-Phrase mit konfiguriertem Recherche-Modell ruft den Recherche-Port, nicht den Standard-Port`() {
        val brain = FakeBrainPort()
        val nano = answer()
        val research = RecordingEscalationPort {
            Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, costCents = 1.3))
        }
        val o = orchestrator(
            brain, nano, InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            researchEscalation = research,
            researchEscalationProvider = "openai-sol",
        )
        val history = listOf(ChatMessage("user", question))

        val events = turn(o, "recherchiere online", history)

        assertEquals(0, nano.queries.size, "der Standard-Port darf NICHT gerufen werden")
        assertEquals(listOf(question), research.queries, "der Recherche-Port bekommt die VORHERIGE Frage")
        assertEquals(listOf(""), research.snippets, "Egress-Gesetz gilt unverändert: NUR die Frage")
        assertEquals(0, brain.callCount.get(), "brain-frei — der Imperativ IST der Consent")
        val start = events.first() as ChatEvent.Start
        assertTrue(start.escalated, "escalated muss gesetzt sein")
        assertEquals(
            "openai-sol", start.escalationProvider,
            "ehrliches Label — keine Nano-Beschriftung auf der Sol-Antwort",
        )
        assertTrue(joinedText(events).contains(cloudAnswer), "die attribuierte Antwort kommt")
    }

    // ── (10) Config leer (Default) ⇒ byte-neutral: eine Recherche-Phrase läuft
    //    EXAKT wie eine generische Lookup-Bitte über den Standard-Port. ──
    @Test
    fun `Recherche-Phrase OHNE konfiguriertes Recherche-Modell laeuft byte-identisch zu einer normalen Lookup-Bitte`() {
        val brainRef = FakeBrainPort()
        val cloudRef = answer()
        val ref = orchestrator(brainRef, cloudRef, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        val brainOn = FakeBrainPort()
        val cloudOn = answer()
        // researchEscalation/-Provider NICHT gesetzt (Default) — exakt der
        // PipelineConfig-Zustand ohne hoshi.escalation.research-model.
        val on = orchestrator(brainOn, cloudOn, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        val history = listOf(ChatMessage("user", question))
        val refEvents = turn(ref, "schau online nach", history)
        val onEvents = turn(on, "recherchiere online", history)
        // Event-für-Event identisch BIS AUF Index 1 (die Cloud-Consent-Floskel,
        // ResponseFormatter.cloudConsentAccept — Anti-Repeat-Zufallsauswahl aus
        // einem Phrasen-Pool, JE Formatter-Instanz unabhängig; hat mit der
        // Modell-/Port-Wahl dieses Tests nichts zu tun).
        assertEquals(refEvents.size, onEvents.size, "gleiche Event-Anzahl")
        assertEquals(
            refEvents.filterIndexed { i, _ -> i != 1 },
            onEvents.filterIndexed { i, _ -> i != 1 },
            "ohne konfiguriertes Recherche-Modell laufen BEIDE Phrasen byte-identisch über den Standard-Port " +
                "(Start/Antwort/Quelle/Done — Provider-Label, Kosten, Query-Hash allesamt gleich)",
        )
    }

    // ── (11) Eine generische Bitte bleibt Nano, AUCH WENN ein Recherche-Modell
    //    konfiguriert ist — kein versehentliches Upgrade nicht-recherche-Bitten. ──
    @Test
    fun `Eine normale Lookup-Bitte bleibt auf dem Standard-Modell, auch wenn ein Recherche-Modell konfiguriert ist`() {
        val brain = FakeBrainPort()
        val nano = answer()
        val research = RecordingEscalationPort {
            Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, costCents = 1.3))
        }
        val o = orchestrator(
            brain, nano, InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            researchEscalation = research,
            researchEscalationProvider = "openai-sol",
        )
        val history = listOf(ChatMessage("user", question))

        val events = turn(o, "schau online nach", history)

        assertEquals(listOf(question), nano.queries, "eine generische Bitte bleibt Nano")
        assertEquals(0, research.queries.size, "der Recherche-Port läuft NUR bei einem Recherche-Imperativ")
        val start = events.first() as ChatEvent.Start
        assertEquals(TurnOrchestrator.LOOKUP_NOTE_PROVIDER, start.escalationProvider)
    }

    // ── (12) Ein offenes Angebot (Naht A/D) wird von einer NACHTRÄGLICHEN
    //    Recherche-Bitte über den Recherche-Port eingelöst — die JETZIGE Bitte
    //    entscheidet die Modell-Wahl, nicht die Herkunft des Angebots. ──
    @Test
    fun `Recherche-Phrase loest ein offenes Angebot recherche-seitig ein`() {
        val brain = FakeBrainPort()
        val nano = answer()
        val research = RecordingEscalationPort {
            Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, costCents = 1.3))
        }
        val o = orchestrator(
            brain, nano, InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            researchEscalation = research,
            researchEscalationProvider = "openai-sol",
        )

        turn(o, question) // FactCoverage-Deflect (kein Recherche-Bezug) setzt das Pending.
        assertEquals(0, nano.queries.size, "ERST_FRAGEN eskaliert nicht ungefragt")

        val events = turn(o, "recherche dazu") // löst das Angebot ein — MIT Recherche-Wunsch.

        assertEquals(listOf(question), research.queries, "Angebot eingelöst — über den Recherche-Port")
        assertEquals(0, nano.queries.size, "der Standard-Port bleibt ungenutzt")
        assertTrue(joinedText(events).contains(cloudAnswer))
    }

    // ── (13)-(16) Naht-C Inline-Query (Andi-Fix 2026-07-20, Live-Repro) ──────────

    // ── (13) T3-Satz wörtlich: die Bitte TRÄGT selbst eine Frage ⇒ die EXTRAHIERTE
    //    Frage (nicht der ganze Satz) wird eskaliert, keine Rückfrage. ───────────
    @Test
    fun `Intent-Satz mit eingebetteter Frage eskaliert die extrahierte Query statt nachzufragen`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        val events = turn(o, "Schau bitte online nach, wann GTA 6 erscheint.")

        assertEquals(
            listOf("wann GTA 6 erscheint"),
            cloud.queries,
            "die EXTRAHIERTE Frage geht raus, nicht der ganze Satz mit dem Intent-Präfix",
        )
        assertEquals(listOf(""), cloud.snippets, "Egress-Gesetz gilt unverändert")
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertTrue(joinedText(events).contains(cloudAnswer), "attribuierte Antwort statt Rückfrage")
        assertFalse(
            joinedText(events).contains(TurnOrchestrator.LOOKUP_INTENT_CLARIFY_DE),
            "die Rückfrage darf NICHT mehr kommen — die Frage stand ja im Satz",
        )
    }

    // ── (14) Recherche-Satz mit eingebetteter Frage + konfiguriertem Recherche-
    //    Modell ⇒ dieselbe Extraktion, aber über den Recherche-Port. ─────────────
    @Test
    fun `Recherche-Satz mit eingebetteter Frage eskaliert die extrahierte Query ueber den Recherche-Port`() {
        val brain = FakeBrainPort()
        val nano = answer()
        val research = RecordingEscalationPort {
            Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, costCents = 1.3))
        }
        val o = orchestrator(
            brain, nano, InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            researchEscalation = research,
            researchEscalationProvider = "openai-sol",
        )

        val events = turn(o, "recherchiere online, wie hoch der Eiffelturm ist")

        assertEquals(0, nano.queries.size, "der Standard-Port darf NICHT gerufen werden")
        assertEquals(
            listOf("wie hoch der Eiffelturm ist"),
            research.queries,
            "die EXTRAHIERTE Frage geht an den Recherche-Port",
        )
        assertEquals(0, brain.callCount.get(), "brain-frei")
        val start = events.first() as ChatEvent.Start
        assertEquals("openai-sol", start.escalationProvider, "ehrliches Recherche-Label")
        assertTrue(joinedText(events).contains(cloudAnswer))
    }

    // ── (15) Ein offenes Pending gewinnt IMMER über einen Inline-Rest der
    //    AKTUELLEN Bitte — das Pending trägt die belastbare Original-Frage. ──────
    @Test
    fun `offenes Pending gewinnt ueber einen Inline-Rest der aktuellen Bitte`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        turn(o, question) // Deflect ⇒ offer setzt das Pending mit der Original-Frage.
        assertEquals(0, cloud.queries.size, "ERST_FRAGEN eskaliert nicht ungefragt")

        // Die Bitte TRÄGT selbst einen Inline-Rest ("wie viel Meter genau") — das
        // offene Pending muss trotzdem gewinnen (Fall 1 vor Fall 2).
        val events = turn(o, "schau online nach, wie viel Meter genau")

        assertEquals(
            listOf(question),
            cloud.queries,
            "das PENDING (Original-Frage) gewinnt, nicht der Inline-Rest der aktuellen Bitte",
        )
        assertTrue(joinedText(events).contains(cloudAnswer))
    }

    // ── (16) Kein Rest (nur Intent-Wörter) ⇒ die ehrliche Rückfrage bleibt —
    //    unverändert, auch mit einem zusätzlichen Füllwort ("mal"). ──────────────
    @Test
    fun `Intent-Satz ohne jeden Rest bekommt weiterhin die ehrliche Rueckfrage`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        val events = turn(o, "schau mal online nach")

        assertEquals(TurnOrchestrator.LOOKUP_INTENT_CLARIFY_DE, joinedText(events), "kein Rest ⇒ heutige Rückfrage")
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertEquals(0, cloud.queries.size, "nichts zum Nachschlagen ⇒ kein Call")
    }

    // ── (17)-(18) Konsens-Kontext-Carry (Andi-Live-Repro 2026-07-20 Teil B) ──────
    //
    // Wörtlicher Live-Befund: Andi fragte nach dem Buch in der Hand der Freiheits-
    // statue, das Brain fabulierte (KEIN Angebot). Im ISOLIERTEN Folge-Turn
    // „schaust du online nach?" recherchierte das System die META-FRAGE SELBST
    // („Ich hab online nachgeschaut: Ja. Für aktuelle oder unsichere
    // Informationen kann ich online recherchieren…" — Unsinn) statt der
    // vorherigen Sachfrage. Erst BEIDE Sätze in einem Turn ergaben die echte
    // Recherche. Diese zwei Tests beweisen den Fix rein an der History-Kante
    // (ohne Pending-Store-Abhängigkeit): eine reine Consent-Form MIT Subjekt-
    // Pronomen ("Schaust du online nach?") darf NIE sich selbst als Query
    // eskalieren.

    // ── (17) Mit vorheriger Sachfrage in der History ⇒ DIE wird eskaliert. ──────
    @Test
    fun `Schaust du online nach mit vorheriger Sachfrage eskaliert die Sachfrage, nicht die Meta-Frage`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })
        val history = listOf(
            ChatMessage("user", question),
            ChatMessage("assistant", "Ehrlich, das weiß ich gerade nicht sicher."),
        )

        val events = turn(o, "Schaust du online nach?", history)

        assertEquals(
            listOf(question), cloud.queries,
            "die VORHERIGE Sachfrage geht raus, NICHT die Meta-Frage selbst (\"du online nach\")",
        )
        assertEquals(listOf(""), cloud.snippets, "Egress-Gesetz: NUR die Frage")
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertTrue(joinedText(events).contains(cloudAnswer), "attribuierte Antwort statt Meta-Frage-Unsinn")
    }

    // ── (18) Ohne History ⇒ ehrliche Rückfrage statt die Meta-Frage zu eskalieren. ─
    @Test
    fun `Schaust du online nach ohne History fragt ehrlich zurueck statt die Meta-Frage zu eskalieren`() {
        val brain = FakeBrainPort()
        val cloud = answer()
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        val events = turn(o, "Schaust du online nach?")

        assertEquals(
            TurnOrchestrator.LOOKUP_INTENT_CLARIFY_DE, joinedText(events),
            "die ehrliche Rückfrage — keine Meta-Frage-Eskalation ohne jeden Anhaltspunkt",
        )
        assertEquals(0, brain.callCount.get(), "brain-frei")
        assertEquals(0, cloud.queries.size, "nichts eskaliert — insbesondere NICHT die Meta-Frage selbst")
    }
}
