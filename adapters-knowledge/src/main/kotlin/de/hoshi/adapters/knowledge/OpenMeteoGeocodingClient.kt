package de.hoshi.adapters.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **OpenMeteoGeocodingClient** — löst einen Orts-NAMEN („Duisburg") über die
 * freie Open-Meteo-Geocoding-API (`https://geocoding-api.open-meteo.com/v1/search`,
 * KEIN API-Key) in eine [WeatherLocation] (Label + lat/lon) auf. 0.5-Referenz:
 * `?name=…&count=1&language=de` — der beste Treffer gewinnt.
 *
 * Semantik der Rückgabe (bewusst zweigeteilt, damit Aufrufer EHRLICH bleiben
 * können):
 *  - **leeres Mono** ⇒ die API hat geantwortet, kennt den Ort aber NICHT
 *    (kein `results`-Treffer) — der Settings-Rand macht daraus ein ehrliches 404.
 *  - **Fehler-Mono** ⇒ Netz/Timeout/HTTP-Fehler — die API war nicht erreichbar.
 *    Der [WeatherGroundingProvider] schluckt das best-effort (Fallback auf den
 *    konfigurierten Ort), der Settings-Rand meldet es ehrlich.
 *
 * Spring-entkoppelt wie [WeatherGroundingProvider] (kein `@Service`): Basis-URL
 * über Konstruktor (Tests zeigen auf einen lokalen Mock), WebClient intern gebaut.
 */
class OpenMeteoGeocodingClient(
    /** Geocoding-Basis-URL (überschreibbar für Tests/Mirror). */
    baseUrl: String = "https://geocoding-api.open-meteo.com",
    private val timeout: Duration = Duration.ofSeconds(4),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl.trimEnd('/'))
        .build()

    /**
     * Geocodet [place] → bester Treffer als [WeatherLocation]; leer, wenn die API
     * den Ort nicht kennt; Fehler, wenn sie nicht erreichbar war (siehe Klassen-Doc).
     */
    fun geocode(place: String): Mono<WeatherLocation> =
        client.get()
            .uri { b ->
                b.path("/v1/search")
                    .queryParam("name", place)
                    .queryParam("count", 1)
                    .queryParam("language", "de")
                    .build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(timeout)
            .flatMap { body ->
                val hit = parseFirst(body)
                if (hit == null) {
                    log.info("[weather-geocode] kein Treffer für '{}'", place)
                    Mono.empty()
                } else {
                    Mono.just(hit)
                }
            }

    /** Erster `results`-Treffer → [WeatherLocation]; fehlend/kaputt → `null`. */
    private fun parseFirst(body: String): WeatherLocation? = runCatching {
        val first = mapper.readTree(body).path("results").path(0)
        val label = first.path("name").asText("")
        val lat = first.path("latitude")
        val lon = first.path("longitude")
        if (label.isBlank() || !lat.isNumber || !lon.isNumber) return null
        WeatherLocation(label = label, lat = lat.asDouble(), lon = lon.asDouble())
    }.getOrNull()
}
