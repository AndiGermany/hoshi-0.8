package de.hoshi.web

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * **FiredItemsController** — die Klingel-Naht zum FE.
 *
 *  - `GET /api/v1/scheduled/fired` — liefert ALLE unbestaetigten gefeuerten
 *    Timer/Wecker aus dem [FiredItemsStore]. **Idempotent** (Ring-1-Fix: frueher
 *    consume-once — der erste pollende Tab schnappte den Event, alle anderen sahen
 *    nie etwas): beliebig oft abrufbar, JEDER Tab sieht dieselben Items, bis eines
 *    per Ack quittiert wird. Laenger als 30 min unbestaetigt ⇒ `missed=true`
 *    (das FE sagt dann ehrlich „hab dich nicht erreicht" statt zu klingeln).
 *  - `POST /api/v1/scheduled/fired/{id}/ack` — quittiert genau ein Klingeln
 *    (Tap auf den Banner). 204 bei Erfolg (erst DANN ist es weg — fuer alle Tabs),
 *    404 bei unbekannter id (z.B. schon von einem anderen Tab quittiert — fuer das
 *    FE gleichwertig: es ist weg).
 *
 * Format: `[{id, kind, label?, dueAtEpochMs, firedAtEpochMs, missed}]` ([FiredItem];
 * `label` fehlt bei null). Leer ⇒ `[]` (HTTP 200).
 *
 * Perimeter: `/api/v1/...` liegt AUTOMATISCH hinter der [PerimeterWebFilter]-Wand
 * ([de.hoshi.kernel.PerimeterPort.isProtected] schuetzt alles unter `/api/` ausser
 * exakt `/api/health` und den Easter-Egg-Pfaden) — ohne/falscher Token ⇒ 401,
 * identisch zu `/api/v1/ping`. Kein eigener Auth-Code noetig; bewiesen im
 * FiredItemsEndpointTest.
 *
 * Die [Clock] ist nur der `now()`-Punkt fuer die 30-min-missed-Markierung
 * (deterministische missed-Tests leben am [FiredItemsStore] mit explizitem `nowMs`).
 */
@RestController
class FiredItemsController(
    private val fired: FiredItemsStore,
) {

    private val clock: Clock = Clock.systemUTC()

    @GetMapping("/api/v1/scheduled/fired")
    fun fired(): List<FiredItem> = fired.pending(clock.millis())

    @PostMapping("/api/v1/scheduled/fired/{id}/ack")
    fun ack(@PathVariable id: String): ResponseEntity<Void> =
        if (fired.ack(id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}
