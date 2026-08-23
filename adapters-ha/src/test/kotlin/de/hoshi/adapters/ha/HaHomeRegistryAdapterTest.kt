package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
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
 *
 * (g)–(l) Live-Zustands-Naht (Draht-Vertrag Andi 2026-08-11, „Zuhause-Kacheln"):
 * ein zweiter JDK-HttpServer-Context spielt `/api/states`. Fälle: (g) State
 * vorhanden wird gemerged UND Attribute werden auf die Allowlist gefiltert
 * (fremde Keys wie `brightness`/`friendly_name`/`supported_features`
 * sickern NICHT durch); (h) State fehlt (Entity nicht im States-Call) ⇒
 * `state=null`, `attrs={}`; (i) `"unavailable"` wird ROH durchgereicht (kein
 * Sonderfall, kein `null`); (j) States-Call HTTP-500 ⇒ Snapshot lebt
 * trotzdem, `state=null` überall; (k) States-Call liefert kaputtes JSON ⇒
 * dasselbe never-throw-Verhalten; (l) genau 1 POST + 1 GET pro Load.
 *
 * (m) `unit_of_measurement` (Andi 2026-08-13, „Sauger-Metrik-Familie" —
 * additive Erweiterung der [HaHomeRegistryAdapter.ATTR_ALLOWLIST] auf fünf
 * Keys): wird stringifiziert durchgereicht wie die anderen vier, fehlt der
 * Key bei HA ⇒ bleibt einfach draussen (kein erfundener Wert).
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

    // ═══ Live-Zustands-Naht: State-Merge aus GET /api/states ══════════════════

    /** Spielt BEIDE Endpunkte auf EINEM Server: `/api/template` (Snapshot) + `/api/states` (Zustaende). */
    private fun withHaAndStates(
        templateStatus: Int = 200,
        templateBody: String = this.templateBody,
        statesStatus: Int = 200,
        statesBody: String = "[]",
        block: (url: String, templateCalls: AtomicInteger, statesCalls: AtomicInteger) -> Unit,
    ) {
        val templateCalls = AtomicInteger(0)
        val statesCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            templateCalls.incrementAndGet()
            respond(ex, templateStatus, templateBody)
        }
        server.createContext("/api/states") { ex ->
            statesCalls.incrementAndGet()
            respond(ex, statesStatus, statesBody)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", templateCalls, statesCalls)
        } finally {
            server.stop(0)
        }
    }

    // ── (g) State vorhanden + (h) State fehlt + Attribute-Allowlist ──────────
    @Test
    fun `mischt vorhandene States und filtert Attribute auf die Allowlist, fehlende Entity bleibt null`() {
        // switch.kuche_kaffee ist BEWUSST NICHT im States-Array (die "State fehlt"-Probe).
        val statesJson = """
            [
              {"entity_id":"light.wohnzimmer_deckenlampe","state":"on","attributes":{"friendly_name":"Deckenlampe","brightness":180}},
              {"entity_id":"climate.tado_wohnzimmer","state":"heat","attributes":{"current_temperature":21.5,"temperature":22.5,"hvac_action":"heating","battery_level":87,"friendly_name":"Tado Wohnzimmer","supported_features":17}}
            ]
        """.trimIndent()
        withHaAndStates(statesBody = statesJson) { url, templateCalls, statesCalls ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!

            val lamp = snapshot.areas.first { it.areaId == "wohnzimmer" }.entities[0]
            assertEquals("on", lamp.state)
            assertTrue(lamp.attrs.isEmpty(), "brightness/friendly_name sind NICHT auf der Allowlist")

            val tado = snapshot.unassigned[0]
            assertEquals("heat", tado.state)
            assertEquals("21.5", tado.attrs["current_temperature"])
            assertEquals("22.5", tado.attrs["temperature"])
            assertEquals("heating", tado.attrs["hvac_action"])
            assertEquals("87", tado.attrs["battery_level"])
            assertEquals(4, tado.attrs.size, "friendly_name/supported_features duerfen NICHT durchsickern")

            val kaffee = snapshot.areas.first { it.areaId == "kuche" }.entities[0]
            assertNull(kaffee.state, "Entity fehlt im States-Call ⇒ ehrlich null statt erfunden")
            assertTrue(kaffee.attrs.isEmpty())

            assertEquals(1, templateCalls.get())
            assertEquals(1, statesCalls.get())
        }
    }

    // ── (i) "unavailable" wird ROH durchgereicht, kein Sonderfall/null ───────
    @Test
    fun `unavailable wird als echter Zustand durchgereicht, nicht als null`() {
        val statesJson = """[{"entity_id":"switch.kuche_kaffee","state":"unavailable","attributes":{}}]"""
        withHaAndStates(statesBody = statesJson) { url, _, _ ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!
            val kaffee = snapshot.areas.first { it.areaId == "kuche" }.entities[0]
            assertEquals("unavailable", kaffee.state)
            assertTrue(kaffee.attrs.isEmpty())
        }
    }

    // ── (j) States-Call HTTP-500 ⇒ Snapshot lebt trotzdem, state=null ueberall ─
    @Test
    fun `States-Call HTTP-500 - Snapshot lebt trotzdem, state=null ueberall`() =
        withHaAndStates(statesStatus = 500, statesBody = "boom") { url, templateCalls, statesCalls ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!

            assertEquals(3, snapshot.areas.size, "Template-Snapshot bleibt vollstaendig, obwohl States scheiterte")
            (snapshot.areas.flatMap { it.entities } + snapshot.unassigned).forEach { e ->
                assertNull(e.state, "${e.entityId}: State-Call gescheitert ⇒ ehrlich null")
                assertTrue(e.attrs.isEmpty())
            }
            assertEquals(1, templateCalls.get())
            assertEquals(1, statesCalls.get())
        }

    // ── (k) States-Call liefert kaputtes JSON ⇒ dasselbe never-throw-Verhalten ─
    @Test
    fun `States-Call liefert kaputtes JSON - Snapshot lebt trotzdem mit state=null`() =
        withHaAndStates(statesBody = "das ist kein JSON") { url, _, _ ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!
            assertTrue((snapshot.areas.flatMap { it.entities } + snapshot.unassigned).all { it.state == null && it.attrs.isEmpty() })
        }

    // ── (l) genau 1 POST /api/template + 1 GET /api/states pro Load, cached innerhalb der TTL ─
    @Test
    fun `laedt pro Refresh genau 1x Template und 1x States, danach gecacht`() =
        withHaAndStates { url, templateCalls, statesCalls ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token", ttl = Duration.ofMinutes(15), clock = clockAt(1_000))
            adapter.registry()
            adapter.registry()
            adapter.registry()
            assertEquals(1, templateCalls.get(), "innerhalb der TTL darf nur einmal geladen werden")
            assertEquals(1, statesCalls.get(), "der States-Call haengt am selben TTL-Refresh wie der Template-Call")
        }

    // ── (m) unit_of_measurement: additive Erweiterung der Allowlist (Andi 2026-08-13,
    //        „Sauger-Metrik-Familie") — stringifiziert durchgereicht wie die anderen vier,
    //        fremde Keys bleiben weiterhin draussen. ─────────────────────────────────
    @Test
    fun `unit_of_measurement wird stringifiziert auf die erweiterte Allowlist durchgereicht`() {
        val statesJson = """
            [
              {"entity_id":"climate.tado_wohnzimmer","state":"heat","attributes":{"current_temperature":21.5,"unit_of_measurement":"°C","friendly_name":"Tado Wohnzimmer","supported_features":17}}
            ]
        """.trimIndent()
        withHaAndStates(statesBody = statesJson) { url, _, _ ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!
            val tado = snapshot.unassigned[0]
            assertEquals("°C", tado.attrs["unit_of_measurement"])
            assertEquals("21.5", tado.attrs["current_temperature"])
            assertEquals(2, tado.attrs.size, "nur die beiden erlaubten Keys, friendly_name/supported_features bleiben draussen")
        }
    }

    @Test
    fun `unit_of_measurement fehlt beim Sensor - attrs bleiben ohne den Key, kein erfundener Wert`() {
        val statesJson = """[{"entity_id":"switch.kuche_kaffee","state":"on","attributes":{"friendly_name":"Kaffeemaschine"}}]"""
        withHaAndStates(statesBody = statesJson) { url, _, _ ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!
            val kaffee = snapshot.areas.first { it.areaId == "kuche" }.entities[0]
            assertTrue(kaffee.attrs.isEmpty(), "kein unit_of_measurement im HA-Body ⇒ der Key darf nicht auftauchen")
        }
    }

    // ═══ States-Frische GETRENNT vom Template-TTL (Andi-Auftrag 2026-08-13,
    //      „Sauger-Sichtbarkeits-Lücke") ═══════════════════════════════════════

    /**
     * Der Kern der Trennung: innerhalb der 15-min-Template-TTL, aber NACH
     * Ablauf der (kuerzeren) States-TTL, refresht [HaHomeRegistryAdapter.registry]
     * NUR `GET /api/states` — KEIN zweiter `POST /api/template`. Erst wenn
     * auch die Template-TTL selbst abläuft, folgt wieder ein voller Reload
     * (der beide Uhren gleichzeitig zuruecksetzt).
     */
    @Test
    fun `States-TTL refresht unabhaengig und OHNE Template-Reload, Template-TTL reloaded beides`() =
        withHaAndStates { url, templateCalls, statesCalls ->
            val clock = MutableClock(startEpochSeconds = 1_000)
            val adapter = HaHomeRegistryAdapter(
                baseUrl = url,
                token = "secret-token",
                ttl = Duration.ofMinutes(15),
                statesTtl = Duration.ofSeconds(60),
                clock = clock,
            )

            adapter.registry()
            assertEquals(1, templateCalls.get(), "erster Aufruf: 1 Template-Load")
            assertEquals(1, statesCalls.get(), "erster Aufruf: 1 States-Load")

            // +30s: WEDER Template- noch States-TTL abgelaufen ⇒ komplett aus dem Cache.
            clock.now = clock.now.plus(Duration.ofSeconds(30))
            adapter.registry()
            assertEquals(1, templateCalls.get(), "innerhalb beider TTLs: kein neuer Call")
            assertEquals(1, statesCalls.get(), "innerhalb beider TTLs: kein neuer Call")

            // +61s (insgesamt): States-TTL (60s) abgelaufen, Template-TTL (15min) NICHT ⇒
            // NUR ein zweiter States-Call, der Template-Snapshot bleibt derselbe Load.
            clock.now = clock.now.plus(Duration.ofSeconds(31))
            adapter.registry()
            assertEquals(1, templateCalls.get(), "States-TTL-Ablauf darf KEINEN Template-Reload ausloesen")
            assertEquals(2, statesCalls.get(), "States-TTL-Ablauf muss NUR den States-Call wiederholen")

            // +16min (insgesamt): Template-TTL abgelaufen ⇒ voller Reload, der beide Uhren
            // gleichzeitig zuruecksetzt (ein weiterer States-Call haengt am Voll-Load).
            clock.now = clock.now.plus(Duration.ofMinutes(16))
            adapter.registry()
            assertEquals(2, templateCalls.get(), "Template-TTL-Ablauf muss den Template-Reload ausloesen")
            assertEquals(3, statesCalls.get(), "der Voll-Reload haengt den States-Call gleich mit an")
        }

    /** Ohne eigenen `statesTtl`-Wert (Default 60s) verhaelt sich [HaHomeRegistryAdapter] byte-gleich zum alten Muster: EIN Call je Endpunkt innerhalb kurzer Zeit. */
    @Test
    fun `Default-statesTtl von 60s haelt einen sofortigen Zweit-Aufruf noch im Cache`() =
        withHaAndStates { url, templateCalls, statesCalls ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token", clock = clockAt(1_000))
            adapter.registry()
            adapter.registry()
            assertEquals(1, templateCalls.get())
            assertEquals(1, statesCalls.get())
        }

    // ═══ statesFetchedAt (additiv, ISO oder null = nie erfolgreich) ═══════════

    @Test
    fun `statesFetchedAt traegt den ISO-Zeitpunkt des ersten erfolgreichen States-Merges`() =
        withHaAndStates { url, _, _ ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token", clock = clockAt(1_000))
            val snapshot = adapter.registry()!!
            assertEquals(Instant.ofEpochSecond(1_000).toString(), snapshot.statesFetchedAt)
        }

    @Test
    fun `statesFetchedAt bleibt null wenn der States-Call nie gelingt, obwohl das Template laedt`() =
        withHa { url, _, _ ->
            // withHa registriert NUR /api/template — GET /api/states trifft keinen Context (404),
            // loadStates() liefert also never-throw null statt einer echten leeren Antwort.
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!
            assertNull(snapshot.statesFetchedAt)
        }

    // ═══ Last-known-good-Fallback (additiv, Andi-Auftrag 2026-08-13) ═══════════

    /** Ein zweiter JDK-HttpServer-Aufbau, dessen `/api/states`-Antwort zwischen Aufrufen AUSTAUSCHBAR ist (fuer den Wechsel usable → unavailable). */
    private fun withHaMutableStates(block: (url: String, statesBody: AtomicReference<String>) -> Unit) {
        val statesBody = AtomicReference("[]")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex -> respond(ex, 200, templateBody) }
        server.createContext("/api/states") { ex -> respond(ex, 200, statesBody.get()) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", statesBody)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `last-known-good ueberlebt eine spaetere unavailable-Runde, der LIVE-Zustand bleibt ehrlich roh`() =
        withHaMutableStates { url, statesBody ->
            val clock = MutableClock(startEpochSeconds = 1_000)
            statesBody.set(
                """[{"entity_id":"switch.kuche_kaffee","state":"on","attributes":{"battery_level":"87"}}]""",
            )
            val adapter = HaHomeRegistryAdapter(
                baseUrl = url,
                token = "secret-token",
                ttl = Duration.ofMinutes(15),
                statesTtl = Duration.ofSeconds(60),
                clock = clock,
            )

            val first = adapter.registry()!!
            val kaffeeFirst = first.areas.first { it.areaId == "kuche" }.entities[0]
            assertEquals("on", kaffeeFirst.state)
            assertNull(kaffeeFirst.lastKnown, "beim ERSTEN brauchbaren Zustand gibt es noch keinen aelteren Stand zum Anhaengen")

            // Naechste States-TTL-Runde: die Kaffeemaschine faellt aus (WLAN-Tiefschlaf-Analogie).
            statesBody.set("""[{"entity_id":"switch.kuche_kaffee","state":"unavailable","attributes":{}}]""")
            clock.now = clock.now.plus(Duration.ofSeconds(61))
            val second = adapter.registry()!!
            val kaffeeSecond = second.areas.first { it.areaId == "kuche" }.entities[0]
            assertEquals("unavailable", kaffeeSecond.state, "der LIVE-Zustand bleibt roh/ehrlich, wird NICHT verfaelscht")
            assertEquals("on", kaffeeSecond.lastKnown?.state, "der zuletzt brauchbare Zustand bleibt gemerkt")
            assertEquals("87", kaffeeSecond.lastKnown?.attrs?.get("battery_level"), "die gemerkten Attrs bleiben auf der Allowlist")
            assertEquals(Instant.ofEpochSecond(1_000).toString(), kaffeeSecond.lastKnown?.seenAt, "seenAt ist der Zeitpunkt der LETZTEN brauchbaren Ablesung, nicht der aktuellen Runde")

            // Eine DRITTE Runde, weiterhin unavailable: das last-known bleibt UNVERAENDERT (kein neuer usable Wert).
            clock.now = clock.now.plus(Duration.ofSeconds(61))
            val third = adapter.registry()!!
            val kaffeeThird = third.areas.first { it.areaId == "kuche" }.entities[0]
            assertEquals("unavailable", kaffeeThird.state)
            assertEquals("on", kaffeeThird.lastKnown?.state)
            assertEquals(Instant.ofEpochSecond(1_000).toString(), kaffeeThird.lastKnown?.seenAt, "seenAt wandert NICHT einfach mit jeder Runde weiter")
        }

    @Test
    fun `lastKnown bleibt weg wenn nie ein brauchbarer Zustand gesehen wurde`() =
        withHaMutableStates { url, statesBody ->
            statesBody.set("""[{"entity_id":"switch.kuche_kaffee","state":"unavailable","attributes":{}}]""")
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val snapshot = adapter.registry()!!
            val kaffee = snapshot.areas.first { it.areaId == "kuche" }.entities[0]
            assertEquals("unavailable", kaffee.state)
            assertNull(kaffee.lastKnown, "kein Vorerfolg fuer diese Entity ⇒ ehrlich kein last-known statt erfunden")
        }

    // ═══ Persistenz-Integration: ein echter HaLastKnownStateStore ueberlebt einen Adapter-Neustart ═══

    @Test
    fun `mit echtem HaLastKnownStateStore ueberlebt der last-known-Stand einen Adapter-Neustart`(@TempDir tmp: Path) =
        withHaMutableStates { url, statesBody ->
            val storePath = tmp.resolve("last-known.json")
            statesBody.set("""[{"entity_id":"switch.kuche_kaffee","state":"on","attributes":{}}]""")
            val adapter1 = HaHomeRegistryAdapter(
                baseUrl = url,
                token = "secret-token",
                lastKnownStore = HaLastKnownStateStore(storePath),
            )
            adapter1.registry()
            assertTrue(Files.exists(storePath), "ein brauchbarer Zustand muss die Datei erzeugen")

            // "Neustart": ein FRISCHER Store liest dieselbe Datei neu ein, unabhaengig vom Adapter.
            val restarted = HaLastKnownStateStore(storePath)
            assertEquals("on", restarted.get("switch.kuche_kaffee")?.state, "der last-known-Stand ueberlebt den Prozess-Neustart")
        }
}
