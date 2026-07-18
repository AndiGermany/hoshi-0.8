package de.hoshi.adapters.tts

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Beweist die beiden Ravi-Invarianten des [PcmStreamSlicer]:
 *  1. LOSSLESS — Konkatenation aller Slices (+flush) == Eingangsstrom, egal in
 *     welchen (auch ungeraden) Netzwerk-Häppchen er ankam.
 *  2. ZERO-CROSSING — geschnitten wird am leisesten Punkt im Fenster: beide
 *     Naht-Samples (Slice-Ende / Folge-Slice-Anfang) liegen nahe Null ⇒ kein Klick.
 */
class PcmStreamSlicerTest {

    /** 440-Hz-Sinus, int16-LE mono @24kHz — realistisches „Stimm"-Signal. */
    private fun sinePcm(samples: Int, amp: Double = 20_000.0, freqHz: Double = 440.0): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amp * sin(2 * PI * freqHz * i / 24_000.0)).roundToInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)

    /** Füttert [pcm] in Häppchen der Größe [chunk] und sammelt Slices + Flush-Rest. */
    private fun sliceAll(pcm: ByteArray, slicer: PcmStreamSlicer, chunk: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < pcm.size) {
            val end = min(pos + chunk, pcm.size)
            out += slicer.push(pcm.copyOfRange(pos, end))
            pos = end
        }
        slicer.flush()?.let { out += it }
        return out
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    @Test
    fun `lossless — Konkatenation aller Slices ist byte-identisch zum Strom`() {
        val pcm = sinePcm(24_000) // 24.000 Samples @24kHz = 1s = 48.000 Bytes
        val slices = sliceAll(pcm, PcmStreamSlicer(targetSliceBytes = 12_000, cutWindowBytes = 960), chunk = 4096)
        assertTrue(slices.size >= 3, "48.000 Bytes @ 12.000er-Ziel muss mehrere Slices geben, war ${slices.size}")
        assertTrue(concat(slices).contentEquals(pcm), "kein Sample verloren/doppelt")
    }

    @Test
    fun `Zero-Crossing — beide Naht-Samples jeder Schnittstelle liegen nahe Null`() {
        val pcm = sinePcm(48_000, amp = 20_000.0) // 2s, Peak 20000
        val slices = sliceAll(pcm, PcmStreamSlicer(targetSliceBytes = 12_000, cutWindowBytes = 960), chunk = 8192)
        assertTrue(slices.size >= 4)
        // 440Hz@24kHz: max. Sample-Schritt am Nulldurchgang ≈ amp·2π·f/fs ≈ 2300 —
        // der leiseste Punkt im ±20ms-Fenster muss deutlich unter Peak liegen.
        for (i in 0 until slices.size - 1) {
            val endOfSlice = sampleAt(slices[i], slices[i].size - 2)
            val startOfNext = sampleAt(slices[i + 1], 0)
            assertTrue(
                abs(endOfSlice) < 2_500,
                "Slice-$i-Ende zu laut für eine Naht: $endOfSlice",
            )
            assertTrue(
                abs(startOfNext) < 2_500,
                "Slice-${i + 1}-Anfang zu laut für eine Naht: $startOfNext",
            )
        }
    }

    @Test
    fun `Slice-Laengen liegen im Ziel-Fenster (ausser Flush-Rest)`() {
        val pcm = sinePcm(48_000)
        val slicer = PcmStreamSlicer(targetSliceBytes = 12_000, cutWindowBytes = 960)
        val slices = sliceAll(pcm, slicer, chunk = 4096)
        for (i in 0 until slices.size - 1) { // letzter = Flush-Rest, darf kürzer sein
            assertTrue(
                slices[i].size in (12_000 - 960)..(12_000 + 960),
                "Slice $i ausserhalb Ziel±Fenster: ${slices[i].size}",
            )
        }
    }

    @Test
    fun `ungerade Netzwerk-Haeppchen — Schnitte bleiben sample-aligned, lossless`() {
        val pcm = sinePcm(12_000) // 0.5s
        val slicer = PcmStreamSlicer(targetSliceBytes = 4_800, cutWindowBytes = 480)
        val out = ArrayList<ByteArray>()
        // bewusst krumme Häppchen (1,3,7,11,...) — wie TCP sie liefern könnte
        var pos = 0
        var step = 1
        while (pos < pcm.size) {
            val end = min(pos + step, pcm.size)
            out += slicer.push(pcm.copyOfRange(pos, end))
            pos = end
            step = (step * 3 + 1) % 977 + 1
        }
        slicer.flush()?.let { out += it }
        assertTrue(out.size >= 2)
        out.forEach { assertEquals(0, it.size % 2, "Slice nicht auf int16-Grenze: ${it.size}") }
        assertTrue(concat(out).contentEquals(pcm), "lossless auch bei krummen Häppchen")
    }

    @Test
    fun `dangling Einzelbyte am Stream-Ende (halbes int16) wird getrimmt`() {
        val pcm = sinePcm(1_000) + byteArrayOf(42) // abgerissener Stream: 2001 Bytes
        val slicer = PcmStreamSlicer(targetSliceBytes = 48_000, cutWindowBytes = 960)
        assertTrue(slicer.push(pcm).isEmpty(), "unter Ziel ⇒ noch keine Slice")
        val tail = slicer.flush()
        assertEquals(2_000, tail!!.size, "gerade Länge — halbes Sample getrimmt")
        assertTrue(tail.contentEquals(pcm.copyOfRange(0, 2_000)))
    }

    @Test
    fun `kleiner Strom unter Ziel-Groesse kommt komplett als eine Flush-Slice`() {
        val pcm = sinePcm(500)
        val slicer = PcmStreamSlicer(targetSliceBytes = 28_800, cutWindowBytes = 960)
        assertTrue(slicer.push(pcm).isEmpty())
        assertTrue(slicer.flush()!!.contentEquals(pcm))
        assertNull(slicer.flush(), "zweiter Flush ist leer")
    }

    // ── Short-First (Latenz-Resthebel): NUR die erste Slice kürzer ──────────────

    @Test
    fun `Short-First — erste Slice am firstSliceBytes-Ziel, Folge-Slices am target, lossless`() {
        val pcm = sinePcm(48_000) // 2s = 96.000 Bytes
        val slicer = PcmStreamSlicer(
            targetSliceBytes = 28_800, // ~600ms
            cutWindowBytes = 960,
            firstSliceBytes = 13_440, // ~280ms
        )
        // bewusst krummer Chunk — Short-First muss auch bei odd-TCP-Häppchen halten
        val slices = sliceAll(pcm, slicer, chunk = 997)
        assertTrue(slices.size >= 3, "kurze erste + normale Folge-Slices erwartet, war ${slices.size}")
        assertTrue(
            slices[0].size in (13_440 - 960)..(13_440 + 960),
            "ERSTE Slice muss am kurzen Ziel liegen: ${slices[0].size}",
        )
        for (i in 1 until slices.size - 1) { // letzter = Flush-Rest, darf kürzer sein
            assertTrue(
                slices[i].size in (28_800 - 960)..(28_800 + 960),
                "Folge-Slice $i muss am normalen Ziel bleiben: ${slices[i].size}",
            )
        }
        assertTrue(concat(slices).contentEquals(pcm), "lossless auch mit Short-First")
    }

    @Test
    fun `Short-First — Zero-Crossing haelt auch an der kurzen ersten Grenze`() {
        val pcm = sinePcm(48_000, amp = 20_000.0)
        val slices = sliceAll(
            pcm,
            PcmStreamSlicer(targetSliceBytes = 28_800, cutWindowBytes = 960, firstSliceBytes = 13_440),
            chunk = 8192,
        )
        assertTrue(slices.size >= 3)
        // Wie im Basis-Test: JEDE Naht (v.a. die erste, kurze) liegt am leisen Punkt.
        for (i in 0 until slices.size - 1) {
            val endOfSlice = sampleAt(slices[i], slices[i].size - 2)
            val startOfNext = sampleAt(slices[i + 1], 0)
            assertTrue(abs(endOfSlice) < 2_500, "Slice-$i-Ende zu laut für eine Naht: $endOfSlice")
            assertTrue(abs(startOfNext) < 2_500, "Slice-${i + 1}-Anfang zu laut für eine Naht: $startOfNext")
        }
    }

    @Test
    fun `Short-First — erste Slice faellt schon nach firstTarget+Fenster Bytes (Latenz-Beweis)`() {
        // Exakt die Bytes, die die KURZE Grenze braucht (Ziel + Fenster + 1 Sample).
        val justEnough = sinePcm((13_440 + 960 + 2) / 2)
        val shortFirst = PcmStreamSlicer(targetSliceBytes = 28_800, cutWindowBytes = 960, firstSliceBytes = 13_440)
        assertEquals(1, shortFirst.push(justEnough).size, "kurze erste Slice muss ohne 600ms-Content-Wartezeit fallen")
        // Gegenprobe: OHNE Short-First (Default) wäre hier noch NICHTS fertig —
        // genau die ~320ms Content-Wartezeit, die der Resthebel einspart.
        val classic = PcmStreamSlicer(targetSliceBytes = 28_800, cutWindowBytes = 960)
        assertTrue(classic.push(justEnough).isEmpty(), "ohne Short-First erst bei target+Fenster")
    }

    @Test
    fun `Default (firstSliceBytes ungesetzt) — byte-identisch zum bisherigen Verhalten`() {
        val pcm = sinePcm(48_000)
        val legacy = sliceAll(pcm, PcmStreamSlicer(targetSliceBytes = 12_000, cutWindowBytes = 960), chunk = 4096)
        val explicit = sliceAll(
            pcm,
            PcmStreamSlicer(targetSliceBytes = 12_000, cutWindowBytes = 960, firstSliceBytes = 12_000),
            chunk = 4096,
        )
        assertEquals(legacy.size, explicit.size, "gleiche Slice-Anzahl")
        for (i in legacy.indices) {
            assertArrayEquals(legacy[i], explicit[i], "Slice $i muss byte-identisch sein")
        }
    }

    @Test
    fun `Fenster 0 schneidet exakt am Ziel (harter, deterministischer Schnitt)`() {
        val pcm = sinePcm(24_000)
        val slices = sliceAll(pcm, PcmStreamSlicer(targetSliceBytes = 9_600, cutWindowBytes = 0), chunk = 4096)
        for (i in 0 until slices.size - 1) {
            assertEquals(9_600, slices[i].size, "harter Schnitt exakt bei targetSliceBytes")
        }
        assertTrue(concat(slices).contentEquals(pcm))
    }
}
