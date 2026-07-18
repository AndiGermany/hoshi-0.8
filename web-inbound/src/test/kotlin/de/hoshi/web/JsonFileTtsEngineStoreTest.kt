package de.hoshi.web

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFileTtsEngineStoreTest** — der Persistenz-Vertrag des TTS-Engine-
 * Settings (Muster [JsonFileWeatherLocationStoreTest]): nie gesetzt ⇒ `null`
 * (das Boot-Property greift), persist-then-commit, Restart-Beweis (neuer Store
 * liest die Datei des alten — genau der von Andis Auftrag verlangte
 * "Persistenz überlebt Reload"-Test), kaputte Datei wirft nie, Schreib-Fehler
 * wirft ehrlich und lässt den Cache unangetastet. Plus die Stimmen-Erweiterung
 * (Andi-Live-Befund „Stimme folgt der aktiven Engine"): eine gemerkte Stimme JE
 * Engine, additiv (Bestandsdateien ohne `voices`-Feld lesen sich unverändert).
 *
 * **Sprachbewusste Stimmen (Andi-Auftrag 21.07):** eine gemerkte Stimme JE
 * (Engine, Sprache) — DE und EN (und jede andere Sprache) behalten getrennte
 * Wahlen für dieselbe Engine. MIGRATIONS-SAUBER: eine ältere Datei mit dem
 * FLACHEN Legacy-Format (`{"say":"Anna"}`, kein Sprach-Schlüssel) lädt weiter
 * und wird als DE-Wahl interpretiert.
 */
class JsonFileTtsEngineStoreTest {

    @Test
    fun `fehlende Datei - null (Boot-Property greift, byte-neutraler Default)`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        assertNull(store.engineId(), "nie gesetzt ⇒ null ⇒ Boot-Property bleibt die Wahrheit")
        assertNull(store.voiceFor("say"), "nie gesetzt ⇒ null ⇒ Factory-Default bleibt die Wahrheit")
    }

    @Test
    fun `setEngineId persistiert - ein NEUER Store liest die Datei des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        JsonFileTtsEngineStore(path).setEngineId("say")

        val restarted = JsonFileTtsEngineStore(path)
        assertEquals("say", restarted.engineId(), "die Engine-Wahl überlebt den Restart")
    }

    @Test
    fun `kaputte Datei wirft nie - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        Files.writeString(path, "{ kein json ]")
        assertNull(JsonFileTtsEngineStore(path).engineId())
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setEngineId("say")

        // Zieldatei durch ein VERZEICHNIS ersetzen ⇒ der atomare Rename schlägt fehl.
        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setEngineId("piper") }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals("say", store.engineId(), "Cache == letzter bewiesener Platten-Zustand")
    }

    // ── Stimmen je Engine (Andi-Live-Befund: Stimme folgt der aktiven Engine) ──

    @Test
    fun `setVoice persistiert - ein NEUER Store liest die Stimme des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        JsonFileTtsEngineStore(path).setVoice("say", "Anna")

        val restarted = JsonFileTtsEngineStore(path)
        assertEquals("Anna", restarted.voiceFor("say"), "die Stimme überlebt den Restart")
    }

    @Test
    fun `jede Engine behaelt ihre EIGENE Stimme - ein Wechsel say-piper-say verliert nichts`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setVoice("say", "Anna")
        store.setVoice("piper", "de_DE-thorsten-medium")

        assertEquals("Anna", store.voiceFor("say"))
        assertEquals("de_DE-thorsten-medium", store.voiceFor("piper"))
        assertNull(store.voiceFor("openai"), "openai hat nie eine Stimme bekommen")
    }

    @Test
    fun `setEngineId laesst gemerkte Stimmen unangetastet`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setVoice("say", "Anna")
        store.setEngineId("piper")

        assertEquals("piper", store.engineId())
        assertEquals("Anna", store.voiceFor("say"), "ein Engine-Wechsel darf die gemerkte say-Stimme nicht löschen")
    }

    @Test
    fun `Bestandsdatei OHNE voices-Feld liest sich unveraendert - additives Feld, kein Bruch`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        Files.writeString(path, """{"engineId":"piper"}""")

        val store = JsonFileTtsEngineStore(path)
        assertEquals("piper", store.engineId())
        assertNull(store.voiceFor("piper"), "altes Format kennt keine Stimmen ⇒ null, kein Wurf")
    }

    @Test
    fun `Schreib-Fehler bei setVoice wirft ehrlich und der Cache bleibt unangetastet`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setVoice("say", "Anna")

        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setVoice("say", "Allison") }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals("Anna", store.voiceFor("say"), "Cache == letzter bewiesener Platten-Zustand")
    }

    // ── Sprachbewusste Stimmen (Andi-Auftrag 21.07: „TTS soll der Sprache folgen") ──

    @Test
    fun `setVoice mit Sprache - DE und EN behalten getrennte Stimmen derselben Engine`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setVoice("say", Language.DE, "Anna")
        store.setVoice("say", Language.EN, "Samantha")

        assertEquals("Anna", store.voiceFor("say", Language.DE))
        assertEquals("Samantha", store.voiceFor("say", Language.EN))
        assertNull(store.voiceFor("say", Language.FR), "FR hat nie eine eigene Stimme bekommen")
    }

    @Test
    fun `Bequemlichkeits-Overload voiceFor(engineId) - Legacy-Bedeutung EXAKT Language-DE`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setVoice("say", Language.EN, "Samantha")
        assertNull(store.voiceFor("say"), "der 1-Parameter-Overload meint DE, EN wurde nie gesetzt")

        store.setVoice("say", "Anna") // 1-Parameter-Overload == setVoice(engineId, Language.DE, voice)
        assertEquals("Anna", store.voiceFor("say", Language.DE))
        assertEquals("Anna", store.voiceFor("say"))
    }

    @Test
    fun `setVoice mit Sprache persistiert - ein NEUER Store liest beide Sprachen des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        val store = JsonFileTtsEngineStore(path)
        store.setVoice("say", Language.DE, "Anna")
        store.setVoice("say", Language.EN, "Samantha")

        val restarted = JsonFileTtsEngineStore(path)
        assertEquals("Anna", restarted.voiceFor("say", Language.DE))
        assertEquals("Samantha", restarted.voiceFor("say", Language.EN))
    }

    @Test
    fun `MIGRATIONS-SAUBER - Legacy-Datei mit flachem String je Engine laedt weiter und gilt als DE-Wahl`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        Files.writeString(path, """{"engineId":"say","voices":{"say":"Anna"}}""")

        val store = JsonFileTtsEngineStore(path)
        assertEquals("say", store.engineId())
        assertEquals("Anna", store.voiceFor("say", Language.DE), "Legacy-Eintrag OHNE Sprach-Schluessel gilt als DE-Wahl")
        assertEquals("Anna", store.voiceFor("say"), "der Bequemlichkeits-Overload spiegelt dieselbe DE-Wahl")
        assertNull(store.voiceFor("say", Language.EN), "die Legacy-Datei kannte keine EN-Wahl")
    }

    @Test
    fun `MIGRATIONS-SAUBER - nach dem Laden einer Legacy-Datei ueberschreibt ein neuer setVoice nur seine Sprache`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        Files.writeString(path, """{"engineId":"say","voices":{"say":"Anna"}}""")

        val store = JsonFileTtsEngineStore(path)
        store.setVoice("say", Language.EN, "Samantha")

        assertEquals("Anna", store.voiceFor("say", Language.DE), "die migrierte DE-Wahl bleibt unangetastet")
        assertEquals("Samantha", store.voiceFor("say", Language.EN))

        // Beweis, dass der naechste Schreibvorgang die Datei ins NEUE, verschachtelte Format hebt.
        val restarted = JsonFileTtsEngineStore(path)
        assertEquals("Anna", restarted.voiceFor("say", Language.DE))
        assertEquals("Samantha", restarted.voiceFor("say", Language.EN))
    }
}
