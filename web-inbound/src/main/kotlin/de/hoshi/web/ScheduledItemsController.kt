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
 *  - `DELETE /api/v1/scheduled/{id}` — **loescht diesen Timer/Wecker in JEDER Wahrheit**:
 *    den geplanten Eintrag ([ScheduledItemPort.cancel]) UND ein bereits laufendes Klingeln
 *    derselben id ([FiredItemsStore.ack]). 204 wenn eines von beiden entfernt wurde, 404
 *    nur wenn wirklich NICHTS zu dieser id existierte.
 *  - `DELETE /api/v1/scheduled` — loescht ALLE aktiven Items ([ScheduledItemPort.cancelAll])
 *    UND stoppt alle laufenden Klingeln; 200 mit `{count}` (wie viele der Mensch losgeworden
 *    ist — geplante + gestoppte; leer ⇒ `{count:0}`).
 *
 * **Warum das Klingeln mitgeht (Andi-Live-Befund 23.08.2026, „Wecker gestellt, geloescht —
 * und heute morgen beim Ausklappen ging er trotzdem"):** ein gefeuertes Item hat den
 * [ScheduledItemPort] verlassen und lebt im [FiredItemsStore] weiter (unbestaetigt, Datei-
 * persistent, ueber Neustarts hinweg — jeder FE-Poll liefert es erneut aus). Der FE-Poll ist
 * bis zu 15 s alt (bei dunklem Display pausiert sein Intervall ganz), die Loesch-Zeile steht
 * also noch da, wenn der Fire-Service laengst gefeuert hat. Frueher antwortete der Loeschweg
 * dann 404 — was das FE laut eigenem Contract als „weg is weg" wertet — und das Klingeln
 * ueberlebte seinen Wecker. Der VOICE-Loeschweg vereinheitlicht beide Wahrheiten seit dem
 * 15.07-Fix ([de.hoshi.core.pipeline.TimerFastpath] + [de.hoshi.core.port.RingingItemPort]:
 * „stoppe den Timer" beendet auch ein laufendes Klingeln); dieser HTTP-Weg zieht damit
 * gleich — EINE Loesch-Semantik fuer beide Wege. Riegel: DeletedAlarmNeverRingsTest.
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
    /**
     * Die ZWEITE Wahrheit derselben Wecker: bereits gefeuerte, noch unbestaetigte Klingeln.
     * Der Loeschweg muss sie mitraeumen, sonst ueberlebt das Klingeln seinen Wecker (s.
     * Klassen-KDoc). Dieselbe Bean, die auch der [FiredItemsController] bedient — bei
     * eingeschalteter Persistenz ist es sogar dasselbe Objekt wie [store].
     */
    private val fired: FiredItemsStore,
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

    /**
     * Loescht diesen Timer/Wecker vollstaendig: geplant UND (falls er in der Zwischenzeit
     * gefeuert hat) klingelnd. Bewusst BEIDE Aufrufe — nicht `||`-kurzgeschlossen: eine id
     * kann nicht in beiden Wahrheiten stehen, aber ein stiller Rest waere genau der Bug.
     * 404 nur, wenn zu dieser id wirklich nichts (mehr) existierte.
     */
    @DeleteMapping("/api/v1/scheduled/{id}")
    fun cancel(@PathVariable id: String): ResponseEntity<Void> {
        val geplantEntfernt = store.cancel(id)
        val klingelnGestoppt = fired.ack(id)
        return if (geplantEntfernt || klingelnGestoppt) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
    }

    /**
     * Loescht ALLES, was der Mensch als „laufend" sieht: geplante Items UND laufende
     * Klingeln. `count` zaehlt beides zusammen (was er losgeworden ist). Das Klingeln wird
     * ueber einen Snapshot von [FiredItemsStore.pending] quittiert — ein in derselben
     * Sekunde neu gefeuertes Item bleibt damit ehrlich erhalten (es war beim Loeschen noch
     * nicht sichtbar), statt blind alles zu schlucken.
     */
    @DeleteMapping("/api/v1/scheduled")
    fun cancelAll(): CancelAllResponse {
        val geplant = store.cancelAll()
        val gestoppt = fired.pending(clock.millis()).count { fired.ack(it.id) }
        return CancelAllResponse(count = geplant + gestoppt)
    }
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
 * Antwort von `DELETE /api/v1/scheduled` (alle stornieren): `{count}` — die Anzahl dessen,
 * was der Mensch tatsaechlich losgeworden ist: entfernte aktive Items PLUS gestoppte
 * laufende Klingeln (leerer Store ⇒ `{count:0}`).
 */
data class CancelAllResponse(val count: Int)
