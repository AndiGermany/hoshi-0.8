package de.hoshi.web

import de.hoshi.adapters.escalation.EscalationModelCatalog
import de.hoshi.adapters.escalation.EscalationSpendStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **LookupModelController** — der Settings-Rand des SCHNELL-Lookup-Modells
 * (Andi-Video-Auftrag: „das Lookup-Sprachmodell in den Einstellungen wählbar,
 * zur Laufzeit, ohne Neustart"), Muster [WeatherLocationController]/
 * [ExtendedThinkController]: ein schlanker `@RestController` hinter der
 * [PerimeterWebFilter]-Wand.
 *
 * Betrifft NUR `hoshi.escalation.model` (der schnelle Online-Nachschlag,
 * `PipelineConfig.escalationPort`) — das Recherche-Modell
 * (`hoshi.escalation.research-model`, explizites „recherchiere online") hat
 * KEINEN Settings-Rand und bleibt Env-only (Andi-Vorgabe). Beide Ports teilen
 * sich weiter DENSELBEN [EscalationSpendStore] (ein Tages-Cap).
 *
 * Zwei Quellen, sauber getrennt:
 *  - der Laufzeit-STORE ist die injizierte [JsonFileLookupModelStore]-Bean
 *    (siehe [LookupModelConfig]) — dieselbe Instanz, aus der
 *    [LookupModelConfig.delegatingEscalationPort] beim Boot seinen initialen
 *    Zustand ableitet.
 *  - der Laufzeit-DELEGAT ist die injizierte [DelegatingEscalationPort]-Bean —
 *    GENAU die Instanz, die `PipelineConfig.turnOrchestrator` für den
 *    Schnell-Lookup nutzt. Ein PUT schaltet SOFORT um (auch mitten in einem
 *    laufenden Turn), zusätzlich zum Store-Persist (überlebt einen Neustart).
 *
 * Endpoints:
 *  - GET /api/v1/settings/lookup-model → {aktiv, modelle:[{id,label,centsProLookup}]}
 *    — die volle [EscalationModelCatalog]-Liste (inkl. der Recherche-Tarife,
 *    Andi kann sie informiert für den Schnell-Lookup wählen).
 *  - PUT /api/v1/settings/lookup-model → Body {id}. Unbekannte/leere id ⇒
 *    422 (unknown-model); Persist fehlgeschlagen ⇒ 500 (ehrlich, KEIN
 *    fake-200); sonst Store-Write bewiesen + Delegat umgeschaltet (best-effort,
 *    nur bei offener Extended-Think-Decke — s. [LookupModelConfig]-KDoc) ⇒
 *    200 + neuer Zustand (Readback, kein optimistisches UI).
 */
@RestController
class LookupModelController(
    private val store: JsonFileLookupModelStore,
    private val delegate: DelegatingEscalationPort,
    private val spendStore: EscalationSpendStore,
    @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") private val extendedThinkEnabled: Boolean,
    @Value("\${hoshi.escalation.model:}") private val escalationModel: String,
    @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") private val haBaseUrl: String,
    @Value("\${hoshi.escalation.web-search:\${HOSHI_ESCALATION_WEB_SEARCH:false}}") private val webSearchEnabled: Boolean,
) {

    @GetMapping("/api/v1/settings/lookup-model")
    fun lookupModel(): LookupModelView = view()

    @PutMapping("/api/v1/settings/lookup-model")
    fun setModel(@RequestBody body: LookupModelRequest): ResponseEntity<Any> {
        val id = body.id?.trim().orEmpty()
        val info = EscalationModelCatalog.byId(id)
            ?: return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(SettingsError("unknown-model", id, "Unbekanntes Modell."))

        // Persist-then-commit: setModelId schreibt ZUERST atomar auf die Platte und
        // wirft, wenn das fehlschlägt (Cache dann unangetastet). 200 NUR bei
        // bewiesenem Persist — nie fake-grün.
        val persisted = runCatching { store.setModelId(info.id) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SettingsError("persist-failed", SETTING_ID, "Konnte die Modell-Wahl nicht dauerhaft speichern."))
        }

        // Best-effort Live-Umschaltung: NUR bei offener Decke bekommt der Delegat
        // einen echten Adapter (kein Netz, solange Extended Think beim Deploy aus
        // ist) — der gespeicherte Wunsch greift ab dem nächsten Boot mit offener
        // Decke ohnehin (LookupModelConfig liest denselben Store beim Start).
        if (extendedThinkEnabled) {
            delegate.switchTo(info.id, buildEscalationAdapter(info.id, spendStore, haBaseUrl, webSearchEnabled))
        }
        return ResponseEntity.ok(view())
    }

    /** Der eine Settings-Zustand: Store-Wert (Readback), sonst das Boot-Property. */
    private fun view(): LookupModelView {
        val fallbackId = escalationModel.ifBlank { EscalationModelCatalog.DEFAULT_MODEL_ID }
        val aktiv = store.modelId()?.let { EscalationModelCatalog.byId(it)?.id } ?: fallbackId
        return LookupModelView(
            aktiv = aktiv,
            modelle = EscalationModelCatalog.MODELS.map {
                LookupModelOption(id = it.id, label = it.label, centsProLookup = it.caPriceCentsPerLookup)
            },
        )
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [ExtendedThinkController.SETTING_ID]). */
        const val SETTING_ID = "lookup-model"
    }
}

/** Eine Zeile der Modell-Auswahl fürs FE-Dropdown. */
data class LookupModelOption(val id: String, val label: String, val centsProLookup: Double)

/** Wire-Vertrag: das aktive Modell + die volle Katalog-Liste. */
data class LookupModelView(val aktiv: String, val modelle: List<LookupModelOption>)

/** PUT-Body: die gewünschte Modell-Id (z.B. `{"id":"gpt-5.4-mini"}`). */
data class LookupModelRequest(val id: String?)
