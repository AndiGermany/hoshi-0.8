package de.hoshi.web

import de.hoshi.kernel.PerimeterPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * **PerimeterWebFilter** — die LIVE-Auth-Wand am gebooteten WebFlux-Context.
 *
 * Verdrahtet die reine Entscheidungslogik des [PerimeterPort] (aus
 * `:capability-kernel`, Spring-frei) in einen echten reaktiven
 * [org.springframework.web.server.WebFilter]. Pro Request:
 *  - Pfad aus `exchange.request.path.value()`,
 *  - Loopback aus `remoteAddress?.address?.isLoopbackAddress`
 *    (fehlende Remote-Address = fail-closed, also NICHT-loopback),
 *  - präsentierter Token aus `Authorization: Bearer <t>` ODER `X-Hoshi-Token`
 *    (Pattern aus 0.5 `IngressAuthFilter`; der WS-`?token=`-Sonderfall kommt
 *    erst mit dem WS-Adapter),
 *  - [PerimeterPort.authorize] entscheidet.
 *
 * Bei [PerimeterPort.PerimeterDecision.Unauthorized] → 401 + `setComplete()`
 * (kein Body-Leak, Token wird nie geloggt). Sonst `chain.filter(exchange)`.
 *
 * Konfiguration:
 *  - `hoshi.perimeter.enabled` (default true) — Wand an/aus.
 *  - `hoshi.perimeter.token` (aus ENV `HOSHI_API_TOKEN`) — der erwartete Token.
 *    Leer + enabled = fail-closed (jeder Nicht-Loopback auf geschützte Pfade 401).
 */
@Component
@Order(FILTER_ORDER)
class PerimeterWebFilter(
    @Value("\${hoshi.perimeter.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${hoshi.perimeter.token:\${HOSHI_API_TOKEN:}}")
    private val configuredToken: String = "",
) : WebFilter {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Der Trust-Kernel — reine, testbare Entscheidungslogik, mit den injizierten Werten konstruiert. */
    private val perimeter = PerimeterPort(enabled = enabled, configuredToken = configuredToken)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        // Easter-Egg-Endpoints (Crew/Fortune/About) sind bewusst OEFFENTLICH — wie
        // exakt `/api/health`, damit das FE die Crew ohne Token zeigen kann. Vor der
        // Wand kurzgeschlossen (kein Token, kein Loopback noetig); rein lesend, kein
        // Geheimnis. Scoped auf die drei genauen Pfade ⇒ `/api/v1/...` bleibt sonst
        // geschuetzt (byte-neutral fuer den Rest der Wand).
        if (path in PUBLIC_EASTER_EGG_PATHS) return chain.filter(exchange)
        // Fehlende Remote-Address = fail-closed (als NICHT-loopback behandeln).
        val isLoopback = exchange.request.remoteAddress?.address?.isLoopbackAddress ?: false
        val presented = presentedToken(exchange)

        return when (val decision = perimeter.authorize(path, isLoopback, presented)) {
            is PerimeterPort.PerimeterDecision.Allow -> chain.filter(exchange)
            is PerimeterPort.PerimeterDecision.Unauthorized -> {
                // Grund intern fürs Log (nie der Token), nach außen nur 401.
                log.warn("[perimeter] 401 {} — {}", path, decision.reason)
                exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                exchange.response.setComplete()
            }
        }
    }

    /**
     * Präsentierter Token: `Authorization: Bearer <t>` zuerst, sonst
     * `X-Hoshi-Token` (Pattern aus 0.5 `IngressAuthFilter`). Bereits ohne
     * `Bearer `-Präfix; `null` wenn keiner präsentiert.
     *
     * **WS-Handshake-Sonderfall:** Browser/Geräte können beim WebSocket-Upgrade
     * KEINE Header setzen — der Token kommt als `?token=`-Query. Nur für `/ws/`-Pfade
     * akzeptieren (scoped ⇒ `/api/` bleibt header-only, byte-neutral), und nur wenn
     * kein Header-Token präsentiert wurde. Der [AudioWebSocketHandler] prüft denselben
     * Query-Token zusätzlich selbst (Defense-in-Depth, Close 1008).
     */
    private fun presentedToken(exchange: ServerWebExchange): String? {
        val auth = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length)
        }
        exchange.request.headers.getFirst(X_HOSHI_TOKEN)?.let { return it }
        if (exchange.request.path.value().startsWith(PerimeterPort.WS_PATH_PREFIX)) {
            return exchange.request.queryParams.getFirst("token")
        }
        return null
    }

    companion object {
        const val BEARER_PREFIX = "Bearer "
        const val X_HOSHI_TOKEN = "X-Hoshi-Token"

        /**
         * Oeffentliche Easter-Egg-Pfade — wie `/api/health` ohne Token erreichbar
         * (rein lesendes Crew-Ritual, kein Geheimnis). Exakte Pfade, damit der
         * Rest von `/api/v1/...` geschuetzt bleibt.
         */
        val PUBLIC_EASTER_EGG_PATHS = setOf("/api/v1/crew", "/api/v1/fortune", "/api/v1/about")
    }
}

/** Auth zuerst (hohe Priorität) — vor allen App-Filtern. */
const val FILTER_ORDER = -100
