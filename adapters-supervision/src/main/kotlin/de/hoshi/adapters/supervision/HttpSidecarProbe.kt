package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.supervision.SidecarHealth
import de.hoshi.core.supervision.SidecarPort
import de.hoshi.core.supervision.SidecarSpec
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **HttpSidecarProbe** — die EINE Live-Probe-Impl des hexagonalen [SidecarPort]. Ersetzt
 * die 5 copy-paste-Bash-Watchdogs aus 0.5 durch genau einen ehrlichen Roundtrip pro Naht.
 *
 * Ehrlichkeits-Mapping (`GET <url>/health`):
 *  - kein/Fehler-Response, Timeout, non-2xx  → **DOWN**.
 *  - 2xx, aber `status` == `loading` (oder nicht `ok`) → **DEGRADED** („grün != lebt").
 *  - 2xx, fehlendes/erwartetes-aber-abweichendes `model` → **DEGRADED** (Drift/nicht bereit).
 *  - 2xx + `status=ok` (und ggf. Soll-`model` getroffen) → **OK**.
 *
 * Das gemessene `model` wird durchgereicht, damit der Report zeigt was WIRKLICH geladen ist.
 */
class HttpSidecarProbe(
    private val timeout: Duration = Duration.ofSeconds(6),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : SidecarPort {

    override fun probe(sidecar: SidecarSpec): SidecarHealth {
        val client = WebClient.builder()
            .baseUrl(sidecar.url)
            .codecs { it.defaultCodecs().maxInMemorySize(1 * 1024 * 1024) }
            .build()

        val body: String? = runCatching {
            client.get().uri("/health")
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(timeout)
                .onErrorResume { Mono.empty() }
                .block(timeout.plusSeconds(1))
        }.getOrNull()

        if (body.isNullOrBlank()) {
            return SidecarHealth.down("${sidecar.url}/health — keine Antwort")
        }

        return runCatching {
            val node = mapper.readTree(body)
            val status = node.path("status").asText("").lowercase()
            val model = node.path("model").asText("").takeIf { it.isNotBlank() }
            val expected = sidecar.expectedModel

            when {
                status == "loading" ->
                    SidecarHealth.degraded("status=loading (Warmup, noch nicht bereit)", model)
                status.isNotBlank() && status != "ok" ->
                    SidecarHealth.degraded("status=$status (nicht 'ok')", model)
                expected != null && model != null && !model.contains(expected) ->
                    SidecarHealth.degraded("model='$model' enthält nicht soll='$expected' (Drift)", model)
                expected != null && model == null ->
                    SidecarHealth.degraded("kein .model-Feld (erwartet '$expected')", null)
                status == "ok" ->
                    SidecarHealth.ok("status=ok${model?.let { " model='$it'" } ?: ""}", model)
                else ->
                    // 2xx ohne erkennbares status-Feld: erreichbar, aber Vertrag unklar → DEGRADED, nicht Fake-OK.
                    SidecarHealth.degraded("2xx ohne status-Feld: ${body.take(60)}", model)
            }
        }.getOrElse {
            SidecarHealth.degraded("Antwort nicht parsebar: ${body.take(60)}")
        }
    }
}
