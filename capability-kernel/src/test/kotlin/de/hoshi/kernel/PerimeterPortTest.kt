package de.hoshi.kernel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerimeterPortTest {

    private val token = "s3cr3t-token"
    private val port = PerimeterPort(enabled = true, configuredToken = token)

    @Test
    fun `api health ist nicht geschuetzt`() {
        assertFalse(PerimeterPort.isProtected("/api/health"))
        // ...aber alles andere unter /api/ schon.
        assertTrue(PerimeterPort.isProtected("/api/v1/speakers"))
        assertTrue(PerimeterPort.isProtected("/ws/audio"))
        assertTrue(PerimeterPort.isProtected("/actuator/health"))
        assertFalse(PerimeterPort.isProtected("/index.html"))
    }

    @Test
    fun `geschuetzter Pfad ohne Token von remote ist UNAUTHORIZED`() {
        val decision = port.authorize("/api/v1/speakers", isLoopback = false, presentedToken = null)
        assertInstanceOf(PerimeterPort.PerimeterDecision.Unauthorized::class.java, decision)
    }

    @Test
    fun `geschuetzter Pfad mit korrektem Token ist ALLOW`() {
        val decision = port.authorize("/api/v1/speakers", isLoopback = false, presentedToken = token)
        assertInstanceOf(PerimeterPort.PerimeterDecision.Allow::class.java, decision)
    }

    @Test
    fun `falscher Token ist UNAUTHORIZED`() {
        val decision = port.authorize("/api/v1/speakers", isLoopback = false, presentedToken = "wrong")
        assertInstanceOf(PerimeterPort.PerimeterDecision.Unauthorized::class.java, decision)
    }

    @Test
    fun `Loopback ohne Token ist immer ALLOW`() {
        val decision = port.authorize("/api/v1/speakers", isLoopback = true, presentedToken = null)
        assertInstanceOf(PerimeterPort.PerimeterDecision.Allow::class.java, decision)
    }

    @Test
    fun `leerer konfigurierter Token plus enabled ist fail-closed`() {
        val failClosed = PerimeterPort(enabled = true, configuredToken = "")
        // Health bleibt erreichbar...
        assertInstanceOf(
            PerimeterPort.PerimeterDecision.Allow::class.java,
            failClosed.authorize("/api/health", isLoopback = false, presentedToken = null),
        )
        // ...aber jeder geschützte Remote-Pfad ist dicht, selbst mit beliebigem Token.
        assertInstanceOf(
            PerimeterPort.PerimeterDecision.Unauthorized::class.java,
            failClosed.authorize("/api/v1/speakers", isLoopback = false, presentedToken = "irgendwas"),
        )
        assertInstanceOf(
            PerimeterPort.PerimeterDecision.Unauthorized::class.java,
            failClosed.authorize("/ws/audio", isLoopback = false, presentedToken = null),
        )
    }

    @Test
    fun `enabled false ist No-op (alles ALLOW)`() {
        val off = PerimeterPort(enabled = false, configuredToken = "")
        assertInstanceOf(
            PerimeterPort.PerimeterDecision.Allow::class.java,
            off.authorize("/api/v1/speakers", isLoopback = false, presentedToken = null),
        )
    }
}
