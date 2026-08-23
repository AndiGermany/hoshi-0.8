package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
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
import java.time.LocalDateTime
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

    /**
     * **Flur-Fertigstellung 2026-07-27:** derselbe Sonntag wie [forecastJson], aber
     * mit den drei NEUEN Bausteinen, die [WeatherGroundingProvider.todayForecast]
     * jetzt zusätzlich parst: `current.time` (Start-Anker für [parseHourly]),
     * `daily.sunrise`/`sunset` und ein `hourly`-Block (00:00–23:00 desselben Tages,
     * „jetzt" = 12:00 ⇒ Index 12). Bewusst NUR Tag 0 mit Stunden hinterlegt (die
     * anderen sechs Tage bräuchten sie für keinen der neuen Tests).
     */
    private val richForecastJson = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "current": { "temperature_2m": 14.2, "weathercode": 61, "time": "2026-06-28T12:00" },
          "daily": {
            "time": ["2026-06-28", "2026-06-29", "2026-06-30", "2026-07-01", "2026-07-02", "2026-07-03", "2026-07-04"],
            "temperature_2m_max": [19.4, 22.1, 24.0, 21.5, 18.2, 20.0, 23.3],
            "temperature_2m_min": [11.3, 13.0, 14.2, 12.8, 10.1, 11.7, 12.9],
            "precipitation_sum": [3.4, 0.0, 0.0, 1.2, 6.7, 0.0, 0.3],
            "weathercode": [61, 2, 0, 3, 63, 1, 2],
            "sunrise": ["2026-06-28T05:02", "2026-06-29T05:03"],
            "sunset": ["2026-06-28T21:34", "2026-06-29T21:33"]
          },
          "hourly": {
            "time": [
              "2026-06-28T00:00", "2026-06-28T01:00", "2026-06-28T02:00", "2026-06-28T03:00",
              "2026-06-28T04:00", "2026-06-28T05:00", "2026-06-28T06:00", "2026-06-28T07:00",
              "2026-06-28T08:00", "2026-06-28T09:00", "2026-06-28T10:00", "2026-06-28T11:00",
              "2026-06-28T12:00", "2026-06-28T13:00", "2026-06-28T14:00", "2026-06-28T15:00",
              "2026-06-28T16:00", "2026-06-28T17:00", "2026-06-28T18:00", "2026-06-28T19:00",
              "2026-06-28T20:00", "2026-06-28T21:00", "2026-06-28T22:00", "2026-06-28T23:00"
            ],
            "temperature_2m": [12,12,11,11,11,12,13,14,15,16,17,18,18,19,19,18,17,16,15,14,13,13,12,12],
            "precipitation_probability": [5,5,5,5,5,10,10,10,15,15,15,15,15,15,20,25,35,45,30,20,15,10,5,5]
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

    /**
     * Ein Grounding-Call in der TURN-Sprache. Default DE — genau die Sprache, in
     * der die Byte-Pin-Tests dieser Datei ihren Block erwarten; die Sprach-Proben
     * setzen [language] explizit.
     */
    private fun block(
        provider: WeatherGroundingProvider,
        query: String,
        language: Language = Language.DE,
    ): String =
        provider.groundingBlock(query, RouteCategory.FACT_SHORT, language).block(Duration.ofSeconds(5)) ?: ""

    // ── Byte-Bausteine der Pin-Tests (Auftrag 2b) ──────────────────────────────
    //
    // Die JETZT-Zeile und die ZEITFORM-Regel kommen ab 2026-08-21 in JEDEM Block
    // vor, der HEUTE enthält. Als Konstanten statt inline, damit ein Wortlaut-
    // Wechsel EINE Stelle ist und die Pin-Tests weiter das prüfen, was sie prüfen
    // sollen (Reihenfolge, Marker, Ort — nicht die Rechtschreibung der Anweisung).

    /** [forecastJson]: `current` OHNE `time`/`precipitation` ⇒ nur Grad + Lage. */
    private val NOW_LINE_DE = "• Wetter Berlin JETZT: 14 Grad, leichter Regen.\n"

    /** [richForecastJson]: `current.time` vorhanden ⇒ zusätzlich der Frische-Marker. */
    private val NOW_LINE_DE_RICH = "• Wetter Berlin JETZT: 14 Grad, leichter Regen, Stand 12:00 Uhr.\n"

    private val TENSE_DE =
        " ZEITFORM: Die JETZT-Zeile gilt für den Augenblick, die Tages-Zeile für den ganzen Tag. " +
            "Sag „es regnet“ NUR, wenn die JETZT-Zeile Niederschlag nennt. " +
            "Niederschlag, der laut Block schon gefallen ist, heißt „heute hat es geregnet“; " +
            "noch erwarteter heißt „es soll noch regnen“ — nie beides verwechseln."

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
            // NEU seit Auftrag 2b (Andi-Livetest 2026-08-21): der Block trägt jetzt
            // eine JETZT-Zeile + die ZEITFORM-Regel. Bewusst KEIN Byte-Rückschritt,
            // sondern die Korrektur selbst — vorher standen hier nur Tageswerte, und
            // genau die hat Hoshi als „grad" ausgegeben („15-irgendwas Grad und
            // Regen", während es trocken war). Die JETZT-Zeile steht UNTEN, weil
            // „Wie WIRD das Wetter?" nach vorne fragt (Futur-Marker, s.
            // [DayReferenceResolver.FUTURE_PATTERN]); bei „Wie IST das Wetter?"
            // steht sie oben — siehe den JETZT-Test weiter unten.
            val expected = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                "• Wetter Berlin heute: 11 bis 19 Grad, leichter Regen, etwa 3 mm Niederschlag.\n" +
                "• Wetter Berlin morgen: 13 bis 22 Grad, teilweise bewölkt, kaum Niederschlag.\n" +
                NOW_LINE_DE +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“." +
                TENSE_DE
            assertEquals(expected, block(provider, "Wie wird das Wetter?"))
        }

    @Test
    fun `Nicht-Wetter-Frage liefert leeren Block und ruft Open-Meteo nicht`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)
            val block = provider.groundingBlock("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT, Language.DE)
                .block(Duration.ofSeconds(5))
            assertEquals("", block, "keine Wetter-Absicht → kein Block")
            assertNull(captured.get(), "Open-Meteo darf ohne Wetter-Absicht nicht angefragt werden")
        }

    @Test
    fun `Nicht-Wissens-Kategorie groundet nicht (kein API-Call)`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)
            // Wetter-Wort vorhanden, aber Kategorie SMALLTALK → Gate greift.
            val block = provider.groundingBlock("schönes Wetter heute, oder?", RouteCategory.SMALLTALK, Language.DE)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
            assertNull(captured.get(), "Open-Meteo darf bei Nicht-Wissens-Kategorie nicht angefragt werden")
        }

    @Test
    fun `Open-Meteo nicht erreichbar liefert best-effort leeren Block, nie Crash`() {
        // Port, auf dem nichts lauscht → connection refused → leerer Block.
        val provider = WeatherGroundingProvider(baseUrl = "http://127.0.0.1:1", timeout = Duration.ofSeconds(2))
        val block = provider.groundingBlock("Wie wird das Wetter morgen?", RouteCategory.FACT_SHORT, Language.DE)
            .block(Duration.ofSeconds(5))
        assertEquals("", block)
    }

    @Test
    fun `Open-Meteo-Fehler (500) liefert leeren Block`() =
        withOpenMeteo("kaputt", status = 500) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)
            val block = provider.groundingBlock("Regnet es morgen?", RouteCategory.FACT_SHORT, Language.DE)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
        }

    @Test
    fun `Wetter-Absichts-Erkennung trennt ganze DE+EN Lexeme von fremden Komposita`() {
        val p = WeatherGroundingProvider()
        assertTrue(p.isWeatherIntent("Wie wird das Wetter morgen?"))
        assertTrue(p.isWeatherIntent("Regnet es heute?"))
        assertTrue(p.isWeatherIntent("Wie warm wird es?"))
        assertTrue(p.isWeatherIntent("Scheint morgen die Sonne?"))
        assertTrue(p.isWeatherIntent("Gibt es heute Sonnenschein?"))
        assertTrue(p.isWeatherIntent("Ist das Sonnencreme-Wetter?"))
        assertTrue(p.isWeatherIntent("Bleibt es bei sonnigem Himmel?"))
        assertTrue(p.isWeatherIntent("Wie entwickelt sich die Wetterlage?"))
        assertTrue(p.isWeatherIntent("what's the weather tomorrow"))
        assertTrue(p.isWeatherIntent("will it rain"))
        assertTrue(p.isWeatherIntent("is rainfall expected"))
        assertFalse(p.isWeatherIntent("Wie viele Planeten gibt es in unserem Sonnensystem?"))
        assertFalse(p.isWeatherIntent("Wann ist die nächste Sonnenfinsternis?"))
        assertFalse(p.isWeatherIntent("Wie funktioniert ein Sonnenkollektor?"))
        assertFalse(p.isWeatherIntent("How fast is a train?"))
        assertFalse(p.isWeatherIntent("Wer war Konrad Adenauer?"))
        assertFalse(p.isWeatherIntent("Wie geht es dir?"))
    }

    @Test
    fun `Sonnensystem und Sonnenfinsternis rufen Open-Meteo nicht und liefern keinen Wetterblock`() =
        withOpenMeteo(forecastJson) { url, captured, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url)

            val solarSystem = block(provider, "Wie viele Planeten gibt es in unserem Sonnensystem?")
            val eclipse = block(provider, "Wann ist die nächste Sonnenfinsternis?")

            assertEquals("", solarSystem)
            assertEquals("", eclipse)
            assertNull(captured.get(), "kein falscher Wettertreffer darf einen Forecast-Call auslösen")
        }

    @Test
    fun `echte Wetter-Lexeme behalten den vollstaendigen deutschen Block bytegleich`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            // Baseline seit 2b im PRÄSENS: die Vergleichsfragen sind es alle drei,
            // und die JETZT-Zeile steht bei Präsens oben, bei Futur unten. Die
            // Zusage dieses Tests ist die LEXEM-Gleichheit („Sonne" groundet wie
            // „Wetter"), nicht die Zeitform — mit „Wie wird" als Baseline würde er
            // ab jetzt die Futur-Erkennung prüfen statt der Absichts-Erkennung.
            val baseline = block(provider, "Wie ist das Wetter?")

            assertEquals(baseline, block(provider, "Scheint die Sonne?"))
            assertEquals(baseline, block(provider, "Gibt es Sonnenschein?"))
            assertEquals(baseline, block(provider, "Ist das Sonnencreme-Wetter?"))
        }

    @Test
    fun `WMO-Code zu Text mappt die gaengigen Lagen in DE und EN`() {
        val de = Language.DE
        val en = Language.EN
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

    // ── Neu (Multilingual-Welle 2026-07-24): WMO-Text jetzt in allen 5 Sprachen ─

    @Test
    fun `DE bleibt nach der Multilingual-Erweiterung WORTGLEICH - Katalog-Stichprobe aller Codes`() {
        // Direkte Katalog-Stichprobe (ergänzt den ON-Block-Pin-Test): JEDER
        // bisherige DE-Code liefert EXAKT denselben Text wie vor der ES/FR/IT-
        // Erweiterung — die Übersetzungs-Arbeit darf DE nicht mal streifen.
        val de = Language.DE
        val expectedDe = mapOf(
            0 to "klar und sonnig",
            1 to "überwiegend klar",
            2 to "teilweise bewölkt",
            3 to "bedeckt",
            45 to "neblig",
            48 to "gefrierender Nebel",
            51 to "leichter Nieselregen",
            53 to "mäßiger Nieselregen",
            55 to "starker Nieselregen",
            56 to "gefrierender Nieselregen",
            57 to "gefrierender Nieselregen",
            61 to "leichter Regen",
            63 to "mäßiger Regen",
            65 to "starker Regen",
            66 to "gefrierender Regen",
            67 to "gefrierender Regen",
            71 to "leichter Schneefall",
            73 to "mäßiger Schneefall",
            75 to "starker Schneefall",
            77 to "Schneekörner",
            80 to "leichte Regenschauer",
            81 to "mäßige Regenschauer",
            82 to "starke Regenschauer",
            85 to "leichte Schneeschauer",
            86 to "starke Schneeschauer",
            95 to "Gewitter",
            96 to "Gewitter mit Hagel",
            99 to "Gewitter mit starkem Hagel",
            123 to "wechselhaft",
        )
        expectedDe.forEach { (code, text) ->
            assertEquals(text, WeatherGroundingProvider.weatherCodeText(code, de), "DE-Code $code unverändert")
        }
    }

    @Test
    fun `WMO-Code zu Text deckt jetzt auch ES, FR und IT ab`() {
        val es = Language.ES
        val fr = Language.FR
        val it = Language.IT
        // Je Sprache mindestens eine Stichprobe über klar/Regen/Gewitter/unbekannt.
        assertEquals("despejado y soleado", WeatherGroundingProvider.weatherCodeText(0, es))
        assertEquals("lluvia ligera", WeatherGroundingProvider.weatherCodeText(61, es))
        assertEquals("tormenta", WeatherGroundingProvider.weatherCodeText(95, es))
        assertEquals("variable", WeatherGroundingProvider.weatherCodeText(123, es))

        assertEquals("ciel clair et ensoleillé", WeatherGroundingProvider.weatherCodeText(0, fr))
        assertEquals("pluie légère", WeatherGroundingProvider.weatherCodeText(61, fr))
        assertEquals("orage", WeatherGroundingProvider.weatherCodeText(95, fr))
        assertEquals("changeant", WeatherGroundingProvider.weatherCodeText(123, fr))

        assertEquals("sereno e soleggiato", WeatherGroundingProvider.weatherCodeText(0, it))
        assertEquals("pioggia leggera", WeatherGroundingProvider.weatherCodeText(61, it))
        assertEquals("temporale", WeatherGroundingProvider.weatherCodeText(95, it))
        assertEquals("variabile", WeatherGroundingProvider.weatherCodeText(123, it))
    }

    @Test
    fun `Turn-Sprache-Naht - EIN Provider liefert je nach Turn-Sprache DE oder EN (kein Adapter-Zustand)`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            // EIN Provider, zwei Turns: die Sprache kommt seit 2026-07-25
            // ausschließlich PRO AUFRUF herein (der Ctor-Sprach-Parameter ist weg,
            // er war nur noch ein Fallback für die abgeschaffte 2-Arg-Signatur).
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")

            val deBlock = block(provider, "Wie wird das Wetter?", Language.DE)
            val enBlock = block(provider, "Wie wird das Wetter?", Language.EN)

            assertTrue(deBlock.contains("leichter Regen"), "DE-Turn: $deBlock")
            assertFalse(deBlock.contains("light rain"), "DE-Turn bleibt DE: $deBlock")
            assertTrue(enBlock.contains("light rain"), "EN-Turn wirkt auf die Wetterlage: $enBlock")
            assertFalse(enBlock.contains("leichter Regen"), "EN ersetzt die DE-Wetterlage: $enBlock")
        }

    // ── Sprach-Naht 2026-07-25: der ganze RAHMEN folgt der Turn-Sprache ────────
    // Befund davor: übersetzter Katalog in einem hart deutschen Rahmen — ein
    // EN-Turn bekam „HINTERGRUND …", „• Wetter … bis … Grad" und eine deutsche
    // „ANWEISUNG: …" und antwortete darum trotz englischer Persona deutsch.

    @Test
    fun `EN-Turn liefert einen KOMPLETT englischen Block - kein deutsches Wort im Rahmen`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)
            val expected = "\n\n---\n" +
                "BACKGROUND (for you only, do NOT mention it in the conversation):\n" +
                "• Weather Berlin today: 11 to 19 degrees, light rain, about 3 mm of precipitation.\n" +
                "• Weather Berlin tomorrow: 13 to 22 degrees, partly cloudy, hardly any precipitation.\n" +
                "• Weather Berlin RIGHT NOW: 14 degrees, light rain.\n" +
                "INSTRUCTION: Use this REAL weather data and answer briefly in your own warm style — " +
                "add nothing you were not given and never mention “the API”, “Open-Meteo” or “the text”." +
                " TENSE: The RIGHT NOW line is about this moment, the day line about the whole day. " +
                "Say “it is raining” ONLY if the RIGHT NOW line states precipitation. " +
                "Precipitation the block says already fell is “it rained today”; " +
                "precipitation still expected is “it is going to rain” — never mix the two up."
            assertEquals(expected, block(provider, "Wie wird das Wetter?", Language.EN))
        }

    @Test
    fun `EN-Turn - expliziter Wochentag traegt englischen Tagesbezug und englische Tages-Anweisung`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)
            val block = block(provider, "How is the weather on Thursday?", Language.EN)

            assertTrue(block.contains("Berlin on Thursday (in 4 days)"), "englischer Tagesbezug: $block")
            assertFalse(block.contains("Donnerstag"), "kein deutscher Wochentag: $block")
            assertFalse(block.contains("in 4 Tagen"), "keine deutsche Tages-Klammer: $block")
            assertTrue(
                block.contains("Answer for the day that was asked about; name that day explicitly."),
                "englische Tages-Anweisung: $block",
            )
        }

    @Test
    fun `EN-Turn - Wetter-Vertrag ON ist ebenfalls englisch (Marker bleiben identisch)`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                enableWeatherContract = true,
                days = fixedDays,
            )
            val block = block(provider, "Wie wird das Wetter?", Language.EN)

            assertTrue(block.contains("WEATHER CONTRACT:"), "englischer Vertrag: $block")
            assertFalse(block.contains("WETTER-VERTRAG"), "kein deutscher Vertrag: $block")
            assertTrue(block.contains("«Berlin»"), "Marker-Vertrag unverändert: $block")
            assertTrue(block.contains("«today»"), "auch der Tagesbezug wird markiert: $block")
        }

    @Test
    fun `EN-Turn - der ehrliche Nicht-gefunden-Hinweis ist englisch, der Ortsname bleibt unuebersetzt`() =
        withOpenMeteo(forecastJson, geocodeJson = noHitJson) { url, capturedForecast, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                geocoding = OpenMeteoGeocodingClient(baseUrl = url),
                days = fixedDays,
            )
            val block = block(provider, "What's the weather in Xyzzyburg?", Language.EN)

            assertTrue(block.contains("WEATHER NOTE"), "englischer Hinweis: $block")
            assertFalse(block.contains("WETTER-HINWEIS"), "kein deutscher Hinweis: $block")
            assertTrue(block.contains("“Xyzzyburg”"), "Ortsname unübersetzt: $block")
            assertFalse(block.contains("Berlin"), "KEIN Heimat-Wetter untergeschoben: $block")
            assertNull(capturedForecast.get(), "KEIN Forecast-Call")
        }

    @Test
    fun `ES, FR und IT tragen ihren eigenen Rahmen (kein deutscher, kein englischer Durchschlag)`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)

            val es = block(provider, "Wie wird das Wetter?", Language.ES)
            assertTrue(es.contains("CONTEXTO (solo para ti"), "ES-Kopf: $es")
            assertTrue(es.contains("• Tiempo Berlin hoy: de 11 a 19 grados, lluvia ligera"), "ES-Zeile: $es")
            assertTrue(es.contains("INSTRUCCIÓN:"), "ES-Anweisung: $es")

            val fr = block(provider, "Wie wird das Wetter?", Language.FR)
            assertTrue(fr.contains("CONTEXTE (pour toi uniquement"), "FR-Kopf: $fr")
            assertTrue(fr.contains("• Météo Berlin aujourd'hui : de 11 à 19 degrés, pluie légère"), "FR-Zeile: $fr")
            assertTrue(fr.contains("INSTRUCTION :"), "FR-Anweisung: $fr")

            val it = block(provider, "Wie wird das Wetter?", Language.IT)
            assertTrue(it.contains("CONTESTO (solo per te"), "IT-Kopf: $it")
            assertTrue(it.contains("• Meteo Berlin oggi: da 11 a 19 gradi, pioggia leggera"), "IT-Zeile: $it")
            assertTrue(it.contains("ISTRUZIONE:"), "IT-Anweisung: $it")

            listOf(es, fr, it).forEach { b ->
                assertFalse(b.contains("HINTERGRUND"), "kein deutscher Kopf: $b")
                assertFalse(b.contains("ANWEISUNG"), "keine deutsche Anweisung: $b")
                assertFalse(b.contains("BACKGROUND"), "kein englischer Durchschlag: $b")
            }
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
            // Beide Fragen sind PRÄSENS ohne Futur-Marker ⇒ [nowFocus] ⇒ die
            // JETZT-Zeile steht OBEN (im Gegensatz zum „Wie WIRD"-Pin oben).
            val expected = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                NOW_LINE_DE +
                "• Wetter Berlin heute: 11 bis 19 Grad, leichter Regen, etwa 3 mm Niederschlag.\n" +
                "• Wetter Berlin morgen: 13 bis 22 Grad, teilweise bewölkt, kaum Niederschlag.\n" +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“." +
                TENSE_DE

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
    fun `WeatherNumberContract ON pinnt Tagesbezug Ort und Wetterwerte jeder Tages-Zeile`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(
                baseUrl = url,
                locationLabel = "Berlin",
                enableWeatherContract = true,
            )
            val block = block(provider, "Wie wird das Wetter?")

            // Die JETZT-Zeile trägt DIESELBEN «»-Marken: sie ist derselbe geerdete
            // Fakt und gehört unter denselben Zitier-Vertrag — im Livetest hat das
            // Brain gerade die Zahlen frei paraphrasiert.
            val expectedOn = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                "• Wetter «Berlin» «heute»: «11» bis «19» Grad, «leichter Regen», etwa 3 mm Niederschlag.\n" +
                "• Wetter «Berlin» «morgen»: «13» bis «22» Grad, «teilweise bewölkt», kaum Niederschlag.\n" +
                "• Wetter «Berlin» JETZT: «14 Grad», «leichter Regen».\n" +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“." +
                TENSE_DE +
                "\n" +
                "WETTER-VERTRAG: Die Werte in «» oben (Ort, Tag, Temperaturen, Wetterlage) sind exakt. " +
                "Nenne sie genau so weiter — gleicher Tagesbezug, gleicher Ortsname, gleiche Ziffern, gleiche Einheit — " +
                "nicht runden, nicht umformulieren, keinen anderen Ort oder Wert erfinden."
            assertEquals(expectedOn, block, "ON-Block pinnt auch den vom Resolver bestimmten Tagesbezug")
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
                NOW_LINE_DE +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“." +
                TENSE_DE
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

    // ── Bug-Fix 2026-07-25 (PREP-i18n-backend-restklassen.md): codeText stand
    // bis heute hart auf Language.DE, egal welche UI-Sprache aktiv war — die
    // Wetter-Kachel zeigte im englischen Modus deutschen Text. [todayForecast]
    // nimmt jetzt eine [displayLanguage] entgegen (Default DE, byte-neutral). ──

    @Test
    fun `todayForecast ohne Parameter bleibt Deutsch - byte-neutraler Default`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!
            assertEquals("leichter Regen", today.codeText, "Default-Aufruf bleibt Deutsch")
        }

    @Test
    fun `todayForecast folgt der uebergebenen Anzeigesprache - je eine Stichprobe pro Sprache`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")

            assertEquals("leichter Regen", provider.todayForecast(Language.DE).block(Duration.ofSeconds(5))!!.codeText)
            assertEquals("light rain", provider.todayForecast(Language.EN).block(Duration.ofSeconds(5))!!.codeText)
            assertEquals("lluvia ligera", provider.todayForecast(Language.ES).block(Duration.ofSeconds(5))!!.codeText)
            assertEquals("pluie légère", provider.todayForecast(Language.FR).block(Duration.ofSeconds(5))!!.codeText)
            assertEquals("pioggia leggera", provider.todayForecast(Language.IT).block(Duration.ofSeconds(5))!!.codeText)
        }

    @Test
    fun `todayForecast - unbekannter WMO-Code liefert in jeder Sprache einen Fallback-Text, nie roh oder leer`() {
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
        withOpenMeteo(unknownCodeJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val expected = mapOf(
                Language.DE to "wechselhaft",
                Language.EN to "changeable",
                Language.ES to "variable",
                Language.FR to "changeant",
                Language.IT to "variabile",
            )
            expected.forEach { (language, text) ->
                val today = provider.todayForecast(language).block(Duration.ofSeconds(5))!!
                assertEquals(text, today.codeText, "unbekannter Code 17, $language")
                assertFalse(today.codeText.isBlank(), "nie leer ($language)")
                assertFalse(today.codeText.contains("17"), "nie der rohe Code ($language): ${today.codeText}")
            }
        }
    }

    // ── Neu (Flur-Fertigstellung 2026-07-27): Jetzt/Morgen/Stunden/Sonne aus
    // demselben Body, den [WeatherGroundingProvider.todayForecast] ohnehin schon
    // abruft — KEIN zweiter Open-Meteo-Call. ─────────────────────────────────────

    @Test
    fun `todayForecast liefert Jetzt-Temperatur und Jetzt-Lage aus dem current-Node`() =
        withOpenMeteo(richForecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertEquals(14, today.nowTemp, "14.2 gerundet")
            assertEquals("leichter Regen", today.nowCodeText, "Code 61 → DE-Text, wie die Tages-Zeile")
        }

    @Test
    fun `todayForecast folgt fuer nowCodeText ebenfalls der uebergebenen Anzeigesprache`() =
        withOpenMeteo(richForecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast(Language.EN).block(Duration.ofSeconds(5))!!

            assertEquals("light rain", today.nowCodeText)
        }

    @Test
    fun `todayForecast - fehlender current-Node liefert nowTemp und nowCodeText als null, kein Crash`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            // forecastJson traegt zwar current, aber OHNE "time" — reicht fuer diesen
            // Test nicht; wir brauchen current KOMPLETT weg, um den Fehlend-Fall zu pruefen.
            val noCurrentJson = """
                {
                  "daily": {
                    "time": ["2026-06-28", "2026-06-29"],
                    "temperature_2m_max": [19.4, 22.1],
                    "temperature_2m_min": [11.3, 13.0],
                    "precipitation_sum": [3.4, 0.0],
                    "weathercode": [61, 2]
                  }
                }
            """.trimIndent()
            withOpenMeteo(noCurrentJson) { url2, _, _ ->
                val provider = WeatherGroundingProvider(baseUrl = url2, locationLabel = "Berlin")
                val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

                assertNull(today.nowTemp, "kein current-Node ⇒ nowTemp null, nicht erfunden")
                assertNull(today.nowCodeText, "kein current-Node ⇒ nowCodeText null, nicht erfunden")
                // Der Rest (heute-Werte) bleibt unberührt vom fehlenden current-Node.
                assertEquals(11, today.todayMin)
                assertEquals(19, today.todayMax)
            }
        }

    @Test
    fun `todayForecast liefert Morgen-Felder aus Offset 1 - dieselbe Wahrheit wie der Grounding-Block`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertEquals(13, today.tomorrowMin, "Tag 1: 13.0 gerundet")
            assertEquals(22, today.tomorrowMax, "Tag 1: 22.1 gerundet")
            assertEquals("teilweise bewölkt", today.tomorrowCodeText, "Code 2 → DE-Text")
        }

    @Test
    fun `todayForecast - Morgen-Felder sind null, wenn der JSON-Horizont nur heute enthaelt`() =
        withOpenMeteo(forecastJson.replace(Regex("\"temperature_2m_max\": \\[[^]]*]"), "\"temperature_2m_max\": [19.4]")) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertNull(today.tomorrowMin, "kein Tag 1 im JSON ⇒ null statt erfunden")
            assertNull(today.tomorrowMax)
            assertNull(today.tomorrowCodeText)
            assertEquals(11, today.todayMin, "heute bleibt trotzdem lesbar")
        }

    @Test
    fun `todayForecast kompaktiert hourly auf 12 Punkte ab current-time (Index 12 = 12-00 Uhr)`() =
        withOpenMeteo(richForecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertEquals(12, today.hourly.size, "genau 12 Stunden, nicht die vollen 24")
            val first = today.hourly.first()
            val last = today.hourly.last()
            assertEquals(
                LocalDateTime.of(2026, 6, 28, 12, 0),
                Instant.ofEpochMilli(first.epochMs).atZone(DayReferenceResolver.BERLIN).toLocalDateTime(),
                "erster Punkt ist current.time (12:00), nicht Mitternacht",
            )
            assertEquals(18, first.tempC, "12:00-Temp aus dem hourly-Array")
            assertEquals(15, first.precipProbability)
            assertEquals(
                LocalDateTime.of(2026, 6, 28, 23, 0),
                Instant.ofEpochMilli(last.epochMs).atZone(DayReferenceResolver.BERLIN).toLocalDateTime(),
                "zwölfter Punkt = 23:00 (12:00 + 11 h)",
            )
            assertEquals(12, last.tempC)
            assertEquals(5, last.precipProbability)
        }

    @Test
    fun `todayForecast - hourly ist leer, wenn der hourly-Block fehlt (best-effort, kein Crash)`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertEquals(emptyList<WeatherGroundingProvider.HourPoint>(), today.hourly)
        }

    @Test
    fun `todayForecast liefert sunriseEpochMs und sunsetEpochMs aus daily-sunrise-sunset Index 0`() =
        withOpenMeteo(richForecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertEquals(
                LocalDateTime.of(2026, 6, 28, 5, 2),
                Instant.ofEpochMilli(today.sunriseEpochMs!!).atZone(DayReferenceResolver.BERLIN).toLocalDateTime(),
            )
            assertEquals(
                LocalDateTime.of(2026, 6, 28, 21, 34),
                Instant.ofEpochMilli(today.sunsetEpochMs!!).atZone(DayReferenceResolver.BERLIN).toLocalDateTime(),
            )
        }

    @Test
    fun `todayForecast - sunriseEpochMs und sunsetEpochMs sind null, wenn daily keine sunrise-sunset traegt`() =
        withOpenMeteo(forecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val today = provider.todayForecast().block(Duration.ofSeconds(5))!!

            assertNull(today.sunriseEpochMs)
            assertNull(today.sunsetEpochMs)
        }

    @Test
    fun `groundingBlock bleibt trotz der neuen hourly-sunrise-sunset-Parameter BYTE-GLEICH (der Prompt-Block ruehrt sie nicht an)`() =
        withOpenMeteo(richForecastJson) { url, _, _ ->
            val provider = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            // `hourly`/`sunrise`/`sunset` rührt der Prompt-Block weiterhin NICHT an
            // (das war und bleibt die Zusage dieses Tests). Was der Block seit 2b
            // ZUSÄTZLICH liest, ist der `current`-Node — inklusive `current.time`
            // als Frische-Marker, den [richForecastJson] als einziges Fixture trägt.
            val expected = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                "• Wetter Berlin heute: 11 bis 19 Grad, leichter Regen, etwa 3 mm Niederschlag.\n" +
                "• Wetter Berlin morgen: 13 bis 22 Grad, teilweise bewölkt, kaum Niederschlag.\n" +
                NOW_LINE_DE_RICH +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“." +
                TENSE_DE
            assertEquals(expected, block(provider, "Wie wird das Wetter?"))
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
        assertNull(p.explicitPlace("Wie viele Planeten gibt es in unserem Sonnensystem?"), "Possessivpronomen ⇒ kein Ort")
        assertNull(p.explicitPlace("Wie viele Sterne sind in unserer Galaxie?"), "Possessivpronomen ⇒ kein Ort")
        assertNull(p.explicitPlace("Wie viel Wasser ist in meinem Körper?"), "Possessivpronomen ⇒ kein Ort")
    }
}
