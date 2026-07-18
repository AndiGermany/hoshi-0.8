package de.hoshi.web

import de.hoshi.core.dto.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **JsonFilePersonaStoreTest** — der Persistenz-Vertrag des Server-seitigen Persona-Settings
 * (Muster [JsonFileWeatherLocationStoreTest]): nie gesetzt ⇒ `null` (die STANDARD-Persona
 * greift ⇒ heutiges Verhalten byte-gleich), persist-then-commit, Restart-Beweis (neuer Store
 * liest die Datei des alten), kaputte/unbekannte Datei wirft nie (⇒ `null`), Schreib-Fehler
 * wirft ehrlich und lässt den Cache unangetastet.
 */
class JsonFilePersonaStoreTest {

    @Test
    fun `fehlende Datei - null (STANDARD-Fallback der Kette, byte-neutraler Default)`(@TempDir dir: Path) {
        val store = JsonFilePersonaStore(dir.resolve("persona.json"))
        assertNull(store.persona(), "nie gesetzt ⇒ null ⇒ die STANDARD-Persona bleibt die Wahrheit")
    }

    @Test
    fun `setPersona persistiert - ein NEUER Store liest die Datei des alten (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("persona.json")
        JsonFilePersonaStore(path).setPersona(Persona.KUMPEL)

        val restarted = JsonFilePersonaStore(path)
        assertEquals(Persona.KUMPEL, restarted.persona(), "die Persona überlebt den Restart")
    }

    @Test
    fun `setPersona akzeptiert jede Persona und ist per Enum-NAME serialisiert`(@TempDir dir: Path) {
        val path = dir.resolve("persona.json")
        for (p in Persona.entries) {
            JsonFilePersonaStore(path).setPersona(p)
            assertEquals(p, JsonFilePersonaStore(path).persona())
        }
        // Das JSON trägt den Enum-NAMEN (RUHIG), nicht den PascalCase-Wire-Code.
        JsonFilePersonaStore(path).setPersona(Persona.RUHIG)
        assertTrue(Files.readString(path).contains("\"persona\":\"RUHIG\""), "Enum-NAME auf der Platte")
    }

    @Test
    fun `kaputte Datei wirft nie - null greift`(@TempDir dir: Path) {
        val path = dir.resolve("persona.json")
        Files.writeString(path, "{ kein json ]")
        assertNull(JsonFilePersonaStore(path).persona())
    }

    @Test
    fun `unbekannter Wert - null greift (kein stilles STANDARD)`(@TempDir dir: Path) {
        val path = dir.resolve("persona.json")
        // Ein veralteter/kaputter Wert MUSS „nie gesetzt" (⇒ null ⇒ STANDARD-Kette) heißen,
        // NICHT „explizit STANDARD gewählt" — deshalb die strikte Parse (nicht Persona.fromCode).
        Files.writeString(path, """{"persona":"KUMPANE"}""")
        assertNull(JsonFilePersonaStore(path).persona())
    }

    @Test
    fun `Wire-Code wird auch gelesen (Kumpel)`(@TempDir dir: Path) {
        val path = dir.resolve("persona.json")
        Files.writeString(path, """{"persona":"Kumpel"}""")
        assertEquals(Persona.KUMPEL, JsonFilePersonaStore(path).persona())
    }

    @Test
    fun `Schreib-Fehler wirft ehrlich und der Cache bleibt unangetastet (persist-then-commit)`(@TempDir dir: Path) {
        val store = JsonFilePersonaStore(dir.resolve("persona.json"))
        store.setPersona(Persona.KUMPEL)

        // Zieldatei durch ein VERZEICHNIS ersetzen ⇒ der atomare Rename schlägt fehl.
        Files.delete(store.path)
        Files.createDirectories(store.path)

        val failed = runCatching { store.setPersona(Persona.RUHIG) }
        assertTrue(failed.isFailure, "Persist-Fehler darf NIE still geschluckt werden")
        assertEquals(Persona.KUMPEL, store.persona(), "Cache == letzter bewiesener Platten-Zustand")
    }
}
