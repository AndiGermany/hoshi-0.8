package de.hoshi.adapters.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Detektor-Tests für [SensitiveMarker]: je Kategorie DE+EN Treffer + ein konservativer
 * Nicht-Treffer-Nachweis (kein Über-Blocking harmloser, präfix-ähnlicher Wörter).
 * Rein, deterministisch, kein Netz.
 */
class SensitiveMarkerTest {

    // ── Gesundheit ────────────────────────────────────────────────────────────
    @Test
    fun `Gesundheit DE Treffer`() {
        assertEquals(SensitiveMarker.Category.GESUNDHEIT, SensitiveMarker.detect("Ich war heute beim Arzt wegen meiner Diagnose"))
    }

    @Test
    fun `Gesundheit EN Treffer`() {
        assertEquals(SensitiveMarker.Category.GESUNDHEIT, SensitiveMarker.detect("I have an appointment with my doctor about the diagnosis"))
    }

    // ── Finanz ────────────────────────────────────────────────────────────────
    @Test
    fun `Finanz DE Treffer`() {
        assertEquals(SensitiveMarker.Category.FINANZ, SensitiveMarker.detect("Mein Kontostand ist im Minus und ich habe Schulden"))
    }

    @Test
    fun `Finanz EN Treffer`() {
        assertEquals(SensitiveMarker.Category.FINANZ, SensitiveMarker.detect("My salary barely covers the mortgage payment"))
    }

    @Test
    fun `Finanz Steuer-ID 11 Ziffern Treffer`() {
        assertEquals(SensitiveMarker.Category.FINANZ, SensitiveMarker.detect("Meine Nummer lautet 12345678901 fuer das Amt"))
    }

    // ── Adresse ───────────────────────────────────────────────────────────────
    @Test
    fun `Adresse DE Treffer`() {
        assertEquals(SensitiveMarker.Category.ADRESSE, SensitiveMarker.detect("Ich wohne in der Beispielstrasse"))
    }

    @Test
    fun `Adresse EN Treffer`() {
        assertEquals(SensitiveMarker.Category.ADRESSE, SensitiveMarker.detect("My address is somewhere downtown"))
    }

    // ── Politik ───────────────────────────────────────────────────────────────
    @Test
    fun `Politik DE Treffer`() {
        assertEquals(SensitiveMarker.Category.POLITIK, SensitiveMarker.detect("Bei der Bundestagswahl gehe ich wählen"))
    }

    @Test
    fun `Politik EN Treffer`() {
        assertEquals(SensitiveMarker.Category.POLITIK, SensitiveMarker.detect("I will vote for that party in the election"))
    }

    // ── Religion ──────────────────────────────────────────────────────────────
    @Test
    fun `Religion DE Treffer`() {
        assertEquals(SensitiveMarker.Category.RELIGION, SensitiveMarker.detect("Am Sonntag gehe ich in die Kirche zum Gebet"))
    }

    @Test
    fun `Religion EN Treffer`() {
        assertEquals(SensitiveMarker.Category.RELIGION, SensitiveMarker.detect("We go to church every Sunday for prayer"))
    }

    // ── Sexualität ────────────────────────────────────────────────────────────
    @Test
    fun `Sexualitaet DE Treffer`() {
        assertEquals(SensitiveMarker.Category.SEXUALITAET, SensitiveMarker.detect("Wir haben über Verhütung gesprochen"))
    }

    @Test
    fun `Sexualitaet EN Treffer`() {
        assertEquals(SensitiveMarker.Category.SEXUALITAET, SensitiveMarker.detect("It was a question about my sexual health"))
    }

    // ── Konservativ: harmlose Turns liefern NULL (kein Über-Blocking) ─────────
    @Test
    fun `harmloser DE Turn ist kein Treffer`() {
        assertNull(SensitiveMarker.detect("Ich war letzte Woche in Italien im Urlaub am Meer"))
    }

    @Test
    fun `harmloser EN Turn ist kein Treffer`() {
        assertNull(SensitiveMarker.detect("I spent last week on holiday at the seaside in Italy"))
    }

    @Test
    fun `kein FP bei praefix-aehnlichen harmlosen Woertern`() {
        // 'taxi' darf nicht als Finanz-'tax' greifen, 'painting' nicht als Health,
        // 'be patient' nicht als Health, 'das lohnt sich' nicht als Finanz.
        assertNull(SensitiveMarker.detect("Ich nehme das Taxi und male ein painting"))
        assertNull(SensitiveMarker.detect("Bitte sei geduldig, please be patient"))
        assertNull(SensitiveMarker.detect("Das lohnt sich richtig, ich habe die Wahl getroffen"))
    }

    @Test
    fun `leerer Text ist kein Treffer`() {
        assertNull(SensitiveMarker.detect(""))
        assertNull(SensitiveMarker.detect("   "))
    }
}
