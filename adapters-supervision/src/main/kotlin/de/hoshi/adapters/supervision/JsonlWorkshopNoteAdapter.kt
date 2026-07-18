package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.WorkshopNote
import de.hoshi.core.port.WorkshopNotePort
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * **JSONL-Briefkasten der Werkstatt-Notiz** — die echte [WorkshopNotePort]-
 * Implementierung, ZEILE FÜR ZEILE nach dem [JsonlTurnTraceAdapter]-Muster:
 * EINE Datei ([file], Default `~/.hoshi/werkstatt-notizen.jsonl`,
 * konfigurierbar), APPEND-only — eine Notiz pro Zeile im Format
 * `{ts,speakerId,text}`. Der Orchestrator liest diese Datei morgens.
 *
 * **ANDERS als [JsonlDailyNoteAdapter]: KEIN Überschreib-Vertrag, kein
 * Datums-Cache.** Ein Briefkasten sammelt, er urteilt nicht — JEDE Notiz wird
 * eine EIGENE, neue Zeile ans Dateiende gehängt, egal wie viele am selben Tag
 * hereinkommen. Das macht den Write auch einfacher: ein einzelner
 * `Files.write(..., APPEND)` genügt (kein Lesen+Neu-Schreiben+Rename wie beim
 * überschreibenden Tagesnote-Adapter nötig) — exakt das
 * [JsonlTurnTraceAdapter]-Muster, das schon appendet statt überschreibt.
 *
 * **Nie den Event-Loop blockieren** (P0-Lehre, exakt [JsonlTurnTraceAdapter]):
 * [record] reiht den Schreib-Job nur in die Queue eines EIGENEN single-thread
 * Daemon-Executors und kehrt sofort zurück; Queue voll ⇒ Job leise weg — eine
 * Werkstatt-Notiz ist nie wichtiger als der Turn. **Best-effort, wirft NIE:**
 * Serialisierungs-/IO-Fehler werden als WARN geloggt und verschluckt.
 */
class JsonlWorkshopNoteAdapter(
    private val file: Path = defaultPath(),
) : WorkshopNotePort, AutoCloseable {

    private val log = LoggerFactory.getLogger(JsonlWorkshopNoteAdapter::class.java)
    private val mapper = ObjectMapper()

    /** Genau EIN Schreib-Thread (Reihenfolge = Ankunftsreihenfolge), Daemon, Queue hart begrenzt. */
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "werkstatt-notiz-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    /** Nicht-blockierend: nur einreihen; der eigentliche Datei-Write läuft auf dem Writer-Thread. */
    override fun record(note: WorkshopNote) {
        try {
            executor.execute { appendLine(note) }
        } catch (e: Exception) {
            // RejectedExecution nach close() o.ä. — der Turn ist heilig, die Notiz nicht.
            log.warn("werkstatt-notiz: Notiz verworfen ({})", e.toString())
        }
    }

    /** Läuft AUSSCHLIESSLICH auf dem Writer-Thread — best-effort, wirft nie: EINE Zeile ans Ende. */
    private fun appendLine(note: WorkshopNote) {
        try {
            val dir = file.toAbsolutePath().parent
                ?: throw IOException("Werkstatt-Notiz-Pfad hat kein Verzeichnis: $file")
            Files.createDirectories(dir)
            Files.write(
                file,
                (serialize(note) + "\n").toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            log.warn("werkstatt-notiz: Schreiben fehlgeschlagen, Notiz verworfen ({})", e.toString())
        }
    }

    /** Eine Zeile JSON, Feld-Reihenfolge stabil gepinnt: `{ts,speakerId,text}` (ts als ISO-8601-String). */
    internal fun serialize(note: WorkshopNote): String = mapper.writeValueAsString(
        linkedMapOf<String, Any?>(
            "ts" to note.ts.toString(),
            "speakerId" to note.speakerId,
            "text" to note.text,
        ),
    )

    /** Flush für Tests/Shutdown: wartet kurz, bis die Queue geschrieben ist. */
    override fun close() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        /** Default-Ablage: der Werkstatt-Briefkasten unter `~/.hoshi` (Wiring: `HOSHI_WORKSHOP_NOTE_PATH`). */
        fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".hoshi", "werkstatt-notizen.jsonl")

        /** Hartes Queue-Limit — mehr ungeschriebene Notizen als das ⇒ verwerfen statt stauen. */
        const val QUEUE_CAPACITY: Int = 64
    }
}
