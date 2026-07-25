package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.knowledge.WeatherGroundingProvider
import de.hoshi.adapters.knowledge.WeatherLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.ResponseEntity
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * **WeatherTodayControllerTest** — der Lese-Vertrag von `GET /api/v1/weather/today`,
 * OHNE Spring-Context und OHNE Live-Netz: der Controller wird direkt konstruiert
 * (Muster [WeatherLocationControllerTest]), ein JDK-HttpServer spielt Open-Meteo.
 *
 * Vertrag: 200 mit `{label, todayMin, todayMax, codeText, precipMm}` aus dem
 * Provider-Datenpfad (Store-Ort gewinnt); Wetter beim Deploy aus ⇒ ehrlich 404
 * OHNE Upstream-Call; Open-Meteo weg ⇒ ehrlich 502; kaputtes JSON ⇒ ehrlich 502
 * (keine Fake-Werte). Die Perimeter-Wand (401 ohne Token) deckt [PerimeterWallTest]
 * für ALLE `/api/v1`-Pfade.
 */
class WeatherTodayControllerTest {

    /** Open-Meteo-Antwort im echten Format (gekürzt; Tag 0: 11.3–19.4°, Code 61, 3.4 mm). */
    private val forecastJson = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "current": { "temperature_2m": 14.2, "weathercode": 61 },
          "daily": {
            "time": ["2026-07-05", "2026-07-06"],
            "temperature_2m_max": [19.4, 22.1],
            "temperature_2m_min": [11.3, 13.0],
            "precipitation_sum": [3.4, 0.0],
            "weathercode": [61, 2]
          }
        }
    """.trimIndent()

    private fun withOpenMeteo(json: String, block: (String, AtomicReference<String?>) -> Unit) {
        val captured = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/forecast") { ex ->
            captured.set(ex.requestURI.query)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    /** Nie gespeicherter Sprach-Store (Pfad existiert nicht) ⇒ [Language.DEFAULT] (DE) — der Bestands-Fall. */
    private fun freshLanguageStore(): JsonFileLanguageStore =
        JsonFileLanguageStore(Files.createTempDirectory("weather-lang-test").resolve("language.json"))

    private fun controller(
        baseUrl: String,
        weatherEnabled: Boolean = true,
        store: JsonFileWeatherLocationStore? = null,
        languageStore: JsonFileLanguageStore = freshLanguageStore(),
    ) = WeatherTodayController(
        reader = WeatherTodayReader(
            WeatherGroundingProvider(
                baseUrl = baseUrl,
                locationLabel = "Berlin",
                timeout = Duration.ofSeconds(2),
                locationSupplier = store?.let { s -> { s.location() } },
            ),
        ),
        weatherEnabled = weatherEnabled,
        languageStore = languageStore,
    )

    private fun get(c: WeatherTodayController): ResponseEntity<Any> =
        c.today().block(Duration.ofSeconds(5))!!

    @Test
    fun `200 - Shape label todayMin todayMax codeText precipMm aus dem Provider-Datenpfad`() =
        withOpenMeteo(forecastJson) { url, captured ->
            val res = get(controller(url))

            assertEquals(200, res.statusCode.value())
            val body = res.body as WeatherGroundingProvider.TodayForecast
            assertEquals("Berlin", body.label)
            assertEquals(11, body.todayMin, "Tag 0: 11.3 gerundet")
            assertEquals(19, body.todayMax, "Tag 0: 19.4 gerundet")
            assertEquals("leichter Regen", body.codeText, "Code 61 → DE-Text")
            assertEquals(3.4, body.precipMm)
            assertTrue((captured.get() ?: "").contains("daily"), "Open-Meteo wurde angefragt: ${captured.get()}")
        }

    @Test
    fun `Store-Ort gewinnt - dieselbe Wahrheit wie Grounding und Settings-Rand`(@TempDir dir: Path) =
        withOpenMeteo(forecastJson) { url, captured ->
            val store = JsonFileWeatherLocationStore(dir.resolve("loc.json"))
            store.setLocation(WeatherLocation("Duisburg", 51.43247, 6.76516))

            val body = get(controller(url, store = store)).body as WeatherGroundingProvider.TodayForecast
            assertEquals("Duisburg", body.label, "Store-Label gewinnt gegen den Seed")
            assertTrue((captured.get() ?: "").contains("latitude=51.43247"), "Store-Koordinaten: ${captured.get()}")
        }

    @Test
    fun `Wetter beim Deploy aus - ehrlich 404 weather-off, KEIN Open-Meteo-Call`() =
        withOpenMeteo(forecastJson) { url, captured ->
            val res = get(controller(url, weatherEnabled = false))

            assertEquals(404, res.statusCode.value())
            val err = res.body as SettingsError
            assertEquals("weather-off", err.error)
            assertEquals(WeatherTodayController.FEATURE_ID, err.id)
            assertNull(captured.get(), "Decke zu ⇒ kein Upstream-Call")
        }

    @Test
    fun `Open-Meteo weg - ehrlich 502 weather-unreachable statt Fake-Werten`() {
        // Port, auf dem nichts lauscht → connection refused → ehrlicher Fehler.
        val res = get(controller("http://127.0.0.1:1"))

        assertEquals(502, res.statusCode.value())
        val err = res.body as SettingsError
        assertEquals("weather-unreachable", err.error)
        assertEquals("Wetter grad nicht lesbar — Open-Meteo nicht erreichbar.", err.message)
    }

    @Test
    fun `Open-Meteo liefert kaputtes JSON - ehrlich 502 weather-no-data (keine Fake-Werte)`() =
        withOpenMeteo("kaputt") { url, _ ->
            val res = get(controller(url))

            assertEquals(502, res.statusCode.value())
            assertEquals("weather-no-data", (res.body as SettingsError).error)
        }

    // ── Bug-Fix 2026-07-25 (vault/tracks/prep/PREP-i18n-backend-restklassen.md):
    // die Wetter-Kachel zeigte im englischen Modus IMMER deutschen Text
    // („17–31° · bedeckt"), weil `codeText` hart auf Language.DE stand. Jetzt
    // folgt `codeText` der AKTIVEN UI-Sprache ([JsonFileLanguageStore]) — je
    // Sprache mindestens eine Stichprobe, plus der unbekannte-Code-Fall. ───────

    @Test
    fun `codeText folgt der aktiven UI-Sprache (EN) - nicht mehr hart Deutsch`(@TempDir dir: Path) =
        withOpenMeteo(forecastJson) { url, _ ->
            val languageStore = JsonFileLanguageStore(dir.resolve("language.json")).also { it.setLanguageCode("en") }
            val body = get(controller(url, languageStore = languageStore)).body as WeatherGroundingProvider.TodayForecast

            assertEquals("light rain", body.codeText, "Code 61 in EN")
            assertFalse(body.codeText.contains("Regen"), "kein deutsches Wort mehr in der EN-Kachel")
        }

    @Test
    fun `codeText deckt auch ES, FR und IT ab - je eine Stichprobe`(@TempDir dir: Path) =
        withOpenMeteo(forecastJson) { url, _ ->
            val es = JsonFileLanguageStore(dir.resolve("es.json")).also { it.setLanguageCode("es") }
            val fr = JsonFileLanguageStore(dir.resolve("fr.json")).also { it.setLanguageCode("fr") }
            val it = JsonFileLanguageStore(dir.resolve("it.json")).also { it.setLanguageCode("it") }

            assertEquals(
                "lluvia ligera",
                (get(controller(url, languageStore = es)).body as WeatherGroundingProvider.TodayForecast).codeText,
            )
            assertEquals(
                "pluie légère",
                (get(controller(url, languageStore = fr)).body as WeatherGroundingProvider.TodayForecast).codeText,
            )
            assertEquals(
                "pioggia leggera",
                (get(controller(url, languageStore = it)).body as WeatherGroundingProvider.TodayForecast).codeText,
            )
        }

    @Test
    fun `ohne gesetzte UI-Sprache bleibt codeText Deutsch - byte-neutraler Default`(@TempDir dir: Path) =
        withOpenMeteo(forecastJson) { url, _ ->
            // Store existiert, aber es wurde nie eine Sprache gesetzt (languageCode() == null)
            // ⇒ dieselbe Kaskade wie TtsSettingsController: Language.DEFAULT (DE).
            val neverSet = JsonFileLanguageStore(dir.resolve("never-set.json"))
            val body = get(controller(url, languageStore = neverSet)).body as WeatherGroundingProvider.TodayForecast

            assertEquals("leichter Regen", body.codeText, "kein gespeicherter Wert ⇒ Default bleibt Deutsch")
        }

    @Test
    fun `unbekannter WMO-Code liefert in jeder Sprache einen Fallback-Text - nie roh oder leer`(@TempDir dir: Path) {
        // WMO kennt keinen Code 17 — der bestehende Katalog fängt das mit dem
        // "wechselhaft"/"changeable"/… Fallback ab, statt der Kachel eine rohe
        // Zahl oder gar nichts zu zeigen.
        val unknownCodeJson = """
            {
              "latitude": 52.52,
              "longitude": 13.41,
              "current": { "temperature_2m": 14.2, "weathercode": 17 },
              "daily": {
                "time": ["2026-07-05"],
                "temperature_2m_max": [19.4],
                "temperature_2m_min": [11.3],
                "precipitation_sum": [3.4],
                "weathercode": [17]
              }
            }
        """.trimIndent()
        withOpenMeteo(unknownCodeJson) { url, _ ->
            val expectedByLanguage = mapOf(
                "de" to "wechselhaft",
                "en" to "changeable",
                "es" to "variable",
                "fr" to "changeant",
                "it" to "variabile",
            )
            expectedByLanguage.forEach { (code, expected) ->
                val store = JsonFileLanguageStore(dir.resolve("$code.json")).also { it.setLanguageCode(code) }
                val body = get(controller(url, languageStore = store)).body as WeatherGroundingProvider.TodayForecast
                assertEquals(expected, body.codeText, "unbekannter Code 17, Sprache $code")
                assertFalse(body.codeText.isBlank(), "nie leer gesprochen ($code)")
                assertFalse(body.codeText.contains("17"), "nie der rohe WMO-Code ($code): ${body.codeText}")
            }
        }
    }
}
