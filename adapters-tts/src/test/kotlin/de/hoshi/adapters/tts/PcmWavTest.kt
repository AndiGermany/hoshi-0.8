package de.hoshi.adapters.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem

/**
 * Beweist den [PcmWav]-Header Byte für Byte (RIFF/fmt/data, LE) UND über einen
 * unabhängigen Parser (JDK [AudioSystem]) — jede Slice muss ein eigenständig
 * dekodierbares WAV sein (FE-Vertrag: `decodeAudioData` je AudioChunk).
 */
class PcmWavTest {

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    @Test
    fun `Header ist kanonisch — RIFF, fmt (PCM, mono, 24kHz, 16bit), data`() {
        val pcm = ByteArray(4800) { (it % 251).toByte() } // 100ms @ 24kHz/16bit
        val wav = PcmWav.wrap(pcm)

        assertEquals(PcmWav.HEADER_BYTES + pcm.size, wav.size, "Gesamtlänge = 44 + PCM")
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(36 + pcm.size, le32(wav, 4), "RIFF-Chunk-Size = Datei − 8")
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, le32(wav, 16), "fmt-Body-Länge (PCM)")
        assertEquals(1, le16(wav, 20), "AudioFormat 1 = PCM")
        assertEquals(1, le16(wav, 22), "mono")
        assertEquals(24_000, le32(wav, 24), "SampleRate 24kHz (OpenAI-pcm-Doku)")
        assertEquals(48_000, le32(wav, 28), "ByteRate = 24000·2")
        assertEquals(2, le16(wav, 32), "BlockAlign = 2 (mono int16)")
        assertEquals(16, le16(wav, 34), "16 bit")
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(pcm.size, le32(wav, 40), "data-Länge = PCM-Länge")
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm), "Payload byte-identisch")
    }

    @Test
    fun `unabhaengiger Parser (JDK AudioSystem) dekodiert die Slice eigenstaendig`() {
        val pcm = ByteArray(9600) // 200ms Stille
        val wav = PcmWav.wrap(pcm)
        AudioSystem.getAudioInputStream(ByteArrayInputStream(wav)).use { stream ->
            val fmt = stream.format
            assertEquals(24_000f, fmt.sampleRate)
            assertEquals(1, fmt.channels)
            assertEquals(16, fmt.sampleSizeInBits)
            assertEquals(pcm.size / 2L, stream.frameLength, "Frames = Samples")
        }
    }

    @Test
    fun `leeres PCM ergibt gueltiges 44-Byte-WAV (Grenzfall, wird eh vorgefiltert)`() {
        val wav = PcmWav.wrap(ByteArray(0))
        assertEquals(44, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(0, le32(wav, 40))
    }
}
