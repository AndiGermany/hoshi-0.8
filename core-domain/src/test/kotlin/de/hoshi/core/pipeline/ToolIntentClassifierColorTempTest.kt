package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **ToolIntentClassifierColorTempTest** (Andi-Live-Befund 2026-07-26): „Schalte
 * das Licht im Wohnzimmer auf Warmweiß" tat NICHTS — im Gegensatz zu „…auf Grün",
 * das über [DeterministicToolIntentClassifier.COLORS] traf. Root Cause: die
 * Licht-Farb-Erkennung kannte FARBEN (`color_name`), aber keine FARBTEMPERATUREN
 * (`color_temp_kelvin`) — „warmweiß" matchte in [DeterministicToolIntentClassifier.COLORS]
 * nichts, und ohne ON/OFF-Partikel fiel der ganze Satz durch `return null` (Zeile
 * „Licht erwähnt, aber keine klare Aktion").
 *
 * Diese Tests belegen die neue [DeterministicToolIntentClassifier.COLOR_TEMPS]/
 * [DeterministicToolIntentClassifier.COLOR_TEMP_PAIRS]-Auflösung: DE-Komposita
 * (ein Token) + EN-Wortpaare (zwei Token) ⇒ `color_temp_kelvin`, UND die
 * Gegenprobe, dass eine echte Farbe (`color_name`) byte-identisch weiterläuft.
 */
class ToolIntentClassifierColorTempTest {

    private val classifier = DeterministicToolIntentClassifier()

    // ── DE-Komposita ──────────────────────────────────────────────────────────

    @Test
    fun `DE Warmweiss setzt color_temp_kelvin 2700`() {
        val call = classifier.classify("Schalte das Licht im Wohnzimmer auf Warmweiß", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
        assertEquals(2700, call.data["color_temp_kelvin"])
        assertNull(call.data["color_name"], "keine Vermischung mit der Farb-Erkennung")
    }

    @Test
    fun `DE Warmweiss ohne Eszett (warmweiss) matcht ebenfalls`() {
        val call = classifier.classify("Licht in der Küche auf warmweiss", Language.DE)!!
        assertEquals("kuche", call.data["area_id"])
        assertEquals(2700, call.data["color_temp_kelvin"])
    }

    @Test
    fun `DE Neutralweiss setzt color_temp_kelvin 4000`() {
        val call = classifier.classify("Licht im Schlafzimmer auf Neutralweiß", Language.DE)!!
        assertEquals("schlafzimmer", call.data["area_id"])
        assertEquals(4000, call.data["color_temp_kelvin"])
    }

    @Test
    fun `DE Kaltweiss setzt color_temp_kelvin 6500`() {
        val call = classifier.classify("mach das Licht im Wohnzimmer auf Kaltweiß", Language.DE)!!
        assertEquals(6500, call.data["color_temp_kelvin"])
    }

    // ── EN-Wortpaare ──────────────────────────────────────────────────────────

    @Test
    fun `EN warm white setzt color_temp_kelvin 2700`() {
        val call = classifier.classify("turn the living room light to warm white", Language.EN)!!
        assertEquals("wohnzimmer", call.data["area_id"])
        assertEquals(2700, call.data["color_temp_kelvin"])
        assertNull(call.data["color_name"])
    }

    @Test
    fun `EN neutral white setzt color_temp_kelvin 4000`() {
        val call = classifier.classify("set the kitchen light to neutral white", Language.EN)!!
        assertEquals("kuche", call.data["area_id"])
        assertEquals(4000, call.data["color_temp_kelvin"])
    }

    @Test
    fun `EN cool white setzt color_temp_kelvin 6500`() {
        val call = classifier.classify("turn the bedroom light to cool white", Language.EN)!!
        assertEquals("schlafzimmer", call.data["area_id"])
        assertEquals(6500, call.data["color_temp_kelvin"])
    }

    @Test
    fun `EN cold white setzt ebenfalls color_temp_kelvin 6500`() {
        val call = classifier.classify("turn the bedroom light to cold white", Language.EN)!!
        assertEquals(6500, call.data["color_temp_kelvin"])
    }

    @Test
    fun `EN weisses Einzel-Token white bleibt unveraendert eine Farbe`() {
        // Gegenprobe zur Wortpaar-Erkennung: OHNE "warm"/"neutral"/"cool"/"cold"
        // davor bleibt "white" die bestehende COLORS-Farbe, keine Kelvin-Erfindung.
        val call = classifier.classify("turn the living room light white", Language.EN)!!
        assertEquals("white", call.data["color_name"])
        assertNull(call.data["color_temp_kelvin"])
    }

    // ── Gegen-Test: „Grün" bleibt byte-identisch eine Farbe ──────────────────

    @Test
    fun `DE Gruen bleibt byte-identisch color_name green`() {
        val call = classifier.classify("Schalte das Licht im Wohnzimmer auf Grün", Language.DE)!!
        assertEquals("green", call.data["color_name"])
        assertNull(call.data["color_temp_kelvin"])
    }

    @Test
    fun `EN green bleibt byte-identisch color_name green`() {
        val call = classifier.classify("turn the living room light green", Language.EN)!!
        assertEquals("green", call.data["color_name"])
        assertNull(call.data["color_temp_kelvin"])
    }

    // ── Unbekanntes Wort faellt weiter durch wie heute ───────────────────────

    @Test
    fun `DE unbekanntes Farbwort ohne ONOFF-Partikel faellt weiter durch (null)`() {
        // "tuerkis" ist weder in COLORS noch in COLOR_TEMPS ⇒ kein Treffer, kein
        // ON/OFF-Partikel im Satz ⇒ "Licht erwaehnt, aber keine klare Aktion" ⇒ null
        // (unveraendertes Bestandsverhalten, s. Klassiker-Test in ToolIntentClassifierTest).
        assertNull(classifier.classify("Schalte das Licht im Wohnzimmer auf Türkis", Language.DE))
    }

    @Test
    fun `EN unbekanntes Farbwort ohne ONOFF-Partikel faellt weiter durch (null)`() {
        assertNull(classifier.classify("turn the living room light to turquoise", Language.EN))
    }
}
