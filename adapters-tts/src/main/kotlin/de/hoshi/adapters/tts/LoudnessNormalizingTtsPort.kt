package de.hoshi.adapters.tts

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.math.abs

/**
 * **Loudness-normalisierender [TtsPort]-Decorator** — schiebt das von einem
 * delegierten TTS-Adapter (Voxtral/OpenAI) gelieferte WAV pro Satz durch den
 * [TtsLoudnessNormalizer], damit jeder Satz auf demselben Pegel klingt
 * (Andi-Befund 2026-06-21: „Stimme unterschiedlich laut").
 *
 * **Transparent + best-effort:** der Decorator ändert NUR die Pegel-Amplitude des
 * `data`-Rumpfs eines WAV. Er reicht alles andere unverändert durch:
 *  - `Mono.empty()` (leerer Text / Voxtral „kein Audio") bleibt `Mono.empty()` —
 *    `map` greift nur auf `onNext`.
 *  - ein leerer `ByteArray` (OpenAI-Best-Effort: fehlender Key, 401, Timeout)
 *    bleibt leer — kein Parse-Versuch.
 *  - Fehler aus dem [delegate] propagieren unverändert (kein zusätzliches
 *    `onErrorResume` — das ist Sache der [de.hoshi.core.pipeline.TtsStage], die den
 *    Never-Silent-Vertrag hält).
 *
 * Der [TtsLoudnessNormalizer] selbst wirft NIE und gibt bei unparsebarem/nicht-PCM16-
 * WAV das Original zurück → bei eingehängtem Decorator kann nie Audio verloren gehen.
 *
 * **Streaming-Pfad ([synthStream]) — Loudness UND Latenz-Gewinn zugleich:**
 * per-Slice-RMS-Normalisierung würde den Pegel INNERHALB eines Satzes atmen
 * lassen (Pumpen — Ravi-Veto), ein Batch-Fallback würde den TTFB-Gewinn des
 * Streaming-Adapters wegwerfen. Stattdessen **Satz-Gain aus der ERSTEN Slice**:
 *  - Die erste nicht-leere Slice liefert via [TtsLoudnessNormalizer.estimateGain]
 *    die RMS-Schätzung; dieser Gain wird via [TtsLoudnessNormalizer.applyFixedGain]
 *    FIX auf die Slices des Satzes angewandt → kein Pumpen, Slices fließen sofort.
 *  - **Short-First-Nachjustierung (EINMAL, sanft):** mit Short-First
 *    ([OpenAiTtsAdapter], erste Slice ~280ms statt 600ms) ist die Schätz-Slice
 *    nur noch eine Onset-STICHPROBE (Anlaut/Lead-in, typisch einige dB unterm
 *    Satz-RMS). Darum wird an der ZWEITEN nicht-leeren Slice (~600ms, satz-
 *    repräsentativer) EINMAL nachgeschätzt: weicht der neue Gain ab, fährt
 *    [TtsLoudnessNormalizer.applyGainRamp] ihn LINEAR über die gesamte Slice 2
 *    vom alten auf den neuen Wert — die Gain-Hüllkurve ist stetig (Start exakt
 *    beim Slice-1-Gain, Ende exakt beim neuen), kein hörbarer Sprung an keiner
 *    Naht. Ab Slice 3 gilt der nachjustierte Gain FIX — keine weitere Anpassung,
 *    kein Pumpen. War die 280ms-Schätz-Slice reine Stille/Lead-in (unter
 *    Silence-Floor ⇒ `estimateGain=null`), rettet dieselbe Rampe den Satz:
 *    Slice 1 (Stille) bleibt unangetastet, Slice 2 rampt von 1.0 auf den echten
 *    Gain. Liefert Slice 2 selbst kein verwertbares Estimate (`null` = Stille/
 *    Gain≈1/kein PCM16 — nicht unterscheidbar), bleibt ehrlich der Erst-Schätzwert.
 *  - **Ehrliche Rest-Ungenauigkeit:** der Satz-zu-Satz-Ausgleich (Andis Befund)
 *    ist approximativ statt exakt (Slice-2-Stichprobe ≠ Ganz-Satz-RMS) — dafür
 *    artefaktfrei.
 *  - **Clip-Schutz:** spätere Slices können heißere Peaks haben, als die
 *    Schätz-Slice ahnen konnte — [TtsLoudnessNormalizer.applyFixedGain] senkt
 *    den Gain je Slice notfalls bis unters Peak-Ceiling (Safety, hebt nie an).
 *  - **Batch-Äquivalenz:** liefert der Delegate nur EIN WAV (der
 *    [TtsPort.synthStream]-Default bzw. Streaming-Flag OFF), ist die
 *    Schätz-Slice der ganze Satz ⇒ byte-identisch zu [synth]/`normalizeWav`
 *    (die Nachjustierung existiert nur ab einer zweiten Slice).
 *
 * **Flag-gated, default OFF:** das Einhängen passiert in `PipelineConfig` hinter
 * `HOSHI_TTS_LOUDNESS_ENABLED` (default false). Bei OFF wird der Decorator gar nicht
 * erst gebaut ⇒ byte-identisch zum heutigen Audio.
 */
class LoudnessNormalizingTtsPort(
    private val delegate: TtsPort,
    private val normalizer: TtsLoudnessNormalizer = TtsLoudnessNormalizer(),
) : TtsPort {

    override fun synth(text: String, language: Language): Mono<ByteArray> =
        normalized(delegate.synth(text, language))

    /** Voice-aware (Backlog #6): [voice] wird UNVERÄNDERT an den Delegate gereicht (Whitelist dort). */
    override fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> =
        normalized(delegate.synth(text, language, voice))

    override fun synthStream(text: String, language: Language): Flux<ByteArray> =
        normalizedStream { delegate.synthStream(text, language) }

    /** Voice-aware (Backlog #6): [voice] wird UNVERÄNDERT an den Delegate gereicht (Whitelist dort). */
    override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> =
        normalizedStream { delegate.synthStream(text, language, voice) }

    private fun normalized(source: Mono<ByteArray>): Mono<ByteArray> =
        source.map { wav ->
            // Leeres Audio (Best-Effort „kein Audio") nie anfassen — sonst nackte Normalisierung.
            if (wav.isEmpty()) wav else normalizer.normalizeWav(wav)
        }

    private fun normalizedStream(source: () -> Flux<ByteArray>): Flux<ByteArray> =
        Flux.defer {
            // Pro Subscription (= pro Satz) frischer Schätz-State. onNext-Signale sind
            // per Reactive-Streams-Spec seriell ⇒ plain vars reichen (kein Atomic).
            var sliceNo = 0 // zählt NICHT-leere Slices (leere sind nie Schätz-Basis)
            var sentenceGain: Double? = null
            source().map { wav ->
                // Leeres Audio (Best-Effort „kein Audio") nie anfassen, nie als Schätz-Slice.
                if (wav.isEmpty()) return@map wav
                sliceNo++
                if (sliceNo == 1) {
                    // Short-First-Schätz-Slice (~280ms Onset-Stichprobe).
                    sentenceGain = normalizer.estimateGain(wav)
                } else if (sliceNo == 2) {
                    // EINMALIGE Nachjustierung: Slice 2 (~600ms) ist satz-repräsentativer.
                    // null (Stille/Gain≈1/kein PCM16 — nicht unterscheidbar) ⇒ ehrlich
                    // beim Erst-Schätzwert bleiben; sonst sanfte Rampe alt→neu über die
                    // GANZE Slice (stetige Gain-Hüllkurve, kein hörbarer Sprung).
                    val refined = normalizer.estimateGain(wav)
                    if (refined != null) {
                        val previous = sentenceGain ?: 1.0 // stille 280ms-Schätz-Slice ⇒ ab hier retten
                        sentenceGain = refined
                        if (abs(refined - previous) >= REFINE_EPSILON) {
                            return@map normalizer.applyGainRamp(wav, previous, refined)
                        }
                    }
                }
                // null = no-op-Entscheid der Schätzung (Stille/Gain≈1/kein PCM16) —
                // gilt konsistent weiter (kein Slice-zu-Slice-Pumpen).
                val gain = sentenceGain ?: return@map wav
                normalizer.applyFixedGain(wav, gain)
            }
        }

    private companion object {
        /**
         * Unterhalb dieser Gain-Differenz lohnt keine Rampe (~0,1 dB — unhörbar):
         * der nachgeschätzte Gain gilt dann direkt fix (spart die Rampen-Kopie).
         */
        private const val REFINE_EPSILON = 0.01
    }
}
