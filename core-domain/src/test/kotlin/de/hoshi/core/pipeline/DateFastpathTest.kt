package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Beweist den brain-freien Vollzug [DateFastpath] mit FESTER Uhr (`Clock.fixed`):
 * die deterministische DE/EN-Quittung für den 2026-07-01 (ein Mittwoch), die
 * konservative Erkennung (DE+EN-Trigger vs. Nicht-Datums-Text) und das Flag-OFF.
 */
class DateFastpathTest {

    private val zone = ZoneId.of("Europe/Berlin")

    /** Feste Uhr auf den gegebenen Kalendertag (Tagesbeginn in der Berlin-Zone). */
    private fun clockAt(year: Int, month: Int, day: Int): Clock {
        val instant = LocalDate.of(year, month, day).atStartOfDay(zone).toInstant()
        return Clock.fixed(instant, zone)
    }

    /** 2026-07-01 ist ein Mittwoch — die Referenz-Ausgabe der Aufgabe. */
    private fun wednesday() = DateFastpath(clock = clockAt(2026, 7, 1))

    // ── Deterministische Quittung ────────────────────────────────────────────

    @Test
    fun `DE nennt Wochentag Datum und Jahr deterministisch`() {
        val phrase = wednesday().handle("welcher Tag ist heute?", Language.DE)
        assertEquals("Heute ist Mittwoch, der 1. Juli 2026.", phrase)
    }

    @Test
    fun `EN nennt Wochentag Datum und Jahr deterministisch`() {
        val phrase = wednesday().handle("what day is it today?", Language.EN)
        assertEquals("Today is Wednesday, 1 July 2026.", phrase)
    }

    // ── Erkennung (DE) ────────────────────────────────────────────────────────

    @Test
    fun `DE erkennt die geforderten Datums-Trigger`() {
        val fp = wednesday()
        assertTrue(fp.isDateQuery("Welcher Tag ist heute?"))
        assertTrue(fp.isDateQuery("Welches Datum haben wir?"))
        assertTrue(fp.isDateQuery("Der wievielte ist heute?"))
        assertTrue(fp.isDateQuery("Was ist heute für ein Tag?"))
    }

    // ── Erkennung (EN) ────────────────────────────────────────────────────────

    @Test
    fun `EN erkennt die geforderten Datums-Trigger`() {
        val fp = wednesday()
        assertTrue(fp.isDateQuery("What day is it?"))
        assertTrue(fp.isDateQuery("What's the date?"))
        assertTrue(fp.isDateQuery("What's today's date?"))
    }

    // ── Nicht-Datums-Text ⇒ null (byte-neutral, normaler Turn) ─────────────────

    @Test
    fun `Nicht-Datums-Text liefert null`() {
        val fp = wednesday()
        assertFalse(fp.isDateQuery("Wie warm ist es heute?"))
        assertNull(fp.handle("Wie warm ist es heute?", Language.DE))
        assertNull(fp.handle("Tell me a joke about dates.", Language.EN))
        assertNull(fp.handle("", Language.DE))
    }

    // ── Flag-OFF (DISABLED) ⇒ immer null ──────────────────────────────────────

    @Test
    fun `DISABLED antwortet nie`() {
        assertNull(DateFastpath.DISABLED.handle("welcher Tag ist heute?", Language.DE))
        assertNull(DateFastpath.DISABLED.handle("what day is it?", Language.EN))
    }
    /**
     * Andi-Befund 21.07 abends: „Hoshi sagt mir grad nicht mal mehr die Uhrzeit."
     * Es war keine Regression, sondern eine Lücke — das Brain hat gar keine Uhr.
     * Diese Frage steht außerdem als Wake-Word-Beispiel im Video-Drehbuch.
     */
    @Test
    fun `Uhrzeit wird deterministisch aus der Uhr beantwortet`() {
        val fixed = Clock.fixed(
            java.time.ZonedDateTime.of(2026, 7, 21, 20, 15, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(),
            ZoneId.of("Europe/Berlin"),
        )
        val fp = DateFastpath(clock = fixed)
        assertEquals("Es ist 20 Uhr 15.", fp.handle("Wie spät ist es?", Language.DE))
        assertEquals("It's 8:15 pm.", fp.handle("What time is it?", Language.EN))
    }

    @Test
    fun `volle Stunde ohne Minutenangabe`() {
        val fixed = Clock.fixed(
            java.time.ZonedDateTime.of(2026, 7, 21, 9, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(),
            ZoneId.of("Europe/Berlin"),
        )
        val fp = DateFastpath(clock = fixed)
        assertEquals("Es ist 9 Uhr.", fp.handle("Wie viel Uhr ist es?", Language.DE))
        assertEquals("It's 9 am.", fp.handle("What is the time?", Language.EN))
    }

    @Test
    fun `Wecker-Fragen gehoeren NICHT hierher`() {
        val fp = DateFastpath()
        assertFalse(fp.isTimeQuery("Wie spät klingelt es?"))
        assertFalse(fp.isTimeQuery("Wann klingelt mein Wecker?"))
        assertFalse(fp.isTimeQuery("Wie lange läuft der Timer noch?"))
    }

    @Test
    fun `Datum bleibt unveraendert erreichbar`() {
        val fp = DateFastpath()
        assertTrue(fp.isDateQuery("Welcher Tag ist heute?"))
        assertFalse(fp.isTimeQuery("Welcher Tag ist heute?"))
    }

}
