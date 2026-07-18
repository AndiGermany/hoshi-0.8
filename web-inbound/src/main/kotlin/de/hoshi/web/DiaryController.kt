package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.supervision.JsonlTurnTraceAdapter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * **DiaryController** — der Lese-Rand des Turn-Diaries (#10) für die Aktivitäts-View.
 *
 *  - `GET /api/v1/diary/recent?limit=50` — liefert die JÜNGSTEN Turn-Traces aus den
 *    JSONL-Tages-Dateien des [JsonlTurnTraceAdapter] (heute + gestern), NEUESTE ZUERST.
 *    Die Zeilen gehen 1:1 als geparste Objekte über den Draht (derselbe Vertrag wie die
 *    Datei — `ts`, `category`, `persona`, `ttftMs`, `deflected`, `error`, …); das Diary
 *    trägt bewusst KEINE Gesprächs-Inhalte (Privacy by Design), also exponiert auch
 *    dieser Endpoint keine.
 *
 * Ehrlichkeits-Regeln:
 *  - Datei/Verzeichnis fehlt (Diary OFF oder noch kein Turn) ⇒ `[]` (HTTP 200, kein Fehler).
 *  - Kaputte Zeilen werden übersprungen (best-effort lesen, nie 500 wegen einer Zeile).
 *
 * Verzeichnis-Auflösung EXAKT wie die `turnTracePort`-Bean in [PipelineConfig]
 * (eine Wahrheit, hier nur gespiegelt): explizit (`hoshi.diary.dir` /
 * `HOSHI_TURN_DIARY_DIR`) ▷ Prod-Datenverzeichnis `/var/lib/hoshi-0.8/diary`
 * (falls beschreibbar) ▷ `~/.hoshi/diary` (Dev). Aufgelöst wird PRO REQUEST —
 * kein Boot-Risiko, und der Controller liest genau da, wo der Adapter schreibt.
 *
 * Blocking-Hygiene: Datei-I/O läuft via [Schedulers.boundedElastic], NIE auf dem
 * Reactor-Netty-Event-Loop (dieselbe P0-Lehre wie beim Schreib-Pfad des Adapters).
 *
 * Perimeter: `/api/v1/...` liegt AUTOMATISCH hinter der [PerimeterWebFilter]-Wand —
 * ohne/falscher Token ⇒ 401, exakt das [FiredItemsController]-Muster; bewiesen im
 * DiaryEndpointTest.
 */
@RestController
class DiaryController(
    @Value("\${hoshi.diary.dir:\${HOSHI_TURN_DIARY_DIR:}}") private val diaryDir: String,
) {

    private val log = LoggerFactory.getLogger(DiaryController::class.java)
    private val mapper = ObjectMapper()

    @GetMapping("/api/v1/diary/recent")
    fun recent(
        @RequestParam(name = "limit", defaultValue = "$DEFAULT_LIMIT") limit: Int,
    ): Mono<List<Map<String, Any?>>> =
        Mono.fromCallable { readRecent(limit.coerceIn(1, MAX_LIMIT)) }
            .subscribeOn(Schedulers.boundedElastic())

    /** Gestern + heute chronologisch einlesen, Tail [limit], dann neueste zuerst. */
    internal fun readRecent(limit: Int): List<Map<String, Any?>> {
        val dir = resolveDirectory()
        val today = LocalDate.now()
        return listOf(today.minusDays(1), today) // chronologisch: gestern vor heute
            .flatMap { day -> readDay(dir, day) }
            .takeLast(limit)
            .asReversed()
    }

    /** Spiegel der `turnTracePort`-Bean-Auflösung in [PipelineConfig] — bitte synchron halten. */
    internal fun resolveDirectory(): Path = when {
        diaryDir.isNotBlank() -> Path.of(diaryDir)
        Files.isWritable(Path.of("/var/lib/hoshi-0.8")) -> Path.of("/var/lib/hoshi-0.8/diary")
        else -> Path.of(System.getProperty("user.home"), ".hoshi", "diary")
    }

    /** Eine Tages-Datei: fehlt ⇒ leer (ehrlich); kaputte Zeile ⇒ überspringen. */
    private fun readDay(dir: Path, day: LocalDate): List<Map<String, Any?>> {
        val file = dir.resolve(
            "${JsonlTurnTraceAdapter.FILE_PREFIX}-${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl",
        )
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            Files.readAllLines(file, StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull(::parseLine)
        } catch (e: Exception) {
            log.warn("diary-read: {} nicht lesbar ({})", file, e.toString())
            emptyList()
        }
    }

    private fun parseLine(line: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        mapper.readValue(line, Map::class.java) as Map<String, Any?>
    } catch (_: Exception) {
        null // eine kaputte Zeile kostet nie den ganzen Feed
    }

    companion object {
        /** Default-Fenstergröße des Feeds (FE fragt genau das an). */
        const val DEFAULT_LIMIT: Int = 50

        /** Hartes Limit — mehr als das gibt der Endpoint nie zurück (Day-Files können groß sein). */
        const val MAX_LIMIT: Int = 500
    }
}
