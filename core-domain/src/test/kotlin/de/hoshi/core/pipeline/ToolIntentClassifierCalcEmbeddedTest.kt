package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.core.tools.CalcIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist das Flag-Gate `calcEmbeddedEnabled` des [DeterministicToolIntentClassifier]
 * (Ticket #1b) — end-to-end durch den Classifier:
 *  - OFF (Default) ⇒ satz-eingebettete Arithmetik wird NICHT gefangen (byte-neutral),
 *    aber der reine Ausdruck weiterhin schon.
 *  - ON ⇒ die eingebettete Rechnung wird zum deterministischen `calc`-ToolCall.
 *  - Greift NUR, wenn der Calc-Skill ohnehin an ist.
 */
class ToolIntentClassifierCalcEmbeddedTest {

    private val calcOnly = SkillStatePort.ofStatic(
        smartHome = false, scenes = false, timer = false, calculator = true,
    )

    private val embedded = "Erklaer mir, wie viel 17 mal 23 ergibt"

    @Test
    fun `OFF Default faengt eingebettete Arithmetik NICHT, reinen Ausdruck schon`() {
        val c = DeterministicToolIntentClassifier(skills = calcOnly) // calcEmbeddedEnabled=false
        assertNull(c.classify(embedded, Language.DE), "eingebettet darf bei OFF nicht fangen")
        val pure = c.classify("was ist 17 mal 23", Language.DE)!!
        assertEquals(CalcIntent.DOMAIN, pure.domain)
        assertEquals("17 * 23", pure.data["expr"])
    }

    @Test
    fun `ON faengt die eingebettete Rechnung als domain calc`() {
        val c = DeterministicToolIntentClassifier(skills = calcOnly, calcEmbeddedEnabled = true)
        val call = c.classify(embedded, Language.DE)!!
        assertEquals(CalcIntent.DOMAIN, call.domain)
        assertEquals(CalcIntent.EVAL, call.service)
        assertEquals("17 * 23", call.data["expr"])
    }

    @Test
    fun `ON faengt normale Saetze trotzdem nicht`() {
        val c = DeterministicToolIntentClassifier(skills = calcOnly, calcEmbeddedEnabled = true)
        assertNull(c.classify("Wie viel kostet das, 5 Euro oder 3 Euro?", Language.DE))
        assertNull(c.classify("Erzähl mir was über die Zahl Pi", Language.DE))
    }

    @Test
    fun `ON ohne Calc-Skill bleibt wirkungslos`() {
        val allOff = SkillStatePort.ofStatic(smartHome = false, scenes = false, timer = false, calculator = false)
        val c = DeterministicToolIntentClassifier(skills = allOff, calcEmbeddedEnabled = true)
        assertNull(c.classify(embedded, Language.DE))
    }
}
