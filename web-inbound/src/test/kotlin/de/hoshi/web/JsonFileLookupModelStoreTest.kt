package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFileLookupModelStoreTest** — der Persistenz-Vertrag des Lookup-Modell-
 * Settings (Muster [JsonFileTtsEngineStoreTest]/[JsonFileWeatherLocationStoreTest]).
 */
class JsonFileLookupModelStoreTest {

    @Test
    fun `fehlende Datei - null (Boot-Property greift, byte-neutraler Default)`(@TempDir dir: Path) {
        val store = JsonFileLookupModelStore(dir.resolve("lookup-model.json"))
        assertNull(store.modelId(), "nie gesetzt ⇒ null ⇒ Boot-Property bleibt die Wahrheit")
    }

    @Test
    fun `setModelId persistiert - ein NEUER Store liest die Datei des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("lookup-model.json")
        JsonFileLookupModelStore(path).setModelId("gpt-5.4-mini")

        val restarted = JsonFileLookupModelStore(path)
        assertEquals("gpt-5.4-mini", restarted.modelId(), "die Modell-Wahl überlebt den Restart")
    }

    @Test
    fun `kaputte Datei wirft nie - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("lookup-model.json")
        Files.writeString(path, "{ kein json ]")
        assertNull(JsonFileLookupModelStore(path).modelId())
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFileLookupModelStore(dir.resolve("lookup-model.json"))
        store.setModelId("gpt-5.4-mini")

        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setModelId("gpt-5.6-sol") }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals("gpt-5.4-mini", store.modelId(), "Cache == letzter bewiesener Platten-Zustand")
    }
}
