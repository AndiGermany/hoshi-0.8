package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language

/**
 * **Sprachpaket-Kern** (Andi-Auftrag 2026-07-20: „Hoshi versteht/denkt/spricht
 * wählbar in DE/EN/ES/FR/IT"; erweitert 2026-07-25: „Smart-Home-Bestätigungen ->
 * sowas soll natürlich auch auf englisch … es soll multilingual werden. von A-Z").
 * EIN [LanguagePack] pro Sprache bündelt ALLE fest verdrahteten, gesprochenen
 * Texte: die Konversations-Phrasen-Pools (Cloud-Consent-/Abstain-Kette aus
 * [de.hoshi.core.pipeline.ResponseFormatter]), die **Smart-Home-Bestätigungen**
 * ([SmartHomeAckPack]), die **Quittungen des realen HA-Executors**
 * ([HaExecutorPack]) und die **gesprochene Tat-Verweigerung des Trust-Kernels**
 * ([CapabilityDenyPack]) + die Intent-Muster-Notizen + die Prompt-Sprachanweisung
 * + den Smart-Home-Hinweis + den TTS-`say`-Stimm-Hinweis.
 *
 * **Byte-neutral für DE:** [de.hoshi.core.pipeline.lang.LangDe] trägt EXAKT die
 * bisherigen Bestands-Inhalte (ResponseFormatter-Pools, HaToolPort-Literale,
 * CapabilityKernel-Refusals), nur hierher VERSCHOBEN (kein Zeichen geändert) —
 * die bestehenden Formatter-/HaToolPort-/Kernel-Tests sind der Beweis.
 *
 * **Ein-Datei-Regel für Übersetzer-Pods:** jede Sprache lebt in GENAU einer Datei
 * ([LangDe]/[LangEn]/[LangEs]/[LangFr]/[LangIt]) — ein Folge-Pod, der z.B. echtes
 * Spanisch nachliefert, fasst NUR `LangEs.kt` an.
 *
 * **Nutzerdaten werden NIE übersetzt** (eiserne Projekt-Regel): Raum-/Area-Namen
 * kommen aus Home Assistant und reisen als `{room}`-Slot unverändert durch JEDEN
 * Satz — „Wohnzimmer" bleibt „Wohnzimmer", auch im englischen/spanischen Satz.
 * Deshalb sind alle raumbezogenen Sätze hier Templates mit Platzhaltern, nie
 * vorgefertigte Vollsätze mit eingebautem Raumnamen.
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

    // ── Ehrlichkeit: was Hoshi sagt, wenn er NICHT weiterweiß ─────────────────
    // (Quelle: [de.hoshi.core.pipeline.HonestyGate] — die ehrlichste und heikelste
    //  Textklasse des Produkts. Haltung, KEIN Defekt: nie „ich kann nicht", nie
    //  Engine-Sprech, immer ein Angebot/eine Rückfrage. Eine Übersetzung, die wie
    //  eine Fehlermeldung klingt, ist eine FALSCHE Übersetzung.)

    /** Explizite „schau online nach"-Bitte bei Cloud-AUS: bietet sofort das eigene Wissen an. */
    val honestyOnlineRequestRefusals: List<String>,

    /** Weak-Domain (Rezept/How-To): ehrlich „da führ ich dich in die Irre" statt geraten. */
    val honestyRecipeRefusals: List<String>,

    /** Existenz-Claim mit Zahl-Entity („gibt es einen 11-Euro-Schein?"): zweifelnd statt erfunden-bestätigend. */
    val honestyExistenceRefusals: List<String>,

    /** Unbekannter Eigenname („Wer ist Neelix?"): warm, neugierig, fragend — eine Repair-Einladung. */
    val honestyNamedEntityRefusals: List<String>,

    /**
     * Wissens-Bridge tot: NICHT „gibt's nicht", sondern ehrlich „komm grad nicht an
     * mein Wissen". Muss in JEDER Sprache auf ERREICHBARKEIT zielen (nicht auf
     * Nicht-Existenz) — sonst lügt Hoshi einen Infrastruktur-Fehler zu einer
     * Tatsachen-Behauptung um.
     */
    val honestyBridgeDownRefusals: List<String>,

    /**
     * **OFFLINE-Kennzeichnung** ([de.hoshi.core.pipeline.FactCoverageGate],
     * Andi-Auftrag 2026-07-26: „im Offline-Modus antwortet er selbst, sagt
     * aber dazu, dass es unbelegt ist"). Anders als [honestyBridgeDownRefusals]
     * & Co. lebt diese Zeile ECHT in allen fünf Sprachen hier (kein DE+EN-
     * [deOr]-Fallback) — sie geht der eigentlichen Modell-Antwort als kurze,
     * warme Vorbemerkung VORAUS (EIN [de.hoshi.core.dto.ChatEvent.TextDelta]
     * vor dem Brain-Stream), darum bewusst mit einem trennenden Doppelpunkt +
     * Leerzeichen am Ende, damit sie sich mit dem ersten Modell-Satz zu einem
     * gesprochenen Satz fügt. Einzelstring statt Pool (wie [FactCoverageGate.
     * deflection]): deterministisch, testbar, kein Anti-Repeat-Ring nötig.
     */
    val factCoverageOfflineDisclaimer: String,

    /**
     * **Lokal-gefunden-Vorspann** (Codex-Wissens-Kette P2, 2026-07-26): steht vor
     * einer Antwort, die nach einem „ja" auf das Nachschau-Angebot LOKAL gedeckt
     * werden konnte (Wiki-Treffer, FactCoverage bestanden) — das Gegenstück zum
     * Online-Vorspann [escalationAnswerFrame]. Vertrag: NIE „online"/„Netz"
     * behaupten (es war lokal), NIE „unbelegt" (es IST gedeckt), Trennzeichen +
     * Leerzeichen am Ende. Einzelstring, bewusst kein Pool — der Slot wird von
     * Codex' Einlöse-Mechanik konsumiert; Streuung wäre ein späterer, gemeinsamer
     * Schritt beider Lanes.
     */
    val localLookupFoundPrefix: String,

    /**
     * **Deflect-Phrasen-POOL** ([de.hoshi.core.pipeline.FactCoverageGate.deflection],
     * Andi-Auftrag 2026-07-26 „die Sprüche für Hoshi schaut online nach sind
     * schlecht" + Nachtrag „nicht immer die gleiche Antwort — gestreut, nicht
     * statisch"). Ehrliche „das weiß ich grad nicht" + Nachschau-Angebot, KEIN
     * hartes „ich kann nicht". Gleiche Regel wie [factCoverageOfflineDisclaimer]:
     * echt in allen fünf Sprachen, kein DE+EN-[deOr]-Fallback für ES/FR/IT mehr
     * (vorher fielen ES/FR/IT hier auf Englisch zurück).
     *
     * **Pool statt Einzelstring** (seit dem Streuungs-Nachtrag): 3–4 idiomatische
     * Varianten je Sprache, ausgewählt über [de.hoshi.core.pipeline.AntiRepeatPicker]
     * — EXAKT derselbe Mechanismus wie [de.hoshi.core.pipeline.ResponseFormatter]s
     * `cloudConsentAccept`-Pool (kein zweites Zufalls-System). **Harte Regel:**
     * JEDE Variante muss mit der Nachschau-FRAGE enden (z.B. „…soll ich kurz
     * nachschauen?") — darauf hängt die Consent-Einlösung im Folge-Turn.
     */
    val factCoverageDeflect: List<String>,

    /**
     * **Ergebnis-Vorspann-POOL** vor der VERBATIM-Cloud-Antwort
     * ([de.hoshi.core.pipeline.TurnOrchestrator.escalationAnswerFrame]) — die
     * ehrliche Attribution „das kommt von draußen" (WikiNumber-Lehre), OHNE die
     * Faktenaussage selbst anzufassen. Jede Variante endet bewusst mit einem
     * trennenden Gedankenstrich + Leerzeichen, damit sich die Cloud-Antwort
     * nahtlos anschließt. Echt in allen fünf Sprachen (vorher DE+EN-[deOr]-
     * Fallback).
     *
     * **Pool statt Einzelstring** (Streuungs-Nachtrag 2026-07-26): 3–4
     * idiomatische Varianten je Sprache, ausgewählt über
     * [de.hoshi.core.pipeline.AntiRepeatPicker] (derselbe Mechanismus wie
     * [factCoverageDeflect]). **Harte Regel:** JEDE Variante trägt das
     * Herkunfts-Label (Netz/online/Internet) — das ist Hoshis ehrliche
     * Quellen-Kennzeichnung und darf in keiner Variante fehlen.
     */
    val escalationAnswerFrame: List<String>,

    /**
     * **Quellen-Zeile** ([de.hoshi.core.pipeline.TurnOrchestrator.escalationSourceNote])
     * — ehrlich + knapp, nie weggelassen. Template mit dem Platzhalter
     * `{source}` (vom Aufrufer per `replace` gefüllt, wie [SmartHomeAckPack]s
     * `{room}`/`{value}`). DE/EN bleiben WORT-FÜR-WORT wie zuvor
     * (`"Quelle: {source}."` / `"Source: {source}."`) — echt neu sind nur
     * ES/FR/IT (vorher DE+EN-[deOr]-Fallback, ES/FR/IT sprachen also Englisch).
     */
    val escalationSourceTemplate: String,

    /**
     * **UNAVAILABLE**-Phrase ([de.hoshi.core.pipeline.TurnOrchestrator.escalationUnavailable]):
     * Nachschlagen ging gerade nicht (kein Key, Netz, Timeout). Ehrlich + warm,
     * ohne Nachtschicht-Versprechen (Mira/Risiko #3). **Zweiter Code-Pfad für
     * „ich komm an mein Wissen nicht ran"** neben [honestyBridgeDownRefusals] —
     * andere Situation (Cloud-Lookup vs. lokale Wissens-Bridge), darum bewusst
     * ein ANDERER Satz je Sprache, aber jetzt derselbe Ausbau-Stand: echt in
     * allen fünf Sprachen (vorher DE+EN-[deOr]-Fallback).
     *
     * **Bewusst KEIN Pool** (Entscheid der Hand, Streuungs-Nachtrag 2026-07-26):
     * anders als [factCoverageDeflect]/[escalationAnswerFrame] bleibt dies ein
     * Einzelstring — eine Fehler-/Ehrlichkeits-Kennzeichnung soll immer gleich
     * klingen, nicht variieren.
     */
    val escalationUnavailable: String,

    // ── EscalationModeFastpath: die vier Stufen-Quittungen ────────────────────
    // (Andi-Auftrag 2026-07-05 „Stufen auch über die Stimme setzen" + 2026-07-26
    //  „Multilingualität von A-Z"). Vorher wählte [EscalationModeFastpath.receipt]
    //  inline über [fallsBackToEnglish] zwischen DE und EN — ES/FR/IT bekamen
    //  Englisch. Jetzt EIN Feld je Stufe, echt in allen fünf Sprachen.
    //
    //  Bewusst KEIN Pool (Entscheid der Hand, Streuungs-Nachtrag 2026-07-26):
    //  eine Stufen-Quittung bestätigt eine Einstellungs-Änderung — da ist
    //  Wiedererkennbarkeit ein Feature, kein Manko. Nur die GESPRÄCHS-Sätze
    //  ([factCoverageDeflect]/[escalationAnswerFrame]) streuen.

    /** Stufen-Quittung [EscalationMode.ERST_FRAGEN] mit Stufen-Echo. */
    val escalationModeErstFragen: String,

    /** Stufen-Quittung [EscalationMode.AUS] mit Stufen-Echo. */
    val escalationModeAus: String,

    /** Stufen-Quittung [EscalationMode.AUTOMATISCH] mit Stufen-Echo. */
    val escalationModeAutomatisch: String,

    /** Stufen-Quittung [EscalationMode.OFFLINE] mit Stufen-Echo. */
    val escalationModeOffline: String,

    // ── Never-Silent-Ränder: der Turn hakt, der Rand macht dicht ──────────────

    /**
     * Die letzte warme Phrase des Never-Silent-Vertrags
     * ([de.hoshi.core.pipeline.TurnOrchestrator]): leere Eingabe / Fehler VOR jedem
     * Text. Sie fällt genau dann, wenn ohnehin schon etwas schiefging — darum ohne
     * Schuldzuweisung und mit einer Einladung, es gleich nochmal zu sagen.
     */
    val warmFallback: String,

    /** Audio-Cap-Abbruch am `/ws/audio`-Rand (zu viele Bytes am Stück). */
    val audioCapTooLong: String,

    /** Dauer-Deckel des Session-Guards am `/ws/audio`-Rand (Aufnahme ohne Ende-Signal). */
    val audioNoEndSignal: String,

    /** Über-Kapazität am Brain-Admission-Gate — kein Defekt, sondern „gleich wieder da". */
    val admissionBusy: String,

    // ── Deterministische Quittungen der brain-freien Fastpaths ────────────────

    /** Tagesnote neu — `{score}` ([SCORE_PLACEHOLDER]) wird durch die Note 1–5 ersetzt. */
    val dailyNoteRecorded: String,

    /** Tagesnote am selben Tag überschrieben (ehrliches „Aktualisiert"), s. [dailyNoteRecorded]. */
    val dailyNoteUpdated: String,

    /** Werkstatt-Notiz im Briefkasten abgelegt (statisch, kein Überschreib-Echo). */
    val workshopNoteRecorded: String,

    /** Probe-Selbsttest („Hoshi, Probe."): die Kette Ohren→Draht→Stimme steht. */
    val probeReceipt: String,

    /** Dokumentarische Notizen der Lookup-/Consent-/Research-Muster (s. KDoc dort). */
    val intentPatterns: IntentPatternNotes,

    /** Die harte Sprachinstruktion für den System-Prompt (z.B. „Antworte IMMER auf Deutsch."). */
    val promptLanguageInstruction: String,

    /**
     * Ehrlicher Hinweistext für die Sprach-Sektion des Frontends, WENN diese
     * Sprache aktiv ist UND es nicht Deutsch ist. `null` für [LangDe].
     *
     * **Inhalt seit 2026-07-25 gedreht:** die Smart-Home-BESTÄTIGUNGEN sprechen
     * jetzt die gewählte Sprache ([SmartHomeAckPack]/[HaExecutorPack]). Was noch
     * NICHT mehrsprachig ist, ist das VERSTEHEN: der
     * [de.hoshi.core.pipeline.ToolIntentClassifier] erkennt Befehle weiterhin nur
     * auf Deutsch und Englisch. Genau das — und nur das — sagt der Hinweis jetzt.
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

    // ── Smart-Home: die gesprochenen Bestätigungen (Andi 2026-07-25) ──────────
    // „Smart-Home-Bestätigungen -> sowas soll natürlich auch auf englisch […]
    //  es soll multilingual werden. von A-Z". Die frühere Ausnahme („Reflexe
    //  bleiben DE") ist damit AUFGEHOBEN — alle drei Packs folgen ab jetzt der
    //  aktiven Sprache. Nutzerdaten (HA-Raumnamen) bleiben davon unberührt:
    //  sie reisen als `{room}`-Slot unübersetzt durch den Satz.

    /** Die warmen Ack-Pools des [de.hoshi.core.pipeline.ResponseFormatter]. */
    val smartHomeAcks: SmartHomeAckPack,

    /** Die Quittungen des realen HA-Executors (`de.hoshi.adapters.ha.HaToolPort`). */
    val haExecutor: HaExecutorPack,

    /** Die gesprochene Tat-Verweigerung des Trust-Kernels (`de.hoshi.kernel.CapabilityKernel`). */
    val capabilityDeny: CapabilityDenyPack,
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
 * Die **Smart-Home-Bestätigungs-Pools** des [de.hoshi.core.pipeline.ResponseFormatter]
 * — je Sprache EINE Instanz, gezogen über [LanguagePack.smartHomeAcks].
 *
 * **Bis 2026-07-25 eine bewusste Ausnahme, jetzt aufgehoben:** diese Pools waren
 * absichtlich immer deutsch und der `language`-Parameter wurde ignoriert. Andis
 * Ansage („Smart-Home-Bestätigungen … es soll multilingual werden. von A-Z") hebt
 * die Ausnahme auf — [LangEn]/[LangEs]/[LangFr]/[LangIt] tragen jetzt eigene,
 * idiomatisch übersetzte Pools. [LangDe] bleibt WORT-FÜR-WORT wie zuvor.
 *
 * **Platzhalter** (vom Formatter gefüllt, NIE übersetzt):
 *  - `{room}` — der HA-Raumname (Nutzerdaten! „Wohnzimmer" bleibt „Wohnzimmer").
 *  - `{value}` — Prozent bzw. Grad.
 *  - `{applied}` / `{offline}` — Lampen-Zähler der PartialOffline-Quittung.
 *  - `{color}` — der erkannte Farbname.
 *
 * **Gesprochen, nicht gelesen:** kurz, warm, zustands-eindeutig. Eine Übersetzung,
 * die wie eine Statusmeldung klingt, ist eine falsche Übersetzung.
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

    // ── Die „kein Slot"-Varianten (vorher INLINE im ResponseFormatter) ────────
    // Bewusst EIN-elementige Pools: das waren feste Literale, keine Variations-
    // Pools — der Anti-Repeat-Ring gibt bei Pool-Größe 1 unverändert pool[0]
    // zurück, der DE-Pfad bleibt damit byte-identisch.

    /** Licht AN ohne Raum-Slot — DE: „Licht ist an." */
    val lightOnNoRoom: List<String>,

    /** Licht AUS ohne Raum-Slot — DE: „Licht ist aus." */
    val lightOffNoRoom: List<String>,

    /** Gedimmt ohne Wert — DE: „Ist gedimmt." */
    val lightDimNoValue: List<String>,

    /** Klima mit Wert, ohne Raum — DE: „Auf {value} Grad." */
    val climateValueNoRoom: List<String>,

    /** Klima ohne Wert — DE: „Ist eingestellt." */
    val climateNoValue: List<String>,

    /** Farbwechsel mit erkanntem Farbnamen — DE: „Farbe ist {color}." */
    val lightColorNamed: List<String>,

    /** Farbwechsel ohne Farbnamen — DE: „Farbe ist geändert." */
    val lightColorUnnamed: List<String>,
)

/**
 * Die **Quittungen des realen HA-Executors** (`de.hoshi.adapters.ha.HaToolPort`) —
 * je Sprache EINE Instanz, gezogen über [LanguagePack.haExecutor].
 *
 * Das ist die Textklasse, die im Alltag WIRKLICH aus dem Lautsprecher kommt: der
 * Readback-Pfad des agentischen HA-Adapters formt hieraus seine Sätze. Anders als
 * [SmartHomeAckPack] sind das EINZEL-Strings, keine Variations-Pools — sie sagen
 * einen konkreten, gemessenen Zustand und dürfen nicht „variieren".
 *
 * **Honesty-Charter in Textform:** kein Satz darf einen Effekt behaupten, den der
 * Readback nicht belegt hat. Wer übersetzt, übersetzt genau diese Abstufung mit
 * („geschickt" ≠ „getan" ≠ „war schon so").
 *
 * Platzhalter: `{room}` (HA-Raumname — Nutzerdaten, NIE übersetzen), `{value}`
 * (Grad), `{count}` (Anzahl nicht erreichbarer Lampen).
 */
data class HaExecutorPack(
    /** Kein HA-Token ⇒ ehrlich NICHTS getan (Schreib-Pfad). */
    val noToken: String,

    /** Kein HA-Token ⇒ ehrlich kein Temperatur-Wert (Lese-Pfad). */
    val noTokenTemperature: String,

    /** Area OHNE climate-Entity — ehrlich VOR jedem Service-Call. `{room}` = Area-Label. */
    val noThermostatInArea: String,

    /** turn_off verifiziert (an=0). `{room}` = Area-Slug. */
    val lightOffArea: String,

    /** turn_off, aber es brennt noch etwas. `{room}` = Area-Slug. */
    val lightSomeStillOn: String,

    /** Offline-Zusatz MIT Zähler — wird an [lightNothingNewOn]/[lightNoneWentOn] angehängt. */
    val offlineHintCount: String,

    /** Offline-Zusatz OHNE Zähler (vage) — dito angehängt. */
    val offlineHintVague: String,

    /** Die Area hat gar keine Lampen. `{room}` = Area-Slug. */
    val noLightsInArea: String,

    /** turn_on verifiziert (echtes Delta oder an≥1). `{room}` = Area-Slug. */
    val lightOnArea: String,

    /** Alle erreichbaren brannten schon — ehrlich „schon an" statt Fake-Erfolg. */
    val lightAlreadyOnArea: String,

    /** Es brannte schon Licht, NEU ging aber nichts an (Satzanfang, Offline-Zusatz folgt). */
    val lightNothingNewOn: String,

    /** Angekommen, aber kein Licht ging an (Satzanfang, Offline-Zusatz folgt). */
    val lightNoneWentOn: String,

    /** Soll-Wert bestätigt. `{room}` = Area-Label, `{value}` = Grad. */
    val climateSetArea: String,

    /** Soll-Wert (noch) nicht bestätigt — ehrlich statt Rateglück. */
    val climateNotYet: String,

    /** HTTP-200 ohne lesbaren Zustand, MIT Area. `{room}` = Area-Slug. */
    val sentToArea: String,

    /** HTTP-200 ohne lesbaren Zustand, ohne Area. */
    val sentToHome: String,

    /** Service-Call fehlgeschlagen (Non-2xx/Timeout/Netz) — warm statt kalt. */
    val failed: String,

    /** Read lieferte keinen (numerischen) Wert. */
    val noValue: String,

    /** Ist-Temperatur einer Area. `{room}` = Area-Label, `{value}` = Grad. */
    val temperatureInArea: String,

    /** Ist-Temperatur als Haus-Durchschnitt. `{value}` = Grad. */
    val temperatureHouseAverage: String,

    /** Temperatur-Read fehlgeschlagen — warm statt kalt. */
    val temperatureUnavailable: String,

    /**
     * Dezimal-Trennzeichen für gesprochene Grad-Werte („21,5" vs. „21.5").
     * Sprachsache, keine Formatierungs-Laune: DE/ES/FR/IT sprechen Komma,
     * EN spricht Punkt.
     */
    val decimalSeparator: String,
)

/**
 * Die **gesprochene Tat-Verweigerung des Trust-Kernels** (`de.hoshi.kernel.CapabilityKernel`)
 * — je Sprache EINE Instanz, gezogen über [LanguagePack.capabilityDeny].
 *
 * Ein Deny ist KEIN Fehler, sondern eine Haltung: Hoshi sagt Nein, ohne kalt zu
 * wirken. Wer übersetzt, übersetzt die Wärme mit — „permission denied" wäre eine
 * falsche Übersetzung, auch wenn es dasselbe bedeutet.
 */
data class CapabilityDenyPack(
    /** Warme Absage-Varianten (Anti-Monotonie per `random()` im Kernel). */
    val refusals: List<String>,

    /** Struktureller Defekt (Slash-Injection in domain/service) — kurz und sachlich. */
    val invalid: String,
)

/**
 * **Die EINE Fallback-Regel** für Deterministik-Bausteine, die (noch) nur eigene
 * DE+EN-Inhalte haben (die restlichen TurnOrchestrator-Fallbacks — leer/Fehler/
 * unklar/Cap-erschöpft/abgelehnt/Extended-Think-aus-Hinweis/Lookup-Rückfrage/
 * Wetter-Ort/agentische Refusals/Tool-Mode-Direktive/Verbatim-Replay-Rahmung —,
 * AmbientWarmth, OpenAiEscalationAdapter-System-Prompt): ES/FR/IT fallen für diese
 * Bausteine auf [en] zurück, bis ein Folge-Pod eigene Strings liefert — unabhängig
 * davon, dass [LangEs]/[LangFr]/[LangIt] selbst längst echte, übersetzte
 * LanguagePack-Pools tragen (Commit 17363ef, keine TODO-Marker mehr dort).
 *
 * **Andi-Auftrag 2026-07-26** („die Sprüche für Hoshi schaut online nach sind
 * schlecht"): [FactCoverageGate.deflection]/[TurnOrchestrator.escalationAnswerFrame]/
 * [TurnOrchestrator.escalationSourceNote]/[TurnOrchestrator.escalationUnavailable]
 * UND die vier [EscalationModeFastpath]-Stufen-Quittungen sind aus dieser Liste
 * RAUS — sie leben jetzt echt fünfsprachig im [LanguagePack] (s.
 * [LanguagePack.factCoverageDeflect] & Nachbarfelder), kein [deOr]-Fallback mehr.
 * NUR [Language.DE] bekommt [de] — jede andere Sprache (EN eingeschlossen) bekommt
 * [en]. Für DE/EN byte-identisch zum vorherigen `when(language){DE->..;EN->..}`;
 * für ES/FR/IT NEU (vorher gar nicht kompilierbar).
 */
fun <T> Language.deOr(de: T, en: T): T = if (this == Language.DE) de else en

/** Der Platzhalter für die Tages-Note 1–5 in [LanguagePack.dailyNoteRecorded]/[LanguagePack.dailyNoteUpdated]. */
const val SCORE_PLACEHOLDER = "{score}"

/**
 * **Die BOOLEAN-Form derselben EINEN Fallback-Regel** wie [deOr] — für die
 * Bausteine, die ihre zwei Textvarianten INLINE im String-Template wählen und
 * dafür bisher jeder für sich `val en = language == Language.EN` schrieben
 * (Timer/Date/List/Radio/Calc/EscalationMode/AreaClarify).
 *
 * **Warum das eine Korrektur ist, keine Kosmetik:** `language == Language.EN`
 * bedeutete für ES/FR/IT DEUTSCH, während [deOr] für dieselben Sprachen ENGLISCH
 * liefert. Ein Spanier bekam also je nach Codepfad mal Englisch (Deflect,
 * Consent, Fehler-Fallbacks), mal Deutsch (Timer-Quittung, Datum, Einkaufsliste)
 * — zwei widersprüchliche Regeln in EINEM Produkt. Es gibt jetzt genau EINE:
 * **nur [Language.DE] bekommt Deutsch, jede andere Sprache Englisch** (ein
 * Spanier versteht eher Englisch als Deutsch, und derselbe Zwischenfallback gilt
 * ohnehin schon für Deflect/Consent/Prompt).
 *
 * DE und EN bleiben dabei byte-identisch — nur ES/FR/IT wechseln von Deutsch auf
 * Englisch.
 */
val Language.fallsBackToEnglish: Boolean get() = deOr(de = false, en = true)

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
