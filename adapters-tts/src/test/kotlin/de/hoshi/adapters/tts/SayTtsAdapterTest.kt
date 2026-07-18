package de.hoshi.adapters.tts

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
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
 * Beweist den [SayTtsAdapter] OHNE echten Mac-Sidecar: ein winziger JDK-
 * HttpServer spielt `sidecars/say/server.py` und liefert kanned WAV-Bytes /
 * Fehler-Statuscodes. Gleiches Muster wie [OpenAiTtsAdapterTest] (Fake-HTTP,
 * kein Mock-Framework) — Never-Silent-Verhalten (ok/timeout/5xx) wird hier
 * GENAUSO bewiesen wie beim OpenAI-Geschwister-Adapter (leerer ByteArray,
 * nie ein Crash).
 */
class SayTtsAdapterTest {

    /** Startet einen Fake-`/tts`, der `body`/`status` liefert und die Anfrage kapert. */
    private fun withSaySidecar(
        body: ByteArray,
        status: Int = 200,
        delayMillis: Long = 0,
        block: (url: String, captured: AtomicReference<String?>) -> Unit,
    ) {
        val captured = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tts") { ex ->
            val raw = ex.requestBody.readBytes()
            captured.set(String(raw, Charsets.UTF_8))
            if (delayMillis > 0) Thread.sleep(delayMillis)
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

    /** Minimaler gültiger WAV-Header (RIFF…WAVE) + ein paar Bytes — reicht als „Audio". */
    private val fakeWav: ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WAVEfmt ".toByteArray(Charsets.US_ASCII) + ByteArray(8)

    @Test
    fun `Text liefert WAV-Bytes aus der Fake-Antwort`() =
        withSaySidecar(fakeWav) { url, captured ->
            val adapter = SayTtsAdapter(baseUrl = url)
            val wav = adapter.synth("Hallo, ich bin Hoshi.", Language.DE).block(Duration.ofSeconds(5))

            assertNotNull(wav, "WAV darf nicht null sein")
            assertTrue(wav!!.isNotEmpty(), "WAV muss Bytes haben")
            assertTrue(wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF", "RIFF-Header fehlt")

            val body = captured.get()
            assertNotNull(body, "Sidecar muss angefragt worden sein")
            assertTrue(body!!.contains("\"text\""), "text fehlt im Body: $body")
            assertTrue(body.contains("Hallo, ich bin Hoshi."), "Text-Inhalt fehlt im Body: $body")
            // Kein lang-Feld — der say-Sidecar-Kontrakt kennt es bewusst nicht.
            assertFalse(body.contains("\"lang\""), "say-Kontrakt darf KEIN lang-Feld senden: $body")
        }

    @Test
    fun `konfigurierte voice und rate landen im Body`() =
        withSaySidecar(fakeWav) { url, captured ->
            val adapter = SayTtsAdapter(baseUrl = url, voice = "Anna", rate = 190)
            adapter.synth("Test.", Language.DE).block(Duration.ofSeconds(5))
            val body = captured.get()!!
            assertTrue(body.contains("\"voice\":\"Anna\""), "voice fehlt im Body: $body")
            assertTrue(body.contains("\"rate\":190"), "rate fehlt im Body: $body")
        }

    @Test
    fun `ohne konfigurierte voice und rate bleiben die Felder im Body weg (Sidecar-Default greift)`() =
        withSaySidecar(fakeWav) { url, captured ->
            val adapter = SayTtsAdapter(baseUrl = url)
            adapter.synth("Test.", Language.DE).block(Duration.ofSeconds(5))
            val body = captured.get()!!
            assertFalse(body.contains("\"voice\""), "voice-Feld darf ohne Konfiguration nicht gesendet werden: $body")
            assertFalse(body.contains("\"rate\""), "rate-Feld darf ohne Konfiguration nicht gesendet werden: $body")
        }

    @Test
    fun `leerer Text fragt den Sidecar gar nicht erst an`() =
        withSaySidecar(fakeWav) { url, captured ->
            val adapter = SayTtsAdapter(baseUrl = url)
            val wav = adapter.synth("   ", Language.DE).block(Duration.ofSeconds(5))
            assertNull(wav, "leerer Text → Mono.empty()")
            assertNull(captured.get(), "leerer Text darf keinen Sidecar-Call auslösen")
        }

    // ── Never-Silent: ok/timeout/5xx wie beim OpenAI-Geschwister-Adapter ────────

    @Test
    fun `5xx vom Sidecar liefert best-effort leeren ByteArray, nie Crash`() =
        withSaySidecar("synthesis failed".toByteArray(), status = 500) { url, _ ->
            val adapter = SayTtsAdapter(baseUrl = url)
            val wav = adapter.synth("Hallo.", Language.DE).block(Duration.ofSeconds(5))
            assertNotNull(wav)
            assertTrue(wav!!.isEmpty(), "5xx muss leeren ByteArray liefern")
        }

    @Test
    fun `413 (Text zu lang) liefert best-effort leeren ByteArray, nie Crash`() =
        withSaySidecar("text too long".toByteArray(), status = 413) { url, _ ->
            val adapter = SayTtsAdapter(baseUrl = url)
            val wav = adapter.synth("Hallo.", Language.DE).block(Duration.ofSeconds(5))
            assertNotNull(wav)
            assertTrue(wav!!.isEmpty(), "413 muss leeren ByteArray liefern")
        }

    @Test
    fun `Timeout liefert best-effort leeren ByteArray, nie Crash`() =
        // Sidecar antwortet erst nach 1500ms, Adapter-Timeout ist 1s — der Adapter-
        // Timeout MUSS zuerst feuern (1s < 1500ms), das ist der echte Timeout-Pfad
        // (timeoutSeconds ist granular in ganzen Sekunden, darum die 1500ms-Lücke).
        withSaySidecar(fakeWav, delayMillis = 1500) { url, _ ->
            val adapter = SayTtsAdapter(baseUrl = url, timeoutSeconds = 1)
            val wav = adapter.synth("Hallo.", Language.DE).block(Duration.ofSeconds(5))
            assertNotNull(wav)
            assertTrue(wav!!.isEmpty(), "Timeout muss leeren ByteArray liefern")
        }

    @Test
    fun `Sidecar down (connection refused) liefert leeren ByteArray`() {
        // Port, auf dem nichts lauscht → connection refused → leerer ByteArray.
        val adapter = SayTtsAdapter(baseUrl = "http://127.0.0.1:1", timeoutSeconds = 2)
        val wav = adapter.synth("Hallo.", Language.DE).block(Duration.ofSeconds(5))
        assertNotNull(wav)
        assertTrue(wav!!.isEmpty(), "Sidecar down → leerer ByteArray")
    }

    // ── Per-Turn-Voice bewusst ignoriert (wie VoxtralTtsAdapter) ────────────────

    @Test
    fun `voice-aware Overload ignoriert den Request-Wunsch ehrlich (Delegation an synth)`() =
        withSaySidecar(fakeWav) { url, captured ->
            val adapter = SayTtsAdapter(baseUrl = url, voice = "Anna")
            adapter.synth("Hallo.", Language.DE, "coral").block(Duration.ofSeconds(5))
            val body = captured.get()!!
            // "coral" ist eine OpenAI-Voice, KEIN macOS-Stimmname — darf nie im Body landen.
            assertFalse(body.contains("coral"), "Request-Voice darf nicht heimlich gemappt werden: $body")
            assertTrue(body.contains("\"voice\":\"Anna\""), "Boot-Default muss stehen bleiben: $body")
        }

    // ── health() ──────────────────────────────────────────────────────────────

    @Test
    fun `health liefert true bei status ok`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val body = """{"status":"ok","engine":"say"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val adapter = SayTtsAdapter(baseUrl = "http://127.0.0.1:${server.address.port}")
            val healthy = adapter.health().block(Duration.ofSeconds(5))
            assertEquals(true, healthy)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `health liefert false wenn der Sidecar down ist`() {
        val adapter = SayTtsAdapter(baseUrl = "http://127.0.0.1:1")
        val healthy = adapter.health().block(Duration.ofSeconds(5))
        assertEquals(false, healthy)
    }
}
