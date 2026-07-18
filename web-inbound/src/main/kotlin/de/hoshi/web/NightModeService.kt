package de.hoshi.web

import de.hoshi.core.port.DeviceDownlinkPort
import de.hoshi.core.port.NightModeCompute
import de.hoshi.core.port.NightModeConfig
import de.hoshi.core.port.NightModeMode
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * **NightModeService** — die EINE Push-Wahrheit des Nachtmodus (Scheibe 2 von 3),
 * die alle drei Wege teilt, über die ein `night_mode`-Frame ans Gerät geht
 * (`vault/tracks/prep/PREP-nachtmodus.md`):
 *
 *  1. **ws-Connect** — [pushNow] (der `onDeviceConnected`-Hook in [WebSocketConfig]
 *     ruft das): der Initialzustand ist KRITISCH, weil das Gerät keine eigene Uhr
 *     hat — ohne diesen Push wüsste es nach einem Reconnect nie, ob es dunkel sein
 *     soll.
 *  2. **Settings-PUT** — [NightModeController] ruft [pushNow] nach jedem
 *     erfolgreichen [JsonFileNightModeStore.set], damit die Änderung SOFORT greift.
 *  3. **Scheduler-Grenze** — der periodische [tickOnce] (alle ~[pollIntervalMs],
 *     Muster [ScheduledItemFireService]): für jedes VERBUNDENE Gerät mit
 *     `mode=SCHEDULE` wird `active` neu berechnet und NUR bei einer ÄNDERUNG
 *     gegenüber dem zuletzt gepushten Zustand gepusht (kein Spam) — `ALWAYS`/
 *     `enabled=false` ändern sich ohne einen PUT ohnehin nie, die brauchen keinen
 *     Tick.
 *
 * [pushNow] und [tickOnce] teilen sich denselben [lastPushedActive]-Cache: ein
 * PUT-getriebener Push aktualisiert ihn, damit der NÄCHSTE Tick nicht redundant
 * denselben (unveränderten) Zustand nochmal pusht.
 *
 * **Flag-gated, default OFF** (`HOSHI_NIGHT_MODE_ENABLED`): [start] startet dann
 * KEINEN Poll-Thread, [tickOnce] und [pushNow] tun NICHTS (liefern `false`/no-op)
 * ⇒ **byte-neutral** (kein Tick, kein Push, kein Verhalten). Der [downlink] ist
 * IMMER ein echtes [DeviceDownlinkPort] (ggf. [DeviceDownlinkPort.NOOP], falls
 * `/ws/audio` selbst aus ist) — nie `null`, das erspart Null-Checks an den
 * Aufrufern.
 */
class NightModeService(
    private val store: JsonFileNightModeStore,
    private val downlink: DeviceDownlinkPort,
    private val enabled: Boolean,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val pollIntervalMs: Long = 60_000,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)

    /** `satelliteId -> zuletzt gepushter active-Zustand` — die Dedupe-Grundlage für [tickOnce]. */
    private val lastPushedActive = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    /** `true` sobald der Tick-Scheduler läuft — Test-Naht für den Flag-OFF-Beweis. */
    val isRunning: Boolean
        get() = scheduler != null

    /**
     * Startet den Tick-Scheduler — NUR wenn [enabled]; sonst No-op (Flag-OFF = kein
     * Thread, kein Effekt). Idempotent: ein zweiter [start] tut nichts.
     */
    @Synchronized
    fun start() {
        if (!enabled) {
            log.info("[night-mode] HOSHI_NIGHT_MODE_ENABLED=false - Service bleibt aus (kein Tick, kein Push).")
            return
        }
        if (scheduler != null) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hoshi-night-mode-tick").apply { isDaemon = true }
        }
        // Ein werfender Tick würde den fixed-delay-Task fuer immer toeten — daher wird
        // JEDE Exception gefangen und nur geloggt (der naechste Tick kommt).
        exec.scheduleWithFixedDelay({
            try {
                tickOnce()
            } catch (e: Exception) {
                log.warn("[night-mode] Tick fehlgeschlagen (naechster kommt): {}", e.toString())
            }
        }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS)
        scheduler = exec
        log.info("[night-mode] Tick laeuft (alle {} ms).", pollIntervalMs)
    }

    /**
     * EIN Tick-Durchlauf (die Test-Naht — Tests rufen das direkt mit fixer [Clock]):
     * für jedes VERBUNDENE Gerät mit einer gespeicherten, `enabled`+`SCHEDULE`-Config
     * wird `active` neu berechnet; gepusht wird NUR bei einer Änderung gegenüber
     * [lastPushedActive] (kein Spam). `ALWAYS`/`enabled=false`/unkonfigurierte Geräte
     * werden übersprungen — ihr Zustand ändert sich nie ohne einen PUT (der pusht
     * bereits selbst über [pushNow]).
     */
    fun tickOnce() {
        if (!enabled) return
        val now = LocalTime.now(clock)
        for (satelliteId in downlink.connectedDevices()) {
            val config = store.get(satelliteId) ?: continue
            if (!config.enabled || config.mode != NightModeMode.SCHEDULE) continue
            val active = NightModeCompute.active(config, now)
            if (lastPushedActive[satelliteId] == active) continue
            if (downlink.pushToDevice(satelliteId, NightModeCompute.buildFrame(config, now))) {
                lastPushedActive[satelliteId] = active
                log.info("[night-mode] {} Zeitfenster-Grenze ueberschritten - active={} gepusht.", satelliteId, active)
            }
        }
    }

    /**
     * Berechnet den AKTUELLEN Zustand für [satelliteId] (unkonfiguriert ⇒ der
     * NightModeConfig-Default, also `enabled=false` ⇒ `active=false`) und pusht ihn
     * SOFORT — unabhängig vom [lastPushedActive]-Cache (der Aufrufer — ws-Connect
     * ODER Settings-PUT — braucht den frischen Zustand JETZT, nicht erst beim
     * nächsten Tick). Aktualisiert danach [lastPushedActive], damit [tickOnce]
     * nicht redundant denselben Zustand nochmal pusht.
     *
     * Flag OFF ⇒ `false`, kein Push (byte-neutral). `false` heisst auch: Gerät
     * aktuell nicht verbunden ODER Zustellung gescheitert — beides kein Fehler für
     * den Aufrufer (best-effort, s. [DeviceDownlinkPort.pushToDevice]-KDoc).
     */
    fun pushNow(satelliteId: String): Boolean {
        if (!enabled) return false
        val config = store.get(satelliteId) ?: NightModeConfig()
        val now = LocalTime.now(clock)
        val active = NightModeCompute.active(config, now)
        val delivered = downlink.pushToDevice(satelliteId, NightModeCompute.buildFrame(config, now))
        if (delivered) lastPushedActive[satelliteId] = active
        return delivered
    }

    /** Die aktuell verbundenen Geräte (delegiert an den [downlink]) — für die Geräte-Liste im Controller. */
    fun connectedDevices(): Set<String> = downlink.connectedDevices()

    /** Fährt den Tick-Scheduler herunter (Bean-`destroyMethod`). Idempotent. */
    @Synchronized
    override fun close() {
        scheduler?.shutdownNow()
        scheduler = null
    }
}
