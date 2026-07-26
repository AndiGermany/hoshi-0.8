package de.hoshi.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **BrainAutoSwitchController** — der Settings-Rand des `brainAutoSwitch`-Flags
 * (Andi-Auftrag „12B für Chat, e4b für Voice", 2026-07-26), nach EXAKT dem
 * [ExtendedThinkController]-Muster: ein schlanker `@RestController` hinter der
 * [PerimeterWebFilter]-Wand.
 *
 * Default AUS (byte-neutral) — solange nie ein PUT kam, tut der
 * [BrainAutoSwitchService] NICHTS (keine Health-/Switch-Sidecar-Calls). Ein PUT
 * greift ab dem NÄCHSTEN Turn/`start`-Frame, ohne Redeploy (derselbe
 * [JsonFileBrainAutoSwitchStore]-Cache, den [BrainAutoSwitchService] liest).
 *
 * Endpoints:
 *  - GET /api/v1/settings/brain-auto-switch → {enabled}.
 *  - PUT /api/v1/settings/brain-auto-switch → Body {enabled}. Persist fehlgeschlagen
 *    ⇒ 500 (ehrlich, KEIN fake-200); sonst 200 + neuer Zustand.
 */
@RestController
class BrainAutoSwitchController(
    private val store: JsonFileBrainAutoSwitchStore,
) {

    @GetMapping("/api/v1/settings/brain-auto-switch")
    fun get(): BrainAutoSwitchView = view()

    @PutMapping("/api/v1/settings/brain-auto-switch")
    fun set(@RequestBody body: BrainAutoSwitchRequest): ResponseEntity<Any> {
        // Persist-then-commit: setEnabled schreibt ZUERST atomar auf die Platte und wirft,
        // wenn das fehlschlägt (der Cache bleibt dann unangetastet). 200 NUR bei
        // bewiesenem Persist — nie fake-grün.
        val persisted = runCatching { store.setEnabled(body.enabled ?: false) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SettingsError("persist-failed", SETTING_ID, "Konnte die Einstellung nicht dauerhaft speichern."))
        }
        return ResponseEntity.ok(view())
    }

    private fun view(): BrainAutoSwitchView = BrainAutoSwitchView(enabled = store.enabled())

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zum Skill-id-Feld in [SettingsError]). */
        const val SETTING_ID = "brain-auto-switch"
    }
}

/** Wire-Vertrag des Auto-Switch-Settings (das FE rendert dagegen). */
data class BrainAutoSwitchView(val enabled: Boolean)

/** PUT-Body: der gewünschte Zustand (`{"enabled":true}`). Fehlendes Feld ⇒ AUS (konservativ). */
data class BrainAutoSwitchRequest(val enabled: Boolean?)
