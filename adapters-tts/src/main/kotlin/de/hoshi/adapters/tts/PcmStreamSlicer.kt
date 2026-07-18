package de.hoshi.adapters.tts

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **PcmStreamSlicer** — schneidet einen ankommenden rohen PCM16-LE-Strom
 * (OpenAI `response_format=pcm`, 24kHz mono) in ~[targetSliceBytes] große
 * Slices, damit das ERSTE Audio-Häppchen rausgehen kann, lange bevor der ganze
 * Satz fertig synthetisiert ist (Latenz-Hebel #1, Lars-Messung: Batch-Roundtrip
 * dominiert Time-to-first-Audio).
 *
 * **Ravi-Veto „nie matschen/klicken" — zwei Invarianten:**
 *  1. **Lossless:** die Konkatenation aller Slices (+[flush]-Rest) ist
 *     BYTE-IDENTISCH zum Eingangsstrom — kein Sample verworfen, keins doppelt,
 *     kein Resampling, kein Overlap. (Einzige Ausnahme: ein DANGLING-Einzelbyte
 *     am Stream-Ende — ein halbes int16 eines abgerissenen Streams — wird
 *     getrimmt, sonst wäre das letzte WAV nicht dekodierbar.)
 *  2. **Zero-Crossing/Ruhepunkt-Schnitt:** geschnitten wird nicht stur bei
 *     [targetSliceBytes], sondern am LEISESTEN Sample-Übergang innerhalb von
 *     ±[cutWindowBytes] um das Ziel (minimale |Amplitude| beidseits der Naht).
 *     Beide Seiten der Chunk-Grenze liegen damit nahe Null ⇒ kein Stufen-Sprung
 *     ⇒ kein Klick; ein FE-Mikro-Gap (onended-back-to-back) klingt an so einer
 *     Naht wie eine winzige Pause, nicht wie ein Knacks.
 *
 * Schnitte sind IMMER auf Sample-Grenzen ausgerichtet (gerade Byte-Offsets) —
 * egal in welchen (auch ungeraden) Netzwerk-Häppchen der Strom ankommt.
 *
 * **Short-First (Latenz-Resthebel, Lars-Nachmessung):** die OpenAI-Synthese ist
 * schneller als Echtzeit, alle Slices kommen als Burst kurz nach dem TTFB —
 * der letzte Hebel ist die WARTEZEIT auf den Inhalt der ERSTEN Slice. Darum
 * kann sie via [firstSliceBytes] kürzer sein (~280ms statt 600ms): erstes Audio
 * ≈ TTFB statt TTFB+600ms-Content. Zero-Crossing-Schnitt gilt unverändert auch
 * an der kurzen ersten Grenze. Default [firstSliceBytes] = [targetSliceBytes]
 * ⇒ exakt das bisherige Verhalten.
 *
 * Stateful pro Satz/Stream (ein Slicer je `synthStream`-Subscription), pur,
 * kein IO — voll unit-testbar.
 *
 * @param targetSliceBytes Ziel-Slice-Größe in Bytes (wird auf gerade Zahl
 *   normalisiert). 24kHz·16bit mono ⇒ 48 Bytes/ms; 600ms ≈ 28.800 Bytes.
 * @param cutWindowBytes Suchfenster ± um das Ziel für den leisesten Schnitt
 *   (gerade normalisiert). 0 = harter Schnitt exakt bei [targetSliceBytes].
 * @param firstSliceBytes Ziel-Größe NUR der ersten Slice (Short-First, gerade
 *   normalisiert). 280ms ≈ 13.440 Bytes. Default = [targetSliceBytes].
 */
class PcmStreamSlicer(
    targetSliceBytes: Int,
    cutWindowBytes: Int = 0,
    firstSliceBytes: Int = targetSliceBytes,
) {
    private val target: Int = max(4, targetSliceBytes) and EVEN_MASK
    private val window: Int = max(0, cutWindowBytes) and EVEN_MASK
    private val firstTarget: Int = max(4, firstSliceBytes) and EVEN_MASK

    /** Noch keine Slice geschnitten ⇒ das (ggf. kürzere) [firstTarget] gilt. */
    private var firstCutPending = true

    /** Noch nicht geschnittene Bytes (Rest wandert von push zu push weiter). */
    private var pending: ByteArray = ByteArray(0)

    /**
     * Nimmt das nächste (beliebig große, auch ungerade) Netzwerk-Häppchen an und
     * liefert alle dadurch KOMPLETT gewordenen Slices (0..n) in Reihenfolge.
     */
    fun push(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        pending = pending + bytes
        val out = ArrayList<ByteArray>(1)
        // Erst schneiden, wenn auch das Suchfenster HINTER dem Ziel voll da ist —
        // sonst würde der „leiseste Punkt" auf halber Information gewählt.
        // Short-First: für die ERSTE Slice gilt das (ggf. kürzere) firstTarget.
        while (true) {
            val tgt = if (firstCutPending) firstTarget else target
            if (pending.size < tgt + window + BYTES_PER_SAMPLE) break
            val cut = quietestCut(pending, tgt)
            out.add(pending.copyOfRange(0, cut))
            pending = pending.copyOfRange(cut, pending.size)
            firstCutPending = false
        }
        return out
    }

    /**
     * Stream-Ende: liefert den Rest als letzte Slice (oder `null`, wenn nichts
     * mehr da ist). Ein dangling Einzelbyte (halbes int16, abgerissener Stream)
     * wird getrimmt — WAV braucht ganze Frames.
     */
    fun flush(): ByteArray? {
        val even = pending.size and EVEN_MASK
        val tail = if (even <= 0) null else pending.copyOfRange(0, even)
        pending = ByteArray(0)
        return tail
    }

    /**
     * Leisester Schnittpunkt: gerader Offset `p` in `[tgt−window, tgt+window]`
     * mit minimaler |sample(p−2)| + |sample(p)| — die beiden Samples, die nach dem
     * Schnitt direkt an der Naht liegen (Slice-Ende / Folge-Slice-Anfang).
     * [tgt] ist [firstTarget] für die erste Slice (Short-First), sonst [target].
     */
    private fun quietestCut(buf: ByteArray, tgt: Int): Int {
        val lo = max(BYTES_PER_SAMPLE, tgt - window) and EVEN_MASK
        val hi = min(tgt + window, buf.size - BYTES_PER_SAMPLE) and EVEN_MASK
        var best = min(tgt, hi)
        if (window == 0) return best
        var bestScore = Int.MAX_VALUE
        var p = lo
        while (p <= hi) {
            val score = abs(sampleAt(buf, p - 2)) + abs(sampleAt(buf, p))
            if (score < bestScore) {
                bestScore = score
                best = p
            }
            p += BYTES_PER_SAMPLE
        }
        return best
    }

    /** int16-LE bei [off] (identischer Trick wie [TtsLoudnessNormalizer]). */
    private fun sampleAt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)

    private companion object {
        private const val EVEN_MASK = -2 // 0xFFFFFFFE — rundet auf gerade Offsets ab
        private const val BYTES_PER_SAMPLE = PcmWav.BYTES_PER_SAMPLE
    }
}
