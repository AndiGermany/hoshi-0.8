package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * **Tool-executor seam in the diary tap** — proves that [TurnDiaryTap.traced] reads the
 * additive [ChatEvent.Done.toolCallRan] honestly into the [TurnTrace], with a synthetic
 * event stream (no orchestrator needed). That the orchestrator SETS the field is proven
 * separately (`TurnOrchestratorToolCallRanTest`, core-domain). Shape:
 * [TurnDiaryTapClaimGateTest].
 */
class TurnDiaryTapToolCallRanTest {

    private class RecordingTrace : TurnTracePort {
        val trace = AtomicReference<TurnTrace?>(null)
        override fun record(trace: TurnTrace) {
            this.trace.set(trace)
        }
    }

    private fun record(events: List<ChatEvent>): TurnTrace {
        val recorder = RecordingTrace()
        TurnDiaryTap.traced(
            turnTrace = recorder,
            stream = Flux.fromIterable(events),
            source = TurnDiaryTap.SOURCE_VOICE,
            chatId = "diary-toolran-test",
            persona = "STANDARD",
            language = "DE",
            speak = true,
        ).collectList().block(Duration.ofSeconds(5))
        return recorder.trace.get()!!
    }

    @Test
    fun `gelaufener Executor reist ehrlich in die Trace`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "policy", targetAreaId = "wohnzimmer"),
                ChatEvent.TextDelta("Licht im wohnzimmer ist an."),
                ChatEvent.Done(provider = "LOCAL", toolCallRan = true),
            ),
        )
        assertTrue(trace.toolCallRan)
    }

    @Test
    fun `Turn ohne Executor - toolCallRan bleibt ehrlich false`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "brain"),
                ChatEvent.TextDelta("Kyoto war bis 1868 die Hauptstadt."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertFalse(trace.toolCallRan, "kein Executor ⇒ nie ein erfundenes true")
    }

    /**
     * The pair Codex' metric needs: a DENY turn that nevertheless claims a switching
     * act ends with `claimGateFired=false` (the latch only guards the brain path) AND
     * `toolCallRan=false` — that combination is what makes the claim falsifiable.
     */
    @Test
    fun `DENY-Turn - weder Riegel noch Executor - beide Felder ehrlich false`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "policy", targetAreaId = "flur"),
                ChatEvent.TextDelta("Das mache ich gerade nicht."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertFalse(trace.toolCallRan)
        assertFalse(trace.claimGateFired)
    }
}
