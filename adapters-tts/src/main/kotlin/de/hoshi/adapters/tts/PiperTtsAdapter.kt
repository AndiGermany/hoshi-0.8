package de.hoshi.adapters.tts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * TTS-Adapter für den Piper-Sidecar (`sidecars/piper/server.py`, Port 8045) —
 * die VIERTE TTS-Engine neben [VoxtralTtsAdapter] (lokal, :8042), [OpenAiTtsAdapter]
 * (Cloud) und [SayTtsAdapter] (macOS-Bordmittel, :8044). Spricht `POST /tts`
 * (`{text, voice}` → WAV-Bytes, PCM16 mono, MODELLNATIV 22.050 Hz — identisch zur
 * `say`-Naht, darum bewusst KEIN Resampling: die Bytes werden unverändert
 * durchgereicht) und implementiert denselben hexagonalen [TtsPort]. CPU-only,
 * GPL-3.0-Runtime hinter HTTP isoliert (Lizenzgrenze s. `sidecars/piper/README.md`);
 * Andis Blind-Hörprobe + der finale Lizenz-/Contest-Entscheid stehen noch aus —
 * dieser Adapter macht die Engine nur ANWÄHLBAR (`HOSHI_TTS=piper`), er flippt
 * keinen Default (Codex-Contract-Vorgabe #4).
 *
 * **Entkoppelt von Spring** (wie die Geschwister): kein `@Service`, keine
 * Properties — Konfiguration über Konstruktor-Parameter, der WebClient wird
 * intern via [WebClient.builder] gebaut.
 *
 * **Voice IMMER im Body** — anders als [SayTtsAdapter] (wo `voice` optional ist
 * und bei Fehlen der Sidecar-Default greift): der Piper-Sidecar validiert `voice`
 * hart gegen die EINE geladene Stimme (`server.py::tts_response`) und antwortet
 * mit HTTP 422 bei jedem Mismatch (unbekannte/nicht geladene Stimme). Codex'
 * bindende Vorgabe: das ist ABSICHTLICH KEIN stiller Fallback auf eine andere
 * Stimme — der Adapter behandelt 422 exakt wie jeden anderen Sidecar-Fehler
 * (Never-Silent, s.u.), NICHT als „dann eben ohne Stimm-Wunsch nochmal versuchen".
 *
 * **Per-Turn-Voice — ehrlich IGNORIERT** (identisches Muster zu [SayTtsAdapter]/
 * [VoxtralTtsAdapter]): die voice-aware [TtsPort]-Overloads werden hier bewusst
 * NICHT überschrieben; ihre Port-Defaults delegieren an [synth] und lassen den
 * Request-Wunsch fallen — die OpenAI-Voice-Namen (coral/nova/…) sind keine
 * Piper-Stimm-IDs, ein heimliches Mapping wäre gelogen.
 *
 * **Best-Effort (Never-Silent):** [synth] liefert `Mono.empty()` bei leerem
 * Text. JEDER Sidecar-Fehler (Timeout, 4xx/5xx — inkl. 422 „Stimme nicht
 * geladen" — Connection-Refused) liefert best-effort einen LEEREN `ByteArray`
 * statt zu werfen — Muster [SayTtsAdapter]/[OpenAiTtsAdapter] (nicht
 * [VoxtralTtsAdapter], der Fehler propagieren lässt): der aufrufende
 * [de.hoshi.core.pipeline.TtsStage] behandelt leere Bytes als „kein Audio" und
 * reicht den Text-Turn unbeschadet durch.
 */
class PiperTtsAdapter(
    baseUrl: String,
    /** Konfigurierte Stimme — IMMER im Body gesendet (Sidecar validiert hart, s. Klassendoc). */
    private val voice: String = "de_DE-thorsten-medium",
    private val timeoutSeconds: Long = 30,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : TtsPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        // WAV eines Satzes kann ~100–300 KB sein — großzügiger In-Memory-Puffer (wie die Geschwister).
        .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
        .build()

    override fun synth(text: String, language: Language): Mono<ByteArray> {
        if (text.isBlank()) return Mono.empty()
        return client.post().uri("/tts")
            .bodyValue(mapOf("text" to text, "voice" to voice))
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .defaultIfEmpty(ByteArray(0))
            // Best-Effort: JEDER Fehler (Timeout/4xx inkl. 422-unbekannte-Stimme/5xx/
            // Connection-Refused) → leeres Audio, NIE Crash. Kein stiller Voice-Fallback.
            .doOnError { e -> log.warn("[piper-tts] /tts fehlgeschlagen (best-effort, Text läuft weiter): {}", e.message) }
            .onErrorReturn(ByteArray(0))
    }

    /**
     * Health-Check gegen den Sidecar (`GET /health`). true nur bei `{"status":"ok"}`
     * (der Sidecar meldet „starting", solange Piper noch lädt — der synchrone Warmup
     * in `server.py::serve()` läuft VOR dem ersten `accept()`, in Praxis also
     * unsichtbar). Bei Parse-Fehler defensiv true (2xx-Antwort kam), bei
     * Netzwerk-Fehler false (identisches Muster zu [SayTtsAdapter.health]).
     */
    fun health(): Mono<Boolean> =
        client.get().uri("/health")
            .retrieve().bodyToMono(String::class.java)
            .timeout(Duration.ofSeconds(3))
            .map { body ->
                runCatching {
                    mapper.readTree(body).path("status").asText("").equals("ok", ignoreCase = true)
                }.getOrDefault(true)
            }
            .defaultIfEmpty(false)
            .onErrorReturn(false)
}
