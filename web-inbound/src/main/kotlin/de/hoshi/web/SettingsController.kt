package de.hoshi.web

import de.hoshi.core.skills.SkillId
import de.hoshi.core.skills.SkillRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **SettingsController** — der Settings-Rand der Skills (S2.3): liest und schreibt die
 * EINE Zwei-Stufen-Toggle-Wahrheit, mit der Andi die Skills ZUR LAUFZEIT togglet
 * (siehe [CeilingAndStoreSkillState]). Spiegelt [OpsStatusController]: ein schlanker
 * `@RestController` hinter der [PerimeterWebFilter]-Wand (alle Pfade unter `/api/v1`
 * sind token-geschützt — ohne gültigen Token bzw. ausserhalb von Loopback ⇒ 401).
 *
 * Zwei Quellen, sauber getrennt:
 *  - die DECKE (Deploy-Zeit-ENV pro Skill) liest der Controller selbst per [Value]:
 *    SMART_HOME←HOSHI_TOOLS_ENABLED, SCENES←HOSHI_TOOLS_ENABLED UND HOSHI_SCENES_ENABLED,
 *    TIMER←HOSHI_TIMER_ENABLED, CALCULATOR←HOSHI_CALCULATOR_ENABLED.
 *  - der Laufzeit-STORE (Read/Write) ist die injizierte [JsonFileSkillStateStore]-Bean —
 *    GENAU die Instanz, deren Cache der Classifier pro Turn liest. Ein PUT greift also
 *    ab dem nächsten Turn, ohne Redeploy.
 *
 * Labels und Tier kommen aus der [SkillRegistry] (eine Wahrheit, nicht erneut hart kodiert).
 *
 * Endpoints:
 *  - GET  /api/v1/settings/skills        → Liste aller Skills mit Decken-/Store-/Effektiv-Zustand.
 *  - PUT  /api/v1/settings/skills/{id}   → Body {enabled:Bool}. Decke zu ⇒ 409 (greift nicht,
 *    beim Deploy deaktiviert); unbekannte id ⇒ 404; Persist fehlgeschlagen ⇒ 500 (ehrlich, KEIN
 *    fake-200); sonst Store-Write bewiesen ⇒ 200 + neuer Zustand.
 */
@RestController
class SettingsController(
    private val store: JsonFileSkillStateStore,
    @Value("\${HOSHI_TOOLS_ENABLED:false}") private val toolsEnabled: Boolean,
    @Value("\${HOSHI_SCENES_ENABLED:false}") private val scenesEnabled: Boolean,
    @Value("\${HOSHI_TIMER_ENABLED:false}") private val timerEnabled: Boolean,
    @Value("\${HOSHI_CALCULATOR_ENABLED:false}") private val calculatorEnabled: Boolean,
) {

    @GetMapping("/api/v1/settings/skills")
    fun skills(): List<SkillStateView> = SkillRegistry.ALL.map { view(it.id) }

    @PutMapping("/api/v1/settings/skills/{id}")
    fun setSkill(
        @PathVariable id: String,
        @RequestBody body: SkillToggleRequest,
    ): ResponseEntity<Any> {
        val skill = runCatching { SkillId.valueOf(id) }.getOrNull()
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(SettingsError("unknown-skill", id, "Unbekannter Skill."))
        if (!ceilingOpen(skill)) {
            // Decke zu: ehrlich 409 — der Schalter greift nicht, der Skill ist beim Deploy
            // deaktiviert (das bewahrt das Egress-/Deploy-Gate). KEIN Store-Write.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(SettingsError("deploy-disabled", id, "Beim Deploy deaktiviert; greift nicht."))
        }
        // Persist-then-commit: setEnabled schreibt ZUERST atomar auf die Platte und wirft,
        // wenn das fehlschlägt (der Cache bleibt dann unangetastet). 200 NUR bei bewiesenem
        // Persist — ein Schreib-Fehler darf NIE als Erfolg quittiert werden (kein fake-grün).
        val persisted = runCatching { store.setEnabled(skill, body.enabled) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SettingsError("persist-failed", id, "Konnte den Schalter nicht dauerhaft speichern."))
        }
        return ResponseEntity.ok(view(skill))
    }

    /** Eine Zeile der Skill-Tabelle: Decke, Store-Wert (tier-Default) und ihr UND. */
    private fun view(id: SkillId): SkillStateView {
        val descriptor = SkillRegistry.byId(id)
        val open = ceilingOpen(id)
        // Nicht gesetzt ⇒ tier-abhängiger Default (Tom): LOCAL ON (byte-neutral), EGRESS/CLOUD OFF.
        val enabled = store.isEnabled(id, default = descriptor.tier.defaultEnabled)
        return SkillStateView(
            id = id.name,
            labelDe = descriptor.labelDe,
            labelEn = descriptor.labelEn,
            tier = descriptor.tier.name,
            ceilingOpen = open,
            enabled = enabled,
            effective = open && enabled,
            locked = !open,
        )
    }

    /** Die Decke pro Skill — exakt die ENV-Logik des `ofStatic`-Ceilings in [PipelineConfig]. */
    private fun ceilingOpen(id: SkillId): Boolean = when (id) {
        SkillId.SMART_HOME -> toolsEnabled
        SkillId.SCENES -> toolsEnabled && scenesEnabled
        SkillId.TIMER -> timerEnabled
        SkillId.CALCULATOR -> calculatorEnabled
    }
}

/**
 * Eine Zeile der Skills-Settings-Tabelle (Wire-Vertrag für das FE, S2.3-FE rendert dagegen):
 *  - [id]: stabiler Enum-Name (auch der Pfad-Parameter des PUT).
 *  - [ceilingOpen]: ist die Deploy-Zeit-Decke offen?
 *  - [enabled]: der Laufzeit-Store-Wert (runtimeDefault ON für die lokalen Skills).
 *  - [effective]: `ceilingOpen UND enabled` — was der Classifier wirklich sieht.
 *  - [locked]: `!ceilingOpen` — der Toggle ist gesperrt (FE greyed).
 */
data class SkillStateView(
    val id: String,
    val labelDe: String,
    val labelEn: String,
    val tier: String,
    val ceilingOpen: Boolean,
    val enabled: Boolean,
    val effective: Boolean,
    val locked: Boolean,
)

/** PUT-Body: der gewünschte Laufzeit-Zustand des Skills. */
data class SkillToggleRequest(val enabled: Boolean)

/** Fehler-Body für 404 (unbekannte id), 409 (Decke zu) und 500 (Persist fehlgeschlagen). */
data class SettingsError(val error: String, val id: String, val message: String)
