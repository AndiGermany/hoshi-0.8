package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.WeatherLocationAskPort
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * **WeatherLocationAskAdapter** — die reale Implementierung der Wetter-Orts-
 * Nachfrage-Naht ([WeatherLocationAskPort], Wetter S3: „hoshi soll nachfragen,
 * wenn kein ort hinterlegt ist").
 *
 * **Das „kein Ort konfiguriert"-Kriterium (begründeter Entscheid):**
 * `Store leer UND Deploy-Seeds == Code-Defaults` ([seedsAreCodeDefaults], im
 * Wiring als `lat==DEFAULT_LAT && lon==DEFAULT_LON && label==DEFAULT_LABEL`
 * berechnet — eine Wahrheit, [WeatherGroundingProvider.DEFAULT_LAT] & Co).
 * BEWUSST KEIN neues `hoshi.weather.location-configured`-Flag: ein Flag kann
 * gegen die Realität driften (Flag true ohne Ort / Flag false trotz Seeds —
 * zwei Wahrheiten), während die Seeds selbst nicht lügen können — ein Deploy,
 * das `HOSHI_WEATHER_LAT/LON/LABEL` setzt, IST konfiguriert. **Prod hat echte
 * Seeds ⇒ [seedsAreCodeDefaults]=false ⇒ [needsLocation] ist konstant false ⇒
 * die Nachfrage feuert dort NIE, das Verhalten bleibt byte-gleich.** Rest-Kante
 * (dokumentiert): setzt jemand die Seeds ABSICHTLICH exakt auf die Code-Defaults
 * (Berlin-Mitte 52.52/13.41/„Berlin"), ist das von „unkonfiguriert" nicht
 * unterscheidbar — er bekommt EINE Nachfrage, antwortet einmal, der Store ist
 * gesetzt und die Nachfrage kommt nie wieder (selbstheilend, nie falsch).
 *
 * [needsLocation] spiegelt exakt die Bedingungen, unter denen der
 * [WeatherGroundingProvider] sonst einen Block mit dem FALSCHEN Default-Ort
 * injizieren würde (geteilte companion-Wahrheiten — nie zwei Listen):
 * Wissens-Kategorie + Wetter-Absicht + KEIN expliziter Ort in der Frage
 * (den geocodet der Provider pro Turn selbst) + Store leer + Seeds Default.
 *
 * [resolveAndStore] ist der Turn-Zwilling des Settings-PUT
 * (`WeatherLocationController`): Geocode (Open-Meteo, keyless) → persistenter
 * [storeLocation] (DIESELBE Store-Wahrheit — „Ich merk's mir" wird wörtlich
 * wahr, GET /settings/weather-location zeigt den Ort danach an). Best-effort
 * per Doktrin: kein Treffer / Geocoding weg / Persist-Fehler ⇒ leeres Mono
 * (der Orchestrator läuft als normaler Turn weiter, nie Crash) — der Fehler
 * wird geloggt, nie verschluckt-still. Der Store-Write (Datei-I/O) läuft via
 * `publishOn(boundedElastic)` NIE auf dem Reactor-Event-Loop (Bestands-Muster).
 */
class WeatherLocationAskAdapter(
    /**
     * TRUE gdw. die Deploy-Seeds noch die Code-Defaults sind (kein ENV-Ort
     * gesetzt) — im Wiring EINMAL beim Boot berechnet. FALSE (Prod mit echten
     * Seeds) macht [needsLocation] konstant false ⇒ byte-gleiches Verhalten.
     */
    private val seedsAreCodeDefaults: Boolean,
    /** Laufzeit-Store-LESER (Cache-Read; dieselbe Instanz wie der Settings-PUT-Rand). */
    private val storedLocation: () -> WeatherLocation?,
    /** Laufzeit-Store-SCHREIBER (persist-then-commit; wirft bei Persist-Fehler). */
    private val storeLocation: (WeatherLocation) -> Unit,
    /** Geocoding (Open-Meteo, keyless) — dieselbe Client-Instanz wie der PUT-Rand. */
    private val geocoding: OpenMeteoGeocodingClient,
) : WeatherLocationAskPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun needsLocation(query: String, category: RouteCategory): Boolean =
        seedsAreCodeDefaults &&
            WeatherGroundingProvider.isKnowledgeCategory(category) &&
            WeatherGroundingProvider.weatherIntent(query) &&
            WeatherGroundingProvider.placeInQuery(query) == null &&
            storedLocation() == null

    override fun resolveAndStore(place: String): Mono<String> =
        geocoding.geocode(place)
            // Store-Write = Datei-I/O → nie auf dem WebClient-Event-Loop.
            .publishOn(Schedulers.boundedElastic())
            .map { resolved ->
                storeLocation(resolved)
                resolved.label
            }
            .onErrorResume { e ->
                // Geocoding weg ODER Persist fehlgeschlagen → best-effort leeres
                // Mono: der Orchestrator läuft als normaler Turn weiter (nie Crash,
                // nie ein „gemerkt!" ohne bewiesenen Persist — kein fake-grün).
                log.warn("[weather-ask] Geocode/Persist '{}' fehlgeschlagen ({}) — normaler Turn", place, e.message)
                Mono.empty()
            }
}
