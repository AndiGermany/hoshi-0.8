package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.RouteCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Beweist den [WeatherLocationAskAdapter] OHNE Live-Netz (Muster
 * [OpenMeteoGeocodingClientTest]: ein JDK-HttpServer spielt die Open-Meteo-
 * Geocoding-API):
 *
 *  - **needsLocation-Kriterium** („kein Ort konfiguriert" = Store leer UND
 *    Seeds auf den Code-Defaults): feuert NUR bei Wetter-Absicht in einer
 *    Wissens-Kategorie ohne expliziten Orts-Namen — und in Prod (echte Seeds
 *    ⇒ [WeatherLocationAskAdapter]-Ctor `seedsAreCodeDefaults=false`) NIE.
 *  - **resolveAndStore** = Turn-Zwilling des Settings-PUT: Treffer ⇒ Store +
 *    Label; kein Treffer/API weg/Persist-Fehler ⇒ leeres Mono, NIE ein Store-
 *    Write ohne Treffer, NIE ein Label ohne bewiesenen Persist (kein fake-grün).
 */
class WeatherLocationAskAdapterTest {

    private val weatherQuestion = "Wie wird das Wetter morgen?"

    /** Geocoding-Antwort im echten Format (gekürzt auf die genutzten Felder). */
    private val duisburgJson = """
        {
          "results": [
            { "id": 2934691, "name": "Duisburg", "latitude": 51.43247, "longitude": 6.76516, "country": "Deutschland" }
          ],
          "generationtime_ms": 0.7
        }
    """.trimIndent()

    /** Kein Treffer: Open-Meteo lässt das `results`-Feld dann komplett weg. */
    private val noHitJson = """{ "generationtime_ms": 0.4 }"""

    private fun withGeocoder(json: String, status: Int = 200, block: (OpenMeteoGeocodingClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/search") { ex ->
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(OpenMeteoGeocodingClient(baseUrl = "http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    /** Ein Geocoding-Client, der NIE erreicht werden darf (needsLocation ruft kein Netz). */
    private fun deadGeocoder() = OpenMeteoGeocodingClient(
        baseUrl = "http://127.0.0.1:1",
        timeout = Duration.ofMillis(200),
    )

    private fun adapter(
        seedsAreCodeDefaults: Boolean = true,
        stored: WeatherLocation? = null,
        store: (WeatherLocation) -> Unit = {},
        geocoding: OpenMeteoGeocodingClient = deadGeocoder(),
    ) = WeatherLocationAskAdapter(
        seedsAreCodeDefaults = seedsAreCodeDefaults,
        storedLocation = { stored },
        storeLocation = store,
        geocoding = geocoding,
    )

    // ── needsLocation: das „kein Ort konfiguriert"-Kriterium ─────────────────────
    @Test
    fun `needsLocation - Wetter-Frage ohne Ort, Store leer, Seeds Default - true`() {
        assertTrue(adapter().needsLocation(weatherQuestion, RouteCategory.FACT_SHORT))
        assertTrue(adapter().needsLocation(weatherQuestion, RouteCategory.NEEDS_WEB))
        assertTrue(adapter().needsLocation(weatherQuestion, RouteCategory.AMBIG))
    }

    @Test
    fun `needsLocation - PROD-Fall - echte Seeds gesetzt - konstant false, NIE eine Nachfrage`() {
        val prod = adapter(seedsAreCodeDefaults = false)
        assertFalse(prod.needsLocation(weatherQuestion, RouteCategory.FACT_SHORT))
        assertFalse(prod.needsLocation("Regnet es heute?", RouteCategory.NEEDS_WEB))
    }

    @Test
    fun `needsLocation - Store traegt einen Ort - false`() {
        val a = adapter(stored = WeatherLocation("Duisburg", 51.43, 6.77))
        assertFalse(a.needsLocation(weatherQuestion, RouteCategory.FACT_SHORT))
    }

    @Test
    fun `needsLocation - expliziter Ort in der Frage - false (der Provider geocodet ihn selbst)`() {
        assertFalse(adapter().needsLocation("Wie wird das Wetter morgen in Duisburg?", RouteCategory.FACT_SHORT))
    }

    @Test
    fun `needsLocation - keine Wetter-Absicht - false`() {
        assertFalse(adapter().needsLocation("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT))
    }

    @Test
    fun `needsLocation - Nicht-Wissens-Kategorie - false (identisches Gate wie der Grounding-Block)`() {
        assertFalse(adapter().needsLocation(weatherQuestion, RouteCategory.SMART_HOME))
        assertFalse(adapter().needsLocation(weatherQuestion, RouteCategory.SMALLTALK))
    }

    // ── resolveAndStore: der Turn-Zwilling des Settings-PUT ──────────────────────
    @Test
    fun `resolveAndStore - Treffer - Ort wird GESPEICHERT und das aufgeloeste Label geliefert`() =
        withGeocoder(duisburgJson) { geocoder ->
            val storeWrites = mutableListOf<WeatherLocation>()
            val a = adapter(store = { storeWrites += it }, geocoding = geocoder)

            val label = a.resolveAndStore("Duisburg").block(Duration.ofSeconds(5))

            assertEquals("Duisburg", label, "das AUFGELÖSTE Geocoder-Label")
            assertEquals(
                listOf(WeatherLocation("Duisburg", 51.43247, 6.76516)),
                storeWrites,
                "genau EIN Store-Write mit dem Geocode-Treffer (wie der Settings-PUT)",
            )
        }

    @Test
    fun `resolveAndStore - kein Treffer - leeres Mono, NIE ein Store-Write`() =
        withGeocoder(noHitJson) { geocoder ->
            val storeWrites = mutableListOf<WeatherLocation>()
            val a = adapter(store = { storeWrites += it }, geocoding = geocoder)

            val result = a.resolveAndStore("Xyzzyburg").blockOptional(Duration.ofSeconds(5))

            assertTrue(result.isEmpty, "kein Treffer ⇒ leer (der Orchestrator läuft als normaler Turn)")
            assertTrue(storeWrites.isEmpty(), "ohne Treffer wird NIE gespeichert")
        }

    @Test
    fun `resolveAndStore - API-Fehler 500 - leeres Mono statt Crash, NIE ein Store-Write`() =
        withGeocoder("kaputt", status = 500) { geocoder ->
            val storeWrites = mutableListOf<WeatherLocation>()
            val a = adapter(store = { storeWrites += it }, geocoding = geocoder)

            val result = a.resolveAndStore("Duisburg").blockOptional(Duration.ofSeconds(5))

            assertTrue(result.isEmpty, "API weg ⇒ best-effort leer, nie ein Fehler nach außen")
            assertTrue(storeWrites.isEmpty())
        }

    @Test
    fun `resolveAndStore - API nicht erreichbar - leeres Mono statt Crash`() {
        val a = adapter(geocoding = deadGeocoder())
        val result = a.resolveAndStore("Duisburg").blockOptional(Duration.ofSeconds(5))
        assertTrue(result.isEmpty)
    }

    @Test
    fun `resolveAndStore - Persist wirft - leeres Mono, kein Label ohne bewiesenen Persist`() =
        withGeocoder(duisburgJson) { geocoder ->
            val a = adapter(store = { throw IOException("Platte voll") }, geocoding = geocoder)

            val result = a.resolveAndStore("Duisburg").blockOptional(Duration.ofSeconds(5))

            assertTrue(result.isEmpty, "Persist-Fehler ⇒ leer — NIE »gemerkt!« ohne bewiesenen Persist")
        }
}
