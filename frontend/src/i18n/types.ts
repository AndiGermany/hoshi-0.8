import type { FiredKind } from '../hooks/useFiredItems';
import type { ScheduledKind } from '../hooks/useScheduledItems';
import type { Persona, Theme, ThemeGroupId } from '../hooks/useSettings';
import type { Language } from '../api/types';
import type { PrivacyTarget } from '../api/privacy';
import type { SettingsCategoryId } from '../components/SettingsPanel';
import type { EscalationModeWire } from '../api/extendedThink';

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
  /** Feld-Label über dem Ortsnamen-Input (Andi-Sweep 24.07: fehlte im Katalog). */
  label: string;
  /** Platzhaltertext des leeren Inputs, z. B. „z. B. Duisburg". */
  placeholder: string;
  /** Ehrliche „lädt…"-Zeile, solange der Ist-Zustand noch nicht da ist. */
  loading: string;
  /** „Aktuell: {Ort}" vor dem Input. */
  current: (label: string) => string;
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
  /** Ehrliche „lädt…"-Zeile, solange der Ist-Zustand noch nicht da ist. */
  loading: string;
}

/** Titel + EIN erklärender Satz einer Extended-Think-Stufe (Shape der Radio-Karten). */
export interface EscalationModeEntryStrings {
  title: string;
  description: string;
}

/**
 * Ehrliche Texte der Extended-Think-Stufenwahl (Andi-Auftrag 26.07: „die
 * Eskalations-Stufe hat KEIN UI-Element" — vorher nur Backend). Vier
 * beschriftete Auswahl-Karten, Reihenfolge nach Online-Grad ({@link
 * ../api/extendedThink.ESCALATION_MODES}).
 */
export interface ExtendedThinkStrings {
  label: string;
  hint: string;
  loadError: string;
  switching: string;
  unknown: string;
  /** Ehrlicher Hinweis, wenn die Deploy-Zeit-Decke zu ist (Auswahl bleibt sichtbar, greift aber nicht). */
  locked: string;
  failed: string;
  /** Badge auf der empfohlenen Stufe (ERST_FRAGEN, zugleich der Laufzeit-Default). */
  recommendedBadge: string;
  modes: Record<EscalationModeWire, EscalationModeEntryStrings>;
  /** Ehrliche „lädt…"-Zeile, solange der Ist-Zustand noch nicht da ist. */
  loading: string;
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
  /**
   * Anzeigenamen der Engines (EN-Sweep 25.07: „macOS say (lokal)" & Co. waren
   * eine hartkodierte deutsche Modul-Konstante und standen damit auch in der
   * englischen Oberfläche). Unbekannte Ids rendern weiterhin as-is.
   */
  engineLabels: Record<string, string>;
  /** Ehrliche „lädt…"-Zeile, solange der Ist-Zustand noch nicht da ist. */
  loading: string;
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
  /** Ehrliche „lädt…"-Zeile, solange der Ist-Zustand noch nicht da ist. */
  loading: string;
  /** aria-label des Hörprobe-Knopfs, z. B. „Hörprobe der Stimme X abspielen". */
  sampleAria: (voice: string) => string;
  /** title des Hörprobe-Knopfs. */
  sampleTitle: string;
  /** Fallback-Hinweis, wenn die aktive Engine (noch) keine Stimmen anbietet. */
  noVoicesFor: (engine: string) => string;
  /** „Lizenz: {lizenz}" unter der gewählten Stimme. */
  licensePrefix: (license: string) => string;
  /** Leise Fehlzeile, wenn die Hörprobe scheitert (503/Netz/Audio-Decode). */
  sampleFailed: string;
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
  /** Ehrliche „lädt…"-Zeile, solange der Ist-Zustand noch nicht da ist. */
  loading: string;
  /** Platzhalter-Option im Select, solange kein aktives Modell bekannt ist. */
  statusReading: string;
  /** Präfix vor dem Status-Wort, z. B. „Status: läuft". */
  statusPrefix: string;
  /**
   * Ehrlicher Zusatz-Hinweis UNTER dem bestehenden [hint], NUR sichtbar wenn die
   * automatische Modellwahl an ist (Andi-Auftrag „12B für Chat, e4b für Voice",
   * 2026-07-26): die manuelle Auswahl hier bleibt funktionsfähig, setzt dann aber
   * nur noch das CHAT-Modell (Voice bleibt e4b, unabhängig von dieser Auswahl).
   */
  autoSwitchNote: string;
}

/**
 * Ehrliche Texte des `brainAutoSwitch`-Toggles (Shape von BRAIN_AUTO_SWITCH_TEXTS,
 * Andi-Auftrag „12B für Chat, e4b für Voice", 2026-07-26): EINE Karte unter der
 * Brain-Modell-Auswahl in „Modell & Leistung".
 */
export interface BrainAutoSwitchStrings {
  label: string;
  /** Der erklärende Satz unter dem Schalter — WAS die Automatik tut. */
  hint: string;
  loadError: string;
  /** Fehlzeile, wenn ein Umschalten des Settings selbst fehlschlägt (Netz/5xx). */
  failed: string;
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

/**
 * Exakt neun Nachsprech-Sätze — drei unabhängige Sitzungen à drei Sätze (Sätze 1–3 =
 * Sitzung 1/Gruppe 1, 4–6 = Sitzung 2/Gruppe 2, 7–9 = Sitzung 3/Gruppe 3). Der Tuple-Typ
 * erzwingt in ALLEN fünf Katalogen genau neun Einträge (Andi-Auftrag 25.07: drei
 * unabhängige Sitzungen an verschiedenen Tagen/Räumen/Mikros statt dreimal derselbe
 * Sitzung — die Sätze selbst waren bisher zudem eine deutsche Modul-Konstante).
 */
export type EnrollSentences = readonly [
  string,
  string,
  string,
  string,
  string,
  string,
  string,
  string,
  string,
];

/**
 * Fehltexte von `api/speakers.ts` (`enrollSpeaker`, Shape von
 * `SpeakerEnrollErrorKind`) — s. KDoc bei {@link SpeakerStrings.enrollErrors}.
 */
export interface SpeakerEnrollErrorStrings {
  /** 401 — Token fehlt/ungültig. */
  auth: string;
  /** 400 — Name passt nicht auf `SPEAKER_NAME_PATTERN`. */
  badName: string;
  /** 409 — Folge-Sample ohne bestehendes Profil (Satz 1 fehlt/verloren). */
  outOfSync: string;
  /** 422 — Audio zu kurz/leise. */
  tooShort: string;
  /** 502 — der Sidecar lieferte kein Embedding. */
  noEmbedding: string;
  /** Sonstiger `!ok`-Status. */
  unknown: (status: number) => string;
  /** 2xx, aber die Antwort ließ sich nicht als {@link ../api/speakers.SpeakerSummary} lesen. */
  unreadable: string;
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
  /** Ehrliche „lädt…"-Zeile, solange die Sprecher-Liste noch nicht da ist. */
  loading: string;
  /** Die neun Nachsprech-Sätze — Gruppe 1 (Index 0–2) ist in `de.ts` byte-gleich zum alten Stand. */
  sentences: EnrollSentences;
  /** „Sitzung i von 3" — zusätzlich zu {@link sampleProgress} sichtbar, s. `SpeakerSection.tsx`. */
  sessionLabel: (session: number) => string;
  /** Fertig-Text NACH Sitzung 1 oder 2 (noch NICHT die letzte): ehrlich „anderer Tag, andere Sätze". */
  sessionDoneHint: (session: number) => string;
  /** Abbruch einer ANGEHÄNGTEN Sitzung: nichts wird gelöscht, nur ehrlich „war unvollständig". */
  sessionIncompleteNote: string;
  /** Status-Badge in der Profil-Liste: Profil hat noch keine 9 Sätze. */
  statusInProgress: string;
  /** Status-Badge in der Profil-Liste: Profil hat alle 9 Sätze (3 Sitzungen komplett). */
  statusComplete: string;
  /**
   * Fehltexte von {@link ../api/speakers.SpeakerEnrollError} (`api/speakers.ts`,
   * `enrollSpeaker`) — landen WÖRTLICH im Anlern-Dialog (`EnrollDialog.
   * finishRecording`s Catch zeigt `err.message` direkt). `api/speakers.ts` hat
   * keinen React-Hook-Zugriff — liest darum synchron aus dem AKTIVEN UI-Katalog
   * (Muster {@link ApiErrorStrings}/`api/chat.ts`).
   */
  enrollErrors: SpeakerEnrollErrorStrings;
  /**
   * Fünf-Sprachen-Sweep 2026-07-27: die Restschuld aus SpeakerSection.tsx, die
   * bisher hart im JSX/in Modul-Funktionen stand (byte-gleich zum Bestand nach
   * `de`, s. `formatEnrolledDate`/`sampleProgress` dort).
   */
  /** Fallback von {@link ../components/SpeakerSection.formatEnrolledDate}, wenn kein/kein gültiges Datum da ist. */
  justNow: string;
  /** Datums-Zeile in der Profil-Liste, z. B. „angelernt 15. Juli 2026". */
  enrolledOn: (date: string) => string;
  /** Sätze-Zähler in der Profil-Liste, sprachrichtig gebeugt: „1 Satz" / „3 Sätze". */
  sentenceCount: (count: number) => string;
  /** „Satz i von n" (Anlern-Knopf/Status) — ersetzt {@link ../components/SpeakerSection.sampleProgress}. */
  progress: (sample: number, total: number) => string;
  /** Umschließt einen Anlern-Satz mit den sprachtypischen Anführungszeichen. */
  quote: (text: string) => string;
  /** aria-label des Lösch-Knopfs einer Profil-Zeile, z. B. „Profil Andi löschen". */
  deleteProfileAria: (name: string) => string;

  // ── Reparatur-Auftrag 07.08 (Kreuz-Kontaminations-Vorfall: zwei Haushaltsmitglieder lernten gleichzeitig
  // im selben Raum an, Löschen war zu leichtgängig, ein Profil ließ sich nicht per Klick
  // fortsetzen, es gab nur Ganz-Profil-Löschung) ─────────────────────────────────────────

  /** „Weiter anlernen"-Knopf einer Profil-Zeile — öffnet den Dialog vorausgefüllt+gesperrt. */
  continueButton: string;
  /** Tooltip am disabled „Weiter anlernen" eines vollen Profils (9/9) — stiller Ersatz-Neustart ist verboten. */
  continueFullHint: string;
  /** aria-label des Fortsetzen-Knopfs, z. B. „Andi weiter anlernen". */
  continueAria: (name: string) => string;
  /**
   * Fester Hinweis im Anlern-Dialog (Intro-Schritt) — der Kreuz-Kontaminations-Vorfall:
   * ZWEI Menschen lernten gleichzeitig im selben Raum an, beide Profile mussten gewiped
   * werden. Ruhig, nicht alarmistisch.
   */
  soloEnrollHint: string;
  /** <summary>-Text des aufklappbaren Aufnahmen-Bereichs einer Profil-Zeile. */
  recordingsToggle: (count: number) => string;
  /** Fehlzeile, wenn die Aufnahmen-Diagnose (GET .../diagnostics) grad nicht lesbar ist. */
  recordingsLoadError: string;
  /** leaveOneOutSimilarity > 0.6 — ruhiger Text statt Rohzahl (die steht im `title`). */
  fitGood: string;
  /** leaveOneOutSimilarity 0.35..0.6. */
  fitMedium: string;
  /** leaveOneOutSimilarity < 0.35 — mögliches Warnsignal (kontaminiert/verrutscht). */
  fitPoor: string;
  /** Kein Vergleichswert da (Profil hat nur 1 Aufnahme — nichts zu leaven). */
  fitUnknown: string;
  /** Fallback für fehlendes Datum/Dauer EINER Aufnahme (Alt-Aufnahme, WAV nicht geparst). */
  recordingUnknown: string;
  /** Dauer EINER Aufnahme, z. B. „2,3 s". */
  recordingDuration: (seconds: number) => string;
  /** Einzel-Löschen-Knopf EINER Aufnahme. */
  deleteRecording: string;
  /** aria-label des Einzel-Löschen-Knopfs, z. B. „Aufnahme 2 löschen". */
  deleteRecordingAria: (index: number) => string;
  /** Hinweis/Tooltip, wenn der Einzel-Löschen-Knopf gesperrt ist (letzte Aufnahme). */
  deleteRecordingLastHint: string;
  /** Fehlzeile, wenn das Einzel-Löschen einer Aufnahme fehlschlägt. */
  deleteRecordingFailed: string;
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
  /** Ehrliche „lädt…"-Zeile, solange die Geräteliste noch nicht da ist. */
  loading: string;
  /** aria-label der Geräte-Radiogroup (Andi-Sweep 24.07: fehlte im Katalog). */
  deviceGroupAria: string;
  /** aria-label der Modus-Radiogroup (Zeitplan/Immer an). */
  modeGroupAria: string;
}

/** Die Zukunfts-Skills der Fähigkeiten-Sektion (ehrlich ausgegraut, ohne Toggle). */
export type FutureSkillId = 'LISTS' | 'MUSIC';

/**
 * Sichtbare Texte der Skills-Sektion (EN-Sweep 25.07). Die Skill-NAMEN selbst
 * kommen vom Draht (`labelDe`/`labelEn` der Registry) und bleiben darum
 * außerhalb des Katalogs; alles, was das Frontend selbst textet — Hinweis,
 * Lade-Zeile, Badges und die Zukunfts-Skills — steht hier in allen fünf
 * Sprachen. Vorher waren Hinweis/„lädt…" hartkodiert Deutsch und die Badges
 * hingen an einer de/en-Sonderlocke, die der CHAT-Sprache folgte.
 */
export interface SkillsStrings {
  hint: string;
  loading: string;
  /** Badge eines beim Deploy abgeschalteten Skills (Toggle bleibt gesperrt). */
  badgeLocked: string;
  /** Badge eines Skills der Stufe EGRESS (verlässt das Gerät). */
  badgeEgress: string;
  /** Badge der Zukunfts-Skills (noch nicht gebaut). */
  badgeSoon: string;
  future: Record<FutureSkillId, { label: string; reason: string }>;
  /** Fallback-Fehlzeile, wenn der Skills-GET ohne Message scheitert (useSkills). */
  loadFailed: string;
  /** Fallback-Fehlzeile, wenn der Skill-PUT ohne Message scheitert (useSkills). */
  toggleFailed: string;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Langschwanz-Sweep 25.07 (Fortsetzung von 380c779): der bewusst vertagte Rest
//  des Einstellungs-Panels, der Timer-/Wecker-Zeile über der Eingabe, der
//  Ops-Pille in der Kopfzeile, der API-Fehlertexte (landen wörtlich als
//  Chat-Blase) und des Sprecher-Chips. DE bleibt byte-gleich zum Hardcode.
// ─────────────────────────────────────────────────────────────────────────────

/** Ein Farbthema im Panel: Name + kurzer Charakter-Hinweis. */
export interface ThemeEntryStrings {
  label: string;
  hint: string;
}

/**
 * Eine Gruppe im Farbthema-Picker (Andi 25.07 „übersichtlicher"): Überschrift +
 * EINE ruhige Zeile, die sagt, was diese Gruppe von den anderen unterscheidet.
 */
export interface ThemeGroupStrings {
  title: string;
  note: string;
}

/** Eine Persönlichkeit im Panel: Name, Beschreibung und gesprochener Beispielsatz. */
export interface PersonaEntryStrings {
  label: string;
  description: string;
  /** EIN sprechbarer Beispielsatz im echten Ton dieser Persona. */
  sample: string;
}

/**
 * Der Langschwanz des Einstellungs-Drawers: alles, was {@link
 * ../components/SettingsPanel} selbst textet und bis 25.07 als deutsche
 * Modul-Konstante (SETTINGS_CATEGORIES, THEMES, LANGUAGES, PERSONAS,
 * PRIVACY_UNITS) oder als hartkodiertes JSX dastand — auch in der englischen
 * Oberfläche.
 */
export interface SettingsPanelStrings {
  /** Die sieben Reiter-Labels (Reihenfolge/Ids liegen im Panel, nicht hier). */
  categories: Record<SettingsCategoryId, string>;
  /** aria-label der Reiter-Leiste (`role="tablist"`). */
  categoryNavAria: string;
  themeLabel: string;
  /** aria-label der Farbthema-Radiogroup. */
  themeGroupAria: string;
  themes: Record<Theme, ThemeEntryStrings>;
  /** Überschrift + Einordnungs-Zeile je Gruppe des Pickers (Reihenfolge liegt in THEME_GROUPS). */
  themeGroups: Record<ThemeGroupId, ThemeGroupStrings>;
  /**
   * Das Beiwort je Thema — die Übersetzung des japanischen Namens („Nagareboshi
   * · Sternschnuppe"). Bewusst NEBEN {@link ThemeEntryStrings}, nicht darin:
   * `THEMES` (useSettings) spiegelt diesen Katalog-Eintrag 1:1, und dessen Form
   * ist von Bestandstests gepinnt.
   */
  themeGlosses: Record<Theme, string>;
  /** Wie das Beiwort an den Namen tritt (Trennzeichen inklusive Sprach-Typografie). */
  themeGlossSuffix: (gloss: string) => string;
  /** Die Sora-Zeile: „folgt dem Tag · jetzt Kasumi" (was die Regel GERADE zeigt). */
  themeSoraNow: (themeName: string) => string;
  /** Trennzeichen des nicht klickbaren Tagesbogens (Nagareboshi › Asa › …). */
  themeArcSeparator: string;
  /** aria-label des Tagesbogens unter Sora (reine Vorschau, nicht wählbar). */
  themeArcAria: string;
  /** Leiser Hinweis, wenn ein Rotations-Theme fest gewählt ist (Automatik pausiert). */
  themePinnedNote: (themeName: string) => string;
  /** aria-label einer Theme-Karte: Gruppe + Name, z. B. „Tageszeiten: Asa". */
  themeOptionAria: (groupTitle: string, themeName: string) => string;
  languageLabel: string;
  /** Anzeigenamen der drei Chat-/STT-Sprachwahlen (auto/de/en). */
  languages: Record<Language, string>;
  languageHint: string;
  languageAutoHint: string;
  personaLabel: string;
  personas: Record<Persona, PersonaEntryStrings>;
  /** Die Hörproben-Zeile inkl. sprach-eigener Anführungszeichen. */
  personaSample: (sample: string) => string;
  escalationLabel: string;
  /** Einheit rechts neben dem Zahl-Input („Sekunden"). */
  escalationUnit: string;
  escalationHint: (seconds: number) => string;
  /** Überschrift der Skills-Gruppe (der Eigenname „Skills" bleibt überall gleich). */
  skillsTitle: string;
  privacyTitle: string;
  privacyIntro: string;
  privacyLoading: string;
  /** „Stimme (TTS): {engine}" — der einzige Egress-Pfad, ehrlich benannt. */
  privacyVoiceLine: (engine: string) => string;
  privacyVoiceCloud: string;
  privacyVoiceLocal: string;
  privacySanitizeOn: string;
  privacySanitizeOff: string;
  privacySanitizeOnDetail: string;
  privacySanitizeOffDetail: string;
  privacyMemoryLine: string;
  privacyEpisodicLine: string;
  privacyDiaryLine: string;
  /** Kurz-Label je Lösch-Knopf (fließt in `{label} löschen`). */
  privacyTargetLabels: Record<PrivacyTarget, string>;
  /** aria-label des Lösch-Knopfs, z. B. „Gedächtnis löschen". */
  privacyDeleteAria: (label: string) => string;
  /** Detailzeile der beiden lokalen Stores („lokale Datei · …"). */
  privacyLocalFile: (detail: string) => string;
  privacyStoreEmpty: string;
  privacyStoreUnreadable: string;
  privacyStoreEntries: (entries: number) => string;
  /** Aufzeichnung aus, alte Einträge liegen noch da. */
  privacyStoreDisabled: (count: string) => string;
  privacyDiaryDetail: (days: number) => string;
  /** Erfolgs-Notiz nach dem Löschen („Gelöscht: 3 Einträge."). */
  privacyDeleted: (deleted: number, target: PrivacyTarget) => string;
}

/**
 * Timer-/Wecker-Zeile über der Eingabe ({@link ../hooks/useScheduledItems} +
 * {@link ../components/ScheduledPanel}). Die Uhrzeit selbst formatiert
 * `dueClock` jetzt über {@link UiStrings.locale} statt hart „de-DE".
 */
export interface ScheduledStrings {
  /** Kind-ehrliche Nomen: TIMER→„Timer", ALARM→„Wecker", REMINDER→„Erinnerung". */
  kindWord: Record<ScheduledKind, { one: string; many: string }>;
  /** Restdauer unter einer Minute. */
  remainingUnderMinute: string;
  remainingMinutes: (minutes: number) => string;
  remainingHours: (hours: number) => string;
  remainingHoursMinutes: (hours: number, minutes: number) => string;
  /** Ein Item: „Timer · noch 44 min". */
  lineOne: (word: string, remaining: string) => string;
  /** Mehrere: „2 Timer · nächster in 12 min". */
  lineMany: (count: number, word: string, remaining: string) => string;
  /** Wecker-Zeile einer Verwaltungs-Zeile: „um 07:00". */
  atClock: (clock: string) => string;
  /** Timer-/Erinnerungs-Zeile einer Verwaltungs-Zeile: „noch 12 min". */
  inRemaining: (remaining: string) => string;
  /** aria-label der ganzen Sektion. */
  panelAria: string;
  /** title des Aufklapp-Knopfs, wenn er offen ist. */
  collapse: string;
  /** title des Aufklapp-Knopfs, wenn er zu ist. */
  expand: string;
  /** Knopf-Text, solange (noch) keine Zusammenfassung da ist. */
  fallbackSummary: string;
  /** Aufgeklappt, aber leer. */
  empty: string;
  /** aria-label des ✕-Knopfs, z. B. „Timer „Tee" löschen". */
  deleteAria: (word: string, label?: string) => string;
  /** title des ✕-Knopfs. */
  deleteTitle: string;
  deleteAll: string;
}

/** Sichtbare Texte der Ops-Pille in der Kopfzeile ({@link ../components/OpsStatusPill}). */
export interface OpsStrings {
  toneWarn: string;
  toneCritical: string;
  ramCritical: string;
  ramWarn: string;
  /**
   * Andi-Auftrag 2026-07-25/26 (Speicherdruck sichtbar statt Auto-Switch): der
   * warme, user-verständliche Hinweis, zu dem die Pille wird, sobald
   * `memory.level==="CRITICAL"` — ersetzt dort das technische `ramCritical` als
   * Pillen-Text (Panel/RAM-Zeile nennt weiterhin den nackten Pegel). Erklärt die
   * SPÜRBARE Folge (Stimme kann zäh werden) statt nur eine Kennzahl zu nennen —
   * genau das, was beim realen Vorfall (Whisper 45s stumm bei grünem Health)
   * gefehlt hat.
   */
  memoryCriticalHint: string;
  /** Der title/aria-label-Satz: „Ops: Gesamt OK · RAM WARN". */
  title: (overall: string, level: string) => string;
  /** Anhang mit dem ehrlichen Detail: „ — RAM-Druck steigt.". */
  titleDetail: (detail: string) => string;
  voiceCloud: string;
  voiceLocal: (engine: string) => string;
  allLocal: string;
}

/**
 * Fehlertexte der API-Schicht (`api/chat.ts`/`api/voice.ts`). Sie landen
 * WÖRTLICH als Chat-Blase im Gespräch — darum sind sie besonders sichtbar und
 * müssen der aktiven Sprache folgen.
 */
export interface ApiErrorStrings {
  unauthorized: string;
  unsupportedAudioType: string;
  httpStatus: (status: number) => string;
  /**
   * Fünf-Sprachen-Sweep 2026-07-27: derselbe generische 401-Satz wie {@link
   * unauthorized}, aber OHNE den Entwickler-Hinweis „Setze VITE_TOKEN." — das ist
   * der Wortlaut, den die ÜBRIGEN Settings-Clients (skills.ts, speakers.ts,
   * nightMode.ts, weatherLocation.ts, lookupModel.ts, brainSettings.ts,
   * ttsSettings.ts, extendedThink.ts, privacy.ts, languageSettings.ts,
   * brainAutoSwitch.ts, homeEdit.ts) schon immer geworfen haben (byte-gleich zum
   * Bestand) — ein eigenes Feld statt {@link unauthorized} wiederzuverwenden,
   * damit sich an chat.ts/voice.ts nichts ändert.
   */
  authWall: string;
}

/** Sichtbare Texte des „Wer sprach"-Chips ({@link ../components/SpeakerChip}). */
export interface SpeakerChipStrings {
  guest: string;
  guestTitle: string;
  recognized: (name: string) => string;
  recognizedWithConfidence: (name: string, percent: string) => string;
  /** Konfidenz als Prozent-Text fürs Tooltip („97 %" / „97%"). */
  percent: (value: number) => string;
}

/** Tooltip-/aria-Texte der Stage-Sparkline ({@link ../components/StageSparkline}). */
export interface StageSparklineStrings {
  /** Messwert-Suffix im Tooltip („1234 ms"). */
  ms: (ms: number) => string;
  /** Anhang bei einem über den Deckel geklemmten Wert. */
  outlierSuffix: string;
  /** Anhang bei einem Fehler-Turn. */
  errorSuffix: string;
  /** aria-Kopf: „STT heute: 7 Messwerte". */
  ariaHead: (label: string, count: number) => string;
  ariaMedian: (ms: number) => string;
  ariaP95: (ms: number) => string;
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
  /** Ehrliche „lädt…"-Zeile, solange der Server-Standard noch nicht da ist. */
  loading: string;
}

/** Die zwei Record-Dictionaries des Klingel-Banners (FIRED_HEADLINE/MISSED_NOUN). */
export interface FiredToastStrings {
  headline: Record<FiredKind, string>;
  missedNoun: Record<FiredKind, string>;
  /** title des Quittier-Knopfs (Andi-Sweep 24.07: fehlte im Katalog). */
  ackTitle: string;
  /** aria-label des Eskalations-Zahnrads. */
  gearAria: string;
  /** title des Eskalations-Zahnrads. */
  gearTitle: string;
  /**
   * Die ganze Verpasst-Zeile (EN-Sweep 25.07: der Satz war in FiredToast.tsx
   * hartkodiert Deutsch und stand so auch in der englischen Oberfläche). Die
   * Sprache baut den Satz KOMPLETT selbst — inklusive der Anführungszeichen um
   * das Label, die je Sprache anders aussehen („…" / "…" / «…»).
   */
  missed: (noun: string, label: string | null, time: string) => string;
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
  /**
   * Kopf der „Diagnose"-Sektion ans Ende von Aktivität (Andi-Auftrag
   * 2026-07-26, Flur-Display-Umbau): hierhin zog die komplette Entwickler-
   * Landing der alten Übersicht (Hero + die drei „Live verdrahtet"-Kacheln +
   * die „Heute"-Turn-Statistik) — s. `views/UebersichtView.tsx#DiagnoseSection`.
   */
  diagnoseTitle: string;
  diagnoseHint: string;
  /**
   * Tages-Trenner + „Frühere laden" (Andi-Auftrag 2026-07-27: der Turn-Feed
   * zeigt standardmäßig nur die letzten `FEED_PAGE_SIZE` Turns, gruppiert
   * unter „Heute"/„Gestern"/Datum — kein Endlos-Scroll, s. `feedDays.ts`).
   */
  dayToday: string;
  dayYesterday: string;
  /** Unlesbarer/fehlender Zeitstempel — eigenes ehrliches Segment statt Rateversuch. */
  dayUnknown: string;
  loadEarlier: string;
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
  /**
   * Scheibe 1 des Räume-Verwaltungs-Konzepts (`.orch-bus/inbox/20260727-2223-
   * cowork-raeume-verwaltung-konzept.md`, §6): Kopfzeilen-Wahrheit, Suche,
   * Domänen-Chips und die „Braucht dich"-Inbox (ersetzt die alte „Nicht
   * zugeordnet"-Darstellung, s. `RoomsInbox.tsx`/`RoomsToolbar.tsx`).
   */
  assignedSummary: (assigned: number, total: number, roomCount: number) => string;
  searchPlaceholder: string;
  searchAria: string;
  /** Ehrliche Leermeldung bei aktivem Filter/Suche ohne einen einzigen Treffer. */
  noMatches: (query: string) => string;
  domainFilterAria: string;
  domainAll: string;
  domainLight: string;
  domainClimate: string;
  domainSensors: string;
  domainOther: string;
  inboxTitle: string;
  /** Echt nichts offen (kein Amber, ruhige Zeile statt Dauer-Aufmerksamkeit). */
  inboxEmpty: string;
  inboxConfirm: string;
  inboxHintEditable: string;
  inboxHintReadOnly: string;
  /** Raum-Karte: ab `ROOM_COLLAPSE_AT` Zeilen bleibt der Rest eingeklappt. */
  roomShowMore: (hiddenCount: number) => string;
  roomShowLess: string;
}

/**
 * Sichtbare Texte der ehemaligen Übersichtsansicht — seit dem Flur-Display-
 * Umbau (Andi-Auftrag 2026-07-26) nur noch die „Diagnose"-Sektion am Ende von
 * Aktivität ({@link ActivityStrings}, `views/UebersichtView.tsx#DiagnoseSection`).
 * Die drei „Noch nicht verdrahtet"-Platzhalter (Sidecar-Health/Sprach-Stats/
 * Geräte) UND der alte Seiten-Titel/Lede sind ERSATZLOS gestrichen: Sidecar-
 * Health lebt längst als Ops-Pille in der Kopfzeile, die anderen beiden waren
 * leere Versprechen ohne Datenquelle.
 */
export interface OverviewStrings {
  heroUpTitle: string;
  heroUpSub: string;
  heroDownTitle: string;
  heroDownSub: string;
  heroUnknownTitle: string;
  heroUnknownSub: string;
  backend: string;
  live: string;
  backendNote: string;
  chatTurn: string;
  liveStreaming: string;
  chatTurnNote: string;
  authToken: string;
  set: string;
  missing: string;
  authSetNote: string;
  authMissingNote: string;
  lastChecked: (time: string) => string;
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
  /** aria-label des Schließen-Knopfs im Settings-Drawer (Andi-Sweep 24.07). */
  closeSettingsAria: string;
}

/**
 * Sichtbare Texte des Aoi-Idle-„Zuhause"-Gesichts (IdleFace) — derselbe
 * Video-Tag-Befund wie {@link TopNavStrings}. `dueClock`/`fmtRemaining`/
 * `KIND_WORD` (aus `hooks/useScheduledItems.ts`) und `codeText` (aus
 * `hooks/useWeatherToday.ts`, vom Backend als deutscher WMO-Lagen-Text
 * geliefert) bleiben außerhalb dieser Scheibe und darum deutsch — siehe
 * Kommentare in IdleFace.tsx.
 *
 * `heute` ist seit dem Flur-Display-Umbau (Andi-Auftrag 2026-07-26) NICHT
 * mehr Teil von IdleFace — die Kachel zog in die „Diagnose"-Sektion von
 * {@link ActivityStrings} um (s. `views/UebersichtView.tsx#DiagnoseSection`).
 * Der Katalog-Key bleibt hier stehen (kein Grund, denselben Text an zwei
 * Stellen zu pflegen); nur die VERWENDUNG wanderte.
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
  /** Die „Läuft"-Karte (ex-„Geplant"): echte Timer/Wecker/Erinnerungen mit Label + Countdown. */
  laeuft: {
    name: string;
  };
  /** Die Einkaufs-Karte (neu, GET /api/v1/lists — Andi-JA 2026-07-08). */
  einkauf: {
    name: string;
    /** „+3 weitere" hinter den ersten sichtbaren Einträgen. */
    more: (count: number) => string;
  };
  wetter: {
    name: string;
    loadingNote: string;
    liveNote: (place: string) => string;
    offNote: string;
    unreachableNote: string;
    /** Warme Niederschlags-Zeile im Jetzt-Band: „3 mm Regen heute". */
    precipSome: (mm: string) => string;
    /** Niederschlag exakt 0 ⇒ „trocken" statt „0 mm". */
    precipNone: string;
    /** Morgen-Zeile (Flur-Fertigstellung 2026-07-27): „morgen 12–22°, sonnig". */
    tomorrow: (span: string, cond: string) => string;
    /** „Regen ab ~17:00" — nur gezeigt, wenn eine Stunde >20% Regenwahrscheinlichkeit hat. */
    rainFrom: (clock: string) => string;
    /** „hell bis 21:34" — Tageszeit, solange die Sonne heute noch nicht unter ist. */
    sunUntil: (clock: string) => string;
    /** „hell ab 05:32" — vor dem heutigen Sonnenaufgang. */
    sunFrom: (clock: string) => string;
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
  /** Sichtbares Label + aria-label des Senden-Knopfs (Andi-Sweep 24.07: README-Screenshot-Befund). */
  send: string;
  /** title des Senden-Knopfs inkl. Tastatur-Hinweis, z. B. „Senden (Enter)". */
  sendTitle: string;
}

/** Status- und Fehlertexte des geteilten Voice-Chat-Hooks. */
export interface VoiceChatStrings {
  slowTurn: string;
  connection: string;
  errorStage: (stage: string) => string;
  noAudioHeard: string;
}

/**
 * Fehlertexte der Mikro-Aufnahme (Shape von {@link ../audio/recorder.VoiceRecorderError}).
 * Fünf-Sprachen-Sweep 2026-07-27: diese Zeilen landen WÖRTLICH als Mikro-Fehlzeile im
 * Chat/Voice-Orb (`useVoiceChatSession.humanMicError`) UND im Anlern-Dialog
 * (SpeakerSection — `err instanceof Error ? err.message : …`), waren aber bisher eine
 * hart deutsche Modul-Konstante in `audio/recorder.ts`, unabhängig von der UI-Sprache.
 * `recorder.ts` selbst hat keinen React-Hook-Zugriff — es liest darum synchron aus dem
 * Modul-Singleton (`resolveUiStrings(getActiveUiLanguage())`), exakt das Muster von
 * `api/chat.ts`/`api/voice.ts`.
 */
export interface MicErrorStrings {
  /** NotAllowedError/PermissionDeniedError — Nutzer hat das Mikro abgelehnt. */
  permissionDenied: string;
  /** NotFoundError/DevicesNotFoundError — kein Mikrofon gefunden. */
  noDevice: string;
  /** SecurityError beim Öffnen ODER `getUserMedia` fehlt UND die Seite läuft unsicher. */
  insecureContext: string;
  /** Browser kennt `navigator.mediaDevices.getUserMedia` gar nicht (und ist NICHT unsicher). */
  noApi: string;
  /** Browser kennt kein `MediaRecorder` (Mikro-Stream stand schon). */
  noRecorder: string;
  /** Alles andere (unbekannter `getUserMedia`-Fehlername). */
  unknown: string;
  /** Dev-Assertion — `stop()` ohne vorheriges `start()`; über die UI nie erreichbar. */
  stopWithoutRecording: string;
  /**
   * {@link ../audio/wav.WavConvertError} — Browser kennt keinen `AudioContext`
   * (kann also gar nicht dekodieren). Nur im Anlern-Dialog sichtbar; der reguläre
   * Voice-Turn (`voiceTurnUploadBlob`) fängt {@link ../audio/wav.WavConvertError}
   * still ab und schickt die rohen Recorder-Bytes weiter (kein UI-Fehler dort).
   */
  decodeUnsupported: string;
  /** {@link ../audio/wav.WavConvertError} — Default-Nachricht, alles andere schlug beim Dekodieren/Umwandeln fehl. */
  convertFailed: string;
}

/** Der komplette UI-Text-Katalog EINER Sprache — jede Sprache implementiert exakt diese Form. */
export interface UiStrings {
  locale: string;
  topNav: TopNavStrings;
  idleFace: IdleFaceStrings;
  weatherLocation: WeatherLocationStrings;
  lookupModel: LookupModelStrings;
  extendedThink: ExtendedThinkStrings;
  ttsEngine: TtsEngineStrings;
  stimme: StimmeStrings;
  brainModel: BrainModelStrings;
  brainAutoSwitch: BrainAutoSwitchStrings;
  privacy: PrivacyStrings;
  speaker: SpeakerStrings;
  nightMode: NightModeStrings;
  language: LanguageSettingsStrings;
  skills: SkillsStrings;
  firedToast: FiredToastStrings;
  activity: ActivityStrings;
  rooms: RoomsStrings;
  overview: OverviewStrings;
  chat: ChatStrings;
  voiceChat: VoiceChatStrings;
  micErrors: MicErrorStrings;
  turnAnatomy: TurnAnatomyStrings;
  voiceOrb: VoiceOrbStrings;
  settings: SettingsPanelStrings;
  scheduled: ScheduledStrings;
  ops: OpsStrings;
  apiErrors: ApiErrorStrings;
  speakerChip: SpeakerChipStrings;
  stageSparkline: StageSparklineStrings;
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
  /**
   * Quelle/Egress-Chip (Andi-Sweep 24.07, README-Screenshot-Befund: „lokal"
   * blieb hartkodiert Deutsch egal welche UI-Sprache aktiv war): `providerChipText`.
   */
  local: string;
  /** Suffix hinter dem Cloud-Provider-Namen, z. B. " · ging online". */
  cloudSuffix: string;
  /** title-Tooltip des Schloss-Chips (blieb lokal). */
  localTitle: string;
  /** title-Tooltip des Wolken-Chips (ging online). */
  cloudTitle: string;
  /** Label des Grounding-Chips („Wissen gedeckt"). */
  grounded: string;
  /** title-Tooltip des Grounding-Chips. */
  groundedTitle: string;
}

/** Sichtbare Texte des Home-Orbs (VoiceOrb) — Andi-Sweep 24.07, README-Screenshot-Befund. */
export interface VoiceOrbStrings {
  /** aria-label der ganzen Orb-Sektion. */
  sectionAria: string;
  /** Hinweistext unter dem Orb im Ruhezustand. */
  idleHint: string;
  /** aria-label/title des Orbs im Ruhezustand. */
  idleTapLabel: string;
  /** Hinweistext, während das Mikro hört (mit verstrichener Zeit). */
  listening: (elapsed: string) => string;
  /** aria-label/title des Orbs, während das Mikro hört. */
  listeningTapLabel: string;
  /** aria-label/title des Orbs, während Hoshi spricht (Barge-in-Hinweis). */
  speakingTapLabel: string;
}
