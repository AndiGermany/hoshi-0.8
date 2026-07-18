package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Ein ehrlicher Live-Befund von `GET {brainBaseUrl}/health`:
 *  - [status]: roh durchgereicht aus dem Sidecar-JSON (`"ok"`/`"loading"`/…),
 *    `"unreachable"` NUR wenn der Sidecar selbst nicht antwortet (Timeout,
 *    Connection-Refused, kaputtes JSON) — kein Fake-`"ok"`.
 *  - [model]: das gemessene `model`-Feld (volle HF-Repo-Id), `null` wenn
 *    unreachable oder das Feld fehlt.
 */
data class BrainHealthSnapshot(val status: String, val model: String?)

/**
 * **BrainHealthProbe** — die hexagonale Live-Probe-Naht für
 * `GET /api/v1/settings/brain`. Funktionales Interface, damit
 * [BrainSettingsController] direkt (ohne Spring-Context, ohne echtes Netz) mit
 * einem Fake getestet werden kann — Muster [TtsEngineProbe].
 */
fun interface BrainHealthProbe {
    /** Wirft NIE — jeder Fehler landet in [BrainHealthSnapshot] mit `status="unreachable"`. */
    fun check(): Mono<BrainHealthSnapshot>
}

/**
 * **HttpBrainHealthProbe** — die EINE Live-Impl: non-blocking WebClient-GET
 * gegen `{baseUrl}/health` (läuft im Reactor-Request-Thread, KEIN `.block()` —
 * Blocking-Verbot s. [de.hoshi.adapters.supervision.HttpSidecarProbe]-KDoc).
 * `hoshi.brain.base-url` ist DIESELBE Property wie
 * [PipelineConfig.brainPort]/[SidecarHealthService] — eine Wahrheit, mehrere Leser.
 */
class HttpBrainHealthProbe(
    baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(3),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : BrainHealthProbe {

    private val client = WebClient.builder().baseUrl(baseUrl).build()

    override fun check(): Mono<BrainHealthSnapshot> =
        client.get().uri("/health")
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(timeout)
            .map { body -> classify(body) }
            .onErrorResume { Mono.just(BrainHealthSnapshot(status = "unreachable", model = null)) }

    private fun classify(body: String): BrainHealthSnapshot {
        val node = runCatching { mapper.readTree(body) }.getOrNull()
            ?: return BrainHealthSnapshot(status = "unreachable", model = null)
        // Fehlendes/leeres status-Feld ⇒ konservativ "loading" (nicht fake-"ok") —
        // ein 2xx ohne erkennbares Status-Feld ist erreichbar, aber der Vertrag ist unklar.
        val status = node.path("status").asText("").ifBlank { "loading" }
        val model = node.path("model").asText("").takeIf { it.isNotBlank() }
        return BrainHealthSnapshot(status = status, model = model)
    }
}
