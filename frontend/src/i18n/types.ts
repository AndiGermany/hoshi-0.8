import type { FiredKind } from '../hooks/useFiredItems';
import type { ScheduledKind } from '../hooks/useScheduledItems';
import type { Persona, ThemeGroupId, VisibleTheme } from '../hooks/useSettings';
import type { Language } from '../api/types';
import type { PrivacyTarget } from '../api/privacy';
import type { SettingsCategoryId } from '../components/SettingsPanel';
import type { EscalationModeWire } from '../api/extendedThink';
import type { VacuumStatusKind } from '../components/homeTiles';
import type { HomeTileSize } from '../components/homeWidgets';
import type { MoonPhaseName } from '../components/moonPhase';

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

  // ── Anlern-Overlay (design DESIGN-widgets-settings-2026-08-15 §3.3) ─────────
  // Der Assistent ist aus dem 340-px-Drawer in die geteilte Overlay-Schale
  // gezogen, EIN Schritt pro Bild. Neu sind darum: der Weiter-Knopf von Bild ①,
  // die zwei Befunde der Client-Mindestprüfung, die Abbruch-Nachfrage und die
  // Beschriftungen der Aufnahmen-Seite.

  /** Knopf auf Bild ① (Name) — führt zu Bild ② (Sprechen). */
  nameNext: string;
  /** aria-label des Schließen-Kreuzes im Overlay-Kopf. */
  closeAria: string;
  /**
   * Client-Mindestprüfung vor dem Upload: die Aufnahme war zu kurz (oder es
   * wurde zu wenig darin gesprochen). Erspart das 422 NACH dem Hochladen.
   */
  checkTooShort: string;
  /** Client-Mindestprüfung: der Pegel blieb (fast) am Boden — Mikro stumm/zu weit weg. */
  checkTooQuiet: string;
  /** Überschrift der Abbruch-Nachfrage (Escape/Backdrop/Abbrechen mitten in der Aufnahme). */
  cancelConfirmTitle: string;
  /** Was ein Abbruch in einer FRISCHEN Sitzung kostet: das unfertige Profil wird verworfen. */
  cancelConfirmFresh: string;
  /** Was ein Abbruch in einer ANGEHÄNGTEN Sitzung kostet: nichts — nur diese Sitzung bleibt offen. */
  cancelConfirmAppend: string;
  /** Bestätigt den Abbruch (läuft durch die Rollback-Semantik). */
  cancelConfirmYes: string;
  /** Verwirft die Nachfrage und macht weiter. */
  cancelConfirmNo: string;
  /** Überschrift der Aufnahmen-Seite des Overlays. */
  recordingsTitle: string;
  /** Knopf einer Profil-Zeile, solange die Aufnahmen-Zahl noch nicht geladen ist (nie geraten). */
  recordingsOpen: string;
  /** aria-label dieses Knopfs, z. B. „Aufnahmen von Andi ansehen". */
  recordingsOpenAria: (name: string) => string;
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
 * Das versteckte Thema Nagori (名残): wie ein normaler Themen-Eintrag, plus sein
 * Beiwort (die anderen holen es aus `themeGlosses` — Nagori steht dort nicht,
 * weil dieser Katalog exakt die sichtbare Liste beschreibt) und die eine Zeile,
 * die den Fund einordnet.
 */
export interface NagoriStrings extends ThemeEntryStrings {
  /** Die Übersetzung des Namens („was zurückbleibt"). */
  gloss: string;
  /** Einordnung unter der Karte: „名残 — ein Vorbote von 0.9". */
  note: string;
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
  /**
   * Die sieben Kategorie-Namen (Reihenfolge/Ids liegen im Panel, nicht hier).
   * Sie stehen auf den Übersichtskarten UND als Überschrift ihres Panels — die
   * Chip-Reiterleiste, die sie bis 15.08 trug, ist gestrichen.
   */
  categories: Record<SettingsCategoryId, string>;
  /**
   * aria-label der Kategorie-Fläche. Trug bis 15.08 die Reiter-Leiste
   * (`role="tablist"`); seither die Übersichts-Karten (`role="group"`) — die
   * Leiste ist weg, der Satz „das sind die Einstellungs-Kategorien" bleibt.
   */
  categoryNavAria: string;
  themeLabel: string;
  /** aria-label der Farbthema-Radiogroup. */
  themeGroupAria: string;
  /**
   * Kicker der „Aktuell"-Zeile ganz oben im Picker (Andi-Auftrag 07.08:
   * „übersichtlicher" — Aktiv-zuerst statt Kachel-Wand). Steht über Swatch +
   * Name + Gruppen-Beiwort des GERADE gewählten Themas, s. {@link
   * ../components/SettingsPanel}.ThemeSection.
   */
  themeActiveLabel: string;
  /**
   * Die ehrliche Zeile, solange `public/themes/manifest.json` noch unterwegs ist
   * (seit dem .old-Umzug 2026-08-08 werden die Themen dynamisch nachgeladen).
   * Lieber „lädt …" als eine erfundene oder halbe Liste.
   */
  themeLoading: string;
  /**
   * Der Charakter-Satz je Thema. Deckt genau die Themen der 0.8-Linie ab
   * ({@link VisibleTheme}); NAME und BEIWORT eines Themas kommen seit dem
   * .old-Umzug dagegen aus `public/themes/manifest.json` (mehrsprachig dort),
   * weil sie mit der Themen-Datei ausgeliefert werden müssen.
   */
  themes: Record<VisibleTheme, ThemeEntryStrings>;
  /**
   * Überschrift + Einordnungs-Zeile je Gruppe des Pickers. Die GRUPPEN-Titel
   * bleiben bewusst i18n (sie müssen beim Sprachwechsel mitgehen); welche
   * Gruppen es gibt und in welcher Reihenfolge, sagt das Manifest.
   */
  themeGroups: Record<ThemeGroupId, ThemeGroupStrings>;
  /**
   * Das Beiwort je Thema — die Übersetzung des japanischen Namens („Nagareboshi
   * · Sternschnuppe"). Bewusst NEBEN {@link ThemeEntryStrings}, nicht darin:
   * `THEMES` (useSettings) spiegelt diesen Katalog-Eintrag 1:1, und dessen Form
   * ist von Bestandstests gepinnt.
   */
  themeGlosses: Record<VisibleTheme, string>;
  /**
   * Das versteckte zehnte Thema — Nagori (名残), der Codename der kommenden 0.9.
   * Bewusst NEBEN {@link SettingsPanelStrings.themes}: es ist kein Teil der
   * Liste, sondern ein Fund (3× auf die Versions-Zeile, s. TopNav). Der
   * Eigenname bleibt in jeder Sprache „Nagori"; übersetzt werden Beiwort,
   * Charakter-Zeile und die Einordnung darunter.
   */
  nagori: NagoriStrings;
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

  // ── Design-Galerie (design DESIGN-widgets-settings-2026-08-15 §3.4) ─────────
  // Die 15 Karten hinter vier `<details>` sind aus dem 340-px-Drawer in ein
  // 960-px-Overlay gezogen; im Panel bleiben die Aktiv-Zeile und EIN Knopf.

  /** Der eine Knopf im Panel, der die Galerie öffnet. */
  themeGalleryOpen: string;
  /** Titel/aria-label des Galerie-Overlays. */
  themeGalleryTitle: string;
  /** aria-label des Schließen-Kreuzes der Galerie. */
  themeGalleryCloseAria: string;
  /**
   * Der ausdrückliche FERTIG-Weg aus der Galerie (Andi-Auftrag 19.08.: eine
   * Auswahl schließt die Galerie bewusst NICHT — man soll vergleichen können —
   * also braucht es einen benannten Ausgang neben Kreuz und Escape).
   */
  themeGalleryDone: string;
  /** alt-Text der echten Szenen-Vorschau eines Szenen-Themas. */
  themeSceneAlt: (themeName: string) => string;
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
  /**
   * Die Zuhause-Widgets-Gruppe (Andi-Auftrag 2026-08-11, erweitert W2
   * 18.08.: acht Schalter, zwei Ränge) — s. `HomeTilesSection` in
   * `SettingsPanel.tsx`. Sauger/Klima rendern weiter NUR, wenn ihre
   * Datenquelle real ist (vacuum gefunden / ≥1 Raum mit climate); die
   * übrigen sechs sind quellenlos immer da.
   */
  homeTilesTitle: string;
  homeTilesHint: string;
  /** Kicker über der Krone-Gruppe (Uhr/Wecker — fester Kopf, nicht verschiebbar). */
  homeTilesCrownGroupLabel: string;
  homeTilesUhrLabel: string;
  homeTilesUhrHint: string;
  homeTilesWeckerLabel: string;
  homeTilesWeckerHint: string;
  /** Kicker über der Bühne-Gruppe (die frei anordenbaren Kacheln). */
  homeTilesStageGroupLabel: string;
  homeTilesWetterLabel: string;
  homeTilesWetterHint: string;
  homeTilesLaeuftLabel: string;
  homeTilesLaeuftHint: string;
  homeTilesEinkaufLabel: string;
  homeTilesEinkaufHint: string;
  homeTilesVacuumLabel: string;
  homeTilesVacuumHint: string;
  homeTilesClimateLabel: string;
  homeTilesClimateHint: string;
  /**
   * Der dritte Schalter der Gruppe: das Nachrichten-Fenster (Code-Name
   * `currentAffairs`/„Lagebild"). Er hat KEIN Quellen-Gate (s.
   * `HomeTilesSection`) und steht per Default auf AN.
   */
  homeTilesCurrentAffairsLabel: string;
  /**
   * Ehrlicher Beschreibungstext: was der Schalter tut (Anzeige auf dem
   * Zuhause-Reiter + der 10-Minuten-Poll dahinter) UND was er NICHT tut — er
   * schaltet weder das Server-Feature noch den Sprach-Weg ab; Hoshi bleibt
   * fragbar, auch wenn das Fenster aus ist.
   */
  homeTilesCurrentAffairsHint: string;
  /**
   * Server-Settings-Naht unter dem Lagebild-Anzeige-Schalter (s.
   * `NewsSourcesSection`): welche Quellen (Tagesschau/heise/Golem) der
   * SERVER überhaupt vorhält, unabhängig von der lokalen Anzeige-Wahl.
   */
  homeTilesNewsSourcesLabel: string;
  homeTilesNewsSourcesHint: string;
  homeTilesNewsSourcesLoading: string;
  homeTilesNewsSourcesLoadError: string;
  homeTilesNewsSourcesFailed: string;
  /** 422 vom PUT — sollte über die UI (Checkboxen aus `verfuegbar`) praktisch nie auftreten. */
  homeTilesNewsSourcesUnknown: string;
  /**
   * Die Anordnung der Bühne (W3, DESIGN §4.3). Die Zusage ist bewusst eng
   * formuliert (Bus 20260818-to-codex-raster-entscheid §2, nach Codex'
   * Gegenprüfung §2): gespeichert wird eine REIHENFOLGE für einen Packer —
   * nicht die freie Zellplatzierung, die „Tetris" verspräche.
   */
  homeTilesLayoutHint: string;
  /** „Layout zurücksetzen" — scharf erst beim zweiten Klick (Idiom der Privatsphäre-Knöpfe). */
  /**
   * „Widgets anordnen" (W4) — schließt den Drawer und öffnet den Edit-Modus
   * auf der Übersicht. Der EINZIGE Weg dorthin, der keinen Zeiger braucht.
   */
  homeTilesLayoutArrange: string;
  homeTilesLayoutReset: string;
  /** Beschriftung des scharfen Knopfes: „Wirklich? Klick nochmal". */
  homeTilesLayoutResetArmed: string;
  /** Bestätigung danach — sagt ausdrücklich, dass die SCHALTER unberührt sind (§4.3). */
  homeTilesLayoutResetDone: string;
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

  // ── Category overview — the drawer's entry level (design 2026-08-15 §3.1) ──
  /**
   * ONE half-sentence per category, shown under name + glyph on the overview
   * card. Names what is inside, never what it does to you — the card is a
   * signpost, not a manual. The category NAMES themselves stay in
   * {@link categories}; the order stays with `SETTINGS_CATEGORY_IDS`.
   */
  categoryBlurbs: Record<SettingsCategoryId, string>;
  /** Visible label of the way back out of a category ("‹ Einstellungen"). */
  overviewBack: string;
  /** `aria-label` of that back button — says where it leads, not just "back". */
  overviewBackAria: string;
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
  /**
   * Raum-Karten-Pille bei aktivem Filter: sichtbare Geräte „von" der vollen
   * Raum-Anzahl (Andi-Auftrag 2026-08-11, Konzept §2 „GRID STATT LISTE").
   * Ohne aktiven Filter bleibt {@link deviceCount} stehen (kein redundantes
   * „12 von 12", s. `RoomCard` in `views/RaeumeView.tsx`).
   */
  deviceCountOfTotal: (visible: number, total: number) => string;
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
  /**
   * Kopfzeile des gefalteten „ohne Raum-Bezug"-Fachs der Inbox
   * (`roomsRelevance.ts`, Andi 2026-08-11): System/Diagnose/Mobiles sind
   * keine offene Aufgabe — ein Handy hat keinen Raum, die Sonne auch nicht.
   * Zuweisbar bleiben sie im aufgeklappten Fach trotzdem.
   */
  inboxRestSummary: (count: number) => string;
  /** Raum-Karte: ab `ROOM_COLLAPSE_AT` Zeilen bleibt der Rest eingeklappt. */
  roomShowMore: (hiddenCount: number) => string;
  roomShowLess: string;
  /**
   * Kopfzeile des zugeklappten „Stille Räume"-Fachs (Andi-Auftrag
   * 2026-08-11, Konzept §4): Räume ohne Geräte-Aktivität (0/1 Gerät),
   * ehrlich benannt statt versteckt.
   */
  silentRooms: (count: number) => string;
  /**
   * Kurzer, ehrlicher Hinweis über dem unfiltrierten Raum-Raster: die
   * Sortierung folgt der Geräteanzahl, NICHT der echten Gesprächs-Nutzung —
   * dafür fehlt (Stand heute) eine Datenspur (Konzept-Pfad 1(b), s.
   * `components/roomsSort.ts`-KDoc). Nur sichtbar ohne aktiven Filter
   * (Muster {@link sortRoomsByUsage}-Aufrufstelle in `views/RaeumeView.tsx`).
   */
  sortHint: string;
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
/**
 * Der Sonnenverlauf der L-Uhr ({@link ../components/SunArc}, Andi 21.08.).
 * **Nur Vorlese- und Hover-Texte**: im Bild selbst steht kein Wort — die zwei
 * Uhrzeiten unter den Bogenfüßen kommen aus `dueClock(…, locale)`. Übersetzt
 * wird also genau das, was ein Screenreader sagt (Muster
 * {@link IdleFaceStrings.wetter.hourly}).
 */
export interface SunArcStrings {
  /** aria-Kopf: „Sonnenverlauf, Aufgang 06:22, Untergang 20:48, die Sonne steht am Himmel". */
  aria: (rise: string, set: string, phase: string) => string;
  /** Lage-Halbsatz bei Tag — steht im aria-Text und als Tooltip am Sonnenpunkt. */
  dayPhase: string;
  /**
   * **`nightPhase` ist am 23.08. weggefallen**, zusammen mit dem Bild, das ihn
   * gebraucht hat: nachts steht hier keine gedimmte Sonne unter einem Horizont
   * mehr, sondern der Mond — und der spricht in {@link MoonStrings} für sich.
   * Ein Katalog-Schlüssel ohne Leser ist eine Übersetzung, die niemand prüft.
   */
  /**
   * Die Mondphase, die nachts an die Stelle des Bogens tritt (Andi 23.08.:
   * „Bei dem goßen sonnenstand möchte ich in der nacht die mondphase angezeigt
   * haben"). Anders als beim Sonnenbogen steht hier **ein Wort im Bild** —
   * der Phasenname unter der Scheibe —, es ist also mehr als Vorlese-Text.
   */
  moon: MoonStrings;
}

/** Die Namen der acht Mondphasen plus der Vorlese-Satz ({@link ../components/moonPhase}). */
export interface MoonStrings {
  /** aria-Kopf: „Mondphase, zunehmender Mond, 62 % beleuchtet, Sonnenaufgang 06:22". */
  aria: (phase: string, percent: string, rise: string) => string;
  /**
   * Der Name je Phase — **vollständig**, `Record` über die Union: eine neue
   * Phase im Modell bricht hier den Typcheck, statt still auf `undefined` zu
   * fallen.
   */
  phases: Record<MoonPhaseName, string>;
}

/**
 * Die Mehrtage-Zeile der XL-Wetterkachel ({@link ../components/weatherOutlook}).
 * Der Lagen-Text je Tag kommt **fertig übersetzt vom Backend** (WMO-Text in der
 * Anzeigesprache) und steht deshalb nicht hier; das Wochentags-Kürzel kommt aus
 * ICU (`toLocaleDateString`). Übrig bleiben die zwei Satz-Bausteine, die das FE
 * selbst formuliert.
 */
export interface WeatherOutlookStrings {
  /** aria-Label der ganzen Zeile: „Ausblick, 7 Tage". */
  aria: (days: number) => string;
  /** Nativer Tooltip einer Spalte: „Freitag, 12–22°, leichter Regen". */
  title: (day: string, span: string, cond: string) => string;
  /**
   * Anhang an den Tooltip, NUR wenn das Wire eine Regenwahrscheinlichkeit
   * liefert — fehlt sie, bleibt der Satz weg statt „0 %" zu behaupten
   * (BE-Vertrag: `precipProbability` null = keine Angabe).
   */
  rainChance: (percent: number) => string;
}

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
  /**
   * The widget stage ("Bühne") — its pages, `components/HomeStage.tsx`
   * ("Komposition v2", 15.08.). The two page strings exist ONLY for screen
   * readers: the dots carry no visible text, and they only ever exist from
   * page two on, so on a single-page stage neither string is rendered at all.
   *
   * The size picker (W3, DESIGN-widget-raster-2026-08-18 §4.2) adds four
   * more. Its buttons READ "S/M/L/XL" — short enough for a 44 px target and
   * the same four letters in every language — but that is not an accessible
   * name (Codex-Gegenprüfung §5), so each button carries the long name from
   * {@link sizeNames} as its `aria-label`.
   *
   * **W6 (Andi 20.08.: „Die Größenauswahl soll ein + und − sein"):** aus vier
   * Stufen-Knöpfen sind ZWEI Richtungs-Knöpfe geworden ({@link sizeLarger}/
   * {@link sizeSmaller}). {@link sizeNames} bleibt — es benennt jetzt die
   * AKTUELLE Stufe zwischen den beiden Knöpfen und trägt weiter
   * {@link sizerEffective}.
   */
  stage: {
    /** Group label of the page dots. */
    pagesAria: string;
    /** Label of one dot: „Seite 2 von 3". */
    page: (index: number, total: number) => string;
    /** Group label of the size picker, with the widget's own name: „Größe für Wetter". */
    sizerAria: (widget: string) => string;
    /**
     * The long name of each step. Until W6 it was the accessible name of each
     * step button; since the picker is `−`/`+` it names the CURRENT step —
     * screen readers get the word, the eye gets the letter.
     */
    sizeNames: Record<HomeTileSize, string>;
    /** `aria-label` des `+`-Knopfes: „Größer". */
    sizeLarger: string;
    /** `aria-label` des `−`-Knopfes: „Kleiner". */
    sizeSmaller: string;
    /**
     * Honest note when the chosen step cannot be shown at this width/height —
     * the stored size stays, the DISPLAY gives way (§0.4/§2.3). Without it a
     * degraded tile looks like a bug.
     */
    sizerEffective: (name: string) => string;
    /**
     * **Der Edit-Modus** (W4, DESIGN §4.2 + Codex-Gegenprüfung §5 „i18n"):
     * Leiste, Fach „Verfügbar", Griffecke und die `aria-live`-Ansagen des
     * Tastatur-Verschiebens. Alles hier ist SICHTBARER oder VORGELESENER
     * Text — Widget-Namen kommen NICHT von hier, sondern aus den bestehenden
     * Kachel-Strings (`idleFace.wetter.name` usw.), damit derselbe Name nicht
     * zweimal übersetzt wird und beim ersten Wording-Wechsel auseinanderdriftet.
     */
    edit: {
      /**
       * Zugänglicher Name der (unsichtbaren) Edit-Gruppe — dasselbe Wort wie
       * der Knopf in den Einstellungen, mit dem man hier hineinkommt.
       *
       * `done`/`reset`/`resetArmed`/`resetDone` standen bis zum 23.08.
       * daneben: die Knöpfe der Edit-Leiste. Die Leiste ist gefallen (Andi:
       * „nimm die UI oben, wenn man etwas bearbeitet raus"), „Fertig" hat mit
       * ihr keinen Ort mehr (drei Ausgänge braucht keinen Knopf), und
       * „Zurücksetzen" wohnt bei den Widget-Schaltern in den Einstellungen —
       * mit EIGENEN Strings (`settings.homeTilesLayoutReset*`). Denselben Satz
       * zweimal übersetzt zu halten wäre der sichere Weg zu zwei Wortlauten.
       */
      title: string;
      /** Zugänglicher Name einer Kachel im Edit: „Wetter — Platz 2 von 7". */
      tileAria: (widget: string, index: number, total: number) => string;
      /** `aria-roledescription` der Kachel: „verschiebbares Widget". */
      tileRole: string;
      /**
       * Die Tastatur-Belegung. **Unsichtbar** — sr-only, sonst nirgends. Sie
       * war bis W6 sichtbar und hat der Bühne dabei Kachelzeilen weggenommen;
       * am 22.08. fiel jeder Text aus der Leiste (Andi: „Entferne bitte die
       * hinweise"), am 23.08. die Leiste selbst. Der Satz bleibt: er ist der
       * einzige Ort, an dem Pfeiltasten und Bild ↑/↓ überhaupt angeboten
       * werden, und er kostet null Pixel.
       */
      keyHint: string;
      /** `aria-live`-Bestätigung nach einem Zug (Codex §5: „Bestätigung der neuen Position"). */
      moved: (widget: string, index: number, total: number, page: number, pages: number) => string;
    };
  };
  /** Die „Läuft"-Karte (ex-„Geplant"): echte Timer/Wecker/Erinnerungen mit Label + Countdown. */
  /**
   * Die Uhr — seit W4 (Andi 19.08.) ein **Bühnen-Widget** statt der festen
   * Krone. Sie trägt keinen sichtbaren Titel (eine Zeile „Uhr" über einer
   * 124-px-Uhr wäre Beschriftung des Offensichtlichen); der Name lebt im
   * `aria-label` und im Edit-Modus.
   */
  uhr: {
    name: string;
    /**
     * Der Sonnenverlauf der **L**-Stufe (Andi 21.08.: „wenn man die Größe
     * ändert, dass man den Sonnenverlauf anzeigt"). Er erscheint nur, wenn das
     * Wire `sunriseEpochMs`/`sunsetEpochMs` trägt — s. {@link SunArcStrings}.
     */
    sun: SunArcStrings;
  };
  /**
   * Der Wecker — seit W6 (Andi 20.08.) ebenfalls ein **Bühnen-Widget**; er war
   * der letzte Bewohner der Krone. Wie die Uhr trägt er keinen sichtbaren
   * Titel: „Wecker" steht bereits im Satz selbst („Wecker 07:00 · noch 22 h"),
   * eine Überschrift darüber sagte dasselbe zweimal. Der Name lebt im
   * `aria-label` der Kachel und im Edit-Modus (Fach, Ansage beim Verschieben).
   */
  wecker: {
    name: string;
  };
  laeuft: {
    name: string;
  };
  /** Die Einkaufs-Karte (neu, GET /api/v1/lists — Andi-JA 2026-07-08). */
  einkauf: {
    name: string;
    /** „+3 weitere" hinter den ersten sichtbaren Einträgen. */
    more: (count: number) => string;
  };
  /**
   * Visible strings of the "Lagebild" window (order F5, wave 1 — see
   * `components/CurrentAffairsTile.tsx`). USER-FACING the feature is called
   * "Nachrichten"/"News" (correction 2026-08-15); "Lagebild"/`currentAffairs`
   * survives only as the internal code name, here and in the settings switch.
   * The relative ages of headlines and of
   * the "Stand" line reuse {@link IdleFaceStrings.homeTiles}`.age` instead of a
   * second set of time words. Feed data itself (source name, headline, snippet)
   * is NEVER translated — it arrives as-is from the backend.
   */
  currentAffairs: {
    name: string;
    /** Meta line under a headline: „<Quelle> · vor 2 Std." — source comes raw. */
    meta: (source: string, relative: string) => string;
    /**
     * „Stand 08:45" — built from `lastSuccessfulRefreshAt`, NEVER from
     * `observedAt` (which would be fresh on every poll and lie about age).
     */
    stand: (clock: string) => string;
    /** Discreet amber age hint appended to the Stand line while freshness = STALE. */
    staleHint: (relative: string) => string;
    /** Expand action of the collapsed window: „+3 weitere" — counts only what the expansion really reveals. */
    more: (count: number) => string;
    /** Same action while expanded (collapses back to three cards). */
    less: string;
    /**
     * Honest footer of the EXPANDED list: everything beyond
     * `CURRENT_AFFAIRS_EXPANDED_COUNT` is counted out loud instead of silently
     * dropped („+14 weitere, hier nicht gezeigt"). The rest arrives with the
     * planned overlay — this window stays inside the viewport.
     */
    restNotShown: (count: number) => string;
    /** Explicit source action on an expanded card. */
    openSource: string;
    /** aria-label of a headline link — it opens the article in a new tab. */
    openAria: (title: string) => string;
    /* Die Vollbild-Ansicht (Andi 23.08.: „ich habe keine möglichkeit diese
       anzuzeigen oder die nachrichten zu filtern") trägt KEINEN eigenen Titel-
       Schlüssel: sie heißt wie das Widget (`name`). Ein zweites Wort für
       dieselbe Sache wäre eine Fundstelle, die beim nächsten Umbenennen
       zurückbleibt. */
    /** Der Chip, der die Quellen-Auswahl aufhebt. */
    allSources: string;
    /** `aria-label` der Chip-Gruppe — sie ist ein Filter, kein Menü. */
    sourceFilterAria: string;
    /**
     * „12 von 20 Meldungen" — die ehrliche Bilanz über der Liste. Sie ersetzt
     * `restNotShown` NICHT, sie beantwortet es: dort steht, wieviel fehlt, hier,
     * wieviel da ist. Bei ungefiltert gleicher Zahl sagen beide Argumente
     * dasselbe, und der Text darf das zusammenziehen.
     */
    countInfo: (shown: number, total: number) => string;
  };
  /**
   * **Maximieren** (Andi 23.08.) — die zwei Wörter, die an JEDER Kachel mit
   * Vollbild-Ansicht gleich lauten. Bewusst EIN Block statt je Widget zwei
   * Schlüssel: „Maximieren" heißt beim Wetter nichts anderes als bei den
   * Nachrichten, und zwei Fundstellen liefen beim nächsten Feinschliff
   * auseinander.
   */
  maximieren: {
    /** Sichtbarer Name der Tat (nur als `aria-label`/`title` — der Knopf ist ein Icon). */
    open: string;
    /** `aria-label` des Knopfs auf einer bestimmten Kachel: „Wetter maximieren". */
    openAria: (name: string) => string;
    /** Der Ausgang aus der Vollbild-Ansicht. */
    close: string;
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
    /**
     * **Die Abschnitte der Vollbild-Ansicht** (Andi 23.08.: „alle informationen
     * vernünftig angezeigt"). Auf der Kachel trägt jede Zeile ihre Aussage
     * selbst („morgen 13–19°, Regenschauer"); im Vollbild stehen dieselben
     * Werte in einer Tabelle, und eine Tabelle braucht Spaltennamen. Das sind
     * sie — Etiketten, keine Sätze.
     */
    sections: {
      now: string;
      /** Etikett der Tagesspanne („18–29°"). */
      span: string;
      /** Etikett des Niederschlags. */
      precip: string;
      /** Etikett der Morgen-Werte. */
      tomorrow: string;
      hourly: string;
      /** Überschrift der Mehrtage-Zeile. */
      days: string;
      sun: string;
      sunrise: string;
      sunset: string;
      /** Etikett der Tageslänge — GERECHNET aus Auf- und Untergang, kein Wire-Feld. */
      daylight: string;
      /** „14 h 26 min" in der Zeitsprache dieser Sprache. */
      daylightValue: (span: { h: number; min: number }) => string;
    };
    /**
     * Der Stunden-Verlauf der XL-Stufe ({@link ../components/WeatherHourly}, W5,
     * DESIGN-widget-raster-2026-08-18 §3.1). **Nur Vorlese-Texte**: im Bild
     * selbst steht kein einziges Wort — die Temperatur-Marken sind Zahlen
     * (`23°`), die Stundenachse kommt aus `toLocaleTimeString(t.locale)`.
     * Übersetzt wird also genau das, was ein Screenreader sagt.
     */
    hourly: {
      /** aria-Kopf: „Stunden-Verlauf, nächste 12 Stunden, 15° bis 23°". */
      aria: (hours: number, min: string, max: string) => string;
      /** aria-Anhang, wenn irgendeine Stunde Regen führt: „Regen bis 80 %". */
      ariaRain: (percent: number) => string;
      /** aria-Anhang, wenn KEINE Stunde Regen führt — ehrlich statt weggelassen. */
      ariaDry: string;
      /** Tooltip eines Balkens: „80 % Regen". */
      barTitle: (percent: number) => string;
    };
    /**
     * Die **Mehrtage-Zeile** unter dem Stunden-Verlauf (XL, Andi 21.08.) — sie
     * liest das seit 21.08. additive Wire-Feld `outlook`. Fehlt es (Alt-Backend),
     * erscheint die Zeile nicht; s. {@link WeatherOutlookStrings}.
     */
    outlook: WeatherOutlookStrings;
  };
  status: {
    online: string;
    offline: string;
    checking: string;
    voiceCloud: string;
    voiceLocal: string;
  };
  /**
   * Die Zuhause-Kacheln (Sauger/Klima) — Andi-Auftrag 2026-08-11 „Kacheln,
   * die man sich verdient": je Kachel nur sichtbar, wenn Andi sie im
   * SettingsPanel aktiviert hat UND ihre Quelle real ist (s.
   * `components/homeTiles.ts`). Sichtbarkeit/Reihenfolge selbst lebt in
   * `HomeTileCards.tsx`, hier nur der Text.
   */
  homeTiles: {
    /**
     * Gemeinsame relative-Zeit-Stufen der Last-known-good-Zeilen (Andi-
     * Auftrag 2026-08-13, „Sauger-Sichtbarkeits-Lücke") — VON BEIDEN Kacheln
     * genutzt, s. `components/homeTiles.ts#relativeAgeStage`/`formatRelativeAge`.
     */
    age: {
      /** < 1 Minute her. */
      justNow: string;
      minutesAgo: (n: number) => string;
      hoursAgo: (n: number) => string;
      daysAgo: (n: number) => string;
    };
    /**
     * „Nicht erreichbar — zuletzt gesehen <relative Zeit>" (Scheibe S2
     * „Ehrliche Anwesenheit", DESIGN-widgets-settings-2026-08-15 §2.4). VON
     * BEIDEN Kacheln genutzt: der Rückfall einer Quelle, die dieser Browser
     * schon einmal lebendig gesehen hat (s.
     * `hooks/useSettings.ts#useHomeTileLastSeen`) — im 18px-Ton der Kachel,
     * NIE amber. Nie gesehene Quellen bleiben bei {@link vacuum.unreachable}
     * bzw. {@link climate.unreachable}.
     *
     * `relative` kommt bewusst aus {@link age} — das Haus hat genau EINEN Satz
     * Zeitwörter, ein zweiter würde „vor 3 Std." und „seit 3 h" nebeneinander
     * stellen.
     */
    unavailableSince: (relative: string) => string;
    vacuum: {
      name: string;
      /** Zustands-Mapping (`components/homeTiles.ts#VacuumStatusKind`) — EIN warmer Satz je HA-Aktivität. */
      status: Record<VacuumStatusKind, string>;
      /**
       * Hybrid-Rettung (Andi-Auftrag 2026-08-13, Sauger-Metrik-Familie): die
       * `vacuum.*`-Entity selbst schweigt (unavailable/unknown), aber
       * `reinigen`/`ladestatus` (binary_sensor) liefern trotzdem — EIGENE,
       * ehrlich gekennzeichnete Texte (`components/homeTiles.ts#vacuumFamilyStatus`),
       * NIE als „echter" vacuum-State ausgegeben.
       */
      hybridStatus: { cleaning: string; charging: string };
      /** „<Status-Satz> · in <Raum>" — der Raumname ist NUTZERDATEN, kommt roh, NIE übersetzt. */
      withRoom: (statusText: string, room: string) => string;
      /**
       * „<Status-Satz> · Akku 100 %" (Andi 21.08., wörtlich: „Bereit in der
       * Ladestation · Akku 100 %"). Der Akkustand hing bis dahin als eigener
       * 13-px-Absatz unter dem Zustandssatz; Andis Bild ist EIN Satz. Ab M —
       * auf S bleibt der Zustandssatz allein (§3.2 „Ein Wert, ein Zustand").
       */
      withBattery: (statusText: string, percent: number) => string;
      /**
       * „Fortschritt 42 %" — NUR während `reinigen`=on
       * (`components/homeTiles.ts#vacuumFamilyProgress`). Das Wort davor kam am
       * 22.08. dazu: seit der Akkustand in den Zustandssatz gewandert ist,
       * stünde eine nackte Prozentzahl direkt unter „… · Akku 63 %" und wäre
       * nicht mehr zuzuordnen.
       */
      progress: (percent: number) => string;
      /** Akku-Zeile, bevorzugt `sensor.<stem>_batterie`, Fallback `attrs.battery_level`: „Akku 42 %". */
      battery: (percent: number) => string;
      /** Roher Fehlwert von `staubsauger_fehler`, NUR wenn er als echter Fehler zählt (`components/homeTiles.ts#vacuumFamilyErrorDetails`). */
      vacuumErrorDetail: (value: string) => string;
      /** Roher Fehlwert von `dock_dock_fehler`, dieselbe Regel. */
      dockErrorDetail: (value: string) => string;
      /** `state` fehlt/unavailable/unknown/unbekannt UND die Familie rettet nichts ⇒ diese stille Zeile, NIE Amber. */
      unreachable: string;
      /**
       * „Zuletzt gesehen <relative Zeit>: <Statussatz>" — NUR wenn live
       * unbrauchbar UND `lastKnown` existiert (Andi-Auftrag 2026-08-13,
       * `components/homeTiles.ts#vacuumLastKnownStatus`). NIE Amber, auch
       * wenn der gemerkte Zustand `error` war — ein ALTER Fehler ist kein
       * AKTUELLER Alarm.
       */
      lastKnownLine: (relative: string, statusText: string) => string;
      /**
       * **Die leise Fußnote des Cache-Carry** („Stand 14:20", Andi 21.08.:
       * „das ist Lärm, meistens ist er einfach im Energiesparmodus"). Erscheint
       * NUR, wenn der BE `fromCacheSinceMs` mitschickt — dann kommen die
       * gezeigten Werte aus dem Gedächtnis statt live, und das wird gesagt,
       * aber im 13-px-Ton einer Fußnote statt als Abwesenheits-Meldung.
       * Der Zeitpunkt ist die ÄLTESTE gecachte Sichtung der Familie
       * (`components/homeTiles.ts#vacuumFamilyCacheSince`).
       */
      cacheSince: (clock: string) => string;
      /** „zuletzt fertig 14:20" — `lastCleanEnd` am selben Kalendertag (`components/homeTiles.ts#vacuumLastClean`). */
      lastCleanToday: (clock: string) => string;
      /** „zuletzt fertig vor 2 Tg." — derselbe Lauf, aber an einem früheren Tag: dann trägt die Zeile das Alter statt einer Uhrzeit ohne Datum. */
      lastCleanAgo: (relative: string) => string;
      /** „Dauer 1 h 40 min" — NUR wenn auch `lastCleanStart` da ist und plausibel zum Ende passt. */
      lastCleanDuration: (span: string) => string;
      /** Zeitspannen-Wörter für {@link lastCleanDuration} — eigene Bausteine, weil `age` relative VERGANGENHEIT beschreibt („vor 2 Std."), das hier aber eine DAUER ist. */
      duration: {
        hoursMinutes: (hours: number, minutes: number) => string;
        minutes: (minutes: number) => string;
      };
      /**
       * **Die zwei Tat-Knöpfe** (Andi 21.08.: „Können wir den Sauger starten
       * und nach Hause fahren lassen?"), `POST /api/v1/home/vacuum/{action}`.
       * Welcher Knopf wann erscheint, entscheidet
       * `components/homeTiles.ts#vacuumActionAvailability` — hier steht nur der
       * Text.
       */
      actions: {
        start: string;
        returnToBase: string;
        /** Während der Request läuft. */
        sending: string;
        /**
         * 200. Sagt AUSDRÜCKLICH nur, dass Home Assistant den Auftrag
         * angenommen hat — nicht, dass der Sauger fährt (der Antwort-Body
         * trägt bewusst kein Zustandsfeld). Die Kachel-Wahrheit kommt weiter
         * aus dem Polling.
         */
        accepted: string;
        /** Letzter Notnagel, wenn ein Fehler ohne lesbare Meldung ankommt — die Server-Meldung hat immer Vorrang. */
        failed: string;
        /** Die Anfrage kam nie an (Netz weg/Abbruch): wir wissen NICHT, ob HA sie bekam, und sagen genau das. */
        networkError: string;
      };
      /**
       * Wartungsblock (seit 22.08. auf L/XL aufgeklappt statt `<details>`,
       * §3.2): Bürsten-/Filter-/Sensoren-Restzeit + Mopp-/Wasserkasten-
       * Anbringung + Mopp-Trocknung — jede Zeile NUR bei brauchbarem Wert.
       *
       * **Restzeit lesbar seit 22.08.** (Andi, wörtlich: „Hauptbürste: 634362 s
       * … nicht in Sekunden ^^" — `ORDER-sauger-wartung-lesbar-2026-08-22.md`):
       * `mainBrush`/`sideBrush`/`filter`/`sensor` bekommen jetzt einen bereits
       * fertig formatierten Restzeit-Satz („noch ~7 Tage"/„überfällig seit
       * ~12 h", s. `components/homeTiles.ts#formatMaintenanceDuration`) statt
       * der rohen HA-Zahl+Einheit — NUR wenn die Einheit eine der vier
       * bekannten HA-`UnitOfTime`-Kürzel ist (`s`/`min`/`h`/`d`); sonst bleibt
       * der alte Wert+Einheit-Text der ehrliche Rückfall (Einheit NIE geraten).
       */
      maintenance: {
        /** Überschrift des Blocks (früher der `<summary>`-Text des Folds). */
        summary: string;
        /** „Hauptbürste: <Wert>" — Wert ist der fertige Restzeit-Satz (s. oben) oder, unkonvertiert, „<Zahl> <Einheit>"/„<Zahl>". */
        mainBrush: (value: string) => string;
        sideBrush: (value: string) => string;
        filter: (value: string) => string;
        sensor: (value: string) => string;
        moppAttached: string;
        moppNotAttached: string;
        waterboxAttached: string;
        waterboxNotAttached: string;
        /** `dock_mopp_trocknung` = on. NUR diese Hälfte hat einen Text: „trocknet nicht" ist der Normalzustand und damit keine Nachricht. */
        moppDrying: string;
        /**
         * Restzeit-Sätze (`components/homeTiles.ts#formatMaintenanceDuration`),
         * je Bucket EIN Text — `remaining` für Restzeit ≥ 0, `overdue` für
         * negative Werte. `remaining.dueNow` ist der eine Grenzfall ohne Zahl
         * (exakt 0 s: weder Rest noch Verzug).
         */
        remaining: { dueNow: string; minutes: (n: number) => string; hours: (n: number) => string; days: (n: number) => string };
        overdue: { minutes: (n: number) => string; hours: (n: number) => string; days: (n: number) => string };
      };
    };
    climate: {
      name: string;
      /** „<Raum> 21,5° → 22°" — der Raumname ist NUTZERDATEN, kommt roh als Parameter. */
      roomLine: (room: string, current: string, target: string) => string;
      /** `hvac_action === 'heating'`. */
      heating: string;
      /** Ein einzelner Raum ohne lesbaren State — der Rest der Kachel bleibt stehen, NUR wenn auch KEIN `lastKnown` existiert (s. {@link lastKnownRoomLine}). */
      roomUnreachable: (room: string) => string;
      /**
       * „<Raum> <alte Ist-Temp> → <alte Soll-Temp> · zuletzt <relative Zeit>"
       * — Fallback EINER Raum-Zeile, wenn ihr Klima-Gerät live unbrauchbar
       * ist, aber `lastKnown` existiert (Andi-Auftrag 2026-08-13). Ersetzt
       * NUR diese eine Zeile, der Rest der Kachel bleibt unberührt.
       */
      lastKnownRoomLine: (room: string, current: string, target: string, relative: string) => string;
      /** „+3 weitere Räume" hinter den ersten {@link CLIMATE_TILE_VISIBLE} Zeilen. */
      restSummary: (count: number) => string;
      /** Die ganze Kachel ohne Registry-Daten (Fetch läuft/Naht aus/nicht erreichbar) ODER kein Raum mit climate. */
      unreachable: string;
    };
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
