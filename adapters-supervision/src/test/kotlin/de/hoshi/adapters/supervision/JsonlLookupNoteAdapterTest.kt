package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.LookupNote
import de.hoshi.core.port.LookupNotePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Beweist die [JsonlLookupNoteAdapter]-Verträge OHNE Live-Infra: (1) eine Notiz
 * wird als EINE lesbare, normativ geordnete JSONL-Zeile persistiert, (2) die
 * Datei ist EINE durchgehende Datei (KEINE Tages-Rotation), (3) [find] liest
 * exakt zurück, (4) ein kaputter Pfad wirft NIE (best-effort).
 */
class JsonlLookupNoteAdapterTest {

    private val mapper = ObjectMapper()

    private fun sampleNote(queryNorm: String = "wie hoch ist der eiffelturm") = LookupNote(
        queryHash = "abc123",
        queryNorm = queryNorm,
        answer = "Der Eiffelturm ist 330 Meter hoch.",
        source = "Wikipedia",
        provider = "openai-nano",
        costCents = 0.1,
        ts = Instant.parse("2026-07-05T12:00:00Z"),
        ttlDays = 30,
        origin = LookupNote.ORIGIN_LIVE,
    )

    @Test
    fun `Notiz wird als eine JSONL-Zeile mit allen Feldern normativ geordnet geschrieben`(@TempDir dir: Path) {
        val path = dir.resolve("nachgeschlagen.jsonl")
        JsonlLookupNoteAdapter(path).use { it.record(sampleNote()) }

        val lines = Files.readAllLines(path)
        assertEquals(1, lines.size)
        val json = mapper.readTree(lines[0])
        assertEquals("abc123", json["queryHash"].asText())
        assertEquals("wie hoch ist der eiffelturm", json["queryNorm"].asText())
        assertEquals("Der Eiffelturm ist 330 Meter hoch.", json["answer"].asText())
        assertEquals("Wikipedia", json["source"].asText())
        assertEquals("openai-nano", json["provider"].asText())
        assertEquals(0.1, json["costCents"].asDouble())
        assertEquals("2026-07-05T12:00:00Z", json["ts"].asText())
        assertEquals(30, json["ttlDays"].asInt())
        assertEquals("live", json["origin"].asText())
        // Feld-Reihenfolge normativ (die Nachtschicht erbt das Format 1:1).
        val fieldOrder = mutableListOf<String>()
        json.fieldNames().forEachRemaining { fieldOrder += it }
        assertEquals(
            listOf("queryHash", "queryNorm", "answer", "source", "provider", "costCents", "ts", "ttlDays", "origin"),
            fieldOrder,
        )
    }

    @Test
    fun `zwei Notizen landen in EINER durchgehenden Datei - keine Tages-Rotation`(@TempDir dir: Path) {
        val path = dir.resolve("nachgeschlagen.jsonl")
        JsonlLookupNoteAdapter(path).use { adapter ->
            adapter.record(sampleNote("frage eins"))
            adapter.record(sampleNote("frage zwei"))
        }
        assertEquals(2, Files.readAllLines(path).size)
    }

    @Test
    fun `find liest exaktes queryNorm-Match zurueck, der neueste Treffer gewinnt`(@TempDir dir: Path) {
        val path = dir.resolve("nachgeschlagen.jsonl")
        val adapter = JsonlLookupNoteAdapter(path)
        adapter.record(sampleNote("wie hoch ist der eiffelturm").copy(answer = "alt"))
        adapter.record(sampleNote("wie hoch ist der eiffelturm").copy(answer = "neu"))
        adapter.record(sampleNote("ganz andere frage"))
        adapter.close()

        val found = adapter.find("wie hoch ist der eiffelturm")
        assertEquals("neu", found?.answer, "der letzte (jüngste) Treffer gewinnt")
        assertNull(adapter.find("kein treffer"), "kein Match ⇒ null")
    }

    @Test
    fun `find ohne Datei liefert null, wirft nie`(@TempDir dir: Path) {
        val adapter = JsonlLookupNoteAdapter(dir.resolve("fehlt.jsonl"))
        assertNull(adapter.find("egal"))
    }

    @Test
    fun `kaputter Pfad wirft nie - record und close bleiben still`(@TempDir dir: Path) {
        val notADir = dir.resolve("blocker.txt")
        Files.writeString(notADir, "ich bin keine directory")
        val path = notADir.resolve("nachgeschlagen.jsonl") // Elternteil ist eine Datei, kein Verzeichnis

        val adapter = JsonlLookupNoteAdapter(path)
        assertDoesNotThrow {
            adapter.record(sampleNote())
            adapter.close()
        }
        assertTrue(Files.isRegularFile(notADir), "blocker darf nicht überschrieben sein")
    }

    @Test
    fun `record nach close wirft nie (RejectedExecution abgefangen)`(@TempDir dir: Path) {
        val adapter = JsonlLookupNoteAdapter(dir.resolve("nachgeschlagen.jsonl"))
        adapter.close()
        assertDoesNotThrow { adapter.record(sampleNote()) }
    }

    @Test
    fun `NOOP schreibt und findet nie, wirft nichts`() {
        assertDoesNotThrow { LookupNotePort.NOOP.record(sampleNote()) }
        assertNull(LookupNotePort.NOOP.find("egal"))
    }

    @Test
    fun `resolveDefaultPath - explizit gewinnt, sonst Prod- bzw Dev-Fallback`(@TempDir dir: Path) {
        val explicit = dir.resolve("custom").resolve("nachgeschlagen.jsonl").toString()
        assertEquals(Path.of(explicit), JsonlLookupNoteAdapter.resolveDefaultPath(explicit))
        // Ohne explizite Property: entweder der Prod-Pfad (falls /var/lib/hoshi-0.8
        // im Testrechner zufällig beschreibbar ist) oder der Dev-Fallback — beides
        // endet auf denselben Dateinamen.
        val resolved = JsonlLookupNoteAdapter.resolveDefaultPath(null)
        assertTrue(resolved.toString().endsWith("lookups/nachgeschlagen.jsonl"))
    }
}
