package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.Language
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
        assertEquals("heute", resolver.dayLabel(0, Language.DE))
        assertEquals("morgen", resolver.dayLabel(1, Language.DE))
        // Sonntag + 2 = Dienstag, Sonntag + 4 = Donnerstag.
        assertEquals("am Dienstag (in 2 Tagen)", resolver.dayLabel(2, Language.DE))
        assertEquals("am Donnerstag (in 4 Tagen)", resolver.dayLabel(4, Language.DE))
    }

    @Test
    fun `dayLabel folgt der Turn-Sprache - derselbe Tag in allen fuenf Sprachen`() {
        // Der Tagesbezug steht MITTEN in der Wetter-Zeile; bliebe er deutsch,
        // wäre jeder fremdsprachige Block halbdeutsch (Sprach-Naht 2026-07-25).
        assertEquals("today", resolver.dayLabel(0, Language.EN))
        assertEquals("tomorrow", resolver.dayLabel(1, Language.EN))
        assertEquals("on Thursday (in 4 days)", resolver.dayLabel(4, Language.EN))

        assertEquals("hoy", resolver.dayLabel(0, Language.ES))
        assertEquals("el jueves (en 4 días)", resolver.dayLabel(4, Language.ES))

        assertEquals("aujourd'hui", resolver.dayLabel(0, Language.FR))
        assertEquals("jeudi (dans 4 jours)", resolver.dayLabel(4, Language.FR))

        assertEquals("oggi", resolver.dayLabel(0, Language.IT))
        assertEquals("giovedì (tra 4 giorni)", resolver.dayLabel(4, Language.IT))
    }

    // ── „dieser oder nächster Samstag?" (Grenzfall-Matrix 2026-08-21) ──────────

    /** Samstag, 2026-06-27, 20:00 Europe/Berlin — „heute Abend". */
    private val saturdayEvening: Clock =
        Clock.fixed(Instant.parse("2026-06-27T18:00:00Z"), DayReferenceResolver.BERLIN)

    /** Freitag, 2026-06-26, 20:00 Europe/Berlin. */
    private val fridayEvening: Clock =
        Clock.fixed(Instant.parse("2026-06-26T18:00:00Z"), DayReferenceResolver.BERLIN)

    @Test
    fun `Grenzfall-Anker - die beiden Abend-Uhren sind wirklich Samstag und Freitag`() {
        assertEquals("SATURDAY", LocalDate.now(saturdayEvening).dayOfWeek.name)
        assertEquals("FRIDAY", LocalDate.now(fridayEvening).dayOfWeek.name)
    }

    @Test
    fun `Samstagabend nach 'Samstag' gefragt - das ist HEUTE (Regel- statt Ratefall)`() {
        // ENTSCHEIDUNG, bewusst und einzeln getestet: ein nackter Wochentag meint
        // sein NÄCHSTES Vorkommen, und heute zählt als 0. Am Samstagabend „wie
        // wird das Wetter am Samstag?" ist damit HEUTE — nicht in sieben Tagen.
        //
        // Warum so herum: die Alternative (heute ausschließen, sobald es spät ist)
        // bräuchte eine Uhrzeit-Schwelle, die niemand belegen kann („ab wann meint
        // man den nächsten?"), und würde bei jeder Fehlannahme sechs Tage daneben
        // liegen. Wer den ÜBERnächsten meint, hat dafür ein eigenes Wort — und das
        // ist unten geprüft.
        val ref = DayReferenceResolver(saturdayEvening).resolve("Wie wird das Wetter am Samstag?")
        assertEquals(listOf(0), ref.offsets)
        assertTrue(ref.explicit)
        assertFalse(ref.beyondHorizon)
    }

    @Test
    fun `Samstagabend nach 'naechsten Samstag' gefragt - jenseits des Horizonts, KEIN stiller Heute-Fallback`() {
        // Das ist die Gegenprobe zum Test darüber: „nächsten Samstag" ist +7 und
        // damit außerhalb der Sieben-Tage-Reichweite. Ehrlich sagen schlägt raten —
        // still „heute" zu liefern wäre exakt sechs Tage falsch.
        val ref = DayReferenceResolver(saturdayEvening).resolve("Wie wird das Wetter nächsten Samstag?")
        assertTrue(ref.beyondHorizon, "[nächsten Samstag] liegt immer jenseits des Fensters")
        assertTrue(ref.offsets.isEmpty(), "keine geratenen Tage")
        assertTrue(ref.explicit)
    }

    @Test
    fun `Freitagabend nach 'Samstag' gefragt - das ist MORGEN`() {
        val ref = DayReferenceResolver(fridayEvening).resolve("Wie wird das Wetter am Samstag?")
        assertEquals(listOf(1), ref.offsets)
    }

    @Test
    fun `Freitagabend nach 'am Wochenende' - Samstag und Sonntag, Wochenend-Flag gesetzt`() {
        val ref = DayReferenceResolver(fridayEvening).resolve("Wie wird das Wetter am Wochenende?")
        assertEquals(listOf(1, 2), ref.offsets, "Sa = morgen, So = übermorgen")
        assertTrue(ref.weekend, "Wochenend-Flag steuert die Zusammenfass-Anweisung")
    }

    @Test
    fun `naechste Woche und in-zehn-Tagen liegen jenseits, in-drei-Tagen nicht`() {
        assertTrue(resolver.resolve("Wie wird das Wetter nächste Woche?").beyondHorizon)
        assertTrue(resolver.resolve("weather next week?").beyondHorizon)
        assertTrue(resolver.resolve("Wie wird das Wetter in 10 Tagen?").beyondHorizon)

        val inThree = resolver.resolve("Wie wird das Wetter in 3 Tagen?")
        assertFalse(inThree.beyondHorizon, "3 Tage liegen im Fenster")
        assertEquals(listOf(3), inThree.offsets)

        // Genau die Kante: 6 ist der letzte beantwortbare Tag, 7 der erste nicht mehr.
        assertEquals(listOf(6), resolver.resolve("Wetter in 6 Tagen?").offsets)
        assertTrue(resolver.resolve("Wetter in 7 Tagen?").beyondHorizon)
    }

    // ── Die drei neuen Sprachen (vorher matchte KEIN ES/FR/IT-Wochentag) ───────

    @Test
    fun `spanische Tage - hoy, manana, pasado manana und die Wochentage`() {
        val r = DayReferenceResolver(sunday)
        assertEquals(listOf(0), r.resolve("¿Qué tiempo hace hoy?").offsets)
        assertEquals(listOf(1), r.resolve("¿Qué tiempo hará mañana?").offsets)
        assertEquals(listOf(2), r.resolve("¿Qué tiempo hará pasado mañana?").offsets)
        // Sonntag + 4 = Donnerstag = jueves.
        assertEquals(listOf(4), r.resolve("¿Qué tiempo hará el jueves?").offsets)
        // Akzent-Toleranz: STT/Tastatur lassen ihn oft weg.
        assertEquals(listOf(6), r.resolve("¿Qué tiempo hará el sábado?").offsets)
        assertEquals(listOf(6), r.resolve("que tiempo hara el sabado?").offsets)
        assertTrue(r.resolve("¿Qué tiempo hará el fin de semana?").weekend)
    }

    @Test
    fun `franzoesische Tage - aujourd'hui, demain, apres-demain und die Wochentage`() {
        val r = DayReferenceResolver(sunday)
        assertEquals(listOf(0), r.resolve("Quel temps fait-il aujourd'hui ?").offsets)
        assertEquals(listOf(1), r.resolve("Quel temps fera-t-il demain ?").offsets)
        // Die Phrase muss ihr eigenes „demain"-Token schlucken (sonst 1 UND 2).
        assertEquals(listOf(2), r.resolve("Quel temps fera-t-il après-demain ?").offsets)
        assertEquals(listOf(4), r.resolve("Quel temps fera-t-il jeudi ?").offsets)
        assertTrue(r.resolve("Quel temps fera-t-il le week-end ?").weekend)
    }

    @Test
    fun `italienische Tage - oggi, domani, dopodomani und die Wochentage`() {
        val r = DayReferenceResolver(sunday)
        assertEquals(listOf(0), r.resolve("Che tempo fa oggi?").offsets)
        assertEquals(listOf(1), r.resolve("Che tempo farà domani?").offsets)
        assertEquals(listOf(2), r.resolve("Che tempo farà dopodomani?").offsets)
        assertEquals(listOf(2), r.resolve("Che tempo farà dopo domani?").offsets)
        assertEquals(listOf(4), r.resolve("Che tempo farà giovedì?").offsets)
        assertEquals(listOf(4), r.resolve("Che tempo fara giovedi?").offsets)
        assertTrue(r.resolve("Che tempo farà il fine settimana?").weekend)
    }

    @Test
    fun `Akzente ueberleben die Normalisierung - sonst matcht kein einziger ES-FR-IT-Tag`() {
        // Regressions-Anker: bis 2026-08-21 warf `normalize` alles außer
        // `a-zäöüß0-9` weg — „miércoles" wurde zu „mi rcoles" und traf nie.
        val r = DayReferenceResolver(sunday)
        assertEquals(listOf(3), r.resolve("el miércoles").offsets, "Sonntag + 3 = Mittwoch")
        assertEquals(listOf(3), r.resolve("mercoledì").offsets)
        assertEquals(listOf(3), r.resolve("mercredi").offsets)
    }

    // ── JETZT-Fokus (Auftrag 2b) ──────────────────────────────────────────────

    @Test
    fun `Andis Frage - 'wie ist grad das Wetter' ist eine JETZT-Frage`() {
        val ref = resolver.resolve("wie ist grad das Wetter?")
        assertTrue(ref.nowFocus, "[grad] ist ein Jetzt-Marker")
    }

    @Test
    fun `Praesens ohne Zeitwort ist JETZT, Futur ohne Zeitwort ist es nicht`() {
        assertTrue(resolver.resolve("Wie ist das Wetter?").nowFocus, "Präsens ohne Zeitwort ⇒ Augenblick")
        assertFalse(resolver.resolve("Wie wird das Wetter?").nowFocus, "Futur ⇒ Tagesbild")
        // Ein explizites Jetzt-Wort gewinnt auch gegen einen Futur-Marker.
        assertTrue(resolver.resolve("Wie wird das Wetter gerade?").nowFocus)
    }

    @Test
    fun `Jetzt-Marker in allen fuenf Sprachen`() {
        assertTrue(resolver.resolve("Wie ist das Wetter im Moment?").nowFocus)
        assertTrue(resolver.resolve("what's the weather right now?").nowFocus)
        assertTrue(resolver.resolve("¿qué tiempo hace ahora mismo?").nowFocus)
        assertTrue(resolver.resolve("quel temps fait-il en ce moment ?").nowFocus)
        assertTrue(resolver.resolve("che tempo fa adesso?").nowFocus)
    }

    @Test
    fun `expliziter Wochentag ist KEINE Jetzt-Frage`() {
        assertFalse(resolver.resolve("Wie wird das Wetter am Donnerstag?").nowFocus)
        assertFalse(resolver.resolve("Wie wird das Wetter morgen?").nowFocus)
    }
}
