package de.hoshi.web

import de.hoshi.core.port.NightModeConfig
import de.hoshi.core.port.NightModeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beweist den [JsonFileNightModeStore] (Nachtmodus-Persistenz über den
 * Backend-Neustart, Scheibe 2 von 3 — EXAKT das [JsonFileListStore]-Vertrauens-
 * Kriterium, pro `satelliteId` statt pro Listen-Item):
 *  - **Restart-Überlebens-Beweis (PFLICHT-Fall):** Store A schreibt Configs
 *    mehrerer Geräte, Store B auf demselben Pfad liest sie byte-genau;
 *  - fehlende/kaputte Datei ⇒ leer starten, KEIN Crash;
 *  - atomare Semantik: keine `.tmp`-Reste; Persist-Fehler ⇒ Mutation wirft und
 *    der Cache bleibt unangetastet (persist-then-commit).
 */
class JsonFileNightModeStoreTest {

    @Test
    fun `Neustart - mehrere Geraete-Configs ueberleben byte-genau (PFLICHT-Fall)`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        val store = JsonFileNightModeStore(file)
        store.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00", dim = 0.2))
        store.set("sat-buero", NightModeConfig(enabled = false, mode = NightModeMode.ALWAYS, from = "20:00", to = "06:00", dim = 0.8))

        val reloaded = JsonFileNightModeStore(file) // „Backend-Neustart"
        assertEquals(
            NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00", dim = 0.2),
            reloaded.get("sat-kueche"),
        )
        assertEquals(
            NightModeConfig(enabled = false, mode = NightModeMode.ALWAYS, from = "20:00", to = "06:00", dim = 0.8),
            reloaded.get("sat-buero"),
        )
        assertEquals(setOf("sat-kueche", "sat-buero"), reloaded.all().keys)
    }

    @Test
    fun `Configs fuer aktuell nicht verbundene Geraete bleiben erhalten`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        val store = JsonFileNightModeStore(file)
        store.set("sat-offline", NightModeConfig(enabled = true))

        // Kein connectedDevices()-Bezug im Store selbst - reines Persistenz-Verhalten:
        // die Config bleibt da, unabhaengig davon, ob das Geraet gerade online ist.
        val reloaded = JsonFileNightModeStore(file)
        assertEquals(NightModeConfig(enabled = true), reloaded.get("sat-offline"))
    }

    @Test
    fun `set ueberschreibt dieselbe satelliteId - auch ueber den Neustart`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        val store = JsonFileNightModeStore(file)
        store.set("sat-a", NightModeConfig(enabled = true, dim = 0.1))
        store.set("sat-a", NightModeConfig(enabled = true, dim = 0.9))

        val reloaded = JsonFileNightModeStore(file)
        assertEquals(0.9, reloaded.get("sat-a")?.dim)
    }

    // ── Robustes Laden: fehlend/kaputt ⇒ leer, NIE crashen ───────────────────

    @Test
    fun `fehlende Datei - leer starten, kein Crash, keine Datei angelegt`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        val store = JsonFileNightModeStore(file)
        assertTrue(store.all().isEmpty())
        assertNull(store.get("irgendwas"))
        assertTrue(!Files.exists(file), "reines Konstruieren schreibt nichts")
    }

    @Test
    fun `kaputte Datei - leer starten, kein Crash, danach voll benutzbar`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        Files.writeString(file, "{ das ist kein gueltiges json ]]")
        val store = JsonFileNightModeStore(file)
        assertTrue(store.all().isEmpty())
        store.set("sat-a", NightModeConfig(enabled = true))
        assertEquals(NightModeConfig(enabled = true), JsonFileNightModeStore(file).get("sat-a"))
    }

    @Test
    fun `unbrauchbare Eintraege werden uebersprungen - kaputtes mode, gueltige bleiben`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        Files.writeString(
            file,
            """{
              "sat-ok": {"enabled":true,"mode":"SCHEDULE","from":"22:00","to":"07:00","dim":0.3},
              "sat-kaputt": {"enabled":true,"mode":"NICHT_EXISTENT","from":"22:00","to":"07:00","dim":0.3},
              "": {"enabled":true,"mode":"SCHEDULE","from":"22:00","to":"07:00","dim":0.3}
            }""",
        )
        val loaded = JsonFileNightModeStore(file)
        assertEquals(setOf("sat-ok"), loaded.all().keys, "nur der gueltige Eintrag laedt")
    }

    @Test
    fun `fehlende Felder im JSON - tolerante Defaults`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        Files.writeString(file, """{"sat-minimal": {"mode":"ALWAYS"}}""")
        val loaded = JsonFileNightModeStore(file).get("sat-minimal")
        assertEquals(false, loaded?.enabled, "enabled-Default false")
        assertEquals("22:00", loaded?.from)
        assertEquals("07:00", loaded?.to)
        assertEquals(0.3, loaded?.dim)
    }

    // ── Atomare Semantik + persist-then-commit ───────────────────────────────

    @Test
    fun `Mutationen hinterlassen keine tmp-Reste, Zieldatei existiert`(@TempDir dir: Path) {
        val file = dir.resolve("night-mode.json")
        val store = JsonFileNightModeStore(file)
        store.set("sat-a", NightModeConfig(enabled = true))
        store.set("sat-b", NightModeConfig(enabled = false))

        assertTrue(Files.exists(store.path))
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        assertEquals(0L, leftovers, "atomarer Rename ⇒ keine .tmp-Reste")
    }

    @Test
    fun `Persist-Fehler - set wirft und committet den Cache NICHT`(@TempDir dir: Path) {
        // Parent des Pfads ist eine REGULAERE DATEI, kein Verzeichnis ⇒
        // Files.createDirectories(...) im Write wirft deterministisch (cross-platform).
        val blocker = Files.createFile(dir.resolve("blocker"))
        val store = JsonFileNightModeStore(blocker.resolve("night-mode.json"))

        assertThrows(IOException::class.java) { store.set("sat-a", NightModeConfig(enabled = true)) }
        assertTrue(store.all().isEmpty(), "Persist-Fehler ⇒ Cache unveraendert (leer)")
    }
}
