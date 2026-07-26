package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import java.nio.file.Path

/**
 * Direkter Konstruktor-Test von [BrainAutoSwitchController] (kein Spring-Context
 * — Muster [ExtendedThinkControllerTest]/[BrainSettingsControllerTest]): GET
 * default AUS, PUT persistiert + roundtrip.
 */
class BrainAutoSwitchControllerTest {

    @Test
    fun `GET - Default AUS, solange nie ein PUT kam`(@TempDir dir: Path) {
        val controller = BrainAutoSwitchController(JsonFileBrainAutoSwitchStore(dir.resolve("s.json")))
        assertFalse(controller.get().enabled)
    }

    @Test
    fun `PUT true - persistiert und die GET-Antwort zeigt den neuen Zustand`(@TempDir dir: Path) {
        val store = JsonFileBrainAutoSwitchStore(dir.resolve("s.json"))
        val controller = BrainAutoSwitchController(store)

        val response = controller.set(BrainAutoSwitchRequest(enabled = true))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue((response.body as BrainAutoSwitchView).enabled)
        assertTrue(store.enabled(), "der Store trägt den neuen Wert")
        assertTrue(controller.get().enabled, "GET danach liest denselben Store")
    }

    @Test
    fun `PUT false nach vorherigem true - Roundtrip`(@TempDir dir: Path) {
        val store = JsonFileBrainAutoSwitchStore(dir.resolve("s.json"))
        val controller = BrainAutoSwitchController(store)

        controller.set(BrainAutoSwitchRequest(enabled = true))
        val second = controller.set(BrainAutoSwitchRequest(enabled = false))

        assertEquals(HttpStatus.OK, second.statusCode)
        assertFalse((second.body as BrainAutoSwitchView).enabled)
        assertFalse(store.enabled())
    }

    @Test
    fun `PUT ohne enabled-Feld - faellt konservativ auf AUS`(@TempDir dir: Path) {
        val store = JsonFileBrainAutoSwitchStore(dir.resolve("s.json"))
        store.setEnabled(true)
        val controller = BrainAutoSwitchController(store)

        val response = controller.set(BrainAutoSwitchRequest(enabled = null))

        assertFalse((response.body as BrainAutoSwitchView).enabled)
        assertFalse(store.enabled())
    }

    @Test
    fun `PUT Persist-Fehler - 500 statt fake-200, Cache unangetastet`(@TempDir dir: Path) {
        val store = JsonFileBrainAutoSwitchStore(dir.resolve("s.json"))
        store.setEnabled(true)
        // Zieldatei durch ein VERZEICHNIS ersetzen ⇒ der atomare Rename schlägt fehl.
        java.nio.file.Files.delete(store.path)
        java.nio.file.Files.createDirectories(store.path)
        val controller = BrainAutoSwitchController(store)

        val response = controller.set(BrainAutoSwitchRequest(enabled = false))

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("persist-failed", (response.body as SettingsError).error)
        assertTrue(store.enabled(), "Persist-Fehler darf den Cache NICHT verändern")
    }
}
