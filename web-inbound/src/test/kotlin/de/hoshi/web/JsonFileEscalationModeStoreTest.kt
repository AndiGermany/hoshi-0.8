package de.hoshi.web

import de.hoshi.core.pipeline.EscalationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFileEscalationModeStoreTest** — der Persistenz-Vertrag des Drei-Stufen-
 * Settings (Muster [JsonFileSkillStateStoreTest]): Laufzeit-Default ERST_FRAGEN
 * (Entscheid #3), persist-then-commit, Restart-Beweis (neuer Store liest die
 * Datei des alten), kaputte Datei wirft nie, Schreib-Fehler wirft ehrlich und
 * lässt den Cache unangetastet.
 */
class JsonFileEscalationModeStoreTest {

    @Test
    fun `fehlende Datei - Laufzeit-Default ERST_FRAGEN (Entscheid Nr 3)`(@TempDir dir: Path) {
        val store = JsonFileEscalationModeStore(dir.resolve("extended-think.json"))
        assertEquals(EscalationMode.ERST_FRAGEN, store.mode(), "Decke offen ⇒ Default ist ERST fragen, nie still Automatisch")
    }

    @Test
    fun `setMode persistiert - ein NEUER Store liest die Datei des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("extended-think.json")
        JsonFileEscalationModeStore(path).setMode(EscalationMode.AUTOMATISCH)

        val restarted = JsonFileEscalationModeStore(path)
        assertEquals(EscalationMode.AUTOMATISCH, restarted.mode(), "der Mode überlebt den Restart")
    }

    @Test
    fun `kaputte Datei wirft nie - Default greift`(@TempDir dir: Path) {
        val path = dir.resolve("extended-think.json")
        Files.writeString(path, "{ kein json ]")
        assertEquals(EscalationMode.ERST_FRAGEN, JsonFileEscalationModeStore(path).mode())
    }

    @Test
    fun `unbekannter Mode-Wert in der Datei - Default greift`(@TempDir dir: Path) {
        val path = dir.resolve("extended-think.json")
        Files.writeString(path, """{"mode":"TURBO"}""")
        assertEquals(EscalationMode.ERST_FRAGEN, JsonFileEscalationModeStore(path).mode())
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFileEscalationModeStore(dir.resolve("extended-think.json"))
        store.setMode(EscalationMode.AUS)

        // Zieldatei durch ein VERZEICHNIS ersetzen ⇒ der atomare Rename schlägt fehl.
        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setMode(EscalationMode.AUTOMATISCH) }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals(EscalationMode.AUS, store.mode(), "Cache == letzter bewiesener Platten-Zustand")
    }
}
