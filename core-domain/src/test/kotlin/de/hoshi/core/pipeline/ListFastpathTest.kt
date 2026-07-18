package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.InMemoryListStore
import de.hoshi.core.tools.ListIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist den brain-freien Vollzug [ListFastpath] mit FESTER Uhr (`Clock.fixed`):
 * ADD (inkl. Dedupe-Zähler „2× Milch"), READ (Aufzählung/ehrlich leer), REMOVE
 * (exaktes Matching, ehrlich bei Nicht-Treffer), CLEAR — DE+EN.
 */
class ListFastpathTest {

    private fun fixedClock(): Clock {
        val now = ZonedDateTime.of(2026, 7, 8, 9, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        return Clock.fixed(now.toInstant(), ZoneId.of("Europe/Berlin"))
    }

    private fun fastpath(store: InMemoryListStore): ListFastpath {
        val seq = AtomicInteger(0)
        return ListFastpath(store = store, clock = fixedClock(), idGen = { "id-${seq.incrementAndGet()}" })
    }

    /**
     * Für Tests, die MEHRERE Items anlegen und ihre relative Reihenfolge prüfen:
     * eine echte fixe Uhr würde jedem Item denselben `addedAtEpochMs` geben (in
     * der Praxis liegen zwei gesprochene Sätze nie im selben Millisekunden-Tick).
     * Jeder `.millis()`-Aufruf tickt um 1s weiter — deterministisch, aber mit
     * garantiert unterschiedlichen, aufsteigenden Zeitstempeln pro Item.
     */
    private fun fastpathAdvancing(store: InMemoryListStore): ListFastpath {
        val start = ZonedDateTime.of(2026, 7, 8, 9, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant()
        val tick = AtomicInteger(0)
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("Europe/Berlin")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): java.time.Instant = start.plusSeconds(tick.getAndIncrement().toLong())
        }
        val seq = AtomicInteger(0)
        return ListFastpath(store = store, clock = clock, idGen = { "id-${seq.incrementAndGet()}" })
    }

    private fun add(fp: ListFastpath, item: String, language: Language = Language.DE): String =
        fp.handle(ListIntent.classify("Setz $item auf die Liste.")!!, language)

    // ── ADD ──────────────────────────────────────────────────────────────────

    @Test
    fun `ADD legt ein neues Item an und quittiert mit Read-back`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)

        val phrase = add(fp, "Milch")

        val item = store.items().single()
        assertEquals("Milch", item.text)
        assertEquals(1, item.quantity)
        assertTrue(phrase.contains("Milch"), "Quittung war: $phrase")
        assertTrue(phrase.contains("drauf"), "Quittung war: $phrase")
    }

    @Test
    fun `ADD traegt addedAtEpochMs aus der injizierten Uhr`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "Milch")
        assertEquals(fixedClock().millis(), store.items().single().addedAtEpochMs)
    }

    @Test
    fun `ADD Dedupe - dasselbe Item zweimal mergt mit Zaehler 2x statt Ablehnung`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)

        add(fp, "Milch")
        val phrase = add(fp, "Milch")

        assertEquals(1, store.items().size, "kein Duplikat, EIN gemergter Eintrag")
        val item = store.items().single()
        assertEquals(2, item.quantity)
        assertEquals("Milch", item.text, "die erste Schreibweise gewinnt")
        assertEquals("Alles klar, 2× Milch steht jetzt drauf.", phrase)
    }

    @Test
    fun `ADD Dedupe ist case-insensitiv`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "milch")
        add(fp, "Milch")
        assertEquals(1, store.items().size)
        assertEquals(2, store.items().single().quantity)
    }

    @Test
    fun `ADD Dedupe dreifach ergibt 3x`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "Milch")
        add(fp, "Milch")
        val phrase = add(fp, "Milch")
        assertEquals(3, store.items().single().quantity)
        assertTrue(phrase.contains("3×"), "Quittung war: $phrase")
    }

    @Test
    fun `ADD verschiedene Items bleiben getrennt`() {
        val store = InMemoryListStore()
        val fp = fastpathAdvancing(store)
        add(fp, "Milch")
        add(fp, "Butter")
        assertEquals(2, store.items().size)
        assertEquals(listOf("Milch", "Butter"), store.items().map { it.text })
    }

    @Test
    fun `ADD Freitext-Menge bleibt als kompletter Item-Text erhalten`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        val phrase = add(fp, "500 g Hack")
        assertEquals("500 g Hack", store.items().single().text)
        assertTrue(phrase.contains("500 g Hack"), "Quittung war: $phrase")
    }

    @Test
    fun `ADD EN Quittung`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        val phrase = fp.handle(ListIntent.classify("Put milk on the list.")!!, Language.EN)
        assertTrue(phrase.contains("milk"), "Quittung war: $phrase")
        assertTrue(phrase.contains("on the list"), "Quittung war: $phrase")
    }

    // ── READ ─────────────────────────────────────────────────────────────────

    @Test
    fun `READ ohne Items ist ehrlich leer, NIE eine Gegenfrage`() {
        val fp = fastpath(InMemoryListStore())
        val phrase = fp.handle(ListIntent.classify("Was steht auf der Liste?")!!, Language.DE)
        assertEquals("Die Liste ist leer.", phrase)
        assertTrue(!phrase.contains("?"), "NIE eine Gegenfrage: $phrase")
    }

    @Test
    fun `READ zaehlt alle Items mit Dedupe-Zaehler auf`() {
        val store = InMemoryListStore()
        val fp = fastpathAdvancing(store)
        add(fp, "Milch")
        add(fp, "Milch")
        add(fp, "Butter")

        val phrase = fp.handle(ListIntent.classify("Was steht auf der Liste?")!!, Language.DE)
        assertEquals("Auf der Liste steht: 2× Milch, Butter.", phrase)
    }

    @Test
    fun `READ EN leer`() {
        val fp = fastpath(InMemoryListStore())
        val phrase = fp.handle(ListIntent.classify("What's on the list?")!!, Language.EN)
        assertEquals("The list is empty.", phrase)
    }

    @Test
    fun `READ EN mit Items`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "milk", Language.EN)
        val phrase = fp.handle(ListIntent.classify("What's on the list?")!!, Language.EN)
        assertEquals("On the list: milk.", phrase)
    }

    // ── REMOVE ───────────────────────────────────────────────────────────────

    @Test
    fun `REMOVE entfernt ein vorhandenes Item und quittiert warm`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "Milch")

        val phrase = fp.handle(ListIntent.classify("Nimm die Milch von der Liste.")!!, Language.DE)

        assertTrue(store.items().isEmpty(), "Milch sollte entfernt sein")
        assertEquals("Milch ist von der Liste runter.", phrase)
    }

    @Test
    fun `REMOVE laesst andere Items unberuehrt`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "Milch")
        add(fp, "Butter")

        fp.handle(ListIntent.classify("Nimm die Milch von der Liste.")!!, Language.DE)

        assertEquals(listOf("Butter"), store.items().map { it.text })
    }

    @Test
    fun `REMOVE ohne Treffer ist ehrlich statt zu raten, Store unveraendert`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "Milch")

        val phrase = fp.handle(ListIntent.classify("Nimm den Käse von der Liste.")!!, Language.DE)

        assertEquals("Käse steht gar nicht auf der Liste.", phrase)
        assertEquals(1, store.items().size, "kein Treffer ⇒ nichts entfernt")
    }

    @Test
    fun `REMOVE ist exakt - Ei matcht NICHT Eis (kein Fuzzy-Substring)`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "Eis")

        val phrase = fp.handle(ListIntent.classify("Entfern Ei von der Liste.")!!, Language.DE)

        assertEquals("Ei steht gar nicht auf der Liste.", phrase)
        assertEquals(1, store.items().size, "Eis darf durch das aehnliche Ei NICHT versehentlich getroffen werden")
    }

    @Test
    fun `REMOVE EN`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "milk", Language.EN)

        val phrase = fp.handle(ListIntent.classify("Remove milk from the list.")!!, Language.EN)

        assertTrue(store.items().isEmpty())
        assertEquals("Removed milk from the list.", phrase)
    }

    @Test
    fun `REMOVE EN ohne Treffer`() {
        val fp = fastpath(InMemoryListStore())
        val phrase = fp.handle(ListIntent.classify("Remove milk from the list.")!!, Language.EN)
        assertEquals("milk isn't on the list.", phrase)
    }

    // ── CLEAR (REMOVE mit all=true) ───────────────────────────────────────────

    @Test
    fun `CLEAR leert die Liste und nennt die Anzahl`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "Milch")
        add(fp, "Butter")

        val phrase = fp.handle(ListIntent.classify("Liste leeren.")!!, Language.DE)

        assertTrue(store.items().isEmpty())
        assertEquals("Okay, die Liste ist jetzt leer (2 gelöscht).", phrase)
    }

    @Test
    fun `CLEAR auf leerer Liste ist ehrlich schon leer`() {
        val fp = fastpath(InMemoryListStore())
        val phrase = fp.handle(ListIntent.classify("Liste leeren.")!!, Language.DE)
        assertEquals("Die Liste ist schon leer.", phrase)
    }

    @Test
    fun `CLEAR EN`() {
        val store = InMemoryListStore()
        val fp = fastpath(store)
        add(fp, "milk", Language.EN)
        val phrase = fp.handle(ListIntent.classify("Clear the list.")!!, Language.EN)
        assertTrue(store.items().isEmpty())
        assertEquals("Okay, cleared the list — 1 item gone.", phrase)
    }
}
