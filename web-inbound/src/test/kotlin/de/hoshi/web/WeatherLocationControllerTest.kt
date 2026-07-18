package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.knowledge.OpenMeteoGeocodingClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.ResponseEntity
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration

/**
 * **WeatherLocationControllerTest** — der Settings-Vertrag des Wetter-Ort-Rands,
 * OHNE Spring-Context und OHNE Live-Netz: der Controller wird direkt konstruiert
 * (die `@Value`-Params sind schlichte Ctor-Args), ein JDK-HttpServer spielt die
 * Open-Meteo-Geocoding-API (Muster `WeatherGroundingProviderTest`).
 *
 * Vertrag: PUT {place} ⇒ Geocode ⇒ Store ⇒ 200 mit AUFGELÖSTEM Label;
 * unbekannter Ort ⇒ 404 („Ort nicht gefunden."); leerer place ⇒ 400;
 * Wetter beim Deploy aus ⇒ 409 (kein Geocode, kein Store-Write);
 * Geocoding-API weg ⇒ 502. Die Perimeter-Wand (401 ohne Token) deckt
 * [PerimeterWallTest] für ALLE `/api/v1`-Pfade.
 */
class WeatherLocationControllerTest {

    private val duisburgJson = """
        {
          "results": [
            { "id": 2934691, "name": "Duisburg", "latitude": 51.43247, "longitude": 6.76516, "country": "Deutschland" }
          ]
        }
    """.trimIndent()

    private val noHitJson = """{ "generationtime_ms": 0.4 }"""

    private fun withGeocoder(json: String, block: (OpenMeteoGeocodingClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/search") { ex ->
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(OpenMeteoGeocodingClient(baseUrl = "http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    private fun controller(
        store: JsonFileWeatherLocationStore,
        geocoding: OpenMeteoGeocodingClient,
        weatherEnabled: Boolean = true,
    ) = WeatherLocationController(
        store = store,
        geocoding = geocoding,
        weatherEnabled = weatherEnabled,
        seedLat = 52.52,
        seedLon = 13.41,
        seedLabel = "Berlin",
    )

    private fun put(c: WeatherLocationController, place: String?): ResponseEntity<Any> =
        c.setLocation(WeatherLocationRequest(place)).block(Duration.ofSeconds(5))!!

    @Test
    fun `GET ohne gespeicherten Ort - ENV-Seed (Berlin), fromStore false`(@TempDir dir: Path) =
        withGeocoder(duisburgJson) { geo ->
            val c = controller(JsonFileWeatherLocationStore(dir.resolve("loc.json")), geo)
            val view = c.weatherLocation()
            assertEquals("Berlin", view.label)
            assertEquals(false, view.fromStore)
            assertEquals(true, view.weatherEnabled)
        }

    @Test
    fun `PUT bekannter Ort - 200 mit aufgeloestem Label, Store persistiert (Restart-Beweis)`(@TempDir dir: Path) =
        withGeocoder(duisburgJson) { geo ->
            val path = dir.resolve("loc.json")
            val store = JsonFileWeatherLocationStore(path)
            val res = put(controller(store, geo), "Duisburg")

            assertEquals(200, res.statusCode.value())
            val view = res.body as WeatherLocationView
            assertEquals("Duisburg", view.label, "aufgelöstes Label aus dem Geocoder")
            assertEquals(51.43247, view.lat)
            assertEquals(true, view.fromStore)

            // Restart-Beweis: ein NEUER Store liest den persistierten Ort.
            assertEquals("Duisburg", JsonFileWeatherLocationStore(path).location()?.label)
        }

    @Test
    fun `PUT unbekannter Ort - ehrlich 404 Ort nicht gefunden, KEIN Store-Write`(@TempDir dir: Path) =
        withGeocoder(noHitJson) { geo ->
            val store = JsonFileWeatherLocationStore(dir.resolve("loc.json"))
            val res = put(controller(store, geo), "Xyzzyburg")

            assertEquals(404, res.statusCode.value())
            val err = res.body as SettingsError
            assertEquals("place-not-found", err.error)
            assertEquals("Ort nicht gefunden.", err.message)
            assertNull(store.location(), "unbekannter Ort ⇒ kein Store-Write")
        }

    @Test
    fun `PUT leerer place - 400`(@TempDir dir: Path) =
        withGeocoder(duisburgJson) { geo ->
            val c = controller(JsonFileWeatherLocationStore(dir.resolve("loc.json")), geo)
            assertEquals(400, put(c, "   ").statusCode.value())
            assertEquals(400, put(c, null).statusCode.value())
            assertEquals("Bitte einen Ort angeben.", (put(c, "").body as SettingsError).message)
        }

    @Test
    fun `PUT bei Wetter-OFF - ehrlich 409 deploy-disabled, KEIN Store-Write`(@TempDir dir: Path) =
        withGeocoder(duisburgJson) { geo ->
            val store = JsonFileWeatherLocationStore(dir.resolve("loc.json"))
            val res = put(controller(store, geo, weatherEnabled = false), "Duisburg")

            assertEquals(409, res.statusCode.value())
            val err = res.body as SettingsError
            assertEquals("deploy-disabled", err.error)
            assertEquals("Beim Deploy deaktiviert; greift nicht.", err.message)
            assertNull(store.location(), "Decke zu ⇒ kein Store-Write")
        }

    @Test
    fun `Geocoding-API nicht erreichbar - ehrlich 502 statt fake-404`(@TempDir dir: Path) {
        val store = JsonFileWeatherLocationStore(dir.resolve("loc.json"))
        val geo = OpenMeteoGeocodingClient(baseUrl = "http://127.0.0.1:1", timeout = Duration.ofSeconds(2))
        val res = put(controller(store, geo), "Duisburg")

        assertEquals(502, res.statusCode.value())
        assertEquals("geocoding-unreachable", (res.body as SettingsError).error)
    }

    @Test
    fun `GET nach PUT - Store-Wert gewinnt gegen den ENV-Seed (eine Wahrheit, zwei Leser)`(@TempDir dir: Path) =
        withGeocoder(duisburgJson) { geo ->
            val store = JsonFileWeatherLocationStore(dir.resolve("loc.json"))
            val c = controller(store, geo)
            put(c, "Duisburg")

            val view = c.weatherLocation()
            assertEquals("Duisburg", view.label)
            assertTrue(view.fromStore)
        }
}
