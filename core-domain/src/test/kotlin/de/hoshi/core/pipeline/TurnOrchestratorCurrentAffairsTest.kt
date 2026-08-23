package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsItem
import de.hoshi.core.port.CurrentAffairsPort
import de.hoshi.core.port.CurrentAffairsSnapshot
import de.hoshi.core.port.CurrentAffairsSourceId
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
 * Wiring proof of the [CurrentAffairsFastpath] in the [TurnOrchestrator] (F5
 * Lagebild):
 *
 * - **OFF (default):** no `currentAffairs` parameter ⇒ "Was ist heute wichtig?"
 *   takes today's brain path — byte-neutral.
 * - **ON:** the question is answered before routing, structurally brain-free
 *   (`model="policy"`, zero brain calls — the measurable "background brain
 *   calls = 0" claim), under its own diary category.
 */
class TurnOrchestratorCurrentAffairsTest {

    private class ThrowingBrain : BrainPort {
        val callCount = AtomicInteger(0)
        override fun streamChat(
            prompt: String, systemPrompt: String, history: List<ChatMessage>,
            temperature: Double?, sessionId: String, userId: String,
            tools: List<Map<String, Any?>>, toolGrammar: Boolean, onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            callCount.incrementAndGet()
            error("Der Brain darf im News-Fastpath-Turn NICHT gerufen werden")
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

    private fun orchestrator(brain: BrainPort, news: CurrentAffairsFastpath? = null): TurnOrchestrator {
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
            currentAffairs = news ?: CurrentAffairsFastpath.DISABLED,
        )
    }

    private val refreshedAt: Instant = Instant.parse("2026-08-15T07:12:00Z")

    private fun newsFastpath(): CurrentAffairsFastpath {
        val port = CurrentAffairsPort {
            CurrentAffairsSnapshot(
                items = listOf(
                    CurrentAffairsItem(
                        id = "1",
                        source = CurrentAffairsSourceId.TAGESSCHAU,
                        title = "Bundestag beschließt neues Energiepaket",
                        snippet = null,
                        canonicalUrl = "https://www.tagesschau.de/1",
                        publishedAt = refreshedAt,
                        fetchedAt = refreshedAt,
                        attribution = "tagesschau.de",
                    ),
                ),
                observedAt = Instant.parse("2026-08-15T07:30:00Z"),
                lastSuccessfulRefreshAt = refreshedAt,
                freshness = CurrentAffairsFreshness.FRESH,
            )
        }
        return CurrentAffairsFastpath(
            port = port,
            clock = Clock.fixed(Instant.parse("2026-08-15T07:30:00Z"), ZoneId.of("Europe/Berlin")),
        )
    }

    private fun run(o: TurnOrchestrator, request: ChatRequest): List<ChatEvent> =
        o.handle(request).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun `OFF laesst die News-Frage unveraendert zum Brain`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val events = run(orchestrator(brain), ChatRequest(text = "Was ist heute wichtig?"))

        assertEquals(1, brain.callCount.get(), "OFF: die Frage geht den heutigen Brain-Weg")
        assertTrue(text(events).contains("Brain-Antwort."))
    }

    @Test
    fun `ON beantwortet die News-Frage brain-frei mit model policy und Kategorie NEWS`() {
        val brain = ThrowingBrain()
        val events = run(orchestrator(brain, newsFastpath()), ChatRequest(text = "Was ist heute wichtig?"))

        assertEquals(0, brain.callCount.get(), "News-Turn darf den Brain NIE rufen")
        val start = events.first() as ChatEvent.Start
        assertEquals("policy", start.model, "Policy-Direktantwort, kein Brain")
        assertEquals(TurnOrchestrator.CATEGORY_NEWS, start.category)
        assertEquals("Stand 9 Uhr 12: Bundestag beschließt neues Energiepaket.", text(events))
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `ON laesst die Datums-Frage in Ruhe - der News-Zweig schluckt sie nicht`() {
        val brain = DeltaBrain("Brain-Antwort.")
        val events = run(orchestrator(brain, newsFastpath()), ChatRequest(text = "Was ist heute für ein Tag?"))

        assertEquals(1, brain.callCount.get(), "ohne Datums-Fastpath geht die Datums-Frage zum Brain, nie an News")
        assertTrue(text(events).contains("Brain-Antwort."))
    }
}
