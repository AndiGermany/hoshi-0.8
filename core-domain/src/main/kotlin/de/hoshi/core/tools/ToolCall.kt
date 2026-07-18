package de.hoshi.core.tools

/**
 * Ein **strukturierter Tat-Wunsch** des Turns: was der deterministische
 * Intent-Classifier aus dem Text destilliert hat, bevor irgendeine Tat passiert.
 *
 * Bewusst reine Daten (Spring-frei), im selben Format wie der
 * [de.hoshi.kernel.CapabilityKernel] prüft: `domain`/`service` als Allowlist-Key,
 * `entityId` als Effekt-Target, `data` als Roh-Payload. Erst der Gate-Pass
 * normalisiert die `data` — der Executor baut den Effekt NUR aus der
 * normalisierten Form (nie aus dem Roh-Input).
 */
data class ToolCall(
    val domain: String,
    val service: String,
    val entityId: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    /**
     * **READ-ONLY-Markierung.** `false` (Default) = eine schreibende Tat (Licht/Klima/
     * Szene) → MUSS durch das Schreib-Gate ([de.hoshi.core.port.CapabilityPort]).
     * `true` = ein reiner Lese-Wunsch (z.B. „wie warm ist es?" → HA-State lesen) →
     * KEIN Aktuator, kein State-Change, darum strukturell am Schreib-Gate VORBEI
     * (der Orchestrator routet Reads auf den brain-freien Lese-Pfad). Default-`false`
     * hält jeden bestehenden (Schreib-)Call byte-identisch.
     */
    val read: Boolean = false,
)
