package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist den flag-gated, KONSERVATIVEN Embedded-Pfad von [CalcIntent] (Ticket #1b):
 * eine satz-EINGEBETTETE einfache Rechnung („Erklär mir, wie viel 17 mal 23 ergibt")
 * geht — bei `allowEmbedded=true` — deterministisch in die Calc-Fastpath statt ins
 * Brain (wo ein 4B „391" zu „dreihundertneunundhochzehn" garbelt).
 *
 *  - **Byte-Neutralität:** mit dem Default (`allowEmbedded=false`) ist das Verhalten
 *    UNVERÄNDERT — eingebettete Rechnungen bleiben ungefangen (`null`).
 *  - **Treffer:** klare 2-Zahlen-Arithmetik mit Cue ⇒ Calc, `expr` wertet korrekt aus.
 *  - **Kein False-Positive:** normale Sätze (auch mit Cue UND Zahlen, aber ohne
 *    sauberen num-op-num) ⇒ `null`.
 */
class CalcIntentEmbeddedTest {

    private fun assertEmbedded(text: String, expected: Double) {
        // Default (OFF) ⇒ byte-neutral: eingebettet wird NICHT gefangen.
        assertNull(CalcIntent.classify(text), "Default (allowEmbedded=false) darf nicht fangen: $text")
        // ON ⇒ Calc-ToolCall, dessen expr korrekt auswertet.
        val call = CalcIntent.classify(text, allowEmbedded = true)
        requireNotNull(call) { "erwartete einen eingebetteten Calc-ToolCall für: $text" }
        assertEquals(CalcIntent.DOMAIN, call.domain)
        assertEquals(CalcIntent.EVAL, call.service)
        val expr = call.data["expr"] as String
        assertEquals(Calculator.Result.Value(expected), Calculator.evaluate(expr), "expr war: $expr")
    }

    // ── Treffer (eingebettet) ────────────────────────────────────────────────

    @Test fun `das Live-Garble Beispiel 17 mal 23 im Satz`() =
        assertEmbedded("Erklaer mir, wie viel 17 mal 23 ergibt", 391.0)

    @Test fun `Rechnung am Satzende mit nachgestellter Frage`() =
        assertEmbedded("17 mal 23, was ergibt das?", 391.0)

    @Test fun `eingebettete Division`() =
        assertEmbedded("Erklaer mir, wie viel 100 geteilt durch 4 ergibt", 25.0)

    @Test fun `eingebettetes Plus mit Fuellwort davor`() =
        assertEmbedded("Sag mal, wie viel ist 5 plus 3", 8.0)

    // ── Kein eingebetteter Echo ⇒ neutrale Quittung (Fastpath spricht sprach-korrekt) ──

    @Test fun `eingebetteter Treffer traegt keinen echo`() {
        val call = CalcIntent.classify("Erklaer mir, wie viel 17 mal 23 ergibt", allowEmbedded = true)!!
        assertNull(call.data["echo"])
    }

    // ── Konservativ: KEIN Fang auf normalen Saetzen (auch mit ON) ─────────────

    @Test fun `Cue plus Zahlen aber kein sauberer Ausdruck ⇒ null`() {
        // „wie viel" als Cue da, aber „5 euro oder 3 euro" ist kein num-op-num.
        assertNull(CalcIntent.classify("Wie viel kostet das, 5 Euro oder 3 Euro?", allowEmbedded = true))
    }

    @Test fun `mal als Haeufigkeit ist keine Multiplikation`() {
        assertNull(CalcIntent.classify("Ich war fünf mal in Berlin", allowEmbedded = true))
    }

    @Test fun `normaler Satz ohne Rechen-Cue ⇒ null`() {
        assertNull(CalcIntent.classify("Erzähl mir was über die Zahl Pi", allowEmbedded = true))
        assertNull(CalcIntent.classify("Wir treffen uns um 5 vor 12", allowEmbedded = true))
    }

    @Test fun `Negation ⇒ null auch eingebettet`() {
        assertNull(CalcIntent.classify("Rechne bloß nicht 5 mal 3 für mich aus", allowEmbedded = true))
    }

    @Test fun `zu komplex eingebettet (Prozent, mehrere Operatoren) ⇒ null`() {
        // Konservativ: Prozent (/100*) und Ketten werden eingebettet NICHT gefangen.
        assertNull(CalcIntent.classify("Erklaer mir, wie viel 15 prozent von 200 sind", allowEmbedded = true))
        assertNull(CalcIntent.classify("Erklaer mir, wie viel 2 plus 3 mal 4 ergibt", allowEmbedded = true))
    }

    // ── Reiner-Ausdruck-Pfad bleibt unberührt (Default faengt ihn weiterhin) ──

    @Test fun `reiner Ausdruck wird auch mit Default gefangen`() {
        val call = CalcIntent.classify("was ist 5 mal 3")
        requireNotNull(call)
        assertEquals("5 * 3", call.data["expr"])
        assertEquals("5 mal 3", call.data["echo"], "der reine Pfad traegt weiterhin den echo")
    }
}
