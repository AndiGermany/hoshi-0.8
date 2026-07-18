package de.hoshi.adapters.memory

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist, dass die Embed-Basis-URL des episodischen Gedächtnisses **konfigurierbar**
 * ist (ct-106: embeddinggemma lebt auf dem Mac im LAN, nicht auf `localhost:11434`).
 *
 * Statt gegen ein echtes Ollama zu sprechen (kein Netz im `verify`), fährt der Test
 * einen winzigen JDK-[HttpServer] auf einem freien Port und zeigt: der
 * [OllamaEpisodicEmbedder] mit injizierter `baseUrl` trifft GENAU diesen Server unter
 * `/api/embeddings` und parst dessen Embedding. Der Default `http://localhost:11434`
 * würde diesen Server nie treffen — der Treffer beweist, dass die URL verwendet wird.
 */
class OllamaEpisodicEmbedderTest {

    @Test
    fun `embed nutzt die injizierte baseUrl und parst das Embedding`() {
        val hitPath = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/embeddings") { exchange ->
            hitPath.set(exchange.requestURI.path)
            val body = """{"embedding":[0.1,0.2,0.3]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val embedder = OllamaEpisodicEmbedder(baseUrl = "http://127.0.0.1:$port")

            val vec = embedder.embed("hallo welt")

            assertEquals("/api/embeddings", hitPath.get(), "die injizierte baseUrl wurde wirklich getroffen")
            assertArrayEquals(doubleArrayOf(0.1, 0.2, 0.3), vec, 1e-9, "das Embedding des konfigurierten Sidecars wird geparst")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `default baseUrl bleibt localhost 11434 (byte-neutral)`() {
        // Der Default ist UNVERÄNDERT: wird keine URL gesetzt, spricht das Gedächtnis
        // weiter den lokalen Sidecar an (Verhalten wie bisher). Kein Netz-Call hier,
        // um nicht versehentlich ein lokal laufendes Ollama zu treffen (Flake-Schutz).
        assertEquals("http://localhost:11434", EpisodicMemoryAdapter.DEFAULT_EMBED_URL)
    }
}
