package de.hoshi.adapters.tts

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsSanitizePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [OpenAiTtsAdapter] OHNE echte Cloud/echten Key: ein winziger
 * JDK-HttpServer spielt `api.openai.com` und liefert kanned WAV-Bytes. Pure —
 * der echte API-Beweis steckt im Live-Smoke (`hoshi tts-openai`). KEIN echter Key.
 */
class OpenAiTtsAdapterTest {

    /** Startet einen Fake-`/v1/audio/speech`, der `body`/`status` liefert und die Anfrage kapert. */
    private fun withOpenAi(
        body: ByteArray,
        status: Int = 200,
        block: (url: String, captured: AtomicReference<RequestMeta?>) -> Unit,
    ) {
        val captured = AtomicReference<RequestMeta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/speech") { ex ->
            val raw = ex.requestBody.readBytes()
            captured.set(
                RequestMeta(
                    authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
                    contentType = ex.requestHeaders.getFirst("Content-Type") ?: "",
                    bodyText = String(raw, Charsets.UTF_8),
                ),
            )
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    data class RequestMeta(val authorization: String, val contentType: String, val bodyText: String)

    /** Minimaler gültiger WAV-Header (RIFF…WAVE) + ein paar Bytes — reicht als „Audio". */
    private val fakeWav: ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WAVEfmt ".toByteArray(Charsets.US_ASCII) + ByteArray(8)

    @Test
    fun `Text liefert WAV-Bytes aus der Fake-Antwort und setzt Bearer-Header`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "test-key-xyz", baseUrl = url)
            val wav = adapter.synth("Hallo, ich bin Hoshi.", Language.DE).block(Duration.ofSeconds(5))

            assertNotNull(wav, "WAV darf nicht null sein")
            assertTrue(wav!!.isNotEmpty(), "WAV muss Bytes haben")
            assertTrue(wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF", "RIFF-Header fehlt")

            val meta = captured.get()
            assertNotNull(meta, "OpenAI muss angefragt worden sein")
            // Bearer-Header gesetzt (Key fließt NUR hier rein).
            assertEquals("Bearer test-key-xyz", meta!!.authorization, "Bearer-Header falsch/fehlt")
            assertTrue(meta.contentType.startsWith("application/json"), "kein JSON: ${meta.contentType}")
            // Body-Vertrag: model/voice/input/response_format.
            assertTrue(meta.bodyText.contains("\"model\""), "model fehlt: ${meta.bodyText}")
            assertTrue(meta.bodyText.contains("\"voice\""), "voice fehlt")
            assertTrue(meta.bodyText.contains("Hallo, ich bin Hoshi."), "input-Text fehlt")
            assertTrue(meta.bodyText.contains("\"wav\""), "response_format=wav fehlt")
        }

    @Test
    fun `ohne Sanitizer geht der ROHE Text in den Body (Default IDENTITY, byte-neutral)`() =
        withOpenAi(fakeWav) { url, captured ->
            // Default-Ctor (kein sanitizer-Arg) ⇒ TtsSanitizePort.IDENTITY ⇒ roher Text.
            val adapter = OpenAiTtsAdapter(apiKey = "k", baseUrl = url)
            adapter.synth("Andi, dein Token ist sk-ABCDEFGHIJKLMNOP1234.", Language.DE).block(Duration.ofSeconds(5))
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("sk-ABCDEFGHIJKLMNOP1234"), "IDENTITY muss den rohen Text durchreichen: $body")
        }

    @Test
    fun `Sanitizer laeuft VOR dem Body-Bau — Maske im Body, roher Span weg, Name bleibt`() =
        withOpenAi(fakeWav) { url, captured ->
            // Fake-Sanitizer maskiert nur sk-Keys → beweist die Naht (sanitizeForSpeech VOR Body).
            val sanitizer = TtsSanitizePort { it.replace(Regex("""sk-[A-Za-z0-9]+"""), "[TOKEN]") }
            val adapter = OpenAiTtsAdapter(apiKey = "k", baseUrl = url, sanitizer = sanitizer)
            adapter.synth("Andi, dein Token ist sk-ABCDEFGHIJKLMNOP1234.", Language.DE).block(Duration.ofSeconds(5))
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("[TOKEN]"), "Maske fehlt im Body: $body")
            assertFalse(body.contains("sk-ABCDEFGHIJKLMNOP1234"), "roher Token darf NICHT im Body sein: $body")
            assertTrue(body.contains("Andi"), "der Name muss erhalten bleiben: $body")
        }

    @Test
    fun `model und voice aus dem Konstruktor landen im Body`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", model = "tts-1", voice = "coral", baseUrl = url)
            adapter.synth("Test.", Language.EN).block(Duration.ofSeconds(5))
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("tts-1"), "model-Override fehlt: $body")
            assertTrue(body.contains("coral"), "voice fehlt: $body")
        }

    @Test
    fun `401 liefert best-effort leeren ByteArray, nie Crash`() =
        withOpenAi("unauthorized".toByteArray(), status = 401) { url, _ ->
            val adapter = OpenAiTtsAdapter(apiKey = "bad-key", baseUrl = url)
            val wav = adapter.synth("Hallo.", Language.DE).block(Duration.ofSeconds(5))
            assertNotNull(wav)
            assertTrue(wav!!.isEmpty(), "401 muss leeren ByteArray liefern")
        }

    @Test
    fun `fehlender Key fragt die API gar nicht erst an und liefert leeren ByteArray`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "   ", baseUrl = url)
            val wav = adapter.synth("Hallo.", Language.DE).block(Duration.ofSeconds(5))
            assertNotNull(wav)
            assertTrue(wav!!.isEmpty(), "ohne Key → leerer ByteArray")
            assertNull(captured.get(), "ohne Key darf kein API-Call rausgehen")
        }

    @Test
    fun `leerer Text fragt die API gar nicht erst an`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", baseUrl = url)
            val wav = adapter.synth("   ", Language.DE).block(Duration.ofSeconds(5))
            assertNull(wav, "leerer Text → Mono.empty()")
            assertNull(captured.get(), "leerer Text darf keinen API-Call auslösen")
        }

    @Test
    fun `API down (connection refused) liefert leeren ByteArray`() {
        // Port, auf dem nichts lauscht → connection refused → leerer ByteArray.
        val adapter = OpenAiTtsAdapter(apiKey = "k", baseUrl = "http://127.0.0.1:1", timeoutSeconds = 2)
        val wav = adapter.synth("Hallo.", Language.DE).block(Duration.ofSeconds(5))
        assertNotNull(wav)
        assertFalse(wav!!.isNotEmpty(), "API down → leerer ByteArray")
    }

    // ── Per-Turn-Voice (Backlog #6): Whitelist-Riegel, nie roh durchreichen ──────

    @Test
    fun `request-voice aus der Whitelist landet im Body statt des Boot-Defaults`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", voice = "coral", baseUrl = url)
            adapter.synth("Hallo.", Language.DE, "nova").block(Duration.ofSeconds(5))
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("\"voice\":\"nova\""), "request-voice fehlt im Body: $body")
            assertFalse(body.contains("coral"), "Boot-Default darf den request-voice nicht überstimmen: $body")
        }

    @Test
    fun `unbekannte request-voice faellt still auf den Boot-Default zurueck — nie roh in den Body`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", voice = "coral", baseUrl = url)
            adapter.synth("Hallo.", Language.DE, "eddie-der-adler").block(Duration.ofSeconds(5))
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("\"voice\":\"coral\""), "Fallback auf Boot-Default fehlt: $body")
            assertFalse(body.contains("eddie-der-adler"), "unbekannte Voice darf NIE roh in den Body: $body")
        }

    @Test
    fun `request-voice wird getrimmt und case-insensitiv normalisiert`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", voice = "coral", baseUrl = url)
            adapter.synth("Hallo.", Language.DE, "  Nova ").block(Duration.ofSeconds(5))
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("\"voice\":\"nova\""), "normalisierte Voice fehlt im Body: $body")
        }

    @Test
    fun `null-voice ueber den voice-aware Overload nutzt den Boot-Default (byte-neutral)`() =
        withOpenAi(fakeWav) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", voice = "coral", baseUrl = url)
            adapter.synth("Hallo.", Language.DE, null).block(Duration.ofSeconds(5))
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("\"voice\":\"coral\""), "null muss den Boot-Default nutzen: $body")
        }

    @Test
    fun `die Whitelist enthaelt die doku-verifizierten Stimmen des Backlog-6-Pickers`() {
        // Kern-Auftrag (Andis Button) + die 2026-07 doku-verifizierten Neuzugänge.
        val briefing = setOf("alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer")
        assertTrue(
            OpenAiTtsAdapter.SUPPORTED_VOICES.containsAll(briefing),
            "Whitelist muss die Backlog-6-Stimmen tragen: ${OpenAiTtsAdapter.SUPPORTED_VOICES}",
        )
        assertTrue(
            OpenAiTtsAdapter.SUPPORTED_VOICES.containsAll(setOf("verse", "marin", "cedar")),
            "die doku-verifizierten gpt-4o-mini-tts-Neuzugänge fehlen",
        )
    }
}
