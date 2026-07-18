package de.hoshi.core.tools

/**
 * Das Ergebnis einer ausgeführten Tat — der **Honesty-Charter** in Typ-Form:
 *
 *  - [Ok]: es ist WIRKLICH etwas passiert (echter Aktuator/Effekt).
 *  - [NoEffect]: ehrlicher 🔵-Platzhalter — Hoshi behauptet NICHT, etwas getan zu
 *    haben (z.B. solange die Haus-Anbindung noch nicht scharf ist).
 *  - [Failed]: die Tat schlug fehl — warm gesagt, nie kalt oder still.
 *
 * Jeder Zweig trägt eine fertige, sprechbare [phrase] (never-silent).
 */
sealed interface ToolResult {
    /** Echte Wirkung — etwas ist wirklich passiert. */
    data class Ok(val phrase: String) : ToolResult

    /** Ehrlicher Platzhalter — NICHTS getan, und genau das wird gesagt (kein Fake). */
    data class NoEffect(val phrase: String) : ToolResult

    /** Tat fehlgeschlagen — warm statt kalt. */
    data class Failed(val phrase: String) : ToolResult
}
