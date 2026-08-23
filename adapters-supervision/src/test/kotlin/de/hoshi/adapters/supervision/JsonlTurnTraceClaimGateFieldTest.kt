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
 * **Execution-claim latch field in the diary contract** — proves the additivity of
 * [TurnTrace.claimGateFired], exactly in the shape of [JsonlTurnTraceAreaFieldTest]:
 *
 *  1. A fired latch survives serialisation.
 *  2. The default serialises explicitly as `false` (never a fabricated `true`).
 *  3. The field hangs ADDITIVELY at the line end, behind `targetAreaId`.
 *  4. An old line without the key stays parseable.
 */
class JsonlTurnTraceClaimGateFieldTest {

    private val mapper = ObjectMapper()

    private fun adapter(dir: java.nio.file.Path) =
        JsonlTurnTraceAdapter(dir, Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC))

    private fun sampleTrace() = TurnTrace(
        ts = Instant.parse("2026-08-13T12:00:00Z"),
        category = "FACT_SHORT",
        provider = "LOCAL",
        persona = "STANDARD",
        language = "DE",
        ttftMs = 120,
        totalMs = 300,
        speak = true,
        source = "voice",
    )

    @Test
    fun `gefeuerter Riegel ueberlebt die Serialisierung`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace().copy(claimGateFired = true)))
            assertTrue(json["claimGateFired"].asBoolean())
        }
    }

    @Test
    fun `default - kein Riegel-Fall - serialisiert explizit als false`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace()))
            assertTrue(json.has("claimGateFired"), "claimGateFired muss in neuen Zeilen als Key existieren")
            assertFalse(json["claimGateFired"].asBoolean(), "nie ein erfundenes true")
        }
    }

    @Test
    fun `claimGateFired haengt additiv am zeilenende - hinter targetAreaId`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val keys = mapper.readTree(a.serialize(sampleTrace())).fieldNames().asSequence().toList()
            // Relative position, not `last`: additive fields keep growing onto the end
            // (brainTimeout + pendingClarify followed on 14.08.) — only the ORDER of the
            // old keys is the contract (LL-2026-08-11-additive-line-end).
            assertEquals(keys.indexOf("targetAreaId") + 1, keys.indexOf("claimGateFired"))
            assertTrue(
                keys.indexOf("targetAreaId") < keys.indexOf("claimGateFired"),
                "davor liegt unveraendert targetAreaId (Raeume-Nutzungs-Naht)",
            )
        }
    }

    @Test
    fun `alt-zeile ohne claimGateFired-key bleibt parsebar - fehlender key ist NICHT false-key`() {
        // A line in the format BEFORE this slice (including targetAreaId).
        val old = """{"ts":"2026-08-12T12:00:00Z","chatId":"","category":"FACT_SHORT",""" +
            """"provider":"LOCAL","persona":"STANDARD","language":"DE","ttftMs":120,""" +
            """"totalMs":300,"deltaChars":9,"audioChunks":0,"speak":true,"deflected":false,""" +
            """"error":null,"groundingUsed":false,"source":"voice","segmentReset":false,""" +
            """"resetReason":"none","segmentLenTurns":0,"sttMs":null,"groundingMs":null,""" +
            """"brainTtftMs":null,"ttsFirstAudioMs":null,"admissionWaitMs":null,"answerEntropy":null,""" +
            """"escalated":false,"escalationCostCents":null,"cacheHit":false,"targetAreaId":null}"""
        val json = mapper.readTree(old)
        assertEquals("FACT_SHORT", json["category"].asText())
        assertFalse(json.has("claimGateFired"), "Alt-Zeile traegt den Key gar nicht — kein Riegel-Wissen")
    }
}
