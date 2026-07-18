package de.hoshi.core.tools

/**
 * **CalcIntent** — die deterministische, LLM-freie Erkennung einer eindeutigen
 * Rechen-Aufforderung (DE **und** EN) in einen [ToolCall] (`domain == "calc"`).
 * Spiegelbild zum [TimerIntent]: rein, uhrfrei, framework- und LLM-frei.
 *
 * KONSERVATIV (false-positive-avers): erkennt NUR, wenn der Rest-Text — nach dem
 * Abziehen eines optionalen Frage-/Aufforderungs-Präfixes („was ist", „rechne",
 * „what is"…) — ein **reiner arithmetischer Ausdruck** ist. „Rein" heißt: jedes
 * Token ist eine Zahl, ein Operator (Wort ODER Symbol), eine Klammer oder `sqrt`
 * — bleibt EIN Fremdwort übrig, ist es kein Rechen-Intent ⇒ `null` (der Text
 * fällt in den normalen Turn). So wird „was ist dein Name" NIE als Rechnung
 * missdeutet, „rechne mit mir nicht" greift die Negations-Sperre.
 *
 * Zusätzlich muss der Ausdruck mindestens einen **binären** Operator oder `sqrt`
 * enthalten (eine bloße Zahl wie „2024" ist keine Rechnung) UND vom [Calculator]
 * strukturell auswertbar sein (Value oder DivByZero) — sonst `null`.
 *
 * Der erzeugte [ToolCall] trägt die SYMBOLISCHE Form (`data["expr"]`, vom
 * [Calculator] auswertbar) und den gesprochenen Roh-Ausdruck (`data["echo"]`, für
 * die warme Quittung). Die eigentliche Auswertung passiert brain-frei im
 * [de.hoshi.core.pipeline.CalcFastpath].
 */
object CalcIntent {

    /** ToolCall-Domain dieses Fast-Paths (vom Orchestrator gegen den Calc-Zweig geprüft). */
    const val DOMAIN = "calc"
    const val EVAL = "eval"

    /**
     * Klassifiziert den (Original-)Text in einen Calc-[ToolCall] oder `null`.
     * Sprache wird NICHT gebraucht (Trigger sind bilingual) — die Quittungs-Sprache
     * reicht der Orchestrator separat in den [de.hoshi.core.pipeline.CalcFastpath].
     *
     * [allowEmbedded] (flag-gated, default `false` ⇒ byte-neutral): bei `true` fängt
     * ein zweiter, KONSERVATIVER Pfad ([embeddedCall]) auch eine satz-EINGEBETTETE
     * einfache Rechnung („Erklär mir, wie viel 17 mal 23 ergibt") — sonst fiele sie
     * (kein reiner Ausdruck) ins Brain und ein 4B garbelt die Zahl. Default `false`
     * reproduziert exakt das bisherige Verhalten (nur der reine-Ausdruck-Pfad).
     */
    fun classify(text: String, allowEmbedded: Boolean = false): ToolCall? {
        if (text.isBlank()) return null
        pureCall(text)?.let { return it }
        return if (allowEmbedded) embeddedCall(text) else null
    }

    /**
     * Der bisherige, byte-stabile Pfad: erkennt NUR, wenn der Rest-Text (nach
     * Präfix-/Trailer-Abzug) ein REINER arithmetischer Ausdruck ist. Unverändert
     * gegenüber dem alten `classify` — nur herausgezogen, damit der Embedded-Pfad
     * als Fallback dahinter andocken kann.
     */
    private fun pureCall(text: String): ToolCall? {
        val lower = text.lowercase().replace(Regex("[’'`´ʼ]"), "")

        // (0) Negation ⇒ KEIN Rechen-Befehl (token-genau, wie [TimerIntent]).
        val words = lower.split(Regex("[^a-zäöüß0-9]+")).filter { it.isNotBlank() }
        if (words.any { it in NEGATION || it.startsWith("kein") }) return null

        // (1) Optionales Frage-/Aufforderungs-Präfix + Trailer abziehen ⇒ Kern-Phrase.
        var core = LEAD_IN_RX.replaceFirst(lower, "")
        core = TRAILER_RX.replace(core, "")
        core = core.replace(Regex("\\s+"), " ").trim()
        if (core.isBlank()) return null

        // (2) In die symbolische Form bringen (Wort-Operatoren/Zahlwörter → Symbole).
        val symbolic = toSymbolic(core)
        if (symbolic.isBlank()) return null

        // (3) Muss eine echte Rechnung sein: mind. ein binärer Operator / sqrt
        //     (eine bloße Zahl ist KEINE Rechnung).
        if (!hasOperatorOrFunction(symbolic)) return null

        // (4) Strukturell auswertbar (Value oder DivByZero) ⇒ Calc; sonst (Error:
        //     Fremdwort/kaputt) ⇒ null (kein reiner Ausdruck ⇒ normaler Turn).
        return when (Calculator.evaluate(symbolic)) {
            is Calculator.Result.Value, Calculator.Result.DivByZero ->
                ToolCall(
                    domain = DOMAIN,
                    service = EVAL,
                    entityId = null,
                    data = mapOf("expr" to symbolic, "echo" to core),
                )
            Calculator.Result.Error -> null
        }
    }

    /**
     * **Embedded-Pfad (flag-gated, KONSERVATIV).** Fängt eine satz-EINGEBETTETE
     * einfache Rechnung („Erklär mir, wie viel 17 mal 23 ergibt", „17 mal 23, was
     * ergibt das?") — genau ZWEI Zahlen mit EINEM binären Operator. So landet die
     * Arithmetik im deterministischen [de.hoshi.core.pipeline.CalcFastpath] statt im
     * Brain (ein 4B garbelt „391" zu „dreihundertneunundhochzehn").
     *
     * Drei harte Wächter gegen False-Positives auf normale Sätze:
     *  1. **Cue-Pflicht:** ohne eine klare Rechen-Frage ([EMBEDDED_CUE_RX]: „wie viel",
     *     „rechne", „was ist"…) wird NICHTS gefangen.
     *  2. **Genau ein** sauberer 2-Zahlen-Ausdruck — 0, ≥2 oder zu komplex ⇒ `null`.
     *  3. **Rand-Sauberkeit:** links/rechts darf KEIN weiterer Operator anschließen
     *     (schützt vor abgeschnittenem Größeren wie Prozent „/100*" oder Ketten
     *     „2+3*4"). Im Zweifel NICHT fangen.
     *
     * Kein `echo` ⇒ der Fastpath spricht die neutrale „Das macht X."/„That's X."-
     * Quittung (sprach-korrekt, ohne dass dieser sprachfreie Klassifizierer einen
     * Echo-Text in der falschen Sprache baut).
     */
    private fun embeddedCall(text: String): ToolCall? {
        val lower = text.lowercase().replace(Regex("[’'`´ʼ]"), "")
        // (0) Negation ⇒ kein Rechen-Befehl (token-genau, wie [pureCall]).
        val words = lower.split(Regex("[^a-zäöüß0-9]+")).filter { it.isNotBlank() }
        if (words.any { it in NEGATION || it.startsWith("kein") }) return null
        // (1) Ohne klare Rechen-Frage NICHT fangen (false-positive-avers).
        if (!EMBEDDED_CUE_RX.containsMatchIn(lower)) return null
        // (2) Symbolische Form über den GANZEN Satz (Fremdwörter bleiben stehen).
        val symbolic = toSymbolic(lower)
        // (3) Genau EINE saubere 2-Zahlen-Rechnung extrahieren.
        val matches = EMBEDDED_EXPR_RX.findAll(symbolic).toList()
        if (matches.size != 1) return null
        val m = matches.first()
        // (3b) Rand-Sauberkeit: links/rechts (über Spaces hinweg) KEIN weiterer Operator.
        if (operatorTouches(symbolic, m.range.first - 1, step = -1) ||
            operatorTouches(symbolic, m.range.last + 1, step = +1)
        ) {
            return null
        }
        val expr = m.value.trim()
        // (4) Strukturell auswertbar ⇒ Calc (ohne echo ⇒ neutrale Quittung).
        return when (Calculator.evaluate(expr)) {
            is Calculator.Result.Value, Calculator.Result.DivByZero ->
                ToolCall(domain = DOMAIN, service = EVAL, entityId = null, data = mapOf("expr" to expr))
            Calculator.Result.Error -> null
        }
    }

    /** Ob bei [from] (über Spaces hinweg, Richtung [step]) ein binärer Operator steht. */
    private fun operatorTouches(s: String, from: Int, step: Int): Boolean {
        var i = from
        while (i in s.indices && s[i] == ' ') i += step
        return i in s.indices && s[i] in "+-*/%^"
    }

    // ── Symbolische Normalisierung (rein) ────────────────────────────────────

    /**
     * Übersetzt die Kern-Phrase in die symbolische Form, die der [Calculator]
     * versteht: Unicode-Operatorzeichen vereinheitlichen, deutsches Dezimalkomma →
     * Punkt, Wort-Operatoren (mehrwortig ZUERST) → Symbole, Zahlwörter → Ziffern.
     * Unbekannte Wörter bleiben STEHEN — so scheitert der [Calculator] an ihnen
     * (gewollt: nur reine Ausdrücke gelten als Rechnung).
     */
    private fun toSymbolic(core: String): String {
        var s = core
        // Unicode-Operatorzeichen → ASCII.
        s = s.replace('×', '*').replace('·', '*').replace('∙', '*')
            .replace('÷', '/').replace('−', '-')
        // Deutsches Dezimalkomma zwischen Ziffern → Punkt (3,5 → 3.5).
        s = Regex("(?<=\\d),(?=\\d)").replace(s, ".")
        // Mehrwortige Operator-Phrasen zuerst (sonst frisst ein Einzelwort den Teil).
        for ((phrase, sym) in MULTI_WORD_OPS) {
            s = Regex("\\b" + Regex.escape(phrase) + "\\b").replace(s, sym)
        }
        // Einzelwort-Operatoren.
        for ((word, sym) in WORD_OPS) {
            s = Regex("\\b" + Regex.escape(word) + "\\b").replace(s, sym)
        }
        // Zahlwörter (längste zuerst) → Ziffern.
        for ((word, digit) in NUMBER_WORDS) {
            s = Regex("\\b" + Regex.escape(word) + "\\b").replace(s, digit)
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Mindestens ein **binärer** Operator (`* / % ^`, oder `+`/`-` mit einer Zahl/
     * Klammer davor) oder eine `sqrt`-Funktion — sonst ist es keine Rechnung,
     * sondern eine bloße Zahl (z.B. „2024"). Ein führendes unäres `-`/`+` zählt NICHT.
     */
    private fun hasOperatorOrFunction(symbolic: String): Boolean =
        symbolic.contains("sqrt") ||
            Regex("[*/%^]").containsMatchIn(symbolic) ||
            Regex("[0-9)]\\s*[+\\-]").containsMatchIn(symbolic)

    // ── Wortlisten (DE + EN), konservativ ────────────────────────────────────

    private val NEGATION = setOf("nicht", "nie", "niemals", "not", "no", "dont", "never")

    /**
     * Cue-Pflicht des [embeddedCall]: nur wenn der Satz wirklich nach einer Rechnung
     * fragt, fangen wir eine eingebettete Arithmetik (DE+EN). Ohne Cue ⇒ kein Fang
     * (so wird „ich war fünf mal in Berlin" nie als Rechnung missdeutet).
     */
    private val EMBEDDED_CUE_RX = Regex(
        "\\b(?:wie\\s*viel|rechne|berechne|errechne|ausgerechnet|" +
            "was\\s+ist|was\\s+sind|was\\s+ergibt|was\\s+macht|was\\s+gibt|" +
            "how\\s+much|what\\s+is|whats|calculate|compute)\\b",
    )

    /**
     * Genau ZWEI Zahlen mit EINEM binären Operator — die einzige eingebettete Form,
     * die [embeddedCall] fängt. Lookbehind/-ahead `(?<![\d.]) … (?![\d.])` sichern,
     * dass keine Zahl mittendrin abgeschnitten wird; die Rand-Operator-Prüfung in
     * [embeddedCall] schließt größere/komplexere Ausdrücke aus.
     */
    private val EMBEDDED_EXPR_RX =
        Regex("(?<![\\d.])\\d+(?:\\.\\d+)?\\s*[-+*/%^]\\s*\\d+(?:\\.\\d+)?(?![\\d.])")

    /**
     * Frage-/Aufforderungs-Präfixe (DE+EN), am Satzanfang abgezogen. NICHT nötig für
     * die Erkennung (ein freistehendes „7 hoch 2" greift auch ohne Präfix), aber sie
     * isolieren den reinen Ausdruck, damit die „nur-Ausdruck"-Prüfung sauber greift.
     */
    private val LEAD_INS = listOf(
        "wie viel ist", "wieviel ist", "wie viel sind", "wieviel sind",
        "wie viel macht", "wieviel macht", "wie viel ergibt", "wieviel ergibt",
        "was ist", "was sind", "was macht", "was ergibt", "was gibt",
        "rechne mir", "rechne", "berechne mir", "berechne", "errechne",
        "how much is", "how much are", "what is", "whats", "what are",
        "calculate", "compute",
    )

    /** Trailer am Satzende abziehen (Frage-/Gleichheits-Marker). */
    private val TRAILER_RX = Regex("\\s*(?:=|gleich|equals|\\?|\\.|!)+\\s*$")

    private val LEAD_IN_RX =
        Regex("^(?:" + LEAD_INS.joinToString("|") { Regex.escape(it) } + ")\\b\\s*")

    /** Mehrwortige Operator-Phrasen (mit Leerzeichen-Polster für saubere Token). */
    private val MULTI_WORD_OPS: List<Pair<String, String>> = listOf(
        "geteilt durch" to " / ", "dividiert durch" to " / ", "divided by" to " / ",
        "multipliziert mit" to " * ", "multiplied by" to " * ",
        "to the power of" to " ^ ", "power of" to " ^ ",
        // „X prozent von Y" = X/100*Y (Prozent-VON, nicht modulo).
        "prozent von" to " /100* ", "percent of" to " /100* ",
        "quadratwurzel aus" to " sqrt ", "quadratwurzel von" to " sqrt ",
        "wurzel aus" to " sqrt ", "wurzel von" to " sqrt ",
        "square root of" to " sqrt ", "square root" to " sqrt ",
    )

    /** Einzelwort-Operatoren (DE+EN). `%`/`modulo` = Restwert; `prozent von` ist oben. */
    private val WORD_OPS: List<Pair<String, String>> = listOf(
        "plus" to " + ",
        "minus" to " - ",
        "mal" to " * ", "times" to " * ",
        "durch" to " / ",
        "hoch" to " ^ ",
        "modulo" to " % ", "mod" to " % ",
    )

    /**
     * Zahlwort → Ziffer (DE+EN, längste zuerst, damit „dreizehn" nicht von „drei"
     * zerteilt wird). Bewusst klein gehalten (0..20, Zehner, hundert) — die meisten
     * Rechnungen kommen als Ziffern; Zahlwörter sind optionaler Komfort.
     */
    private val NUMBER_WORDS: List<Pair<String, String>> = listOf(
        // DE Zehner / hundert zuerst (vor 1..9).
        "hundert" to "100",
        "neunzehn" to "19", "achtzehn" to "18", "siebzehn" to "17", "sechzehn" to "16",
        "fünfzehn" to "15", "fuenfzehn" to "15", "vierzehn" to "14", "dreizehn" to "13",
        "zwölf" to "12", "zwoelf" to "12", "elf" to "11", "zehn" to "10",
        "zwanzig" to "20", "dreißig" to "30", "dreissig" to "30", "vierzig" to "40",
        "fünfzig" to "50", "fuenfzig" to "50", "sechzig" to "60", "siebzig" to "70",
        "achtzig" to "80", "neunzig" to "90",
        "null" to "0", "eins" to "1", "eine" to "1", "ein" to "1",
        "zwei" to "2", "drei" to "3", "vier" to "4", "fünf" to "5", "fuenf" to "5",
        "sechs" to "6", "sieben" to "7", "acht" to "8", "neun" to "9",
        // EN.
        "hundred" to "100",
        "nineteen" to "19", "eighteen" to "18", "seventeen" to "17", "sixteen" to "16",
        "fifteen" to "15", "fourteen" to "14", "thirteen" to "13", "twelve" to "12",
        "eleven" to "11", "ten" to "10",
        "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
        "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90",
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )
}
