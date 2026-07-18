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
 * **Die EN-Deflect/Angebot/Consent-Kette durchgespielt** (Andi-Auftrag 2026-07-20,
 * Sprachpaket-Kern — Beweis-Vorgabe „EN: Deflect/Angebot/Consent-Kette auf
 * Englisch durchgespielt"). Orchestrator-Test-Muster von
 * [TurnOrchestratorLookupIntentTest] Fall (1)/(10), NUR mit `language = Language.EN`:
 *
 *  1. **Deflect** ([FactCoverageGate.deflection]): eine ungedeckte FACT_SHORT-Frage
 *     bekommt [FactCoverageGate.DEFLECT_EN] — NICHT die deutsche Phrase — und
 *     registriert dabei implizit das Angebot (die Deflect-Phrase selbst endet als
 *     Frage „…want me to look it up?").
 *  2. **Angebot einlösen**: „look it up online" ([LookupIntentRecognizer], bereits
 *     DE+EN geteilt) löst das offene Angebot mit der ORIGINAL-Frage ein.
 *  3. **Consent-Brücke** ([ResponseFormatter.cloudConsentAccept]): der englische
 *     [de.hoshi.core.pipeline.lang.LangEn]-Pool liefert die Brücken-Phrase (Event-
 *     Index 1, s. KDoc [TurnOrchestrator.escalationTurn]) — NICHT die deutsche.
 *  4. **Eskalations-Rahmung** ([TurnOrchestrator.escalationAnswerFrame]): „I looked
 *     it up online: " vor der attribuierten Antwort.
 *
 * Beweist: [Language.EN] fließt tatsächlich bis in ALLE drei Phrasen-Kategorien
 * durch — nicht nur theoretisch als Parameter durchgestochen.
 */
class TurnOrchestratorEnglishConsentChainTest {

    private val question = "Wie hoch ist der Eiffelturm?"
    private val cloudAnswer = "The Eiffel Tower is 330 meters tall."
    private val cloudSource = "Wikipedia"

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

    private class RecordingEscalationPort(
        private val result: () -> Mono<EscalationResult>,
    ) : EscalationPort {
        val queries = mutableListOf<String>()
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
            queries += query
            return result()
        }
    }

    private fun orchestrator(brain: FakeBrainPort, escalation: EscalationPort): TurnOrchestrator {
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
                grounding = { _, _ -> Mono.just("") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            factCoverage = FactCoverageGate(enabled = true),
            escalation = escalation,
            pendingLookup = InMemoryPendingLookupStore(),
            escalationMode = { EscalationMode.ERST_FRAGEN },
            lookupIntentEnabled = true,
        )
    }

    private fun turn(o: TurnOrchestrator, text: String, history: List<ChatMessage> = emptyList()): List<ChatEvent> =
        o.handle(ChatRequest(text = text, history = history, language = Language.EN)).collectList().block(Duration.ofSeconds(5))!!

    private fun joinedText(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun `Deflect-Angebot-Consent-Kette laeuft komplett auf Englisch`() {
        val brain = FakeBrainPort()
        val cloud = RecordingEscalationPort {
            Mono.just(EscalationResult.Answer(cloudAnswer, cloudSource, costCents = 0.05))
        }
        val o = orchestrator(brain, cloud)

        // ── (1) Deflect: die ENGLISCHE Phrase, nicht die deutsche ──
        val deflect = turn(o, question)
        assertEquals(
            FactCoverageGate.DEFLECT_EN,
            joinedText(deflect),
            "Deflect muss der EN-Phrase folgen, nicht der DE-Konstante",
        )
        assertFalse(joinedText(deflect).contains(FactCoverageGate.DEFLECT_DE), "keine deutsche Deflect-Phrase")
        assertEquals(0, brain.callCount.get(), "Deflect ist brain-frei")
        assertEquals(0, cloud.queries.size, "ERST_FRAGEN eskaliert nicht ungefragt")

        // ── (2)+(3) Angebot einlösen (EN-Lookup-Intent) + Consent-Bruecke (EN-Pool) ──
        val resolved = turn(o, "look it up online")
        assertEquals(listOf(question), cloud.queries, "die ORIGINAL-Frage wird eskaliert, nicht die Bitte selbst")
        assertEquals(0, brain.callCount.get(), "der Intent-Turn bleibt brain-frei")

        val bridge = (resolved[1] as ChatEvent.TextDelta).text
        assertTrue(
            de.hoshi.core.pipeline.lang.LangEn.PACK.cloudConsentAccept.contains(bridge),
            "die Consent-Bruecke muss aus dem ENGLISCHEN Pool kommen: '$bridge'",
        )
        assertFalse(
            de.hoshi.core.pipeline.lang.LangDe.PACK.cloudConsentAccept.contains(bridge),
            "darf NICHT aus dem deutschen Pool kommen: '$bridge'",
        )

        // ── (4) Eskalations-Rahmung: EN-Frame vor der attribuierten Antwort ──
        val full = joinedText(resolved)
        assertTrue(
            full.contains(TurnOrchestrator.ESCALATION_FRAME_EN + cloudAnswer),
            "EN-Rahmung + attribuierte Antwort erwartet, war: '$full'",
        )
        assertFalse(full.contains(TurnOrchestrator.ESCALATION_FRAME_DE), "keine deutsche Rahmung")
    }
}
