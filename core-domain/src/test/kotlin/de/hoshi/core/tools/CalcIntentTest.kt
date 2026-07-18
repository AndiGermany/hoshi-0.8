package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist die deterministische, konservative Rechen-Erkennung [CalcIntent]:
 *  - DE+EN Treffer (Grundrechenarten, Potenz, Prozent-von, Wurzel, mit/ohne Präfix),
 *  - der erzeugte `expr` ist vom [Calculator] auf den erwarteten Wert auswertbar,
 *  - konservative Nicht-Treffer (Frage ohne Rechnung, Negation, Timer-Befehl) ⇒ null.
 */
class CalcIntentTest {

    /** Hilfs-Assertion: klassifiziert als Calc UND der expr wertet auf [expected] aus. */
    private fun assertCalc(text: String, expected: Double) {
        val call = CalcIntent.classify(text)
        requireNotNull(call) { "erwartete einen Calc-ToolCall für: $text" }
        assertEquals(CalcIntent.DOMAIN, call.domain)
        assertEquals(CalcIntent.EVAL, call.service)
        val expr = call.data["expr"] as String
        val result = Calculator.evaluate(expr)
        assertEquals(Calculator.Result.Value(expected), result, "expr war: $expr")
    }

    // ── Treffer DE ───────────────────────────────────────────────────────────

    @Test fun `DE was ist 5 mal 3`() = assertCalc("was ist 5 mal 3", 15.0)
    @Test fun `DE rechne 12 plus 7`() = assertCalc("rechne 12 + 7", 19.0)
    @Test fun `DE wie viel ist 100 geteilt durch 4`() = assertCalc("wie viel ist 100 geteilt durch 4", 25.0)
    @Test fun `DE 7 hoch 2 ohne praefix`() = assertCalc("7 hoch 2", 49.0)
    @Test fun `DE 15 prozent von 200`() = assertCalc("15 prozent von 200", 30.0)
    @Test fun `DE berechne 2 plus 3 mal 4`() = assertCalc("berechne 2 + 3 * 4", 14.0)
    @Test fun `DE dezimalkomma`() = assertCalc("was ist 3,5 plus 1,5", 5.0)
    @Test fun `DE zahlwort fuenf mal drei`() = assertCalc("was ist fünf mal drei", 15.0)

    // ── Treffer EN ───────────────────────────────────────────────────────────

    @Test fun `EN what is 8 times 9`() = assertCalc("what is 8 times 9", 72.0)
    @Test fun `EN whats 10 divided by 2`() = assertCalc("what's 10 divided by 2", 5.0)
    @Test fun `EN square root of 144`() = assertCalc("square root of 144", 12.0)
    @Test fun `EN 2 to the power of 10`() = assertCalc("2 to the power of 10", 1024.0)

    // ── Division durch 0 ist trotzdem ein Calc (warme Phrase folgt im Fastpath) ──

    @Test fun `division durch null ist ein Calc`() {
        val call = CalcIntent.classify("was ist 10 geteilt durch 0")
        requireNotNull(call)
        assertEquals(Calculator.Result.DivByZero, Calculator.evaluate(call.data["expr"] as String))
    }

    // ── Echo trägt den gesprochenen Roh-Ausdruck ─────────────────────────────

    @Test fun `echo enthaelt den gesprochenen ausdruck`() {
        val call = CalcIntent.classify("was ist 5 mal 3")!!
        assertEquals("5 mal 3", call.data["echo"])
    }

    // ── Konservative Nicht-Treffer ⇒ null ────────────────────────────────────

    @Test fun `Frage ohne Rechnung ergibt null`() {
        assertNull(CalcIntent.classify("was ist dein name"))
        assertNull(CalcIntent.classify("wie warm ist es"))
    }

    @Test fun `bloße Zahl ist keine Rechnung`() = assertNull(CalcIntent.classify("was ist 2024"))

    @Test fun `Negation ergibt null`() {
        assertNull(CalcIntent.classify("rechne mit mir nicht"))
        assertNull(CalcIntent.classify("rechne nicht 5 mal 3"))
    }

    @Test fun `Geraete- und Timer-Befehl sind kein Calc`() {
        assertNull(CalcIntent.classify("mach das Licht aus"))
        assertNull(CalcIntent.classify("stell einen Timer auf 10 Minuten"))
    }

    @Test fun `unvollstaendige Rechnung ergibt null`() = assertNull(CalcIntent.classify("rechne 5 plus"))

    @Test fun `leere Eingabe ergibt null`() = assertNull(CalcIntent.classify("   "))
}
