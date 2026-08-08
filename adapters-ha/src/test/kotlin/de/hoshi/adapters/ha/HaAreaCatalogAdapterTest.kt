package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * Beweist den [HaAreaCatalogAdapter] OHNE echtes HA (Muster [HaSceneCatalogAdapterTest]):
 * ein winziger JDK-HttpServer spielt `/api/template`. KEIN echter HA-Call, READ-ONLY.
 *
 * Fälle: (a) parst `id::Name||…` + POST/Bearer/Body korrekt; (b) TTL-Cache (nur 1
 * POST innerhalb der TTL, ein zweiter NACH Ablauf); (c) blank/null Token ⇒ statischer
 * Fallback OHNE HTTP; (d) HTTP-500 ⇒ never-throw, fällt auf den statischen Fallback
 * zurück (kein vorheriger Erfolg); (e) HA down ⇒ never-throw, statischer Fallback;
 * (f) NIE ein leerer Katalog — weder bei Erst-Ausfall noch nach einem Erfolg gefolgt
 * von einem Ausfall (dann gewinnt der LETZTE erfolgreiche Cache-Stand).
 */
class HaAreaCatalogAdapterTest {

    /**
     * `area_id::Name`, `||`-getrennt — das reale Template-Antwortformat. Der dritte
     * Eintrag simuliert eine Area OHNE Namen: das Jinja-`default(a, true)` im echten
     * Template ersetzt ein `None` bereits SERVERSEITIG durch den Slug, sodass so ein
     * Fall über die Leitung als LEERER Name ankommen kann ("flur::") — der Parser
     * fängt genau das über `name.ifBlank { id }` ab (s. Test unten).
     */
    private val templateBody = "wohnzimmer::Wohnzimmer||kuche::Küche||flur::"

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
    fun `parst area_id Name Paare und faellt bei leerem Namen auf den Slug zurueck`() = withHa { url, _, last ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token")
        val areas = adapter.areas()

        assertEquals(3, areas.size)
        val byId = areas.associateBy { it.areaId }
        assertEquals("Wohnzimmer", byId.getValue("wohnzimmer").label)
        assertEquals("Küche", byId.getValue("kuche").label)
        // leerer Name ("flur::") -> Slug selbst als Label (nie ein leeres Label).
        assertEquals("flur", byId.getValue("flur").label)

        val meta = last.get()!!
        assertEquals("POST", meta.method)
        assertEquals("/api/template", meta.path)
        assertEquals("Bearer secret-token", meta.authorization)
        assertTrue(meta.body.contains("template"), "Body muss das Jinja-Template tragen: ${meta.body}")
    }

    @Test
    fun `Aliase enthalten area_id und kleingeschriebenes Label`() = withHa { url, _, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token")
        val kuche = adapter.areas().first { it.areaId == "kuche" }
        assertTrue(kuche.aliases.contains("kuche"))
        assertTrue(kuche.aliases.contains("küche"))
    }

    // ── (b) TTL-Cache: innerhalb TTL nur 1 Call, nach Ablauf ein zweiter ──────
    @Test
    fun `cacht innerhalb der TTL (nur ein HTTP-Call)`() = withHa { url, calls, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token", ttl = Duration.ofMinutes(15), clock = clockAt(1_000))
        adapter.areas()
        adapter.areas()
        adapter.areas()
        assertEquals(1, calls.get(), "innerhalb der TTL darf nur einmal geladen werden")
    }

    @Test
    fun `laedt nach TTL-Ablauf neu (zweiter HTTP-Call)`() = withHa { url, calls, _ ->
        val clock = MutableClock(startEpochSeconds = 1_000)
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token", ttl = Duration.ofMinutes(15), clock = clock)
        adapter.areas()
        assertEquals(1, calls.get())

        clock.now = clock.now.plus(Duration.ofMinutes(16))
        adapter.areas()
        assertEquals(2, calls.get(), "nach TTL-Ablauf muss neu geladen werden")
    }

    // ── (c) blank/null Token ⇒ statischer Fallback, OHNE HTTP-Call ───────────
    @Test
    fun `blank Token liefert den statischen Fallback ohne HTTP-Call`() = withHa { url, calls, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "   ")
        val areas = adapter.areas()
        assertEquals(0, calls.get(), "ohne Token darf kein POST rausgehen")
        assertEquals(AreaCatalogPort.STATIC.areas(), areas)
    }

    @Test
    fun `null Token liefert den statischen Fallback ohne HTTP-Call`() = withHa { url, calls, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = null)
        val areas = adapter.areas()
        assertEquals(0, calls.get())
        assertEquals(AreaCatalogPort.STATIC.areas(), areas)
    }

    // ── (d) HTTP-Fehler ⇒ never-throw, statischer Fallback (kein Vorerfolg) ───
    @Test
    fun `HTTP-500 liefert den statischen Fallback statt zu werfen`() = withHa(status = 500, body = "boom") { url, _, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token")
        val areas = adapter.areas()
        assertFalse(areas.isEmpty(), "NIE ein leerer Katalog")
        assertEquals(AreaCatalogPort.STATIC.areas(), areas)
    }

    @Test
    fun `Kaputter Body liefert den statischen Fallback statt zu werfen`() = withHa(status = 200, body = "not-a-valid-pipe-format-but-still-parses-to-nothing") { url, _, _ ->
        // Kein "::" im Body -> parseAreas() liefert eine leere Liste -> loadOnce() null (ifEmpty) -> Fallback.
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token")
        val areas = adapter.areas()
        assertFalse(areas.isEmpty())
        assertEquals(AreaCatalogPort.STATIC.areas(), areas)
    }

    // ── (e) HA down (connection refused) ⇒ never-throw, statischer Fallback ──
    @Test
    fun `HA down liefert den statischen Fallback statt zu werfen`() {
        val adapter = HaAreaCatalogAdapter(baseUrl = "http://127.0.0.1:1", token = "secret-token", timeoutMs = 1500)
        val areas = adapter.areas()
        assertFalse(areas.isEmpty(), "NIE ein leerer Katalog")
        assertEquals(AreaCatalogPort.STATIC.areas(), areas)
    }

    // ── (f) Nach einem Erfolg gewinnt bei einem SPÄTEREN Ausfall der letzte
    //        Cache-Stand — NICHT der statische Fallback (frischere echte Daten
    //        schlagen den generischen Default, solange sie mal geladen wurden). ──
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
            val adapter = HaAreaCatalogAdapter(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                token = "secret-token",
                ttl = Duration.ofMinutes(15),
                clock = clock,
            )
            val first = adapter.areas()
            assertEquals(3, first.size)

            up.set(false)
            clock.now = clock.now.plus(Duration.ofMinutes(16))
            val second = adapter.areas()

            assertEquals(2, calls.get(), "TTL-Ablauf muss einen zweiten Versuch ausloesen")
            assertEquals(first, second, "bei Ausfall NACH einem Erfolg gewinnt der letzte gute Cache-Stand")
        } finally {
            server.stop(0)
        }
    }

    // ── Custom staticFallback wird tatsaechlich verwendet (kein hartkodierter STATIC-Zugriff) ──
    @Test
    fun `Custom staticFallback greift wenn NIE ein erfolgreicher Load da war`() {
        val custom = AreaCatalogPort { listOf(AreaInfo(areaId = "garten", label = "Garten", aliases = setOf("garten"))) }
        val adapter = HaAreaCatalogAdapter(
            baseUrl = "http://127.0.0.1:1",
            token = "secret-token",
            timeoutMs = 1000,
            staticFallback = custom,
        )
        val areas = adapter.areas()
        assertEquals(listOf(AreaInfo(areaId = "garten", label = "Garten", aliases = setOf("garten"))), areas)
    }

    // ── Regressions-Fix (2026-07-25): die statische ToolAreas-Bruecke ────────────
    // Der Live-Endpunkt liefert NUR {id, name.lowercase()} je Area (kein HA-natives
    // aliases-Feld, s. Klassen-KDoc) — ohne die Bruecke wuerden die kuratierten
    // EN-Aliase ("living room", "kitchen", …) beim dynamischen Katalog verloren
    // gehen. `mergeStaticAliases` schliesst genau diese Luecke.

    @Test
    fun `mergt die kuratierten EN-Aliase in bekannte area_ids obwohl der Live-Payload sie nicht traegt`() = withHa { url, _, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token")
        val byId = adapter.areas().associateBy { it.areaId }

        // Roh-Payload traegt nur "wohnzimmer"/"wohnzimmer" (id + Name-lowercase) —
        // "living room" kommt NUR aus der statischen Bruecke.
        assertTrue(byId.getValue("wohnzimmer").aliases.contains("living room"), "EN-Mehrwort-Alias muss aus ToolAreas gemerged werden")
        assertTrue(byId.getValue("kuche").aliases.contains("kitchen"), "EN-Alias fuer Kueche muss gemerged werden")
        // Die urspruenglichen dynamischen Aliase bleiben erhalten (Vereinigung, kein Ersatz).
        assertTrue(byId.getValue("wohnzimmer").aliases.contains("wohnzimmer"))
    }

    @Test
    fun `deutsche Labels bleiben nach dem Alias-Merge byte-identisch`() = withHa { url, _, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token")
        val byId = adapter.areas().associateBy { it.areaId }
        assertEquals("Wohnzimmer", byId.getValue("wohnzimmer").label, "Label darf der Merge nicht anfassen")
        assertEquals("Küche", byId.getValue("kuche").label, "Label darf der Merge nicht anfassen")
    }

    @Test
    fun `unbekannte area_id ausserhalb des statischen Mappings bleibt unangetastet`() {
        val adapter = HaAreaCatalogAdapter(baseUrl = "http://127.0.0.1:1", token = "secret-token")
        val dynamic = listOf(AreaInfo(areaId = "gastzimmer", label = "Gästezimmer", aliases = setOf("gastzimmer", "gästezimmer")))
        val merged = adapter.mergeStaticAliases(dynamic)
        assertEquals(dynamic, merged, "kein statisches Mapping fuer 'gastzimmer' -> unveraendert durchreichen")
    }

    @Test
    fun `Kollisionsregel - ein von einer ANDEREN dynamischen Area beanspruchtes Wort wird NICHT durch die Bruecke ueberschrieben`() {
        val adapter = HaAreaCatalogAdapter(baseUrl = "http://127.0.0.1:1", token = "secret-token")
        // Simuliert einen kuenftigen HA-nativen Alias: "office" wird (fiktiv) NICHT
        // "arbeitszimmer" zugeordnet, sondern einer separaten Area "gastzimmer" —
        // ein echter Widerspruch zur statischen ToolAreas-Bruecke (die "office" ->
        // "arbeitszimmer" kennt). Dynamisch/nativ MUSS gewinnen.
        val dynamic = listOf(
            AreaInfo(areaId = "arbeitszimmer", label = "Arbeitszimmer", aliases = setOf("arbeitszimmer")),
            AreaInfo(areaId = "gastzimmer", label = "Gästezimmer", aliases = setOf("gastzimmer", "office")),
        )
        val merged = adapter.mergeStaticAliases(dynamic).associateBy { it.areaId }

        // "office" bleibt bei gastzimmer (dynamisch/nativ gewinnt) …
        assertTrue(merged.getValue("gastzimmer").aliases.contains("office"))
        // … und wird NICHT zusaetzlich in arbeitszimmer gemischt (keine Doppel-Vergabe).
        assertFalse(merged.getValue("arbeitszimmer").aliases.contains("office"), "die Bruecke darf ein anderswo beanspruchtes Wort nicht ueberschreiben")
        // Luecken OHNE Widerspruch werden trotzdem gefuellt ("büro"/"buero" sind frei).
        assertTrue(merged.getValue("arbeitszimmer").aliases.contains("büro"))
        assertTrue(merged.getValue("arbeitszimmer").aliases.contains("buero"))
    }

    @Test
    fun `mehrere Areas gleichzeitig - jede bekommt nur ihre eigenen Luecken gefuellt`() = withHa { url, _, _ ->
        val adapter = HaAreaCatalogAdapter(baseUrl = url, token = "secret-token")
        val byId = adapter.areas().associateBy { it.areaId }
        // wohnzimmer bekommt KEINE Kueche-Worte und umgekehrt.
        assertFalse(byId.getValue("wohnzimmer").aliases.contains("kitchen"))
        assertFalse(byId.getValue("kuche").aliases.contains("living room"))
    }
}
