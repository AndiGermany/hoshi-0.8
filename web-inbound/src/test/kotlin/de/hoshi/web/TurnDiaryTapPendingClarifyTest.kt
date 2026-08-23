package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Room-clarify cycle in the diary tap (F1-4) — proves [TurnDiaryTap.traced]
 * reads the additive [ChatEvent.Done.pendingClarify] honestly into the
 * [TurnTrace]. That the orchestrator SETS the field is proven separately
 * (`TurnOrchestratorAreaClarifyPendingTest`, core-domain). Shape:
 * [TurnDiaryTapClaimGateTest].
 */
class TurnDiaryTapPendingClarifyTest {

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
            chatId = "diary-clarify-test",
            persona = "STANDARD",
            language = "DE",
            speak = true,
        ).collectList().block(Duration.ofSeconds(5))
        return recorder.trace.get()!!
    }

    @Test
    fun `Clarify-Zyklus reist ehrlich in die Trace`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "policy"),
                ChatEvent.TextDelta("ok"),
                ChatEvent.Done(provider = "LOCAL", pendingClarify = "resolved"),
            ),
        )
        assertEquals("resolved", trace.pendingClarify)
    }

    @Test
    fun `Turn ohne Clarify-Zyklus - pendingClarify bleibt ehrlich null`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "policy"),
                ChatEvent.TextDelta("Licht im flur ist an."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertNull(trace.pendingClarify, "abwesend heisst nicht gemessen — nie ein erfundener Wert")
    }
}
