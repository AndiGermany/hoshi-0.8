package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Ergebnis EINES HA-Service-Calls. `never-throw` (exakt das Muster von
 * [RegistryWriteOutcome]): der [HaServiceCallClient] wirft nie nach außen —
 * jeder Fehler (kein Token, HA-Fehlerstatus, Timeout, Netz) wird zu [Failed].
 *
 * **Bewusst „Accepted", nicht „Ok":** HA quittiert einen Service-Call mit
 * HTTP 2xx, sobald es ihn ANGENOMMEN hat — nicht, wenn das Gerät die Tat
 * ausgeführt hat. Ein Sauger, der die Fahrt gleich wieder abbricht, hat
 * trotzdem eine 200 erzeugt. Der Name hält diese Grenze im Typ fest, damit
 * kein Aufrufer versehentlich Vollzug behauptet (Kagami-Regel: nie Vollzug
 * ohne Beleg — der Beleg für die WIRKUNG kommt aus dem State-Polling).
 */
sealed class ServiceCallOutcome {
    /** HA hat den Call angenommen ([httpStatus] ist der echte 2xx-Status). */
    data class Accepted(val httpStatus: Int) : ServiceCallOutcome()

    /**
     * HA hat abgelehnt oder der Call kam nicht durch. [httpStatus] ist HA's
     * echter Statuscode, falls es überhaupt einen gab (`null` = nie geantwortet:
     * Timeout/Netz/kein Token). [reason] ist eine kurze, token-freie Kennung
     * fürs Log und für die ehrliche Durchreichung an den Aufrufer.
     */
    data class Failed(val reason: String, val httpStatus: Int? = null) : ServiceCallOutcome()
}

/**
 * Der schmale Service-Call-Kontrakt, den der Web-Rand kennt — eine
 * `fun interface` aus demselben Grund wie [RegistryWriter]: ein Controller
 * bleibt ohne echte HA-Naht testbar (Fake-Lambda), und der
 * [HaServiceCallClient] bleibt die einzige reale Implementierung
 * (hexagonale Trennung).
 *
 * **Warum nicht [de.hoshi.core.port.ToolPort]/[HaToolPort] wiederverwenden:**
 * jener Port ist der SPRACH-Executor — er liefert fertig formulierte deutsche
 * Quittungs-Phrasen ([de.hoshi.core.tools.ToolResult]) und verschluckt dabei
 * den HTTP-Status, den ein REST-Rand ehrlich durchreichen muss. Für die Kachel
 * brauchen wir genau das Gegenteil: rohes Outcome + echter Statuscode, keine
 * Phrase. Zwei Konsumenten, zwei Verträge, EIN HA-Muster (vgl. die bewusste
 * Trennung [HaAreaCatalogAdapter] ↔ [HaHomeRegistryAdapter]).
 */
fun interface HaServiceCaller {
    /** Ruft `POST /api/services/{domain}/{service}` mit `{"entity_id": …}`. */
    fun callService(domain: String, service: String, entityId: String): ServiceCallOutcome
}

/**
 * **HaServiceCallClient** — die REST-Schreibnaht für gezielte Service-Calls auf
 * EINE Entity (`POST {baseUrl}/api/services/{domain}/{service}`, Body
 * `{"entity_id": "<id>"}`), das dokumentierte HA-Muster
 * [EXTERN https://developers.home-assistant.io/docs/api/rest/].
 *
 * **Synchroner JDK-[HttpClient]** (kein `WebClient`), exakt wie [HaToolPort]:
 * dieselbe Naht, dasselbe Timeout-Muster, und der Aufrufer lagert den Call
 * ohnehin auf `Schedulers.boundedElastic` aus (Blocking-Hygiene am Web-Rand).
 *
 * Der Token wird NIE geloggt; der Response-Body wird bewusst verworfen
 * (`discarding`) — für die Ehrlichkeit zählt der Statuscode, und ein
 * HA-Fehlerbody kann Entity-/Setup-Details tragen, die nicht ins Log gehören.
 */
class HaServiceCallClient(
    baseUrl: String,
    private val token: String?,
    /** Budget für Connect UND für den Call selbst (je einzeln) — Default wie [HaToolPort]. */
    private val timeoutMs: Long = 5000,
    /** Injizierbar für Tests. */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build(),
    private val mapper: ObjectMapper = ObjectMapper(),
) : HaServiceCaller {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')

    override fun callService(domain: String, service: String, entityId: String): ServiceCallOutcome {
        val t = token
        if (t.isNullOrBlank()) {
            // Ehrlich: ohne Token gibt es keinen Call — kein Versuch, kein Fake-Erfolg.
            return ServiceCallOutcome.Failed("no-token")
        }
        return try {
            val body = mapper.writeValueAsString(mapOf("entity_id" to entityId))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/services/$domain/$service"))
                .header("Authorization", "Bearer $t")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding())
            val status = resp.statusCode()
            if (status in 200..299) {
                ServiceCallOutcome.Accepted(status)
            } else {
                // Status zählt, nicht der Body (s. Klassen-KDoc).
                log.warn("[ha-service] {}.{} auf {} -> HTTP {}", domain, service, entityId, status)
                ServiceCallOutcome.Failed("ha-http-$status", httpStatus = status)
            }
        } catch (e: Exception) {
            // never-throw: Timeout/Netz/Serialisierung enden warm als Failed.
            log.warn("[ha-service] {}.{} auf {} warf: {}", domain, service, entityId, e.message)
            ServiceCallOutcome.Failed("exception:${e.javaClass.simpleName}")
        }
    }
}
