package de.hoshi.adapters.tts

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist den echten [IcuVerbalizer]: Uhrzeiten, Ganz-/Dezimalzahlen und (nur DE)
 * Ordinalzahlen werden ziffernfrei ausgeschrieben; unbekannte/zu grosse Formen
 * crashen NIE, sondern bleiben als Original-Fragment stehen.
 */
class IcuVerbalizerTest {

    private val verbalizer = IcuVerbalizer()

    // ---- Uhrzeiten ----------------------------------------------------

    @Test
    fun `DE Uhrzeit mit Wort-Form und Minutenteil`() {
        val out = verbalizer.verbalize("Der Termin ist um 20 Uhr 15.", Language.DE)
        assertEquals("Der Termin ist um zwanzig Uhr fünfzehn.", out)
    }

    @Test
    fun `DE volle Stunde ohne Minutenteil`() {
        val out = verbalizer.verbalize("Der Wecker klingelt um 7 Uhr.", Language.DE)
        assertEquals("Der Wecker klingelt um sieben Uhr.", out)
    }

    @Test
    fun `DE Doppelpunkt-Uhrzeit`() {
        val out = verbalizer.verbalize("Es ist jetzt 20:15.", Language.DE)
        assertEquals("Es ist jetzt zwanzig Uhr fünfzehn.", out)
    }

    @Test
    fun `DE Doppelpunkt-Uhrzeit volle Stunde`() {
        val out = verbalizer.verbalize("Start ist um 09:00.", Language.DE)
        assertEquals("Start ist um neun Uhr.", out)
    }

    @Test
    fun `DE einstellige Minute bekommt null-Praefix`() {
        val out = verbalizer.verbalize("Der Zug faehrt um 20:05.", Language.DE)
        assertEquals("Der Zug faehrt um zwanzig Uhr null fünf.", out)
    }

    @Test
    fun `EN Doppelpunkt-Uhrzeit sinngemaess`() {
        val out = verbalizer.verbalize("It is now 20:15.", Language.EN)
        assertEquals("It is now twenty fifteen.", out)
    }

    @Test
    fun `EN volle Stunde mit o clock`() {
        val out = verbalizer.verbalize("The meeting starts at 09:00.", Language.EN)
        assertEquals("The meeting starts at nine o'clock.", out)
    }

    // ---- Ganz-/Dezimalzahlen -------------------------------------------

    @Test
    fun `DE Dezimalzahl mit Komma`() {
        val out = verbalizer.verbalize("Die Temperatur betraegt 17,1 Grad.", Language.DE)
        assertEquals("Die Temperatur betraegt siebzehn Komma eins Grad.", out)
    }

    @Test
    fun `EN Dezimalzahl mit Punkt`() {
        val out = verbalizer.verbalize("The temperature is 17.1 degrees.", Language.EN)
        assertEquals("The temperature is seventeen point one degrees.", out)
    }

    @Test
    fun `DE Ganzzahl`() {
        val out = verbalizer.verbalize("Ich habe 42 neue Nachrichten.", Language.DE)
        assertEquals("Ich habe zweiundvierzig neue Nachrichten.", out)
    }

    @Test
    fun `EN Ganzzahl`() {
        val out = verbalizer.verbalize("I have 42 new messages.", Language.EN)
        assertEquals("I have forty-two new messages.", out)
    }

    // ---- Ordinalzahlen (NUR Deutsch, s. Klassen-KDoc) ------------------

    @Test
    fun `DE Ordinalzahl wird zu dritte-Form`() {
        val out = verbalizer.verbalize("Der 3. Mai ist ein Feiertag.", Language.DE)
        assertTrue(out.contains("dritte"), "erwarte 'dritte' in '$out'")
        assertFalse(out.any { it.isDigit() }, "keine Ziffern erwartet in '$out'")
    }

    @Test
    fun `EN Ordinalzahlen als Wort brauchen keine Sonderbehandlung`() {
        // Englisch schreibt Ordinalzahlen nie als reine Ziffer+Punkt-Form ("3rd"),
        // darum bleibt ein blosses "3." (kaeme in EN-Text so gut wie nie vor) eine
        // Ganzzahl gefolgt vom Satzpunkt - bewusst KEIN geratenes Ordinal-Ruleset.
        val out = verbalizer.verbalize("Chapter 3.", Language.EN)
        assertEquals("Chapter three.", out)
    }

    @Test
    fun `ES FR IT Ordinalzahl faellt ehrlich auf Kardinalzahl zurueck statt Genus zu raten`() {
        assertEquals("El día tres. es festivo.", verbalizer.verbalize("El día 3. es festivo.", Language.ES))
        assertEquals("Le trois. est férié.", verbalizer.verbalize("Le 3. est férié.", Language.FR))
        assertEquals("Il tre. è festivo.", verbalizer.verbalize("Il 3. è festivo.", Language.IT))
    }

    // ---- Ziel-Invariante: KEINE Ziffern mehr im Ergebnis ----------------

    @Test
    fun `realistische Saetze enthalten nach der Verbalisierung keine Ziffern mehr`() {
        val cases = listOf(
            Language.DE to "Der Termin ist um 20 Uhr 15, es sind noch 3 Grad und 17,1 Prozent Luftfeuchtigkeit.",
            Language.DE to "Um 09:00 startet die Praesentation vor 42 Gaesten.",
            Language.EN to "The flight leaves at 20:15 with 17.1 degrees outside and 3 passengers waiting.",
            Language.EN to "The meeting starts at 09:00 with 42 attendees.",
        )
        for ((language, text) in cases) {
            val out = verbalizer.verbalize(text, language)
            assertFalse(out.any { it.isDigit() }, "erwarte KEINE Ziffer in '$out' (aus '$text', $language)")
        }
    }

    // ---- Robustheit: NIE werfen -----------------------------------------

    @Test
    fun `zu grosse Zahl jenseits von Long crasht nicht und bleibt als Fragment stehen`() {
        val huge = "123456789012345678901234567890"
        val out = verbalizer.verbalize("Die Zahl $huge ist riesig.", Language.DE)
        // Fragment konnte nicht verbalisiert werden (Long-Overflow) -> bleibt UNVERAENDERT
        // stehen, statt den ganzen Aufruf crashen zu lassen (lieber eine Ziffer sprechen).
        assertTrue(out.contains(huge), "erwarte das unveraenderte Riesen-Fragment in '$out'")
    }

    @Test
    fun `leerer Text bleibt leer`() {
        assertEquals("", verbalizer.verbalize("", Language.DE))
        assertEquals("   ", verbalizer.verbalize("   ", Language.DE))
    }

    @Test
    fun `Text ohne Ziffern bleibt byte-identisch`() {
        val text = "Hallo, wie geht es dir heute?"
        assertEquals(text, verbalizer.verbalize(text, Language.DE))
    }

    @Test
    fun `verbalize wirft nie eine Exception, auch bei kaputten Randfaellen`() {
        val weird = listOf(
            "..:.. Uhr", // kaputte Uhrzeit-Attrappe
            "999999999999999999999999999999,999999999999999999999999999999", // riesige Dezimalzahl
            "Uhr Uhr Uhr 3.3.3. ,,,, 20:20:20:20",
        )
        for (text in weird) {
            var result: String? = null
            var threw = false
            try {
                result = verbalizer.verbalize(text, Language.DE)
            } catch (e: Exception) {
                threw = true
            }
            assertFalse(threw, "verbalize() darf NIE werfen (Eingabe: '$text')")
            assertTrue(result != null)
        }
    }
}
