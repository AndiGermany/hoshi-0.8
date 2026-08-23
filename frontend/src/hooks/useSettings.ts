import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import type { Language } from '../api/types';
import type { HomeWidgetId } from '../components/homeWidgets';
import { de } from '../i18n/de';
import {
  isKnownTheme,
  loadThemeManifest,
  visibleGroups,
  type ThemeManifest,
  type VisibleThemeGroup,
} from '../styles/themeCatalog';

/**
 * Die Gruppen-Ids des Theme-Pickers wohnen seit dem .old-Umzug (2026-08-08) im
 * Manifest-Modul — sie sind ein Manifest-Begriff, kein Settings-Begriff. Hier
 * nur re-exportiert, damit der i18n-Katalog (`i18n/types.ts`) seine
 * Import-Adresse behält.
 */
export type { ThemeGroupId, VisibleThemeGroup } from '../styles/themeCatalog';

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
 * Die neun im Picker SICHTBAREN Farbthemen. Aoi (青) = der Default seit Andis Design-Adopt
 * (Cowork-Spec 2026-07-02: „übernehmen wir die Farbe und das Design"). Die
 * bisherigen Themen bleiben wählbar; ein in localStorage gespeichertes Theme
 * wird NICHT überschrieben — nur der Fallback ist jetzt Aoi.
 *
 * 'sora' (Arbeitsname, Andi-Auftrag 19.07) ist eine SECHSTE Wahl obendrauf: kein
 * eigenes Farbthema, sondern „folgt automatisch dem Tag" — löst sich zur Laufzeit
 * in eins der vier Rotations-Themes auf (siehe {@link resolveSoraTheme}). Wird wie
 * jedes andere Theme gespeichert; bestehende manuelle Wahlen bleiben unberührt.
 */
export type VisibleTheme =
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
 * Eine Theme-Id — seit dem .old-Umzug (2026-08-08) bewusst ein OFFENER `string`
 * und keine Union mehr.
 *
 * Grund: die Themen sind jetzt Dateien unter `public/themes/` und stehen in
 * `public/themes/manifest.json`. Eine TS-Union hier wäre eine zweite, sofort
 * driftende Wahrheit — ein neues Thema hieße wieder „Frontend anfassen". Was
 * echt existiert, entscheidet das Manifest; wo es auf eine gespeicherte Wahl
 * ankommt, prüft {@link loadSettings} dagegen (unbekannt ⇒ Default).
 *
 * {@link VisibleTheme} bleibt daneben stehen: es beschreibt genau die Themen,
 * für die die fünf TEXT-Kataloge (`i18n/*.ts`, `settings.themes`) einen
 * Beschreibungssatz führen — das ist eine andere Menge als „alle Themen".
 */
export type Theme = string;

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

/**
 * Die neun Themen, für die die TEXT-Kataloge (`i18n/*.ts`, `settings.themes`)
 * einen Beschreibungssatz führen — in ihrer historischen Reihenfolge.
 *
 * ACHTUNG, seit dem .old-Umzug (2026-08-08): das ist NICHT mehr die Picker-
 * Wahrheit. Was im Picker steht, in welcher Gruppe und in welcher Reihenfolge,
 * sagt allein `public/themes/manifest.json` (s. `styles/themeCatalog.ts`).
 * Diese Liste ist nur noch der Schlüsselsatz der Text-Kataloge und der Anker
 * der Bestandstests, die deren Vollständigkeit prüfen.
 */
export const THEME_IDS: readonly VisibleTheme[] = [
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

/**
 * Die neun Text-Katalog-Themen plus das versteckte Nagori — die Menge, die
 * dieses Frontend seit der 0.8-Linie kennt.
 *
 * Seit dem .old-Umzug ist auch das kein Riegel mehr: was persistiert werden
 * darf, sagt das Manifest ({@link isKnownTheme}). Die Konstante bleibt als
 * Doku- und Testanker stehen — und als Beleg, dass der Umzug keine dieser Ids
 * verloren hat (der Manifest-Test prüft genau das).
 */
export const ALL_THEME_IDS: readonly Theme[] = [...THEME_IDS, 'nagori'];

const VALID_LANGS: readonly Language[] = LANGUAGE_IDS;
const VALID_PERSONAS: readonly Persona[] = PERSONA_IDS;

/**
 * Sieht dieser Wert überhaupt wie eine Theme-Id aus? (Sie landet in einem
 * CSS-Attribut-Selektor und in einem Datei-Pfad — dieselbe enge Form wie im
 * Manifest-Parser.) Fängt Müll ab, BEVOR das Manifest da ist.
 */
function isPlausibleThemeId(value: unknown): value is string {
  return typeof value === 'string' && /^[a-z0-9-]+$/.test(value);
}

/**
 * Defensiver Zugriff auf localStorage (node/SSR/privater Modus kennen es nicht).
 *
 * **Exportiert seit W3** (`hooks/useHomeLayout.ts`): der Layout-Speicher muss
 * auf DEMSELBEN Storage-Zugang sitzen wie die Schalter — eine zweite,
 * strukturgleiche Kopie wäre eine zweite Wahrheit über „privater Modus".
 */
export function safeStorage(): Storage | null {
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
      // Theme-Riegel seit dem .old-Umzug: geprüft wird gegen das MANIFEST, nicht
      // gegen eine Union im Code. Solange das Manifest noch unterwegs ist
      // (Kaltstart), lässt {@link isKnownTheme} jede plausible Id durch — eine
      // gespeicherte Wahl darf nicht sterben, nur weil eine Datei noch lädt.
      // Sobald es da ist, räumt {@link useSettings} nach (Effekt unten).
      theme: isPlausibleThemeId(parsed.theme) && isKnownTheme(parsed.theme)
        ? parsed.theme
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
//  Zuhause-Kacheln (Sauger/Klima/Lagebild) — standalone Prefs
//
//  Andi-Auftrag 2026-08-11 „Zuhause-Kacheln, die man sich verdient": zwei
//  reine Sichtbarkeits-Flags für den Home-Reiter, bewusst NICHT im
//  {@link Settings}-Objekt (exaktes Muster der Eskalations-Frist oben) — nur
//  das Zuhause-Reiter-Rendering (IdleFace) und das neue Settings-Zahnrad
//  (SettingsPanel) lesen sie, kein Grund, das Chat-Request-Settings-Objekt zu
//  verbreitern. Default AUS (Andi schaltet bewusst an): im SettingsPanel
//  existiert ein Schalter ohnehin nur, wenn seine Datenquelle real ist
//  (vacuum gefunden / ≥1 Raum mit climate) — dass die Quelle da ist, ist kein
//  Grund, sie automatisch anzuschalten.
//
//  NACHTRAG (Lagebild-Schalter): the third flag follows the identical seam but
//  defaults ON. Its tile has no discoverable HA source that could gate it, and
//  it already keeps itself invisible until the endpoint carries real headlines
//  — so the honest default is "visible when it has something to say", and the
//  switch exists for the case where Andi does NOT want to see the news at all.
//  It is a DISPLAY switch only: the voice path never reads these flags.
//
//  NACHTRAG W2 (`vault/tracks/DESIGN-widget-raster-2026-08-18.md` §1.2): five
//  more flags for the widgets that were always-on and unswitchable before —
//  clock/alarm (Krone) plus weather/scheduled/shopping (Bühne). Same idiom,
//  DEFAULT ON (loadTileFlagDefaultOn — they took nothing away before, so a
//  new switch must not silently hide them). The three keys above keep their
//  name, semantics and default byte-for-byte — no migration, no JSON blob
//  (design rationale: an object would force a version bump on these three).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * All eight home-widget ids — the Krone pair plus the six Bühne tiles (§1.1 of
 * the design doc). Was defined locally here (W2 could not import `homeWidgets.ts`
 * — that file is W1's, and did not exist yet in the W2 pod's worktree); now that
 * both landed on the same branch, re-exported from the registry so there is one
 * source instead of two structurally-identical unions drifting apart (idiom:
 * {@link ThemeGroupId} above).
 */
export type { HomeWidgetId } from '../components/homeWidgets';

/** localStorage-Schlüssel der Sauger-Kachel-Sichtbarkeit. */
export const VACUUM_TILE_STORAGE_KEY = 'hoshi.homeTiles.vacuum';
/** localStorage-Schlüssel der Klima-Kachel-Sichtbarkeit. */
export const CLIMATE_TILE_STORAGE_KEY = 'hoshi.homeTiles.climate';
/**
 * localStorage key of the "Lagebild" window's visibility. Same storage idiom as
 * the two above, but the DEFAULT is ON (see {@link loadCurrentAffairsTileEnabled}):
 * unlike the vacuum/climate tiles, this window already earns its own place —
 * it renders nothing at all unless the endpoint really has headlines — so a
 * default-off switch would only hide a tile that is silent anyway.
 */
export const CURRENT_AFFAIRS_TILE_STORAGE_KEY = 'hoshi.homeTiles.currentAffairs';

/** Liest ein Boolean-Flag aus localStorage; unbelegt/blockiert/kaputt ⇒ `false` (Default AUS). */
function loadTileFlag(key: string): boolean {
  const store = safeStorage();
  if (!store) return false;
  try {
    return store.getItem(key) === 'true';
  } catch {
    return false;
  }
}

/** Persistiert ein Boolean-Flag, defensiv (kein Bruch, wenn Storage blockiert/voll). */
function saveTileFlag(key: string, value: boolean): void {
  const store = safeStorage();
  if (!store) return;
  try {
    store.setItem(key, String(value));
  } catch {
    /* Storage voll/geblockt — ignorieren. */
  }
}

/**
 * Reads a boolean flag whose DEFAULT is ON: only a literal `'false'` switches
 * it off — an unset key, a blocked/full storage or a garbage value all keep it
 * on. Deliberate mirror image of {@link loadTileFlag}: a default-on tile must
 * not fall dark just because localStorage is unavailable.
 */
function loadTileFlagDefaultOn(key: string): boolean {
  const store = safeStorage();
  if (!store) return true;
  try {
    return store.getItem(key) !== 'false';
  } catch {
    return true;
  }
}

/** Ist die Sauger-Kachel aktiviert? */
export function loadVacuumTileEnabled(): boolean {
  return loadTileFlag(VACUUM_TILE_STORAGE_KEY);
}
/** Persistiert die Sauger-Kachel-Sichtbarkeit. */
export function saveVacuumTileEnabled(enabled: boolean): void {
  saveTileFlag(VACUUM_TILE_STORAGE_KEY, enabled);
}
/** Ist die Klima-Kachel aktiviert? */
export function loadClimateTileEnabled(): boolean {
  return loadTileFlag(CLIMATE_TILE_STORAGE_KEY);
}
/** Persistiert die Klima-Kachel-Sichtbarkeit. */
export function saveClimateTileEnabled(enabled: boolean): void {
  saveTileFlag(CLIMATE_TILE_STORAGE_KEY, enabled);
}
/** Is the "Lagebild" window switched on? DEFAULT ON, see {@link CURRENT_AFFAIRS_TILE_STORAGE_KEY}. */
export function loadCurrentAffairsTileEnabled(): boolean {
  return loadTileFlagDefaultOn(CURRENT_AFFAIRS_TILE_STORAGE_KEY);
}
/** Persists the "Lagebild" window's visibility. */
export function saveCurrentAffairsTileEnabled(enabled: boolean): void {
  saveTileFlag(CURRENT_AFFAIRS_TILE_STORAGE_KEY, enabled);
}

/** localStorage key of the "Uhr" (clock) header switch — Krone group, DEFAULT ON. */
export const CLOCK_TILE_STORAGE_KEY = 'hoshi.homeTiles.uhr';
/** localStorage key of the "Wecker" (alarm) header switch — Krone group, DEFAULT ON. */
export const ALARM_TILE_STORAGE_KEY = 'hoshi.homeTiles.wecker';
/** localStorage key of the "Wetter" (weather) tile switch, DEFAULT ON. */
export const WEATHER_TILE_STORAGE_KEY = 'hoshi.homeTiles.wetter';
/** localStorage key of the "Läuft" (running timers/alarms/reminders) tile switch, DEFAULT ON. */
export const SCHEDULED_TILE_STORAGE_KEY = 'hoshi.homeTiles.laeuft';
/** localStorage key of the "Einkauf" (shopping list) tile switch, DEFAULT ON. */
export const SHOPPING_TILE_STORAGE_KEY = 'hoshi.homeTiles.einkauf';

/** Is the clock header switched on? DEFAULT ON, see {@link CLOCK_TILE_STORAGE_KEY}. */
export function loadClockTileEnabled(): boolean {
  return loadTileFlagDefaultOn(CLOCK_TILE_STORAGE_KEY);
}
/** Persists the clock header's visibility. */
export function saveClockTileEnabled(enabled: boolean): void {
  saveTileFlag(CLOCK_TILE_STORAGE_KEY, enabled);
}
/** Is the alarm header switched on? DEFAULT ON, see {@link ALARM_TILE_STORAGE_KEY}. */
export function loadAlarmTileEnabled(): boolean {
  return loadTileFlagDefaultOn(ALARM_TILE_STORAGE_KEY);
}
/** Persists the alarm header's visibility. */
export function saveAlarmTileEnabled(enabled: boolean): void {
  saveTileFlag(ALARM_TILE_STORAGE_KEY, enabled);
}
/** Is the weather tile switched on? DEFAULT ON, see {@link WEATHER_TILE_STORAGE_KEY}. */
export function loadWeatherTileEnabled(): boolean {
  return loadTileFlagDefaultOn(WEATHER_TILE_STORAGE_KEY);
}
/** Persists the weather tile's visibility. */
export function saveWeatherTileEnabled(enabled: boolean): void {
  saveTileFlag(WEATHER_TILE_STORAGE_KEY, enabled);
}
/** Is the "Läuft" (scheduled items) tile switched on? DEFAULT ON, see {@link SCHEDULED_TILE_STORAGE_KEY}. */
export function loadScheduledTileEnabled(): boolean {
  return loadTileFlagDefaultOn(SCHEDULED_TILE_STORAGE_KEY);
}
/** Persists the "Läuft" tile's visibility. */
export function saveScheduledTileEnabled(enabled: boolean): void {
  saveTileFlag(SCHEDULED_TILE_STORAGE_KEY, enabled);
}
/** Is the shopping list tile switched on? DEFAULT ON, see {@link SHOPPING_TILE_STORAGE_KEY}. */
export function loadShoppingTileEnabled(): boolean {
  return loadTileFlagDefaultOn(SHOPPING_TILE_STORAGE_KEY);
}
/** Persists the shopping list tile's visibility. */
export function saveShoppingTileEnabled(enabled: boolean): void {
  saveTileFlag(SHOPPING_TILE_STORAGE_KEY, enabled);
}

/** Persist-side of the generic `enabled` record — one entry point per widget id, reused by {@link useHomeTiles}'s `setEnabled`. */
const HOME_WIDGET_SAVERS: Record<HomeWidgetId, (enabled: boolean) => void> = {
  uhr: saveClockTileEnabled,
  wecker: saveAlarmTileEnabled,
  wetter: saveWeatherTileEnabled,
  laeuft: saveScheduledTileEnabled,
  einkauf: saveShoppingTileEnabled,
  vacuum: saveVacuumTileEnabled,
  climate: saveClimateTileEnabled,
  news: saveCurrentAffairsTileEnabled,
};

export interface UseHomeTilesResult {
  vacuumEnabled: boolean;
  setVacuumEnabled: (enabled: boolean) => void;
  climateEnabled: boolean;
  setClimateEnabled: (enabled: boolean) => void;
  /**
   * "Lagebild" window — DEFAULT ON (the other two default off). This flag is a
   * pure DISPLAY switch: off means the window and its "mehr" expansion never
   * appear AND the endpoint is no longer polled (see
   * `hooks/useCurrentAffairs.ts`). It touches neither the backend feature flag
   * nor the voice path — asking Hoshi out loud keeps working either way.
   */
  currentAffairsEnabled: boolean;
  setCurrentAffairsEnabled: (enabled: boolean) => void;
  /**
   * Generic view over ALL eight widgets — same live state as the three named
   * fields above (they stay as thin aliases so existing callers/tests keep
   * working), plus the five new W2 switches. `SettingsPanel`'s widget list
   * reads/writes exclusively through this pair (§1.2 of the design doc).
   */
  enabled: Record<HomeWidgetId, boolean>;
  setEnabled: (id: HomeWidgetId, on: boolean) => void;
}

/**
 * Same-Tab-Sync der Kachel-Flags: localStorage feuert im EIGENEN Tab kein
 * `storage`-Event — der Schalter (SettingsPanel) und die Kachel (IdleFaceLive)
 * halten je eine eigene Hook-Instanz, und mit zwei blinden useStates erschien
 * Andis frisch aktivierte Kachel erst nach einem Reload (Live-Fund
 * 2026-08-11 ~23:20). Ein Modul-lokaler Mini-Store benachrichtigt alle
 * Instanzen im selben Dokument; `useSyncExternalStore` liest den Ist-Stand
 * direkt aus localStorage (primitive Rückgabe, kein Tearing).
 */
const homeTilesListeners = new Set<() => void>();
/**
 * Abo auf die Zuhause-Kachel-Wahrheit. **Exportiert seit W3**: das gespeicherte
 * Layout (`hooks/useHomeLayout.ts`) hängt an DIESER Naht, nicht an einer
 * eigenen — sonst sähe die Bühne ein „Layout zurücksetzen" aus den
 * Einstellungen erst nach einem Reload (genau der Live-Fund vom 11.08.).
 */
export function subscribeHomeTiles(listener: () => void): () => void {
  homeTilesListeners.add(listener);
  return () => homeTilesListeners.delete(listener);
}
/** Weckt alle Hook-Instanzen im Dokument — s. {@link subscribeHomeTiles}. */
export function notifyHomeTiles(): void {
  for (const listener of homeTilesListeners) listener();
}

/** React-Hook über die Zuhause-Kacheln-Flags: liest live, persistiert + benachrichtigt ALLE Instanzen bei Änderung. */
export function useHomeTiles(): UseHomeTilesResult {
  // Dritter Parameter = Server-Snapshot: die Test-Suite rendert per
  // renderToStaticMarkup (SSR-Pfad), dort liest derselbe Loader.
  const vacuumEnabled = useSyncExternalStore(subscribeHomeTiles, loadVacuumTileEnabled, loadVacuumTileEnabled);
  const climateEnabled = useSyncExternalStore(subscribeHomeTiles, loadClimateTileEnabled, loadClimateTileEnabled);
  const currentAffairsEnabled = useSyncExternalStore(
    subscribeHomeTiles,
    loadCurrentAffairsTileEnabled,
    loadCurrentAffairsTileEnabled,
  );
  // W2: the five new switches, same idiom.
  const uhrEnabled = useSyncExternalStore(subscribeHomeTiles, loadClockTileEnabled, loadClockTileEnabled);
  const weckerEnabled = useSyncExternalStore(subscribeHomeTiles, loadAlarmTileEnabled, loadAlarmTileEnabled);
  const wetterEnabled = useSyncExternalStore(subscribeHomeTiles, loadWeatherTileEnabled, loadWeatherTileEnabled);
  const laeuftEnabled = useSyncExternalStore(subscribeHomeTiles, loadScheduledTileEnabled, loadScheduledTileEnabled);
  const einkaufEnabled = useSyncExternalStore(subscribeHomeTiles, loadShoppingTileEnabled, loadShoppingTileEnabled);
  const setVacuumEnabled = useCallback((enabled: boolean) => {
    saveVacuumTileEnabled(enabled);
    notifyHomeTiles();
  }, []);
  const setClimateEnabled = useCallback((enabled: boolean) => {
    saveClimateTileEnabled(enabled);
    notifyHomeTiles();
  }, []);
  const setCurrentAffairsEnabled = useCallback((enabled: boolean) => {
    saveCurrentAffairsTileEnabled(enabled);
    notifyHomeTiles();
  }, []);
  const setEnabled = useCallback((id: HomeWidgetId, on: boolean) => {
    HOME_WIDGET_SAVERS[id](on);
    notifyHomeTiles();
  }, []);
  return {
    vacuumEnabled,
    setVacuumEnabled,
    climateEnabled,
    setClimateEnabled,
    currentAffairsEnabled,
    setCurrentAffairsEnabled,
    enabled: {
      uhr: uhrEnabled,
      wecker: weckerEnabled,
      wetter: wetterEnabled,
      laeuft: laeuftEnabled,
      einkauf: einkaufEnabled,
      vacuum: vacuumEnabled,
      climate: climateEnabled,
      news: currentAffairsEnabled,
    },
    setEnabled,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
//  Presence memory of the home tiles — slice S2 "Ehrliche Anwesenheit"
//  (vault/tracks/DESIGN-widgets-settings-2026-08-15.md §2.4, route 1: FE-only)
//
//  Live finding 15.08.2026: HA reported the whole Roborock family as
//  `unavailable` with `lastKnown=null`. The tile did the formally right thing
//  and whispered "grad nicht erreichbar" in 13px/--text-4 next to 18px
//  neighbours — Andi read that silence as a Hoshi bug. The fix is not a louder
//  alarm (an OLD outage is not an ACUTE one, and amber stays reserved for the
//  vacuum's real `error` state); it is an honest MINIMAL PRESENCE: a tile whose
//  source was demonstrably alive at some point says for how long it has been
//  gone, in the same 18px voice as its normal lines.
//
//  That needs a "since when", and the design offers two routes: (1) the FE
//  remembers it, (2) the BE ships `unavailableSince` next to `lastKnown`. This
//  is route 1 — buildable today, no BE seam. Its honest cost: the memory lives
//  in this browser profile, so a device/browser change starts over and the tile
//  falls back to the quiet line until it has seen its source once. Route 2 stays
//  the better answer and would replace ONLY the read side here.
//
//  Deliberately NOT part of `useHomeTiles`: those three flags are Andi's
//  DECISIONS (he sets them), these stamps are OBSERVATIONS (the tile writes
//  them). Same storage idiom, same notify seam, different meaning — mixing them
//  into one hook would let a settings render write presence stamps.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The tiles that remember their own presence. Kept as a narrow union rather
 * than a free string: the value ends up in a localStorage key, and a typo would
 * silently create a second, never-read store.
 */
export type HomeTileLastSeenKey = 'vacuum' | 'climate';

/** Key prefix of the presence stamps: `hoshi.homeTiles.lastSeen.<key>`. */
export const HOME_TILE_LAST_SEEN_KEY_PREFIX = 'hoshi.homeTiles.lastSeen.';

/** The full localStorage key of one tile's presence stamp. */
export function homeTileLastSeenStorageKey(key: HomeTileLastSeenKey): string {
  return `${HOME_TILE_LAST_SEEN_KEY_PREFIX}${key}`;
}

/**
 * Reads the epoch-ms stamp of the last moment this tile's source was alive.
 * Unset, blocked storage, or anything that is not a positive finite number all
 * yield `null` — "we do not know", which keeps the tile on its quiet line
 * instead of inventing an outage duration out of garbage.
 */
export function loadHomeTileLastSeen(key: HomeTileLastSeenKey): number | null {
  const store = safeStorage();
  if (!store) return null;
  try {
    const raw = store.getItem(homeTileLastSeenStorageKey(key));
    if (!raw) return null;
    const ms = Number(raw);
    return Number.isFinite(ms) && ms > 0 ? ms : null;
  } catch {
    return null;
  }
}

/** Persists a presence stamp, defensively (blocked/full storage must not break a render). */
export function saveHomeTileLastSeen(key: HomeTileLastSeenKey, atMs: number): void {
  if (!Number.isFinite(atMs) || atMs <= 0) return;
  const store = safeStorage();
  if (!store) return;
  try {
    store.setItem(homeTileLastSeenStorageKey(key), String(Math.round(atMs)));
  } catch {
    /* Storage voll/geblockt — ignorieren (Muster saveTileFlag). */
  }
}

/**
 * The presence seam of ONE tile. [presentAtMs] is the caller's "my source is
 * alive right now, at this instant" (its `nowMs`), or `null` for "gone".
 *
 * Alive ⇒ the stamp is refreshed on every minute tick, because the answer the
 * tile owes later is "since WHEN gone", not "when first seen" — a stamp written
 * once at first sight would age into a lie. Gone ⇒ nothing is written and the
 * remembered stamp is returned, so the outage clock keeps running from the last
 * true sighting.
 *
 * Reading goes through `useSyncExternalStore` on the same seam as
 * {@link useHomeTiles} (SSR/test path included — `renderToStaticMarkup` reads
 * the same loader and effects never run there, so a static render can only ever
 * READ the memory, never write it).
 */
export function useHomeTileLastSeen(
  key: HomeTileLastSeenKey,
  presentAtMs: number | null,
): number | null {
  const remembered = useSyncExternalStore(
    subscribeHomeTiles,
    () => loadHomeTileLastSeen(key),
    () => loadHomeTileLastSeen(key),
  );
  useEffect(() => {
    if (presentAtMs === null) return;
    saveHomeTileLastSeen(key, presentAtMs);
    notifyHomeTiles();
  }, [key, presentAtMs]);
  return presentAtMs ?? remembered;
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
//  Theme-GRUPPEN — seit dem .old-Umzug (2026-08-08) aus dem MANIFEST
//
//  Die Gruppierung selbst ist Andis Idee vom 25.07 („Überlege dir ein Konzept,
//  wie man die Auswahl der Themen übersichtlicher machen kann") und bleibt
//  unverändert das, was sie war: eine reine ANZEIGE-Ordnung über denselben
//  persistierten Ids. Neu ist nur, WO sie steht — in
//  `public/themes/manifest.json` statt in einem Array hier. Damit gilt für die
//  Gruppierung dasselbe wie für die Themen: ein Eintrag im Manifest, fertig.
//
//  Seit 21.08. ordnet die TAGESLAGE (Andi: „Sortiere die Designs logisch und
//  gruppiere diese") — s. {@link ThemeGroupId} in styles/themeCatalog.ts:
//    1. 'automatik'   — nur Sora. Keine Farbe, sondern eine REGEL. GANZ OBEN.
//    2. 'morgen'      3. 'tag'      4. 'abend-nacht'  (je hell → dunkel)
//    5. 'stimmung'    — Bilder statt Tageszeiten (hier wohnt Nagori).
//    6. 'klassiker'   — Ruhestand; steht nur da, wenn so ein Thema aktiv ist.
//
//  Die GRUPPEN-TITEL bleiben i18n (`settings.themeGroups`) — sie sind
//  Oberflächen-Text und müssen beim Sprachwechsel mitgehen.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
//  NAGORI (名残) — das versteckte zehnte Theme, ein Vorbote von 0.9
//
//  名残 kommt von 波残り (nami-nokori): das, was die Welle am Strand zurücklässt.
//  Genau das ist das Bild — die Sternschnuppe der 0.x-Linie ist durch, ihre
//  Leuchtspur bleibt stehen. Als Codename der KOMMENDEN Version gehört es noch
//  nicht in die Liste: man findet es (3× schnell auf die Versions-Zeile in der
//  Kopfzeile — die Version ist die Stelle, an der die Zukunft durchscheint),
//  und ab da bleibt es. Kein Zurückschließen, keine Wiederholung des Zaubers.
//
//  Zwei getrennte Wahrheiten, bewusst:
//   • das FLAG ({@link NAGORI_UNLOCK_KEY}) sagt „gefunden" → Nagori steht im
//     Picker (Gruppe „Eigene Stimmung"),
//   • die THEME-WAHL selbst liegt wie jede andere in {@link Settings}.
//  Beides zusammen hält den Zustand ehrlich: wer Nagori aktiv hat, sieht seine
//  Karte auch dann, wenn das Flag verloren ging (kein unsichtbar aktives Thema).
//
//  Warum ein Mini-Bus statt eines Callbacks: der Unlock passiert in der TopNav,
//  der Theme-Zustand wohnt in {@link useSettings} (App). Statt eine Prop-Kette
//  durch die halbe Shell zu ziehen, schreibt {@link unlockNagori} in denselben
//  localStorage-Schlüssel wie {@link saveSettings} und meldet es den laufenden
//  Hook-Instanzen — die laden neu und rendern. EIN Mechanismus, kein zweiter
//  Zustand.
// ─────────────────────────────────────────────────────────────────────────────

/** localStorage-Schlüssel des Fund-Flags (Wert 'true' = entdeckt). */
export const NAGORI_UNLOCK_KEY = 'hoshi.nagoriUnlocked';

/** Wurde Nagori auf diesem Gerät schon gefunden? (Storage blockiert ⇒ nein.) */
export function isNagoriUnlocked(): boolean {
  const store = safeStorage();
  if (!store) return false;
  try {
    return store.getItem(NAGORI_UNLOCK_KEY) === 'true';
  } catch {
    return false;
  }
}

/** Abonnenten, die auf extern geänderte Settings reagieren (s. Kommentar oben). */
const settingsListeners = new Set<() => void>();

/** Meldet allen laufenden {@link useSettings}-Instanzen: bitte neu laden. */
function notifySettingsChanged(): void {
  for (const listener of settingsListeners) listener();
}

/** Abonniert externe Settings-Änderungen; gibt die Abmelde-Funktion zurück. */
export function subscribeSettings(listener: () => void): () => void {
  settingsListeners.add(listener);
  return () => settingsListeners.delete(listener);
}

/**
 * Der Fund: setzt das Flag dauerhaft UND aktiviert Nagori sofort — der Moment
 * soll etwas sein, nicht eine Zeile in einer Liste. Idempotent: ein zweiter
 * Aufruf ändert nichts Sichtbares mehr (das Thema steht dann ohnehin im Picker).
 */
export function unlockNagori(): void {
  const store = safeStorage();
  try {
    store?.setItem(NAGORI_UNLOCK_KEY, 'true');
  } catch {
    /* Storage voll/geblockt — der Fund gilt dann nur für diese Session. */
  }
  saveSettings({ ...loadSettings(), theme: 'nagori' });
  notifySettingsChanged();
}

/**
 * Die Gruppen, wie der Picker sie zeigen soll — die EINE Sichtbarkeits-Regel
 * des Pickers, unverändert in ihrer Bedeutung seit dem Fund-Feature:
 * `hidden`-Themen (heute genau Nagori) stehen erst in der Liste, wenn sie
 * gefunden wurden. `activeTheme` zählt mit: ein aktives Nagori ist immer
 * sichtbar, auch ohne Flag — sonst wäre das laufende Thema nicht ankreuzbar.
 * Seit 21.08. trägt {@link visibleGroups} diese Ausnahme selbst (Parameter
 * `activeId`), weil sie nun für ZWEI Unsichtbarkeits-Gründe gilt: das
 * versteckte Nagori und das zurückgezogene Kasumi (`retired`).
 *
 * Die Mechanik selbst (Reihenfolge, Zugehörigkeit) liegt im Manifest
 * ({@link visibleGroups}); hier wohnt nur, was mit dem Easter-Egg zu tun hat.
 * Ohne geladenes Manifest gibt es ehrlich eine leere Liste — der Picker zeigt
 * dann „lädt …", keine erfundene Auswahl.
 */
export function visibleThemeGroups(
  manifest: ThemeManifest | null,
  activeTheme: Theme,
): readonly VisibleThemeGroup[] {
  return visibleGroups(manifest, isNagoriUnlocked(), activeTheme);
}

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
 *
 * Die erste Überladung hält den Aufruf `useResolvedTheme('sora')` (der Picker
 * zeigt damit, was die Regel GERADE ergibt) auf {@link SoraTheme} genau: dort
 * kann nie ein Stimmungs-Theme und erst recht nicht das versteckte Nagori
 * herauskommen — nur eine der fünf Tageszeiten.
 */
export function useResolvedTheme(theme: 'sora'): SoraTheme;
export function useResolvedTheme(theme: Theme): Theme;
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

  // Von außen geschriebene Settings übernehmen (heute: der Nagori-Fund in der
  // TopNav, s. unlockNagori). Ohne das bliebe der Hook-Zustand stehen und würde
  // die frisch geschriebene Wahl beim nächsten Speichern wieder überschreiben.
  useEffect(() => subscribeSettings(() => setSettings(loadSettings())), []);

  // Nachträglicher Theme-Riegel: {@link loadSettings} läuft synchron beim ersten
  // Render, das Manifest kommt erst danach — eine gespeicherte Id, die es gar
  // nicht (mehr) gibt, käme also durch. Sobald das Manifest da ist, wird genau
  // einmal aufgeräumt: unbekannt ⇒ Default (und der Effekt oben persistiert es).
  // Ein NICHT ladbares Manifest ändert bewusst nichts — lieber die alte Wahl
  // behalten als sie wegen eines Netzfehlers verlieren.
  useEffect(() => {
    let cancelled = false;
    void loadThemeManifest().then((loaded) => {
      if (cancelled || !loaded) return;
      setSettings((s) => (isKnownTheme(s.theme, loaded) ? s : { ...s, theme: DEFAULT_SETTINGS.theme }));
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const setTheme = useCallback((theme: Theme) => setSettings((s) => ({ ...s, theme })), []);
  const setLanguage = useCallback(
    (language: Language) => setSettings((s) => ({ ...s, language })),
    [],
  );
  const setPersona = useCallback((persona: Persona) => setSettings((s) => ({ ...s, persona })), []);
  const setVoice = useCallback((voice: string) => setSettings((s) => ({ ...s, voice })), []);

  return { ...settings, setTheme, setLanguage, setPersona, setVoice };
}
