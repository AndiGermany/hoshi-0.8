package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [HaHomeRegistryAdapter] OHNE echtes HA (Muster
 * [HaAreaCatalogAdapterTest]): ein winziger JDK-HttpServer spielt
 * `/api/template`. KEIN echter HA-Call, READ-ONLY.
 *
 * Fälle: (a) parst Areas+Entities+Labels korrekt, inkl. leerer Area und
 * Entities OHNE Area (⇒ `unassigned`, die „tado-Lücke"); (b) TTL-Cache (nur 1
 * POST innerhalb der TTL, ein zweiter nach Ablauf); (c) blank/null Token ⇒
 * `null` OHNE HTTP-Call; (d) HTTP-500/kaputter Body ⇒ never-throw, `null`
 * (kein Vorerfolg); (e) HA down ⇒ never-throw, `null`; (f) nach einem Erfolg
 * gewinnt bei einem SPÄTEREN Ausfall der letzte gute Cache-Stand (NICHT `null`).
 */
class HaHomeRegistryAdapterTest {

    /**
     * Areas: wohnzimmer/kuche/schlafzimmer (schlafzimmer OHNE ein einziges Gerät —
     * die leere Area). Entities: eine Wohnzimmer-Lampe MIT Label, eine Küchen-
     * Steckdose OHNE Label, ein Thermostat OHNE jede Area-Zuordnung (die
     * "tado-Lücke", landet in `unassigned`).
     */
    private val templateBody =
        "wohnzimmer::Wohnzimmer||kuche::Küche||schlafzimmer::Schlafzimmer" +
            "@@ENTITIES@@" +
            "light.wohnzimmer_deckenlampe::wohnzimmer::Deckenlampe::hoshi:leselampen||" +
            "switch.kuche_kaffee::kuche::Kaffeemaschine::||" +
            "climate.tado_wohnzimmer::::Tado Wohnzimmer::"

    data class Meta(val method: String, val path: String, val authorization: String, val body: String)

    private fun withHa(
        status: Int = 200,
        body: String = templateBody,
        block: (url: String, calls: AtomicInteger, last: AtomicReference<Meta?>) -> Unit,
    ) {
        val calls = AtomicInteger(0)
        val last = AtomicReference<Meta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            calls.incrementAndGet()
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            last.set(
                Meta(
                    method = ex.requestMethod,
                    path = ex.requestURI.path,
                    authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
                    body = reqBody,
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
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun clockAt(epochSeconds: Long): Clock =
        Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)

    /** Ein Clock-Fake, dessen `instant()` frei vorspulbar ist (für TTL-Ablauf ohne echtes Warten). */
    private class MutableClock(startEpochSeconds: Long) : Clock() {
        @Volatile var now: Instant = Instant.ofEpochSecond(startEpochSeconds)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    // ── (a) parst + POST/Bearer/Body korrekt ─────────────────────────────────
    @Test
    fun `parst Areas inkl leerer Area und Entities inkl unassigned und Labels`() = withHa { url, _, last ->
        val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token", clock = clockAt(1_000))
        val snapshot = adapter.registry()!!

        assertEquals(3, snapshot.areas.size)
        val byId = snapshot.areas.associateBy { it.areaId }
        assertEquals("Wohnzimmer", byId.getValue("wohnzimmer").label)
        assertEquals(1, byId.getValue("wohnzimmer").entities.size)
        assertEquals("light", byId.getValue("wohnzimmer").entities[0].domain)
        assertEquals("Deckenlampe", byId.getValue("wohnzimmer").entities[0].name)
        assertEquals(listOf("hoshi:leselampen"), byId.getValue("wohnzimmer").entities[0].labels)

        // Küche: Steckdose OHNE Label ⇒ leere Liste, kein `null`, kein `[""]`.
        assertEquals(1, byId.getValue("kuche").entities.size)
        assertTrue(byId.getValue("kuche").entities[0].labels.isEmpty())

        // Schlafzimmer: bekannt, aber OHNE ein einziges Gerät — ehrlich leer, kein Fehler.
        assertTrue(byId.getValue("schlafzimmer").entities.isEmpty())

        // Thermostat ohne Area-Zuordnung landet in unassigned (die "tado-Lücke").
        assertEquals(1, snapshot.unassigned.size)
        assertEquals("climate.tado_wohnzimmer", snapshot.unassigned[0].entityId)
        assertEquals("climate", snapshot.unassigned[0].domain)
        assertEquals("Tado Wohnzimmer", snapshot.unassigned[0].name)

        val meta = last.get()!!
        assertEquals("POST", meta.method)
        assertEquals("/api/template", meta.path)
        assertEquals("Bearer secret-token", meta.authorization)
        assertTrue(meta.body.contains("template"), "Body muss das Jinja-Template tragen: ${meta.body}")
    }

    // ── (b) TTL-Cache: innerhalb TTL nur 1 Call, nach Ablauf ein zweiter ──────
    @Test
    fun `cacht innerhalb der TTL (nur ein HTTP-Call)`() = withHa { url, calls, _ ->
        val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token", ttl = Duration.ofMinutes(15), clock = clockAt(1_000))
        adapter.registry()
        adapter.registry()
        adapter.registry()
        assertEquals(1, calls.get(), "innerhalb der TTL darf nur einmal geladen werden")
    }

    @Test
    fun `laedt nach TTL-Ablauf neu (zweiter HTTP-Call)`() = withHa { url, calls, _ ->
        val clock = MutableClock(startEpochSeconds = 1_000)
        val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token", ttl = Duration.ofMinutes(15), clock = clock)
        adapter.registry()
        assertEquals(1, calls.get())

        clock.now = clock.now.plus(Duration.ofMinutes(16))
        adapter.registry()
        assertEquals(2, calls.get(), "nach TTL-Ablauf muss neu geladen werden")
    }

    // ── (c) blank/null Token ⇒ null, OHNE HTTP-Call ───────────────────────────
    @Test
    fun `blank Token liefert null ohne HTTP-Call`() = withHa { url, calls, _ ->
        val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "   ")
        assertNull(adapter.registry())
        assertEquals(0, calls.get(), "ohne Token darf kein POST rausgehen")
    }

    @Test
    fun `null Token liefert null ohne HTTP-Call`() = withHa { url, calls, _ ->
        val adapter = HaHomeRegistryAdapter(baseUrl = url, token = null)
        assertNull(adapter.registry())
        assertEquals(0, calls.get())
    }

    // ── (d) HTTP-Fehler / kaputter Body ⇒ never-throw, null (kein Vorerfolg) ──
    @Test
    fun `HTTP-500 liefert null statt zu werfen`() = withHa(status = 500, body = "boom") { url, _, _ ->
        val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
        assertNull(adapter.registry())
    }

    @Test
    fun `Body ohne den ENTITIES-Marker gilt als Garbage und liefert null`() =
        withHa(status = 200, body = "das ist keine Template-Antwort") { url, _, _ ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            assertNull(adapter.registry())
        }

    @Test
    fun `Body mit dem Marker aber leeren Seiten ist ein gueltiges leeres Zuhause`() =
        withHa(status = 200, body = "@@ENTITIES@@") { url, _, _ ->
            // Marker vorhanden (echte Template-Antwort), beide Seiten leer ⇒ ein
            // frisches/leeres HA ist ein legitimer, kein fehlerhafter Zustand.
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!
            assertTrue(snapshot.areas.isEmpty())
            assertTrue(snapshot.unassigned.isEmpty())
        }

    // ── (e) HA down (connection refused) ⇒ never-throw, null ─────────────────
    @Test
    fun `HA down liefert null statt zu werfen`() {
        val adapter = HaHomeRegistryAdapter(baseUrl = "http://127.0.0.1:1", token = "secret-token", timeoutMs = 1500)
        assertNull(adapter.registry())
    }

    // ── (f) Nach einem Erfolg gewinnt bei einem SPÄTEREN Ausfall der letzte
    //        Cache-Stand — NICHT null (frischere echte Daten schlagen einen
    //        späteren Ausfall, solange sie mal geladen wurden). ─────────────
    @Test
    fun `nach TTL-Ablauf und HA-Ausfall bleibt der letzte erfolgreiche Cache-Stand aktiv`() {
        val clock = MutableClock(startEpochSeconds = 1_000)
        val calls = AtomicInteger(0)
        val up = AtomicReference(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            calls.incrementAndGet()
            if (up.get()) respond(ex, 200, templateBody) else respond(ex, 500, "boom")
        }
        server.start()
        try {
            val adapter = HaHomeRegistryAdapter(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                token = "secret-token",
                ttl = Duration.ofMinutes(15),
                clock = clock,
            )
            val first = adapter.registry()
            assertEquals(3, first!!.areas.size)

            up.set(false)
            clock.now = clock.now.plus(Duration.ofMinutes(16))
            val second = adapter.registry()

            assertEquals(2, calls.get(), "TTL-Ablauf muss einen zweiten Versuch ausloesen")
            assertEquals(first, second, "bei Ausfall NACH einem Erfolg gewinnt der letzte gute Cache-Stand")
        } finally {
            server.stop(0)
        }
    }
}
