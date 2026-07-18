package de.hoshi.web

import de.hoshi.core.port.DeviceDownlinkPort
import de.hoshi.core.port.NightModeConfig
import de.hoshi.core.port.NightModeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **NightModeServiceTest** — beweist die EINE Push-Wahrheit des Nachtmodus
 * (Scheibe 2 von 3): [NightModeService.pushNow] (Push-Weg a: ws-Connect / Weg b:
 * Settings-PUT — beide rufen exakt dieselbe Methode), [NightModeService.tickOnce]
 * (Push-Weg c: Scheduler-Grenze, NUR bei Zustandswechsel, kein Spam), und den
 * Flag-OFF-Beweis (`enabled=false` ⇒ byte-neutral).
 *
 * [FakeDownlinkPort] simuliert das [DeviceDownlinkPort] ohne echten WS-Socket —
 * ein Gerät ist "verbunden", wenn es in [FakeDownlinkPort.connected] steht;
 * [FakeDownlinkPort.pushed] zeichnet jeden erfolgreichen Push auf.
 */
class NightModeServiceTest {

    private class FakeDownlinkPort : DeviceDownlinkPort {
        val connected = mutableSetOf<String>()
        val pushed = mutableListOf<Pair<String, Map<String, Any?>>>()
        var pushShouldFail = false

        override fun pushToDevice(satelliteId: String, frame: Map<String, Any?>): Boolean {
            if (satelliteId !in connected || pushShouldFail) return false
            pushed.add(satelliteId to frame)
            return true
        }

        override fun connectedDevices(): Set<String> = connected.toSet()
    }

    private fun clockAt(hour: Int, minute: Int): Clock =
        Clock.fixed(Instant.parse("2026-07-12T${"%02d".format(hour)}:${"%02d".format(minute)}:00Z"), ZoneOffset.UTC)

    /** Ein verstellbarer [Clock] fuer Tests, die eine Zeit-GRENZE ueberschreiten muessen (dieselbe Service-Instanz, also derselbe [NightModeService.lastPushedActive]-Cache). */
    private class MutableClock(hour: Int, minute: Int) : Clock() {
        private var current: Instant = Instant.parse("2026-07-12T${"%02d".format(hour)}:${"%02d".format(minute)}:00Z")
        fun setTime(hour: Int, minute: Int) {
            current = Instant.parse("2026-07-12T${"%02d".format(hour)}:${"%02d".format(minute)}:00Z")
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
    }

    private fun service(
        store: JsonFileNightModeStore,
        downlink: FakeDownlinkPort,
        enabled: Boolean = true,
        clock: Clock = clockAt(23, 0),
    ) = NightModeService(store = store, downlink = downlink, enabled = enabled, clock = clock)

    private fun store(dir: Path) = JsonFileNightModeStore(dir.resolve("night-mode.json"))

    // ── pushNow (Push-Wege a + b: ws-Connect + Settings-PUT) ─────────────────

    @Test
    fun `pushNow - verbundenes Geraet mit aktiver Config bekommt das Frame`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00", dim = 0.2))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(s, downlink, clock = clockAt(23, 0))

        assertTrue(svc.pushNow("sat-kueche"))
        assertEquals(1, downlink.pushed.size)
        val (id, frame) = downlink.pushed.single()
        assertEquals("sat-kueche", id)
        assertEquals("night_mode", frame["type"])
        assertEquals(true, frame["active"])
        assertEquals(0.2, frame["dim"])
    }

    @Test
    fun `pushNow - unkonfiguriertes Geraet bekommt den Default (enabled false, also active false)`(@TempDir dir: Path) {
        val s = store(dir)
        val downlink = FakeDownlinkPort().apply { connected += "sat-neu" }
        val svc = service(s, downlink, clock = clockAt(23, 0))

        assertTrue(svc.pushNow("sat-neu"), "Push gelingt trotz fehlender Config (Default wird verwendet)")
        assertEquals(false, downlink.pushed.single().second["active"])
    }

    @Test
    fun `pushNow - nicht verbundenes Geraet liefert false, kein Crash`(@TempDir dir: Path) {
        val s = store(dir)
        val downlink = FakeDownlinkPort() // niemand verbunden
        val svc = service(s, downlink)

        assertFalse(svc.pushNow("sat-nirgendwo"))
        assertTrue(downlink.pushed.isEmpty())
    }

    @Test
    fun `pushNow - aktualisiert lastPushed, damit ein direkt folgender Tick nicht doppelt pusht`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00"))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(s, downlink, clock = clockAt(23, 0))

        assertTrue(svc.pushNow("sat-kueche"))
        svc.tickOnce() // derselbe Zustand (23 Uhr, SCHEDULE aktiv) - darf NICHT nochmal pushen
        assertEquals(1, downlink.pushed.size, "kein redundanter Tick-Push nach frischem pushNow")
    }

    // ── tickOnce (Push-Weg c: Scheduler-Grenze, nur bei Aenderung) ───────────

    @Test
    fun `tickOnce - Zustandswechsel (22-07 Fenster, 21 zu 23 Uhr) loest nach dem Connect-Baseline genau einen weiteren Push aus`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00", dim = 0.5))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val clock = MutableClock(21, 0)
        // EINE Service-Instanz (= EIN lastPushedActive-Cache) ueber den ganzen Ablauf,
        // exakt wie in Produktion (derselbe Bean bedient Connect-Push, PUT-Push und Tick).
        val svc = NightModeService(store = s, downlink = downlink, enabled = true, clock = clock)

        // Connect-Push (Weg a): die Baseline - 21 Uhr, noch ausserhalb des Fensters.
        assertTrue(svc.pushNow("sat-kueche"))
        assertEquals(false, downlink.pushed.single().second["active"])

        svc.tickOnce() // weiterhin 21 Uhr - unveraendert seit der Baseline
        assertEquals(1, downlink.pushed.size, "kein Push, solange sich der Zustand nicht aendert")

        clock.setTime(23, 0) // Grenze ueberschritten
        svc.tickOnce()
        assertEquals(2, downlink.pushed.size, "Grenze ueberschritten - GENAU EIN weiterer Push")
        assertEquals(true, downlink.pushed.last().second["active"])

        svc.tickOnce() // weiterhin 23 Uhr - unveraendert
        assertEquals(2, downlink.pushed.size, "kein Spam bei gleichbleibendem Zustand")
    }

    @Test
    fun `tickOnce - unveraenderter Zustand ueber mehrere Ticks - kein Spam`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00"))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(s, downlink, clock = clockAt(23, 0))

        svc.tickOnce()
        svc.tickOnce()
        svc.tickOnce()
        assertEquals(1, downlink.pushed.size, "gleicher active-Zustand ueber 3 Ticks - genau EIN Push")
    }

    @Test
    fun `tickOnce - ALWAYS Modus wird NICHT getickt (aendert sich nie ohne PUT)`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.ALWAYS, dim = 0.5))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(s, downlink, clock = clockAt(23, 0))

        svc.tickOnce()
        assertTrue(downlink.pushed.isEmpty(), "ALWAYS braucht keinen Tick-Push - der PUT-Weg deckt Aenderungen ab")
    }

    @Test
    fun `tickOnce - enabled false wird NICHT getickt`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = false, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00"))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(s, downlink, clock = clockAt(23, 0))

        svc.tickOnce()
        assertTrue(downlink.pushed.isEmpty())
    }

    @Test
    fun `tickOnce - nur verbundene Geraete werden beruecksichtigt`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-offline", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00"))
        val downlink = FakeDownlinkPort() // sat-offline NICHT in connected
        val svc = service(s, downlink, clock = clockAt(23, 0))

        svc.tickOnce()
        assertTrue(downlink.pushed.isEmpty(), "nicht verbunden ⇒ kein Tick-Push")
    }

    // ── Flag OFF - byte-neutral ───────────────────────────────────────────────

    @Test
    fun `Flag OFF - pushNow liefert false, kein Push, kein Effekt`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00"))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(s, downlink, enabled = false, clock = clockAt(23, 0))

        assertFalse(svc.pushNow("sat-kueche"))
        assertTrue(downlink.pushed.isEmpty())
    }

    @Test
    fun `Flag OFF - tickOnce tut nichts`(@TempDir dir: Path) {
        val s = store(dir)
        s.set("sat-kueche", NightModeConfig(enabled = true, mode = NightModeMode.SCHEDULE, from = "22:00", to = "07:00"))
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(s, downlink, enabled = false, clock = clockAt(23, 0))

        svc.tickOnce()
        assertTrue(downlink.pushed.isEmpty())
    }

    @Test
    fun `Flag OFF - start() startet keinen Poll-Thread`(@TempDir dir: Path) {
        val s = store(dir)
        val downlink = FakeDownlinkPort()
        val svc = service(s, downlink, enabled = false)

        svc.start()
        assertFalse(svc.isRunning, "Flag OFF ⇒ kein Scheduler-Thread")
        svc.close()
    }

    @Test
    fun `Flag ON - start() startet den Poll-Thread, close() faehrt ihn wieder herunter`(@TempDir dir: Path) {
        val s = store(dir)
        val downlink = FakeDownlinkPort()
        val svc = service(s, downlink, enabled = true)

        svc.start()
        assertTrue(svc.isRunning)
        svc.close()
        assertFalse(svc.isRunning)
    }

    // ── connectedDevices delegiert an den Downlink-Port ──────────────────────

    @Test
    fun `connectedDevices spiegelt den Downlink-Port wider`(@TempDir dir: Path) {
        val s = store(dir)
        val downlink = FakeDownlinkPort().apply { connected += setOf("sat-a", "sat-b") }
        val svc = service(s, downlink)
        assertEquals(setOf("sat-a", "sat-b"), svc.connectedDevices())
    }
}
