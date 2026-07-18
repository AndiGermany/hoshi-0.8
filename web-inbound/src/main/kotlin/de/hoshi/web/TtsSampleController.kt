package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **TtsSampleController** — die Sofort-Hörprobe des Stimmen-Pickers (Backlog #6+#7):
 * `GET /api/v1/settings/tts/sample?voice=<name>` synthetisiert EINEN kurzen festen
 * Satz ([SAMPLE_TEXT]) über den [TtsPort] mit dem gewünschten Voice-Namen und
 * liefert das WAV (`audio/wav`, Mono vom Port) — das FE spielt es direkt ab.
 *
 * **Kosten-Ehrlichkeit:** jede Hörprobe ist EIN Cloud-Call zu OpenAI
 * (`/v1/audio/speech`, ~Cent-Bruchteil) — bewusst NUR auf Klick (▶ im Panel),
 * nie automatisch/preloadend. Der Satz ist fest (kein User-Input) ⇒ kein
 * Kosten-/Injection-Hebel über den Text.
 *
 * **Whitelist:** der `voice`-Parameter wird NICHT roh durchgereicht — der
 * [de.hoshi.adapters.tts.OpenAiTtsAdapter] prüft ihn gegen seine
 * `SUPPORTED_VOICES`-Whitelist; unbekannte Namen fallen still auf den
 * Boot-Default (coral) zurück. Der lokale Voxtral-Adapter ignoriert das Feld
 * ehrlich (dann klingt die Probe wie die lokale Boot-Stimme).
 *
 * **Text-Whitelist (Filler für die 7s-Leiter):** der optionale `text`-Parameter
 * ist KEIN freier Text, sondern ein Schlüssel in die feste [SampleText]-Enum
 * (`moment`/`augenblick`/`mmh`). Fehlend oder unbekannt ⇒ exakt der bisherige
 * [SAMPLE_TEXT] (backward-kompatibel, konservativ: kein 400 für Tippfehler).
 * Das Whitelist-Prinzip bleibt damit unangetastet — kein User-Input erreicht
 * jemals die TTS, es gibt nur die handverlesenen festen Sätze.
 *
 * **Auth:** liegt unter `/api/v1` ⇒ automatisch hinter der
 * [PerimeterWebFilter]-Wand (Token oder Loopback, sonst 401).
 *
 * **Ehrliche Fehler:** kurzer eigener Timeout ([timeoutSeconds], Default 10s —
 * enger als die 30s des Adapters), und JEDER Fehlschlag (Timeout, Port-Fehler,
 * leeres Best-Effort-Audio des Adapters) endet in einem ehrlichen 503 mit
 * Message ([SettingsError]) statt in einem leeren 200 oder einem Stacktrace.
 */
@RestController
class TtsSampleController(
    // Explizit auf den Laufzeit-Delegaten (TtsRuntimeConfig) gequalifiziert: seit der
    // Runtime-Engine-Wahl (Andi-Video-Auftrag) gibt es ZWEI TtsPort-Beans (der Boot-fixe
    // "ttsPort" + der "delegatingTtsPort"); ohne Qualifier wäre der Parameter-Name "tts"
    // gegen KEINEN der beiden Bean-Namen eindeutig (Spring würfe NoUniqueBeanDefinitionException).
    // Die Hörprobe folgt so derselben Laufzeit-Wahl wie die echte Turn-Synthese.
    @Qualifier("delegatingTtsPort") private val tts: TtsPort,
    @Value("\${hoshi.tts.sample.timeout-seconds:10}") private val timeoutSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/v1/settings/tts/sample")
    fun sample(
        @RequestParam(name = "voice", required = false) voice: String?,
        @RequestParam(name = "text", required = false) text: String? = null,
    ): Mono<ResponseEntity<Any>> =
        // Whitelist-Prinzip bleibt: `text` ist nur ein SCHLÜSSEL in die feste
        // Satz-Liste ([SampleText]), nie der Satz selbst — kein User-Input
        // erreicht die TTS. Unbekannt/fehlend ⇒ der EINE bisherige Satz.
        tts.synth(SampleText.resolve(text), Language.DE, voice)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            // Nur e.message loggen (nie Keys/Bodies) — der Fehlerfall wird unten
            // einheitlich zur ehrlichen 503 (leeres Mono ⇒ defaultIfEmpty greift).
            .doOnError { e -> log.warn("[tts-sample] Hörprobe (voice={}) fehlgeschlagen: {}", voice, e.message) }
            .onErrorResume { Mono.empty() }
            // Best-Effort-Vertrag des Adapters: Fehler ⇒ leerer ByteArray. Ein leeres
            // "WAV" wäre ein fake-200 — ehrlich als "nicht möglich" behandeln.
            .filter { it.isNotEmpty() }
            .map<ResponseEntity<Any>> { wav -> ResponseEntity.ok().contentType(WAV).body(wav) }
            .defaultIfEmpty(
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body<Any>(
                    SettingsError(
                        error = "sample-unavailable",
                        id = voice ?: "",
                        message = "Hörprobe grad nicht möglich — die Stimme ließ sich nicht synthetisieren.",
                    ),
                ),
            )

    /**
     * **SampleText** — die Whitelist der festen Hörproben-/Filler-Sätze
     * (7s-Leiter, Backlog): jeder Eintrag ist ein handverlesener Satz hinter
     * einem stabilen [key]. Es gibt bewusst KEINEN Weg, hier freien Text
     * einzuschleusen — die Enum IST die Whitelist (kein Kosten-/Injection-Hebel).
     */
    enum class SampleText(val key: String, val text: String) {
        /** Kürzester Filler — das gesprochene Gedankenstrich-„Moment". */
        MOMENT("moment", "Moment —"),

        /** Mittlerer Filler — kündigt ehrlich kurzes Nachdenken an. */
        AUGENBLICK("augenblick", "Augenblick, ich denk kurz nach."),

        /** Warmer Mini-Filler — das menschliche „Mmh". */
        MMH("mmh", "Mmh, Moment."),
        ;

        companion object {
            /**
             * Schlüssel ⇒ fester Satz. Fehlend/unbekannt ⇒ exakt [SAMPLE_TEXT]
             * (backward-kompatibel; konservativ KEIN 400 — ein Tippfehler im FE
             * soll die Hörprobe nicht brechen, nur den Default liefern).
             */
            fun resolve(key: String?): String = entries.firstOrNull { it.key == key }?.text ?: SAMPLE_TEXT
        }
    }

    companion object {
        /** Der EINE feste Default-Hörproben-Satz — kurz, warm, deutsch. Fest ⇒ kalkulierbare Kosten. */
        const val SAMPLE_TEXT = "Hallo, ich bin Hoshi — so klinge ich."

        /** Content-Type der Probe: das WAV, wie es der Port liefert (16-bit mono PCM). */
        private val WAV: MediaType = MediaType.parseMediaType("audio/wav")
    }
}
