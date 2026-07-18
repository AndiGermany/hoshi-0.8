package de.hoshi.web

import de.hoshi.adapters.knowledge.WeatherLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFileWeatherLocationStoreTest** — der Persistenz-Vertrag des Wetter-Ort-
 * Settings (Muster [JsonFileEscalationModeStoreTest]): nie gesetzt ⇒ `null`
 * (die ENV-Seeds greifen ⇒ heutiges Verhalten byte-gleich), persist-then-commit,
 * Restart-Beweis (neuer Store liest die Datei des alten), kaputte Datei wirft
 * nie, Schreib-Fehler wirft ehrlich und lässt den Cache unangetastet.
 */
class JsonFileWeatherLocationStoreTest {

    private val duisburg = WeatherLocation("Duisburg", 51.43247, 6.76516)

    @Test
    fun `fehlende Datei - null (ENV-Seed greift, byte-neutraler Default)`(@TempDir dir: Path) {
        val store = JsonFileWeatherLocationStore(dir.resolve("weather-location.json"))
        assertNull(store.location(), "nie gesetzt ⇒ null ⇒ Deploy-Seed bleibt die Wahrheit")
    }

    @Test
    fun `setLocation persistiert - ein NEUER Store liest die Datei des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("weather-location.json")
        JsonFileWeatherLocationStore(path).setLocation(duisburg)

        val restarted = JsonFileWeatherLocationStore(path)
        assertEquals(duisburg, restarted.location(), "der Ort überlebt den Restart")
    }

    @Test
    fun `kaputte Datei wirft nie - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("weather-location.json")
        Files.writeString(path, "{ kein json ]")
        assertNull(JsonFileWeatherLocationStore(path).location())
    }

    @Test
    fun `unvollstaendige Datei (lat fehlt) - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("weather-location.json")
        Files.writeString(path, """{"label":"Duisburg","lon":6.76}""")
        assertNull(JsonFileWeatherLocationStore(path).location())
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFileWeatherLocationStore(dir.resolve("weather-location.json"))
        store.setLocation(duisburg)

        // Zieldatei durch ein VERZEICHNIS ersetzen ⇒ der atomare Rename schlägt fehl.
        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setLocation(WeatherLocation("Berlin", 52.52, 13.41)) }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals(duisburg, store.location(), "Cache == letzter bewiesener Platten-Zustand")
    }
}
