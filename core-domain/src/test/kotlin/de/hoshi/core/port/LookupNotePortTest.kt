package de.hoshi.core.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.time.Instant

/**
 * Beweist die EINE Normalisierungs-Wahrheit ([LookupNoteNormalizer]), die
 * Schreib- ([de.hoshi.core.pipeline.TurnOrchestrator]) und Lese-Seite
 * (`NachgeschlagenGroundingProvider`) teilen, sowie den byte-neutralen
 * [LookupNotePort.NOOP]-Default.
 */
class LookupNotePortTest {

    @Test
    fun `normalize - lowercase, Satzzeichen weg, Whitespace kollabiert`() {
        assertEquals(
            "wie hoch ist der eiffelturm",
            LookupNoteNormalizer.normalize("Wie hoch ist der Eiffelturm?"),
        )
        assertEquals(
            "wie hoch ist der eiffelturm",
            LookupNoteNormalizer.normalize("  Wie   hoch ist der Eiffelturm?!  "),
            "Whitespace/Satzzeichen-Varianten normalisieren auf dieselbe Form",
        )
    }

    @Test
    fun `normalize ist deterministisch fuer dieselbe Frage in unterschiedlicher Schreibweise`() {
        val a = LookupNoteNormalizer.normalize("Wie hoch ist der Eiffelturm?")
        val b = LookupNoteNormalizer.normalize("wie HOCH ist DER eiffelturm")
        assertEquals(a, b)
    }

    @Test
    fun `tokens filtert auf Content-Woerter ueber 3 Zeichen`() {
        val tokens = LookupNoteNormalizer.tokens(LookupNoteNormalizer.normalize("Wie hoch ist der Eiffelturm?"))
        assertEquals(setOf("hoch", "eiffelturm"), tokens, "wie/ist/der sind <=3 Zeichen und fallen raus")
    }

    @Test
    fun `sha256Hex ist deterministisch und aendert sich mit dem Text`() {
        val h1 = LookupNoteNormalizer.sha256Hex("wie hoch ist der eiffelturm")
        val h2 = LookupNoteNormalizer.sha256Hex("wie hoch ist der eiffelturm")
        val h3 = LookupNoteNormalizer.sha256Hex("wie hoch ist der kölner dom")
        assertEquals(h1, h2, "gleicher Text ⇒ gleicher Hash")
        assertTrue(h1 != h3, "unterschiedlicher Text ⇒ unterschiedlicher Hash")
        assertEquals(64, h1.length, "SHA-256 hex = 64 Zeichen")
    }

    @Test
    fun `LookupNoteFenceGuard neutralize ersetzt nur die beiden Zaun-Klammerzeichen`() {
        assertEquals(
            "[ZITAT-ANFANG] Text [ZITAT-ENDE]",
            LookupNoteFenceGuard.neutralize("⟦ZITAT-ANFANG⟧ Text ⟦ZITAT-ENDE⟧"),
        )
    }

    @Test
    fun `LookupNoteFenceGuard neutralize laesst normalen Text byte-identisch`() {
        val text = "Der Eiffelturm ist 330 Meter hoch (Quelle: Wikipedia)."
        assertEquals(text, LookupNoteFenceGuard.neutralize(text), "kein normales Satzzeichen wird angefasst")
    }

    @Test
    fun `NOOP schreibt und findet nie, wirft nie`() {
        val note = LookupNote(
            queryHash = "x", queryNorm = "y", answer = "z", source = "s",
            provider = "openai-nano", costCents = 0.1, ts = Instant.now(), ttlDays = 30,
        )
        assertDoesNotThrow { LookupNotePort.NOOP.record(note) }
        assertNull(LookupNotePort.NOOP.find("y"), "NOOP findet nie, egal was zuvor 'geschrieben' wurde")
    }
}
