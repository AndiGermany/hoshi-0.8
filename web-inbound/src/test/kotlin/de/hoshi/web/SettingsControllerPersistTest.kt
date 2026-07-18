package de.hoshi.web

import de.hoshi.core.skills.SkillId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **SettingsControllerPersistTest** — beweist Fix 2 ohne gebooteten Context: bei OFFENER
 * Decke darf der Controller NUR 200 liefern, wenn der Store-Write WIRKLICH persistiert hat.
 * Schlägt der Persist fehl, ist die Antwort ehrlich 500 (kein fake-200) und der Cache bleibt
 * unangetastet. Reine Konstruktor-Verdrahtung (TIMER-Decke offen über die Ctor-Booleans).
 */
class SettingsControllerPersistTest {

    private fun controller(store: JsonFileSkillStateStore) = SettingsController(
        store = store,
        toolsEnabled = false,
        scenesEnabled = false,
        timerEnabled = true, // TIMER-Decke offen ⇒ der Store-Toggle greift
        calculatorEnabled = false,
    )

    @Test
    fun `Persist-Fehler bei offener Decke ⇒ 500 statt fake-200`(@TempDir dir: Path) {
        // Parent des Settings-Pfads ist eine Datei ⇒ der atomare Write wirft deterministisch.
        val blocker = Files.createFile(dir.resolve("blocker"))
        val store = JsonFileSkillStateStore(blocker.resolve("skills.json"))

        val resp = controller(store).setSkill("TIMER", SkillToggleRequest(enabled = false))

        assertEquals(500, resp.statusCode.value(), "Persist-Fehler ⇒ 5xx, nicht fake-200")
        val body = resp.body
        assertInstanceOf(SettingsError::class.java, body)
        assertEquals("persist-failed", (body as SettingsError).error)
        // Kein fake-grün: der gewollte false ist NICHT in den Cache gerutscht.
        assertTrue(store.isEnabled(SkillId.TIMER, default = true), "Persist-Fehler ⇒ Cache unverändert")
    }

    @Test
    fun `erfolgreicher Persist bei offener Decke ⇒ 200 und Cache committed`(@TempDir dir: Path) {
        val store = JsonFileSkillStateStore(dir.resolve("skills.json"))

        val resp = controller(store).setSkill("TIMER", SkillToggleRequest(enabled = false))

        assertEquals(200, resp.statusCode.value(), "bewiesener Persist ⇒ 200")
        val body = resp.body
        assertInstanceOf(SkillStateView::class.java, body)
        assertEquals(false, (body as SkillStateView).enabled)
        assertEquals(false, store.isEnabled(SkillId.TIMER, default = true), "200 ⇒ Persist + Cache committed")
    }
}
