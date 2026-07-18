package de.hoshi.core.tools

/**
 * Das geparste Roh-Resultat eines gemma-Tool-Calls: der Tool-[name] plus die
 * [args] **als reine Strings** (Werte-Typisierung passiert erst in der
 * [AgenticToolRegistry], die name+args gegen die echten Kernel-Permits auflöst).
 *
 * Bewusst minimal & string-typisiert: das Wire-Format des Brain liefert String-
 * Werte im Spezial-Token `<|"|>` gewrappt und Zahlen „bare" — beides landet hier
 * als String (das `<|"|>`-Quote wird entfernt, die Zahl bleibt ihr String-Literal).
 */
data class ParsedToolCall(
    val name: String,
    val args: Map<String, String>,
)

/**
 * **ToolCallParser** — die PURE, framework-freie Extraktion des ERSTEN
 * gemma-Tool-Calls aus einem (evtl. größeren) Brain-Text.
 *
 * Das live gemessene Wire-Format des e4b-Brain (`server_e4b.py`, :8041):
 * ```
 * <|tool_call>call:NAME{key:<|"|>stringwert<|"|>,key2:123,...}<tool_call|>
 * ```
 * - Öffner `<|tool_call>`, Schließer `<tool_call|>` (asymmetrische Pipe-Position!).
 * - String-Werte sind in das Spezial-Token `<|"|>` gewrappt (NICHT normale `"`).
 * - Zahlen stehen „bare" (ohne Quote).
 * - Bei Nicht-Tool-Antworten emittiert der Brain normalen Text (kein `<|tool_call>`).
 *
 * Vertrag (BFCL „1 Tool pro Turn"): es wird der ERSTE vollständige Block
 * extrahiert. Kein/halber/defekter Block ⇒ `null` (nie eine Exception).
 */
object ToolCallParser {

    private const val OPEN = "<|tool_call>"
    private const val CLOSE = "<tool_call|>"

    /** Das Spezial-Quote-Token, in das der Brain String-Werte wrappt. */
    private const val QUOTE = "<|\"|>"

    /**
     * Extrahiert den ERSTEN `<|tool_call>call:NAME{...}<tool_call|>`-Block aus
     * [raw]. Liefert `null`, wenn kein vollständiger Block vorhanden ist oder die
     * Args defekt sind (fail-soft, wirft NIE).
     */
    /**
     * Sicherheits-Wächter (Invariante: rohe Tool-Tokens NIE an den User streamen):
     * `true`, sobald [raw] das Öffner-Token enthält — auch wenn [parse] mangels
     * vollständigem/auflösbarem Block `null` liefert (halber/abgeschnittener Block).
     * Der Orchestrator nutzt das, um einen Text mit Tool-Markern NIE roh zu streamen.
     */
    fun containsToolMarker(raw: String): Boolean = raw.contains(OPEN)

    fun parse(raw: String): ParsedToolCall? {
        val start = raw.indexOf(OPEN)
        if (start < 0) return null
        val contentStart = start + OPEN.length
        val end = raw.indexOf(CLOSE, contentStart)
        if (end < 0) return null

        // Inhalt zwischen Öffner und Schließer: "call:NAME{...}".
        val inner = raw.substring(contentStart, end)

        val braceOpen = inner.indexOf('{')
        if (braceOpen < 0) return null
        val braceClose = inner.lastIndexOf('}')
        if (braceClose < braceOpen) return null

        // Kopf "call:NAME" → NAME (das "call:"-Präfix ist optional/robust entfernt).
        val name = inner.substring(0, braceOpen).trim().removePrefix("call:").trim()
        if (name.isEmpty()) return null

        val argsBody = inner.substring(braceOpen + 1, braceClose)
        val args = parseArgs(argsBody) ?: return null
        return ParsedToolCall(name, args)
    }

    /**
     * Parst den Arg-Body `key:value,key2:value2,...` per Hand-Scan (kein naives
     * Komma-Split): String-Werte sind in [QUOTE] gewrappt, sodass Kommas INNERHALB
     * eines Strings nicht fälschlich trennen. Bare-Werte (Zahlen) laufen bis zum
     * nächsten Top-Level-Komma. Leerer Body ⇒ leere Map. Defekt (kein `:`,
     * unterminierter String) ⇒ `null`.
     */
    private fun parseArgs(s: String): Map<String, String>? {
        val args = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            // Trenner/Whitespace überspringen.
            while (i < n && (s[i] == ',' || s[i].isWhitespace())) i++
            if (i >= n) break

            // Schlüssel bis zum ':'.
            val colon = s.indexOf(':', i)
            if (colon < 0) return null
            val key = s.substring(i, colon).trim()
            if (key.isEmpty()) return null
            i = colon + 1
            while (i < n && s[i].isWhitespace()) i++

            val value: String
            if (s.startsWith(QUOTE, i)) {
                // String-Wert: bis zum schließenden QUOTE (Quote-Token entfernt).
                val valStart = i + QUOTE.length
                val valEnd = s.indexOf(QUOTE, valStart)
                if (valEnd < 0) return null
                value = s.substring(valStart, valEnd)
                i = valEnd + QUOTE.length
            } else {
                // Bare-Wert (Zahl/Literal): bis zum nächsten Top-Level-Komma.
                val comma = s.indexOf(',', i)
                val valEnd = if (comma < 0) n else comma
                value = s.substring(i, valEnd).trim()
                i = valEnd
            }
            args[key] = value
        }
        return args
    }
}
