package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.knowledge.WeatherLocation
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileWeatherLocationStore** — der LAUFZEIT-Zustand des Wetter-Ort-Settings,
 * persistiert als kleine JSON-Datei (`~/.hoshi/weather-location.json` bzw.
 * `hoshi.weather-location.path` / `HOSHI_WEATHER_LOCATION_PATH`). ZEILE FÜR ZEILE
 * nach dem [JsonFileEscalationModeStore]-Muster — der einzige Unterschied: eine
 * [WeatherLocation] (label/lat/lon) statt eines Enum-Werts.
 *
 * Pro Turn liest der Ort-Supplier des `WeatherGroundingProvider` billig aus dem
 * [Volatile]-Cache (NIE pro Turn von der Platte). Die Datei wird genau einmal
 * beim Konstruieren gelesen und danach nur bei [setLocation] (Settings-PUT)
 * atomar neu geschrieben.
 *
 * Robust per Doktrin: fehlende/kaputte/unvollständige Datei ⇒ kein gesetzter Wert
 * ⇒ [location] liefert `null` und die Deploy-ENV-Seeds (`hoshi.weather.lat/lon/
 * label`) greifen unverändert — heutiges Verhalten byte-gleich; kein LESE-Fehler
 * wirft je. Ein SCHREIB-Fehler ([setLocation]) ist dagegen NICHT schluckbar: er
 * wirft, der Cache bleibt unangetastet (persist-then-commit) — der Controller
 * liefert ehrlich 5xx statt fake-200.
 */
class JsonFileWeatherLocationStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: WeatherLocation? = null

    init {
        reload()
    }

    /**
     * Laufzeit-Read (billiger Cache-Read, kein Datei-I/O): der gespeicherte Ort,
     * oder `null` wenn nie einer gesetzt wurde (⇒ ENV-Seed greift).
     */
    fun location(): WeatherLocation? = cached

    /**
     * **Atomar setzen — persist-then-commit** (Settings-PUT): ZUERST atomar auf
     * die Platte, DANN — nur bei bewiesenem Persist — der Cache. Schlägt der
     * Schreibvorgang fehl, WIRFT [writeSnapshot] und der Cache bleibt
     * unangetastet (`cache == letzter bewiesener Platten-Zustand`).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setLocation(location: WeatherLocation) {
        writeSnapshot(location)
        cached = location
    }

    /** Datei lesen (best-effort, wirft NIE). Kaputt/fehlend/unvollständig ⇒ kein Cache ⇒ `null`. */
    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            val label = root.path(LABEL_FIELD).asText("")
            val lat = root.path(LAT_FIELD)
            val lon = root.path(LON_FIELD)
            if (label.isBlank() || !lat.isNumber || !lon.isNumber) return
            cached = WeatherLocation(label = label, lat = lat.asDouble(), lon = lon.asDouble())
        }
    }

    /** Temp-File im Zielverzeichnis + atomarer Rename; Schreib-Fehler WIRFT (Temp best-effort geräumt). */
    private fun writeSnapshot(location: WeatherLocation) {
        val dir = path.parent ?: throw IOException("Wetter-Ort-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".weather-location", ".tmp")
        try {
            val payload = mapOf(
                LABEL_FIELD to location.label,
                LAT_FIELD to location.lat,
                LON_FIELD to location.lon,
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
        /** Die drei JSON-Felder: `{"label":"Duisburg","lat":51.43,"lon":6.77}`. */
        const val LABEL_FIELD = "label"
        const val LAT_FIELD = "lat"
        const val LON_FIELD = "lon"
    }
}
