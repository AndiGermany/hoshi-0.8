package de.hoshi.adapters.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Beweist die Tages-Referenz-Matrix des [DayReferenceResolver] gegen eine FIXE Uhr
 * (Muster `DateFastpath`-Tests: `Clock.fixed` ⇒ voll deterministisch). Referenz-Tag
 * ist **Sonntag, der 28.06.2026** (bewusst derselbe Starttag wie das kanned
 * Forecast-JSON im [WeatherGroundingProviderTest]).
 */
class DayReferenceResolverTest {

    /** Sonntag, 2026-06-28, 12:00 Europe/Berlin. */
    private val sunday: Clock =
        Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), DayReferenceResolver.BERLIN)

    /** Montag, 2026-06-29. */
    private val monday: Clock =
        Clock.fixed(Instant.parse("2026-06-29T10:00:00Z"), DayReferenceResolver.BERLIN)

    private val resolver = DayReferenceResolver(sunday)

    @Test
    fun `Referenz-Tag der fixen Uhr ist wirklich ein Sonntag (Test-Anker)`() {
        assertEquals("SUNDAY", LocalDate.now(sunday).dayOfWeek.name)
    }

    @Test
    fun `ohne Tages-Referenz - Default heute+morgen, nicht explizit (heutiges Verhalten)`() {
        val ref = resolver.resolve("Wie wird das Wetter?")
        assertEquals(listOf(0, 1), ref.offsets)
        assertFalse(ref.explicit, "keine Referenz ⇒ Default, nicht explizit")
    }

    @Test
    fun `morgen - nur Offset 1`() {
        val ref = resolver.resolve("Wie wird das Wetter morgen?")
        assertEquals(listOf(1), ref.offsets)
        assertTrue(ref.explicit)
    }

    @Test
    fun `uebermorgen - nur Offset 2 (und matcht NICHT zusaetzlich als morgen)`() {
        assertEquals(listOf(2), resolver.resolve("Regnet es übermorgen?").offsets)
        assertEquals(listOf(2), resolver.resolve("Regnet es uebermorgen?").offsets)
    }

    @Test
    fun `Donnerstag am Sonntag - Offset 4 (naechstes Vorkommen)`() {
        assertEquals(listOf(4), resolver.resolve("Wie wird das Wetter am Donnerstag?").offsets)
        assertEquals(listOf(4), resolver.resolve("weather on Thursday?").offsets)
    }

    @Test
    fun `Wochentag ist heute - Offset 0 (heute zaehlt als 0)`() {
        assertEquals(listOf(0), resolver.resolve("Wie wird das Wetter am Sonntag?").offsets)
    }

    @Test
    fun `am Wochenende - Sa+So als naechste Vorkommen (Sonntag - 0 und 6, Montag - 5 und 6)`() {
        assertEquals(listOf(0, 6), resolver.resolve("Wie wird das Wetter am Wochenende?").offsets)
        assertEquals(
            listOf(5, 6),
            DayReferenceResolver(monday).resolve("Wie wird das Wetter am Wochenende?").offsets,
        )
    }

    @Test
    fun `heute und morgen - Vereinigungsmenge, explizit`() {
        val ref = resolver.resolve("Wie wird das Wetter heute und morgen?")
        assertEquals(listOf(0, 1), ref.offsets)
        assertTrue(ref.explicit)
    }

    @Test
    fun `englisch - tomorrow und day after tomorrow (Phrase schluckt ihr tomorrow-Token)`() {
        assertEquals(listOf(1), resolver.resolve("what's the weather tomorrow?").offsets)
        assertEquals(listOf(2), resolver.resolve("weather the day after tomorrow?").offsets)
    }

    @Test
    fun `morgens ist KEIN morgen - Token-Gleichheit statt Substring`() {
        val ref = resolver.resolve("Wie ist das Wetter morgens so?")
        assertFalse(ref.explicit, "morgens (Tageszeit) darf nicht als Tages-Referenz zählen")
        assertEquals(listOf(0, 1), ref.offsets)
    }

    @Test
    fun `dayLabel - heute und morgen wie bisher, ab 2 der Tag beim Namen`() {
        assertEquals("heute", resolver.dayLabel(0))
        assertEquals("morgen", resolver.dayLabel(1))
        // Sonntag + 2 = Dienstag, Sonntag + 4 = Donnerstag.
        assertEquals("am Dienstag (in 2 Tagen)", resolver.dayLabel(2))
        assertEquals("am Donnerstag (in 4 Tagen)", resolver.dayLabel(4))
    }
}
