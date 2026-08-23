package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * Beweist den [HaToolPort] OHNE echtes HA: ein winziger JDK-HttpServer
 * (`com.sun.net.httpserver`, wie [de.hoshi.adapters.tts.OpenAiTtsAdapter]-Test)
 * spielt `192.168.178.56:8123` und kapert die Anfragen. KEIN Call an echtes HA.
 *
 * Der Fake bedient ZWEI Endpunkte:
 *  - `POST /api/services/{domain}/{service}` — der Tat-Call (→ konfigurierbarer Status).
 *  - `POST /api/template`                    — der READ-ONLY State-Readback
 *    (→ liefert Klartext `gesamt|an`, z.B. `"8|2"`).
 *
 * Fälle: (a) Token blank ⇒ NoEffect ohne HTTP; (b) turn_on + readback „8|2" ⇒ Ok „an";
 * (c) turn_on + readback „8|0" ⇒ NoEffect „kein Licht ging an"; (d) turn_off + „8|0" ⇒ Ok „aus";
 * (e) Service-Call 500 ⇒ Failed (kein Readback); (f) Readback 500 / Timeout ⇒ Fallback-Ok (nicht Failed);
 * (g) scene-Domain ⇒ kein Readback, HTTP-200 ⇒ Ok; (h) korrekter Service-Request (Methode/Pfad/Bearer/Body).
 */
class HaToolPortTest {

    data class RequestMeta(
        val method: String,
        val path: String,
        val authorization: String,
        val bodyText: String,
    )

    /**
     * Startet einen Fake-HA mit `/api/services/...` UND `/api/template`. Beide Anfragen
     * werden separat gekapert ([service]/[template]). Der Service-Endpunkt liefert
     * [serviceStatus]; der Template-Endpunkt liefert [templateStatus] mit Klartext-Body.
     *
     * Der Template-Body kann EINZELN ([templateBody], z.B. „8|2") ODER als **Sequenz**
     * ([templateBodies], z.B. `["8|0","8|0","8|2"]`) geliefert werden: der Mock gibt die
     * Sequenz pro Anfrage der Reihe nach zurück (auf dem letzten Element geklemmt) — so
     * lässt sich der Übergangs→End-Zustand des Settle-Polls nachstellen. Optional verzögert
     * [templateDelayMs] jede Template-Antwort (Timeout-Probe).
     */
    private fun withHa(
        serviceStatus: Int = 200,
        serviceBody: String = "[]",
        templateStatus: Int = 200,
        templateBody: String = "8|2",
        templateBodies: List<String>? = null,
        /**
         * **Frische-Phrasen-Probe** (F2-Rest): wie [templateBodies], nur für den
         * HTTP-Status je Anfrage — teilt sich [templateIdx] mit [templateBodies],
         * damit sich „Anfrage 1 = 200 mit Wert, Anfrage 2 = 500" simulieren lässt
         * (der Last-known-Fallback greift NUR nach einem vorherigen Erfolg).
         * `null` ⇒ [templateStatus] gilt fest für jede Anfrage (unverändertes
         * Verhalten der Bestandstests).
         */
        templateStatuses: List<Int>? = null,
        templateDelayMs: Long = 0,
        block: (url: String, service: AtomicReference<RequestMeta?>, template: AtomicReference<RequestMeta?>) -> Unit,
    ) {
        val service = AtomicReference<RequestMeta?>(null)
        val template = AtomicReference<RequestMeta?>(null)
        val templateIdx = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/services/") { ex ->
            service.set(metaOf(ex))
            respond(ex, serviceStatus, serviceBody)
        }
        server.createContext("/api/template") { ex ->
            template.set(metaOf(ex))
            if (templateDelayMs > 0) Thread.sleep(templateDelayMs)
            val idx = templateIdx.getAndIncrement()
            val body = if (templateBodies != null) {
                templateBodies[idx.coerceAtMost(templateBodies.size - 1)]
            } else {
                templateBody
            }
            val status = if (templateStatuses != null) {
                templateStatuses[idx.coerceAtMost(templateStatuses.size - 1)]
            } else {
                templateStatus
            }
            respond(ex, status, body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", service, template)
        } finally {
            server.stop(0)
        }
    }

    /**
     * Baut einen [HaToolPort] mit **kleinen Poll-Budgets**, damit die Suite NICHT real
     * ~1,8 s schläft. Default hier: Gesamt 300 ms, Intervall 50 ms (also ~6 Versuche).
     * Einzelne Tests überschreiben nur, was sie brauchen (Token/Timeouts).
     */
    private fun haPort(
        url: String,
        token: String = "secret-token",
        timeoutMs: Long = 5000,
        readbackTimeoutMs: Long = 2500,
        readbackSettleMs: Long = 300,
        readbackPollIntervalMs: Long = 50,
        climateReadbackSettleMs: Long = 200,
        // Default = production default: STATIC labels are byte-identical to ToolAreas.
        areaCatalog: AreaCatalogPort = AreaCatalogPort.STATIC,
        // Default = echte Uhr; Frische-Phrasen-Tests injizieren eine feste Clock.
        clock: Clock = Clock.systemUTC(),
    ) = HaToolPort(
        baseUrl = url,
        token = token,
        timeoutMs = timeoutMs,
        readbackTimeoutMs = readbackTimeoutMs,
        readbackSettleMs = readbackSettleMs,
        readbackPollIntervalMs = readbackPollIntervalMs,
        climateReadbackSettleMs = climateReadbackSettleMs,
        areaCatalog = areaCatalog,
        clock = clock,
    )

    /** Eine feste [Clock], die sich per [advanceBy] weiterstellen lässt — für deterministische Alters-Stufen ohne `Thread.sleep`. */
    private class MutableClock(start: Instant) : Clock() {
        private var now = start
        fun advanceBy(millis: Long) { now = now.plusMillis(millis) }
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = now
    }

    private fun metaOf(ex: HttpExchange) = RequestMeta(
        method = ex.requestMethod,
        path = ex.requestURI.path,
        authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
        bodyText = String(ex.requestBody.readBytes(), Charsets.UTF_8),
    )

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** Ein area-getargeter Licht-Befehl, wie ihn der Classifier+Gate liefert. */
    private val lightOn = ToolCall(
        domain = "light",
        service = "turn_on",
        entityId = null,
        data = mapOf("area_id" to "kuche", "brightness_pct" to 40),
    )
    private val lightOff = ToolCall(
        domain = "light",
        service = "turn_off",
        entityId = null,
        data = mapOf("area_id" to "kuche"),
    )

    @Test
    fun `ohne Token liefert NoEffect ohne HTTP-Call`() = withHa { url, service, template ->
        val port = haPort(url, token = "   ")
        val result = port.execute(lightOn)
        assertTrue(result is ToolResult.NoEffect, "blank Token muss NoEffect liefern, war $result")
        assertNull(service.get(), "ohne Token darf kein Service-Call rausgehen")
        assertNull(template.get(), "ohne Token darf kein Readback rausgehen")
    }

    @Test
    fun `turn_on mit Readback 8 von 2 an liefert Ok mit an im Text`() =
        withHa(templateBody = "8|2") { url, _, template ->
            val port = haPort(url)
            val result = port.execute(lightOn)

            assertTrue(result is ToolResult.Ok, "an>=1 muss Ok liefern, war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Küche"), "Gesprochen gehört der HA-Anzeigename, nie der Slug: $phrase")
            assertTrue(phrase.contains("ist an"), "muss verifiziert bestätigen dass Licht an ist: $phrase")
            // Bewusst KEINE genaue Zahl (Unterzähl-Race beim Früh-Stop) — „ist an" reicht ehrlich.

            // Readback ging als READ-ONLY POST /api/template raus, mit Bearer (Token nie geloggt).
            val rb = template.get()
            assertNotNull(rb, "Readback muss /api/template angefragt haben")
            assertEquals("POST", rb!!.method)
            assertEquals("/api/template", rb.path)
            assertEquals("Bearer secret-token", rb.authorization)
            assertTrue(rb.bodyText.contains("area_entities"), "Template muss area_entities zählen: ${rb.bodyText}")
            assertTrue(rb.bodyText.contains("kuche"), "Template muss die Area-ID (Slug!) tragen: ${rb.bodyText}")
        }

    @Test
    fun `turn_on pollt durch den Uebergang 8-0 8-0 8-2 bis an gross genug ist`() =
        // Race-Reproduktion: die ERSTEN Reads sehen noch den Übergangs-Zustand (an=0),
        // erst der spätere Read sieht das Ziel (an=2). Der Settle-Poll darf NICHT beim
        // ersten „8|0" als NoEffect aufgeben, sondern muss bis an>=1 weiterlesen.
        withHa(templateBodies = listOf("8|0", "8|0", "8|2")) { url, _, template ->
            val port = haPort(url)
            val result = port.execute(lightOn)

            assertTrue(result is ToolResult.Ok, "Poll muss bis an>=1 warten und Ok liefern, war $result")
            val phrase = (result as ToolResult.Ok).phrase
            // Phrase nennt bewusst KEINE genaue Zahl (Unterzähl-Race beim Früh-Stop) —
            // sie bestätigt ehrlich „ist an". Beweis dass der Poll wartete: Ok statt NoEffect.
            assertTrue(phrase.contains("ist an"), "muss verifiziert 'ist an' melden: $phrase")
            assertNotNull(template.get(), "Readback muss /api/template angefragt haben")
        }

    @Test
    fun `turn_off pollt durch den Uebergang 8-2 8-0 bis aus`() =
        // turn_off: erster Read sieht noch 2 an, der zweite sieht alle aus → Poll bis an=0.
        withHa(templateBodies = listOf("8|2", "8|0")) { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(lightOff)
            assertTrue(result is ToolResult.Ok, "Poll muss bis an=0 warten und Ok liefern, war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("aus"),
                "muss bestätigen dass Licht aus ist: ${result.phrase}",
            )
        }

    @Test
    fun `turn_on bleibt NoEffect wenn das Geraet im ganzen Budget nie reagiert`() =
        // Gerät reagiert NIE (immer 8|0). Nach Budget-Ende → letzter Stand 8|0 → NoEffect.
        withHa(templateBodies = listOf("8|0")) { url, _, template ->
            val port = haPort(url)
            val result = port.execute(lightOn)
            assertTrue(result is ToolResult.NoEffect, "kein Effekt im Budget muss NoEffect liefern, war $result")
            assertTrue(
                (result as ToolResult.NoEffect).phrase.contains("kein Licht"),
                "muss ehrlich sagen dass nichts anging: ${result.phrase}",
            )
            // Es wurde mehrfach gepollt (Budget 300ms / Intervall 50ms → ~6 Versuche), nicht nur 1x.
            assertNotNull(template.get(), "Readback muss gelaufen sein")
        }

    @Test
    fun `turn_on mit Readback 8 von 0 an liefert NoEffect kein Licht ging an`() =
        withHa(templateBody = "8|0") { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(lightOn)
            assertTrue(result is ToolResult.NoEffect, "an=0 (gesamt>0) muss NoEffect liefern, war $result")
            val phrase = (result as ToolResult.NoEffect).phrase
            assertTrue(phrase.contains("Küche"), "Gesprochen gehört der HA-Anzeigename, nie der Slug: $phrase")
            assertTrue(phrase.contains("kein Licht"), "muss ehrlich sagen dass nichts anging: $phrase")
        }

    @Test
    fun `turn_on mit Readback 0 Lampen liefert NoEffect keine Lampen gefunden`() =
        withHa(templateBody = "0|0") { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(lightOn)
            assertTrue(result is ToolResult.NoEffect, "gesamt=0 muss NoEffect liefern, war $result")
            assertTrue(
                (result as ToolResult.NoEffect).phrase.contains("keine Lampen"),
                "muss sagen dass keine Lampen da sind: ${result.phrase}",
            )
        }

    @Test
    fun `turn_off mit Readback 8 von 0 an liefert Ok ist aus`() =
        withHa(templateBody = "8|0") { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(lightOff)
            assertTrue(result is ToolResult.Ok, "turn_off + an=0 muss Ok liefern, war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Küche"), "Gesprochen gehört der HA-Anzeigename, nie der Slug: $phrase")
            assertTrue(phrase.contains("aus"), "muss bestätigen dass Licht aus ist: $phrase")
        }

    @Test
    fun `turn_off mit Readback 8 von 3 an liefert NoEffect noch an`() =
        withHa(templateBody = "8|3") { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(lightOff)
            assertTrue(result is ToolResult.NoEffect, "turn_off + an>0 muss NoEffect liefern, war $result")
            assertTrue(
                (result as ToolResult.NoEffect).phrase.contains("noch an"),
                "muss ehrlich sagen dass welche noch an sind: ${result.phrase}",
            )
        }

    @Test
    fun `Service-Call 500 liefert Failed ohne Readback`() =
        withHa(serviceStatus = 500, serviceBody = "boom") { url, _, template ->
            val port = haPort(url)
            val result = port.execute(lightOn)
            assertTrue(result is ToolResult.Failed, "500 muss Failed liefern, war $result")
            assertNull(template.get(), "bei Service-500 darf KEIN Readback laufen")
        }

    @Test
    fun `Readback 500 faellt auf die HTTP-200-Phrase zurueck statt Failed`() =
        withHa(templateStatus = 500, templateBody = "kaputt") { url, _, template ->
            val port = haPort(url)
            val result = port.execute(lightOn)
            assertTrue(result is ToolResult.Ok, "Readback-Fehler darf NICHT Failed werden, war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("geschickt"),
                "Fallback muss die HTTP-200-„geschickt\"-Phrase sein: ${result.phrase}",
            )
            assertNotNull(template.get(), "Readback wurde versucht")
        }

    @Test
    fun `Readback-Timeout faellt auf die HTTP-200-Phrase zurueck statt Failed`() =
        withHa(templateBody = "8|2", templateDelayMs = 800) { url, _, _ ->
            // Readback-Timeout (150ms) < Template-Delay (800ms) → Timeout → Fallback-Ok.
            val port = haPort(url, readbackTimeoutMs = 150)
            val result = port.execute(lightOn)
            assertTrue(result is ToolResult.Ok, "Readback-Timeout darf NICHT Failed werden, war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("geschickt"),
                "Timeout-Fallback muss die HTTP-200-Phrase sein: ${result.phrase}",
            )
        }

    @Test
    fun `scene-Domain macht keinen Readback und liefert Ok aus HTTP-200`() =
        withHa { url, service, template ->
            val sceneCall = ToolCall(
                domain = "scene",
                service = "turn_on",
                entityId = null,
                data = mapOf("area_id" to "wohnzimmer"),
            )
            val port = haPort(url)
            val result = port.execute(sceneCall)
            assertTrue(result is ToolResult.Ok, "scene-200 muss Ok liefern, war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("geschickt"),
                "scene bleibt bei der HTTP-200-Phrase: ${result.phrase}",
            )
            assertNotNull(service.get(), "Service-Call muss raus")
            assertNull(template.get(), "nicht-light-Domain darf KEINEN Readback machen")
        }

    @Test
    fun `light ohne area_id macht keinen Readback und liefert Ok aus HTTP-200`() =
        withHa { url, _, template ->
            val noArea = ToolCall(domain = "light", service = "turn_on", entityId = "light.flur", data = emptyMap())
            val port = haPort(url)
            val result = port.execute(noArea)
            assertTrue(result is ToolResult.Ok, "light ohne area muss Ok liefern, war $result")
            assertNull(template.get(), "ohne area_id darf KEIN Readback laufen")
        }

    @Test
    fun `Service-Request hat Methode Pfad Bearer und Body korrekt`() =
        withHa(templateBody = "8|2") { url, service, _ ->
            val port = haPort(url)
            port.execute(lightOn)

            val meta = service.get()
            assertNotNull(meta, "HA muss angefragt worden sein")
            assertEquals("POST", meta!!.method, "Service-Call muss POST sein")
            assertEquals("/api/services/light/turn_on", meta.path, "Pfad = /api/services/{domain}/{service}")
            assertEquals("Bearer secret-token", meta.authorization, "Bearer-Header falsch/fehlt")
            assertTrue(meta.bodyText.contains("area_id"), "Body muss area_id tragen: ${meta.bodyText}")
            assertTrue(meta.bodyText.contains("kuche"), "Body muss die Area-ID (Slug!) tragen: ${meta.bodyText}")
            assertTrue(meta.bodyText.contains("brightness_pct"), "Body muss die Params tragen: ${meta.bodyText}")
        }

    @Test
    fun `HA down (connection refused) liefert Failed`() {
        // Port, auf dem nichts lauscht → connection refused → Failed (never-throw).
        val port = haPort("http://127.0.0.1:1", timeoutMs = 1500)
        val result = port.execute(lightOn)
        assertTrue(result is ToolResult.Failed, "connection refused muss Failed liefern, war $result")
    }

    // ── Bare turn_on Delta-Baseline (Live-Bug 2026-07-09) ───────────────────────

    /** Nackter Area-Licht-Befehl OHNE Dimm-/Farb-Params — löst die Delta-Baseline aus. */
    private val bareOn = ToolCall(
        domain = "light",
        service = "turn_on",
        entityId = null,
        data = mapOf("area_id" to "kuche"),
    )

    /**
     * Baseline sieht 1 von 10 an; der Settle-Poll pollt weiter, bis das echte Delta da
     * ist (an steigt auf 4 > 1) — erst dann darf die Antwort „ist an" behaupten.
     */
    @Test
    fun `bare turn_on mit Delta Baseline 10-1 dann 10-4 liefert Ok ist an`() =
        withHa(templateBodies = listOf("10|1", "10|1", "10|4")) { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(bareOn)

            assertTrue(result is ToolResult.Ok, "an > baseline.an muss Ok liefern, war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("ist an"),
                "muss verifiziert bestätigen dass Licht an ist: ${result.phrase}",
            )
        }

    /**
     * Reproduktion des Live-Bugs vom 2026-07-09: eine Lampe brannte schon VOR dem
     * Befehl (Baseline an=1), trotz 9 Reserven ging keine neu an — mit der alten
     * an≥1-Logik hätte Hoshi fälschlich „ist an" gesagt, die Delta-Baseline muss
     * ehrlich NoEffect liefern.
     */
    @Test
    fun `bare turn_on ohne Delta trotz Reserven liefert NoEffect schon Licht aber nichts neu`() =
        withHa(templateBodies = listOf("10|1")) { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(bareOn)

            assertTrue(result is ToolResult.NoEffect, "an unverändert trotz Reserven muss NoEffect liefern, war $result")
            val phrase = (result as ToolResult.NoEffect).phrase
            assertTrue(phrase.contains("schon Licht"), "muss sagen dass schon Licht brannte: $phrase")
            assertTrue(phrase.contains("neu"), "muss sagen dass nichts NEU anging: $phrase")
        }

    /**
     * Alle erreichbaren Lampen der Area brannten schon VOR dem Befehl (2 an, 4
     * offline ⇒ erreichbar=2=baseline.an) — kein Delta mehr möglich, aber ehrlich
     * „schon an" statt eines irreführenden NoEffect.
     */
    @Test
    fun `bare turn_on wenn alle erreichbaren schon brannten liefert Ok schon an`() =
        withHa(templateBodies = listOf("6|2|4")) { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(bareOn)

            assertTrue(result is ToolResult.Ok, "baseline.an >= erreichbar muss Ok liefern, war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("schon an"),
                "muss ehrlich sagen dass es schon an war: ${result.phrase}",
            )
        }

    /**
     * Alle 4 Lampen der Area sind offline (unavailable) — kein Delta, keine Reserven,
     * ehrlich NoEffect mit Offline-Zähler in der Phrase.
     */
    @Test
    fun `bare turn_on alles offline liefert NoEffect mit Offline-Zaehler`() =
        withHa(templateBodies = listOf("4|0|4")) { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(bareOn)

            assertTrue(result is ToolResult.NoEffect, "alles offline muss NoEffect liefern, war $result")
            val phrase = (result as ToolResult.NoEffect).phrase
            assertTrue(phrase.contains("kein Licht"), "muss ehrlich sagen dass nichts anging: $phrase")
            assertTrue(phrase.contains("4 Lampen"), "muss die Offline-Zahl nennen: $phrase")
            assertTrue(phrase.contains("nicht erreichbar"), "muss 'nicht erreichbar' nennen: $phrase")
        }

    // ── Klima-Ehrlichkeit: Existenz-Check + Soll-Wert-Readback ──────────────────

    /** Ein area-getargeter Klima-Befehl, wie ihn der Classifier+Gate liefert. */
    private val climateSet = ToolCall(
        domain = "climate",
        service = "set_temperature",
        entityId = null,
        data = mapOf("area_id" to "badezimmer", "temperature" to 21),
    )

    @Test
    fun `climate_set auf Area ohne Thermostat liefert NoEffect ohne Service-Call`() =
        withHa(templateBody = "0") { url, service, template ->
            val port = haPort(url)
            val result = port.execute(climateSet)

            assertTrue(result is ToolResult.NoEffect, "Area ohne climate-Entity muss NoEffect liefern, war $result")
            val phrase = (result as ToolResult.NoEffect).phrase
            assertTrue(phrase.contains("Badezimmer"), "Area-Label muss im Text stehen: $phrase")
            assertTrue(phrase.contains("kein Thermostat"), "muss ehrlich sagen dass kein Thermostat da ist: $phrase")

            assertNull(service.get(), "ohne Thermostat darf KEIN Service-Call raus (kein leeres 200-Fake-Quittieren)")
            assertNotNull(template.get(), "Existenz-Check muss /api/template angefragt haben")
            assertTrue(
                template.get()!!.bodyText.contains("climate"),
                "Existenz-Check-Template muss auf climate-Entities filtern: ${template.get()!!.bodyText}",
            )
        }

    @Test
    fun `climate_set mit bestaetigtem Readback liefert Ok mit Grad-Wert`() =
        // idx0 = Existenz-Check (1 climate-Entity vorhanden), idx1 = Soll-Wert-Readback
        // (trifft das Ziel 21 sofort beim ersten Poll-Versuch).
        withHa(templateBodies = listOf("1", "21")) { url, service, _ ->
            val port = haPort(url)
            val result = port.execute(climateSet)

            assertTrue(result is ToolResult.Ok, "bestätigter Soll-Wert muss Ok liefern, war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertEquals("Heizung im Badezimmer auf 21 Grad.", phrase, "Phrase muss wörtlich passen")
            assertNotNull(service.get(), "Service-Call muss raus")
        }

    @Test
    fun `climate_set ohne Bestaetigung im Budget liefert ehrliches NoEffect`() =
        // idx0 = Existenz-Check (Entity da), danach IMMER "19" (weicht vom Ziel 21 ab,
        // klemmt auf dem letzten Sequenz-Element) → Budget läuft ab, nie bestätigt.
        withHa(templateBodies = listOf("1", "19")) { url, service, _ ->
            val port = haPort(url)
            val result = port.execute(climateSet)

            assertTrue(result is ToolResult.NoEffect, "unbestätigter Soll-Wert muss NoEffect liefern, war $result")
            assertEquals(
                "Hab's geschickt, die Heizung hat noch nicht reagiert.",
                (result as ToolResult.NoEffect).phrase,
                "Phrase muss wörtlich passen",
            )
            assertNotNull(service.get(), "Service-Call ging trotzdem raus (HTTP-200 kam an, nur die Bestätigung fehlt)")
        }

    @Test
    fun `climate_set macht Service-Call trotz kaputtem Existenz-Check (fail-open)`() =
        // Existenz-Check UND Readback schlagen durchgehend fehl (500) → der Check darf
        // die echte Tat NICHT blockieren (best-effort, fail-open) — Service-Call geht
        // raus; ohne Bestätigung bleibt es ehrlich bei NoEffect statt einer Ok-Lüge.
        withHa(templateStatus = 500, templateBody = "boom") { url, service, _ ->
            val port = haPort(url)
            val result = port.execute(climateSet)

            assertNotNull(service.get(), "kaputter Read darf den Service-Call NICHT blockieren")
            assertTrue(
                result is ToolResult.NoEffect,
                "ohne jede Bestätigung bleibt es ehrlich NoEffect statt geraten Ok, war $result",
            )
        }

    // ── READ-ONLY: Ist-Temperatur lesen ─────────────────────────────────────────

    /** Ein Read-ToolCall mit Area, wie ihn der Classifier liefert (read=true). */
    private fun readTemp(area: String?) = ToolCall(
        domain = "sensor",
        service = "read_temperature",
        entityId = null,
        data = if (area != null) mapOf("area_id" to area) else emptyMap(),
        read = true,
    )

    @Test
    fun `read Temperatur mit Area liest per Template und meldet warm den Wert`() =
        withHa(templateBody = "21.5") { url, service, template ->
            val port = haPort(url)
            val result = port.execute(readTemp("wohnzimmer"))

            assertTrue(result is ToolResult.Ok, "Wert vorhanden ⇒ Ok, war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Wohnzimmer"), "Area-Label muss im Text stehen: $phrase")
            assertTrue(phrase.contains("21,5 Grad"), "Komma-Dezimal + Grad erwartet: $phrase")

            // READ-ONLY: NUR /api/template, NIE /api/services (kein Schalten beim Lesen).
            assertNull(service.get(), "Read darf KEINEN Service-Call (Schaltung) auslösen")
            val rb = template.get()
            assertNotNull(rb, "Read muss /api/template anfragen")
            assertEquals("POST", rb!!.method)
            assertEquals("/api/template", rb.path)
            assertEquals("Bearer secret-token", rb.authorization)
            assertTrue(rb.bodyText.contains("current_temperature"), "Template liest current_temperature: ${rb.bodyText}")
            assertTrue(rb.bodyText.contains("wohnzimmer"), "Template muss die Area tragen: ${rb.bodyText}")
        }

    @Test
    fun `read Temperatur ganze Zahl rendert ohne Nachkomma und mit Area-Label Kueche`() =
        withHa(templateBody = "20.0") { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(readTemp("kuche"))
            assertTrue(result is ToolResult.Ok, "war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Küche"), "kuche ergibt Label Kueche: $phrase")
            assertTrue(phrase.contains("20 Grad"), "ganze Zahl ohne Nachkomma: $phrase")
        }

    @Test
    fun `read Temperatur ohne Raum liest das Haus-Aggregat`() =
        withHa(templateBody = "21.3") { url, _, template ->
            val port = haPort(url)
            val result = port.execute(readTemp(null))
            assertTrue(result is ToolResult.Ok, "war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Haus"), "ohne Raum ⇒ Haus-Aggregat-Phrase: $phrase")
            assertTrue(phrase.contains("21,3 Grad"), "Wert erwartet: $phrase")
            assertTrue(template.get()!!.bodyText.contains("states.climate"), "Haus-Template aggregiert states.climate")
        }

    @Test
    fun `read Temperatur ohne Wert (none) liefert ehrlich NoEffect`() =
        withHa(templateBody = "none") { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(readTemp("wohnzimmer"))
            assertTrue(result is ToolResult.NoEffect, "kein Wert ⇒ NoEffect, war $result")
            assertTrue(
                (result as ToolResult.NoEffect).phrase.contains("keinen Wert"),
                "muss ehrlich keinen Wert melden: ${result.phrase}",
            )
        }

    @Test
    fun `read Temperatur bei HA-Fehler (500) liefert honesten Failed-Fallback statt Crash`() =
        withHa(templateStatus = 500, templateBody = "boom") { url, _, _ ->
            val port = haPort(url)
            val result = port.execute(readTemp("wohnzimmer"))
            assertTrue(result is ToolResult.Failed, "HA-Fehler ⇒ Failed, war $result")
            assertTrue(
                (result as ToolResult.Failed).phrase.contains("Temperatur"),
                "warmer honest Fallback: ${result.phrase}",
            )
        }

    @Test
    fun `read Temperatur ohne Token liefert NoEffect ohne HTTP`() =
        withHa { url, service, template ->
            val port = haPort(url, token = "   ")
            val result = port.execute(readTemp("wohnzimmer"))
            assertTrue(result is ToolResult.NoEffect, "ohne Token ⇒ NoEffect, war $result")
            assertNull(service.get(), "ohne Token kein Service-Call")
            assertNull(template.get(), "ohne Token kein Template-Read")
        }

    @Test
    fun `read Temperatur bei HA down liefert Failed (never-throw)`() {
        val port = haPort("http://127.0.0.1:1", readbackTimeoutMs = 1000)
        val result = port.execute(readTemp("wohnzimmer"))
        assertTrue(result is ToolResult.Failed, "connection refused ⇒ Failed, war $result")
    }

    // ── Frische-Phrasen (F2-Rest): Last-known-Fallback nach einem vorherigen Erfolg ──

    @Test
    fun `none nach vorherigem Erfolg spricht den gemerkten Wert MIT gerade-eben-Phrase statt NoEffect`() =
        withHa(templateBodies = listOf("21.5", "none")) { url, _, _ ->
            val port = haPort(url)
            val live = port.execute(readTemp("wohnzimmer"))
            assertTrue(live is ToolResult.Ok, "erster Read ist live, war $live")

            val stale = port.execute(readTemp("wohnzimmer"))
            assertTrue(stale is ToolResult.Ok, "zweiter Read (none) faellt auf den gemerkten Wert zurueck, war $stale")
            val phrase = (stale as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Wohnzimmer"), "Area-Label erwartet: $phrase")
            assertTrue(phrase.contains("21,5 Grad"), "gemerkter Wert erwartet: $phrase")
            assertTrue(phrase.contains("gerade eben"), "kaum Zeit vergangen ⇒ gerade-eben-Phrase: $phrase")
            assertTrue(phrase.contains("waren es"), "Vergangenheitsform erwartet (nicht mehr live): $phrase")
            assertFalse(phrase.contains("sind es gerade"), "darf NICHT die Live-Formulierung sprechen: $phrase")
        }

    @Test
    fun `500 nach vorherigem Erfolg spricht den gemerkten Wert statt Failed`() =
        withHa(templateBodies = listOf("20.0", "boom"), templateStatuses = listOf(200, 500)) { url, _, _ ->
            val port = haPort(url)
            assertTrue(port.execute(readTemp("kuche")) is ToolResult.Ok, "erster Read ist live")

            val stale = port.execute(readTemp("kuche"))
            assertTrue(stale is ToolResult.Ok, "HTTP-500 nach Erfolg faellt auf den gemerkten Wert zurueck, war $stale")
            val phrase = (stale as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Küche") && phrase.contains("20 Grad"), "gemerkter Wert erwartet: $phrase")
        }

    @Test
    fun `Haus-Aggregat nutzt einen eigenen Cache-Schluessel, getrennt von jeder Area`() =
        withHa(templateBodies = listOf("21.3", "none")) { url, _, _ ->
            val port = haPort(url)
            assertTrue(port.execute(readTemp(null)) is ToolResult.Ok, "erster Read ist live")

            val stale = port.execute(readTemp(null))
            assertTrue(stale is ToolResult.Ok, "war $stale")
            val phrase = (stale as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Haus") && phrase.contains("21,3 Grad"), "Haus-Aggregat-Fallback erwartet: $phrase")
        }

    @Test
    fun `ohne vorherigen Erfolg bleibt das Verhalten unveraendert - kein Cache, kein Fallback`() =
        withHa(templateBody = "none") { url, _, _ ->
            // Frischer Port, NIE zuvor erfolgreich gelesen ⇒ exakt das alte Verhalten.
            val result = haPort(url).execute(readTemp("wohnzimmer"))
            assertTrue(result is ToolResult.NoEffect, "ohne gemerkten Wert bleibt es ehrlich NoEffect, war $result")
        }

    @Test
    fun `Frische-Phrase trifft die Minuten-Stufe bei 12 Minuten Alter`() =
        withHa(templateBodies = listOf("21.5", "none")) { url, _, _ ->
            val clock = MutableClock(Instant.parse("2026-08-19T10:00:00Z"))
            val port = haPort(url, clock = clock)
            assertTrue(port.execute(readTemp("wohnzimmer")) is ToolResult.Ok)

            clock.advanceBy(12 * 60_000L)
            val stale = port.execute(readTemp("wohnzimmer"))
            val phrase = (stale as ToolResult.Ok).phrase
            assertTrue(phrase.contains("vor 12 Minuten"), "12 Minuten Alter erwartet: $phrase")
        }

    @Test
    fun `Frische-Phrase trifft den Stunden-Auffang ab 60 Minuten Alter`() =
        withHa(templateBodies = listOf("21.5", "none")) { url, _, _ ->
            val clock = MutableClock(Instant.parse("2026-08-19T10:00:00Z"))
            val port = haPort(url, clock = clock)
            assertTrue(port.execute(readTemp("wohnzimmer")) is ToolResult.Ok)

            clock.advanceBy(90 * 60_000L)
            val stale = port.execute(readTemp("wohnzimmer"))
            val phrase = (stale as ToolResult.Ok).phrase
            assertTrue(phrase.contains("vor über einer Stunde"), "über eine Stunde Alter erwartet: $phrase")
            assertFalse(phrase.contains("90 Minuten"), "keine Sekunden-/Minuten-Pedanterie jenseits einer Stunde: $phrase")
        }

    // ── One room, one name: spoken labels come from the AreaCatalogPort ─────────

    /** Stub catalog: exactly the given areas, no HTTP (the adapter itself has its own test). */
    private fun catalogOf(vararg areas: Pair<String, String>): AreaCatalogPort =
        AreaCatalogPort { areas.map { (id, label) -> AreaInfo(areaId = id, label = label) } }

    @Test
    fun `Temperatur-Label kommt aus dem Katalog und schlaegt die statische ToolAreas-Map`() =
        withHa(templateBody = "20.0") { url, _, _ ->
            // HA renamed the area; the static map still says "Küche" — the catalog must win.
            val port = haPort(url, areaCatalog = catalogOf("kuche" to "Kochnische"))
            val result = port.execute(readTemp("kuche"))

            assertTrue(result is ToolResult.Ok, "war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Kochnische"), "HA-Name muss gesprochen werden: $phrase")
            assertFalse(phrase.contains("Küche"), "statisches Label darf NICHT gewinnen: $phrase")
        }

    @Test
    fun `neu in HA angelegte Area wird mit ihrem echten Namen gesprochen statt als Slug`() =
        withHa(templateBody = "19.0") { url, _, _ ->
            // The bug this slice fixes: ToolAreas knows no "gaestezimmer" ⇒ would say "Gaestezimmer".
            val port = haPort(url, areaCatalog = catalogOf("gaestezimmer" to "Gäste-Zimmer"))
            val result = port.execute(readTemp("gaestezimmer"))

            assertTrue(result is ToolResult.Ok, "war $result")
            val phrase = (result as ToolResult.Ok).phrase
            assertTrue(phrase.contains("Gäste-Zimmer"), "echter HA-Name erwartet: $phrase")
            assertFalse(phrase.contains("Gaestezimmer"), "kapitalisierter Slug darf nicht mehr fallen: $phrase")
        }

    @Test
    fun `Katalog kennt den Slug nicht ⇒ ToolAreas-Fallback`() =
        withHa(templateBody = "20.0") { url, _, _ ->
            val port = haPort(url, areaCatalog = catalogOf("flur" to "Flur"))
            val result = port.execute(readTemp("kuche"))

            assertTrue(result is ToolResult.Ok, "war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("Küche"),
                "unbekannter Slug muss auf ToolAreas.label zurückfallen: ${result.phrase}",
            )
        }

    @Test
    fun `leeres Katalog-Label ⇒ ToolAreas-Fallback statt leerer Raumname`() =
        withHa(templateBody = "20.0") { url, _, _ ->
            val port = haPort(url, areaCatalog = catalogOf("kuche" to "   "))
            val result = port.execute(readTemp("kuche"))

            assertTrue(result is ToolResult.Ok, "war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("Küche"),
                "leeres Label darf keinen leeren {room}-Slot erzeugen: ${result.phrase}",
            )
        }

    @Test
    fun `Katalog im Fallback-Stand (HA unerreichbar) wirft nicht und spricht ein sinnvolles Label`() =
        withHa(templateBody = "20.0") { url, _, _ ->
            // Real adapter against a dead port: never-throw ⇒ its own STATIC fallback.
            val deadCatalog = HaAreaCatalogAdapter(
                baseUrl = "http://127.0.0.1:1",
                token = "secret-token",
                ttl = Duration.ofMinutes(15),
                timeoutMs = 300,
            )
            val port = haPort(url, areaCatalog = deadCatalog)
            val result = port.execute(readTemp("kuche"))

            assertTrue(result is ToolResult.Ok, "Katalog-Ausfall darf die Antwort nicht kippen, war $result")
            assertTrue(
                (result as ToolResult.Ok).phrase.contains("Küche"),
                "Fallback-Stand muss ein sinnvolles Label sprechen: ${result.phrase}",
            )
        }

    @Test
    fun `climate ohne Thermostat nennt den Raum mit dem Katalog-Namen`() =
        withHa(templateBody = "0") { url, service, _ ->
            val port = haPort(url, areaCatalog = catalogOf("badezimmer" to "Bad unten"))
            val result = port.execute(climateSet)

            assertTrue(result is ToolResult.NoEffect, "war $result")
            val phrase = (result as ToolResult.NoEffect).phrase
            assertTrue(phrase.contains("Bad unten"), "Katalog-Label erwartet: $phrase")
            assertNull(service.get(), "ohne Thermostat darf KEIN Service-Call raus")
        }

    @Test
    fun `climate_set Quittung nennt den Raum mit dem Katalog-Namen`() =
        withHa(templateBodies = listOf("1", "21")) { url, _, _ ->
            val port = haPort(url, areaCatalog = catalogOf("badezimmer" to "Bad unten"))
            val result = port.execute(climateSet)

            assertTrue(result is ToolResult.Ok, "war $result")
            assertEquals(
                "Heizung im Bad unten auf 21 Grad.",
                (result as ToolResult.Ok).phrase,
                "Quittung muss den echten HA-Namen tragen",
            )
        }
}
