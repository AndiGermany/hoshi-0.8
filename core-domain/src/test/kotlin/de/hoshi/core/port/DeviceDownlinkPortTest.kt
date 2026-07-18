package de.hoshi.core.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Vertrag des [DeviceDownlinkPort]-Defaults: [DeviceDownlinkPort.NOOP] ist der
 * byte-neutrale OFF-Zustand — er verbindet NIE etwas und liefert NIE ein
 * Frame aus, egal welche `satelliteId`/`frame` hereinkommen (Muster
 * [EscalationPortTest]/[TurnTracePort.NOOP]).
 */
class DeviceDownlinkPortTest {

    @Test
    fun `NOOP liefert immer false und wirft nie`() {
        assertFalse(DeviceDownlinkPort.NOOP.pushToDevice("sat-kueche", mapOf("type" to "night_mode")))
        assertFalse(DeviceDownlinkPort.NOOP.pushToDevice("", emptyMap()))
    }

    @Test
    fun `NOOP kennt nie ein verbundenes Geraet`() {
        assertEquals(emptySet<String>(), DeviceDownlinkPort.NOOP.connectedDevices())
    }

    @Test
    fun `pushToDevice-Signatur nimmt ein primitives JSON-taugliches Map, kein Jackson-Typ`() {
        // Struktureller Vertrag: der Kern bleibt frei von Jackson-databind an der API-Grenze.
        val method = DeviceDownlinkPort::class.java.getMethod(
            "pushToDevice", String::class.java, Map::class.java,
        )
        assertTrue(
            method.parameterTypes.none { it.name.startsWith("com.fasterxml.jackson") },
            "pushToDevice darf keinen Jackson-Typ in der Signatur tragen",
        )
    }
}
