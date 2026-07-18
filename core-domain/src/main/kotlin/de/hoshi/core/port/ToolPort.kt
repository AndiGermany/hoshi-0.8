package de.hoshi.core.port

import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult

/**
 * Hexagonaler Port zur Tat-Ausführung. Wird NUR mit einem bereits gegateten
 * (Grant-normalisierten) [ToolCall] aufgerufen — der Port führt aus, er prüft
 * nicht (das Gate ist der [CapabilityPort]).
 *
 * [HONEST_PLACEHOLDER] ist der Default-Executor, solange kein echter
 * Home-Assistant-Adapter verdrahtet ist: er fakt NICHTS, sondern sagt ehrlich,
 * dass die Haus-Anbindung noch nicht scharf ist ([ToolResult.NoEffect], 🔵).
 */
fun interface ToolPort {
    fun execute(call: ToolCall): ToolResult

    companion object {
        /** Ehrlicher Platzhalter — tut NICHTS und behauptet auch nichts (kein Fake). */
        val HONEST_PLACEHOLDER = ToolPort { _ ->
            ToolResult.NoEffect(
                "Verstanden — das würde ich gleich erledigen, sobald meine Haus-Anbindung scharf ist.",
            )
        }
    }
}
