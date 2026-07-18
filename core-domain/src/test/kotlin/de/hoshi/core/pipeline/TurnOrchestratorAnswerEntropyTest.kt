package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **Antwort-Entropie am Done-Event (S1, MESSEN-first)** — mathematisch GEPINNT:
 * der [TurnOrchestrator] mittelt die Token-Surprisals (−logprob) der Brain-
 * Deltas laufend (Summe+Zähler, keine Liste) und legt −mean(logprob) als
 * [ChatEvent.StageTimings.answerEntropy] an den Rand.
 *
 * Ehrlichkeits-Vertrag:
 *  - bekannte Logprobs ⇒ EXAKTER Mittelwert (binär-exakte Doubles, kein Delta),
 *  - Deltas OHNE logprob zählen NICHT mit (nie ein erfundener Beitrag),
 *  - GAR keine logprobs (heutiges Prod-server_e4b / Flag OFF) ⇒ answerEntropy
 *    null — die übrigen Timings (brainTtft/grounding) bleiben unberührt,
 *  - leerer Brain-Stream ⇒ null (nie ein erfundenes 0).
 */
class TurnOrchestratorAnswerEntropyTest {

    private class FakeBrainPort(private val deltas: List<LlmDelta>) : BrainPort {
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
        ): Flux<LlmDelta> = Flux.fromIterable(deltas)
    }

    /** Realer Orchestrator (FACT_SHORT/LOCAL) mit Fake-Brain — Muster [TurnOrchestratorStageTimingsTest]. */
    private fun orchestrator(deltas: List<LlmDelta>): TurnOrchestrator {
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
                grounding = { _, _ -> Mono.just("\n\nHINTERGRUND: 330 Meter.") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(deltas),
        )
    }

    private fun doneTimings(deltas: List<LlmDelta>): ChatEvent.StageTimings? =
        orchestrator(deltas)
            .handle(ChatRequest(text = "Wie hoch ist der Eiffelturm?"))
            .collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Done>().single()
            .stageTimings

    @Test
    fun `bekannte logprobs ergeben den EXAKTEN mittelwert der surprisals`() {
        // −(−0.5) = 0.5 und −(−1.5) = 1.5 ⇒ Mittel EXAKT 1.0 (binär-exakt, kein Rundungs-Delta).
        val timings = doneTimings(
            listOf(
                LlmDelta("Der Eiffelturm ", logprob = -0.5),
                LlmDelta("ist 330 Meter hoch.", logprob = -1.5),
            ),
        )!!
        assertEquals(1.0, timings.answerEntropy, "answerEntropy = −mean(logprob), mathematisch gepinnt")
    }

    @Test
    fun `deltas ohne logprob zaehlen nicht mit - mittelwert nur ueber gemessene tokens`() {
        // Nur −0.25 und −0.75 sind gemessen ⇒ Mittel EXAKT 0.5; das logprob-lose
        // Delta traegt NICHT bei (nie ein erfundener 0-Beitrag, der verduennt).
        val timings = doneTimings(
            listOf(
                LlmDelta("Der ", logprob = -0.25),
                LlmDelta("Turm ", logprob = null),
                LlmDelta("steht.", logprob = -0.75),
            ),
        )!!
        assertEquals(0.5, timings.answerEntropy)
    }

    @Test
    fun `ohne logprobs (heutiges prod) bleibt answerEntropy null - uebrige timings unberuehrt`() {
        val timings = doneTimings(
            listOf(LlmDelta("Der Eiffelturm "), LlmDelta("ist 330 Meter hoch.")),
        )!!
        assertNull(timings.answerEntropy, "nichts gemessen ⇒ null — NIE ein erfundenes 0")
        assertNotNull(timings.brainTtftMs, "die TTFT-Messung läuft unverändert weiter")
        assertNotNull(timings.groundingMs, "die Grounding-Messung läuft unverändert weiter")
    }

    @Test
    fun `leerer brain-stream - answerEntropy ehrlich null am never-silent-Done`() {
        val timings = doneTimings(emptyList())!!
        assertNull(timings.answerEntropy)
    }
}
