package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.RouteCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [WeatherGroundingProvider] OHNE Live-Netz: ein winziger JDK-HttpServer
 * spielt Open-Meteo und liefert kanned `/v1/forecast`-JSON (7 Tage ab Sonntag,
 * 2026-06-28) und optional `/v1/search`-Geocoding. Pure — der echte
 * Open-Meteo-Beweis bleibt einem Live-Smoke vorbehalten.
 *
 * Tages-Szenarien laufen gegen eine FIXE Uhr (Sonntag 2026-06-28, wie das JSON) —
 * siehe [DayReferenceResolverTest] für die reine Referenz-Matrix.
 */
class WeatherGroundingProviderTest {

    /** Sonntag, 2026-06-28, 12:00 Europe/Berlin — Tag 0 des kanned JSON. */
    private val sunday: Clock =
        Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), DayReferenceResolver.BERLIN)

    private val fixedDays = DayReferenceResolver(sunday)

    /** Open-Meteo-Antwort im echten Format (7 Tage, gekürzt auf die genutzten Felder). */
    private val forecastJson = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "current": { "temperature_2m": 14.2, "weathercode": 61 },
          "daily": {
            "time": ["2026-06-28", "2026-06-29", "2026-06-30", "2026-07-01", "2026-07-02", "2026-07-03", "2026-07-04"],
            "temperature_2m_max": [19.4, 22.1, 24.0, 21.5, 18.2, 20.0, 23.3],
            "temperature_2m_min": [11.3, 13.0, 14.2, 12.8, 10.1, 11.7, 12.9],
            "precipitation_sum": [3.4, 0.0, 0.0, 1.2, 6.7, 0.0, 0.3],
            "weathercode": [61, 2, 0, 3, 63, 1, 2]
          }
        }
    """.trimIndent()

    /** Geocoding-Treffer für „Duisburg" (echtes Format, gekürzt). */
    private val duisburgJson = """
        {
          "results": [
            { "id": 2934691, "name": "Duisburg", "latitude": 51.43247, "longitude": 6.76516, "country": "Deutschland" }
          ]
        }
    """.trimIndent()

    /** Geocoding-Treffer für „Kairo" (Live-Bug-Szenario 2026-07-15). */
    private val cairoJson = """
        {
          "results": [
            { "id": 360630, "name": "Kairo", "latitude": 30.06263, "longitude": 31.24967, "country": "Ägypten" }
          ]
        }
    """.trimIndent()

    /** Kein Treffer: Open-Meteo lässt das `results`-Feld dann komplett weg (Muster [OpenMeteoGeocodingClientTest]). */
    private val noHitJson = """{ "generationtime_ms": 0.4 }"""

    /**
     * Ein Server, zwei Kontexte: `/v1/forecast` (kanned [forecastJson]) und optional
     * `/v1/search` (Geocoding). Captured werden die Query-Strings beider Endpunkte.
     */
    private fun withOpenMeteo(
        json: String,
        status: Int = 200,
        geocodeJson: String? = null,
        block: (String, AtomicReference<String?>, AtomicReference<String?>) -> Unit,
    ) {
        val capturedForecast = AtomicReference<String?>(null)
        val capturedGeocode = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/forecast") { ex ->
            capturedForecast.set(ex.requestURI.query)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        if (geocodeJson != null) {
            server.createContext("/v1/search") { ex ->
                capturedGeocode.set(ex.requestURI.query)
                val bytes = geocodeJson.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", capturedForecast, capturedGeocode)
        } finally {
            server.stop(0)
        }
    }

    private fun block(provider: WeatherGroundingProvider, query: String): String =
        provider.groundingBlock(query, RouteCategory.FACT_SHORT).block(Duration.ofSeconds(5)) ?: ""

    // ── Bestand: Default-Verhalten (ohne Setting, ohne Tages-Referenz) ─────────

    @Test
    fun `Wetter-Frage ohne Tages-Referenz liefert geerdeten Block mit heute+morgen Temp und Bedingung`() =
        // Frage BEWUSST ohne Tages-Referenz („Wie wird das Wetter?") — seit den
        // Tages-Szenarien injiziert eine „morgen?"-Frage NUR noch morgen; der
        // heute+morgen-Default gilt exakt für referenzlose Fragen (wie bisher).
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val block = block(provider, "Wie wird das Wetter?")

            assertTrue(block.isNotBlank(), "Block darf nicht leer sein")
            assertTrue(block.contains("HINTERGRUND"), "Block trägt den Hintergrund-Marker")
            assertTrue(block.contains("Berlin heute"), "heute-Zeile mit Label: $block")
            assertTrue(block.contains("Berlin morgen"), "morgen-Zeile mit Label: $block")
            // heute: 11.3→11 bis 19.4→19 Grad, code 61 = leichter Regen, 3.4 mm
            assertTrue(block.contains("11 bis 19 Grad"), "heute-Temp gerundet: $block")
            assertTrue(block.contains("leichter Regen"), "heute-Bedingung (code 61): $block")
            assertTrue(block.contains("etwa 3 mm Niederschlag"), "heute-Niederschlag: $block")
            // morgen: code 2 = teilweise bewölkt, 0 mm → kaum Niederschlag
            assertTrue(block.contains("teilweise bewölkt"), "morgen-Bedingung (code 2): $block")
            assertTrue(block.contains("kaum Niederschlag"), "morgen ohne Regen: $block")
            // NUR heute+morgen — die restlichen 5 Tage bleiben draußen.
            assertFalse(block.contains("in 2 Tagen"), "ohne Referenz kein dritter Tag: $block")
            // Open-Meteo wurde tatsächlich angefragt (daily-Parameter gesetzt).
            assertTrue((captured.get() ?: "").contains("daily"), "daily-Query gesetzt: ${captured.get()}")
        }

    @Test
    fun `ohne Setting und ohne Referenz ist der Block BYTE-GLEICH zum bisherigen Verhalten`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val expected = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                "• Wetter Berlin heute: 11 bis 19 Grad, leichter Regen, etwa 3 mm Niederschlag.\n" +
                "• Wetter Berlin morgen: 13 bis 22 Grad, teilweise bewölkt, kaum Niederschlag.\n" +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“."
            assertEquals(expected, block(provider, "Wie wird das Wetter?"))
        }

    @Test
    fun `Nicht-Wetter-Frage liefert leeren Block und ruft Open-Meteo nicht`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)
            val block = provider.groundingBlock("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5))
            assertEquals("", block, "keine Wetter-Absicht → kein Block")
            assertNull(captured.get(), "Open-Meteo darf ohne Wetter-Absicht nicht angefragt werden")
        }

    @Test
    fun `Nicht-Wissens-Kategorie groundet nicht (kein API-Call)`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)
            // Wetter-Wort vorhanden, aber Kategorie SMALLTALK → Gate greift.
            val block = provider.groundingBlock("schönes Wetter heute, oder?", RouteCategory.SMALLTALK)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
            assertNull(captured.get(), "Open-Meteo darf bei Nicht-Wissens-Kategorie nicht angefragt werden")
        }

    @Test
    fun `Open-Meteo nicht erreichbar liefert best-effort leeren Block, nie Crash`() {
        // Port, auf dem nichts lauscht → connection refused → leerer Block.
        val provider = WeatherGroundingProvider(baseUrl = "http://127.0.0.1:1", timeout = Duration.ofSeconds(2))
        val block = provider.groundingBlock("Wie wird das Wetter morgen?", RouteCategory.FACT_SHORT)
            .block(Duration.ofSeconds(5))
        assertEquals("", block)
    }

    @Test
    fun `Open-Meteo-Fehler (500) liefert leeren Block`() =
        withOpenMeteo("kaputt", status = 500) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)
            val block = provider.groundingBlock("Regnet es morgen?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
        }

    @Test
    fun `Wetter-Absichts-Erkennung trennt DE+EN Wetterfragen von Wissensfragen`() {
        val p = WeatherGroundingProvider()
        assertTrue(p.isWeatherIntent("Wie wird das Wetter morgen?"))
        assertTrue(p.isWeatherIntent("Regnet es heute?"))
        assertTrue(p.isWeatherIntent("Wie warm wird es?"))
        assertTrue(p.isWeatherIntent("what's the weather tomorrow"))
        assertTrue(p.isWeatherIntent("will it rain"))
        assertEquals(false, p.isWeatherIntent("Wer war Konrad Adenauer?"))
        assertEquals(false, p.isWeatherIntent("Wie geht es dir?"))
    }

    @Test
    fun `WMO-Code zu Text mappt die gaengigen Lagen in DE und EN`() {
        val de = WeatherGroundingProvider.Lang.DE
        val en = WeatherGroundingProvider.Lang.EN
        assertEquals("klar und sonnig", WeatherGroundingProvider.weatherCodeText(0, de))
        assertEquals("clear and sunny", WeatherGroundingProvider.weatherCodeText(0, en))
        assertEquals("teilweise bewölkt", WeatherGroundingProvider.weatherCodeText(2, de))
        assertEquals("leichter Regen", WeatherGroundingProvider.weatherCodeText(61, de))
        assertEquals("light rain", WeatherGroundingProvider.weatherCodeText(61, en))
        assertEquals("leichter Schneefall", WeatherGroundingProvider.weatherCodeText(71, de))
        assertEquals("Gewitter", WeatherGroundingProvider.weatherCodeText(95, de))
        assertEquals("thunderstorm", WeatherGroundingProvider.weatherCodeText(95, en))
        assertEquals("neblig", WeatherGroundingProvider.weatherCodeText(45, de))
        // Unbekannter Code → wechselhaft / changeable.
        assertEquals("wechselhaft", WeatherGroundingProvider.weatherCodeText(123, de))
        assertEquals("changeable", WeatherGroundingProvider.weatherCodeText(123, en))
    }

    // ── Neu: Tages-Szenarien (smarte Injection NUR der gefragten Tage) ─────────

    @Test
    fun `morgen-Frage injiziert NUR morgen (keine heute-Zeile) plus Tages-Anweisung`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)
            val block = block(provider, "Regnet es morgen?")

            assertTrue(block.contains("Berlin morgen"), "morgen-Zeile: $block")
            assertFalse(block.contains("Berlin heute"), "heute bleibt draußen: $block")
            assertTrue(block.contains("13 bis 22 Grad"), "morgen-Temp (Tag 1): $block")
            assertTrue(
                block.contains("Antworte für den gefragten Tag; nenne den Tag beim Namen."),
                "Tages-Anweisung bei expliziter Referenz: $block",
            )
        }

    @Test
    fun `Donnerstag-Frage am Sonntag injiziert NUR den Donnerstag mit praezisem Label`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)
            val block = block(provider, "Wie wird das Wetter am Donnerstag?")

            // Sonntag + 4 = Donnerstag (Tag 4 des JSON: 10.1..18.2, code 63, 6.7 mm).
            assertTrue(block.contains("Berlin am Donnerstag (in 4 Tagen)"), "präzises Label: $block")
            assertTrue(block.contains("10 bis 18 Grad"), "Donnerstag-Temp: $block")
            assertTrue(block.contains("mäßiger Regen"), "Donnerstag-Bedingung (code 63): $block")
            assertTrue(block.contains("etwa 7 mm Niederschlag"), "Donnerstag-Niederschlag: $block")
            assertFalse(block.contains("Berlin heute"), "heute bleibt draußen: $block")
            assertFalse(block.contains("Berlin morgen"), "morgen bleibt draußen: $block")
            // Der Horizont deckt den Wochentag: forecast_days=7.
            assertTrue((captured.get() ?: "").contains("forecast_days=7"), "7-Tage-Horizont: ${captured.get()}")
        }

    @Test
    fun `Wochenend-Frage am Sonntag injiziert heute (So) und den naechsten Samstag`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)
            val block = block(provider, "Wie wird das Wetter am Wochenende?")

            // Sonntag: So = Offset 0 („heute"), nächster Sa = Offset 6.
            assertTrue(block.contains("Berlin heute"), "Sonntag=heute-Zeile: $block")
            assertTrue(block.contains("Berlin am Samstag (in 6 Tagen)"), "Samstag-Zeile: $block")
            assertTrue(block.contains("13 bis 23 Grad"), "Samstag-Temp (Tag 6): $block")
            assertFalse(block.contains("Berlin morgen"), "Montag bleibt draußen: $block")
        }

    @Test
    fun `Referenz jenseits des JSON-Horizonts - leerer Block statt falscher Tage`() =
        withOpenMeteo(forecastJson.replace(Regex("\"temperature_2m_max\": \\[[^]]*]"), "\"temperature_2m_max\": [19.4]")) { url, _, _ ->
            // JSON liefert nur noch Tag 0 → eine Donnerstag-Frage (Offset 4) findet nichts.
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)
            assertEquals("", block(provider, "Wie wird das Wetter am Donnerstag?"))
        }

    // ── Neu: Laufzeit-Ort (Supplier) + expliziter Ort in der Frage ─────────────

    @Test
    fun `Ort-Supplier (Settings-Store) gewinnt gegen die Ctor-Seeds`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                locationSupplier = { WeatherLocation("Duisburg", 51.43247, 6.76516) },
            )
            val block = block(provider, "Wie wird das Wetter?")

            assertTrue(block.contains("Wetter Duisburg heute"), "Store-Label gewinnt: $block")
            assertTrue((captured.get() ?: "").contains("latitude=51.43247"), "Store-Koordinaten: ${captured.get()}")
        }

    @Test
    fun `Ort-Supplier liefert null (nie gespeichert) - Ctor-Seeds greifen (byte-gleicher Fallback)`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                locationSupplier = { null },
            )
            val block = block(provider, "Wie wird das Wetter?")

            assertTrue(block.contains("Wetter Berlin heute"), "ENV-Seed-Label: $block")
            assertTrue((captured.get() ?: "").contains("latitude=52.52"), "ENV-Seed-Koordinaten: ${captured.get()}")
        }

    @Test
    fun `expliziter Ort in der Frage wird EINMALIG geocodet und ueberschreibt den konfigurierten Ort`() =
        withOpenMeteo(forecastJson, geocodeJson = duisburgJson) { url, capturedForecast, capturedGeocode ->
            var supplierReads = 0
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                locationSupplier = { supplierReads++; null },
                geocoding = OpenMeteoGeocodingClient(baseUrl = url),
                days = fixedDays,
            )
            val block = block(provider, "Wie wird das Wetter morgen in Duisburg?")

            assertTrue(block.contains("Wetter Duisburg morgen"), "aufgelöstes Label dieses Turns: $block")
            assertTrue((capturedGeocode.get() ?: "").contains("name=Duisburg"), "Geocode angefragt: ${capturedGeocode.get()}")
            assertTrue((capturedForecast.get() ?: "").contains("latitude=51.43247"), "Duisburg-Koordinaten: ${capturedForecast.get()}")
            // NICHT gespeichert: der Ort gilt nur für diesen Turn — der Store wird
            // nicht einmal gelesen (kein setLocation existiert hier ohnehin).
            assertEquals(0, supplierReads, "expliziter Ort ⇒ Store bleibt außen vor")
        }

    // ── Live-Bug 2026-07-15: „Wie ist das Wetter in Kairo?" antwortete mit dem
    // Heimat-Wetter (stiller Fallback + Großschreibungs-Pflicht). Fix: Ehrlichkeit
    // statt stillem Fallback + kleingeschrieben-tolerante Orts-Erkennung. ────────

    @Test
    fun `expliziter Ort GROSS geschrieben (in Kairo) wird geocodet und im Block benutzt (Label Kairo)`() =
        withOpenMeteo(forecastJson, geocodeJson = cairoJson) { url, capturedForecast, capturedGeocode ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                geocoding = OpenMeteoGeocodingClient(baseUrl = url),
                days = fixedDays,
            )
            val block = block(provider, "Und wie ist das Wetter in Kairo?")

            assertTrue(block.contains("Wetter Kairo"), "geocodetes Label im Block: $block")
            assertFalse(block.contains("Berlin"), "KEIN Heimat-Wetter untergeschoben: $block")
            assertTrue((capturedGeocode.get() ?: "").contains("name=Kairo"), "Geocode mit dem erkannten Ort: ${capturedGeocode.get()}")
            assertTrue((capturedForecast.get() ?: "").contains("latitude=30.06263"), "Kairo-Koordinaten: ${capturedForecast.get()}")
        }

    @Test
    fun `expliziter Ort KLEIN geschrieben (wetter in kairo) wird trotzdem erkannt und geocodet (Voice-STT-Fix)`() =
        withOpenMeteo(forecastJson, geocodeJson = cairoJson) { url, capturedForecast, capturedGeocode ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                geocoding = OpenMeteoGeocodingClient(baseUrl = url),
                days = fixedDays,
            )
            val block = block(provider, "wetter in kairo")

            assertTrue(block.contains("Wetter Kairo"), "geocodetes Label auch bei Kleinschreibung: $block")
            assertFalse(block.contains("Berlin"), "KEIN Heimat-Wetter untergeschoben: $block")
            assertTrue((capturedGeocode.get() ?: "").contains("name=kairo"), "Geocode mit dem klein erkannten Ort: ${capturedGeocode.get()}")
            assertTrue((capturedForecast.get() ?: "").contains("latitude=30.06263"), "Kairo-Koordinaten: ${capturedForecast.get()}")
        }

    @Test
    fun `Geocode-Fehlschlag bei explizitem Ort - ehrlicher Hinweis statt stillem Heimat-Fallback, KEIN Forecast-Call`() =
        withOpenMeteo(forecastJson) { url, capturedForecast, _ ->
            // KEIN /v1/search-Kontext auf dem Server ⇒ der Geocode-Call scheitert (404).
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                geocoding = OpenMeteoGeocodingClient(baseUrl = url, timeout = Duration.ofSeconds(2)),
                days = fixedDays,
            )
            val block = block(provider, "Wie wird das Wetter morgen in Duisburg?")

            assertTrue(block.contains("WETTER-HINWEIS"), "ehrlicher Hinweis statt Heimat-Fallback: $block")
            assertTrue(block.contains("„Duisburg“"), "nennt den nicht gefundenen Ort: $block")
            assertFalse(block.contains("Berlin"), "KEIN Heimat-Wetter untergeschoben (der Live-Bug): $block")
            assertNull(capturedForecast.get(), "KEIN Forecast-Call — weder für Duisburg noch für Berlin")
        }

    @Test
    fun `Geocode ohne Treffer bei explizitem Ort - ehrlicher Hinweis statt stillem Heimat-Fallback, KEIN Forecast-Call`() =
        withOpenMeteo(forecastJson, geocodeJson = noHitJson) { url, capturedForecast, capturedGeocode ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                geocoding = OpenMeteoGeocodingClient(baseUrl = url),
                days = fixedDays,
            )
            val block = block(provider, "Wie wird das Wetter morgen in Xyzzyburg?")

            assertTrue(block.contains("WETTER-HINWEIS"), "ehrlicher Hinweis statt Heimat-Fallback: $block")
            assertTrue(block.contains("„Xyzzyburg“"), "nennt den nicht gefundenen Ort: $block")
            assertFalse(block.contains("Berlin"), "KEIN Heimat-Wetter untergeschoben: $block")
            assertTrue((capturedGeocode.get() ?: "").contains("name=Xyzzyburg"), "Geocode wurde versucht: ${capturedGeocode.get()}")
            assertNull(capturedForecast.get(), "KEIN Forecast-Call — weder für Xyzzyburg noch für Berlin")
        }

    @Test
    fun `Zeit-Phrasen nach 'in' sind KEIN Orts-Kandidat - Heimat-Pfad byte-identisch`() =
        withOpenMeteo(forecastJson) { url, _, capturedGeocode ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                geocoding = OpenMeteoGeocodingClient(baseUrl = url),
            )
            val expected = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                "• Wetter Berlin heute: 11 bis 19 Grad, leichter Regen, etwa 3 mm Niederschlag.\n" +
                "• Wetter Berlin morgen: 13 bis 22 Grad, teilweise bewölkt, kaum Niederschlag.\n" +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“."

            assertEquals(expected, block(provider, "Wetter in zwei Tagen"), "Zahlwort-Stoppwort ⇒ Heimat-Pfad byte-identisch")
            assertEquals(expected, block(provider, "wie ist das Wetter in der Zukunft"), "Artikel-Stoppwort ⇒ Heimat-Pfad byte-identisch")
            assertNull(capturedGeocode.get(), "kein Orts-Kandidat erkannt ⇒ Geocode wird nie angefragt")
        }

    // ── Neu: WeatherNumberContract (Verbatim-«»-Marker, Muster WikiNumberContract) ─
    // Live-Befund 2026-07-16: „Wetter morgen?" trug im Block 17,1–22,7°, gesprochen
    // wurde „17–20" (Obergrenze verstümmelt); „Wetter in Kairo?" grounded nachweislich
    // Kairo, die Antwort klang trotzdem nach Heimat-Werten — das 4B paraphrasiert den
    // HINTERGRUND-Block frei statt Zahlen/Ort wörtlich zu übernehmen.

    @Test
    fun `WeatherNumberContract ON markiert Ort und Min-Max-Temperatur jeder Tages-Zeile mit Guillemets`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                enableWeatherContract = true,
            )
            val block = block(provider, "Wie wird das Wetter?")

            assertTrue(block.contains("Wetter «Berlin» heute: «11» bis «19» Grad"), "Ort+Min+Max heute markiert: $block")
            assertTrue(block.contains("Wetter «Berlin» morgen: «13» bis «22» Grad"), "Ort+Min+Max morgen markiert: $block")
            assertTrue(block.contains("«leichter Regen»"), "Wetterlage markiert: $block")
            assertTrue(block.contains("WETTER-VERTRAG:"), "Zitier-Instruktion steht im Block: $block")
            assertTrue(block.contains("gleicher Ortsname, gleiche Ziffern"), "Instruktions-Text: $block")
        }

    @Test
    fun `WeatherNumberContract OFF (Default) laesst den Block byte-identisch zum bisherigen Verhalten`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val on = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", enableWeatherContract = true)
            val off = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")

            val onBlock = block(on, "Wie wird das Wetter?")
            val offBlock = block(off, "Wie wird das Wetter?")

            val expectedOff = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                "• Wetter Berlin heute: 11 bis 19 Grad, leichter Regen, etwa 3 mm Niederschlag.\n" +
                "• Wetter Berlin morgen: 13 bis 22 Grad, teilweise bewölkt, kaum Niederschlag.\n" +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“."
            assertEquals(expectedOff, offBlock, "OFF (Default) bleibt byte-identisch zum bisherigen Block")
            assertFalse(offBlock.contains("«"), "OFF: kein Marker-Zeichen im Block")
            assertFalse(offBlock.contains("WETTER-VERTRAG"), "OFF: keine Zusatz-Instruktion")
            // ON markiert INLINE in jeder Tages-Zeile (anders als der Wiki-Vertrag, der
            // additiv NACH dem Block anhängt) — daher kein startsWith, sondern: entfernt
            // man die Marker+Vertrag-Zusätze aus ON, bleibt exakt der OFF-Text übrig.
            val onWithoutMarkers = onBlock.filterNot { it == '«' || it == '»' }
                .substringBefore("\nWETTER-VERTRAG:")
            assertEquals(offBlock, onWithoutMarkers, "ON ohne Marker+Vertrag ist wortgleich zu OFF")
        }

    @Test
    fun `WeatherNumberContract ON markiert auch den geocodeten expliziten Ort (Kairo-Szenario)`() =
        withOpenMeteo(forecastJson, geocodeJson = cairoJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                geocoding = OpenMeteoGeocodingClient(baseUrl = url),
                days = fixedDays,
                enableWeatherContract = true,
            )
            val block = block(provider, "Und wie ist das Wetter in Kairo?")

            assertTrue(block.contains("«Kairo»"), "geocodetes Label wird markiert: $block")
            assertFalse(block.contains("Berlin"), "kein Heimat-Wetter untergeschoben: $block")
        }

    // ── Neu: kleiner Lese-Pfad (todayForecast) für GET /api/v1/weather/today ──

    @Test
    fun `todayForecast liefert die heutigen Werte am konfigurierten Ort (Seeds)`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertEquals("Berlin", today.label)
            assertEquals(11, today.todayMin, "Tag 0: 11.3 gerundet")
            assertEquals(19, today.todayMax, "Tag 0: 19.4 gerundet")
            assertEquals("leichter Regen", today.codeText, "Code 61 → DE-Text")
            assertEquals(3.4, today.precipMm)
            assertTrue((captured.get() ?: "").contains("latitude=52.52"), "Seed-Koordinaten: ${captured.get()}")
        }

    @Test
    fun `todayForecast - Store-Ort gewinnt (dieselbe Wahrheit wie das Grounding)`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                locationSupplier = { WeatherLocation("Duisburg", 51.43247, 6.76516) },
            )
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertEquals("Duisburg", today.label)
            assertTrue((captured.get() ?: "").contains("latitude=51.43247"), "Store-Koordinaten: ${captured.get()}")
        }

    @Test
    fun `todayForecast propagiert Fehler ehrlich (KEIN best-effort-Schlucken wie beim Grounding)`() {
        val provider = WeatherGroundingProvider(baseUrl = "http://127.0.0.1:1", timeout = Duration.ofSeconds(2))
        assertThrows(Exception::class.java) {
            provider.todayForecast().block(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `todayForecast - kaputtes JSON ist ein leeres Mono (nie Fake-Werte)`() =
        withOpenMeteo("kaputt") { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)
            assertNull(provider.todayForecast().block(Duration.ofSeconds(5)))
        }

    @Test
    fun `expliziter-Ort-Erkenner erkennt GROSS UND klein (Bigram erlaubt), aber keine Zahlen oder Zeit-Stoppwoerter`() {
        // WICHTIG (Live-Bug-Fix 2026-07-15): früher war dieser Erkenner auf
        // Großschreibung beschränkt — Voice-/STT-Transkripte kommen aber oft
        // komplett kleingeschrieben rein („wetter in kairo"), was den Ort NIE traf
        // und still das Heimat-Wetter lieferte. Jetzt: GROSS UND klein, abgesichert
        // durch [WeatherGroundingProvider.placeInQuery]s Stoppwort-Filter.
        val p = WeatherGroundingProvider()
        assertEquals("Duisburg", p.explicitPlace("Wie wird das Wetter morgen in Duisburg?"))
        assertEquals("Bad Homburg", p.explicitPlace("Regnet es in Bad Homburg?"))
        assertNull(p.explicitPlace("Wie wird das Wetter morgen?"), "kein Ort ⇒ null")
        assertEquals("duisburg", p.explicitPlace("wetter in duisburg"), "kleingeschrieben wird jetzt erkannt (STT-Fix)")
        assertNull(p.explicitPlace("Regnet es in 3 Tagen?"), "Zahl ⇒ kein Ort")
        // Zeit-/artikelhafte Stoppwörter nach „in" sind NIE ein Ort — auch nicht
        // kleingeschrieben (verhindert Fehltreffer durch die neue Toleranz).
        assertNull(p.explicitPlace("Wetter in zwei Tagen"), "Zahlwort-Stoppwort ⇒ kein Ort")
        assertNull(p.explicitPlace("wie ist das Wetter in der Zukunft"), "Artikel-Stoppwort ⇒ kein Ort")
        assertNull(p.explicitPlace("Regnet es in einem Urlaub?"), "Stoppwort ⇒ kein Ort")
    }
}
