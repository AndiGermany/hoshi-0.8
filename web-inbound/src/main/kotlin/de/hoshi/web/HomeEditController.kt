package de.hoshi.web

import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import de.hoshi.adapters.ha.HomeRegistrySnapshot
import de.hoshi.adapters.ha.RegistryWriteOutcome
import de.hoshi.adapters.ha.RegistryWriter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * **HomeEditController** — der SCHREIB-Rand von Scheibe 2 des
 * Geräte-Zuordnungs-Konzepts (`.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`): „HA bleibt die eine Wahrheit, Hoshi wird ihr
 * Editor." Diese Scheibe ZUWEISEN: eine HA-Entity bekommt eine HA-Area — nur
 * Registry-Objekte, nur die offizielle WS-API ([RegistryWriter] ▷
 * [de.hoshi.adapters.ha.HaRegistryWriteClient]), nie fremde Struktur gelöscht.
 *
 * Liegt AUTOMATISCH hinter der [PerimeterWebFilter]-Wand (`/api/v1/…` ⇒ ohne/mit
 * falschem Token 401) — kein eigener Auth-Code. Die 5-Punkte-Verfassung ist hier
 * verdrahtet:
 *  - **flag-gated** (`HOSHI_HOME_EDIT_ENABLED`, Default false, byte-neutral): Flag
 *    zu ⇒ **409** `home-edit-off`, KEIN Write, KEIN HA-Call, KEINE Audit-Zeile.
 *    Das FE liest den Flag-Stand über `GET .../edit/status` und rendert den Picker
 *    gar nicht erst, wenn er zu ist.
 *  - **nur bekannte Areas**: `areaId` wird gegen den READ-Katalog
 *    ([HaHomeRegistryAdapter.registry] — dieselbe eine Wahrheit, die auch der
 *    Picker füllt) geprüft ⇒ unbekannt ⇒ **400** `unknown-area`. Leer ⇒ **400**
 *    `invalid-area`. Katalog nicht ladbar (HA gerade nicht erreichbar) ⇒ **502**
 *    `home-edit-unreachable` (blind schreiben wäre unehrlich).
 *  - **nur bekannte Entities**: dieselbe READ-Katalog-Prüfung gilt auch für die
 *    `entityId` selbst — steht sie in KEINER Area und NICHT unter `unassigned`,
 *    ist sie dem Snapshot fremd ⇒ **404** `unknown-entity`, KEIN Write-Aufruf
 *    (derselbe geladene Snapshot trägt beide Prüfungen — kein zweiter Read nötig).
 *  - **Audit je Write** ([HomeEditAuditLog]): jeder ECHTE Schreibversuch (nach der
 *    Area-Validierung) hinterlässt eine Zeile `ok`/`failed`, MIT der bisherigen
 *    Area laut Snapshot (`fromAreaId`, `null` = unassigned) neben der neuen
 *    (`toAreaId`) — Reassigns bleiben rekonstruierbar. Eine abgelehnte unbekannte
 *    Entity hinterlässt ebenfalls eine Zeile (`rejected_unknown_entity`) — „jede
 *    Op geloggt" gilt auch für den Ablehnungsfall. Die reinen 409/400-Client-
 *    Fehler (Flag zu, leere/unbekannte Area) berühren HA nie und werden nicht
 *    als Write geloggt.
 *  - **read-first**: nach einem `ok` wird der Read-Cache SOFORT invalidiert
 *    ([HaHomeRegistryAdapter.invalidate]) — der nächste FE-Read holt den frischen
 *    HA-Stand und die Karte wandert ECHT (kein optimistisches Raten).
 *  - **never-throw ⇒ ehrliche 502**: der [RegistryWriter] wirft nie; ein
 *    [RegistryWriteOutcome.Failed] wird zu **502** `home-edit-write-failed`
 *    (nie ein Fake-200 „hat geklappt", wenn HA nicht bestätigt hat).
 *
 * **Blocking-Hygiene:** [assignArea] macht blockierende I/O (der Registry-Read
 * via `java.net.http.HttpClient`, der Registry-Write via WebSocket, bis zu ~10s
 * zusammen) — darum auf [Schedulers.boundedElastic] ausgelagert, NIE auf dem
 * Reactor-Netty-Event-Loop (dieselbe P0-Lehre wie [PrivacyController]/
 * [DiaryController]/[SpeakerController]).
 */
@RestController
class HomeEditController(
    private val registryAdapter: HaHomeRegistryAdapter,
    private val writer: RegistryWriter,
    private val auditLog: HomeEditAuditLog,
    @Value("\${HOSHI_HOME_EDIT_ENABLED:false}") private val editEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `GET /api/v1/home/edit/status` → `{editEnabled}` — die EINE Quelle, aus der
     * das FE erfährt, ob es Picker rendern darf. Byte-neutral bei Flag OFF (`false`).
     */
    @GetMapping("/api/v1/home/edit/status")
    fun status(): HomeEditStatus = HomeEditStatus(editEnabled = editEnabled)

    /**
     * `PUT /api/v1/home/entity/{entityId}/area` mit Body `{areaId}` — weist der
     * Entity die Area zu. Statuscodes s. Klassen-KDoc (200/404/409/400/502; 401 =
     * Wand). Nur der dünne Reactor-Rahmen: die eigentliche (blockierende) Arbeit
     * steckt in [assignAreaBlocking], ausgelagert auf [Schedulers.boundedElastic]
     * (Blocking-Hygiene, s. Klassen-KDoc).
     */
    @PutMapping("/api/v1/home/entity/{entityId}/area")
    fun assignArea(
        @PathVariable entityId: String,
        @RequestBody body: AreaAssignmentRequest,
    ): Mono<ResponseEntity<Any>> =
        Mono.fromCallable { assignAreaBlocking(entityId, body) }.subscribeOn(Schedulers.boundedElastic())

    /** Die blockierende Zuweisungs-Logik selbst — s. [assignArea] fürs Offload. */
    private fun assignAreaBlocking(entityId: String, body: AreaAssignmentRequest): ResponseEntity<Any> {
        if (!editEnabled) {
            // Flag zu: die Einstellung existiert bei diesem Deploy nicht ⇒ 409, kein Write.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(SettingsError("home-edit-off", FEATURE_ID, "Geräte-Zuordnung ist beim Deploy deaktiviert (HOSHI_HOME_EDIT_ENABLED)."))
        }
        val areaId = body.areaId?.trim().orEmpty()
        if (areaId.isBlank()) {
            return ResponseEntity.badRequest()
                .body(SettingsError("invalid-area", FEATURE_ID, "areaId fehlt oder ist leer."))
        }
        // Nur bekannte Areas — gegen den READ-Katalog (dieselbe eine Wahrheit).
        val snapshot = registryAdapter.registry()
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(SettingsError("home-edit-unreachable", FEATURE_ID, "Home Assistant ist gerade nicht erreichbar — Zuordnung nicht möglich."))
        if (snapshot.areas.none { it.areaId == areaId }) {
            return ResponseEntity.badRequest()
                .body(SettingsError("unknown-area", FEATURE_ID, "Unbekannte Area: $areaId."))
        }
        // read-first vollenden: die entityId muss im selben Snapshot existieren
        // (Area ODER unassigned) — sonst 404, KEIN Write-Aufruf. Trägt zugleich
        // die BISHERIGE Area für die Audit-Zeile (fromAreaId, null = unassigned).
        val lookup = snapshot.lookupEntity(entityId)
        if (!lookup.exists) {
            auditLog.record(ACTION_ASSIGN, entityId, fromAreaId = null, toAreaId = areaId, outcome = OUTCOME_REJECTED_UNKNOWN_ENTITY)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(SettingsError("unknown-entity", FEATURE_ID, "Unbekannte Entity: $entityId."))
        }

        // Echter Write über die offizielle WS-API (never-throw ⇒ Ok/Failed).
        return when (val outcome = writer.assignEntityArea(entityId, areaId)) {
            is RegistryWriteOutcome.Ok -> {
                auditLog.record(ACTION_ASSIGN, entityId, fromAreaId = lookup.currentAreaId, toAreaId = areaId, outcome = OUTCOME_OK)
                // read-first: der nächste Read holt frisch ⇒ die Karte wandert echt.
                registryAdapter.invalidate()
                ResponseEntity.ok(AreaAssignmentResult(entityId = entityId, areaId = areaId))
            }
            is RegistryWriteOutcome.Failed -> {
                auditLog.record(ACTION_ASSIGN, entityId, fromAreaId = lookup.currentAreaId, toAreaId = areaId, outcome = OUTCOME_FAILED, reason = outcome.reason)
                log.warn("[home-edit] Zuweisung {} → {} fehlgeschlagen: {}", entityId, areaId, outcome.reason)
                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(SettingsError("home-edit-write-failed", FEATURE_ID, "Home Assistant hat die Zuordnung nicht bestätigt."))
            }
        }
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [HomeRegistryController.FEATURE_ID]). */
        const val FEATURE_ID = "home-edit"

        /** Audit-Aktion für die Entity→Area-Zuweisung. */
        const val ACTION_ASSIGN = "entity.area.assign"

        /** Audit-`outcome`-Werte — stabile Strings, damit spätere Auswertung nicht rät. */
        const val OUTCOME_OK = "ok"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_REJECTED_UNKNOWN_ENTITY = "rejected_unknown_entity"
    }
}

/**
 * Ergebnis der Entity-Suche im [HomeRegistrySnapshot]: existiert die Entity
 * überhaupt (in irgendeiner Area ODER unter `unassigned`), und falls ja, in
 * welcher Area steht sie GERADE ([currentAreaId], `null` = unassigned)?
 */
private data class EntityLookup(val exists: Boolean, val currentAreaId: String?)

/**
 * Sucht [entityId] im ganzen Snapshot (Areas + unassigned) — die eine Wahrheit
 * dafür, ob die Entity dem Katalog bekannt ist, UND für ihre bisherige Area
 * (Audit-`fromAreaId`, s. [HomeEditController.assignAreaBlocking]).
 */
private fun HomeRegistrySnapshot.lookupEntity(entityId: String): EntityLookup {
    for (area in areas) {
        if (area.entities.any { it.entityId == entityId }) {
            return EntityLookup(exists = true, currentAreaId = area.areaId)
        }
    }
    if (unassigned.any { it.entityId == entityId }) return EntityLookup(exists = true, currentAreaId = null)
    return EntityLookup(exists = false, currentAreaId = null)
}

/** `GET .../edit/status`-Body: sagt dem FE, ob der Zuordnungs-Editor scharf ist. */
data class HomeEditStatus(val editEnabled: Boolean)

/** `PUT .../entity/{id}/area`-Body: die Ziel-Area. Nullable ⇒ ehrlicher 400 statt Jackson-500. */
data class AreaAssignmentRequest(val areaId: String? = null)

/** 200-Body nach erfolgreicher Zuweisung — die autoritative neue Zuordnung. */
data class AreaAssignmentResult(val entityId: String, val areaId: String)
