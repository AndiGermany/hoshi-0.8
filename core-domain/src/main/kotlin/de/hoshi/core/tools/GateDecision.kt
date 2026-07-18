package de.hoshi.core.tools

/**
 * Das Verdict des Tat-Gates aus Sicht des Domänen-Kerns — der Spring-freie
 * Spiegel der `de.hoshi.kernel.CapabilityKernel.Decision`. Kein Boolean: das
 * `when` erzwingt den Deny-Zweig (fail-closed), und [Grant] trägt die
 * **normalisierte** data (nur geprüfte Keys), aus der der Executor den Effekt baut.
 */
sealed interface GateDecision {
    /** Erlaubt — [normalizedData] enthält NUR geprüfte, erlaubte Keys. */
    data class Grant(val normalizedData: Map<String, Any?>) : GateDecision

    /** Verweigert — [reason] (intern, fürs Log) + [phrase] (warm, für die Stimme). */
    data class Deny(val reason: String, val phrase: String) : GateDecision
}
