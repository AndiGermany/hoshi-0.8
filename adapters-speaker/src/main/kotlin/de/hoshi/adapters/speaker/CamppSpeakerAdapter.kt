package de.hoshi.adapters.speaker

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.port.SpeakerEmbedPort
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * **CamppSpeakerAdapter** — [SpeakerEmbedPort] gegen den CAM++-Speaker-Sidecar
 * (Wespeaker/CAMPPlus-TSTP, VoxCeleb2, ONNX; Port 9002, `POST /embed`).
 *
 * Vertrag (0.5 `hoshi-speaker-id/server.py`, verifiziert):
 *  - Request-Body: `{"audio": <base64>, "sampleRate": <int>}` — `audio` ist ein WAV-Container
 *    (RIFF-Magic, Samplerate selbst-beschreibend, `sampleRate` dann ignoriert) ODER ein rohes
 *    PCM16-LE-Mono-Stream (kein RIFF ⇒ interpretiert @ `sampleRate`).
 *  - Response: `{"embedding": float[512], "dim": 512}`, **L2-normalisiert** (⇒ Cosine == Dot).
 *
 * Synchron ueber `java.net.http` (wie `HttpBrainHealthSource` in `SidecarHealthService`) —
 * der Port-Vertrag ist synchron (`FloatArray?`), das Blocking kapselt der Aufrufer
 * (Enroll-Controller per `Schedulers.boundedElastic()`, nie auf dem Netty-Event-Loop).
 * **Kein Token noetig** (lokaler LAN-Sidecar). **Best-Effort:** jeder Fehler/Timeout/Nicht-2xx
 * ⇒ `null`, NIE eine Exception.
 *
 * `mime` wird akzeptiert (Ehrlichkeit ueber das Empfangene), aber NICHT an den Sidecar gereicht:
 * der erkennt WAV selbst am RIFF-Magic; `sampleRate` greift nur fuer rohes PCM (Default 16k).
 */
class CamppSpeakerAdapter(
    baseUrl: String,
    private val sampleRate: Int = 16_000,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : SpeakerEmbedPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val embedUri: URI = URI.create(baseUrl.trimEnd('/') + "/embed")
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun embed(audioBytes: ByteArray, mime: String): FloatArray? {
        if (audioBytes.isEmpty()) return null
        return runCatching {
            val body = mapper.writeValueAsBytes(
                mapOf(
                    "audio" to Base64.getEncoder().encodeToString(audioBytes),
                    "sampleRate" to sampleRate,
                ),
            )
            val req = HttpRequest.newBuilder(embedUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[speaker-embed] /embed → HTTP {} (best-effort null)", resp.statusCode())
                null
            } else {
                parseEmbedding(resp.body())
            }
        }.getOrElse { e ->
            log.warn("[speaker-embed] /embed fehlgeschlagen (best-effort null): {}", e.message)
            null
        }
    }

    /** Zieht `embedding` aus `{"embedding":[…],"dim":…}`; fehlend/leer/nicht-Array ⇒ null. */
    private fun parseEmbedding(responseBody: String): FloatArray? {
        val arr = mapper.readTree(responseBody).path("embedding")
        if (!arr.isArray || arr.isEmpty) return null
        val out = FloatArray(arr.size())
        for (i in 0 until arr.size()) out[i] = arr.get(i).floatValue()
        return out
    }
}
