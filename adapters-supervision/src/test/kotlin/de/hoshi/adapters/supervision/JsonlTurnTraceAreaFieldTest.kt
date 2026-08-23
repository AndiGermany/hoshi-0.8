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
 * **Räume-Nutzungs-Feld im Diary-Vertrag** — beweist die Additivität von
 * [TurnTrace.targetAreaId] (kartiert in Commit f049965), exakt im Muster von
 * [JsonlTurnTraceEscalationFieldsTest]:
 *
 *  1. Ein gemessener Wert überlebt die Serialisierung.
 *  2. Der Default (kein Tool-Turn / keine aufgelöste Area) serialisiert
 *     explizit als `null` (nie eine erfundene Area).
 *  3. Das Feld hängt ADDITIV am Zeilenende, hinter `cacheHit` (S4).
 *  4. Eine Alt-Zeile (vor dieser Scheibe) OHNE den Key bleibt parsebar.
 */
class JsonlTurnTraceAreaFieldTest {

    private val mapper = ObjectMapper()

    private fun adapter(dir: java.nio.file.Path) =
        JsonlTurnTraceAdapter(dir, Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC))

    private fun sampleTrace() = TurnTrace(
        ts = Instant.parse("2026-08-11T12:00:00Z"),
        category = "SMART_HOME",
        provider = "LOCAL",
        persona = "STANDARD",
        language = "DE",
        ttftMs = 120,
        totalMs = 300,
        speak = false,
        source = "chat",
    )

    @Test
    fun `aufgeloeste Area ueberlebt die Serialisierung`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace().copy(targetAreaId = "kueche")))
            assertEquals("kueche", json["targetAreaId"].asText())
        }
    }

    @Test
    fun `default - kein Tool-Turn - targetAreaId serialisiert explizit als null`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace()))
            assertTrue(json.has("targetAreaId"), "targetAreaId muss in neuen Zeilen als Key existieren")
            assertTrue(json["targetAreaId"].isNull, "kein Tool-Turn ⇒ nie eine erfundene Area")
        }
    }

    @Test
    fun `targetAreaId haengt additiv am zeilenende - hinter cacheHit`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val line = a.serialize(sampleTrace())
            val keys = mapper.readTree(line).fieldNames().asSequence().toList()
            assertTrue(keys.indexOf("cacheHit") < keys.indexOf("targetAreaId"), "davor liegt unveraendert cacheHit (S4)")
            // Additive line-end rule: every later slice appends BEHIND targetAreaId,
            // it never moves. claimGateFired (2026-08-13) is the current tail.
            assertTrue(
                keys.indexOf("targetAreaId") < keys.indexOf("claimGateFired"),
                "targetAreaId bleibt VOR jedem spaeter angehaengten Feld",
            )
        }
    }

    @Test
    fun `alt-zeile ohne targetAreaId-key bleibt parsebar - fehlender key ist NICHT null-key`() {
        // Eine Zeile im Format VOR dieser Scheibe (inkl. S4-Feldern, aber noch
        // OHNE die Raeume-Nutzungs-Naht).
        val old = """{"ts":"2026-08-10T12:00:00Z","chatId":"","category":"SMART_HOME",""" +
            """"provider":"LOCAL","persona":"STANDARD","language":"DE","ttftMs":120,""" +
            """"totalMs":300,"deltaChars":9,"audioChunks":0,"speak":false,"deflected":false,""" +
            """"error":null,"groundingUsed":false,"source":"chat","segmentReset":false,""" +
            """"resetReason":"none","segmentLenTurns":0,"sttMs":null,"groundingMs":null,""" +
            """"brainTtftMs":null,"ttsFirstAudioMs":null,"admissionWaitMs":null,"answerEntropy":null,""" +
            """"escalated":false,"escalationCostCents":null,"cacheHit":false}"""
        val json = mapper.readTree(old)
        assertEquals("SMART_HOME", json["category"].asText())
        assertFalse(json.has("targetAreaId"), "Alt-Zeile traegt den Key gar nicht — kein Area-Wissen")
    }
}
