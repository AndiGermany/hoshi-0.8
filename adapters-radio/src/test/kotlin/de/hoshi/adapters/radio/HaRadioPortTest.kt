package de.hoshi.adapters.radio

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.RadioCallOutcome
import de.hoshi.core.port.RadioStation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [HaRadioPort] OHNE echtes HA (Fake-HA, Muster [de.hoshi.adapters.ha.HaToolPortTest]):
 * der **Service-Call-Body** ist exakt das HA-Wire-Format (`entity_id`/`media_content_id`/
 * `media_content_type`), der Bearer-Header sitzt bei BEIDEN Endpunkten, und die
 * Honesty-Charter hält (kein Token/Target ⇒ [RadioCallOutcome.NOT_ACCEPTED] OHNE Call;
 * HTTP-Fehler ⇒ [RadioCallOutcome.NOT_ACCEPTED], nie Throw).
 *
 * **P2-Bug-Fix 2026-07-11:** zusätzlich zum Service-Call (`POST /api/services/media_player/…`)
 * kapert der Fake-HA den READ-ONLY State-Readback (`GET /api/states/{target}`) und beweist
 * den Settle-Poll: (a) Readback „playing" ⇒ [RadioCallOutcome.VERIFIED]; (b) Readback bleibt
 * „idle"/„off" (nie playing) ⇒ ehrliches [RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED];
 * (c) Readback-500/Timeout ⇒ best-effort Fallback auf [RadioCallOutcome.VERIFIED] (NIE
 * NOT_ACCEPTED nur wegen eines kaputten Lesens); (d) stop + „idle" ⇒ VERIFIED.
 */
class HaRadioPortTest {

    data class RequestMeta(val method: String, val path: String, val authorization: String?, val bodyText: String)

    private val mapper = ObjectMapper()
    private val wdr2 = RadioStation(name = "WDR 2", streamUrl = "https://wdr2.example/stream")

    /** Baut den State-Readback-JSON-Body, wie `GET /api/states/{id}` ihn liefert. */
    private fun stateJson(state: String) = """{"entity_id":"media_player.rx_v6a","state":"$state"}"""

    /**
     * Startet einen Fake-HA mit `/api/services/media_player/…` UND `/api/states/…`. Beide
     * Anfragen werden separat gekapert ([service]/[state]). Der State-Body kann EINZELN
     * ([stateBody]) ODER als **Sequenz** ([stateBodies], auf dem letzten Element geklemmt)
     * geliefert werden — so lässt sich der Übergangs→End-Zustand des Settle-Polls nachstellen.
     * Optional verzögert [stateDelayMs] jede State-Antwort (Timeout-Probe).
     */
    private fun withHa(
        serviceStatus: Int = 200,
        serviceBody: String = "[]",
        stateStatus: Int = 200,
        stateBody: String = """{"entity_id":"media_player.rx_v6a","state":"playing"}""",
        stateBodies: List<String>? = null,
        stateDelayMs: Long = 0,
        block: (url: String, service: AtomicReference<RequestMeta?>, state: AtomicReference<RequestMeta?>) -> Unit,
    ) {
        val service = AtomicReference<RequestMeta?>(null)
        val state = AtomicReference<RequestMeta?>(null)
        val stateIdx = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/services/media_player/") { ex ->
            service.set(metaOf(ex))
            respond(ex, serviceStatus, serviceBody)
        }
        server.createContext("/api/states/") { ex ->
            state.set(metaOf(ex))
            if (stateDelayMs > 0) Thread.sleep(stateDelayMs)
            val body = if (stateBodies != null) {
                stateBodies[stateIdx.getAndIncrement().coerceAtMost(stateBodies.size - 1)]
            } else {
                stateBody
            }
            respond(ex, stateStatus, body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", service, state)
        } finally {
            server.stop(0)
        }
    }

    /**
     * Baut einen [HaRadioPort] mit **kleinen Poll-Budgets**, damit die Suite NICHT real
     * ~2 s schläft. Default hier: Gesamt 300 ms, Intervall 50 ms (also ~6 Versuche).
     */
    private fun haPort(
        url: String,
        token: String = "test-token",
        timeoutMs: Long = 5000,
        readbackTimeoutMs: Long = 1200,
        readbackSettleMs: Long = 300,
        readbackPollIntervalMs: Long = 50,
    ) = HaRadioPort(
        browser = RadioBrowserAdapter(baseUrl = "http://127.0.0.1:1"), // nie gerufen in play/stop-Tests
        baseUrl = url,
        token = token,
        timeoutMs = timeoutMs,
        readbackTimeoutMs = readbackTimeoutMs,
        readbackSettleMs = readbackSettleMs,
        readbackPollIntervalMs = readbackPollIntervalMs,
    )

    private fun metaOf(ex: HttpExchange) = RequestMeta(
        method = ex.requestMethod,
        path = ex.requestURI.path,
        authorization = ex.requestHeaders.getFirst("Authorization"),
        bodyText = String(ex.requestBody.readBytes(), Charsets.UTF_8),
    )

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // ── play: korrekter HA-Service-Call + Readback ───────────────────────────

    @Test
    fun `play postet play_media mit korrektem Body und Bearer`() =
        withHa(stateBody = stateJson("playing")) { url, service, state ->
            val port = haPort(url)
            val result = port.play(wdr2, "media_player.rx_v6a")
            assertEquals(RadioCallOutcome.VERIFIED, result, "Readback 'playing' muss VERIFIED liefern, war $result")

            val meta = service.get()
            assertNotNull(meta)
            assertEquals("POST", meta!!.method)
            assertEquals("/api/services/media_player/play_media", meta.path)
            assertEquals("Bearer test-token", meta.authorization)
            val body = mapper.readTree(meta.bodyText)
            assertEquals("media_player.rx_v6a", body.path("entity_id").asText())
            assertEquals("https://wdr2.example/stream", body.path("media_content_id").asText())
            assertEquals("music", body.path("media_content_type").asText())

            // READ-ONLY Readback: GET /api/states/{target}, Bearer, kein Body.
            val rb = state.get()
            assertNotNull(rb, "Readback muss /api/states/{target} angefragt haben")
            assertEquals("GET", rb!!.method)
            assertEquals("/api/states/media_player.rx_v6a", rb.path)
            assertEquals("Bearer test-token", rb.authorization)
        }

    // ── (a) play + Readback „playing" ⇒ Ok-läuft (VERIFIED) ──────────────────

    @Test
    fun `play mit Readback state playing liefert VERIFIED`() =
        withHa(stateBody = stateJson("playing")) { url, _, state ->
            val port = haPort(url)
            assertEquals(RadioCallOutcome.VERIFIED, port.play(wdr2, "media_player.rx_v6a"))
            assertNotNull(state.get(), "Readback muss gelaufen sein")
        }

    @Test
    fun `play mit Readback state buffering zaehlt als im Anlauf und liefert ebenfalls VERIFIED`() =
        withHa(stateBody = stateJson("buffering")) { url, _, _ ->
            val port = haPort(url)
            assertEquals(RadioCallOutcome.VERIFIED, port.play(wdr2, "media_player.rx_v6a"))
        }

    @Test
    fun `play pollt durch den Uebergang idle-idle-playing bis playing erreicht ist`() =
        // Race-Reproduktion: die ERSTEN Reads sehen noch „idle" (Receiver lädt), erst der
        // spätere Read sieht „playing". Der Settle-Poll darf NICHT beim ersten „idle" als
        // ACCEPTED_STATE_NOT_REACHED aufgeben, sondern muss bis playing weiterlesen.
        withHa(stateBodies = listOf(stateJson("idle"), stateJson("idle"), stateJson("playing"))) { url, _, state ->
            val port = haPort(url)
            assertEquals(RadioCallOutcome.VERIFIED, port.play(wdr2, "media_player.rx_v6a"))
            assertNotNull(state.get())
        }

    // ── (b) play + Readback „idle/off" (kommt nie playing) ⇒ ehrlich ─────────

    @Test
    fun `play mit Readback state idle das nie playing wird liefert ACCEPTED_STATE_NOT_REACHED`() =
        withHa(stateBody = stateJson("idle")) { url, _, state ->
            val port = haPort(url)
            val result = port.play(wdr2, "media_player.rx_v6a")
            assertEquals(
                RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED,
                result,
                "State bleibt 'idle' im ganzen Budget → ehrlich ACCEPTED_STATE_NOT_REACHED, war $result",
            )
            assertNotNull(state.get(), "Readback muss gelaufen sein (mind. 1 gültige Antwort)")
        }

    @Test
    fun `play mit Readback state off (Receiver aus) liefert ACCEPTED_STATE_NOT_REACHED`() =
        withHa(stateBody = stateJson("off")) { url, _, _ ->
            val port = haPort(url)
            assertEquals(RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED, port.play(wdr2, "media_player.rx_v6a"))
        }

    // ── (c) play + Readback-Fehler/Timeout ⇒ best-effort Fallback (NIE NOT_ACCEPTED) ──

    @Test
    fun `play mit Readback-500 faellt best-effort auf VERIFIED zurueck statt NOT_ACCEPTED`() =
        withHa(stateStatus = 500, stateBody = "boom") { url, service, state ->
            val port = haPort(url)
            val result = port.play(wdr2, "media_player.rx_v6a")
            assertEquals(
                RadioCallOutcome.VERIFIED,
                result,
                "kaputter Readback darf die akzeptierte Tat NIE als NOT_ACCEPTED melden, war $result",
            )
            assertNotNull(service.get(), "der Service-Call ging trotzdem raus")
            assertNotNull(state.get(), "Readback wurde versucht")
        }

    @Test
    fun `play mit Readback-Timeout faellt best-effort auf VERIFIED zurueck statt NOT_ACCEPTED`() =
        withHa(stateBody = stateJson("playing"), stateDelayMs = 800) { url, _, _ ->
            // Readback-Timeout (150ms) < State-Delay (800ms) → jeder Versuch timeoutet lokal,
            // bevor der (im Test winzige) Settle-Budget aufgebraucht ist → best-effort VERIFIED.
            val port = haPort(url, readbackTimeoutMs = 150)
            val result = port.play(wdr2, "media_player.rx_v6a")
            assertEquals(RadioCallOutcome.VERIFIED, result, "Readback-Timeout darf NICHT NOT_ACCEPTED werden, war $result")
        }

    @Test
    fun `play mit kaputtem JSON im Readback faellt best-effort auf VERIFIED zurueck`() =
        withHa(stateBody = "das ist kein json") { url, _, state ->
            val port = haPort(url)
            val result = port.play(wdr2, "media_player.rx_v6a")
            assertEquals(RadioCallOutcome.VERIFIED, result, "kaputtes JSON darf NICHT NOT_ACCEPTED werden, war $result")
            assertNotNull(state.get())
        }

    // ── stop: korrekter HA-Service-Call + Readback ───────────────────────────

    @Test
    fun `stop postet media_stop mit entity_id`() =
        withHa(stateBody = stateJson("idle")) { url, service, state ->
            val port = haPort(url)
            val result = port.stop("media_player.rx_v6a")
            assertEquals(RadioCallOutcome.VERIFIED, result)

            val meta = service.get()
            assertNotNull(meta)
            assertEquals("/api/services/media_player/media_stop", meta!!.path)
            val body = mapper.readTree(meta.bodyText)
            assertEquals("media_player.rx_v6a", body.path("entity_id").asText())
            assertTrue(!body.has("media_content_id"), "media_stop braucht nur die entity_id")

            val rb = state.get()
            assertNotNull(rb, "Readback muss /api/states/{target} angefragt haben")
            assertEquals("GET", rb!!.method)
            assertEquals("/api/states/media_player.rx_v6a", rb.path)
        }

    // ── (d) stop + „idle" ⇒ VERIFIED („aus") ──────────────────────────────────

    @Test
    fun `stop mit Readback state idle liefert VERIFIED`() =
        withHa(stateBody = stateJson("idle")) { url, _, _ ->
            val port = haPort(url)
            assertEquals(RadioCallOutcome.VERIFIED, port.stop("media_player.rx_v6a"))
        }

    @Test
    fun `stop mit Readback state paused off oder standby liefert ebenfalls VERIFIED`() {
        for (target in listOf("paused", "off", "standby")) {
            withHa(stateBody = stateJson(target)) { url, _, _ ->
                val port = haPort(url)
                assertEquals(RadioCallOutcome.VERIFIED, port.stop("media_player.rx_v6a"), "State war: $target")
            }
        }
    }

    @Test
    fun `stop pollt durch den Uebergang playing-playing-idle bis idle erreicht ist`() =
        withHa(stateBodies = listOf(stateJson("playing"), stateJson("playing"), stateJson("idle"))) { url, _, state ->
            val port = haPort(url)
            assertEquals(RadioCallOutcome.VERIFIED, port.stop("media_player.rx_v6a"))
            assertNotNull(state.get())
        }

    @Test
    fun `stop bleibt ACCEPTED_STATE_NOT_REACHED wenn der Receiver im ganzen Budget weiterspielt`() =
        withHa(stateBody = stateJson("playing")) { url, _, state ->
            val port = haPort(url)
            val result = port.stop("media_player.rx_v6a")
            assertEquals(RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED, result, "war $result")
            assertNotNull(state.get())
        }

    // ── Honesty-Charter ──────────────────────────────────────────────────────

    @Test
    fun `ohne Token oder Target gibt es NOT_ACCEPTED OHNE HTTP-Call`() {
        withHa { _, service, state ->
            val blankToken = HaRadioPort(
                RadioBrowserAdapter(baseUrl = "http://127.0.0.1:1"),
                baseUrl = "http://127.0.0.1:1", // dürfte eh nie gerufen werden
                token = "",
            )
            assertEquals(RadioCallOutcome.NOT_ACCEPTED, blankToken.play(wdr2, "media_player.rx_v6a"))
            assertNull(service.get(), "ohne Token darf KEIN Call rausgehen")
            assertNull(state.get(), "ohne Token darf KEIN Readback rausgehen")
        }
        withHa { url, service, state ->
            val port = haPort(url)
            assertEquals(RadioCallOutcome.NOT_ACCEPTED, port.play(wdr2, ""), "ohne Target ehrlich NOT_ACCEPTED")
            assertEquals(RadioCallOutcome.NOT_ACCEPTED, port.stop(""))
            assertNull(service.get(), "ohne Target darf KEIN Call rausgehen")
            assertNull(state.get(), "ohne Target darf KEIN Readback rausgehen")
        }
    }

    @Test
    fun `Service-Call 500 liefert NOT_ACCEPTED ohne Readback`() =
        withHa(serviceStatus = 500, serviceBody = "boom") { url, service, state ->
            val port = haPort(url)
            val result = port.play(wdr2, "media_player.rx_v6a")
            assertEquals(RadioCallOutcome.NOT_ACCEPTED, result, "500 muss NOT_ACCEPTED liefern, war $result")
            assertNotNull(service.get(), "der Call ging raus, HA lehnte ab")
            assertNull(state.get(), "bei Service-Fehler darf KEIN Readback laufen — nichts zu bestätigen")
        }

    @Test
    fun `HTTP-Fehler endet ehrlich als NOT_ACCEPTED nie als Throw`() {
        // Nicht erreichbares HA (Port 1) ⇒ Exception intern ⇒ NOT_ACCEPTED, nie Throw.
        val dead = HaRadioPort(RadioBrowserAdapter(baseUrl = "http://127.0.0.1:1"), "http://127.0.0.1:1", "t", timeoutMs = 300)
        assertEquals(RadioCallOutcome.NOT_ACCEPTED, dead.play(wdr2, "media_player.rx_v6a"))
        assertEquals(RadioCallOutcome.NOT_ACCEPTED, dead.stop("media_player.rx_v6a"))
    }

    // ── search: reine Delegation an den Browser-Adapter ──────────────────────

    @Test
    fun `search delegiert an den RadioBrowserAdapter`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/json/stations/byname/") { ex ->
            val body = """[{"name":"WDR 2","url_resolved":"https://wdr2.example/stream","votes":10,"lastcheckok":1}]"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val browser = RadioBrowserAdapter(baseUrl = "http://127.0.0.1:${server.address.port}")
            val port = HaRadioPort(browser, baseUrl = "http://127.0.0.1:1", token = "t")
            assertEquals(wdr2, port.search("wdr 2"))
        } finally {
            server.stop(0)
        }
    }
}
