package de.hoshi.core.port

import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ToolCall

/**
 * Hexagonaler Port zum Tat-Gate: gibt der Domäne eine Test-Naht, ohne sie an den
 * konkreten `de.hoshi.kernel.CapabilityKernel` zu koppeln (Abhängigkeit zeigt nur
 * nach innen). Die echte Impl ([de.hoshi.kernel.KernelCapabilityAdapter]) lebt im
 * `:capability-kernel`, die Verdrahtung im `:web-inbound`.
 *
 * [DENY_ALL] ist der verhaltens-neutrale Default (Tools aus ⇒ alles verweigert,
 * leise — die Phrase ist leer, der Aufrufer fällt auf seinen warmen Fallback).
 */
fun interface CapabilityPort {
    fun check(call: ToolCall): GateDecision

    companion object {
        /** Default: DENY-ALL (Tools deaktiviert) — fail-closed, ohne eigene Phrase. */
        val DENY_ALL = CapabilityPort { GateDecision.Deny("tools-disabled", "") }
    }
}
