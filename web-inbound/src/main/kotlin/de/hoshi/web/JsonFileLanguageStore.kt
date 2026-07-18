package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileLanguageStore** — der LAUFZEIT-Zustand des Sprach-Settings (Andi-
 * Auftrag 2026-07-20: „DE/EN/ES/FR/IT wählbar"), persistiert als kleine JSON-Datei
 * (`~/.hoshi/language.json` bzw. `hoshi.language.path` / `HOSHI_LANGUAGE_PATH`).
 * ZEILE FÜR ZEILE nach [JsonFileLookupModelStore]/[JsonFileWeatherLocationStore]:
 * der Store kennt KEINE Boot-ENV-Seeds — der Aufrufer ([LanguageSettingsController]/
 * [WebSocketConfig]/[VoiceInboundController]) legt `?: Language.DEFAULT` (DE) als
 * Fallback davor — byte-neutral, solange nie ein Wert gespeichert wurde.
 *
 * Robust per Doktrin: fehlende/kaputte/unbekannte Datei ⇒ [languageCode] liefert
 * `null` (⇒ Boot-Default DE greift); kein LESE-Fehler wirft je. Ein SCHREIB-Fehler
 * ([setLanguageCode]) wirft (persist-then-commit, Cache bleibt unangetastet).
 */
class JsonFileLanguageStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: String? = null

    init {
        reload()
    }

    /** Der gespeicherte Sprach-Code (z.B. "en"), oder `null` wenn nie einer gesetzt wurde (⇒ Boot-Default DE greift). */
    fun languageCode(): String? = cached

    /**
     * **Atomar setzen — persist-then-commit.**
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setLanguageCode(code: String) {
        writeSnapshot(code)
        cached = code
    }

    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            val code = root.path(LANGUAGE_FIELD).asText("")
            if (code.isBlank()) return
            cached = code
        }
    }

    private fun writeSnapshot(code: String) {
        val dir = path.parent ?: throw IOException("Sprach-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".language", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(mapOf(LANGUAGE_FIELD to code)))
            moveOnto(tmp, path)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    private fun moveOnto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Das eine JSON-Feld: `{"languageCode":"en"}`. */
        const val LANGUAGE_FIELD = "languageCode"
    }
}
