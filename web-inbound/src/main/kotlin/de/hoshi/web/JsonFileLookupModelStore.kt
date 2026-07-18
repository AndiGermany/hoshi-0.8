package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileLookupModelStore** — der LAUFZEIT-Zustand des Lookup-Modell-Settings
 * (Andi-Video-Auftrag), persistiert als kleine JSON-Datei
 * (`~/.hoshi/lookup-model.json` bzw. `hoshi.lookup-model.path` /
 * `HOSHI_LOOKUP_MODEL_PATH`). ZEILE FÜR ZEILE nach [JsonFileTtsEngineStore]/
 * [JsonFileWeatherLocationStore]: der Store kennt KEINE Boot-ENV-Seeds — der
 * Aufrufer ([LookupModelController]/`LookupModelConfig`) legt
 * `?: escalationModel.ifBlank { EscalationModelCatalog.DEFAULT_MODEL_ID }` als
 * Fallback davor.
 *
 * Betrifft NUR das SCHNELL-Lookup-Modell (`hoshi.escalation.model`) — das
 * Recherche-Modell (`hoshi.escalation.research-model`) hat KEINEN Settings-Rand
 * (Andi-Vorgabe: bleibt Env-only).
 *
 * Robust per Doktrin: fehlende/kaputte Datei ⇒ [modelId] liefert `null`; kein
 * LESE-Fehler wirft je. Ein SCHREIB-Fehler ([setModelId]) wirft (persist-then-
 * commit, Cache bleibt unangetastet).
 */
class JsonFileLookupModelStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: String? = null

    init {
        reload()
    }

    /** Die gespeicherte Modell-Id, oder `null` wenn nie eine gesetzt wurde (⇒ Boot-Default greift). */
    fun modelId(): String? = cached

    /**
     * **Atomar setzen — persist-then-commit.**
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setModelId(modelId: String) {
        writeSnapshot(modelId)
        cached = modelId
    }

    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            val id = root.path(MODEL_FIELD).asText("")
            if (id.isBlank()) return
            cached = id
        }
    }

    private fun writeSnapshot(modelId: String) {
        val dir = path.parent ?: throw IOException("Lookup-Modell-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".lookup-model", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(mapOf(MODEL_FIELD to modelId)))
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
        /** Das eine JSON-Feld: `{"modelId":"gpt-5.4-mini"}`. */
        const val MODEL_FIELD = "modelId"
    }
}
