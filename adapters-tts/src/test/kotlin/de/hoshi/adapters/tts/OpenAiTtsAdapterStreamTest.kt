package de.hoshi.adapters.tts

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsSanitizePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Beweist den STREAMING-Pfad des [OpenAiTtsAdapter] OHNE echte Cloud: ein
 * JDK-HttpServer streamt rohes PCM CHUNKED (mit Pause zwischen den Hälften —
 * wie die echte API, deren erstes Byte lange vor Fertigstellung kommt).
 *
 * Kern-Beweise: mehrere eigenständige WAVs pro Satz, das ERSTE deutlich vor
 * Stream-Ende (Latenz-Hebel #1), Payload lossless, `response_format=pcm` im
 * Body, Sanitizer-Naht VOR dem Call, Flag OFF ⇒ byte-identischer Batch-Pfad.
 */
class OpenAiTtsAdapterStreamTest {

    data class RequestMeta(val authorization: String, val bodyText: String)

    /** Fake-`/v1/audio/speech`, streamt [parts] chunked mit [pauseMs] Pause dazwischen. */
    private fun withStreamingOpenAi(
        parts: List<ByteArray>,
        pauseMs: Long = 0,
        status: Int = 200,
        block: (url: String, captured: AtomicReference<RequestMeta?>) -> Unit,
    ) {
        val captured = AtomicReference<RequestMeta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/speech") { ex ->
            val raw = ex.requestBody.readBytes()
            captured.set(
                RequestMeta(
                    authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
                    bodyText = String(raw, Charsets.UTF_8),
                ),
            )
            if (status != 200) {
                val err = "error".toByteArray()
                ex.sendResponseHeaders(status, err.size.toLong())
                ex.responseBody.use { it.write(err) }
                return@createContext
            }
            ex.sendResponseHeaders(200, 0) // 0 ⇒ chunked transfer encoding
            ex.responseBody.use { os ->
                for ((i, part) in parts.withIndex()) {
                    os.write(part)
                    os.flush()
                    if (pauseMs > 0 && i < parts.size - 1) Thread.sleep(pauseMs)
                }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    /** 440-Hz-Sinus int16-LE @24kHz (identisch zum Slicer-Test). */
    private fun sinePcm(samples: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (20_000.0 * sin(2 * PI * 440.0 * i / 24_000.0)).roundToInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun isRiff(b: ByteArray): Boolean =
        b.size >= 44 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF"

    /** PCM-Payload hinter dem kanonischen 44-Byte-[PcmWav]-Header. */
    private fun payload(wav: ByteArray): ByteArray = wav.copyOfRange(44, wav.size)

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    private fun streamingAdapter(url: String, sanitizer: TtsSanitizePort = TtsSanitizePort.IDENTITY) =
        OpenAiTtsAdapter(
            apiKey = "test-key-xyz",
            baseUrl = url,
            sanitizer = sanitizer,
            streamEnabled = true,
            streamSliceMillis = 250, // 12.000 Bytes — klein, damit der Test mehrere Slices sieht
            streamFirstSliceMillis = 250, // hier bewusst == sliceMillis; Short-First hat einen eigenen Test
            streamCutWindowMillis = 20,
        )

    @Test
    fun `Streaming liefert mehrere eigenstaendige WAVs — das erste DEUTLICH vor Stream-Ende`() {
        val pcm = sinePcm(24_000) // 1s Audio = 48.000 Bytes
        val half = pcm.size / 2
        val parts = listOf(pcm.copyOfRange(0, half), pcm.copyOfRange(half, pcm.size))
        withStreamingOpenAi(parts, pauseMs = 500) { url, captured ->
            val adapter = streamingAdapter(url)
            val times = ArrayList<Long>()
            val wavs = adapter.synthStream("Dein Timer ist fertig.", Language.DE)
                .doOnNext { times.add(System.nanoTime()) }
                .collectList()
                .block(Duration.ofSeconds(15))!!

            assertTrue(wavs.size >= 3, "mehrere Slices erwartet, war ${wavs.size}")
            wavs.forEach { assertTrue(isRiff(it), "jede Slice muss ein eigenständiges WAV sein") }
            // LOSSLESS über die HTTP-Naht: alle Payloads zusammen == gesendetes PCM.
            assertTrue(concat(wavs.map { payload(it) }).contentEquals(pcm), "PCM muss lossless ankommen")
            // Der Latenz-Beweis: das erste WAV kam aus der ERSTEN Hälfte — mindestens
            // die 500ms-Server-Pause VOR dem letzten WAV (Batch hätte alles am Ende).
            val firstToLastMs = (times.last() - times.first()) / 1_000_000
            assertTrue(firstToLastMs >= 200, "erstes Audio muss vor Stream-Ende fließen (gap=${firstToLastMs}ms)")

            val meta = captured.get()!!
            assertEquals("Bearer test-key-xyz", meta.authorization)
            assertTrue(meta.bodyText.contains("\"response_format\":\"pcm\""), "Streaming-Pfad muss pcm anfordern: ${meta.bodyText}")
        }
    }

    @Test
    fun `Short-First — ERSTE Slice kurz (streamFirstSliceMillis), Folge-Slices normal, lossless`() {
        val pcm = sinePcm(24_000) // 1s = 48.000 Bytes
        withStreamingOpenAi(listOf(pcm)) { url, _ ->
            val adapter = OpenAiTtsAdapter(
                apiKey = "test-key-xyz",
                baseUrl = url,
                streamEnabled = true,
                streamSliceMillis = 250, // 12.000 Bytes
                streamFirstSliceMillis = 100, // 4.800 Bytes — deutlich kürzer als die Folge-Slices
                streamCutWindowMillis = 20, // ±960 Bytes
            )
            val wavs = adapter.synthStream("Dein Timer ist fertig.", Language.DE)
                .collectList()
                .block(Duration.ofSeconds(15))!!

            assertTrue(wavs.size >= 3, "kurze erste + normale Folge-Slices erwartet, war ${wavs.size}")
            val first = payload(wavs[0]).size
            assertTrue(first in (4_800 - 960)..(4_800 + 960), "erste Slice muss ~100ms sein, war $first Bytes")
            val second = payload(wavs[1]).size
            assertTrue(second in (12_000 - 960)..(12_000 + 960), "Folge-Slice muss ~250ms bleiben, war $second Bytes")
            assertTrue(concat(wavs.map { payload(it) }).contentEquals(pcm), "lossless auch mit Short-First")
        }
    }

    @Test
    fun `Flag OFF (Default) — synthStream ist der byte-identische Batch-Pfad (ein WAV, response_format=wav)`() {
        val fakeWav = "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) +
            "WAVEfmt ".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        withStreamingOpenAi(listOf(fakeWav)) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "k", baseUrl = url) // streamEnabled default false
            val wavs = adapter.synthStream("Hallo, ich bin Hoshi.", Language.DE)
                .collectList()
                .block(Duration.ofSeconds(5))!!

            assertEquals(1, wavs.size, "OFF ⇒ genau EIN Batch-WAV")
            assertTrue(wavs[0].contentEquals(fakeWav), "OFF ⇒ Bytes exakt wie der synth-Pfad")
            assertTrue(captured.get()!!.bodyText.contains("\"response_format\":\"wav\""), "OFF ⇒ Batch fordert wav an")
        }
    }

    @Test
    fun `request-voice fliesst durch dieselbe Whitelist-Naht in den Streaming-Body (Backlog 6)`() {
        withStreamingOpenAi(listOf(sinePcm(1_000))) { url, captured ->
            val adapter = streamingAdapter(url) // Boot-Default-Voice: coral
            adapter.synthStream("Hallo.", Language.DE, "sage")
                .collectList()
                .block(Duration.ofSeconds(5))

            val body = captured.get()!!.bodyText
            assertTrue(body.contains("\"voice\":\"sage\""), "request-voice fehlt im Streaming-Body: $body")
            assertTrue(body.contains("\"response_format\":\"pcm\""), "Streaming-Pfad muss pcm bleiben: $body")
        }
    }

    @Test
    fun `Sanitizer laeuft VOR dem Streaming-Call — Maske im Body, roher Span weg`() {
        withStreamingOpenAi(listOf(sinePcm(1_000))) { url, captured ->
            val sanitizer = TtsSanitizePort { it.replace(Regex("""sk-[A-Za-z0-9]+"""), "[TOKEN]") }
            val adapter = streamingAdapter(url, sanitizer = sanitizer)
            adapter.synthStream("Andi, dein Token ist sk-ABCDEFGHIJKLMNOP1234.", Language.DE)
                .collectList()
                .block(Duration.ofSeconds(5))

            val body = captured.get()!!.bodyText
            assertTrue(body.contains("[TOKEN]"), "Maske fehlt im Body: $body")
            assertFalse(body.contains("sk-ABCDEFGHIJKLMNOP1234"), "roher Token darf NICHT raus: $body")
            assertTrue(body.contains("Andi"), "Name muss erhalten bleiben: $body")
        }
    }

    @Test
    fun `401 im Streaming-Pfad — best-effort leerer Flux, nie Crash`() {
        withStreamingOpenAi(listOf(ByteArray(0)), status = 401) { url, _ ->
            val adapter = streamingAdapter(url)
            val wavs = adapter.synthStream("Hallo.", Language.DE)
                .collectList()
                .block(Duration.ofSeconds(5))!!
            assertTrue(wavs.isEmpty(), "401 ⇒ leerer Flux (Text-Turn läuft weiter)")
        }
    }

    @Test
    fun `fehlender Key — kein Call, leerer Flux`() {
        withStreamingOpenAi(listOf(sinePcm(100))) { url, captured ->
            val adapter = OpenAiTtsAdapter(apiKey = "  ", baseUrl = url, streamEnabled = true)
            val wavs = adapter.synthStream("Hallo.", Language.DE)
                .collectList()
                .block(Duration.ofSeconds(5))!!
            assertTrue(wavs.isEmpty())
            assertNull(captured.get(), "ohne Key darf kein API-Call rausgehen")
        }
    }

    @Test
    fun `leerer Text — kein Call, leerer Flux`() {
        withStreamingOpenAi(listOf(sinePcm(100))) { url, captured ->
            val adapter = streamingAdapter(url)
            val wavs = adapter.synthStream("   ", Language.DE)
                .collectList()
                .block(Duration.ofSeconds(5))!!
            assertTrue(wavs.isEmpty())
            assertNull(captured.get(), "leerer Text darf keinen API-Call auslösen")
        }
    }
}
