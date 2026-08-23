package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.TurnTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Room-clarify field in the diary contract (F1-4) — additivity of
 * [TurnTrace.pendingClarify], shape of [JsonlTurnTraceClaimGateFieldTest]:
 *  1. A clarify outcome survives serialisation.
 *  2. The field hangs ADDITIVELY at the line end, behind `claimGateFired`.
 *  3. An old line without the key stays parseable.
 */
class JsonlTurnTracePendingClarifyFieldTest {

    private val mapper = ObjectMapper()

    private fun adapter(dir: java.nio.file.Path) =
        JsonlTurnTraceAdapter(dir, Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC))

    private fun sampleTrace() = TurnTrace(
        ts = Instant.parse("2026-08-14T12:00:00Z"),
        category = "SMART_HOME",
        provider = "LOCAL",
        persona = "STANDARD",
        language = "DE",
        ttftMs = 120,
        totalMs = 300,
        speak = true,
        source = "voice",
    )

    @Test
    fun `Clarify-Outcome ueberlebt die Serialisierung`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace().copy(pendingClarify = "resolved")))
            assertEquals("resolved", json["pendingClarify"].asText())
        }
    }

    @Test
    fun `pendingClarify haengt additiv am Zeilenende - hinter claimGateFired`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val keys = mapper.readTree(a.serialize(sampleTrace())).fieldNames().asSequence().toList()
            // Relative position, not `last`: additive fields keep growing onto the end
            // (toolCallRan followed on 14.08.) — only the ORDER of the old keys is the
            // contract (LL-2026-08-11-additive-line-end), same fix as in
            // JsonlTurnTraceClaimGateFieldTest.
            assertEquals(keys.indexOf("claimGateFired") + 2, keys.indexOf("pendingClarify"))
            assertTrue(
                keys.indexOf("claimGateFired") < keys.indexOf("pendingClarify"),
                "davor liegt unveraendert claimGateFired (Vollzugs-Riegel)",
            )
        }
    }

    @Test
    fun `Alt-Zeile ohne pendingClarify-Key bleibt parsebar`() {
        val old = """{"ts":"2026-08-12T12:00:00Z","chatId":"","category":"SMART_HOME",""" +
            """"provider":"LOCAL","persona":"STANDARD","language":"DE","ttftMs":120,""" +
            """"totalMs":300,"deltaChars":2,"audioChunks":0,"speak":true,"deflected":false,""" +
            """"error":null,"groundingUsed":false,"source":"voice","segmentReset":false,""" +
            """"resetReason":"none","segmentLenTurns":0,"targetAreaId":null,"claimGateFired":false}"""
        val json = mapper.readTree(old)
        assertEquals("SMART_HOME", json["category"].asText())
        assertFalse(json.has("pendingClarify"), "Alt-Zeile traegt den Key gar nicht — kein Clarify-Wissen")
    }
}
