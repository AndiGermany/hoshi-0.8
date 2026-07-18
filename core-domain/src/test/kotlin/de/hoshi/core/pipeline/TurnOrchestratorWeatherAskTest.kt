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

/**
 * **Der KETTEN-Test der Wetter-Orts-Nachfrage (Wetter S3)** — über den ECHTEN
 * [TurnOrchestrator] (echte Policies, kein Spring, Muster
 * [TurnOrchestratorExtendedThinkTest]) mit gefaktem [WeatherLocationAskPort]:
 *
 *  - Wetter-Frage OHNE konfigurierten Ort ⇒ deterministische warme Nachfrage
 *    ([TurnOrchestrator.WEATHER_LOCATION_ASK_DE]) + Pending, KEIN Brain-Call.
 *  - Folge-Turn „Duisburg" ⇒ Geocode+Store (»Ich merk's mir« wird wahr) und
 *    DIREKT die Wetter-Antwort der GEMERKTEN Ursprungs-Frage (der Brain sieht
 *    die Original-Frage, NIE den bloßen Orts-Namen).
 *  - „nein"/Fremd-Turn ⇒ normaler Turn, Pending verfällt (one-shot).
 *  - Geocode ohne Treffer ⇒ normaler Turn mit dem gesagten Text.
 *  - Prod-Fall (Ort konfiguriert ⇒ needsLocation=false) ⇒ NIE eine Nachfrage,
 *    Events byte-gleich zum Default-Orchestrator.
 *  - Kollision mit einem offenen Extended-Think-Angebot ⇒ die Orts-Kette löst
 *    NIE ein (Ketten sauber getrennt), beide Pendings werden geräumt.
 */
class TurnOrchestratorWeatherAskTest {

    private val weatherQuestion = "Wie wird das Wetter morgen?"

    // ── Aufzeichnender Fake-Brain ────────────────────────────────────────────────
    private class RecordingBrain : BrainPort {
        val prompts = mutableListOf<String>()
        val systemPrompts = mutableListOf<String>()
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
            prompts += prompt
            systemPrompts += systemPrompt
            return Flux.just(LlmDelta("Brain-Antwort."))
        }
    }

    /**
     * Fake der Orts-Naht — bildet den ECHTEN Adapter-Vertrag nach: eine kleine
     * Geocode-Tabelle + der `configured`-Übergang (nach [resolveAndStore] ist der
     * Store nicht mehr leer ⇒ [needsLocation] wird false ⇒ strukturell keine
     * zweite Nachfrage beim Re-Dispatch der Ursprungs-Frage).
     */
    private class FakeWeatherAsk(
        var configured: Boolean = false,
        private val geocode: Map<String, String> = mapOf("Duisburg" to "Duisburg"),
    ) : WeatherLocationAskPort {
        val resolveCalls = mutableListOf<String>()
        val storedLabels = mutableListOf<String>()
        override fun needsLocation(query: String, category: RouteCategory): Boolean =
            !configured && query.lowercase().let { "wetter" in it || "weather" in it }
        override fun resolveAndStore(place: String): Mono<String> {
            resolveCalls += place
            val label = geocode[place] ?: return Mono.empty()
            return Mono.fromCallable {
                storedLabels += label
                configured = true
                label
            }
        }
    }

    // ── Aufzeichnender Eskalations-Port (für den Kollisions-Test) ────────────────
    private class RecordingEscalationPort : EscalationPort {
        val queries = mutableListOf<String>()
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
            queries += query
            return Mono.just(EscalationResult.Answer("330 Meter.", "Wikipedia", costCents = 0.05))
        }
    }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
    }

    /** Grounding-Fake: liefert den Wetter-Block NUR, wenn der Fake-Store konfiguriert ist. */
    private fun groundingFor(ask: FakeWeatherAsk): GroundingPort = GroundingPort { query, _ ->
        Mono.just(
            if (ask.configured && query.lowercase().contains("wetter")) {
                "\n\nHINTERGRUND: Wetter Duisburg morgen: 12 bis 18 Grad."
            } else {
                ""
            },
        )
    }

    // ── Echte Pipeline-Nähte (Spring-frei), Wetter-Nähte EXPLIZIT ────────────────
    private fun orchestrator(
        brain: BrainPort,
        weatherAsk: WeatherLocationAskPort = WeatherLocationAskPort.NONE,
        pendingLocation: PendingLocationQuestionPort = PendingLocationQuestionPort.NONE,
        pendingLookup: PendingLookupPort = PendingLookupPort.NONE,
        escalation: EscalationPort = EscalationPort.NONE,
        mode: () -> EscalationMode = { EscalationMode.AUS },
        grounding: GroundingPort = GroundingPort { _, _ -> Mono.just("") },
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
                grounding = grounding,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            escalation = escalation,
            pendingLookup = pendingLookup,
            escalationMode = mode,
            weatherAsk = weatherAsk,
            pendingLocation = pendingLocation,
        )
    }

    private fun turn(o: TurnOrchestrator, text: String, language: Language = Language.DE): List<ChatEvent> =
        o.handle(ChatRequest(text = text, language = language)).collectList().block(Duration.ofSeconds(5))!!

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── (1) Die Kette: Wetter-Frage ohne Ort → Nachfrage → „Duisburg" → Antwort ──
    @Test
    fun `Kette - Nachfrage dann Duisburg - Ort gespeichert und die URSPRUNGS-Frage beantwortet`() {
        val brain = RecordingBrain()
        val ask = FakeWeatherAsk()
        val o = orchestrator(
            brain,
            weatherAsk = ask,
            pendingLocation = InMemoryPendingLocationQuestionStore(),
            grounding = groundingFor(ask),
        )

        // Turn 1: Wetter-Frage ohne Ort ⇒ warme deterministische Nachfrage, KEIN Brain.
        val turn1 = turn(o, weatherQuestion)
        assertEquals(TurnOrchestrator.WEATHER_LOCATION_ASK_DE, joinedText(turn1), "die wörtliche Nachfrage")
        assertTrue(brain.prompts.isEmpty(), "die Nachfrage ist brain-frei")
        val start1 = turn1.first() as ChatEvent.Start
        assertEquals("policy", start1.model, "Policy-Direktantwort, kein Brain")

        // Turn 2: „Duisburg" ⇒ Geocode+Store + DIREKT die Antwort der Ursprungs-Frage.
        val turn2 = turn(o, "Duisburg")
        assertEquals(listOf("Duisburg"), ask.storedLabels, "der Ort ist GESPEICHERT (wie der Settings-PUT)")
        assertEquals(listOf(weatherQuestion), brain.prompts, "der Brain sieht die URSPRUNGS-Frage, nie »Duisburg«")
        assertTrue(
            brain.systemPrompts.single().contains("Wetter Duisburg"),
            "das Grounding des Folge-Turns nutzt den GESPEICHERTEN Ort",
        )
        assertEquals(
            "Duisburg" + TurnOrchestrator.WEATHER_LOCATION_SAVED_SUFFIX_DE + "Brain-Antwort.",
            joinedText(turn2),
            "warme Bestätigung + die Wetter-Antwort der gemerkten Frage",
        )
        assertTrue(turn2.first() is ChatEvent.Start, "Event-Ordnung: Start zuerst")
        assertEquals(
            "Duisburg" + TurnOrchestrator.WEATHER_LOCATION_SAVED_SUFFIX_DE,
            (turn2[1] as ChatEvent.TextDelta).text,
            "die Bestätigung kommt direkt hinter dem Start",
        )
        assertTrue(turn2.last() is ChatEvent.Done, "never-silent: Done am Ende")
        assertEquals(1, turn2.count { it is ChatEvent.Start }, "genau EIN Start pro Turn")
        assertEquals(1, turn2.count { it is ChatEvent.Done }, "genau EIN Done pro Turn")

        // Turn 3: nächste Wetter-Frage ⇒ Store konfiguriert ⇒ KEINE zweite Nachfrage.
        val turn3 = turn(o, weatherQuestion)
        assertTrue(
            TurnOrchestrator.WEATHER_LOCATION_ASK_DE !in joinedText(turn3),
            "mit gespeichertem Ort wird nie wieder nachgefragt",
        )
        assertEquals(2, brain.prompts.size, "die Folge-Frage läuft als normaler Brain-Turn")
    }

    // ── (2) „nein"/Fremd-Turn ⇒ normaler Turn, Pending verfällt one-shot ─────────
    @Test
    fun `nein nach Nachfrage - normaler Turn und das Pending ist geraeumt`() {
        val brain = RecordingBrain()
        val ask = FakeWeatherAsk()
        val o = orchestrator(
            brain,
            weatherAsk = ask,
            pendingLocation = InMemoryPendingLocationQuestionStore(),
            grounding = groundingFor(ask),
        )

        turn(o, weatherQuestion)
        turn(o, "nein") // Fremd-Turn: räumt die Nachfrage (one-shot consume), läuft normal.
        assertEquals(listOf("nein"), brain.prompts, "»nein« läuft als normaler Turn zum Brain")
        assertTrue(ask.resolveCalls.isEmpty(), "»nein« wird NIE geocodet")

        turn(o, "Duisburg") // die Nachfrage ist weg ⇒ ein später Orts-Name löst NICHTS ein.
        assertEquals(listOf("nein", "Duisburg"), brain.prompts, "spätes »Duisburg« ist ein normaler Turn")
        assertTrue(ask.resolveCalls.isEmpty() && ask.storedLabels.isEmpty(), "kein Geocode, kein Store")
    }

    // ── (3) Geocode ohne Treffer ⇒ normaler Turn mit dem GESAGTEN Text ───────────
    @Test
    fun `kein Geocode-Treffer - der gesagte Text laeuft als normaler Turn, nichts gespeichert`() {
        val brain = RecordingBrain()
        val ask = FakeWeatherAsk()
        val o = orchestrator(
            brain,
            weatherAsk = ask,
            pendingLocation = InMemoryPendingLocationQuestionStore(),
            grounding = groundingFor(ask),
        )

        turn(o, weatherQuestion)
        val t = turn(o, "Xyzzy")
        assertEquals(listOf("Xyzzy"), ask.resolveCalls, "der Orts-Kandidat wurde probiert")
        assertTrue(ask.storedLabels.isEmpty(), "ohne Treffer wird NIE gespeichert")
        assertEquals(listOf("Xyzzy"), brain.prompts, "kein Treffer ⇒ normaler Turn mit dem gesagten Text")
        assertEquals("Brain-Antwort.", joinedText(t))
        assertTrue(t.last() is ChatEvent.Done, "never-silent")
    }

    // ── (4) Prod-Fall: Ort konfiguriert ⇒ NIE Nachfrage, byte-gleiche Events ─────
    @Test
    fun `Prod-Fall - needsLocation false - Events byte-gleich zum Default-Orchestrator`() {
        val ask = FakeWeatherAsk(configured = true) // Prod: echte Seeds ⇒ nie nachfragen.
        val grounding = groundingFor(ask)
        val baseline = orchestrator(RecordingBrain(), grounding = grounding) // reine NONE-Defaults — „heute".
        val prod = orchestrator(
            RecordingBrain(),
            weatherAsk = ask,
            pendingLocation = InMemoryPendingLocationQuestionStore(),
            grounding = grounding,
        )

        val baselineEvents = turn(baseline, weatherQuestion)
        val prodEvents = turn(prod, weatherQuestion)
        assertEquals(baselineEvents, prodEvents, "mit konfiguriertem Ort sind die Events byte-identisch")
        assertTrue(ask.resolveCalls.isEmpty(), "es wird NIE geocodet")
        assertTrue(TurnOrchestrator.WEATHER_LOCATION_ASK_DE !in joinedText(prodEvents), "NIE eine Nachfrage in Prod")
    }

    // ── (5) Byte-neutraler Default: NONE-Ports ⇒ Wetter-Frage ist normaler Turn ──
    @Test
    fun `Decke zu (reine Defaults) - Wetter-Frage laeuft als normaler Turn (byte-neutral)`() {
        val brain = RecordingBrain()
        val o = orchestrator(brain) // NONE-Ports: needsLocation konstant false.
        val events = turn(o, weatherQuestion)
        assertEquals(listOf(weatherQuestion), brain.prompts, "ohne Wiring bleibt die Wetter-Frage ein Brain-Turn")
        assertEquals("Brain-Antwort.", joinedText(events))
    }

    // ── (6) Kollision mit Extended-Think: die Orts-Kette löst NIE ein ────────────
    @Test
    fun `Kollision - offenes Extended-Think-Angebot - Duisburg loest NIE die Orts-Kette ein`() {
        val brain = RecordingBrain()
        val ask = FakeWeatherAsk()
        val lookupStore = InMemoryPendingLookupStore()
        val locStore = InMemoryPendingLocationQuestionStore()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(
            brain,
            weatherAsk = ask,
            pendingLocation = locStore,
            pendingLookup = lookupStore,
            escalation = cloud,
            mode = { EscalationMode.ERST_FRAGEN },
            grounding = groundingFor(ask),
        )
        // Beide Pendings direkt am Store gesetzt (der Orchestrator selbst erzeugt den
        // Zustand nie — die Wand wird trotzdem bewiesen: Ketten sauber getrennt).
        lookupStore.offer(PendingLookupPort.LOCAL_KEY, PendingLookup("Wie hoch ist der Eiffelturm?", Language.DE))
        locStore.offer(PendingLookupPort.LOCAL_KEY, PendingLocationQuestion(weatherQuestion, Language.DE))

        turn(o, "Duisburg")
        assertTrue(ask.resolveCalls.isEmpty(), "NIE eine Orts-Einlösung, solange ein Extended-Think-Angebot offen war")
        assertTrue(cloud.queries.isEmpty(), "»Duisburg« ist keine Affirmation — keine Eskalation")
        assertEquals(listOf("Duisburg"), brain.prompts, "der Turn läuft normal")
        assertNull(lookupStore.consume(PendingLookupPort.LOCAL_KEY), "das Extended-Think-Angebot ist geräumt (one-shot)")
        assertNull(locStore.consume(PendingLookupPort.LOCAL_KEY), "die Orts-Nachfrage ist geräumt (kein Alt-Köder)")
    }

    @Test
    fun `Kollision - ja loest Extended-Think ein und raeumt auch die Orts-Nachfrage`() {
        val brain = RecordingBrain()
        val ask = FakeWeatherAsk()
        val lookupStore = InMemoryPendingLookupStore()
        val locStore = InMemoryPendingLocationQuestionStore()
        val cloud = RecordingEscalationPort()
        val o = orchestrator(
            brain,
            weatherAsk = ask,
            pendingLocation = locStore,
            pendingLookup = lookupStore,
            escalation = cloud,
            mode = { EscalationMode.ERST_FRAGEN },
            grounding = groundingFor(ask),
        )
        lookupStore.offer(PendingLookupPort.LOCAL_KEY, PendingLookup("Wie hoch ist der Eiffelturm?", Language.DE))
        locStore.offer(PendingLookupPort.LOCAL_KEY, PendingLocationQuestion(weatherQuestion, Language.DE))

        turn(o, "ja")
        assertEquals(listOf("Wie hoch ist der Eiffelturm?"), cloud.queries, "»ja« löst die Extended-Think-Kette ein")
        assertTrue(ask.resolveCalls.isEmpty(), "die Orts-Kette bleibt unberührt")
        assertNull(locStore.consume(PendingLookupPort.LOCAL_KEY), "auch die Orts-Nachfrage ist geräumt — kein Alt-Köder")
    }

    // ── (7) TTL: eine Nachfrage von vorhin fängt keinen Orts-Namen von jetzt ─────
    @Test
    fun `TTL abgelaufen - Duisburg nach 121s ist ein normaler Turn`() {
        val brain = RecordingBrain()
        val ask = FakeWeatherAsk()
        val clock = MutableClock(Instant.now())
        val o = orchestrator(
            brain,
            weatherAsk = ask,
            pendingLocation = InMemoryPendingLocationQuestionStore(clock = clock),
            grounding = groundingFor(ask),
        )

        turn(o, weatherQuestion)
        clock.advanceSeconds(121)
        turn(o, "Duisburg")
        assertTrue(ask.resolveCalls.isEmpty(), "eine Nachfrage von vorhin fängt keinen Orts-Namen von jetzt")
        assertEquals(listOf("Duisburg"), brain.prompts, "der späte Orts-Name läuft als normaler Turn")
    }

    // ── (8) Sprache: die EN-Nachfrage folgt der Turn-Sprache ─────────────────────
    @Test
    fun `EN-Turn - die Nachfrage kommt auf Englisch`() {
        val brain = RecordingBrain()
        val ask = FakeWeatherAsk()
        val o = orchestrator(
            brain,
            weatherAsk = ask,
            pendingLocation = InMemoryPendingLocationQuestionStore(),
            grounding = groundingFor(ask),
        )
        val events = turn(o, "What's the weather tomorrow?", language = Language.EN)
        assertEquals(TurnOrchestrator.WEATHER_LOCATION_ASK_EN, joinedText(events))
    }
}
