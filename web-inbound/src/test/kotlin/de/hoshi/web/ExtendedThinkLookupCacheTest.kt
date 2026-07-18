package de.hoshi.web

import de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider
import de.hoshi.adapters.supervision.JsonlLookupNoteAdapter
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.EscalationMode
import de.hoshi.core.pipeline.ExistenceClaimSignal
import de.hoshi.core.pipeline.FactCoverageGate
import de.hoshi.core.pipeline.HonestyGate
import de.hoshi.core.pipeline.HonestySignal
import de.hoshi.core.pipeline.KeywordRouter
import de.hoshi.core.pipeline.NamedEntitySignal
import de.hoshi.core.pipeline.OnlineRequestSignal
import de.hoshi.core.pipeline.PersonaService
import de.hoshi.core.pipeline.ResponseFormatter
import de.hoshi.core.pipeline.RoutingPolicy
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.pipeline.TurnPromptAssembler
import de.hoshi.core.pipeline.WeakDomainSignal
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import de.hoshi.core.port.LookupNote
import de.hoshi.core.port.LookupNoteNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * **Der Cache-Hit-vor-Cloud-Beweis (Extended Think S3, Abnahme-Kriterium #1 aus
 * PREP-extended-think.md):** echter [TurnOrchestrator] + echter
 * [JsonlLookupNoteAdapter] (Write) + echter [NachgeschlagenGroundingProvider]
 * (Read) — OHNE Spring-Boot-Context (Muster [ChatStreamDiaryGroundingTest]):
 * **dieselbe Frage kostet nur EINMAL.**
 *
 * Turn 1: kein Cache-Treffer ⇒ [FactCoverageGate] deflektet, AUTOMATISCH
 * eskaliert SOFORT ⇒ EIN Cloud-Call ⇒ die Antwort wird als [de.hoshi.core.port.LookupNote]
 * geschrieben. Turn 2 (dieselbe Frage): der [NachgeschlagenGroundingProvider]
 * deckt das Grounding aus der GESCHRIEBENEN Datei ⇒ [FactCoverageGate] deflektet
 * gar nicht erst ⇒ normaler (lokaler) Brain-Turn, KEIN zweiter Cloud-Call.
 */
class ExtendedThinkLookupCacheTest {

    private class FakeBrainPort : BrainPort {
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
        ): Flux<LlmDelta> = Flux.just(LlmDelta("Der Eiffelturm ist 330 Meter hoch (aus dem Hintergrund geplaudert)."))
    }

    private class CountingEscalationPort(private val answer: String, private val source: String) : EscalationPort {
        var calls = 0
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
            calls++
            return Mono.just(EscalationResult.Answer(answer, source, costCents = 0.05))
        }
    }

    @Test
    fun `dieselbe Frage - EIN Cloud-Call, der zweite Turn kommt aus dem Nachgeschlagen-Cache`(@TempDir tmp: Path) {
        val question = "Wie hoch ist der Eiffelturm?"
        val notePath = tmp.resolve("nachgeschlagen.jsonl")
        val writer = JsonlLookupNoteAdapter(notePath)
        val reader = NachgeschlagenGroundingProvider(path = notePath)
        val cloud = CountingEscalationPort("Der Eiffelturm ist 330 Meter hoch.", "Wikipedia")

        val persona = PersonaService()
        val orchestrator = TurnOrchestrator(
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
                entityMemory = { null },
                // NUR die Nachgeschlagen-Scheibe — der Beweis gilt für die Schicht selbst,
                // die volle CompositeGroundingPort-Verkettung ist CompositeGroundingPortTest.
                grounding = reader,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
            factCoverage = FactCoverageGate(enabled = true),
            escalation = cloud,
            escalationMode = { EscalationMode.AUTOMATISCH },
            lookupNotes = writer,
        )

        // Turn 1: kein Treffer ⇒ Deflect ⇒ AUTOMATISCH eskaliert direkt ⇒ 1 Cloud-Call, Notiz wird geschrieben.
        val first = orchestrator.handle(ChatRequest(text = question)).collectList().block(Duration.ofSeconds(5))!!
        assertEquals(1, cloud.calls, "Turn 1 eskaliert genau einmal")
        assertTrue(
            first.filterIsInstance<ChatEvent.TextDelta>().any { it.text.contains("330 Meter") },
            "Turn 1 trägt die Cloud-Antwort verbatim",
        )
        writer.close() // Daemon-Writer deterministisch flushen, bevor der Reader dieselbe Datei liest.

        // Turn 2: dieselbe Frage ⇒ der Nachgeschlagen-Cache deckt das Grounding ⇒
        // FactCoverageGate deflektet gar nicht erst ⇒ normaler (lokaler) Brain-Turn.
        val second = orchestrator.handle(ChatRequest(text = question)).collectList().block(Duration.ofSeconds(5))!!
        assertEquals(1, cloud.calls, "Turn 2 kommt aus dem Cache — KEIN zweiter Cloud-Call")
        val start = second.filterIsInstance<ChatEvent.Start>().first()
        assertTrue(start.grounded, "der Cache-Treffer deckt das Grounding ehrlich")
        assertEquals("brain", start.model, "Turn 2 ist ein normaler gedeckter Brain-Turn, keine Policy-Eskalation")
    }

    @Test
    fun `TTL abgelaufen - der Cache-Treffer verfaellt, dieselbe Frage eskaliert erneut`(@TempDir tmp: Path) {
        val question = "Wie hoch ist der Eiffelturm?"
        val notePath = tmp.resolve("nachgeschlagen.jsonl")
        val writer = JsonlLookupNoteAdapter(notePath)
        // Eine bereits abgelaufene Notiz direkt seeden (ttlDays=1, ts vor 10 Tagen) —
        // simuliert den Zustand NACH Ablauf, ohne 10 Tage warten zu müssen.
        writer.record(
            LookupNote(
                queryHash = "seed",
                queryNorm = LookupNoteNormalizer.normalize(question),
                answer = "Der Eiffelturm ist 330 Meter hoch.",
                source = "Wikipedia",
                provider = "openai-nano",
                costCents = 0.05,
                ts = Instant.now().minus(Duration.ofDays(10)),
                ttlDays = 1,
            ),
        )
        writer.close()

        val reader = NachgeschlagenGroundingProvider(path = notePath)
        val cloud = CountingEscalationPort("Der Eiffelturm ist 330 Meter hoch.", "Wikipedia")
        val persona = PersonaService()
        val orchestrator = TurnOrchestrator(
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
                entityMemory = { null },
                grounding = reader,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
            factCoverage = FactCoverageGate(enabled = true),
            escalation = cloud,
            escalationMode = { EscalationMode.AUTOMATISCH },
        )

        orchestrator.handle(ChatRequest(text = question)).collectList().block(Duration.ofSeconds(5))

        assertEquals(1, cloud.calls, "die abgelaufene Notiz deckt NICHT mehr ⇒ der Turn eskaliert wieder zur Cloud")
    }
}
