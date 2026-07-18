package de.hoshi.adapters.tts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TtsSanitizePort
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * TTS-Adapter für die **OpenAI-Cloud-TTS-API** (`POST /v1/audio/speech`) — die
 * Alternative zum lokalen [VoxtralTtsAdapter] (:8042), wenn der Voxtral-Sidecar
 * gestoppt ist (RAM sparen). Spricht `{"model","voice","input","response_format":"wav"}`
 * → WAV-Bytes und implementiert denselben hexagonalen [TtsPort].
 *
 * **Spring-entkoppelt** (wie [VoxtralTtsAdapter] / [de.hoshi.adapters.stt.WhisperSttAdapter]):
 * kein `@Service`, keine Properties — Konfiguration über Konstruktor, der
 * WebClient wird intern via [WebClient.builder] gebaut.
 *
 * **Key:** [apiKey] wird vom Aufrufer aus der Env (`OPENAI_API_KEY`) gereicht und
 * ausschließlich als `Authorization: Bearer …`-Header verwendet — der Wert wird
 * NIE geloggt (nur Präsenz/Länge).
 *
 * **Multilingual:** `gpt-4o-mini-tts`/Stimme `coral` erkennen die Sprache aus dem
 * Text selbst — [language] wird darum als Hinweis angenommen, aber nicht an die
 * API durchgereicht (Param bewusst akzeptiert, nicht benutzt).
 *
 * **Per-Turn-Voice (Backlog #6):** die voice-aware [TtsPort]-Overloads nehmen den
 * Request-Wunsch entgegen. Er wird NIE roh in den Body gereicht — [resolveVoice]
 * prüft (case-insensitiv) gegen [SUPPORTED_VOICES]; unbekannt/null ⇒ Boot-Default
 * [voice] wie heute (byte-neutral ohne gesetztes Feld).
 *
 * **Best-Effort (Never-Silent):** Fehlender Key, leerer Text, HTTP-Fehler (z.B.
 * 401) oder Netzwerk-/Timeout-Probleme liefern einen **leeren ByteArray** statt zu
 * werfen — die aufrufende [de.hoshi.core.pipeline.TtsStage] behandelt leere Bytes
 * als „kein Audio" und reicht den Text-Turn unbeschadet durch.
 *
 * **Egress-Sanitize (Privacy):** dies ist der EINZIGE TTS-Pfad, der die Box verlaesst.
 * Vor dem Body-Bau laeuft der Antworttext durch [sanitizer] ([TtsSanitizePort]), der
 * die Never-Speak-Spans (Token/API-Key, URL, IP, UUID/ID, HA-Entity-ID) maskiert und
 * Namen/normalen Inhalt behaelt. Default [TtsSanitizePort.IDENTITY] ⇒ byte-identisch
 * (das Scharfschalten ist eine Deploy-Entscheidung, Flag `HOSHI_TTS_SANITIZE_ENABLED`).
 */
class OpenAiTtsAdapter(
    private val apiKey: String?,
    private val model: String = "gpt-4o-mini-tts",
    private val voice: String = "coral",
    private val timeoutSeconds: Long = 30,
    baseUrl: String = "https://api.openai.com",
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    /**
     * Egress-Maskierung VOR dem Cloud-Call. Default [TtsSanitizePort.IDENTITY] =
     * byte-neutral (roher Text). Der Inbound-Adapter injiziert flag-gated den echten
     * Never-Speak-Sanitizer.
     */
    private val sanitizer: TtsSanitizePort = TtsSanitizePort.IDENTITY,
    /**
     * **Streaming-TTS (Latenz-Hebel #1, Flag `HOSHI_TTS_STREAM_ENABLED`) — default OFF.**
     * Bei OFF ist [synthStream] der byte-identische Batch-Pfad ([synth] als ein
     * WAV-Element). Bei ON liest [synthStream] die Response STREAMEND
     * (`response_format=pcm`, chunked transfer — OpenAI liefert das erste Byte
     * deutlich vor Fertigstellung), schneidet den PCM-Strom via [PcmStreamSlicer]
     * an Zero-Crossings in ~[streamSliceMillis]-Slices und wrappt jede in ein
     * eigenständiges WAV ([PcmWav]) ⇒ mehrere AudioChunks pro Satz, erstes Audio
     * deutlich früher. [synth] bleibt in BEIDEN Fällen unverändert (Batch-WAV).
     */
    private val streamEnabled: Boolean = false,
    /**
     * Ziel-Slice-Länge in ms (nur Streaming-Pfad). Kleiner ⇒ früheres erstes
     * Audio, aber mehr Chunk-Nähte; größer ⇒ weniger Nähte, späteres erstes Audio.
     * 600ms ≈ 28.800 Bytes @ 24kHz/16bit mono.
     */
    private val streamSliceMillis: Int = 600,
    /**
     * **Short-First (Latenz-Resthebel, Lars-Nachmessung):** Länge NUR der ERSTEN
     * Slice in ms. Die OpenAI-Synthese ist schneller als Echtzeit (alle Slices
     * kommen als Burst ~0,3s nach Stream-Start) ⇒ das erste Audio wartete bisher
     * TTFB + [streamSliceMillis] Content. Mit ~280ms ist die erste Slice fertig,
     * sobald minimal Inhalt da ist ⇒ erstes Audio ≈ TTFB (~−300ms p50).
     * Folge-Slices bleiben [streamSliceMillis]; Zero-Crossing-Schnitt unverändert.
     */
    private val streamFirstSliceMillis: Int = 280,
    /**
     * Suchfenster ±ms um das Slice-Ziel für den leisesten Schnittpunkt
     * (Zero-Crossing/Ruhepunkt — Klick-Vermeidung an der Naht).
     */
    private val streamCutWindowMillis: Int = 20,
) : TtsPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        // Ein Satz WAV (24kHz mono) kann ~100–300 KB sein — großzügiger Puffer.
        .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
        .build()

    override fun synth(text: String, language: Language): Mono<ByteArray> =
        synth(text, language, null)

    /** Voice-aware Batch-Pfad: der Request-[voice] geht durch [resolveVoice] (Whitelist), nie roh. */
    override fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> {
        if (text.isBlank()) return Mono.empty()
        val key = apiKey?.trim()
        if (key.isNullOrBlank()) {
            // Best-Effort: ohne Key keinen Call wagen — leeres Audio, Text läuft weiter.
            log.warn("[openai-tts] kein OPENAI_API_KEY — best-effort leeres Audio (Text-Turn läuft weiter)")
            return Mono.just(ByteArray(0))
        }
        // Egress-Riegel: NUR der maskierte Text verlaesst die Box. Bei IDENTITY (Default)
        // ist das exakt `text` (byte-neutral); flag-scharf maskiert es die Never-Speak-Spans.
        val speakable = sanitizer.sanitizeForSpeech(text)
        val body = mapOf(
            "model" to model,
            "voice" to resolveVoice(voice),
            "input" to speakable,
            "response_format" to "wav",
        )
        return client.post().uri("/v1/audio/speech")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .defaultIfEmpty(ByteArray(0))
            // Best-Effort: jeder Fehler (401, Netz, Timeout) → leeres Audio, NIE Crash.
            // Der Key-Wert taucht in keiner Log-Zeile auf (nur e.message).
            .doOnError { e -> log.warn("[openai-tts] /v1/audio/speech fehlgeschlagen (best-effort): {}", e.message) }
            .onErrorReturn(ByteArray(0))
    }

    /**
     * **Streaming-Synthese** (Flag OFF ⇒ byte-identischer Batch-Fallback): liest
     * `response_format=pcm` (24kHz/16bit/mono, headerless — Doku-verifiziert)
     * CHUNKED aus der Response, schneidet via [PcmStreamSlicer] am leisesten
     * Punkt (±[streamCutWindowMillis]) und emittiert jede Slice als eigenständig
     * dekodierbares WAV — das erste Element fließt, sobald ~[streamFirstSliceMillis]
     * Audio da sind (Short-First), NICHT erst nach dem kompletten Satz.
     *
     * Verträge wie [synth]: Sanitizer-Naht VOR dem Call (einziger Egress-Pfad),
     * Key nur als Bearer-Header (NIE geloggt), Best-Effort (Fehler ⇒ der Flux
     * endet — bereits emittierte Slices bleiben gültig, der Text-Turn lebt).
     */
    override fun synthStream(text: String, language: Language): Flux<ByteArray> =
        synthStream(text, language, null)

    /** Voice-aware Streaming-Pfad: identische Whitelist-Naht ([resolveVoice]) wie im Batch. */
    override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> {
        // Flag OFF ⇒ exakt der heutige Batch-Pfad ([synth], ein WAV-Element) —
        // der Request-Voice fließt auch dorthin (gleiche Whitelist-Naht).
        if (!streamEnabled) return synth(text, language, voice).flux()
        if (text.isBlank()) return Flux.empty()
        val key = apiKey?.trim()
        if (key.isNullOrBlank()) {
            // Best-Effort: ohne Key keinen Call wagen — kein Audio, Text läuft weiter.
            log.warn("[openai-tts] kein OPENAI_API_KEY — best-effort kein Stream-Audio (Text-Turn läuft weiter)")
            return Flux.empty()
        }
        // Egress-Riegel: identische Sanitizer-Naht wie im Batch-Pfad — NUR der
        // maskierte Text verlässt die Box.
        val speakable = sanitizer.sanitizeForSpeech(text)
        val body = mapOf(
            "model" to model,
            "voice" to resolveVoice(voice),
            "input" to speakable,
            "response_format" to "pcm",
        )
        return Flux.defer {
            // Ein Slicer je Subscription — stateful pro Satz, nie geteilt.
            val slicer = PcmStreamSlicer(
                targetSliceBytes = streamSliceMillis * PCM_BYTES_PER_MILLI,
                cutWindowBytes = streamCutWindowMillis * PCM_BYTES_PER_MILLI,
                firstSliceBytes = streamFirstSliceMillis * PCM_BYTES_PER_MILLI,
            )
            client.post().uri("/v1/audio/speech")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(DataBuffer::class.java)
                .concatMap { buf ->
                    val chunk = ByteArray(buf.readableByteCount())
                    buf.read(chunk)
                    DataBufferUtils.release(buf)
                    Flux.fromIterable(slicer.push(chunk))
                }
                // Stream-Ende: den Rest (< eine Slice) als letztes WAV nachschieben.
                .concatWith(Flux.defer { slicer.flush()?.let { Flux.just(it) } ?: Flux.empty<ByteArray>() })
                .map { pcm -> PcmWav.wrap(pcm) }
                // Inter-Chunk-Timeout (Flux.timeout wirkt zwischen Emissionen).
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // Best-Effort: Fehler mitten im Stream ⇒ sauber beenden, NIE Crash.
                // Der Key-Wert taucht in keiner Log-Zeile auf (nur e.message).
                .doOnError { e -> log.warn("[openai-tts] streaming /v1/audio/speech fehlgeschlagen (best-effort): {}", e.message) }
                .onErrorResume { Flux.empty() }
        }
    }

    /**
     * Whitelist-Riegel für den per-Turn-Voice-Wunsch: NUR exakt bekannte Namen
     * (case-insensitiv, getrimmt) gehen in den Body — alles andere (null, leer,
     * Tippfehler, Injection-Versuch) fällt STILL auf den Boot-Default [voice]
     * zurück (best-effort, kein Error-Pfad; nur ein debug-Log für die Diagnose).
     */
    private fun resolveVoice(requested: String?): String {
        val candidate = requested?.trim()?.lowercase() ?: return voice
        if (candidate in SUPPORTED_VOICES) return candidate
        log.debug("[openai-tts] unbekannte Voice '{}' — Fallback auf Boot-Default '{}'", candidate, voice)
        return voice
    }

    companion object {
        /** 24.000 Hz · 2 Bytes/Sample (mono int16) ⇒ 48 Bytes je Millisekunde. */
        private const val PCM_BYTES_PER_MILLI =
            PcmWav.OPENAI_PCM_SAMPLE_RATE_HZ * PcmWav.BYTES_PER_SAMPLE / 1000

        /**
         * Die von `/v1/audio/speech` akzeptierten Voice-Namen — doku-verifiziert
         * 2026-07 (developers.openai.com, Guide „Text to speech") für das
         * Boot-Default-Modell `gpt-4o-mini-tts` (13 Stimmen). ACHTUNG: die
         * Legacy-Modelle `tts-1`/`tts-1-hd` können nur die 9er-Teilmenge OHNE
         * ballad/verse/marin/cedar — ein nicht unterstützter Name dort ⇒
         * API-Fehler ⇒ Best-Effort leeres Audio (nie Crash). Der FE-Picker
         * sollte diese Liste als Quelle nutzen (Folge-Scheibe).
         */
        val SUPPORTED_VOICES: Set<String> = setOf(
            "alloy", "ash", "ballad", "cedar", "coral", "echo", "fable",
            "marin", "nova", "onyx", "sage", "shimmer", "verse",
        )
    }
}
