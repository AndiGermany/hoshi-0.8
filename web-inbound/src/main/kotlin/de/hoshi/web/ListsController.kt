package de.hoshi.web

import com.fasterxml.jackson.annotation.JsonInclude
import de.hoshi.core.port.ListEntry
import de.hoshi.core.port.ListPort
import de.hoshi.core.port.addWithDedupe
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * **ListsController** — die Sichtbarkeits- UND Verwaltungs-Naht der Listen-Lane
 * (Andi-JA 2026-07-08 „Listen auf die Ring-1-Karte", exakt das
 * [ScheduledItemsController]-Muster der Wecker-Lane).
 *
 *  - `GET /api/v1/lists` — listet ALLE Einträge der Default-Liste
 *    ([ListPort.DEFAULT_LIST_ID], „einkauf") aus dem [ListPort], älteste zuerst.
 *    Strikt READ-ONLY: beliebig oft abrufbar, konsumiert nichts.
 *  - `POST /api/v1/lists/items` — legt EIN Item an ODER merged dedupe-artig in
 *    einen bestehenden Eintrag ([de.hoshi.core.port.addWithDedupe] —
 *    case-insensitiver Text-Vergleich, EINE Wahrheit mit
 *    [de.hoshi.core.pipeline.ListFastpath], keine Drift zwischen Voice/Text und
 *    REST). 200 mit dem (ggf. gemergten) Item; leerer/fehlender `text` ⇒ 400.
 *  - `DELETE /api/v1/lists/items/{id}` — storniert GENAU EIN Item
 *    ([ListPort.remove]). 204 wenn entfernt, 404 bei unbekannter id (z.B. schon
 *    entfernt oder von einem anderen Client — fürs FE gleichwertig: weg ist weg).
 *
 * GET/POST-Format: `[{id, text, quantity, addedAtEpochMs}]` ([ListItemView]).
 * Leer ⇒ `[]` (HTTP 200) — auch bei Flag-OFF ([ListPort.NONE] liefert nie),
 * verhaltens-neutral.
 *
 * Perimeter: `/api/v1/...` liegt AUTOMATISCH hinter der [PerimeterWebFilter]-Wand
 * (alles unter `/api/` außer `/api/health` + Easter-Egg-Pfade) — ohne/falscher
 * Token ⇒ 401 (GET/POST/DELETE). Kein eigener Auth-Code nötig; bewiesen im
 * ListsEndpointTest (Muster: ScheduledItemsEndpointTest).
 *
 * **Privacy** (Tom-Veto, PREP-Risiko 3 — Listeninhalte sind persönliche Daten):
 * dieser Controller loggt NICHTS (kein `log.*` mit Item-Text).
 *
 * Die [Clock] ist der `now()`-Punkt für `addedAtEpochMs` bei neu angelegten Items
 * (Server-UTC, analog zum [ScheduledItemsController]).
 */
@RestController
class ListsController(
    private val store: ListPort,
) {

    private val clock: Clock = Clock.systemUTC()

    @GetMapping("/api/v1/lists")
    fun items(): List<ListItemView> = store.items(ListPort.DEFAULT_LIST_ID).map { it.toView() }

    @PostMapping("/api/v1/lists/items")
    fun add(@RequestBody body: AddListItemRequest): ResponseEntity<ListItemView> {
        val text = body.text?.trim().orEmpty()
        if (text.isBlank()) return ResponseEntity.badRequest().build()
        val saved = store.addWithDedupe(ListPort.DEFAULT_LIST_ID, text, clock.millis()) { UUID.randomUUID().toString() }
        return ResponseEntity.ok(saved.toView())
    }

    @DeleteMapping("/api/v1/lists/items/{id}")
    fun remove(@PathVariable id: String): ResponseEntity<Void> =
        if (store.remove(id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}

/** `POST /api/v1/lists/items`-Body: `{text}` — Freitext-Item, keine Einheiten-Ontologie. */
data class AddListItemRequest(val text: String?)

/**
 * Ein Listen-Eintrag — das Wire-Format von `GET /api/v1/lists` und dem
 * `POST /api/v1/lists/items`-Ergebnis: `{id, text, quantity, addedAtEpochMs}`.
 * `quantity` ist der Dedupe-Zähler ("2×" bei doppelt genanntem Item), NICHT eine
 * geparste Mengenangabe (s. [ListEntry]-KDoc).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ListItemView(
    val id: String,
    val text: String,
    val quantity: Int,
    val addedAtEpochMs: Long,
)

private fun ListEntry.toView() = ListItemView(id = id, text = text, quantity = quantity, addedAtEpochMs = addedAtEpochMs)
