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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **brainTtftMs-Messpunkt (Perf-Diary)** — deterministisch mit Fake-Clock:
 * Brain-Call-Start (Subscribe) → ERSTE TextDelta, gemessen im [TurnOrchestrator]
 * an der EINEN Brain-Naht; reist zusammen mit dem [TurnPromptAssembler]-
 * groundingMs additiv im [ChatEvent.Done.stageTimings] an den Rand.
 * Ehrlichkeit: leerer Brain-Stream ⇒ brainTtftMs=null (Grounding-Messung bleibt);
 * Policy-Pfade (kein Brain, kein Grounding) ⇒ stageTimings=null ⇒ das Done ist
 * byte-identisch zu heute.
 */
class TurnOrchestratorStageTimingsTest {

    private class FakeNano(vararg ticks: Long) : () -> Long {
        private val queue = ArrayDeque(ticks.toList())
        private var last = ticks.last()
        override fun invoke(): Long = queue.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class FakeBrainPort(
        private val deltas: List<String>,
        /** Non-null ⇒ the stream fails instead of emitting (adapter error path). */
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
        ): Flux<LlmDelta> =
            error?.let { Flux.error(it) } ?: Flux.fromIterable(deltas.map { LlmDelta(it) })
    }

    /** Realer Orchestrator (FACT_SHORT/LOCAL) mit Fake-Brain + zwei Fake-Uhren. */
    private fun orchestrator(
        brainDeltas: List<String>,
        orchestratorNano: () -> Long,
        assemblerNano: () -> Long,
        brainError: Throwable? = null,
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
                grounding = GroundingPort.fixed("\n\nHINTERGRUND: 330 Meter."),
                episodicMemory = null,
                nanoTime = assemblerNano,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(brainDeltas, brainError),
            nanoTime = orchestratorNano,
        )
    }

    private fun done(events: List<ChatEvent>): ChatEvent.Done =
        events.filterIsInstance<ChatEvent.Done>().single()

    @Test
    fun `brain-ttft und grounding-dauer reisen gepinnt im Done-stageTimings`() {
        val events = orchestrator(
            brainDeltas = listOf("Der Eiffelturm ", "ist 330 Meter hoch."),
            // Aufruf 1 = doOnSubscribe (t0=1s), Aufruf 2 = erste Delta (1,25s) ⇒ TTFT 250ms.
            orchestratorNano = FakeNano(1_000_000_000L, 1_250_000_000L),
            // Aufruf 1 = t0(0), Aufruf 2 = Grounding-Antwort (42ms).
            assemblerNano = FakeNano(0L, 42_000_000L),
        ).handle(ChatRequest(text = "Wie hoch ist der Eiffelturm?"))
            .collectList().block(Duration.ofSeconds(5))!!

        val timings = done(events).stageTimings!!
        assertEquals(250L, timings.brainTtftMs, "Brain-Subscribe→erste Delta, exakt aus der Fake-Uhr")
        assertEquals(42L, timings.groundingMs, "die Assembler-Messung reist mit an den Rand")
        assertNull(timings.ttsFirstAudioMs, "keine TtsStage in diesem Turn ⇒ null")
        assertNull(timings.admissionWaitMs, "kein Gate in diesem Turn ⇒ null")
    }

    @Test
    fun `leerer brain-stream - ttft ehrlich null, grounding-messung bleibt (never-silent-Done)`() {
        val events = orchestrator(
            brainDeltas = emptyList(),
            orchestratorNano = FakeNano(1_000_000_000L),
            assemblerNano = FakeNano(0L, 42_000_000L),
        ).handle(ChatRequest(text = "Wie hoch ist der Eiffelturm?"))
            .collectList().block(Duration.ofSeconds(5))!!

        val timings = done(events).stageTimings!!
        assertNull(timings.brainTtftMs, "nie eine Delta gesehen ⇒ null — NIE ein erfundenes 0")
        assertEquals(42L, timings.groundingMs)
    }

    @Test
    fun `policy-pfad (leere eingabe) - stageTimings null - Done byte-identisch zu heute`() {
        val events = orchestrator(
            brainDeltas = listOf("egal"),
            orchestratorNano = FakeNano(0L),
            assemblerNano = FakeNano(0L),
        ).handle(ChatRequest(text = "   "))
            .collectList().block(Duration.ofSeconds(5))!!

        assertNull(done(events).stageTimings, "kein Brain, kein Grounding ⇒ kein Timings-Objekt")
    }

    /**
     * Brain-Timeout: bis 14.08. sah ein 30-s-Wedge im Diary exakt aus wie „Turn
     * ohne Brain" (brainTtftMs=null in BEIDEN Fällen) — acht reale Stalls blieben
     * sieben Wochen unsichtbar (MESSUNG-latenz-diary-2026-08-13, KORREKTUR).
     * Der Adapter verpackt die `Flux.timeout`-[TimeoutException] in seinen eigenen
     * „nicht erreichbar"-Fehler, darum die Ursachen-Kette im Test.
     */
    @Test
    fun `brain-timeout traegt sich explizit ins Done - brainTtft bleibt ehrlich null`() {
        val events = orchestrator(
            brainDeltas = emptyList(),
            orchestratorNano = FakeNano(1_000_000_000L),
            assemblerNano = FakeNano(0L, 42_000_000L),
            brainError = RuntimeException(
                "MLX-Brain (/v1/chat) nicht erreichbar",
                java.util.concurrent.TimeoutException("Did not observe any item within 20000ms"),
            ),
        ).handle(ChatRequest(text = "Wie hoch ist der Eiffelturm?"))
            .collectList().block(Duration.ofSeconds(5))!!

        val timings = done(events).stageTimings!!
        assertEquals(true, timings.brainTimeout, "der Timeout MUSS am Done stehen — sonst ist der Wedge unsichtbar")
        assertNull(timings.brainTtftMs, "nie eine Delta gesehen ⇒ null — der Timeout erklärt jetzt WARUM")
        assertEquals(42L, timings.groundingMs, "die Grounding-Messung bleibt unberührt")
        // Never-Silent unberührt: der Turn endet weiterhin in der warmen Fehler-Phrase.
        assertEquals(
            TurnOrchestrator.ERROR_FALLBACK_DE,
            events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `nicht-timeout-fehler faerbt das feld NICHT - kein erfundener timeout`() {
        val events = orchestrator(
            brainDeltas = emptyList(),
            orchestratorNano = FakeNano(1_000_000_000L),
            assemblerNano = FakeNano(0L, 42_000_000L),
            brainError = IllegalStateException("Sidecar 500"),
        ).handle(ChatRequest(text = "Wie hoch ist der Eiffelturm?"))
            .collectList().block(Duration.ofSeconds(5))!!

        assertNull(done(events).stageTimings!!.brainTimeout, "kein Timeout ⇒ Feld fehlt (Done byte-identisch)")
    }
}
