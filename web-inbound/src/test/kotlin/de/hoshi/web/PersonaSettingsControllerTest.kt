package de.hoshi.web

import de.hoshi.core.dto.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.ResponseEntity
import java.nio.file.Files
import java.nio.file.Path

/**
 * **PersonaSettingsControllerTest** — der Settings-Vertrag des Server-seitigen Persona-Rands,
 * OHNE Spring-Context: der Controller wird direkt konstruiert (die `@Value`-Params sind
 * schlichte Ctor-Args, Muster [WeatherLocationControllerTest]/[ExtendedThinkControllerTest]).
 *
 * Vertrag: GET ohne Store ⇒ STANDARD/fromStore=false; PUT bekannte Persona ⇒ 200 + Store
 * persistiert (Restart-Beweis); unbekannt ⇒ 400 (KEIN Store-Write); Persist-Fehler ⇒ 500.
 * Die Perimeter-Wand (401 ohne Token) deckt [PerimeterWallTest] für ALLE `/api/v1`-Pfade.
 */
class PersonaSettingsControllerTest {

    private fun controller(store: JsonFilePersonaStore, personaEnabled: Boolean = true) =
        PersonaSettingsController(store = store, personaEnabled = personaEnabled)

    private fun put(c: PersonaSettingsController, persona: String?): ResponseEntity<Any> =
        c.setPersona(PersonaSettingRequest(persona))

    @Test
    fun `GET ohne gespeicherte Persona - STANDARD, fromStore false`(@TempDir dir: Path) {
        val c = controller(JsonFilePersonaStore(dir.resolve("p.json")))
        val view = c.persona()
        assertEquals("STANDARD", view.persona)
        assertFalse(view.fromStore)
        assertTrue(view.personaEnabled)
    }

    @Test
    fun `PUT bekannte Persona - 200, Store persistiert (Restart-Beweis)`(@TempDir dir: Path) {
        val path = dir.resolve("p.json")
        val store = JsonFilePersonaStore(path)
        val res = put(controller(store), "KUMPEL")

        assertEquals(200, res.statusCode.value())
        val view = res.body as PersonaSettingView
        assertEquals("KUMPEL", view.persona)
        assertTrue(view.fromStore)
        // Restart-Beweis: ein NEUER Store liest die persistierte Persona.
        assertEquals(Persona.KUMPEL, JsonFilePersonaStore(path).persona())
    }

    @Test
    fun `PUT Wire-Code (Ruhig) wird akzeptiert`(@TempDir dir: Path) {
        val store = JsonFilePersonaStore(dir.resolve("p.json"))
        val res = put(controller(store), "Ruhig")
        assertEquals(200, res.statusCode.value())
        assertEquals(Persona.RUHIG, store.persona())
    }

    @Test
    fun `PUT unbekannte Persona - 400, KEIN Store-Write`(@TempDir dir: Path) {
        val store = JsonFilePersonaStore(dir.resolve("p.json"))
        val res = put(controller(store), "Grummelig")

        assertEquals(400, res.statusCode.value())
        assertEquals("unknown-persona", (res.body as SettingsError).error)
        assertEquals(null, store.persona(), "unbekannte Persona ⇒ kein Store-Write")
    }

    @Test
    fun `PUT leere Persona - 400`(@TempDir dir: Path) {
        val c = controller(JsonFilePersonaStore(dir.resolve("p.json")))
        assertEquals(400, put(c, "   ").statusCode.value())
        assertEquals(400, put(c, null).statusCode.value())
    }

    @Test
    fun `PUT bei Persona-OFF - trotzdem speicherbar (reine Zusatz-API), view sagt personaEnabled false`(@TempDir dir: Path) {
        val store = JsonFilePersonaStore(dir.resolve("p.json"))
        val res = put(controller(store, personaEnabled = false), "KUMPEL")
        assertEquals(200, res.statusCode.value(), "die Wahl ist speicherbar (kein 409) — das FE sagt via personaEnabled dazu, ob sie wirkt")
        assertEquals(false, (res.body as PersonaSettingView).personaEnabled)
        assertEquals(Persona.KUMPEL, store.persona())
    }

    @Test
    fun `Persist-Fehler - ehrlich 500 statt fake-200`(@TempDir dir: Path) {
        val store = JsonFilePersonaStore(dir.resolve("p.json"))
        // Ziel-Pfad in ein Verzeichnis verwandeln ⇒ der atomare Rename schlägt fehl.
        Files.createDirectories(store.path)
        val res = put(controller(store), "KUMPEL")
        assertEquals(500, res.statusCode.value())
        assertEquals("persist-failed", (res.body as SettingsError).error)
    }

    @Test
    fun `GET nach PUT - Store-Wert gewinnt gegen den STANDARD-Default`(@TempDir dir: Path) {
        val store = JsonFilePersonaStore(dir.resolve("p.json"))
        val c = controller(store)
        put(c, "KNAPP")
        val view = c.persona()
        assertEquals("KNAPP", view.persona)
        assertTrue(view.fromStore)
    }
}
