package de.hoshi.web

import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import de.hoshi.adapters.ha.HaServiceCaller
import de.hoshi.adapters.ha.ServiceCallOutcome
import de.hoshi.adapters.ha.VacuumFamily
import de.hoshi.core.port.TurnTracePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * **VacuumActionController** — die zwei Knöpfe der Sauger-Kachel (Andi
 * 2026-08-21, wörtlich: „Können wir den Sauger starten und nach Hause fahren
 * lassen?"): `vacuum.start` und `vacuum.return_to_base`.
 *
 * `POST /api/v1/home/vacuum/{action}` mit `action ∈ start|return_to_base`.
 * Liegt AUTOMATISCH hinter der [PerimeterWebFilter]-Wand (`/api/v1/…` ⇒ ohne/
 * mit falschem Token 401) — kein eigener Auth-Code (Muster
 * [HomeEditController]).
 *
 * ## Die Kagami-Regel: die Antwort behauptet NIE Vollzug
 * Ein 200 heißt hier ausdrücklich **„HA hat den Auftrag angenommen"**, nicht
 * „der Sauger fährt" — darum das Feld [VacuumActionResult.accepted] (nie
 * `done`/`success`) und der mitgelieferte [VacuumActionResult.haStatus] als
 * Beleg. Die Kachel-WAHRHEIT kommt weiterhin AUSSCHLIESSLICH aus dem
 * State-Polling (`GET /api/v1/home/registry`); dieser Endpoint schreibt den
 * Zustand NICHT optimistisch um und liefert auch keinen Zustand zurück.
 * Was er tut: [HaHomeRegistryAdapter.invalidate] nach einem angenommenen Call,
 * damit der nächste Poll FRISCH bei HA nachliest (read-first, exakt wie
 * [HomeEditController]) — schneller zur Wahrheit, ohne sie zu raten. Sieht der
 * nächste Poll noch `docked`, ist das die Wahrheit und keine Panne.
 *
 * Ein HA-Fehler wird EHRLICH DURCHGEREICHT: HTTP 502 mit HA's eigenem
 * Statuscode in der Meldung ([ServiceCallOutcome.Failed.httpStatus]) — nie ein
 * geschöntes 200, nie ein verschwiegener Fehlschlag.
 *
 * ## Statuscodes
 *  - **200** — HA hat angenommen (`{action, entityId, accepted:true, haStatus}`).
 *  - **400** `vacuum-unknown-action` — action ist weder `start` noch `return_to_base`.
 *  - **409** `vacuum-off` — HA ist beim Deploy aus (`HOSHI_HA_ENABLED`), also
 *    existiert die Tat hier nicht. KEIN neues Flag: der Endpoint fährt unter
 *    derselben bestehenden Tat-Decke mit, die schon den Sprach-Executor
 *    ([PipelineConfig.toolPort]) und die Registry freigibt — ein eigenes Flag
 *    für dieselbe HA-Instanz mit demselben Token wäre Bürokratie ohne neues
 *    Risiko (Andis Auftrag IST das Go).
 *  - **409** `vacuum-not-found` — die Registry kennt gar keine `vacuum.*`-Entity.
 *  - **409** `vacuum-asleep` — der Sauger ist gerade NICHT live bei HA
 *    ([VacuumFamily.isLive]); es wurde NICHTS gesendet (s. u.).
 *  - **502** `vacuum-unreachable` — die Registry ließ sich nie laden, wir wissen
 *    also nicht einmal, OB es einen Sauger gibt. Blind schalten wäre unehrlich.
 *  - **502** `vacuum-action-failed` — HA hat den Call NICHT angenommen.
 *  - **401** — die Perimeter-Wand (generisch, s. [PerimeterWallTest]).
 *
 * ## Das Zustellbarkeits-Tor — und die Prämisse, an der es früher scheiterte
 * **Hier stand bis 23.08.2026 das Gegenteil.** Der ursprüngliche Entwurf prüfte
 * bewusst nur die EXISTENZ der Entity und nicht ihre Erreichbarkeit, mit der
 * Begründung: „ob HA den Call an ein schlafendes Gerät zustellen kann,
 * beantwortet HA selbst — und diese Antwort reichen wir unverfälscht durch."
 * Die Abwägung war in sich schlüssig, ihre Prämisse ist **widerlegt**: HA
 * beantwortet diese Frage NICHT. `helpers/service.py#entity_service_call`
 * (HA 2025.4.4, Z. 976–1002) filtert eine `unavailable` Entity mit
 * `if not entity.available: continue` heraus und endet dann auf
 * `if not entities: return None` — **ohne Exception, ohne Log**. Der REST-View
 * antwortet darauf mit **HTTP 200** und einer leeren Liste geänderter States.
 * Ein 2xx bedeutet für einen schlafenden Sauger also GAR NICHTS. (Ironie der
 * Stelle: die Feature-Prüfung direkt darunter WIRFT — hätten wir den falschen
 * Service gerufen, hätten wir einen ehrlichen Fehler bekommen.)
 *
 * Folge (Andi 23.08. wörtlich: „da steht, dass der auftrag an HA gegeben wurde,
 * aber der sauger startet nicht"): der Knopf lieferte ein Häkchen für einen
 * Auftrag, den nie jemand bekam — in ~91 % aller Klicks, denn so oft ist dieses
 * Gerät `unavailable` (gemessen, `RESULT.md` §2). Darum prüft dieser Controller
 * jetzt VOR dem Call [VacuumFamily.isLive] und sendet gar nicht erst, was HA
 * beweisbar verwirft: `409 vacuum-asleep`, `toolCallRan=false`, mit einem Satz,
 * der die Lage benennt statt sie zu verschweigen. Ein Knopf, der ehrlich sagt
 * „er schläft gerade", ist besser als einer, der beweisbar nichts tut und dabei
 * ein Häkchen zeigt.
 *
 * **Der Cache-Carry bleibt für die ANZEIGE richtig und wird hier NICHT
 * rückgängig gemacht:** [VacuumFamily.carryCache] zeigt den schlafenden Sauger
 * weiter als `docked` (sein Normalzustand, kein Ausfall) — nur als Grundlage
 * einer TAT taugt ein gemerkter Zustand nicht. Genau diese Trennung steckt in
 * [VacuumFamily.isLive] (dort auch, warum `state == "docked"` allein nicht
 * reicht). Wie der Vollzugs-Satz künftig die WIRKUNG statt der Annahme prüfen
 * kann, steht in `RESULT.md` §7 (Stufe B, Wirkungs-Fenster über den bestehenden
 * Registry-Poll).
 *
 * ## Diary
 * JEDE Aktion hinterlässt eine Zeile ([TurnDiaryTap.recordHomeAction]) — auch
 * die abgelehnte. `toolCallRan` trennt darin „HA wurde wirklich gerufen" von
 * „vorher abgelehnt" (Kreuzbeweis-Naht, s. dort).
 *
 * **Blocking-Hygiene:** Registry-Read und HA-Call sind blockierende I/O — darum
 * auf [Schedulers.boundedElastic], NIE auf dem Reactor-Netty-Event-Loop
 * (dieselbe P0-Lehre wie [HomeEditController]).
 */
@RestController
class VacuumActionController(
    private val registryAdapter: HaHomeRegistryAdapter,
    private val serviceCaller: HaServiceCaller,
    @Value("\${HOSHI_HA_ENABLED:false}") private val haEnabled: Boolean,
    private val turnTrace: TurnTracePort = TurnTracePort.NOOP,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `POST /api/v1/home/vacuum/{action}` — s. Klassen-KDoc für Semantik und
     * Statuscodes. Nur der dünne Reactor-Rahmen; die blockierende Arbeit steckt
     * in [actionBlocking].
     */
    @PostMapping("/api/v1/home/vacuum/{action}")
    fun action(@PathVariable action: String): Mono<ResponseEntity<Any>> =
        Mono.fromCallable { actionBlocking(action) }.subscribeOn(Schedulers.boundedElastic())

    /** Die blockierende Tat-Logik selbst — s. [action] fürs Offload. */
    private fun actionBlocking(action: String): ResponseEntity<Any> {
        val startedAt = System.nanoTime()

        /** Schreibt die Diary-Zeile und gibt die Antwort zurück (ein Ausgang, keine vergessene Zeile). */
        fun done(response: ResponseEntity<Any>, category: String, toolCallRan: Boolean, error: String?): ResponseEntity<Any> {
            TurnDiaryTap.recordHomeAction(
                turnTrace = turnTrace,
                category = category,
                toolCallRan = toolCallRan,
                error = error,
                totalMs = (System.nanoTime() - startedAt) / 1_000_000,
            )
            return response
        }

        val service = ALLOWED_ACTIONS[action]
            ?: return done(
                ResponseEntity.badRequest()
                    .body(SettingsError(ERROR_UNKNOWN_ACTION, FEATURE_ID, "Unbekannte Sauger-Aktion: $action.")),
                category = CATEGORY_UNKNOWN,
                toolCallRan = false,
                error = ERROR_UNKNOWN_ACTION,
            )
        val category = categoryOf(service)

        if (!haEnabled) {
            return done(
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(SettingsError(ERROR_OFF, FEATURE_ID, "Home Assistant ist beim Deploy deaktiviert (HOSHI_HA_ENABLED).")),
                category, toolCallRan = false, error = ERROR_OFF,
            )
        }

        // Aus DERSELBEN einen Wahrheit, die auch die Kachel füllt — kein zweites Register.
        val snapshot = registryAdapter.registry()
            ?: return done(
                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(SettingsError(ERROR_UNREACHABLE, FEATURE_ID, "Home Assistant ist gerade nicht erreichbar — die Aktion wurde NICHT gesendet.")),
                category, toolCallRan = false, error = ERROR_UNREACHABLE,
            )
        val vacuum = VacuumFamily.find(snapshot)
            ?: return done(
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(SettingsError(ERROR_NOT_FOUND, FEATURE_ID, "In Home Assistant ist kein Sauger eingerichtet.")),
                category, toolCallRan = false, error = ERROR_NOT_FOUND,
            )

        // Zustellbarkeits-Tor (s. Klassen-KDoc): HA quittiert einen Call auf eine
        // schlafende Entity mit 200 und verwirft ihn — ein Versuch wäre also kein
        // Versuch, sondern nur ein Häkchen. Lieber nicht senden und es sagen.
        if (!VacuumFamily.isLive(vacuum)) {
            return done(
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                        SettingsError(
                            ERROR_ASLEEP,
                            FEATURE_ID,
                            "Der Sauger ist gerade im Energiesparmodus und für Home Assistant nicht erreichbar. " +
                                "Home Assistant würde den Auftrag zwar annehmen, ihn aber nicht zustellen — " +
                                "darum wurde NICHTS gesendet. Sobald er wieder wach ist, geht der Knopf.",
                        ),
                    ),
                category, toolCallRan = false, error = ERROR_ASLEEP,
            )
        }

        // Ab hier läuft der Executor WIRKLICH ⇒ toolCallRan=true, egal wie es ausgeht.
        return when (val outcome = serviceCaller.callService(VacuumFamily.DOMAIN, service, vacuum.entityId)) {
            is ServiceCallOutcome.Accepted -> {
                // read-first: der nächste Poll liest FRISCH bei HA nach (kein optimistisches Umschreiben).
                registryAdapter.invalidate()
                done(
                    ResponseEntity.ok(
                        VacuumActionResult(
                            action = action,
                            entityId = vacuum.entityId,
                            accepted = true,
                            haStatus = outcome.httpStatus,
                        ),
                    ),
                    category, toolCallRan = true, error = null,
                )
            }
            is ServiceCallOutcome.Failed -> {
                log.warn("[vacuum] {} auf {} nicht angenommen: {}", service, vacuum.entityId, outcome.reason)
                done(
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(SettingsError(ERROR_ACTION_FAILED, FEATURE_ID, haMessage(outcome))),
                    category, toolCallRan = true, error = outcome.reason,
                )
            }
        }
    }

    /** Ehrliche Kurzmeldung: HA's eigener Statuscode, wenn es überhaupt geantwortet hat. */
    private fun haMessage(outcome: ServiceCallOutcome.Failed): String =
        outcome.httpStatus
            ?.let { "Home Assistant hat die Aktion abgelehnt (HTTP $it)." }
            ?: "Home Assistant hat nicht geantwortet (${outcome.reason})."

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [HomeRegistryController.FEATURE_ID]). */
        const val FEATURE_ID = "vacuum-action"

        /**
         * Die ERLAUBTEN Aktionen — eine Allowlist, keine Durchreichung des
         * Pfad-Segments an HA. Der `{action}`-Pfadteil kommt aus dem Browser;
         * ungeprüft weitergereicht wäre er ein Weg, JEDEN `vacuum.*`-Service zu
         * rufen (`send_command`, `set_fan_speed`, …). Zwei Knöpfe waren der
         * Auftrag, zwei Services stehen hier — mehr kommt bewusst nur durch
         * einen Commit dazu, nie durch eine URL.
         */
        val ALLOWED_ACTIONS: Map<String, String> = mapOf(
            "start" to "start",
            "return_to_base" to "return_to_base",
        )

        const val ERROR_UNKNOWN_ACTION = "vacuum-unknown-action"
        const val ERROR_OFF = "vacuum-off"
        const val ERROR_NOT_FOUND = "vacuum-not-found"

        /**
         * Der Sauger existiert, ist aber gerade nicht live bei HA — ein
         * Zustands-Konflikt, kein Serverfehler, darum 409 wie [ERROR_OFF]/
         * [ERROR_NOT_FOUND] (und bewusst kein 503: dessen Retry-Semantik gehört
         * Proxys, nicht einem schlafenden Roboter). S. Klassen-KDoc.
         */
        const val ERROR_ASLEEP = "vacuum-asleep"
        const val ERROR_UNREACHABLE = "vacuum-unreachable"
        const val ERROR_ACTION_FAILED = "vacuum-action-failed"

        /** Diary-Kategorie einer nicht einmal erkannten Aktion. */
        const val CATEGORY_UNKNOWN = "HOME_VACUUM_UNKNOWN"

        /**
         * Diary-Kategorie je Tat (`HOME_VACUUM_START` / `HOME_VACUUM_RETURN_TO_BASE`):
         * die Kategorie trägt WELCHE Tat gemeint war, ohne dafür ein neues
         * [de.hoshi.core.port.TurnTrace]-Feld zu brauchen — das Feld `category`
         * ist genau dafür ein freier String (s. dessen KDoc).
         */
        fun categoryOf(service: String): String = "HOME_VACUUM_${service.uppercase()}"
    }
}

/**
 * 200-Body einer angenommenen Sauger-Aktion. **[accepted] heißt „HA hat den
 * Auftrag angenommen", NICHT „der Sauger fährt"** — der Name ist die Grenze
 * (s. [VacuumActionController]-KDoc, Kagami-Regel). [haStatus] ist HA's echter
 * Statuscode als Beleg. Bewusst OHNE Zustandsfeld: den Zustand liefert
 * ausschließlich das State-Polling.
 */
data class VacuumActionResult(
    val action: String,
    val entityId: String,
    val accepted: Boolean,
    val haStatus: Int,
)
