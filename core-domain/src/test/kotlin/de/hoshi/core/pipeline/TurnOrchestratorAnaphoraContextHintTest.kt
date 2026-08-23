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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Andis Live-Bug vom 15.08. als Kette** — Turn 1 „Wozu isst man ihn denn?"
 * (Anapher, der Referent stand im VORHERIGEN Turn) ⇒ „soll ich kurz
 * nachschauen?"; Turn 2 „ja" ⇒ Eskalation. Vorher reiste der ROHE Satz nach
 * draußen, die Cloud sah ein Pronomen ohne Referenten und fand ehrlich nichts.
 *
 * Bewiesen wird die ganze Reise des [PendingLookup.contextHint]: er entsteht am
 * Angebot, liegt im geparkten Pending, formt beim Einlösen die Eskalations-Query
 * — und taucht in KEINEM emittierten [ChatEvent] auf (aus denen allein das Diary
 * gebaut wird, s. `de.hoshi.web.TurnDiaryTap`).
 *
 * Harness-Muster von [TurnOrchestratorEnglishConsentChainTest]; der Angebots-Pfad
 * ist bewusst der HonestyGate-`AskConsent`-Zweig — genau der Zweig, der Andi live
 * „Gute Frage — soll ich kurz nachschauen?" gesagt hat.
 */
class TurnOrchestratorAnaphoraContextHintTest {

    private val anaphoricQuestion = "Wozu isst man ihn denn?"
    private val selfContainedQuestion = "Wie hoch ist der Eiffelturm?"
    private val previousUserTurn = "Was ist Ingwer?"
    private val previousAnswer = "Ingwer ist eine scharfe Wurzelknolle."
    private val cloudAnswer = "Man nutzt sie als Gewürz und Hausmittel."

    private val history = listOf(
        ChatMessage("user", previousUserTurn),
        ChatMessage("assistant", previousAnswer),
    )

    private class FakeBrainPort(private val line: String = "Brain answer.") : BrainPort {
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

    private class RecordingEscalationPort(private val result: () -> Mono<EscalationResult>) : EscalationPort {
        val queries = mutableListOf<String>()
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
            queries += query
            return result()
        }
    }

    /** Sieht, WAS geparkt wurde — der Beweis, dass der Hint schon im Pending liegt. */
    private class RecordingPendingLookupStore(
        private val delegate: PendingLookupPort = InMemoryPendingLookupStore(),
    ) : PendingLookupPort {
        val offered = mutableListOf<PendingLookup>()
        override fun offer(key: String, pending: PendingLookup) {
            offered += pending
            delegate.offer(key, pending)
        }

        override fun consume(key: String): PendingLookup? = delegate.consume(key)
    }

    private fun orchestrator(
        brain: FakeBrainPort,
        escalation: EscalationPort,
        pending: PendingLookupPort,
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
            // Weak-Domain + Cloud verfügbar ⇒ Verdict.AskConsent — der Live-Zweig.
            honesty = HonestyGate(
                weakDomain = WeakDomainSignal { true },
                onlineRequest = OnlineRequestSignal { false },
                existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
                namedEntity = NamedEntitySignal { HonestySignal.NONE },
                cloudEnabled = { true },
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
            factCoverage = FactCoverageGate(enabled = true),
            escalation = escalation,
            pendingLookup = pending,
            escalationMode = { EscalationMode.ERST_FRAGEN },
            lookupIntentEnabled = true,
        )
    }

    private fun turn(o: TurnOrchestrator, text: String, hist: List<ChatMessage>): List<ChatEvent> =
        o.handle(ChatRequest(text = text, history = hist)).collectList().block(Duration.ofSeconds(5))!!

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    /** Die Kette Angebot → „ja" → Eskalation, mit dem Query-Beweis am Ende. */
    private fun redeem(
        question: String,
        hist: List<ChatMessage>,
    ): Triple<RecordingPendingLookupStore, RecordingEscalationPort, List<ChatEvent>> {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort {
            Mono.just(EscalationResult.Answer(cloudAnswer, "openai", costCents = 0.05))
        }
        val pending = RecordingPendingLookupStore()
        val o = orchestrator(brain, cloud, pending)

        val offer = turn(o, question, hist)
        assertTrue(
            joinedText(offer) in de.hoshi.core.pipeline.lang.LangDe.PACK.cloudConsentAsk,
            "Turn 1 muss das Nachschlag-Angebot sein, war: '${joinedText(offer)}'",
        )
        assertEquals(0, brain.callCount.get(), "das Angebot ist brain-frei")

        val redeemed = turn(o, "ja", hist)
        assertEquals(1, cloud.queries.size, "genau EIN Eskalations-Call")
        return Triple(pending, cloud, redeemed)
    }

    // ── (1) Anapher + History ⇒ Hint im Pending, Kontext+Frage in der Query ──

    @Test
    fun `anaphorischer Satz mit History - der Referent reist mit`() {
        val (pending, cloud, events) = redeem(anaphoricQuestion, history)

        val parked = pending.offered.single()
        assertNotNull(parked.contextHint, "das Pending muss den Referenten tragen")
        assertTrue(parked.contextHint!!.contains("Ingwer"), parked.contextHint!!)
        assertEquals(anaphoricQuestion, parked.query, "die Original-Frage bleibt unangetastet")

        val outbound = cloud.queries.single()
        assertTrue(outbound.startsWith("Kontext: "), outbound)
        assertTrue(outbound.contains("Ingwer"), "ohne Referenten kann die Cloud nichts finden: $outbound")
        assertTrue(outbound.endsWith("\nFrage: $anaphoricQuestion"), outbound)

        // Die Antwort selbst bleibt die gewohnte verbatim-Rahmung.
        assertTrue(joinedText(events).contains(cloudAnswer), joinedText(events))
    }

    // ── (2) Ohne Anapher ⇒ kein Hint, Query byte-identisch zu heute ──────────

    @Test
    fun `selbsttragender Satz - kein Hint, Query byte-identisch`() {
        val (pending, cloud, _) = redeem(selfContainedQuestion, history)

        assertNull(pending.offered.single().contextHint, "kein Referenz-Signal ⇒ kein Hint")
        assertEquals(selfContainedQuestion, cloud.queries.single(), "die Query muss byte-identisch bleiben")
    }

    // ── (3) Anapher OHNE History ⇒ kein Hint (nichts zu erinnern) ────────────

    @Test
    fun `anaphorischer Satz ohne History - kein Hint, Query byte-identisch`() {
        val (pending, cloud, _) = redeem(anaphoricQuestion, emptyList())

        assertNull(pending.offered.single().contextHint, "ohne Verlauf gibt es keinen Referenten")
        assertEquals(anaphoricQuestion, cloud.queries.single(), "die Query bleibt exakt das heutige Verhalten")
    }

    // ── (4) Der Deckel greift auch am echten Turn ────────────────────────────

    @Test
    fun `der Hint-Deckel greift auf dem ganzen Weg`() {
        val fatHistory = listOf(
            ChatMessage("user", "Ingwer " + "sehr ausführlich ".repeat(60)),
            ChatMessage("assistant", "Eine Wurzelknolle, " + "und noch viel mehr Text ".repeat(60)),
        )
        val (pending, cloud, _) = redeem(anaphoricQuestion, fatHistory)

        val hint = pending.offered.single().contextHint!!
        assertTrue(hint.length <= ContextHint.MAX_CHARS, "Deckel gebrochen: ${hint.length}")

        val outbound = cloud.queries.single()
        val outboundHint = outbound.removePrefix("Kontext: ").substringBefore("\nFrage: ")
        assertEquals(hint, outboundHint)
        assertTrue(outboundHint.length <= ContextHint.MAX_CHARS, "Deckel gebrochen: ${outboundHint.length}")
    }

    // ── (5) Egress-/Diary-Vertrag: der Hint verlässt den Eskalationspfad nie ──

    @Test
    fun `kein emittiertes Event traegt den Hint - das Diary bleibt inhaltsfrei`() {
        val (pending, _, events) = redeem(anaphoricQuestion, history)
        val hint = pending.offered.single().contextHint!!

        // Das Diary (TurnDiaryTap) wird AUSSCHLIESSLICH aus diesen Events gebaut —
        // taucht der Hint hier nicht auf, kann er auch dort nicht landen.
        events.forEach { event ->
            assertFalse(event.toString().contains(hint), "Hint im Event: $event")
            assertFalse(event.toString().contains(previousAnswer), "Vor-Antwort im Event: $event")
            assertFalse(event.toString().contains("Kontext: "), "Eskalations-Rahmung im Event: $event")
        }
        // Und keine Notiz-Verknüpfung: eine anaphorische Query ist kein Cache-Key.
        val done = events.filterIsInstance<ChatEvent.Done>().last()
        assertNull(done.escalationQueryHash, "ein gehinteter Nachschlag wird bewusst nicht gecacht")
    }
}
