package de.hoshi.kernel

import de.hoshi.core.port.CapabilityPort
import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ToolCall

/**
 * **KernelCapabilityAdapter** — die hexagonale Naht, die den (bisher verwaisten)
 * [CapabilityKernel] an den Domänen-[CapabilityPort] anschließt. Übersetzt einen
 * [ToolCall] in den passenden `kernel.permit(...)`-Aufruf und mappt die
 * Kernel-`Decision` zurück auf die Spring-freie [GateDecision] des Kerns.
 *
 * Bewusst hier (`:capability-kernel`), nicht im Kern: der Kern darf NICHT auf
 * `de.hoshi.kernel..` zeigen (Abhängigkeit nur nach innen, ArchUnit-bewacht).
 *
 * **Sprach-Naht (2026-07-25):** [CapabilityPort.check] ist ein Ein-Argument-Port —
 * die Turn-Sprache reist deshalb auf dem [ToolCall.language] mit und wird hier an
 * den Kernel durchgereicht, damit die gesprochene Absage in der gewählten Sprache
 * kommt. Ohne gesetzte Sprache gilt [de.hoshi.core.dto.Language.DEFAULT] (DE).
 */
class KernelCapabilityAdapter(
    private val kernel: CapabilityKernel = CapabilityKernel(),
) : CapabilityPort {

    override fun check(call: ToolCall): GateDecision =
        when (val decision = kernel.permit(call.domain, call.service, call.entityId, call.data, call.language)) {
            is CapabilityKernel.Decision.Grant -> GateDecision.Grant(decision.normalizedData)
            is CapabilityKernel.Decision.Deny -> GateDecision.Deny(decision.reason, decision.phrase)
        }
}
