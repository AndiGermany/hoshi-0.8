package de.hoshi.adapters.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **PcmWav** — hüllt einen rohen PCM16-LE-Block in einen kanonischen 44-Byte
 * RIFF/WAVE-Header, sodass JEDE Slice ein komplett eigenständig dekodierbares
 * WAV ist (der FE-Chunk-Vertrag: `decodeAudioData` je [de.hoshi.core.dto.ChatEvent.AudioChunk]).
 *
 * Format-Wahrheit (OpenAI-Doku, verifiziert 2026-07-01): `response_format=pcm`
 * liefert „raw samples in 24kHz (16-bit signed, low-endian), without the header"
 * — mono. Der Header hier macht daraus exakt das WAV, das der Batch-Pfad
 * (`response_format=wav`) ohnehin liefert, nur pro Slice.
 *
 * Pur + deterministisch (kein IO, kein Spring) — voll unit-testbar.
 */
object PcmWav {

    /** Kanonische Headerlänge: RIFF(12) + fmt(24) + data-Header(8). */
    const val HEADER_BYTES: Int = 44

    /** OpenAI-`pcm`-Format (Doku-verifiziert): 24kHz. */
    const val OPENAI_PCM_SAMPLE_RATE_HZ: Int = 24_000

    /** int16 ⇒ 2 Bytes pro Sample (mono). */
    const val BYTES_PER_SAMPLE: Int = 2

    /**
     * Wickelt [pcm] (roh, int16-LE) in ein vollständiges WAV. Default = das
     * OpenAI-`pcm`-Format (24kHz / mono / 16-bit). Leeres [pcm] ⇒ gültiges,
     * leeres WAV (44 Bytes) — der Aufrufer filtert leere Slices ohnehin vorher.
     */
    fun wrap(
        pcm: ByteArray,
        sampleRateHz: Int = OPENAI_PCM_SAMPLE_RATE_HZ,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRateHz * blockAlign
        val buf = ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcm.size)                       // RIFF-Chunk-Size = Datei − 8
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                                  // fmt-Body: 16 Bytes (PCM)
        buf.putShort(1)                                 // AudioFormat 1 = PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRateHz)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcm.size)
        buf.put(pcm)
        return buf.array()
    }
}
