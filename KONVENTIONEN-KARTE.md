# Konventions-Karte für die zwei Stabilitäts-Fixes (vom Leser-Sub-Agenten, 21.08.)

## Honest-Fallback (never silent)
- Wrapper: TurnOrchestrator.kt:1965 `neverSilent(...)`; Error-Branch :2043 →
  `fallbackStream(provider, errorFallback(language), stageTimings())` (:2729-2737).
- Timeout-Klassifikator: :1896 `isTimeout(error)` — läuft die Cause-Kette ab;
  ein neuer TimeoutException landet AUTOMATISCH im errorFallback-Zweig.
- Phrasen: ERROR_FALLBACK_DE/EN sind Companion-Konstanten (:2887f, nur DE/EN
  via `deOr`). ES/FR/IT bekommen EN! Der 5-Sprachen-Weg ist LanguagePack:
  bestes Vorbild für eine neue Budget-Phrase = `escalationUnavailable`
  (LanguagePack.kt:168 + alle 5 Lang*.kt; bewusst EIN String, kein Pool).
  KEINE „Brain zu langsam"-Phrase existiert — heute spricht ein Brain-Timeout
  den generischen errorFallback.

## Diary-Konvention (brainTimeout existiert schon!)
- Wire: ChatEvent.StageTimings :376 `brainTimeout: Boolean? = null` — additive
  AT LINE END (LL-2026-08-11); false bleibt ABWESEND (byte-stabil).
- Diary: TurnTracePort.kt:217 `brainTimeout: Boolean = false`;
  JsonlTurnTraceAdapter.kt:150 ans Zeilenende.
- Tap: TurnDiaryTap.kt:288 liest es aus Done.stageTimings.
- Muster im Orchestrator (:1662-1690): AtomicBoolean-Latch via
  `doOnError { if (isTimeout(it)) brainTimedOut.set(true) }` + deferred
  stageTimings-Supplier. Für ein ÄUSSERES Turn-Deadline gilt das
  Merge-Muster `(ev.stageTimings ?: StageTimings()).copy(...)`
  (BrainAdmissionGate.kt:118f, TtsStage.kt:220).

## Timeout-Property-Konvention
- KEIN @ConfigurationProperties; application.yml = 5 Zeilen ohne hoshi:-Block.
  Defaults leben inline im @Value UND als Konstruktor-Default (dupliziert).
- Präzedenz für Ops-Knöpfe: PipelineConfig.kt:281-284
  `@Value("\${HOSHI_BRAIN_CHAT_TIMEOUT_SECONDS:20}")` — SCREAMING_SNAKE, weil
  Wedge-Forensik den Wert ohne Rebuild aus der systemd-Unit drehen will;
  Unit-Zeile = `Environment=HOSHI_...=x` (tools/systemd/hoshi-0.8-backend.service,
  aktuell existiert KEIN HOSHI_*TIMEOUT* dort → 20s-Default ist live).
- Brain: MlxBrainAdapter.kt:75 `chatTimeoutSeconds: Long = 20`, angewandt :225
  `.timeout(...)` PRO VERSUCH innerhalb Flux.defer; Retry :179-184.
- HaAreaCatalogAdapter: 2 Konstruktions-Stellen PipelineConfig.kt:825 + :906
  (keine Timeout-Übergabe); Adapter-Defaults :68f ttl=15min, timeoutMs=5000 —
  ACHTUNG: timeoutMs ist NUR connectTimeout (:81-83), es gibt KEINEN
  Read-Timeout auf dem HttpClient!
- Turn-Deadline-Vorbild: Companion-Duration + ctor-Param, injizierbar für
  Tests: ESCALATION_LOOKUP_TIMEOUT (:2901→:313→:2554),
  AGENTIC_COLLECT_TIMEOUT (:2842), STT_SURPRISAL_TIMEOUT (:2796).
