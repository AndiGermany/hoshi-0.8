package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * **Andis Befund vom 22.08.: „Das TTS sagt aber leider Kuche und nicht Küche."**
 *
 * Der Vollzugs-Satz trug den rohen `area_id`-SLUG (`kuche`) statt des echten
 * HA-Anzeigenamens (`Küche`). HA slugifiziert ü→u, der Slug ist also ein
 * SCHLÜSSEL und kein Name — ihn zu sprechen heißt, ein Nutzerdatum zu
 * VERSTÜMMELN. Das bricht dieselbe eiserne Regel wie es zu übersetzen.
 *
 * Diese Klasse pinnt die drei Zusagen, die daraus folgen:
 *  1. **Der gesprochene Satz trägt den Anzeigenamen** — Andis Originalfall
 *     (`area_id=kuche` ⇒ hörbar „Küche"), und zwar auf JEDEM Quittungs-Pfad
 *     (Licht an/aus, „nichts ging an", keine Lampen, sentToArea).
 *  2. **Der Fallback ist ehrlich-vage, nie verstümmelt** — kein Anzeigename
 *     auffindbar ⇒ „im gewünschten Raum", NIEMALS „Kuche".
 *  3. **Der Raumname selbst wird nie übersetzt** — je Sprache eine Probe: der
 *     Satz drumherum wechselt, „Küche" bleibt in allen fünf Sprachen stehen.
 *
 * Der SLUG bleibt dabei unangetastet, wo er hingehört: im Service-Call-Body
 * (Matching-Wahrheit) — auch das ist hier gepinnt.
 */
class HaToolPortRoomNameTest {

    /** Fake-HA: Service-Call + READ-ONLY Template-Readback, wie in [HaToolPortTest]. */
    private fun withHa(templateBody: String = "8|2", block: (url: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/services/") { ex -> respond(ex, 200, "[]") }
        server.createContext("/api/template") { ex -> respond(ex, 200, templateBody) }
        server.executor = null
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        ex.requestBody.readBytes()
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun haPort(url: String, areaCatalog: AreaCatalogPort = AreaCatalogPort.STATIC) = HaToolPort(
        baseUrl = url,
        token = "t",
        timeoutMs = 2000,
        readbackTimeoutMs = 800,
        readbackSettleMs = 200,
        readbackPollIntervalMs = 50,
        climateReadbackSettleMs = 200,
        areaCatalog = areaCatalog,
    )

    private fun phrase(r: ToolResult) = when (r) {
        is ToolResult.Ok -> r.phrase
        is ToolResult.NoEffect -> r.phrase
        is ToolResult.Failed -> r.phrase
    }

    /**
     * Andis echter Fall: der reale HA-Slug der Küche ist `kuche` (ü→u), nicht `kueche`.
     *
     * `brightness_pct` ist bewusst gesetzt — ein NACKTES `turn_on` (nur `area_id`)
     * nimmt den Delta-Baseline-Pfad und quittiert bei unverändertem Zähler ehrlich
     * „nichts ging an". Hier geht es um den RAUMNAMEN, nicht um die Delta-Ehrlichkeit.
     */
    private fun lightOn(language: Language = Language.DE, area: String = "kuche") = ToolCall(
        domain = "light", service = "turn_on", entityId = null,
        data = mapOf("area_id" to area, "brightness_pct" to 40), language = language,
    )

    private fun lightOff(area: String = "kuche") = ToolCall(
        domain = "light", service = "turn_off", entityId = null,
        data = mapOf("area_id" to area),
    )

    private fun catalogOf(vararg areas: Pair<String, String>): AreaCatalogPort =
        AreaCatalogPort { areas.map { (id, label) -> AreaInfo(areaId = id, label = label) } }

    // ── (1) Andis Fall: gesprochen wird der Anzeigename ──────────────────────

    @Test
    fun `Andis Fall - area_id kuche wird als Kueche gesprochen, nie als Kuche`() =
        withHa(templateBody = "8|2") { url ->
            val actual = phrase(haPort(url).execute(lightOn()))

            assertTrue(actual.contains("Küche"), "Der Anzeigename gehört in den Satz: '$actual'")
            assertFalse(actual.contains("Kuche"), "NIE der verstümmelte Slug — genau Andis Befund: '$actual'")
            assertFalse(actual.contains("kuche"), "Auch nicht kleingeschrieben: '$actual'")
            assertEquals("Licht im Küche ist an.", actual)
        }

    @Test
    fun `auch die Aus-Quittung traegt den Anzeigenamen`() = withHa(templateBody = "8|0") { url ->
        assertEquals("Licht im Küche ist aus.", phrase(haPort(url).execute(lightOff())))
    }

    /** Der live gelesene HA-Registry-Name gewinnt gegen die kuratierte Karte. */
    @Test
    fun `der Katalog-Name der echten HA-Registry gewinnt`() = withHa(templateBody = "8|2") { url ->
        val port = haPort(url, areaCatalog = catalogOf("kuche" to "Kochnische"))
        val actual = phrase(port.execute(lightOn()))

        assertTrue(actual.contains("Kochnische"), "Der echte Registry-Name gehört in den Satz: '$actual'")
        assertFalse(actual.contains("Küche"), "Die kuratierte Karte darf den Live-Namen nicht überstimmen: '$actual'")
    }

    /** Der ehrliche NoEffect-Pfad („nichts ging an") sprach den Slug genauso. */
    @Test
    fun `auch der NoEffect-Pfad traegt den Anzeigenamen`() = withHa(templateBody = "8|0") { url ->
        val actual = phrase(haPort(url).execute(lightOn()))

        assertTrue(actual.contains("Küche"), "auch der ehrliche NoEffect spricht den Namen: '$actual'")
        assertFalse(actual.contains("kuche"), "kein Slug im NoEffect-Satz: '$actual'")
    }

    /** `sentToArea` — die Quittung ohne Readback (Szene/Cover), traf JEDE Domain. */
    @Test
    fun `die sentToArea-Quittung ohne Readback traegt den Anzeigenamen`() = withHa { url ->
        val scene = ToolCall(
            domain = "scene", service = "turn_on", entityId = null,
            data = mapOf("area_id" to "kuche"),
        )
        val actual = phrase(haPort(url).execute(scene))

        assertTrue(actual.contains("Küche"), "auch ohne Readback der Name: '$actual'")
        assertFalse(actual.contains("kuche"), "kein Slug in der sentToArea-Quittung: '$actual'")
    }

    // ── (2) Ehrlicher Fallback statt verstümmeltem Slug ──────────────────────

    /**
     * Eine Area, die WEDER der Katalog NOCH die kuratierte Karte kennt: früher
     * wäre der kapitalisierte Slug gesprochen worden („Gaestezimmer_oben"), was
     * genau Andis Beschwerde in neuer Verkleidung wäre. Jetzt: ehrlich vage.
     */
    @Test
    fun `unbekannte Area - lieber vage als ein verstuemmelter Slug`() = withHa(templateBody = "8|2") { url ->
        val port = haPort(url, areaCatalog = catalogOf("wohnzimmer" to "Wohnzimmer"))
        val actual = phrase(port.execute(lightOn(area = "gaestezimmer_oben")))

        assertEquals("Licht im gewünschten Raum ist an.", actual)
        assertFalse(actual.contains("gaestezimmer"), "nie der rohe Slug: '$actual'")
        assertFalse(actual.contains("Gaestezimmer"), "nie der kapitalisierte Slug: '$actual'")
        assertFalse(actual.contains("_"), "ein Unterstrich verrät den Slug sofort: '$actual'")
    }

    /** Katalog mit leerem Label ⇒ kein Name auffindbar ⇒ derselbe ehrliche Fallback. */
    @Test
    fun `leeres Katalog-Label faellt auf die kuratierte Karte, sonst auf den vagen Satz`() =
        withHa(templateBody = "8|2") { url ->
            // kuche IST kuratiert bekannt ⇒ trotz leerem Katalog-Label „Küche".
            val known = haPort(url, areaCatalog = catalogOf("kuche" to "   "))
            assertEquals("Licht im Küche ist an.", phrase(known.execute(lightOn())))

            // Eine unbekannte Area mit leerem Label hat keinen Anker mehr ⇒ vage.
            val unknown = haPort(url, areaCatalog = catalogOf("dachboden" to "   "))
            assertEquals(
                "Licht im gewünschten Raum ist an.",
                phrase(unknown.execute(lightOn(area = "dachboden"))),
            )
        }

    /** Ein werfender Katalog darf die Quittung nicht kippen — und trotzdem nie einen Slug sprechen. */
    @Test
    fun `werfender Katalog - kuratierte Karte traegt den Namen weiter`() = withHa(templateBody = "8|2") { url ->
        val dead = AreaCatalogPort { error("Katalog kaputt") }
        val actual = phrase(haPort(url, areaCatalog = dead).execute(lightOn()))

        assertEquals("Licht im Küche ist an.", actual)
    }

    // ── (3) Der Raumname reist unübersetzt durch JEDE Sprache ────────────────

    /**
     * Je Sprache EINE Probe: der Satz drumherum ist übersetzt, der Raumname NICHT.
     * „Küche" bleibt „Küche" — auch im englischen, spanischen, französischen und
     * italienischen Satz. Nutzerdaten werden weder übersetzt noch verstümmelt.
     */
    @Test
    fun `der Raumname bleibt in JEDER Sprache woertlich Kueche`() = withHa(templateBody = "8|2") { url ->
        val port = haPort(url)
        for (language in Language.entries) {
            val expected = LanguagePackRegistry.forLanguage(language).haExecutor.lightOnArea
                .replace("{room}", "Küche")
            val actual = phrase(port.execute(lightOn(language)))

            assertEquals(expected, actual, "$language: eigener Satz, unübersetzter Raumname")
            assertTrue(actual.contains("Küche"), "$language: der Raumname darf nie übersetzt werden: '$actual'")
            assertFalse(actual.contains("Kuche"), "$language: nie der verstümmelte Slug: '$actual'")
        }
    }

    /**
     * Der ehrliche Fallback ist das GEGENTEIL: er ist KEIN Nutzerdatum, sondern
     * Hoshis eigenes Wort — er MUSS deshalb der Sprache folgen (sonst stünde ein
     * deutscher Brocken im italienischen Satz).
     */
    @Test
    fun `der vage Fallback folgt der Sprache - er ist kein Nutzerdatum`() = withHa(templateBody = "8|2") { url ->
        val port = haPort(url, areaCatalog = catalogOf("wohnzimmer" to "Wohnzimmer"))
        val seen = mutableSetOf<String>()
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language).haExecutor
            val actual = phrase(port.execute(lightOn(language, area = "dachboden")))

            assertEquals(pack.lightOnArea.replace("{room}", pack.roomFallbackName), actual, "$language")
            assertTrue(actual.contains(pack.roomFallbackName), "$language: eigenes Fallback-Wort: '$actual'")
            assertFalse(actual.contains("dachboden"), "$language: nie der Slug: '$actual'")
            seen += pack.roomFallbackName
        }
        assertEquals(
            Language.entries.size,
            seen.size,
            "jede Sprache braucht ihr EIGENES Fallback-Wort, keine Kopie",
        )
    }

    // ── (4) Der Slug bleibt die Matching-Wahrheit ────────────────────────────

    /**
     * Die Gegenprobe zur ganzen Änderung: gesprochen wird der NAME, geschaltet
     * wird über den SLUG. Ginge der Anzeigename in den Service-Call, würde HA
     * die Area nicht mehr finden — der Fix darf das Matching nicht anfassen.
     */
    @Test
    fun `im Service-Call reist weiter der SLUG, nie der Anzeigename`() {
        val body = java.util.concurrent.atomic.AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/services/") { ex ->
            body.set(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            respond(ex, 200, "[]")
        }
        server.createContext("/api/template") { ex -> respond(ex, 200, "8|2") }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val spoken = phrase(haPort(url).execute(lightOn()))

            assertTrue(body.get().contains("kuche"), "der Slug ist der Matching-Schlüssel: ${body.get()}")
            assertFalse(body.get().contains("Küche"), "der Anzeigename gehört NICHT in den Call: ${body.get()}")
            assertTrue(spoken.contains("Küche"), "gesprochen aber der Name: '$spoken'")
        } finally {
            server.stop(0)
        }
    }
}
