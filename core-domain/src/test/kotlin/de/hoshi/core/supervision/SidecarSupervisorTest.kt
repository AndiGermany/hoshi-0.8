package de.hoshi.core.supervision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Supervisor-Verhalten OHNE Live-Naht: ein Fake-[SidecarPort] skriptet die Health je
 * Name. Beweist die ehrliche OK/DEGRADED/DOWN-Trennung, die Restart-nötig-Logik und
 * dass der RAM-Arbiter einen zweiten Brain-Start verweigert.
 */
class SidecarSupervisorTest {

    private val registry = SidecarRegistry(
        listOf(
            SidecarSpec("brain(e4b)", "http://x:8041", ramCostMb = 8000, brainGated = true),
            SidecarSpec("whisper-stt", "http://x:9001", ramCostMb = 1600),
            SidecarSpec("voxtral-tts", "http://x:8042", ramCostMb = 3000),
        ),
    )

    /** Fake-Probe: liefert die in [scripted] hinterlegte Health je Sidecar-Name. */
    private fun probe(scripted: Map<String, SidecarHealth>) = SidecarPort { spec ->
        scripted[spec.name] ?: SidecarHealth.down("kein Skript")
    }

    private val plentyRam = MemorySnapshot(totalMb = 16000, availableMb = 12000)

    @Test
    fun `OK DEGRADED DOWN werden ehrlich getrennt`() {
        val sup = SidecarSupervisor(
            registry,
            probe(
                mapOf(
                    "brain(e4b)" to SidecarHealth.ok("status=ok", "gemma-4-e4b-it-4bit"),
                    "whisper-stt" to SidecarHealth.degraded("status=loading"),
                    "voxtral-tts" to SidecarHealth.down("connection refused"),
                ),
            ),
            DefaultRamBudget(),
        )

        val report = sup.inspect(plentyRam)

        assertEquals(HealthState.OK, report.sidecars.first { it.name == "brain(e4b)" }.health.state)
        assertEquals(HealthState.DEGRADED, report.sidecars.first { it.name == "whisper-stt" }.health.state)
        assertEquals(HealthState.DOWN, report.sidecars.first { it.name == "voxtral-tts" }.health.state)
        assertEquals(1, report.okCount)
        assertEquals(1, report.degradedCount)
        assertEquals(1, report.downCount)
        // DOWN dominiert: Exit 3, nicht 2.
        assertEquals(3, report.exitCode)
    }

    @Test
    fun `loading ist DEGRADED und triggert KEINEN Restart, DOWN schon`() {
        val sup = SidecarSupervisor(
            registry,
            probe(
                mapOf(
                    "brain(e4b)" to SidecarHealth.ok("ok"),
                    "whisper-stt" to SidecarHealth.degraded("status=loading"),
                    "voxtral-tts" to SidecarHealth.down("timeout"),
                ),
            ),
            DefaultRamBudget(),
        )

        val report = sup.inspect(plentyRam)

        // Warmup nicht abwürgen: DEGRADED → kein Restart.
        assertFalse(report.sidecars.first { it.name == "whisper-stt" }.restartNeeded)
        assertNull(report.sidecars.first { it.name == "whisper-stt" }.restartPlan)
        // DOWN → Restart nötig, Plan vorhanden.
        val down = report.sidecars.first { it.name == "voxtral-tts" }
        assertTrue(down.restartNeeded)
        assertNotNull(down.restartPlan)
        assertEquals(1, report.restartNeededCount)
    }

    @Test
    fun `Restart-Effekt ist GATED - kein echter Prozess-Eingriff`() {
        val sup = SidecarSupervisor(
            registry,
            probe(mapOf("voxtral-tts" to SidecarHealth.down("dead"))),
            DefaultRamBudget(),
        )
        val report = sup.inspect(plentyRam)
        val outcomes = sup.requestRestarts(report)

        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { !it.executed }, "Default-RestartPort darf NIE ausführen")
        assertTrue(outcomes.all { it.note.contains("GATED") })
    }

    @Test
    fun `RAM-Arbiter verweigert zweiten Brain wenn e4b schon resident`() {
        val twoBrains = SidecarRegistry(
            listOf(
                SidecarSpec("brain(e4b)", "http://x:8041", ramCostMb = 8000, brainGated = true),
                SidecarSpec("brain(12b)", "http://x:8043", ramCostMb = 12000, brainGated = true),
            ),
        )
        val sup = SidecarSupervisor(
            twoBrains,
            probe(
                mapOf(
                    "brain(e4b)" to SidecarHealth.ok("resident"),
                    // 12b ist DOWN (will starten), aber e4b belegt den Slot.
                    "brain(12b)" to SidecarHealth.down("not started"),
                ),
            ),
            // RAM im Überfluss — die Verweigerung kommt NUR aus der Brain-Slot-Invariante.
            DefaultRamBudget(headroomMb = 0),
        )

        val report = sup.inspect(MemorySnapshot(totalMb = 64000, availableMb = 60000))

        val twelveB = report.sidecars.first { it.name == "brain(12b)" }
        assertFalse(twelveB.ramVerdict.permitted, "zweiter Brain darf trotz freiem RAM NICHT starten")
        assertTrue(twelveB.ramVerdict.reason.contains("Brain-Slot"))
        // e4b selbst ist resident → für ihn ist kein ANDERER Brain im Weg.
        assertTrue(report.sidecars.first { it.name == "brain(e4b)" }.ramVerdict.permitted)
    }

    @Test
    fun `alle OK ergibt Exit 0`() {
        val sup = SidecarSupervisor(
            registry,
            probe(
                mapOf(
                    "brain(e4b)" to SidecarHealth.ok("ok"),
                    "whisper-stt" to SidecarHealth.ok("ok"),
                    "voxtral-tts" to SidecarHealth.ok("ok"),
                ),
            ),
            DefaultRamBudget(),
        )
        val report = sup.inspect(plentyRam)
        assertEquals(0, report.exitCode)
        assertEquals(0, report.restartNeededCount)
    }
}
