import { useCallback, useEffect, useState } from 'react';
import type { Language } from '../api/types';
import { de } from '../i18n/de';

/**
 * Globale, persistente UI-Einstellungen für die Hoshi-0.8-Shell:
 *   - theme    — Farbthema (data-theme am <html>, Overrides in styles/themes.css)
 *   - language — Chat- + STT-Sprache (fließt in api/chat.ts & api/voice.ts)
 *   - persona  — Persönlichkeit (Gerüst; Optionen kommen aus laufendem Research)
 *   - voice    — OpenAI-Cloud-Stimme (fließt in api/chat.ts & api/voice.ts; ☁️ geht online)
 *
 * Die reinen `loadSettings`/`saveSettings`-Funktionen sind bewusst React-frei,
 * damit die API-Schicht (chat.ts/voice.ts) die persistierte Sprache/Persona als
 * Fallback lesen kann, ohne durch ChatView gereicht zu werden. Headless testbar.
 */

/**
 * Die neun wählbaren Farbthemen. Aoi (青) = der Default seit Andis Design-Adopt
 * (Cowork-Spec 2026-07-02: „übernehmen wir die Farbe und das Design"). Die
 * bisherigen Themen bleiben wählbar; ein in localStorage gespeichertes Theme
 * wird NICHT überschrieben — nur der Fallback ist jetzt Aoi.
 *
 * 'sora' (Arbeitsname, Andi-Auftrag 19.07) ist eine SECHSTE Wahl obendrauf: kein
 * eigenes Farbthema, sondern „folgt automatisch dem Tag" — löst sich zur Laufzeit
 * in eins der vier Rotations-Themes auf (siehe {@link resolveSoraTheme}). Wird wie
 * jedes andere Theme gespeichert; bestehende manuelle Wahlen bleiben unberührt.
 */
export type Theme =
  | 'aoi'
  | 'yoru'
  | 'asa'
  | 'kasumi'
  | 'nagareboshi'
  | 'yoake'
  | 'natsunohi'
  | 'amayadori'
  | 'sora';

/**
 * Persona-Wahl: vier feste Persönlichkeiten. Die IDs sind exakt die Label-Strings,
 * die das Backend (case-insensitiv) als `persona` im Chat-Body erwartet.
 */
export type Persona = 'Standard' | 'Kumpel' | 'Knapp' | 'Ruhig';

export interface Settings {
  theme: Theme;
  language: Language;
  persona: Persona;
  /** OpenAI-Voice-Name (Cloud-TTS). Muss in {@link VOICES} liegen, sonst Default. */
  voice: string;
}

/** Die neun wählbaren Themen in Panel-Reihenfolge (Aoi zuerst = Default). */
export const THEME_IDS: readonly Theme[] = [
  'aoi',
  'yoru',
  'asa',
  'natsunohi',
  'kasumi',
  'nagareboshi',
  'yoake',
  'amayadori',
  'sora',
];

/**
 * Theme-Katalog fürs Panel (Name + kurzer Charakter-Hinweis, aus den 0.5-Themen).
 * Name/Hinweis stehen seit dem Langschwanz-Sweep 25.07 im Text-Katalog
 * (`i18n/*.ts`, `settings.themes`) — hier steht nur noch die REIHENFOLGE der Ids.
 * Diese Konstante bleibt der DE-Blick darauf (byte-gleich zum bisherigen Stand,
 * von Bestandstests referenziert); gerendert wird `useUiStrings().settings.themes`.
 */
export const THEMES: { id: Theme; label: string; hint: string }[] = THEME_IDS.map((id) => ({
  id,
  ...de.settings.themes[id],
}));

/**
 * Die sechs wählbaren Chat-/STT-Sprachen in Panel-Reihenfolge (Andi-Auftrag
 * 2026-07-27, „fünf Sprachen ohne Sternchen"): 'auto' bleibt die bilinguale
 * DE/EN-Erkennung — Español/Français/Italiano kommen NEU dazu, aber NUR als
 * explizite Wahl (s. KDoc von {@link Language} in `api/types.ts`), nie als Teil
 * von 'auto'.
 */
export const LANGUAGE_IDS: readonly Language[] = ['auto', 'de', 'en', 'es', 'fr', 'it'];

/**
 * Die wählbaren Sprachen. 'de'/'en'/'es'/'fr'/'it' spiegeln das Backend-Enum
 * Language (DE/EN/ES/FR/IT); 'auto' ist die bilinguale Auto-Erkennung — das FE
 * schickt sie als `languagePolicy=AUTO` mit konkretem `language=DE`-Fallback
 * (api/chat.ts). Für 'es'/'fr'/'it' lässt das FE `languagePolicy` bewusst WEG
 * (das Backend-Enum `LanguagePolicy` kennt nur AUTO/DE/EN) und schickt nur das
 * konkrete `language`-Feld — s. api/chat.ts & api/voice.ts.
 * Die Anzeigenamen kommen aus dem Text-Katalog (`settings.languages`).
 */
export const LANGUAGES: { id: Language; label: string }[] = LANGUAGE_IDS.map((id) => ({
  id,
  label: de.settings.languages[id],
}));

/**
 * Persona-Katalog fürs Panel (Label + kurze Charakter-Beschreibung). `id` ist der
 * Label-String, der 1:1 als `persona` an das Backend geht (dort case-insensitiv
 * gematcht). Die Beschreibung erscheint live als Hint unter dem Dropdown.
 *
 * `sample` (self-demonstrating Picker): EIN sprechbarer Beispielsatz — wie Hoshi
 * auf „Wie wird das Wetter morgen?" antworten würde. Ton je Persona GENAU an den
 * echten Backend-Prompts kalibriert (PersonaService.kt: toneLineDe + Few-Shots),
 * nicht erfunden. Kein Markdown; erscheint kursiv unter der Beschreibung.
 */
export const PERSONA_IDS: readonly Persona[] = ['Standard', 'Kumpel', 'Knapp', 'Ruhig'];

export const PERSONAS: { id: Persona; label: string; description: string; sample: string }[] =
  PERSONA_IDS.map((id) => ({ id, ...de.settings.personas[id] }));

/**
 * Die 13 wählbaren OpenAI-Stimmen (gpt-4o-mini-tts) — deckungsgleich mit der
 * Backend-Whitelist (`OpenAiTtsAdapter.SUPPORTED_VOICES`). Unbekannte Namen
 * fallen dort STILL auf den Boot-Default (coral) zurück; diese Liste hält den
 * Picker ehrlich (nur, was die Cloud wirklich kann). ☁️ Ehrlichkeit: jede
 * Cloud-Stimme (und jede Hörprobe) geht zu OpenAI.
 */
export const VOICES: readonly string[] = [
  'alloy',
  'ash',
  'ballad',
  'coral',
  'echo',
  'fable',
  'onyx',
  'nova',
  'sage',
  'shimmer',
  'verse',
  'marin',
  'cedar',
];

export const DEFAULT_SETTINGS: Settings = {
  // Default = Aoi (Andi-Entscheid 2026-07-02, Cowork-Spec §1: „übernehmen wir die
  // Farbe und das Design"). Wirkt nur als FALLBACK: ein bereits gespeichertes
  // Theme (loadSettings) gewinnt immer — niemandes Wahl wird überschrieben.
  theme: 'aoi',
  // Default = bilinguale Auto-Erkennung: Hoshi antwortet pro Nachricht in der
  // erkannten Sprache (DE/EN) statt fest auf eine Sprache gepinnt.
  language: 'auto',
  persona: 'Standard',
  // Default = coral, der Boot-Default des Backend-Adapters (byte-neutral: ohne
  // Auswahl klingt Hoshi exakt wie heute).
  voice: 'coral',
};

export const SETTINGS_STORAGE_KEY = 'hoshi.settings';

const VALID_THEMES: readonly Theme[] = THEME_IDS;
const VALID_LANGS: readonly Language[] = LANGUAGE_IDS;
const VALID_PERSONAS: readonly Persona[] = PERSONA_IDS;

/** Defensiver Zugriff auf localStorage (node/SSR/privater Modus kennen es nicht). */
function safeStorage(): Storage | null {
  try {
    if (typeof localStorage !== 'undefined') return localStorage;
  } catch {
    /* Zugriff geblockt (privater Modus) — kein Bruch. */
  }
  return null;
}

/** Lädt die gespeicherten Einstellungen, fällt auf Defaults zurück, validiert Felder. */
export function loadSettings(): Settings {
  const store = safeStorage();
  if (!store) return { ...DEFAULT_SETTINGS };
  try {
    const raw = store.getItem(SETTINGS_STORAGE_KEY);
    if (!raw) return { ...DEFAULT_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<Settings>;
    // Einmal-Migration zum Aoi-Adopt (Andi via Cowork, 2026-07-02: „übernehmen wir
    // die Farbe und das Design"): Bestands-Clients tragen den ALTEN Default 'yoru'
    // im Storage (useSettings persistiert beim ersten Start automatisch) und sähen
    // Aoi sonst nie. Genau EINMAL alter-Default→aoi; wer danach bewusst zurück auf
    // yoru wechselt, bleibt dort (Flag verhindert Wiederholung).
    const AOI_MIGRATION_FLAG = SETTINGS_STORAGE_KEY + '.aoi-migrated';
    if (parsed.theme === 'yoru' && !store.getItem(AOI_MIGRATION_FLAG)) {
      parsed.theme = 'aoi';
      try {
        store.setItem(AOI_MIGRATION_FLAG, '1');
        store.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ ...parsed }));
      } catch {
        /* Storage voll/geblockt — Migration greift dann nur für diese Session. */
      }
    }
    return {
      theme: VALID_THEMES.includes(parsed.theme as Theme)
        ? (parsed.theme as Theme)
        : DEFAULT_SETTINGS.theme,
      language: VALID_LANGS.includes(parsed.language as Language)
        ? (parsed.language as Language)
        : DEFAULT_SETTINGS.language,
      persona: VALID_PERSONAS.includes(parsed.persona as Persona)
        ? (parsed.persona as Persona)
        : DEFAULT_SETTINGS.persona,
      voice: VOICES.includes(parsed.voice as string)
        ? (parsed.voice as string)
        : DEFAULT_SETTINGS.voice,
    };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

/** Persistiert die Einstellungen, defensiv (kein Bruch, wenn Storage blockiert/voll). */
export function saveSettings(settings: Settings): void {
  const store = safeStorage();
  if (!store) return;
  try {
    store.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings));
  } catch {
    /* ignorieren */
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Eskalations-Frist (Wecker-Ursprungs-Lane) — standalone Pref
//
//  Bewusst NICHT im {@link Settings}-Objekt, sondern als eigener localStorage-Key
//  (Muster wie loadSpeakPref in audio/playback.ts): eine reine Verhaltens-Zahl,
//  die der Klingel-Hook (useFiredItems) liest — kein Grund, das Chat-Request-
//  Settings-Objekt dafür zu verbreitern. „Wecker bimmelt erst am Gerät, wo du ihn
//  gestellt hast — nach X Sekunden auf allen." (Sara-Ton)
// ─────────────────────────────────────────────────────────────────────────────

/** localStorage-Schlüssel der Eskalations-Frist (Sekunden). */
export const ESCALATION_STORAGE_KEY = 'hoshi.escalationSeconds';

/** Default: 15 s — kurz genug, dass ein verpasster Wecker rasch überall bimmelt. */
export const DEFAULT_ESCALATION_SECONDS = 15;

/** Sinnvoller Bereich der Frist (s): nicht hektisch, nicht ewig. */
export const ESCALATION_MIN_SECONDS = 5;
export const ESCALATION_MAX_SECONDS = 120;

/** Auf den gültigen Bereich klemmen; Müll/NaN → Default. */
export function clampEscalationSeconds(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_ESCALATION_SECONDS;
  return Math.min(ESCALATION_MAX_SECONDS, Math.max(ESCALATION_MIN_SECONDS, Math.round(value)));
}

/** Liest die persistierte Eskalations-Frist (s); unbelegt/kaputt → Default. */
export function loadEscalationSeconds(): number {
  const store = safeStorage();
  if (!store) return DEFAULT_ESCALATION_SECONDS;
  try {
    const raw = store.getItem(ESCALATION_STORAGE_KEY);
    if (raw === null) return DEFAULT_ESCALATION_SECONDS;
    const n = Number(raw);
    return Number.isFinite(n) ? clampEscalationSeconds(n) : DEFAULT_ESCALATION_SECONDS;
  } catch {
    return DEFAULT_ESCALATION_SECONDS;
  }
}

/** Persistiert die Eskalations-Frist (geklemmt), defensiv. */
export function saveEscalationSeconds(seconds: number): void {
  const store = safeStorage();
  if (!store) return;
  try {
    store.setItem(ESCALATION_STORAGE_KEY, String(clampEscalationSeconds(seconds)));
  } catch {
    /* Storage voll/geblockt — ignorieren. */
  }
}

export interface UseEscalationSecondsResult {
  seconds: number;
  setSeconds: (seconds: number) => void;
}

/** React-Hook über die Eskalations-Frist: initial aus localStorage, persistiert bei Änderung. */
export function useEscalationSeconds(): UseEscalationSecondsResult {
  const [seconds, setSecondsState] = useState<number>(() => loadEscalationSeconds());
  const setSeconds = useCallback((next: number) => {
    const clamped = clampEscalationSeconds(next);
    setSecondsState(clamped);
    saveEscalationSeconds(clamped);
  }, []);
  return { seconds, setSeconds };
}

// ─────────────────────────────────────────────────────────────────────────────
//  Sora (Arbeitsname) — automatischer Theme-Wechsel nach Tageszeit
//
//  Rein zeit-basiert auf der GERÄTE-Uhr (lokale Zeit) — kein Netz, kein Geo.
//  Yoake bleibt bewusst außen vor: ein eigenständiger manueller Look statt einer
//  der „normalen" Tageszeiten.
//
//  Nagareboshi war bis 0.8.2 ebenfalls ausgenommen — es war Hoshis Signature-Theme.
//  Seit Suisei diese Rolle trägt, ist die Sternschnuppe frei und bekommt genau das
//  Fenster, in das sie gehört: 02:00–05:59, die tiefste Nacht. Wer um drei Uhr
//  morgens mit Hoshi redet, bekommt sie — kein Gag, keine Überraschung, die stört,
//  sondern ein Look, den man FINDET, wenn man zu einer Zeit da ist, zu der man
//  Sternschnuppen sieht. Suisei ist die Bahn, Nagareboshi der Moment.
//  Die Grenzen sind
//  grobe Fenster, keine echten Sonnenauf-/-untergangszeiten — Ausbaustufe: über
//  die Wetter-/Geo-Naht (die App kennt bereits einen Standort für den Wetterblock)
//  ließen sich echte Sonnenzeiten ziehen. Bewusst NICHT Teil dieser Scheibe.
// ─────────────────────────────────────────────────────────────────────────────

/** 02:00 — Nagareboshi („Sternschnuppe") beginnt, bis 05:59: die tiefste Nacht. */
export const SORA_NAGAREBOSHI_START_HOUR = 2;
/** 06:00 — Asa („Morgen") beginnt. */
export const SORA_ASA_START_HOUR = 6;
/** 10:00 — Aoi („Tag") beginnt. */
export const SORA_AOI_START_HOUR = 10;
/** 18:00 — Kasumi („Abend") beginnt. */
export const SORA_KASUMI_START_HOUR = 18;
/** 22:00 — Yoru („Nacht") beginnt, bis 06:00 (Fenster wickelt über Mitternacht). */
export const SORA_YORU_START_HOUR = 22;

/** Die fünf Rotations-Themes von Sora, in Tages-Reihenfolge (Doku + Tests). */
export type SoraTheme = 'nagareboshi' | 'asa' | 'aoi' | 'kasumi' | 'yoru';
export const SORA_ROTATION: readonly SoraTheme[] = ['nagareboshi', 'asa', 'aoi', 'kasumi', 'yoru'];

// ─────────────────────────────────────────────────────────────────────────────
//  Theme-GRUPPEN (Andi 25.07: „Überlege dir ein Konzept, wie man die Auswahl der
//  Themen übersichtlicher machen kann. Das sind jetzt schon einige.")
//
//  Das Problem war nie die Menge, sondern die UNGLEICHARTIGKEIT: sechs
//  Farbwelten, eine Automatik (sora) und nagareboshi, das seit 0.8.2 beides ist
//  — wählbar UND Teil der Tagesrotation. Drei Gruppen statt einer Liste:
//
//    1. 'automatik'   — nur Sora. Keine Farbe, sondern eine REGEL.
//    2. 'tageszeiten' — die fünf Rotations-Themes in TAGESREIHENFOLGE
//                       ({@link SORA_ROTATION}), nicht alphabetisch: wer eins
//                       fest wählt, pinnt damit einen Schritt der Automatik.
//    3. 'stimmung'    — Yoake, Natsu no Hi + Amayadori. Bilder, keine
//                       Tageszeiten; sie hängen bewusst NICHT an der Uhr.
//
//  Die IDs selbst sind PERSISTIERT (localStorage, {@link SETTINGS_STORAGE_KEY})
//  und bleiben unangetastet — hier wird ausschließlich die ANZEIGE gruppiert.
//  {@link THEME_IDS}/{@link THEMES} bleiben ebenfalls, wie sie sind (Reihenfolge
//  + Katalog-Blick der Bestandstests); die Gruppen sind eine zweite Sicht auf
//  dieselbe Menge, kein Ersatz — `themegroups.test.tsx` hält beide deckungsgleich.
// ─────────────────────────────────────────────────────────────────────────────

export type ThemeGroupId = 'automatik' | 'tageszeiten' | 'stimmung';

/** Die drei Gruppen in Anzeige-Reihenfolge (Automatik zuerst, ganz oben). */
export const THEME_GROUP_IDS: readonly ThemeGroupId[] = ['automatik', 'tageszeiten', 'stimmung'];

export interface ThemeGroup {
  id: ThemeGroupId;
  /** Die Themen dieser Gruppe, in der Reihenfolge, in der sie erscheinen sollen. */
  themes: readonly Theme[];
}

/**
 * Die Gruppierung des Farbthema-Pickers. Zusammen decken die drei Gruppen
 * exakt {@link THEME_IDS} ab (jede Id genau einmal) — geprüft im Test, damit ein
 * künftiges zehntes Theme nicht still aus dem Panel fällt.
 */
export const THEME_GROUPS: readonly ThemeGroup[] = [
  { id: 'automatik', themes: ['sora'] },
  // Tages-Reihenfolge, NICHT alphabetisch: der Bogen der Automatik ist die
  // Ordnung, die man beim Wählen im Kopf hat (tiefe Nacht → Morgen → … → Nacht).
  { id: 'tageszeiten', themes: SORA_ROTATION },
  { id: 'stimmung', themes: ['yoake', 'natsunohi', 'amayadori'] },
];

/** Bildet eine lokale Uhrzeit auf das Sora-Theme dieses Tagesfensters ab. */
export function resolveSoraTheme(date: Date): SoraTheme {
  const h = date.getHours();
  if (h < SORA_NAGAREBOSHI_START_HOUR) return 'yoru'; // 00:00–01:59 — noch die Nacht davor
  if (h < SORA_ASA_START_HOUR) return 'nagareboshi'; // 02:00–05:59 — die tiefste Nacht
  if (h < SORA_AOI_START_HOUR) return 'asa'; // 06:00–09:59
  if (h < SORA_KASUMI_START_HOUR) return 'aoi'; // 10:00–17:59
  if (h < SORA_YORU_START_HOUR) return 'kasumi'; // 18:00–21:59
  return 'yoru'; // 22:00–23:59
}

/**
 * Millisekunden bis zur nächsten Sora-Fenstergrenze (für den Wechsel-Timer:
 * EIN Timeout auf den nächsten Sprung, statt Minuten-Polling).
 */
export function msUntilNextSoraBoundary(date: Date): number {
  const boundaries = [
    SORA_ASA_START_HOUR,
    SORA_AOI_START_HOUR,
    SORA_KASUMI_START_HOUR,
    SORA_YORU_START_HOUR,
  ];
  const h = date.getHours();
  const nextHour = boundaries.find((b) => b > h);
  const target = new Date(date);
  target.setHours(nextHour ?? SORA_ASA_START_HOUR, 0, 0, 0);
  if (nextHour === undefined) target.setDate(target.getDate() + 1); // nächster Tag: 06:00 morgen
  return target.getTime() - date.getTime();
}

/**
 * Löst 'sora' zur aktuellen Uhrzeit auf ein konkretes Anzeige-Theme auf; jedes
 * andere Theme geht unverändert durch (Identität — manuelle Wahlen bleiben
 * unberührt). Solange 'sora' aktiv ist, läuft EIN Timer auf die nächste
 * Fenstergrenze; danach wird neu aufgelöst und der nächste Timer gesetzt.
 */
export function useResolvedTheme(theme: Theme): Theme {
  const [now, setNow] = useState<Date>(() => new Date());
  useEffect(() => {
    if (theme !== 'sora') return;
    const ms = msUntilNextSoraBoundary(new Date());
    const id = setTimeout(() => setNow(new Date()), ms);
    return () => clearTimeout(id);
    // `now` gehört bewusst in die Deps: erst der Boundary-Feuer-Tick plant den
    // nächsten Timer neu (kein Minuten-Polling, EIN Timeout pro Fenster).
  }, [theme, now]);
  return theme === 'sora' ? resolveSoraTheme(now) : theme;
}

export interface UseSettingsResult extends Settings {
  setTheme: (theme: Theme) => void;
  setLanguage: (language: Language) => void;
  setPersona: (persona: Persona) => void;
  setVoice: (voice: string) => void;
}

/**
 * React-Hook über {@link Settings}: initial aus localStorage, persistiert bei jeder
 * Änderung. App liest `theme` und setzt data-theme am <html>; Sprache/Persona
 * werden zusätzlich von der API-Schicht direkt aus localStorage gelesen (Fallback).
 */
export function useSettings(): UseSettingsResult {
  const [settings, setSettings] = useState<Settings>(() => loadSettings());

  useEffect(() => {
    saveSettings(settings);
  }, [settings]);

  const setTheme = useCallback((theme: Theme) => setSettings((s) => ({ ...s, theme })), []);
  const setLanguage = useCallback(
    (language: Language) => setSettings((s) => ({ ...s, language })),
    [],
  );
  const setPersona = useCallback((persona: Persona) => setSettings((s) => ({ ...s, persona })), []);
  const setVoice = useCallback((voice: string) => setSettings((s) => ({ ...s, voice })), []);

  return { ...settings, setTheme, setLanguage, setPersona, setVoice };
}
