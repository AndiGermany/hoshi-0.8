package de.hoshi.adapters.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Beweist den **Null-Toleranz-Vertrag** des [MlxScoreAdapter] gegen `/v1/score`
 * OHNE Live-Brain: ein winziger JDK-HttpServer spielt den e4b-Sidecar (Muster
 * [MlxBrainAdapterTest]). Fokus: JEDE Abweichung vom sauberen 200-Happy-Path
 * (404, Timeout, kaputtes JSON) kollabiert auf `Mono.empty()` — NIE ein
 * geworfener Fehler, NIE ein erfundener Wert (s. Klassen-KDoc des Adapters).
 */
class MlxScoreAdapterTest {

    private fun withServer(
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit,
        block: (url: String) -> Unit,
    ) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/score") { ex -> handler(ex) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `200-Happy-Path liefert das SttSurprisal exakt durchgereicht`() =
        withServer({ ex ->
            respond(
                ex,
                200,
                """{"tokens":["hallo","welt"],"logprobs":[-0.2,-0.8],"mean_surprisal":0.5,"max_surprisal":0.8,"token_count":2,"ms":12}""",
            )
        }) { url ->
            val result = MlxScoreAdapter(baseUrl = url).score("hallo welt").block(Duration.ofSeconds(5))
            assertEquals(0.5, result?.meanSurprisal)
            assertEquals(0.8, result?.maxSurprisal)
            assertEquals(2, result?.tokenCount)
        }

    @Test
    fun `404 (Endpoint fehlt, heutiger Prod-Fall) liefert null`() =
        withServer({ ex -> respond(ex, 404, "not found") }) { url ->
            val result = MlxScoreAdapter(baseUrl = url).score("hallo welt").block(Duration.ofSeconds(5))
            assertNull(result, "404 ist der ERWARTETE Normalfall, solange /v1/score fehlt")
        }

    @Test
    fun `500 (Sidecar-Fehler) liefert null`() =
        withServer({ ex -> respond(ex, 500, "boom") }) { url ->
            val result = MlxScoreAdapter(baseUrl = url).score("hallo welt").block(Duration.ofSeconds(5))
            assertNull(result)
        }

    @Test
    fun `Timeout (Sidecar antwortet zu langsam) liefert null`() =
        withServer({ ex ->
            Thread.sleep(300)
            respond(ex, 200, """{"mean_surprisal":0.5,"max_surprisal":0.8,"token_count":2}""")
        }) { url ->
            val result = MlxScoreAdapter(baseUrl = url, timeoutMs = 100).score("hallo welt").block(Duration.ofSeconds(5))
            assertNull(result, "Timeout ueberschritten ⇒ null, NIE ein geworfener Fehler")
        }

    @Test
    fun `kaputtes JSON liefert null`() =
        withServer({ ex -> respond(ex, 200, "das ist kein json{{{") }) { url ->
            val result = MlxScoreAdapter(baseUrl = url).score("hallo welt").block(Duration.ofSeconds(5))
            assertNull(result)
        }

    @Test
    fun `fehlendes Pflichtfeld (max_surprisal) liefert null - die GANZE Antwort gilt als unbrauchbar`() =
        withServer({ ex -> respond(ex, 200, """{"mean_surprisal":0.5,"token_count":2}""") }) { url ->
            val result = MlxScoreAdapter(baseUrl = url).score("hallo welt").block(Duration.ofSeconds(5))
            assertNull(result)
        }

    @Test
    fun `nicht-numerisches Pflichtfeld liefert null`() =
        withServer({ ex ->
            respond(ex, 200, """{"mean_surprisal":"viel","max_surprisal":0.8,"token_count":2}""")
        }) { url ->
            val result = MlxScoreAdapter(baseUrl = url).score("hallo welt").block(Duration.ofSeconds(5))
            assertNull(result)
        }

    @Test
    fun `sendet den Transkript-Text als text-Feld im Body`() {
        val captured = java.util.concurrent.atomic.AtomicReference<String?>(null)
        withServer({ ex ->
            captured.set(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            respond(ex, 200, """{"mean_surprisal":0.1,"max_surprisal":0.2,"token_count":1}""")
        }) { url ->
            MlxScoreAdapter(baseUrl = url).score("wie ist das wetter").block(Duration.ofSeconds(5))
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val node = mapper.readTree(captured.get())
            assertEquals("wie ist das wetter", node.path("text").asText())
        }
    }
}
