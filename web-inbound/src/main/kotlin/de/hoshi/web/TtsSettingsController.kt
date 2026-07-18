package de.hoshi.web

import de.hoshi.core.dto.Language
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * **TtsSettingsController** — der Settings-Rand der TTS-Engine-Wahl (Andi-Video-
 * Auftrag: „die TTS-Engine in den Einstellungen wählbar, zur Laufzeit, ohne
 * Neustart"), Muster [WeatherLocationController]. `verfuegbar` ist EHRLICH per
 * Live-Health-Probe ([TtsEngineProbe]) — kein Fake-grün: ein gestoppter Sidecar
 * (z.B. Voxtral, RAM sparen) zeigt sich als „nicht gestartet", nicht als
 * unsichtbar-aus.
 *
 * **Stimme folgt der aktiven Engine** (Andi-Live-Befund: die alte Stimme-Sektion
 * zeigte den OpenAI-Cloud-Hinweis + OpenAI-Stimmen AUCH bei piper/say): GET
 * trägt zusätzlich `stimmen` (die LIVE-Liste der AKTIVEN Engine, via
 * [TtsVoiceCatalog]) + `aktiveStimme` (gemerkter Wunsch aus [store], sonst der
 * Boot-Default aus [factory]). PUT nimmt optional `{voice}` entgegen — validiert
 * gegen exakt diese Live-Liste, persistiert NUR für die betroffene Engine und
 * schaltet den [delegate] auf einen frischen Adapter derselben Engine + neuer
 * Stimme (Muster-Referenz [factory]-KDoc).
 *
 * Drei Quellen, sauber getrennt:
 *  - der Laufzeit-STORE ist die injizierte [JsonFileTtsEngineStore]-Bean (siehe
 *    [TtsRuntimeConfig]) — dieselbe Instanz, aus der
 *    [TtsRuntimeConfig.delegatingTtsPort] beim Boot seinen initialen Zustand
 *    ableitet.
 *  - der Laufzeit-DELEGAT ist die injizierte [DelegatingTtsPort]-Bean — GENAU
 *    die Instanz, die `PipelineConfig.ttsStage` für die ECHTE Synthese nutzt.
 *    Ein PUT schaltet SOFORT um.
 *  - der Stimmen-KATALOG ([TtsVoiceCatalog]) probt live, welche Stimmen die
 *    AKTIVE Engine gerade wirklich anbietet (openai: statische Whitelist;
 *    say/piper: `GET {baseUrl}/voices`; voxtral: bewusst leer).
 *
 * **Sprachbewusst** (Andi-Auftrag 21.07 — dieselbe Auflösungs-Wahrheit wie
 * [LanguageSettingsController]s Sprachwechsel-PUT, s. [TtsVoiceResolver]): ein
 * PUT OHNE `voice` wendet nicht mehr blind die zuletzt gemerkte Engine-Stimme
 * an, sondern die für (Engine, AKTUELL AKTIVE Sprache) resolvierte — eine
 * explizite Wahl GENAU für dieses Paar, sonst (nur `say`) der
 * `sayVoiceHint` der aktiven Sprache, sonst der bisherige Default. `stimmen`
 * (GET, via [TtsVoiceCatalog]) listet bei nicht-deutscher aktiver Sprache die
 * sprach-passenden Stimmen zuerst. **Persistiert** wird dabei NUR eine
 * WIRKLICH explizite `voice` aus dem PUT-Body — ein automatisch resolvierter
 * Hint wird NIE stillschweigend als „Andis Wahl" in den Store geschrieben
 * (sonst würde ein Hint-Fallback zur unlöschbaren Fake-Wahl mutieren).
 *
 * Endpoints:
 *  - GET /api/v1/settings/tts → {aktiv, engines:[{id,verfuegbar,hinweis}],
 *    stimmen:[{id,label,locale?,lizenz?}], stimmenHinweis, aktiveStimme}.
 *  - PUT /api/v1/settings/tts → Body {id, voice?}. Unbekannte id ⇒ 422
 *    (unknown-engine); bekannte, aber gerade NICHT verfügbare Engine ⇒ 409
 *    (engine-unavailable, trägt den ehrlichen [TtsEngineAvailability.hinweis]);
 *    gesetztes `voice`, das NICHT in der Live-Liste der Ziel-Engine steht ⇒ 422
 *    (unknown-voice); Persist fehlgeschlagen ⇒ 500; sonst Store-Write + Delegat-
 *    Umschaltung bewiesen ⇒ 200 + neuer Zustand (Readback, kein optimistisches UI).
 */
@RestController
class TtsSettingsController(
    private val store: JsonFileTtsEngineStore,
    private val delegate: DelegatingTtsPort,
    private val factory: TtsEngineFactory,
    private val probe: TtsEngineProbe,
    private val voiceCatalog: TtsVoiceCatalog,
    private val languageStore: JsonFileLanguageStore,
    @Value("\${HOSHI_TTS:}") private val ttsImpl: String,
) {

    @GetMapping("/api/v1/settings/tts")
    fun ttsSettings(): Mono<TtsSettingsView> = fullView()

    @PutMapping("/api/v1/settings/tts")
    fun setEngine(@RequestBody body: TtsEngineRequest): Mono<ResponseEntity<Any>> {
        val id = body.id?.trim().orEmpty()
        if (id !in TtsEngineIds.ALL) {
            return Mono.just(
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(SettingsError("unknown-engine", id, "Unbekannte Engine.")),
            )
        }
        val requestedVoice = body.voice?.trim()?.takeIf { it.isNotBlank() }
        return probe.check(id).flatMap<ResponseEntity<Any>> { availability ->
            if (!availability.verfuegbar) {
                Mono.just(
                    ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(SettingsError("engine-unavailable", id, availability.hinweis)),
                )
            } else if (requestedVoice != null) {
                // Stimm-Wunsch: NUR gegen die LIVE-Liste der Ziel-Engine validieren — kein Raten.
                voiceCatalog.voicesFor(id, activeLanguage()).flatMap<ResponseEntity<Any>> { catalog ->
                    if (catalog.stimmen.none { it.id == requestedVoice }) {
                        Mono.just(
                            ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                                .body(SettingsError("unknown-voice", requestedVoice, "Unbekannte Stimme für diese Engine.")),
                        )
                    } else {
                        // Eine WIRKLICH explizite Wahl — persistiert für (id, aktive Sprache) UND baut damit.
                        applyAndRespond(id, persistVoice = requestedVoice, buildVoice = requestedVoice)
                    }
                }
            } else {
                // Kein Stimm-Wunsch in diesem PUT — NICHTS Neues persistieren, aber sprachbewusst
                // bauen: eine ZUVOR gemerkte Wahl für (id, aktive Sprache), sonst (nur say) der
                // Sprach-Hint, sonst der bisherige Default (TtsVoiceResolver, s. Klassen-KDoc).
                applyAndRespond(id, persistVoice = null, buildVoice = TtsVoiceResolver.resolveVoice(id, activeLanguage(), store))
            }
        }
    }

    /**
     * Persist-then-commit (Engine, dann — falls [persistVoice] gesetzt, d.h. eine
     * WIRKLICHE explizite PUT-Wahl — die Stimme für (id, aktive Sprache)), Live-
     * Delegat-Umschaltung auf einen frischen Adapter derselben Engine + [buildVoice],
     * danach Readback (kein optimistisches UI).
     */
    private fun applyAndRespond(id: String, persistVoice: String?, buildVoice: String?): Mono<ResponseEntity<Any>> {
        val persisted = runCatching {
            store.setEngineId(id)
            if (persistVoice != null) store.setVoice(id, activeLanguage(), persistVoice)
        }
        return if (persisted.isFailure) {
            Mono.just(
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(SettingsError("persist-failed", SETTING_ID, "Konnte die Engine nicht dauerhaft speichern.")),
            )
        } else {
            delegate.switchTo(id, factory.build(id, buildVoice))
            fullView().map<ResponseEntity<Any>> { ResponseEntity.ok(it) }
        }
    }

    /** Probt ALLE vier Engines + holt die Stimmen der AKTIVEN Engine — der volle GET-Wire-Vertrag. */
    private fun fullView(): Mono<TtsSettingsView> {
        val aktivId = activeId()
        val language = activeLanguage()
        val enginesMono: Mono<List<TtsEngineOption>> = Mono.zip(
            TtsEngineIds.ALL.map { id ->
                probe.check(id).map { availability -> TtsEngineOption(id, availability.verfuegbar, availability.hinweis) }
            },
        ) { results -> results.map { it as TtsEngineOption } }

        return Mono.zip(enginesMono, voiceCatalog.voicesFor(aktivId, language)).map { tuple ->
            val engines = tuple.t1
            val catalog = tuple.t2
            TtsSettingsView(
                aktiv = aktivId,
                engines = engines,
                stimmen = catalog.stimmen,
                stimmenHinweis = catalog.hinweis,
                aktiveStimme = TtsVoiceResolver.resolveVoice(aktivId, language, store) ?: factory.defaultVoiceFor(aktivId),
            )
        }
    }

    /** Store-Wert (Readback), sonst das Boot-Property — dieselbe Kaskade wie [TtsRuntimeConfig.delegatingTtsPort]. */
    private fun activeId(): String =
        store.engineId()?.takeIf { it in TtsEngineIds.ALL } ?: TtsEngineIds.canonicalOf(ttsImpl)

    /** Die AKTIV gewählte Sprache ([JsonFileLanguageStore], Readback), sonst [Language.DEFAULT] (DE). */
    private fun activeLanguage(): Language =
        Language.fromCodeOrNull(languageStore.languageCode()) ?: Language.DEFAULT

    companion object {
        /** Stabile id für Fehler-Bodies. */
        const val SETTING_ID = "tts"
    }
}

/** Eine Engine-Zeile fürs FE: id + ehrlicher Live-Status + Klartext-Hinweis. */
data class TtsEngineOption(val id: String, val verfuegbar: Boolean, val hinweis: String)

/**
 * Wire-Vertrag: die aktive Engine + der Live-Status aller vier + die Stimmen der
 * AKTIVEN Engine ([stimmen]/[stimmenHinweis]/[aktiveStimme] — additiv, Default
 * "leer"/`null` hält ältere Konstruktions-Stellen unverändert lauffähig).
 */
data class TtsSettingsView(
    val aktiv: String,
    val engines: List<TtsEngineOption>,
    val stimmen: List<TtsVoiceOption> = emptyList(),
    val stimmenHinweis: String = "",
    val aktiveStimme: String? = null,
)

/** PUT-Body: die gewünschte Engine-Id (z.B. `{"id":"say"}`) + optional eine Stimme (`{"id":"say","voice":"Anna"}`). */
data class TtsEngineRequest(val id: String?, val voice: String? = null)
