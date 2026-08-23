package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.supervision.JsonlTurnTraceAdapter
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * **AreaUsageReader** — die NEUE Lese-Naht am `GET /api/v1/home/registry`-Rand
 * (Konzept-Pfad 1a, kartiert in Commit f049965 /
 * `frontend/src/components/roomsSort.ts`-KDoc: „die echte Nutzungs-Naht … wird
 * die nächste Scheibe"): zählt, wie oft jede HA-Area in den letzten
 * [WINDOW_DAYS] Tagen als `targetAreaId` in einem [de.hoshi.core.port.TurnTrace]
 * auftauchte — das ERSTE echte Signal für „Räume, mit denen gesprochen wird"
 * statt der bislang ehrlich zugegebenen Nicht-Existenz dieser Datengrundlage.
 *
 * Liest DIESELBEN Tages-Dateien wie [DiaryController]
 * (`turn-diary-YYYY-MM-DD.jsonl`, EIN JSON-Objekt pro Zeile), mit derselben
 * Ehrlichkeits-Haltung:
 *  - fehlendes Verzeichnis/fehlende Tages-Datei ⇒ diese Zeilen zählen einfach
 *    nicht mit (kein Fehler, keine geratene Zahl);
 *  - eine kaputte/unparsbare Zeile wird übersprungen (best-effort, nie ein
 *    Absturz wegen einer Zeile);
 *  - eine Zeile ohne (oder mit leerer) `targetAreaId` trägt NICHTS zur Zählung
 *    bei (kein Tool-Turn ODER keine aufgelöste Area, s.
 *    [de.hoshi.core.port.TurnTrace.targetAreaId]-KDoc).
 *
 * [directory]`==null` (der [NONE]-Default) ⇒ [countsByArea] liefert IMMER die
 * leere Map, OHNE je das Dateisystem zu berühren — dasselbe NONE-/NOOP-Muster
 * wie [de.hoshi.core.pipeline.LastAreaPort.NONE]/[de.hoshi.core.port.TurnTracePort.NOOP]:
 * ein [de.hoshi.web.HomeRegistryController], der diesen Reader nicht bewusst
 * verdrahtet bekommt, bleibt byte-neutral (kein I/O, keine Test-Flakiness durch
 * zufällig vorhandene echte Diary-Dateien auf der Entwicklungsmaschine).
 *
 * [clock] injizierbar (Tests: deterministisches 14-Tage-Fenster ohne echte
 * Wanduhr — Muster [JsonlTurnTraceAdapter]/[DiaryController]).
 */
class AreaUsageReader(
    private val directory: Path?,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val log = LoggerFactory.getLogger(AreaUsageReader::class.java)
    private val mapper = ObjectMapper()

    /**
     * Area-id → Anzahl der Turns mit dieser `targetAreaId` in den letzten
     * [WINDOW_DAYS] Tagen (heute EINGESCHLOSSEN, also heute + [WINDOW_DAYS]-1
     * Tage zurück). Areas OHNE einen einzigen Treffer fehlen als Key (der
     * Aufrufer entscheidet den `0`-Default, s. [de.hoshi.adapters.ha.HomeRegistryArea.recentCommands]).
     */
    fun countsByArea(): Map<String, Int> {
        val dir = directory ?: return emptyMap()
        val today = LocalDate.now(clock)
        val counts = LinkedHashMap<String, Int>()
        for (offset in 0 until WINDOW_DAYS) {
            readDay(dir, today.minusDays(offset.toLong())).forEach { areaId ->
                counts[areaId] = (counts[areaId] ?: 0) + 1
            }
        }
        return counts
    }

    /** Eine Tages-Datei: fehlt ⇒ leer (ehrlich); kaputte Zeile ⇒ überspringen. */
    private fun readDay(dir: Path, day: LocalDate): List<String> {
        val file = dir.resolve(
            "${JsonlTurnTraceAdapter.FILE_PREFIX}-${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl",
        )
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            Files.readAllLines(file, StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull(::targetAreaOf)
        } catch (e: Exception) {
            log.warn("area-usage-read: {} nicht lesbar ({})", file, e.toString())
            emptyList()
        }
    }

    /** `targetAreaId`-Feld einer geparsten Diary-Zeile, oder `null` bei Junk/Fehlen/leer. */
    private fun targetAreaOf(line: String): String? = try {
        @Suppress("UNCHECKED_CAST")
        val row = mapper.readValue(line, Map::class.java) as Map<String, Any?>
        (row["targetAreaId"] as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null // eine kaputte Zeile kostet nie die ganze Zählung
    }

    companion object {
        /** 14-Tage-Fenster (Konzept §1a — dieselbe Fenstergröße wie der North-Star „Andi-Faktor über 14 Tage"). */
        const val WINDOW_DAYS: Int = 14

        /** Verhaltens-neutraler Default: nie I/O, immer leere Zählung. */
        val NONE: AreaUsageReader = AreaUsageReader(directory = null)
    }
}
