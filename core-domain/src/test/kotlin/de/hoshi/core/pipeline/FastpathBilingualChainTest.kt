package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.InMemoryListStore
import de.hoshi.core.port.InMemoryScheduledItemStore
import de.hoshi.core.tools.ListIntent
import de.hoshi.core.tools.TimerIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Ein Nutzer, eine Sprache, die ganze Kette.** Die Intent-Tests beweisen die
 * ERKENNUNG einzelner Sätze; diese Datei spielt nach, was Andi tatsächlich tut:
 * stellen → nachfragen → stoppen, und zwar auf Deutsch UND auf Englisch, jeweils
 * durch die echten Erkenner ([TimerIntent]/[ListIntent]) IN die echten Fastpaths.
 *
 * Nur so fällt auf, wenn Erkennung und Quittung auseinanderlaufen — der Befund,
 * der diese Runde ausgelöst hat: Hoshi ANTWORTETE bereits englisch, VERSTAND aber
 * nur deutsch. Deshalb prüft jeder Schritt auch die SPRACHE der Quittung.
 */
class FastpathBilingualChainTest {

    private val zone = ZoneId.of("Europe/Berlin")

    /** Uhr, die auf Zuruf weiterspringt — sonst wäre die Restzeit immer die volle Dauer. */
    private class MovableClock(start: Instant, private val zone: ZoneId) : Clock() {
        var now: Instant = start
        fun advanceMinutes(minutes: Long) {
            now = now.plusSeconds(minutes * 60)
        }
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun clockAt(hour: Int, minute: Int = 0) =
        MovableClock(ZonedDateTime.of(2026, 7, 25, hour, minute, 0, 0, zone).toInstant(), zone)

    private fun timerFastpath(store: InMemoryScheduledItemStore, clock: Clock): TimerFastpath {
        val seq = AtomicInteger(0)
        return TimerFastpath(store = store, clock = clock, idGen = { "id-${seq.incrementAndGet()}" })
    }

    private fun listFastpath(store: InMemoryListStore, clock: Clock): ListFastpath {
        val seq = AtomicInteger(0)
        return ListFastpath(store = store, clock = clock, idGen = { "id-${seq.incrementAndGet()}" })
    }

    /** Ein gesprochener Satz durch den Timer-Erkenner in den Timer-Fastpath. */
    private fun say(fp: TimerFastpath, text: String, language: Language): String {
        val call = TimerIntent.classify(text)
        assertNotNull(call, "nicht erkannt: $text")
        return fp.handle(call!!, language)
    }

    /** Ein gesprochener Satz durch den Listen-Erkenner in den Listen-Fastpath. */
    private fun say(fp: ListFastpath, text: String, language: Language): String {
        val call = ListIntent.classify(text)
        assertNotNull(call, "nicht erkannt: $text")
        return fp.handle(call!!, language)
    }

    // ── Timer: stellen → abfragen → stoppen ──────────────────────────────────

    @Test
    fun `englische Timer-Kette - stellen abfragen stoppen`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(9)
        val fp = timerFastpath(store, clock)

        val set = say(fp, "set a timer for ten minutes", Language.EN)
        assertEquals("Got it, timer for 10 minutes is running.", set)
        assertEquals(1, store.query().size)

        clock.advanceMinutes(4)
        val query = say(fp, "how long is left", Language.EN)
        assertEquals("6 minutes left.", query)

        val cancel = say(fp, "stop the timer", Language.EN)
        assertEquals("Stopped.", cancel)
        assertTrue(store.query().isEmpty(), "Store war danach nicht leer")
    }

    @Test
    fun `deutsche Timer-Kette - stellen abfragen stoppen`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(9)
        val fp = timerFastpath(store, clock)

        val set = say(fp, "stell einen Timer auf zehn Minuten", Language.DE)
        assertEquals("Alles klar, Timer für 10 Minuten läuft.", set)
        assertEquals(1, store.query().size)

        clock.advanceMinutes(4)
        val query = say(fp, "wie lange läuft der Timer noch", Language.DE)
        assertEquals("Noch 6 Minuten.", query)

        val cancel = say(fp, "stopp den Timer", Language.DE)
        assertEquals("Gestoppt.", cancel)
        assertTrue(store.query().isEmpty(), "Store war danach nicht leer")
    }

    // ── Wecker: stellen → abfragen → stoppen ─────────────────────────────────

    @Test
    fun `englische Wecker-Kette - quarter past seven`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = timerFastpath(store, clock)

        val set = say(fp, "set an alarm for quarter past seven", Language.EN)
        assertEquals("Alarm set for 07:15.", set)

        val query = say(fp, "when does the alarm go off", Language.EN)
        assertEquals("Your alarm rings at 07:15 — one hour and 15 minutes to go.", query)

        assertEquals("Stopped.", say(fp, "cancel the alarm", Language.EN))
        assertTrue(store.query().isEmpty(), "Store war danach nicht leer")
    }

    @Test
    fun `deutsche Wecker-Kette - viertel nach sieben`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = timerFastpath(store, clock)

        val set = say(fp, "weck mich um viertel nach sieben", Language.DE)
        assertEquals("Alles klar, ich weck dich um 07:15 Uhr.", set)

        val query = say(fp, "wann klingelt der Wecker", Language.DE)
        assertEquals("Dein Wecker klingelt um 07:15 Uhr — noch eine Stunde und 15 Minuten.", query)

        assertEquals("Gestoppt.", say(fp, "stell den Wecker ab", Language.DE))
        assertTrue(store.query().isEmpty(), "Store war danach nicht leer")
    }

    /**
     * Die leere Antwort ist die ehrlichste Stelle der ganzen Kette — sie darf nie
     * eine Gegenfrage sein und muss in der Sprache des Nutzers kommen.
     */
    @Test
    fun `leerer Store antwortet ehrlich in beiden Sprachen`() {
        val fpEn = timerFastpath(InMemoryScheduledItemStore(), clockAt(9))
        assertEquals("No timers running right now.", say(fpEn, "how long is left", Language.EN))
        assertEquals("There's no timer running right now.", say(fpEn, "stop the timer", Language.EN))

        val fpDe = timerFastpath(InMemoryScheduledItemStore(), clockAt(9))
        assertEquals("Gerade läuft kein Timer.", say(fpDe, "wie lange läuft der Timer noch", Language.DE))
        assertEquals("Da läuft gerade kein Timer.", say(fpDe, "stopp den Timer", Language.DE))
    }

    // ── Liste: draufsetzen → vorlesen → leeren ───────────────────────────────

    @Test
    fun `englische Listen-Kette - draufsetzen vorlesen leeren`() {
        val store = InMemoryListStore()
        val clock = clockAt(9)
        val fp = listFastpath(store, clock)

        assertEquals("Got it, milk is on the list now.", say(fp, "add milk to the list", Language.EN))
        assertEquals("Got it, bread is on the list now.", say(fp, "put bread on the shopping list", Language.EN))

        assertEquals("On the list: milk, bread.", say(fp, "what's on the list", Language.EN))

        assertEquals("Removed bread from the list.", say(fp, "take bread off the list", Language.EN))
        assertEquals("Okay, cleared the list — 1 item gone.", say(fp, "clear the list", Language.EN))
        assertTrue(store.items().isEmpty(), "Liste war danach nicht leer")
    }

    @Test
    fun `deutsche Listen-Kette - draufsetzen vorlesen leeren`() {
        val store = InMemoryListStore()
        val clock = clockAt(9)
        val fp = listFastpath(store, clock)

        assertEquals("Alles klar, Milch steht jetzt drauf.", say(fp, "Setz Milch auf die Liste", Language.DE))
        assertEquals("Alles klar, Brot steht jetzt drauf.", say(fp, "pack Brot auf die Liste", Language.DE))

        assertEquals("Auf der Liste steht: Milch, Brot.", say(fp, "was steht auf der Liste", Language.DE))

        assertEquals("Brot ist von der Liste runter.", say(fp, "nimm Brot von der Liste", Language.DE))
        assertEquals("Okay, die Liste ist jetzt leer (1 gelöscht).", say(fp, "leer die Liste", Language.DE))
        assertTrue(store.items().isEmpty(), "Liste war danach nicht leer")
    }

    @Test
    fun `leere Liste antwortet ehrlich in beiden Sprachen`() {
        assertEquals(
            "The list is empty.",
            say(listFastpath(InMemoryListStore(), clockAt(9)), "what's on the list", Language.EN),
        )
        assertEquals(
            "Die Liste ist leer.",
            say(listFastpath(InMemoryListStore(), clockAt(9)), "was steht auf der Liste", Language.DE),
        )
    }
}
