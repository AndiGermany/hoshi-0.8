package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TtsSanitizePort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * **Sanitize-Hülle für JEDE TTS-Engine.**
 *
 * Andi-Befund 21.07. abends: eine Recherche-Antwort endete auf
 * `([rockstargames.com](https://…?utm_source=openai))` — und Hoshi las die komplette
 * Quelle samt URL vor. Ursache war nicht die Regel, sondern ihre VERDRAHTUNG: der
 * [NeverSpeakTtsSanitizer] hing ausschließlich im [de.hoshi.adapters.tts.OpenAiTtsAdapter].
 * `say` und `piper` bekamen ihn nie und sprachen den Rohtext.
 *
 * Die Tragweite geht über vorgelesene Quellen hinaus: die Regel heißt „sprich niemals ein
 * Geheimnis" und deckt Tokens, API-Keys, LAN-IPs, UUIDs und HA-Entity-Ids ab. Sie galt
 * damit ausgerechnet für die CLOUD-Engine und NICHT für die beiden LOKALEN — also genau
 * andersherum, als man es bei einem lokal-first Assistenten erwartet, und genau
 * andersherum, als die Doku es nahelegte.
 *
 * Konsequenz: die Hülle sitzt jetzt in der [TtsEngineFactory] um JEDE gebaute Engine,
 * statt in einem einzelnen Adapter. Neue Engines sind damit automatisch geschützt — man
 * kann nicht mehr vergessen, sie anzuschließen. Der OpenAI-Adapter behält seinen eigenen
 * Sanitizer; doppeltes Anwenden ist wirkungslos (die Masken enthalten selbst nichts, was
 * die Regeln erneut treffen), und ich fasse einen funktionierenden Pfad am Drehtag nicht
 * ohne Not an.
 *
 * Berührt AUSSCHLIESSLICH gesprochenen Text. Der angezeigte Text im Chat behält seine
 * Quellenangabe und das Info-Icon.
 */
class SanitizingTtsPort(
    private val delegate: TtsPort,
    private val sanitizer: TtsSanitizePort,
) : TtsPort {

    override fun synth(text: String, language: Language): Mono<ByteArray> =
        delegate.synth(sanitizer.sanitizeForSpeech(text), language)

    override fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> =
        delegate.synth(sanitizer.sanitizeForSpeech(text), language, voice)

    override fun synthStream(text: String, language: Language): Flux<ByteArray> =
        delegate.synthStream(sanitizer.sanitizeForSpeech(text), language)

    override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> =
        delegate.synthStream(sanitizer.sanitizeForSpeech(text), language, voice)
}
