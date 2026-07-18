package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFileBrainModelStoreTest** — der Persistenz-Vertrag des GEWÄHLTEN Brain-Modells
 * (Muster [JsonFileLookupModelStoreTest]/[JsonFileTtsEngineStoreTest]).
 */
class JsonFileBrainModelStoreTest {

    @Test
    fun `fehlende Datei - null (Boot-Default greift, nie umgeschaltet)`(@TempDir dir: Path) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json"))
        assertNull(store.selectedRepo(), "nie gesetzt ⇒ null ⇒ Boot-Default bleibt die Wahrheit")
    }

    @Test
    fun `setSelectedRepo persistiert - ein NEUER Store liest die Datei des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("brain-model.json")
        JsonFileBrainModelStore(path).setSelectedRepo("mlx-community/gemma-4-e4b-it-4bit")

        val restarted = JsonFileBrainModelStore(path)
        assertEquals("mlx-community/gemma-4-e4b-it-4bit", restarted.selectedRepo(), "die Modell-Wahl überlebt den Restart")
    }

    @Test
    fun `kaputte Datei wirft nie - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("brain-model.json")
        Files.writeString(path, "{ kein json ]")
        assertNull(JsonFileBrainModelStore(path).selectedRepo())
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json"))
        store.setSelectedRepo("mlx-community/gemma-4-e2b-it-4bit")

        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setSelectedRepo("mlx-community/gemma-4-e4b-it-4bit") }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals("mlx-community/gemma-4-e2b-it-4bit", store.selectedRepo(), "Cache == letzter bewiesener Platten-Zustand")
    }
}
