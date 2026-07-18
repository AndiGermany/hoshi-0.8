package de.hoshi.web

import de.hoshi.core.port.RingingItem
import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Beweist [FiredItemsRingingAdapter] — die Naht, über die [de.hoshi.core.pipeline.TimerFastpath]
 * (`core-domain`) bereits klingelnde Items aus einem [FiredItemsStore] (`web-inbound`) sieht
 * und stoppen (= ack) kann, ohne dass `core-domain` je auf `web-inbound` zeigen müsste
 * (Live-Bug Andi 2026-07-15: „Stoppe den Timer" fand ein bereits gefeuertes Item bisher
 * NICHT, weil nur der [FiredItemsStore] noch davon wusste).
 */
class FiredItemsRingingAdapterTest {

    private val now = 1_750_000_000_000L
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    @Test
    fun `ringing spiegelt die pending Items des FiredItemsStore (id, kind, label)`() {
        val fired = InMemoryFiredItemsStore()
        fired.add(FiredItem(id = "a", kind = ScheduledKind.ALARM, label = "Aufstehen", dueAtEpochMs = now, firedAtEpochMs = now))
        val adapter = FiredItemsRingingAdapter(fired, fixedClock)

        val ringing = adapter.ringing()

        assertEquals(listOf(RingingItem(id = "a", kind = ScheduledKind.ALARM, label = "Aufstehen")), ringing)
    }

    @Test
    fun `ringing ist leer, wenn nichts unbestaetigt klingelt`() {
        val adapter = FiredItemsRingingAdapter(InMemoryFiredItemsStore(), fixedClock)
        assertTrue(adapter.ringing().isEmpty())
    }

    @Test
    fun `stopRinging quittiert (ackt) das Item im FiredItemsStore - fuer alle Abfragen`() {
        val fired = InMemoryFiredItemsStore()
        fired.add(FiredItem(id = "a", kind = ScheduledKind.TIMER, dueAtEpochMs = now, firedAtEpochMs = now))
        val adapter = FiredItemsRingingAdapter(fired, fixedClock)

        val stopped = adapter.stopRinging("a")

        assertTrue(stopped, "erstes Stoppen quittiert")
        assertTrue(adapter.ringing().isEmpty(), "danach klingelt nichts mehr")
        assertTrue(fired.pending(now).isEmpty(), "auch der zugrundeliegende Store ist geleert (dieselbe Quelle)")
    }

    @Test
    fun `stopRinging einer unbekannten id ist false (kein Effekt)`() {
        val adapter = FiredItemsRingingAdapter(InMemoryFiredItemsStore(), fixedClock)
        assertFalse(adapter.stopRinging("nope"))
    }
}
