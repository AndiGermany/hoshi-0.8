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
 * TTS-Adapter für den Voxtral-Sidecar (`mlx-community/Voxtral-4B-TTS`, Port 8042).
 * Spricht `POST /tts` (`{text, lang?, voice?}` → WAV-Bytes, 16-bit mono 24kHz) und
 * implementiert den hexagonalen [TtsPort]. Macht den Turn HÖRBAR.
 *
 * ENTKOPPELT von Spring (wie [de.hoshi.adapters.brain.MlxBrainAdapter]): kein
 * `@Service`, keine Properties — Konfiguration über Konstruktor-Parameter, der
 * WebClient wird intern via [WebClient.builder] gebaut.
 *
 * **Multilingual:** [Language.code] geht als `lang` an Voxtral durch. [voice] ist
 * der Default-Stimmen-Tag (Sidecar-Default `de_female`).
 *
 * **Per-Turn-Voice (Backlog #6) — ehrlich IGNORIERT:** die voice-aware
 * [TtsPort]-Overloads werden hier bewusst NICHT überschrieben; ihre
 * Port-Defaults delegieren an [synth] und lassen den Request-Wunsch fallen.
 * Grund: die OpenAI-Voice-Namen (coral/nova/…) sind KEINE Voxtral-Stimmen —
 * Voxtral kennt nur eigene Sidecar-Tags ([voice], z.B. `de_female`). Ein
 * heimliches Mapping wäre gelogen; der Turn klingt hier immer wie der
 * Boot-Default.
 *
 * **Best-Effort:** [synth] liefert `Mono.empty()` bei leerem Text. Echte
 * Netzwerk-/Sidecar-Fehler propagieren als Error-Mono — der aufrufende
 * [de.hoshi.core.pipeline.TtsStage] verschluckt sie (`onErrorResume`), so dass
 * der Text-Turn nie an der Audio-Schicht stirbt.
 */
class VoxtralTtsAdapter(
    baseUrl: String,
    private val voice: String = "de_female",
    private val timeoutSeconds: Long = 30,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : TtsPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        // WAV eines Satzes kann ~100–300 KB sein — großzügiger In-Memory-Puffer.
        .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
        .build()

    override fun synth(text: String, language: Language): Mono<ByteArray> {
        if (text.isBlank()) return Mono.empty()
        val body = mapOf(
            "text" to text,
            "lang" to language.code,
            "voice" to voice,
        )
        return client.post().uri("/tts")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .doOnError { e -> log.warn("[voxtral-tts] /tts fehlgeschlagen (best-effort, Text läuft weiter): {}", e.message) }
    }

    /**
     * Health-Check gegen den Sidecar (`GET /health`). true nur bei `{"status":"ok"}`.
     * Bei Parse-Fehler defensiv true (2xx-Antwort kam), bei Netzwerk-Fehler false.
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
