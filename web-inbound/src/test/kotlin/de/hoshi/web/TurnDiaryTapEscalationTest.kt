package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * **Extended Think S4 im Diary-Tap** — direkter Beweis, dass [TurnDiaryTap.traced]
 * die additiven Eskalations-/Cache-Hit-Felder
 * ([ChatEvent.Start.escalated]/[ChatEvent.Start.escalationProvider]/
 * [ChatEvent.Start.cacheHit] sowie [ChatEvent.Done.escalationCostCents]) EHRLICH
 * in die [TurnTrace] liest — mit einem synthetischen [ChatEvent]-Strom (KEIN echter
 * Eskalations-Call/-Orchestrator nötig). Dass der [de.hoshi.core.pipeline.TurnOrchestrator]
 * diese Felder korrekt SETZT, ist separat bewiesen
 * (`TurnOrchestratorExtendedThinkTest`/`TurnOrchestratorCacheHitTest`, core-domain).
 */
class TurnDiaryTapEscalationTest {

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
            source = TurnDiaryTap.SOURCE_CHAT,
            chatId = "diary-escalation-test",
            persona = "STANDARD",
            language = "DE",
            speak = false,
        ).collectList().block(Duration.ofSeconds(5))
        return recorder.trace.get()!!
    }

    @Test
    fun `eskalierter Turn - escalated und die echten Kosten reisen ehrlich in die Trace`() {
        val trace = record(
            listOf(
                ChatEvent.Start(
                    provider = "LOCAL",
                    category = "FACT_SHORT",
                    model = "policy",
                    escalated = true,
                    escalationProvider = "openai-nano",
                ),
                ChatEvent.TextDelta("Ich hab online nachgeschaut: 330 Meter."),
                ChatEvent.Done(
                    provider = "LOCAL",
                    escalationCostCents = 0.05,
                    escalationQueryHash = "abc123",
                    escalationSource = "Wikipedia",
                ),
            ),
        )
        assertTrue(trace.escalated, "die Trace muss den Eskalations-Turn markieren")
        assertEquals(0.05, trace.escalationCostCents, "die echten Kosten reisen aus dem terminalen Done")
        assertFalse(trace.cacheHit, "ein Eskalations-Turn ist per Definition KEIN Cache-Hit")
        // H2: Turn↔Note-Verknüpfung reist aus dem terminalen Done.
        assertEquals("abc123", trace.escalationQueryHash)
        assertEquals("Wikipedia", trace.escalationSource)
        assertFalse(trace.escalationCapExhausted, "eine echte Answer ist keine Cap-Erschöpfung (H3)")
    }

    @Test
    fun `Cache-Hit-Turn - cacheHit=true, escalated=false, keine Kosten, Quelle aus dem Start`() {
        val trace = record(
            listOf(
                ChatEvent.Start(
                    provider = "LOCAL",
                    category = "FACT_SHORT",
                    model = "brain",
                    grounded = true,
                    cacheHit = true,
                    escalationSource = "Wikipedia",
                ),
                ChatEvent.TextDelta("Der Eiffelturm ist 330 Meter hoch."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertTrue(trace.cacheHit, "die S3-Cache-Scheibe deckte den Grounding-Block")
        assertFalse(trace.escalated, "ein Cache-Hit brauchte KEINEN Eskalations-Call")
        assertNull(trace.escalationCostCents, "kein Lookup ⇒ keine Kosten (nie eine erfundene 0.0)")
        // H2: die Cache-Hit-Quelle reist aus dem Start UND wird vom (leeren) Done
        // NICHT überschrieben (Coalesce-Vertrag von TurnDiaryTap.traced).
        assertEquals("Wikipedia", trace.escalationSource)
        assertNull(trace.escalationQueryHash, "ein Cache-Hit identifiziert die gelesene Notiz nicht per Hash")
    }

    @Test
    fun `H3 - CapExhausted-Turn - Diary unterscheidet Cap von Netzfehler`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "policy", escalated = true),
                ChatEvent.TextDelta("Ich würd's nachschauen, aber mein Online-Budget für heute ist aufgebraucht."),
                ChatEvent.Done(provider = "LOCAL", escalationCapExhausted = true),
            ),
        )
        assertTrue(trace.escalationCapExhausted, "H3: das Diary-Feld markiert die Cap-Erschöpfung")
        assertNull(trace.escalationCostCents)
    }

    @Test
    fun `normaler Turn - alle S4-Felder bleiben auf ihren ehrlichen Defaults`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
                ChatEvent.TextDelta("Alles gut bei mir!"),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertFalse(trace.escalated)
        assertFalse(trace.cacheHit)
        assertNull(trace.escalationCostCents)
        assertNull(trace.escalationQueryHash)
        assertNull(trace.escalationSource)
        assertFalse(trace.escalationCapExhausted)
    }
}
