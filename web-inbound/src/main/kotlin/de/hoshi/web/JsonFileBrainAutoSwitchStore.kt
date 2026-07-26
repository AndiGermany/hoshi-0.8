package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileBrainAutoSwitchStore** — der Laufzeit-Zustand des `brainAutoSwitch`-
 * Settings (Andi-Auftrag „12B für Chat, e4b für Voice", 2026-07-26), persistiert
 * als kleine JSON-Datei (`~/.hoshi/brain-auto-switch.json` bzw.
 * `hoshi.brain-auto-switch.path` / `HOSHI_BRAIN_AUTO_SWITCH_PATH`). ZEILE FÜR
 * ZEILE nach dem [JsonFileEscalationModeStore]-Muster — der einzige Unterschied:
 * ein `Boolean` statt eines [de.hoshi.core.pipeline.EscalationMode]-Enums.
 *
 * **Default AUS (byte-neutral):** fehlt die Datei (frischer Boot, nie gesetzt),
 * liefert [enabled] `false` — der [BrainAutoSwitchService] tut dann NICHTS (kein
 * Health-Call, kein Switch-Call). Pro Turn liest der Aufrufer billig aus dem
 * [Volatile]-Cache (NIE von der Platte).
 *
 * Robust per Doktrin: fehlende/kaputte Datei ⇒ kein gesetzter Wert ⇒ [enabled]
 * liefert `false`; kein LESE-Fehler wirft je. Ein SCHREIB-Fehler ([setEnabled])
 * ist dagegen NICHT schluckbar: er wirft, der Cache bleibt unangetastet
 * (persist-then-commit) — der Controller liefert ehrlich 5xx statt fake-200.
 */
class JsonFileBrainAutoSwitchStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: Boolean? = null

    init {
        reload()
    }

    /** Laufzeit-Read (billiger Cache-Read, kein Datei-I/O): `false` (AUS), wenn nie gesetzt. */
    fun enabled(): Boolean = cached ?: false

    /**
     * **Atomar setzen — persist-then-commit**: ZUERST atomar auf die Platte, DANN
     * — nur bei bewiesenem Persist — der Cache.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setEnabled(value: Boolean) {
        writeSnapshot(value)
        cached = value
    }

    /** Datei lesen (best-effort, wirft NIE). Kaputt/fehlend/unbekannter Wert ⇒ kein Cache ⇒ Default AUS. */
    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            val node = root.get(ENABLED_FIELD) ?: return
            if (!node.isBoolean) return
            cached = node.booleanValue()
        }
    }

    /** Temp-File im Zielverzeichnis + atomarer Rename; Schreib-Fehler WIRFT (Temp best-effort geräumt). */
    private fun writeSnapshot(value: Boolean) {
        val dir = path.parent ?: throw IOException("Brain-Auto-Switch-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".brain-auto-switch", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(mapOf(ENABLED_FIELD to value)))
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
        /** Das eine JSON-Feld: `{"enabled":true}`. */
        const val ENABLED_FIELD = "enabled"
    }
}
