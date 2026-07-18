package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.skills.SkillId
import de.hoshi.core.skills.SkillRegistry
import de.hoshi.core.skills.SkillStatePort
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * **JsonFileSkillStateStore** — der LAUFZEIT-Zustand der Skills (S2.2), persistiert als
 * kleine JSON-Datei (`~/.hoshi/skills.json` bzw. `HOSHI_SETTINGS_PATH`). Implementiert
 * den [SkillStatePort]: pro Turn liest der Classifier billig aus einem
 * [ConcurrentHashMap]-Cache (NIE pro-Turn von der Platte). Die Datei wird genau
 * einmal beim Konstruieren gelesen und danach nur noch bei [setEnabled] (Settings-PUT,
 * S2.3) atomar neu geschrieben.
 *
 * Bewusst JSON statt sqlite (es sind eine Handvoll Booleans, exakt das `secrets.json`-
 * Muster, das [PipelineConfig] schon nutzt). Robust per Doktrin: fehlende oder kaputte
 * Datei ⇒ leerer Store ⇒ die per-Skill Defaults greifen; kein LESE-Fehler wirft je. Ein
 * SCHREIB-Fehler ([setEnabled]) ist dagegen NICHT schluckbar: er wirft, der Cache bleibt
 * unangetastet (persist-then-commit) — sonst läge der Laufzeit-Zustand über die Persistenz.
 *
 * Cache-Semantik ist drei-wertig: ein Eintrag ist gesetzt (true/false) ODER abwesend.
 * Abwesend ⇒ [isEnabled] gibt den mitgegebenen Default zurück — so trägt der AUFRUFER
 * (die Zwei-Stufen-Decke) den `runtimeDefault`, nicht der Store.
 */
class JsonFileSkillStateStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) : SkillStatePort {

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    private val cache = ConcurrentHashMap<SkillId, Boolean>()

    init {
        reload()
    }

    /**
     * **Laufzeit-Read mit explizitem Default** (billiger Cache-Read, kein Datei-I/O). Gibt
     * den gespeicherten Zustand zurück; ist für diesen Skill nichts gespeichert, den
     * mitgegebenen [default]. So bestimmt die Decke pro Skill ihren `runtimeDefault`.
     */
    fun isEnabled(id: SkillId, default: Boolean): Boolean = cache[id] ?: default

    /**
     * [SkillStatePort]-Vertrag: nackter Read. Unbekannt ⇒ der **tier-abhängige** Default
     * (Tom, [SkillRegistry.defaultEnabledFor]): LOCAL ⇒ ON (heutige Byte-Parität), aber
     * EGRESS/CLOUD ⇒ fail-closed OFF — ein Egress-Skill ist nie still an, bevor ihn jemand
     * setzt. Die Decke nutzt für die feinere Logik die explizite Overload [isEnabled] mit
     * `runtimeDefault` (das ebenfalls den Tier-Default trägt).
     */
    override fun isEnabled(id: SkillId): Boolean =
        isEnabled(id, default = SkillRegistry.defaultEnabledFor(id))

    /**
     * Alle EXPLIZIT gesetzten Zustände (Snapshot, für die spätere Settings-API). Nicht
     * gesetzte Skills fehlen hier bewusst — ihr Effektiv-Wert kommt aus dem Default der
     * Decke, nicht aus dem Store.
     */
    fun all(): Map<SkillId, Boolean> = cache.toMap()

    /**
     * **Atomar setzen — persist-then-commit** (Settings-PUT, S2.3): ZUERST die GEWÜNSCHTE
     * Sicht (aktueller Cache + neuer Eintrag) atomar auf die Platte schreiben, DANN — und nur
     * bei bewiesenem Persist — den Cache committen. Schlägt der Schreibvorgang fehl, WIRFT
     * [writeSnapshot] und der Cache bleibt **unangetastet** (kein „fake-grün": der Laufzeit-
     * Cache spiegelt genau das, was wirklich persistiert wurde — die Invariante
     * `cache == letzter bewiesener Platten-Zustand` hält). Der Aufrufer (Settings-Controller)
     * muss den Fehler ehrlich nach aussen reichen (5xx), nicht still als 200 quittieren.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setEnabled(id: SkillId, enabled: Boolean) {
        val desired = LinkedHashMap<SkillId, Boolean>()
        for (entry in SkillId.entries) cache[entry]?.let { desired[entry] = it }
        desired[id] = enabled
        // Erst Platte (wirft bei Fehler) …
        writeSnapshot(desired)
        // … dann erst der Cache — nur bei bewiesenem Persist.
        cache[id] = enabled
    }

    /** Datei lesen (best-effort, wirft NIE). Kaputt/fehlend ⇒ leerer Cache ⇒ Defaults. */
    private fun reload() {
        cache.clear()
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            for (id in SkillId.entries) {
                val node = root.get(id.name) ?: continue
                if (node.isBoolean) cache[id] = node.booleanValue()
            }
        }
    }

    /**
     * Schreibt den übergebenen [snapshot] als Temp-File im Zielverzeichnis + atomarer Rename.
     * Anders als ein Lesefehler ist ein SCHREIB-Fehler NICHT schluckbar — er WIRFT, damit
     * [setEnabled] den Cache nicht fälschlich committet und der Controller ehrlich 5xx liefert.
     * Aufgeräumt wird best-effort (Temp-Rest gelöscht), der ursprüngliche Fehler fliegt weiter.
     */
    private fun writeSnapshot(snapshot: Map<SkillId, Boolean>) {
        val dir = path.parent ?: throw IOException("Settings-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val wire = LinkedHashMap<String, Boolean>()
        for (id in SkillId.entries) snapshot[id]?.let { wire[id.name] = it }
        val tmp = Files.createTempFile(dir, ".skills", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(wire))
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

/**
 * **CeilingAndStoreSkillState** — die EINE Zwei-Stufen-Toggle-Wahrheit (S2.2):
 * `effektiv(id) = Decke(id) AND store(id, runtimeDefault(id))`.
 *
 *  - Die [ceiling] ist die Deploy-Zeit-DECKE (die heutigen ENV-Flags pro Skill). Ist sie
 *    für einen Skill ZU, ist der Skill hart aus — ein Store-Toggle bleibt wirkungslos
 *    (das bewahrt das Egress-Deploy-Gate).
 *  - Ist die Decke OFFEN, entscheidet der [store] zur LAUFZEIT (ohne Redeploy). Ist im
 *    Store nichts gesetzt, greift [runtimeDefault] — lokale Skills = ON, d.h. ohne
 *    `skills.json` ist `effektiv == Decke` ⇒ byte-neutral zum heutigen `ofStatic(...)`.
 */
class CeilingAndStoreSkillState(
    private val ceiling: SkillStatePort,
    private val store: JsonFileSkillStateStore,
    // Default = der tier-abhängige Fail-closed-Default (Tom): LOCAL ⇒ ON (byte-neutral),
    // EGRESS/CLOUD ⇒ OFF. Wer eine andere Politik will, reicht ein eigenes Lambda.
    private val runtimeDefault: (SkillId) -> Boolean = { id -> SkillRegistry.defaultEnabledFor(id) },
) : SkillStatePort {
    override fun isEnabled(id: SkillId): Boolean =
        ceiling.isEnabled(id) && store.isEnabled(id, runtimeDefault(id))
}
