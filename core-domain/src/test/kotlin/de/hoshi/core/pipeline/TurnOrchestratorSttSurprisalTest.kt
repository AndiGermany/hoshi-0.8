package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.SttSurprisal
import de.hoshi.core.port.SttSurprisalPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Verhör-Detektor am Done-Event (S1, MESSEN-first)** — mathematisch GEPINNT,
 * exakt das Muster von [TurnOrchestratorAnswerEntropyTest]:
 *
 *  - Port `null` (Default, Flag OFF) ⇒ [ChatEvent.StageTimings.sttSurprisal]/
 *    `sttSurprisalMax` bleiben `null`, KEIN Call.
 *  - Port gesetzt, ABER `source` kein Voice-Transkript (`"chat"`/`null`) ⇒
 *    ebenfalls KEIN Call, Felder `null` (der Chat-Rand hat kein STT).
 *  - Port gesetzt + `source="voice"` ⇒ die Werte reisen EXAKT durch.
 *  - Port wirft/liefert leer ⇒ der Turn ist UNBEEINTRÄCHTIGT (never-silent,
 *    normale TextDelta + Done), Felder ehrlich `null`.
 */
class TurnOrchestratorSttSurprisalTest {

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
        ): Flux<LlmDelta> = Flux.just(LlmDelta("Der Eiffelturm ist 330 Meter hoch."))
    }

    /** Zählt Calls + liefert ein konfigurierbares Ergebnis (Wert/leer/Fehler). */
    private class FakeSttSurprisalPort(
        private val result: SttSurprisal? = null,
        private val throwError: Boolean = false,
    ) : SttSurprisalPort {
        val calls = AtomicInteger(0)
        override fun score(text: String): Mono<SttSurprisal> {
            calls.incrementAndGet()
            return when {
                throwError -> Mono.error(RuntimeException("Score-Sidecar boom"))
                result != null -> Mono.just(result)
                else -> Mono.empty()
            }
        }
    }

    /** Realer Orchestrator (FACT_SHORT/LOCAL) mit Fake-Brain — Muster [TurnOrchestratorAnswerEntropyTest]. */
    private fun orchestrator(sttSurprisal: SttSurprisalPort?): TurnOrchestrator {
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
            brain = FakeBrainPort(),
            sttSurprisal = sttSurprisal,
        )
    }

    private fun doneOf(request: ChatRequest, sttSurprisal: SttSurprisalPort?): ChatEvent.Done =
        orchestrator(sttSurprisal)
            .handle(request)
            .collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Done>().single()

    @Test
    fun `Port null (Flag OFF) - sttSurprisal-Felder bleiben null`() {
        val done = doneOf(ChatRequest(text = "wie hoch ist der eiffelturm", source = "voice"), sttSurprisal = null)
        assertNull(done.stageTimings?.sttSurprisal)
        assertNull(done.stageTimings?.sttSurprisalMax)
    }

    @Test
    fun `Port gesetzt aber source=chat (kein Voice-Transkript) - KEIN Call, Felder null`() {
        val port = FakeSttSurprisalPort(result = SttSurprisal(0.5, 1.2, 4))
        val done = doneOf(ChatRequest(text = "wie hoch ist der eiffelturm", source = "chat"), sttSurprisal = port)
        assertEquals(0, port.calls.get(), "Chat-Rand hat kein STT - der Port darf nicht gerufen werden")
        assertNull(done.stageTimings?.sttSurprisal)
    }

    @Test
    fun `Port gesetzt aber source=null (Alt-Client) - KEIN Call, Felder null`() {
        val port = FakeSttSurprisalPort(result = SttSurprisal(0.5, 1.2, 4))
        val done = doneOf(ChatRequest(text = "wie hoch ist der eiffelturm", source = null), sttSurprisal = port)
        assertEquals(0, port.calls.get())
        assertNull(done.stageTimings?.sttSurprisal)
    }

    @Test
    fun `Port liefert Werte bei source=voice - die Felder reisen EXAKT durch`() {
        val port = FakeSttSurprisalPort(result = SttSurprisal(meanSurprisal = 0.73, maxSurprisal = 2.4, tokenCount = 5))
        val done = doneOf(ChatRequest(text = "wie hoch ist der eiffelturm", source = "voice"), sttSurprisal = port)
        assertEquals(1, port.calls.get())
        assertEquals(0.73, done.stageTimings?.sttSurprisal)
        assertEquals(2.4, done.stageTimings?.sttSurprisalMax)
    }

    @Test
    fun `Port liefert Werte bei source=ws - dieselbe Naht wie voice`() {
        val port = FakeSttSurprisalPort(result = SttSurprisal(meanSurprisal = 0.11, maxSurprisal = 0.9, tokenCount = 3))
        val done = doneOf(ChatRequest(text = "wie hoch ist der eiffelturm", source = "ws"), sttSurprisal = port)
        assertEquals(0.11, done.stageTimings?.sttSurprisal)
        assertEquals(0.9, done.stageTimings?.sttSurprisalMax)
    }

    @Test
    fun `Port liefert leer (Mono empty, z_B_ 404-Endpoint) - Turn unbeeintraechtigt, Felder null`() {
        val port = FakeSttSurprisalPort(result = null)
        val events = orchestrator(port)
            .handle(ChatRequest(text = "wie hoch ist der eiffelturm", source = "voice"))
            .collectList().block(Duration.ofSeconds(5))!!
        val done = events.filterIsInstance<ChatEvent.Done>().single()
        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("330 Meter"), "der normale Brain-Text bleibt unangetastet")
        assertNull(done.stageTimings?.sttSurprisal)
    }

    @Test
    fun `Port wirft - Turn unbeeintraechtigt (never-silent), Felder null`() {
        val port = FakeSttSurprisalPort(throwError = true)
        val events = orchestrator(port)
            .handle(ChatRequest(text = "wie hoch ist der eiffelturm", source = "voice"))
            .collectList().block(Duration.ofSeconds(5))!!
        val done = events.filterIsInstance<ChatEvent.Done>().single()
        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("330 Meter"), "ein Score-Fehler darf den Brain-Text nie beruehren")
        assertNull(done.stageTimings?.sttSurprisal)
        assertNull(done.stageTimings?.sttSurprisalMax)
    }

    @Test
    fun `leerer Text (source=voice) - KEIN Call, kein Crash`() {
        val port = FakeSttSurprisalPort(result = SttSurprisal(0.5, 1.2, 4))
        // Leerer Text lauft ueber den warmDirectAnswer-EMPTY-Pfad (handleTurn), NICHT
        // ueber den Brain - hier zaehlt nur: withSttSurprisal ruft den Port nicht.
        orchestrator(port)
            .handle(ChatRequest(text = "", source = "voice"))
            .collectList().block(Duration.ofSeconds(5))
        assertEquals(0, port.calls.get())
    }
}
