package de.hoshi.adapters.stt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.Language
import de.hoshi.core.port.SttPort
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * STT-Adapter für den Whisper-MLX-Sidecar (`mlx-community/whisper-large-v3-turbo`,
 * Port 9001). Spricht `POST /asr?encode=true&task=transcribe&language=<code>&output=json`
 * (multipart, Feld `audio_file`) → `{"text": "..."}` und implementiert den
 * hexagonalen [SttPort]. Macht den Turn HÖRBEREIT — Andi kann Hoshi ansprechen.
 *
 * Das Spiegelbild von [de.hoshi.adapters.tts.VoxtralTtsAdapter]: ENTKOPPELT von
 * Spring (kein `@Service`, keine Properties) — Konfiguration über Konstruktor,
 * der WebClient wird intern via [WebClient.builder] gebaut.
 *
 * **Multilingual:** [Language.code] geht als `language`-Query-Hint an Whisper.
 * 0.7-Parität: Default DE. (0.7 hatte `language=de` hartcodiert — hier additiv
 * aus dem Turn aufgelöst.)
 *
 * **Best-Effort (Never-Silent):** Bei leerem Audio, Stille-Gate-Treffer (Sidecar
 * liefert `{"text":""}`) ODER echtem Netzwerk-/Sidecar-Fehler liefert [transcribe]
 * einen **leeren String** statt zu werfen — der Inbound-Rand (`/api/v1/voice`)
 * übersetzt das in eine warme `no_input`-Antwort, nie in einen Crash.
 */
class WhisperSttAdapter(
    baseUrl: String,
    private val timeoutSeconds: Long = 30,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : SttPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        // Mic-WAV einer Frage kann ~100–300 KB sein — großzügiger In-Memory-Puffer.
        .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
        .build()

    override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> {
        if (audioWav.isEmpty()) return Mono.just("")

        // multipart/form-data mit dem Feld `audio_file` (FastAPI UploadFile braucht
        // einen Dateinamen, sonst kommt es als plain Form-Feld an).
        val parts = MultipartBodyBuilder().apply {
            part("audio_file", object : ByteArrayResource(audioWav) {
                override fun getFilename() = "audio.wav"
            }).contentType(MediaType.parseMediaType("audio/wav"))
        }.build()

        return client.post()
            .uri { uri ->
                uri.path("/asr")
                    .queryParam("encode", true)
                    .queryParam("task", "transcribe")
                    // language == null ⇒ Hint WEGLASSEN ⇒ Whisper auto-detectet die Sprache.
                    // Sonst der konkrete ISO-Code wie bisher (DE/EN-Hint).
                    .apply { if (language != null) queryParam("language", language.code) }
                    .queryParam("output", "json")
                    .build()
            }
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts))
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .map { body -> parseText(body) }
            .doOnNext { text -> log.info("[whisper-stt] /asr → {} Zeichen", text.length) }
            .defaultIfEmpty("")
            .doOnError { e -> log.warn("[whisper-stt] /asr fehlgeschlagen (best-effort, no_input): {}", e.message) }
            .onErrorReturn("")
    }

    /** Zieht das `text`-Feld aus der `{"text": "..."}`-Antwort; defensiv "" bei Parse-Fehler. */
    private fun parseText(body: String): String =
        runCatching { mapper.readTree(body).path("text").asText("").trim() }.getOrDefault("")
}
