package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist die drei strukturellen Invarianten des [TurnOrchestrator] mit einem
 * zählenden [FakeBrainPort] — ohne den Live-Brain:
 *  (a) GENAU 1 Brain-Call pro normalem Turn.
 *  (b) Never-Silent: leerer Brain-Stream UND Brain-Fehler → trotzdem warme
 *      TextDelta + terminales Done (nie stille Sackgasse).
 *  (c) Abstention (HonestyGate.Refuse) ruft den Brain GAR NICHT.
 */
class TurnOrchestratorTest {

    // ── Zählender Fake-Brain ─────────────────────────────────────────────────
    private class FakeBrainPort(
        private val deltas: List<String> = listOf("Hallo, schön dass du da bist!"),
        private val error: Throwable? = null,
    ) : BrainPort {
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
        ): Flux<de.hoshi.core.dto.LlmDelta> {
            callCount.incrementAndGet()
            if (error != null) return Flux.error(error)
            return Flux.fromIterable(deltas).map { de.hoshi.core.dto.LlmDelta(it) }
        }
    }

    // ── Honesty-Signal-Fakes (konservativ konfigurierbar) ────────────────────
    private fun honestyGate(
        weakDomain: Boolean = false,
        onlineRequest: Boolean = false,
        cloudEnabled: Boolean = false,
    ) = HonestyGate(
        weakDomain = WeakDomainSignal { weakDomain },
        onlineRequest = OnlineRequestSignal { onlineRequest },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { cloudEnabled },
    )

    private fun routingPolicy(category: RouteCategory = RouteCategory.SMALLTALK) =
        RoutingPolicy(
            keywordRouter = KeywordRouter { RouteDecision(category, RouteProvider.LOCAL, "fake") },
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
        brain: FakeBrainPort,
        honesty: HonestyGate = honestyGate(),
        category: RouteCategory = RouteCategory.SMALLTALK,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = routingPolicy(category),
            honesty = honesty,
            promptAssembler = assembler(persona),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
        )
    }

    private fun run(o: TurnOrchestrator, text: String, language: Language = Language.DE): List<ChatEvent> =
        o.handle(ChatRequest(text = text, language = language)).collectList().block(Duration.ofSeconds(5))!!

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── (a) genau 1 Brain-Call ───────────────────────────────────────────────
    @Test
    fun `normaler Turn ruft den Brain genau einmal und streamt dessen Text`() {
        val brain = FakeBrainPort(listOf("Hallo, ", "schön dich zu hören!"))
        val events = run(orchestrator(brain), "Sag in einem warmen Satz Hallo.")

        assertEquals(1, brain.callCount.get(), "genau EIN Brain-Call pro Turn")
        assertTrue(events.first() is ChatEvent.Start, "Turn beginnt mit Start")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hallo, schön dich zu hören!", text, "der Brain-Text fließt unverändert durch")
    }

    // ── (b) Never-Silent: leerer Brain-Stream → modus-spezifische LEER-Phrase (DE) ──
    @Test
    fun `leerer Brain-Stream liefert die warme LEER-Phrase und Done ohne Fehler-Event`() {
        val brain = FakeBrainPort(deltas = emptyList())
        val events = run(orchestrator(brain), "Erzähl mir was.")

        assertEquals(1, brain.callCount.get())
        val texts = events.filterIsInstance<ChatEvent.TextDelta>()
        assertTrue(texts.isNotEmpty(), "warme Fallback-Phrase erwartet")
        assertTrue(texts.first().text.isNotBlank(), "Fallback darf nie leer sein")
        assertEquals(
            TurnOrchestrator.EMPTY_FALLBACK_DE,
            joinedText(events),
            "leerer Stream → LEER-Phrase (nicht die FEHLER-Phrase)",
        )
        assertTrue(events.none { it is ChatEvent.Error }, "leerer Stream ist KEIN Fehler — kein Error-Event")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (b) Never-Silent: Brain wirft → modus-spezifische FEHLER-Phrase (DE) ──
    @Test
    fun `Brain-Fehler vor Text liefert die warme FEHLER-Phrase und Done ohne separates Error-Event`() {
        val brain = FakeBrainPort(error = RuntimeException("Sidecar weg"))
        val events = run(orchestrator(brain), "Wie geht's dir?")

        assertEquals(1, brain.callCount.get())
        val texts = events.filterIsInstance<ChatEvent.TextDelta>()
        assertTrue(texts.isNotEmpty() && texts.first().text.isNotBlank(), "warme Phrase trotz Fehler")
        assertEquals(
            TurnOrchestrator.ERROR_FALLBACK_DE,
            joinedText(events),
            "Fehler vor Text → FEHLER-Phrase (nicht die LEER-Phrase)",
        )
        assertTrue(events.none { it is ChatEvent.Error }, "kein Doppel-Render: kein separates Error-Event")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (b) Differenzierung: LEER- und FEHLER-Phrase sind NICHT dieselbe ──────
    @Test
    fun `LEER- und FEHLER-Phrase unterscheiden sich (warm differenziert nach Modus)`() {
        val emptyText = joinedText(run(orchestrator(FakeBrainPort(deltas = emptyList())), "Erzähl mir was."))
        val errorText = joinedText(run(orchestrator(FakeBrainPort(error = RuntimeException("weg"))), "Wie geht's?"))

        assertTrue(emptyText != errorText, "leerer Stream und Fehler dürfen NICHT dieselbe Phrase geben")
        assertEquals(TurnOrchestrator.EMPTY_FALLBACK_DE, emptyText)
        assertEquals(TurnOrchestrator.ERROR_FALLBACK_DE, errorText)
    }

    // ── (b/EN) Never-Silent: dieselben zwei Pfade auf Englisch ────────────────
    @Test
    fun `leerer Brain-Stream liefert die englische LEER-Phrase bei EN-Turn`() {
        val events = run(orchestrator(FakeBrainPort(deltas = emptyList())), "Tell me something.", Language.EN)

        assertEquals(TurnOrchestrator.EMPTY_FALLBACK_EN, joinedText(events), "EN-Turn → englische LEER-Phrase")
        assertTrue(events.none { it is ChatEvent.Error }, "leerer Stream ist kein Fehler")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    @Test
    fun `Brain-Fehler vor Text liefert die englische FEHLER-Phrase bei EN-Turn`() {
        val events = run(orchestrator(FakeBrainPort(error = RuntimeException("Sidecar gone"))), "How are you?", Language.EN)

        assertEquals(TurnOrchestrator.ERROR_FALLBACK_EN, joinedText(events), "EN-Turn → englische FEHLER-Phrase")
        assertTrue(events.none { it is ChatEvent.Error }, "kein separates Error-Event")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (c) Abstention ruft den Brain NICHT ──────────────────────────────────
    @Test
    fun `Abstention bei Cloud-aus refuse ruft den Brain gar nicht`() {
        val brain = FakeBrainPort()
        // weakDomain=true + cloud aus → HonestyGate.Verdict.Refuse → KEIN Brain.
        val o = orchestrator(brain, honesty = honestyGate(weakDomain = true, cloudEnabled = false))
        val events = run(o, "Wie backe ich einen Kuchen?")

        assertEquals(0, brain.callCount.get(), "Abstention darf den Brain NIE rufen")
        val texts = events.filterIsInstance<ChatEvent.TextDelta>()
        assertTrue(texts.isNotEmpty() && texts.first().text.isNotBlank(), "ehrliche warme Absage erwartet")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    // ── (c2) AskConsent (Cloud an) ruft den Brain ebenfalls nicht ────────────
    @Test
    fun `AskConsent bei Cloud-an ruft den Brain nicht und fragt warm nach`() {
        val brain = FakeBrainPort()
        val o = orchestrator(brain, honesty = honestyGate(weakDomain = true, cloudEnabled = true))
        val events = run(o, "Wie backe ich einen Kuchen?")

        assertEquals(0, brain.callCount.get(), "Consent-Frage ruft den Brain nicht")
        assertTrue(events.filterIsInstance<ChatEvent.TextDelta>().first().text.isNotBlank())
        assertTrue(events.last() is ChatEvent.Done)
    }

    // ── Guillemet-Strip (Wand statt Tapete): Vertrags-Marker nie an FE/TTS ────
    // Live-Befund 2026-07-02: das 4B schreibt die WikiNumberContract-Marker »«
    // aus dem Prompt mit („über die »330 Meter«") — die Prompt-Regel „Zeichen
    // NICHT mitschreiben" hält es nicht. Die deterministische Wand sitzt an der
    // EINEN Brain-Prosa→TextDelta-Naht (brainTurn), vor ALLEN Kanälen (FE+TTS).
    @Test
    fun `Guillemets aus Brain-Deltas werden ersatzlos gestrippt bevor sie FE und TTS erreichen`() {
        val brain = FakeBrainPort(listOf("Der Eiffelturm ist »330 Meter« hoch."))
        val events = run(orchestrator(brain), "Wie hoch ist der Eiffelturm?")

        assertEquals(
            "Der Eiffelturm ist 330 Meter hoch.",
            joinedText(events),
            "Vertrags-Marker »« sind Prompt-Interna und dürfen nie gesprochen werden",
        )
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    @Test
    fun `Delta ohne Guillemets fliesst byte-identisch durch (dieselbe String-Instanz)`() {
        val original = "Hallo, schön dich zu hören — ganz ohne Marker!"
        val brain = FakeBrainPort(listOf(original))
        val events = run(orchestrator(brain), "Sag Hallo.")

        val delta = events.filterIsInstance<ChatEvent.TextDelta>().single()
        assertSame(original, delta.text, "ohne Marker: Identität, keine Allokation, byte-neutral")
    }

    @Test
    fun `Marker ueber die Delta-Grenze wird sauber gestrippt ohne Puffer`() {
        // „»33" + „0«" — der Span ist über zwei Deltas gesplittet. Einzel-Zeichen-Strip
        // macht das trivial: jedes Delta strippt seine eigenen Marker, kein Hold nötig.
        val brain = FakeBrainPort(listOf("»33", "0«"))
        val events = run(orchestrator(brain), "Wie hoch?")

        val deltas = events.filterIsInstance<ChatEvent.TextDelta>().map { it.text }
        assertEquals(listOf("33", "0"), deltas, "je Delta gestrippt — streaming-safe, kein Puffer")
        assertEquals("330", joinedText(events), "zusammengesetzt bleibt die Zahl zeichengenau")
    }

    @Test
    fun `alle vier Guillemet-Zeichen werden gestrippt`() {
        assertEquals("330 Meter", TurnOrchestrator.stripContractMarkers("«330 Meter»"))
        assertEquals("330 Meter", TurnOrchestrator.stripContractMarkers("‹330 Meter›"))
        assertEquals("", TurnOrchestrator.stripContractMarkers("«»‹›"))
    }

    // ── Wetter-Vertrag (WeatherNumberContract, adapters-knowledge) reused DENSELBEN
    // Marker-Vertrag wie das Wiki-Grounding — Beweis: der Guillemet-Strip ist
    // bereits GENERISCH (jedes «»‹›-Zeichen, unabhängig von der Quelle). Der
    // WeatherGroundingProvider markiert Ort/Min/Max mit denselben vier Zeichen,
    // OHNE dass TurnOrchestrator dafür angefasst werden musste (kein zweiter
    // Marker-Dialekt).
    @Test
    fun `Wetter-Vertrag-Marker (Ort und Temperaturen) werden genauso gestrippt wie Wiki-Zahl-Marker`() {
        val brain = FakeBrainPort(listOf("In «Kairo» wird es morgen «17» bis «23» Grad warm."))
        val events = run(orchestrator(brain), "Wie ist das Wetter in Kairo?")

        assertEquals(
            "In Kairo wird es morgen 17 bis 23 Grad warm.",
            joinedText(events),
            "derselbe Marker-Vertrag wie WikiNumberContract — kein zweiter Dialekt nötig",
        )
    }

    // ── (P0 Event-Loop-Fix) honesty.assess läuft OFF der Event-Loop ───────────
    // Strukturbeweis, dass das (live ggf. SYNCHRON die Wissens-Bridge probende)
    // honesty.assess via `Mono.fromCallable{}.subscribeOn(boundedElastic)` ausgelagert
    // ist — die Detektor-Naht wird auf einem boundedElastic-Worker gerufen, NICHT auf
    // dem Reactor-Netty-Event-Loop. Gleichzeitig bleibt der Verdict-Pfad korrekt:
    // Pass ⇒ genau 1 Brain-Call, Brain-Text fließt unverändert durch.
    @Test
    fun `honesty assess laeuft off der Event-Loop auf boundedElastic und Verdict bleibt korrekt`() {
        val assessThread = AtomicReference<String?>(null)
        // onlineRequest wird in HonestyGate.classify IMMER zuerst geprüft → verlässlicher
        // Haken, um den Ausführungs-Thread von assess festzuhalten (Verdict bleibt Pass).
        val gate = HonestyGate(
            weakDomain = WeakDomainSignal { false },
            onlineRequest = OnlineRequestSignal {
                assessThread.set(Thread.currentThread().name)
                false
            },
            existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
            namedEntity = NamedEntitySignal { HonestySignal.NONE },
            cloudEnabled = { false },
        )
        val brain = FakeBrainPort(listOf("Hallo!"))
        val events = run(orchestrator(brain, honesty = gate), "Sag Hallo.")

        // Verdict-Pfad unverändert: Pass → genau 1 Brain-Call, Text fließt durch.
        assertEquals(1, brain.callCount.get(), "Pass-Verdict ⇒ genau 1 Brain-Call")
        assertEquals("Hallo!", joinedText(events), "Brain-Text fließt unverändert durch")
        assertTrue(events.last() is ChatEvent.Done, "Turn endet mit Done")

        // Strukturbeweis: assess lief auf einem boundedElastic-Worker, NICHT auf dem
        // Reactor-Event-Loop (sonst würde ein blockierender Bridge-Probe-Call ihn blocken).
        val name = assessThread.get()
        assertTrue(name != null, "assess muss aufgerufen worden sein")
        assertTrue(
            name!!.contains("boundedElastic"),
            "assess muss off der Event-Loop auf boundedElastic laufen, lief aber auf: $name",
        )
    }
}
