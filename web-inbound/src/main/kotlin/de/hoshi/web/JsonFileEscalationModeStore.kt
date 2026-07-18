package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.pipeline.EscalationMode
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileEscalationModeStore** — der LAUFZEIT-Zustand des Extended-Think-
 * Drei-Stufen-Settings (S2), persistiert als kleine JSON-Datei
 * (`~/.hoshi/extended-think.json` bzw. `hoshi.extended-think.path` /
 * `HOSHI_EXTENDED_THINK_PATH`). ZEILE FÜR ZEILE nach dem
 * [JsonFileSkillStateStore]-Muster — der einzige Unterschied: ein
 * [EscalationMode]-Enum statt per-Skill-Booleans.
 *
 * Pro Turn liest der TurnOrchestrator-Mode-Supplier billig aus dem
 * [Volatile]-Cache (NIE pro Turn von der Platte). Die Datei wird genau einmal
 * beim Konstruieren gelesen und danach nur bei [setMode] (Settings-PUT)
 * atomar neu geschrieben.
 *
 * Robust per Doktrin: fehlende/kaputte Datei ⇒ kein gesetzter Wert ⇒ [mode]
 * liefert den Laufzeit-Default [EscalationMode.RUNTIME_DEFAULT] (ERST_FRAGEN,
 * bindender Entscheid #3 — greift ohnehin nur bei OFFENER Decke, die Decke-zu-
 * Kaskade kollabiert im Wiring auf AUS); kein LESE-Fehler wirft je. Ein
 * SCHREIB-Fehler ([setMode]) ist dagegen NICHT schluckbar: er wirft, der Cache
 * bleibt unangetastet (persist-then-commit) — der Controller liefert ehrlich
 * 5xx statt fake-200.
 */
class JsonFileEscalationModeStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: EscalationMode? = null

    init {
        reload()
    }

    /**
     * Laufzeit-Read (billiger Cache-Read, kein Datei-I/O): der gespeicherte
     * Mode, oder [EscalationMode.RUNTIME_DEFAULT] wenn nie einer gesetzt wurde.
     */
    fun mode(): EscalationMode = cached ?: EscalationMode.RUNTIME_DEFAULT

    /**
     * **Atomar setzen — persist-then-commit** (Settings-PUT): ZUERST atomar auf
     * die Platte, DANN — nur bei bewiesenem Persist — der Cache. Schlägt der
     * Schreibvorgang fehl, WIRFT [writeSnapshot] und der Cache bleibt
     * unangetastet (`cache == letzter bewiesener Platten-Zustand`).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setMode(mode: EscalationMode) {
        writeSnapshot(mode)
        cached = mode
    }

    /** Datei lesen (best-effort, wirft NIE). Kaputt/fehlend/unbekannter Wert ⇒ kein Cache ⇒ Default. */
    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            cached = EscalationMode.fromWire(root.get(MODE_FIELD)?.asText())
        }
    }

    /** Temp-File im Zielverzeichnis + atomarer Rename; Schreib-Fehler WIRFT (Temp best-effort geräumt). */
    private fun writeSnapshot(mode: EscalationMode) {
        val dir = path.parent ?: throw IOException("Extended-Think-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".extended-think", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(mapOf(MODE_FIELD to mode.name)))
            moveOnto(tmp, path)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /** Atomarer Rename, mit Fallback für Dateisysteme ohne ATOMIC_MOVE. */
    private fun moveOnto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Das eine JSON-Feld: `{"mode":"ERST_FRAGEN"}`. */
        const val MODE_FIELD = "mode"
    }
}
