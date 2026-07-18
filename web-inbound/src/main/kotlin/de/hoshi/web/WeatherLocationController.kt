package de.hoshi.web

import de.hoshi.adapters.knowledge.OpenMeteoGeocodingClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * **WeatherLocationController** — der Settings-Rand des Wetter-Ort-Settings
 * („der ort muss einstellbar sein"), nach EXAKT dem [ExtendedThinkController]-
 * Muster: ein schlanker `@RestController` hinter der [PerimeterWebFilter]-Wand
 * (alle Pfade unter `/api/v1` sind token-geschützt — ohne gültigen Token ⇒ 401).
 *
 * Zwei Quellen, sauber getrennt:
 *  - die DECKE (`HOSHI_WEATHER_ENABLED`, Deploy-Zeit, default false) liest der
 *    Controller selbst per [Value] — Wetter aus ⇒ ein PUT greift nicht
 *    (ehrlich 409, KEIN Geocode-Call, KEIN Store-Write).
 *  - der Laufzeit-STORE ist die injizierte [JsonFileWeatherLocationStore]-Bean
 *    (siehe [WeatherLocationConfig]) — dieselbe Instanz liest der Ort-Supplier
 *    des `WeatherGroundingProvider` pro Turn. Ein PUT greift also ab dem
 *    nächsten Turn, ohne Redeploy.
 *
 * Endpoints:
 *  - GET /api/v1/settings/weather-location → {label, lat, lon, fromStore,
 *    weatherEnabled} — Store-Wert, sonst die ENV-Seeds (eine Wahrheit, zwei Leser).
 *  - PUT /api/v1/settings/weather-location → Body {place:"Duisburg"} ⇒ Geocode
 *    (Open-Meteo, keyless) ⇒ Store {label,lat,lon} ⇒ 200 mit dem AUFGELÖSTEN
 *    Label. Leerer place ⇒ 400; Wetter beim Deploy aus ⇒ 409; unbekannter Ort ⇒
 *    ehrlich 404 („Ort nicht gefunden."); Geocoding-API nicht erreichbar ⇒ 502;
 *    Persist fehlgeschlagen ⇒ 500 (ehrlich, KEIN fake-200).
 */
@RestController
class WeatherLocationController(
    private val store: JsonFileWeatherLocationStore,
    private val geocoding: OpenMeteoGeocodingClient,
    @Value("\${HOSHI_WEATHER_ENABLED:false}") private val weatherEnabled: Boolean,
    @Value("\${hoshi.weather.lat:\${HOSHI_WEATHER_LAT:52.52}}") private val seedLat: Double,
    @Value("\${hoshi.weather.lon:\${HOSHI_WEATHER_LON:13.41}}") private val seedLon: Double,
    @Value("\${hoshi.weather.label:\${HOSHI_WEATHER_LABEL:Berlin}}") private val seedLabel: String,
) {

    @GetMapping("/api/v1/settings/weather-location")
    fun weatherLocation(): WeatherLocationView = view()

    @PutMapping("/api/v1/settings/weather-location")
    fun setLocation(@RequestBody body: WeatherLocationRequest): Mono<ResponseEntity<Any>> {
        val place = body.place?.trim().orEmpty()
        if (place.isEmpty()) {
            return Mono.just(
                ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(SettingsError("empty-place", SETTING_ID, "Bitte einen Ort angeben.")),
            )
        }
        if (!weatherEnabled) {
            // Decke zu: ehrlich 409 — der Ort greift nicht, das Wetter-Grounding ist
            // beim Deploy deaktiviert. KEIN Geocode-Call, KEIN Store-Write.
            return Mono.just(
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(SettingsError("deploy-disabled", SETTING_ID, "Beim Deploy deaktiviert; greift nicht.")),
            )
        }
        return geocoding.geocode(place)
            .map<ResponseEntity<Any>> { resolved ->
                // Persist-then-commit: setLocation schreibt ZUERST atomar auf die Platte
                // und wirft, wenn das fehlschlägt (Cache bleibt unangetastet). 200 NUR
                // bei bewiesenem Persist — nie fake-grün.
                runCatching { store.setLocation(resolved) }.fold(
                    onSuccess = { ResponseEntity.ok(view()) },
                    onFailure = {
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(SettingsError("persist-failed", SETTING_ID, "Konnte den Ort nicht dauerhaft speichern."))
                    },
                )
            }
            // Leeres Mono = die API kennt den Ort nicht ⇒ ehrlich 404, KEIN Store-Write.
            .defaultIfEmpty(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(SettingsError("place-not-found", SETTING_ID, "Ort nicht gefunden.")),
            )
            // Fehler-Mono = Geocoding-API nicht erreichbar ⇒ ehrlich 502 statt fake-404.
            .onErrorResume {
                Mono.just(
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(SettingsError("geocoding-unreachable", SETTING_ID, "Ort-Suche grad nicht erreichbar — bitte später nochmal.")),
                )
            }
    }

    /** Der eine Settings-Zustand: Store-Wert gewinnt, sonst die ENV-Seeds. */
    private fun view(): WeatherLocationView {
        val stored = store.location()
        return WeatherLocationView(
            label = stored?.label ?: seedLabel,
            lat = stored?.lat ?: seedLat,
            lon = stored?.lon ?: seedLon,
            fromStore = stored != null,
            weatherEnabled = weatherEnabled,
        )
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [ExtendedThinkController.SETTING_ID]). */
        const val SETTING_ID = "weather-location"
    }
}

/**
 * Wire-Vertrag des Wetter-Ort-Settings (das FE rendert dagegen):
 *  - [label]/[lat]/[lon]: der wirksame Ort (Store-Wert, sonst ENV-Seed).
 *  - [fromStore]: `true` ⇔ ein Ort wurde zur Laufzeit gespeichert.
 *  - [weatherEnabled]: ist das Wetter-Grounding beim Deploy an? (aus ⇒ der Ort
 *    ist einstell-, aber nicht wirksam — das FE sagt das ehrlich dazu.)
 */
data class WeatherLocationView(
    val label: String,
    val lat: Double,
    val lon: Double,
    val fromStore: Boolean,
    val weatherEnabled: Boolean,
)

/** PUT-Body: der gewünschte Ort als freier Name (z.B. `{"place":"Duisburg"}`). */
data class WeatherLocationRequest(val place: String?)
