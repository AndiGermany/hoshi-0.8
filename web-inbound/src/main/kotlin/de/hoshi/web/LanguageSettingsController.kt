package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **LanguageSettingsController** — der Settings-Rand der Sprachwahl (Andi-Auftrag
 * 2026-07-20: „Hoshi versteht/denkt/spricht wählbar in DE/EN/ES/FR/IT"), Muster
 * [WeatherLocationController]/[LookupModelController]: ein schlanker
 * `@RestController` hinter der [PerimeterWebFilter]-Wand.
 *
 * **Was die Wahl zusammenschaltet** (Andi-Vorgabe): der gespeicherte Wert ist der
 * SERVER-DEFAULT für Ränder ohne explizite Per-Turn-Wahl —
 *  - STT-`language`-Hint: [WebSocketConfig] (ws-Session-Default) + [VoiceInboundController]
 *    (Query-Param-Fallback) lesen denselben [JsonFileLanguageStore].
 *  - Prompt-Sprachanweisung + aktives [de.hoshi.core.pipeline.lang.LanguagePack]:
 *    beide hängen am EINEN `language`-Wert, der pro Turn durch die Pipeline reist
 *    ([de.hoshi.core.pipeline.PersonaService.systemPrompt]/[de.hoshi.core.pipeline.ResponseFormatter]) —
 *    sobald ein Rand den Store-Default statt der hartcodierten DE-Konstante nimmt,
 *    folgen Prompt UND Formatter automatisch (EIN Wert, keine zweite Schaltstelle).
 *  - TTS FOLGT der Sprache (Andi-Auftrag 21.07: „…dann soll das TTS auch auf
 *    englisch umschwänken" — Nachtrag zur ursprünglichen Andi-Vorgabe oben, die
 *    TTS noch als reine Engine-Sache sah): NACH einem erfolgreich persistierten
 *    Sprachwechsel wird die AKTIVE TTS-Engine mit der für (Engine, NEUE Sprache)
 *    resolvierten Stimme neu gebaut und der [delegatingTtsPort] schaltet SOFORT
 *    um — [TtsVoiceResolver] ist dieselbe Auflösungs-Wahrheit wie ein expliziter
 *    TTS-Settings-PUT ([TtsSettingsController]): eine zuvor für (Engine, neue
 *    Sprache) explizit gemerkte Stimme gewinnt, sonst (nur `say`) der
 *    [de.hoshi.core.pipeline.lang.LanguagePack.sayVoiceHint] der neuen Sprache,
 *    sonst der bisherige Default. openai/voxtral bleiben unberührt (openai ist
 *    multilingual, s. [TtsVoiceResolver]-KDoc). Bewusst KEIN Store-Write hier:
 *    ein automatisch angewandter Hint wird NIE zur „expliziten Wahl" (nur der
 *    Delegat wechselt den Adapter, der Store bleibt Andis Wahrheit).
 *
 * Endpoints:
 *  - GET /api/v1/settings/language → {aktiv, sprachen:[{code,endonym,beta}], smartHomeHinweis?}
 *  - PUT /api/v1/settings/language → Body {code}. Unbekannter/leerer Code ⇒ 422
 *    (unknown-language); Persist fehlgeschlagen ⇒ 500 (ehrlich, KEIN fake-200);
 *    sonst Store-Write bewiesen ⇒ 200 + neuer Zustand (Readback, kein optimistisches UI).
 */
@RestController
class LanguageSettingsController(
    private val store: JsonFileLanguageStore,
    private val ttsEngineStore: JsonFileTtsEngineStore,
    private val ttsEngineFactory: TtsEngineFactory,
    private val delegatingTtsPort: DelegatingTtsPort,
    @Value("\${HOSHI_TTS:}") private val ttsImpl: String,
) {

    @GetMapping("/api/v1/settings/language")
    fun language(): LanguageSettingsView = view()

    @PutMapping("/api/v1/settings/language")
    fun setLanguage(@RequestBody body: LanguageSettingsRequest): ResponseEntity<Any> {
        val lang = Language.fromCodeOrNull(body.code)
            ?: return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(SettingsError("unknown-language", body.code.orEmpty(), "Unbekannte Sprache."))

        // Persist-then-commit: setLanguageCode schreibt ZUERST atomar auf die Platte
        // und wirft, wenn das fehlschlägt (Cache dann unangetastet). 200 NUR bei
        // bewiesenem Persist — nie fake-grün.
        val persisted = runCatching { store.setLanguageCode(lang.code) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SettingsError("persist-failed", SETTING_ID, "Konnte die Sprach-Wahl nicht dauerhaft speichern."))
        }
        switchTtsVoiceForLanguage(lang)
        return ResponseEntity.ok(view())
    }

    /**
     * **TTS-Stimme folgt der Sprache** (Andi-Auftrag 21.07, s. Klassen-KDoc):
     * NACH einem bewiesenen Sprach-Persist wird die AKTIVE TTS-Engine mit der
     * für ([engineId], [language]) resolvierten Stimme frisch gebaut und der
     * Delegat schaltet sofort um — exakt der Mechanismus, den ein TTS-Settings-
     * PUT auch nutzt ([TtsSettingsController.applyAndRespond]), nur durch den
     * Sprachwechsel statt eine explizite Engine-Wahl ausgelöst. Engine-Bau ist
     * laut [TtsEngineFactory]-KDoc billig (kein Netz-Call im Konstruktor) — ohne
     * Try/Catch, da hier keine bekannte Exception-Quelle existiert.
     */
    private fun switchTtsVoiceForLanguage(language: Language) {
        val engineId = ttsEngineStore.engineId()?.takeIf { it in TtsEngineIds.ALL } ?: TtsEngineIds.canonicalOf(ttsImpl)
        val resolvedVoice = TtsVoiceResolver.resolveVoice(engineId, language, ttsEngineStore)
        delegatingTtsPort.switchTo(engineId, ttsEngineFactory.build(engineId, resolvedVoice))
    }

    /** Der eine Settings-Zustand: Store-Wert (Readback), sonst [Language.DEFAULT]. */
    private fun view(): LanguageSettingsView {
        val aktiv = Language.fromCodeOrNull(store.languageCode()) ?: Language.DEFAULT
        return LanguageSettingsView(
            aktiv = aktiv.code,
            sprachen = Language.entries.map { LanguageOption(it.code, it.endonym, beta = it != Language.DE) },
            smartHomeHinweis = LanguagePackRegistry.forLanguage(aktiv).smartHomeNotice,
        )
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [LookupModelController.SETTING_ID]). */
        const val SETTING_ID = "language"
    }
}

/** Eine Zeile der Sprach-Auswahl fürs FE-Dropdown. [beta] = alles außer Deutsch (Tier-1-Reichweite noch im Aufbau). */
data class LanguageOption(val code: String, val endonym: String, val beta: Boolean)

/**
 * Wire-Vertrag: die aktive Sprache + die volle Auswahl-Liste + (falls die aktive
 * Sprache nicht Deutsch ist) der ehrliche Smart-Home-Hinweis fürs FE.
 */
data class LanguageSettingsView(
    val aktiv: String,
    val sprachen: List<LanguageOption>,
    val smartHomeHinweis: String?,
)

/** PUT-Body: der gewünschte Sprach-Code (z.B. `{"code":"en"}`). */
data class LanguageSettingsRequest(val code: String?)
