package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFileBrainAutoSwitchStoreTest** — der Persistenz-Vertrag des
 * `brainAutoSwitch`-Settings (Muster [JsonFileEscalationModeStoreTest]):
 * Default AUS, persist-then-commit, Restart-Beweis, kaputte Datei wirft nie,
 * Schreib-Fehler wirft ehrlich und lässt den Cache unangetastet.
 */
class JsonFileBrainAutoSwitchStoreTest {

    @Test
    fun `fehlende Datei - Default AUS (byte-neutral)`(@TempDir dir: Path) {
        val store = JsonFileBrainAutoSwitchStore(dir.resolve("brain-auto-switch.json"))
        assertFalse(store.enabled(), "kein je gesetzter Wert ⇒ AUS")
    }

    @Test
    fun `setEnabled persistiert - ein NEUER Store liest die Datei des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("brain-auto-switch.json")
        JsonFileBrainAutoSwitchStore(path).setEnabled(true)

        val restarted = JsonFileBrainAutoSwitchStore(path)
        assertTrue(restarted.enabled(), "der Wert überlebt den Restart")
    }

    @Test
    fun `Roundtrip AN dann AUS - der zuletzt geschriebene Wert gewinnt`(@TempDir dir: Path) {
        val path = dir.resolve("brain-auto-switch.json")
        val store = JsonFileBrainAutoSwitchStore(path)
        store.setEnabled(true)
        assertTrue(store.enabled())
        store.setEnabled(false)
        assertFalse(store.enabled())
        assertFalse(JsonFileBrainAutoSwitchStore(path).enabled(), "auch nach Restart bleibt AUS")
    }

    @Test
    fun `kaputte Datei wirft nie - Default AUS greift`(@TempDir dir: Path) {
        val path = dir.resolve("brain-auto-switch.json")
        Files.writeString(path, "{ kein json ]")
        assertFalse(JsonFileBrainAutoSwitchStore(path).enabled())
    }

    @Test
    fun `unbrauchbarer Wert in der Datei - Default AUS greift`(@TempDir dir: Path) {
        val path = dir.resolve("brain-auto-switch.json")
        Files.writeString(path, """{"enabled":"ja bitte"}""") // kein Boolean
        assertFalse(JsonFileBrainAutoSwitchStore(path).enabled())
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFileBrainAutoSwitchStore(dir.resolve("brain-auto-switch.json"))
        store.setEnabled(true)

        // Zieldatei durch ein VERZEICHNIS ersetzen ⇒ der atomare Rename schlägt fehl.
        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setEnabled(false) }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals(true, store.enabled(), "Cache == letzter bewiesener Platten-Zustand")
    }
}
