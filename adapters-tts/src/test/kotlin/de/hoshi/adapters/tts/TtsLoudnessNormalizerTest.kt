package de.hoshi.adapters.tts

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests für [TtsLoudnessNormalizer] + [LoudnessNormalizingTtsPort] (Andi-Befund
 * „Stimme unterschiedlich laut", 2026-06-21). Beweist: leises WAV wird lauter
 * (bis Max-Gain), Peak-Guard hält unter der Decke, Stille bleibt unangetastet,
 * kaputter Input gibt das Original zurück, und der Decorator wahrt die
 * `Mono.empty()`-/Leer-Audio-Semantik.
 *
 * **Streaming-Pfad (Satz-Gain aus der ERSTEN Slice):** [TtsLoudnessNormalizer.estimateGain]
 * + [TtsLoudnessNormalizer.applyFixedGain] entsprechen auf einem Ganz-WAV exakt
 * [TtsLoudnessNormalizer.normalizeWav] (Batch-Äquivalenz); der Decorator wendet
 * den Satz-Gain fix auf die Slices an (kein Pumpen, kein Batch-Fallback),
 * der Per-Slice-Peak-Guard verhindert Clipping heißerer Folge-Slices.
 *
 * **Short-First-Nachjustierung (EINMAL, sanft):** die Schätz-Slice ist mit
 * Short-First nur ~280ms (Onset-Stichprobe) — an Slice 2 wird EINMAL nachgeschätzt
 * und via [TtsLoudnessNormalizer.applyGainRamp] stetig übergeblendet (Rampe über
 * die GANZE Slice, kein Pegel-Sprung an keiner Naht); ab Slice 3 gilt der Gain FIX.
 *
 * Reine, sidecar-freie Tests (JUnit Jupiter, wie [OpenAiTtsAdapterTest]).
 */
class TtsLoudnessNormalizerTest {

    private val targetRmsDb = -18.0
    private val peakCeilingDb = -1.0
    private val maxGainDb = 6.0
    private val silenceFloorDb = -50.0
    private val sr = 24_000

    private val normalizer = TtsLoudnessNormalizer(
        targetRmsDb = targetRmsDb,
        peakCeilingDb = peakCeilingDb,
        maxGainDb = maxGainDb,
        silenceFloorDb = silenceFloorDb,
    )

    // ── Helfer ────────────────────────────────────────────────────────────────

    /** Sinus-PCM (int16 LE) mit gegebener Amplitude (0..1 relativ zu Vollskala). */
    private fun sinePcm(amplitude: Double, samples: Int = 2400, freq: Double = 220.0): ByteArray {
        val out = ByteArray(samples * 2)
        for (n in 0 until samples) {
            val v = (amplitude * 32767.0 * sin(2 * PI * freq * n / sr)).toInt()
                .coerceIn(-32768, 32767)
            out[n * 2] = (v and 0xFF).toByte()
            out[n * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Minimaler 44-Byte-PCM-WAV-Header (RIFF/fmt/data) um rohes int16-LE-PCM. */
    private fun wrapPcmAsWav(
        pcm: ByteArray,
        sampleRate: Int = 24_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buf = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcm.size)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                       // fmt-Chunk-Größe (PCM)
        buf.putShort(1)                      // AudioFormat = PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcm.size)
        buf.put(pcm)
        return buf.array()
    }

    private fun rmsDbfs(pcm: ByteArray): Double {
        val n = pcm.size / 2
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(sumSq / n) / 32767.0
        return 20 * Math.log10(rms)
    }

    private fun peakDbfs(pcm: ByteArray): Double {
        val n = pcm.size / 2
        var peak = 0
        for (i in 0 until n) {
            val s = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
            val a = abs(s)
            if (a > peak) peak = a
        }
        return 20 * Math.log10(peak / 32767.0)
    }

    private fun dataOf(wav: ByteArray): ByteArray = wav.copyOfRange(44, wav.size)

    /** linearer Gain → dB (Soll-Deltas der Streaming-/Rampen-Tests). */
    private fun db(gain: Double): Double = 20 * Math.log10(gain)

    // ── WAV-Normalisierung ──────────────────────────────────────────────────────

    @Test
    fun `leises WAV wird auf Ziel-RMS angehoben (im Rahmen des Max-Gain)`() {
        // Sinus bei ~-23 dBFS RMS (Amplitude ~0.1) — 5 dB unter Ziel, voll vom +6 dB erreichbar.
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val before = rmsDbfs(dataOf(wav))
        assertTrue(before < targetRmsDb, "Test-Setup: Eingang ($before) leiser als Ziel")

        val normed = normalizer.normalizeWav(wav)
        val after = rmsDbfs(dataOf(normed))
        assertTrue(
            abs(after - targetRmsDb) < 1.0,
            "RMS nach Norm ($after dBFS) nahe Ziel ($targetRmsDb dBFS)",
        )
        assertEquals(wav.size, normed.size, "WAV-Größe unverändert (nur data-Rumpf skaliert)")
        // Header (44 Byte) byte-identisch — nur der data-Rumpf wird verändert.
        assertArrayEquals(
            wav.copyOfRange(0, 44),
            normed.copyOfRange(0, 44),
            "RIFF/fmt/data-Header unverändert",
        )
    }

    @Test
    fun `zu lautes WAV wird auf Ziel-RMS abgesenkt`() {
        // Sinus bei ~-9 dBFS RMS (Amplitude ~0.35) — deutlich über Ziel → absenken.
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.35), sampleRate = sr)
        val before = rmsDbfs(dataOf(wav))
        assertTrue(before > targetRmsDb, "Test-Setup: Eingang ($before) lauter als Ziel")

        val after = rmsDbfs(dataOf(normalizer.normalizeWav(wav)))
        assertTrue(
            abs(after - targetRmsDb) < 1.0,
            "RMS nach Norm ($after dBFS) auf Ziel ($targetRmsDb dBFS) abgesenkt",
        )
    }

    @Test
    fun `Peak-Ceiling greift bei sehr leisem WAV (kein Clipping)`() {
        // Sehr leiser Sinus weit unter Ziel — Anheben würde ohne Deckel den Peak treiben;
        // Max-Gain (+6 dB) + Peak-Ceiling halten unter der Decke.
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.03), sampleRate = sr)
        val peak = peakDbfs(dataOf(normalizer.normalizeWav(wav)))
        assertTrue(peak <= peakCeilingDb + 0.5, "Peak ($peak dBFS) bleibt unter Ceiling ($peakCeilingDb dBFS)")
        assertTrue(peak < 0.0, "kein Vollskala-Clipping")
    }

    @Test
    fun `Max-Gain begrenzt das Anheben (kein Aufblasen)`() {
        // -40 dBFS RMS Eingang: Ziel wäre +22 dB, aber maxGain ist +6 dB.
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.014), sampleRate = sr)
        val before = rmsDbfs(dataOf(wav))
        val after = rmsDbfs(dataOf(normalizer.normalizeWav(wav)))
        val applied = after - before
        assertTrue(applied <= maxGainDb + 0.5, "angewandtes Gain ($applied dB) ≤ maxGain ($maxGainDb dB)")
        assertTrue(after < targetRmsDb, "bleibt unter Ziel, weil Gain gedeckelt")
    }

    // ── Fix „Satz-zu-Satz-Stufen" (Ravi-Messung 2026-07-03, Andi Wetter-Antwort) ──
    // coral rendert Sätze chronisch leise (~−28…−34 dBFS) und mit 3–5 dB Pegel-
    // Streuung. Reicht der Gain-Cap NICHT für den Weg aufs Ziel (−18), sättigen
    // ALLE Sätze am Cap ⇒ jeder wird identisch verschoben ⇒ die Roh-Streuung (die
    // hörbaren Stufen) BLEIBT. Mit genug Headroom hebt die Pro-Satz-Normalisierung
    // den LEISEREN Satz STÄRKER an als den lauteren ⇒ die Stufe verschwindet.
    @Test
    fun `zu kleiner Gain-Cap konserviert die Satz-zu-Satz-Stufe, Headroom entfernt sie`() {
        // Zwei „Sätze" (Sinus-WAVs) mit ~3 dB Pegel-Abstand, beide chronisch leise
        // (wie coral) — beide bräuchten +8…+11 dB aufs −18-Ziel.
        val loud = wrapPcmAsWav(sinePcm(amplitude = 0.0709), sampleRate = sr)   // ~−26 dBFS
        val quiet = wrapPcmAsWav(sinePcm(amplitude = 0.0502), sampleRate = sr)  // ~−29 dBFS
        val rawGap = abs(rmsDbfs(dataOf(loud)) - rmsDbfs(dataOf(quiet)))
        assertTrue(rawGap in 2.0..4.0, "Test-Setup: Roh-Stufe (~3 dB) vorhanden: $rawGap")

        val capped = TtsLoudnessNormalizer(
            targetRmsDb = targetRmsDb, peakCeilingDb = peakCeilingDb,
            maxGainDb = 6.0, silenceFloorDb = silenceFloorDb,
        )
        val headroom = TtsLoudnessNormalizer(
            targetRmsDb = targetRmsDb, peakCeilingDb = peakCeilingDb,
            maxGainDb = 12.0, silenceFloorDb = silenceFloorDb,
        )

        val gapCapped = abs(
            rmsDbfs(dataOf(capped.normalizeWav(loud))) - rmsDbfs(dataOf(capped.normalizeWav(quiet))),
        )
        val gapHeadroom = abs(
            rmsDbfs(dataOf(headroom.normalizeWav(loud))) - rmsDbfs(dataOf(headroom.normalizeWav(quiet))),
        )

        // +6 dB: beide am Cap ⇒ Stufe bleibt (≈ Roh-Stufe).
        assertTrue(gapCapped > 2.0, "mit +6-Cap bleibt die Stufe (gap=$gapCapped ≈ roh $rawGap)")
        // +12 dB: beide erreichen ~Ziel ⇒ Stufe entfernt.
        assertTrue(gapHeadroom < 1.0, "mit +12-Cap ist die Stufe weg (gap=$gapHeadroom < 1 dB)")
        assertTrue(gapHeadroom < gapCapped - 1.5, "Headroom schrumpft die Stufe deutlich ($gapHeadroom << $gapCapped)")

        // Das ist exakt der STREAMING-Pfad-Entscheid: estimateGain je Satz — mit
        // Headroom bekommt der LEISERE Satz MEHR Gain (Differenzierung, nicht Uniform).
        val gLoud = headroom.estimateGain(loud)!!
        val gQuiet = headroom.estimateGain(quiet)!!
        assertTrue(gQuiet > gLoud, "leiserer Satz bekommt mehr Gain (${db(gQuiet)} > ${db(gLoud)} dB)")
    }

    @Test
    fun `Clip-Schutz haelt auch beim groesseren Gain-Cap (+12) — Peak-Guard dominiert`() {
        // Chronisch leiser Sinus (will viel Gain) MIT einem heissen Peak (Plosiv-artig,
        // hohe Crest) — der Peak-Guard muss trotz +12-Cap unter −1 dBFS deckeln.
        val pcm = sinePcm(amplitude = 0.02) // ~−37 dBFS RMS
        val spike = (0.7 * 32767).toInt()   // ein heisser Peak ~−3 dBFS
        pcm[100] = (spike and 0xFF).toByte()
        pcm[101] = ((spike shr 8) and 0xFF).toByte()
        val wav = wrapPcmAsWav(pcm, sampleRate = sr)

        val headroom = TtsLoudnessNormalizer(
            targetRmsDb = targetRmsDb, peakCeilingDb = peakCeilingDb,
            maxGainDb = 12.0, silenceFloorDb = silenceFloorDb,
        )
        val peak = peakDbfs(dataOf(headroom.normalizeWav(wav)))
        assertTrue(peak <= peakCeilingDb + 0.5, "Peak ($peak) bleibt trotz +12-Cap unter Ceiling ($peakCeilingDb)")
        assertTrue(peak < 0.0, "kein Vollskala-Clipping")
    }

    @Test
    fun `Stille unter Silence-Floor bleibt unveraendert`() {
        val wav = wrapPcmAsWav(ByteArray(2400), sampleRate = sr) // alle 0 → RMS = -inf
        val normed = normalizer.normalizeWav(wav)
        assertArrayEquals(wav, normed, "reine Stille unangetastet")
    }

    @Test
    fun `bereits normalisiertes WAV ist no-op (idempotent)`() {
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val once = normalizer.normalizeWav(wav)
        val twice = normalizer.normalizeWav(once)
        assertArrayEquals(once, twice, "zweiter Durchlauf liegt schon am Ziel → byte-identisch")
    }

    @Test
    fun `kaputter Nicht-WAV-Input wird unveraendert durchgereicht`() {
        val notWav = ByteArray(100) { (it % 7).toByte() }
        assertArrayEquals(notWav, normalizer.normalizeWav(notWav), "kein RIFF → Original")
    }

    @Test
    fun `zu kurzer Buffer wird unveraendert durchgereicht`() {
        val tiny = byteArrayOf(0x10, 0x20, 0x30)
        assertArrayEquals(tiny, normalizer.normalizeWav(tiny))
    }

    @Test
    fun `nicht-16-bit-WAV wird unveraendert durchgereicht`() {
        // 8-bit PCM-Header: Normalizer fasst nur 16-bit an.
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr, bitsPerSample = 8)
        assertArrayEquals(wav, normalizer.normalizeWav(wav), "nicht-16-bit → Original")
    }

    // ── Decorator ───────────────────────────────────────────────────────────────

    @Test
    fun `Decorator schickt WAV durch den Normalizer`() {
        val quietWav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val port = LoudnessNormalizingTtsPort(
            delegate = { _, _ -> Mono.just(quietWav) },
            normalizer = normalizer,
        )
        val out = port.synth("Hallo.", Language.DE).block()!!
        assertTrue(
            abs(rmsDbfs(dataOf(out)) - targetRmsDb) < 1.0,
            "Decorator-Ausgang ist auf Ziel-RMS normalisiert",
        )
    }

    @Test
    fun `Decorator wahrt Mono-empty (kein Audio)`() {
        val port = LoudnessNormalizingTtsPort(
            delegate = { _, _ -> Mono.empty() },
            normalizer = normalizer,
        )
        StepVerifier.create(port.synth("", Language.DE)).verifyComplete()
    }

    @Test
    fun `Decorator laesst leeren ByteArray unangetastet (Best-Effort kein Audio)`() {
        val port = LoudnessNormalizingTtsPort(
            delegate = { _, _ -> Mono.just(ByteArray(0)) },
            normalizer = normalizer,
        )
        val out = port.synth("Hallo.", Language.DE).block()!!
        assertTrue(out.isEmpty(), "leeres Audio bleibt leer (kein nackter Normalisier-Versuch)")
    }

    // ── Streaming: Satz-Gain aus der ERSTEN Slice (estimateGain/applyFixedGain) ──

    /**
     * Streaming-Delegate: [TtsPort.synthStream] liefert die gegebenen Slices; der
     * Batch-Pfad wirft absichtlich — läuft er doch, wäre der Streaming-Pfad des
     * Decorators zum Batch degradiert (genau der Regressions-Fall).
     */
    private fun streamingPort(vararg slices: ByteArray): TtsPort =
        object : TtsPort {
            override fun synth(text: String, language: Language): Mono<ByteArray> =
                Mono.error(IllegalStateException("Batch-Pfad darf im Streaming-Test nicht laufen"))
            override fun synthStream(text: String, language: Language): Flux<ByteArray> =
                Flux.fromIterable(slices.toList())
        }

    @Test
    fun `estimateGain plus applyFixedGain entspricht normalizeWav (Batch-Aequivalenz)`() {
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val gain = normalizer.estimateGain(wav)
        assertTrue(gain != null && gain > 1.0, "leises WAV → Anhebe-Gain geschätzt")
        assertArrayEquals(
            normalizer.normalizeWav(wav),
            normalizer.applyFixedGain(wav, gain!!),
            "Schätzen+fix anwenden auf dem GANZEN WAV == normalizeWav (byte-identisch)",
        )
    }

    @Test
    fun `applyFixedGain deckelt am Peak-Ceiling der Slice (kein Clipping heisser Folge-Slices)`() {
        // Heiße Slice (Peak ~-3 dBFS): ein fixer Satz-Gain von 2.0 (+6 dB, aus einer
        // leiseren Schätz-Slice) würde sie ohne Guard auf +3 dBFS treiben → Clipping.
        val hot = wrapPcmAsWav(sinePcm(amplitude = 0.7), sampleRate = sr)
        val out = normalizer.applyFixedGain(hot, 2.0)
        val peak = peakDbfs(dataOf(out))
        assertTrue(peak <= peakCeilingDb + 0.5, "Peak ($peak dBFS) bleibt unter Ceiling ($peakCeilingDb dBFS)")
    }

    @Test
    fun `applyFixedGain laesst kaputten Input und Sinnlos-Gain unveraendert`() {
        val notWav = ByteArray(100) { (it % 7).toByte() }
        assertArrayEquals(notWav, normalizer.applyFixedGain(notWav, 1.5), "kein RIFF → Original")
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        assertArrayEquals(wav, normalizer.applyFixedGain(wav, 0.0), "Gain ≤ 0 → Original")
        assertArrayEquals(wav, normalizer.applyFixedGain(wav, 1.0), "Gain≈1 → no-op (Original)")
    }

    // ── Streaming: sanfte Gain-Rampe (Short-First-Nachjustierung, applyGainRamp) ──

    @Test
    fun `applyGainRamp — Anfang exakt am fromGain, Ende exakt am toGain (stetige Huellkurve)`() {
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10, samples = 14_400), sampleRate = sr) // 600ms
        val out = normalizer.applyGainRamp(wav, 1.2, 1.8)
        val inData = dataOf(wav)
        val outData = dataOf(out)
        assertEquals(inData.size, outData.size)
        // 2%-RMS-Fenster: dort ist die Rampe praktisch konstant — der Gain am
        // Anfang MUSS from (Naht zur vorigen Slice), am Ende to (Naht zur nächsten) sein.
        val win = (inData.size / 50) and -2
        val headDelta = rmsDbfs(outData.copyOfRange(0, win)) - rmsDbfs(inData.copyOfRange(0, win))
        val tailDelta = rmsDbfs(outData.copyOfRange(outData.size - win, outData.size)) -
            rmsDbfs(inData.copyOfRange(inData.size - win, inData.size))
        assertEquals(db(1.2), headDelta, 0.2, "Rampen-Anfang am fromGain")
        assertEquals(db(1.8), tailDelta, 0.2, "Rampen-Ende am toGain")
        // Und die Mitte liegt dazwischen — monotone, sanfte Überblendung statt Sprung.
        val mid = (inData.size / 2) and -2
        val midDelta = rmsDbfs(outData.copyOfRange(mid - win / 2, mid + win / 2)) -
            rmsDbfs(inData.copyOfRange(mid - win / 2, mid + win / 2))
        assertTrue(midDelta > headDelta && midDelta < tailDelta, "Mitte zwischen from und to (midDelta=$midDelta)")
    }

    @Test
    fun `applyGainRamp mit from gleich to ist byte-identisch zu applyFixedGain`() {
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        assertArrayEquals(
            normalizer.applyFixedGain(wav, 1.5),
            normalizer.applyGainRamp(wav, 1.5, 1.5),
            "konstante Rampe == fixer Gain (gleiches Rounding, gleicher Guard)",
        )
    }

    @Test
    fun `applyGainRamp deckelt das Rampen-Ende am Peak-Ceiling (kein Clipping)`() {
        // Heiße Slice (Peak ~-3 dBFS): ein Ziel-Gain 2.0 würde das Rampen-Ende ohne
        // Guard auf +3 dBFS treiben → Clipping. Der Guard kappt to (senkt nur).
        val hot = wrapPcmAsWav(sinePcm(amplitude = 0.7), sampleRate = sr)
        val out = normalizer.applyGainRamp(hot, 1.0, 2.0)
        val peak = peakDbfs(dataOf(out))
        assertTrue(peak <= peakCeilingDb + 0.5, "Peak ($peak dBFS) bleibt unter Ceiling ($peakCeilingDb dBFS)")
    }

    @Test
    fun `applyGainRamp laesst kaputten Input und Sinnlos-Gains unveraendert`() {
        val notWav = ByteArray(100) { (it % 7).toByte() }
        assertArrayEquals(notWav, normalizer.applyGainRamp(notWav, 1.0, 1.5), "kein RIFF → Original")
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        assertArrayEquals(wav, normalizer.applyGainRamp(wav, 0.0, 1.5), "from ≤ 0 → Original")
        assertArrayEquals(wav, normalizer.applyGainRamp(wav, 1.5, 0.0), "to ≤ 0 → Original")
        assertArrayEquals(wav, normalizer.applyGainRamp(wav, 1.0, 1.0), "beide ≈1 → no-op (Original)")
    }

    @Test
    fun `Decorator-Stream haelt den Satz-Gain ab Slice 2 FIX (kein Pumpen)`() {
        // Slice 1+2 gleich laut (~-23 dBFS) ⇒ die einmalige Slice-2-Nachschätzung
        // ergibt denselben Gain (keine Rampe nötig); Slice 3 ist leiser (~-29 dBFS) —
        // per-Slice-RMS würde sie STÄRKER anheben (Pumpen), der fixe Satz-Gain hebt
        // ALLE um exakt dasselbe Delta.
        val slice1 = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val slice2 = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val slice3 = wrapPcmAsWav(sinePcm(amplitude = 0.05), sampleRate = sr)
        val port = LoudnessNormalizingTtsPort(
            delegate = streamingPort(slice1, slice2, slice3),
            normalizer = normalizer,
        )

        val out = port.synthStream("Hallo Welt.", Language.DE).collectList().block()!!

        assertEquals(3, out.size, "Streaming bleibt Streaming (KEIN Batch-Fallback auf synth)")
        val gainOnSlice1 = rmsDbfs(dataOf(out[0])) - rmsDbfs(dataOf(slice1))
        val gainOnSlice2 = rmsDbfs(dataOf(out[1])) - rmsDbfs(dataOf(slice2))
        val gainOnSlice3 = rmsDbfs(dataOf(out[2])) - rmsDbfs(dataOf(slice3))
        assertTrue(gainOnSlice1 > 1.0, "Satz wurde angehoben (leise Schätz-Slice)")
        assertEquals(gainOnSlice1, gainOnSlice2, 0.1, "identisches Gain-Delta auf Slice 2 (fix)")
        assertEquals(gainOnSlice1, gainOnSlice3, 0.1, "identisches Gain-Delta auch auf der LEISEREN Slice 3 (kein Pumpen)")
        assertTrue(
            abs(rmsDbfs(dataOf(out[0])) - targetRmsDb) < 1.0,
            "erste Slice (Schätz-Basis) liegt am Ziel-RMS",
        )
    }

    @Test
    fun `Decorator-Stream — EINMALIGE sanfte Nachjustierung an Slice 2 (Short-First), danach fix`() {
        // Kurze Schätz-Slice (~280ms Onset) täuscht zu leise (~-29 dBFS ⇒ Gain am
        // +6dB-Cap); Slice 2 (~-23 dBFS) ist satz-repräsentativer ⇒ EINMAL nachjustieren:
        // Slice 2 rampt STETIG vom Slice-1-Gain auf den neuen (kein Sprung an keiner
        // Naht), Slice 3 läuft fix mit dem neuen Gain — und Slice 4 (wieder leiser)
        // beweist: KEINE zweite Nachjustierung (kein Pumpen).
        val shortFirst = wrapPcmAsWav(sinePcm(amplitude = 0.05, samples = 6_720), sampleRate = sr) // 280ms
        val slice2 = wrapPcmAsWav(sinePcm(amplitude = 0.10, samples = 14_400), sampleRate = sr) // 600ms
        val slice3 = wrapPcmAsWav(sinePcm(amplitude = 0.10, samples = 14_400), sampleRate = sr)
        val slice4 = wrapPcmAsWav(sinePcm(amplitude = 0.05, samples = 14_400), sampleRate = sr)
        val g1 = normalizer.estimateGain(shortFirst)!! // ~2.0 (+6dB-Cap)
        val g2 = normalizer.estimateGain(slice2)!! // ~1.78
        assertTrue(g1 > g2 + 0.1, "Test-Setup: kurze Schätz-Slice muss den Gain überschätzen")
        val port = LoudnessNormalizingTtsPort(
            delegate = streamingPort(shortFirst, slice2, slice3, slice4),
            normalizer = normalizer,
        )

        val out = port.synthStream("Hallo Welt.", Language.DE).collectList().block()!!
        assertEquals(4, out.size)

        // Slice 1: Erst-Schätzwert voll angewandt.
        assertEquals(db(g1), rmsDbfs(dataOf(out[0])) - rmsDbfs(dataOf(shortFirst)), 0.15, "Slice 1 am Erst-Gain")
        // Slice 2: Rampe — ANFANG exakt am alten Gain (kein Sprung an Naht 1→2),
        // ENDE exakt am neuen (kein Sprung an Naht 2→3). 2%-RMS-Fenster.
        val win = (dataOf(slice2).size / 50) and -2
        val headDelta = rmsDbfs(dataOf(out[1]).copyOfRange(0, win)) -
            rmsDbfs(dataOf(slice2).copyOfRange(0, win))
        val tailDelta = rmsDbfs(dataOf(out[1]).copyOfRange(dataOf(out[1]).size - win, dataOf(out[1]).size)) -
            rmsDbfs(dataOf(slice2).copyOfRange(dataOf(slice2).size - win, dataOf(slice2).size))
        assertEquals(db(g1), headDelta, 0.2, "Rampen-ANFANG am alten Gain — kein Sprung an der Naht 1→2")
        assertEquals(db(g2), tailDelta, 0.2, "Rampen-ENDE am neuen Gain — kein Sprung an der Naht 2→3")
        // Slice 3: fix am nachjustierten Gain.
        assertEquals(db(g2), rmsDbfs(dataOf(out[2])) - rmsDbfs(dataOf(slice3)), 0.15, "Slice 3 fix am neuen Gain")
        assertTrue(abs(rmsDbfs(dataOf(out[2])) - targetRmsDb) < 1.0, "Satz-Rest liegt am Ziel-RMS")
        // Slice 4 (leiser): DERSELBE Gain — Nachjustierung gibt es nur EINMAL.
        assertEquals(db(g2), rmsDbfs(dataOf(out[3])) - rmsDbfs(dataOf(slice4)), 0.15, "keine zweite Nachjustierung")
    }

    @Test
    fun `Decorator-Stream mit stiller 280ms-Schaetz-Slice — Stille bleibt, ab Slice 2 sanft eingefahren`() {
        // Mit Short-First ist eine reine Lead-in-Stille-Schätz-Slice realistisch.
        // Früher blieb dann der GANZE Satz unnormalisiert; jetzt rettet die einmalige
        // Slice-2-Nachschätzung den Satz: Stille bleibt Stille, Slice 2 rampt stetig
        // von 1.0 (nahtlos zur unangetasteten Stille) auf den echten Gain, Rest fix.
        val silent = wrapPcmAsWav(ByteArray(2 * 6_720), sampleRate = sr) // 280ms Stille
        val loud1 = wrapPcmAsWav(sinePcm(amplitude = 0.10, samples = 14_400), sampleRate = sr)
        val loud2 = wrapPcmAsWav(sinePcm(amplitude = 0.10, samples = 14_400), sampleRate = sr)
        val g = normalizer.estimateGain(loud1)!!
        val port = LoudnessNormalizingTtsPort(delegate = streamingPort(silent, loud1, loud2), normalizer = normalizer)

        val out = port.synthStream("Hallo.", Language.DE).collectList().block()!!

        assertEquals(3, out.size)
        assertArrayEquals(silent, out[0], "stille Schätz-Slice unangetastet")
        val win = (dataOf(loud1).size / 50) and -2
        val headDelta = rmsDbfs(dataOf(out[1]).copyOfRange(0, win)) -
            rmsDbfs(dataOf(loud1).copyOfRange(0, win))
        assertEquals(0.0, headDelta, 0.2, "Rampen-Anfang bei 1.0 — nahtlos zur unangetasteten Stille")
        assertTrue(
            abs(rmsDbfs(dataOf(out[2])) - targetRmsDb) < 1.0,
            "ab Slice 3 fix am echten Gain (Satz gerettet statt komplett unnormalisiert)",
        )
        assertEquals(db(g), rmsDbfs(dataOf(out[2])) - rmsDbfs(dataOf(loud2)), 0.15, "fixer Gain = Slice-2-Schätzung")
    }

    @Test
    fun `Decorator-Stream ueber Batch-Default (ein WAV) ist byte-identisch zu normalizeWav`() {
        // synthStream-Default eines Batch-Adapters = EIN Ganz-WAV-Element: die
        // Schätz-Slice IST der ganze Satz ⇒ exakt das normalizeWav-Ergebnis.
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val port = LoudnessNormalizingTtsPort(
            delegate = { _, _ -> Mono.just(wav) }, // SAM: nur synth, synthStream = Default-Wrap
            normalizer = normalizer,
        )
        val out = port.synthStream("Hallo.", Language.DE).collectList().block()!!
        assertEquals(1, out.size)
        assertArrayEquals(normalizer.normalizeWav(wav), out[0], "Batch-Äquivalenz byte-identisch")
    }

    @Test
    fun `Decorator-Stream laesst leere Elemente unangetastet und verbraucht sie nicht als Schaetz-Slice`() {
        val quiet = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val port = LoudnessNormalizingTtsPort(
            delegate = streamingPort(ByteArray(0), quiet, quiet),
            normalizer = normalizer,
        )
        val out = port.synthStream("Hallo.", Language.DE).collectList().block()!!

        assertEquals(3, out.size)
        assertTrue(out[0].isEmpty(), "leeres Element bleibt leer")
        // Die Schätzung lief auf der ersten NICHT-leeren Slice → beide normalisiert.
        assertTrue(
            abs(rmsDbfs(dataOf(out[1])) - targetRmsDb) < 1.0,
            "erste nicht-leere Slice normalisiert (leeres Element war keine Schätz-Slice)",
        )
        assertArrayEquals(out[1], out[2], "identische Slices → identisches Ergebnis (fixer Gain)")
    }

    // ── Per-Turn-Voice (Backlog #6): der Decorator ist voice-transparent ─────────

    @Test
    fun `Decorator reicht den Request-Voice UNVERAENDERT an den Delegate (Batch und Stream)`() {
        val wav = wrapPcmAsWav(sinePcm(amplitude = 0.10), sampleRate = sr)
        val voices = mutableListOf<String?>()
        val delegate = object : TtsPort {
            override fun synth(text: String, language: Language): Mono<ByteArray> =
                Mono.error(IllegalStateException("voice-loser Batch-Pfad darf hier nicht laufen"))
            override fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> {
                voices.add(voice)
                return Mono.just(wav)
            }
            override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> {
                voices.add(voice)
                return Flux.just(wav)
            }
        }
        val port = LoudnessNormalizingTtsPort(delegate = delegate, normalizer = normalizer)

        port.synth("Hallo.", Language.DE, "nova").block()!!
        port.synthStream("Hallo.", Language.DE, "ballad").collectList().block()!!

        assertEquals(listOf<String?>("nova", "ballad"), voices, "voice muss den Decorator ungefiltert passieren")
    }
}
