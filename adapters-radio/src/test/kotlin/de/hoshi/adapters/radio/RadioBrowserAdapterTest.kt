package de.hoshi.adapters.radio

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [RadioBrowserAdapter] OHNE echte radio-browser-API: ein winziger
 * JDK-HttpServer (`com.sun.net.httpserver`, wie [de.hoshi.adapters.ha]-Tests)
 * spielt `de1.api.radio-browser.info` und kapert die Anfrage.
 *
 * Kern der Scheibe ist die **Andi-Schwelle**: Match NUR bei echter
 * Namensähnlichkeit — „WDR 2" matcht, der Köder „xyzzy" liefert `null`,
 * auch wenn die API irgendwas zurückgibt (kein „bester Treffer trotzdem").
 */
class RadioBrowserAdapterTest {

    data class RequestMeta(val path: String, val query: String?, val userAgent: String?)

    /** Startet einen Fake-radio-browser, der auf `/json/stations/byname/…` [status]+[body] liefert. */
    private fun withApi(
        status: Int = 200,
        body: String = "[]",
        block: (adapter: RadioBrowserAdapter, seen: AtomicReference<RequestMeta?>) -> Unit,
    ) {
        val seen = AtomicReference<RequestMeta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/json/stations/byname/") { ex: HttpExchange ->
            seen.set(RequestMeta(ex.requestURI.path, ex.requestURI.query, ex.requestHeaders.getFirst("User-Agent")))
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(RadioBrowserAdapter(baseUrl = "http://127.0.0.1:${server.address.port}"), seen)
        } finally {
            server.stop(0)
        }
    }

    private fun station(name: String, urlResolved: String, votes: Long, lastcheckok: Int = 1, url: String = "") =
        """{"name":"$name","url":"$url","url_resolved":"$urlResolved","votes":$votes,"lastcheckok":$lastcheckok}"""

    // ── Andi-Schwelle: echte Namensähnlichkeit ───────────────────────────────

    @Test
    fun `WDR 2 matcht die aehnliche Station`() {
        val body = "[${station("WDR 2", "https://wdr2.example/stream", 5000)}]"
        withApi(body = body) { adapter, _ ->
            val hit = adapter.search("WDR 2")
            assertNotNull(hit)
            assertEquals("WDR 2", hit!!.name)
            assertEquals("https://wdr2.example/stream", hit.streamUrl)
        }
    }

    @Test
    fun `xyzzy Koeder liefert null trotz API-Treffern`() {
        // Die API liefert IRGENDWAS (byname ist serverseitig lax) — die
        // Andi-Schwelle muss den unähnlichen Treffer verwerfen: kein stilles
        // Falsch-Matching, kein „bester Treffer trotzdem".
        val body = "[${station("Radio Paradise", "https://rp.example/stream", 99999)}]"
        withApi(body = body) { adapter, _ ->
            assertNull(adapter.search("xyzzy"))
        }
    }

    @Test
    fun `enthaelt-Match greift in beide Richtungen`() {
        val body = "[${station("WDR 2 Rhein und Ruhr", "https://wdr2rr.example/stream", 800)}]"
        withApi(body = body) { adapter, _ ->
            // Query ⊂ Stationsname („wdr 2" in „WDR 2 Rhein und Ruhr").
            assertEquals("WDR 2 Rhein und Ruhr", adapter.search("wdr 2")?.name)
        }
    }

    @Test
    fun `Levenshtein-nah matcht Hoerfehler aber keine Koeder`() {
        withApi { adapter, _ ->
            // reine Schwellen-Logik (netzfrei): Tippfehler/Hörer nah dran → match…
            assertTrue(adapter.isSimilar("antenne bayer", "Antenne Bayern"))
            // …Umlaut-Faltung zählt als gleich…
            assertTrue(adapter.isSimilar("radio köln", "Radio Koeln"))
            // …aber Unähnliches bleibt draußen.
            assertFalse(adapter.isSimilar("xyzzy", "Radio Paradise"))
            assertFalse(adapter.isSimilar("wdr 2", "Bayern 3"))
        }
    }

    // ── Auswahl: votes entscheiden unter den Ähnlichen ───────────────────────

    @Test
    fun `unter aehnlichen Kandidaten gewinnen die meisten votes`() {
        val body = "[" +
            station("WDR 2 Rhein und Ruhr", "https://wdr2rr.example/stream", 300) + "," +
            station("WDR 2", "https://wdr2.example/stream", 5000) + "," +
            station("Bayern 3", "https://b3.example/stream", 99999) + // unähnlich: zählt nicht
            "]"
        withApi(body = body) { adapter, _ ->
            assertEquals("WDR 2", adapter.search("wdr 2")?.name)
        }
    }

    // ── Robustheit: URL-Fallback, tote Streams, Fehler ───────────────────────

    @Test
    fun `url ist der Fallback wenn url_resolved leer ist`() {
        val body = "[${station("WDR 2", "", 100, url = "https://fallback.example/stream")}]"
        withApi(body = body) { adapter, _ ->
            assertEquals("https://fallback.example/stream", adapter.search("wdr 2")?.streamUrl)
        }
    }

    @Test
    fun `Kandidaten ohne Stream-URL oder mit lastcheckok 0 werden uebersprungen`() {
        val body = "[" +
            station("WDR 2", "", 5000) + "," + // keine URL
            station("WDR 2", "https://tot.example/stream", 4000, lastcheckok = 0) + "," +
            station("WDR 2 Ruhrgebiet", "https://lebt.example/stream", 10) +
            "]"
        withApi(body = body) { adapter, _ ->
            assertEquals("https://lebt.example/stream", adapter.search("wdr 2")?.streamUrl)
        }
    }

    @Test
    fun `leeres Array HTTP-Fehler und kaputtes JSON enden ehrlich als null`() {
        withApi(body = "[]") { adapter, _ -> assertNull(adapter.search("wdr 2")) }
        withApi(status = 500, body = "kaputt") { adapter, _ -> assertNull(adapter.search("wdr 2")) }
        withApi(body = "{nicht-json") { adapter, _ -> assertNull(adapter.search("wdr 2")) }
    }

    // ── Wire-Format: Pfad, Encoding, User-Agent (Community-Etikette) ─────────

    @Test
    fun `Request traegt encodeten Namen votes-Sortierung und User-Agent`() {
        withApi(body = "[${station("WDR 2", "https://wdr2.example/stream", 1)}]") { adapter, seen ->
            adapter.search("WDR 2")
            val meta = seen.get()
            assertNotNull(meta, "die Suche muss die byname-API rufen")
            // Leerzeichen encoded auf der Leitung (URLEncoder ⇒ „+"; „%20" wäre ebenso ok).
            assertTrue(meta!!.path.endsWith("/WDR+2") || meta.path.endsWith("/WDR%202"), "Pfad war: ${meta.path}")
            assertTrue(meta.query!!.contains("order=votes"), "Query war: ${meta.query}")
            assertTrue(meta.query!!.contains("hidebroken=true"), "Query war: ${meta.query}")
            assertTrue(!meta.userAgent.isNullOrBlank() && meta.userAgent!!.contains("Hoshi"),
                "User-Agent (Community-Etikette) war: ${meta.userAgent}")
        }
    }
}
