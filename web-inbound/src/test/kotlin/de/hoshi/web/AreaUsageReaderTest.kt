package de.hoshi.web

import de.hoshi.adapters.supervision.JsonlTurnTraceAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * **AreaUsageReaderTest** — die neue Lese-Naht (kartiert in Commit f049965 /
 * `frontend/src/components/roomsSort.ts`-KDoc): beweist das 14-Tage-Fenster
 * ([AreaUsageReader.WINDOW_DAYS]), die Ehrlichkeits-Regeln (fehlende Datei/
 * kaputte Zeile/leere `targetAreaId` ⇒ zählen nicht mit) und den `NONE`-
 * Default (nie I/O).
 *
 * [Clock.fixed] injiziert — KEINE Wanduhr, KEIN `Instant.now()` im Fixture
 * (Lehre aus drei früheren Vorfällen dieser Klasse).
 */
class AreaUsageReaderTest {

    private val fixedNow: Instant = Instant.parse("2026-08-11T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.ofInstant(fixedNow, ZoneOffset.UTC)

    private fun writeDay(dir: Path, day: LocalDate, lines: List<String>) {
        Files.createDirectories(dir)
        val file = dir.resolve("${JsonlTurnTraceAdapter.FILE_PREFIX}-${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl")
        Files.write(file, lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun `zaehlt targetAreaId-Treffer je Area ueber mehrere Tage`(@TempDir dir: Path) {
        writeDay(
            dir,
            today,
            listOf(
                """{"ts":"2026-08-11T09:00:00Z","targetAreaId":"kueche"}""",
                """{"ts":"2026-08-11T10:00:00Z","targetAreaId":"kueche"}""",
            ),
        )
        writeDay(dir, today.minusDays(1), listOf("""{"ts":"2026-08-10T09:00:00Z","targetAreaId":"wohnzimmer"}"""))

        val counts = AreaUsageReader(directory = dir, clock = clock).countsByArea()

        assertEquals(2, counts["kueche"])
        assertEquals(1, counts["wohnzimmer"])
    }

    @Test
    fun `Tag genau 14 zurueck zaehlt noch mit - Tag 15 nicht mehr`(@TempDir dir: Path) {
        writeDay(dir, today.minusDays(13), listOf("""{"ts":"2026-07-29T09:00:00Z","targetAreaId":"buero"}"""))
        writeDay(dir, today.minusDays(14), listOf("""{"ts":"2026-07-28T09:00:00Z","targetAreaId":"buero"}"""))

        val counts = AreaUsageReader(directory = dir, clock = clock).countsByArea()

        assertEquals(1, counts["buero"], "Tag -13 (14. Tag im Fenster) muss noch zaehlen")
    }

    @Test
    fun `Zeile ohne oder mit leerer targetAreaId zaehlt nicht mit`(@TempDir dir: Path) {
        writeDay(
            dir,
            today,
            listOf(
                """{"ts":"2026-08-11T09:00:00Z"}""",
                """{"ts":"2026-08-11T09:05:00Z","targetAreaId":null}""",
                """{"ts":"2026-08-11T09:10:00Z","targetAreaId":""}""",
                """{"ts":"2026-08-11T09:15:00Z","targetAreaId":"kueche"}""",
            ),
        )

        val counts = AreaUsageReader(directory = dir, clock = clock).countsByArea()

        assertEquals(1, counts["kueche"])
        assertEquals(1, counts.size, "nur der eine echte Treffer zaehlt")
    }

    @Test
    fun `kaputte Zeile wird uebersprungen - der Rest der Datei zaehlt trotzdem`(@TempDir dir: Path) {
        writeDay(
            dir,
            today,
            listOf(
                "{kein-json",
                """{"ts":"2026-08-11T09:00:00Z","targetAreaId":"kueche"}""",
            ),
        )

        val counts = AreaUsageReader(directory = dir, clock = clock).countsByArea()

        assertEquals(1, counts["kueche"])
    }

    @Test
    fun `fehlendes Verzeichnis - leere Zaehlung, kein Fehler`(@TempDir dir: Path) {
        val counts = AreaUsageReader(directory = dir.resolve("existiert-nicht"), clock = clock).countsByArea()

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `NONE-Default liefert immer leere Zaehlung ohne I-O`() {
        val counts = AreaUsageReader.NONE.countsByArea()

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `directory null - leere Zaehlung wie NONE`() {
        assertTrue(AreaUsageReader(directory = null, clock = clock).countsByArea().isEmpty())
    }
}
