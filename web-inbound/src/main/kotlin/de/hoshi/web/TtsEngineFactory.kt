package de.hoshi.web

import de.hoshi.adapters.tts.IcuVerbalizer
import de.hoshi.adapters.tts.LoudnessNormalizingTtsPort
import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.adapters.tts.PiperTtsAdapter
import de.hoshi.adapters.tts.SayTtsAdapter
import de.hoshi.adapters.tts.TtsLoudnessNormalizer
import de.hoshi.adapters.tts.VerbalizingTtsPort
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
    /** Fresh-Clone-Default: macOS-Bordmittel, kein Key, kein Modell und kein Lizenz-Opt-in. */
    const val DEFAULT = SAY

    /** Alle bekannten Engines, in der Anzeige-Reihenfolge der Settings-UI. */
    val ALL: List<String> = listOf(OPENAI, SAY, PIPER, VOXTRAL)

    /**
     * Kanonische Id aus dem rohen `HOSHI_TTS`-Wert — DECKUNGSGLEICH mit der
     * `when`-Verzweigung in [TtsEngineFactory.buildRaw] (dieselbe Fallback-Regel,
     * damit der NAME nie eine andere Engine meldet als die, die gebaut wird):
     * case-insensitiv `openai`/`say`/`piper`/`voxtral`; leer fällt auf [DEFAULT]
     * (`say`) zurück. Ein unbekannter NICHT-leerer Wert bricht dagegen hart ab:
     * ein Tippfehler darf nie still eine andere Engine wählen.
     *
     * **First-Run-Wahrheit 0.8.1:** Ein zwischenzeitlicher P5-Fix setzte hier und
     * im systemd-Renderer `piper`. Das war zwar lokal, aber auf einem frischen Klon
     * stumm: Piper braucht Bootstrap, Modell-Download und GPL-Opt-in und wird von
     * `bin/hoshi up` nicht gestartet. [SAY] ist auf der ohnehin vorausgesetzten
     * macOS-Plattform die kleinere Wahrheit: kein Key, kein Modell, kein
     * Cloud-Egress; `up` startet genau diesen Sidecar. Der Sidecar selbst braucht
     * einmalig seinen Python-Webserver-Bootstrap und meldet dessen Fehlen laut.
     *
     * `voxtral` bleibt vollwertig anwählbar — nur eben EXPLIZIT (`HOSHI_TTS=voxtral`
     * oder per `PUT /api/v1/settings/tts`), nicht mehr als stiller Auffang-Zweig.
     */
    fun canonicalOf(rawTtsImpl: String): String {
        val normalized = rawTtsImpl.trim().lowercase()
        return when (normalized) {
            "" -> DEFAULT
            OPENAI, SAY, PIPER, VOXTRAL -> normalized
            else -> throw IllegalArgumentException(
                "Unbekannte TTS-Engine '$rawTtsImpl'. Erlaubt: ${ALL.joinToString(", ")}. " +
                    "Abbruch statt stiller Ersatz-Engine.",
            )
        }
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
 *  2. sonst der dokumentarische Sprach-Hint des [de.hoshi.core.pipeline.lang.LanguagePack]
 *     der aktiven Sprache — für `say` [de.hoshi.core.pipeline.lang.LanguagePack.sayVoiceHint]
 *     (z.B. EN → „Samantha", ES → „Mónica"), für `piper`
 *     [de.hoshi.core.pipeline.lang.LanguagePack.piperVoiceHint] (aktuell NUR
 *     EN → „en_US-kristin-medium" — handverifiziert + lizenzgeprüft, s.
 *     `sidecars/piper/artifacts.lock.json`); DE trägt für beide `null`.
 *  3. sonst `null` ⇒ der Aufrufer fällt auf den bisherigen Default zurück
 *     (Boot-Property der Factory / bereits gemerkte Engine-Stimme).
 *
 * **piper: Hint NUR für Sprachen mit einem wirklich installierten Modell**
 * (Nachtrag 21.07 zum ursprünglichen Live-Befund unten: die Video-Stimme
 * `en_US-kristin-medium` ist jetzt handverifiziert + lizenzgeprüft gepinnt,
 * s. Lockfile — der Sidecar meldet sie über `/voices` nur, wenn ihre Dateien
 * WIRKLICH auf der Platte liegen, s. dortiges KDoc). Für ES/FR/IT existiert
 * bewusst KEIN Piper-Modell — [de.hoshi.core.pipeline.lang.LanguagePack.piperVoiceHint]
 * bleibt dort `null` (kein geratener Modellname), Punkt 3 greift: der
 * bisherige Boot-Default der Factory (typischerweise `de_DE-thorsten-medium`).
 * Das ist ein BEKANNTER, unveränderter Bestandszustand (piper liest ohne
 * expliziten Andi-Wunsch fürs Setup dann Spanisch/Französisch/Italienisch
 * MIT der deutschen Stimme vor) — kein neues Verhalten dieser Naht, aber
 * ausdrücklich NICHT durch einen erfundenen Hint kaschiert. Punkt 1 (explizite
 * Wahl) deckt auch diese drei Sprachen ab, sobald Andi selbst eine Piper-Stimme
 * über die Settings-UI wählt.
 *
 * **openai/voxtral bewusst außen vor:** openai ist multilingual (EINE Stimme
 * liest jede Sprache verständlich, Andi-Vorgabe „keine Stimm-Umschaltung nötig")
 * und voxtral bietet ohnehin (noch) keine Stimmwahl ([TtsVoiceCatalog]) — für
 * beide bleibt Punkt 2 immer `null`, Punkt 3 (bisheriger Default) greift.
 */
object TtsVoiceResolver {
    fun resolveVoice(engineId: String, language: Language, store: JsonFileTtsEngineStore?): String? =
        store?.voiceFor(engineId, language) ?: languageHintFor(engineId, language)

    /** Der dokumentarische Sprach-Hint des aktiven [LanguagePack] — `say`/`piper` je ihr eigenes Feld, sonst `null`. */
    private fun languageHintFor(engineId: String, language: Language): String? {
        val pack = LanguagePackRegistry.forLanguage(language)
        return when (engineId) {
            TtsEngineIds.SAY -> pack.sayVoiceHint
            TtsEngineIds.PIPER -> pack.piperVoiceHint
            else -> null
        }
    }
}

/**
 * **TtsEngineFactory** — die EINZIGE Bauwahrheit für einen [TtsPort]. Sie baut den
 * benannten Adapter frisch (Andi-Notiz: „die vier Adapter werden lazy/leichtgewichtig
 * konstruiert — WebClient-Konstruktion ist billig") UND hängt die komplette
 * Dekorator-Kette an. Reine Konstruktions-Naht, KEINE Adapter-Logik: die vier
 * Adapter-Klassen selbst bleiben unangetastet.
 *
 * **Warum EIN Ort (0.8.1, struktureller Fix).** Es gab ZWEI Bau-Wege — diesen und
 * `PipelineConfig.ttsPort`, das die Kette selbst zusammensetzte — und sie liefen
 * auseinander: der Boot-Weg hatte Sanitize+Loudness, aber KEIN Verbalize; dieser Weg
 * hatte Sanitize+Verbalize, aber KEIN Loudness. Weil [TtsRuntimeConfig.delegatingTtsPort]
 * bei unangetasteten Settings den BOOT-Weg nimmt, war `HOSHI_TTS_VERBALIZE_ENABLED=true`
 * auf einer frischen Installation ein stiller No-op. Derselbe Fehlertyp („zwei Wege,
 * eine Regel, nur ein Weg gepflegt") hatte davor die SICHERHEITSLÜCKE verursacht, dass
 * say/piper/voxtral im Boot-Pfad Rohtext sprachen (s. [PipelineConfigTtsSanitizeTest]).
 * Deshalb baut `PipelineConfig.ttsPort` seit 0.8.1 NICHTS mehr selbst, sondern ruft
 * diese Fabrik ([PipelineConfig.ttsPort] → [build]) — festgenagelt in
 * [TtsBuildPathSingleTruthTest].
 *
 * **Die Kette, in Aufruf-Reihenfolge des Textes/Audios:**
 * `Loudness( Sanitize( Verbalize( Engine ) ) )` — s. [build] für die Begründung
 * jeder Position.
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
    // Default `false` (statt eines Pflicht-Parameters): haelt die bestehenden
    // Testfixturen (SanitizingTtsPortTest/TtsSettingsControllerTest/
    // LanguageSettingsControllerTest, alle mit vollstaendiger Named-Arg-Liste OHNE
    // dieses Feld) unveraendert kompilierbar UND verkoerpert direkt den geforderten
    // Default OFF (Muster sanitizeEnabled/ttsStreamEnabled oben).
    private val verbalizeEnabled: Boolean = false,
    // ── Loudness (0.5-Port, war bis 0.8.1 NUR im Boot-Bean) ──────────────────
    // Wanderte hierher, damit ein Runtime-Engine-Switch die Normalisierung NICHT mehr
    // verliert (vorher: `PUT /settings/tts` ⇒ nackter Adapter ohne Loudness, obwohl
    // HOSHI_TTS_LOUDNESS_ENABLED=true in der Prod-Unit steht). Defaults = exakt die
    // `@Value`-Defaults der EINEN Aufrufstelle (TtsRuntimeConfig.ttsEngineFactory),
    // damit Testfixturen ohne diese Felder byte-neutral OFF bleiben.
    private val loudnessEnabled: Boolean = false,
    private val loudnessTargetRmsDb: Double = -18.0,
    private val loudnessPeakCeilingDb: Double = -1.0,
    private val loudnessMaxGainDb: Double = 12.0,
    private val loudnessSilenceFloorDb: Double = -50.0,
    // Sidecar-Token-Wand (opt-in, s. de.hoshi.core.security.SidecarTokenHeader-KDoc):
    // gilt NUR für say/piper (eigene Hoshi-Sidecars) — openai (Cloud, eigener API-Key)
    // und voxtral (Port 8042, kein Teil der Token-Wand) bleiben unberührt. Default ""
    // ⇒ byte-neutral.
    private val sidecarToken: String = "",
) {
    /** Baut den Adapter für [engineId] mit dem BOOT-Default (kein Stimm-Wunsch) — unverändertes Bestandsverhalten. */
    fun build(engineId: String): TtsPort = build(engineId, voice = null)

    /**
     * Baut den Adapter für [engineId] (eine der [TtsEngineIds]-Konstanten) samt
     * kompletter Dekorator-Kette. Unbekannt ⇒ harter Abbruch; der Fresh-Clone-
     * Default wird ausschließlich von [TtsEngineIds.canonicalOf] aufgelöst.
     * [voice] überschreibt — falls nicht-leer — die konfigurierte Boot-Stimme NUR
     * dieser einen Engine.
     *
     * **Reihenfolge — der Grund steht in den Tests:**
     *  1. **Sanitize wirkt auf TEXT und muss ZUERST laufen** ⇒ die Hülle sitzt AUSSEN,
     *     der Verbalizer INNEN. Beim Aufruf sieht `sanitize()` dadurch die rohe
     *     Ziffernform (z.B. eine LAN-IP), erst DANACH verbalisiert der
     *     [VerbalizingTtsPort] den bereits maskierten Text. Vertauscht man die beiden,
     *     sähe der Sanitizer nur noch die ausgeschriebene Wort-Form und die
     *     Masken-Regex träfe NICHT mehr (bewiesen in [VerbalizingWiringTest]:
     *     „Sanitizer UND Verbalizer an — die Reihenfolge stimmt").
     *  2. **Loudness wirkt auf AUDIO, ist also orthogonal** zum Text ⇒ es ist egal, ob
     *     die Normalisierung innerhalb oder außerhalb der Text-Dekoratoren hängt; sie
     *     kommt ganz nach außen, weil sie erst mit den fertigen WAV-Bytes arbeitet
     *     (und ein Text-Dekorator um sie herum die Bytes gar nicht mehr anfassen könnte).
     *
     * **openai-Sonderfall:** der [OpenAiTtsAdapter] trägt seinen Sanitizer INTERN,
     * direkt vor dem Cloud-Call (stärkste Position, bewiesen in
     * [OpenAiTtsSanitizeWiringTest]) — solange NICHTS zwischen Hülle und Adapter sitzt,
     * wäre eine zweite Maskierung wirkungslos-doppelt und wird weggelassen (dieselbe
     * bewusste Asymmetrie, die der Boot-Pfad schon hatte). Hängt aber der Verbalizer
     * dazwischen, ist die INTERNE Position zu spät (sie sähe nur die Wort-Form) — dann
     * ist die äußere Hülle die einzige, die Regel 1 durchsetzt, und wird gesetzt.
     */
    fun build(engineId: String, voice: String?): TtsPort {
        require(engineId in TtsEngineIds.ALL) {
            "Unbekannte TTS-Engine '$engineId'. Erlaubt: ${TtsEngineIds.ALL.joinToString(", ")}."
        }
        // Sanitize-Hülle um JEDE Engine (Andi-Befund 21.07.: piper/say lasen Quellen-URLs
        // vor, weil der Sanitizer NUR im OpenAI-Adapter hing — die „sprich niemals ein
        // Geheimnis"-Regel galt damit ausgerechnet nicht für die lokalen Engines).
        // Neue Engines sind dadurch automatisch geschützt; man kann es nicht vergessen.
        val raw = buildRaw(engineId, voice)
        val verbalized = wrapVerbalizing(raw)
        // `verbalized === raw` ⇒ zwischen Hülle und Adapter sitzt NICHTS. Die Identität
        // (statt eines zweiten `if (verbalizeEnabled)`) ist absichtlich: die Ausnahme kann
        // nicht von der tatsächlichen Kette abdriften, egal welche Dekoratoren hier später
        // dazukommen.
        val carriesOwnSanitizer = raw is OpenAiTtsAdapter && verbalized === raw
        return wrapLoudness(if (carriesOwnSanitizer) verbalized else wrapSanitizing(verbalized))
    }

    /** Hüllt [port], solange die Sanitize-Regel scharf ist — sonst unverändert (byte-neutral). */
    private fun wrapSanitizing(port: TtsPort): TtsPort =
        if (sanitizeEnabled) SanitizingTtsPort(port, NeverSpeakTtsSanitizer()) else port

    /**
     * Hüllt [port] mit [VerbalizingTtsPort]/[IcuVerbalizer], solange
     * `HOSHI_TTS_VERBALIZE_ENABLED` scharf ist — sonst unverändert (byte-neutral,
     * Muster [wrapSanitizing]/[wrapLoudness]).
     * MUSS innerhalb von [wrapSanitizing] aufgerufen werden (s. [build]-KDoc).
     */
    private fun wrapVerbalizing(port: TtsPort): TtsPort =
        if (verbalizeEnabled) VerbalizingTtsPort(port, IcuVerbalizer()) else port

    /**
     * Hüllt [port] mit [LoudnessNormalizingTtsPort]/[TtsLoudnessNormalizer], solange
     * `HOSHI_TTS_LOUDNESS_ENABLED` scharf ist — sonst unverändert (byte-neutral,
     * Muster [wrapSanitizing]/[wrapVerbalizing]). Wirkt auf AUDIO ⇒ ganz außen
     * (s. [build]-KDoc, Punkt 2).
     */
    private fun wrapLoudness(port: TtsPort): TtsPort =
        if (loudnessEnabled) {
            LoudnessNormalizingTtsPort(
                delegate = port,
                normalizer = TtsLoudnessNormalizer(
                    targetRmsDb = loudnessTargetRmsDb,
                    peakCeilingDb = loudnessPeakCeilingDb,
                    maxGainDb = loudnessMaxGainDb,
                    silenceFloorDb = loudnessSilenceFloorDb,
                ),
            )
        } else {
            port
        }

    /**
     * Der nackte Engine-Adapter. [build] hat [engineId] bereits gegen
     * [TtsEngineIds.ALL] validiert; deshalb gibt es hier keinen stillen
     * Fallback-Zweig.
     */
    private fun buildRaw(engineId: String, voice: String?): TtsPort {
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
                token = sidecarToken,
            )
            TtsEngineIds.PIPER -> PiperTtsAdapter(
                baseUrl = piperBaseUrl,
                voice = wish ?: piperVoice,
                token = sidecarToken,
            )
            TtsEngineIds.VOXTRAL -> VoxtralTtsAdapter(baseUrl = voxtralBaseUrl, voice = wish ?: voxtralVoice)
            else -> error("TTS-Engine wurde trotz Build-Validierung unbekannt: $engineId")
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
