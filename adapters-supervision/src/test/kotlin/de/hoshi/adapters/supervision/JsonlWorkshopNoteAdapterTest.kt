package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.WorkshopNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Beweist die Datei-Verträge des [JsonlWorkshopNoteAdapter] OHNE Live-Infra:
 * (1) eine Notiz wird als EINE JSONL-Zeile `{ts,speakerId,text}` persistiert
 * (Roundtrip), (2) `speakerId=null` landet als JSON-null, (3) eine ZWEITE
 * Notiz wird ANGEHÄNGT statt zu überschreiben (der Briefkasten-Vertrag —
 * ANDERS als der überschreibende [JsonlDailyNoteAdapter]), sogar wenn beide
 * am selben Tag/mit derselben speakerId ankommen, (4) ein kaputter Pfad wirft
 * NIE (best-effort).
 */
class JsonlWorkshopNoteAdapterTest {

    private val mapper = ObjectMapper()

    private fun note(ts: String, speakerId: String? = "andi", text: String) =
        WorkshopNote(ts = Instant.parse(ts), speakerId = speakerId, text = text)

    private fun lines(file: Path): List<String> =
        Files.readAllLines(file).filter { it.isNotBlank() }

    @Test
    fun `Notiz wird als eine JSONL-Zeile mit exakten Feldern geschrieben`(@TempDir dir: Path) {
        val file = dir.resolve("werkstatt-notizen.jsonl")
        JsonlWorkshopNoteAdapter(file).use { adapter ->
            adapter.record(note("2026-07-08T09:30:00Z", "andi", "Timer-Antwort zu lang"))
        }
        val line = lines(file).single()
        val json = mapper.readTree(line)
        assertEquals("2026-07-08T09:30:00Z", json.get("ts").asText())
        assertEquals("andi", json.get("speakerId").asText())
        assertEquals("Timer-Antwort zu lang", json.get("text").asText())
    }

    @Test
    fun `speakerId null landet als JSON-null`(@TempDir dir: Path) {
        val file = dir.resolve("werkstatt-notizen.jsonl")
        JsonlWorkshopNoteAdapter(file).use { it.record(note("2026-07-08T09:30:00Z", null, "Kaffee ist alle")) }
        assertTrue(mapper.readTree(lines(file).single()).get("speakerId").isNull, "kein Sprecher ⇒ ehrliches null")
    }

    @Test
    fun `zwei Notizen werden angehaengt statt ueberschrieben`(@TempDir dir: Path) {
        val file = dir.resolve("werkstatt-notizen.jsonl")
        JsonlWorkshopNoteAdapter(file).use { adapter ->
            // Gleicher Tag, gleicher Sprecher — der Briefkasten hat KEINEN
            // Ueberschreib-Vertrag: beide Notizen muessen ueberleben.
            adapter.record(note("2026-07-08T09:30:00Z", "andi", "erste Notiz"))
            adapter.record(note("2026-07-08T10:00:00Z", "andi", "zweite Notiz"))
        }
        val all = lines(file).map { mapper.readTree(it).get("text").asText() }
        assertEquals(listOf("erste Notiz", "zweite Notiz"), all, "zwei Notizen ⇒ zwei Zeilen, Reihenfolge stabil")
    }

    @Test
    fun `Notizen ueber mehrere Adapter-Instanzen hinweg werden angehaengt`(@TempDir dir: Path) {
        val file = dir.resolve("werkstatt-notizen.jsonl")
        JsonlWorkshopNoteAdapter(file).use { it.record(note("2026-07-08T09:30:00Z", "andi", "vor Neustart")) }
        // Neustart-Szenario: frischer Adapter über derselben Datei.
        JsonlWorkshopNoteAdapter(file).use { it.record(note("2026-07-08T10:00:00Z", "andi", "nach Neustart")) }
        val all = lines(file).map { mapper.readTree(it).get("text").asText() }
        assertEquals(listOf("vor Neustart", "nach Neustart"), all)
    }

    @Test
    fun `Default-Pfad zeigt unter HOME auf hoshi werkstatt-notizen jsonl`() {
        val expected = Path.of(System.getProperty("user.home"), ".hoshi", "werkstatt-notizen.jsonl")
        assertEquals(expected, JsonlWorkshopNoteAdapter.defaultPath())
    }

    @Test
    fun `kaputter Pfad wirft nie`() {
        // Datei unter einer DATEI statt einem Verzeichnis ⇒ jeder Write scheitert — aber leise.
        val bogus = Files.createTempFile("werkstatt-notiz-bogus", ".txt")
        val adapter = JsonlWorkshopNoteAdapter(bogus.resolve("unmoeglich.jsonl"))
        assertDoesNotThrow {
            adapter.record(note("2026-07-08T09:30:00Z", "andi", "Timer-Antwort zu lang"))
            adapter.close()
        }
    }
}
