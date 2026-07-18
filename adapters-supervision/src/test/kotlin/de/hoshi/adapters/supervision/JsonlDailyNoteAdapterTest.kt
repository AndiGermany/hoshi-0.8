package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.DailyNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * Beweist die Datei-Verträge des [JsonlDailyNoteAdapter] OHNE Live-Infra:
 * (1) eine Note wird als EINE JSONL-Zeile `{ts,score,grund,source}` persistiert
 * (Roundtrip), (2) die zweite Note am SELBEN Kalendertag ÜBERSCHREIBT die erste
 * (eine Zeile pro Tag, `record` meldet es synchron), (3) andere Tage werden
 * angehängt statt ersetzt, (4) Fremd-/kaputte Zeilen überleben jedes
 * Neu-Schreiben, (5) ein kaputter Pfad wirft NIE (best-effort).
 */
class JsonlDailyNoteAdapterTest {

    private val mapper = ObjectMapper()
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun note(ts: String, score: Int, grund: String? = null, source: String = "chat") =
        DailyNote(ts = Instant.parse(ts), score = score, grund = grund, source = source)

    private fun lines(file: Path): List<String> =
        Files.readAllLines(file).filter { it.isNotBlank() }

    @Test
    fun `Note wird als eine JSONL-Zeile mit exakten Feldern geschrieben`(@TempDir dir: Path) {
        val file = dir.resolve("andi-faktor.jsonl")
        JsonlDailyNoteAdapter(file, berlin).use { adapter ->
            val replaced = adapter.record(note("2026-07-07T09:30:00Z", 4, grund = "zu langsam", source = "voice"))
            assertFalse(replaced, "erste Note des Tages ist KEIN Überschreiben")
        }
        val line = lines(file).single()
        val json = mapper.readTree(line)
        assertEquals("2026-07-07T09:30:00Z", json.get("ts").asText())
        assertEquals(4, json.get("score").asInt())
        assertEquals("zu langsam", json.get("grund").asText())
        assertEquals("voice", json.get("source").asText())
    }

    @Test
    fun `grund null landet als JSON-null`(@TempDir dir: Path) {
        val file = dir.resolve("andi-faktor.jsonl")
        JsonlDailyNoteAdapter(file, berlin).use { it.record(note("2026-07-07T09:30:00Z", 5)) }
        assertTrue(mapper.readTree(lines(file).single()).get("grund").isNull, "kein Grund ⇒ ehrliches null")
    }

    @Test
    fun `zweite Note am selben Tag ueberschreibt die erste`(@TempDir dir: Path) {
        val file = dir.resolve("andi-faktor.jsonl")
        JsonlDailyNoteAdapter(file, berlin).use { adapter ->
            assertFalse(adapter.record(note("2026-07-07T09:30:00Z", 4)))
            assertTrue(adapter.record(note("2026-07-07T21:00:00Z", 3, grund = "abends doch zäh")), "zweite Note desselben Tages meldet Überschreiben")
        }
        val line = lines(file).single()
        val json = mapper.readTree(line)
        assertEquals(3, json.get("score").asInt(), "die NEUE Note gilt — genau eine Zeile pro Tag")
        assertEquals("abends doch zäh", json.get("grund").asText())
    }

    @Test
    fun `Noten verschiedener Tage werden angehaengt`(@TempDir dir: Path) {
        val file = dir.resolve("andi-faktor.jsonl")
        JsonlDailyNoteAdapter(file, berlin).use { adapter ->
            assertFalse(adapter.record(note("2026-07-06T12:00:00Z", 2)))
            assertFalse(adapter.record(note("2026-07-07T12:00:00Z", 5)), "neuer Tag ist kein Überschreiben")
        }
        val all = lines(file).map { mapper.readTree(it).get("score").asInt() }
        assertEquals(listOf(2, 5), all, "zwei Tage ⇒ zwei Zeilen, Reihenfolge stabil")
    }

    @Test
    fun `neuer Adapter kennt die Bestands-Tage aus der Datei`(@TempDir dir: Path) {
        val file = dir.resolve("andi-faktor.jsonl")
        JsonlDailyNoteAdapter(file, berlin).use { it.record(note("2026-07-07T09:30:00Z", 4)) }
        // Neustart-Szenario: frischer Adapter über derselben Datei.
        JsonlDailyNoteAdapter(file, berlin).use { adapter ->
            assertTrue(adapter.record(note("2026-07-07T21:00:00Z", 5)), "Datei-Stand zählt auch nach Neustart als bekannt")
        }
        assertEquals(5, mapper.readTree(lines(file).single()).get("score").asInt())
    }

    @Test
    fun `fremde und kaputte Zeilen ueberleben das Neu-Schreiben`(@TempDir dir: Path) {
        val file = dir.resolve("andi-faktor.jsonl")
        Files.write(file, listOf("KAPUTT { keine json", """{"ts":"2026-07-06T08:00:00Z","score":1,"grund":null,"source":"chat"}"""))
        JsonlDailyNoteAdapter(file, berlin).use { adapter ->
            adapter.record(note("2026-07-07T09:30:00Z", 4))
        }
        val all = lines(file)
        assertEquals(3, all.size, "kaputte + fremde Tages-Zeile bleiben, neue kommt dazu")
        assertTrue(all[0].startsWith("KAPUTT"), "nie Daten zerstören")
    }

    @Test
    fun `Mitternachts-Grenze der Berlin-Zone trennt die Tage ehrlich`(@TempDir dir: Path) {
        val file = dir.resolve("andi-faktor.jsonl")
        JsonlDailyNoteAdapter(file, berlin).use { adapter ->
            // 22:30Z am 06.07. == 00:30 Berlin am 07.07. ⇒ NICHT derselbe Tag wie 12:00Z am 06.07.
            assertFalse(adapter.record(note("2026-07-06T12:00:00Z", 2)))
            assertFalse(adapter.record(note("2026-07-06T22:30:00Z", 4)), "nach Berlin-Mitternacht beginnt ein neuer Tag")
        }
        assertEquals(2, lines(file).size)
    }

    @Test
    fun `kaputter Pfad wirft nie`() {
        // Datei unter einer DATEI statt einem Verzeichnis ⇒ jeder Write scheitert — aber leise.
        val bogus = Files.createTempFile("andi-faktor-bogus", ".txt")
        val adapter = JsonlDailyNoteAdapter(bogus.resolve("unmoeglich.jsonl"), berlin)
        assertDoesNotThrow {
            adapter.record(note("2026-07-07T09:30:00Z", 4))
            adapter.close()
        }
    }
}
