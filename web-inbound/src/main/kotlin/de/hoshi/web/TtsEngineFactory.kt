package de.hoshi.web

import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.adapters.tts.PiperTtsAdapter
import de.hoshi.adapters.tts.SayTtsAdapter
import de.hoshi.adapters.tts.VoxtralTtsAdapter
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TtsSanitizePort

/**
 * **TtsEngineIds** — die EINE kanonische Namens-Wahrheit der vier TTS-Engines
 * (`"openai"`/`"say"`/`"piper"`/`"voxtral"`). Sowohl der Settings-Rand
 * ([TtsSettingsController], [DelegatingTtsPort]) als auch [PipelineConfig]
 * ([PipelineConfig.ttsEngineName], byte-identisch verhaltend) leiten sich hieraus
 * ab — eine Wahrheit, kein zweites, driftendes String-Set.
 */
object TtsEngineIds {
    const val OPENAI = "openai"
    const val SAY = "say"
    const val PIPER = "piper"
    const val VOXTRAL = "voxtral"

    /** Alle bekannten Engines, in der Anzeige-Reihenfolge der Settings-UI. */
    val ALL: List<String> = listOf(OPENAI, SAY, PIPER, VOXTRAL)

    /**
     * Kanonische Id aus dem rohen `HOSHI_TTS`-Wert — DECKUNGSGLEICH mit der
     * `when`-Verzweigung in [PipelineConfig.ttsPort]: case-insensitiv
     * `openai`/`say`/`piper`, jeder andere (auch leere) Wert fällt auf `voxtral`
     * zurück (unveränderte Default-Semantik, s. dortiges KDoc).
     */
    fun canonicalOf(rawTtsImpl: String): String = when {
        rawTtsImpl.equals(OPENAI, ignoreCase = true) -> OPENAI
        rawTtsImpl.equals(SAY, ignoreCase = true) -> SAY
        rawTtsImpl.equals(PIPER, ignoreCase = true) -> PIPER
        else -> VOXTRAL
    }

    /**
     * **Die EINE Laufzeit-Auflösung der aktiven TTS-Engine** — GENUTZT von
     * [SidecarHealthService.currentVoice] UND [PrivacyController.buildSummary],
     * damit beide Ränder IMMER denselben Wert melden (kein zweiter, driftender
     * Ableitungs-Pfad). Der GEWÄHLTE Laufzeit-Wunsch aus [JsonFileTtsEngineStore]
     * gewinnt (dieselbe Wahrheit wie die Settings-Sektion, b4844d0); NUR ohne
     * einen Runtime-Switch (Store `null`/leer/unbekannte Id) fällt es auf den
     * Boot-Default (`HOSHI_TTS` via [canonicalOf]) zurück.
     */
    fun effectiveEngineId(ttsEngineStore: JsonFileTtsEngineStore?, rawTtsImpl: String): String =
        ttsEngineStore?.engineId()?.takeIf { it in ALL } ?: canonicalOf(rawTtsImpl)
}

/**
 * **TtsVoiceResolver** — die EINE sprachbewusste Stimm-Auflösung (Andi-Auftrag
 * 21.07: „…dann soll das TTS auch auf englisch umschwänken" — eine deutsche
 * `say`-Stimme liest englischen Text grauenhaft). GENUTZT sowohl von
 * [LanguageSettingsController] (Sprachwechsel-PUT schaltet die Stimme der
 * AKTIVEN Engine live um) als auch von [TtsSettingsController] (Engine-PUT ohne
 * eigenen `voice`-Wunsch + das `aktiveStimme`-Feld im GET) — EINE Wahrheit,
 * kein zweiter, driftender Auflösungs-Pfad (Muster [TtsEngineIds.effectiveEngineId]).
 *
 * **Auflösungs-Reihenfolge** für ([engineId], [language]):
 *  1. eine explizite, per Settings-PUT gemerkte Wahl GENAU für dieses Paar
 *     ([JsonFileTtsEngineStore.voiceFor] mit Sprache) — gewinnt IMMER, auch
 *     gegen einen späteren Sprachwechsel hin und zurück (jede Sprache behält
 *     ihre EIGENE gemerkte Wahl, s. Store-KDoc).
 *  2. sonst — NUR für die `say`-Engine — der dokumentarische
 *     [de.hoshi.core.pipeline.lang.LanguagePack.sayVoiceHint] der aktiven
 *     Sprache (z.B. EN → „Samantha", ES → „Mónica"; DE trägt `null`, s. dort).
 *  3. sonst `null` ⇒ der Aufrufer fällt auf den bisherigen Default zurück
 *     (Boot-Property der Factory / bereits gemerkte Engine-Stimme).
 *
 * **piper bewusst OHNE automatischen Hint** (Live-Befund beim Bau dieser Naht):
 * `piper` liefert über `/voices` zwar Locale-Tags (s. [TtsVoiceCatalog] Punkt c,
 * Sortierung), aber KEINE statisch verlässliche Stimmen-ID über alle Installationen
 * hinweg (welches Modell installiert ist, entscheidet Andis Sidecar-Setup, nicht
 * dieser Code) — anders als `say`s benannte macOS-Systemstimmen. Ein geratener
 * Piper-Modellname wäre unehrlich. Punkt 1 (explizite Wahl) deckt piper trotzdem
 * ab, sobald Andi eine Sprach-passende Piper-Stimme selbst über die Settings-UI
 * wählt — nur der AUTOMATISCHE Fallback (Punkt 2) bleibt `say`-exklusiv.
 *
 * **openai/voxtral bewusst außen vor:** openai ist multilingual (EINE Stimme
 * liest jede Sprache verständlich, Andi-Vorgabe „keine Stimm-Umschaltung nötig")
 * und voxtral bietet ohnehin (noch) keine Stimmwahl ([TtsVoiceCatalog]) — für
 * beide bleibt Punkt 2 immer `null`, Punkt 3 (bisheriger Default) greift.
 */
object TtsVoiceResolver {
    fun resolveVoice(engineId: String, language: Language, store: JsonFileTtsEngineStore?): String? =
        store?.voiceFor(engineId, language) ?: sayHintFor(engineId, language)

    private fun sayHintFor(engineId: String, language: Language): String? =
        if (engineId == TtsEngineIds.SAY) LanguagePackRegistry.forLanguage(language).sayVoiceHint else null
}

/**
 * **TtsEngineFactory** — baut EINEN benannten TTS-Adapter frisch (Andi-Notiz:
 * „die vier Adapter werden lazy/leichtgewichtig konstruiert — WebClient-
 * Konstruktion ist billig"). Reine Konstruktions-Naht, KEINE Adapter-Logik: die
 * vier Adapter-Klassen selbst bleiben unangetastet (VERBOTEN laut Auftrag).
 *
 * Die Konstruktor-Parameter spiegeln 1:1 die `@Value`-Parameter von
 * [PipelineConfig.ttsPort] (gleiche Property-Namen/Defaults an der Aufrufstelle)
 * — EIN Ort, an dem „wie baut man Engine X" steht, egal ob beim Boot
 * ([PipelineConfig.ttsPort] bleibt für die Bestandstests unangetastet und baut
 * weiterhin selbst) oder bei einem Runtime-Switch ([TtsSettingsController]).
 *
 * **Bewusst OHNE Loudness-Wrap:** [de.hoshi.adapters.tts.LoudnessNormalizingTtsPort]
 * bleibt eine Boot-Entscheidung des `ttsPort`-Beans (Andi-Hörprobe-Gate) — ein
 * Runtime-Switch hier liefert den nackten Engine-Adapter. Der BOOT-Zustand (inkl.
 * Loudness, falls aktiv) bleibt unverändert, solange niemand die Engine wechselt
 * (s. [TtsRuntimeConfig.delegatingTtsPort]-KDoc).
 *
 * **Stimm-Wunsch je Engine** (Andi-Live-Befund: „die Stimme-Sektion muss der
 * aktiven Engine folgen"): [build] nimmt optional eine konkrete [voice] entgegen
 * — leer/blank/`null` ⇒ EXAKT der bisherige Boot-Default (byte-neutral, das
 * überladene Ein-Parameter-[build] bleibt für Bestandsaufrufer unverändert).
 * Gesetzt ⇒ überschreibt NUR die eine Engine, die gerade gebaut wird; die
 * anderen drei Engines behalten ihre eigenen (Boot- oder gemerkten) Stimmen.
 */
class TtsEngineFactory(
    private val voxtralBaseUrl: String,
    private val voxtralVoice: String,
    private val openaiModel: String,
    private val openaiVoice: String,
    private val sayBaseUrl: String,
    private val sayVoice: String,
    private val sayRate: Int,
    private val piperBaseUrl: String,
    private val piperVoice: String,
    private val sanitizeEnabled: Boolean,
    private val ttsStreamEnabled: Boolean,
) {
    /** Baut den Adapter für [engineId] mit dem BOOT-Default (kein Stimm-Wunsch) — unverändertes Bestandsverhalten. */
    fun build(engineId: String): TtsPort = build(engineId, voice = null)

    /**
     * Baut den Adapter für [engineId] (eine der [TtsEngineIds]-Konstanten).
     * Unbekannt ⇒ Voxtral (Default-Naht). [voice] überschreibt — falls
     * nicht-leer — die konfigurierte Boot-Stimme NUR dieser einen Engine.
     */
    fun build(engineId: String, voice: String?): TtsPort {
        val wish = voice?.trim()?.takeIf { it.isNotBlank() }
        return when (engineId) {
            TtsEngineIds.OPENAI -> OpenAiTtsAdapter(
                apiKey = System.getenv("OPENAI_API_KEY"),
                model = openaiModel,
                voice = wish ?: openaiVoice,
                sanitizer = if (sanitizeEnabled) NeverSpeakTtsSanitizer() else TtsSanitizePort.IDENTITY,
                streamEnabled = ttsStreamEnabled,
            )
            TtsEngineIds.SAY -> SayTtsAdapter(
                baseUrl = sayBaseUrl,
                voice = (wish ?: sayVoice).ifBlank { null },
                rate = sayRate.takeIf { it > 0 },
            )
            TtsEngineIds.PIPER -> PiperTtsAdapter(
                baseUrl = piperBaseUrl,
                voice = wish ?: piperVoice,
            )
            else -> VoxtralTtsAdapter(baseUrl = voxtralBaseUrl, voice = wish ?: voxtralVoice)
        }
    }

    /** Die konfigurierte BOOT-Stimme von [engineId] (Fallback fürs GET, wenn nie eine Stimme gemerkt wurde). */
    fun defaultVoiceFor(engineId: String): String? = when (engineId) {
        TtsEngineIds.OPENAI -> openaiVoice
        TtsEngineIds.SAY -> sayVoice.ifBlank { null }
        TtsEngineIds.PIPER -> piperVoice
        TtsEngineIds.VOXTRAL -> voxtralVoice
        else -> null
    }
}
