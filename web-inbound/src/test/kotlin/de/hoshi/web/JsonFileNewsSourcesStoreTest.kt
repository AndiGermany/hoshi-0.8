package de.hoshi.web

import de.hoshi.core.port.CurrentAffairsSourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFileNewsSourcesStoreTest** — der Persistenz-Vertrag des Quellen-Settings
 * (Muster [JsonFileWeatherLocationStoreTest]), mit dem EINEN bindenden
 * Unterschied, den dieser Auftrag explizit fordert: nie gesetzt ⇒ `null` (die
 * drei Defaults greifen extern), ein EXPLIZIT gespeichertes leeres Set bleibt
 * dagegen leer und wird NICHT zu `null` — beide Fälle müssen hier bewiesen sein.
 */
class JsonFileNewsSourcesStoreTest {

    private val tagesschauHeise = setOf(CurrentAffairsSourceId.TAGESSCHAU, CurrentAffairsSourceId.HEISE)

    @Test
    fun `fehlende Datei - null (die drei Defaults greifen extern)`(@TempDir dir: Path) {
        val store = JsonFileNewsSourcesStore(dir.resolve("news-sources.json"))
        assertNull(store.activeSources(), "nie gesetzt ⇒ null ⇒ der Aufrufer legt DEFAULT_ACTIVE_SOURCES zugrunde")
    }

    @Test
    fun `setActiveSources mit einem NICHT-leeren Set persistiert und ueberlebt den Restart`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        JsonFileNewsSourcesStore(path).setActiveSources(tagesschauHeise)

        val restarted = JsonFileNewsSourcesStore(path)
        assertEquals(tagesschauHeise, restarted.activeSources(), "das Set ueberlebt den Restart")
    }

    @Test
    fun `explizit leeres Set persistiert - bleibt leer, wird NICHT zu null (bindende Semantik)`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        JsonFileNewsSourcesStore(path).setActiveSources(emptySet())

        val restarted = JsonFileNewsSourcesStore(path)
        assertEquals(emptySet<CurrentAffairsSourceId>(), restarted.activeSources())
        assertTrue(restarted.activeSources() != null, "explizit leer ist NICHT dasselbe wie nie gesetzt")
    }

    @Test
    fun `kaputte Datei wirft nie - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        Files.writeString(path, "{ kein json ]")
        assertNull(JsonFileNewsSourcesStore(path).activeSources())
    }

    @Test
    fun `Datei ohne sources-Feld - null greift (kein Datensatz)`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        Files.writeString(path, """{"anderesFeld":true}""")
        assertNull(JsonFileNewsSourcesStore(path).activeSources())
    }

    @Test
    fun `sources ist kein Array - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        Files.writeString(path, """{"sources":"TAGESSCHAU"}""")
        assertNull(JsonFileNewsSourcesStore(path).activeSources())
    }

    @Test
    fun `unbekannte Quellen-Token im Array werden beim Lesen uebersprungen (best effort)`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        Files.writeString(path, """{"sources":["TAGESSCHAU","NICHT_BEKANNT"]}""")
        assertEquals(setOf(CurrentAffairsSourceId.TAGESSCHAU), JsonFileNewsSourcesStore(path).activeSources())
    }

    @Test
    fun `Array aus NUR unbekannten Token wird zu einem gueltigen leeren Set, nicht zu null`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        Files.writeString(path, """{"sources":["NICHT_BEKANNT"]}""")
        val sources = JsonFileNewsSourcesStore(path).activeSources()
        assertTrue(sources != null && sources.isEmpty(), "ein gueltiges Array bleibt ein gueltiges (leeres) Set")
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFileNewsSourcesStore(dir.resolve("news-sources.json"))
        store.setActiveSources(tagesschauHeise)

        // Zieldatei durch ein VERZEICHNIS ersetzen ⇒ der atomare Rename schlägt fehl.
        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setActiveSources(emptySet()) }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals(tagesschauHeise, store.activeSources(), "Cache == letzter bewiesener Platten-Zustand")
    }
}
