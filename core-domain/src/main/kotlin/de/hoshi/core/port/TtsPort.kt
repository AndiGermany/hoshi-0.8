package de.hoshi.core.port

import de.hoshi.core.dto.Language
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * **TtsPort** — die Sprach-Synthese-Naht (hexagonaler Port). Macht den Turn
 * HÖRBAR: ein vollständiger Satz rein → WAV-Bytes raus.
 *
 * Bewusst winzig + testbar (ein `fun interface`): die Domäne ([de.hoshi.core.pipeline.TtsStage])
 * hängt am Interface, nicht am konkreten Voxtral-Adapter — Unit-Tests injizieren
 * einen Fake statt den Live-Sidecar (:8042) zu brauchen.
 *
 * **Best-Effort-Vertrag:** Audio ist KÜR, nicht Pflicht. Der Aufrufer
 * ([TtsStage]) hüllt jeden [synth]-Aufruf in `onErrorResume`/`switchIfEmpty`, so
 * dass ein TTS-Fehler den Text-Turn NIE killt (Never-Silent). Eine Impl darf bei
 * leerem Text oder Fehler `Mono.empty()` liefern.
 *
 * [language] fließt durch (Voxtral `lang`-Hint) — multilingual von Anfang an.
 *
 * **[voice] (Backlog #6, OPTIONALER Weg):** die per-Turn gewünschte Stimme fließt
 * über die voice-aware OVERLOADS (`synth(text, language, voice)` /
 * `synthStream(text, language, voice)`) — bewusst NICHT als Default-Parameter am
 * SAM: `synth(text, language)` bleibt die unveränderte abstrakte Methode, damit
 * ALLE bestehenden Impls (Voxtral, Fakes, `TtsPort { _, _ -> … }`-SAM-Lambdas)
 * unverändert kompilieren. Die Overload-Defaults IGNORIEREN voice ehrlich
 * (Delegation an die voice-lose Variante) — nur Adapter, die wirklich Stimmen
 * kennen (OpenAI, Whitelist), überschreiben sie. `voice = null` ⇒ exakt der
 * heutige Pfad (byte-neutral).
 */
fun interface TtsPort {
    /**
     * Synthetisiert EINEN Satz zu WAV-Bytes (16-bit mono PCM). `Mono.empty()` =
     * „kein Audio" (z.B. leerer Text) — der Stage reicht den Text trotzdem durch.
     */
    fun synth(text: String, language: Language): Mono<ByteArray>

    /**
     * **Voice-aware Overload** (Backlog #6): wie [synth], mit per-Turn-Stimm-Wunsch.
     * Default: [voice] wird ehrlich IGNORIERT (Delegation an [synth]) — bestehende
     * Impls verhalten sich byte-identisch. Nur stimm-fähige Adapter (OpenAI)
     * überschreiben; die validieren gegen ihre Whitelist (nie roh durchreichen).
     */
    fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> =
        synth(text, language)

    /**
     * **Streaming-Variante** (Latenz-Hebel „erstes Audio früher"): EIN Satz →
     * MEHRERE seq-geordnete WAV-Häppchen. **Jedes Element ist ein komplett
     * eigenständig dekodierbares WAV** (RIFF-Header + PCM-Slice) — exakt der
     * [de.hoshi.core.pipeline.TtsStage]/FE-Chunk-Vertrag (`decodeAudioData`
     * back-to-back), nur feiner granuliert.
     *
     * **Byte-neutraler Default:** delegiert an [synth] und emittiert dessen EIN
     * Batch-WAV als einziges Element — jede bestehende Impl (Voxtral, Fakes,
     * SAM-Lambdas) verhält sich damit unverändert. Nur Adapter, die wirklich
     * streamen können (OpenAI `response_format=pcm`, chunked transfer),
     * überschreiben diese Methode. Best-Effort-Vertrag wie [synth]: Fehler ⇒
     * leerer/beendeter Flux, NIE ein Crash des Text-Turns.
     */
    fun synthStream(text: String, language: Language): Flux<ByteArray> =
        synth(text, language).flux()

    /**
     * **Voice-aware Overload** (Backlog #6): wie [synthStream], mit per-Turn-Stimm-
     * Wunsch. Default: [voice] wird ehrlich IGNORIERT (Delegation an
     * [synthStream]) — Streaming-Verhalten und Batch-Fallback der Impl bleiben
     * byte-identisch. Nur stimm-fähige Adapter (OpenAI) überschreiben.
     */
    fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> =
        synthStream(text, language)
}
