package de.hoshi.core.pipeline

import de.hoshi.core.port.InMemoryScheduledItemStore
import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Beweist set/query/cancel/cancelAll der thread-sicheren In-Mem-[InMemoryScheduledItemStore]. */
class ScheduledItemStoreTest {

    private fun item(id: String, dueAt: Long, label: String? = null) =
        ScheduledItem(id = id, kind = ScheduledKind.TIMER, dueAtEpochMs = dueAt, label = label)

    @Test
    fun `set dann query liefert das Item`() {
        val store = InMemoryScheduledItemStore()
        store.set(item("a", 1000))
        assertEquals(listOf("a"), store.query().map { it.id })
    }

    @Test
    fun `query ist nach Faelligkeit sortiert`() {
        val store = InMemoryScheduledItemStore()
        store.set(item("late", 3000))
        store.set(item("early", 1000))
        store.set(item("mid", 2000))
        assertEquals(listOf("early", "mid", "late"), store.query().map { it.id })
    }

    @Test
    fun `cancel entfernt genau ein Item`() {
        val store = InMemoryScheduledItemStore()
        store.set(item("a", 1000))
        store.set(item("b", 2000))
        assertTrue(store.cancel("a"))
        assertEquals(listOf("b"), store.query().map { it.id })
    }

    @Test
    fun `cancel auf unbekannte id ist false`() {
        val store = InMemoryScheduledItemStore()
        assertFalse(store.cancel("nope"))
    }

    @Test
    fun `cancelAll leert und zaehlt`() {
        val store = InMemoryScheduledItemStore()
        store.set(item("a", 1000))
        store.set(item("b", 2000))
        assertEquals(2, store.cancelAll())
        assertTrue(store.query().isEmpty())
    }

    // ── originSatelliteId (PREP-wecker-am-satelliten) — additiv, Default null ───

    @Test
    fun `ScheduledItem ohne originSatelliteId (Legacy-Konstruktion) ist null`() {
        // Konstruktion wie ein VOR dieser Naht gebautes Item (kein 6. Argument) —
        // beweist, dass der Default additiv/rueckwaerts-kompatibel bleibt.
        val legacy = ScheduledItem(id = "alt", kind = ScheduledKind.TIMER, dueAtEpochMs = 1000)
        assertEquals(null, legacy.originSatelliteId)
    }

    @Test
    fun `set dann query traegt originSatelliteId unveraendert durch den Store`() {
        val store = InMemoryScheduledItemStore()
        store.set(
            ScheduledItem(
                id = "a", kind = ScheduledKind.ALARM, dueAtEpochMs = 1000,
                origin = "kueche-tab", originSatelliteId = "sat-kueche",
            ),
        )
        val reloaded = store.query().single()
        assertEquals("kueche-tab", reloaded.origin)
        assertEquals("sat-kueche", reloaded.originSatelliteId)
    }
}
