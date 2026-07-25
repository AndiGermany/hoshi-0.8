package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.GroundingPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Beweist die Composite-Strategie (Wetter zuerst, sonst Nachgeschlagen, sonst
 * Wiki) mit reinen Fake-Ports — und, seit der Sprach-Naht (2026-07-25), dass die
 * TURN-SPRACHE durch DIESEN Knoten hindurch bis in die Scheiben ankommt.
 *
 * **Warum hier auch ein ECHTER [WeatherGroundingProvider] hängt:** Prod geht
 * IMMER durch den Composite (`PipelineConfig.groundingPort` wickelt die Scheiben
 * bei `HOSHI_WEATHER_ENABLED`/`HOSHI_EXTENDED_THINK_ENABLED` genau so). Ein
 * Sprach-Beweis, der den Provider direkt anspricht, prüft deshalb eine Kette, die
 * so nie läuft — genau darin konnte sich der stille Sprach-Verlust verstecken
 * (der Composite trug bis 2026-07-25 einen handkopierten Zwilling je Overload).
 * Die Sprach-Tests unten gehen deshalb durch den Composite, nicht an ihm vorbei.
 */
class CompositeGroundingPortTest {

    /** Fake-Port mit fester Antwort, der mitzählt, ob er angefragt wurde — und MIT WELCHER Sprache. */
    private class FakePort(private val answer: String, var called: Boolean = false) : GroundingPort {
        var lastLanguage: Language? = null
        override fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String> {
            called = true
            lastLanguage = language
            return Mono.just(answer)
        }
    }

    /** Fake-Port, der immer wirft (best-effort-Beweis). */
    private class BoomPort : GroundingPort {
        override fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String> =
            Mono.error(RuntimeException("boom"))
    }

    private val cat = RouteCategory.FACT_SHORT

    @Test
    fun `Wetter-Block gewinnt und die Wiki-Scheibe wird gar nicht erst angefragt`() {
        val weather = FakePort("WETTER-BLOCK")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki).groundingBlock("Wetter morgen?", cat, Language.DE)
            .block(Duration.ofSeconds(2))

        assertEquals("WETTER-BLOCK", block)
        assertTrue(weather.called, "Wetter wird zuerst gefragt")
        assertFalse(wiki.called, "Wiki wird bei Wetter-Treffer NICHT angefragt")
    }

    @Test
    fun `ohne Wetter-Block faellt der Composite zur Wiki-Scheibe durch (byte-identisch)`() {
        val weather = FakePort("") // keine Wetter-Absicht / API leer
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki).groundingBlock("Wer war Adenauer?", cat, Language.DE)
            .block(Duration.ofSeconds(2))

        // Der durchgereichte Block ist GENAU der Wiki-Block — die Wetter-Scheibe ist
        // für Nicht-Wetter-Fragen transparent (byte-neutral zum reinen Wiki-Pfad).
        assertEquals("WIKI-BLOCK", block)
        assertTrue(wiki.called, "Wiki übernimmt, wenn Wetter leer ist")
    }

    @Test
    fun `ein Fehler in der Wetter-Scheibe faellt sauber zur Wiki-Scheibe durch`() {
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(BoomPort(), wiki).groundingBlock("Wetter morgen?", cat, Language.DE)
            .block(Duration.ofSeconds(2))

        assertEquals("WIKI-BLOCK", block, "Wetter-Fehler darf den Turn nie kippen")
    }

    // ── Extended Think S3: die dritte Scheibe (nachgeschlagen), NACH weather, VOR wiki ──

    @Test
    fun `ohne explizite dritte Scheibe - Zwei-Argument-Konstruktor bleibt byte-identisch`() {
        // Bestehende Aufrufer (2 positionale Argumente) kompilieren unverändert UND
        // verhalten sich unverändert — der Default-Stub liefert immer "".
        val weather = FakePort("")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki).groundingBlock("Wer war Adenauer?", cat, Language.DE)
            .block(Duration.ofSeconds(2))
        assertEquals("WIKI-BLOCK", block)
    }

    @Test
    fun `Nachgeschlagen-Block gewinnt gegen leeres Wetter, Wiki wird NICHT gefragt`() {
        val weather = FakePort("")
        val nachgeschlagen = FakePort("CACHE-BLOCK")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, nachgeschlagen)
            .groundingBlock("Wie hoch ist der Eiffelturm?", cat, Language.DE)
            .block(Duration.ofSeconds(2))

        assertEquals("CACHE-BLOCK", block)
        assertTrue(weather.called, "Wetter wird zuerst gefragt")
        assertTrue(nachgeschlagen.called, "Nachgeschlagen wird gefragt, wenn Wetter leer ist")
        assertFalse(wiki.called, "Wiki wird bei Cache-Treffer NICHT angefragt")
    }

    @Test
    fun `Wetter gewinnt weiterhin gegen einen Nachgeschlagen-Treffer`() {
        val weather = FakePort("WETTER-BLOCK")
        val nachgeschlagen = FakePort("CACHE-BLOCK")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, nachgeschlagen)
            .groundingBlock("Wetter morgen?", cat, Language.DE)
            .block(Duration.ofSeconds(2))

        assertEquals("WETTER-BLOCK", block)
        assertFalse(nachgeschlagen.called, "Nachgeschlagen wird bei Wetter-Treffer NICHT angefragt")
        assertFalse(wiki.called)
    }

    @Test
    fun `weder Wetter noch Nachgeschlagen - faellt zu Wiki durch`() {
        val weather = FakePort("")
        val nachgeschlagen = FakePort("")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, nachgeschlagen)
            .groundingBlock("Wer war Adenauer?", cat, Language.DE)
            .block(Duration.ofSeconds(2))

        assertEquals("WIKI-BLOCK", block)
        assertTrue(nachgeschlagen.called)
        assertTrue(wiki.called)
    }

    @Test
    fun `ein Fehler in der Nachgeschlagen-Scheibe faellt sauber zur Wiki-Scheibe durch`() {
        val weather = FakePort("")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, BoomPort())
            .groundingBlock("Wie hoch ist der Eiffelturm?", cat, Language.DE)
            .block(Duration.ofSeconds(2))

        assertEquals("WIKI-BLOCK", block, "Nachgeschlagen-Fehler darf den Turn nie kippen")
    }

    // ── Sprach-Naht (2026-07-25): die Turn-Sprache muss DURCH diesen Knoten ────

    @Test
    fun `die Turn-Sprache erreicht ALLE drei Scheiben (Wetter, Nachgeschlagen, Wiki)`() {
        val weather = FakePort("")
        val nachgeschlagen = FakePort("")
        val wiki = FakePort("WIKI-BLOCK")

        CompositeGroundingPort(weather, wiki, nachgeschlagen)
            .groundingBlock("Wer war Adenauer?", cat, Language.EN)
            .block(Duration.ofSeconds(2))

        assertEquals(Language.EN, weather.lastLanguage, "Wetter-Scheibe bekommt die Turn-Sprache")
        assertEquals(Language.EN, nachgeschlagen.lastLanguage, "Nachgeschlagen-Scheibe bekommt die Turn-Sprache")
        assertEquals(Language.EN, wiki.lastLanguage, "Wiki-Scheibe bekommt die Turn-Sprache")
    }

    @Test
    fun `auch der Fehler-Fallback zur Wiki-Scheibe traegt die Turn-Sprache`() {
        val wiki = FakePort("WIKI-BLOCK")

        CompositeGroundingPort(BoomPort(), wiki).groundingBlock("Wetter morgen?", cat, Language.FR)
            .block(Duration.ofSeconds(2))

        assertEquals(Language.FR, wiki.lastLanguage, "der onErrorResume-Pfad darf die Sprache nicht verlieren")
    }

    // ── ECHTE Prod-Kette: Composite → echter WeatherGroundingProvider ──────────

    /** Zwei Tage (heute+morgen) — reicht für den referenzlosen Default-Block. */
    private val forecastJson = """
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

    private fun withOpenMeteo(block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/forecast") { ex ->
            val bytes = forecastJson.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    /** Genau die Verdrahtung aus `PipelineConfig.groundingPort` (Wetter ON, Wiki dahinter). */
    private fun prodChain(url: String, wiki: GroundingPort): GroundingPort =
        CompositeGroundingPort(
            weather = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin"),
            wiki = wiki,
        )

    @Test
    fun `Prod-Kette mit EN-Turn - der Wetter-Block kommt KOMPLETT englisch durch den Composite`() =
        withOpenMeteo { url ->
            val block = prodChain(url, FakePort("WIKI-BLOCK"))
                .groundingBlock("Wie wird das Wetter?", cat, Language.EN)
                .block(Duration.ofSeconds(5))!!

            assertTrue(block.contains("BACKGROUND (for you only"), "englischer Kopf: $block")
            assertTrue(block.contains("• Weather Berlin today: 11 to 19 degrees, light rain"), "englische Zeile: $block")
            assertTrue(block.contains("INSTRUCTION: Use this REAL weather data"), "englische Anweisung: $block")
            assertFalse(block.contains("HINTERGRUND"), "kein deutscher Kopf: $block")
            assertFalse(block.contains("ANWEISUNG"), "keine deutsche Anweisung: $block")
        }

    @Test
    fun `Prod-Kette mit DE-Turn - unveraendert der bisherige deutsche Block`() =
        withOpenMeteo { url ->
            val block = prodChain(url, FakePort("WIKI-BLOCK"))
                .groundingBlock("Wie wird das Wetter?", cat, Language.DE)
                .block(Duration.ofSeconds(5))!!

            val expected = "\n\n---\n" +
                "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
                "• Wetter Berlin heute: 11 bis 19 Grad, leichter Regen, etwa 3 mm Niederschlag.\n" +
                "• Wetter Berlin morgen: 13 bis 22 Grad, teilweise bewölkt, kaum Niederschlag.\n" +
                "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“."
            assertEquals(expected, block, "DE bleibt durch den Composite byte-identisch")
        }
}
