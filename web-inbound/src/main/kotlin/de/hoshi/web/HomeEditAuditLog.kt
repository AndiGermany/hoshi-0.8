package de.hoshi.web

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock

/**
 * Eine Audit-Zeile pro Registry-Schreibversuch (Scheibe 2 des
 * Geräte-Zuordnungs-Konzepts, Verfassungs-Regel 2: „jede Schreiboperation einzeln
 * geloggt"). Trägt **kein Secret** — kein Token, kein HA-Interna: nur WER (der
 * authentifizierte API-Aufrufer — im Ein-Haushalt-Modell gibt es keine feinere
 * Nutzer-Identität, darum die feste Quelle `api`), WAS ([action] + [entityId] +
 * [fromAreaId] → [toAreaId]), WANN ([timestamp]) und der [outcome]
 * (`ok`/`failed`/`rejected_unknown_entity`, [reason] nur bei `failed` — die
 * HA-Fehlerklasse, nie Klartext).
 */
data class HomeEditAuditEntry(
    /** ISO-8601, via injizierte [Clock]. */
    val timestamp: String,
    /** WER — der authentifizierte Perimeter-Aufrufer (Ein-Haushalt: fest `api`). */
    val actor: String,
    /** WAS — die Schreib-Operation, z.B. `entity.area.assign`. */
    val action: String,
    /** Die betroffene HA-`entity_id` (kein Secret). */
    val entityId: String,
    /** Die BISHERIGE HA-`area_id` laut Snapshot vor diesem Versuch — `null` = unassigned. */
    val fromAreaId: String?,
    /** Die ANGEFRAGTE neue HA-`area_id` (kein Secret). */
    val toAreaId: String,
    /** `ok` = HA hat bestätigt, `failed` = abgelehnt/unerreichbar, `rejected_unknown_entity` = gar nicht erst versucht. */
    val outcome: String,
    /** Nur bei `failed`: die technische Ursache (HA-Fehlerklasse/Timeout), nie ein Secret. */
    val reason: String? = null,
)

/**
 * **HomeEditAuditLog** — append-only JSONL-Audit der Registry-Schreibvorgänge.
 *
 * Muster [de.hoshi.core.cloud.CloudAuditLog]: rein + testbar (Datei-[path] und
 * [clock] injiziert, kein `now()`, kein hartkodiertes Verzeichnis), append-only
 * (bei Crash geht max. die letzte Zeile verloren), **Schreibfehler sind nie
 * fatal, aber nie stumm** ([record]/[append] schlucken jede Exception — ein
 * voller/kaputter Datenträger darf einen Write-Turn nie killen, der eigentliche
 * HA-Write ist davon unberührt, das Audit ist die Nachricht darüber, nicht die
 * Tat selbst — aber der Ausfall landet als WARN im Log, sonst verschwindet er
 * lautlos und niemand merkt, dass die Spur fehlt).
 *
 * Bewusst hand-gerolltes JSON (ASCII-Escaper, s. [jsonStr]: ALLE Steuerzeichen
 * < 0x20 werden `\u00XX`-escaped, nicht nur die benannten — eine geschmuggelte
 * entityId mit z.B. NUL darf die JSONL-Zeile nie zerbrechen), damit die Zeile
 * ohne Jackson deterministisch und ohne Reflection entsteht (exakt wie
 * [CloudAuditLog]).
 */
class HomeEditAuditLog(
    private val path: Path,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Schreibt EINE Audit-Zeile für einen Schreibversuch. `actor` fest = `api` (s. KDoc). */
    fun record(action: String, entityId: String, fromAreaId: String?, toAreaId: String, outcome: String, reason: String? = null) {
        append(
            HomeEditAuditEntry(
                timestamp = clock.instant().toString(),
                actor = ACTOR_API,
                action = action,
                entityId = entityId,
                fromAreaId = fromAreaId,
                toAreaId = toAreaId,
                outcome = outcome,
                reason = reason,
            ),
        )
    }

    /** Hängt eine [entry] an. Robust: jeder I/O-Fehler wird geschluckt, aber als WARN sichtbar (s. Klassen-KDoc). */
    fun append(entry: HomeEditAuditEntry) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                path,
                render(entry) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure { e ->
            log.warn("[home-edit] Audit-Zeile konnte nicht geschrieben werden (Write bleibt davon unberührt): {}", e.toString())
        }
    }

    /** Serialisiert die Zeile als kompaktes JSON-Objekt; `fromAreaId` roh `null` wenn unassigned, `reason` nur wenn gesetzt. */
    private fun render(entry: HomeEditAuditEntry): String = buildString {
        append('{')
        append(jsonStr("timestamp")).append(':').append(jsonStr(entry.timestamp)).append(',')
        append(jsonStr("actor")).append(':').append(jsonStr(entry.actor)).append(',')
        append(jsonStr("action")).append(':').append(jsonStr(entry.action)).append(',')
        append(jsonStr("entityId")).append(':').append(jsonStr(entry.entityId)).append(',')
        append(jsonStr("fromAreaId")).append(':').append(entry.fromAreaId?.let { jsonStr(it) } ?: "null").append(',')
        append(jsonStr("toAreaId")).append(':').append(jsonStr(entry.toAreaId)).append(',')
        append(jsonStr("outcome")).append(':').append(jsonStr(entry.outcome))
        entry.reason?.let { append(',').append(jsonStr("reason")).append(':').append(jsonStr(it)) }
        append('}')
    }

    /**
     * Minimaler JSON-String-Escaper für unsere kontrollierten Felder — inkl.
     * ALLER Steuerzeichen < 0x20 (nicht nur `" \ \n \r \t`): eine entityId/areaId
     * kommt vom HTTP-Rand (`@PathVariable`/`@RequestBody`) und ist damit NICHT
     * vertrauenswürdig — ein rohes `U+0000`/`U+0001`/… in der Zeile würde JSONL
     * zerbrechen (Zeile nicht mehr parsbar); die benannten Escapes zuerst, der
     * Rest generisch als `\u00XX`.
     */
    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    companion object {
        /** Ein-Haushalt-Modell: keine feingranulare Nutzer-Identität — der Perimeter-Token ist „wer". */
        const val ACTOR_API = "api"
    }
}
