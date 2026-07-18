package de.hoshi.web

import de.hoshi.core.skills.SkillId
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.core.skills.SkillTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beweist den Laufzeit-Store (S2.2) und die Zwei-Stufen-AND-Logik:
 *  - [JsonFileSkillStateStore]: fehlende/kaputte Datei ⇒ Default; set ⇒ persistent;
 *    atomarer Temp-File-Write hinterlässt keine `.tmp`-Reste.
 *  - [CeilingAndStoreSkillState]: Decke zu ⇒ hart aus; Decke offen ⇒ Store entscheidet;
 *    leerer Store ⇒ runtimeDefault.
 *  - Byte-Neutralität: ohne `skills.json` + runtimeDefault=ON ⇒ effektiv == `ofStatic`.
 */
class JsonFileSkillStateStoreTest {

    // ── Store ────────────────────────────────────────────────────────────────

    @Test
    fun `fehlende Datei ⇒ Default greift`(@TempDir dir: Path) {
        val store = JsonFileSkillStateStore(dir.resolve("skills.json"))
        assertTrue(store.isEnabled(SkillId.TIMER, default = true), "unbekannt ⇒ default true")
        assertFalse(store.isEnabled(SkillId.TIMER, default = false), "unbekannt ⇒ default false")
        assertTrue(store.all().isEmpty(), "nichts gesetzt ⇒ leerer Snapshot")
    }

    @Test
    fun `nackter SkillStatePort-Read ist ON (lokale Default-Parität)`(@TempDir dir: Path) {
        val store: SkillStatePort = JsonFileSkillStateStore(dir.resolve("skills.json"))
        for (id in SkillId.entries) assertTrue(store.isEnabled(id), "unbekannt ⇒ ON: $id")
    }

    @Test
    fun `set ⇒ get persistent über eine neue Store-Instanz`(@TempDir dir: Path) {
        val file = dir.resolve("skills.json")
        JsonFileSkillStateStore(file).setEnabled(SkillId.TIMER, false)

        val reloaded = JsonFileSkillStateStore(file)
        assertFalse(reloaded.isEnabled(SkillId.TIMER, default = true), "persistierter false schlägt default true")
        assertEquals(mapOf(SkillId.TIMER to false), reloaded.all())
    }

    @Test
    fun `set mehrere ⇒ alle persistent, andere bleiben Default`(@TempDir dir: Path) {
        val file = dir.resolve("skills.json")
        val store = JsonFileSkillStateStore(file)
        store.setEnabled(SkillId.TIMER, false)
        store.setEnabled(SkillId.CALCULATOR, true)

        val reloaded = JsonFileSkillStateStore(file)
        assertFalse(reloaded.isEnabled(SkillId.TIMER, default = true))
        assertTrue(reloaded.isEnabled(SkillId.CALCULATOR, default = false))
        // nicht gesetzt ⇒ Default
        assertTrue(reloaded.isEnabled(SkillId.SMART_HOME, default = true))
        assertFalse(reloaded.isEnabled(SkillId.SMART_HOME, default = false))
    }

    @Test
    fun `kaputte Datei ⇒ robust, leerer Store, Default greift`(@TempDir dir: Path) {
        val file = dir.resolve("skills.json")
        Files.writeString(file, "{ das ist kein gueltiges json ]]")

        val store = JsonFileSkillStateStore(file)
        assertTrue(store.all().isEmpty(), "kaputt ⇒ leerer Store")
        assertTrue(store.isEnabled(SkillId.TIMER, default = true))
        assertFalse(store.isEnabled(SkillId.TIMER, default = false))
    }

    @Test
    fun `setEnabled hinterlaesst keine tmp-Reste, Zieldatei existiert`(@TempDir dir: Path) {
        val file = dir.resolve("skills.json")
        val store = JsonFileSkillStateStore(file)
        store.setEnabled(SkillId.TIMER, true)
        store.setEnabled(SkillId.CALCULATOR, false)

        assertTrue(Files.exists(store.path), "Zieldatei muss nach setEnabled existieren")
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        assertEquals(0L, leftovers, "atomarer Rename ⇒ keine .tmp-Reste")
    }

    // ── Persist-then-commit: Schreib-Fehler ist EHRLICH (kein fake-grün) ──────

    @Test
    fun `Persist-Fehler ⇒ setEnabled wirft und committet den Cache NICHT`(@TempDir dir: Path) {
        // Parent des Settings-Pfads ist eine REGULÄRE DATEI, kein Verzeichnis ⇒
        // Files.createDirectories(...) im Write wirft deterministisch (cross-platform).
        val blocker = Files.createFile(dir.resolve("blocker"))
        val store = JsonFileSkillStateStore(blocker.resolve("skills.json"))

        assertThrows(IOException::class.java) {
            store.setEnabled(SkillId.TIMER, false)
        }
        // Kein fake-grün: der gewollte false ist NICHT in den Cache gerutscht.
        assertTrue(store.isEnabled(SkillId.TIMER, default = true), "Persist-Fehler ⇒ false NICHT committed")
        assertFalse(store.isEnabled(SkillId.TIMER, default = false), "Cache hat keinen Eintrag ⇒ Default greift")
        assertTrue(store.all().isEmpty(), "Persist-Fehler ⇒ Cache unverändert (leer)")
    }

    @Test
    fun `erfolgreicher Persist ⇒ Cache committed und Datei geschrieben`(@TempDir dir: Path) {
        val file = dir.resolve("skills.json")
        val store = JsonFileSkillStateStore(file)
        store.setEnabled(SkillId.TIMER, false) // darf NICHT werfen
        assertFalse(store.isEnabled(SkillId.TIMER, default = true), "bewiesener Persist ⇒ Cache committed")
        assertTrue(Files.exists(file), "bewiesener Persist ⇒ Zieldatei existiert")
    }

    // ── Tier-abhängiger Fail-closed Default (Tom) ─────────────────────────────

    @Test
    fun `tier-Default - LOCAL opt-out true, EGRESS und CLOUD fail-closed false`() {
        assertTrue(SkillTier.LOCAL.defaultEnabled, "LOCAL ⇒ opt-out (Default AN, byte-neutral)")
        assertFalse(SkillTier.EGRESS.defaultEnabled, "EGRESS ⇒ fail-closed (Default AUS)")
        assertFalse(SkillTier.CLOUD.defaultEnabled, "CLOUD ⇒ fail-closed (Default AUS)")
    }

    @Test
    fun `nackter Read - heutige LOCAL-Skills ON, simulierter EGRESS fail-closed OFF`(@TempDir dir: Path) {
        val store = JsonFileSkillStateStore(dir.resolve("skills.json")) // leer
        // Heute trägt jeder Skill LOCAL ⇒ nackter Port-Read bleibt ON (byte-identisch).
        for (id in SkillId.entries) assertTrue(store.isEnabled(id), "LOCAL unbekannt ⇒ ON: $id")
        // Derselbe leere Store, gelesen mit dem EGRESS-Default ⇒ fail-closed OFF
        // (so verhielte sich der erste Egress-Skill, sobald er dazukommt).
        val egressDefault = SkillTier.EGRESS.defaultEnabled
        for (id in SkillId.entries) assertFalse(
            store.isEnabled(id, default = egressDefault),
            "simulierter EGRESS unbekannt ⇒ fail-closed OFF: $id",
        )
    }

    // ── Zwei-Stufen-AND (CeilingAndStoreSkillState) ───────────────────────────

    @Test
    fun `Decke zu ⇒ hart aus, egal was der Store sagt`(@TempDir dir: Path) {
        val store = JsonFileSkillStateStore(dir.resolve("skills.json"))
        store.setEnabled(SkillId.TIMER, true) // Store will ON
        val effective = CeilingAndStoreSkillState(SkillStatePort.NONE, store) { true }
        assertFalse(effective.isEnabled(SkillId.TIMER), "Decke zu ⇒ false trotz Store-ON")
    }

    @Test
    fun `Decke offen ⇒ Store entscheidet`(@TempDir dir: Path) {
        val file = dir.resolve("skills.json")
        val ceilingOpen = SkillStatePort.ofStatic(smartHome = false, scenes = false, timer = true, calculator = false)

        val storeOff = JsonFileSkillStateStore(file).also { it.setEnabled(SkillId.TIMER, false) }
        assertFalse(
            CeilingAndStoreSkillState(ceilingOpen, storeOff) { true }.isEnabled(SkillId.TIMER),
            "Decke offen + Store-OFF ⇒ false",
        )

        val storeOn = JsonFileSkillStateStore(file).also { it.setEnabled(SkillId.TIMER, true) }
        assertTrue(
            CeilingAndStoreSkillState(ceilingOpen, storeOn) { true }.isEnabled(SkillId.TIMER),
            "Decke offen + Store-ON ⇒ true",
        )
    }

    @Test
    fun `Decke offen + leerer Store ⇒ runtimeDefault entscheidet`(@TempDir dir: Path) {
        val ceilingOpen = SkillStatePort.ofStatic(smartHome = false, scenes = false, timer = true, calculator = false)
        val emptyStore = JsonFileSkillStateStore(dir.resolve("skills.json"))

        assertTrue(
            CeilingAndStoreSkillState(ceilingOpen, emptyStore) { true }.isEnabled(SkillId.TIMER),
            "leerer Store + runtimeDefault ON ⇒ true",
        )
        assertFalse(
            CeilingAndStoreSkillState(ceilingOpen, emptyStore) { false }.isEnabled(SkillId.TIMER),
            "leerer Store + runtimeDefault OFF ⇒ false (lokal-first für Egress-Skills)",
        )
    }

    // ── Byte-Neutralität ──────────────────────────────────────────────────────

    @Test
    fun `byte-neutral - ohne json + runtimeDefault ON ⇒ effektiv identisch zu ofStatic`(@TempDir dir: Path) {
        // Matrix aller Decken-Belegungen, exakt wie der heutige intentClassifier sie faltet.
        val flags = listOf(true, false)
        for (tools in flags) for (scenes in flags) for (timer in flags) for (calc in flags) {
            val ofStatic = SkillStatePort.ofStatic(
                smartHome = tools,
                scenes = tools && scenes,
                timer = timer,
                calculator = calc,
            )
            // Frische, leere Store-Datei (kein skills.json) ⇒ Store inert.
            val store = JsonFileSkillStateStore(dir.resolve("matrix-$tools-$scenes-$timer-$calc.json"))
            val effective = CeilingAndStoreSkillState(ofStatic, store) { true }
            for (id in SkillId.entries) {
                assertEquals(
                    ofStatic.isEnabled(id), effective.isEnabled(id),
                    "byte-neutral: $id bei tools=$tools scenes=$scenes timer=$timer calc=$calc",
                )
            }
        }
    }
}
