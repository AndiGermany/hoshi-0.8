package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/** Ein ehrlicher Verfügbarkeits-Befund für EINE TTS-Engine (Andi-Doktrin: kein Fake-grün). */
data class TtsEngineAvailability(val verfuegbar: Boolean, val hinweis: String)

/**
 * **TtsEngineProbe** — die hexagonale Live-Probe-Naht für `GET /api/v1/settings/tts`.
 * Funktionales Interface (genau EINE Methode), damit [TtsSettingsController]
 * direkt (ohne Spring-Context, ohne echtes Netz) mit einem Fake getestet werden
 * kann — Muster [de.hoshi.core.supervision.SidecarPort]/[BrainHealthSource].
 */
fun interface TtsEngineProbe {
    /** Ehrlicher Live-Befund für [engineId] (eine [TtsEngineIds]-Konstante). Wirft NIE. */
    fun check(engineId: String): Mono<TtsEngineAvailability>
}

/**
 * **HttpTtsEngineProbe** — die EINE Live-Impl von [TtsEngineProbe]:
 *  - `openai`: KEIN Netz-Call — „verfügbar" heißt hier nur „ist ein
 *    `OPENAI_API_KEY` gesetzt?" (derselbe Mechanismus wie
 *    [de.hoshi.adapters.tts.OpenAiTtsAdapter]/`OpenAiEscalationAdapter`).
 *  - `say`/`piper`/`voxtral`: `GET {baseUrl}/health` (kurzer Timeout, non-blocking
 *    WebClient — läuft im Reactor-Request-Thread, KEIN `.block()`, s.
 *    [de.hoshi.adapters.supervision.HttpSidecarProbe]-KDoc zum Blocking-Verbot).
 *    2xx mit `status` fehlend/leer/`"ok"` ⇒ verfügbar; alles andere (Timeout,
 *    Connection-Refused, non-2xx, `status≠ok`) ⇒ ehrlich NICHT verfügbar mit
 *    einem Klartext-Hinweis („nicht gestartet" bei Verbindungsfehlern — der
 *    häufigste Fall, wenn ein Sidecar bewusst aus ist, RAM sparen).
 *
 * Best-effort: JEDER Fehler landet in `verfuegbar=false`, NIE in einer
 * Exception (der GET/PUT-Handler darf nie an einer Probe sterben).
 */
class HttpTtsEngineProbe(
    private val voxtralBaseUrl: String,
    private val sayBaseUrl: String,
    private val piperBaseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(2),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : TtsEngineProbe {

    override fun check(engineId: String): Mono<TtsEngineAvailability> = when (engineId) {
        TtsEngineIds.OPENAI -> Mono.just(checkOpenAi())
        TtsEngineIds.SAY -> checkHttp(sayBaseUrl)
        TtsEngineIds.PIPER -> checkHttp(piperBaseUrl)
        TtsEngineIds.VOXTRAL -> checkHttp(voxtralBaseUrl)
        else -> Mono.just(TtsEngineAvailability(false, "Unbekannte Engine."))
    }

    /** Kein Netz-Call — der Key-Check ist synchron/billig, `Mono.just` reicht. */
    private fun checkOpenAi(): TtsEngineAvailability {
        val key = System.getenv("OPENAI_API_KEY")
        return if (!key.isNullOrBlank()) {
            TtsEngineAvailability(true, "")
        } else {
            TtsEngineAvailability(false, "Kein OPENAI_API_KEY gesetzt.")
        }
    }

    private fun checkHttp(baseUrl: String): Mono<TtsEngineAvailability> =
        WebClient.builder().baseUrl(baseUrl).build()
            .get().uri("/health")
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(timeout)
            .map { body -> classify(body) }
            .onErrorResume { Mono.just(TtsEngineAvailability(false, "nicht gestartet")) }

    private fun classify(body: String): TtsEngineAvailability {
        val status = runCatching { mapper.readTree(body).path("status").asText("") }.getOrDefault("")
        return if (status.isBlank() || status.equals("ok", ignoreCase = true)) {
            TtsEngineAvailability(true, "")
        } else {
            TtsEngineAvailability(false, "läuft, ist aber noch nicht bereit (status=$status).")
        }
    }
}
