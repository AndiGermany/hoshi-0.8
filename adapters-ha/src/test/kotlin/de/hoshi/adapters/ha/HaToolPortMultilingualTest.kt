package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * **HaToolPortMultilingualTest** — der Beweis, dass die Quittungen des ECHTEN
 * HA-Executors der Turn-Sprache folgen (Andi 2026-07-25: „Smart-Home-Bestätigungen
 * … es soll multilingual werden. von A-Z").
 *
 * Wichtig fürs Verständnis der Naht: [HaToolPort] ist ein langlebiger Singleton,
 * die Sprache dagegen per Turn wählbar — sie reist deshalb auf dem
 * [ToolCall.language] mit, nicht im Konstruktor und nicht in einem globalen
 * Zustand. Genau dieser Weg wird hier geprüft.
 *
 * Drei Zusicherungen:
 *  1. **DE ist byte-identisch** — die deutschen Quittungen stehen wörtlich wie im
 *     Bestand (der Rest der DE-Erwartungen liegt unverändert in [HaToolPortTest]).
 *  2. **Jede Sprache spricht sich selbst** — Ok/NoEffect/Failed je Sprache.
 *  3. **HA-Raumnamen bleiben unübersetzt** — in JEDER Sprache.
 *
 * Fake-HA wie in [HaToolPortTest] (JDK-HttpServer), KEIN Call an echtes HA.
 */
class HaToolPortMultilingualTest {

    private fun withHa(
        serviceStatus: Int = 200,
        templateBodies: List<String> = listOf("8|2"),
        block: (url: String) -> Unit,
    ) {
        val idx = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/services/") { ex -> respond(ex, serviceStatus, "[]") }
        server.createContext("/api/template") { ex ->
            respond(ex, 200, templateBodies[idx.getAndIncrement().coerceAtMost(templateBodies.size - 1)])
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** Kleine Poll-Budgets — die Suite darf nicht real sekundenlang schlafen. */
    private fun haPort(
        url: String,
        token: String = "secret-token",
        areaCatalog: AreaCatalogPort = AreaCatalogPort.STATIC,
    ) = HaToolPort(
        baseUrl = url,
        token = token,
        readbackSettleMs = 300,
        readbackPollIntervalMs = 50,
        climateReadbackSettleMs = 200,
        areaCatalog = areaCatalog,
    )

    private fun lightOn(language: Language) = ToolCall(
        domain = "light", service = "turn_on", entityId = null,
        data = mapOf("area_id" to "kuche", "brightness_pct" to 40),
        language = language,
    )

    private fun lightOff(language: Language) = ToolCall(
        domain = "light", service = "turn_off", entityId = null,
        data = mapOf("area_id" to "kuche"),
        language = language,
    )

    private fun climateSet(language: Language) = ToolCall(
        domain = "climate", service = "set_temperature", entityId = null,
        data = mapOf("area_id" to "badezimmer", "temperature" to 21),
        language = language,
    )

    private fun readTemp(language: Language, area: String? = "wohnzimmer") = ToolCall(
        domain = "sensor", service = "read_temperature", entityId = null,
        data = if (area != null) mapOf("area_id" to area) else emptyMap(),
        read = true,
        language = language,
    )

    private fun phrase(result: ToolResult): String = when (result) {
        is ToolResult.Ok -> result.phrase
        is ToolResult.NoEffect -> result.phrase
        is ToolResult.Failed -> result.phrase
    }

    // ── (1) DE byte-identisch ────────────────────────────────────────────────

    /**
     * Der Byte-Anker für den Executor: OHNE gesetzte Sprache (Default DE) UND mit
     * explizitem [Language.DE] fallen exakt die bisherigen deutschen Sätze.
     */
    @Test
    fun `DE bleibt byte-identisch - mit und ohne gesetzte Sprache`() =
        withHa(templateBodies = listOf("8|2")) { url ->
            val port = haPort(url)
            val ohneSprache = ToolCall(
                domain = "light", service = "turn_on", entityId = null,
                data = mapOf("area_id" to "kuche", "brightness_pct" to 40),
            )
            assertEquals("Licht im Küche ist an.", phrase(port.execute(ohneSprache)))
            assertEquals("Licht im Küche ist an.", phrase(port.execute(lightOn(Language.DE))))
        }

    @Test
    fun `DE-Klima- und Temperatur-Quittungen bleiben woertlich`() {
        withHa(templateBodies = listOf("1", "21")) { url ->
            assertEquals("Heizung im Badezimmer auf 21 Grad.", phrase(haPort(url).execute(climateSet(Language.DE))))
        }
        withHa(templateBodies = listOf("21.5")) { url ->
            assertEquals("Im Wohnzimmer sind es gerade 21,5 Grad.", phrase(haPort(url).execute(readTemp(Language.DE))))
        }
        withHa(templateBodies = listOf("21.3")) { url ->
            assertEquals(
                "Im Haus sind es gerade durchschnittlich 21,3 Grad.",
                phrase(haPort(url).execute(readTemp(Language.DE, area = null))),
            )
        }
    }

    /** Der HA-Executor-Pack von DE ist byte-gleich zu den früheren Literalen der Adapter-Datei. */
    @Test
    fun `DE-Executor-Pack traegt woertlich die frueheren Adapter-Literale`() {
        val de = LangDe.HA_EXECUTOR
        assertEquals("Licht im {room} ist an.", de.lightOnArea)
        assertEquals("Licht im {room} ist aus.", de.lightOffArea)
        assertEquals("Im {room} ist das Licht schon an.", de.lightAlreadyOnArea)
        assertEquals("Im {room} hab ich gar keine Lampen gefunden.", de.noLightsInArea)
        assertEquals("Heizung im {room} auf {value} Grad.", de.climateSetArea)
        assertEquals("Ist erledigt — ich hab's an die Geräte im {room} geschickt.", de.sentToArea)
        assertEquals("Ist erledigt — ich hab's an Home Assistant geschickt.", de.sentToHome)
        assertEquals("Hat nicht geklappt — Home Assistant hat gerade nicht reagiert.", de.failed)
        assertEquals("Dafür hab ich gerade keinen Wert.", de.noValue)
        assertEquals("Im {room} sind es gerade {value} Grad.", de.temperatureInArea)
        assertEquals("Im Haus sind es gerade durchschnittlich {value} Grad.", de.temperatureHouseAverage)
        assertEquals(",", de.decimalSeparator)
    }

    // ── (2) Jede Sprache spricht sich selbst ─────────────────────────────────

    /** Erfolgs-Quittung (verifizierter Readback) je Sprache — eigener Satz, nie der deutsche. */
    @Test
    fun `Licht-an-Quittung folgt der Sprache`() = withHa(templateBodies = listOf("8|2")) { url ->
        val port = haPort(url)
        for (language in Language.entries) {
            val expected = LanguagePackRegistry.forLanguage(language).haExecutor.lightOnArea
                .replace("{room}", "Küche")
            assertEquals(expected, phrase(port.execute(lightOn(language))), "$language: eigene Quittung erwartet")
            if (language != Language.DE) {
                assertNotDe(phrase(port.execute(lightOn(language))), LangDe.HA_EXECUTOR.lightOnArea, language)
            }
        }
    }

    /** Licht-AUS-Quittung je Sprache. */
    @Test
    fun `Licht-aus-Quittung folgt der Sprache`() = withHa(templateBodies = listOf("8|0")) { url ->
        val port = haPort(url)
        for (language in Language.entries) {
            val expected = LanguagePackRegistry.forLanguage(language).haExecutor.lightOffArea
                .replace("{room}", "Küche")
            assertEquals(expected, phrase(port.execute(lightOff(language))), "$language: eigene Quittung erwartet")
        }
    }

    /** Der ehrliche NoEffect („nichts ging an") inkl. Offline-Zähler je Sprache. */
    @Test
    fun `NoEffect mit Offline-Zaehler folgt der Sprache und nennt die Zahl`() =
        withHa(templateBodies = listOf("4|0|4")) { url ->
            val port = haPort(url)
            for (language in Language.entries) {
                val pack = LanguagePackRegistry.forLanguage(language).haExecutor
                val expected = pack.lightNoneWentOn.replace("{room}", "Küche") +
                    pack.offlineHintCount.replace("{count}", "4")
                val actual = phrase(port.execute(lightOn(language)))
                assertEquals(expected, actual, "$language: NoEffect-Satz erwartet")
                assertTrue(actual.contains("4"), "$language: Offline-Zahl muss genannt werden: '$actual'")
            }
        }

    /** Kein Token ⇒ ehrlich nichts getan — auch das in der Turn-Sprache. */
    @Test
    fun `kein-Token-Ehrlichkeit folgt der Sprache`() = withHa { url ->
        for (language in Language.entries) {
            val port = haPort(url, token = "   ")
            val expected = LanguagePackRegistry.forLanguage(language).haExecutor.noToken
            assertEquals(expected, phrase(port.execute(lightOn(language))))
            val expectedRead = LanguagePackRegistry.forLanguage(language).haExecutor.noTokenTemperature
            assertEquals(expectedRead, phrase(port.execute(readTemp(language))))
        }
    }

    /** HA antwortet 500 ⇒ warmes Failed in der Turn-Sprache (nie ein Throw, nie Deutsch für alle). */
    @Test
    fun `Failed-Phrase folgt der Sprache`() = withHa(serviceStatus = 500) { url ->
        val port = haPort(url)
        for (language in Language.entries) {
            val result = port.execute(lightOn(language))
            assertTrue(result is ToolResult.Failed, "$language: 500 muss Failed liefern, war $result")
            assertEquals(LanguagePackRegistry.forLanguage(language).haExecutor.failed, phrase(result))
        }
    }

    /** Klima ohne Thermostat: ehrliche Absage VOR jedem Service-Call, in der Turn-Sprache. */
    @Test
    fun `Klima-ohne-Thermostat folgt der Sprache und nennt das Area-Label`() =
        withHa(templateBodies = listOf("0")) { url ->
            val port = haPort(url)
            for (language in Language.entries) {
                val expected = LanguagePackRegistry.forLanguage(language).haExecutor.noThermostatInArea
                    .replace("{room}", "Badezimmer")
                val actual = phrase(port.execute(climateSet(language)))
                assertEquals(expected, actual, "$language: eigener Satz erwartet")
                assertTrue(actual.contains("Badezimmer"), "$language: Area-Label unübersetzt: '$actual'")
            }
        }

    /**
     * Der Dezimal-Trenner ist Sprachsache, nicht Formatierungs-Laune: DE/ES/FR/IT
     * sprechen „21,5", Englisch „21.5". Ein hörbarer Unterschied — deshalb geprüft.
     */
    @Test
    fun `Temperatur-Dezimaltrenner folgt der Sprache`() = withHa(templateBodies = listOf("21.5")) { url ->
        val port = haPort(url)
        assertTrue(phrase(port.execute(readTemp(Language.DE))).contains("21,5"), "DE spricht Komma")
        assertTrue(phrase(port.execute(readTemp(Language.EN))).contains("21.5"), "EN spricht Punkt")
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            assertTrue(
                phrase(port.execute(readTemp(language))).contains("21,5"),
                "$language spricht Komma",
            )
        }
    }

    /** Ganze Werte bleiben in jeder Sprache ohne Nachkomma (21.0 → „21"). */
    @Test
    fun `ganze Temperaturwerte bleiben in jeder Sprache ohne Nachkomma`() =
        withHa(templateBodies = listOf("20.0")) { url ->
            val port = haPort(url)
            for (language in Language.entries) {
                val actual = phrase(port.execute(readTemp(language)))
                assertTrue(actual.contains("20"), "$language: Wert erwartet: '$actual'")
                assertFalse(
                    actual.contains("20,0") || actual.contains("20.0"),
                    "$language: ganze Werte ohne Nachkomma erwartet: '$actual'",
                )
            }
        }

    /** Scene/kein Readback ⇒ die „geschickt"-Phrase — ebenfalls je Sprache. */
    @Test
    fun `an-HA-geschickt-Phrase folgt der Sprache`() = withHa { url ->
        val port = haPort(url)
        for (language in Language.entries) {
            val call = ToolCall(
                domain = "scene", service = "turn_on", entityId = "scene.abend",
                data = emptyMap(), language = language,
            )
            assertEquals(
                LanguagePackRegistry.forLanguage(language).haExecutor.sentToHome,
                phrase(port.execute(call)),
                "$language: eigene Quittung erwartet",
            )
        }
    }

    // ── (3) Raumnamen sind Nutzerdaten ───────────────────────────────────────

    /**
     * **Die eiserne Regel am Executor:** der HA-Area-Name steht in JEDER Sprache
     * wörtlich im Satz. Geprüft mit einem Namen, den keine Übersetzung kennt
     * (`Hobbyraum`) — hätte irgendwo ein übersetzter Raumbegriff Einzug gehalten,
     * fiele es hier auf.
     *
     * Der Name kommt seit 2026-08-22 aus der Area-Registry (`hobbyraum` ⇒
     * `Hobbyraum`) statt aus dem rohen Slug: gesprochen wird der ANZEIGENAME.
     * Die Regel selbst ist dieselbe geblieben — er wird nur eingesetzt, nie
     * übersetzt.
     */
    @Test
    fun `HA-Raumname bleibt in JEDER Sprache woertlich im Satz`() =
        withHa(templateBodies = listOf("8|2")) { url ->
            val port = haPort(
                url,
                areaCatalog = AreaCatalogPort { listOf(AreaInfo(areaId = "hobbyraum", label = "Hobbyraum")) },
            )
            for (language in Language.entries) {
                val call = ToolCall(
                    domain = "light", service = "turn_on", entityId = null,
                    data = mapOf("area_id" to "hobbyraum", "brightness_pct" to 40),
                    language = language,
                )
                val actual = phrase(port.execute(call))
                assertTrue(actual.contains("Hobbyraum"), "$language: Raumname muss stehen bleiben: '$actual'")
                assertFalse(actual.contains("{"), "$language: ungefüllter Platzhalter: '$actual'")
            }
        }

    /** Kein Pack darf einen Platzhalter vergessen oder einen leeren Satz tragen. */
    @Test
    fun `jede Sprache hat ein vollstaendiges HA-Executor-Pack`() {
        for (language in Language.entries) {
            val p = LanguagePackRegistry.forLanguage(language).haExecutor
            val roomBearing = listOf(
                "noThermostatInArea" to p.noThermostatInArea,
                "lightOffArea" to p.lightOffArea,
                "lightSomeStillOn" to p.lightSomeStillOn,
                "noLightsInArea" to p.noLightsInArea,
                "lightOnArea" to p.lightOnArea,
                "lightAlreadyOnArea" to p.lightAlreadyOnArea,
                "lightNothingNewOn" to p.lightNothingNewOn,
                "lightNoneWentOn" to p.lightNoneWentOn,
                "climateSetArea" to p.climateSetArea,
                "sentToArea" to p.sentToArea,
                "temperatureInArea" to p.temperatureInArea,
            )
            for ((name, text) in roomBearing) {
                assertTrue(text.contains("{room}"), "$language.$name: {room}-Slot fehlt — Raumname ginge verloren")
            }
            assertTrue(p.climateSetArea.contains("{value}"), "$language: climateSetArea braucht {value}")
            assertTrue(p.temperatureInArea.contains("{value}"), "$language: temperatureInArea braucht {value}")
            assertTrue(p.temperatureHouseAverage.contains("{value}"), "$language: Haus-Schnitt braucht {value}")
            assertTrue(p.offlineHintCount.contains("{count}"), "$language: Offline-Hinweis braucht {count}")
            assertTrue(p.decimalSeparator.isNotEmpty(), "$language: Dezimaltrenner darf nicht leer sein")
            for (text in listOf(p.noToken, p.noTokenTemperature, p.failed, p.noValue, p.climateNotYet, p.sentToHome)) {
                assertTrue(text.isNotBlank(), "$language: keine leere Executor-Phrase")
            }
        }
    }

    private fun assertNotDe(actual: String, deTemplate: String, language: Language) {
        assertFalse(
            actual == deTemplate.replace("{room}", "Küche"),
            "$language: darf nicht mehr den deutschen Satz liefern: '$actual'",
        )
    }
}
