package de.hoshi.web

import de.hoshi.adapters.knowledge.OpenMeteoGeocodingClient
import de.hoshi.adapters.knowledge.WeatherGroundingProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **WeatherLocationConfig** — das MINIMALE Wiring des Wetter-Ort-Settings
 * (Muster [ExtendedThinkConfig]): der [JsonFileWeatherLocationStore], den der
 * [WeatherLocationController] (GET/PUT) und der Ort-Supplier des
 * `WeatherGroundingProvider` (PipelineConfig) TEILEN — eine Store-Instanz, eine
 * Wahrheit, ein PUT greift ab dem nächsten Turn. Dazu der
 * [OpenMeteoGeocodingClient] (keyless, best-effort), den Controller (PUT ⇒
 * Geocode ⇒ Store) und Provider (expliziter Ort in der Frage, einmalig pro Turn)
 * teilen.
 *
 * Bewusst eine EIGENE `@Configuration` statt PipelineConfig-Anbau: die
 * Turn-Pipeline-Verdrahtung (Ort-Supplier + Geocoding in den
 * `WeatherGroundingProvider` in [PipelineConfig.groundingPort]) ist im
 * Übergabe-Text dokumentiert und wird dort vom Integrator eingesetzt. Bis dahin
 * ist das Setting settings-seitig voll funktional (GET/PUT persistiert),
 * pipeline-seitig aber inert: ohne den Supplier fährt der Provider seine
 * byte-neutralen ENV-Seeds.
 *
 * Pfad-Auflösung exakt das `extended-think.json`-Muster: explizit
 * (`hoshi.weather-location.path` / `HOSHI_WEATHER_LOCATION_PATH`) ▷
 * `~/.hoshi/weather-location.json`.
 */
@Configuration
class WeatherLocationConfig {

    @Bean
    fun weatherLocationStore(
        @Value("\${hoshi.weather-location.path:\${HOSHI_WEATHER_LOCATION_PATH:}}") settingsPath: String,
    ): JsonFileWeatherLocationStore = JsonFileWeatherLocationStore(resolvePath(settingsPath))

    @Bean
    fun openMeteoGeocodingClient(
        @Value("\${hoshi.weather.geocoding-base-url:https://geocoding-api.open-meteo.com}") baseUrl: String,
    ): OpenMeteoGeocodingClient = OpenMeteoGeocodingClient(baseUrl = baseUrl)

    /**
     * Der Lese-Griff des Read-Endpoints `GET /api/v1/weather/today`
     * ([WeatherTodayController]): eine EIGENE [WeatherGroundingProvider]-Instanz
     * mit EXAKT den PipelineConfig-Seeds (gleiche Properties/Envs — bitte
     * synchron halten) + DERSELBEN Store-Instanz wie Settings-Rand und
     * Turn-Pipeline: eine Wahrheit, dritter Leser; ein Settings-PUT greift ab
     * dem nächsten Request. Kein Geocoding nötig (der Read-Pfad hat keine Frage
     * mit explizitem Ort).
     *
     * BEWUSST als [WeatherTodayReader] verpackt statt als Provider-Bean: der
     * Provider implementiert `GroundingPort` — eine zweite GroundingPort-Bean
     * würde die `grounding`-Injektion der Pipeline mehrdeutig machen
     * (Boot-Bruch). PipelineConfig bleibt unangetastet.
     */
    @Bean
    fun weatherTodayReader(
        @Value("\${hoshi.weather.base-url:https://api.open-meteo.com}") baseUrl: String,
        @Value("\${hoshi.weather.lat:\${HOSHI_WEATHER_LAT:52.52}}") lat: Double,
        @Value("\${hoshi.weather.lon:\${HOSHI_WEATHER_LON:13.41}}") lon: Double,
        @Value("\${hoshi.weather.label:\${HOSHI_WEATHER_LABEL:Berlin}}") label: String,
        store: JsonFileWeatherLocationStore,
    ): WeatherTodayReader = WeatherTodayReader(
        WeatherGroundingProvider(
            baseUrl = baseUrl,
            lat = lat,
            lon = lon,
            locationLabel = label,
            locationSupplier = { store.location() },
        ),
    )

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "weather-location.json")
}
