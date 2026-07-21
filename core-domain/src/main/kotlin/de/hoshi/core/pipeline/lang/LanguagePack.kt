package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language

/**
 * **Sprachpaket-Kern** (Andi-Auftrag 2026-07-20: „Hoshi versteht/denkt/spricht
 * wählbar in DE/EN/ES/FR/IT — Konversations-Schicht; Smart-Home-Reflexe bleiben
 * bewusst DE"). EIN [LanguagePack] pro Sprache bündelt GENAU die Konversations-
 * Phrasen-Pools, die heute schon per-Turn `language`-bewusst sind (die
 * Cloud-Consent-/Abstain-Kette aus [de.hoshi.core.pipeline.ResponseFormatter]) +
 * die Intent-Muster-Notizen + die Prompt-Sprachanweisung + den Smart-Home-Hinweis
 * + den TTS-`say`-Stimm-Hinweis.
 *
 * **Byte-neutral für DE:** [de.hoshi.core.pipeline.lang.LangDe] trägt EXAKT die
 * bisherigen ResponseFormatter-Pool-Inhalte, nur hierher VERSCHOBEN (kein Zeichen
 * geändert) — die bestehenden Formatter-/Orchestrator-Tests sind der Beweis.
 *
 * **Ein-Datei-Regel für Übersetzer-Pods:** jede Sprache lebt in GENAU einer Datei
 * ([LangDe]/[LangEn]/[LangEs]/[LangFr]/[LangIt]) — ein Folge-Pod, der z.B. echtes
 * Spanisch nachliefert, fasst NUR `LangEs.kt` an.
 *
 * **Smart-Home-/Timer-Reflexe bleiben AUSSERHALB dieses Packs** (s.
 * [SmartHomeAckPack]): sie werden NICHT übersetzt (Andi-Vorgabe) — jede
 * Konversationssprache außer Deutsch bekommt stattdessen [smartHomeNotice] als
 * ehrlichen Hinweistext für die Sprach-Sektion des Frontends.
 */
data class LanguagePack(
    /** Die Sprache, die dieses Pack bedient — Single Source of Truth, kein zweites Tag. */
    val language: Language,

    // ── Cloud-Consent (Human-in-the-loop), s. ResponseFormatter ───────────────
    val cloudConsentAsk: List<String>,
    val cloudConsentAskExplicit: List<String>,
    val cloudConsentAccept: List<String>,
    val cloudConsentDecline: List<String>,

    /** Naht D (Hörbarkeit): das Angebot NACH einem ehrlichen Brain-Abstain. */
    val abstainLookupOffer: List<String>,

    /** Dokumentarische Notizen der Lookup-/Consent-/Research-Muster (s. KDoc dort). */
    val intentPatterns: IntentPatternNotes,

    /** Die harte Sprachinstruktion für den System-Prompt (z.B. „Antworte IMMER auf Deutsch."). */
    val promptLanguageInstruction: String,

    /**
     * Ehrlicher Hinweistext für die Sprach-Sektion des Frontends, WENN diese
     * Sprache aktiv ist UND es nicht Deutsch ist — Smart-Home-/Timer-Reflexe
     * sprechen (noch) nur Deutsch. `null` für [LangDe] (kein Hinweis nötig).
     */
    val smartHomeNotice: String?,

    /**
     * Default-Systemstimme für die `say`-Engine (macOS-Bordmittel), NUR als
     * Voice-HINWEIS (Andi-Vorgabe) — reine Daten hier, noch NICHT live in
     * [de.hoshi.web] verdrahtet (die TTS-Settings-Dateien waren beim Bau dieses
     * Packs parallel von einem anderen Pod in Arbeit, s. Report). `null` = keine
     * Empfehlung (z.B. DE: die Boot-Default-Stimme ist ohnehin schon deutsch).
     */
    val sayVoiceHint: String?,

    /**
     * Piper-Stimmen-ID-HINWEIS (Andi-Auftrag 21.07 Nachtrag „TTS soll auf
     * Englisch umschwenken", Build-Week-Video): GENUTZT von
     * [de.hoshi.web.TtsVoiceResolver] genau wie [sayVoiceHint], NUR ehrlich
     * begrenzt auf Sprachen, für die tatsächlich ein handverifiziertes,
     * lizenzgeprüftes Piper-Modell existiert (s.
     * `sidecars/piper/artifacts.lock.json`) — aktuell NUR Englisch
     * (`en_US-kristin-medium`). `null` für DE (die Boot-Default-Stimme
     * `de_DE-thorsten-medium` ist ohnehin schon deutsch) UND für ES/FR/IT
     * (es gibt dafür schlicht kein Piper-Modell — ein geratener Hint wäre
     * unehrlich, s. [de.hoshi.web.TtsVoiceResolver]-KDoc: NIE Spanisch/
     * Französisch/Italienisch mit einer erfundenen Stimmen-ID vortäuschen).
     */
    val piperVoiceHint: String?,
)

/**
 * Dokumentarische Notizen der deterministischen Lookup-/Consent-/Research-Muster
 * (Recognizer: [de.hoshi.core.pipeline.LookupIntentRecognizer],
 * [de.hoshi.core.pipeline.ConsentRecognizer], [de.hoshi.core.pipeline.ResearchIntentRecognizer]).
 *
 * **Bewusst NUR dokumentarisch, nicht die Laufzeit-Quelle:** die drei Recognizer
 * bleiben aus gutem Grund EIN geteiltes DE+EN-Regelwerk (false-positive-avers,
 * jede Nuance einzeln Negativ-getestet) — ein Recognizer, der pro aktiver Sprache
 * SEINE Muster aus hier neu zusammenbaut, würde dieses fein kalibrierte Regelwerk
 * ohne Not verdoppeln/riskieren. Dieses Feld hält fest, WAS je Sprache abgedeckt
 * ist (für FE-Hinweise/Reports); ES/FR/IT sind seit Commit 17363ef mit echten
 * dokumentarischen Signalwörtern gefüllt (kein TODO mehr, s. `status`) — der Ausbau
 * zu eigenen, negativ-getesteten Recognizer-MUSTERN bleibt bewusst Post-Build-Week
 * (s. PREP-multilingual.md „Reflex-Qualität steht und fällt mit den Mustern —
 * halbgar schadet").
 */
data class IntentPatternNotes(
    val lookupVerbs: List<String> = emptyList(),
    val lookupScope: List<String> = emptyList(),
    val consentWords: List<String> = emptyList(),
    val researchMarkers: List<String> = emptyList(),
    /** z.B. "aktiv" (DE/EN, im Recognizer scharf) oder "TODO – Folge-Pod". */
    val status: String = "",
)

/**
 * Die Smart-Home-/Timer-Reflex-Ack-Pools — verschoben (nicht verändert) aus
 * [de.hoshi.core.pipeline.ResponseFormatter]. Lebt bewusst AUSSERHALB von
 * [LanguagePack]: diese Pools sprechen IMMER Deutsch, unabhängig von der aktiven
 * Konversationssprache (Andi-Vorgabe „Smart-Home-Reflexe NICHT anfassen") — nur
 * [LangDe] instanziiert sie, [ResponseFormatter] referenziert sie direkt.
 */
data class SmartHomeAckPack(
    val lightOnRoom: List<String>,
    val lightOffRoom: List<String>,
    val lightDimRoom: List<String>,
    val lightDimNoRoom: List<String>,
    val scene: List<String>,
    val coverOpen: List<String>,
    val coverClose: List<String>,
    val climateRoom: List<String>,
    val unknown: List<String>,
    val lightOffNoEffectRoom: List<String>,
    val lightOffNoEffectNoRoom: List<String>,
    val lightOnNoEffectRoom: List<String>,
    val lightOnNoEffectNoRoom: List<String>,
    val lightDimNoEffectRoom: List<String>,
    val lightDimNoEffectNoRoom: List<String>,
    val coverOpenNoEffect: List<String>,
    val coverCloseNoEffect: List<String>,
    val climateNoEffectRoom: List<String>,
    val climateNoEffectNoRoom: List<String>,
    val genericNoEffect: List<String>,
    val lightOnPartialOfflineOne: List<String>,
    val lightOnPartialOfflineMany: List<String>,
    val lightOffPartialOfflineOne: List<String>,
    val lightOffPartialOfflineMany: List<String>,
    val partialOfflineNoRoom: List<String>,
    val unsupportedCover: List<String>,
    val unsupportedClimate: List<String>,
    val unsupportedScene: List<String>,
    val unsupportedGeneric: List<String>,
)

/**
 * **Die EINE Fallback-Regel** für Deterministik-Bausteine, die (noch) nur eigene
 * DE+EN-Inhalte haben (TurnOrchestrator-Fallbacks, FactCoverageGate-Deflect,
 * AmbientWarmth, OpenAiEscalationAdapter-System-Prompt): ES/FR/IT fallen für GENAU
 * diese vier Bausteine auf [en] zurück, bis ein Folge-Pod eigene Strings liefert —
 * unabhängig davon, dass [LangEs]/[LangFr]/[LangIt] selbst längst echte, übersetzte
 * LanguagePack-Pools tragen (Commit 17363ef, keine TODO-Marker mehr dort). NUR
 * [Language.DE] bekommt [de] — jede andere Sprache (EN eingeschlossen) bekommt
 * [en]. Für DE/EN byte-identisch zum vorherigen `when(language){DE->..;EN->..}`;
 * für ES/FR/IT NEU (vorher gar nicht kompilierbar).
 */
fun <T> Language.deOr(de: T, en: T): T = if (this == Language.DE) de else en

/**
 * Registry: EIN Ort, an dem jede [Language] auf ihr [LanguagePack] zeigt —
 * `forLanguage` ist TOTAL (jede Enum-Konstante hat einen Eintrag, s.
 * [LanguagePackRegistryTest]).
 */
object LanguagePackRegistry {

    private val ALL: Map<Language, LanguagePack> = mapOf(
        Language.DE to LangDe.PACK,
        Language.EN to LangEn.PACK,
        Language.ES to LangEs.PACK,
        Language.FR to LangFr.PACK,
        Language.IT to LangIt.PACK,
    )

    /** Das Pack der aktiven Sprache — Fallback [LangDe.PACK], falls je eine Sprache ohne Pack existiert. */
    fun forLanguage(language: Language): LanguagePack = ALL[language] ?: LangDe.PACK
}
