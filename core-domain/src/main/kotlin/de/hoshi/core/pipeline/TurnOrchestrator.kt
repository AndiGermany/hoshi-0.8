package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.dto.TurnPrompt
import de.hoshi.core.pipeline.lang.deOr
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.CapabilityPort
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import de.hoshi.core.port.EscalationSourceRef
import de.hoshi.core.port.LookupNote
import de.hoshi.core.port.LookupNoteFenceGuard
import de.hoshi.core.port.LookupNoteNormalizer
import de.hoshi.core.port.LookupNotePort
import de.hoshi.core.port.LookupReplayPort
import de.hoshi.core.port.SttSurprisal
import de.hoshi.core.port.SttSurprisalPort
import de.hoshi.core.port.ToolPort
import de.hoshi.core.port.WorkingSessionPort
import de.hoshi.core.port.WorkingSessionSegment
import de.hoshi.core.tools.AgenticToolRegistry
import de.hoshi.core.tools.AreaClarifyIntent
import de.hoshi.core.tools.CalcIntent
import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ListIntent
import de.hoshi.core.tools.ToolAreas
import de.hoshi.core.tools.TimerIntent
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolGrammarParser
import de.hoshi.core.tools.ToolResult
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.DoubleAdder

/**
 * **TurnOrchestrator** — der dünne Keystone, der die portierten Policies + den
 * [BrainPort] zu EINEM lebenden Turn durchs Hexagon verdrahtet (M2c).
 *
 * Reiner Domänen-Kern: NUR Reactor + die Pipeline-Policies, KEIN Spring, KEIN
 * Infra-Wissen. Das Wiring (welcher Brain-Adapter, welche Stubs) lebt im
 * `:web-inbound`-`@Configuration`, NICHT hier.
 *
 * **Zwei strukturell erzwungene Invarianten:**
 *
 *  1. **Max. 1 Brain-Call pro Turn.** Der [BrainPort] ist ein PRIVATES Feld dieser
 *     Klasse — sonst hält ihn niemand. Es gibt genau EINE Aufruf-Stelle
 *     ([brainTurn] → `brain.streamChat`), und die wird ausschließlich auf dem
 *     [HonestyGate.Verdict.Pass]-Zweig erreicht. Jeder Abstentions-Zweig
 *     (Refuse/AskConsent) gibt einen Flux zurück, der den Brain NIE referenziert.
 *
 *  1b. **Tool-Pfad strukturell brain-frei.** Klassifiziert der [intent] einen
 *     eindeutigen Befehl, läuft der Turn über [toolTurn] (Gate → Executor →
 *     warme Quittung) und ruft den [brain] NIE — „max. 1 Brain-Call/Turn" bleibt
 *     trivial erfüllt (Tool-Turn = 0 Brain-Calls).
 *
 *  2. **Never-Silent.** [neverSilent] umhüllt den Brain-Stream so, dass JEDER Pfad
 *     — normaler Stream, leerer Brain-Stream, Fehler — in mindestens einer warmen
 *     [ChatEvent.TextDelta] + einem terminalen [ChatEvent.Done] endet, nie in
 *     stiller Sackgasse. (Portiert aus Hoshi 0.5 `NeverSilentStage`, inline auf
 *     den 0.8-Text-Turn reduziert — Audio/TTS kommt später.)
 *
 * Sequenz: `RoutingPolicy.resolve` → `HonestyGate.assess`
 * (Abstention → warme Direkt-Antwort, KEIN Brain) → `TurnPromptAssembler.assemble`
 * → `BrainPort.streamChat` (genau 1×) → Never-Silent → `Flux<ChatEvent>`.
 *
 * `language` aus dem [ChatRequest] fließt über den [TurnPrompt] an Persona +
 * [ResponseFormatter] (Consent-Phrasen).
 */
class TurnOrchestrator(
    private val routing: RoutingPolicy,
    private val honesty: HonestyGate,
    private val promptAssembler: TurnPromptAssembler,
    private val persona: PersonaService,
    private val formatter: ResponseFormatter,
    /** Die EINZIGE Brain-Naht des Turns. Privat → strukturell max. 1 Call/Turn. */
    private val brain: BrainPort,
    /** Deterministischer Tool-Intent-Classifier. Default [ToolIntentClassifier.DISABLED] ⇒ kein Tool-Turn (Verhalten unverändert). */
    private val intent: ToolIntentClassifier = ToolIntentClassifier.DISABLED,
    /** Tat-Gate (DEFAULT DENY-ALL). Default [CapabilityPort.DENY_ALL]. */
    private val capability: CapabilityPort = CapabilityPort.DENY_ALL,
    /** Tat-Executor. Default [ToolPort.HONEST_PLACEHOLDER] (ehrlicher 🔵-Platzhalter, nie Fake). */
    private val tools: ToolPort = ToolPort.HONEST_PLACEHOLDER,
    /**
     * **Pro-Sprecher Last-Area-Gedächtnis** für die deterministische Anaphern-Auflösung
     * („schalt das Licht wieder aus" ohne Raum ⇒ die zuletzt von DIESEM Sprecher
     * geschaltete Area). Default [LastAreaPort.NONE] (merkt nie, erinnert nie) ⇒
     * byte-neutral: ohne echten Store gibt es keinen Fallback, der Pfad bleibt wie heute.
     */
    private val lastArea: LastAreaPort = LastAreaPort.NONE,
    /**
     * **Working-Session — serverseitige, speakerId-gekeyte History-Rekonstruktion
     * (räumliches Gedächtnis, S1). Default [WorkingSessionPort.NONE] ⇒ byte-neutral:**
     * `recentTurns` liefert immer die leere Liste, eine leere Client-history bleibt
     * leer und eine nicht-leere fließt unverändert durch — der Brain-Call ist EXAKT
     * wie heute. Erst bei `HOSHI_WORKING_SESSION_ENABLED=true` (PipelineConfig)
     * rekonstruiert [effectiveHistory] den Verlauf aus der Server-Session, wenn der
     * Client KEINE history schickt (Voice, zweites Gerät, Page-Reload).
     */
    private val workingSession: WorkingSessionPort = WorkingSessionPort.NONE,
    /**
     * Agentischer Tool-Layer (gemma-`tools` + resolve). Default `null` ⇒ Feature
     * AUS ⇒ der Pass-Pfad bleibt der unveränderte [brainTurn] (byte-neutral). Bei
     * gesetztem Registry läuft der Pass-Pfad über den sicheren [agenticBrainTurn].
     */
    private val agenticTools: AgenticToolRegistry? = null,
    /**
     * **Warmth v2 — passiver Mood-Temperatur-Hebel. Default [MoodTemperaturePort.NONE]
     * (Identität) ⇒ byte-neutral:** die an den Brain gereichte Temperatur ist EXAKT
     * `persona.temperatureFor()`, das bestehende Verhalten ändert sich NICHT. Erst bei
     * `HOSHI_WARMTH_V2_ENABLED=true` (PipelineConfig) nudgt der clock-gebundene Adapter
     * die Temperatur leicht (CALM tiefer, WAKE höher). KEIN zweiter Brain-Call.
     */
    private val mood: MoodTemperaturePort = MoodTemperaturePort.NONE,
    /**
     * **TimerFastpath — deterministische Timer/Wecker/Erinnerungen, brain-frei.**
     * Default [TimerFastpath.DISABLED] ⇒ nie betreten: ohne `HOSHI_TIMER_ENABLED`
     * emittiert der [intent]-Classifier keinen `timer`-[ToolCall], der Timer-Zweig
     * in [handle] ist dann tot ⇒ byte-neutral.
     */
    private val timer: TimerFastpath = TimerFastpath.DISABLED,
    /**
     * **CalcFastpath — deterministische Arithmetik, brain-frei.** Default
     * [CalcFastpath.DISABLED] ⇒ nie betreten: ohne `HOSHI_CALCULATOR_ENABLED`
     * emittiert der [intent]-Classifier keinen `calc`-[ToolCall], der Calc-Zweig
     * in [handle] ist dann tot ⇒ byte-neutral.
     */
    private val calculator: CalcFastpath = CalcFastpath.DISABLED,
    /**
     * **ListFastpath — deterministische Einkaufsliste, brain-frei** (Andi-JA
     * 2026-07-08, „Listen auf die Ring-1-Karte"). Default [ListFastpath.DISABLED]
     * ⇒ nie betreten: ohne `HOSHI_LIST_ENABLED` emittiert der [intent]-Classifier
     * keinen `list`-[ToolCall], der List-Zweig in [handle] ist dann tot ⇒
     * byte-neutral, exakt wie Timer/Calc.
     */
    private val list: ListFastpath = ListFastpath.DISABLED,
    /**
     * **FACT-Route-Temperatur-Clamp — flag-gated, default OFF** (byte-neutral).
     * Bei `true` wird die Persona-Temperatur fuer [RouteCategory.FACT_SHORT] auf
     * <= [FACT_TEMP_CEILING] gedeckelt (`min(personaTemp, 0.30)`): eine Jahres-/
     * Zahl-/Wissens-Antwort darf NICHT "kreativ" sein — hohe Temperatur garbelt
     * Zahlen ("dreihundertundsterzehn" statt 391) und laedt zum Ausweichen ein.
     * Query-seitig in Kotlin, NICHT als Prompt-Bitte (ein 4B haelt keine
     * "sei genau"-Regel). Nicht-FACT-Routen bleiben unveraendert. Default `false`
     * ⇒ Identitaet ⇒ byte-identische Temperatur wie heute.
     */
    private val factLowTemp: Boolean = false,
    /**
     * **DateFastpath — deterministisches Datum, brain-frei.** „Welcher Tag ist
     * heute?" beantwortet reine Code-Zeit (Clock), nicht der Brain (der kennt kein
     * Datum). Default [DateFastpath.DISABLED] ⇒ `handle()`==null ⇒ toter Zweig ⇒
     * byte-neutral, exakt wie Calc/Timer.
     */
    private val date: DateFastpath = DateFastpath.DISABLED,
    /**
     * **RadioFastpath — Internetradio (Musik Stufe A), brain-frei.** „spiel radio
     * wdr 2" ⇒ [de.hoshi.core.port.RadioPort] (Suche + Abspielziel), IN-SITU-
     * Erkennung wie [DateFastpath]. Default [RadioFastpath.DISABLED] ⇒
     * `matches()`==false ⇒ toter Zweig ⇒ byte-neutral, exakt wie Calc/Timer/Date.
     */
    private val radio: RadioFastpath = RadioFastpath.DISABLED,
    /**
     * **EscalationModeFastpath — Extended-Think-Stufe per Sprache/Chat, brain-frei.**
     * „frag mich erst, bevor du online gehst" ⇒ ERST_FRAGEN, „schalte
     * Online-Nachschauen aus" ⇒ AUS, „geh automatisch online" ⇒ AUTOMATISCH —
     * über die schmale [EscalationModeSwitchPort]-Naht in DIESELBE Store-Wahrheit
     * wie `PUT /api/v1/settings/extended-think`. Default
     * [EscalationModeFastpath.DISABLED] (Decke zu ⇒ im Wiring nie verdrahtet)
     * ⇒ `handle()`==null ⇒ toter Zweig ⇒ byte-neutral, exakt wie Calc/Timer/Date.
     */
    private val escalationModeSwitch: EscalationModeFastpath = EscalationModeFastpath.DISABLED,
    /**
     * **DailyNoteFastpath — Andi-Faktor-Tagesnote („Tagesnote 4"), brain-frei.**
     * Datiert über die [de.hoshi.core.port.DailyNotePort]-Naht in die eigene
     * JSONL (`andi-faktor.jsonl`, async best-effort); zweite Note am selben Tag
     * überschreibt ehrlich. Default [DailyNoteFastpath.DISABLED] ⇒
     * `handle()`==null ⇒ toter Zweig ⇒ byte-neutral, exakt wie Calc/Timer/Date.
     */
    private val dailyNote: DailyNoteFastpath = DailyNoteFastpath.DISABLED,
    /**
     * **WorkshopNoteFastpath — Werkstatt-Notiz an den Orchestrator, brain-frei**
     * (Cowork-Idee, von der Hand adoptiert, S1). „Notiz an die Werkstatt: …" /
     * „Werkstatt-Notiz: …" ⇒ verbatim über die
     * [de.hoshi.core.port.WorkshopNotePort]-Naht in den eigenen JSONL-
     * Briefkasten (`werkstatt-notizen.jsonl`, async best-effort, APPEND-only —
     * anders als die Tagesnote wird HIER NIE überschrieben). Default
     * [WorkshopNoteFastpath.DISABLED] ⇒ `handle()`==null ⇒ toter Zweig ⇒
     * byte-neutral, exakt wie Calc/Timer/Date/Tagesnote.
     */
    private val workshopNote: WorkshopNoteFastpath = WorkshopNoteFastpath.DISABLED,
    /**
     * **FactCoverageGate — die Anti-Konfabulations-Wand.** Eine FACT_SHORT-Frage,
     * deren Grounding NICHTS fand, darf der Brain NICHT freestylen (er erfindet
     * dann Falsches). Stattdessen ehrlich deflekten. Default [FactCoverageGate.DISABLED]
     * ⇒ immer Proceed ⇒ byte-neutral. Query-seitig statt Prompt-Bitte (ein 4B hält
     * keine „sei-ehrlich"-Regel).
     */
    private val factCoverage: FactCoverageGate = FactCoverageGate.DISABLED,
    /**
     * **Extended Think (S2) — der dritte Ausgang des Deflect-Zweigs.** Default
     * [EscalationPort.NONE] (antwortet immer Unavailable, kein Netz) ⇒ ohne
     * Wiring byte-neutral: der Eskalations-Pfad ist nur über [escalationMode]
     * != AUS erreichbar, und der steht default auf AUS.
     */
    private val escalation: EscalationPort = EscalationPort.NONE,
    /**
     * **Recherche-Modell-Eskalation (Andi-Auftrag 2026-07-19)** — eine ZWEITE,
     * unabhängige [EscalationPort]-Instanz NUR für explizite Recherche-Imperative
     * ([ResearchIntentRecognizer], z.B. „recherchiere online"), die — wenn
     * konfiguriert — das gründlichere (und teurere) gpt-5.6-Modell statt des
     * Nano-Defaults ruft. Default [EscalationPort.NONE] ⇒ [escalationChoice]
     * fällt IMMER auf [escalation] zurück (der Umschalter ist NICHT dieser Port,
     * sondern [researchEscalationProvider] — s. dort), exakt wie vor diesem
     * Auftrag (byte-neutral). „Universal by design" bleibt gewahrt (Kai-
     * Leitplanke, s. [EscalationPort]-KDoc): KEIN `model`-Parameter am Port
     * selbst, sondern eine zweite, eigenständige Instanz — s. PipelineConfig-
     * KDoc für die volle Begründung dieser Design-Wahl.
     */
    private val researchEscalation: EscalationPort = EscalationPort.NONE,
    /**
     * **Ehrliches Anzeige-Label** des [researchEscalation]-Modells (Muster
     * [LOOKUP_NOTE_PROVIDER], z.B. `"openai-sol"`) — reist als
     * [ChatEvent.Start.escalationProvider] und [LookupNote.provider] mit, damit
     * eine Sol-Antwort NIE als „openai-nano" beschriftet erscheint (Andi-Auftrag:
     * „keine Nano-Beschriftung auf einer Sol-Antwort"). **Leer ist zugleich der
     * FEATURE-SCHALTER** (Muster [de.hoshi.adapters.escalation.
     * FileBackedEscalationSpendStore] „Pfad-Präsenz als Schalter"): leer (Default)
     * ⇒ [escalationChoice] ignoriert [researchEscalation] und bleibt beim
     * Standard-Port/-Label ⇒ byte-neutral. Gesetzt von PipelineConfig NUR,
     * wenn `hoshi.escalation.research-model` eine bekannte Katalog-ID trägt
     * (fail-fast bei unbekannter ID, s. PipelineConfig-KDoc) — der Wert hier ist
     * darum, wann immer nicht-leer, vertrauenswürdig konsistent zu [researchEscalation].
     */
    private val researchEscalationProvider: String = "",
    /**
     * Session-Gedächtnis des offenen „soll ich kurz nachschauen?"-Angebots
     * (Key: `chatId ?: speakerId ?: "local"`, TTL+one-shot im Store). Default
     * [PendingLookupPort.NONE] (merkt nie, liefert nie) ⇒ byte-neutral.
     */
    private val pendingLookup: PendingLookupPort = PendingLookupPort.NONE,
    /**
     * Drei-Stufen-Setting als SUPPLIER (pro Turn gelesen — ein Settings-PUT
     * greift ab dem nächsten Turn, ohne Redeploy). Default konstant
     * [EscalationMode.AUS] ⇒ exakt heutiges Verhalten. Die Deploy-Decke
     * (`HOSHI_EXTENDED_THINK_ENABLED=false`) kollabiert im Wiring auf
     * denselben konstanten AUS-Supplier.
     */
    private val escalationMode: () -> EscalationMode = { EscalationMode.AUS },
    /**
     * **Wetter-Orts-Nachfrage (Wetter S3).** Entscheidet, ob eine Wetter-Frage
     * OHNE konfigurierten Ort vorliegt ([WeatherLocationAskPort.needsLocation])
     * — dann fragt der Orchestrator deterministisch nach ([weatherLocationAsk])
     * statt einen Wetter-Block mit falschem Default-Ort zu liefern — und löst
     * die Orts-Antwort des Folge-Turns auf (Geocode + Store, wie der
     * Settings-PUT). Default [WeatherLocationAskPort.NONE] (fragt nie nach) ⇒
     * beide Zweige tot ⇒ byte-neutral.
     */
    private val weatherAsk: WeatherLocationAskPort = WeatherLocationAskPort.NONE,
    /**
     * Session-Gedächtnis der offenen „Für welchen Ort denn?"-Nachfrage —
     * EXAKT das [pendingLookup]-Muster (Key `chatId ?: speakerId ?: "local"`,
     * TTL + one-shot im Store), aber ein EIGENER Store: getrennte Ketten
     * können sich strukturell nie vermischen (Begründung im
     * [PendingLocationQuestionPort]). Default [PendingLocationQuestionPort.NONE]
     * (merkt nie, liefert nie) ⇒ byte-neutral.
     */
    private val pendingLocation: PendingLocationQuestionPort = PendingLocationQuestionPort.NONE,
    /** Timeout des Eskalations-Lookups (~8 s) — danach der warme Unavailable-Pfad (never-silent). */
    private val escalationTimeout: Duration = ESCALATION_LOOKUP_TIMEOUT,
    /**
     * **Nachgeschlagen-Store-WRITE (Extended Think S3).** Default [LookupNotePort.NOOP]
     * (schreibt nie) ⇒ byte-neutral. Bei echtem Store: [escalationTurn] persistiert
     * NACH JEDER bezahlten [EscalationResult.Answer] EINE [LookupNote] (best-effort,
     * non-blocking) — UNKLAR/Unavailable/Declined werden NIE gespeichert
     * (Nora-Veto „Konfabulations-Waschmaschine": nur verifizierte Antworten cachen).
     * Der Lese-Pfad ([de.hoshi.adapters.knowledge]s `NachgeschlagenGroundingProvider`)
     * hängt an der Composite-Grounding-Kette (nach weather, vor wiki) und ist bewusst
     * UNABHÄNGIG von diesem Port (eigener Datei-Rand, „eine Datei-Wahrheit, zwei
     * schmale Ränder").
     */
    private val lookupNotes: LookupNotePort = LookupNotePort.NOOP,
    /**
     * **Nachgeschlagen-Verbatim-Replay-Naht (S3, Andi-Fix 2026-07-16).** Exponiert den
     * DETERMINISTISCHEN Cache-Treffer ([de.hoshi.adapters.knowledge]s
     * `NachgeschlagenGroundingProvider` als [LookupReplayPort]) als rohe [LookupNote].
     * Default [LookupReplayPort.NONE] (findet nie) — der Replay-Zweig ist zusätzlich
     * per [verbatimReplayEnabled] flag-gated, ohne Wiring also doppelt tot ⇒ byte-neutral.
     */
    private val lookupReplay: LookupReplayPort = LookupReplayPort.NONE,
    /**
     * **Flag `HOSHI_LOOKUP_VERBATIM_REPLAY_ENABLED`, default `false` ⇒ byte-neutral.**
     * Bei `false` betritt [brainTurn] den Replay-Zweig GAR NICHT (kein [lookupReplay]-
     * Call, kein Scheduler-Hop) — [brainTurn] ist dann EXAKT [brainStreamTurn] (der
     * bisherige Brain-Pfad). Bei `true` UND einem SICHEREN Cache-Treffer über der
     * Provider-Schwelle spielt [verbatimReplayTurn] die Notiz WÖRTLICH, brain-frei
     * zurück; unter der Schwelle / abgelaufen / leerer Store fällt der Turn
     * byte-identisch auf [brainStreamTurn] (heutiger Grounding-Injektions-Pfad).
     */
    private val verbatimReplayEnabled: Boolean = false,
    /** Warme Fallback-Phrase für den Never-Silent-Vertrag (leerer/fehlerhafter Brain-Stream). */
    private val warmFallback: () -> String = { DEFAULT_FALLBACK },
    /**
     * Zeitquelle der Brain-TTFT-Messung ([ChatEvent.StageTimings.brainTtftMs]) —
     * injizierbar für deterministische Tests (Fake-Clock), Default die echte
     * Nano-Uhr. Rein additiv: kein bestehender Aufrufer ändert sich.
     */
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * **Verhör-Detektor (S1) — STT-Surprisal-Naht, nullable injiziert.** Default
     * `null` (Feature AUS, Wiring: Env `HOSHI_STT_SURPRISAL_ENABLED`) ⇒
     * [withSttSurprisal] gibt den Turn-Stream UNVERÄNDERT zurück ⇒ byte-neutral,
     * KEIN Call. Bei gesetztem Port misst [withSttSurprisal] NUR (kein Verhalten
     * hängt am Wert) — Details s. dortiges KDoc + [SttSurprisalPort]-Klassen-KDoc.
     */
    private val sttSurprisal: SttSurprisalPort? = null,
    /**
     * **ProbeFastpath — der Selbsttest-Satz „Hoshi, Probe." (Golden-Utterance
     * #20), brain-frei.** EIN warmer, statischer Status-Satz, der die Kette
     * (Ohren→Draht→Server→Stimme) binär beweist. Default [ProbeFastpath.DISABLED]
     * ⇒ `handle()`==null ⇒ toter Zweig ⇒ byte-neutral, exakt wie Calc/Timer/
     * Date/Tagesnote/Werkstatt-Notiz.
     */
    private val probe: ProbeFastpath = ProbeFastpath.DISABLED,
    /**
     * **Sprecher-Vertrauens-Gate (P1-Privacy) — flag-gated, default OFF ⇒ byte-neutral.**
     * Schließt die vom Bau-Pod ehrlich gemeldete Restlücke des P1-Fixes (Commit `bc64190`,
     * [SpeakerTrust]): [effectiveSession] las den WorkingSession-Recall bislang UNGEGATET
     * über die bloß behauptete `ctx.speaker?.speakerId` — derselbe Bedrohungsvektor wie beim
     * Entity-/Episodic-Recall in [TurnPromptAssembler] (ein Client kann
     * `speakerContext:{speakerId:"andi"}` senden und so fremden Sitzungskontext lesen; siehe
     * [SpeakerTrust]-KDoc für das volle Bedrohungsmodell). Bei `false` (Default) bleibt
     * [effectiveSession] EXAKT beim heutigen Verhalten: die behauptete `speakerId` wird
     * ungeprüft an [workingSession] gereicht. Bei `true` entscheidet [SpeakerTrust.resolve]
     * — DIESELBE zentrale Funktion, DIESELBE Gast-Kollaps-Semantik wie [TurnPromptAssembler]
     * (Entity-/Episodic-Recall) und `de.hoshi.web.ChatStreamController.rememberAfter`
     * (Write) — ob der Claim vertraut wird (Score >= [speakerTrustThreshold]) oder auf
     * [SpeakerTrust.GUEST_SPEAKER_ID] kollabiert (kein Cross-User-WorkingSession-Recall).
     * Gespeist vom SELBEN Flag (`HOSHI_SPEAKER_TRUST_ENFORCED`) wie die anderen beiden
     * Nähte — EINE Entscheidung, jetzt DREI Nähte.
     */
    private val speakerTrustEnforced: Boolean = false,
    /**
     * Score-Schwelle des Gates (nur wirksam bei [speakerTrustEnforced]) — DIESELBE
     * Property wie die anderen beiden Nähte (`hoshi.speaker.recognition.threshold`,
     * Default 0.80): EINE Messlatte für „ist das wirklich diese Person", jetzt von
     * Recall (Entity+Episodic+WorkingSession) UND Write geteilt.
     */
    private val speakerTrustThreshold: Double = 0.80,
    /**
     * **Explizite Nachschlag-Bitte + Brain-Abstain-Pending — flag-gated, default OFF
     * ⇒ byte-neutral** (`HOSHI_LOOKUP_INTENT_ENABLED`, Wiring in PipelineConfig).
     *
     * Schließt die zwei Live-Löcher der Nachschlag-Kette:
     *  - **Naht C ([lookupIntentTurn]):** „schau (bitte) online nach" ist selbst der
     *    Consent — der Orchestrator löst ein offenes [pendingLookup]-Angebot ein oder
     *    eskaliert die VORHERIGE User-Frage direkt (Egress-Gesetz: NUR die Frage geht
     *    raus). Statt dass das 4B über „das Internet" konfabuliert.
     *  - **Naht D ([maybeOfferAbstainPending]):** passt das Brain bei einem
     *    LOCAL-FACT-Turn ehrlich ([BrainAbstainRecognizer]), registriert der
     *    Orchestrator ein [PendingLookup] mit der User-Frage — OHNE die Antwort
     *    anzufassen —, damit ein „ja"/Intent es danach einlösen kann.
     *
     * **Naht D HÖRBAR (Andi-Auftrag 2026-07-20):** ein reines `offer` im Store
     * beweist Andis Live-Befund nach — der Deflect-Antwort fehlte JEDES Wort
     * über ein Angebot, ein „ja" danach hatte nichts sichtbar einzulösen. Jetzt
     * hängt [neverSilent] bei registriertem Pending UND erlaubendem
     * [escalationMode] (NICHT [EscalationMode.AUS]) EINEN warmen
     * [ResponseFormatter.abstainLookupOffer]-Satz an (Antwort-Bytes davor
     * unverändert) — UND ein schlichtes Zustimmungswort, das
     * [AffirmationRecognizer] (Naht B) nicht kennt (z.B. „jo"/„jap"), löst über
     * [ConsentRecognizer] an Naht C zusätzlich ein (NUR bei offenem Angebot,
     * s. dortiger Kommentar).
     *
     * Bei `false` (Default) werden ALLE Nähte nie betreten (kein Recognizer-Call,
     * kein History-Load, kein Antwort-Puffer, kein `offer`, kein Angebots-Satz)
     * ⇒ jeder Pfad bleibt byte-identisch zu heute.
     */
    private val lookupIntentEnabled: Boolean = false,
) {

    /**
     * Fährt einen kompletten Turn: Text rein → Routing → Honesty → Prompt →
     * Brain(1×) → Formatter → `Flux<ChatEvent>` (Start/TextDelta…/Done) raus.
     *
     * **Verhör-Detektor (S1, additiv):** hüllt [handleTurn] in [withSttSurprisal]
     * — die eigentliche Turn-Logik bleibt [handleTurn] (dorthin ruft
     * [locationAnswerTurn] für den GESPEICHERTEN Alt-Query INTERN direkt durch,
     * ohne die Messung ein zweites Mal für einen Nicht-Transkript-Text
     * anzustoßen; s. dortiges KDoc).
     */
    fun handle(request: ChatRequest): Flux<ChatEvent> = withSttSurprisal(request, handleTurn(request))

    /**
     * Die eigentliche Turn-Logik (unverändert aus [handle] extrahiert, S1):
     * Text rein → Routing → Honesty → Prompt → Brain(1×) → Formatter →
     * `Flux<ChatEvent>` raus. Siehe [handle] für den öffentlichen Vertrag.
     */
    private fun handleTurn(request: ChatRequest): Flux<ChatEvent> {
        val ctx = TurnPrompt.from(request)
        // Leere Eingabe ist auch ein Turn — never-silent, ohne Routing/Brain.
        if (ctx.text.isBlank()) {
            return warmDirectAnswer(provider = "LOCAL", category = "EMPTY", phrase = warmFallback())
        }
        val speakerId = ctx.speaker?.speakerId
        val key = pendingKey(ctx)
        // ── Naht B (Extended Think S2): die Einlösung des „soll ich kurz nachschauen?"-
        //    Angebots — VOR dem Routing (ein „ja" würde sonst als Smalltalk zum Brain
        //    plaudern). consume() ist bewusst one-shot und läuft bei JEDEM Turn: auch
        //    ein „nein"/Fremd-Turn räumt das Angebot (kein alter „ja"-Köder). Nur eine
        //    deterministische Affirmation ([AffirmationRecognizer]) löst ein — dann mit
        //    der GESPEICHERTEN Original-Query (NIE mit „ja"). Bei Laufzeit-Stufe AUS
        //    (Decke offen, Setting aus) gibt es den ehrlich-warmen Setting-Hinweis
        //    statt eines stillen Calls. Default (NONE-Store) ⇒ consume()==null ⇒
        //    dieser Zweig ist tot ⇒ byte-neutral.
        val pendingThink = pendingLookup.consume(key)
        // ── Naht B2 (Wetter S3): die offene Orts-Nachfrage wird bei JEDEM Turn
        //    one-shot konsumiert (auch ein Fremd-Turn räumt sie — kein alter
        //    Orts-Köder), noch BEVOR unten entschieden wird, ob sie einlöst.
        //    Default (NONE-Store) ⇒ null ⇒ toter Zweig ⇒ byte-neutral.
        val pendingPlace = pendingLocation.consume(key)
        if (pendingThink != null && AffirmationRecognizer.matches(ctx.text)) {
            return redeemLookup(pendingThink.query, pendingThink.language)
        }
        // ── Naht C (Lookup-Intent, Live-Fix 2026-07-16): eine EXPLIZITE Nachschlag-
        //    Bitte („schau online nach") IST selbst der Consent — sie darf NICHT als
        //    Prosa zum Brain plaudern (der konfabuliert dann über „das Internet").
        //    Flag-gated (default OFF ⇒ Recognizer wird nie gerufen ⇒ byte-neutral);
        //    delegiert an [lookupIntentTurn] (dort die drei Ausgänge: offenes Angebot
        //    einlösen / vorige Frage eskalieren / ehrliche Rückfrage).
        //    PRÄZEDENZ: ein Stufen-Schaltbefehl gewinnt IMMER (z.B. „schalte online
        //    nachschauen aus" trägt „nachschauen"+„online" und sähe sonst wie eine
        //    Nachschlag-Bitte aus — ist aber ein Settings-Kommando). [match] ist reine
        //    Erkennung (ohne Store-Effekt, unabhängig vom Fastpath-enabled) ⇒ Naht C
        //    tritt bei einem erkannten Schaltbefehl NIE ein.
        //    ZWEITER Eingang (Andi-Auftrag 2026-07-20, „ein schlichtes ja muss es
        //    einlösen"): ein schlichtes Zustimmungswort ([ConsentRecognizer], z.B.
        //    „jo"/„jap" — Wörter, die [AffirmationRecognizer] an Naht B (oben) NICHT
        //    kennt) löst NUR ein, wenn [pendingThink] hier bereits (one-shot, s.o.)
        //    konsumiert vorliegt — KEIN zweiter `consume`-Call (peek/consume-Disziplin:
        //    der lokale [pendingThink] ist schon der einmalig gezogene Wert). OHNE
        //    offenes Angebot bleibt ein bloßes Zustimmungswort IMMER normaler
        //    Smalltalk (Turn fällt durch, Naht C bleibt zu) — Consent ohne Frage ist
        //    kein Consent. ──
        if (lookupIntentEnabled &&
            escalationModeSwitch.match(ctx.text) == null &&
            (LookupIntentRecognizer.matches(ctx.text) || (pendingThink != null && ConsentRecognizer.matches(ctx.text)))
        ) {
            // Recherche-Modell-Wahl (Andi-Auftrag 2026-07-19): NUR innerhalb der
            // bereits offenen Naht C entschieden — [ResearchIntentRecognizer] ist
            // eine strenge Teilmenge von [LookupIntentRecognizer] (s. dessen KDoc),
            // öffnet also NIE einen eigenen Eingang. [escalationChoice] kollabiert
            // das ohnehin auf den Standard-Port, solange kein Recherche-Modell
            // konfiguriert ist ([researchEscalationProvider] leer) ⇒ byte-neutral.
            val research = ResearchIntentRecognizer.matches(ctx.text)
            return lookupIntentTurn(ctx, pendingThink, research)
        }
        // ── Naht B2-Einlösung: NUR wenn KEIN Extended-Think-Angebot in flight war
        //    (Ketten sauber getrennt — ein Orts-Name löst NIE während einer offenen
        //    Nachschau-Frage ein) UND der Folge-Turn konservativ wie ein Ort aussieht
        //    ([LocationAnswerRecognizer]). Sonst: normaler Turn, die Nachfrage ist
        //    konsumiert und verfällt.
        if (pendingThink == null && pendingPlace != null) {
            LocationAnswerRecognizer.place(ctx.text)?.let { place ->
                return locationAnswerTurn(place, pendingPlace, ctx, speakerId)
            }
        }
        // ── Stufen-Fastpath (Extended Think per Sprache/Chat, Andi-Intent
        //    2026-07-05): „frag mich erst, bevor du online gehst" ⇒ ERST_FRAGEN —
        //    VOR dem Routing (ein Settings-Satz würde sonst als Smalltalk zum
        //    Brain plaudern), deterministisch + brain-frei, dieselbe
        //    Store-Wahrheit wie der Settings-PUT. Default (DISABLED) ⇒ null ⇒
        //    toter Zweig ⇒ byte-neutral. ──
        escalationModeSwitch.handle(ctx.text, ctx.language)?.let { phrase ->
            return warmDirectAnswer(RouteProvider.LOCAL.name, CATEGORY_SETTINGS, phrase)
        }
        // ── Tagesnote-Fastpath (Andi-Faktor): „Tagesnote 4(, weil …)" ⇒ datiert
        //    in die eigene JSONL (async best-effort), warme deterministische
        //    Quittung, brain-frei — ebenfalls VOR dem Routing. Der Eingangs-Rand
        //    ([ChatRequest.source]; alt-Client null ⇒ "chat") fließt nur in die
        //    JSONL-Zeile. Default (DISABLED) ⇒ null ⇒ toter Zweig ⇒ byte-neutral. ──
        dailyNote.handle(ctx.text, request.source ?: SOURCE_CHAT_DEFAULT)?.let { phrase ->
            return warmDirectAnswer(RouteProvider.LOCAL.name, CATEGORY_NOTE, phrase)
        }
        // ── Werkstatt-Notiz-Fastpath (Cowork-Idee, S1): „Notiz an die
        //    Werkstatt: …" ⇒ verbatim in den JSONL-Briefkasten (async
        //    best-effort), warme deterministische Quittung, brain-frei —
        //    ebenfalls VOR dem Routing. Default (DISABLED) ⇒ null ⇒ toter
        //    Zweig ⇒ byte-neutral. ──
        workshopNote.handle(ctx.text, speakerId)?.let { phrase ->
            return warmDirectAnswer(RouteProvider.LOCAL.name, CATEGORY_NOTE, phrase)
        }
        // ── Probe-Fastpath (Golden-Utterance #20, Andis Selbsttest-Ritual):
        //    „Hoshi, Probe." ⇒ EIN warmer, statischer Status-Satz, der Ohren/
        //    Draht/Server/Stimme binär beweist, brain-frei — ebenfalls VOR dem
        //    Routing (ein Selbsttest-Ruf darf nicht als Smalltalk zum Brain
        //    plaudern oder in eine andere Kategorie geroutet werden). Default
        //    (DISABLED) ⇒ null ⇒ toter Zweig ⇒ byte-neutral, exakt wie
        //    Calc/Timer/Date/Tagesnote/Werkstatt-Notiz. ──
        probe.handle(ctx.text)?.let { phrase ->
            return warmDirectAnswer(RouteProvider.LOCAL.name, CATEGORY_PROBE, phrase)
        }
        return routedTurn(request, ctx, speakerId)
    }

    /**
     * **Verhör-Detektor (S1, MESSEN-first, additiv) — hüllt [stream] mit einer
     * NEBENLÄUFIGEN STT-Surprisal-Messung des rohen Whisper-Transkripts.**
     *
     * Byte-neutral (KEIN Call): [sttSurprisal]==`null` (Default, Flag
     * `HOSHI_STT_SURPRISAL_ENABLED` OFF) ODER [request] trägt KEIN Voice-
     * Transkript (`source` weder `"voice"` noch `"ws"`, s. [SOURCE_VOICE]/
     * [SOURCE_WS]) ODER der Text ist leer (der `no_input`-Pfad hat nichts zu
     * scoren) ⇒ [stream] UNVERÄNDERT zurück.
     *
     * **Nebenläufig statt seriell (Latenz-Budget):** [SttSurprisalPort.score]
     * wird SOFORT angestoßen — parallel zum eigentlichen Turn, NICHT davor
     * gewartet — und über [Mono.cache] einmal geteilt. Start-/TextDelta-Events
     * von [stream] laufen dadurch UNGEBREMST durch (keine TTFT-Verzögerung).
     * Erst am terminalen [ChatEvent.Done] wird das (in aller Regel bis dahin
     * längst fertige — ein Brain-Turn dauert typischerweise deutlich länger
     * als die Score-Messung) Ergebnis eingesammelt. Ist der Turn schneller
     * fertig als die Messung (kurze Fastpath-/Tool-Antworten), wartet NUR das
     * Done — bounded auf [STT_SURPRISAL_TIMEOUT] (500 ms; der Adapter kapselt
     * denselben Timeout zusätzlich, s. [SttSurprisalPort]-KDoc) — danach
     * `null`. KEIN Verhalten hängt am Wert; ein Mess-Fehler/Timeout
     * unterscheidet sich für den Turn selbst in NICHTS von Flag OFF.
     */
    private fun withSttSurprisal(request: ChatRequest, stream: Flux<ChatEvent>): Flux<ChatEvent> {
        val port = sttSurprisal ?: return stream
        if (request.text.isBlank()) return stream
        if (request.source != SOURCE_VOICE && request.source != SOURCE_WS) return stream
        val scoreMono: Mono<SttSurprisal> = port.score(request.text)
            .timeout(STT_SURPRISAL_TIMEOUT)
            .onErrorResume { Mono.empty() }
            .cache()
        // Sofort anstoßen (nicht erst beim Done-Merge) — die HTTP-Messung läuft
        // ab JETZT parallel zum Rest des Turns. subscribe() blockiert nicht
        // (WebClient/Netty ist non-blocking); Fehler sind oben schon abgefangen.
        scoreMono.subscribe({}, {})
        return stream.concatMap { event ->
            if (event is ChatEvent.Done) {
                scoreMono.map { score -> mergeSttSurprisal(event, score) }.defaultIfEmpty(event)
            } else {
                Mono.just(event)
            }
        }
    }

    /** Merged [score] additiv in [done]s `stageTimings` (Muster `answerEntropy`) — bestehende Felder bleiben unberührt. */
    private fun mergeSttSurprisal(done: ChatEvent.Done, score: SttSurprisal): ChatEvent.Done =
        done.copy(
            stageTimings = (done.stageTimings ?: ChatEvent.StageTimings()).copy(
                sttSurprisal = score.meanSurprisal,
                sttSurprisalMax = score.maxSurprisal,
            ),
        )

    /**
     * Der GEROUTETE Turn — das bisherige Herzstück von [handle] nach den
     * Pending-Nähten (Routing → Fastpaths → Tool-Pfad → Honesty → Brain),
     * UNVERÄNDERT extrahiert, damit der Orts-Folge-Turn ([locationAnswerTurn])
     * bei „kein Geocode-Treffer" ehrlich als normaler Turn weiterlaufen kann.
     */
    private fun routedTurn(request: ChatRequest, ctx: TurnPrompt, speakerId: String?): Flux<ChatEvent> {
        return routing.resolve(ctx.text).flatMapMany { decision ->
            // ── Datums-Fastpath: „welcher Tag ist heute?" → deterministisch aus der
            //    Code-Uhr (Clock), brain-frei. Ohne HOSHI_DATE_FASTPATH_ENABLED ist
            //    date == DISABLED ⇒ handle()==null ⇒ dieser Zweig bleibt tot. ──
            date.handle(ctx.text, ctx.language)?.let { phrase ->
                return@flatMapMany warmDirectAnswer(decision.provider.name, decision.category.name, phrase)
            }
            // ── Radio-Fastpath: „spiel radio <name>" / „radio aus" → RadioPort
            //    (Stream-Suche + Abspielziel), brain-frei. Ohne HOSHI_RADIO_ENABLED
            //    ist radio == DISABLED ⇒ matches()==false ⇒ dieser Zweig bleibt tot.
            //    (P0 Event-Loop-Fix) handle() macht bei Treffern echtes I/O
            //    (radio-browser-Suche + HA-Call) → off der Event-Loop auf
            //    boundedElastic (Bestands-Muster wie toolReadTurn/honesty.assess);
            //    das billige, netzfreie matches() bleibt am Aufrufer-Thread. ──
            if (radio.matches(ctx.text)) {
                return@flatMapMany Mono.fromCallable { radio.handle(ctx.text, ctx.language).orEmpty() }
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany { phrase ->
                        warmDirectAnswer(decision.provider.name, decision.category.name, phrase.ifBlank { warmFallback() })
                    }
            }
            // ── Tool-Pfad: eindeutiger Befehl ⇒ Gate → Executor, OHNE Brain ──
            // Anaphern-Auflösung (pro Sprecher): ein roomless Licht-/Klima-Befehl darf
            // auf die zuletzt geschaltete Area dieses Sprechers fallen ([resolveToolCall]).
            val toolCall = resolveToolCall(intent.classify(ctx.text, ctx.language), ctx.text, speakerId)
            if (toolCall != null) {
                // ── Timer-Fastpath: ein `timer`-Befehl geht an die ScheduledItemPort-
                //    Naht (NICHT durchs HA-Schreib-Gate), brain-frei. Ohne Flag emittiert
                //    der Classifier nie domain="timer" ⇒ dieser Zweig bleibt tot. ──
                return@flatMapMany if (toolCall.domain == TimerIntent.DOMAIN) {
                    // origin = die Gerät-/Session-Id des Turn-Ursprungs (ChatRequest.deviceId).
                    // null (alt-Client) ⇒ origin=null ⇒ byte-neutraler Alt-Pfad.
                    timerTurn(toolCall, decision, ctx.language, request.deviceId, request.originSatelliteId)
                } else if (toolCall.domain == CalcIntent.DOMAIN) {
                    // ── Calc-Fastpath: eine Rechnung geht an den brain-freien, gate-freien
                    //    CalcFastpath (reine Arithmetik, kein HA-Aktuator). Ohne Flag emittiert
                    //    der Classifier nie domain="calc" ⇒ dieser Zweig bleibt tot. ──
                    calcTurn(toolCall, decision, ctx.language)
                } else if (toolCall.domain == ListIntent.DOMAIN) {
                    // ── List-Fastpath: ein `list`-Befehl geht an die ListPort-Naht (NICHT
                    //    durchs HA-Schreib-Gate), brain-frei — eine Einkaufsliste ist kein
                    //    HA-Aktuator, exakt wie der Timer. Ohne Flag emittiert der Classifier
                    //    nie domain="list" ⇒ dieser Zweig bleibt tot. ──
                    listTurn(toolCall, decision, ctx.language)
                } else if (toolCall.domain == AreaClarifyIntent.DOMAIN) {
                    // ── Clarify-Fastpath (Live-Befund 2026-07-15): ein sicher erkanntes
                    //    Schalt-Verb + An/Aus-Partikel OHNE auflösbares Ziel geht NICHT durch
                    //    das HA-Schreib-Gate (keine Tat) und NICHT zum Brain (der würde Prosa
                    //    OHNE Tat liefern) — brain-frei, exakt wie Timer/Calc/List. Der
                    //    Classifier klassifiziert diesen Zweig NUR, wenn HOSHI_TOOLS_ENABLED
                    //    (SMART_HOME-Skill) ohnehin an ist ⇒ kein neues Flag nötig. ──
                    clarifyTurn(toolCall, decision)
                } else {
                    toolTurn(toolCall, decision, speakerId)
                }
            }
            // ── Wetter-Orts-Nachfrage (Wetter S3): eine WETTER-Frage, für die WEDER
            //    der Laufzeit-Store NOCH ein Deploy-Seed einen echten Ort trägt
            //    (Kriterium beim Adapter: Store leer UND Seeds auf den Code-Defaults),
            //    bekommt statt eines Wetter-Blocks mit falschem Default-Ort eine
            //    warme, deterministische Nachfrage — und die ORIGINAL-Frage wird als
            //    Pending gemerkt (offer, TTL+one-shot), damit der Orts-Folge-Turn sie
            //    direkt beantworten kann. Brain-frei. Default (NONE-Port) ⇒
            //    needsLocation konstant false ⇒ toter Zweig ⇒ byte-neutral; in Prod
            //    (echte Seeds konfiguriert) feuert der Zweig NIE.
            if (weatherAsk.needsLocation(ctx.text, decision.category)) {
                pendingLocation.offer(pendingKey(ctx), PendingLocationQuestion(query = ctx.text, language = ctx.language))
                return@flatMapMany warmDirectAnswer(
                    decision.provider.name,
                    decision.category.name,
                    weatherLocationAsk(ctx.language),
                )
            }
            // ── Ehrlichkeits-Gate VOR dem Brain — OFF der Event-Loop ──
            // (P0 Event-Loop-Fix) honesty.assess kann SYNCHRON die Wissens-Bridge
            // proben (ExistenceClaim/NamedEntity → BridgeSearchClient = blockierendes
            // java.net.http, Connect-/Read-Timeout bis 5 s). Direkt im flatMapMany
            // liefe das auf dem Reactor-Netty-Event-Loop und blockierte ihn. Darum
            // exakt nach dem Bestands-Muster (EpisodicMemoryAdapter.recallBlock /
            // EmbeddingRouterRefiner.refine) auf boundedElastic auslagern. Bei
            // HOSHI_HONESTY_PROBE_ENABLED=false proben die Detektoren NICHT (inerte
            // Stubs ⇒ HonestySignal.NONE) → assess ist rein/schnell ⇒ byte-neutral,
            // nur ein zusätzlicher Scheduler-Hop ohne Verhaltens-Effekt.
            Mono.fromCallable { honesty.assess(ctx.text) }
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany { verdict ->
                    when (verdict) {
                        // ── Abstention: warme Direkt-Antwort, der Brain wird NIE gerufen ──
                        is HonestyGate.Verdict.Refuse ->
                            warmDirectAnswer(decision.provider.name, decision.category.name, verdict.phrase)
                        HonestyGate.Verdict.AskConsent ->
                            warmDirectAnswer(decision.provider.name, decision.category.name, formatter.cloudConsentAsk(ctx.language))
                        HonestyGate.Verdict.AskConsentExplicit ->
                            warmDirectAnswer(decision.provider.name, decision.category.name, formatter.cloudConsentAskExplicit(ctx.language))
                        // ── Pass: der einzige Pfad, der den Brain (genau 1×) ruft ──
                        // Mit agentischem Tool-Layer läuft der Pass über den sicheren
                        // [agenticBrainTurn] (Brain-mit-Tools → klassifizieren → ggf. gaten);
                        // ohne ihn (Default) bleibt es exakt beim heutigen [brainTurn].
                        // Der agentische Grammar-Pfad erzwingt Tool-JSON → er DARF nur für
                        // smart-home-geroutete Turns greifen, sonst bekäme jede Plauderei ein
                        // ehrliches „kann ich nicht" (tool=none) statt Konversation (max-1-Brain-
                        // Call verbietet einen 2. Prosa-Call). Alles andere → unveränderter brainTurn.
                        // (Folge-Scheibe: Router routet indirekte Komfort-Phrasen „mir ist kalt"
                        //  → SMART_HOME, damit der agentische Mehrwert greift.)
                        HonestyGate.Verdict.Pass ->
                            if (agenticTools != null && decision.category == RouteCategory.SMART_HOME)
                                agenticBrainTurn(ctx, decision)
                            else brainTurn(ctx, decision)
                    }
                }
        }.onErrorResume { e ->
            // Fängt auch Fehler aus Routing/Assembly ab (vor dem Brain) — never-silent.
            warmDirectAnswer(provider = "LOCAL", category = "ERROR", phrase = warmFallback())
        }
    }

    /**
     * Der Tool-Pfad — strukturell **brain-frei**. Verzweigt nach Lesen vs. Schreiben:
     *
     *  - **Lesen** ([ToolCall.read]==true, z.B. „wie warm ist es?"): KEIN Aktuator,
     *    kein State-Change ⇒ am Schreib-Gate VORBEI direkt in [toolReadTurn]
     *    ([tools].execute liest den HA-State, warme Quittung). Das Schreib-Gate
     *    ([capability]) bewacht ausschließlich schreibende Taten — ein reiner Read
     *    braucht (und bekommt) keine Schreib-Freigabe.
     *  - **Schreiben** (Default): Tat gaten ([capability]), bei Grant den Executor mit
     *    der NORMALISIERTEN data rufen ([tools]), bei Deny die warme Absage.
     *
     * Ruft den [brain] NIE (0 Brain-Calls/Turn).
     */
    private fun toolTurn(call: ToolCall, decision: RouteDecision, speakerId: String?): Flux<ChatEvent> =
        if (call.read) toolReadTurn(call, decision) else writeToolTurn(call, decision, speakerId)

    /**
     * Der **Timer**-Pfad — strukturell brain-frei UND HA-gate-frei (ein Timer ist ein
     * lokaler Convenience-Store, kein HA-Aktuator). [timer].handle legt an/fragt/
     * storniert über die [de.hoshi.core.port.ScheduledItemPort] und liefert die warme,
     * deterministische Quittung in der Turn-Sprache. Leer ⇒ warmer Fallback (never-silent).
     *
     * [origin] (aus [ChatRequest.deviceId]) reicht der Fastpath bei SET in das angelegte
     * [de.hoshi.core.port.ScheduledItem] durch (Wecker-Ursprung fürs FE); `null` ⇒
     * byte-neutraler Alt-Pfad (kein Ursprung ⇒ FE klingelt überall).
     */
    private fun timerTurn(call: ToolCall, decision: RouteDecision, language: Language, origin: String?, originSatelliteId: String? = null): Flux<ChatEvent> {
        val phrase = timer.handle(call, language, origin, originSatelliteId)
        return warmDirectAnswer(decision.provider.name, decision.category.name, phrase.ifBlank { warmFallback() })
    }

    /**
     * Der **Calc**-Pfad — strukturell brain-frei UND HA-gate-frei (eine Rechnung ist
     * reine Arithmetik, kein HA-Aktuator). [calculator].handle wertet den sicheren
     * Ausdruck aus und liefert die warme, deterministische Quittung in der Turn-Sprache.
     * Leer ⇒ warmer Fallback (never-silent).
     */
    private fun calcTurn(call: ToolCall, decision: RouteDecision, language: Language): Flux<ChatEvent> {
        val phrase = calculator.handle(call, language)
        return warmDirectAnswer(decision.provider.name, decision.category.name, phrase.ifBlank { warmFallback() })
    }

    /**
     * Der **List**-Pfad — strukturell brain-frei UND HA-gate-frei (eine Einkaufsliste
     * ist ein lokaler Convenience-Store, kein HA-Aktuator — exakt wie der Timer).
     * [list].handle legt an/liest/entfernt über die [de.hoshi.core.port.ListPort] und
     * liefert die warme, deterministische Quittung MIT Read-back in der Turn-Sprache.
     * Leer ⇒ warmer Fallback (never-silent).
     */
    private fun listTurn(call: ToolCall, decision: RouteDecision, language: Language): Flux<ChatEvent> {
        val phrase = list.handle(call, language)
        return warmDirectAnswer(decision.provider.name, decision.category.name, phrase.ifBlank { warmFallback() })
    }

    /**
     * Der **Rückfrage**-Pfad ([AreaClarifyIntent], Live-Befund 2026-07-15) —
     * strukturell brain-frei UND HA-gate-frei (eine ehrliche „welchen Raum?"-
     * Nachfrage ist keine Tat, kein State-Change). Der [ToolCall] trägt die
     * fertige, sprechbare Frage bereits in seiner `data` (der Classifier baut sie
     * — er kennt Sprache + Areas, s. [DeterministicToolIntentClassifier]-KDoc
     * Schritt (5)). Leer ⇒ warmer Fallback (never-silent).
     */
    private fun clarifyTurn(call: ToolCall, decision: RouteDecision): Flux<ChatEvent> {
        val phrase = call.data[AreaClarifyIntent.PHRASE] as? String ?: ""
        return warmDirectAnswer(decision.provider.name, decision.category.name, phrase.ifBlank { warmFallback() })
    }

    /**
     * **Deterministische Anaphern-Auflösung** (pro Sprecher) eines roomless Licht-/
     * Klima-Befehls — die Essenz des Live-Befunds „Wohnzimmerlicht an" (klappt) →
     * „schalt das Licht wieder aus" (kein Raum ⇒ Hoshi wusste nicht welches Licht).
     *
     * Konservativ + byte-erhaltend (passthrough, außer ein klarer Anaphern-Fall):
     *  - [call]==null ⇒ unverändert (⇒ Brain, wie ohne eindeutigen Befehl).
     *  - speakerId-los / anonym ⇒ unverändert durch (kein Store, kein Fallback) —
     *    der bestehende Pfad inkl. Classifier-Default-Area bleibt byte-identisch.
     *  - kein Last-Area-fähiger Befehl (kein schreibender Licht/Klima-Call mit area_id)
     *    ⇒ unverändert.
     *  - genannter Raum ([ToolAreas.mentionsRoom]) ⇒ der explizite Raum gewinnt (unverändert).
     *  - roomless + echter Sprecher MIT Historie ⇒ `area_id` := zuletzt geschaltete Area.
     *  - roomless + echter Sprecher OHNE Historie ⇒ `null` (⇒ Brain; NICHT raten).
     */
    private fun resolveToolCall(call: ToolCall?, text: String, speakerId: String?): ToolCall? {
        if (call == null) return null
        if (LastAreaPort.isAnonymous(speakerId)) return call
        if (!isLastAreaEligible(call) || ToolAreas.mentionsRoom(text)) return call
        val remembered = lastArea.lastArea(speakerId!!) ?: return null
        return call.copy(data = call.data + ("area_id" to remembered))
    }

    /**
     * Ein Last-Area-fähiger Befehl: ein SCHREIBENDER Licht-/Klima-Call mit
     * `area_id`-Targeting (Szenen/Reads/entity-getargetete Calls bleiben unberührt).
     */
    private fun isLastAreaEligible(call: ToolCall): Boolean =
        !call.read &&
            (call.domain == "light" || call.domain == "climate") &&
            call.data.containsKey("area_id")

    /**
     * Merkt die geschaltete Area als „zuletzt aktiv" für DIESEN Sprecher — NUR nach
     * einem gegateten (Grant) schreibenden Licht-/Klima-Call mit `area_id`. Anonyme
     * Sprecher / Calls ohne `area_id` ⇒ no-op (zusätzlich schützt der Store). Speist
     * die Anaphern-Auflösung ([resolveToolCall]) des NÄCHSTEN roomless Turns.
     */
    private fun rememberLastArea(call: ToolCall, speakerId: String?) {
        if (speakerId == null || !isLastAreaEligible(call)) return
        (call.data["area_id"] as? String)?.let { area -> lastArea.remember(speakerId, area) }
    }

    /**
     * Der **Lese**-Tool-Pfad — brain-frei UND gate-frei (reiner Read, keine schreibende
     * Tat). Ruft [tools].execute mit dem Read-[ToolCall] (der Executor liest den HA-State,
     * z.B. die Ist-Temperatur einer Area) und streamt die warme Antwort. Best-effort:
     * der Executor (HaToolPort) wirft NIE — kein Wert/Fehler ⇒ ehrliche Phrase.
     *
     * (P0 Event-Loop-Fix) [tools].execute ist beim realen [de.hoshi.adapters.ha.HaToolPort]
     * ein SYNCHRONER JDK-HttpClient-Call (Read/Readback gegen HA) → niemals auf dem
     * Reactor-Event-Loop. Auf boundedElastic auslagern (Bestands-Muster wie
     * EpisodicMemoryAdapter/EmbeddingRouterRefiner). Mit dem Default-Placeholder ist
     * execute nicht-blockierend ⇒ byte-neutral (nur ein zusätzlicher Scheduler-Hop).
     */
    private fun toolReadTurn(call: ToolCall, decision: RouteDecision): Flux<ChatEvent> =
        Mono.fromCallable {
            when (val result = tools.execute(call)) {
                is ToolResult.Ok -> result.phrase
                is ToolResult.NoEffect -> result.phrase
                is ToolResult.Failed -> result.phrase
            }
        }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany { phrase ->
                warmDirectAnswer(decision.provider.name, decision.category.name, phrase.ifBlank { warmFallback() })
            }

    /**
     * Der **Schreib**-Tool-Pfad — Tat gaten ([capability]), bei Grant den Executor mit
     * der NORMALISIERTEN data rufen ([tools]) und die warme Quittung als Direkt-Antwort
     * streamen; bei Deny die warme Absage. Ruft den [brain] NIE (0 Brain-Calls/Turn).
     *
     * (P0 Event-Loop-Fix) Der Grant-Zweig macht beim realen [de.hoshi.adapters.ha.HaToolPort]
     * blockierendes I/O ([tools].execute = synchroner HA-Call inkl. Settle-Poll, plus
     * der ggf. blockierende [rememberLastArea]-Store) → beides off der Event-Loop auf
     * boundedElastic (Bestands-Muster). [capability].check bleibt am Aufrufer-Thread
     * (reine, schnelle In-Memory-Gate-Prüfung). Mit dem Default-Placeholder/Store
     * nicht-blockierend ⇒ byte-neutral (nur ein Scheduler-Hop).
     */
    private fun writeToolTurn(call: ToolCall, decision: RouteDecision, speakerId: String?): Flux<ChatEvent> =
        when (val gate = capability.check(call)) {
            is GateDecision.Grant -> {
                val executed = call.copy(data = gate.normalizedData)
                Mono.fromCallable {
                    val phrase = when (val result = tools.execute(executed)) {
                        is ToolResult.Ok -> result.phrase
                        is ToolResult.NoEffect -> result.phrase
                        is ToolResult.Failed -> result.phrase
                    }
                    // Nach der gegateten Schreib-Tat die geschaltete Area als „zuletzt
                    // aktiv" merken (pro Sprecher) → speist die Anaphern-Auflösung.
                    rememberLastArea(executed, speakerId)
                    phrase
                }
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany { phrase ->
                        warmDirectAnswer(decision.provider.name, decision.category.name, phrase)
                    }
            }
            is GateDecision.Deny ->
                warmDirectAnswer(decision.provider.name, decision.category.name, gate.phrase.ifBlank { warmFallback() })
        }

    /**
     * Deckelt die Persona-Temperatur fuer eine [RouteCategory.FACT_SHORT]-Route auf
     * <= [FACT_TEMP_CEILING] — NUR wenn der [factLowTemp]-Clamp an ist. Fuer jede
     * Nicht-FACT-Route UND bei OFF Identitaet (`personaTemp` unveraendert). Pure,
     * deterministisch testbar; query-seitig, kein Prompt-Eingriff.
     */
    private fun clampFactTemperature(personaTemp: Double, category: RouteCategory): Double =
        if (factLowTemp && category == RouteCategory.FACT_SHORT) {
            minOf(personaTemp, FACT_TEMP_CEILING)
        } else {
            personaTemp
        }

    /**
     * **Die EINE Rekonstruktions-Naht des räumlichen Gedächtnisses (S1+S2):**
     * bestimmt die history, die an `windowHistory` (und damit an den Brain) geht,
     * plus die Segment-Diary-Felder fürs [ChatEvent.Start].
     *
     *  - Client-history NICHT leer (Text-UI schickt seit d4e3c9f den Tab-Verlauf)
     *    ⇒ der CLIENT ist im selben Tab autoritativ — unverändert durch, die
     *    Server-Session wird NICHT einmal gelesen (Diary-Felder neutral).
     *  - Client-history LEER (Voice, zweites Gerät, Page-Reload) + Sprecher bekannt
     *    ⇒ Verlauf aus der personen-gekeyten Server-Session rekonstruieren —
     *    S2-Naht [WorkingSessionPort.readSegment]: das AKTUELLE Themen-Segment;
     *    `ctx.text` reist mit, damit eine Reset-Phrase („ganz was anderes: …")
     *    schon DIESEN Turn frisch startet.
     *  - Kein Sprecher-Kontext ⇒ unverändert (leer) — kein Schlüssel, kein Load.
     *
     * **P1-Privacy-Ergänzung (schließt die Restlücke aus Commit `bc64190`):** die
     * tatsächlich für den Recall verwendete `speakerId` kommt aus [SpeakerTrust.resolve]
     * statt direkt aus `ctx.speaker?.speakerId` — [speakerTrustEnforced]`=false` (Default)
     * ist byte-neutral (exakt der bisherige Claim/Fallback); `=true` lässt einen zu
     * unsicheren/fremden Claim NICHT mehr fremden WorkingSession-Kontext lesen, sondern
     * kollabiert auf [SpeakerTrust.GUEST_SPEAKER_ID] — dieselbe Funktion/Schwelle/
     * Gast-Semantik wie der Entity-/Episodic-Recall in [TurnPromptAssembler] und der
     * Write-Pfad in `de.hoshi.web.ChatStreamController.rememberAfter` (siehe
     * [SpeakerTrust]-KDoc für Bedrohungsmodell + Design-Entscheidungen).
     *
     * `windowHistory(...)` bleibt UNVERÄNDERT der defensive Cap obendrauf. Mit dem
     * Default [WorkingSessionPort.NONE] (Flag OFF) ist das Ergebnis byte-identisch
     * zu `ctx.request.history` — leere Liste bleibt leer, nicht-leere identisch.
     */
    private fun effectiveSession(ctx: TurnPrompt): WorkingSessionSegment {
        val clientHistory = ctx.request.history
        if (clientHistory.isNotEmpty()) return WorkingSessionSegment(turns = clientHistory)
        // Vertrauens-Gate (P1): OFF ⇒ exakt der bisherige Claim/Kurzschluss (byte-neutral);
        // ON ⇒ SpeakerTrust entscheidet Claim-vs-Gast — dieselbe Funktion wie beim Entity-/
        // Episodic-Recall + beim Write (siehe SpeakerTrust-KDoc). Never-throw: resolve()
        // wirft nie, ein GUEST-Ergebnis liest wie jede andere Id (WorkingSessionAdapter
        // haertet "gast" ohnehin auf leer, s. dessen isGuest-Guard).
        val speakerId = SpeakerTrust.resolve(ctx.speaker, speakerTrustEnforced, speakerTrustThreshold)?.speakerId
            ?: return WorkingSessionSegment(turns = clientHistory)
        return workingSession.readSegment(speakerId, ctx.text)
    }

    /**
     * **Brain-Pfad mit vorgelagertem Verbatim-Replay-Off-Ramp (Andi-Fix 2026-07-16).**
     *
     * Bei ausgeschaltetem Flag ([verbatimReplayEnabled]`=false`, Default) ODER einem
     * Nicht-LOCAL-Provider IST dieser Turn EXAKT [brainStreamTurn] (byte-neutral —
     * kein [lookupReplay]-Call, kein Scheduler-Hop). Der Provider-Gate spiegelt den
     * Grounding-Gate im [TurnPromptAssembler] (Grounding läuft nur für LOCAL) — so
     * greift der Replay GENAU dort, wo heute ein Cache-HINTERGRUND-Block assembliert
     * und vom Brain paraphrasiert würde, und nirgends sonst.
     *
     * Bei Flag AN + LOCAL: EIN [LookupReplayPort.bestNote]-Call (Datei-I/O, darum auf
     * `boundedElastic` — P0-Event-Loop-Disziplin, exakt wie honesty/tools/grounding).
     *  - **Sicherer Treffer** (Overlap ÜBER der Provider-Schwelle, nicht abgelaufen,
     *    Wissens-Kategorie) ⇒ [verbatimReplayTurn]: die [LookupNote] WÖRTLICH,
     *    brain-frei (0 Brain-Calls) — statt der bisherigen 4B-Paraphrase.
     *  - **Kein Treffer** (`null` ⇒ leeres Mono) ⇒ `switchIfEmpty` → [brainStreamTurn]:
     *    der heutige Grounding-Injektions-Pfad, byte-identisch (unter der Schwelle
     *    liefert derselbe Provider als [GroundingPort] ohnehin einen leeren Block).
     *
     * Ein (per Vertrag nie geworfener) Fehler des Replay-Calls propagiert an den
     * `onErrorResume` in [routedTurn] (warme FEHLER-Antwort) — Never-Silent gewahrt.
     */
    private fun brainTurn(ctx: TurnPrompt, decision: RouteDecision): Flux<ChatEvent> {
        if (!verbatimReplayEnabled || decision.provider != RouteProvider.LOCAL) {
            return brainStreamTurn(ctx, decision)
        }
        // Mono.fromCallable behandelt `null` (kein sicherer Treffer) als LEERES Mono —
        // die `!!` ist damit sicher (die Lambda läuft NUR bei non-null Emission), und
        // switchIfEmpty greift genau im null-Fall (heutiger Pfad). Muster: der
        // NachgeschlagenGroundingProvider selbst (`bestMatch` → `buildBlock(note!!)`).
        return Mono.fromCallable { lookupReplay.bestNote(ctx.text, decision.category) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany { note -> verbatimReplayTurn(note!!, decision, ctx.language) }
            .switchIfEmpty(Flux.defer { brainStreamTurn(ctx, decision) })
    }

    /**
     * **Brain-freies Verbatim-Replay eines sicheren Cache-Treffers (Andi-Fix
     * 2026-07-16)** — strukturell **brain-frei** (0 Brain-Calls; der max-1-Brain-Call-
     * Vertrag bleibt trivial erfüllt): kurze warme Rahmung + [LookupNote.answer]
     * **WÖRTLICH** + Stand-Datum + Quelle, deterministisch aus der Notiz.
     *
     * Muster [escalationTurn]/[warmDirectAnswer]: Start (`model=policy`) → EINE warme
     * [ChatEvent.TextDelta] → Done. Das [ChatEvent.Start] trägt EHRLICH `cacheHit=true`
     * + `grounded=true` (ein Cache-Hit IST gedeckt) + die [LookupNote.source] als
     * `escalationSource` (Turn↔Note-Verknüpfung, dieselbe Diary-Spalte wie der
     * Brain-Cache-Hit-Pfad), `escalated` bleibt `false` (kein Netz-Call).
     *
     * **Marker-Hygiene selbst sichergestellt** ([stripContractMarkers] auf der
     * Answer in [verbatimReplayPhrase]): dieser Pfad läuft NICHT durch die
     * Brain-Prosa→TextDelta-Naht, die sonst die WikiNumberContract-Guillemets
     * strippt — exakt wie [escalationTurn] es für seine Verbatim-Antwort tut.
     */
    private fun verbatimReplayTurn(note: LookupNote, decision: RouteDecision, language: Language): Flux<ChatEvent> {
        val provider = decision.provider.name
        return Flux.just(
            ChatEvent.Start(
                provider = provider,
                category = decision.category.name,
                model = "policy",
                grounded = true,
                cacheHit = true,
                escalationSource = note.source,
            ),
            ChatEvent.TextDelta(verbatimReplayPhrase(note, language), provider = provider),
            ChatEvent.Done(provider = provider),
        )
    }

    /**
     * Baut die Replay-Phrase: warme Rahmung ([verbatimReplayFrame], mit dem Stand-
     * Datum aus [LookupNote.ts], Europe/Berlin — dasselbe Format, das der Provider im
     * HINTERGRUND-Block rendert) + die [LookupNote.answer] **WÖRTLICH** (nur
     * [stripContractMarkers], identisch zur Erst-Antwort in [escalationTurn], KEINE
     * Paraphrase) + Quellen-Nachsatz ([escalationSourceNote]). DE/EN.
     */
    private fun verbatimReplayPhrase(note: LookupNote, language: Language): String {
        val dateLabel = REPLAY_DATE_FORMAT.format(note.ts)
        val answer = stripContractMarkers(note.answer)
        return verbatimReplayFrame(dateLabel, language) + answer + " " + escalationSourceNote(note.source, language)
    }

    /**
     * Der eigentliche Brain-Pfad (unverändert aus dem bisherigen `brainTurn`
     * extrahiert): Persona-System-Prompt schichten, Grounding/Episodic parallel
     * holen, dann GENAU EINMAL `brain.streamChat`, umhüllt vom Never-Silent-Vertrag.
     * Siehe [brainTurn] für den vorgelagerten Replay-Off-Ramp.
     */
    private fun brainStreamTurn(ctx: TurnPrompt, decision: RouteDecision): Flux<ChatEvent> {
        val speaker = ctx.speaker ?: SpeakerContext()
        // Working-Session (S1+S2): history-Quelle + Segment-Diary-Felder GENAU EINMAL
        // bestimmen — speist den Brain-Call UND das ehrliche Start-Event.
        val session = effectiveSession(ctx)
        // Sprache aus dem Turn an die Persona durchstechen → der System-Prompt
        // instruiert die Antwortsprache explizit (Multilingual-Sprachsteuerung).
        val baseSystemPrompt = promptAssembler.baseSystemPrompt(speaker, ctx.language, ctx.persona)
        return promptAssembler.assemble(ctx, decision, baseSystemPrompt, followBlock = "")
            .flatMapMany { assembled ->
                // ── Anti-Konfabulations-Wand: FACT_SHORT ohne gedecktes Grounding ⇒ den
                //    Brain NICHT freestylen lassen (er erfindet dann Falsches), sondern
                //    ehrlich deflekten. Der Query-Text reist mit: fehl-geroutetes
                //    Smalltalk („Kurz: alles ok bei dir?") sieht nicht wie eine
                //    Wissensfrage aus und wird NIE kalt deflektet (Wärme-Leitplanke).
                //    OFF (Default) ⇒ Proceed ⇒ byte-neutral. ──
                // „Hat Grounding gedeckt?" GENAU EINMAL bestimmen (Instanz-Sicht: trägt
                // den optionalen strict-Modus; DISABLED/lax ⇒ byte-identisch zur
                // Companion-Funktion) — speist die Wand UND ehrlich das Start-Event
                // ([ChatEvent.Start.grounded] → Diary `groundingUsed`).
                val grounded = factCoverage.groundingCovered(decision.provider, assembled.groundBlock, ctx.text)
                // S4 Diary: „ist DIES ein Cache-Hit aus dem Nachgeschlagen-Store?" — reiner
                // String-Check des Herkunfts-Markers (geteilte Konstante, s. Companion-KDoc)
                // im bereits assemblierten groundBlock. Trägt der Block den Marker NICHT
                // (wiki/weather/kein Treffer), bleibt cacheHit ehrlich false — byte-neutral,
                // wenn die S3-Cache-Scheibe nie einen Treffer lieferte.
                val cacheHit = assembled.groundBlock.contains(TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER)
                // H2 Diary: bei einem Cache-Hit steckt die Quelle bereits im gerenderten
                // groundBlock (NachgeschlagenGroundingProvider.buildBlock schreibt
                // "Quelle: <note.source>."). Best-effort String-Parse statt eines
                // zweiten Lese-Pfads auf die Notiz-Datei — kein Verhalten hängt daran.
                val cacheHitSource = if (cacheHit) parseCacheHitSource(assembled.groundBlock) else null
                if (factCoverage.decide(
                        decision.category,
                        grounded,
                        query = ctx.text,
                    ) == FactCoverageGate.Decision.Deflect
                ) {
                    // ── Naht A (Extended Think S2): der Deflect bekommt einen AUSGANG.
                    //    AUTOMATISCH ⇒ direkt eskalieren statt deflekten (kein Brain-Call —
                    //    Kosten UND Konfabulation gespart). ERST_FRAGEN/AUS ⇒ heutige
                    //    Deflection-Phrase; das Angebot wird gemerkt (offer), damit ein
                    //    „ja" im Folge-Turn einlösen kann — bei AUS löst Naht B es mit dem
                    //    ehrlichen Setting-Hinweis ein, nie mit einem Call. Default
                    //    (AUS-Supplier + NONE-Store) ⇒ offer ist no-op ⇒ die emittierten
                    //    Events sind byte-identisch zu heute (Test).
                    if (escalationMode() == EscalationMode.AUTOMATISCH) {
                        return@flatMapMany escalationTurn(
                            ctx.text,
                            ctx.language,
                            decision.provider.name,
                            decision.category.name,
                        )
                    }
                    pendingLookup.offer(pendingKey(ctx), PendingLookup(query = ctx.text, language = ctx.language))
                    return@flatMapMany warmDirectAnswer(
                        decision.provider.name,
                        decision.category.name,
                        FactCoverageGate.deflection(ctx.language),
                        // Perf-Diary: das Grounding LIEF (und fand nichts Deckendes) —
                        // seine ehrlich gemessene Dauer reist auch am Deflect-Done mit.
                        stageTimings = assembled.groundingMs?.let { ChatEvent.StageTimings(groundingMs = it) },
                    )
                }
                // ── Perf-Diary: Brain-TTFT — Brain-Call-Start (Subscribe) → ERSTE
                //    TextDelta. −1 = nie eine Delta gesehen ⇒ null (nie ein
                //    erfundenes 0); ein gemessener Wert reist über [neverSilent]
                //    im Done-stageTimings an den Rand. ──
                val brainT0 = AtomicLong(0)
                val brainTtftMs = AtomicLong(-1)
                // ── Antwort-Entropie (S1, additiv — nur Messung): laufende Summe +
                //    Zähler der Token-Surprisals (−logprob) statt einer Liste — kein
                //    Speicher-Wachstum, egal wie lang der Turn wird. Deltas OHNE
                //    logprob (Flag OFF / ungepatchter Brain) zählen NICHT mit ⇒
                //    count==0 ⇒ answerEntropy null ⇒ Done byte-identisch zu heute.
                //    Kein Verhalten hängt am Wert (Abstain = S2 nach Kalibrierung). ──
                val surprisalSum = DoubleAdder()
                val surprisalCount = AtomicLong(0)
                // ── Naht D (Brain-Abstain-Pending): die volle Antwort mitschreiben, um
                //    am Stream-Ende ein ehrliches Passen zu erkennen. NUR bei aktiver
                //    Naht ([lookupIntentEnabled]) — sonst `null` ⇒ kein Puffer, kein
                //    `offer` ⇒ byte-neutral. Reactor serialisiert onNext↔doOnComplete
                //    pro Subscription (happens-before), der einfache StringBuilder ist
                //    hier sicher. ──
                val answerBuf: StringBuilder? = if (lookupIntentEnabled) StringBuilder() else null
                // ── Naht D Hörbarkeit (Andi-Auftrag 2026-07-20): merkt, ob DIESER Turn
                //    GENAU JETZT ein Abstain-Pending registriert hat — gesetzt im
                //    `doOnComplete` unten (VOR der `neverSilent`-Auswertung, happens-
                //    before wie [answerBuf]). `false` bleibt der Default (Flag OFF /
                //    kein Abstain) ⇒ [neverSilent] hängt nichts an ⇒ byte-neutral. ──
                val offeredPendingAudibly = AtomicBoolean(false)
                val brainStream: Flux<ChatEvent> = brain.streamChat(
                    prompt = ctx.text,
                    systemPrompt = assembled.finalPrompt,
                    // Working-Session-Rekonstruktion (S1+S2, [effectiveSession]) + Server-side
                    // Working-Memory-Window: kappt die History defensiv auf die letzten
                    // N Turns (Default 0 = kein Cap; NONE + Default ⇒ byte-neutral).
                    history = promptAssembler.windowHistory(session.turns),
                    // Persona-Default-Stimmung → Temperatur (STANDARD = heutige currentEmotionEnum,
                    // byte-neutral); Warmth v2 OFF (NONE) lässt den Wert unverändert.
                    temperature = mood.adjust(
                        clampFactTemperature(persona.temperatureFor(persona.moodFor(ctx.persona)), decision.category),
                        ctx.text,
                    ),
                    sessionId = ctx.chatId,
                    userId = speaker.speakerId,
                ).doOnSubscribe { brainT0.set(nanoTime()) }.map { delta ->
                    brainTtftMs.compareAndSet(-1, (nanoTime() - brainT0.get()) / 1_000_000)
                    // Antwort-Entropie (S1): nur GEMESSENE Logprobs mitteln — null-
                    // tolerante Naht, der Normalfall (kein logprob) kostet ein let.
                    delta.logprob?.let { lp ->
                        surprisalSum.add(-lp)
                        surprisalCount.incrementAndGet()
                    }
                    // Naht D: rohe Antwort mitschreiben (nur bei aktiver Naht ⇒ answerBuf != null).
                    answerBuf?.append(delta.text)
                    // Guillemet-Strip an der EINEN Brain-Prosa→TextDelta-Naht — deckt
                    // damit FE (SSE/WS) UND TTS, die alle downstream an handle() hängen.
                    ChatEvent.TextDelta(stripContractMarkers(delta.text), provider = decision.provider.name) as ChatEvent
                }.doOnComplete {
                    // Naht D: NACH allen Deltas (Antwort-Bytes unverändert), VOR dem Done —
                    // bei ehrlichem Passen ein Nachschlag-Angebot registrieren. `null`-Puffer
                    // (Flag OFF) ⇒ Lambda ist ein reiner Pass-through-No-op ⇒ byte-neutral.
                    val offered = answerBuf?.let { maybeOfferAbstainPending(ctx, decision, it.toString()) } ?: false
                    // Naht D Hörbarkeit: HÖRBAR wird das Angebot NUR, wenn der Escalation-
                    // Modus Nachfragen überhaupt erlaubt (NICHT AUS) — bei AUS bliebe ein
                    // „ja" ohnehin folgenlos ([redeemLookup] landet dann im ehrlichen
                    // Setting-Hinweis, s. dessen KDoc), ein lautes Angebot wäre da
                    // irreführend. Das stille `offer` selbst (s.o.) bleibt UNABHÄNGIG
                    // davon bestehen — nur das Aussprechen hängt am Modus.
                    offeredPendingAudibly.set(offered && escalationMode() != EscalationMode.AUS)
                }
                // Timings-SUPPLIER (erst bei Done-Emission gelesen — dann sind TTFT-
                // und Entropie-Messung sicher passiert): alle null ⇒ null ⇒ Done
                // byte-identisch.
                val stageTimings: () -> ChatEvent.StageTimings? = {
                    val ttft = brainTtftMs.get().takeIf { it >= 0 }
                    val entropy = surprisalCount.get().takeIf { it > 0 }?.let { surprisalSum.sum() / it }
                    if (assembled.groundingMs == null && ttft == null && entropy == null) null
                    else ChatEvent.StageTimings(
                        groundingMs = assembled.groundingMs,
                        brainTtftMs = ttft,
                        answerEntropy = entropy,
                    )
                }
                neverSilent(
                    brainStream, decision, ctx.language, grounded, cacheHit, session, stageTimings, cacheHitSource,
                    abstainOffer = offeredPendingAudibly::get,
                )
            }
    }

    /**
     * Der **agentische** Brain-Pfad (single-turn, sicher) — der Brain darf ein Tool
     * *vorschlagen*, aber der Kernel entscheidet, ob es geschieht. Vier strukturell
     * verankerte Sicherheits-Invarianten (der Tool-Pfad steuert echte Geräte, der
     * Brain ist UNTRUSTED):
     *
     *  1. **Der Kernel gatet ALLES.** Ein vom Brain emittierter Tool-Call wird NIE
     *     ausgeführt, ohne dass [capability].check ein [GateDecision.Grant] liefert.
     *     Deny ⇒ warme Absage, KEINE Ausführung. Selbst ein halluzinierter/
     *     manipulierter Tool-Call kann so keine unerlaubte Tat auslösen.
     *  2. **Roh-Ausgabe wird NIE gestreamt.** Unter PATH B (`tool_grammar=true`) ist
     *     die Brain-Ausgabe ein einzelnes JSON-Objekt `{tool,args}` — KEINE sprechbare
     *     Prosa. Die rohe JSON geht nie als [ChatEvent.TextDelta] raus; der Caller
     *     spricht ausschließlich aufgelöste Quittungen/Absagen ([dispatchAgentic]).
     *     Defektes/kein JSON ⇒ [ToolGrammarParser.Result.Malformed] ⇒ warme Absage
     *     statt Roh-Leak (Residue-Guard).
     *  3. **Max. 1 Brain-Call/Turn.** Der Tool-Grammar-Call IST der eine Call;
     *     die Tool-Ausführung danach ist brain-frei (kein zweiter `streamChat`).
     *  4. **flag default-OFF.** Dieser Pfad wird nur betreten, wenn [agenticTools]
     *     gesetzt ist (sonst [brainTurn]); `HOSHI_AGENTIC_TOOLS_ENABLED=false` ⇒
     *     byte-neutral (kein `tools`/`tool_grammar` im Request-Body).
     *
     * Ablauf (PATH B): Prompt wie in [brainTurn] assemblen → EIN
     * `brain.streamChat(tools=…, toolGrammar=true)` → den (kurzen) Antwort-Stream zu
     * vollständigem Text SAMMELN (erst vollständig parsen, dann handeln — Sicherheit
     * vor Streaming-Eleganz) → [dispatchAgentic]. Jeder Fehler (Assembly/Brain) endet
     * never-silent in der warmen FEHLER-Phrase.
     */
    private fun agenticBrainTurn(ctx: TurnPrompt, decision: RouteDecision): Flux<ChatEvent> {
        val registry = agenticTools ?: return brainTurn(ctx, decision)
        val speaker = ctx.speaker ?: SpeakerContext()
        val baseSystemPrompt = promptAssembler.baseSystemPrompt(speaker, ctx.language, ctx.persona)
        return promptAssembler.assemble(ctx, decision, baseSystemPrompt, followBlock = "")
            .flatMapMany { assembled ->
                // (K1, HONESTY) Tool-Mode: der Brain ist UNTRUSTED und behauptet bei
                // indirekter Phrasierung sonst Effekte im TEXT, ohne ein Tool zu rufen
                // („Küche brennt" o.ä.) → Hoshi lügt. Die sprach-abhängige Direktive
                // zwingt: Aktion ⇒ Tool wirklich rufen, NIE einen Effekt behaupten.
                val toolModePrompt = assembled.finalPrompt + "\n\n" + toolModeDirective(ctx.language)
                // (3) GENAU EIN Brain-Call/Turn — mit den agentischen Tool-Schemas
                //     UND PATH B (`toolGrammar=true`): der Brain wird STRUKTURELL auf ein
                //     einzelnes JSON-Objekt `{tool,args}` gezwungen (Logits-Enforcer), das
                //     [dispatchAgentic] via [ToolGrammarParser] parst — keine Roh-Prosa.
                brain.streamChat(
                    prompt = ctx.text,
                    systemPrompt = toolModePrompt,
                    // Working-Session-Rekonstruktion + Window (s. brainTurn): byte-neutral bei NONE.
                    // (Der agentische Start läuft über warmDirectAnswer — Segment-Diary-Felder
                    // bleiben dort ehrlich auf den Defaults; Diary deckt den Prosa-Brain-Pfad.)
                    history = promptAssembler.windowHistory(effectiveSession(ctx).turns),
                    // Persona-Default-Stimmung → Temperatur (STANDARD = heutige currentEmotionEnum,
                    // byte-neutral); Warmth v2 OFF (NONE) lässt den Wert unverändert.
                    temperature = mood.adjust(
                        clampFactTemperature(persona.temperatureFor(persona.moodFor(ctx.persona)), decision.category),
                        ctx.text,
                    ),
                    sessionId = ctx.chatId,
                    userId = speaker.speakerId,
                    tools = registry.schemas(),
                    toolGrammar = true,
                )
                    .map { it.text }
                    // (H1) Schutz gegen unbegrenztes Sammeln: collectList puffert ALLES,
                    // ein entlaufener/bösartiger Brain-Stream könnte den Speicher sonst
                    // unbegrenzt füllen. take() kappt die Delta-Menge, timeout die Zeit;
                    // beide enden im Fehlerfall über die äußere onErrorResume in der warmen
                    // FEHLER-Phrase (kein Throw nach außen — Never-Silent bleibt gewahrt).
                    .take(MAX_AGENTIC_DELTAS)
                    .timeout(AGENTIC_COLLECT_TIMEOUT)
                    .collectList() // erst vollständig sammeln, dann erst entscheiden
                    .flatMapMany { parts ->
                        dispatchAgentic(parts.joinToString(""), decision, ctx.language, registry)
                    }
            }
            // (Never-Silent) Brain-/Assembly-Fehler VOR jeder Ausgabe → warme FEHLER-Phrase.
            .onErrorResume { _ ->
                warmDirectAnswer(decision.provider.name, decision.category.name, errorFallback(ctx.language))
            }
    }

    /**
     * Wertet den gesammelten Brain-Text aus (brain-frei) und entscheidet sicher.
     * Unter PATH B ist die Ausgabe ein einzelnes JSON-Objekt `{tool,args}`, das der
     * [ToolGrammarParser] dreiwertig auflöst — die rohe JSON wird NIE gestreamt:
     *
     *  - **[ToolGrammarParser.Result.Call]** (der Brain wählte ein Tool) → [registry].resolve:
     *      - `null` (unbekanntes Tool/Raum/Pflicht-Arg) → warme Absage (Inv. 1: nichts ausgeführt).
     *      - [ToolCall] → [capability].check (Inv. 1, der Kernel gatet ALLES):
     *          - [GateDecision.Grant] → [tools].execute mit der NORMALISIERTEN data →
     *            warme Quittung der Tat (HaToolPort-Readback).
     *          - [GateDecision.Deny] → warme Absage (deny.phrase), KEINE Ausführung.
     *  - **[ToolGrammarParser.Result.None]** (`tool=="none"`, der Brain wählte BEWUSST
     *    keine Tat) → ehrliche, kurze Absage ([agenticNone]). KEINE Tat, KEIN Fake-Confirm.
     *  - **[ToolGrammarParser.Result.Malformed]** (defekt/kein JSON/fehlendes `tool`) →
     *    ehrlicher Fallback ([agenticRefusal]). NIE die rohe JSON streamen (Residue-Guard).
     *
     * Ruft den [brain] NIE (single-turn) — die Tat-Ausführung ist brain-frei.
     */
    private fun dispatchAgentic(
        fullText: String,
        decision: RouteDecision,
        language: Language,
        registry: AgenticToolRegistry,
    ): Flux<ChatEvent> {
        val provider = decision.provider.name
        val category = decision.category.name

        return when (val result = ToolGrammarParser.parse(fullText)) {
            // (Call) Der Brain hat ein Tool gewählt → resolve → Kernel-Gate → ggf. Tat.
            is ToolGrammarParser.Result.Call -> {
                // resolve→null: unbekanntes/unerlaubtes Tool/Raum → warme Absage, NICHTS gaten/tun.
                val call = registry.resolve(result.parsed)
                if (call == null) {
                    warmDirectAnswer(provider, category, agenticRefusal(language))
                } else {
                    // (Inv. 1) Der Kernel gatet ALLES — Ausführung NUR auf Grant.
                    when (val gate = capability.check(call)) {
                        // (P0 Event-Loop-Fix) Dieser Zweig läuft NACH dem Brain-collectList
                        // auf einem Reactor-Thread; [tools].execute ist beim realen HaToolPort
                        // blockierendes I/O → auf boundedElastic auslagern (Bestands-Muster).
                        is GateDecision.Grant ->
                            Mono.fromCallable {
                                when (val r = tools.execute(call.copy(data = gate.normalizedData))) {
                                    is ToolResult.Ok -> r.phrase
                                    is ToolResult.NoEffect -> r.phrase
                                    is ToolResult.Failed -> r.phrase
                                }
                            }
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany { phrase -> warmDirectAnswer(provider, category, phrase) }
                        // Deny ⇒ warme Absage, der Executor wird NIE gerufen.
                        is GateDecision.Deny ->
                            warmDirectAnswer(provider, category, gate.phrase.ifBlank { agenticRefusal(language) })
                    }
                }
            }
            // (None) Bewusstes „kein Tool" → ehrlich ablehnen, KEINE Tat, kein Fake-Confirm.
            ToolGrammarParser.Result.None ->
                warmDirectAnswer(provider, category, agenticNone(language))
            // (Malformed) Defekt/kein JSON → ehrlicher Fallback; NIE die rohe JSON streamen.
            ToolGrammarParser.Result.Malformed ->
                warmDirectAnswer(provider, category, agenticRefusal(language))
        }
    }

    /**
     * Never-Silent-Hülle (portiert aus 0.5 `NeverSilentStage.wrap`, auf den
     * Text-Turn reduziert): EIN [ChatEvent.Start] voran, dann der Brain-Stream;
     *  - floss Text → sauberes [ChatEvent.Done].
     *  - leerer Stream → warme **LEER**-Phrase + Done („nochmal sagen" hilft).
     *  - Fehler VOR Text → warme **FEHLER**-Phrase + Done („was hängt im Hintergrund");
     *    KEIN separates Error-Event, sonst Doppel-Render — Andi-Befund 0.5.
     *  - Fehler NACH Text → sauberes Done (kein Doppel-Audio/-Text).
     *
     * 0.5-Essenz: warm DIFFERENZIEREN nach dem Fehler-Modus, den der Orchestrator
     * schon kennt — ein leerer Brain-Stream ist etwas anderes als ein Brain-FEHLER.
     * Die Phrasen sind sprach-abhängig ([language] aus dem Turn) und PURE
     * ([emptyFallback]/[errorFallback], deterministisch testbar).
     *
     * [grounded] = „hat Grounding diesen Turn gedeckt?" ([FactCoverageGate]-Sicht,
     * am Konsum-Punkt in [brainTurn] bestimmt) — reist EHRLICH im [ChatEvent.Start]
     * an den Rand (Diary `groundingUsed`), statt dort hardcoded false zu stehen.
     *
     * [session] (S2 räumliches Gedächtnis, additiv — Muster [grounded]): die
     * Working-Session-Grenz-Entscheidung des Turns ([effectiveSession]) — reist als
     * `segmentReset/resetReason/segmentLenTurns` im [ChatEvent.Start] an den Rand
     * (Diary, S4-Kalibrier-Basis). `null` (Nicht-Brain-Pfade) ⇒ Defaults.
     *
     * [cacheHit] (S4 Diary, additiv — Muster [grounded]): deckte die S3-Cache-
     * Scheibe ([de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider]) den
     * Grounding-Block dieses Turns? Reist ehrlich im [ChatEvent.Start] an den Rand
     * (Diary `cacheHit`). Default `false` ⇒ byte-neutral ohne S3-Treffer.
     *
     * [stageTimings] (Perf-Diary, additiv — Muster [grounded]): SUPPLIER der
     * gemessenen Stage-Latenzen des Brain-Pfads (grounding/brainTtft), erst bei
     * der Done-Emission gelesen (alle Done-Pfade sind deferred — die TTFT-Messung
     * ist dann sicher passiert). Default `{ null }` ⇒ Done byte-identisch.
     *
     * [cacheHitSource] (H2 Diary, additiv — Muster [cacheHit], nur bei
     * `cacheHit=true` gefüllt): die aus dem bereits assemblierten groundBlock
     * geparste `Quelle:`-Zeile der getroffenen [LookupNote] — bekannt VOR dem
     * Brain-Call, reist darum wie [cacheHit] am [ChatEvent.Start] (Diary
     * `escalationSource`, Turn↔Note-Verknüpfung). `null` ⇒ Start byte-identisch.
     *
     * [abstainOffer] (Naht D Hörbarkeit, Andi-Auftrag 2026-07-20, additiv):
     * liefert `true`, wenn DIESER Turn GENAU JETZT ein Brain-Abstain-Pending
     * registriert hat UND der Escalation-Modus Nachfragen erlaubt (s.
     * [brainStreamTurn]s `doOnComplete`) — ausgewertet ERST hier (nach dem
     * letzten Antwort-Delta, also sicher gesetzt), NICHT beim Aufbau des
     * Streams. Bei `true` hängt [neverSilent] EINEN zusätzlichen, warmen
     * [ChatEvent.TextDelta] ([ResponseFormatter.abstainLookupOffer]) VOR das
     * [ChatEvent.Done] — die eigentliche Antwort (alle Deltas VOR diesem
     * Anhang) bleibt dabei byte-identisch, es kommt nur EIN Satz obendrauf.
     * Default `{ false }` ⇒ dieser Zweig bleibt tot ⇒ byte-neutral (Flag OFF,
     * kein Abstain, oder Modus AUS).
     */
    private fun neverSilent(
        stream: Flux<ChatEvent>,
        decision: RouteDecision,
        language: Language,
        grounded: Boolean = false,
        cacheHit: Boolean = false,
        session: WorkingSessionSegment? = null,
        stageTimings: () -> ChatEvent.StageTimings? = { null },
        cacheHitSource: String? = null,
        abstainOffer: () -> Boolean = { false },
    ): Flux<ChatEvent> {
        val provider = decision.provider.name
        val sawText = AtomicBoolean(false)
        val chars = AtomicInteger(0)

        val start: ChatEvent = ChatEvent.Start(
            provider = provider,
            category = decision.category.name,
            model = "brain",
            grounded = grounded,
            segmentReset = session?.segmentReset ?: false,
            resetReason = session?.resetReason ?: WorkingSessionSegment.REASON_NONE,
            segmentLenTurns = session?.segmentLenTurns ?: 0,
            cacheHit = cacheHit,
            escalationSource = cacheHitSource ?: "",
        )

        val body = stream
            .doOnNext { ev ->
                if (ev is ChatEvent.TextDelta && ev.text.isNotEmpty()) {
                    sawText.set(true)
                    chars.addAndGet(ev.text.length)
                }
            }
            .concatWith(
                Flux.defer {
                    if (sawText.get()) {
                        // Naht D Hörbarkeit: EIN zusätzlicher TextDelta NACH der Antwort,
                        // NIE in sie hinein gemischt (s. [abstainOffer]-KDoc) — Default
                        // (`false`) ⇒ nur Done, byte-identisch zu heute.
                        if (abstainOffer()) {
                            Flux.just<ChatEvent>(
                                ChatEvent.TextDelta(formatter.abstainLookupOffer(language), provider = provider),
                                ChatEvent.Done(provider = provider, stageTimings = stageTimings()),
                            )
                        } else {
                            Flux.just<ChatEvent>(ChatEvent.Done(provider = provider, stageTimings = stageTimings()))
                        }
                    } else {
                        // Leerer Brain-Stream (kein Text, kein Fehler) → warme LEER-Phrase.
                        fallbackStream(provider, emptyFallback(language), stageTimings())
                    }
                },
            )
            .onErrorResume { _ ->
                if (sawText.get()) {
                    // Text war schon raus → nur sauber schließen, kein Doppel.
                    Flux.just<ChatEvent>(ChatEvent.Done(provider = provider, stageTimings = stageTimings()))
                } else {
                    // Fehler vor Text → warme FEHLER-Phrase statt stillem Tod.
                    fallbackStream(provider, errorFallback(language), stageTimings())
                }
            }

        return Flux.concat(Flux.just(start), body)
    }

    /**
     * Warme Direkt-Antwort OHNE Brain (Abstention-Pfade + Eingabe-/Vor-Brain-Fehler):
     * Start + eine warme TextDelta + Done. Strukturell brain-frei.
     *
     * [stageTimings] (Perf-Diary, additiv): Default `null` ⇒ Done byte-identisch
     * zu heute (alle Policy-Pfade unverändert). Nur der FactCoverage-Deflect
     * reicht seine ehrlich gemessene Grounding-Dauer hier durch.
     */
    private fun warmDirectAnswer(
        provider: String,
        category: String,
        phrase: String,
        stageTimings: ChatEvent.StageTimings? = null,
    ): Flux<ChatEvent> {
        val text = phrase.ifBlank { warmFallback() }
        return Flux.just(
            ChatEvent.Start(provider = provider, category = category, model = "policy"),
            ChatEvent.TextDelta(text, provider = provider),
            ChatEvent.Done(provider = provider, stageTimings = stageTimings),
        )
    }

    /**
     * **Session-Schlüssel des Pending-Angebots** (Extended Think S2, dokumentierter
     * Entscheid zur größten Design-Unbekannten): `chatId ?: speakerId ?: "local"`.
     * Der Voice-Pfad baut den ChatRequest heute OHNE chatId und OHNE speakerContext
     * ⇒ er fällt auf den Ein-Haushalt-Single-Slot [PendingLookupPort.LOCAL_KEY]
     * (ehrlich + sicher: max. EIN offenes Angebot, TTL + one-shot). Upgrade-Pfad:
     * liefert die Sprecher-ID-Lane (S3) eine echte speakerId in den Voice-Pfad,
     * greift automatisch die mittlere Stufe — pro Sprecher, ohne Umbau hier.
     * Bewusst `ctx.request.chatId` (nullable) statt `ctx.chatId` (fällt auf
     * "default"): der Fallback soll sichtbar der dokumentierte Single-Slot sein.
     */
    private fun pendingKey(ctx: TurnPrompt): String =
        ctx.request.chatId?.takeIf { it.isNotBlank() }
            ?: ctx.speaker?.speakerId?.takeIf { it.isNotBlank() }
            ?: PendingLookupPort.LOCAL_KEY

    /**
     * **Einlösung eines Nachschlags** (geteilt von Naht B [Affirmation] und Naht C
     * [Lookup-Intent]): dieselbe modus-gegatete Kaskade, mit der ein Consent heute
     * eingelöst wird — AUS ⇒ ehrlicher Setting-Hinweis (nie ein stiller Call),
     * ERST_FRAGEN/AUTOMATISCH ⇒ brain-freier [escalationTurn] mit GENAU dieser
     * [query] (Egress-Gesetz: NUR die Frage geht raus, nie History/Memory).
     * Provider/Kategorie fest LOCAL/FACT_SHORT — wie der bestehende Consent-Pfad.
     *
     * [research] (Default `false`, Andi-Auftrag 2026-07-19): trägt DIESER Turn
     * einen erkannten Recherche-Imperativ ([ResearchIntentRecognizer])? Reicht
     * NUR an [escalationChoice] durch, WELCHER Port/Label den Call macht — der
     * Consent-/Modus-Vertrag oben ist für BEIDE Modelle identisch (der Cap gilt
     * für beide gemeinsam, s. dessen KDoc).
     */
    private fun redeemLookup(query: String, language: Language, research: Boolean = false): Flux<ChatEvent> =
        when (escalationMode()) {
            EscalationMode.AUS -> warmDirectAnswer(
                RouteProvider.LOCAL.name,
                RouteCategory.FACT_SHORT.name,
                extendedThinkOffHint(language),
            )
            EscalationMode.ERST_FRAGEN, EscalationMode.AUTOMATISCH -> {
                val (port, label) = escalationChoice(research)
                escalationTurn(query, language, RouteProvider.LOCAL.name, RouteCategory.FACT_SHORT.name, port, label)
            }
        }

    /**
     * **Recherche-Modell-Wahl (Andi-Auftrag 2026-07-19).** Wählt Port + Anzeige-
     * Label für EINEN Eskalations-Call: ein erkannter Recherche-Imperativ
     * ([research]=true) UND ein konfiguriertes Recherche-Modell
     * ([researchEscalationProvider] nicht leer) ⇒ [researchEscalation]; sonst
     * (kein Recherche-Imperativ ODER kein Recherche-Modell konfiguriert) IMMER
     * der Standard-[escalation]-Port mit [LOOKUP_NOTE_PROVIDER] — EXAKT das
     * Verhalten vor diesem Auftrag (byte-neutral Default, da
     * [researchEscalationProvider] ohne Wiring `""` bleibt).
     */
    private fun escalationChoice(research: Boolean): Pair<EscalationPort, String> =
        if (research && researchEscalationProvider.isNotBlank()) {
            researchEscalation to researchEscalationProvider
        } else {
            escalation to LOOKUP_NOTE_PROVIDER
        }

    /**
     * **Naht C — der Turn einer expliziten Nachschlag-Bitte** (brain-frei bei jeder
     * Verzweigung). Vier Ausgänge, konservativ geordnet:
     *
     *  1. **Offenes Angebot** ([pendingThink] != null, one-shot am Turn-Kopf schon
     *     konsumiert) ⇒ es wie ein Consent einlösen ([redeemLookup] mit der
     *     GESPEICHERTEN Original-Query + -Sprache) — die Bitte bestätigt das Angebot.
     *     Gewinnt IMMER über Fall 2: das Pending trägt die belastbare ORIGINAL-Frage,
     *     ein Inline-Rest der AKTUELLEN Bitte tritt dagegen nie an.
     *  2. **Kein Angebot, aber die Bitte TRÄGT selbst eine Frage** (Andi-Fix
     *     2026-07-20, Live-Repro: „Schau bitte online nach, wann GTA 6 erscheint."
     *     bekam trotz enthaltener Frage die Rückfrage) ⇒
     *     [LookupIntentRecognizer.extractInlineQuery] streift das Intent-Präfix
     *     („schau bitte online nach,") ab; bleibt ein brauchbarer Rest, wird ER
     *     direkt eingelöst — die Rückfrage entfällt, weil die Frage schon im Satz
     *     steht. NUR Fallback, wenn KEIN Pending offen ist (Fall 1 gewinnt immer).
     *  3. **Kein Angebot, kein Inline-Rest, aber eine vorherige Frage** in der
     *     Session-History ([previousUserQuestion]) ⇒ diese direkt einlösen — die
     *     explizite Bitte IST der Consent (auch im ERST_FRAGEN-Modus; AUS ⇒
     *     ehrlicher Setting-Hinweis über [redeemLookup]). Egress-Gesetz: NUR die
     *     Frage reist, nie die History selbst.
     *  4. **Nichts zum Nachschlagen** ⇒ ehrliche, brain-freie Rückfrage
     *     ([lookupIntentClarify]) — 0 Brain-Calls, kein Raten.
     *
     * [research] (Andi-Auftrag 2026-07-19): das AKTUELLE Turn-Wort war ein
     * Recherche-Imperativ — reist in ALLE [redeemLookup]-Ausgänge (1+2+3), auch
     * wenn das eingelöste Angebot selbst NICHT recherche-ausgelöst war (die
     * JETZIGE Bitte entscheidet, nicht die Herkunft des Angebots).
     */
    private fun lookupIntentTurn(ctx: TurnPrompt, pendingThink: PendingLookup?, research: Boolean = false): Flux<ChatEvent> {
        if (pendingThink != null) {
            return redeemLookup(pendingThink.query, pendingThink.language, research)
        }
        val inlineQuery = LookupIntentRecognizer.extractInlineQuery(ctx.text)
        if (inlineQuery != null) {
            return redeemLookup(inlineQuery, ctx.language, research)
        }
        val previous = previousUserQuestion(ctx)
        if (previous != null) {
            return redeemLookup(previous, ctx.language, research)
        }
        return warmDirectAnswer(
            RouteProvider.LOCAL.name,
            RouteCategory.FACT_SHORT.name,
            lookupIntentClarify(ctx.language),
        )
    }

    /**
     * **Die vorherige User-Frage aus der Session-History** (Naht C, Fall 2) — die
     * letzte User-Äußerung des rekonstruierten Verlaufs ([effectiveSession]: Client-
     * History ODER die serverseitige WorkingSession), die selbst KEINE Nachschlag-
     * Bitte / Affirmation ist (sonst würde „schau online nach" bzw. „ja" als Query
     * eskaliert — nonsens). `null`, wenn kein tauglicher Vor-Turn existiert
     * (⇒ ehrliche Rückfrage). Rein lesend; der aktuelle Turn-Text ist NICHT Teil
     * der History (der reist separat als `prompt`), muss also nicht ausgefiltert werden.
     */
    private fun previousUserQuestion(ctx: TurnPrompt): String? =
        effectiveSession(ctx).turns
            .lastOrNull { msg ->
                msg.role == ROLE_USER &&
                    msg.content.isNotBlank() &&
                    !LookupIntentRecognizer.matches(msg.content) &&
                    !AffirmationRecognizer.matches(msg.content)
            }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * **Naht D — Brain-Abstain erzeugt ein Nachschlag-Angebot** (Live-Fix Wurzel a):
     * passt das Brain bei einem LOCAL-FACT_SHORT-Turn ehrlich
     * ([BrainAbstainRecognizer] auf die fertige Antwort), registriert der
     * Orchestrator ein [PendingLookup] mit der ORIGINAL-User-Frage — OHNE die
     * emittierte Antwort anzufassen (reiner `offer`-Seiteneffekt). Ein späteres
     * „ja" ([AffirmationRecognizer]) oder „schau online nach"
     * ([LookupIntentRecognizer]) kann es dann wie gewohnt einlösen.
     *
     * Streng eingezäunt: NUR bei aktiver Naht ([lookupIntentEnabled]), NUR
     * LOCAL/FACT_SHORT, NUR bei erkanntem Abstain. Sonst no-op. Läuft am
     * `doOnComplete` des Brain-Streams (nach allen Deltas, vor dem Done) — die
     * Antwort-Bytes sind dadurch unverändert.
     *
     * @return `true` gdw. GENAU JETZT ein Pending registriert wurde — der Aufrufer
     *   ([brainStreamTurn]) nutzt das, um das hörbare Angebot ([neverSilent]s
     *   `abstainOffer`) zu steuern (s. dortiges KDoc): das REGISTRIEREN bleibt
     *   unverändert bedingungslos (auch bei [EscalationMode.AUS] — ein
     *   verwaistes Pending schadet nicht, es verfällt per TTL/one-shot), NUR das
     *   HÖRBARMACHEN hängt zusätzlich am Modus (s. [brainStreamTurn]).
     */
    private fun maybeOfferAbstainPending(ctx: TurnPrompt, decision: RouteDecision, answer: String): Boolean {
        if (!lookupIntentEnabled) return false
        if (decision.provider != RouteProvider.LOCAL || decision.category != RouteCategory.FACT_SHORT) return false
        if (!BrainAbstainRecognizer.isAbstain(answer)) return false
        pendingLookup.offer(pendingKey(ctx), PendingLookup(query = ctx.text, language = ctx.language))
        return true
    }

    /**
     * **Der Orts-Folge-Turn (Wetter S3)** — die Einlösung der „Für welchen Ort
     * denn?"-Nachfrage, wenn der Folge-Turn konservativ wie ein Ort aussieht:
     *
     *  1. [WeatherLocationAskPort.resolveAndStore]: Geocode + persistenter Store
     *     (DIESELBE Store-Wahrheit wie der Settings-PUT — „Ich merk's mir" wird
     *     wörtlich wahr).
     *  2. **Treffer** ⇒ Re-Dispatch der GEMERKTEN Ursprungs-Frage ([pending].query,
     *     NIE der bloße Orts-Name) durch die normale Pipeline ([handle]) — der
     *     Store trägt den Ort jetzt, das Wetter-Grounding nutzt ihn, und
     *     `needsLocation` ist false (Store nicht mehr leer) ⇒ strukturell keine
     *     zweite Nachfrage. Die warme Bestätigung ([weatherLocationSaved]) wird
     *     direkt HINTER das Start-Event des inneren Turns injiziert
     *     (`switchOnFirst`, genau EIN Subscribe) — die Event-Ordnung
     *     Start → Deltas → Done bleibt gewahrt, genau ein Start/Done pro Turn.
     *  3. **Kein Treffer / Geocoding weg** (leeres Mono) ⇒ die Antwort war wohl
     *     doch kein Ort: der GESAGTE Text läuft als normaler Turn ([routedTurn]),
     *     die Nachfrage ist konsumiert und verfällt (Auftrag: „kein Treffer ⇒
     *     normaler Turn").
     *
     * Never-silent: ein unerwarteter Port-Fehler endet in der warmen
     * Fallback-Antwort (Muster [handle]-onErrorResume), nie in stiller Sackgasse.
     *
     * **S1-Hinweis:** ruft [handleTurn] (NICHT [handle]) für [pending].query auf
     * — der GESPEICHERTE Alt-Query ist NICHT das frische Whisper-Transkript
     * DIESES Turns (das wurde schon am äußeren [handle]-Aufruf mit `place`
     * gemessen); ein zweiter Score-Call auf einem veralteten Text wäre
     * semantisch falsch und würde die Messung verdoppeln.
     */
    private fun locationAnswerTurn(
        place: String,
        pending: PendingLocationQuestion,
        ctx: TurnPrompt,
        speakerId: String?,
    ): Flux<ChatEvent> =
        weatherAsk.resolveAndStore(place)
            .flatMapMany { label ->
                val confirm: ChatEvent = ChatEvent.TextDelta(
                    weatherLocationSaved(label, pending.language),
                    provider = RouteProvider.LOCAL.name,
                )
                handleTurn(ctx.request.copy(text = pending.query)).switchOnFirst { first, inner ->
                    val head = first.get()
                    // handle() emittiert IMMER zuerst ein Start — die Bestätigung
                    // kommt direkt dahinter. Defensiv: ohne Start-Kopf unverändert.
                    if (head is ChatEvent.Start) Flux.concat(Flux.just(head, confirm), inner.skip(1)) else inner
                }
            }
            .switchIfEmpty(Flux.defer { routedTurn(ctx.request, ctx, speakerId) })
            .onErrorResume { warmDirectAnswer(provider = "LOCAL", category = "ERROR", phrase = warmFallback()) }

    /**
     * **Der Eskalations-Turn (Extended Think S2)** — strukturell **brain-frei**
     * (0 Brain-Calls; der max-1-Brain-Call-Vertrag bleibt trivial erfüllt):
     *
     *  1. [ChatEvent.Start] (model=`policy` — eine Policy-Direktantwort, kein Brain),
     *  2. sofort [ResponseFormatter.cloudConsentAccept] als erste TextDelta (die
     *     Brücke „Klar, einen Moment — ich frag schnell." — bis S2 ungenutzt),
     *  3. GENAU EIN [EscalationPort.lookup] mit der Original-[query] (v1: NUR die
     *     Frage, groundingSnippets bewusst leer — Tom-freundlichste Auslegung;
     *     NIE finalPrompt/History/Memory),
     *  4. das Ergebnis als warme TextDelta(s):
     *     - [EscalationResult.Answer] ⇒ lokale Rahmung + Antwort **VERBATIM**
     *       (WikiNumber-Lehre: keine Brain-Umformulierung) + Quelle als Nachsatz.
     *       Marker-Hygiene selbst sichergestellt ([stripContractMarkers] — dieser
     *       Pfad läuft NICHT durch die Brain-Prosa-Naht).
     *     - Unclear/Unavailable/Declined ⇒ ehrliche warme Fortsetzung (Entscheid-#2-Ton).
     *  5. [ChatEvent.Done] — trägt [ChatEvent.Done.escalationCostCents], FALLS der
     *     Ausgang eine [EscalationResult.Answer] war (s. [costCents]).
     *
     * Never-silent: Timeout ([escalationTimeout]) / Fehler / leeres Mono ⇒
     * Unavailable-Pfad — der Turn endet IMMER in Text + Done.
     *
     * **S4 Diary:** das [ChatEvent.Start] setzt [ChatEvent.Start.escalated]=`true` +
     * [ChatEvent.Start.escalationProvider] SOFORT (synchron, vor dem Lookup) — JEDER
     * Aufruf dieser Funktion IST ein Eskalations-Versuch am gewählten
     * [providerLabel], unabhängig vom späteren Ausgang. Die Kosten dagegen sind
     * erst NACH dem asynchronen [EscalationPort.lookup]-Call bekannt und reisen darum
     * am terminalen [ChatEvent.Done] (nicht am Start) — eine lokale `AtomicReference`
     * hält sie, befüllt vom `doOnNext` der Outcome-Kette, BEVOR die `concatWith`-Kette
     * das Done erreicht (Reactor: `concatWith` subscribed sequentiell, das
     * abschließende [Flux.defer] liest den Wert also sicher NACH dem Lookup).
     *
     * [escalationPort]/[providerLabel] (Andi-Auftrag 2026-07-19, Default =
     * [escalation]/[LOOKUP_NOTE_PROVIDER] ⇒ EXAKT das Verhalten vor diesem
     * Auftrag): WELCHER Port gerufen wird und WIE der Turn sich beschriftet —
     * [escalationChoice] wählt beides gemeinsam, damit ein Recherche-Turn NIE
     * mit dem Standard-Port läuft, aber mit dem Recherche-Label beschriftet ist
     * (oder umgekehrt).
     */
    private fun escalationTurn(
        query: String,
        language: Language,
        provider: String,
        category: String,
        escalationPort: EscalationPort = escalation,
        providerLabel: String = LOOKUP_NOTE_PROVIDER,
    ): Flux<ChatEvent> {
        val head = Flux.just<ChatEvent>(
            ChatEvent.Start(
                provider = provider,
                category = category,
                model = "policy",
                escalated = true,
                escalationProvider = providerLabel,
            ),
            ChatEvent.TextDelta(formatter.cloudConsentAccept(language), provider = provider),
        )
        // S4 Diary: Kosten sind erst NACH dem Lookup bekannt (nur bei EscalationResult.Answer
        // > 0) — best-effort in einer Referenz gehalten, das terminale Done liest sie deferred.
        val costCents = AtomicReference<Double?>(null)
        // H2 Diary: Turn↔Note-Verknüpfung — NUR bei einer echten Answer gesetzt (sonst
        // wurde nie eine Notiz geschrieben, s. recordLookupNote/Nora-Veto). Der Hash ist
        // derselbe, mit dem recordLookupNote GLEICH DANACH die Notiz schreibt (dieselbe
        // normalisierte ORIGINAL-Query).
        val queryHash = AtomicReference<String?>(null)
        val source = AtomicReference<String?>(null)
        // Quellen-Struktur-Auftrag 2026-07-21 (Muster [source]): NUR bei ECHTEN
        // url_citation-Treffern gefüllt (s. escalationOutcomeDeltas-KDoc) — leer/
        // null ⇒ das FE zeigt ehrlich KEIN Quellen-Icon.
        val sources = AtomicReference<List<EscalationSourceRef>?>(null)
        // H3 Diary: Cap-Erschöpfung EHRLICH von einem Netzfehler unterscheidbar.
        val capExhausted = AtomicBoolean(false)
        val outcome: Flux<ChatEvent> = escalationPort.lookup(query, "", language)
            .timeout(escalationTimeout)
            .onErrorReturn(EscalationResult.Unavailable)
            .defaultIfEmpty(EscalationResult.Unavailable)
            .doOnNext { result ->
                // H2 Diary: derselbe Hash, mit dem die Notiz GERADE geschrieben wurde
                // (recordLookupNote gibt sie zurück statt einer zweiten Normalisierung —
                // eine Wahrheit, kein Duplikat).
                val note = recordLookupNote(query, result, providerLabel)
                if (result is EscalationResult.Answer) {
                    costCents.set(result.costCents)
                    queryHash.set(note?.queryHash)
                    source.set(result.source)
                    sources.set(result.sources.takeIf { it.isNotEmpty() })
                }
                if (result is EscalationResult.CapExhausted) capExhausted.set(true)
            }
            .flatMapMany { result -> Flux.fromIterable(escalationOutcomeDeltas(result, language, provider)) }
        return head
            .concatWith(outcome)
            .concatWith(
                Flux.defer {
                    Flux.just<ChatEvent>(
                        ChatEvent.Done(
                            provider = provider,
                            escalationCostCents = costCents.get(),
                            escalationQueryHash = queryHash.get(),
                            escalationSource = source.get(),
                            // Nullable Wire-Feld (s. ChatEvent.Done.escalationCapExhausted-KDoc):
                            // NUR bei true gesetzt, sonst null ⇒ Feld fehlt im JSON.
                            escalationCapExhausted = capExhausted.get().takeIf { it },
                            escalationSources = sources.get(),
                        ),
                    )
                },
            )
    }

    /**
     * **Der Nachgeschlagen-Store-WRITE (S3).** NUR eine [EscalationResult.Answer]
     * wird zur [LookupNote] (Nora-Veto: UNKLAR/Unavailable/Declined NIE cachen — eine
     * ungesicherte Antwort dürfte nie mit Grounding-Autorität wiederkommen). Best-effort
     * (der [lookupNotes]-Port wirft laut Vertrag nie); eine leere Normalisierung
     * (z.B. reine Satzzeichen-Query) schreibt ebenfalls nicht — es gäbe nichts
     * Sinnvolles zum Wiederfinden.
     *
     * @return die geschriebene [LookupNote] (H2, additiv) — `null` bei jedem
     *   Nicht-Schreib-Fall (kein Answer / leere Normalisierung). Der Aufrufer
     *   nutzt sie NUR für [LookupNote.queryHash] im S4 Diary — EINE Normalisierung,
     *   keine zweite Quelle der Wahrheit.
     *
     * **H1b — Schreib-Zaun-Hygiene (Security-Fix, Pod Tom/Jonas 2026-07-08,
     * Defense in depth zu H1):** answer/source sind ein Webtext-Derivat einer
     * Cloud-Eskalation (Nano-Antwort), NICHT von Hoshi geprüft. Bevor sie in den
     * 30-Tage-Store wandern, neutralisiert [LookupNoteFenceGuard.neutralize] die
     * H1-Zitat-Zaun-Zeichen (`⟦`/`⟧`) — dieselbe Ersetzung, die
     * [de.hoshi.adapters.knowledge]s `NachgeschlagenGroundingProvider` beim LESEN
     * anwendet (s. dessen KDoc), hier zusätzlich schon beim SCHREIBEN. So kann
     * die gespeicherte Notiz den Lese-Zaun nie enthalten — und ein später
     * hinzukommender zweiter Lese-Pfad erbt den Schutz automatisch, ohne selbst
     * daran denken zu müssen. Der Lese-Zaun bleibt unverändert als zweite,
     * unabhängige Wand bestehen (u.a. für Alt-Notizen von vor H1b).
     *
     * [providerLabel] (Andi-Auftrag 2026-07-19, Default [LOOKUP_NOTE_PROVIDER]
     * ⇒ byte-neutral): das TATSÄCHLICH gerufene Modell — eine Sol-Antwort landet
     * NIE als „openai-nano" im 30-Tage-Store (die Notiz kann später als
     * Cache-Hit resurfacen, s. `NachgeschlagenGroundingProvider`; eine falsche
     * Herkunfts-Beschriftung wäre dort DAUERHAFT falsch, nicht nur transient).
     */
    private fun recordLookupNote(query: String, result: EscalationResult, providerLabel: String = LOOKUP_NOTE_PROVIDER): LookupNote? {
        if (result !is EscalationResult.Answer) return null
        val norm = LookupNoteNormalizer.normalize(query)
        if (norm.isBlank()) return null
        val note = LookupNote(
            queryHash = LookupNoteNormalizer.sha256Hex(norm),
            queryNorm = norm,
            answer = LookupNoteFenceGuard.neutralize(result.text),
            source = LookupNoteFenceGuard.neutralize(result.source),
            provider = providerLabel,
            costCents = result.costCents,
            ts = Instant.now(),
            ttlDays = LOOKUP_NOTE_TTL_DAYS,
            origin = LookupNote.ORIGIN_LIVE,
        )
        lookupNotes.record(note)
        return note
    }

    /**
     * Mappt das [EscalationResult] auf die warmen Antwort-Deltas des
     * Eskalations-Turns (pure, exhaustiv).
     *
     * **Quellen-Struktur-Auftrag (2026-07-21, Andi-Befund):** der Antwort-TEXT
     * trägt seit diesem Auftrag KEINEN Quellen-/URL-Nachsatz mehr — vorher hing
     * hier `" " + escalationSourceNote(result.source, …)` an, was bei einer
     * echten Web-Search-Antwort zu „…330 Meter. Quelle: Quellen:
     * https://…?utm_source=openai." entartete (der bereits mit „Quellen: "
     * präfigierte [EscalationResult.Answer.source]-String bekam HIER ein
     * zweites „Quelle: " davor) — unbrauchbar, besonders gesprochen (TTS).
     * Die Attribution reist jetzt AUSSCHLIESSLICH strukturiert über
     * [ChatEvent.Done.escalationSources] (echte Citations) bzw. bleibt als
     * reiner Diary-String in [ChatEvent.Done.escalationSource] — nie mehr
     * angehängter Sprech-/Anzeige-Text. [escalationSourceNote] lebt für den
     * unveränderten Verbatim-Replay-Pfad ([verbatimReplayPhrase]) weiter, der
     * bewusst NICHT Teil dieses Auftrags ist (andere Quellen-Semantik: ein
     * einzelnes, sauberes Attributions-Wort aus dem 30-Tage-Cache, keine URL).
     */
    private fun escalationOutcomeDeltas(
        result: EscalationResult,
        language: Language,
        provider: String,
    ): List<ChatEvent> = when (result) {
        is EscalationResult.Answer -> listOf(
            // Rahmung + Antwort VERBATIM in EINEM Delta (die Faktenaussage bleibt
            // String-identisch mit result.text — nur die Rahmung kommt davor).
            // KEIN Quellen-Nachsatz mehr (s. Funktions-KDoc) — die Attribution
            // reist strukturiert am Done (escalationSource/escalationSources).
            ChatEvent.TextDelta(
                escalationAnswerFrame(language) + stripContractMarkers(result.text),
                provider = provider,
            ),
        )
        EscalationResult.Unclear ->
            listOf(ChatEvent.TextDelta(escalationUnclear(language), provider = provider))
        is EscalationResult.Declined ->
            listOf(ChatEvent.TextDelta(escalationDeclined(language), provider = provider))
        EscalationResult.Unavailable ->
            listOf(ChatEvent.TextDelta(escalationUnavailable(language), provider = provider))
        // H3: EIGENE, ehrliche Phrase — Cap-Erschöpfung ist NICHTS Kaputtes, darum NIE
        // die Unavailable-Phrase (die bleibt Netz-/Key-Fehlern vorbehalten, unverändert).
        EscalationResult.CapExhausted ->
            listOf(ChatEvent.TextDelta(escalationCapExhausted(language), provider = provider))
    }

    /**
     * Warme Fallback-Phrase als TextDelta + Done (Never-Silent-Endstück). Die
     * [phrase] ist schon modus-/sprach-aufgelöst; leer → [warmFallback] als letzter
     * Rückfall (nie eine stille TextDelta). [stageTimings] (Perf-Diary, additiv):
     * Default `null` ⇒ Done byte-identisch; der Never-Silent-Pfad reicht die bis
     * dahin ehrlich gemessenen Brain-Pfad-Timings durch.
     */
    private fun fallbackStream(
        provider: String,
        phrase: String,
        stageTimings: ChatEvent.StageTimings? = null,
    ): Flux<ChatEvent> = Flux.just(
        ChatEvent.TextDelta(phrase.ifBlank { warmFallback() }, provider = provider),
        ChatEvent.Done(provider = provider, stageTimings = stageTimings),
    )

    companion object {
        /** Hartcodierte warme Phrase, falls kein Phrasen-Pool injiziert / keine Sprache bekannt ist (nie leer). */
        const val DEFAULT_FALLBACK =
            "Hab dich gehört, aber bei mir hakt's grad kurz. Sag's gleich nochmal?"

        /** Start-Kategorie eines Settings-Fastpath-Turns (Stufe per Sprache) — freie Kategorie wie "EMPTY"/"ERROR". */
        const val CATEGORY_SETTINGS = "SETTINGS"

        /** Start-Kategorie eines Tagesnoten-Fastpath-Turns (Andi-Faktor) — freie Kategorie wie "EMPTY"/"ERROR". */
        const val CATEGORY_NOTE = "NOTE"

        /** Start-Kategorie des Probe-Fastpath-Turns (Golden-Utterance #20) — freie Kategorie wie "EMPTY"/"ERROR". */
        const val CATEGORY_PROBE = "PROBE"

        /** [de.hoshi.core.dto.ChatRequest.source]-Fallback eines Alt-Clients ohne Feld: der Text-/Chat-Rand. */
        const val SOURCE_CHAT_DEFAULT = "chat"

        /** [de.hoshi.core.dto.ChatRequest.source] eines Sprach-Turns über `POST /api/v1/voice` (s. [withSttSurprisal]). */
        const val SOURCE_VOICE = "voice"

        /** [de.hoshi.core.dto.ChatRequest.source] eines Sprach-Turns über den WebSocket `/ws/audio` (s. [withSttSurprisal]). */
        const val SOURCE_WS = "ws"

        /** [de.hoshi.core.dto.ChatMessage.role] einer User-Äußerung (Naht C: die vorherige Frage suchen). */
        const val ROLE_USER = "user"

        /**
         * Hartes Zeitbudget der [withSttSurprisal]-Messung — die Score-Naht darf
         * den Turn NIE spürbar aufhalten. Der Adapter kapselt denselben Timeout
         * zusätzlich (Verteidigung in der Tiefe, s. [SttSurprisalPort]-KDoc).
         */
        val STT_SURPRISAL_TIMEOUT: Duration = Duration.ofMillis(500)

        /**
         * Die vier Guillemet-Zeichen `« » ‹ ›` — Vertrags-Marker des WikiNumberContract
         * ([de.hoshi.adapters.knowledge]-Grounding markiert Zahl-Spans als «330 Meter»
         * im PROMPT; sie sind Prompt-Interna und gehören NIE in die gesprochene Antwort).
         */
        private const val CONTRACT_MARKERS = "«»‹›"

        /**
         * **Guillemet-Strip (Wand statt Tapete, Kai):** entfernt die [CONTRACT_MARKERS]
         * ersatzlos aus einem Brain-Delta, BEVOR es als [ChatEvent.TextDelta] an
         * FE und TTS geht. Live-Befund 2026-07-02: das 4B hält die Prompt-Regel
         * „Zeichen NICHT mitschreiben" nicht und spricht »330 Meter« mit — eine
         * deterministische Wand schlägt jede Prompt-Tapete.
         *
         * KEIN Flag: Vertrags-Marker sind Prompt-Interna, und deutsche
         * »Anführungszeichen« nutzt Hoshi in gesprochener Sprache nicht — es gibt
         * keinen legitimen Fall, in dem Hoshi Guillemets spricht.
         *
         * Streaming-safe OHNE Puffer: jedes Marker-Zeichen ist ein Einzel-Zeichen und
         * wird im Delta gestrippt, in dem es ankommt — auch ein über die Delta-Grenze
         * gesplitteter Span („»33" + „0«") wird trivial sauber. Deltas ohne Marker
         * fließen byte-identisch (dieselbe String-Instanz, keine Allokation) durch.
         */
        internal fun stripContractMarkers(text: String): String =
            if (text.none { it in CONTRACT_MARKERS }) text
            else text.filterNot { it in CONTRACT_MARKERS }

        /**
         * Temperatur-Decke fuer FACT_SHORT-Antworten (Jahr/Zahl/Wissen), wenn der
         * [factLowTemp]-Clamp an ist. 0.30 == [de.hoshi.core.dto.PersonaEmotion.FOCUSED]:
         * knapp, vorhersagbar, wenig Ueberraschungen — gegen Zahl-Garbles + Ausweichen.
         */
        const val FACT_TEMP_CEILING: Double = 0.30

        /**
         * (H1) Harte Obergrenzen für die agentische [collectList]-Sammlung: max. so
         * viele Brain-Deltas puffern und max. so lange auf den Stream warten — beides
         * gegen unbegrenztes Sammeln (Speicher/Hänger). Großzügig bemessen, sodass
         * normale (kurze) Tool-Antworten nie anschlagen.
         */
        const val MAX_AGENTIC_DELTAS: Long = 200
        val AGENTIC_COLLECT_TIMEOUT: Duration = Duration.ofSeconds(30)

        /**
         * (K1, HONESTY) **Tool-Mode-Direktive** — wird im agentischen Pfad (Tools
         * werden mitgeschickt) an den System-Prompt gehängt. Der Brain ist UNTRUSTED
         * und behauptet bei indirekter Phrasierung sonst Effekte im reinen Text, ohne
         * das Tool zu rufen (live beobachtet) → Hoshi lügt. Die Direktive bindet den
         * Brain an die ehrliche Tool-Naht, statt brittle „Verb-ohne-Call → Absage"-
         * Heuristik (false positives) zu bauen. Pure Konstante, sprach-abhängig.
         */
        const val TOOL_MODE_DIRECTIVE_DE =
            "WICHTIG: Willst du eine Aktion im Haus ausführen (Licht, Heizung, Szene), " +
                "MUSST du das passende Tool aufrufen. Behaupte NIEMALS, dass du etwas getan " +
                "hast, ohne das Tool wirklich aufzurufen. Kannst du es nicht ausführen, sag " +
                "es ehrlich und kurz."
        const val TOOL_MODE_DIRECTIVE_EN =
            "IMPORTANT: If you want to perform an action in the house (light, heating, scene), " +
                "you MUST call the matching tool. NEVER claim you did something without actually " +
                "calling the tool. If you cannot do it, say so honestly and briefly."

        /**
         * Pure, deterministische Auswahl der **Tool-Mode-Direktive** nach Turn-Sprache.
         * ES/FR/IT fallen auf EN zurück (s. [de.hoshi.core.pipeline.lang.deOr]).
         */
        fun toolModeDirective(language: Language): String = language.deOr(TOOL_MODE_DIRECTIVE_DE, TOOL_MODE_DIRECTIVE_EN)

        /**
         * **LEER**-Phrase: Brain kam leer zurück (kein Text, kein Fehler) — „nochmal
         * sagen" hilft hier, weil nichts strukturell kaputt ist.
         */
        const val EMPTY_FALLBACK_DE = "Da ist mir grad nichts gekommen — sag's mir nochmal?"
        const val EMPTY_FALLBACK_EN = "I drew a blank just now — say it again?"

        /**
         * **FEHLER**-Phrase: etwas warf/ist weg VOR dem ersten Text — kein „nochmal",
         * sondern ehrlich „gib mir einen Moment" (etwas hängt im Hintergrund).
         */
        const val ERROR_FALLBACK_DE = "Bei mir hakt grad was im Hintergrund — gib mir einen Moment."
        const val ERROR_FALLBACK_EN = "Something's stuck on my end — give me a moment."

        /**
         * Pure, deterministische Auswahl der **LEER**-Phrase nach Turn-Sprache.
         * Exhaustiv über [Language] — eine neue Sprache zwingt hier zur bewussten
         * Phrase (statt still auf DE zu fallen).
         */
        fun emptyFallback(language: Language): String = language.deOr(EMPTY_FALLBACK_DE, EMPTY_FALLBACK_EN)

        /** Pure, deterministische Auswahl der **FEHLER**-Phrase nach Turn-Sprache. */
        fun errorFallback(language: Language): String = language.deOr(ERROR_FALLBACK_DE, ERROR_FALLBACK_EN)

        /** Timeout des Eskalations-Lookups: nach ~8 s der warme Unavailable-Pfad (never-silent). */
        val ESCALATION_LOOKUP_TIMEOUT: Duration = Duration.ofSeconds(8)

        /**
         * Nachgeschlagen-Notiz-TTL (S3): wie lange ein Cache-Treffer gilt, bevor
         * [de.hoshi.adapters.knowledge]s `NachgeschlagenGroundingProvider` wieder
         * durchfällt und eine erneute Cloud-Eskalation nötig wird. 30 Tage — ein
         * Faktum wie „Höhe des Eiffelturms" veraltet nicht in Tagen, aber eine
         * unbegrenzte TTL würde eine falsch verifizierte Notiz für immer festschreiben.
         */
        const val LOOKUP_NOTE_TTL_DAYS: Int = 30

        /**
         * Grobe Herkunfts-Klasse der Notiz (Kai-Universalitäts-Vertrag: core-domain
         * kennt KEINE OpenAI-Spezifik) — das Label des STANDARD-[EscalationPort]
         * (`escalation`, Nano-Klasse). Seit dem Recherche-Modell-Auftrag (2026-07-19)
         * NICHT mehr das einzige mögliche Label: ein erkannter Recherche-Imperativ
         * mit konfiguriertem Recherche-Modell trägt stattdessen
         * [researchEscalationProvider] (z.B. `"openai-sol"`) — s. [escalationChoice].
         * Ein künftiger lokaler 12B-Adapter würde „local-12b" tragen (PREP-
         * Vokabular, `LookupNote.provider`).
         */
        const val LOOKUP_NOTE_PROVIDER: String = "openai-nano"

        /**
         * **Eskalations-Rahmung** (Extended Think S2): der lokale Prefix vor der
         * VERBATIM-Cloud-Antwort — ehrliche Attribution „das kommt von draußen",
         * ohne die Faktenaussage anzufassen (WikiNumber-Lehre).
         */
        const val ESCALATION_FRAME_DE = "Ich hab online nachgeschaut: "
        const val ESCALATION_FRAME_EN = "I looked it up online: "

        /** Pure, deterministische Auswahl der **Eskalations-Rahmung** nach Turn-Sprache. */
        fun escalationAnswerFrame(language: Language): String = language.deOr(ESCALATION_FRAME_DE, ESCALATION_FRAME_EN)

        /** Quellen-Nachsatz der Eskalations-Antwort — ehrlich, kurz, nie weggelassen. */
        fun escalationSourceNote(source: String, language: Language): String =
            language.deOr("Quelle: $source.", "Source: $source.")

        /**
         * **Verbatim-Replay-Rahmung** (Andi-Fix 2026-07-16): der warme, ehrliche
         * Prefix vor der WÖRTLICHEN Cache-Antwort — „das hab ich neulich für dich
         * nachgeschlagen, Stand <Datum>". Trägt bewusst den geteilten Herkunfts-Wortlaut
         * [TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER] („neulich nachgeschlagen"),
         * denselben, den der Provider dem Brain bislang als Anweisung mitgab — so klingt
         * der Replay wie die ehrliche Selbst-Auskunft, die vorher der Brain formulieren
         * sollte, nur jetzt deterministisch statt paraphrasiert.
         */
        const val VERBATIM_REPLAY_FRAME_DE = "Hab ich neulich nachgeschlagen, Stand "
        const val VERBATIM_REPLAY_FRAME_EN = "I looked this up recently, as of "

        /**
         * Pure, deterministische **Verbatim-Replay-Rahmung** nach Turn-Sprache, mit
         * dem [dateLabel] (Stand-Datum der Notiz) — exhaustiv über [Language].
         */
        fun verbatimReplayFrame(dateLabel: String, language: Language): String =
            "${language.deOr(VERBATIM_REPLAY_FRAME_DE, VERBATIM_REPLAY_FRAME_EN)}$dateLabel: "

        /**
         * Stand-Datum-Format der Replay-Rahmung — IDENTISCH zum HINTERGRUND-Block des
         * [de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider]
         * (`dd.MM.yyyy`, Europe/Berlin): dieselbe Notiz zeigt in BEIDEN Pfaden (Replay
         * hier / Grounding-Injektion dort) denselben „Stand". Bewusst hier repliziert —
         * core-domain darf nicht auf den Adapter zeigen (Modul-Graph).
         */
        val REPLAY_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Berlin"))

        /** Erkennt die `Quelle: <text>.`-Zeile im Cache-Hit-groundBlock (s. [parseCacheHitSource]). */
        private val CACHE_HIT_SOURCE_LINE = Regex("""(?m)^Quelle:\s*(.+)\.$""")

        /**
         * **H2 — Cache-Hit-Quelle aus dem groundBlock lesen (pure, best-effort):**
         * [de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider]s `buildBlock`
         * rendert IMMER exakt eine Zeile `Quelle: ${note.source}.` in den HINTERGRUND-
         * Block (dasselbe Literal-Muster wie [escalationSourceNote]s DE-Variante).
         * Diese Funktion liest sie zurück, statt einen zweiten Lese-Pfad auf die
         * Notiz-Datei zu öffnen (die Note selbst ist am Konsum-Punkt in [brainTurn]
         * nicht mehr verfügbar, nur ihr bereits gerenderter Text). `null` bei
         * fehlender/kaputter Zeile — best-effort, kein Verhalten hängt daran außer
         * dem Diary-Feld `TurnTrace.escalationSource`.
         */
        fun parseCacheHitSource(groundBlock: String): String? =
            CACHE_HIT_SOURCE_LINE.find(groundBlock)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

        /**
         * **UNKLAR**-Phrase: die externe Instanz weiß es ehrlich nicht — lieber
         * zugeben als raten (Entscheid-#2-Ton, kein Technik-Wort).
         */
        const val ESCALATION_UNCLEAR_DE =
            "Ich hab nachgeschaut, aber nichts Sicheres gefunden — da will ich dir nichts Falsches erzählen."
        const val ESCALATION_UNCLEAR_EN =
            "I looked it up, but found nothing solid — I'd rather not tell you something wrong."

        /** Pure, deterministische Auswahl der **UNKLAR**-Phrase nach Turn-Sprache. */
        fun escalationUnclear(language: Language): String = language.deOr(ESCALATION_UNCLEAR_DE, ESCALATION_UNCLEAR_EN)

        /**
         * **UNAVAILABLE**-Phrase: Nachschlagen ging gerade nicht (kein Key, Netz,
         * Timeout — der Port unterscheidet das bewusst nicht). Ehrlich + warm, ohne
         * das Nachtschicht-Versprechen („merk's mir für die Nacht" kommt erst, wenn
         * die Nachtschicht es wörtlich wahr macht — Mira/Risiko #3).
         *
         * **H3, bindend:** trägt SEIT H3 NICHT mehr den Cap-erreicht-Fall — der hat
         * jetzt [ESCALATION_CAP_EXHAUSTED_DE]/[EscalationResult.CapExhausted], eine
         * EIGENE ehrliche Phrase, damit Andi „kein Netz" von „Budget alle"
         * unterscheiden kann. Diese Phrase bleibt für Netz-/Key-Fälle UNVERÄNDERT
         * (Test: byte-gleich zum Vor-H3-Stand).
         */
        const val ESCALATION_UNAVAILABLE_DE =
            "Ich wollt nachschauen, aber grad komm ich nicht ran — probieren wir's später nochmal."
        const val ESCALATION_UNAVAILABLE_EN =
            "I tried to look it up, but couldn't get through just now — let's try again later."

        /** Pure, deterministische Auswahl der **UNAVAILABLE**-Phrase nach Turn-Sprache. */
        fun escalationUnavailable(language: Language): String =
            language.deOr(ESCALATION_UNAVAILABLE_DE, ESCALATION_UNAVAILABLE_EN)

        /**
         * **CAP_EXHAUSTED**-Phrase (H3, additiv): das Tages-Budget
         * ([de.hoshi.adapters.escalation.OpenAiEscalationAdapter.dailyCapCents]) ist
         * für heute aufgebraucht — bewusst EINE EIGENE Phrase statt
         * [ESCALATION_UNAVAILABLE_DE]: hier ist NICHTS kaputt (kein Netz-/Key-Fehler),
         * nur das Budget für heute leer. Ehrlich + warm, ohne ein Nachtschicht-
         * Versprechen (Mira/Risiko #3, wie [ESCALATION_UNAVAILABLE_DE]).
         */
        const val ESCALATION_CAP_EXHAUSTED_DE =
            "Ich würd's nachschauen, aber mein Online-Budget für heute ist aufgebraucht — morgen wieder, " +
                "oder frag mich einfach so."
        const val ESCALATION_CAP_EXHAUSTED_EN =
            "I'd look that up, but my online budget for today is used up — try again tomorrow, or just ask me directly."

        /** Pure, deterministische Auswahl der **CAP_EXHAUSTED**-Phrase nach Turn-Sprache. */
        fun escalationCapExhausted(language: Language): String =
            language.deOr(ESCALATION_CAP_EXHAUSTED_DE, ESCALATION_CAP_EXHAUSTED_EN)

        /**
         * **DECLINED**-Phrase: der Egress-Riegel hat die Frage geblockt (Memory-
         * Referenz/ID/Secret) — es ging NICHTS raus, und Hoshi sagt das warm, ohne
         * den Block-Grund im Klartext auszuplaudern.
         */
        const val ESCALATION_DECLINED_DE =
            "Die Frage behalte ich lieber hier bei uns — die schick ich nicht nach draußen."
        const val ESCALATION_DECLINED_EN =
            "That one I'd rather keep between us — I'm not sending it out."

        /** Pure, deterministische Auswahl der **DECLINED**-Phrase nach Turn-Sprache. */
        fun escalationDeclined(language: Language): String =
            language.deOr(ESCALATION_DECLINED_DE, ESCALATION_DECLINED_EN)

        /**
         * **AUS-Hinweis**: „ja" auf ein Nachschau-Angebot, aber die Laufzeit-Stufe
         * steht auf AUS — ehrlich-warm aufs Setting zeigen, NIE ein stiller Call
         * (Mira sieht die Phrase; S2-Auftrag Punkt 4).
         */
        const val EXTENDED_THINK_OFF_HINT_DE =
            "Grad darf ich nicht online — wenn du magst, schalt's in den Einstellungen frei."
        const val EXTENDED_THINK_OFF_HINT_EN =
            "I'm not allowed to go online right now — if you like, enable it in the settings."

        /** Pure, deterministische Auswahl des **AUS-Hinweises** nach Turn-Sprache. */
        fun extendedThinkOffHint(language: Language): String =
            language.deOr(EXTENDED_THINK_OFF_HINT_DE, EXTENDED_THINK_OFF_HINT_EN)

        /**
         * **LOOKUP-INTENT-RÜCKFRAGE** (Naht C, Fall 3): eine explizite „schau online
         * nach"-Bitte kam, aber es gibt WEDER ein offenes Angebot NOCH eine vorherige
         * Frage zum Nachschlagen — ehrlich + brain-frei nachfragen, statt zu raten.
         */
        const val LOOKUP_INTENT_CLARIFY_DE = "Klar — was genau soll ich nachschauen?"
        const val LOOKUP_INTENT_CLARIFY_EN = "Sure — what exactly should I look up?"

        /** Pure, deterministische Auswahl der **Lookup-Intent-Rückfrage** nach Turn-Sprache. */
        fun lookupIntentClarify(language: Language): String =
            language.deOr(LOOKUP_INTENT_CLARIFY_DE, LOOKUP_INTENT_CLARIFY_EN)

        /**
         * **WETTER-ORTS-NACHFRAGE** (Wetter S3): eine Wetter-Frage kam, aber es ist
         * KEIN Ort konfiguriert (Store leer, Seeds auf den Code-Defaults) — statt
         * einer Vorhersage für einen falschen Default-Ort fragt Hoshi warm nach und
         * verspricht das Merken (das [locationAnswerTurn] wörtlich einlöst).
         */
        const val WEATHER_LOCATION_ASK_DE = "Für welchen Ort denn? Ich merk's mir."
        const val WEATHER_LOCATION_ASK_EN = "Which place should I check? I'll remember it."

        /** Pure, deterministische Auswahl der **Orts-Nachfrage** nach Turn-Sprache. */
        fun weatherLocationAsk(language: Language): String =
            language.deOr(WEATHER_LOCATION_ASK_DE, WEATHER_LOCATION_ASK_EN)

        /**
         * **ORTS-BESTÄTIGUNG** (Wetter S3): der genannte Ort wurde geocodet und
         * GESPEICHERT — die kurze Brücke vor der eigentlichen Wetter-Antwort der
         * gemerkten Ursprungs-Frage (Label = das AUFGELÖSTE Geocoder-Label, nie
         * der Roh-Text). Trailing Space, weil direkt die Antwort-Prosa folgt.
         */
        const val WEATHER_LOCATION_SAVED_SUFFIX_DE = " — gemerkt! "
        const val WEATHER_LOCATION_SAVED_SUFFIX_EN = " — noted! "

        /** Pure, deterministische **Orts-Bestätigung** nach Turn-Sprache. */
        fun weatherLocationSaved(label: String, language: Language): String =
            label + language.deOr(WEATHER_LOCATION_SAVED_SUFFIX_DE, WEATHER_LOCATION_SAVED_SUFFIX_EN)

        /**
         * **AGENTISCHE ABSAGE**: der Brain wollte eine Tat, die der Kernel/Resolver
         * nicht trägt (unbekanntes/unerlaubtes Tool, defekter Block) — warm „das kann
         * ich (so) nicht", ohne je rohe Tool-Tokens zu zeigen.
         */
        const val AGENTIC_REFUSAL_DE = "Das kann ich so gerade nicht machen."
        const val AGENTIC_REFUSAL_EN = "That's something I can't do like this right now."

        /** Pure, deterministische Auswahl der **AGENTISCHEN ABSAGE** nach Turn-Sprache. */
        fun agenticRefusal(language: Language): String = language.deOr(AGENTIC_REFUSAL_DE, AGENTIC_REFUSAL_EN)

        /**
         * **AGENTISCHES „none"** (PATH B [ToolGrammarParser.Result.None]): der Brain hat
         * STRUKTURELL `tool=="none"` gewählt — bewusst KEINE Tat, kein Halluzinieren
         * eines Effekts. Ehrlich + kurz ablehnen, statt einen Erfolg vorzutäuschen
         * (0.5-Essenz „kein Fake-Confirm"). Bewusst getrennt von der [agenticRefusal]
         * (defektes/unbekanntes Tool), damit der bewusste Verzicht anders klingt als ein Defekt.
         */
        const val AGENTIC_NONE_DE = "Das kann ich (noch) nicht."
        const val AGENTIC_NONE_EN = "That's something I can't do (yet)."

        /** Pure, deterministische Auswahl der **AGENTISCHEN „none"-Absage** nach Turn-Sprache. */
        fun agenticNone(language: Language): String = language.deOr(AGENTIC_NONE_DE, AGENTIC_NONE_EN)
    }
}
