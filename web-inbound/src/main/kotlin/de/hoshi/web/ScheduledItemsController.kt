package de.hoshi.web

import com.fasterxml.jackson.annotation.JsonInclude
import de.hoshi.core.port.ScheduledItemPort
import de.hoshi.core.port.ScheduledKind
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * **ScheduledItemsController** — die Sichtbarkeits- UND Verwaltungs-Naht der Wecker-Lane
 * (Cowork-Befund: laufende Timer/Wecker waren UNSICHTBAR — der Store persistiert, aber
 * kein Endpoint zeigte oder loeschte sie; exakt der Voice-PE-Fehler).
 *
 *  - `GET /api/v1/scheduled` — listet die AKTIVEN (noch nicht gefeuerten) Items aus dem
 *    [ScheduledItemPort], aufsteigend nach Faelligkeit (der Port sortiert). Strikt
 *    READ-ONLY: beliebig oft abrufbar, konsumiert nichts (wie inzwischen auch der
 *    idempotente fired-GET des [FiredItemsController]). Das FE pollt und rendert daraus
 *    die ruhige Timer-Zeile ueber der Compose-Bar.
 *  - `DELETE /api/v1/scheduled/{id}` — storniert GENAU EIN aktives Item
 *    ([ScheduledItemPort.cancel]). 204 wenn entfernt, 404 bei unbekannter id (z.B. schon
 *    gefeuert oder von einem anderen Tab storniert — fuers FE gleichwertig: weg ist weg).
 *  - `DELETE /api/v1/scheduled` — storniert ALLE aktiven Items
 *    ([ScheduledItemPort.cancelAll]); 200 mit `{count}` (wie viele entfernt wurden;
 *    leerer Store ⇒ `{count:0}`). Beruehrt gefeuert-unbestaetigte Items NICHT (das ist
 *    die fired/ack-Naht des [FiredItemsController]).
 *
 * GET-Format: `[{id, kind, label?, dueAtEpochMs, remainingSeconds}]` ([ScheduledItemView];
 * `label` fehlt bei null — NON_NULL, derselbe Contract wie [FiredItem]). `remainingSeconds`
 * ist die additive Rest-Sekunden-Bequemlichkeit fuers FE (nie negativ; faellig ⇒ 0),
 * berechnet gegen die Server-Uhr. Leer ⇒ `[]` (HTTP 200) — auch bei Flag-OFF
 * ([ScheduledItemPort.NONE] liefert nie), verhaltens-neutral.
 *
 * Perimeter: `/api/v1/...` liegt AUTOMATISCH hinter der [PerimeterWebFilter]-Wand
 * (alles unter `/api/` ausser `/api/health` + Easter-Egg-Pfade) — ohne/falscher
 * Token ⇒ 401 (GET wie DELETE). Kein eigener Auth-Code noetig; bewiesen im
 * ScheduledItemsEndpointTest (Muster: FiredItemsEndpointTest).
 *
 * Die [Clock] ist der `now()`-Punkt fuer `remainingSeconds` (Server-UTC, analog zum
 * [FiredItemsController]; die Rest-Berechnung ist gutmuetig — nie negativ).
 */
@RestController
class ScheduledItemsController(
    private val store: ScheduledItemPort,
) {

    private val clock: Clock = Clock.systemUTC()

    @GetMapping("/api/v1/scheduled")
    fun scheduled(): List<ScheduledItemView> {
        val now = clock.millis()
        return store.query().map {
            ScheduledItemView(
                id = it.id,
                kind = it.kind,
                label = it.label,
                dueAtEpochMs = it.dueAtEpochMs,
                remainingSeconds = (it.dueAtEpochMs - now).coerceAtLeast(0) / 1000,
            )
        }
    }

    @DeleteMapping("/api/v1/scheduled/{id}")
    fun cancel(@PathVariable id: String): ResponseEntity<Void> =
        if (store.cancel(id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    @DeleteMapping("/api/v1/scheduled")
    fun cancelAll(): CancelAllResponse = CancelAllResponse(count = store.cancelAll())
}

/**
 * Ein AKTIVES (geplantes, noch nicht gefeuertes) Item — das Wire-Format von
 * `GET /api/v1/scheduled`: `{id, kind, label?, dueAtEpochMs, remainingSeconds}`.
 * `label=null` wird im JSON weggelassen (NON_NULL), exakt der `label?`-Contract der
 * Klingel-Naht. `remainingSeconds` ist immer da (nicht-nullbar; faellig ⇒ 0) — die
 * additive FE-Bequemlichkeit, damit das FE keinen eigenen `now`-Diff bilden MUSS.
 * Bewusst OHNE `firedAtEpochMs` — aktiv heisst: noch nicht gefeuert.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScheduledItemView(
    val id: String,
    val kind: ScheduledKind,
    val label: String? = null,
    val dueAtEpochMs: Long,
    val remainingSeconds: Long = 0,
)

/**
 * Antwort von `DELETE /api/v1/scheduled` (alle stornieren): `{count}` — die Anzahl der
 * tatsaechlich entfernten aktiven Items (leerer Store ⇒ `{count:0}`).
 */
data class CancelAllResponse(val count: Int)
