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
 * **Execution-claim latch in the diary tap** — proves that [TurnDiaryTap.traced]
 * reads the additive [ChatEvent.Done.claimGateFired] honestly into the
 * [TurnTrace], with a synthetic event stream (no orchestrator needed). That the
 * orchestrator SETS the field is proven separately
 * (`TurnOrchestratorExecutionClaimTest`, core-domain). Shape: [TurnDiaryTapAreaTest].
 */
class TurnDiaryTapClaimGateTest {

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
            chatId = "diary-claim-test",
            persona = "STANDARD",
            language = "DE",
            speak = true,
        ).collectList().block(Duration.ofSeconds(5))
        return recorder.trace.get()!!
    }

    @Test
    fun `gefeuerter Riegel reist ehrlich in die Trace`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "brain"),
                ChatEvent.TextDelta("Das habe ich nicht sicher als Schaltbefehl verstanden — magst du es nochmal sagen?"),
                ChatEvent.Done(provider = "LOCAL", claimGateFired = true),
            ),
        )
        assertTrue(trace.claimGateFired)
    }

    @Test
    fun `Turn ohne Riegel-Fall - claimGateFired bleibt ehrlich false`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "policy", targetAreaId = "flur"),
                ChatEvent.TextDelta("Licht im flur ist an."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertFalse(trace.claimGateFired, "kein Riegel-Fall ⇒ nie ein erfundenes true")
    }
}
