package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.DeviceDownlinkPort
import org.slf4j.LoggerFactory
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * **WsDeviceRegistry** — die `:web-inbound`-Implementierung des hexagonalen
 * [DeviceDownlinkPort] (Nachtmodus-Vorstufe, Scheibe 1 von 3): hält
 * `satelliteId → Outbound-Sink der aktiven Session` und macht damit ein
 * verbundenes Voice-PE-Gerät für Turn-FREMDE Aufrufer (künftige Scheiben:
 * Scheduler/Nachtmodus-Trigger) adressierbar.
 *
 * **Registrierung/Lifecycle** (durch [AudioWebSocketHandler] getrieben, Muster
 * dessen `activeTurns`/`sinks`-Maps): [register] hängt den Session-Sink ein,
 * SOBALD `onStart` eine `satelliteId` kennt; [unregister] räumt ihn beim
 * ws-Close/Terminate wieder ab (`closeSession`) — GENAU EIN Eintrag je
 * `satelliteId` (ein Doppel-`start`/Reconnect überschreibt den alten Sink mit
 * dem neuen, kein Leak). Kein Consumer im Handler ⇒ diese Klasse bleibt leer
 * ⇒ [connectedDevices] `emptySet()`, [pushToDevice] immer `false`.
 *
 * **Thread-Sicherheit des Push (der Knackpunkt dieser Scheibe):** der
 * Session-Sink ist ein `Sinks.many().unicast().onBackpressureBuffer()`
 * ([AudioWebSocketHandler.openSession]) — dessen `tryEmitNext` ist NUR für
 * EINEN Produzenten zur Zeit sicher; ein zweiter, ZEITGLEICH aufrufender
 * Produzent bekommt (statt Korruption/interleavtem Output) deterministisch
 * `Sinks.EmitResult.FAIL_NON_SERIALIZED` zurück — Reactor serialisiert den
 * Zugriff intern per CAS-Guard und lässt IMMER nur einen Schreiber wirklich
 * in die Queue schreiben; ein Kollidierender wird sauber abgewiesen, NIE
 * halb hereingelassen. [pushToDevice] nutzt deshalb NICHT das robuste (aber
 * stille) `tryEmitNext`, sondern `emitNext` mit
 * [Sinks.EmitFailureHandler.busyLooping] (das WebFlux-Idiom für „mehrere
 * Produzenten auf einem Sink"): kollidiert der Push zufällig mit einem
 * `llm_*`-Frame, das der laufende Turn GERADE auf denselben Sink schreibt,
 * retried der Push automatisch (Busy-Loop innerhalb [BUSY_LOOP_BUDGET]),
 * bis er entweder durchkommt oder das Budget verstreicht — der Turn-Schreiber
 * (bestehendes `tryEmitNext` im Handler, UNVERÄNDERT) bekommt von dieser
 * Kollision nichts mit außer einem einzelnen, sauberen `FAIL_NON_SERIALIZED`-
 * Retry-Fenster; es entsteht NIE ein korrupter/interleaved Frame auf dem
 * Draht, weil Reactors Guard einen "halben" `onNext` grundsätzlich verhindert.
 */
class WsDeviceRegistry(
    private val objectMapper: ObjectMapper,
) : DeviceDownlinkPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /** `satelliteId -> Outbound-Sink` der aktuell für dieses Gerät aktiven Session. */
    private val devices = ConcurrentHashMap<String, Sinks.Many<String>>()

    /** Hängt den Session-[sink] unter [satelliteId] ein (überschreibt einen evtl. alten Eintrag). */
    fun register(satelliteId: String, sink: Sinks.Many<String>) {
        devices[satelliteId] = sink
    }

    /** Entfernt [satelliteId] aus der Registry (ws-Close/Terminate) — kein Leak. */
    fun unregister(satelliteId: String) {
        devices.remove(satelliteId)
    }

    override fun pushToDevice(satelliteId: String, frame: Map<String, Any?>): Boolean {
        val sink = devices[satelliteId] ?: return false
        return runCatching {
            val json = objectMapper.writeValueAsString(frame)
            // s. Klassen-KDoc: emitNext + busyLooping statt tryEmitNext — retried
            // automatisch gegen einen ZEITGLEICH schreibenden Turn, NIE ein
            // korrupter/verlorener Frame durch einen unbehandelten FAIL_NON_SERIALIZED.
            sink.emitNext(json, Sinks.EmitFailureHandler.busyLooping(BUSY_LOOP_BUDGET))
            true
        }.getOrElse { e ->
            log.warn("[ws-device-registry] pushToDevice an {} fehlgeschlagen: {}", satelliteId, e.message)
            false
        }
    }

    override fun connectedDevices(): Set<String> = devices.keys.toSet()

    companion object {
        /** Retry-Budget für [Sinks.EmitFailureHandler.busyLooping] gegen kollidierende Turn-Writes. */
        val BUSY_LOOP_BUDGET: Duration = Duration.ofMillis(200)
    }
}
