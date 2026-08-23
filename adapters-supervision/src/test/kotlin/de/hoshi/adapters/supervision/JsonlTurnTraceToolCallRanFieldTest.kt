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
 * Tool-executor field in the diary contract — additivity of [TurnTrace.toolCallRan],
 * shape of [JsonlTurnTraceClaimGateFieldTest]:
 *
 *  1. A run executor survives serialisation.
 *  2. The default serialises explicitly as `false` (never a fabricated `true`).
 *  3. The field hangs ADDITIVELY at the line end, behind `pendingClarify`
 *     (LL-2026-08-11-additive-line-end).
 *  4. An old line without the key stays parseable — a missing key is NOT a false key.
 */
class JsonlTurnTraceToolCallRanFieldTest {

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
    fun `gelaufener Executor ueberlebt die Serialisierung`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace().copy(toolCallRan = true, targetAreaId = "wohnzimmer")))
            assertTrue(json["toolCallRan"].asBoolean())
        }
    }

    @Test
    fun `default - kein Executor - serialisiert explizit als false`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace()))
            assertTrue(json.has("toolCallRan"), "toolCallRan muss in neuen Zeilen als Key existieren")
            assertFalse(json["toolCallRan"].asBoolean(), "nie ein erfundenes true")
        }
    }

    @Test
    fun `toolCallRan haengt additiv am Zeilenende - hinter pendingClarify`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val keys = mapper.readTree(a.serialize(sampleTrace())).fieldNames().asSequence().toList()
            assertEquals(keys.indexOf("pendingClarify") + 1, keys.indexOf("toolCallRan"))
            assertTrue(
                keys.indexOf("claimGateFired") < keys.indexOf("toolCallRan"),
                "die Riegel-Naht liegt unveraendert davor",
            )
            assertEquals("ts", keys.first(), "die Alt-Reihenfolge beginnt unveraendert mit ts")
        }
    }

    @Test
    fun `Alt-Zeile ohne toolCallRan-Key bleibt parsebar - fehlender Key ist NICHT false-Key`() {
        // A line in the format BEFORE this slice (including pendingClarify).
        val old = """{"ts":"2026-08-13T12:00:00Z","chatId":"","category":"SMART_HOME",""" +
            """"provider":"LOCAL","persona":"STANDARD","language":"DE","ttftMs":120,""" +
            """"totalMs":300,"deltaChars":2,"audioChunks":0,"speak":true,"deflected":false,""" +
            """"error":null,"groundingUsed":false,"source":"voice","segmentReset":false,""" +
            """"resetReason":"none","segmentLenTurns":0,"targetAreaId":null,""" +
            """"claimGateFired":false,"brainTimeout":false,"pendingClarify":null}"""
        val json = mapper.readTree(old)
        assertEquals("SMART_HOME", json["category"].asText())
        assertFalse(json.has("toolCallRan"), "Alt-Zeile traegt den Key gar nicht — kein Executor-Wissen")
    }
}
