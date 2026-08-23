package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * **Auftrag 2b (Andi-Livetest 2026-08-21) + der Sieben-Tage-Ausblick.**
 *
 * Andis Befund wörtlich: *„Ich habe gefragt, wie grad das Wetter ist und Hoshi
 * sagt 15-irgendwas Grad und Regen. Ich will dann die aktuelle Temperatur, und es
 * regnet nicht — hat es aber heute."*
 *
 * Diese Datei pinnt GENAU diesen Fall ([andisFall]): `current` meldet trocken,
 * die TAGES-Summe meldet Regen. Vor 2b enthielt der Grounding-Block dafür nur die
 * Tages-Werte — das Brain konnte gar nicht anders, als sie als Gegenwart
 * auszugeben. Getestet wird der BLOCK, nicht die Modell-Antwort: der Block ist
 * das, was wir deterministisch verantworten (Muster [WeatherGroundingProviderTest]).
 *
 * Schwester-Datei zu [WeatherGroundingProviderTest] (dort: Bestand + Byte-Pins),
 * hier ausschließlich das Neue: JETZT-Zeile, Zeitform-Trennung, Horizont-Grenze,
 * Wochenende, Ausblick-Wire.
 */
class WeatherNowAndOutlookTest {

    /** Sonntag, 2026-06-28, 12:00 Europe/Berlin — Tag 0 aller Fixtures. */
    private val sunday: Clock =
        Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), DayReferenceResolver.BERLIN)

    private val fixedDays = DayReferenceResolver(sunday)

    /**
     * **ANDIS FALL, als JSON.** `current`: 16 Grad, bedeckt (Code 3), **0,0 mm
     * Niederschlag** — es regnet GERADE NICHT. `daily[0]`: Code 61 (leichter
     * Regen), 3,4 mm Tagessumme — es hat heute geregnet. Der `hourly`-Verlauf legt
     * den Regen VOR 12:00 (Vormittag) und lässt den Nachmittag trocken; damit ist
     * „hat geregnet" (statt „soll noch regnen") ein FAKT im Block, keine Vermutung.
     */
    private val andisFall = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "current": {
            "temperature_2m": 15.6, "weathercode": 3, "precipitation": 0.0,
            "time": "2026-06-28T12:00"
          },
          "daily": {
            "time": ["2026-06-28", "2026-06-29", "2026-06-30", "2026-07-01", "2026-07-02", "2026-07-03", "2026-07-04"],
            "temperature_2m_max": [19.4, 22.1, 24.0, 21.5, 18.2, 20.0, 23.3],
            "temperature_2m_min": [11.3, 13.0, 14.2, 12.8, 10.1, 11.7, 12.9],
            "precipitation_sum": [3.4, 0.0, 0.0, 1.2, 6.7, 0.0, 0.3],
            "precipitation_probability_max": [80, 10, 0, 40, 90, 5, 20],
            "weathercode": [61, 2, 0, 3, 63, 1, 2]
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
            "temperature_2m": [12,12,11,11,11,12,13,14,15,16,16,16,16,17,18,18,17,16,15,14,13,13,12,12],
            "precipitation_probability": [70,70,60,60,50,40,40,30,20,10,5,5,5,5,5,5,5,5,5,5,5,5,5,5],
            "precipitation": [0.6,0.8,0.7,0.5,0.4,0.2,0.1,0.1,0.0,0.0,0.0,0.0,
                              0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0]
          }
        }
    """.trimIndent()

    /** Wie [andisFall], aber Open-Meteo liefert GAR KEINEN `current`-Node. */
    private val noCurrentJson = """
        {
          "daily": {
            "time": ["2026-06-28", "2026-06-29", "2026-06-30", "2026-07-01", "2026-07-02", "2026-07-03", "2026-07-04"],
            "temperature_2m_max": [19.4, 22.1, 24.0, 21.5, 18.2, 20.0, 23.3],
            "temperature_2m_min": [11.3, 13.0, 14.2, 12.8, 10.1, 11.7, 12.9],
            "precipitation_sum": [3.4, 0.0, 0.0, 1.2, 6.7, 0.0, 0.3],
            "weathercode": [61, 2, 0, 3, 63, 1, 2]
          }
        }
    """.trimIndent()

    private fun withOpenMeteo(json: String, block: (String, java.util.concurrent.atomic.AtomicReference<String?>) -> Unit) {
        val captured = java.util.concurrent.atomic.AtomicReference<String?>(null)
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

    private fun provider(url: String) =
        WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin", days = fixedDays)

    private fun block(url: String, query: String, language: Language = Language.DE): String =
        provider(url).groundingBlock(query, RouteCategory.FACT_SHORT, language)
            .block(Duration.ofSeconds(5)) ?: ""

    // ── Andis Fall, gepinnt ───────────────────────────────────────────────────

    @Test
    fun `ANDIS FALL - jetzt trocken bei Tages-Regen nennt die AKTUELLE Temperatur, nicht die Tagesspanne`() =
        withOpenMeteo(andisFall) { url, _ ->
            val block = block(url, "wie ist grad das Wetter?")

            // 1) Die aktuelle Temperatur STEHT im Block — vorher gab es sie dort nie.
            assertTrue(block.contains("JETZT: 16 Grad"), "aktuelle Temperatur (15,6 → 16): $block")
            // 2) Und sie steht OBEN, vor den Tages-Zeilen: was oben steht, prägt die Antwort.
            assertTrue(
                block.indexOf("JETZT:") < block.indexOf("Berlin heute:"),
                "JETZT-Zeile muss bei einer Jetzt-Frage VOR der Tages-Zeile stehen: $block",
            )
            // 3) Der Kern: es regnet gerade NICHT, und der Block sagt das ausdrücklich.
            assertTrue(block.contains("gerade KEIN Niederschlag"), "trockener Augenblick explizit: $block")
            // 4) Trotzdem bleibt wahr, dass es heute geregnet hat — als TAGES-Aussage.
            assertTrue(block.contains("bis jetzt etwa 3 mm gefallen"), "Tages-Regen als Vergangenheit: $block")
            assertTrue(
                block.contains("für den Rest des Tages nichts mehr erwartet"),
                "und kein neuer Regen in Sicht: $block",
            )
            // 5) Die Zeitform-Regel, die beides auseinanderhält.
            assertTrue(block.contains("ZEITFORM:"), "Zeitform-Regel angehängt: $block")
        }

    @Test
    fun `ANDIS FALL - die 15-irgendwas-Grad-Verwechslung ist strukturell ausgeschlossen`() =
        withOpenMeteo(andisFall) { url, _ ->
            val block = block(url, "wie ist grad das Wetter?")

            // Andi bekam „15-irgendwas Grad": das war [temperature_2m_min] = 11,3 …
            // [temperature_2m_max] = 19,4 als Spanne, aus der das Brain sich etwas
            // gegriffen hat. Die Spanne DARF weiter im Block stehen (sie ist wahr
            // für den Tag) — aber sie ist jetzt eindeutig als Tages-Zeile markiert,
            // und daneben steht der echte Jetzt-Wert.
            assertTrue(block.contains("11 bis 19 Grad"), "Tagesspanne bleibt erhalten (als Tages-Aussage): $block")
            assertTrue(block.contains("JETZT: 16 Grad"), "…aber der Augenblick steht daneben: $block")

            // Und: der Block behauptet NIRGENDS Regen im Augenblick.
            assertFalse(
                block.contains("gerade etwa") && block.contains("mm Niederschlag,"),
                "kein Jetzt-Niederschlag behauptet: $block",
            )
        }

    @Test
    fun `ANDIS FALL in allen fuenf Sprachen - aktuelle Temperatur ja, Regen-jetzt nein`() =
        withOpenMeteo(andisFall) { url, _ ->
            // Je Sprache: (Frage, JETZT-Marker der Zeile, „gerade kein Regen"-Fragment)
            val cases = listOf(
                Triple(Language.DE, "JETZT: 16 Grad", "gerade KEIN Niederschlag"),
                Triple(Language.EN, "RIGHT NOW: 16 degrees", "NO precipitation right now"),
                Triple(Language.ES, "AHORA MISMO: 16 grados", "AHORA MISMO sin precipitación"),
                Triple(Language.FR, "MAINTENANT : 16 degrés", "AUCUNE précipitation en ce moment"),
                Triple(Language.IT, "ADESSO: 16 gradi", "adesso NESSUNA precipitazione"),
            )
            for ((language, nowMarker, dryMarker) in cases) {
                val block = block(url, "wie ist grad das Wetter?", language)
                assertTrue(block.contains(nowMarker), "$language: aktuelle Temperatur in der JETZT-Zeile: $block")
                assertTrue(block.contains(dryMarker), "$language: sagt ausdrücklich, dass es gerade nicht regnet: $block")
                // Kein deutscher Durchschlag in den vier anderen Sprachen.
                if (language != Language.DE) {
                    assertFalse(block.contains("gerade KEIN Niederschlag"), "$language: kein DE-Durchschlag: $block")
                    assertFalse(block.contains("ZEITFORM:"), "$language: kein DE-Durchschlag: $block")
                }
            }
        }

    @Test
    fun `Zeitform-Regel steht in jeder der fuenf Sprachen und nie auf Englisch statt der Zielsprache`() =
        withOpenMeteo(andisFall) { url, _ ->
            val heads = mapOf(
                Language.DE to "ZEITFORM:",
                Language.EN to "TENSE:",
                Language.ES to "TIEMPO VERBAL:",
                Language.FR to "TEMPS VERBAL :",
                Language.IT to "TEMPO VERBALE:",
            )
            for (language in Language.entries) {
                val block = block(url, "wie ist grad das Wetter?", language)
                assertTrue(block.contains(heads.getValue(language)), "$language: eigene Zeitform-Überschrift: $block")
                // Vorbild `EscalationLanguageTest`: ES/FR/IT dürfen nie die
                // englische Fassung als stillen Fallback bekommen.
                if (language in listOf(Language.ES, Language.FR, Language.IT)) {
                    assertFalse(block.contains("TENSE: The RIGHT NOW line"), "$language: kein EN-Fallback: $block")
                }
                assertNotEquals("", block, "$language: Block darf nicht leer sein")
            }
        }

    @Test
    fun `kein current-Node - Ausweich aufs Tagesbild wird SPRACHLICH gekennzeichnet, nie still`() =
        withOpenMeteo(noCurrentJson) { url, _ ->
            for (language in Language.entries) {
                val block = block(url, "wie ist grad das Wetter?", language)
                // Keine JETZT-Zeile (es gibt keinen Wert) …
                assertFalse(block.contains("JETZT:"), "$language: keine erfundene JETZT-Zeile: $block")
                assertFalse(block.contains("RIGHT NOW:"), "$language: keine erfundene JETZT-Zeile: $block")
                // … aber auch keine stille Tagesspanne: der Ausweich ist markiert.
                val marker = when (language) {
                    Language.DE -> "ACHTUNG: Für den AUGENBLICK liegt kein Messwert vor"
                    Language.EN -> "CAUTION: There is no reading for this MOMENT"
                    Language.ES -> "ATENCIÓN: No hay medición para este INSTANTE"
                    Language.FR -> "ATTENTION : Aucune mesure pour l'INSTANT présent"
                    Language.IT -> "ATTENZIONE: Non c'è una misura per QUESTO momento"
                }
                assertTrue(block.contains(marker), "$language: Ausweich gekennzeichnet: $block")
            }
        }

    @Test
    fun `Futur-Frage stellt die JETZT-Zeile nach unten, Praesens-Frage nach oben`() =
        withOpenMeteo(andisFall) { url, _ ->
            val present = block(url, "Wie ist das Wetter?")
            val future = block(url, "Wie wird das Wetter?")

            assertTrue(present.indexOf("JETZT:") < present.indexOf("Berlin heute:"), "Präsens ⇒ oben: $present")
            assertTrue(future.indexOf("JETZT:") > future.indexOf("Berlin heute:"), "Futur ⇒ unten: $future")
            // Beide tragen den Wert — die Reihenfolge gewichtet nur, sie unterschlägt nie.
            assertTrue(future.contains("JETZT: 16 Grad"), "auch die Futur-Frage kennt den Augenblick: $future")
        }

    @Test
    fun `expliziter Wochentag holt KEINE Jetzt-Werte (der Augenblick gehoert nicht zum Donnerstag)`() =
        withOpenMeteo(andisFall) { url, _ ->
            val block = block(url, "Wie wird das Wetter am Donnerstag?")

            assertFalse(block.contains("JETZT:"), "kein Jetzt-Wert bei reiner Zukunftsfrage: $block")
            assertFalse(block.contains("ZEITFORM:"), "und damit auch keine Zeitform-Regel: $block")
            assertTrue(block.contains("Berlin am Donnerstag (in 4 Tagen)"), "der gefragte Tag: $block")
        }

    // ── Ehrliche Horizont-Grenze ──────────────────────────────────────────────

    @Test
    fun `jenseits der Reichweite - ehrliche Grenze statt geratener Tage, und KEIN API-Call`() =
        withOpenMeteo(andisFall) { url, captured ->
            for (query in listOf(
                "Wie wird das Wetter nächsten Samstag?",
                "Wie wird das Wetter nächste Woche?",
                "Wie wird das Wetter in 10 Tagen?",
            )) {
                val block = block(url, query)
                assertTrue(
                    block.contains("Der gefragte Tag liegt jenseits meiner Vorhersage — ich sehe nur 7 Tage voraus."),
                    "[$query] ⇒ ehrliche Grenze: $block",
                )
                assertTrue(block.contains("Rate KEINE Werte."), "[$query] ⇒ ausdrückliches Rate-Verbot: $block")
                assertFalse(block.contains("bis 19 Grad"), "[$query] ⇒ keine Tageswerte untergeschoben: $block")
            }
            // Für eine Antwort, die schon feststeht, wird Open-Meteo nicht behelligt.
            assertNull(captured.get(), "jenseits des Horizonts ⇒ kein Forecast-Call")
        }

    @Test
    fun `Horizont-Grenze gibt es in allen fuenf Sprachen (Vorbild escalationUnavailable)`() {
        val expected = mapOf(
            Language.DE to "Der gefragte Tag liegt jenseits meiner Vorhersage — ich sehe nur 7 Tage voraus.",
            Language.EN to "The day asked about is beyond my forecast — I only see 7 days ahead.",
            Language.ES to "El día preguntado queda fuera de mi previsión — solo veo 7 días por delante.",
            Language.FR to "Le jour demandé dépasse ma prévision — je ne vois que 7 jours à l'avance.",
            Language.IT to "Il giorno richiesto è oltre la mia previsione — vedo solo 7 giorni in avanti.",
        )
        for (language in Language.entries) {
            val text = WeatherBlockTexts.beyondHorizon(language, 7)
            assertTrue(text.contains(expected.getValue(language)), "$language: eigene Grenz-Phrase: $text")
        }
        // Kein stiller Englisch-Fallback für ES/FR/IT (Muster `EscalationLanguageTest`).
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            assertNotEquals(
                WeatherBlockTexts.beyondHorizon(Language.EN, 7),
                WeatherBlockTexts.beyondHorizon(language, 7),
                "$language darf nie die englische Grenz-Phrase bekommen",
            )
        }
    }

    @Test
    fun `Teil-Antwort - heute UND naechste Woche liefert heute und kennzeichnet den Rest als jenseits`() =
        withOpenMeteo(andisFall) { url, _ ->
            val block = block(url, "Wie wird das Wetter heute und nächste Woche?")

            assertTrue(block.contains("Berlin heute:"), "der beantwortbare Teil kommt: $block")
            assertTrue(block.contains("jenseits meiner Vorhersage"), "der unbeantwortbare wird benannt: $block")
        }

    // ── Wochenende ────────────────────────────────────────────────────────────

    @Test
    fun `Wochenend-Frage fasst Sa+So zusammen - beide Tage im Block, EIN Bild in der Anweisung`() =
        withOpenMeteo(andisFall) { url, _ ->
            val block = block(url, "Wie wird das Wetter am Wochenende?")

            // Sonntag ist Tag 0, der nächste Samstag Tag 6.
            assertTrue(block.contains("Berlin heute:"), "Sonntag = heute: $block")
            assertTrue(block.contains("Berlin am Samstag (in 6 Tagen)"), "nächster Samstag: $block")
            assertTrue(
                block.contains("Fasse Samstag und Sonntag zu EINEM Wochenend-Bild zusammen"),
                "Zusammenfass-Anweisung: $block",
            )
        }

    @Test
    fun `Wochenend-Anweisung gibt es in allen fuenf Sprachen`() {
        val heads = mapOf(
            Language.DE to "Fasse Samstag und Sonntag",
            Language.EN to "Summarise Saturday and Sunday",
            Language.ES to "Resume el sábado y el domingo",
            Language.FR to "Résume samedi et dimanche",
            Language.IT to "Riassumi sabato e domenica",
        )
        for (language in Language.entries) {
            assertTrue(
                WeatherBlockTexts.weekendSuffix(language).contains(heads.getValue(language)),
                "$language: eigene Wochenend-Anweisung",
            )
        }
    }

    // ── Der Sieben-Tage-Ausblick (Wire) ───────────────────────────────────────

    @Test
    fun `todayForecast liefert alle sieben Tage im additiven outlook-Feld`() =
        withOpenMeteo(andisFall) { url, _ ->
            val forecast = provider(url).todayForecast(Language.DE).block(Duration.ofSeconds(5))!!

            assertEquals(7, forecast.outlook.size, "sieben Tage aus DEMSELBEN Fetch")
            val today = forecast.outlook.first()
            assertEquals(0, today.offset)
            assertEquals("2026-06-28", today.dateIso)
            assertEquals(11, today.tempMin)
            assertEquals(19, today.tempMax)
            assertEquals("leichter Regen", today.codeText)
            assertEquals(80, today.precipProbability)

            val last = forecast.outlook.last()
            assertEquals(6, last.offset)
            assertEquals("2026-07-04", last.dateIso)
            assertEquals("teilweise bewölkt", last.codeText)
            assertEquals(20, last.precipProbability)

            // Bestandsfelder unverändert daneben (K4: additiv, nichts umgehängt).
            assertEquals(11, forecast.todayMin)
            assertEquals(19, forecast.todayMax)
            assertEquals(13, forecast.tomorrowMin)
        }

    @Test
    fun `outlook folgt der Anzeigesprache - dieselben Zahlen, uebersetzte Lage`() =
        withOpenMeteo(andisFall) { url, _ ->
            val en = provider(url).todayForecast(Language.EN).block(Duration.ofSeconds(5))!!
            assertEquals("light rain", en.outlook.first().codeText, "Lage folgt der Anzeigesprache")
            assertEquals(19, en.outlook.first().tempMax, "Zahlen bleiben Zahlen")
            assertEquals("2026-06-28", en.outlook.first().dateIso, "Datum ist ein Datum, keine Übersetzung")
        }

    @Test
    fun `fehlende Regenwahrscheinlichkeit ist null, nicht 0 Prozent`() =
        withOpenMeteo(noCurrentJson) { url, _ ->
            // [noCurrentJson] trägt kein `precipitation_probability_max`.
            val forecast = provider(url).todayForecast(Language.DE).block(Duration.ofSeconds(5))!!
            assertEquals(7, forecast.outlook.size)
            assertNull(
                forecast.outlook.first().precipProbability,
                "keine Angabe ⇒ null; 0 % wäre eine erfundene Aussage",
            )
        }

    @Test
    fun `der Ausblick kostet KEINEN zusaetzlichen Call - ein Fetch, erweiterte Parameter`() =
        withOpenMeteo(andisFall) { url, captured ->
            provider(url).todayForecast(Language.DE).block(Duration.ofSeconds(5))

            val query = captured.get() ?: ""
            // Alles aus EINEM Request: die neuen Felder hängen an den bestehenden
            // Parametern, es gibt keinen zweiten Endpunkt und keinen zweiten Aufruf.
            assertTrue(query.contains("precipitation_probability_max"), "Tages-Regenwahrscheinlichkeit: $query")
            assertTrue(query.contains("forecast_days=7"), "unveränderter Sieben-Tage-Horizont: $query")
            assertTrue(query.contains("current="), "current-Node weiterhin am selben Call: $query")
        }
}
