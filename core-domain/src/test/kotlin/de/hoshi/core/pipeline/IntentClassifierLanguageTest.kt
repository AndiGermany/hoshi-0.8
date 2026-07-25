package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **IntentClassifierLanguageTest** (2026-07-25) — der Beweis, dass der
 * `language`-Parameter WIRKT.
 *
 * Vorher trug [IntentClassifier] die Sprache zwar in der Signatur, ignorierte sie
 * aber ausdrücklich (KDoc: „heute für alle Sprachen die DE-Keyword-Listen") — jeder
 * englische Befehl lief gegen deutsche Wortlisten und war damit still kein
 * Smart-Home-Kandidat, jede englische Anfrage wurde in der Komplexität zu niedrig
 * eingestuft.
 *
 * Zwei Zusicherungen zusammen:
 *  1. **EN wird erkannt** — eigene Wortlisten, ganze Wörter, Idiom-Riegel.
 *  2. **DE bleibt byte-identisch** — dieselben Ergebnisse wie ohne Argument; der
 *     [IntentClassifierTest] (Bestand) prüft den Rest unverändert weiter.
 */
class IntentClassifierLanguageTest {

    private val classifier = IntentClassifier(complexityThreshold = 4)

    // ── (1) Der Parameter wirkt: EN-Sätze sind jetzt Smart-Home-Kandidaten ───────

    @Test
    fun `englische Befehle sind mit Language EN Smart-Home-Kandidaten`() {
        listOf(
            "turn on the light",
            "turn off the lights",
            "switch on the lamp",
            "dim the light",
            "brighten the lights",
            "open the blinds",
            "close the curtains",
            "turn the heating on",
            "set the temperature to 21 degrees",
        ).forEach { p ->
            assertTrue(classifier.isSmartHomeCandidate(p, Language.EN), "Satz: $p")
        }
    }

    @Test
    fun `derselbe englische Satz war und bleibt mit Language DE kein Kandidat (Sprache entscheidet)`() {
        assertTrue(classifier.isSmartHomeCandidate("turn on the light", Language.EN))
        assertFalse(classifier.isSmartHomeCandidate("turn on the light", Language.DE))
    }

    /**
     * Code-Switching: Andis Geräte-/Raumwörter sind deutsch. Ein englisch geführter
     * Turn darf einen deutsch gesprochenen Befehl NICHT verlieren.
     */
    @Test
    fun `deutscher Befehl bleibt auch im EN-Modus ein Kandidat`() {
        assertTrue(classifier.isSmartHomeCandidate("schalte das licht an", Language.EN))
    }

    // ── (2) Die teure Richtung bleibt geschlossen ────────────────────────────────

    @Test
    fun `uebertragene englische Wendungen sind KEINE Smart-Home-Kandidaten`() {
        listOf(
            "turn on the charm",
            "that's a bright idea",
            "he turned a blind eye",
            "the light of my life",
            "I feel warm",
            "it's warm in here",
        ).forEach { p ->
            assertFalse(classifier.isSmartHomeCandidate(p, Language.EN), "Satz: $p")
        }
    }

    @Test
    fun `englische Funktionswoerter matchen nur als ganze Woerter`() {
        // „set" steckt in „sunset"/„settings"/„asset", „on" in „only" — ein
        // Substring-Match wäre eine False-Positive-Maschine.
        assertFalse(classifier.isSmartHomeCandidate("the sunset over the lighthouse", Language.EN))
        assertFalse(classifier.isSmartHomeCandidate("only a lighthouse keeper knows", Language.EN))
    }

    @Test
    fun `temperature zaehlt nur mit konkretem Sollwert als Ziel`() {
        assertTrue(classifier.isSmartHomeCandidate("set the temperature to 21 degrees", Language.EN))
        assertFalse(classifier.isSmartHomeCandidate("what temperature should I set the oven to", Language.EN))
        assertFalse(classifier.isSmartHomeCandidate("set the oven to 200 degrees", Language.EN))
    }

    // ── (3) Komplexitaet folgt ebenfalls der Sprache ─────────────────────────────

    @Test
    fun `englische Agent-Marker heben den Komplexitaetsscore (vorher stumm null)`() {
        assertEquals(0, classifier.complexityScore("add milk to the shopping list", Language.DE))
        assertEquals(4, classifier.complexityScore("add milk to the shopping list", Language.EN))
        assertTrue(classifier.isOpenClawEligible("add milk to the shopping list", Language.EN))
        assertFalse(classifier.isOpenClawEligible("add milk to the shopping list", Language.DE))
    }

    @Test
    fun `englische Komplexitaets-Marker zaehlen`() {
        assertEquals(3, classifier.complexityScore("switch every room at the same time", Language.EN))
    }

    @Test
    fun `englische Raum-Aliase zaehlen als Raum-Nennung (Name selbst bleibt deutsch)`() {
        // Drei Räume, englisch benannt ⇒ +2 (wie „Wohnzimmer Schlafzimmer Küche" auf Deutsch).
        assertEquals(2, classifier.complexityScore("lights in the living room bedroom and kitchen", Language.EN))
        // Derselbe Satz mit den REALEN (deutschen) HA-Namen zählt in JEDER Sprache.
        assertEquals(2, classifier.complexityScore("lights in the wohnzimmer schlafzimmer and küche", Language.EN))
        assertEquals(2, classifier.complexityScore("lights in the wohnzimmer schlafzimmer and küche", Language.DE))
    }

    // ── (4) DE byte-identisch ────────────────────────────────────────────────────

    @Test
    fun `DE-Ergebnisse sind mit und ohne Sprach-Argument identisch`() {
        val de = listOf(
            "schalte das licht an", "schalte die zeit", "das licht ist an", "Dimme die Lampe",
            "bitte schalte das wohnzimmer licht aus", "Wie spät ist es?",
            "erstelle eine routine für morgens", "erstelle eine einkaufsliste",
            "Was ist das? Und das?", "Mach das Licht in Wohnzimmer Schlafzimmer und Küche aus",
            "Wohnzimmer und Schlafzimmer", "Hallo",
        )
        de.forEach { p ->
            assertEquals(
                classifier.isSmartHomeCandidate(p),
                classifier.isSmartHomeCandidate(p, Language.DE),
                "Kandidat: $p",
            )
            assertEquals(classifier.complexityScore(p), classifier.complexityScore(p, Language.DE), "Score: $p")
        }
    }

    /** ES/FR/IT haben noch keine eigenen Erkenner — sie laufen (ehrlich) auf dem DE-Set. */
    @Test
    fun `ES FR IT laufen unveraendert auf dem DE-Set`() {
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            assertTrue(classifier.isSmartHomeCandidate("schalte das licht an", language), "$language")
            assertFalse(classifier.isSmartHomeCandidate("turn on the light", language), "$language")
            assertEquals(
                classifier.complexityScore("erstelle eine einkaufsliste"),
                classifier.complexityScore("erstelle eine einkaufsliste", language),
                "$language",
            )
        }
    }
}
