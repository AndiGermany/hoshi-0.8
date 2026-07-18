package de.hoshi.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityKernelTest {

    private val kernel = CapabilityKernel()

    @Test
    fun `default deny - unbekannte Aktion wird verweigert`() {
        // lock.unlock steht bewusst NICHT auf der Allowlist.
        val decision = kernel.permit("lock", "unlock", entityId = "lock.haustuer", data = emptyMap())

        val deny = assertInstanceOf(CapabilityKernel.Decision.Deny::class.java, decision)
        assertTrue(deny.reason.contains("nicht freigegeben"), "Grund war: ${deny.reason}")
    }

    @Test
    fun `erlaubte Aktion wird gewährt und liefert normalisierte data`() {
        val decision = kernel.permit(
            domain = "light",
            service = "turn_on",
            entityId = "light.wohnzimmer",
            data = mapOf("brightness_pct" to 60, "transition" to 2),
        )

        val grant = assertInstanceOf(CapabilityKernel.Decision.Grant::class.java, decision)
        assertEquals(mapOf("brightness_pct" to 60, "transition" to 2), grant.normalizedData)
    }

    @Test
    fun `nicht erlaubter data-Key wird verweigert (kein Smuggle)`() {
        val decision = kernel.permit(
            domain = "light",
            service = "turn_on",
            entityId = "light.wohnzimmer",
            data = mapOf("brightness_pct" to 60, "evil" to "rm -rf"),
        )
        val deny = assertInstanceOf(CapabilityKernel.Decision.Deny::class.java, decision)
        assertTrue(deny.reason.contains("evil"), "Grund war: ${deny.reason}")
    }

    @Test
    fun `Range-Verletzung wird verweigert (kein Heizungs-GAU)`() {
        val decision = kernel.permit(
            domain = "climate",
            service = "set_temperature",
            entityId = "climate.flur",
            data = mapOf("temperature" to 50),
        )
        assertInstanceOf(CapabilityKernel.Decision.Deny::class.java, decision)
    }

    @Test
    fun `WriteEffectingTool ohne Grant wird verweigert`() {
        val tool = object : WriteEffectingTool {
            override val domain = "alarm_control_panel"
            override val service = "disarm"
            override val entityId = "alarm_control_panel.home"
            override val data = emptyMap<String, Any?>()
        }
        val decision = kernel.permit(tool)

        val deny = assertInstanceOf(CapabilityKernel.Decision.Deny::class.java, decision)
        assertTrue(deny.reason.contains("nicht freigegeben"), "Grund war: ${deny.reason}")
    }

    @Test
    fun `enabled false ist Hard-Deny - nicht alles erlaubt`() {
        val offKernel = CapabilityKernel(CapabilityPolicy(enabled = false))
        // light.turn_on steht auf der Default-Allowlist, MUSS aber bei enabled=false denied sein.
        val decision = offKernel.permit("light", "turn_on", "light.wohnzimmer", emptyMap())
        assertInstanceOf(CapabilityKernel.Decision.Deny::class.java, decision)
    }

    @Test
    fun `Slash-Injection in domain wird verweigert`() {
        val decision = kernel.permit("light/../lock", "unlock", "lock.haustuer", emptyMap())
        val deny = assertInstanceOf(CapabilityKernel.Decision.Deny::class.java, decision)
        assertEquals(CapabilityKernel.PHRASE_INVALID, deny.phrase)
    }
}
