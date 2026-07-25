package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.TurnPrompt
import de.hoshi.core.pipeline.EntityContextPort
import de.hoshi.core.pipeline.GroundingPort
import de.hoshi.core.pipeline.PersonaService
import de.hoshi.core.pipeline.TurnPromptAssembler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/**
 * **Ende-zu-Ende-Beweis der Turn-Sprache-Naht** (Multilingual-Welle, 2026-07-24):
 * ein ECHTER [TurnPromptAssembler] (core-domain) mit einem ECHTEN
 * [WeatherGroundingProvider] (kanned Open-Meteo-HttpServer, Muster
 * [WeatherGroundingProviderTest]) — lebt bewusst HIER in `adapters-knowledge`
 * (nicht in core-domain), weil core-domain NICHT von adapters-knowledge abhängt
 * (Modul-Graph-Richtung, s. [TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER]-KDoc)
 * — nur hier lässt sich der komplette Aufrufpfad
 * `TurnPromptAssembler.assemble → ctx.language → GroundingPort.groundingBlock(…,
 * language) → CompositeGroundingPort → WeatherGroundingProvider` in einem
 * einzigen Test zusammenstecken — inklusive des Composite-Knotens, den Prod
 * IMMER durchläuft.
 *
 * Ein Turn mit `language = Language.EN` lässt die Wetterlage im
 * [TurnPromptAssembler.AssembledPrompt.groundBlock] auf Englisch erscheinen; ein
 * Turn mit DE (Default) bleibt beim bisherigen deutschen Text — die Turn-Sprache
 * gewinnt gegen den (hier nicht gesetzten) Ctor-Default des Providers.
 */
class TurnPromptAssemblerWeatherLanguageTest {

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

    /**
     * **Wie in Prod verdrahtet:** der Assembler sieht NICHT den Wetter-Provider,
     * sondern den [CompositeGroundingPort] (`PipelineConfig.groundingPort` wickelt
     * die Scheiben bei `HOSHI_WEATHER_ENABLED` genau so). Vorher hing der Provider
     * hier direkt am Assembler — der Test übersprang damit ausgerechnet den Knoten,
     * in dem sich ein Sprach-Verlust verstecken kann.
     */
    private fun assemblerFor(weather: WeatherGroundingProvider): TurnPromptAssembler =
        TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = EntityContextPort { null },
            grounding = CompositeGroundingPort(weather = weather, wiki = GroundingPort.EMPTY),
            episodicMemory = null,
        )

    private fun turn(language: Language): TurnPrompt =
        TurnPrompt.from(ChatRequest(text = "Wie wird das Wetter?", speak = false, chatId = "c1", language = language))

    private val localFact = RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "x")

    @Test
    fun `Turn mit language=EN traegt englische Wetterlage im Grounding-Block ueber den kompletten Assembler-Pfad`() =
        withOpenMeteo { url ->
            // Provider BEWUSST ohne eigenen language-Ctor-Wert (= Language.DEFAULT/DE) —
            // der Beweis gilt genau der PER-TURN-Sprache, nicht einer Adapter-Konfiguration.
            val weather = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val asm = assemblerFor(weather)

            val out = asm.assemble(turn(Language.EN), localFact, "BASE", "").block(Duration.ofSeconds(5))!!

            assertTrue(out.groundBlock.contains("light rain"), "EN-Turn traegt englische Wetterlage: ${out.groundBlock}")
            assertFalse(out.groundBlock.contains("leichter Regen"), "keine deutsche Wetterlage bei EN-Turn: ${out.groundBlock}")
            // Seit der Sprach-Naht (2026-07-25) folgt der GANZE Rahmen der Turn-Sprache:
            // ein deutscher Kopf + eine deutsche ANWEISUNG holten die Antwort sonst
            // zuverlässig nach Deutsch zurück, trotz englischer Persona.
            assertTrue(out.groundBlock.contains("BACKGROUND (for you only"), "englischer Kopf: ${out.groundBlock}")
            assertTrue(out.groundBlock.contains("• Weather Berlin today:"), "englische Zeile: ${out.groundBlock}")
            assertTrue(out.groundBlock.contains("INSTRUCTION: Use this REAL weather data"), "englische Anweisung: ${out.groundBlock}")
            assertFalse(out.groundBlock.contains("HINTERGRUND"), "kein deutscher Kopf: ${out.groundBlock}")
            assertFalse(out.groundBlock.contains("ANWEISUNG"), "keine deutsche Anweisung: ${out.groundBlock}")
            assertTrue(out.finalPrompt.contains(out.groundBlock), "Grounding-Block wird ins finale Prompt geschichtet")
        }

    @Test
    fun `Turn mit Default-Sprache (DE) bleibt ueber denselben Assembler-Pfad unveraendert deutsch`() =
        withOpenMeteo { url ->
            val weather = WeatherGroundingProvider(baseUrl = url, locationLabel = "Berlin")
            val asm = assemblerFor(weather)

            val out = asm.assemble(turn(Language.DE), localFact, "BASE", "").block(Duration.ofSeconds(5))!!

            assertTrue(out.groundBlock.contains("leichter Regen"), "DE-Turn bleibt deutsch: ${out.groundBlock}")
            assertFalse(out.groundBlock.contains("light rain"), "keine englische Wetterlage bei DE-Turn: ${out.groundBlock}")
        }
}
