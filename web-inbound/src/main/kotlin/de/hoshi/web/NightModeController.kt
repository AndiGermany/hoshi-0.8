package de.hoshi.web

import de.hoshi.core.port.NightModeCompute
import de.hoshi.core.port.NightModeConfig
import de.hoshi.core.port.NightModeMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **NightModeController** — der Settings-Rand des Nachtmodus (Scheibe 2 von 3,
 * fürs FE Scheibe 3), PRO GERÄT (Andi-Entscheidung 2026-07-12). Nach dem
 * [WeatherLocationController]-Muster: ein schlanker `@RestController` hinter der
 * [PerimeterWebFilter]-Wand (alle Pfade unter `/api/v1` sind token-geschützt).
 *
 * Zwei Quellen, sauber getrennt:
 *  - die DECKE (`HOSHI_NIGHT_MODE_ENABLED`, Deploy-Zeit, default false) liest der
 *    Controller selbst per [Value] — Nachtmodus aus ⇒ ein PUT greift nicht
 *    (ehrlich 409, KEIN Store-Write, KEIN Push).
 *  - der Laufzeit-STORE ist die injizierte [JsonFileNightModeStore]-Bean (dieselbe
 *    Instanz, die [NightModeService] liest) — ein PUT greift sofort, dank des
 *    Push in (b) sogar ohne auf den nächsten Tick zu warten.
 *
 * Endpoints:
 *  - `GET /api/v1/night-mode/devices` → ALLE konfigurierten UND aktuell
 *    verbundenen `satelliteId`s (Union), je mit `connected`-Flag + Config.
 *  - `GET /api/v1/night-mode/{satelliteId}` → EIN Gerät; unkonfiguriert ⇒ der
 *    [NightModeConfig]-Default (`enabled=false`), KEIN 404 — GET ist eine reine
 *    Settings-Sicht, kein Existenz-Check.
 *  - `PUT /api/v1/night-mode/{satelliteId}` → Body `{enabled,mode,from,to,dim}`.
 *    Validiert `mode` (SCHEDULE/ALWAYS), `from`/`to` (`HH:mm`, [NightModeCompute.parseTimeOrNull])
 *    und `dim` (`0.0..1.0`) — jeweils 400 bei Verstoß. Deploy-Decke zu ⇒ 409.
 *    Persist-Fehler ⇒ 500 (ehrlich, KEIN fake-200). Erfolg ⇒ 200 + Sofort-Push
 *    ([NightModeService.pushNow], best-effort — ob das Gerät gerade verbunden ist,
 *    ändert NICHTS am HTTP-Ergebnis).
 *
 * Perimeter: `/api/v1/...` liegt AUTOMATISCH hinter der [PerimeterWebFilter]-Wand
 * — ohne/falscher Token ⇒ 401 (GET wie PUT). Kein eigener Auth-Code nötig.
 */
@RestController
class NightModeController(
    private val store: JsonFileNightModeStore,
    private val nightModeService: NightModeService,
    @Value("\${HOSHI_NIGHT_MODE_ENABLED:false}") private val nightModeEnabled: Boolean,
) {

    @GetMapping("/api/v1/night-mode/devices")
    fun devices(): List<NightModeDeviceView> {
        val ids = (store.all().keys + nightModeService.connectedDevices()).toSortedSet()
        return ids.map { view(it) }
    }

    @GetMapping("/api/v1/night-mode/{satelliteId}")
    fun device(@PathVariable satelliteId: String): NightModeDeviceView = view(satelliteId)

    @PutMapping("/api/v1/night-mode/{satelliteId}")
    fun setDevice(
        @PathVariable satelliteId: String,
        @RequestBody body: NightModeConfigRequest,
    ): ResponseEntity<Any> {
        if (!nightModeEnabled) {
            // Decke zu: ehrlich 409 — die Einstellung greift nicht, der Nachtmodus ist
            // beim Deploy deaktiviert. KEIN Store-Write, KEIN Push.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(NightModeError("deploy-disabled", satelliteId, "Beim Deploy deaktiviert; greift nicht."))
        }
        val mode = runCatching { NightModeMode.valueOf(body.mode.trim().uppercase()) }.getOrNull()
            ?: return ResponseEntity.badRequest()
                .body(NightModeError("invalid-mode", satelliteId, "mode muss SCHEDULE oder ALWAYS sein."))
        if (NightModeCompute.parseTimeOrNull(body.from) == null) {
            return ResponseEntity.badRequest()
                .body(NightModeError("invalid-from", satelliteId, "from muss im Format HH:mm sein."))
        }
        if (NightModeCompute.parseTimeOrNull(body.to) == null) {
            return ResponseEntity.badRequest()
                .body(NightModeError("invalid-to", satelliteId, "to muss im Format HH:mm sein."))
        }
        if (body.dim < 0.0 || body.dim > 1.0) {
            return ResponseEntity.badRequest()
                .body(NightModeError("invalid-dim", satelliteId, "dim muss zwischen 0.0 und 1.0 liegen."))
        }
        val config = NightModeConfig(
            enabled = body.enabled,
            mode = mode,
            from = body.from.trim(),
            to = body.to.trim(),
            dim = body.dim,
        )
        // Persist-then-commit: store.set schreibt ZUERST atomar auf die Platte und wirft,
        // wenn das fehlschlägt (Cache bleibt unangetastet). 200 NUR bei bewiesenem Persist
        // — ein Schreib-Fehler darf NIE als Erfolg quittiert werden (kein fake-grün).
        val persisted = runCatching { store.set(satelliteId, config) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(NightModeError("persist-failed", satelliteId, "Konnte die Nachtmodus-Einstellung nicht dauerhaft speichern."))
        }
        // Settings-Änderung (Push-Weg b): sofort neu berechnen + pushen, best-effort —
        // nicht verbunden ⇒ false, ändert nichts am HTTP-Erfolg dieses PUT.
        nightModeService.pushNow(satelliteId)
        return ResponseEntity.ok(view(satelliteId))
    }

    /** Der eine Settings-Zustand EINES Geräts: Store-Wert (sonst Default) + Live-`connected`-Flag. */
    private fun view(satelliteId: String): NightModeDeviceView {
        val config = store.get(satelliteId) ?: NightModeConfig()
        return NightModeDeviceView(
            satelliteId = satelliteId,
            connected = nightModeService.connectedDevices().contains(satelliteId),
            enabled = config.enabled,
            mode = config.mode.name,
            from = config.from,
            to = config.to,
            dim = config.dim,
            nightModeEnabled = nightModeEnabled,
        )
    }
}

/**
 * `PUT /api/v1/night-mode/{satelliteId}`-Body: der gewünschte Zustand.
 * `mode` als String (case-insensitiv, `SCHEDULE`/`ALWAYS`), damit ein Tippfehler
 * einen ehrlichen 400 statt eines rohen Jackson-Deserialisierungs-500 liefert.
 */
data class NightModeConfigRequest(
    val enabled: Boolean = false,
    val mode: String = "SCHEDULE",
    val from: String = "22:00",
    val to: String = "07:00",
    val dim: Double = 0.3,
)

/**
 * Ein Geräte-Eintrag — das Wire-Format von `GET .../devices`, `GET .../{id}` und
 * dem `PUT .../{id}`-Ergebnis: `{satelliteId, connected, enabled, mode, from, to,
 * dim, nightModeEnabled}`. `connected` kommt LIVE aus der [NightModeService]
 * (Downlink-Registry); `nightModeEnabled` ist die Deploy-Decke (FE greyed den
 * Regler, wenn `false`).
 */
data class NightModeDeviceView(
    val satelliteId: String,
    val connected: Boolean,
    val enabled: Boolean,
    val mode: String,
    val from: String,
    val to: String,
    val dim: Double,
    val nightModeEnabled: Boolean,
)

/** Fehler-Body für 400 (ungültige Felder), 409 (Decke zu) und 500 (Persist fehlgeschlagen). */
data class NightModeError(val error: String, val satelliteId: String, val message: String)
