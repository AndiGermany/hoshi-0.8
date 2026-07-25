package de.hoshi.web

import de.hoshi.adapters.knowledge.WeatherGroundingProvider
import de.hoshi.core.dto.Language
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * **WeatherTodayController** — der kleine LESE-Rand der Wetter-Scheibe fürs
 * Idle-Gesicht (Wetter-Kachel), nach dem [DiaryController]-Muster: ein schlanker
 * Read-only-`@RestController` hinter der [PerimeterWebFilter]-Wand (alle Pfade
 * unter `/api/v1` sind token-geschützt — ohne gültigen Token ⇒ 401).
 *
 * `GET /api/v1/weather/today` → `{label, todayMin, todayMax, codeText, precipMm}`
 * ([WeatherGroundingProvider.TodayForecast]) — EXAKT der Datenpfad des
 * Grounding-Blocks (derselbe Open-Meteo-Call, dasselbe Parsing, dieselbe
 * WMO-Code-Tabelle, Store-Ort gewinnt gegen die ENV-Seeds), nichts dupliziert.
 *
 * **`codeText` folgt seit 2026-07-25 der Anzeigesprache** (Fix
 * `vault/tracks/prep/PREP-i18n-backend-restklassen.md`): vorher lieferte der
 * Endpoint IMMER deutschen Text, egal welche UI-Sprache aktiv war — im
 * englischen Modus zeigte die Wetter-Kachel „17–31° · bedeckt". [languageStore]
 * ist dieselbe [JsonFileLanguageStore]-Bean, die auch `TtsSettingsController`/
 * `TtsRuntimeConfig` als „aktive Sprache" lesen (Kaskade: Store-Wert ▷
 * [Language.DEFAULT]) — eine Wahrheit, ein weiterer Leser.
 *
 * Ehrlichkeits-Regeln (kein best-effort-Schlucken wie im Turn-Pfad):
 *  - Wetter beim Deploy aus (`HOSHI_WEATHER_ENABLED=false`, Default) ⇒ 404 —
 *    das Feature EXISTIERT nicht; KEIN Open-Meteo-Call. Das FE zeigt dann die
 *    gestrichelte „kommt"-Kachel wie vor dem Endpoint.
 *  - Open-Meteo weg/Timeout/HTTP-Fehler ⇒ ehrlich 502 (`weather-unreachable`).
 *  - Open-Meteo antwortet, aber ohne lesbare heutige Daten ⇒ ehrlich 502
 *    (`weather-no-data`) — NIE erfundene Zahlen.
 */
@RestController
class WeatherTodayController(
    private val reader: WeatherTodayReader,
    @Value("\${HOSHI_WEATHER_ENABLED:false}") private val weatherEnabled: Boolean,
    private val languageStore: JsonFileLanguageStore,
) {

    @GetMapping("/api/v1/weather/today")
    fun today(): Mono<ResponseEntity<Any>> {
        if (!weatherEnabled) {
            // Decke zu: das Wetter-Feature existiert bei diesem Deploy nicht ⇒ 404,
            // kein Upstream-Call — das FE bleibt bei der ehrlichen „kommt"-Kachel.
            return Mono.just(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(SettingsError("weather-off", FEATURE_ID, "Wetter ist beim Deploy deaktiviert (HOSHI_WEATHER_ENABLED).")),
            )
        }
        return reader.today(activeLanguage())
            .map<ResponseEntity<Any>> { ResponseEntity.ok(it) }
            // Leeres Mono = Open-Meteo hat geantwortet, aber ohne heutige Daten
            // (kaputtes/leeres JSON) ⇒ ehrlich 502 statt Fake-Werten.
            .defaultIfEmpty(
                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(SettingsError("weather-no-data", FEATURE_ID, "Open-Meteo hat keine heutige Vorhersage geliefert.")),
            )
            // Fehler-Mono = Open-Meteo weg/Timeout ⇒ ehrlich 502, kein Fake.
            .onErrorResume {
                Mono.just(
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(SettingsError("weather-unreachable", FEATURE_ID, "Wetter grad nicht lesbar — Open-Meteo nicht erreichbar.")),
                )
            }
    }

    /** Store-Wert (Readback), sonst [Language.DEFAULT] (DE) — dieselbe Kaskade wie [TtsSettingsController.activeLanguage]. */
    private fun activeLanguage(): Language =
        Language.fromCodeOrNull(languageStore.languageCode()) ?: Language.DEFAULT

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [WeatherLocationController.SETTING_ID]). */
        const val FEATURE_ID = "weather-today"
    }
}

/**
 * **WeatherTodayReader** — der schmale Lese-Griff um den [WeatherGroundingProvider]
 * für den Read-Endpoint. WARUM ein eigener Typ statt einer Provider-Bean: der
 * Provider implementiert `GroundingPort`; eine zweite GroundingPort-Bean würde
 * die `grounding`-Injektion der Pipeline (PipelineConfig, das `groundingPort`-Bean)
 * mehrdeutig machen — Boot-Bruch. Der Reader delegiert nur (keine Logik) und
 * hält die eine Wahrheit im Provider. Bean-Wiring in [WeatherLocationConfig].
 */
class WeatherTodayReader(private val provider: WeatherGroundingProvider) {
    /**
     * Heutige Vorhersage am konfigurierten Ort — Fehler propagieren ehrlich.
     * [displayLanguage] steuert seit 2026-07-25 den `codeText` (Default DE,
     * byte-neutral für Aufrufer ohne explizite Sprache) — s. [WeatherGroundingProvider.todayForecast].
     */
    fun today(displayLanguage: Language = Language.DE): Mono<WeatherGroundingProvider.TodayForecast> =
        provider.todayForecast(displayLanguage)
}
