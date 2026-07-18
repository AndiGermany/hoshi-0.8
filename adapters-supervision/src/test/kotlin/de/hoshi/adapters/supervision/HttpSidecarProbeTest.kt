package de.hoshi.adapters.supervision

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.supervision.HealthState
import de.hoshi.core.supervision.SidecarSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Beweist das ehrliche Mapping des [HttpSidecarProbe] OHNE Live-Infra: ein winziger
 * JDK-HttpServer liefert kanned `/health`-Bodies, wir prüfen OK/DEGRADED/DOWN.
 */
class HttpSidecarProbeTest {

    private val probe = HttpSidecarProbe(timeout = Duration.ofSeconds(2))

    private fun withServer(body: String, status: Int = 200, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun spec(url: String, expectedModel: String? = null) =
        SidecarSpec("test", url, ramCostMb = 100, expectedModel = expectedModel)

    @Test
    fun `status ok ist OK und reicht das gemessene model durch`() = withServer(
        """{"status":"ok","model":"mlx-community/gemma-4-e4b-it-4bit"}""",
    ) { url ->
        val h = probe.probe(spec(url, expectedModel = "gemma-4-e4b-it-4bit"))
        assertEquals(HealthState.OK, h.state)
        assertEquals("mlx-community/gemma-4-e4b-it-4bit", h.measuredModel)
    }

    @Test
    fun `status loading ist DEGRADED, nicht OK`() = withServer(
        """{"status":"loading"}""",
    ) { url ->
        assertEquals(HealthState.DEGRADED, probe.probe(spec(url)).state)
    }

    @Test
    fun `model-Drift gegen Soll ist DEGRADED`() = withServer(
        """{"status":"ok","model":"some-other-model"}""",
    ) { url ->
        assertEquals(HealthState.DEGRADED, probe.probe(spec(url, expectedModel = "gemma-4-e4b-it-4bit")).state)
    }

    @Test
    fun `keine Antwort ist DOWN`() {
        // Port, auf dem nichts lauscht → connection refused → DOWN.
        val h = probe.probe(spec("http://127.0.0.1:1"))
        assertEquals(HealthState.DOWN, h.state)
    }
}
