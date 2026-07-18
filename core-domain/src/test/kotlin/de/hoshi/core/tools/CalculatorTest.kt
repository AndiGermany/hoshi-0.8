package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist den SICHEREN [Calculator] (rekursiver Abstieg, KEIN `eval`):
 *  - Grundrechenarten, Klammern, Potenz, Modulo, Vorrang, Dezimal,
 *  - Division/Modulo durch 0 ⇒ [Calculator.Result.DivByZero],
 *  - Fremdwort/kaputt ⇒ [Calculator.Result.Error] (Sicherheits-Gate).
 */
class CalculatorTest {

    private fun value(expr: String): Double {
        val r = Calculator.evaluate(expr)
        assertTrue(r is Calculator.Result.Value, "erwartete Value, war: $r")
        return (r as Calculator.Result.Value).value
    }

    // ── Grundrechenarten ─────────────────────────────────────────────────────

    @Test fun addition() = assertEquals(19.0, value("12 + 7"))
    @Test fun subtraktion() = assertEquals(8.0, value("15 - 7"))
    @Test fun multiplikation() = assertEquals(15.0, value("5 * 3"))
    @Test fun division() = assertEquals(25.0, value("100 / 4"))
    @Test fun modulo() = assertEquals(1.0, value("10 % 3"))

    // ── Potenz (rechts-assoziativ) ───────────────────────────────────────────

    @Test fun potenz() = assertEquals(49.0, value("7 ^ 2"))
    @Test fun `potenz rechts assoziativ`() = assertEquals(512.0, value("2 ^ 3 ^ 2"))

    // ── Klammern + Vorrang ───────────────────────────────────────────────────

    @Test fun `vorrang punkt vor strich`() = assertEquals(14.0, value("2 + 3 * 4"))
    @Test fun `klammern erzwingen reihenfolge`() = assertEquals(20.0, value("( 2 + 3 ) * 4"))
    @Test fun `verschachtelte klammern`() = assertEquals(14.0, value("2 * ( 3 + ( 1 + 3 ) )"))
    @Test fun `potenz vor mal`() = assertEquals(18.0, value("2 * 3 ^ 2"))

    // ── Prozent-von (als /100*) + Wurzel ─────────────────────────────────────

    @Test fun `prozent von symbolisch`() = assertEquals(30.0, value("15 /100* 200"))
    @Test fun wurzel() = assertEquals(12.0, value("sqrt 144"))
    @Test fun `wurzel mit klammer-ausdruck`() = assertEquals(13.0, value("sqrt ( 144 + 25 )"))

    // ── Unäres Vorzeichen + Dezimal ──────────────────────────────────────────

    @Test fun `unaeres minus`() = assertEquals(-15.0, value("5 * -3"))
    @Test fun dezimal() = assertEquals(5.0, value("3.5 + 1.5"))
    @Test fun `dezimal bruch`() = assertEquals(2.5, value("10 / 4"))

    // ── Division/Modulo durch 0 ──────────────────────────────────────────────

    @Test fun `division durch null`() =
        assertEquals(Calculator.Result.DivByZero, Calculator.evaluate("10 / 0"))

    @Test fun `division durch null in teilausdruck`() =
        assertEquals(Calculator.Result.DivByZero, Calculator.evaluate("5 + 1 / 0"))

    @Test fun `modulo durch null`() =
        assertEquals(Calculator.Result.DivByZero, Calculator.evaluate("10 % 0"))

    // ── Sicherheit: nur reine Ausdrücke ──────────────────────────────────────

    @Test fun `fremdwort ergibt error`() =
        assertEquals(Calculator.Result.Error, Calculator.evaluate("ich bin 5 - gelaunt"))

    @Test fun `unvollstaendig ergibt error`() =
        assertEquals(Calculator.Result.Error, Calculator.evaluate("5 +"))

    @Test fun `leer ergibt error`() =
        assertEquals(Calculator.Result.Error, Calculator.evaluate(""))

    @Test fun `offene klammer ergibt error`() =
        assertEquals(Calculator.Result.Error, Calculator.evaluate("( 2 + 3"))
}
