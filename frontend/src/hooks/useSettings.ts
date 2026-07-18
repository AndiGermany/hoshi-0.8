import { useCallback, useEffect, useState } from 'react';
import type { Language } from '../api/types';

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
 * Die fünf wählbaren Farbthemen. Aoi (青) = der Default seit Andis Design-Adopt
 * (Cowork-Spec 2026-07-02: „übernehmen wir die Farbe und das Design"). Die
 * bisherigen Themen bleiben wählbar; ein in localStorage gespeichertes Theme
 * wird NICHT überschrieben — nur der Fallback ist jetzt Aoi.
 *
 * 'sora' (Arbeitsname, Andi-Auftrag 19.07) ist eine SECHSTE Wahl obendrauf: kein
 * eigenes Farbthema, sondern „folgt automatisch dem Tag" — löst sich zur Laufzeit
 * in eins der vier Rotations-Themes auf (siehe {@link resolveSoraTheme}). Wird wie
 * jedes andere Theme gespeichert; bestehende manuelle Wahlen bleiben unberührt.
 */
export type Theme = 'aoi' | 'yoru' | 'asa' | 'kasumi' | 'nagareboshi' | 'sora';

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

/** Theme-Katalog fürs Panel (Name + kurzer Charakter-Hinweis, aus den 0.5-Themen). */
export const THEMES: { id: Theme; label: string; hint: string }[] = [
  { id: 'aoi', label: 'Aoi', hint: 'Morgenblau auf Tinte (青) — der Standard' },
  { id: 'yoru', label: 'Yoru', hint: 'Warmes Laternenlicht (夜)' },
  { id: 'asa', label: 'Asa', hint: 'Heller Tag auf Washi-Papier (朝)' },
  { id: 'kasumi', label: 'Kasumi', hint: 'Kühle Nebel-Nacht in Jade (霞)' },
  { id: 'nagareboshi', label: 'Nagareboshi', hint: 'Stille Neumond-Nacht, dunkler Samt (流れ星)' },
  {
    id: 'sora',
    label: 'Sora (automatisch — folgt dem Tag)',
    hint: 'Asa → Aoi → Kasumi → Yoru, nach Uhrzeit dieses Geräts',
  },
];

/**
 * Die wählbaren Sprachen. 'de'/'en' spiegeln das Backend-Enum Language (DE/EN);
 * 'auto' ist die bilinguale Auto-Erkennung — das FE schickt sie als
 * `languagePolicy=AUTO` mit konkretem `language=DE`-Fallback (api/chat.ts).
 */
export const LANGUAGES: { id: Language; label: string }[] = [
  { id: 'auto', label: 'Automatisch (Deutsch / Englisch)' },
  { id: 'de', label: 'Deutsch' },
  { id: 'en', label: 'English' },
];

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
export const PERSONAS: { id: Persona; label: string; description: string; sample: string }[] = [
  {
    id: 'Standard',
    label: 'Standard',
    description: 'Warm, locker, kumpelhaft — Hoshis Grundton.',
    sample: "Morgen wird's mild, so um die 18 Grad — abends könnte ein kleiner Schauer kommen.",
  },
  {
    id: 'Kumpel',
    label: 'Kumpel',
    description: 'Flapsig und spielfreudig: duzt, witzelt, viel Energie.',
    sample: 'Morgen? Locker 18 Grad, bisschen Regen dabei — Jacke einpacken, fertig!',
  },
  {
    id: 'Knapp',
    label: 'Knapp',
    description: 'Wortkarg und sachlich: nur das Nötige, kein Geplänkel.',
    sample: 'Morgen: 18 Grad, leichter Regen.',
  },
  {
    id: 'Ruhig',
    label: 'Ruhig',
    description: 'Sanft und entschleunigt: leise, gelassen, gedämpft.',
    sample: 'Morgen wird es mild, um die 18 Grad — gegen Abend zieht wohl etwas Regen auf.',
  },
];

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

const VALID_THEMES: readonly Theme[] = ['aoi', 'yoru', 'asa', 'kasumi', 'nagareboshi', 'sora'];
const VALID_LANGS: readonly Language[] = ['auto', 'de', 'en'];
const VALID_PERSONAS: readonly Persona[] = ['Standard', 'Kumpel', 'Knapp', 'Ruhig'];

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
//  Nagareboshi bleibt bewusst außen vor: es ist Hoshis Marken-/Signature-Theme,
//  keine der vier „normalen" Tageszeiten. Die Grenzen sind grobe Fenster, keine
//  echten Sonnenauf-/-untergangszeiten — Ausbaustufe: über die Wetter-/Geo-Naht
//  (die App kennt bereits einen Standort für den Wetterblock) ließen sich echte
//  Sonnenzeiten ziehen. Bewusst NICHT Teil dieser Scheibe (Arbeitsauftrag 19.07).
// ─────────────────────────────────────────────────────────────────────────────

/** 06:00 — Asa („Morgen") beginnt. */
export const SORA_ASA_START_HOUR = 6;
/** 10:00 — Aoi („Tag") beginnt. */
export const SORA_AOI_START_HOUR = 10;
/** 18:00 — Kasumi („Abend") beginnt. */
export const SORA_KASUMI_START_HOUR = 18;
/** 22:00 — Yoru („Nacht") beginnt, bis 06:00 (Fenster wickelt über Mitternacht). */
export const SORA_YORU_START_HOUR = 22;

/** Die vier Rotations-Themes von Sora, in Tages-Reihenfolge (Doku + Tests). */
export const SORA_ROTATION: readonly Exclude<Theme, 'sora' | 'nagareboshi'>[] = [
  'asa',
  'aoi',
  'kasumi',
  'yoru',
];

/** Bildet eine lokale Uhrzeit auf das Sora-Theme dieses Tagesfensters ab. */
export function resolveSoraTheme(date: Date): Exclude<Theme, 'sora' | 'nagareboshi'> {
  const h = date.getHours();
  if (h < SORA_ASA_START_HOUR) return 'yoru'; // 00:00–05:59 — noch die Nacht davor
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
