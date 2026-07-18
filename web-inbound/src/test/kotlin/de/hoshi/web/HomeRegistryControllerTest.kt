package de.hoshi.web

import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * **HomeRegistryControllerTest** — der Lese-Vertrag von
 * `GET /api/v1/home/registry`, OHNE Spring-Context und OHNE Live-HA: der
 * Controller wird direkt konstruiert (Muster [WeatherTodayControllerTest]),
 * ein JDK-HttpServer spielt HA (via [HaHomeRegistryAdapter]).
 *
 * Vertrag: `HOSHI_HA_ENABLED=false` (Default) ⇒ ehrlich 404 `home-registry-off`,
 * KEIN HA-Call. `true` + erreichbares HA ⇒ 200 mit dem geparsten Snapshot.
 * `true` + HA nie erreichbar (kein Vorerfolg) ⇒ ehrlich 502
 * `home-registry-unreachable` — NIE erfundene Räume/Geräte. Die
 * Perimeter-Wand (401 ohne Token) deckt [PerimeterWallTest] für ALLE
 * `/api/v1`-Pfade generisch ab.
 */
class HomeRegistryControllerTest {

    private val templateBody =
        "wohnzimmer::Wohnzimmer" +
            "@@ENTITIES@@" +
            "light.wohnzimmer_deckenlampe::wohnzimmer::Deckenlampe::"

    private fun withHa(status: Int = 200, body: String = templateBody, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
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

    private fun controller(baseUrl: String, haEnabled: Boolean, token: String? = "secret-token") =
        HomeRegistryController(
            adapter = HaHomeRegistryAdapter(baseUrl = baseUrl, token = token),
            haEnabled = haEnabled,
        )

    @Test
    fun `HOSHI_HA_ENABLED aus - ehrlich 404 home-registry-off, KEIN HA-Call`() = withHa { url ->
        val res = controller(url, haEnabled = false).registry()

        assertEquals(404, res.statusCode.value())
        val err = res.body as SettingsError
        assertEquals("home-registry-off", err.error)
        assertEquals(HomeRegistryController.FEATURE_ID, err.id)
    }

    @Test
    fun `200 mit dem geparsten Snapshot wenn HA erreichbar ist`() = withHa { url ->
        val res = controller(url, haEnabled = true).registry()

        assertEquals(200, res.statusCode.value())
        val body = res.body as de.hoshi.adapters.ha.HomeRegistrySnapshot
        assertEquals(1, body.areas.size)
        assertEquals("wohnzimmer", body.areas[0].areaId)
        assertEquals(1, body.areas[0].entities.size)
        assertEquals("light.wohnzimmer_deckenlampe", body.areas[0].entities[0].entityId)
    }

    @Test
    fun `HA nie erreichbar - ehrlich 502 home-registry-unreachable statt Fake-Raeumen`() {
        // Port, auf dem nichts lauscht → connection refused → nie geladen → ehrlich 502.
        val res = controller("http://127.0.0.1:1", haEnabled = true).registry()

        assertEquals(502, res.statusCode.value())
        val err = res.body as SettingsError
        assertEquals("home-registry-unreachable", err.error)
        assertEquals(HomeRegistryController.FEATURE_ID, err.id)
    }

    @Test
    fun `Kein Token - ehrlich 502 statt Fake-Raeumen`() = withHa { url ->
        val res = controller(url, haEnabled = true, token = null).registry()

        assertEquals(502, res.statusCode.value())
        assertEquals("home-registry-unreachable", (res.body as SettingsError).error)
    }

    @Test
    fun `Adapter registry gibt null zurueck wenn nie erfolgreich geladen wurde`() {
        val adapter = HaHomeRegistryAdapter(baseUrl = "http://127.0.0.1:1", token = "secret-token", timeoutMs = 1000)
        assertNull(adapter.registry())
    }
}
