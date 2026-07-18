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
import de.hoshi.core.port.EscalationSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Der KETTEN-Test von Extended Think S2** — die Consent-Kette über den ECHTEN
 * [TurnOrchestrator] (echte Policies, kein Spring, Muster
 * [TurnOrchestratorFactCoverageChainTest]) mit gemocktem [EscalationPort]:
 *
 *  - Naht A: der [FactCoverageGate.Decision.Deflect]-Zweig setzt bei ERST_FRAGEN
 *    das Pending (Deflection-Phrase unverändert!), eskaliert bei AUTOMATISCH
 *    direkt, bleibt bei AUS/Decke-zu byte-identisch.
 *  - Naht B: ein deterministisches „ja" im Folge-Turn löst mit der GESPEICHERTEN
 *    Original-Query ein (nie mit „ja"); „nein"/Fremd-Turn räumt das Pending;
 *    TTL macht alte Angebote wertlos.
 *  - Eskalations-Turn: Accept-Brücke → Rahmung + Antwort VERBATIM + Quelle;
 *    Unclear/Unavailable/Declined/Timeout enden warm + never-silent;
 *    0 Brain-Calls (max-1-Brain-Call-Vertrag: dieser Turn hat 0).
 *
 * Der Happy-Path-Test schreibt das Event-für-Event-Transkript der Kette nach
 * `build/extended-think-transcript.txt` (Abnahme-Beweis der Consent-Kette;
 * der Live-Smoke gegen die echte API ist Orchestrator-Sache).
 */
class TurnOrchestratorExtendedThinkTest {

    private val question = "Wie hoch ist der Eiffelturm?"
    private val cloudAnswer = "Der Eiffelturm ist 330 Meter hoch."
    private val cloudSource = "Wikipedia"

    /** Die 4 Accept-Brücken-Varianten aus [ResponseFormatter.cloudConsentAccept] (Pool, Anti-Repeat). */
    private val acceptPool = setOf(
        "Klar, einen Moment — ich frag schnell.",
        "Geht klar, kurz schauen…",
        "Mache ich. Moment.",
        "Okay, einen Augenblick.",
    )

    // ── Zählender Fake-Brain ─────────────────────────────────────────────────────
    private class FakeBrainPort : BrainPort {
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

    // ── Aufzeichnender Eskalations-Port (der Mock der Kette) ─────────────────────
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

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
    }

    // ── Echte Pipeline-Nähte (Spring-frei), Extended-Think-Nähte EXPLIZIT ────────
    private fun orchestrator(
        brain: FakeBrainPort,
        escalation: EscalationPort = EscalationPort.NONE,
        pending: PendingLookupPort = PendingLookupPort.NONE,
        mode: () -> EscalationMode = { EscalationMode.AUS },
        timeout: Duration = Duration.ofSeconds(8),
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
                // Leeres Grounding ⇒ FACT_SHORT ohne Deckung ⇒ der Deflect-Zweig feuert.
                grounding = { _, _ -> Mono.just("") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            factCoverage = FactCoverageGate(enabled = true),
            escalation = escalation,
            pendingLookup = pending,
            escalationMode = mode,
            escalationTimeout = timeout,
        )
    }

    private fun turn(o: TurnOrchestrator, text: String): List<ChatEvent> =
        o.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private fun transcript(label: String, events: List<ChatEvent>): String =
        events.joinToString("\n") { ev -> "  [$label] $ev" }

    // ── (1) ERST_FRAGEN: Deflect → „ja" ⇒ genau 1 Lookup mit der Original-Query ──
    @Test
    fun `ERST_FRAGEN - Deflect dann ja - genau 1 Lookup mit Original-Query, verbatim attribuierte Antwort`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort {
            Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, costCents = 0.05))
        }
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        // Turn 1: die Wissensfrage ⇒ ehrliche Deflection (Phrase UNVERÄNDERT — Mira-Veto).
        val deflect = turn(o, question)
        assertEquals(FactCoverageGate.DEFLECT_DE, joinedText(deflect), "Turn 1 ist die unveränderte Deflection")
        assertEquals(0, brain.callCount.get(), "Deflect ruft den Brain nie")
        assertEquals(0, cloud.queries.size, "ERST_FRAGEN eskaliert NICHT ungefragt")

        // Turn 2: „ja" ⇒ Accept-Brücke + Eskalation mit der GESPEICHERTEN Query.
        val ja = turn(o, "ja")
        assertEquals(listOf(question), cloud.queries, "genau 1 Lookup, mit der Original-Query — NIE mit »ja«")
        assertEquals(listOf(""), cloud.snippets, "v1: NUR die Frage geht raus, Schnipsel bewusst leer")
        assertEquals(0, brain.callCount.get(), "der Eskalations-Turn ist brain-frei (0 Brain-Calls)")

        // Event-für-Event: Start / Accept-Brücke / Rahmung+Antwort VERBATIM / Done.
        // Quellen-Struktur-Auftrag 2026-07-21: KEIN dritter „Quelle: …"-TextDelta
        // mehr (die Attribution reist strukturiert am Done, s. unten) — sonst
        // entartete das bei einer echten Web-Search-Quelle zu „Quelle: Quellen:
        // https://…?utm_source=openai." (Andi-Befund).
        assertEquals(4, ja.size, "Start + 2 TextDeltas + Done")
        val start = ja[0] as ChatEvent.Start
        assertEquals("LOCAL", start.provider)
        assertEquals("FACT_SHORT", start.category)
        assertEquals("policy", start.model, "Eskalation ist eine Policy-Direktantwort, kein Brain")
        // S4 Diary: escalated/escalationProvider werden SOFORT am Start gesetzt —
        // der Turn IST ein Eskalations-Versuch, unabhängig vom späteren Ausgang.
        assertTrue(start.escalated, "S4 Diary: der Eskalations-Turn markiert sich selbst am Start")
        assertEquals(
            TurnOrchestrator.LOOKUP_NOTE_PROVIDER,
            start.escalationProvider,
            "S4 Diary: der wirkende Eskalations-Adapter (dieselbe Wahrheit wie LookupNote.provider)",
        )
        assertFalse(start.cacheHit, "ein Eskalations-Turn liest kein Grounding — kein Cache-Hit")
        val accept = (ja[1] as ChatEvent.TextDelta).text
        assertTrue(accept in acceptPool, "die erste TextDelta ist die (endlich benutzte) cloudConsentAccept-Brücke: »$accept«")
        assertEquals(
            TurnOrchestrator.ESCALATION_FRAME_DE + cloudAnswer,
            (ja[2] as ChatEvent.TextDelta).text,
            "Rahmung + Antwort VERBATIM (String-identisch, keine Umformulierung)",
        )
        val done = ja[3] as ChatEvent.Done
        assertEquals(
            0.05,
            done.escalationCostCents,
            "S4 Diary: die ECHTEN Kosten aus EscalationResult.Answer reisen am terminalen Done",
        )
        // H2: Turn↔Note-Verknüpfung — derselbe Hash, mit dem recordLookupNote die
        // Notiz DIESES Turns geschrieben hat (64 Hex-Zeichen, SHA-256 der Original-Query).
        assertEquals(64, done.escalationQueryHash?.length, "SHA-256-Hex der normalisierten ORIGINAL-Query")
        assertEquals(cloudSource, done.escalationSource, "die Quelle reist ehrlich mit ans Done")
        assertNull(done.escalationCapExhausted, "eine echte Answer ist keine Cap-Erschöpfung (nullable Wire-Feld, s. KDoc)")
        // Quellen-Struktur-Auftrag: cloudSource ("Wikipedia") ist eine reine
        // Modell-Selbstauskunft, KEINE echte url_citation ⇒ keine strukturierten
        // Quellen ⇒ das FE zeigt (c)-konform kein Icon.
        assertNull(done.escalationSources, "eine bloße Modell-Attribution ist keine strukturierte Quelle")

        // Turn 3: noch ein „ja" ⇒ Pending war one-shot konsumiert ⇒ normaler Turn.
        val nochmal = turn(o, "ja")
        assertEquals(1, cloud.queries.size, "kein zweiter Lookup — das Angebot war one-shot")
        assertEquals(1, brain.callCount.get(), "»ja« ohne Angebot läuft als normaler Turn zum Brain")

        // Abnahme-Beweis: das Transkript der Kette (Deflect → ja → attribuierte Antwort).
        runCatching {
            Files.createDirectories(Path.of("build"))
            Files.writeString(
                Path.of("build", "extended-think-transcript.txt"),
                "CONSENT-KETTE (ERST_FRAGEN), echter TurnOrchestrator + gemockter EscalationPort:\n" +
                    "Turn 1 — »$question«\n" + transcript("deflect", deflect) + "\n" +
                    "Turn 2 — »ja«\n" + transcript("ja", ja) + "\n" +
                    "Turn 3 — »ja« (ohne Angebot)\n" + transcript("normal", nochmal) + "\n",
            )
        }
    }

    // ── (1b) Quellen-Struktur-Auftrag 2026-07-21: echte url_citations reisen ─────
    //        strukturiert am Done, NICHT mehr als Text-Anhang (Andi-Befund:
    //        „…Quelle: Quellen: https://…?utm_source=openai." in der Sprache).
    @Test
    fun `echte Web-Search-Quellen reisen strukturiert am Done - der Antworttext bleibt frei von URL und Quelle-Anhang`() {
        val brain = FakeBrainPort()
        val refs = listOf(
            EscalationSourceRef(title = "Tokyo Metropolitan Government", url = "https://www.metro.tokyo.lg.jp/english/"),
        )
        val cloud = RecordingEscalationPort {
            Mono.just(
                EscalationResult.Answer(
                    "Tokio hat 14.299.726 Einwohner.",
                    "Quellen: https://www.metro.tokyo.lg.jp/english/",
                    costCents = 0.05,
                    sources = refs,
                ),
            )
        }
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        turn(o, "Wie viele Einwohner hat Tokio?")
        val ja = turn(o, "ja")

        val text = joinedText(ja)
        assertTrue(text.contains("14.299.726 Einwohner"), "die Faktenaussage bleibt VERBATIM")
        assertFalse(text.contains("http"), "der Antworttext traegt NIE eine URL")
        assertFalse(text.contains("Quelle"), "der Antworttext traegt KEINEN Quellen-Anhang mehr (TTS spricht quellenfrei)")

        val done = ja.last() as ChatEvent.Done
        assertEquals(refs, done.escalationSources, "die echten Citations reisen strukturiert am Done — fuers FE-i-Icon")
        assertEquals("Quellen: https://www.metro.tokyo.lg.jp/english/", done.escalationSource, "der Diary-String bleibt als Metadatum erhalten")
    }

    // ── (2) ERST_FRAGEN: Deflect → „nein" ⇒ 0 Lookups, Pending geräumt ───────────
    @Test
    fun `ERST_FRAGEN - Deflect dann nein - 0 Lookups und das Pending ist geraeumt`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort { Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, 0.05)) }
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        turn(o, question)
        turn(o, "nein") // Fremd-Turn: räumt das Pending (one-shot consume), läuft normal.
        assertEquals(0, cloud.queries.size, "»nein« eskaliert nie")

        turn(o, "ja") // das Angebot ist weg ⇒ normaler Turn, keine Alt-Einlösung.
        assertEquals(0, cloud.queries.size, "ein spätes »ja« nach »nein« löst NICHTS mehr ein")
        assertEquals(2, brain.callCount.get(), "»nein« und das späte »ja« liefen als normale Turns")
    }

    // ── (3) „ja" ohne vorheriges Angebot ⇒ normaler Turn ─────────────────────────
    @Test
    fun `ja ohne vorheriges Angebot - normaler Turn, 0 Lookups`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort { Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, 0.05)) }
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.ERST_FRAGEN })

        turn(o, "ja")
        assertEquals(0, cloud.queries.size, "ohne offenes Angebot ist »ja« nie ein Consent")
        assertEquals(1, brain.callCount.get(), "der Turn läuft normal (Routing → Brain)")
    }

    // ── (4) TTL abgelaufen ⇒ normaler Turn ───────────────────────────────────────
    @Test
    fun `TTL abgelaufen - ja nach 121s ist ein normaler Turn`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort { Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, 0.05)) }
        val clock = MutableClock(Instant.now())
        val o = orchestrator(
            brain, cloud,
            InMemoryPendingLookupStore(clock = clock),
            mode = { EscalationMode.ERST_FRAGEN },
        )

        turn(o, question)
        clock.advanceSeconds(121)
        turn(o, "ja")
        assertEquals(0, cloud.queries.size, "ein Angebot von vorhin ist kein Consent von jetzt (TTL 120 s)")
        assertEquals(1, brain.callCount.get(), "das späte »ja« läuft als normaler Turn")
    }

    // ── (5) AUTOMATISCH ⇒ Lookup ohne Rückfrage ──────────────────────────────────
    @Test
    fun `AUTOMATISCH - der Deflect-Zweig eskaliert direkt, ohne Rueckfrage und ohne Brain`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort { Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, 0.05)) }
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.AUTOMATISCH })

        val events = turn(o, question)
        assertEquals(listOf(question), cloud.queries, "AUTOMATISCH: genau 1 Lookup, direkt mit der Frage")
        assertEquals(0, brain.callCount.get(), "kein Brain-Call — Kosten UND Konfabulation gespart")
        val text = joinedText(events)
        assertTrue(FactCoverageGate.DEFLECT_DE !in text, "keine Deflection mehr — der Ausgang ersetzt sie")
        assertTrue(text.contains(TurnOrchestrator.ESCALATION_FRAME_DE + cloudAnswer), "attribuierte verbatim-Antwort")
    }

    // ── (6) AUS ⇒ byte-identisch heute + ehrlicher Setting-Hinweis auf „ja" ──────
    @Test
    fun `AUS - der Deflect-Turn ist byte-identisch zum heutigen Verhalten`() {
        val baselineBrain = FakeBrainPort()
        val baseline = orchestrator(baselineBrain) // reine Defaults: NONE/NONE/AUS — „heute"
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort { Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, 0.05)) }
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.AUS })

        assertEquals(
            turn(baseline, question),
            turn(o, question),
            "AUS: die emittierten Events des Deflect-Turns sind byte-identisch zu heute",
        )
        assertEquals(0, cloud.queries.size, "AUS eskaliert nie")
    }

    @Test
    fun `AUS - ja nach Deflect - ehrlich-warmer Hinweis aufs Setting, KEIN Call`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort { Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, 0.05)) }
        val o = orchestrator(brain, cloud, InMemoryPendingLookupStore(), mode = { EscalationMode.AUS })

        turn(o, question)
        val ja = turn(o, "ja")
        assertEquals(TurnOrchestrator.EXTENDED_THINK_OFF_HINT_DE, joinedText(ja), "der ehrliche Setting-Hinweis")
        assertEquals(0, cloud.queries.size, "bei AUS geht NIE ein Call raus")
        assertEquals(0, brain.callCount.get(), "der Hinweis ist eine brain-freie Direktantwort")
    }

    @Test
    fun `Decke zu (reine Defaults) - ja nach Deflect laeuft als normaler Turn (byte-neutral)`() {
        val brain = FakeBrainPort()
        val o = orchestrator(brain) // NONE-Store: offer ist no-op, consume immer null.

        turn(o, question)
        turn(o, "ja")
        assertEquals(1, brain.callCount.get(), "ohne Wiring bleibt »ja« ein normaler Turn — exakt heute")
    }

    // ── (8) Unclear/Unavailable/Declined/Timeout/Fehler ⇒ warm + never-silent ────
    @Test
    fun `Unavailable - warme Phrase, kein Crash, Done am Ende`() {
        val o = orchestrator(
            FakeBrainPort(),
            RecordingEscalationPort { Mono.just(EscalationResult.Unavailable) },
            InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
        )
        turn(o, question)
        val ja = turn(o, "ja")
        assertTrue(
            joinedText(ja).contains(TurnOrchestrator.ESCALATION_UNAVAILABLE_DE),
            "Unavailable endet in der ehrlichen warmen Phrase",
        )
        assertTrue(ja.last() is ChatEvent.Done, "never-silent")
        assertTrue((ja.first() as ChatEvent.Start).escalated, "der Versuch fand statt, unabhängig vom Ausgang")
        val done = ja.last() as ChatEvent.Done
        assertNull(done.escalationCostCents, "Unavailable lieferte keine Answer ⇒ keine Kosten (nie eine erfundene 0.0)")
        // H2: kein Answer ⇒ keine Notiz ⇒ keine Turn↔Note-Verknüpfung.
        assertNull(done.escalationQueryHash, "Unavailable schrieb keine Notiz")
        assertNull(done.escalationSource, "Unavailable schrieb keine Notiz")
        // H3: ein echter Netzfehler ist KEINE Cap-Erschöpfung (nullable Wire-Feld, s. KDoc).
        assertNull(done.escalationCapExhausted, "Unavailable ist kein Cap-Fall")
    }

    // ── (8b) H3: Tages-Cap erreicht ⇒ EIGENE ehrliche Phrase, NICHT Unavailable ──
    @Test
    fun `CapExhausted - eigene ehrliche Phrase, NICHT die Unavailable-Phrase, Diary unterscheidbar`() {
        val o = orchestrator(
            FakeBrainPort(),
            RecordingEscalationPort { Mono.just(EscalationResult.CapExhausted) },
            InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
        )
        turn(o, question)
        val ja = turn(o, "ja")
        val text = joinedText(ja)
        assertTrue(text.contains(TurnOrchestrator.ESCALATION_CAP_EXHAUSTED_DE), "H3: die eigene Cap-Phrase")
        assertFalse(
            text.contains(TurnOrchestrator.ESCALATION_UNAVAILABLE_DE),
            "H3: NIE die Netzfehler-Phrase bei Cap-Erschöpfung",
        )
        assertTrue(ja.last() is ChatEvent.Done, "never-silent")
        val done = ja.last() as ChatEvent.Done
        assertEquals(true, done.escalationCapExhausted, "das Diary unterscheidet Cap von Netzfehler")
        assertNull(done.escalationCostCents, "CapExhausted lieferte keine Answer ⇒ keine Kosten")
        assertNull(done.escalationQueryHash, "CapExhausted schrieb keine Notiz")
        assertNull(done.escalationSource, "CapExhausted schrieb keine Notiz")
    }

    @Test
    fun `Unclear - ehrliche keine-sichere-Antwort-Phrase`() {
        val o = orchestrator(
            FakeBrainPort(),
            RecordingEscalationPort { Mono.just(EscalationResult.Unclear) },
            InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
        )
        turn(o, question)
        assertTrue(joinedText(turn(o, "ja")).contains(TurnOrchestrator.ESCALATION_UNCLEAR_DE))
    }

    @Test
    fun `Declined - warme bleibt-bei-uns-Phrase ohne Klartext-Grund`() {
        val o = orchestrator(
            FakeBrainPort(),
            RecordingEscalationPort { Mono.just(EscalationResult.Declined("memory-reference")) },
            InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
        )
        turn(o, question)
        val text = joinedText(turn(o, "ja"))
        assertTrue(text.contains(TurnOrchestrator.ESCALATION_DECLINED_DE))
        assertTrue("memory-reference" !in text, "der Audit-Grund wird NIE gesprochen")
    }

    @Test
    fun `Timeout - haengender Lookup endet im warmen Unavailable-Pfad (never-silent)`() {
        val o = orchestrator(
            FakeBrainPort(),
            RecordingEscalationPort { Mono.never() }, // hängt — der Timeout muss retten
            InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
            timeout = Duration.ofMillis(100),
        )
        turn(o, question)
        val ja = turn(o, "ja")
        assertTrue(joinedText(ja).contains(TurnOrchestrator.ESCALATION_UNAVAILABLE_DE))
        assertTrue(ja.last() is ChatEvent.Done, "never-silent auch bei hängendem Port")
    }

    @Test
    fun `Port-Fehler - Mono-error endet im warmen Unavailable-Pfad, kein Crash`() {
        val o = orchestrator(
            FakeBrainPort(),
            RecordingEscalationPort { Mono.error(IllegalStateException("kaputt")) },
            InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
        )
        turn(o, question)
        val ja = turn(o, "ja")
        assertTrue(joinedText(ja).contains(TurnOrchestrator.ESCALATION_UNAVAILABLE_DE))
        assertTrue(ja.last() is ChatEvent.Done)
    }

    // ── Marker-Hygiene: die verbatim-Antwort läuft NICHT durch die Brain-Naht ────
    @Test
    fun `Guillemet-Marker in der Cloud-Antwort werden gestrippt (Marker-Hygiene selbst sichergestellt)`() {
        val o = orchestrator(
            FakeBrainPort(),
            RecordingEscalationPort {
                Mono.just(EscalationResult.Answer("Der Eiffelturm ist «330 Meter» hoch.", cloudSource, 0.05))
            },
            InMemoryPendingLookupStore(),
            mode = { EscalationMode.ERST_FRAGEN },
        )
        turn(o, question)
        val text = joinedText(turn(o, "ja"))
        assertTrue(text.contains("330 Meter"), "die Faktenaussage bleibt")
        assertTrue('«' !in text && '»' !in text, "Vertrags-Marker werden nie gesprochen")
    }
}
