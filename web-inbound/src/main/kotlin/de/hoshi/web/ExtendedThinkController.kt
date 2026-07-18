package de.hoshi.web

import de.hoshi.core.pipeline.EscalationMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **ExtendedThinkController** — der Settings-Rand des Extended-Think-Drei-Stufen-
 * Settings (S2), nach EXAKT dem [SettingsController]-Muster: ein schlanker
 * `@RestController` hinter der [PerimeterWebFilter]-Wand (alle Pfade unter
 * `/api/v1` sind token-geschützt — ohne gültigen Token ⇒ 401).
 *
 * Zwei Quellen, sauber getrennt:
 *  - die DECKE (`HOSHI_EXTENDED_THINK_ENABLED`, Deploy-Zeit, default false)
 *    liest der Controller selbst per [Value] — Decke zu ⇒ effektiv IMMER
 *    [EscalationMode.AUS], ein PUT greift nicht (ehrlich 409).
 *  - der Laufzeit-STORE ist die injizierte [JsonFileEscalationModeStore]-Bean
 *    (siehe [ExtendedThinkConfig]) — GENAU die Instanz, deren Cache der
 *    TurnOrchestrator-Mode-Supplier pro Turn liest. Ein PUT greift also ab dem
 *    nächsten Turn, ohne Redeploy.
 *
 * Endpoints:
 *  - GET /api/v1/settings/extended-think → {mode, ceilingOpen, locked, effectiveMode}.
 *  - PUT /api/v1/settings/extended-think → Body {mode:"AUS"|"ERST_FRAGEN"|"AUTOMATISCH"}.
 *    Unbekannte Stufe ⇒ 400; Decke zu ⇒ 409 (deploy-disabled); Persist
 *    fehlgeschlagen ⇒ 500 (ehrlich, KEIN fake-200); sonst 200 + neuer Zustand.
 */
@RestController
class ExtendedThinkController(
    private val store: JsonFileEscalationModeStore,
    @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") private val ceilingOpen: Boolean,
) {

    @GetMapping("/api/v1/settings/extended-think")
    fun extendedThink(): ExtendedThinkView = view()

    @PutMapping("/api/v1/settings/extended-think")
    fun setMode(@RequestBody body: ExtendedThinkModeRequest): ResponseEntity<Any> {
        val mode = EscalationMode.fromWire(body.mode)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(SettingsError("unknown-mode", body.mode ?: "", "Unbekannte Stufe."))
        if (!ceilingOpen) {
            // Decke zu: ehrlich 409 — der Schalter greift nicht, Extended Think ist beim
            // Deploy deaktiviert (das bewahrt das Egress-/Deploy-Gate). KEIN Store-Write.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(SettingsError("deploy-disabled", SETTING_ID, "Beim Deploy deaktiviert; greift nicht."))
        }
        // Persist-then-commit: setMode schreibt ZUERST atomar auf die Platte und wirft,
        // wenn das fehlschlägt (der Cache bleibt dann unangetastet). 200 NUR bei
        // bewiesenem Persist — nie fake-grün.
        val persisted = runCatching { store.setMode(mode) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SettingsError("persist-failed", SETTING_ID, "Konnte die Stufe nicht dauerhaft speichern."))
        }
        return ResponseEntity.ok(view())
    }

    /** Der eine Settings-Zustand: Store-Wert, Decke und ihr Effektiv-Kollaps. */
    private fun view(): ExtendedThinkView {
        val mode = store.mode()
        return ExtendedThinkView(
            mode = mode.name,
            ceilingOpen = ceilingOpen,
            locked = !ceilingOpen,
            // Decke zu ⇒ effektiv AUS — exakt die Kaskade, die auch der
            // TurnOrchestrator-Mode-Supplier fährt (eine Wahrheit, zwei Leser).
            effectiveMode = (if (ceilingOpen) mode else EscalationMode.AUS).name,
        )
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zum Skill-id-Feld in [SettingsError]). */
        const val SETTING_ID = "extended-think"
    }
}

/**
 * Wire-Vertrag des Extended-Think-Settings (das FE rendert dagegen, S5):
 *  - [mode]: der Laufzeit-Store-Wert (Default ERST_FRAGEN bei offener Decke).
 *  - [ceilingOpen]: ist die Deploy-Zeit-Decke offen?
 *  - [locked]: `!ceilingOpen` — die Auswahl ist gesperrt (FE greyed).
 *  - [effectiveMode]: was die Pipeline wirklich fährt (Decke zu ⇒ "AUS").
 */
data class ExtendedThinkView(
    val mode: String,
    val ceilingOpen: Boolean,
    val locked: Boolean,
    val effectiveMode: String,
)

/** PUT-Body: die gewünschte Laufzeit-Stufe ("AUS" | "ERST_FRAGEN" | "AUTOMATISCH"). */
data class ExtendedThinkModeRequest(val mode: String?)
