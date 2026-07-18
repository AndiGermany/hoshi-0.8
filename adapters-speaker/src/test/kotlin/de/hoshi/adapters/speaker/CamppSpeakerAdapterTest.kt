package de.hoshi.adapters.speaker

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.SpeakerEmbedPort
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [CamppSpeakerAdapter] OHNE Live-Infra: ein winziger JDK-HttpServer spielt den
 * CAM++-Sidecar (:9002) und liefert kanned `/embed`-JSON. Pure — der echte Sidecar-Beweis
 * steckt im Live-Smoke. Plus [SpeakerEmbedPort.similarity] als reine Cosine-Mathematik.
 */
class CamppSpeakerAdapterTest {

    /** Startet einen Fake-`/embed`, der `body`/`status` liefert und den Request-Body kapert. */
    private fun withSidecar(
        body: String,
        status: Int = 200,
        block: (url: String, captured: AtomicReference<String?>) -> Unit,
    ) {
        val captured = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embed") { ex ->
            captured.set(String(ex.requestBody.readBytes(), Charsets.UTF_8))
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

    private fun embeddingJson(vararg v: Double): String =
        """{"embedding":[${v.joinToString(",")}],"dim":${v.size}}"""

    @Test
    fun `WAV liefert Embedding, Request traegt base64-Audio + sampleRate`() =
        withSidecar(embeddingJson(0.1, 0.2, 0.3)) { url, captured ->
            val adapter = CamppSpeakerAdapter(baseUrl = url)
            val emb = adapter.embed("FAKE-WAV".toByteArray(), "audio/wav")
            assertNotNull(emb, "Embedding muss aus der Sidecar-Antwort kommen")
            assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), emb!!, 1e-6f)

            val reqBody = captured.get()
            assertNotNull(reqBody, "Sidecar muss angefragt worden sein")
            assertTrue(reqBody!!.contains("\"audio\""), "audio-Feld fehlt: $reqBody")
            assertTrue(reqBody.contains("\"sampleRate\":16000"), "sampleRate fehlt: $reqBody")
            val b64 = Base64.getEncoder().encodeToString("FAKE-WAV".toByteArray())
            assertTrue(reqBody.contains(b64), "base64-Audio fehlt im Request: $reqBody")
        }

    @Test
    fun `similarity - identisch ~1, orthogonal 0, ungleiche Groesse 0, Nullvektor 0`() {
        val port = SpeakerEmbedPort.NONE
        val a = floatArrayOf(0.6f, 0.8f) // Norm 1
        assertEquals(1.0, port.similarity(a, a), 1e-6)
        assertEquals(0.0, port.similarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6)
        assertEquals(0.0, port.similarity(floatArrayOf(1f), floatArrayOf(1f, 0f)), 1e-9)
        assertEquals(0.0, port.similarity(floatArrayOf(0f, 0f), a), 1e-9)
    }

    @Test
    fun `similarity == Dot-Product bei L2-normalisierten Vektoren`() {
        val a = floatArrayOf(0.6f, 0.8f)
        val b = floatArrayOf(0.8f, 0.6f)
        val dot = 0.6 * 0.8 + 0.8 * 0.6
        assertEquals(dot, SpeakerEmbedPort.NONE.similarity(a, b), 1e-6)
    }

    @Test
    fun `leeres Audio fragt den Sidecar gar nicht erst an`() =
        withSidecar(embeddingJson(0.1)) { url, captured ->
            val adapter = CamppSpeakerAdapter(baseUrl = url)
            assertNull(adapter.embed(ByteArray(0), "audio/wav"))
            assertNull(captured.get(), "leeres Audio darf keinen /embed-Call ausloesen")
        }

    @Test
    fun `Sidecar-Fehler 500 liefert null, nie Crash`() =
        withSidecar("kaputt", status = 500) { url, _ ->
            assertNull(CamppSpeakerAdapter(baseUrl = url).embed("FAKE".toByteArray(), "audio/wav"))
        }

    @Test
    fun `fehlendes embedding-Feld liefert null`() =
        withSidecar("""{"dim":0}""") { url, _ ->
            assertNull(CamppSpeakerAdapter(baseUrl = url).embed("FAKE".toByteArray(), "audio/wav"))
        }

    @Test
    fun `Sidecar down (connection refused) liefert null`() {
        val adapter = CamppSpeakerAdapter(baseUrl = "http://127.0.0.1:1", timeout = Duration.ofSeconds(2))
        assertNull(adapter.embed("FAKE".toByteArray(), "audio/wav"))
    }
}
