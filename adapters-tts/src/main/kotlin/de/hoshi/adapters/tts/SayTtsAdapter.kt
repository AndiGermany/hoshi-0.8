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
 * TTS-Adapter für den macOS-`say`-Sidecar (`sidecars/say/server.py`, Port 8044) —
 * die DRITTE TTS-Engine neben [VoxtralTtsAdapter] (lokal, :8042) und
 * [OpenAiTtsAdapter] (Cloud). Spricht `POST /tts` (`{text, voice?, rate?}` →
 * WAV-Bytes, 16-bit mono 22050 Hz — LEI16@22050) und implementiert denselben
 * hexagonalen [TtsPort]. Macht den Turn HÖRBAR mit reinen macOS-Bordmitteln
 * (`/usr/bin/say` + `/usr/bin/afconvert`) — kein Modell im RAM, kein Cloud-Egress.
 *
 * **Entkoppelt von Spring** (wie [VoxtralTtsAdapter]/[OpenAiTtsAdapter]): kein
 * `@Service`, keine Properties — Konfiguration über Konstruktor-Parameter, der
 * WebClient wird intern via [WebClient.builder] gebaut.
 *
 * **Kein `lang`-Feld im Body:** anders als [VoxtralTtsAdapter] trägt der
 * `say`-Sidecar-Kontrakt bewusst KEIN `lang` — die macOS-Stimme selbst kodiert
 * die Sprache (z.B. „Anna" = de_DE, „Samantha" = en_US); fehlt [voice], wählt
 * der Sidecar seinen eigenen Default (`server.py::DEFAULT_VOICE`).
 *
 * **Per-Turn-Voice — ehrlich IGNORIERT** (identisches Muster zu
 * [VoxtralTtsAdapter]): die voice-aware [TtsPort]-Overloads werden hier
 * bewusst NICHT überschrieben; ihre Port-Defaults delegieren an [synth] und
 * lassen den Request-Wunsch fallen. Grund: die OpenAI-Voice-Namen (coral/nova/…)
 * sind KEINE macOS-Stimmnamen — ein heimliches Mapping wäre gelogen.
 *
 * **Best-Effort (Never-Silent):** [synth] liefert `Mono.empty()` bei leerem
 * Text. JEDER Sidecar-Fehler (Timeout, 4xx/5xx, Connection-Refused) liefert
 * best-effort einen LEEREN `ByteArray` statt zu werfen — Muster [OpenAiTtsAdapter]
 * (nicht [VoxtralTtsAdapter], der Fehler propagieren lässt): der aufrufende
 * [de.hoshi.core.pipeline.TtsStage] behandelt leere Bytes als „kein Audio" und
 * reicht den Text-Turn unbeschadet durch.
 */
class SayTtsAdapter(
    baseUrl: String,
    /** Konfigurierter Stimm-Wunsch (wörtlicher macOS-Stimmname, z.B. "Anna"). null = Sidecar-Default. */
    private val voice: String? = null,
    /** Sprechgeschwindigkeit (Wörter/Minute, `say -r`). null = Sidecar/`say`-Default. */
    private val rate: Int? = null,
    private val timeoutSeconds: Long = 30,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : TtsPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        // WAV eines Satzes kann ~100–300 KB sein — großzügiger In-Memory-Puffer (wie Voxtral/OpenAI).
        .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
        .build()

    override fun synth(text: String, language: Language): Mono<ByteArray> {
        if (text.isBlank()) return Mono.empty()
        val body = buildMap<String, Any> {
            put("text", text)
            voice?.let { put("voice", it) }
            rate?.let { put("rate", it) }
        }
        return client.post().uri("/tts")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .defaultIfEmpty(ByteArray(0))
            // Best-Effort: jeder Fehler (Timeout/4xx/5xx/Connection-Refused) → leeres Audio, NIE Crash.
            .doOnError { e -> log.warn("[say-tts] /tts fehlgeschlagen (best-effort, Text läuft weiter): {}", e.message) }
            .onErrorReturn(ByteArray(0))
    }

    /**
     * Health-Check gegen den Sidecar (`GET /health`). true nur bei `{"status":"ok"}`.
     * Bei Parse-Fehler defensiv true (2xx-Antwort kam), bei Netzwerk-Fehler false
     * (identisches Muster zu [VoxtralTtsAdapter.health]).
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
