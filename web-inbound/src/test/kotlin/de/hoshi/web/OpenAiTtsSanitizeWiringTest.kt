package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsSanitizePort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Verdrahtet den ECHTEN [NeverSpeakTtsSanitizer] (web-inbound) in den
 * [OpenAiTtsAdapter] (adapters-tts) gegen einen Fake-`api.openai.com` und prueft den
 * RAUSGEHENDEN Request-Body direkt — der P0-Leck-Riegel end-to-end an der Cloud-Naht.
 *
 *  - Flag ON (echter Sanitizer): die Never-Speak-Spans tauchen im Body NICHT auf,
 *    der NAME schon.
 *  - Flag OFF ([TtsSanitizePort.IDENTITY]): der Body ist byte-identisch zum rohen Text.
 */
class OpenAiTtsSanitizeWiringTest {

    private fun withOpenAi(block: (url: String, capturedBody: AtomicReference<String?>) -> Unit) {
        val captured = AtomicReference<String?>(null)
        val wav = "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) +
            "WAVEfmt ".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/speech") { ex ->
            captured.set(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            ex.sendResponseHeaders(200, wav.size.toLong())
            ex.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    private val sentence =
        "Andi, dein Key sk-ABCDEFGHIJKLMNOP1234 oeffnet https://ha.local/api auf 192.168.178.56, " +
            "session 123e4567-e89b-12d3-a456-426614174000, schalte light.wohnzimmer ein."

    @Test
    fun `Flag ON — Never-Speak-Spans NICHT im OpenAI-Request-Body, Name schon`() =
        withOpenAi { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", baseUrl = url, sanitizer = NeverSpeakTtsSanitizer())
            adapter.synth(sentence, Language.DE).block(Duration.ofSeconds(5))

            val body = captured.get()
            assertNotNull(body, "OpenAI muss angefragt worden sein")
            // Kein einziger Never-Speak-Span im rausgehenden Body.
            assertFalse(body!!.contains("sk-ABCDEFGHIJKLMNOP1234"), "Token im Body: $body")
            assertFalse(body.contains("https://ha.local/api"), "URL im Body: $body")
            assertFalse(body.contains("192.168.178.56"), "IP im Body: $body")
            assertFalse(body.contains("123e4567-e89b-12d3-a456-426614174000"), "UUID im Body: $body")
            assertFalse(body.contains("light.wohnzimmer"), "Entity-ID im Body: $body")
            // Der Name bleibt (warmes Audio).
            assertTrue(body.contains("Andi"), "Name fehlt im Body: $body")
        }

    @Test
    fun `Flag OFF (IDENTITY) — Body enthaelt den ROHEN Text byte-identisch`() =
        withOpenAi { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", baseUrl = url, sanitizer = TtsSanitizePort.IDENTITY)
            adapter.synth(sentence, Language.DE).block(Duration.ofSeconds(5))

            val body = captured.get()!!
            // IDENTITY ⇒ jeder rohe Span ist (wie heute) im Body — byte-neutral.
            assertTrue(body.contains("sk-ABCDEFGHIJKLMNOP1234"), "IDENTITY muss roh durchreichen: $body")
            assertTrue(body.contains("https://ha.local/api"), "IDENTITY muss roh durchreichen: $body")
            assertTrue(body.contains("192.168.178.56"), "IDENTITY muss roh durchreichen: $body")
            assertTrue(body.contains("light.wohnzimmer"), "IDENTITY muss roh durchreichen: $body")
        }
}
