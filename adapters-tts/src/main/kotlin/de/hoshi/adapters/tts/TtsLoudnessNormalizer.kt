package de.hoshi.adapters.tts

import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lautstärke-Normalisierung auf den TTS-Audio-Output (Andi-Befund 2026-06-21:
 * „Stimme unterschiedlich laut") — **portiert aus Hoshi 0.5** (`de.hoshi.app.tts.TtsLoudnessNormalizer`).
 *
 * **Problem:** die TTS-Engines (Voxtral :8042, OpenAI-Cloud) rendern jeder auf
 * seinem eigenen Pegel. Im Dialog springt die Lautstärke hörbar von Satz zu Satz.
 *
 * **Lösung:** EIN gemeinsamer RMS-Ziel-Pegel mit Peak-Sicherung, pro Satz-WAV
 * angewandt. Damit trägt jedes ausgespielte Audio denselben Pegel.
 *
 * **Konservativ + reversibel:**
 *  - Nur ein **lineares** Gain (kein Limiter/Kompressor, keine Klangfärbung).
 *  - **Begrenztes** Gain (max [maxGainDb], typ. +6 dB) — kein Aufblasen von
 *    Stille/Rauschen ins Clipping.
 *  - **Peak-Guard:** das Gain wird so gedeckelt, dass der Spitzenwert
 *    [peakCeilingDb] (z.B. −1 dBFS) nicht überschreitet → kein hartes Clipping.
 *  - **Floor-Gate:** sehr leise/leere Buffer (RMS unter [silenceFloorDb]) bleiben
 *    unangetastet — kein Hochziehen von Atem/Rauschen.
 *  - Greift NUR, wenn der [LoudnessNormalizingTtsPort]-Decorator eingehängt ist
 *    (flag-gated, default OFF in PipelineConfig) → sonst byte-identisches heutiges Audio.
 *
 * **Best-Effort / nie werfen:** unparsebare, nicht-PCM16- oder Nicht-WAV-Bytes
 * werden **unverändert** durchgereicht (nie Stille, nie Korruption, nie Exception).
 * Idempotent genug: erneutes Anwenden auf bereits normalisiertes Audio ändert
 * (innerhalb der Rundung) nichts mehr.
 *
 * Arbeitet auf int16-LE-PCM (Voxtral/OpenAI liefern 16-bit mono WAV). Bewusst
 * **pur** (kein Spring): konstruierbar mit den 0.5-Defaults, voll unit-testbar.
 * Der Roh-PCM-Pfad aus 0.5 (`normalizePcm`) ist hier WEG — 0.8 arbeitet mit
 * (Slice-)WAVs. Für den Streaming-Pfad ([de.hoshi.core.port.TtsPort.synthStream],
 * mehrere Slice-WAVs pro Satz) gibt es stattdessen die Naht
 * [estimateGain]/[applyFixedGain]/[applyGainRamp]: EIN Satz-Gain aus der ersten
 * Slice geschätzt, fix auf alle Slices angewandt (kein per-Slice-RMS-Pumpen);
 * mit Short-First (~280ms Schätz-Slice) EINMALIGE sanfte Nachjustierung an
 * Slice 2 — Details im [LoudnessNormalizingTtsPort].
 *
 * @property targetRmsDb   Ziel-RMS-Pegel in dBFS (0.5-Default −18 dBFS).
 * @property peakCeilingDb Spitzenwert-Decke in dBFS (0.5-Default −1 dBFS, < 0).
 * @property maxGainDb     maximales Anheben in dB (0.5-Default +6 dB).
 * @property silenceFloorDb RMS-Floor in dBFS: leiser → unangetastet (0.5-Default −50 dBFS).
 */
class TtsLoudnessNormalizer(
    private val targetRmsDb: Double = -18.0,
    private val peakCeilingDb: Double = -1.0,
    private val maxGainDb: Double = 6.0,
    private val silenceFloorDb: Double = -50.0,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Normalisiert ein vollständiges WAV (RIFF/fmt/data) auf den Ziel-Pegel.
     * Nicht-PCM16-, Nicht-WAV- oder kaputte Bytes werden **unverändert**
     * durchgereicht (nie Stille, nie Korruption, nie Exception). Original-Bytes
     * bleiben unangetastet (Arbeit auf einer Kopie — Cache-Sicherheit).
     */
    fun normalizeWav(wav: ByteArray): ByteArray = runCatching {
        val located = locateWavData(wav) ?: run {
            log.debug("[tts-norm] kein PCM16-WAV (size={}) — unverändert", wav.size)
            return@runCatching wav
        }
        val (dataOffset, dataLen, bitsPerSample) = located
        if (bitsPerSample != 16) {
            log.debug("[tts-norm] WAV nicht 16-bit (bps={}) — unverändert", bitsPerSample)
            return@runCatching wav
        }
        val gain = computeGain(wav, dataOffset, dataLen) ?: return@runCatching wav
        val out = wav.copyOf()
        applyGain(out, dataOffset, dataLen, gain)
        out
    }.getOrElse { e ->
        // Absolut never-throw: jeder unerwartete Parse-/Index-Fehler → Original.
        log.warn("[tts-norm] unerwarteter Fehler — Original unverändert: {}", e.message)
        wav
    }

    /**
     * **Streaming-Naht 1/2 (Satz-Gain schätzen):** ermittelt den Gain, den
     * [normalizeWav] auf dieses WAV anwenden WÜRDE — ohne ihn anzuwenden. Der
     * [LoudnessNormalizingTtsPort] ruft das auf der ERSTEN Slice eines Satzes und
     * wendet das Ergebnis via [applyFixedGain] fix auf ALLE Slices an.
     *
     * `null` = no-op (kein parsebares PCM16-WAV, leer, unter Silence-Floor oder
     * Gain≈1) — der Aufrufer lässt die Slices dann unangetastet. Never-throw wie
     * [normalizeWav].
     */
    fun estimateGain(wav: ByteArray): Double? = runCatching {
        val located = locateWavData(wav) ?: return@runCatching null
        val (dataOffset, dataLen, bitsPerSample) = located
        if (bitsPerSample != 16) return@runCatching null
        computeGain(wav, dataOffset, dataLen)
    }.getOrElse { e ->
        log.warn("[tts-norm] estimateGain-Fehler — no-op: {}", e.message)
        null
    }

    /**
     * **Streaming-Naht 2/2 (fixen Satz-Gain anwenden):** wendet den via
     * [estimateGain] geschätzten Satz-[gain] auf ein Slice-WAV an.
     *
     * **Per-Slice-Peak-Guard (reine Safety, senkt nur, hebt nie):** die Schätz-Slice
     * kennt die Peaks SPÄTERER Slices nicht — hat diese Slice einen heißeren Peak,
     * wird der Gain NUR für sie so weit gesenkt, dass [peakCeilingDb] hält. Ein
     * seltener, kleiner Pegel-Schritt (typisch <1–2 dB an einer Slice-Naht) ist
     * hörbar unauffälliger als Clipping-Knistern; RMS-Pumpen entsteht dadurch
     * nicht, weil nie ANGEHOBEN wird.
     *
     * Best-Effort wie [normalizeWav]: unparsebar/nicht-PCM16/Gain≈1/Sinnlos-Gain
     * → Original unverändert. Original-Bytes bleiben unangetastet (Kopie).
     */
    fun applyFixedGain(wav: ByteArray, gain: Double): ByteArray = runCatching {
        if (gain <= 0.0) return@runCatching wav
        val located = locateWavData(wav) ?: return@runCatching wav
        val (dataOffset, dataLen, bitsPerSample) = located
        if (bitsPerSample != 16) return@runCatching wav

        var g = gain
        // Peak-Guard dieser Slice: nach dem Gain darf ihr Spitzenwert peakCeiling nicht reißen.
        val peakNorm = peakNorm(wav, dataOffset, dataLen)
        if (peakNorm > 0.0) {
            val maxGainForPeak = dbToLinear(peakCeilingDb) / peakNorm
            if (g > maxGainForPeak) g = maxGainForPeak
        }
        // Praktisch kein Effekt → no-op (identisches Kriterium wie computeGain).
        if (abs(g - 1.0) < 0.01) return@runCatching wav

        val out = wav.copyOf()
        applyGain(out, dataOffset, dataLen, g)
        out
    }.getOrElse { e ->
        log.warn("[tts-norm] applyFixedGain-Fehler — Original unverändert: {}", e.message)
        wav
    }

    /**
     * **Streaming-Naht 3/3 (EINMALIGE sanfte Nachjustierung, Short-First):** die
     * Schätz-Slice ist mit Short-First nur noch ~280ms (statt 600ms) — eine
     * Onset-Stichprobe, typisch einige dB neben dem Satz-RMS. Der
     * [LoudnessNormalizingTtsPort] darf darum an Slice 2 EINMAL nachschätzen und
     * hier den Übergang fahren: der Gain läuft LINEAR über die GESAMTE Slice von
     * [fromGain] (exakt der Pegel, mit dem Slice 1 endete) auf [toGain] — die
     * Gain-Hüllkurve ist stetig, an KEINER Naht gibt es einen Pegel-Sprung, und
     * die Änderung verteilt sich über ~600ms (unhörbar langsam; Ravi-Veto hält).
     *
     * **Peak-Guard** wie [applyFixedGain] auf [toGain] (senkt nur, hebt nie);
     * während der Rampe schützt zusätzlich der Hard-Clip in [applyGain]-Manier —
     * die Slice beginnt am leisesten Schnittpunkt, das Restrisiko ist minimal.
     *
     * Best-Effort wie [normalizeWav]: unparsebar/nicht-PCM16/Sinnlos-Gains →
     * Original unverändert; `fromGain≈toGain≈1` → no-op (spart die Kopie).
     * `applyGainRamp(wav, g, g)` ist byte-identisch zu `applyFixedGain(wav, g)`,
     * solange der Peak-Guard nicht greift ([fromGain] bleibt bewusst ungekappt —
     * Naht-Stetigkeit zur vorigen Slice geht vor; der Hard-Clip sichert den Rest).
     */
    fun applyGainRamp(wav: ByteArray, fromGain: Double, toGain: Double): ByteArray = runCatching {
        if (fromGain <= 0.0 || toGain <= 0.0) return@runCatching wav
        val located = locateWavData(wav) ?: return@runCatching wav
        val (dataOffset, dataLen, bitsPerSample) = located
        if (bitsPerSample != 16) return@runCatching wav

        var to = toGain
        // Peak-Guard des Ziel-Gains: das Rampen-ENDE (und alle Folge-Slices mit
        // diesem Gain) darf den Spitzenwert dieser Slice nicht über die Decke treiben.
        val peakNorm = peakNorm(wav, dataOffset, dataLen)
        if (peakNorm > 0.0) {
            val maxGainForPeak = dbToLinear(peakCeilingDb) / peakNorm
            if (to > maxGainForPeak) to = maxGainForPeak
        }
        // Beide Enden praktisch 1 → kein Effekt → no-op (identisches Kriterium wie computeGain).
        if (abs(fromGain - 1.0) < 0.01 && abs(to - 1.0) < 0.01) return@runCatching wav

        val out = wav.copyOf()
        val sampleCount = dataLen / 2
        if (sampleCount <= 1) {
            applyGain(out, dataOffset, dataLen, to)
            return@runCatching out
        }
        val step = (to - fromGain) / (sampleCount - 1) // Sample 0 = fromGain, letztes = to
        var i = dataOffset
        var n = 0
        val end = dataOffset + (sampleCount * 2)
        while (i < end) {
            val s = (out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8)
            var scaled = Math.round(s * (fromGain + step * n)).toInt()
            // Hard-Clip-Schutz (Rampen-Anfang liegt an der leisen Naht, aber sicher ist sicher).
            if (scaled > 32767) scaled = 32767
            if (scaled < -32768) scaled = -32768
            out[i] = (scaled and 0xFF).toByte()
            out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
            n++
        }
        out
    }.getOrElse { e ->
        log.warn("[tts-norm] applyGainRamp-Fehler — Original unverändert: {}", e.message)
        wav
    }

    /** Spitzenwert (0..1 relativ Vollskala) der int16-LE-Samples in `[offset,offset+len)`. */
    private fun peakNorm(bytes: ByteArray, offset: Int, len: Int): Double {
        val sampleCount = len / 2
        var peak = 0
        var i = offset
        val end = offset + (sampleCount * 2)
        while (i < end) {
            val s = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            val a = abs(s)
            if (a > peak) peak = a
            i += 2
        }
        return peak / INT16_MAX
    }

    /**
     * Ermittelt den Gain-Faktor aus RMS-Ziel, gedeckelt durch [maxGainDb] und
     * Peak-Ceiling. Liefert `null` (→ no-op), wenn das Audio leer ist, unter dem
     * Silence-Floor liegt oder das Gain praktisch 1.0 ist (spart eine Kopie).
     */
    private fun computeGain(bytes: ByteArray, offset: Int, len: Int): Double? {
        val sampleCount = len / 2
        if (sampleCount <= 0) return null

        var sumSq = 0.0
        var peak = 0
        var i = offset
        val end = offset + (sampleCount * 2)
        while (i < end) {
            val s = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8) // signed int16 LE
            val a = abs(s)
            if (a > peak) peak = a
            val f = s.toDouble()
            sumSq += f * f
            i += 2
        }
        val rms = sqrt(sumSq / sampleCount) / INT16_MAX  // 0..1
        val peakNorm = peak / INT16_MAX                  // 0..1

        val silenceFloor = dbToLinear(silenceFloorDb)
        if (rms < silenceFloor || rms <= 0.0) {
            log.debug("[tts-norm] unter Silence-Floor (rms={} < {}) — no-op", rms, silenceFloor)
            return null
        }

        val targetRms = dbToLinear(targetRmsDb)
        var gain = targetRms / rms

        // Gain nach oben begrenzen (kein Aufblasen).
        val maxGain = dbToLinear(maxGainDb)
        if (gain > maxGain) gain = maxGain
        // Konsistenter Pegel: zu lautes Audio wird auch leiser gemacht. Untergrenze
        // nur gegen Sinnlos-Werte (kein negatives/0-Gain).
        if (gain <= 0.0) return null

        // Peak-Guard: nach dem Gain darf der Spitzenwert peakCeiling nicht reißen.
        val peakCeiling = dbToLinear(peakCeilingDb) // < 1.0, z.B. -1 dBFS
        if (peakNorm > 0.0) {
            val maxGainForPeak = peakCeiling / peakNorm
            if (gain > maxGainForPeak) gain = maxGainForPeak
        }

        // Praktisch kein Effekt → no-op (spart Kopie, hält Idempotenz sauber).
        if (abs(gain - 1.0) < 0.01) {
            log.debug("[tts-norm] Gain≈1 ({}) — no-op", gain)
            return null
        }
        log.debug("[tts-norm] rms={} peak={} → gain={} ({} dB)", rms, peakNorm, gain, 20 * Math.log10(gain))
        return gain
    }

    /** Wendet [gain] linear auf die int16-LE-Samples in `[offset,offset+len)` an, mit Hard-Clip-Schutz. */
    private fun applyGain(bytes: ByteArray, offset: Int, len: Int, gain: Double) {
        val sampleCount = len / 2
        var i = offset
        val end = offset + (sampleCount * 2)
        while (i < end) {
            val s = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            var scaled = Math.round(s * gain).toInt()
            // Hard-Clip-Schutz (sollte dank Peak-Guard nicht greifen, aber sicher ist sicher).
            if (scaled > 32767) scaled = 32767
            if (scaled < -32768) scaled = -32768
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * Findet den `data`-Subchunk in einem RIFF/WAV. Liefert
     * `(dataOffset, dataLen, bitsPerSample)` oder `null`, wenn es kein
     * geparstes PCM-WAV ist. Robust gegen Vor-`data`-Chunks (LIST/fact).
     */
    private fun locateWavData(bytes: ByteArray): Triple<Int, Int, Int>? {
        if (bytes.size < 44 ||
            String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null

        var bitsPerSample = 16
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    // fmt: bitsPerSample steht bei Offset +14 im fmt-Body (16-bit LE).
                    if (body + 16 <= bytes.size) {
                        bitsPerSample = (bytes[body + 14].toInt() and 0xFF) or
                            ((bytes[body + 15].toInt() and 0xFF) shl 8)
                    }
                }
                "data" -> {
                    val dataLen = size.coerceAtMost(bytes.size - body)
                    if (dataLen <= 0) return null
                    return Triple(body, dataLen, bitsPerSample)
                }
            }
            pos = body + size + (size and 1) // 2-Byte-Alignment
        }
        return null
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private companion object {
        private const val INT16_MAX = 32767.0

        /** dBFS → linearer Faktor (0 dBFS = 1.0). */
        private fun dbToLinear(db: Double): Double = Math.pow(10.0, db / 20.0)
    }
}
