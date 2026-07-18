package de.hoshi.web

import de.hoshi.core.dto.Persona
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **PersonaSettingsController** — der Settings-Rand des SERVER-seitigen Persona-Settings
 * (Andi 16.07: „vieles eher serverseitig"), nach EXAKT dem [ExtendedThinkController]-/
 * [WeatherLocationController]-Muster: ein schlanker `@RestController` hinter der
 * [PerimeterWebFilter]-Wand (alle Pfade unter `/api/v1` sind token-geschützt — ohne
 * gültigen Token ⇒ 401).
 *
 * Zwei Quellen, sauber getrennt:
 *  - das FLAG (`HOSHI_PERSONA_ENABLED`, Deploy-Zeit, default false) liest der Controller
 *    selbst per [Value] — es entscheidet, ob die (server- oder request-seitig gewählte)
 *    Persona am Ende ÜBERHAUPT greift oder der [de.hoshi.core.pipeline.PersonaResolver]
 *    alles auf STANDARD kollabiert. Die Wahl bleibt trotzdem speicherbar (rein additiv,
 *    KEIN 409) — das FE sagt über [PersonaSettingView.personaEnabled] ehrlich dazu, ob sie
 *    schon wirkt.
 *  - der Laufzeit-STORE ist die injizierte [JsonFilePersonaStore]-Bean (siehe
 *    [PersonaSettingsConfig]) — GENAU die Instanz, deren Cache die ws-Resolver-Naht pro
 *    Turn liest (`personaResolver.resolve(frameFeld, store.persona())`). Ein PUT greift
 *    also ab dem nächsten Satelliten-Turn, ohne Redeploy.
 *
 * Endpoints:
 *  - GET /api/v1/settings/persona → {persona, personaEnabled, fromStore}.
 *  - PUT /api/v1/settings/persona → Body {persona:"KUMPEL"} (Enum-NAME oder Wire-Code
 *    „Kumpel"). Unbekannte/leere Persona ⇒ 400; Persist fehlgeschlagen ⇒ 500 (ehrlich,
 *    KEIN fake-200); sonst 200 + neuer Zustand.
 */
@RestController
class PersonaSettingsController(
    private val store: JsonFilePersonaStore,
    @Value("\${HOSHI_PERSONA_ENABLED:false}") private val personaEnabled: Boolean,
) {

    @GetMapping("/api/v1/settings/persona")
    fun persona(): PersonaSettingView = view()

    @PutMapping("/api/v1/settings/persona")
    fun setPersona(@RequestBody body: PersonaSettingRequest): ResponseEntity<Any> {
        // Strikte Parse (NICHT Persona.fromCode, das Unbekanntes still auf STANDARD kollabiert):
        // eine unbekannte/leere Wahl ist ein ehrlicher 400, kein still gespeichertes STANDARD.
        val persona = JsonFilePersonaStore.parseStrict(body.persona)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(SettingsError("unknown-persona", SETTING_ID, "Unbekannte Persona."))
        // Persist-then-commit: setPersona schreibt ZUERST atomar auf die Platte und wirft,
        // wenn das fehlschlägt (der Cache bleibt dann unangetastet). 200 NUR bei bewiesenem
        // Persist — nie fake-grün.
        val persisted = runCatching { store.setPersona(persona) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SettingsError("persist-failed", SETTING_ID, "Konnte die Persona nicht dauerhaft speichern."))
        }
        return ResponseEntity.ok(view())
    }

    /** Der eine Settings-Zustand: Store-Wert gewinnt, sonst der STANDARD-Default. */
    private fun view(): PersonaSettingView {
        val stored = store.persona()
        return PersonaSettingView(
            persona = (stored ?: Persona.STANDARD).name,
            personaEnabled = personaEnabled,
            fromStore = stored != null,
        )
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [ExtendedThinkController.SETTING_ID]). */
        const val SETTING_ID = "persona"
    }
}

/**
 * Wire-Vertrag des Persona-Settings (das FE rendert dagegen):
 *  - [persona]: die wirksame gespeicherte Persona (Enum-NAME), sonst „STANDARD".
 *  - [personaEnabled]: ist die Persona-Steuerung beim Deploy an? (aus ⇒ die Wahl ist
 *    speicher-, aber nicht wirksam — der Resolver kollabiert auf STANDARD; das FE sagt
 *    das ehrlich dazu.)
 *  - [fromStore]: `true` ⇔ eine Persona wurde zur Laufzeit gespeichert.
 */
data class PersonaSettingView(
    val persona: String,
    val personaEnabled: Boolean,
    val fromStore: Boolean,
)

/** PUT-Body: die gewünschte Persona als Enum-NAME oder Wire-Code (z.B. `{"persona":"KUMPEL"}`). */
data class PersonaSettingRequest(val persona: String?)
