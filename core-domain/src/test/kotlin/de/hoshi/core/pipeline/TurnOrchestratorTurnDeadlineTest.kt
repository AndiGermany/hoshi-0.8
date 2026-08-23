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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * **Die Turn-Decke** (Stabilitäts-Fix 2026-08-20): jede EINZELNE Stufe hat ihr
 * eigenes Budget (Eskalation 8 s, Agentic-Collect 30 s, Brain gesamt 25 s) —
 * aber sie ADDIEREN sich, und keines misst den Turn als Ganzes. Ein Turn, dessen
 * Brain-Stream einfach offen stehen bleibt (kein Delta, kein Fehler, kein
 * Abschluss — genau das WEDGE-Bild), lief vorher UNBEGRENZT: der Nutzer hörte
 * nichts, die Verbindung hing.
 *
 * `turnDeadline` (Default [TurnOrchestrator.TURN_DEADLINE] = 60 s, Ops-Knopf
 * `HOSHI_TURN_DEADLINE_SECONDS`) zieht die letzte Wanduhr. Entscheidend ist,
 * WIE sie reisst: als [TimeoutException] — dadurch landet der Riss im bestehenden
 * `onErrorResume` und damit in der warmen Fehler-Phrase (never-silent), statt den
 * Turn still abzuschneiden.
 *
 * Alle Deadline-Fälle laufen über den [reactor.test.scheduler.VirtualTimeScheduler]
 * (`StepVerifier.withVirtualTime`) — die 60 s vergehen in Mikrosekunden.
 */
class TurnOrchestratorTurnDeadlineTest {

    /** Brain-Fake, der genau das WEDGE-Bild liefert: erst [deltas], dann NIE etwas. */
    private class HangingBrain(
        private val deltas: List<String> = emptyList(),
        private val error: Throwable? = null,
    ) : BrainPort {
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
            if (error != null) return Flux.error(error)
            // never() haengt nach den Deltas: kein Delta, kein Fehler, kein Abschluss.
            return Flux.fromIterable(deltas).map { LlmDelta(it) }.concatWith(Flux.never())
        }
    }

    private fun orchestrator(brain: BrainPort, deadline: Duration): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter { RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "fake") },
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
                grounding = GroundingPort.EMPTY,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            turnDeadline = deadline,
        )
    }

    private fun request(text: String = "Erzaehl mir was.") =
        ChatRequest(text = text, language = Language.DE)

    // ── (1) Der Riss: haengender Brain ⇒ warme FEHLER-Phrase statt Stille ────
    @Test
    fun `haengender Turn reisst an der Deadline in die warme FEHLER-Phrase`() {
        val o = orchestrator(HangingBrain(), Duration.ofSeconds(60))
        StepVerifier.withVirtualTime { o.handle(request()) }
            .expectSubscription()
            // Start kommt sofort, danach passiert bis zur Deadline NICHTS.
            .expectNextMatches { it is ChatEvent.Start }
            .expectNoEvent(Duration.ofSeconds(59))
            .thenAwait(Duration.ofSeconds(2))
            .expectNextMatches {
                it is ChatEvent.TextDelta && it.text == TurnOrchestrator.ERROR_FALLBACK_DE
            }
            .expectNextMatches { ev ->
                ev is ChatEvent.Done && ev.stageTimings?.brainTimeout == true
            }
            .verifyComplete()
    }

    // ── (2) Text war schon raus ⇒ sauberes Done, KEINE zweite Phrase ─────────
    @Test
    fun `Deadline nach schon gestreamtem Text schliesst sauber mit Done statt Doppel-Phrase`() {
        val o = orchestrator(HangingBrain(deltas = listOf("Hallo, ")), Duration.ofSeconds(60))
        val events = StepVerifier.withVirtualTime { o.handle(request()) }
            .expectSubscription()
            .expectNextMatches { it is ChatEvent.Start }
            .expectNextMatches { it is ChatEvent.TextDelta && it.text == "Hallo, " }
            .expectNoEvent(Duration.ofSeconds(59))
            .thenAwait(Duration.ofSeconds(2))
            .expectNextMatches { ev ->
                // Kein zweiter TextDelta: die halbe Antwort wird nicht mit einer
                // Fehler-Phrase ueberklebt — aber das Diary erfaehrt den Timeout.
                ev is ChatEvent.Done && ev.stageTimings?.brainTimeout == true
            }
            .verifyComplete()
        assertTrue(events != null)
    }

    // ── (3) Byte-Neutralitaet: ein gesunder Turn merkt von der Decke nichts ──
    @Test
    fun `gesunder Turn bleibt unveraendert - kein Warten, keine Timings aus der Decke`() {
        val o = orchestrator(
            object : BrainPort {
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
                ): Flux<LlmDelta> = Flux.just(LlmDelta("Hallo, "), LlmDelta("schoen dich zu hoeren!"))
            },
            Duration.ofSeconds(60),
        )
        val startedAt = System.nanoTime()
        val events = o.handle(request()).collectList().block(Duration.ofSeconds(5))!!
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(elapsedMs < 3_000, "der gesunde Turn darf NICHT auf die Decke warten (waren ${elapsedMs}ms)")
        assertEquals(
            "Hallo, schoen dich zu hoeren!",
            events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text },
        )
        val done = events.last() as ChatEvent.Done
        // Die Decke fasst das Done NICHT an: die Timings sind exakt die schon immer
        // gemessenen (grounding/TTFT), und `brainTimeout` bleibt ABWESEND — `false`
        // reist nie ueber die Leitung, nur ein echtes `true` (LL-2026-08-11).
        assertNull(done.stageTimings?.brainTimeout, "ohne Riss darf brainTimeout nicht auftauchen")
    }

    // ── (4) Die Naht zum Brain-Gesamt-Budget: dessen TimeoutException landet
    //        ueber isTimeout in DEMSELBEN warmen Zweig — und im Diary. ────────
    @Test
    fun `TimeoutException aus dem Brain-Budget wird zur warmen FEHLER-Phrase mit brainTimeout im Diary`() {
        val o = orchestrator(
            HangingBrain(error = TimeoutException("brain total budget 25s exceeded")),
            Duration.ofSeconds(60),
        )
        val events = o.handle(request()).collectList().block(Duration.ofSeconds(5))!!

        assertEquals(
            TurnOrchestrator.ERROR_FALLBACK_DE,
            events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text },
            "ein gerissenes Brain-Budget spricht die warme FEHLER-Phrase",
        )
        val done = events.last() as ChatEvent.Done
        assertEquals(true, done.stageTimings?.brainTimeout, "der Timeout muss im Diary stehen")
    }

    @Test
    fun `ein NICHT-Timeout-Fehler markiert brainTimeout NICHT (kein falsches Signal)`() {
        val o = orchestrator(HangingBrain(error = RuntimeException("Sidecar weg")), Duration.ofSeconds(60))
        val events = o.handle(request()).collectList().block(Duration.ofSeconds(5))!!

        val done = events.last() as ChatEvent.Done
        assertNull(done.stageTimings?.brainTimeout, "ein Absturz ist kein Timeout — das Feld bleibt abwesend")
    }
}
