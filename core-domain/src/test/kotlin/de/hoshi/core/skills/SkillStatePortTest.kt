package de.hoshi.core.skills

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die zwei tragenden Eigenschaften des [SkillStatePort]:
 *  - [SkillStatePort.NONE] ist all-OFF (jeder [SkillId] aus) ⇒ der verhaltens-neutrale
 *    Default, mit dem der Classifier nichts klassifiziert.
 *  - [SkillStatePort.ofStatic] bildet jeden [SkillId] korrekt auf seinen festen Wert
 *    ab — die byte-identische Faltung der früheren vier Ctor-Booleans.
 */
class SkillStatePortTest {

    @Test
    fun `NONE ist all-off`() {
        for (id in SkillId.values()) {
            assertFalse(SkillStatePort.NONE.isEnabled(id), "NONE muss $id aus halten")
        }
    }

    @Test
    fun `ofStatic mappt jede SkillId auf ihr Boolean`() {
        val port = SkillStatePort.ofStatic(
            smartHome = true,
            scenes = false,
            timer = true,
            calculator = false,
        )
        assertTrue(port.isEnabled(SkillId.SMART_HOME))
        assertFalse(port.isEnabled(SkillId.SCENES))
        assertTrue(port.isEnabled(SkillId.TIMER))
        assertFalse(port.isEnabled(SkillId.CALCULATOR))
    }

    @Test
    fun `ofStatic all-true und all-false sind konsistent`() {
        val on = SkillStatePort.ofStatic(smartHome = true, scenes = true, timer = true, calculator = true)
        val off = SkillStatePort.ofStatic(smartHome = false, scenes = false, timer = false, calculator = false)
        for (id in SkillId.values()) {
            assertTrue(on.isEnabled(id), "all-true muss $id an haben")
            assertFalse(off.isEnabled(id), "all-false muss $id aus halten")
        }
    }
}
