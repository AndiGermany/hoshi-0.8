package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileBrainModelStore** — merkt sich das ZULETZT per `PUT /settings/brain`
 * GEWÄHLTE Brain-Modell (volle HF-Repo-Id, z.B. `mlx-community/gemma-4-e4b-it-4bit`),
 * persistiert als kleine JSON-Datei (`~/.hoshi/brain-model.json` bzw.
 * `hoshi.brain-model.path` / `HOSHI_BRAIN_MODEL_PATH`). ZEILE FÜR ZEILE nach
 * [JsonFileLookupModelStore]/[JsonFileTtsEngineStore].
 *
 * **Warum dieser Store existiert** (Andi-Befund 2026-07-20): die Ops-Drift-Prüfung
 * ([SidecarHealthService]) verglich das GEMESSENE Brain-Modell gegen ein per-Deploy
 * fixiertes Env-Literal (`HOSHI_BRAIN_EXPECTED_MODEL`, aus `HOSHI_BRAIN_MODEL` zur
 * Deploy-Zeit abgeleitet, s. `pipeline/deploy.sh#resolve_brain_expected`). Ein
 * bewusster Laufzeit-Wechsel über die Settings-UI (`PUT /api/v1/settings/brain` →
 * [BrainSettingsController]) änderte dieses Literal NIE — die Übersicht meldete
 * darum „Drift" gegen ein Soll, das der User selbst überholt hatte. Dieser Store
 * trägt das GEWÄHLTE Soll separat, damit [SidecarHealthService] IMMER gegen den
 * echten Nutzer-Wunsch prüft, nicht gegen ein Boot-Zeit-Fossil.
 *
 * Betrifft NUR die Ops-Drift-Anzeige — die LIVE `aktiv`-Zeile in
 * [BrainSettingsController] bleibt unverändert die gemessene `/health`-Wahrheit
 * (kein Store nötig dafür, s. [BrainRuntimeConfig]-KDoc).
 *
 * Robust per Doktrin: fehlende/kaputte Datei ⇒ [selectedRepo] liefert `null` (⇒
 * Boot-Default `HOSHI_BRAIN_EXPECTED_MODEL` bleibt die Wahrheit); kein LESE-Fehler
 * wirft je. Ein SCHREIB-Fehler ([setSelectedRepo]) wirft (persist-then-commit,
 * Cache bleibt unangetastet). Kein Boot-ENV-Seed hier — ein Neustart des Backends
 * OHNE je einen Runtime-Switch bleibt beim Boot-Default; der Aufrufer
 * ([SidecarHealthService]) legt den Fallback davor.
 */
class JsonFileBrainModelStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: String? = null

    init {
        reload()
    }

    /** Die zuletzt per PUT gewählte VOLLE Repo-Id, oder `null` wenn nie gewählt (⇒ Boot-Default gilt). */
    fun selectedRepo(): String? = cached

    /**
     * **Atomar setzen — persist-then-commit.**
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setSelectedRepo(repo: String) {
        writeSnapshot(repo)
        cached = repo
    }

    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            val repo = root.path(REPO_FIELD).asText("")
            if (repo.isBlank()) return
            cached = repo
        }
    }

    private fun writeSnapshot(repo: String) {
        val dir = path.parent ?: throw IOException("Brain-Modell-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".brain-model", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(mapOf(REPO_FIELD to repo)))
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
        /** Das eine JSON-Feld: `{"repo":"mlx-community/gemma-4-e4b-it-4bit"}`. */
        const val REPO_FIELD = "repo"
    }
}
