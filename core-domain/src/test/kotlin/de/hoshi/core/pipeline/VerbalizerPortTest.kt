package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Beweist: [VerbalizerPort.NONE] ist die Identität — kein Text ändert sich. */
class VerbalizerPortTest {

    @Test
    fun `NONE liefert einfachen Text unveraendert (DE)`() {
        assertEquals("Hallo Welt", VerbalizerPort.NONE.verbalize("Hallo Welt", Language.DE))
    }

    @Test
    fun `NONE liefert Ziffern unveraendert (DE)`() {
        assertEquals("21°C", VerbalizerPort.NONE.verbalize("21°C", Language.DE))
        assertEquals("Es sind 3 Nachrichten da.", VerbalizerPort.NONE.verbalize("Es sind 3 Nachrichten da.", Language.DE))
    }

    @Test
    fun `NONE liefert Umlaute unveraendert (DE)`() {
        assertEquals("Grüße aus Düsseldorf ä ö ü ß", VerbalizerPort.NONE.verbalize("Grüße aus Düsseldorf ä ö ü ß", Language.DE))
    }

    @Test
    fun `NONE liefert leeren String unveraendert (DE)`() {
        assertEquals("", VerbalizerPort.NONE.verbalize("", Language.DE))
    }

    @Test
    fun `NONE liefert Ziffern und Symbole unveraendert (EN)`() {
        assertEquals("It's 1.5 degrees, 100% sure.", VerbalizerPort.NONE.verbalize("It's 1.5 degrees, 100% sure.", Language.EN))
    }

    @Test
    fun `NONE liefert leeren String unveraendert (EN)`() {
        assertEquals("", VerbalizerPort.NONE.verbalize("", Language.EN))
    }

    @Test
    fun `NONE liefert Ziffern unveraendert (ES)`() {
        assertEquals("Son las 21:30 y 5°C.", VerbalizerPort.NONE.verbalize("Son las 21:30 y 5°C.", Language.ES))
    }
}
