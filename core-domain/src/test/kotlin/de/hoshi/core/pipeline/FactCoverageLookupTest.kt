package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der lokale Lookup darf Deckung nie aus Flags oder bloßem Textvolumen ableiten:
 * er verlangt einen echten, query-bezogenen Wissensblock.
 */
class FactCoverageLookupTest {

    /** Absichtlich beide Legacy-Schalter AUS: lookupCovered muss trotzdem streng bleiben. */
    private val gate = FactCoverageGate(enabled = false, strict = false)

    @Test
    fun `lookupCovered akzeptiert einen on-target Wiki-Block`() {
        val query = "Wie viele Planeten gibt es in unserem Sonnensystem?"
        val block = "\n\n---\nHINTERGRUND: Das Sonnensystem umfasst acht Planeten."

        assertTrue(gate.lookupCovered(block, query))
    }

    @Test
    fun `lookupCovered lehnt einen off-target Block trotz Possessiv-Ueberschneidung ab`() {
        val query = "Wie viele Planeten gibt es in unserem Sonnensystem?"
        val block = "\n\n---\nHINTERGRUND: In unserem Haus gibt es mehrere Lampen."

        assertFalse(
            gate.lookupCovered(block, query),
            "»unserem« ist Possessiv-Filler und darf keine falsche Deckung erzeugen",
        )
    }

    @Test
    fun `lookupCovered ist bei leer sentinel und filler-only fail-closed`() {
        assertFalse(gate.lookupCovered("", "Was ist Photosynthese?"))
        assertFalse(
            gate.lookupCovered(
                TurnPromptAssembler.BRIDGE_DOWN_SENTINEL,
                "Was ist Photosynthese?",
            ),
        )
        assertFalse(
            gate.lookupCovered(
                "irgendein nichtleerer Block",
                "Wie ist das denn?",
            ),
            "ohne substantielles Query-Token gibt es keinen Coverage-Beweis",
        )
    }
}
