package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Beweist die drei Diary-Verträge OHNE Live-Infra: (1) ein Trace wird als
 * EINE lesbare JSONL-Zeile mit allen Feldern persistiert, (2) die Tages-Datei
 * folgt der injizierten [Clock] (Rotation), (3) ein kaputter Pfad wirft NIE
 * (best-effort, der Turn bleibt heilig).
 */
class JsonlTurnTraceAdapterTest {

    private val mapper = ObjectMapper()

    /** Fixe Clock: 2026-07-01T12:00Z ⇒ Tages-Datei `turn-diary-2026-07-01.jsonl`. */
    private fun clockAt(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    private fun sampleTrace() = TurnTrace(
        ts = Instant.parse("2026-07-01T12:00:00Z"),
        chatId = "chat-42",
        category = "FACT_SHORT",
        provider = "LOCAL",
        persona = "STANDARD",
        language = "DE",
        ttftMs = 350,
        totalMs = 2100,
        deltaChars = 87,
        audioChunks = 3,
        speak = true,
        deflected = true,
        error = null,
        groundingUsed = false,
    )

    @Test
    fun `trace wird als eine JSONL-Zeile mit allen Feldern geschrieben`(@TempDir dir: Path) {
        JsonlTurnTraceAdapter(dir, clockAt("2026-07-01T12:00:00Z")).use { adapter ->
            adapter.record(sampleTrace())
        } // close() flusht den Writer-Thread deterministisch

        val file = dir.resolve("turn-diary-2026-07-01.jsonl")
        val lines = Files.readAllLines(file)
        assertEquals(1, lines.size)

        val json = mapper.readTree(lines[0])
        assertEquals("2026-07-01T12:00:00Z", json["ts"].asText())
        assertEquals("chat-42", json["chatId"].asText())
        assertEquals("FACT_SHORT", json["category"].asText())
        assertEquals("LOCAL", json["provider"].asText())
        assertEquals("STANDARD", json["persona"].asText())
        assertEquals("DE", json["language"].asText())
        assertEquals(350, json["ttftMs"].asLong())
        assertEquals(2100, json["totalMs"].asLong())
        assertEquals(87, json["deltaChars"].asInt())
        assertEquals(3, json["audioChunks"].asInt())
        assertTrue(json["speak"].asBoolean())
        assertTrue(json["deflected"].asBoolean())
        assertTrue(json["error"].isNull) // fehlerfrei ⇒ explizit null, nicht fehlend
        assertEquals(false, json["groundingUsed"].asBoolean())
        assertEquals("", json["source"].asText(), "Default-source ist leer (Alt-Aufrufer unverändert)")
    }

    /**
     * Voice-Sichtbarkeit (2026-07-05): das additive `source`-Feld überlebt die
     * Serialisierung — seit dem Voice-Diary-Fix trägt jede Zeile den Eingangs-Rand
     * (`chat`/`voice`), damit der STRICT-Entscheid Andis Hauptnutzungsweg sieht.
     */
    @Test
    fun `source=voice wird serialisiert - der Eingangs-Rand steht in der Zeile`(@TempDir dir: Path) {
        val adapter = JsonlTurnTraceAdapter(dir, clockAt("2026-07-01T12:00:00Z"))
        val json = mapper.readTree(adapter.serialize(sampleTrace().copy(source = "voice")))
        assertEquals("voice", json["source"].asText())
        adapter.close()
    }

    /**
     * Grounding-Ehrlichkeit (2026-07-02): `groundingUsed=true` überlebt die
     * Serialisierung — seit der Rand den Wert echt aus [ChatEvent.Start.grounded]
     * liest (statt hardcoded false), muss auch der Diary-Vertrag beide Werte tragen.
     */
    @Test
    fun `groundingUsed=true wird ehrlich serialisiert - kein hardcoded false im Vertrag`(@TempDir dir: Path) {
        val adapter = JsonlTurnTraceAdapter(dir, clockAt("2026-07-01T12:00:00Z"))
        val json = mapper.readTree(
            adapter.serialize(sampleTrace().copy(deflected = false, groundingUsed = true)),
        )
        assertTrue(json["groundingUsed"].asBoolean(), "ein gedeckter Turn muss groundingUsed=true tragen")
        assertEquals(false, json["deflected"].asBoolean(), "gedeckt ⇒ nicht deflektet (kohärente Zeile)")
        adapter.close()
    }

    /**
     * S2 räumliches Gedächtnis: die additiven Segment-Grenz-Felder (Muster
     * `groundingUsed`) überleben die Serialisierung — die S4-Kalibrier-Basis
     * (Reset-Rate/Grund/Segment-Länge) liest genau diese JSONL-Felder.
     */
    @Test
    fun `segment-felder werden serialisiert - defaults und echter reset`(@TempDir dir: Path) {
        val adapter = JsonlTurnTraceAdapter(dir, clockAt("2026-07-01T12:00:00Z"))
        // Default (kein Segment-Wissen): neutrale Werte, aber die Felder SIND da.
        val defaults = mapper.readTree(adapter.serialize(sampleTrace()))
        assertEquals(false, defaults["segmentReset"].asBoolean())
        assertEquals("none", defaults["resetReason"].asText())
        assertEquals(0, defaults["segmentLenTurns"].asInt())
        // Echter Reset (Zeit-Lücke) mit Segment-Länge.
        val reset = mapper.readTree(
            adapter.serialize(sampleTrace().copy(segmentReset = true, resetReason = "time-gap", segmentLenTurns = 3)),
        )
        assertTrue(reset["segmentReset"].asBoolean())
        assertEquals("time-gap", reset["resetReason"].asText())
        assertEquals(3, reset["segmentLenTurns"].asInt())
        adapter.close()
    }

    @Test
    fun `ttftMs null bleibt null in der Serialisierung`(@TempDir dir: Path) {
        val adapter = JsonlTurnTraceAdapter(dir, clockAt("2026-07-01T12:00:00Z"))
        val json = mapper.readTree(adapter.serialize(sampleTrace().copy(ttftMs = null, error = "TTS")))
        assertTrue(json["ttftMs"].isNull)
        assertEquals("TTS", json["error"].asText())
        adapter.close()
    }

    @Test
    fun `tages-datei folgt der injizierten Clock (Rotation)`(@TempDir dir: Path) {
        // Zwei Adapter mit unterschiedlichen Tagen ⇒ zwei getrennte Dateien.
        JsonlTurnTraceAdapter(dir, clockAt("2026-07-01T23:59:00Z")).use { it.record(sampleTrace()) }
        JsonlTurnTraceAdapter(dir, clockAt("2026-07-02T00:01:00Z")).use { it.record(sampleTrace()) }

        assertTrue(Files.exists(dir.resolve("turn-diary-2026-07-01.jsonl")), "Tag 1 fehlt")
        assertTrue(Files.exists(dir.resolve("turn-diary-2026-07-02.jsonl")), "Tag 2 fehlt")
        assertEquals(1, Files.readAllLines(dir.resolve("turn-diary-2026-07-01.jsonl")).size)
        assertEquals(1, Files.readAllLines(dir.resolve("turn-diary-2026-07-02.jsonl")).size)
    }

    @Test
    fun `fileForToday nutzt die Zone der Clock, nicht UTC pauschal`(@TempDir dir: Path) {
        // 23:30Z am 1.7. ist in +02:00 schon der 2.7. — die Clock entscheidet.
        val plus2 = Clock.fixed(Instant.parse("2026-07-01T23:30:00Z"), ZoneOffset.ofHours(2))
        JsonlTurnTraceAdapter(dir, plus2).use { adapter ->
            assertEquals(dir.resolve("turn-diary-2026-07-02.jsonl"), adapter.fileForToday())
        }
    }

    @Test
    fun `kaputter pfad wirft nie - record und close bleiben still`(@TempDir dir: Path) {
        // Ein FILE als "directory" ⇒ createDirectories/write scheitern garantiert.
        val notADir = dir.resolve("blocker.txt")
        Files.writeString(notADir, "ich bin keine directory")

        val adapter = JsonlTurnTraceAdapter(notADir, clockAt("2026-07-01T12:00:00Z"))
        assertDoesNotThrow {
            adapter.record(sampleTrace())
            adapter.close() // wartet den Writer ab ⇒ der Fehlerpfad ist wirklich gelaufen
        }
        assertTrue(Files.isRegularFile(notADir), "blocker darf nicht überschrieben sein")
    }

    @Test
    fun `record nach close wirft nie (RejectedExecution abgefangen)`(@TempDir dir: Path) {
        val adapter = JsonlTurnTraceAdapter(dir, clockAt("2026-07-01T12:00:00Z"))
        adapter.close()
        assertDoesNotThrow { adapter.record(sampleTrace()) }
    }

    @Test
    fun `NOOP tut nichts und wirft nichts`() {
        assertDoesNotThrow { TurnTracePort.NOOP.record(sampleTrace()) }
    }
}
