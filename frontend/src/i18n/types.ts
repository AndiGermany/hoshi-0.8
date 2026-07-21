import type { FiredKind } from '../hooks/useFiredItems';

// ─────────────────────────────────────────────────────────────────────────────
//  UI-Sprache (Andi-Auftrag 21.07: „Ich muss die Sprache der UI auch in den
//  Einstellungen auswählen können") — Orchestrator-Entscheid: die EINE
//  bestehende Sprachwahl (LanguageSection.tsx / api/languageSettings.ts,
//  Server-Default de/en/es/fr/it, s. dort) steuert künftig AUCH die UI-Texte.
//  KEIN zweiter Selector. Dieses Modul ist die Katalog-Schicht: `de` ist die
//  Quelle der Wahrheit (Referenz auf die bestehenden TEXTS-Konstanten in den
//  Komponenten, byte-gleich — s. de.ts), en/es/fr/it sind vollständige
//  Übersetzungen derselben Keys.
// ─────────────────────────────────────────────────────────────────────────────

/** Dieselben fünf Codes wie das Sprachpaket (LanguagePackRegistry/api/languageSettings). */
export const SUPPORTED_UI_LANGUAGES = ['de', 'en', 'es', 'fr', 'it'] as const;

export type UiLanguage = (typeof SUPPORTED_UI_LANGUAGES)[number];

/** Ehrliche Texte des Wetter-Ort-Settings (Shape von WEATHER_LOCATION_TEXTS). */
export interface WeatherLocationStrings {
  save: string;
  saving: string;
  notFound: string;
  locked: string;
  failed: string;
  loadError: string;
  seedSuffix: string;
  hint: string;
  saved: (label: string) => string;
}

/** Ehrliche Texte des Lookup-Modell-Settings (Shape von LOOKUP_MODEL_TEXTS). */
export interface LookupModelStrings {
  label: string;
  hint: string;
  loadError: string;
  switching: string;
  unknown: string;
  failed: string;
  priceSuffix: (cents: number) => string;
}

/** Ehrliche Texte des TTS-Engine-Settings (Shape von TTS_ENGINE_TEXTS). */
export interface TtsEngineStrings {
  label: string;
  hint: string;
  loadError: string;
  switching: string;
  unavailable: (hinweis: string) => string;
  unknown: string;
  failed: string;
  active: string;
  available: string;
  notStarted: string;
}

/** Ehrliche Texte der Stimmen-Sektion (Shape von STIMME_TEXTS). */
export interface StimmeStrings {
  label: string;
  loadError: string;
  switching: string;
  unknownVoice: string;
  failed: string;
  cloudBadge: string;
  cloudLine: string;
  cloudPrivacy: string;
  localLine: string;
  localPrivacy: string;
}

/** Ehrliche Texte des Brain-Modell-Settings (Shape von BRAIN_MODEL_TEXTS). */
export interface BrainModelStrings {
  label: string;
  hint: string;
  loadError: string;
  switching: (label: string) => string;
  timeout: string;
  unknown: string;
  switchUnavailable: string;
  failed: string;
  statusOk: string;
  statusLoading: string;
  statusUnreachable: string;
}

/** Ehrliche Notiz-Texte des Lösch-Flows (Shape von PRIVACY_TEXTS). */
export interface PrivacyStrings {
  confirm: string;
  delete: string;
  deleting: string;
  notYet: string;
  failed: string;
  loadError: string;
}

/** Alle sichtbaren Texte der Sprecher-Sektion (Shape von SPEAKER_TEXTS). */
export interface SpeakerStrings {
  groupTitle: string;
  intro: string;
  consent: string;
  empty: string;
  enrollButton: string;
  delete: string;
  confirm: string;
  deleting: string;
  deleteFailed: string;
  enrolledNote: string;
  loadError: string;
  dialogTitle: string;
  dialogIntro: string;
  nameLabel: string;
  nameHint: string;
  nameInvalid: string;
  recordSample: string;
  recordingHint: string;
  finish: string;
  cancel: string;
  saving: string;
  sampleSaved: string;
  nextUp: string;
  partialHint: string;
  done: string;
  close: string;
  retry: string;
  errorPartialHint: string;
  abortedNote: string;
  insecure: string;
  noMic: string;
  genericFail: string;
}

/** Alle sichtbaren Texte des Nachtmodus (Shape von NIGHT_MODE_TEXTS). */
export interface NightModeStrings {
  groupTitle: string;
  intro: string;
  loadError: string;
  empty: string;
  emptyHint: string;
  manualLabel: string;
  manualPlaceholder: string;
  manualButton: string;
  manualNotFound: string;
  onlineHint: string;
  neverSeenHint: string;
  offlineHint: (when: string) => string;
  master: string;
  modeSchedule: string;
  modeAlways: string;
  fromLabel: string;
  toLabel: string;
  dimLabel: string;
  save: string;
  saving: string;
  saved: string;
  locked: string;
  invalid: string;
  failed: string;
}

/**
 * Alle sichtbaren Texte der Sprach-Sektion (Shape von LANGUAGE_SETTINGS_TEXTS)
 * + `uiNotice` (NEU, Andi-Auftrag 21.07): der ehrliche Hinweis, dass UI +
 * Gespräch dieser Wahl folgen, Smart-Home-Befehle aber vorerst Deutsch bleiben.
 */
export interface LanguageSettingsStrings {
  label: string;
  hint: string;
  loadError: string;
  switching: string;
  unknown: string;
  failed: string;
  betaSuffix: string;
  uiNotice: string;
}

/** Die zwei Record-Dictionaries des Klingel-Banners (FIRED_HEADLINE/MISSED_NOUN). */
export interface FiredToastStrings {
  headline: Record<FiredKind, string>;
  missedNoun: Record<FiredKind, string>;
}

/** Sichtbare Texte der Aktivitätsansicht. */
export interface ActivityStrings {
  stateOnline: string;
  stateOffline: string;
  stateChecking: string;
  noData: string;
  noStageData: string;
  noStageValues: string;
  stageBreakdown: string;
  rest: string;
  total: string;
  title: string;
  lede: string;
  stageLatencyTitle: string;
  stageLatencyHint: string;
  diaryUnavailable: string;
  turnFeedTitle: string;
  refresh: string;
  turnFeedHint: string;
  diaryUnavailableRetry: string;
  diaryEmpty: string;
  deflected: string;
  deflectedTitle: string;
  error: string;
  errorStage: (stage: string) => string;
  privacy: string;
  healthTitle: string;
  healthHint: string;
  noObservation: string;
  backendState: (state: string) => string;
}

/** Sichtbare Texte der Raumansicht. */
export interface RoomsStrings {
  sketchRoom: string;
  sketchAria: string;
  pickerAria: (name: string) => string;
  assigning: string;
  chooseRoom: string;
  deviceCount: (count: number) => string;
  roomEmpty: string;
  allAssigned: string;
  unassignedEditable: string;
  unassignedReadOnly: string;
  unassigned: string;
  pendingTitle: string;
  notWired: string;
  unreachable: string;
  unreachableNote: string;
  title: string;
  ledeEditable: string;
  ledeReadOnly: string;
  loading: string;
  offNote: string;
  idea: string;
  ideaHint: string;
  assignFailed: string;
}

/** Sichtbare Texte der Übersichtsansicht. */
export interface OverviewStrings {
  heroUpTitle: string;
  heroUpSub: string;
  heroDownTitle: string;
  heroDownSub: string;
  heroUnknownTitle: string;
  heroUnknownSub: string;
  sidecarHealthNote: string;
  voiceStatsNote: string;
  devicesNote: string;
  backend: string;
  sidecarHealth: string;
  voiceStats: string;
  devices: string;
  live: string;
  notWired: string;
  backendNote: string;
  chatTurn: string;
  liveStreaming: string;
  chatTurnNote: string;
  authToken: string;
  set: string;
  missing: string;
  authSetNote: string;
  authMissingNote: string;
  title: string;
  lede: string;
  lastChecked: (time: string) => string;
  liveWired: string;
  notWiredTitle: string;
  notWiredHint: string;
}

export type DayPart = 'night' | 'morning' | 'day' | 'evening';

/**
 * Sichtbare Texte der Top-Nav (Andi-Auftrag 21.07, Video-Tag-Befund: die vier
 * Reiter-Labels + Bedienelemente riefen `useUiStrings` bisher NICHT auf und
 * blieben darum deutsch, egal welche Sprache aktiv war).
 */
export interface TopNavStrings {
  overview: string;
  chat: string;
  rooms: string;
  activity: string;
  mainNav: string;
  openSettingsAria: string;
  settingsTitle: string;
}

/**
 * Sichtbare Texte des Aoi-Idle-„Zuhause"-Gesichts (IdleFace) — derselbe
 * Video-Tag-Befund wie {@link TopNavStrings}. `dueClock`/`fmtRemaining`/
 * `KIND_WORD` (aus `hooks/useScheduledItems.ts`) und `codeText` (aus
 * `hooks/useWeatherToday.ts`, vom Backend als deutscher WMO-Lagen-Text
 * geliefert) bleiben außerhalb dieser Scheibe und darum deutsch — siehe
 * Kommentare in IdleFace.tsx.
 */
export interface IdleFaceStrings {
  sectionAria: string;
  greeting: (dayPart: DayPart) => string;
  noAlarm: string;
  alarmLine: (clock: string, remaining: string) => string;
  alarmTrustText: string;
  alarmTrustTitle: string;
  live: string;
  pending: string;
  heute: {
    name: string;
    turnOne: string;
    turnMany: string;
    outageWord: string;
    noTurnYet: string;
    noteUnavailable: string;
    noteEmpty: string;
    noteWithData: string;
  };
  geplant: {
    name: string;
    nichtsGeplant: string;
    note: string;
  };
  wetter: {
    name: string;
    loadingNote: string;
    liveNote: (place: string) => string;
    offNote: string;
    unreachableNote: string;
    settingsAria: string;
    settingsTitle: string;
  };
  status: {
    online: string;
    offline: string;
    checking: string;
    voiceCloud: string;
    voiceLocal: string;
  };
}

/** Sichtbare Texte der Chatansicht. */
export interface ChatStrings {
  suggestions: readonly [string, string, string];
  waveTap: string;
  micIdle: string;
  micListening: string;
  micTranscribing: string;
  micResponding: string;
  ttsOn: string;
  ttsOff: string;
  greeting: (dayPart: DayPart) => string;
  speakerSettingsAria: string;
  manageSpeakers: string;
  recordingUnderstood: string;
  sourcesTitle: string;
  sources: string;
  micAria: string;
  discardRecording: string;
  discard: string;
  speaking: string;
  processingRecording: string;
  transcribing: string;
  thinking: string;
  placeholder: string;
}

/** Status- und Fehlertexte des geteilten Voice-Chat-Hooks. */
export interface VoiceChatStrings {
  slowTurn: string;
  connection: string;
  errorStage: (stage: string) => string;
  noAudioHeard: string;
}

/** Der komplette UI-Text-Katalog EINER Sprache — jede Sprache implementiert exakt diese Form. */
export interface UiStrings {
  locale: string;
  topNav: TopNavStrings;
  idleFace: IdleFaceStrings;
  weatherLocation: WeatherLocationStrings;
  lookupModel: LookupModelStrings;
  ttsEngine: TtsEngineStrings;
  stimme: StimmeStrings;
  brainModel: BrainModelStrings;
  privacy: PrivacyStrings;
  speaker: SpeakerStrings;
  nightMode: NightModeStrings;
  language: LanguageSettingsStrings;
  firedToast: FiredToastStrings;
  activity: ActivityStrings;
  rooms: RoomsStrings;
  overview: OverviewStrings;
  chat: ChatStrings;
  voiceChat: VoiceChatStrings;
  turnAnatomy: TurnAnatomyStrings;
}

/** Denk-Stufen-Zeile über der Antwort (TurnAnatomy) — jede Stufe IST passiert. */
export interface TurnAnatomyStrings {
  heard: string;
  understood: string;
  route: string;
  answering: string;
  speaking: string;
  /** Sprecher-Chip, z. B. „erkannt: Andi". */
  recognized: (who: string) => string;
  /** Unter der Erkennungsschwelle wird NIE ein Name geraten. */
  guest: string;
  /** aria-label der Zeile. */
  rowLabel: string;
}
