package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.tools.CalcIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist das Flag-Gate `calculatorEnabled` des [DeterministicToolIntentClassifier]:
 *  - OFF (Default) ⇒ keine Rechnung wird erkannt (inert, byte-neutral).
 *  - ON aktiviert NUR den Calc-Zweig; Geräte-/Timer-Zweige bleiben unberührt.
 *  - der Calc-Zweig ist unabhängig von `toolsEnabled` (wie der Timer).
 */
class ToolIntentClassifierCalcFlagTest {

    @Test
    fun `OFF Default erkennt keine Rechnung`() {
        val c = DeterministicToolIntentClassifier() // calculatorEnabled=false per Default
        assertNull(c.classify("was ist 5 mal 3", Language.DE))
        assertNull(c.classify("7 hoch 2", Language.DE))
    }

    @Test
    fun `ON erkennt die Rechnung als domain calc`() {
        val c = DeterministicToolIntentClassifier(calculatorEnabled = true)
        val call = c.classify("was ist 5 mal 3", Language.DE)!!
        assertEquals(CalcIntent.DOMAIN, call.domain)
        assertEquals(CalcIntent.EVAL, call.service)
        assertEquals("5 * 3", call.data["expr"])
    }

    @Test
    fun `ON Calc laesst die Geraete-Zweige unveraendert`() {
        val c = DeterministicToolIntentClassifier(calculatorEnabled = true)
        val call = c.classify("Licht in der Küche an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
    }

    @Test
    fun `calc-Zweig ist unabhaengig von toolsEnabled`() {
        val c = DeterministicToolIntentClassifier(toolsEnabled = false, calculatorEnabled = true)
        // Geräte-Befehl ⇒ null (Geräte-Zweige aus)…
        assertNull(c.classify("Licht in der Küche an", Language.DE))
        // …aber die Rechnung wird weiter erkannt.
        assertEquals(CalcIntent.DOMAIN, c.classify("was ist 5 mal 3", Language.DE)!!.domain)
    }
}
