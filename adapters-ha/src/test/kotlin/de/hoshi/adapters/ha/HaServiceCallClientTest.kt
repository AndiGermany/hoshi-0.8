package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * **HaServiceCallClientTest** — der Service-Call-Vertrag gegen einen FAKE-HA
 * (eingebetteter `com.sun.net.httpserver.HttpServer`, Muster [HaToolPortTest]):
 * NIE ein echter Home-Assistant-Call im Test.
 *
 * Bewiesen: der Client trifft exakt `POST /api/services/{domain}/{service}` mit
 * `{"entity_id": …}` und Bearer-Token · 2xx ⇒ [ServiceCallOutcome.Accepted] mit
 * echtem Status · Nicht-2xx ⇒ [ServiceCallOutcome.Failed] MIT HA's Statuscode
 * (ehrliche Durchreichung) · kein Token ⇒ Failed OHNE jeden Call.
 */
class HaServiceCallClientTest {

    private data class RequestMeta(val method: String, val path: String, val authorization: String, val body: String)

    private fun metaOf(ex: HttpExchange) = RequestMeta(
        method = ex.requestMethod,
        path = ex.requestURI.path,
        authorization = ex.requestHeaders.getFirst("Authorization").orEmpty(),
        body = ex.requestBody.readBytes().decodeToString(),
    )

    private fun withHa(status: Int, block: (url: String, seen: AtomicReference<RequestMeta?>) -> Unit) {
        val seen = AtomicReference<RequestMeta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/services/") { ex ->
            seen.set(metaOf(ex))
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", seen)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `start trifft den richtigen Service mit der richtigen Entity`() = withHa(200) { url, seen ->
        val client = HaServiceCallClient(baseUrl = url, token = "secret-token")

        val outcome = client.callService("vacuum", "start", "vacuum.roborock")

        assertEquals(ServiceCallOutcome.Accepted(200), outcome)
        val req = seen.get()!!
        assertEquals("POST", req.method)
        assertEquals("/api/services/vacuum/start", req.path)
        assertEquals("Bearer secret-token", req.authorization)
        assertTrue(req.body.contains("\"entity_id\":\"vacuum.roborock\""), "Body trug die Entity nicht: ${req.body}")
    }

    @Test
    fun `return_to_base trifft den richtigen Service`() = withHa(200) { url, seen ->
        val client = HaServiceCallClient(baseUrl = url, token = "secret-token")

        client.callService("vacuum", "return_to_base", "vacuum.roborock")

        assertEquals("/api/services/vacuum/return_to_base", seen.get()!!.path)
    }

    @Test
    fun `HA-Fehlerstatus wird ehrlich mit Statuscode durchgereicht`() = withHa(503) { url, _ ->
        val client = HaServiceCallClient(baseUrl = url, token = "secret-token")

        val outcome = client.callService("vacuum", "start", "vacuum.roborock")

        assertEquals(ServiceCallOutcome.Failed("ha-http-503", httpStatus = 503), outcome)
    }

    @Test
    fun `ohne Token - Failed no-token und KEIN Call`() = withHa(200) { url, seen ->
        val client = HaServiceCallClient(baseUrl = url, token = null)

        val outcome = client.callService("vacuum", "start", "vacuum.roborock")

        assertEquals(ServiceCallOutcome.Failed("no-token"), outcome)
        assertNull((outcome as ServiceCallOutcome.Failed).httpStatus)
        assertNull(seen.get(), "Ohne Token darf HA nie berührt werden")
    }
}
