package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [HaSceneCatalogAdapter] OHNE echtes HA: ein winziger JDK-HttpServer
 * spielt `/api/states` (wie [HaToolPortTest]). KEIN echter HA-Call, READ-ONLY.
 *
 * Fälle: (a) parst scene.*-entity_ids + strippt Präfix; (b) cacht (nur 1 GET);
 * (c) blank/null Token ⇒ leer OHNE HTTP; (d) HTTP-500 ⇒ leer (never-throw);
 * (e) HA down ⇒ leer (never-throw); (f) korrekter GET + Bearer.
 */
class HaSceneCatalogAdapterTest {

    private val statesJson = """
        [
          {"entity_id":"scene.wohnzimmer_nordlichter","state":"x"},
          {"entity_id":"light.flur_decke","state":"on"},
          {"entity_id":"scene.kuche_gedimmt","state":"x"},
          {"entity_id":"climate.schlafzimmer","state":"heat"}
        ]
    """.trimIndent()

    data class Meta(val method: String, val path: String, val authorization: String)

    private fun withHa(
        status: Int = 200,
        body: String = statesJson,
        block: (url: String, calls: AtomicInteger, last: AtomicReference<Meta?>) -> Unit,
    ) {
        val calls = AtomicInteger(0)
        val last = AtomicReference<Meta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/states") { ex ->
            calls.incrementAndGet()
            last.set(
                Meta(
                    method = ex.requestMethod,
                    path = ex.requestURI.path,
                    authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
                ),
            )
            respond(ex, status, body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", calls, last)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `parst scene-entities und strippt das Praefix`() = withHa { url, _, last ->
        val adapter = HaSceneCatalogAdapter(baseUrl = url, token = "secret-token")
        val ids = adapter.sceneIds()
        assertEquals(listOf("wohnzimmer_nordlichter", "kuche_gedimmt"), ids)

        val meta = last.get()!!
        assertEquals("GET", meta.method)
        assertEquals("/api/states", meta.path)
        assertEquals("Bearer secret-token", meta.authorization)
    }

    @Test
    fun `cacht den Katalog (nur ein HTTP-Call)`() = withHa { url, calls, _ ->
        val adapter = HaSceneCatalogAdapter(baseUrl = url, token = "secret-token")
        adapter.sceneIds()
        adapter.sceneIds()
        adapter.sceneIds()
        assertEquals(1, calls.get(), "Katalog muss nach dem ersten Load gecacht sein")
    }

    @Test
    fun `blank Token liefert leer ohne HTTP-Call`() = withHa { url, calls, _ ->
        val adapter = HaSceneCatalogAdapter(baseUrl = url, token = "   ")
        assertTrue(adapter.sceneIds().isEmpty())
        assertEquals(0, calls.get(), "ohne Token darf kein GET rausgehen")
    }

    @Test
    fun `null Token liefert leer ohne HTTP-Call`() = withHa { url, calls, _ ->
        val adapter = HaSceneCatalogAdapter(baseUrl = url, token = null)
        assertTrue(adapter.sceneIds().isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `HTTP-500 liefert leer statt zu werfen`() = withHa(status = 500, body = "boom") { url, _, _ ->
        val adapter = HaSceneCatalogAdapter(baseUrl = url, token = "secret-token")
        assertTrue(adapter.sceneIds().isEmpty())
    }

    @Test
    fun `HA down (connection refused) liefert leer`() {
        val adapter = HaSceneCatalogAdapter(baseUrl = "http://127.0.0.1:1", token = "secret-token", timeoutMs = 1500)
        assertTrue(adapter.sceneIds().isEmpty())
    }
}
