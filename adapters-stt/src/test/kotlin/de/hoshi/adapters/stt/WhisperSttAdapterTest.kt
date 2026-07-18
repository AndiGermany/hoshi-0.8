package de.hoshi.adapters.stt

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [WhisperSttAdapter] OHNE Live-Infra: ein winziger JDK-HttpServer
 * spielt den Whisper-MLX-Sidecar (:9001) und liefert kanned `/asr`-JSON. Pure —
 * der echte Sidecar-Beweis steckt im Live-Smoke (`hoshi voicein`).
 */
class WhisperSttAdapterTest {

    /** Startet einen Fake-`/asr` der `body`/`status` liefert und die Anfrage-Metadaten kapert. */
    private fun withWhisper(
        body: String,
        status: Int = 200,
        block: (url: String, captured: AtomicReference<RequestMeta?>) -> Unit,
    ) {
        val captured = AtomicReference<RequestMeta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asr") { ex ->
            val raw = ex.requestBody.readBytes()
            captured.set(
                RequestMeta(
                    query = ex.requestURI.query ?: "",
                    contentType = ex.requestHeaders.getFirst("Content-Type") ?: "",
                    bodyLen = raw.size,
                    bodyText = String(raw, Charsets.ISO_8859_1),
                ),
            )
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    data class RequestMeta(val query: String, val contentType: String, val bodyLen: Int, val bodyText: String)

    @Test
    fun `WAV liefert Transkript aus der Sidecar-Antwort`() =
        withWhisper("""{"text":"Wer war Konrad Adenauer?"}""") { url, captured ->
            val adapter = WhisperSttAdapter(baseUrl = url)
            val text = adapter.transcribe("FAKE-WAV-BYTES".toByteArray(), Language.DE)
                .block(Duration.ofSeconds(5))

            assertEquals("Wer war Konrad Adenauer?", text)
            val meta = captured.get()
            assertNotNull(meta, "Sidecar muss angefragt worden sein")
            // language-Hint geht als Query mit (multilingual).
            assertTrue(meta!!.query.contains("language=de"), "language-Hint fehlt: ${meta.query}")
            assertTrue(meta.query.contains("task=transcribe"), "task fehlt: ${meta.query}")
            // multipart mit Datei-Feld `audio_file` (sonst behandelt FastAPI es als Form-Feld).
            assertTrue(meta.contentType.startsWith("multipart/form-data"), "kein multipart: ${meta.contentType}")
            assertTrue(meta.bodyText.contains("name=\"audio_file\""), "audio_file-Part fehlt")
            assertTrue(meta.bodyText.contains("filename=\"audio.wav\""), "Dateiname fehlt (UploadFile braucht ihn)")
        }

    @Test
    fun `EN-Frage gibt language=en als Hint mit`() =
        withWhisper("""{"text":"Who was Konrad Adenauer?"}""") { url, captured ->
            val adapter = WhisperSttAdapter(baseUrl = url)
            val text = adapter.transcribe("FAKE".toByteArray(), Language.EN).block(Duration.ofSeconds(5))
            assertEquals("Who was Konrad Adenauer?", text)
            assertTrue(captured.get()!!.query.contains("language=en"))
        }

    @Test
    fun `ES-FR-IT-Sprachwahl gibt den jeweiligen language-Hint mit (Sprachpaket-Kern 2026-07-20)`() {
        listOf(
            Language.ES to "es",
            Language.FR to "fr",
            Language.IT to "it",
        ).forEach { (language, code) ->
            withWhisper("""{"text":"transkript"}""") { url, captured ->
                val adapter = WhisperSttAdapter(baseUrl = url)
                adapter.transcribe("FAKE".toByteArray(), language).block(Duration.ofSeconds(5))
                assertTrue(
                    captured.get()!!.query.contains("language=$code"),
                    "STT-language muss der Sprach-Wahl folgen ($language): ${captured.get()!!.query}",
                )
            }
        }
    }

    @Test
    fun `null-Sprache laesst den language-Hint WEG (Whisper auto-detect)`() =
        withWhisper("""{"text":"Wer war Konrad Adenauer?"}""") { url, captured ->
            val adapter = WhisperSttAdapter(baseUrl = url)
            val text = adapter.transcribe("FAKE".toByteArray(), null).block(Duration.ofSeconds(5))
            assertEquals("Wer war Konrad Adenauer?", text)
            val meta = captured.get()
            assertNotNull(meta, "Sidecar muss angefragt worden sein")
            // Kein language-Query ⇒ Whisper erkennt selbst (AUTO). task=transcribe bleibt.
            assertTrue(!meta!!.query.contains("language="), "language-Hint darf fehlen: ${meta.query}")
            assertTrue(meta.query.contains("task=transcribe"), "task fehlt: ${meta.query}")
        }

    @Test
    fun `Stille-Gate (leerer text) liefert leeren String, kein Fehler`() =
        withWhisper("""{"text":""}""") { url, _ ->
            val adapter = WhisperSttAdapter(baseUrl = url)
            assertEquals("", adapter.transcribe("FAKE".toByteArray(), Language.DE).block(Duration.ofSeconds(5)))
        }

    @Test
    fun `leeres Audio fragt den Sidecar gar nicht erst an`() =
        withWhisper("""{"text":"darf nicht kommen"}""") { url, captured ->
            val adapter = WhisperSttAdapter(baseUrl = url)
            assertEquals("", adapter.transcribe(ByteArray(0), Language.DE).block(Duration.ofSeconds(5)))
            assertEquals(null, captured.get(), "leeres Audio darf keinen Sidecar-Call auslösen")
        }

    @Test
    fun `Sidecar-Fehler (500) liefert best-effort leeren String, nie Crash`() =
        withWhisper("kaputt", status = 500) { url, _ ->
            val adapter = WhisperSttAdapter(baseUrl = url)
            assertEquals("", adapter.transcribe("FAKE".toByteArray(), Language.DE).block(Duration.ofSeconds(5)))
        }

    @Test
    fun `Sidecar down (connection refused) liefert leeren String`() {
        // Port, auf dem nichts lauscht → connection refused → leerer String.
        val adapter = WhisperSttAdapter(baseUrl = "http://127.0.0.1:1", timeoutSeconds = 2)
        assertEquals("", adapter.transcribe("FAKE".toByteArray(), Language.DE).block(Duration.ofSeconds(5)))
    }
}
