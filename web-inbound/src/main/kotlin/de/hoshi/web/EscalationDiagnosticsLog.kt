package de.hoshi.web

import de.hoshi.core.port.EscalationDiagnosticsPort
import de.hoshi.core.port.EscalationUnavailableEvent
import org.slf4j.LoggerFactory

/**
 * SLF4J-Rand fuer finale Online-Fehlschlaege. Der Text ist absichtlich fest und
 * klartextfrei, damit `journalctl` jeden warm beantworteten Unavailable-Turn mit
 * genau einer maschinenlesbaren Ursache zeigt, ohne Query, Key oder Antwort.
 */
class EscalationDiagnosticsLog : EscalationDiagnosticsPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun unavailable(event: EscalationUnavailableEvent) {
        log.warn(
            "[escalation] unavailable cause={} provider={} elapsed_ms={} timeout_ms={} http_status={}",
            event.reason.logValue,
            event.provider,
            event.elapsedMs,
            event.timeoutMs,
            event.httpStatus?.toString() ?: "-",
        )
    }
}
