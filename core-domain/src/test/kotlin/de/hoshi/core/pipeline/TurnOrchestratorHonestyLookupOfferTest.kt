package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Paket 7: das HonestyGate-Angebot nutzt dieselbe PendingLookup-Kette wie der
 * FactCoverage-Deflect. Kein Spring, kein Netz; der Recording-Port beweist die
 * tatsächlich eingelöste Originalfrage.
 */
class TurnOrchestratorHonestyLookupOfferTest {

    private enum class Kind { ONLINE, RECIPE, EXISTENCE, NAMED, BRIDGE_DOWN }

    private class Brain : BrainPort {
        val calls = AtomicInteger()
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
            calls.incrementAndGet()
            return Flux.just(LlmDelta("Brain-Antwort."))
        }
    }

    private class Cloud : EscalationPort {
        val queries = mutableListOf<String>()
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
            queries += query
            return Mono.just(EscalationResult.Answer("Nachgeschlagene Antwort.", "Testquelle", 0.01))
        }
    }

    private class RecordingPending(private val delegate: PendingLookupPort) : PendingLookupPort {
        val offered = mutableListOf<PendingLookup>()
        override fun offer(key: String, pending: PendingLookup) {
            offered += pending
            delegate.offer(key, pending)
        }
        override fun consume(key: String): PendingLookup? = delegate.consume(key)
    }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    }

    private fun orchestrator(
        kind: Kind,
        available: Boolean,
        brain: Brain,
        cloud: Cloud,
        pending: PendingLookupPort,
    ): TurnOrchestrator {
        val persona = PersonaService()
        val bridgeDown = kind == Kind.BRIDGE_DOWN
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter { RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "test") },
                llmRefiner = { _, fallback -> Mono.just(fallback) },
                embeddingRefiner = { _, fallback -> Mono.just(fallback) },
                softRoutingEnabled = false,
                softRoutingMode = "embedding",
            ),
            honesty = HonestyGate(
                weakDomain = WeakDomainSignal { text -> kind == Kind.RECIPE && WeakDomainDetector().isWeakDomain(text) },
                onlineRequest = OnlineRequestSignal { text ->
                    kind == Kind.ONLINE && OnlineRequestDetector().isOnlineRequest(text)
                },
                existenceClaim = ExistenceClaimSignal { text ->
                    if ((kind == Kind.EXISTENCE || bridgeDown) && "11" in text) HonestySignal(true, bridgeDown)
                    else HonestySignal.NONE
                },
                namedEntity = NamedEntitySignal { text ->
                    if (kind == Kind.NAMED && "Neelix" in text) HonestySignal(true) else HonestySignal.NONE
                },
                cloudEnabled = { available },
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
            factCoverage = FactCoverageGate.DISABLED,
            escalation = cloud,
            pendingLookup = pending,
            escalationMode = { EscalationMode.ERST_FRAGEN },
        )
    }

    private fun turn(orchestrator: TurnOrchestrator, text: String): List<ChatEvent> =
        orchestrator.handle(ChatRequest(text = text)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun `Rezept-Angebot wird gespeichert und ja loest GENAU die Originalfrage ein`() {
        val brain = Brain()
        val cloud = Cloud()
        val pending = RecordingPending(InMemoryPendingLookupStore())
        val orchestrator = orchestrator(Kind.RECIPE, available = true, brain, cloud, pending)
        val question = "Kennst du ein gutes Rezept für Okonomiyaki?"

        val offer = turn(orchestrator, question)
        assertTrue(text(offer) in LangDe.PACK.cloudConsentAsk, "das hörbare Bestands-Angebot erscheint")
        assertEquals(listOf(question), pending.offered.map { it.query }, "dieselbe PendingLookup-Naht merkt die Originalfrage")
        assertEquals(0, brain.calls.get(), "Honesty-Deflect bleibt brain-frei")

        turn(orchestrator, "ja")
        assertEquals(listOf(question), cloud.queries, "ja eskaliert die Originalfrage, nie das Wort ja")
        assertEquals(0, brain.calls.get(), "auch die Einlösung ist brain-frei")
    }

    @Test
    fun `Existenz-Claim und unbekannter Eigenname erhalten dasselbe einloesbare Angebot`() {
        for ((kind, question) in listOf(
            Kind.EXISTENCE to "Gibt es einen 11-Euro-Schein?",
            Kind.NAMED to "Wer ist Neelix?",
        )) {
            val brain = Brain()
            val cloud = Cloud()
            val pending = RecordingPending(InMemoryPendingLookupStore())
            val orchestrator = orchestrator(kind, available = true, brain, cloud, pending)

            assertTrue(text(turn(orchestrator, question)) in LangDe.PACK.cloudConsentAsk, "$kind bietet hörbar an")
            turn(orchestrator, "ja")
            assertEquals(listOf(question), cloud.queries, "$kind löst dieselbe Originalfrage ein")
        }
    }

    @Test
    fun `explizite Online-Bitte legt dasselbe Pending an und nutzt nur die aufgreifende Formulierung`() {
        val brain = Brain()
        val cloud = Cloud()
        val pending = RecordingPending(InMemoryPendingLookupStore())
        val orchestrator = orchestrator(Kind.ONLINE, available = true, brain, cloud, pending)
        val question = "Kannst du online schauen, wie viele Monde Jupiter hat?"

        assertTrue(text(turn(orchestrator, question)) in LangDe.PACK.cloudConsentAskExplicit)
        turn(orchestrator, "ja")
        assertEquals(listOf(question), cloud.queries, "auch der explizite Honesty-Ursprung nutzt dieselbe Originalfrage")
    }

    @Test
    fun `Cloud oder Budget nicht verfuegbar laesst Rezept-Wortlaut im Bestands-Pool und legt nichts an`() {
        val brain = Brain()
        val cloud = Cloud()
        val pending = RecordingPending(InMemoryPendingLookupStore())
        val orchestrator = orchestrator(Kind.RECIPE, available = false, brain, cloud, pending)

        val refusal = text(turn(orchestrator, "Wie mache ich Käsekuchen?"))
        assertTrue(
            refusal == "Kochen ist nicht meine Stärke — da führ ich dich in die Irre." ||
                refusal == "Beim Rezept würd ich raten, und das wär dir keine Hilfe.",
            "OFF/Cap behält den bestehenden Wortlaut byte-genau: $refusal",
        )
        assertTrue(pending.offered.isEmpty(), "ohne ehrliche Verfügbarkeit kein Angebot im Store")
        turn(orchestrator, "ja")
        assertTrue(cloud.queries.isEmpty(), "ja ohne Angebot löst keinen Egress aus")
    }

    @Test
    fun `Bridge-down bleibt lokal und legt trotz verfuegbarem Nachschlag kein Pending an`() {
        val brain = Brain()
        val cloud = Cloud()
        val pending = RecordingPending(InMemoryPendingLookupStore())
        val orchestrator = orchestrator(Kind.BRIDGE_DOWN, available = true, brain, cloud, pending)

        val refusal = text(turn(orchestrator, "Gibt es einen 11-Euro-Schein?"))
        assertTrue(
            refusal.contains("Wissensspeicher", ignoreCase = true) ||
                refusal.contains("Nachschlagewerk", ignoreCase = true),
            "Bridge-down nennt den lokalen Infrastrukturfehler ehrlich: $refusal",
        )
        assertTrue(pending.offered.isEmpty(), "kein stiller Privacy-Wechsel")
    }

    @Test
    fun `abgelaufenes Honesty-Angebot macht ein spaetes ja zum normalen Turn`() {
        // PendingLookup.ts nutzt bewusst die Produktionsuhr; die Store-Uhr muss
        // deshalb am selben Jetzt starten und wird danach kontrolliert bewegt.
        val clock = MutableClock(Instant.now())
        val brain = Brain()
        val cloud = Cloud()
        val pending = InMemoryPendingLookupStore(clock = clock)
        val orchestrator = orchestrator(Kind.RECIPE, available = true, brain, cloud, pending)

        turn(orchestrator, "Wie backe ich Brot?")
        clock.advanceSeconds(121)
        turn(orchestrator, "ja")

        assertTrue(cloud.queries.isEmpty(), "ein Angebot von vorhin ist kein Consent von jetzt")
        assertEquals(1, brain.calls.get(), "das späte ja läuft normal statt als Lookup")
    }
}
