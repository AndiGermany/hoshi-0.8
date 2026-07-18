package de.hoshi.web

import de.hoshi.kernel.PerimeterPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **WebConfigTest** — sichert die reine FE-Serving-Logik ohne Live-Backend:
 *
 *  1. Der SPA-Fallback-Resolver ([WebConfig.resolveSpa]): existierende statische
 *     Datei → ausliefern; `/api`/`/ws`/`/actuator` → NIE index.html; Root/unbekannt
 *     → index.html.
 *  2. Der Perimeter-Vertrag: statische UI-Pfade sind oeffentlich, der `api`-Pfad bleibt
 *     token-geschuetzt. Das gilt FLAG-UNABHAENGIG — der [PerimeterPort] liest
 *     `serve-frontend` nicht. Damit ist die Perimeter-Logik bei serve-frontend ON
 *     und OFF identisch (kein Code-Pfad weicht ab), und das FE-Serving braucht
 *     KEINE Perimeter-Aenderung.
 */
class WebConfigTest {

    // ── 1. SPA-Fallback-Resolver (resourcePath ist OHNE fuehrenden Slash) ──

    @Test
    fun `existierende statische Datei wird ausgeliefert`() {
        assertEquals(WebConfig.SpaResolution.SERVE_REQUESTED, WebConfig.resolveSpa("assets/app-abc123.js", requestedIsFile = true))
        assertEquals(WebConfig.SpaResolution.SERVE_REQUESTED, WebConfig.resolveSpa("index.html", requestedIsFile = true))
        assertEquals(WebConfig.SpaResolution.SERVE_REQUESTED, WebConfig.resolveSpa("favicon.ico", requestedIsFile = true))
        assertEquals(WebConfig.SpaResolution.SERVE_REQUESTED, WebConfig.resolveSpa("vite.svg", requestedIsFile = true))
    }

    @Test
    fun `api ws actuator fallen NIE auf index html zurueck`() {
        assertEquals(WebConfig.SpaResolution.PASS_THROUGH, WebConfig.resolveSpa("api/v1/ping", requestedIsFile = false))
        assertEquals(WebConfig.SpaResolution.PASS_THROUGH, WebConfig.resolveSpa("api/health", requestedIsFile = false))
        assertEquals(WebConfig.SpaResolution.PASS_THROUGH, WebConfig.resolveSpa("ws/audio", requestedIsFile = false))
        assertEquals(WebConfig.SpaResolution.PASS_THROUGH, WebConfig.resolveSpa("actuator/health", requestedIsFile = false))
    }

    @Test
    fun `root und unbekannte SPA-Route fallen auf index html`() {
        assertEquals(WebConfig.SpaResolution.SERVE_INDEX, WebConfig.resolveSpa("", requestedIsFile = false))
        assertEquals(WebConfig.SpaResolution.SERVE_INDEX, WebConfig.resolveSpa("/", requestedIsFile = false))
        // Client-Routing: ein nicht existierender Pfad → index.html (Browser-Reload).
        assertEquals(WebConfig.SpaResolution.SERVE_INDEX, WebConfig.resolveSpa("uebersicht", requestedIsFile = false))
        assertEquals(WebConfig.SpaResolution.SERVE_INDEX, WebConfig.resolveSpa("assets/fehlt.js", requestedIsFile = false))
    }

    // ── 2. Perimeter-Vertrag: statisch oeffentlich, /api geschuetzt (flag-unabhaengig) ──

    @Test
    fun `statische UI-Pfade sind oeffentlich`() {
        assertFalse(PerimeterPort.isProtected("/"))
        assertFalse(PerimeterPort.isProtected("/index.html"))
        assertFalse(PerimeterPort.isProtected("/assets/app-abc123.js"))
        assertFalse(PerimeterPort.isProtected("/favicon.ico"))
        assertFalse(PerimeterPort.isProtected("/vite.svg"))
    }

    @Test
    fun `api ws actuator bleiben geschuetzt`() {
        assertTrue(PerimeterPort.isProtected("/api/v1/chat/stream"))
        assertTrue(PerimeterPort.isProtected("/ws/audio"))
        assertTrue(PerimeterPort.isProtected("/actuator/health"))
        // Einzige Ausnahme innerhalb /api/: die Basis-Health bleibt oeffentlich.
        assertFalse(PerimeterPort.isProtected("/api/health"))
    }

    @Test
    fun `non-loopback ohne Token darf FE-Asset, aber nicht api`() {
        // Genau das FE-Serving-Szenario: Browser im LAN, kein Loopback, kein Token
        // beim ersten Seitenaufruf → statische Assets muessen durch, /api nicht.
        val wall = PerimeterPort(enabled = true, configuredToken = "lan-secret")
        assertInstanceOf(
            PerimeterPort.PerimeterDecision.Allow::class.java,
            wall.authorize("/assets/app-abc123.js", isLoopback = false, presentedToken = null),
        )
        assertInstanceOf(
            PerimeterPort.PerimeterDecision.Allow::class.java,
            wall.authorize("/", isLoopback = false, presentedToken = null),
        )
        assertInstanceOf(
            PerimeterPort.PerimeterDecision.Unauthorized::class.java,
            wall.authorize("/api/v1/chat/stream", isLoopback = false, presentedToken = null),
        )
    }
}
