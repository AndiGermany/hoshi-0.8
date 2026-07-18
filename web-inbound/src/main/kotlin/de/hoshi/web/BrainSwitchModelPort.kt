package de.hoshi.web

import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/** Ergebnis eines `/switch-model`-Aufrufs — sealed, damit der Controller erschöpfend matcht. */
sealed interface BrainSwitchResult {
    /** Der Sidecar hat den Wechsel angenommen (2xx) — er läuft jetzt (60-120s, s. Klassendoc). */
    data object Accepted : BrainSwitchResult

    /**
     * Der Sidecar kennt `/switch-model` noch nicht (404 — Endpoint wird PARALLEL
     * gebaut), ist nicht erreichbar, oder antwortet mit einem Fehler. [detail]
     * ist ein Klartext-Hinweis fürs Log (nie an den User als Rohtext gereicht —
     * der Controller spricht die feste, ehrliche Meldung).
     */
    data class Unavailable(val detail: String) : BrainSwitchResult
}

/**
 * **BrainSwitchModelPort** — die hexagonale Naht für `POST {brainBaseUrl}/switch-model`.
 * Funktionales Interface, damit [BrainSettingsController] direkt mit einem Fake
 * getestet werden kann (kein echter Sidecar-Call in Tests).
 */
fun interface BrainSwitchModelPort {
    /** [repo] ist die VOLLE HF-Repo-Id (z.B. `mlx-community/gemma-4-e4b-it-4bit`). Wirft NIE. */
    fun switchModel(repo: String): Mono<BrainSwitchResult>
}

/**
 * **HttpBrainSwitchModelPort** — die EINE Live-Impl: `POST {baseUrl}/switch-model`
 * mit Body `{"model": "<repo>"}`. Der Endpoint wird PARALLEL von einem anderen
 * Pod in `sidecars/brain/server.py` gebaut (Andi-Auftrag: server.py hier NICHT
 * anfassen) — solange er fehlt, antwortet der Sidecar ehrlich 404 und dieser
 * Adapter behandelt das wie JEDEN anderen Fehler (Timeout, Connection-Refused,
 * 5xx): [BrainSwitchResult.Unavailable], NIE eine Exception. Der Wechsel selbst
 * dauert 60-120s — dieser Call meldet nur „angenommen", nicht „fertig"; der
 * Fortschritt kommt über wiederholte `GET /health`-Polls ([BrainHealthProbe]).
 */
class HttpBrainSwitchModelPort(
    baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5),
) : BrainSwitchModelPort {

    private val client = WebClient.builder().baseUrl(baseUrl).build()

    override fun switchModel(repo: String): Mono<BrainSwitchResult> =
        client.post().uri("/switch-model")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("model" to repo))
            .retrieve()
            .toBodilessEntity()
            .timeout(timeout)
            .map<BrainSwitchResult> { BrainSwitchResult.Accepted }
            .onErrorResume { e -> Mono.just(BrainSwitchResult.Unavailable(e.message ?: "nicht erreichbar")) }
}
