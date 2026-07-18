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
 * **Stage-Metriken im Diary-Vertrag (Perf-Diary)** — beweist die Additivität
 * der fünf neuen TurnTrace-Felder (sttMs/groundingMs/brainTtftMs/
 * ttsFirstAudioMs/admissionWaitMs), exakt im Muster der `source`-/Segment-
 * Felder-Tests:
 *
 *  1. Gemessene Werte überleben die Serialisierung (die Aktivitäts-View liest
 *     genau diese JSONL-Keys).
 *  2. Defaults (nichts gemessen) serialisieren als EXPLIZITES null — nie ein
 *     erfundenes 0; Alt-Aufrufer (TurnTrace ohne die Felder) kompilieren und
 *     schreiben unverändert.
 *  3. Die Felder hängen ADDITIV am Zeilenende — die Reihenfolge der Alt-Felder
 *     bleibt byte-stabil (Alt-Zeilen-Leser/`jq`-Skripte unverändert).
 *  4. Alt-Zeilen OHNE die Keys bleiben parsebar (der Lese-Rand reicht rohe
 *     Maps durch; fehlender Key ≠ null-Key — das FE unterscheidet das ehrlich).
 */
class JsonlTurnTraceStageFieldsTest {

    private val mapper = ObjectMapper()

    private fun adapter(dir: java.nio.file.Path) =
        JsonlTurnTraceAdapter(dir, Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC))

    private fun sampleTrace() = TurnTrace(
        ts = Instant.parse("2026-07-06T12:00:00Z"),
        category = "FACT_SHORT",
        provider = "LOCAL",
        persona = "STANDARD",
        language = "DE",
        ttftMs = 350,
        totalMs = 2100,
        speak = true,
        source = "voice",
    )

    @Test
    fun `gemessene stage-werte ueberleben die serialisierung`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(
                a.serialize(
                    sampleTrace().copy(
                        sttMs = 300,
                        groundingMs = 42,
                        brainTtftMs = 900,
                        ttsFirstAudioMs = 1400,
                        admissionWaitMs = 0, // gemessenes 0 (tryAcquire sofort) ist erlaubt
                        answerEntropy = 1.25,
                    ),
                ),
            )
            assertEquals(300, json["sttMs"].asLong())
            assertEquals(42, json["groundingMs"].asLong())
            assertEquals(900, json["brainTtftMs"].asLong())
            assertEquals(1400, json["ttsFirstAudioMs"].asLong())
            assertEquals(0, json["admissionWaitMs"].asLong())
            assertEquals(1.25, json["answerEntropy"].asDouble(), "Antwort-Entropie (S1) überlebt die Serialisierung exakt")
        }
    }

    @Test
    fun `defaults serialisieren als explizites null - nie ein erfundenes 0`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val json = mapper.readTree(a.serialize(sampleTrace()))
            for (field in listOf("sttMs", "groundingMs", "brainTtftMs", "ttsFirstAudioMs", "admissionWaitMs", "answerEntropy")) {
                assertTrue(json.has(field), "$field muss in neuen Zeilen als Key existieren")
                assertTrue(json[field].isNull, "$field ohne Messung muss null sein (nie 0)")
            }
        }
    }

    @Test
    fun `stage-felder haengen additiv hinter segmentLenTurns - alt-feld-reihenfolge byte-stabil`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        adapter(dir).use { a ->
            val line = a.serialize(sampleTrace())
            val keys = mapper.readTree(line).fieldNames().asSequence().toList()
            // Die fünf Stage-Keys + answerEntropy (S1, additiv dahinter) folgen DIREKT
            // auf segmentLenTurns — alles davor ist die unveränderte Alt-Reihenfolge
            // (beginnend mit ts). Seit Extended Think S4 (escalated/escalationCostCents/
            // cacheHit, additiv NOCH weiter hinten) sind sie NICHT mehr die absolut
            // letzten Keys der Zeile — additive Felder wachsen immer weiter ans Ende,
            // darum hier die RELATIVE Position statt eines starren `takeLast`.
            val stageKeys = listOf("sttMs", "groundingMs", "brainTtftMs", "ttsFirstAudioMs", "admissionWaitMs", "answerEntropy")
            val start = keys.indexOf("segmentLenTurns") + 1
            assertEquals(stageKeys, keys.subList(start, start + stageKeys.size))
            assertEquals("ts", keys.first())
        }
    }

    @Test
    fun `alt-zeile ohne stage-keys bleibt parsebar - fehlender key ist NICHT null-key`() {
        // Eine echte Alt-Zeile (Format vor dem 06.07.) — ohne die Stage-Keys.
        val old = """{"ts":"2026-07-01T12:00:00Z","chatId":"","category":"FACT_SHORT",""" +
            """"provider":"LOCAL","persona":"STANDARD","language":"DE","ttftMs":350,""" +
            """"totalMs":2100,"deltaChars":87,"audioChunks":3,"speak":true,"deflected":false,""" +
            """"error":null,"groundingUsed":false,"source":"chat","segmentReset":false,""" +
            """"resetReason":"none","segmentLenTurns":0}"""
        val json = mapper.readTree(old)
        assertEquals("FACT_SHORT", json["category"].asText())
        assertFalse(json.has("sttMs"), "Alt-Zeile trägt den Key gar nicht — das FE zeigt „keine Stage-Daten“")
        assertFalse(json.has("ttsFirstAudioMs"))
    }
}
