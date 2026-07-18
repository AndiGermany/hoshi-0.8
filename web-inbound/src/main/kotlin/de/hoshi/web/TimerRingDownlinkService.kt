package de.hoshi.web

import de.hoshi.core.port.DeviceDownlinkPort
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * **TimerRingDownlinkService** — der Ring-Downlink des Wecker-am-Satelliten-Vertrags
 * (`vault/tracks/prep/PREP-wecker-am-satelliten.md`, Scheibe 2 von 2): beim Feuern eines
 * Timers/Weckers/einer Erinnerung MIT bekannter `originSatelliteId` schickt dieser Service
 * ZUSÄTZLICH zum bestehenden FE-Poll-Pfad (`GET /api/v1/scheduled/fired`, **unverändert**)
 * ein `{"type":"timer_ring","id":…,"label"?:…}`-Frame an genau diesen Satelliten — über den
 * BESTEHENDEN server-initiierten WS-Downlink-Kanal ([DeviceDownlinkPort], Nachtmodus-Muster
 * `116fa8d`/[NightModeService]. KEIN neuer Kanal, KEIN neuer Wire-Vertrag am ws-Rand.
 *
 * **Retry bis Ack oder Timeout:** ein einzelner Push kann verloren gehen (UDP-artiges
 * Best-effort-`emitNext`, Netzwerkwackler) — [tick] (Muster [ScheduledItemFireService.pollOnce]/
 * [NightModeService.tickOnce], eigener ~[retryIntervalMs]-Daemon-Thread) wiederholt den Push
 * alle [retryIntervalMs], bis entweder [onAck] (`timer_ack{id}`-Inbound-Frame vom Satelliten,
 * s. [AudioWebSocketHandler.onTimerAck]) die Wiederholung stoppt ODER [timeoutMs] verstreicht
 * (dann gibt der Satellit-Pfad ehrlich auf — der FE-Pfad bleibt die ganze Zeit UNBERÜHRT und
 * läuft unabhängig weiter).
 *
 * **Ehrlich bei nicht verbundenem Satelliten:** liefert [DeviceDownlinkPort.pushToDevice]
 * `false` (Satellit aktuell nicht verbunden ODER Zustellung endgültig gescheitert), gibt
 * dieser Service für dieses Item SOFORT auf (KEIN Retry-Sturm gegen ein totes Ziel) — GENAU
 * EINE Log-Zeile, kein Fehler, der Turn/das Feuern selbst bleibt unberührt (der FE-Pfad hat
 * das Item ohnehin längst im [FiredItemsStore]).
 *
 * **Flag-gated, default OFF** (`HOSHI_TIMER_RING_DOWNLINK_ENABLED`): bei OFF tut [onFired]
 * NICHTS (kein Push-Versuch, kein Log, kein Zustand), [start] startet KEINEN Retry-Thread —
 * **byte-neutral**, exakt der heutige Zustand (FE-Pfad ist der einzige Klingel-Weg).
 */
class TimerRingDownlinkService(
    private val downlink: DeviceDownlinkPort,
    private val enabled: Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val retryIntervalMs: Long = 4_000,
    private val timeoutMs: Long = 60_000,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Ein Item, das gerade beim Satelliten beklingelt wird — Retry-Zustand. */
    private data class RingState(
        val satelliteId: String,
        val label: String?,
        val firstPushAtMs: Long,
        val lastPushAtMs: Long,
    )

    /** `id -> Retry-Zustand` der aktuell laufenden Ring-Versuche. */
    private val ringing = ConcurrentHashMap<String, RingState>()

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    /** `true` sobald der Retry-Ticker laeuft — Test-Naht fuer den Flag-OFF-Beweis. */
    val isRunning: Boolean
        get() = scheduler != null

    /**
     * Startet den Retry-Ticker — NUR wenn [enabled]; sonst No-op (Flag-OFF = kein Thread,
     * kein Effekt). Idempotent: ein zweiter [start] tut nichts.
     */
    @Synchronized
    fun start() {
        if (!enabled) {
            log.info("[timer-ring] HOSHI_TIMER_RING_DOWNLINK_ENABLED=false - Ring-Downlink bleibt aus (kein Retry-Thread).")
            return
        }
        if (scheduler != null) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hoshi-timer-ring").apply { isDaemon = true }
        }
        // Ein werfender Tick wuerde den fixed-delay-Task fuer immer toeten — daher wird
        // JEDE Exception gefangen und nur geloggt (der naechste Tick kommt).
        exec.scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
                log.warn("[timer-ring] Tick fehlgeschlagen (naechster kommt): {}", e.toString())
            }
        }, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS)
        scheduler = exec
        log.info("[timer-ring] Retry-Ticker laeuft (alle {} ms, Timeout {} ms).", retryIntervalMs, timeoutMs)
    }

    /**
     * **Ein Item ist gerade gefeuert** (der [ScheduledItemFireService.onFired]-Hook ruft
     * das GENAU EINMAL je Feuerung). Flag OFF ODER kein bekannter Satelliten-Ursprung
     * ([originSatelliteId] `null`, z.B. Chat/FE-gestellter Timer) ⇒ No-op — der FE-Pfad
     * bleibt der EINZIGE Klingel-Weg, exakt wie heute.
     *
     * Der ERSTE Push passiert SOFORT (nicht erst beim nächsten Tick) — ein Wecker, der
     * gerade fällig wurde, soll ohne künstliche Verzögerung am Satelliten ankommen.
     * Schlägt er fehl (Satellit nicht verbunden), gibt dieser Service sofort auf (s.
     * Klassen-KDoc „Ehrlich bei nicht verbundenem Satelliten") — KEIN Retry-Zustand wird
     * angelegt.
     */
    fun onFired(id: String, label: String?, originSatelliteId: String?) {
        if (!enabled || originSatelliteId == null) return
        val now = clock.millis()
        if (pushOnce(id, originSatelliteId, label)) {
            ringing[id] = RingState(originSatelliteId, label, firstPushAtMs = now, lastPushAtMs = now)
        } else {
            log.info(
                "[timer-ring] Satellit {} nicht verbunden beim Feuern von {} - nur FE-Pfad (kein Fehler).",
                originSatelliteId, id,
            )
        }
    }

    /**
     * `timer_ack{id}` vom Satelliten — stoppt die Wiederholung für [id] sofort. Unbekannte/
     * bereits gestoppte `id` (Doppel-Ack, Timeout schon abgelaufen) ⇒ stilles No-op.
     */
    fun onAck(id: String) {
        ringing.remove(id)
    }

    /**
     * EIN Tick-Durchlauf (die Test-Naht — Tests rufen das direkt mit fixer [Clock]):
     * für jedes noch laufende Ring-Item entweder erneut pushen (fälliges Retry-Intervall
     * verstrichen) oder — bei Timeout ODER gescheitertem Push (Satellit inzwischen
     * getrennt) — ehrlich aufgeben (GENAU EINE Log-Zeile, kein Fehler; der FE-Pfad läuft
     * unabhängig weiter).
     */
    fun tick() {
        if (!enabled) return
        val now = clock.millis()
        val toRemove = mutableListOf<String>()
        for ((id, state) in ringing) {
            if (now - state.firstPushAtMs >= timeoutMs) {
                toRemove.add(id)
                log.info(
                    "[timer-ring] Timeout ohne timer_ack fuer {} (Satellit {}) nach {} ms - gebe auf, FE-Pfad bleibt.",
                    id, state.satelliteId, timeoutMs,
                )
                continue
            }
            if (now - state.lastPushAtMs < retryIntervalMs) continue
            if (pushOnce(id, state.satelliteId, state.label)) {
                ringing[id] = state.copy(lastPushAtMs = now)
            } else {
                toRemove.add(id)
                log.info(
                    "[timer-ring] Satellit {} waehrend Retry von {} nicht mehr verbunden - nur FE-Pfad (kein Fehler).",
                    state.satelliteId, id,
                )
            }
        }
        toRemove.forEach { ringing.remove(it) }
    }

    /** Wie viele Items aktuell auf eine `timer_ack` warten — Test-/Diagnose-Naht. */
    fun ringingCount(): Int = ringing.size

    private fun pushOnce(id: String, satelliteId: String, label: String?): Boolean =
        downlink.pushToDevice(satelliteId, buildMap {
            put("type", "timer_ring")
            put("id", id)
            if (label != null) put("label", label)
        })

    /** Fährt den Retry-Ticker herunter (Bean-`destroyMethod`). Idempotent. */
    @Synchronized
    override fun close() {
        scheduler?.shutdownNow()
        scheduler = null
    }
}
