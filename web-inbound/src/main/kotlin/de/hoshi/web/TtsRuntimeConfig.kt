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
 *  - [ttsEngineFactory]: baut einen benannten Engine-Adapter frisch — dieselben
 *    Konstruktions-Parameter (Property-Namen/Defaults) wie
 *    [PipelineConfig.ttsPort], damit ein Runtime-Switch GENAU dieselbe
 *    Konfiguration (Base-URLs, Stimmen, Sanitize/Stream-Flags) nutzt wie der
 *    Boot-Adapter.
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
 * Bean-Output (inkl. Loudness-Wrap, falls `HOSHI_TTS_LOUDNESS_ENABLED=true`) —
 * kein zweiter, abweichend konstruierter Adapter ersetzt ihn heimlich. Hat Andi
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
        @Value("\${HOSHI_TTS_SANITIZE_ENABLED:false}") sanitizeEnabled: Boolean,
        @Value("\${HOSHI_TTS_STREAM_ENABLED:false}") ttsStreamEnabled: Boolean,
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
            // gewinnt (Store-/Sprach-Wahrheit), ohne Loudness-Wrap (s. TtsEngineFactory-KDoc).
            DelegatingTtsPort(initialEngineId = effectiveId, initial = ttsEngineFactory.build(effectiveId, resolvedVoice))
        }
    }

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "tts-engine.json")
}
