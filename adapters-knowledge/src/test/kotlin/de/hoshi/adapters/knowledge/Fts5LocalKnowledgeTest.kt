package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist das explizite lokale Opt-in des FTS5-Adapters und dass es dieselbe
 * Fetchlogik genau einmal nutzt.
 */
class Fts5LocalKnowledgeTest {

    @Test
    fun `localKnowledgeBlock liefert lokalen Wiki-Block mit genau einem Bridge-Request`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { exchange ->
            calls.incrementAndGet()
            val body = """
                {
                  "hits":[{
                    "title":"Sonnensystem",
                    "bm25Score":-42.0,
                    "extract":"Das Sonnensystem umfasst acht Planeten.",
                    "summary":null,
                    "facts":[]
                  }]
                }
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val adapter = Fts5GroundingAdapter(
                baseUrl = "http://127.0.0.1:${server.address.port}",
            )

            val block = adapter
                .localKnowledgeBlock(
                    "Was bedeutet Sonnensystem?",
                    RouteCategory.FACT_SHORT,
                    Language.DE,
                )
                .block(Duration.ofSeconds(5))!!

            assertTrue(block.contains("acht Planeten"), "die normale FTS-Filter-/Blocklogik bleibt erhalten")
            assertEquals(1, calls.get(), "Opt-in delegiert einmal; es gibt keine zweite Fetchlogik")
        } finally {
            server.stop(0)
        }
    }
}
