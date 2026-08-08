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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 *  - `GET /api/v1/diary/recent?limit=25&before=<ISO-8601-ts>` — additive Paginierung
 *    (Andi-Auftrag „Frühere laden" 2026-07-27, FE-Befund: der Turn-Feed rendert bisher
 *    JEDEN geladenen Turn ungegliedert): liefert die `limit` jüngsten Zeilen STRIKT VOR
 *    `before`, rückwärts Tag für Tag gesucht (nicht nur heute+gestern — genau dafür
 *    braucht es diese Erweiterung, sonst kommt ein Diagnose-Tab, der monatelang läuft,
 *    nie an ältere Turns heran). Ein ALTER Client, der `before` gar nicht sendet, sieht
 *    exakt das alte Verhalten (Default-Zweig unverändert) — reiner additiver Vertrag.
 *
 * Ehrlichkeits-Regeln:
 *  - Datei/Verzeichnis fehlt (Diary OFF oder noch kein Turn) ⇒ `[]` (HTTP 200, kein Fehler).
 *  - Kaputte Zeilen werden übersprungen (best-effort lesen, nie 500 wegen einer Zeile).
 *  - Ein kaputter/unparsbarer `before`-Wert ⇒ ehrlich `[]` (kein Fehler, keine geratene Seite).
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
        @RequestParam(name = "before", required = false) before: String?,
    ): Mono<List<Map<String, Any?>>> =
        Mono.fromCallable { readRecent(limit.coerceIn(1, MAX_LIMIT), before) }
            .subscribeOn(Schedulers.boundedElastic())

    /**
     * Ohne `before` (Alt-Vertrag, UNVERÄNDERT): gestern + heute chronologisch
     * einlesen, Tail [limit], dann neueste zuerst.
     *
     * Mit `before` (additive Paginierung — s. Klassendoc): die [limit] jüngsten
     * Zeilen STRIKT VOR dem `before`-Zeitpunkt, rückwärts Tag für Tag gesucht
     * (Anker-Tag von `before` zuerst, dann immer weiter zurück), bis genug
     * Zeilen beisammen sind oder [MAX_LOOKBACK_DAYS] erreicht ist (Kosten-Deckel
     * — ein manueller „Frühere laden"-Klick, kein Dauerpoll). Ein unparsbarer
     * `before`-Wert liefert ehrlich `[]`.
     */
    internal fun readRecent(limit: Int, before: String? = null): List<Map<String, Any?>> {
        val dir = resolveDirectory()
        if (before == null) {
            val today = LocalDate.now()
            return listOf(today.minusDays(1), today) // chronologisch: gestern vor heute
                .flatMap { day -> readDay(dir, day) }
                .takeLast(limit)
                .asReversed()
        }
        val beforeInstant = parseInstant(before) ?: return emptyList()
        val anchorDay = LocalDate.ofInstant(beforeInstant, ZoneId.systemDefault())
        // Chronologisch aufsteigend gesammelt (ältere Tage werden VORNE angehängt),
        // damit `takeLast(limit)` am Ende exakt dieselbe Semantik wie oben hat.
        val collected = mutableListOf<Map<String, Any?>>()
        var day = anchorDay
        var scanned = 0
        while (scanned < MAX_LOOKBACK_DAYS && collected.size < limit) {
            val olderLines = readDay(dir, day).filter { row ->
                val ts = tsOf(row) ?: return@filter false // unlesbares/fehlendes ts ⇒ raus, nie geraten
                ts.isBefore(beforeInstant)
            }
            collected.addAll(0, olderLines)
            day = day.minusDays(1)
            scanned++
        }
        return collected.takeLast(limit).asReversed()
    }

    /** ISO-8601-Instant oder `null` bei Junk — nie eine geratene Seite. */
    private fun parseInstant(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (_: Exception) {
        null
    }

    /** `ts`-Feld einer geparsten Diary-Zeile als [Instant], oder `null` bei Junk/Fehlen. */
    private fun tsOf(row: Map<String, Any?>): Instant? = (row["ts"] as? String)?.let(::parseInstant)

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

        /**
         * Kosten-Deckel für die `before`-Rückwärtssuche: mehr Tage als das werden nie
         * gescannt, selbst wenn [limit] nie erreicht wird (ein „Frühere laden"-Klick ist
         * ein einzelner Request, kein Dauerpoll — aber Datei-I/O bleibt trotzdem endlich).
         * ~14 Monate decken das „das Diary wächst monatelang"-Szenario komfortabel ab.
         */
        const val MAX_LOOKBACK_DAYS: Int = 430
    }
}
