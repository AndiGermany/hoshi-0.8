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
 * **Extended-Think-S4-Felder im Diary-Vertrag** — beweist die Additivität der drei
 * neuen [TurnTrace]-Felder (escalated/escalationCostCents/cacheHit), exakt im
 * Muster von [JsonlTurnTraceStageFieldsTest]:
 *
 *  1. Gemessene Werte überleben die Serialisierung.
 *  2. Defaults (kein Eskalations-/Cache-Wissen) serialisieren explizit
 *     (`escalated`/`cacheHit` als `false`, `escalationCostCents` als `null` —
 *     nie eine erfundene 0.0).
 *  3. Die Felder hängen ADDITIV am Zeilenende, hinter `answerEntropy` (S1).
 *  4. Alt-Zeilen (vor diesem PREP) OHNE die Keys bleiben parsebar.
 */
class JsonlTurnTraceEscalationFieldsTest {

    private val mapper = ObjectMapper()

    private fun adapter(dir: java.nio.file.Path) =
        JsonlTurnTraceAdapter(dir, Clock.fixed(Instant.parse("2026-07-07T12:00:00Z"), ZoneOffset.UTC))

    private fun sampleTrace() = TurnTrace(
        ts = Instant.parse("2026-07-07T12:00:00Z"),
        category = "FACT_SHORT",
        provider = "LOCAL",
        persona = "STANDARD",
        language = "DE",
        ttftMs = 350,
        totalMs = 2100,
        speak = false,
        source = "chat",
    )

    @Test
    fun `eskalierte kosten ueberleben die serialisierung`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(
                a.serialize(sampleTrace().copy(escalated = true, escalationCostCents = 0.05, cacheHit = false)),
            )
            assertTrue(json["escalated"].asBoolean())
            assertEquals(0.05, json["escalationCostCents"].asDouble())
            assertFalse(json["cacheHit"].asBoolean())
        }
    }

    @Test
    fun `cache-hit ueberlebt die serialisierung - kosten bleiben null`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace().copy(cacheHit = true)))
            assertTrue(json["cacheHit"].asBoolean())
            assertFalse(json["escalated"].asBoolean())
            assertTrue(json["escalationCostCents"].isNull, "kein Lookup ⇒ keine Kosten (nie eine erfundene 0.0)")
        }
    }

    @Test
    fun `defaults serialisieren explizit - escalated und cacheHit false, kosten null`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace()))
            assertTrue(json.has("escalated"), "escalated muss in neuen Zeilen als Key existieren")
            assertFalse(json["escalated"].asBoolean())
            assertTrue(json.has("escalationCostCents"), "escalationCostCents muss in neuen Zeilen als Key existieren")
            assertTrue(json["escalationCostCents"].isNull)
            assertTrue(json.has("cacheHit"), "cacheHit muss in neuen Zeilen als Key existieren")
            assertFalse(json["cacheHit"].asBoolean())
        }
    }

    @Test
    fun `s4-felder haengen additiv am zeilenende - hinter answerEntropy`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val line = a.serialize(sampleTrace())
            val keys = mapper.readTree(line).fieldNames().asSequence().toList()
            assertEquals(listOf("escalated", "escalationCostCents", "cacheHit"), keys.takeLast(3))
            assertTrue(keys.indexOf("answerEntropy") < keys.indexOf("escalated"), "davor liegt unverändert answerEntropy (S1)")
        }
    }

    @Test
    fun `alt-zeile ohne s4-keys bleibt parsebar - fehlender key ist NICHT null-key`() {
        // Eine Zeile im Format VOR diesem PREP (06.07., inkl. Stage-Metriken + Entropie,
        // aber noch OHNE die Extended-Think-S4-Felder).
        val old = """{"ts":"2026-07-06T12:00:00Z","chatId":"","category":"FACT_SHORT",""" +
            """"provider":"LOCAL","persona":"STANDARD","language":"DE","ttftMs":350,""" +
            """"totalMs":2100,"deltaChars":87,"audioChunks":0,"speak":false,"deflected":false,""" +
            """"error":null,"groundingUsed":false,"source":"chat","segmentReset":false,""" +
            """"resetReason":"none","segmentLenTurns":0,"sttMs":null,"groundingMs":42,""" +
            """"brainTtftMs":900,"ttsFirstAudioMs":null,"admissionWaitMs":null,"answerEntropy":null}"""
        val json = mapper.readTree(old)
        assertEquals("FACT_SHORT", json["category"].asText())
        assertFalse(json.has("escalated"), "Alt-Zeile trägt den Key gar nicht — kein Eskalations-Wissen")
        assertFalse(json.has("cacheHit"))
        assertFalse(json.has("escalationCostCents"))
    }
}
