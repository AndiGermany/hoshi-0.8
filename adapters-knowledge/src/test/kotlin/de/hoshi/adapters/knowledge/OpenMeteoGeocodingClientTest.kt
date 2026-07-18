package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [OpenMeteoGeocodingClient] OHNE Live-Netz (Muster
 * [WeatherGroundingProviderTest]): ein JDK-HttpServer spielt die Open-Meteo-
 * Geocoding-API. Vertrag: Treffer ⇒ [WeatherLocation]; kein Treffer/kaputtes
 * JSON ⇒ LEERES Mono (ehrliches „Ort nicht gefunden"); API weg/5xx ⇒ FEHLER-Mono
 * (der Provider fällt best-effort zurück, der Settings-Rand meldet 502).
 */
class OpenMeteoGeocodingClientTest {

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

    private fun withGeocoder(
        json: String,
        status: Int = 200,
        block: (String, AtomicReference<String?>) -> Unit,
    ) {
        val capturedQuery = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/search") { ex ->
            capturedQuery.set(ex.requestURI.query)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", capturedQuery)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Treffer - bester Ort als WeatherLocation, Query mit count=1 und language=de`() =
        withGeocoder(duisburgJson) { url, captured ->
            val loc = OpenMeteoGeocodingClient(baseUrl = url).geocode("Duisburg")
                .block(Duration.ofSeconds(5))

            assertEquals(WeatherLocation("Duisburg", 51.43247, 6.76516), loc)
            val query = captured.get() ?: ""
            assertTrue(query.contains("name=Duisburg"), "name-Parameter gesetzt: $query")
            assertTrue(query.contains("count=1"), "nur der beste Treffer: $query")
            assertTrue(query.contains("language=de"), "deutsche Labels: $query")
        }

    @Test
    fun `kein Treffer - leeres Mono (ehrliches nicht gefunden), kein Fehler`() =
        withGeocoder(noHitJson) { url, _ ->
            val result = OpenMeteoGeocodingClient(baseUrl = url).geocode("Xyzzyburg")
                .blockOptional(Duration.ofSeconds(5))
            assertTrue(result.isEmpty, "kein results-Treffer ⇒ leeres Mono")
        }

    @Test
    fun `kaputtes JSON - leeres Mono, nie Crash`() =
        withGeocoder("{ kein json ]") { url, _ ->
            val result = OpenMeteoGeocodingClient(baseUrl = url).geocode("Duisburg")
                .blockOptional(Duration.ofSeconds(5))
            assertTrue(result.isEmpty)
        }

    @Test
    fun `API-Fehler (500) - Fehler-Mono (Aufrufer entscheidet ehrlich)`() =
        withGeocoder("kaputt", status = 500) { url, _ ->
            StepVerifier.create(OpenMeteoGeocodingClient(baseUrl = url).geocode("Duisburg"))
                .expectError()
                .verify(Duration.ofSeconds(5))
        }

    @Test
    fun `API nicht erreichbar - Fehler-Mono`() {
        val client = OpenMeteoGeocodingClient(baseUrl = "http://127.0.0.1:1", timeout = Duration.ofSeconds(2))
        StepVerifier.create(client.geocode("Duisburg"))
            .expectError()
            .verify(Duration.ofSeconds(5))
    }
}
