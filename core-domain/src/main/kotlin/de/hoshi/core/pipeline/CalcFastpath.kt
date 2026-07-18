package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.tools.Calculator
import de.hoshi.core.tools.ToolCall
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * **CalcFastpath** — der brain-freie Vollzug eines Calc-[ToolCall] (`domain ==
 * "calc"`): den vom [de.hoshi.core.tools.CalcIntent] gelieferten SYMBOLISCHEN
 * Ausdruck (`data["expr"]`) durch den SICHEREN [Calculator] (rekursiver Abstieg,
 * KEIN `eval`, KEINE `ScriptEngine`) auswerten und eine warme, deterministische
 * deutsche/englische Quittung sprechen. Ruft den Brain NIE.
 *
 * Spiegelbild zum [TimerFastpath], aber ZUSTANDSLOS: kein Store, keine Uhr — eine
 * Rechnung ist reine Arithmetik. Die Quittung echo't den gesprochenen Ausdruck
 * (`data["echo"]`): „5 mal 3 ist 15." / „5 times 3 is 15.". Division durch null ⇒
 * warme Phrase („Durch null geht leider nicht."). Ganze Zahlen ohne `.0`, sonst
 * sinnvoll gerundet; in der DE-Quittung mit Dezimalkomma.
 *
 * [DISABLED] ist der nie-erreichte Default (Flag-OFF): ohne `HOSHI_CALCULATOR_ENABLED`
 * emittiert der Classifier keinen `calc`-Call, der Zweig im [TurnOrchestrator] ist
 * tot ⇒ byte-neutral.
 */
class CalcFastpath {

    /** Wertet den Calc-Call aus und liefert die fertige, sprechbare Quittung. */
    fun handle(call: ToolCall, language: Language): String {
        val expr = (call.data["expr"] as? String)?.takeIf { it.isNotBlank() } ?: return ""
        val echo = (call.data["echo"] as? String)?.takeIf { it.isNotBlank() }
        val en = language == Language.EN
        return when (val result = Calculator.evaluate(expr)) {
            is Calculator.Result.Value -> {
                val number = formatNumber(result.value, en)
                if (echo != null) {
                    // DE-Quittung: das Nomen "Prozent" gross schreiben (live war es "prozent").
                    // EN unveraendert; ohne "prozent" im Echo ist der replace ein No-Op (byte-neutral).
                    if (en) "$echo is $number." else "${echo.replace("prozent", "Prozent")} ist $number."
                } else {
                    if (en) "That's $number." else "Das macht $number."
                }
            }
            Calculator.Result.DivByZero ->
                if (en) "You can't divide by zero." else "Durch null geht leider nicht."
            // Sollte nach der Intent-Validierung nie auftreten — leer ⇒ Never-Silent-Fallback.
            Calculator.Result.Error -> ""
        }
    }

    /**
     * Hübsche Zahl-Ausgabe: ganze Zahlen ohne `.0`, sonst auf 6 Nachkommastellen
     * gerundet und trailing-Nullen gestrippt. DE nutzt das Dezimalkomma.
     */
    private fun formatNumber(value: Double, en: Boolean): String {
        val s =
            if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                BigDecimal.valueOf(value)
                    .setScale(6, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
            }
        return if (en) s else s.replace('.', ',')
    }

    companion object {
        /** Nie erreichter Default (Flag-OFF): der Calc-Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = CalcFastpath()
    }
}
