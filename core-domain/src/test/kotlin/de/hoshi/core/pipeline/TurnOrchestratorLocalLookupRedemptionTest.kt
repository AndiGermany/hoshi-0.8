package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
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
 * End-to-end-Beweis der P2-Korrektur: Ein eingelöstes FactCoverage-Angebot
 * fragt zuerst die enge lokale Wissenssicht mit der ORIGINALfrage. Nur ein
 * leerer/off-target/kaputter lokaler Versuch darf in den bestehenden
 * Eskalationspfad fallen. Explizite Online-Bitten ohne Pending bleiben online.
 */
class TurnOrchestratorLocalLookupRedemptionTest {

    private val question = "Wie viele Planeten gibt es in unserem Sonnensystem?"
    private val localBlock =
        "\n\n---\nHINTERGRUND: • Sonnensystem: Acht Planeten umkreisen die Sonne.\n"
    private val offTargetBlock =
        "\n\n---\nHINTERGRUND: • Sprache: Das Pronomen „unserem“ ist ein Possessivbegleiter.\n"
    private val localAnswer = "Unser Sonnensystem hat acht Planeten."

    private class FakeBrain(
        private val response: () -> Flux<LlmDelta> = { Flux.just(LlmDelta("lokal")) },
    ) : BrainPort {
        val calls = AtomicInteger()
        val prompts = mutableListOf<String>()

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
            prompts += prompt
            return response()
        }
    }

    private class SplitGrounding(
        private val local: () -> Mono<String>,
    ) : GroundingPort {
        val normalCalls = AtomicInteger()
        val localCalls = AtomicInteger()

        override fun groundingBlock(
            query: String,
            category: RouteCategory,
            language: Language,
        ): Mono<String> {
            normalCalls.incrementAndGet()
            return Mono.just("")
        }

        override fun localKnowledgeBlock(
            query: String,
            category: RouteCategory,
            language: Language,
        ): Mono<String> {
            localCalls.incrementAndGet()
            return local()
        }
    }

    private class RecordingCloud(
        private val answer: String = "Es sind acht Planeten.",
    ) : EscalationPort {
        val queries = mutableListOf<String>()

        override fun lookup(
            query: String,
            groundingSnippets: String,
            language: Language,
        ): Mono<EscalationResult> {
            queries += query
            return Mono.just(EscalationResult.Answer(answer, "test", 0.01))
        }
    }

    private class RecordingPending(
        private val delegate: PendingLookupPort = InMemoryPendingLookupStore(),
    ) : PendingLookupPort {
        val offers = mutableListOf<PendingLookup>()

        override fun offer(key: String, pending: PendingLookup) {
            offers += pending
            delegate.offer(key, pending)
        }

        override fun consume(key: String): PendingLookup? = delegate.consume(key)
    }

    private fun orchestrator(
        brain: FakeBrain,
        grounding: GroundingPort,
        cloud: RecordingCloud = RecordingCloud(),
        pending: PendingLookupPort = InMemoryPendingLookupStore(),
        mode: () -> EscalationMode = { EscalationMode.ERST_FRAGEN },
        lookupIntentEnabled: Boolean = false,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "test")
                },
                llmRefiner = { _, fallback -> Mono.just(fallback) },
                embeddingRefiner = { _, fallback -> Mono.just(fallback) },
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
                grounding = grounding,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            factCoverage = FactCoverageGate(enabled = true, strict = true),
            escalation = cloud,
            pendingLookup = pending,
            escalationMode = mode,
            lookupIntentEnabled = lookupIntentEnabled,
        )
    }

    private fun turn(orchestrator: TurnOrchestrator, text: String): List<ChatEvent> =
        orchestrator.handle(ChatRequest(text = text))
            .collectList()
            .block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    private fun createOffer(orchestrator: TurnOrchestrator) {
        val first = turn(orchestrator, question)
        assertFalse(first.filterIsInstance<ChatEvent.Start>().single().grounded)
    }

    @Test
    fun `on-target Wiki-Fund - ein lokaler Brain-Call, kein Cloud-Call, Originalfrage und lokaler Vorspann`() {
        val brain = FakeBrain { Flux.just(LlmDelta(localAnswer)) }
        val grounding = SplitGrounding { Mono.just(localBlock) }
        val cloud = RecordingCloud()
        val orchestrator = orchestrator(brain, grounding, cloud)

        createOffer(orchestrator)
        val events = turn(orchestrator, "ja")

        assertEquals(1, grounding.localCalls.get())
        assertEquals(1, brain.calls.get(), "genau ein lokaler Brain-Call")
        assertEquals(listOf(question), brain.prompts, "nie »ja« an den Brain")
        assertEquals(emptyList<String>(), cloud.queries, "gedecktes Wiki bleibt vollständig lokal")
        assertTrue((events.first() as ChatEvent.Start).grounded)
        assertFalse((events.first() as ChatEvent.Start).escalated)
        assertEquals(
            LanguagePackRegistry.forLanguage(Language.DE).localLookupFoundPrefix + localAnswer,
            text(events),
        )
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `natuerliches ja schau kurz nach behaelt die Originalfrage statt als Smalltalk durchzufallen`() {
        val brain = FakeBrain { Flux.just(LlmDelta(localAnswer)) }
        val grounding = SplitGrounding { Mono.just(localBlock) }
        val cloud = RecordingCloud()
        val orchestrator = orchestrator(brain, grounding, cloud)

        createOffer(orchestrator)
        val events = turn(orchestrator, "ja schau kurz nach")

        assertEquals(1, grounding.localCalls.get())
        assertEquals(listOf(question), brain.prompts, "der Folge-Satz ersetzt nie die gespeicherte Frage")
        assertEquals(emptyList<String>(), cloud.queries)
        assertEquals(
            LanguagePackRegistry.forLanguage(Language.DE).localLookupFoundPrefix + localAnswer,
            text(events),
        )
        assertTrue((events.first() as ChatEvent.Start).grounded)
    }

    @Test
    fun `leerer lokaler Versuch - kein Brain, genau ein Cloud-Call ohne lokalen Vorspann`() {
        val brain = FakeBrain()
        val grounding = SplitGrounding { Mono.just("") }
        val cloud = RecordingCloud()
        val orchestrator = orchestrator(brain, grounding, cloud)

        createOffer(orchestrator)
        val events = turn(orchestrator, "ja")

        assertEquals(0, brain.calls.get())
        assertEquals(listOf(question), cloud.queries)
        assertFalse(
            text(events).contains(LanguagePackRegistry.forLanguage(Language.DE).localLookupFoundPrefix),
        )
        assertTrue((events.first() as ChatEvent.Start).escalated)
        val done = events.last() as ChatEvent.Done
        assertTrue(
            done.stageTimings?.groundingMs != null,
            "der lokale Vorversuch bleibt als gemessene Grounding-Stufe sichtbar",
        )
    }

    @Test
    fun `tangentialer lokaler Block mit unserem - fail-closed zur Cloud`() {
        val brain = FakeBrain()
        val grounding = SplitGrounding { Mono.just(offTargetBlock) }
        val cloud = RecordingCloud()
        val orchestrator = orchestrator(brain, grounding, cloud)

        createOffer(orchestrator)
        turn(orchestrator, "ja")

        assertEquals(0, brain.calls.get())
        assertEquals(listOf(question), cloud.queries)
    }

    @Test
    fun `lokaler Port-Fehler - sauberer Cloud-Fallback statt stiller Sackgasse`() {
        val brain = FakeBrain()
        val grounding = SplitGrounding { Mono.error(IllegalStateException("bridge kaputt")) }
        val cloud = RecordingCloud()
        val orchestrator = orchestrator(brain, grounding, cloud)

        createOffer(orchestrator)
        val events = turn(orchestrator, "ja")

        assertEquals(0, brain.calls.get())
        assertEquals(listOf(question), cloud.queries)
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `AUS - lokaler Treffer antwortet lokal, lokaler Miss bleibt ohne Cloud`() {
        val hitBrain = FakeBrain { Flux.just(LlmDelta(localAnswer)) }
        val hitGrounding = SplitGrounding { Mono.just(localBlock) }
        val hitCloud = RecordingCloud()
        val hit = orchestrator(
            hitBrain,
            hitGrounding,
            hitCloud,
            mode = { EscalationMode.AUS },
        )
        createOffer(hit)
        val hitEvents = turn(hit, "ja")
        assertEquals(1, hitBrain.calls.get())
        assertEquals(emptyList<String>(), hitCloud.queries)
        assertTrue(text(hitEvents).endsWith(localAnswer))

        val missBrain = FakeBrain()
        val missGrounding = SplitGrounding { Mono.just("") }
        val missCloud = RecordingCloud()
        val miss = orchestrator(
            missBrain,
            missGrounding,
            missCloud,
            mode = { EscalationMode.AUS },
        )
        createOffer(miss)
        val missEvents = turn(miss, "ja")
        assertEquals(0, missBrain.calls.get())
        assertEquals(emptyList<String>(), missCloud.queries)
        assertEquals(TurnOrchestrator.EXTENDED_THINK_OFF_HINT_DE, text(missEvents))
    }

    @Test
    fun `lokaler Fund plus leerer oder fehlernder Brain bleibt never-silent hinter dem Vorspann`() {
        val prefix = LanguagePackRegistry.forLanguage(Language.DE).localLookupFoundPrefix

        val emptyBrain = FakeBrain { Flux.empty() }
        val emptyCloud = RecordingCloud()
        val empty = orchestrator(emptyBrain, SplitGrounding { Mono.just(localBlock) }, emptyCloud)
        createOffer(empty)
        val emptyEvents = turn(empty, "ja")
        assertEquals(prefix + TurnOrchestrator.EMPTY_FALLBACK_DE, text(emptyEvents))
        assertEquals(emptyList<String>(), emptyCloud.queries, "nach gedecktem Fund nie zur Cloud springen")

        val errorBrain = FakeBrain { Flux.error(IllegalStateException("brain kaputt")) }
        val errorCloud = RecordingCloud()
        val error = orchestrator(errorBrain, SplitGrounding { Mono.just(localBlock) }, errorCloud)
        createOffer(error)
        val errorEvents = turn(error, "ja")
        assertEquals(prefix + TurnOrchestrator.ERROR_FALLBACK_DE, text(errorEvents))
        assertEquals(emptyList<String>(), errorCloud.queries)
        assertTrue(errorEvents.last() is ChatEvent.Done)
    }

    @Test
    fun `lokaler Brain-Abstain erzeugt kein neues Pending und keine Consent-Schleife`() {
        val pending = RecordingPending()
        val brain = FakeBrain { Flux.just(LlmDelta("Das weiß ich leider nicht sicher.")) }
        val orchestrator = orchestrator(
            brain,
            SplitGrounding { Mono.just(localBlock) },
            pending = pending,
            lookupIntentEnabled = true,
        )

        createOffer(orchestrator)
        turn(orchestrator, "ja")

        assertEquals(1, pending.offers.size, "nur der ursprüngliche FactCoverage-Deflect offert")
        assertTrue(pending.offers.single().retryLocalKnowledge)
    }

    @Test
    fun `explizite Online-Bitte ohne Pending bleibt direkt online und fragt Wiki nicht`() {
        val brain = FakeBrain()
        val grounding = SplitGrounding { Mono.just(localBlock) }
        val cloud = RecordingCloud()
        val orchestrator = orchestrator(
            brain,
            grounding,
            cloud,
            lookupIntentEnabled = true,
        )

        val events = turn(orchestrator, "Schau bitte online nach, wann GTA 6 erscheint.")

        assertEquals(0, grounding.localCalls.get())
        assertEquals(0, brain.calls.get())
        assertEquals(listOf("wann GTA 6 erscheint"), cloud.queries)
        assertTrue((events.first() as ChatEvent.Start).escalated)
    }
}
