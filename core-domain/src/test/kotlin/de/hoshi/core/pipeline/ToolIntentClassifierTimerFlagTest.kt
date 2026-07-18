package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.tools.TimerIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist die Flag-Gates des [DeterministicToolIntentClassifier]:
 *  - `timerEnabled=false` (Default) ⇒ Timer wird gar nicht erkannt (OFF = inert).
 *  - `timerEnabled=true` aktiviert NUR den Timer-Zweig; die Geräte-Zweige bleiben
 *    unverändert aktiv (kein Regress).
 *  - `toolsEnabled=false` schaltet die Geräte-Zweige aus, ohne den Timer zu berühren.
 */
class ToolIntentClassifierTimerFlagTest {

    @Test
    fun `OFF Default erkennt keinen Timer`() {
        val c = DeterministicToolIntentClassifier() // timerEnabled=false per Default
        assertNull(c.classify("stell einen Timer auf 10 Minuten", Language.DE))
        assertNull(c.classify("weck mich um 7", Language.DE))
    }

    @Test
    fun `ON erkennt den Timer als domain timer`() {
        val c = DeterministicToolIntentClassifier(timerEnabled = true)
        val call = c.classify("stell einen Timer auf 10 Minuten", Language.DE)!!
        assertEquals(TimerIntent.DOMAIN, call.domain)
        assertEquals(TimerIntent.SET, call.service)
    }

    @Test
    fun `ON Timer laesst die Geraete-Zweige unveraendert`() {
        // Mit timerEnabled=true muss „Licht in der Küche an" weiter das Licht-Tool treffen.
        val c = DeterministicToolIntentClassifier(timerEnabled = true)
        val call = c.classify("Licht in der Küche an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("kuche", call.data["area_id"])
    }

    @Test
    fun `toolsEnabled false schaltet Geraete-Zweige aus aber nicht den Timer`() {
        val c = DeterministicToolIntentClassifier(toolsEnabled = false, timerEnabled = true)
        // Geräte-Befehl ⇒ null (Geräte-Zweige aus)…
        assertNull(c.classify("Licht in der Küche an", Language.DE))
        // …aber der Timer wird weiter erkannt.
        assertEquals(TimerIntent.DOMAIN, c.classify("stell einen Timer auf 10 Minuten", Language.DE)!!.domain)
    }
}
