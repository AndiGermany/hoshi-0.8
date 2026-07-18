package de.hoshi.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.NightModeConfig
import de.hoshi.core.port.NightModeMode
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * **JsonFileNightModeStore** — die pro-Gerät Nachtmodus-Einstellung
 * (`satelliteId → NightModeConfig`), JSON-Datei-persistiert, damit sie einen
 * Backend-Neustart ÜBERLEBT (EXAKT das [JsonFileListStore]-Muster, auf
 * [NightModeConfig] reduziert). Auch Configs für aktuell NICHT verbundene Geräte
 * bleiben erhalten (Andi 12.07: „Auswahl der verbundenen Geräte" — zuletzt gesehene
 * Geräte behalten ihre Einstellung, offline oder nicht).
 *
 *  - Reads ([get]/[all]) kommen billig aus dem [ConcurrentHashMap]-Cache, NIE von
 *    der Platte. Die Datei wird genau einmal beim Konstruieren gelesen und danach
 *    nur bei [set] (Settings-PUT) atomar neu geschrieben.
 *  - **Persist-then-commit:** [set] schreibt ZUERST die GEWÜNSCHTE Sicht auf die
 *    Platte und committet den Cache NUR bei bewiesenem Persist. Ein SCHREIB-Fehler
 *    ist NICHT schluckbar — er wirft, der Cache bleibt unangetastet (kein
 *    „fake-grün").
 *  - Ein LESE-Fehler beim Start wirft dagegen NIE: fehlende/kaputte Datei ⇒ leer
 *    starten + WARN (robust per Doktrin).
 *
 * Datei-Format: JSON-**Objekt**, `satelliteId → {enabled, mode, from, to, dim}`.
 * Gelesen wird tolerant per `readTree`; einzelne unbrauchbare Einträge (kaputtes
 * `mode`, fehlende Felder) werden mit WARN übersprungen, der Rest lädt.
 *
 * Thread-Sicherheit wie der Listen-Store: [ConcurrentHashMap]-Cache +
 * `@Synchronized` auf [set] (serialisiert Snapshot-Bau + Datei-Write; [get]/[all]
 * bleiben lock-frei).
 */
class JsonFileNightModeStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    private val configs = ConcurrentHashMap<String, NightModeConfig>()

    init {
        loadInitial()
    }

    /** Die Config EINES Geräts — `null`, wenn (noch) nichts gespeichert ist (Aufrufer trägt den Default). */
    fun get(satelliteId: String): NightModeConfig? = configs[satelliteId]

    /** Alle gespeicherten Configs (Snapshot) — die Grundlage für `GET .../night-mode/devices`. */
    fun all(): Map<String, NightModeConfig> = configs.toMap()

    /**
     * Legt [config] für [satelliteId] an/überschreibt sie — **persist-then-commit**:
     * erst die Platte (wirft bei Fehler), dann erst der Cache.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun set(satelliteId: String, config: NightModeConfig): NightModeConfig {
        val desired = HashMap(configs)
        desired[satelliteId] = config
        writeSnapshot(desired)
        configs[satelliteId] = config
        return config
    }

    // ── Persistenz ────────────────────────────────────────────────────────────

    /** Datei einmalig beim Konstruieren lesen. Fehlend ⇒ leer (still); kaputt ⇒ leer + WARN, wirft NIE. */
    private fun loadInitial() {
        if (!Files.exists(path)) return
        try {
            val root = mapper.readTree(path.toFile()) ?: return
            if (!root.isObject) {
                log.warn("Nachtmodus-Datei {} hat unbekannte JSON-Form (kein Objekt) — starte leer.", path)
                return
            }
            root.fields().forEach { (satelliteId, node) -> loadEntry(satelliteId, node) }
        } catch (e: Exception) {
            configs.clear()
            log.warn("Nachtmodus-Datei {} unlesbar — starte leer (Einstellungen gingen verloren): {}", path, e.toString())
        }
    }

    /** Eine Config aus dem JSON laden; unbrauchbar ⇒ WARN + überspringen. */
    private fun loadEntry(satelliteId: String, node: JsonNode) {
        if (satelliteId.isBlank()) {
            log.warn("Überspringe Nachtmodus-Eintrag mit leerer satelliteId in {}.", path)
            return
        }
        val modeRaw = node.get("mode")?.takeIf { it.isTextual }?.textValue()
        val mode = modeRaw?.let { runCatching { NightModeMode.valueOf(it) }.getOrNull() }
        if (mode == null) {
            log.warn("Überspringe unbrauchbaren Nachtmodus-Eintrag {} in {} (mode fehlt/ungültig).", satelliteId, path)
            return
        }
        val enabled = node.get("enabled")?.takeIf { it.isBoolean }?.booleanValue() ?: false
        val from = node.get("from")?.takeIf { it.isTextual }?.textValue() ?: "22:00"
        val to = node.get("to")?.takeIf { it.isTextual }?.textValue() ?: "07:00"
        val dim = node.get("dim")?.takeIf { it.isNumber }?.doubleValue()?.coerceIn(0.0, 1.0) ?: 0.3
        configs[satelliteId] = NightModeConfig(enabled = enabled, mode = mode, from = from, to = to, dim = dim)
    }

    /**
     * Schreibt ALLE Configs als JSON-Objekt: Temp-File im Zielverzeichnis + atomarer
     * Rename. Ein SCHREIB-Fehler ist NICHT schluckbar — er WIRFT, damit die Mutation
     * den Cache nicht fälschlich committet. Aufgeräumt wird best-effort.
     */
    private fun writeSnapshot(active: Map<String, NightModeConfig>) {
        val dir = path.parent ?: throw IOException("Nachtmodus-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val root = LinkedHashMap<String, Any>()
        for ((satelliteId, config) in active.toSortedMap()) {
            val node = LinkedHashMap<String, Any>()
            node["enabled"] = config.enabled
            node["mode"] = config.mode.name
            node["from"] = config.from
            node["to"] = config.to
            node["dim"] = config.dim
            root[satelliteId] = node
        }
        val tmp = Files.createTempFile(dir, ".night-mode", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(root))
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
}
