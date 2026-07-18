package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.DailyNote
import de.hoshi.core.port.DailyNotePort
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * **JSONL-Speicher der Andi-Faktor-Tagesnote** — die echte
 * [DailyNotePort]-Implementierung, ZEILE FÜR ZEILE nach dem
 * [JsonlTurnTraceAdapter]-Muster: EIGENE Datei (`andi-faktor.jsonl` unter
 * `~/.hoshi` im Dev bzw. `/var/lib/hoshi-0.8` in Prod, [file] konfigurierbar),
 * eine Note pro Zeile im Format `{ts,score,grund,source}`. Der North-Star
 * („Andi-Faktor ≥ 4,2 über 14 Tage") liest später genau diese Datei.
 *
 * **Überschreib-Vertrag (eine Zeile pro Kalendertag):** eine zweite Note am
 * selben Tag (Zone [zone], Kalendertag von [DailyNote.ts]) ERSETZT die erste —
 * der Writer schreibt die Datei dann OHNE die Alt-Zeile(n) dieses Tages neu
 * (Temp-File + atomarer Rename). Unparsebare Fremd-Zeilen werden dabei NIE
 * verworfen (kein Daten-Zerstören, best-effort-Doktrin). [record] meldet
 * synchron aus dem Datums-Cache, OB überschrieben wird — die Quittung des
 * Fastpaths kann so ehrlich „Aktualisiert" sagen.
 *
 * **Nie den Event-Loop blockieren** (P0-Lehre, exakt [JsonlTurnTraceAdapter]):
 * [record] reiht den Schreib-Job nur in die Queue eines EIGENEN single-thread
 * Daemon-Executors und kehrt sofort zurück; Queue voll ⇒ Job leise weg — eine
 * Tagesnote ist nie wichtiger als der Turn. **Best-effort, wirft NIE:**
 * Serialisierungs-/IO-Fehler werden als WARN geloggt und verschluckt.
 */
class JsonlDailyNoteAdapter(
    private val file: Path = Path.of(DEFAULT_PATH),
    /** Zone, in der der Kalendertag einer Note bestimmt wird (Andis Wohnort). */
    private val zone: ZoneId = ZoneId.of("Europe/Berlin"),
) : DailyNotePort, AutoCloseable {

    private val log = LoggerFactory.getLogger(JsonlDailyNoteAdapter::class.java)
    private val mapper = ObjectMapper()

    /**
     * Kalendertage, für die (Datei-Stand beim Konstruieren + alle seitherigen
     * [record]s) bereits eine Note bekannt ist — die synchrone Wahrheit hinter
     * dem Überschreib-Flag. Zugriff nur unter `synchronized(this)` ([record]).
     */
    private val knownDates: MutableSet<LocalDate> = loadKnownDates()

    /** Genau EIN Schreib-Thread (Reihenfolge = Ankunftsreihenfolge), Daemon, Queue hart begrenzt. */
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "andi-faktor-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    /**
     * Nicht-blockierend: Überschreib-Flag synchron aus dem Datums-Cache, der
     * eigentliche Datei-Write läuft auf dem Writer-Thread (best-effort).
     */
    @Synchronized
    override fun record(note: DailyNote): Boolean {
        val day = note.ts.atZone(zone).toLocalDate()
        val replaced = !knownDates.add(day)
        try {
            executor.execute { writeNote(note, day) }
        } catch (e: Exception) {
            // RejectedExecution nach close() o.ä. — der Turn ist heilig, die Note nicht.
            log.warn("andi-faktor: Tagesnote verworfen ({})", e.toString())
        }
        return replaced
    }

    /**
     * Läuft AUSSCHLIESSLICH auf dem Writer-Thread — best-effort, wirft nie:
     * bestehende Zeilen lesen, Alt-Zeilen DESSELBEN Tages entfernen (ehrliches
     * Überschreiben; unparsebare Zeilen bleiben), neue Zeile ans Ende, dann
     * Temp-File + atomarer Rename (nie eine halb geschriebene Datei).
     */
    private fun writeNote(note: DailyNote, day: LocalDate) {
        try {
            val kept = readLines().filterNot { dayOf(it) == day }
            val lines = kept + serialize(note)
            val dir = file.toAbsolutePath().parent
                ?: throw java.io.IOException("Andi-Faktor-Pfad hat kein Verzeichnis: $file")
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, ".andi-faktor", ".tmp")
            try {
                Files.write(tmp, lines.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
                moveOnto(tmp, file.toAbsolutePath())
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                throw e
            }
        } catch (e: Exception) {
            log.warn("andi-faktor: Schreiben fehlgeschlagen, Tagesnote verworfen ({})", e.toString())
        }
    }

    /** Eine Zeile JSON, Feld-Reihenfolge stabil gepinnt: `{ts,score,grund,source}` (ts als ISO-8601-String). */
    internal fun serialize(note: DailyNote): String = mapper.writeValueAsString(
        linkedMapOf<String, Any?>(
            "ts" to note.ts.toString(),
            "score" to note.score,
            "grund" to note.grund,
            "source" to note.source,
        ),
    )

    /** Bestehende Zeilen (ohne Leerzeilen); fehlende/unlesbare Datei ⇒ leer (best-effort). */
    private fun readLines(): List<String> =
        runCatching {
            if (Files.exists(file)) Files.readAllLines(file, StandardCharsets.UTF_8).filter { it.isNotBlank() }
            else emptyList()
        }.getOrDefault(emptyList())

    /** Kalendertag einer JSONL-Zeile (ts-Feld, Zone [zone]); unparsebar ⇒ null (Zeile bleibt erhalten). */
    private fun dayOf(line: String): LocalDate? =
        runCatching {
            val ts = mapper.readTree(line)?.get("ts")?.asText() ?: return null
            Instant.parse(ts).atZone(zone).toLocalDate()
        }.getOrNull()

    /** Einmal beim Konstruieren: alle bekannten Kalendertage aus der Datei (best-effort, wirft nie). */
    private fun loadKnownDates(): MutableSet<LocalDate> =
        readLines().mapNotNull { dayOf(it) }.toMutableSet()

    /** Atomarer Rename, mit Fallback für Dateisysteme ohne ATOMIC_MOVE (Store-Muster). */
    private fun moveOnto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Flush für Tests/Shutdown: wartet kurz, bis die Queue geschrieben ist. */
    override fun close() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        /** Default-Ablage in Prod (Dev/Wiring: `~/.hoshi/andi-faktor.jsonl`, `HOSHI_ANDI_FAKTOR_PATH`). */
        const val DEFAULT_PATH: String = "/var/lib/hoshi-0.8/andi-faktor.jsonl"

        /** Hartes Queue-Limit — mehr ungeschriebene Noten als das ⇒ verwerfen statt stauen. */
        const val QUEUE_CAPACITY: Int = 64
    }
}
