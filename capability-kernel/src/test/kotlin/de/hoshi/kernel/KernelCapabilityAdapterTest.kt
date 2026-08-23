package de.hoshi.kernel

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.DeterministicToolIntentClassifier
import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die Naht [KernelCapabilityAdapter]: ToolCall → Kernel-Permit →
 * [GateDecision]. Belegt zusätzlich, dass die [DeterministicToolIntentClassifier]-
 * ToolCalls gegen `CapabilityKernel.DEFAULT_PERMITS` GRANTEN (Happy-Path).
 */
class KernelCapabilityAdapterTest {

    private val adapter = KernelCapabilityAdapter(CapabilityKernel())
    private val classifier = DeterministicToolIntentClassifier()

    @Test
    fun `erlaubtes Tool aus DEFAULT_PERMITS ergibt Grant`() {
        val decision = adapter.check(
            ToolCall("light", "turn_on", "light.wohnzimmer", mapOf("brightness_pct" to 60)),
        )
        val grant = assertInstanceOf(GateDecision.Grant::class.java, decision)
        assertEquals(60, grant.normalizedData["brightness_pct"])
    }

    @Test
    fun `nicht gelistetes Tool lock unlock ergibt Deny`() {
        val decision = adapter.check(ToolCall("lock", "unlock", "lock.haustuer"))
        val deny = assertInstanceOf(GateDecision.Deny::class.java, decision)
        assertTrue(deny.reason.contains("nicht freigegeben"), "Grund war: ${deny.reason}")
    }

    @Test
    fun `klassifizierte Befehle granten gegen DEFAULT_PERMITS`() {
        // Alle mit GENANNTEM Raum — seit F2/S2 traegt nur ein solcher Befehl ein
        // area_id (kein geratener Default mehr), und nur ein getargeteter Call grantet.
        val commands = listOf(
            classifier.classify("Licht in der Küche an", Language.DE),
            classifier.classify("mach das Licht im Wohnzimmer aus", Language.DE),
            classifier.classify("dimm das Wohnzimmer auf 30 Prozent", Language.DE),
            classifier.classify("turn on the kitchen light", Language.EN),
        )
        commands.forEachIndexed { i, call ->
            requireNotNull(call) { "Befehl #$i wurde nicht klassifiziert" }
            assertInstanceOf(
                GateDecision.Grant::class.java,
                adapter.check(call),
                "ToolCall #$i ($call) sollte granten",
            )
        }
    }

    /**
     * Das zweite Netz unter F2/S2: ein roomless Licht-Befehl traegt seit dem Fall des
     * „wohnzimmer"-Hardcodes KEIN Target — der Kernel denyt ihn, weil targetlos „alle
     * Lichter der Domain" hiesse. Der Orchestrator laesst es nie so weit kommen (er
     * fragt nach dem Raum); dieser Test haelt die Rueckfallebene fest.
     */
    @Test
    fun `roomless Licht-Befehl ist targetlos und wird verweigert`() {
        val call = requireNotNull(classifier.classify("mach das Licht aus", Language.DE))
        assertTrue(call.data["area_id"] == null, "roomless ⇒ kein area_id, war: ${call.data}")
        val deny = assertInstanceOf(GateDecision.Deny::class.java, adapter.check(call))
        assertTrue(deny.reason.contains("entity_id erforderlich"), "Grund war: ${deny.reason}")
    }
}
