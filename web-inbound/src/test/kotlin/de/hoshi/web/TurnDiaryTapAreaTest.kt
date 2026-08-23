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
 * **Räume-Nutzungs-Naht im Diary-Tap** — direkter Beweis, dass
 * [TurnDiaryTap.traced] das additive [ChatEvent.Start.targetAreaId]-Feld
 * EHRLICH in die [TurnTrace] liest — mit einem synthetischen [ChatEvent]-
 * Strom (KEIN echter Tool-Call/Orchestrator nötig). Dass der
 * [de.hoshi.core.pipeline.TurnOrchestrator] das Feld korrekt SETZT, ist
 * separat bewiesen (`TurnOrchestratorAreaTraceTest`, core-domain). Exaktes
 * Muster [TurnDiaryTapEscalationTest].
 */
class TurnDiaryTapAreaTest {

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
            chatId = "diary-area-test",
            persona = "STANDARD",
            language = "DE",
            speak = false,
        ).collectList().block(Duration.ofSeconds(5))
        return recorder.trace.get()!!
    }

    @Test
    fun `Tool-Turn mit aufgeloester Area - targetAreaId reist ehrlich in die Trace`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMART_HOME", model = "policy", targetAreaId = "kueche"),
                ChatEvent.TextDelta("Licht an."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertEquals("kueche", trace.targetAreaId)
    }

    /**
     * **Raumname-Naht (Andi 2026-08-22):** der lesbare HA-Anzeigename reist NEBEN
     * dem Slug ins Diary — er ERSETZT ihn nicht. Der Slug bleibt die Matching-
     * Wahrheit (stabil über HA-Umbenennungen, darauf zählen die Räume-
     * Auswertungen), der Name macht die Zeile für Menschen lesbar.
     */
    @Test
    fun `Raumname reist additiv NEBEN dem Slug in die Trace`() {
        val trace = record(
            listOf(
                ChatEvent.Start(
                    provider = "LOCAL",
                    category = "SMART_HOME",
                    model = "policy",
                    targetAreaId = "kuche",
                    targetAreaName = "Küche",
                ),
                ChatEvent.TextDelta("Licht im Küche ist an."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertEquals("kuche", trace.targetAreaId, "der Slug bleibt die Matching-Wahrheit")
        assertEquals("Küche", trace.targetAreaName, "der lesbare Name reist daneben mit")
    }

    /** Kein auffindbarer Name ⇒ ehrlich `null`, NIE ein kapitalisierter Slug. */
    @Test
    fun `ohne auffindbaren Namen bleibt targetAreaName null statt verstuemmelt`() {
        val trace = record(
            listOf(
                ChatEvent.Start(
                    provider = "LOCAL",
                    category = "SMART_HOME",
                    model = "policy",
                    targetAreaId = "dachboden",
                ),
                ChatEvent.TextDelta("Ist erledigt."),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertEquals("dachboden", trace.targetAreaId)
        assertNull(trace.targetAreaName, "lieber kein Name als ein geratener, kapitalisierter Slug")
    }

    @Test
    fun `Turn ohne Tool-Area - targetAreaId bleibt ehrlich null`() {
        val trace = record(
            listOf(
                ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
                ChatEvent.TextDelta("Alles gut bei mir!"),
                ChatEvent.Done(provider = "LOCAL"),
            ),
        )
        assertNull(trace.targetAreaId, "kein Tool-Turn ⇒ nie eine erfundene Area")
    }
}
