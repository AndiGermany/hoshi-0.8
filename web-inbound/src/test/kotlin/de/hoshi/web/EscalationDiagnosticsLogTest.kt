package de.hoshi.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.hoshi.core.port.EscalationUnavailableEvent
import de.hoshi.core.port.EscalationUnavailableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class EscalationDiagnosticsLogTest {

    @Test
    fun `ein finaler Unavailable-Ausgang schreibt genau eine klartextfreie Ursachen-Zeile`() {
        val logger = LoggerFactory.getLogger(EscalationDiagnosticsLog::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            EscalationDiagnosticsLog().unavailable(
                EscalationUnavailableEvent(
                    provider = "openai-sol",
                    reason = EscalationUnavailableReason.HTTP_STATUS,
                    elapsedMs = 8123,
                    timeoutMs = 8000,
                    httpStatus = 503,
                ),
            )

            assertEquals(1, appender.list.size)
            val event = appender.list.single()
            assertEquals(Level.WARN, event.level)
            assertEquals(
                "[escalation] unavailable cause=http_status provider=openai-sol " +
                    "elapsed_ms=8123 timeout_ms=8000 http_status=503",
                event.formattedMessage,
            )
            assertFalse(event.formattedMessage.contains("query", ignoreCase = true))
            assertFalse(event.formattedMessage.contains("key", ignoreCase = true))
            assertEquals(null, event.throwableProxy, "Normalbetrieb loggt keinen Stacktrace")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
