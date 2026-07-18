package de.hoshi.core.port

/**
 * **DeviceDownlinkPort** — der server-INITIIERTE Downlink-Kanal zu einem
 * verbundenen Voice-PE-Gerät, AUSSERHALB eines Turns (Nachtmodus-Vorstufe,
 * Scheibe 1 von 3: die geteilte Voraussetzung für die drei LED-Downlink-Frames
 * `speaker`/`timer_state`/`night_mode`, s. `vault/tracks/…nachtmodus…`).
 *
 * Bislang konnte der Server ein Gerät NUR INNERHALB eines laufenden Turns
 * erreichen — der `AudioWebSocketHandler` schreibt `llm_*`/`speaker`-Frames
 * direkt auf den Outbound-Sink der Session, ausgelöst durch ein `stop`/`abort`
 * DES GERÄTS. Dieser Port ist die hexagonale Naht, über die ein Turn-FREMDER
 * Aufrufer (ein Scheduler, ein Nachtmodus-Trigger, künftige Scheiben) ein
 * JSON-Frame an ein KONKRETES Gerät schicken kann — adressiert über
 * [pushToDevice]s `satelliteId` (dieselbe Kennung, die das Gerät im `start`-
 * Frame mitschickt, s. `AudioWebSocketHandler.onStart`).
 *
 * **Hexagonale Naht:** `core-domain` kennt die konkrete WebSocket-/Sink-
 * Implementierung NICHT — sie lebt als `WsDeviceRegistry` in `:web-inbound`
 * (hält `satelliteId → Outbound-Sink der aktiven Session`). [frame] ist
 * bewusst ein primitives, JSON-taugliches `Map<String, Any?>` (verschachtelt
 * aus String/Zahl/Boolean/Map/List/null) statt eines Jackson-Typs — der Kern
 * bleibt frei von Jackson-databind an seiner API-Grenze (nur
 * `jackson-annotations` ist `api`, `databind` bleibt `implementation`-intern,
 * s. `core-domain/build.gradle.kts`); die Impl serialisiert.
 *
 * **Honesty-Charter:** [pushToDevice] wirft NIE (never-throw). Ist
 * [pushToDevice]s `satelliteId` nicht verbunden ODER scheitert die Zustellung
 * endgültig, liefert es `false` — kein Crash, kein stiller Absturz des
 * aufrufenden Schedulers. Der Aufrufer entscheidet, ob/wie er ein `false`
 * quittiert (z.B. Retry beim nächsten Sweep).
 *
 * **Default-OFF:** [NOOP] verbindet nie etwas und sendet nie — der
 * verhaltensneutrale Zustand, solange kein Adapter verdrahtet ist
 * (Muster [TurnTracePort.NOOP]/[EpisodicWriter.NOOP]).
 */
interface DeviceDownlinkPort {

    /**
     * Schickt [frame] als EIN JSON-Text-Frame an das über [satelliteId]
     * verbundene Gerät (AUSSERHALB eines Turns). `true` = zugestellt (auf den
     * Outbound-Sink der Session geschrieben); `false` = kein Gerät mit dieser
     * Kennung aktuell verbunden ODER die Zustellung ist (best-effort, z.B.
     * unter Nebenläufigkeits-Druck durch einen parallel laufenden Turn)
     * endgültig gescheitert. NIE eine Exception.
     */
    fun pushToDevice(satelliteId: String, frame: Map<String, Any?>): Boolean

    /**
     * Die `satelliteId`s aller aktuell verbundenen Geräte — die Grundlage für
     * spätere Broadcasts an Scheduler-Grenzen (z.B. „schalte für ALLE
     * verbundenen Geräte den Nachtmodus scharf").
     */
    fun connectedDevices(): Set<String>

    companion object {
        /** Verhaltens-neutraler Default (Kanal OFF/nicht verdrahtet) — verbindet nie, sendet nie. */
        val NOOP: DeviceDownlinkPort = object : DeviceDownlinkPort {
            override fun pushToDevice(satelliteId: String, frame: Map<String, Any?>): Boolean = false
            override fun connectedDevices(): Set<String> = emptySet()
        }
    }
}
