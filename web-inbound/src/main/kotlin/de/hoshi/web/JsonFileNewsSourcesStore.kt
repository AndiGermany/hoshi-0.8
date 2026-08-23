package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.CurrentAffairsSourceId
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileNewsSourcesStore** — der LAUFZEIT-Zustand des aktive-Nachrichten-
 * Quellen-Settings, persistiert als kleine JSON-Datei (`~/.hoshi/news-sources.json`
 * bzw. `hoshi.news-sources.path` / `HOSHI_NEWS_SOURCES_PATH`). ZEILE FÜR ZEILE
 * nach dem [JsonFileWeatherLocationStore]-Muster — der einzige bewusste
 * Unterschied liegt in der Semantik von [activeSources]:
 *  - KEIN Datensatz (Datei fehlt/kaputt/Feld fehlt/kein Array) ⇒ `null` ⇒ der
 *    Aufrufer ([NewsAdapterConfig]/[NewsSourcesSettingsController]) legt die
 *    drei [de.hoshi.adapters.news.MultiSourceCurrentAffairsAdapter.DEFAULT_ACTIVE_SOURCES]
 *    zugrunde.
 *  - EIN explizit gespeichertes Set — auch ein EXPLIZIT LEERES — kommt exakt
 *    so zurück, wie gespeichert: ein leeres Set bedeutet „bewusst keine Quelle
 *    aktiv", nicht „nie gesetzt". Diese Unterscheidung ist bindend (Auftrag).
 *
 * Pro Refresh/Read liest der Adapter-Supplier billig aus dem [Volatile]-Cache
 * (NIE pro Zugriff von der Platte). Die Datei wird genau einmal beim
 * Konstruieren gelesen und danach nur bei [setActiveSources] (Settings-PUT)
 * atomar neu geschrieben.
 *
 * Robust per Doktrin: eine kaputte/unvollständige Datei wirft NIE beim Lesen —
 * einzelne unbekannte Quellen-Token in einem sonst gültigen Array werden
 * übersprungen (best effort), ein Array das NUR unbekannte Token enthält wird
 * so zu einem gültigen, aber explizit leeren Set (nicht zu `null`). Ein
 * SCHREIB-Fehler ([setActiveSources]) ist dagegen NICHT schluckbar: er wirft,
 * der Cache bleibt unangetastet (persist-then-commit) — der Controller liefert
 * ehrlich 5xx statt fake-200.
 */
class JsonFileNewsSourcesStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: Set<CurrentAffairsSourceId>? = null

    init {
        reload()
    }

    /**
     * Laufzeit-Read (billiger Cache-Read, kein Datei-I/O): das gespeicherte
     * Set, oder `null` wenn nie eines explizit gesetzt wurde (⇒ die drei
     * Defaults greifen extern). Ein zurückgegebenes leeres Set ist bewusst
     * leer, kein „unset".
     */
    fun activeSources(): Set<CurrentAffairsSourceId>? = cached

    /**
     * **Atomar setzen — persist-then-commit** (Settings-PUT): ZUERST atomar auf
     * die Platte, DANN — nur bei bewiesenem Persist — der Cache. Schlägt der
     * Schreibvorgang fehl, WIRFT [writeSnapshot] und der Cache bleibt
     * unangetastet (`cache == letzter bewiesener Platten-Zustand`).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setActiveSources(sources: Set<CurrentAffairsSourceId>) {
        writeSnapshot(sources)
        cached = sources
    }

    /** Datei lesen (best-effort, wirft NIE). Kaputt/fehlend/kein Array-Feld ⇒ kein Cache ⇒ `null`. */
    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            val node = root.path(SOURCES_FIELD)
            if (!node.isArray) return
            cached = node.mapNotNull { entry ->
                runCatching { CurrentAffairsSourceId.valueOf(entry.asText("")) }.getOrNull()
            }.toSet()
        }
    }

    /** Temp-File im Zielverzeichnis + atomarer Rename; Schreib-Fehler WIRFT (Temp best-effort geräumt). */
    private fun writeSnapshot(sources: Set<CurrentAffairsSourceId>) {
        val dir = path.parent ?: throw IOException("Quellen-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".news-sources", ".tmp")
        try {
            val payload = mapOf(
                SOURCES_FIELD to sources.sortedBy(CurrentAffairsSourceId::ordinal).map { it.name },
            )
            Files.write(tmp, mapper.writeValueAsBytes(payload))
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
        /** Das eine JSON-Feld: `{"sources":["TAGESSCHAU","HEISE"]}` (leeres Array ⇒ explizit leer). */
        const val SOURCES_FIELD = "sources"
    }
}
