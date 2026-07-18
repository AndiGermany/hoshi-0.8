package de.hoshi.web

import de.hoshi.core.pipeline.EscalationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beweist die Sprach-Naht in den Extended-Think-Store: [StoreEscalationModeSwitch]
 * schreibt in DENSELBEN [JsonFileEscalationModeStore] wie der Settings-PUT —
 * der Mode ist danach (1) im Laufzeit-Cache umgeschaltet, (2) als Datei
 * persistiert (Roundtrip über einen FRISCHEN Store) und (3) ein Schreib-Fehler
 * wird ehrlich als `false` gemeldet, nie geworfen.
 */
class StoreEscalationModeSwitchTest {

    @Test
    fun `switchTo schaltet den Mode wirklich um und persistiert ihn`(@TempDir dir: Path) {
        val path = dir.resolve("extended-think.json")
        val store = JsonFileEscalationModeStore(path)
        assertEquals(EscalationMode.RUNTIME_DEFAULT, store.mode(), "Vorbedingung: nie gesetzt ⇒ Default")

        assertTrue(StoreEscalationModeSwitch(store).switchTo(EscalationMode.AUTOMATISCH))

        assertEquals(EscalationMode.AUTOMATISCH, store.mode(), "Laufzeit-Cache ist umgeschaltet (wirkt ab dem nächsten Turn)")
        // Datei-Roundtrip: ein FRISCHER Store (Neustart-Szenario) liest denselben Wert.
        assertEquals(EscalationMode.AUTOMATISCH, JsonFileEscalationModeStore(path).mode(), "der Persist ist die eine Store-Wahrheit des Settings-PUT")
    }

    @Test
    fun `jede Stufe ist per Sprache erreichbar`(@TempDir dir: Path) {
        val store = JsonFileEscalationModeStore(dir.resolve("extended-think.json"))
        val naht = StoreEscalationModeSwitch(store)
        for (mode in EscalationMode.entries) {
            assertTrue(naht.switchTo(mode))
            assertEquals(mode, store.mode())
        }
    }

    @Test
    fun `Schreib-Fehler wird ehrlich als false gemeldet und wirft nie`() {
        // Zielpfad UNTER einer Datei ⇒ createDirectories scheitert ⇒ setMode wirft ⇒ Naht meldet false.
        val blockingFile = Files.createTempFile("extended-think-blocker", ".txt")
        val store = JsonFileEscalationModeStore(blockingFile.resolve("unmoeglich").resolve("extended-think.json"))
        assertFalse(StoreEscalationModeSwitch(store).switchTo(EscalationMode.AUS))
        assertEquals(EscalationMode.RUNTIME_DEFAULT, store.mode(), "persist-then-commit: der Cache bleibt unangetastet")
    }
}
