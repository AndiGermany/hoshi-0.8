package de.hoshi.adapters.tts

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.VerbalizerPort
import de.hoshi.core.port.TtsPort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * **Verbalize-Huelle fuer eine TTS-Engine** — wendet [verbalizer] auf den Text an,
 * BEVOR er an [delegate] (die eigentliche Synthese-Engine) geht. Macht Ziffern/
 * Uhrzeiten/Dezimalzahlen sprechbar, weil piper/`say`/OpenAI-TTS Zahlen
 * unterschiedlich normalisieren (s. [VerbalizerPort]-KDoc).
 *
 * **Stil/Idiom 1:1 uebernommen von der Sanitize-Huelle** (`SanitizingTtsPort`,
 * `web-inbound`): ein winziger Decorator, der den Text-Transform NUR anwendet und
 * sonst komplett durchreicht — kein eigener State, keine Fehlerbehandlung noetig
 * (der [VerbalizerPort] wirft laut seinem Vertrag NIE).
 *
 * ---
 * ### SICHERHEITSKRITISCHE REIHENFOLGE: Sanitizer AUSSEN, Verbalizer INNEN
 *
 * Die Verdrahtungs-Reihenfolge ist NICHT verhandelbar: **erst sanitizen, DANN
 * verbalisieren** — nicht umgekehrt. Konkret heisst das beim Bauen der Kette:
 * ```
 * // RICHTIG — SanitizingTtsPort aussen, VerbalizingTtsPort innen:
 * val port = SanitizingTtsPort(VerbalizingTtsPort(engine, IcuVerbalizer()), NeverSpeakTtsSanitizer())
 * // Aufruf-Reihenfolge, wenn port.synth(text, lang) laeuft:
 * //   1. NeverSpeakTtsSanitizer.sanitizeForSpeech(text)   -- maskiert Geheimnisse ZUERST
 * //   2. IcuVerbalizer.verbalize(sanitizedText, lang)     -- verbalisiert DANACH den bereits maskierten Text
 * //   3. engine.synth(verbalizedText, lang)
 * ```
 * **BEGRUENDUNG:** wuerde ZUERST verbalisiert (Verbalizer aussen, Sanitizer innen),
 * saehe der Sanitizer nicht mehr die LAN-IP `192.168.178.106`, sondern deren bereits
 * ausgeschriebene Wort-Form (z.B. "eins neun zwei Punkt eins sechs acht Punkt …").
 * Die Masken-Regex des Sanitizers (`LAN_IP_PATTERN`, erwartet Ziffern-Punkt-Ziffern)
 * trifft auf diese Wort-Form NICHT mehr — das Geheimnis wuerde ungemaskiert
 * gesprochen. Die „sprich niemals ein Geheimnis"-Regel gilt nur, wenn der
 * Sanitizer die Ziffern-Form noch SIEHT, bevor sie in Worte zerlegt wird.
 *
 * Ein Test, der GENAU dieses Sicherheits-Argument beweist (korrekte Kette maskiert
 * eine LAN-IP zuverlaessig; die vertauschte Kette laesst sie als gesprochene Worte
 * durch), liegt in `VerbalizingTtsPortOrderingTest`.
 *
 * ---
 * ### Offene Verdrahtungs-Naht (ausserhalb dieser Scheibe)
 *
 * Das tatsaechliche Einhaengen dieser Huelle (analog `TtsEngineFactory.wrapSanitizing`,
 * flag-gated hinter `HOSHI_TTS_VERBALIZE_ENABLED`, Default OFF, byte-neutral wenn
 * OFF — Muster [de.hoshi.adapters.tts.LoudnessNormalizingTtsPort]/`SanitizingTtsPort`)
 * passiert in `web-inbound` (`TtsEngineFactory`/`TtsRuntimeConfig`/`PipelineConfig`),
 * ausserhalb des `adapters-tts`-Moduls, in dem dieser Decorator lebt. Diese Klasse
 * liefert das fertige, getestete Bauteil; das Verdrahten selbst ist eine offene
 * Naht fuer den Orchestrator/naechsten Pod.
 */
class VerbalizingTtsPort(
    private val delegate: TtsPort,
    private val verbalizer: VerbalizerPort,
) : TtsPort {

    override fun synth(text: String, language: Language): Mono<ByteArray> =
        delegate.synth(verbalizer.verbalize(text, language), language)

    override fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> =
        delegate.synth(verbalizer.verbalize(text, language), language, voice)

    override fun synthStream(text: String, language: Language): Flux<ByteArray> =
        delegate.synthStream(verbalizer.verbalize(text, language), language)

    override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> =
        delegate.synthStream(verbalizer.verbalize(text, language), language, voice)
}
