package de.hoshi.core.tools

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * **Calculator** — ein SICHERER, reiner Ausdrucks-Evaluator (KEIN `eval`, KEINE
 * `ScriptEngine`, keine Reflection, keine I/O). Tokenisiert einen bereits in
 * SYMBOLISCHE Form gebrachten Ausdruck (Ziffern, `+ - * / % ^`, Klammern,
 * `sqrt`, Dezimalpunkt) und wertet ihn per **rekursivem Abstieg** mit korrekter
 * Operator-Präzedenz aus. Reines Kotlin, deterministisch, side-effect-frei.
 *
 * Grammatik (Präzedenz aufsteigend, `^` rechts-assoziativ):
 * ```
 * expression := term      (('+' | '-') term)*
 * term       := unary     (('*' | '/' | '%') unary)*
 * unary      := ('+' | '-') unary | power
 * power      := primary   ('^' unary)?
 * primary    := NUMBER | '(' expression ')' | 'sqrt' unary
 * ```
 *
 * Sicherheit: nur die obigen Token sind erlaubt — JEDES unbekannte Zeichen/Wort
 * lässt [tokenize] scheitern ([Result.Error]). Division/Modulo durch 0 ⇒
 * [Result.DivByZero] (warme Phrase im [de.hoshi.core.pipeline.CalcFastpath]).
 * Nicht-endliche Ergebnisse (NaN/∞, z.B. Wurzel aus negativ) ⇒ [Result.Error].
 *
 * Die **Normalisierung** von Wort-Operatoren („mal", „geteilt durch", „times"…)
 * und Zahlwörtern in diese symbolische Form macht der [CalcIntent] — der
 * [Calculator] sieht NUR Symbole und bleibt damit klein und scharf prüfbar.
 */
object Calculator {

    /** Dreiwertiges Auswerte-Ergebnis (analog [ToolGrammarParser.Result]). */
    sealed interface Result {
        /** Ein endliches Zahl-Ergebnis. */
        data class Value(val value: Double) : Result

        /** Es wurde durch null geteilt (oder modulo null) — kein Zahl-Ergebnis. */
        object DivByZero : Result

        /** Nicht parsebar / unbekanntes Token / nicht-endliches Ergebnis. */
        object Error : Result
    }

    /** Wertet den symbolischen [expr] sicher aus. Wirft NIE — alles via [Result]. */
    fun evaluate(expr: String): Result {
        val tokens = tokenize(expr) ?: return Result.Error
        if (tokens.isEmpty()) return Result.Error
        return try {
            val parser = Parser(tokens)
            val value = parser.expression()
            if (!parser.atEnd()) return Result.Error // unverbrauchte Token ⇒ kein gültiger Ausdruck
            if (!value.isFinite()) return Result.Error
            Result.Value(value)
        } catch (_: DivByZeroSignal) {
            Result.DivByZero
        } catch (_: ParseSignal) {
            Result.Error
        }
    }

    // ── Tokenizer ────────────────────────────────────────────────────────────

    private sealed interface Token
    private data class Num(val v: Double) : Token
    private data class Op(val c: Char) : Token
    private object LParen : Token
    private object RParen : Token
    private object Sqrt : Token

    /** Zerlegt [expr] in Token; `null` bei JEDEM unbekannten Zeichen/Wort (Sicherheits-Gate). */
    private fun tokenize(expr: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        val n = expr.length
        while (i < n) {
            val ch = expr[i]
            when {
                ch == ' ' || ch == '\t' -> i++
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    var dots = if (ch == '.') 1 else 0
                    i++
                    while (i < n && (expr[i].isDigit() || expr[i] == '.')) {
                        if (expr[i] == '.') dots++
                        i++
                    }
                    if (dots > 1) return null // mehrfacher Dezimalpunkt ⇒ ungültig
                    val num = expr.substring(start, i).toDoubleOrNull() ?: return null
                    out.add(Num(num))
                }
                ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '%' || ch == '^' -> {
                    out.add(Op(ch)); i++
                }
                ch == '(' -> { out.add(LParen); i++ }
                ch == ')' -> { out.add(RParen); i++ }
                expr.startsWith("sqrt", i) -> { out.add(Sqrt); i += 4 }
                else -> return null // unbekanntes Zeichen/Wort ⇒ kein reiner Ausdruck
            }
        }
        return out
    }

    // ── Rekursiver-Abstiegs-Parser (+ Auswertung in einem Pass) ──────────────

    /** Interner Signal-Wurf für Division durch 0 (oben in [Result.DivByZero] gefangen). */
    private class DivByZeroSignal : RuntimeException()

    /** Interner Signal-Wurf für Struktur-/Parse-Fehler (oben in [Result.Error] gefangen). */
    private class ParseSignal : RuntimeException()

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= tokens.size
        private fun peek(): Token? = tokens.getOrNull(pos)
        private fun advance(): Token? = tokens.getOrNull(pos++)

        fun expression(): Double {
            var left = term()
            while (true) {
                val t = peek()
                if (t is Op && (t.c == '+' || t.c == '-')) {
                    pos++
                    val right = term()
                    left = if (t.c == '+') left + right else left - right
                } else {
                    return left
                }
            }
        }

        private fun term(): Double {
            var left = unary()
            while (true) {
                val t = peek()
                if (t is Op && (t.c == '*' || t.c == '/' || t.c == '%')) {
                    pos++
                    val right = unary()
                    left = when (t.c) {
                        '*' -> left * right
                        '/' -> if (right == 0.0) throw DivByZeroSignal() else left / right
                        else -> if (right == 0.0) throw DivByZeroSignal() else left % right
                    }
                } else {
                    return left
                }
            }
        }

        private fun unary(): Double {
            val t = peek()
            if (t is Op && (t.c == '+' || t.c == '-')) {
                pos++
                val v = unary()
                return if (t.c == '-') -v else v
            }
            return power()
        }

        private fun power(): Double {
            val base = primary()
            val t = peek()
            if (t is Op && t.c == '^') {
                pos++
                val exp = unary() // rechts-assoziativ, erlaubt 2^-1 und 2^3^2
                return base.pow(exp)
            }
            return base
        }

        private fun primary(): Double {
            return when (val t = advance()) {
                is Num -> t.v
                LParen -> {
                    val v = expression()
                    if (advance() !== RParen) throw ParseSignal()
                    v
                }
                Sqrt -> sqrt(unary())
                else -> throw ParseSignal()
            }
        }
    }
}
