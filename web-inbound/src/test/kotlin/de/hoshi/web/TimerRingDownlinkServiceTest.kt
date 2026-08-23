package de.hoshi.web

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.hoshi.core.port.DeviceDownlinkPort
import de.hoshi.core.port.InMemoryScheduledItemStore
import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **TimerRingDownlinkServiceTest** — beweist den Ring-Downlink des Wecker-am-Satelliten-
 * Vertrags (PREP-wecker-am-satelliten, Scheibe 2): [TimerRingDownlinkService.onFired] pusht
 * SOFORT beim Feuern (Satellit verbunden ⇒ Frame; nicht verbunden ⇒ ehrlich aufgeben, KEIN
 * Retry-Sturm), [TimerRingDownlinkService.tick] wiederholt bis [TimerRingDownlinkService.onAck]
 * oder Timeout, und der Flag-OFF-Beweis (byte-neutral, kein Push, kein Thread).
 */
class TimerRingDownlinkServiceTest {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class LegacyTimerRingFrame(
        val type: String,
        val id: String,
        val label: String? = null,
    )

    private class FakeDownlinkPort : DeviceDownlinkPort {
        val connected = mutableSetOf<String>()
        val pushed = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun pushToDevice(satelliteId: String, frame: Map<String, Any?>): Boolean {
            if (satelliteId !in connected) return false
            pushed.add(satelliteId to frame)
            return true
        }

        override fun connectedDevices(): Set<String> = connected.toSet()
    }

    /** Verstellbarer [Clock] (Muster [NightModeServiceTest.MutableClock]) fuer Retry-/Timeout-Tests. */
    private class MutableClock(private var current: Instant) : Clock() {
        fun advanceMs(ms: Long) {
            current = current.plusMillis(ms)
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
    }

    private val t0: Instant = Instant.parse("2026-07-20T12:00:00Z")

    private fun item(
        id: String = "t1",
        label: String? = "Pizza",
        originSatelliteId: String? = "sat-kueche",
        scheduledFor: Long = t0.toEpochMilli(),
        kind: ScheduledKind = ScheduledKind.TIMER,
    ) = ScheduledItem(
        id = id,
        kind = kind,
        dueAtEpochMs = scheduledFor,
        label = label,
        originSatelliteId = originSatelliteId,
    )

    private fun service(
        downlink: FakeDownlinkPort,
        enabled: Boolean = true,
        clock: Clock = Clock.fixed(t0, ZoneOffset.UTC),
        retryIntervalMs: Long = 1_000,
        timeoutMs: Long = 5_000,
    ) = TimerRingDownlinkService(
        downlink = downlink, enabled = enabled, clock = clock,
        retryIntervalMs = retryIntervalMs, timeoutMs = timeoutMs,
    )

    // ── Flag OFF ⇒ byte-neutral ──────────────────────────────────────────────

    @Test
    fun `Flag OFF - onFired pusht nichts, ringingCount bleibt 0`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(downlink, enabled = false)

        svc.onFired(item())

        assertTrue(downlink.pushed.isEmpty(), "Flag OFF ⇒ kein Push-Versuch")
        assertEquals(0, svc.ringingCount())
    }

    @Test
    fun `Flag OFF - start startet keinen Retry-Thread`() {
        val svc = service(FakeDownlinkPort(), enabled = false)
        svc.start()
        assertFalse(svc.isRunning, "enabled=false ⇒ kein Thread")
        svc.close() // idempotent, auch ohne Start sicher
    }

    @Test
    fun `Flag ON - tick ohne Wiring ist ein No-op solange nichts klingelt`() {
        val svc = service(FakeDownlinkPort(), enabled = true)
        svc.tick() // darf nicht werfen
        assertEquals(0, svc.ringingCount())
    }

    // ── onFired: kein Ursprung bekannt ⇒ No-op (Chat/FE-Timer ringen nirgends) ──

    @Test
    fun `onFired ohne originSatelliteId ist ein No-op`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(downlink)

        svc.onFired(item(originSatelliteId = null))

        assertTrue(downlink.pushed.isEmpty(), "kein Satelliten-Ursprung ⇒ kein Push")
        assertEquals(0, svc.ringingCount())
    }

    // ── onFired: Satellit verbunden ⇒ SOFORTIGER Push, Frame-Format ──────────

    @ParameterizedTest
    @EnumSource(ScheduledKind::class)
    fun `onFired pusht scheduledFor und kind fuer jede ScheduledKind`(kind: ScheduledKind) {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(downlink)
        val scheduledFor = 1_750_000_123_456L

        svc.onFired(item(kind = kind, scheduledFor = scheduledFor))

        assertEquals(1, downlink.pushed.size, "genau ein sofortiger Push")
        val (satelliteId, frame) = downlink.pushed.single()
        assertEquals("sat-kueche", satelliteId)
        assertEquals("timer_ring", frame["type"])
        assertEquals("t1", frame["id"])
        assertEquals("Pizza", frame["label"])
        assertEquals(scheduledFor, frame["scheduledFor"])
        assertEquals(kind.name, frame["kind"])
        assertEquals(1, svc.ringingCount(), "Retry-Zustand angelegt (wartet auf Ack/Timeout)")
    }

    @Test
    fun `altes timer_ring-Schema parst das additive Frame unveraendert`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(downlink)
        svc.onFired(item(kind = ScheduledKind.REMINDER))

        val json = jacksonObjectMapper().writeValueAsString(downlink.pushed.single().second)
        val legacy = jacksonObjectMapper().readValue<LegacyTimerRingFrame>(json)

        assertEquals(LegacyTimerRingFrame(type = "timer_ring", id = "t1", label = "Pizza"), legacy)
    }

    @Test
    fun `Fire-Service reicht Zeit und Kind aus dem bestehenden ScheduledItem bis ins Frame`() {
        val scheduledFor = 1_750_000_123_456L
        val scheduled = item(kind = ScheduledKind.ALARM, scheduledFor = scheduledFor)
        val store = InMemoryScheduledItemStore().apply { set(scheduled) }
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val ring = service(downlink)

        ScheduledItemFireService(
            store = store,
            fired = InMemoryFiredItemsStore(),
            enabled = true,
            clock = Clock.fixed(Instant.ofEpochMilli(scheduledFor), ZoneOffset.UTC),
            onFired = ring::onFired,
        ).pollOnce()

        val frame = downlink.pushed.single().second
        assertEquals(scheduled.dueAtEpochMs, frame["scheduledFor"])
        assertEquals(scheduled.kind.name, frame["kind"])
    }

    @Test
    fun `onFired ohne label laesst das label-Feld im Frame weg`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val svc = service(downlink)

        svc.onFired(item(label = null))

        val (_, frame) = downlink.pushed.single()
        assertFalse(frame.containsKey("label"), "label=null ⇒ Feld fehlt im Frame")
    }

    // ── onFired: Satellit NICHT verbunden ⇒ ehrlich aufgeben, KEIN Retry-Sturm ──

    @Test
    fun `onFired mit nicht verbundenem Satelliten gibt sofort auf - kein Retry-Zustand`() {
        val downlink = FakeDownlinkPort() // niemand verbunden
        val svc = service(downlink)

        svc.onFired(item())

        assertTrue(downlink.pushed.isEmpty())
        assertEquals(0, svc.ringingCount(), "kein Retry-Zustand fuer ein totes Ziel (kein Sturm)")
    }

    // ── tick: Wiederholung bis Ack oder Timeout ──────────────────────────────

    @Test
    fun `tick wiederholt den Push erst NACH dem Retry-Intervall, nicht davor`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val clock = MutableClock(t0)
        val svc = service(downlink, clock = clock, retryIntervalMs = 1_000, timeoutMs = 10_000)
        svc.onFired(item())
        assertEquals(1, downlink.pushed.size, "der sofortige Erst-Push")

        clock.advanceMs(500) // < retryIntervalMs
        svc.tick()
        assertEquals(1, downlink.pushed.size, "vor Ablauf des Intervalls kein zweiter Push")

        clock.advanceMs(600) // jetzt insgesamt 1100ms seit dem letzten Push
        svc.tick()
        assertEquals(2, downlink.pushed.size, "nach Ablauf des Intervalls ein weiterer Push")
        assertEquals(downlink.pushed.first().second, downlink.pushed.last().second, "Retry wiederholt dasselbe Frame")
        assertEquals(1, svc.ringingCount(), "weiterhin wartend (kein Ack, kein Timeout)")
    }

    @Test
    fun `tick gibt nach Timeout ehrlich auf - FE-Pfad bleibt unberuehrt`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val clock = MutableClock(t0)
        val svc = service(downlink, clock = clock, retryIntervalMs = 1_000, timeoutMs = 3_000)
        svc.onFired(item())

        clock.advanceMs(3_500) // ueber den Timeout hinaus
        svc.tick()

        assertEquals(0, svc.ringingCount(), "Timeout ⇒ Ring-Zustand raeumt sich selbst auf")
    }

    @Test
    fun `Satellit trennt sich waehrend eines Retries - tick gibt auf statt zu spammen`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val clock = MutableClock(t0)
        val svc = service(downlink, clock = clock, retryIntervalMs = 1_000, timeoutMs = 10_000)
        svc.onFired(item())
        assertEquals(1, downlink.pushed.size)

        downlink.connected.clear() // Satellit trennt sich
        clock.advanceMs(1_500)
        svc.tick()

        assertEquals(1, downlink.pushed.size, "kein weiterer Push gegen ein getrenntes Ziel")
        assertEquals(0, svc.ringingCount(), "gibt sofort auf, statt bis zum Timeout zu spammen")
    }

    // ── onAck stoppt die Wiederholung ─────────────────────────────────────────

    @Test
    fun `onAck stoppt die Wiederholung sofort`() {
        val downlink = FakeDownlinkPort().apply { connected += "sat-kueche" }
        val clock = MutableClock(t0)
        val svc = service(downlink, clock = clock, retryIntervalMs = 1_000, timeoutMs = 10_000)
        svc.onFired(item())

        svc.onAck("t1")
        assertEquals(0, svc.ringingCount(), "Ack raeumt den Ring-Zustand sofort")

        clock.advanceMs(2_000)
        svc.tick()
        assertEquals(1, downlink.pushed.size, "nach Ack kein weiterer Push mehr")
    }

    @Test
    fun `onAck auf unbekannte oder schon gestoppte id ist ein stilles No-op`() {
        val svc = service(FakeDownlinkPort())
        svc.onAck("nie-gefeuert") // darf nicht werfen
        assertEquals(0, svc.ringingCount())
    }

    // ── Mehrere Items unabhaengig voneinander ────────────────────────────────

    @Test
    fun `zwei gleichzeitig klingelnde Items werden unabhaengig verwaltet`() {
        val downlink = FakeDownlinkPort().apply { connected += setOf("sat-kueche", "sat-buero") }
        val svc = service(downlink)

        svc.onFired(item())
        svc.onFired(item(id = "t2", label = "Meeting", originSatelliteId = "sat-buero"))

        assertEquals(2, svc.ringingCount())
        svc.onAck("t1")
        assertEquals(1, svc.ringingCount(), "nur t1 gestoppt, t2 klingelt weiter")
    }
}
