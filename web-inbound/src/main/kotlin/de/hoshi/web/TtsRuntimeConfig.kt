package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **TtsRuntimeConfig** — das MINIMALE Wiring der TTS-Engine-Laufzeit-Wahl
 * (Andi-Video-Auftrag, Muster [WeatherLocationConfig]/[ExtendedThinkConfig]:
 * eine EIGENE `@Configuration` statt PipelineConfig-Anbau, damit die riesige
 * [PipelineConfig] nur die zwei kleinen, gezielten Änderungen braucht, die ein
 * Runtime-Switch wirklich erzwingt — s. dortiges KDoc bei `ttsStage`).
 *
 * Vier Beans:
 *  - [ttsEngineStore]: der [JsonFileTtsEngineStore], den [TtsSettingsController]
 *    (GET/PUT) und [delegatingTtsPort] TEILEN.
 *  - [ttsEngineFactory]: die EINZIGE Bau-Aufrufstelle — hier (und NUR hier) stehen
 *    die `HOSHI_TTS_*`-Flags und die Engine-Properties. Seit 0.8.1 ruft AUCH der
 *    Boot-Bean [PipelineConfig.ttsPort] diese Fabrik, statt seine eigene Kette zu
 *    bauen; damit KANN ein Runtime-Switch keine andere Konfiguration/Dekorator-Kette
 *    mehr bekommen als der Boot (vorher: Boot ohne Verbalize, Switch ohne Loudness —
 *    s. [TtsEngineFactory]-KDoc).
 *  - [ttsVoiceCatalog]: die Live-Stimmen-Naht je Engine ([TtsVoiceCatalog]),
 *    die [TtsSettingsController] für `stimmen`/PUT-Validierung nutzt.
 *  - [delegatingTtsPort]: der [DelegatingTtsPort], den [PipelineConfig.ttsStage]
 *    für die ECHTE Synthese nutzt (s. dessen KDoc) und
 *    [TtsSettingsController] bei einem PUT umschaltet.
 *
 * **Byte-neutral, solange niemand die Einstellung anfasst:** wurde NIE ein
 * Runtime-Engine gewählt (`ttsEngineStore.engineId() == null`) UND wurde NIE
 * eine Stimme für den Boot-Default gemerkt UND ist die aktive Sprache Deutsch
 * (Boot-Default, [TtsVoiceResolver] liefert dann für DE ohnehin `null`), ist
 * der initiale Delegat EXAKT der bereits getestete [PipelineConfig.ttsPort]-
 * Bean-Output. Seit 0.8.1 ist das ohnehin dieselbe Kette wie ein Switch: BEIDE
 * kommen aus [ttsEngineFactory] (inkl. Loudness-, Sanitize- UND Verbalize-Hülle,
 * je nach Flag) — kein zweiter, abweichend konstruierter Adapter mehr. Hat Andi
 * VOR einem Neustart über `PUT /api/v1/settings/tts` eine Stimme gemerkt (auch
 * für den Boot-Default selbst) ODER bereits eine nicht-deutsche Sprache gewählt
 * (Andi-Auftrag 21.07: „TTS soll der Sprache folgen"), überlebt/gilt das ab dem
 * ERSTEN Turn nach dem Neustart genau wie die Engine-Wahl — [TtsVoiceResolver]
 * ist dieselbe Auflösungs-Wahrheit wie beim Laufzeit-Wechsel
 * ([LanguageSettingsController]/[TtsSettingsController]).
 */
@Configuration
class TtsRuntimeConfig {

    @Bean
    fun ttsEngineStore(
        @Value("\${hoshi.tts-engine.path:\${HOSHI_TTS_ENGINE_PATH:}}") settingsPath: String,
    ): JsonFileTtsEngineStore = JsonFileTtsEngineStore(resolvePath(settingsPath))

    @Bean
    fun ttsEngineFactory(
        @Value("\${hoshi.tts.base-url:http://localhost:8042}") voxtralBaseUrl: String,
        @Value("\${hoshi.tts.voice:de_female}") voxtralVoice: String,
        @Value("\${hoshi.tts.openai.model:gpt-4o-mini-tts}") openaiModel: String,
        @Value("\${hoshi.tts.openai.voice:coral}") openaiVoice: String,
        @Value("\${hoshi.tts.say.base-url:http://127.0.0.1:8044}") sayBaseUrl: String,
        @Value("\${hoshi.tts.say.voice:}") sayVoice: String,
        @Value("\${hoshi.tts.say.rate:0}") sayRate: Int,
        @Value("\${hoshi.tts.piper.base-url:http://127.0.0.1:8045}") piperBaseUrl: String,
        @Value("\${hoshi.tts.piper.voice:de_DE-thorsten-medium}") piperVoice: String,
        // Default ON seit 0.8.1 (Sicherheits-Default) — muss mit PrivacyController identisch
        // sein, sonst zeigt die UI etwas anderes an, als die Kette tut (Riegel:
        // PipelineConfigTtsSanitizeTest.`Default-Riegel …`).
        @Value("\${HOSHI_TTS_SANITIZE_ENABLED:true}") sanitizeEnabled: Boolean,
        @Value("\${HOSHI_TTS_STREAM_ENABLED:false}") ttsStreamEnabled: Boolean,
        // ── TTS-Verbalize (ziffernfreier Sprechtext, adapters-tts) — default OFF ──
        // OFF (Default) ⇒ TtsEngineFactory.wrapVerbalizing() haengt den Decorator gar
        // nicht erst ein ⇒ byte-identische Kette zu heute. ON ⇒ jede gebaute Engine wird
        // MIT VerbalizingTtsPort/IcuVerbalizer umhuellt, INNERHALB der Sanitize-Huelle
        // (Sanitizer aussen, Verbalizer innen — s. TtsEngineFactory.build-KDoc).
        // Seit 0.8.1 wirkt das Flag AUCH beim Boot: PipelineConfig.ttsPort ruft dieselbe
        // Fabrik. Vorher war es auf einer frischen Installation ein stiller No-op.
        @Value("\${HOSHI_TTS_VERBALIZE_ENABLED:false}") verbalizeEnabled: Boolean,
        // ── TTS-Loudness-Normalisierung (0.5-Port) — flag-gated, default OFF (byte-neutral) ──
        // Fixt Andis Befund 2026-06-21 „Stimme unterschiedlich laut". OFF ⇒ exakt der nackte
        // Adapter ⇒ byte-identisches Audio. Wanderte 0.8.1 aus PipelineConfig.ttsPort hierher:
        // vorher hatte NUR der Boot-Pfad die Normalisierung, ein Engine-Switch zur Laufzeit
        // verlor sie stillschweigend (obwohl HOSHI_TTS_LOUDNESS_ENABLED=true in der Prod-Unit steht).
        @Value("\${HOSHI_TTS_LOUDNESS_ENABLED:false}") loudnessEnabled: Boolean,
        @Value("\${hoshi.tts.loudness.target-rms-db:-18.0}") loudnessTargetRmsDb: Double,
        @Value("\${hoshi.tts.loudness.peak-ceiling-db:-1.0}") loudnessPeakCeilingDb: Double,
        // ── Gain-Cap (Ravi-Messung 2026-07-03, Andi „Wetter-Antwort ungleich laut") ──
        // Live-Messung der ECHTEN coral-Stimme (streamEnabled=true, 3 Wetter-Sätze):
        // coral rendert Sätze mit 3–5 dB Roh-Pegel-Streuung (Satz-zu-Satz), und ihr
        // Roh-RMS liegt chronisch ~−28…−34 dBFS ⇒ um aufs −18-Ziel zu kommen braucht
        // JEDER Satz +9…+13 dB. Der bisherige +6-Cap SÄTTIGT damit ALLE Sätze auf
        // exakt +6 dB (Onset-Gains gemessen alle ≈+6, am Cap) ⇒ die Normalisierung
        // reicht jeden Satz identisch verschoben durch und kann die coral-Streuung
        // NICHT entfernen ⇒ Andi hört die Roh-Stufen (~4,6 dB gated). +12 dB gibt der
        // Pro-Satz-Schätzung Luft, LEISERE Sätze STÄRKER anzuheben als lautere ⇒ die
        // Satz-zu-Satz-Spanne schrumpft (gemessen 4,6 → 3,0 dB gated / 4,2 → 2,5 dB
        // full-RMS). Clip-Schutz (Peak-Guard, −1 dBFS) bleibt je Slice; die Restspanne
        // ist crest-faktor-bedingt (peakige Sätze) und bräuchte einen Limiter (Andi-Call).
        @Value("\${hoshi.tts.loudness.max-gain-db:12.0}") loudnessMaxGainDb: Double,
        @Value("\${hoshi.tts.loudness.silence-floor-db:-50.0}") loudnessSilenceFloorDb: Double,
    ): TtsEngineFactory = TtsEngineFactory(
        voxtralBaseUrl = voxtralBaseUrl,
        voxtralVoice = voxtralVoice,
        openaiModel = openaiModel,
        openaiVoice = openaiVoice,
        sayBaseUrl = sayBaseUrl,
        sayVoice = sayVoice,
        sayRate = sayRate,
        piperBaseUrl = piperBaseUrl,
        piperVoice = piperVoice,
        sanitizeEnabled = sanitizeEnabled,
        ttsStreamEnabled = ttsStreamEnabled,
        verbalizeEnabled = verbalizeEnabled,
        loudnessEnabled = loudnessEnabled,
        loudnessTargetRmsDb = loudnessTargetRmsDb,
        loudnessPeakCeilingDb = loudnessPeakCeilingDb,
        loudnessMaxGainDb = loudnessMaxGainDb,
        loudnessSilenceFloorDb = loudnessSilenceFloorDb,
    )

    @Bean
    fun ttsEngineProbe(
        @Value("\${hoshi.tts.base-url:http://localhost:8042}") voxtralBaseUrl: String,
        @Value("\${hoshi.tts.say.base-url:http://127.0.0.1:8044}") sayBaseUrl: String,
        @Value("\${hoshi.tts.piper.base-url:http://127.0.0.1:8045}") piperBaseUrl: String,
    ): TtsEngineProbe = HttpTtsEngineProbe(
        voxtralBaseUrl = voxtralBaseUrl,
        sayBaseUrl = sayBaseUrl,
        piperBaseUrl = piperBaseUrl,
    )

    @Bean
    fun ttsVoiceCatalog(
        @Value("\${hoshi.tts.say.base-url:http://127.0.0.1:8044}") sayBaseUrl: String,
        @Value("\${hoshi.tts.piper.base-url:http://127.0.0.1:8045}") piperBaseUrl: String,
    ): TtsVoiceCatalog = HttpTtsVoiceCatalog(sayBaseUrl = sayBaseUrl, piperBaseUrl = piperBaseUrl)

    @Bean
    fun delegatingTtsPort(
        ttsPort: TtsPort,
        ttsEngineStore: JsonFileTtsEngineStore,
        ttsEngineFactory: TtsEngineFactory,
        languageStore: JsonFileLanguageStore,
        @Value("\${HOSHI_TTS:}") ttsImpl: String,
    ): DelegatingTtsPort {
        val bootDefaultId = TtsEngineIds.canonicalOf(ttsImpl)
        val storedId = ttsEngineStore.engineId()?.takeIf { it in TtsEngineIds.ALL }
        val effectiveId = storedId ?: bootDefaultId
        // Sprachbewusst (Andi-Auftrag 21.07): dieselbe Auflösungs-Wahrheit wie ein
        // Laufzeit-Sprach-/Engine-Wechsel, s. TtsVoiceResolver-KDoc.
        val activeLanguage = Language.fromCodeOrNull(languageStore.languageCode()) ?: Language.DEFAULT
        val resolvedVoice = TtsVoiceResolver.resolveVoice(effectiveId, activeLanguage, ttsEngineStore)
        return if ((storedId == null || storedId == bootDefaultId) && resolvedVoice == null) {
            // Kein abweichender Laufzeit-Wunsch (weder Engine noch Stimme/Sprach-Hint
            // für den Boot-Default) ⇒ EXAKT der Boot-Adapter (inkl. Loudness-Wrap,
            // falls aktiv) — byte-identisches Verhalten wie vor dieser Naht.
            DelegatingTtsPort(initialEngineId = bootDefaultId, initial = ttsPort)
        } else {
            // Andi hat vor einem Neustart bereits eine andere Engine, eine Stimme
            // ODER eine nicht-deutsche Sprache gewählt — der resolvierte Wunsch
            // gewinnt (Store-/Sprach-Wahrheit). Seit 0.8.1 mit IDENTISCHER
            // Dekorator-Kette wie der Boot-Zweig darüber (inkl. Loudness), weil beide
            // aus derselben Fabrik kommen (s. TtsEngineFactory-KDoc).
            DelegatingTtsPort(initialEngineId = effectiveId, initial = ttsEngineFactory.build(effectiveId, resolvedVoice))
        }
    }

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "tts-engine.json")
}
