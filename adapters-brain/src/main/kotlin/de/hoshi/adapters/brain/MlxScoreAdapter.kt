package de.hoshi.adapters.brain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.port.SttSurprisal
import de.hoshi.core.port.SttSurprisalPort
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **MlxScoreAdapter — der Verhör-Detektor-Adapter (S1).** Spricht `POST
 * /v1/score` (`{"text": "<transkript>"}` → `{"tokens":[...],"logprobs":[...],
 * "mean_surprisal":<float>,"max_surprisal":<float>,"token_count":<int>,
 * "ms":<int>}`) gegen denselben e4b-Sidecar wie [MlxBrainAdapter] (Port 8041)
 * und implementiert den hexagonalen [SttSurprisalPort].
 *
 * **EHRLICH (2026-07-07):** der `/v1/score`-Endpoint existiert im heutigen
 * `server_e4b.py` noch NICHT (paralleler Bau, anderer Pod) — dieser Adapter
 * ist darum VOLLSTÄNDIG null-tolerant gebaut: ein 404 (Endpoint fehlt), jeder
 * Non-200-Status, ein Verbindungsfehler, der harte Timeout ODER ein
 * kaputtes/unvollständiges JSON kollabieren ALLE auf `Mono.empty()` — NIE ein
 * `Mono.error`, NIE ein geworfener Fehler, NIE Error-Spam (nur EIN `debug`-Log
 * je Fehlschlag, kein `warn`/`error` — solange der Endpoint fehlt, ist ein
 * 404 der ERWARTETE Normalfall, kein Betriebsproblem). Der Konsument
 * ([de.hoshi.core.pipeline.TurnOrchestrator]) behandelt eine leere Mono exakt
 * wie „nicht gemessen" — kein Turn-Impact, s. [SttSurprisalPort]-KDoc.
 *
 * **Enger Timeout (≤500 ms, Default [DEFAULT_TIMEOUT_MS]):** die Messung darf
 * den Turn nie spürbar aufhalten (Latenz-Budget, s. Orchestrator-KDoc
 * `withSttSurprisal`). Dieser Adapter kapselt den Timeout SELBST — der
 * Aufrufer legt zusätzlich denselben Deckel obendrauf (Verteidigung in der
 * Tiefe, kein Vertrauen in eine einzelne Schicht).
 *
 * **Ein-Zeilen-Wiring:** [baseUrl] ist bewusst derselbe Konstruktor-Parameter
 * wie bei [MlxBrainAdapter] (`hoshi.brain.base-url`) — der Score-Endpoint
 * lebt am SELBEN Sidecar, kein zweiter Host/Port nötig.
 */
class MlxScoreAdapter(
    baseUrl: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : SttSurprisalPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        .codecs { it.defaultCodecs().maxInMemorySize(1 * 1024 * 1024) }
        .build()

    /**
     * `POST /v1/score` mit `{"text": [text]}`. Liefert GENAU EIN [SttSurprisal]
     * bei einer sauberen 200-Antwort mit allen drei Pflichtfeldern
     * (`mean_surprisal`/`max_surprisal`/`token_count`, alle numerisch), sonst
     * `Mono.empty()` — wirft NIE (s. Klassen-KDoc).
     */
    override fun score(text: String): Mono<SttSurprisal> =
        client.post().uri("/v1/score")
            .bodyValue(mapOf("text" to text))
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(Duration.ofMillis(timeoutMs))
            .flatMap { body -> parseScore(body)?.let { Mono.just(it) } ?: Mono.empty() }
            .onErrorResume { e ->
                // Kein warn/error: solange /v1/score im Prod-server_e4b fehlt, ist
                // ein 404/Connect-Refused der ERWARTETE Normalfall (s. Klassen-KDoc).
                log.debug("[mlx-score] /v1/score nicht verfuegbar/fehlerhaft: {}", e.message)
                Mono.empty()
            }

    /** Defensiver JSON-Parse: fehlt EIN Pflichtfeld oder ist es nicht numerisch, gilt die GANZE Antwort als unbrauchbar (null). */
    private fun parseScore(body: String): SttSurprisal? = runCatching {
        val node = mapper.readTree(body)
        val mean = node.path("mean_surprisal").takeIf { it.isNumber }?.asDouble() ?: return null
        val max = node.path("max_surprisal").takeIf { it.isNumber }?.asDouble() ?: return null
        val count = node.path("token_count").takeIf { it.isIntegralNumber }?.asInt() ?: return null
        SttSurprisal(meanSurprisal = mean, maxSurprisal = max, tokenCount = count)
    }.getOrNull()

    companion object {
        /** Default-Zeitbudget des `/v1/score`-Calls — deckt sich mit dem Orchestrator-Deckel (s. Klassen-KDoc). */
        const val DEFAULT_TIMEOUT_MS: Long = 500
    }
}
