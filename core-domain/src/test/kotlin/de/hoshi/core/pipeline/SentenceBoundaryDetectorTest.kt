package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Fokus-Test für die portierte reine Satz-Grenz-Funktion. In 0.5 wurde sie nur
 * indirekt über SentenceStreamBuilderTest abgedeckt (Reactor-/Spring-verdrahtet,
 * → für M2b/Orchestrator zurückgestellt). Dieser Test hält die pure Logik im Gate.
 */
class SentenceBoundaryDetectorTest {

    @Test
    fun `findet erste Grenze ab minChars`() {
        // "Hallo Andi." — Punkt an Index 10, minChars 5
        assertEquals(10, SentenceBoundaryDetector.firstSentenceBoundary("Hallo Andi.", 5))
    }

    @Test
    fun `respektiert minChars und ueberspringt fruehe Satzzeichen`() {
        // Komma an Index 2 liegt vor minChars=5 → erst der Punkt an Index 9 zaehlt
        assertEquals(9, SentenceBoundaryDetector.firstSentenceBoundary("Ja, danke.", 5))
    }

    @Test
    fun `keine Grenze bei zu kurzem Text`() {
        assertEquals(-1, SentenceBoundaryDetector.firstSentenceBoundary("Hi.", 5))
    }

    @Test
    fun `keine Grenze ohne Satzzeichen`() {
        assertEquals(-1, SentenceBoundaryDetector.firstSentenceBoundary("kurz ohne Punkt", 5))
    }

    @Test
    fun `konfigurierbares Punctuation-Set triggert auf Doppelpunkt`() {
        // Default-Set enthaelt ":" — Doppelpunkt an Index 6
        assertEquals(6, SentenceBoundaryDetector.firstSentenceBoundary("Listen: a, b", 5))
        // Eingeschraenktes Set ".!?" ignoriert ":" → faellt auf Komma? Nein, Komma nicht im Set → -1
        assertEquals(-1, SentenceBoundaryDetector.firstSentenceBoundary("Listen: a b", 5, ".!?"))
    }

    // ── Ordinal-Guard (Andis Juli-Pitch-Befund 2026-07-01) ───────────────────────

    @Test
    fun `Ordinal-Datum splittet nicht am Ordinal-Punkt sondern erst am Satzende`() {
        // "1." (Ziffer davor, Buchstabe danach) ist KEINE Grenze — erst der
        // finale Punkt nach der Jahreszahl (Punctuation-Set wie im TtsStage).
        val text = "Heute ist Mittwoch, der 1. Juli 2026."
        assertEquals(text.lastIndex, SentenceBoundaryDetector.firstSentenceBoundary(text, 5, ".!?"))
    }

    @Test
    fun `zweistelliges Ordinal wird ebenfalls geschuetzt`() {
        val text = "Am 24. Dezember ist Heiligabend."
        assertEquals(text.lastIndex, SentenceBoundaryDetector.firstSentenceBoundary(text, 5, ".!?"))
    }

    @Test
    fun `Jahreszahl mit 4 Ziffern splittet weiterhin normal`() {
        // 4 Ziffern vor dem Punkt = Jahr, kein Ordinal → Grenze direkt nach "2026."
        val text = "Das war im Jahr 2026. Danach kam mehr."
        assertEquals(text.indexOf('.'), SentenceBoundaryDetector.firstSentenceBoundary(text, 5, ".!?"))
    }

    @Test
    fun `Ordinal-Punkt am Puffer-Ende wartet auf mehr Text`() {
        // Streaming-Race: der Folgetext ("Juli …") ist noch nicht im Puffer → -1.
        assertEquals(-1, SentenceBoundaryDetector.firstSentenceBoundary("Heute ist Mittwoch, der 1.", 5, ".!?"))
        // Auch mit trailing Whitespace fehlt das entscheidende Folgezeichen noch.
        assertEquals(-1, SentenceBoundaryDetector.firstSentenceBoundary("Heute ist Mittwoch, der 1. ", 5, ".!?"))
    }

    @Test
    fun `normale Saetze splitten unveraendert`() {
        // Buchstabe vor dem Punkt = kein Ordinal → Grenze auch am Puffer-Ende.
        assertEquals(10, SentenceBoundaryDetector.firstSentenceBoundary("Hallo Andi.", 5, ".!?"))
        // Wort vor dem Punkt + Folgewort → Grenze wie bisher (Index 11).
        assertEquals(11, SentenceBoundaryDetector.firstSentenceBoundary("Es ist warm. Juli eben.", 5, ".!?"))
    }
    /**
     * Andi-Befund 21.07 abends: „der liest immer noch die komplette quelle vor."
     * Ursache war NICHT der TTS-Filter (der war korrekt), sondern DIESE Stelle: eine
     * URL steckt voller Satzzeichen, der Puffer zerlegte die Quellenangabe in
     * Bruchstücke, und auf ein Bruchstück passt keine Filter-Regel mehr.
     */
    @Test
    fun `Satzzeichen INNERHALB einer URL sind keine Satzgrenze`() {
        val text = "Es erscheint am 19. November 2026. ([rockstargames.com](https://www.rockstargames.com/newswire/x?utm_source=openai))"
        val idx = SentenceBoundaryDetector.firstSentenceBoundary(text, 12)
        // Die einzige echte Grenze ist der Punkt nach "2026" — davor/danach nichts.
        assertEquals(text.indexOf("2026.") + 4, idx)
    }

    @Test
    fun `Uhrzeiten und Domains bleiben unzerschnitten`() {
        assertEquals(-1, SentenceBoundaryDetector.firstSentenceBoundary("Es ist jetzt 20:15 Uhr", 12))
        assertEquals(-1, SentenceBoundaryDetector.firstSentenceBoundary("Schau auf rockstargames.com nach", 12))
    }

}
