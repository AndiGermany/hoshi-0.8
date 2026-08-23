package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist die STT-Verb-Drop-Kante vom 2026-08-13-Befund (It-K1, Schicht 2):
 * `OFF_WORDS` enthaelt "aus" bedingungslos, `ON_WORDS` enthaelt "ein" bewusst
 * NICHT (Artikel-Falle, s. [DeterministicToolIntentClassifier]-Kommentar an der
 * `turn_on`-Verzweigung). Ein STT-Verb-Drop wie "[Schalte] das Licht im Flur ein"
 * kommt ohne Schaltverb durch und fiel bisher auf `null` (Brain-Pfad) statt auf
 * `light.turn_on` — exakt die Asymmetrie, die diese Tests schliessen.
 *
 * Die Kante: ein Licht-Wort + ein Praeposition+bekannter-Raum-Anker
 * ("im"/"in der"/"in dem" + Raum aus dem Katalog) + ein nacktes "ein" NACH
 * diesem Anker zaehlt als An-Partikel — auch OHNE Schaltverb. Die Praeposition+
 * Raum-Kombination ist der Anker, der den Artikel-Fall ("ein Licht im Flur" —
 * "ein" steht VOR dem Anker) ausschliesst. Eng gefasst: nur der isLight-Zweig
 * (Licht-Domaene), kein globales "ein"-Trigger.
 */
class ToolIntentClassifierVerbDropLightTest {

    private val classifier = DeterministicToolIntentClassifier()

    // ── Golden-Befunde (wortgenau aus BEFUND-brain-behauptet-vollzug-2026-08-11.md) ──

    @Test
    fun `Golden 2 - das Licht im Flur ein - verb-los mappt auf light turn_on area flur`() {
        val call = classifier.classify("das Licht im Flur ein.", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("flur", call.data["area_id"])
        assertEquals(false, call.read)
    }

    @Test
    fun `Golden 1 - Jetzt druebe das Licht im Flur ein - Praefix-Rauschen vor dem Anker faengt die Kante trotzdem`() {
        // Ehrlicher Befund: die Kante prueft NUR die Position von Praeposition+Raum
        // relativ zum nackten "ein" (Anker VOR "ein" => Partikel) — fuehrende
        // unerkannte Tokens ("Jetzt druebe", STT-Muell/verschlucktes Verb) aendern
        // daran nichts, weil sie ausserhalb des Anker-Musters liegen. Diese Kante
        // FAENGT diesen Fall also mit; sie ist NICHT auf den (parallel entstehenden)
        // Vollzugs-Riegel angewiesen.
        val call = classifier.classify("Jetzt drübe das Licht im Flur ein.", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("flur", call.data["area_id"])
    }

    // ── Positiv: Anker-Formen (im / in der / in dem) ─────────────────────────────

    @Test
    fun `verb-los mit im - das licht im flur ein`() {
        val call = classifier.classify("das licht im flur ein", Language.DE)!!
        assertEquals("turn_on", call.service)
        assertEquals("flur", call.data["area_id"])
    }

    @Test
    fun `verb-los mit in der - das licht in der kueche ein`() {
        val call = classifier.classify("das licht in der küche ein", Language.DE)!!
        assertEquals("turn_on", call.service)
        assertEquals("kuche", call.data["area_id"])
    }

    @Test
    fun `verb-los mit in dem - das licht in dem schlafzimmer ein`() {
        val call = classifier.classify("das licht in dem schlafzimmer ein", Language.DE)!!
        assertEquals("turn_on", call.service)
        assertEquals("schlafzimmer", call.data["area_id"])
    }

    @Test
    fun `aus bleibt unveraendert auch ohne Anker - Regression`() {
        // "aus" steht bedingungslos in OFF_WORDS — braucht (und bekommt hier) KEINEN
        // Anker. Reiner Regressionsbeweis, dass die neue "ein"-Kante daran nichts aendert.
        val call = classifier.classify("das licht im flur aus", Language.DE)!!
        assertEquals("turn_off", call.service)
        assertEquals("flur", call.data["area_id"])
    }

    // ── Negativ-Liste (eingefroren, Andi-Auftrag 2026-08-13) ─────────────────────
    // STT liefert Interpunktion unzuverlaessig — die Kante darf sich NICHT auf "?" verlassen.

    @Test
    fun `Negativ - brennt da ein Licht im Flur - Frage darf nicht matchen`() {
        assertNull(classifier.classify("brennt da ein Licht im Flur?", Language.DE))
    }

    @Test
    fun `Negativ - brennt da ein Licht im Flur ohne Fragezeichen - STT liefert Interpunktion unzuverlaessig`() {
        // Dieselbe Frage, aber OHNE "?" (STT normalisiert Interpunktion oft weg) —
        // die Kante darf sich nicht auf das Fragezeichen verlassen, sondern muss
        // rein aus der Token-Position ("ein" VOR dem Anker = Artikel) ableiten.
        assertNull(classifier.classify("brennt da ein Licht im Flur", Language.DE))
    }

    @Test
    fun `Negativ - es war ein Licht im Flur - Aussage darf nicht matchen`() {
        assertNull(classifier.classify("es war ein Licht im Flur", Language.DE))
    }

    @Test
    fun `Negativ - mach ein Foto im Flur - kein Lichtwort, darf nicht matchen`() {
        assertNull(classifier.classify("mach ein Foto im Flur", Language.DE))
    }

    @Test
    fun `Negativ - ein Licht im Flur waere schoen - Wunsch darf nicht matchen`() {
        assertNull(classifier.classify("ein Licht im Flur wäre schön", Language.DE))
    }

    // ── EN-Analyse (dokumentiert, keine Kante gebaut) ────────────────────────────
    // Auftrag: "the light in the hallway on" ist kein natuerliches Englisch — vor
    // dem Bauen pruefen, ob EN die Luecke ueberhaupt hat, statt eine Kante zu
    // erfinden. Befund: NEIN. "on" steht (anders als "ein") bereits bedingungslos
    // in ON_WORDS und triggert ohne Schaltverb UND ohne Anker — es gibt im
    // Englischen keinen Artikel, der mit "on" kollidiert (der unbestimmte Artikel
    // ist "a"/"an", nicht "on"). Der Verb-Drop-Fall ist links vom bestehenden Code
    // schon geschlossen; dieser Test dokumentiert den Befund als Regression.

    @Test
    fun `EN-Befund - the light in the hallway on braucht KEINE neue Kante - on ist schon bedingungslos in ON_WORDS`() {
        val call = classifier.classify("the light in the hallway on", Language.EN)!!
        assertEquals("turn_on", call.service)
        assertEquals("flur", call.data["area_id"])
    }

    // ── Negativ: unbekannter Raum ⇒ kein Anker ⇒ kein Match ──────────────────────

    @Test
    fun `Negativ - unbekannter Raum im Anker - kein Katalog-Treffer heisst kein Trigger, faellt auf Rueckfrage-Pfad`() {
        // "im garten" ist kein bekannter Raum im Default-Katalog ⇒ kein Anker ⇒ die
        // neue Kante greift nicht. isLight bleibt aber true (Lichtwort "licht") — der
        // Turn faellt auf die bestehende Licht-ohne-Aktion-Regel (null), NICHT auf
        // den Raum-als-Ziel-Pfad (der ist nur fuer isLight=false gedacht).
        assertNull(classifier.classify("das licht im garten ein", Language.DE))
    }
}
