package de.hoshi.adapters.brain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.LlmDelta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den **Body-Vertrag** des [MlxBrainAdapter] gegen `/v1/chat` OHNE
 * Live-Brain: ein winziger JDK-HttpServer spielt den e4b-Sidecar (:8041),
 * kapert den Request-Body und antwortet mit einem kanned SSE-Frame.
 *
 * Fokus D1 (Sampling-Hebel): `min_p` / `presence_penalty` dürfen NUR im Body
 * stehen, wenn das Flag ([MlxBrainAdapter]s `samplingEnabled`) AN ist UND der
 * Wert konfiguriert wurde — sonst muss der Body byte-identisch zu heute sein
 * (Feld FEHLT, nicht null).
 */
class MlxBrainAdapterTest {

    private val mapper = jacksonObjectMapper()

    /**
     * Startet einen Fake-`/v1/chat`, der den Body kapert und einen SSE-Turn liefert.
     * [sse] ist der kanned Response-Stream (Default: ein `delta`-Frame OHNE logprob —
     * exakt das heutige Prod-server_e4b).
     */
    private fun withBrain(
        sse: String = "data: {\"delta\":\"ok\"}\n\ndata: [DONE]\n\n",
        block: (url: String, captured: AtomicReference<String?>) -> Unit,
    ) {
        val captured = AtomicReference<String?>(null)
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat") { ex ->
            captured.set(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            val bytes = sse.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
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

    /** Ein voller Turn gegen den Fake-Brain; liefert den geparsten Request-Body. */
    private fun turnBody(adapter: MlxBrainAdapter, captured: AtomicReference<String?>): JsonNode {
        val deltas: List<LlmDelta>? = adapter.streamChat(prompt = "hi").collectList().block(Duration.ofSeconds(5))
        assertEquals(listOf("ok"), deltas?.map { it.text }, "SSE-Antwort muss ankommen (Setup-Sanity)")
        val raw = captured.get()
        assertNotNull(raw, "Brain muss angefragt worden sein")
        return mapper.readTree(raw)
    }

    @Test
    fun `Default (Flag OFF, nichts konfiguriert) sendet KEINE Sampling-Felder`() =
        withBrain { url, captured ->
            val body = turnBody(MlxBrainAdapter(baseUrl = url), captured)
            assertFalse(body.has("min_p"), "min_p darf im Default-Body fehlen")
            assertFalse(body.has("presence_penalty"), "presence_penalty darf im Default-Body fehlen")
            // Bestehender Vertrag bleibt: Pflichtfelder unverändert drin.
            assertTrue(body.has("messages") && body.has("temperature") && body.has("max_tokens"))
            assertFalse(body.has("tools"), "ohne Tools kein tools-Feld (Bestandsvertrag)")
        }

    @Test
    fun `Flag OFF unterdrueckt Sampling-Felder AUCH wenn Werte konfiguriert sind`() =
        withBrain { url, captured ->
            val adapter = MlxBrainAdapter(baseUrl = url, samplingEnabled = false, minP = 0.08, presencePenalty = 1.1)
            val body = turnBody(adapter, captured)
            assertFalse(body.has("min_p"), "Flag OFF ⇒ min_p darf NICHT gesendet werden")
            assertFalse(body.has("presence_penalty"), "Flag OFF ⇒ presence_penalty darf NICHT gesendet werden")
        }

    @Test
    fun `Flag ON sendet konfigurierte Sampling-Felder korrekt`() =
        withBrain { url, captured ->
            val adapter = MlxBrainAdapter(baseUrl = url, samplingEnabled = true, minP = 0.08, presencePenalty = 1.1)
            val body = turnBody(adapter, captured)
            assertEquals(0.08, body.path("min_p").asDouble(), 1e-9)
            assertEquals(1.1, body.path("presence_penalty").asDouble(), 1e-9)
        }

    @Test
    fun `Flag ON mit null-Werten laesst die Felder WEG (kein JSON-null)`() =
        withBrain { url, captured ->
            val adapter = MlxBrainAdapter(baseUrl = url, samplingEnabled = true, minP = null, presencePenalty = null)
            val body = turnBody(adapter, captured)
            assertFalse(body.has("min_p"), "null ⇒ Feld fehlt, nicht min_p:null")
            assertFalse(body.has("presence_penalty"), "null ⇒ Feld fehlt, nicht presence_penalty:null")
        }

    @Test
    fun `Flag ON mit nur min_p sendet presence_penalty nicht mit`() =
        withBrain { url, captured ->
            val adapter = MlxBrainAdapter(baseUrl = url, samplingEnabled = true, minP = 0.05)
            val body = turnBody(adapter, captured)
            assertEquals(0.05, body.path("min_p").asDouble(), 1e-9)
            assertFalse(body.has("presence_penalty"), "unkonfiguriert ⇒ Feld fehlt")
        }

    // ---- D1b (XTC/Opener): eigener Flag-Gate, unabhängig von samplingEnabled ----

    @Test
    fun `D1b OFF sendet KEINE XTC-Opener-Felder (byte-identischer Body)`() =
        withBrain { url, captured ->
            val body = turnBody(MlxBrainAdapter(baseUrl = url, d1bEnabled = false), captured)
            assertFalse(body.has("xtc_probability"), "Flag OFF ⇒ xtc_probability darf NICHT gesendet werden")
            assertFalse(body.has("xtc_threshold"), "Flag OFF ⇒ xtc_threshold darf NICHT gesendet werden")
            assertFalse(body.has("opener_bias"), "Flag OFF ⇒ opener_bias darf NICHT gesendet werden")
        }

    @Test
    fun `D1b ON sendet alle drei XTC-Opener-Felder mit Werten`() =
        withBrain { url, captured ->
            val adapter = MlxBrainAdapter(
                baseUrl = url,
                d1bEnabled = true,
                xtcProbability = 0.5,
                xtcThreshold = 0.1,
                openerBias = true,
            )
            val body = turnBody(adapter, captured)
            assertEquals(0.5, body.path("xtc_probability").asDouble(), 1e-9)
            assertEquals(0.1, body.path("xtc_threshold").asDouble(), 1e-9)
            assertTrue(body.path("opener_bias").asBoolean(), "opener_bias muss als true im Body stehen")
            // D1 bleibt unabhängig: ohne samplingEnabled kein min_p/presence_penalty.
            assertFalse(body.has("min_p"), "D1b allein darf keine D1-Felder senden")
            assertFalse(body.has("presence_penalty"), "D1b allein darf keine D1-Felder senden")
        }

    @Test
    fun `D1 und D1b zusammen senden alle fuenf Felder`() =
        withBrain { url, captured ->
            val adapter = MlxBrainAdapter(
                baseUrl = url,
                samplingEnabled = true,
                minP = 0.08,
                presencePenalty = 1.1,
                d1bEnabled = true,
                xtcProbability = 0.5,
                xtcThreshold = 0.1,
                openerBias = true,
            )
            val body = turnBody(adapter, captured)
            assertEquals(0.08, body.path("min_p").asDouble(), 1e-9)
            assertEquals(1.1, body.path("presence_penalty").asDouble(), 1e-9)
            assertEquals(0.5, body.path("xtc_probability").asDouble(), 1e-9)
            assertEquals(0.1, body.path("xtc_threshold").asDouble(), 1e-9)
            assertTrue(body.path("opener_bias").asBoolean())
        }

    // ---- Antwort-Entropie (S1): eigener Flag-Gate + null-tolerantes logprob-Parsen ----

    @Test
    fun `entropy-flag OFF sendet KEIN logprobs-feld (byte-identischer Body)`() =
        withBrain { url, captured ->
            val body = turnBody(MlxBrainAdapter(baseUrl = url), captured)
            assertFalse(body.has("logprobs"), "Flag OFF (Default) ⇒ logprobs darf NICHT gesendet werden")
        }

    @Test
    fun `entropy-flag ON sendet logprobs true im Body`() =
        withBrain { url, captured ->
            val body = turnBody(MlxBrainAdapter(baseUrl = url, entropyEnabled = true), captured)
            assertTrue(body.path("logprobs").asBoolean(), "Flag ON ⇒ logprobs:true im Body")
        }

    @Test
    fun `server OHNE logprobs (heutiges prod) - alle delta-logprobs bleiben null - stream unveraendert`() =
        withBrain(sse = "data: {\"delta\":\"Hallo \"}\n\ndata: {\"delta\":\"Welt.\"}\n\ndata: [DONE]\n\n") { url, _ ->
            val deltas = MlxBrainAdapter(baseUrl = url, entropyEnabled = true)
                .streamChat(prompt = "hi").collectList().block(Duration.ofSeconds(5))!!
            assertEquals(listOf("Hallo ", "Welt."), deltas.map { it.text }, "Text-Vertrag unverändert")
            assertTrue(deltas.all { it.logprob == null }, "kein logprob-Feld ⇒ null, NIE ein erfundener Wert")
        }

    @Test
    fun `server MIT logprobs - werte reisen exakt am LlmDelta`() =
        withBrain(
            sse = "data: {\"delta\":\"Der \",\"logprob\":-0.25}\n\n" +
                "data: {\"delta\":\"Turm\",\"logprob\":-1.75}\n\ndata: [DONE]\n\n",
        ) { url, _ ->
            val deltas = MlxBrainAdapter(baseUrl = url, entropyEnabled = true)
                .streamChat(prompt = "hi").collectList().block(Duration.ofSeconds(5))!!
            assertEquals(listOf(-0.25, -1.75), deltas.map { it.logprob })
        }

    @Test
    fun `nicht-numerischer logprob wird null-tolerant verworfen statt zu crashen`() =
        withBrain(
            sse = "data: {\"delta\":\"ok\",\"logprob\":\"kaputt\"}\n\n" +
                "data: {\"delta\":\"weiter\",\"logprob\":null}\n\ndata: [DONE]\n\n",
        ) { url, _ ->
            val deltas = MlxBrainAdapter(baseUrl = url, entropyEnabled = true)
                .streamChat(prompt = "hi").collectList().block(Duration.ofSeconds(5))!!
            assertEquals(listOf("ok", "weiter"), deltas.map { it.text }, "der Turn bricht NIE an einem defekten Feld")
            assertTrue(deltas.all { it.logprob == null }, "nicht-numerisch/null ⇒ null")
        }

    @Test
    fun `flag OFF parst trotzdem null-tolerant - stream bleibt der heutige`() =
        withBrain(sse = "data: {\"delta\":\"ok\",\"logprob\":-0.5}\n\ndata: [DONE]\n\n") { url, _ ->
            val deltas = MlxBrainAdapter(baseUrl = url)
                .streamChat(prompt = "hi").collectList().block(Duration.ofSeconds(5))!!
            assertEquals(listOf("ok"), deltas.map { it.text })
            // Der Parse ist zustandslos + ehrlich: liefert ein (fremd-gepatchter) Server
            // das Feld, trägt das LlmDelta es — MESSEN/loggen tut bei Flag OFF niemand.
            assertEquals(listOf(-0.5), deltas.map { it.logprob })
        }
}
