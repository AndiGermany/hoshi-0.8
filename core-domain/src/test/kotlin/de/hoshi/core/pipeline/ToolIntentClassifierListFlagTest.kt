package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.tools.ListIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist die Flag-Gates des [DeterministicToolIntentClassifier] für die
 * Listen-Lane (Andi-JA 2026-07-08):
 *  - `listEnabled=false` (Default) ⇒ Listen-Befehle werden gar nicht erkannt
 *    (OFF = inert, byte-neutral).
 *  - `listEnabled=true` aktiviert NUR den List-Zweig; die Geräte-/Timer-Zweige
 *    bleiben unverändert aktiv (kein Regress).
 *  - `toolsEnabled=false` schaltet die Geräte-Zweige aus, ohne den List-Zweig
 *    zu berühren (List ist unabhängig von HOSHI_TOOLS_ENABLED, exakt wie Timer).
 */
class ToolIntentClassifierListFlagTest {

    @Test
    fun `OFF Default erkennt keine Listen-Befehle`() {
        val c = DeterministicToolIntentClassifier() // listEnabled=false per Default
        assertNull(c.classify("Setz Milch auf die Einkaufsliste.", Language.DE))
        assertNull(c.classify("Was steht auf der Liste?", Language.DE))
        assertNull(c.classify("Nimm die Milch von der Liste.", Language.DE))
    }

    @Test
    fun `ON erkennt ADD als domain list`() {
        val c = DeterministicToolIntentClassifier(listEnabled = true)
        val call = c.classify("Setz Milch auf die Einkaufsliste.", Language.DE)!!
        assertEquals(ListIntent.DOMAIN, call.domain)
        assertEquals(ListIntent.ADD, call.service)
    }

    @Test
    fun `ON erkennt REMOVE mit Vorrang vor ADD`() {
        val c = DeterministicToolIntentClassifier(listEnabled = true)
        val call = c.classify("Nimm die Milch von der Liste.", Language.DE)!!
        assertEquals(ListIntent.REMOVE, call.service)
    }

    @Test
    fun `ON Waechter-Satz bleibt weiterhin unerkannt (faellt an den Brain)`() {
        val c = DeterministicToolIntentClassifier(listEnabled = true)
        assertNull(c.classify("Mach mir eine Liste von Ideen für Papas Geburtstag.", Language.DE))
    }

    @Test
    fun `ON List laesst die Geraete- und Timer-Zweige unveraendert`() {
        val c = DeterministicToolIntentClassifier(listEnabled = true, timerEnabled = true)
        val light = c.classify("Licht in der Küche an", Language.DE)!!
        assertEquals("light", light.domain)
        assertEquals("turn_on", light.service)

        val timer = c.classify("stell einen Timer auf 10 Minuten", Language.DE)!!
        assertEquals("timer", timer.domain)
    }

    @Test
    fun `toolsEnabled false schaltet Geraete-Zweige aus aber nicht die Liste`() {
        val c = DeterministicToolIntentClassifier(toolsEnabled = false, listEnabled = true)
        // Geräte-Befehl ⇒ null (Geräte-Zweige aus)…
        assertNull(c.classify("Licht in der Küche an", Language.DE))
        // …aber die Liste wird weiter erkannt.
        assertEquals(ListIntent.DOMAIN, c.classify("Was steht auf der Liste?", Language.DE)!!.domain)
    }
}
