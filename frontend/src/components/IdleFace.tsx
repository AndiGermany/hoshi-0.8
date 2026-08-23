import { useEffect, useState, type ReactNode } from 'react';
import { dayPartForHour } from './greeting';
import { type HealthState } from '../hooks/useHealth';
import { type OpsVoice } from '../hooks/useOpsStatus';
import {
  clockParts,
  dueClock,
  fmtRemaining,
  runningItemLine,
  scheduledItemPrimary,
  useScheduledItems,
  SCHEDULED_TEXTS,
  type ScheduledItem,
} from '../hooks/useScheduledItems';
import type { DiaryTurn } from '../hooks/useDiary';
import {
  useWeatherToday,
  type DayOutlook,
  type HourlyPoint,
  type WeatherToday,
  type WeatherTodayState,
} from '../hooks/useWeatherToday';
import { useShoppingList } from '../hooks/useShoppingList';
import type { ListItem } from '../api/lists';
import { useHomeRegistry } from '../hooks/useHomeRegistry';
import { useHomeTiles } from '../hooks/useSettings';
import type { HomeRegistryState } from '../api/homeRegistry';
import {
  CURRENT_AFFAIRS_POLL_MS,
  useCurrentAffairs,
  type CurrentAffairsState,
} from '../hooks/useCurrentAffairs';
import { CurrentAffairsTile, renderableCurrentAffairs } from './CurrentAffairsTile';
import { WeatherHourly } from './WeatherHourly';
import { SunArc, type SunTimes } from './SunArc';
import { outlookColumns } from './weatherOutlook';
import { ClimateTile, VacuumTile } from './HomeTileCards';
import { HomeStage, type HomeStageTile } from './HomeStage';
import { homeWidget, type HomeTileSize, type HomeWidgetId } from './homeWidgets';
import { AlarmGlyph } from './icons';
// Die Lagen→Icon-Zuordnung wohnt seit dem 23.08. in einem eigenen Modul, weil
// die Maximieren-Ansicht sie ebenfalls braucht (s. dessen Kopf). Hier steht sie
// unveraendert weiter zur Verfuegung — der Re-Export unten haelt jeden
// bestehenden Import gueltig.
import { WeatherGlyph, weatherCategory, type WeatherCategory } from './weatherGlyph';

import { DECIMAL_SEPARATOR, fmtPrecip } from './weatherFormat';
import { MaximizeButton } from './MaximizeButton';
import { NewsMaxOverlay, WeatherMaxOverlay } from './MaximizeOverlay';

export { weatherCategory, WeatherGlyph, fmtPrecip };
export type { WeatherCategory };
import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import type { IdleFaceStrings, ScheduledStrings } from '../i18n/types';

/**
 * Sprach-Katalog-Default für die exportierten PUR-Funktionen unten (Muster
 * {@link BRAIN_MODEL_TEXTS} in SettingsPanel.tsx): `idleface.test.tsx` ruft
 * `alarmLineText`/`weatherNowContent`/`statusChips` DIREKT mit der alten
 * Signatur auf (kein Strings-Argument) — der Default `de.idleFace` hält dieses
 * Rendering byte-gleich zum bisherigen Stand. Die echte Komponente
 * {@link IdleFace} reicht stattdessen den LIVE-Katalog (`useUiStrings().idleFace`)
 * durch.
 */
const IDLE_FACE_TEXTS = de.idleFace;

/** Höchstens so viele Einkaufs-Einträge sichtbar, Rest hinter „+N weitere" (Andi-Vorgabe „3-4 Einträge" — jetzt die M-Stufe, §3.6). */
export const SHOPPING_VISIBLE_COUNT = 4;

/**
 * **IdleFace** — das Aoi-Idle-/Papier-Gesicht, Andis Flur-Display-Layout
 * (ursprünglich Cowork-Spec 2026-07-02 §2; grundlegend neu geschnitten beim
 * Flur-Display-Umbau, Andi-Auftrag 2026-07-26 — Home lief bei Andi als
 * iPad-Display im Flur und verschenkte Fläche/Informationen). Fünf Elemente:
 *
 *  1. **Kopfzeile**: NUR NOCH die typo-first Uhr + tageszeitbewusster Gruß
 *     ({@link dayPartForHour} + `idleFace.greeting`) + echtes Datum — die
 *     Krone (DESIGN-widget-raster-2026-08-18 §0.3, §4: „Kopf nicht umbauen").
 *     **Das Jetzt-Band (Wetter) verließ den Kopf** (W1, 18.08.): es war seit
 *     dem „Flur wird fertig"-Auftrag (2026-07-27) keine Kachel, lief aber
 *     prominent neben der Uhr — jetzt ist es das ERSTE Bühnen-Widget in `size`
 *     `'L'` (Andi: „default ist die uhr und wetter", §1.1), mit exakt
 *     demselben Inhalt wie zuvor ({@link weatherTileBody}): Zeile 1 dezentes
 *     Lage-Icon ({@link weatherCategory}/{@link WeatherGlyph}) + Jetzt-
 *     Temperatur groß + Jetzt-Lage; Zeile 2 die Tagesspanne; Zeile 3
 *     `precipMm` als warme Zeile „3 mm Regen heute" / „trocken"; danach je
 *     EINZELN nur bei echten Daten: Morgen, die Regen-ab-Uhrzeit
 *     ({@link rainOnsetEpochMs}, nur >20 % Regenwahrscheinlichkeit — kein
 *     Zahlenfriedhof) und eine sehr leise Sonnenauf-/-untergangs-Zeile
 *     ({@link weatherNowContent}). Kleinere Stufen zeigen weniger davon (§3.1)
 *     — S nur Icon+Temperatur, M zusätzlich Lage/Spanne/Regen. BEWUSST KEINE
 *     `StageSparkline` für den Stunden-Verlauf: die existiert für Latenz-
 *     Serien (p50/p95-Referenzlinien, Ausreißer-Dreieck, Fehler-Punkte) — für
 *     12 Temperatur-/Regenwerte ohne Achsen wäre sie aus Flur-Distanz kaum
 *     lesbar (die XL-Stufe bekommt ihren eigenen Stunden-Chart erst in W5).
 *     KEIN Settings-Zahnrad mehr hier (Andi-Korrektur 26.07: das Zahnrad oben
 *     rechts in der Top-Nav reicht — ein zweites an derselben Stelle war
 *     redundant).
 *  2. **Wecker-Zeile**: ⏰ + „Wecker 07:00 · noch X h" + 2px-Fortschritts-
 *     Haarlinie in accent + rechts der Vertrauens-Satz „klingelt auch offline".
 *     Kein Wecker gestellt ⇒ die Zeile sagt das ehrlich.
 *  3. **Die Bühne** (`stageTiles`, §1.1/§5.3): Wetter · „Läuft" (ex-„Geplant",
 *     ECHTE Countdowns mit Labels statt nur einer Zählung, „12:04 Nudeln" ·
 *     „38 min Wäsche", {@link runningItemLine} aus `hooks/useScheduledItems.ts`)
 *     · Einkauf (`GET /api/v1/lists`, Andi-JA 2026-07-08) · Sauger · Klima ·
 *     Lagebild — jede Kachel in ihrer registry-gelesenen Default-Größe
 *     (`components/homeWidgets.ts`), Inhalts-Dichte je Stufe s. `weatherTileBody`/
 *     `laeuftTileBody`/`einkaufTileBody`/`HomeTileCards.tsx`/`CurrentAffairsTile.tsx`.
 *     Läuft/Einkauf VERSCHWINDEN bei leeren Daten (kein Platzhalter — Lärm-
 *     Vermeidung, Muster ScheduledPanel); Sauger/Klima/Lagebild folgen der
 *     Verdien-Regel §1.3.
 *  4. **Statuszeile** — UMGEZOGEN (Andi-Bestellung 19.08.): die stillen
 *     Text-Chips (`● online · ☁ Stimme: Cloud` bzw. `🔒 Stimme: lokal`) sind
 *     die Zuhause-Fußleiste geworden ({@link ./HomeStatusBar.tsx}) und stehen
 *     jetzt UNTER dem Orb am Fensterboden statt als vierte `auto`-Zeile
 *     mitten in dieser Komposition. Die pure Regel {@link statusChips} bleibt
 *     hier wohnen (der Stimme-Chip erscheint NUR, wenn `/api/v1/ops/status`
 *     das voice-Feld ehrlich liefert) — nur das Rendern ist umgezogen.
 *
 *  UMGEZOGEN (Flur-Display-Umbau): die „Heute"-Turn-Statistik-Kachel lebt jetzt
 *  in der „Diagnose"-Sektion am Ende von Aktivität
 *  ({@link ../views/UebersichtView.tsx#DiagnoseSection}) — sie ist Entwickler-
 *  Diagnostik, kein Flur-Inhalt. `IdleFace` braucht darum kein Diary/`turns`
 *  mehr.
 *
 *  KEINE Welle hier (Andi-Feedback 2026-07-06 + Cowork-Korrektur
 *  20260706-1729): auf der Übersicht hört Hoshi nichts — also leuchtet auch
 *  nichts. Die Welle existiert NUR im Chat-Voice-Flow bei offenem Audio-Kanal.
 *
 * {@link IdleFace} ist prop-getrieben (kein Netz) und braucht keine DOM-Umgebung
 * → weiter unit-testbar per `renderToStaticMarkup` (test/idleface.test.tsx).
 * Die exportierten PUR-Helfer (`alarmLineText`, `weatherNowContent`, `fmtPrecip`,
 * `statusChips`) bleiben hook-frei und nehmen den Katalog optional als Parameter
 * (Default `de.idleFace`/`de.locale`), damit `idleface.test.tsx` unverändert
 * byte-gleich grün bleibt. Die Live-Verdrahtung (Ops/Scheduled/Wetter/Einkauf-
 * Hooks + Minuten-Tick) macht {@link IdleFaceLive}.
 */

/* ── pure Helfer (exportiert für Tests) ─────────────────────────────────── */

/**
 * Fenster der Fortschritts-Haarlinie: die letzten 24 h vor dem Wecker.
 * Ohne createdAt im Wire-Format ist das die EHRLICHE Basis, die wir haben —
 * dokumentiert statt erfunden: die Linie füllt sich über den letzten Tag
 * vor dem Klingeln (mehr als 24 h entfernt ⇒ leer).
 */
export const ALARM_PROGRESS_WINDOW_MS = 24 * 60 * 60 * 1000;

/** Nächster (frühester) WECKER — Timer/Erinnerungen zählen hier nicht. */
export function nextAlarm(items: ScheduledItem[]): ScheduledItem | null {
  const alarms = items.filter((i) => i.kind === 'ALARM');
  if (alarms.length === 0) return null;
  return alarms.reduce((a, b) => (b.dueAtEpochMs < a.dueAtEpochMs ? b : a));
}

/** Füllstand 0..1 der Haarlinie: Anteil der letzten 24 h, der schon vergangen ist. */
export function alarmProgress(dueAtEpochMs: number, nowMs: number): number {
  const remaining = dueAtEpochMs - nowMs;
  if (remaining <= 0) return 1;
  return Math.max(0, Math.min(1, 1 - remaining / ALARM_PROGRESS_WINDOW_MS));
}

/**
 * „Wecker 07:00 · noch 7 h 12 min" — Weck-Uhrzeit + Restzeit (nie negativ).
 * Seit dem Langschwanz-Sweep 25.07 folgen auch `dueClock`/`fmtRemaining` der
 * aktiven Sprache (Uhrzeit über `locale`, Einheiten über `scheduled`); ohne
 * Argumente bleibt alles byte-gleich Deutsch.
 */
export function alarmLineText(
  alarm: ScheduledItem,
  nowMs: number,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  s: ScheduledStrings = SCHEDULED_TEXTS,
  locale: string = de.locale,
): string {
  const remaining = fmtRemaining(Math.max(0, alarm.dueAtEpochMs - nowMs), s);
  return t.alarmLine(dueClock(alarm.dueAtEpochMs, locale), remaining);
}

export interface DiaryTodayStats {
  /** Zahl der echten Turns HEUTE (lokaler Kalendertag von nowMs). */
  turns: number;
  /** Median der ttftMs heutiger Turns; null = kein Turn hatte je ein Token. */
  p50Ms: number | null;
  /** Turns mit Fehler-Stage (STT/LLM/SIDECAR/TTS) — die „Aussetzer". */
  errors: number;
}

/**
 * Verdichtet die Diary-Zeilen auf HEUTE (Turns · p50 · Aussetzer). Lebt hier
 * (statt in AktivitaetView.tsx), weil die Formel unverändert aus der früheren
 * „Heute"-Kachel dieser Datei stammt — die „Diagnose"-Sektion in
 * `views/UebersichtView.tsx` importiert sie von hier statt sie zu duplizieren.
 */
export function diaryTodayStats(turns: DiaryTurn[], nowMs: number): DiaryTodayStats {
  const now = new Date(nowMs);
  const today = turns.filter((t) => {
    const d = new Date(t.ts);
    return (
      !Number.isNaN(d.getTime()) &&
      d.getFullYear() === now.getFullYear() &&
      d.getMonth() === now.getMonth() &&
      d.getDate() === now.getDate()
    );
  });
  const ttfts = today
    .flatMap((t) => (t.ttftMs !== null ? [t.ttftMs] : []))
    .sort((a, b) => a - b);
  const n = ttfts.length;
  const p50Ms =
    n === 0 ? null : n % 2 === 1 ? ttfts[(n - 1) / 2] : (ttfts[n / 2 - 1] + ttfts[n / 2]) / 2;
  return { turns: today.length, p50Ms, errors: today.filter((t) => t.error !== null).length };
}

/** ms → Sekunden mit dem Dezimaltrenner der aktiven Sprache: 1800 → „1,8 s" (de) / „1.8 s" (en). */
export function fmtP50(ms: number, locale: string = de.locale): string {
  const sep = DECIMAL_SEPARATOR[locale] ?? '.';
  return `${(ms / 1000).toFixed(1).replace('.', sep)} s`;
}

/**
 * „14 Turns · p50 1,8 s · 0 Aussetzer" — nur echte Diary-Zahlen. „p50" bleibt
 * über alle Sprachen hinweg unübersetzt (Fachbegriff). Genutzt von der
 * „Diagnose"-Sektion (die ex-„Heute"-Kachel, s. Datei-KDoc oben).
 */
export function todayTileValue(
  stats: DiaryTodayStats,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  locale: string = de.locale,
): string {
  const word = stats.turns === 1 ? t.heute.turnOne : t.heute.turnMany;
  const p50 = stats.p50Ms !== null ? fmtP50(stats.p50Ms, locale) : '—';
  return `${stats.turns} ${word} · p50 ${p50} · ${stats.errors} ${t.heute.outageWord}`;
}

/** Der Inhalt des Jetzt-Bands: entweder eine ehrliche Lücke (Text) oder die echten Wetter-Zeilen + Icon-Kategorie. */
export type WeatherNowContent =
  | { kind: 'gap'; text: string }
  | {
      kind: 'live';
      /**
       * Jetzt-Temperatur, z.B. „22°" — `null` NUR wenn `current` beim Backend
       * fehlt/kaputt war (Timeout, altes BE ohne die additiven Felder): die
       * Kopfzeile zeigt dann ehrlich NUR {@link cond} (Fallback: die
       * Tagesbedingung), statt eine erfundene Zahl zu zeigen.
       */
      nowTemp: string | null;
      /** Jetzt-Lage bei vorhandener Jetzt-Temperatur, SONST die Tagesbedingung (ehrlicher Fallback, ex-Hauptzeile vor dieser Erweiterung). */
      cond: string;
      category: WeatherCategory;
      /** Tagesspanne („18–29°") — jetzt die ZWEITE Zeile (Flur-Auftrag 2026-07-27), vorher die Hauptzeile. */
      span: string;
      precip: string;
      /** „morgen 12–22°, sonnig" — `null` wenn Min/Max/Bedingung nicht ALLE drei da sind. */
      tomorrow: string | null;
      /** „Regen ab ~17:00" — `null` ohne Stunden-Daten ODER wenn keine Stunde >20% Regenwahrscheinlichkeit hat. */
      rainFrom: string | null;
      /** „hell bis 21:34"/„hell ab 05:32" — `null` ohne Sonnenauf-/-untergangsdaten. */
      sun: string | null;
      /**
       * Der kompakte Stunden-Verlauf, ROH durchgereicht (W5, §3.1): bis zu
       * diesem Schnitt wurde `hourly` nur für die Regen-ab-Uhrzeit ausgewertet
       * und danach verworfen. Die XL-Stufe zeichnet daraus ihr Bild
       * ({@link ./WeatherHourly}). **Leeres Array**, wenn das BE keine Stunden
       * liefert — dann rendert die Kurve nichts, statt eine zu erfinden.
       */
      hourly: HourlyPoint[];
      /**
       * Der Mehrtage-Ausblick, ROH durchgereicht wie {@link hourly} (Andi
       * 21.08.; Wire-Feld `outlook`, seit 21.08. additiv im BE). Die XL-Stufe
       * beschriftet daraus ihre Tages-Zeile ({@link ./weatherOutlook}).
       * **Leeres Array** bei einem Backend, das das Feld noch nicht kennt —
       * dann bleibt die Zeile weg, statt Tage zu rechnen.
       */
      outlook: DayOutlook[];
    };

/**
 * **Regen-ab-Logik** (Flur-Fertigstellung 2026-07-27): die ERSTE Stunde im
 * kompakten `hourly`-Fenster mit einer Regenwahrscheinlichkeit über
 * [thresholdPercent] (Default 20 — Andi-Vorgabe „kein Zahlenfriedhof": eine
 * 12%-Chance verdient keine Zeile). Kein Treffer ODER keine `hourly`-Daten ⇒
 * `null`, die Zeile verschwindet dann ehrlich statt „0% Regen" zu behaupten.
 */
export function rainOnsetEpochMs(hourly: HourlyPoint[] | undefined, thresholdPercent = 20): number | null {
  const hit = (hourly ?? []).find((h) => h.precipProbability > thresholdPercent);
  return hit ? hit.epochMs : null;
}

/**
 * Leitet den Inhalt des Jetzt-Bands aus dem ehrlichen Wetter-Endpoint-Zustand
 * ab (ex-`weatherTile`, jetzt kein `.tile` mehr):
 *  - `null` (erster Fetch läuft) / `off` (404, Wetter beim Deploy aus) /
 *    `unreachable` (401/5xx/Netz) ⇒ EINE ehrliche Lücken-Zeile — exakt dieselben
 *    Texte wie die frühere gestrichelte Kachel, nur ohne Kachel-Rahmen.
 *  - `live` ⇒ Jetzt-Temperatur + Jetzt-Lage (Flur-Auftrag 2026-07-27: der
 *    `current`-Node wurde schon immer angefragt, aber bis heute verworfen),
 *    Icon-Kategorie ({@link weatherCategory}), Tagesspanne („18–29°", jetzt
 *    Zeile 2), die warme Niederschlags-Zeile (`precipMm > 0` ⇒ „3 mm Regen
 *    heute" via {@link fmtPrecip}, sonst „trocken"), plus DREI neue optionale
 *    Zeilen — Morgen, Regen-ab-Uhrzeit ({@link rainOnsetEpochMs}) und
 *    Sonnenauf-/-untergang (relativ zu [nowMs]) —, die JEDE EINZELN nur
 *    erscheinen, wenn ihre Rohdaten da sind (additive BE-Felder, s.
 *    `hooks/useWeatherToday.ts`). `cond`/`tomorrow`-Bedingungstexte kommen
 *    bereits in der Anzeigesprache vom Backend — hier wird nichts übersetzt,
 *    nur die Icon-Zuordnung liest den Text.
 */
export function weatherNowContent(
  weather: WeatherTodayState | null,
  nowMs: number,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  locale: string = de.locale,
): WeatherNowContent {
  if (weather === null) return { kind: 'gap', text: t.wetter.loadingNote };
  if (weather.kind === 'off') return { kind: 'gap', text: t.wetter.offNote };
  if (weather.kind === 'unreachable') return { kind: 'gap', text: t.wetter.unreachableNote };
  const w: WeatherToday = weather.data;
  const precip = w.precipMm > 0 ? t.wetter.precipSome(fmtPrecip(w.precipMm, locale)) : t.wetter.precipNone;

  const nowTemp = w.nowTemp !== undefined ? `${w.nowTemp}°` : null;
  const cond = w.nowCodeText !== undefined ? w.nowCodeText : w.codeText;

  const tomorrow =
    w.tomorrowMin !== undefined && w.tomorrowMax !== undefined && w.tomorrowCodeText !== undefined
      ? t.wetter.tomorrow(`${w.tomorrowMin}–${w.tomorrowMax}°`, w.tomorrowCodeText)
      : null;

  const rainAt = rainOnsetEpochMs(w.hourly);
  const rainFrom = rainAt === null ? null : t.wetter.rainFrom(dueClock(rainAt, locale));

  const sun =
    w.sunriseEpochMs === undefined || w.sunsetEpochMs === undefined
      ? null
      : nowMs < w.sunriseEpochMs
        ? t.wetter.sunFrom(dueClock(w.sunriseEpochMs, locale))
        : t.wetter.sunUntil(dueClock(w.sunsetEpochMs, locale));

  return {
    kind: 'live',
    nowTemp,
    cond,
    category: weatherCategory(cond),
    span: `${w.todayMin}–${w.todayMax}°`,
    precip,
    tomorrow,
    rainFrom,
    sun,
    hourly: w.hourly ?? [],
    outlook: w.outlook ?? [],
  };
}

/**
 * Die Sonnenzeiten des Tages für den **Uhr**-Bogen ({@link ./SunArc}) — aus
 * demselben Wetter-Zustand, den die Wetter-Kachel liest.
 *
 * `null`, sobald einer der beiden Werte fehlt (Alt-Backend, Open-Meteo ohne
 * `daily.sunrise/sunset`, `off`/`unreachable`, erster Fetch): der Bogen
 * erscheint dann gar nicht (Verdien-Regel). Ein Bogen mit nur EINEM Fuß wäre
 * eine halbe Behauptung — beide Enden gehören zusammen.
 *
 * **Bewusst NICHT an den Wetter-Schalter gebunden:** der Bogen ist eine Aussage
 * über die Tageszeit, nicht über das Wetter, und er lebt in der Uhr-Kachel. Wer
 * die Wetter-Kachel abschaltet, schaltet keine Sonne ab. Der Endpoint wird von
 * `IdleFaceLive` ohnehin gepollt — kein zusätzlicher Fetch.
 */
export function sunTimesOf(weather: WeatherTodayState | null): SunTimes | null {
  if (weather === null || weather.kind !== 'live') return null;
  const { sunriseEpochMs, sunsetEpochMs } = weather.data;
  if (sunriseEpochMs === undefined || sunsetEpochMs === undefined) return null;
  return { sunriseEpochMs, sunsetEpochMs };
}

/**
 * The body of the Wetter stage tile, at a given {@link HomeTileSize} (DESIGN-
 * widget-raster-2026-08-18 §3.1 — Andi's core example, "default ist die uhr
 * und wetter"). A gap state (loading/off/unreachable) is size-independent —
 * the honest note is the same whether the tile is small or large. Otherwise:
 * S is icon + Jetzt-Temperatur only, M adds the condition/span/precip lines,
 * L adds tomorrow/rain-onset/sun — **exactly today's former header band**.
 *
 * **XL (W5) ist die Belohnung** (§3.1): dieselben echten Zeilen wie L, aber
 * NEBENEINANDER in einer Fakten-Zeile über die ganze Bühnenbreite — und
 * darunter der Stunden-Verlauf ({@link ./WeatherHourly}) aus `hourly`, dem
 * einzigen Feld, das die Kachel bis heute geholt und wieder weggeworfen hat.
 * Kein neues Feld, keine erfundene Zeile: fehlt `hourly`, steht dort nichts
 * (§2.3 „L/XL erfindet niemals Inhalt"). Die waagerechte Fakten-Zeile ist
 * derselbe Gedanke wie bei M (Andi 19.08.: der freie Platz einer breiten
 * Kachel liegt RECHTS, nicht unten) — nur nutzt XL ihn, um dem Bild die
 * ganze Höhe zu lassen.
 *
 * Classes are unchanged from the former header markup on purpose:
 * `onewindow.test.ts` pins `.idle__now` and the density tests in
 * `idleface.test.tsx` pin the line classes.
 */
export function weatherTileBody(
  now: WeatherNowContent,
  size: HomeTileSize,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  locale: string = de.locale,
): ReactNode {
  if (now.kind === 'gap') return <span className="idle__nowgap">{now.text}</span>;
  const cs = size;
  // Die Mehrtage-Zeile wird NUR für XL gerechnet — S/M/L zeigen sie nicht, und
  // eine Ableitung für ein Bild, das niemand rendert, ist verschwendete Arbeit
  // auf einem Flur-iPad, das den Rumpf bei jedem Minuten-Tick neu baut.
  const outlook = cs === 'XL' ? outlookColumns(now.outlook, locale, t.wetter.outlook) : [];
  return (
    <>
      <span className="idle__nowcond">
        <WeatherGlyph category={now.category} className="idle__nowicon" />
        {now.nowTemp && <span className="idle__nowtemp">{now.nowTemp}</span>}
        {cs !== 'S' && (
          <span className={now.nowTemp ? 'idle__nowcondtext' : undefined}>{now.cond}</span>
        )}
      </span>
      {/* M steht NEBEN der Lage statt darunter (Andi 19.08.: „wenn ich die
          mittlere Größe auswähle, habe ich echt viel Platz"). Gemessen war der
          Platz nämlich BREIT, nicht hoch: die M-Kachel ist 2×1 — 583 px breit,
          aber je nach Zeilenzahl der Bühne nur 134 px hoch. Die freie Fläche
          lag rechts vom Text, und dort füllt sie jetzt eine eigene Spalte mit
          den drei kurzen Fakten (Tagesspanne · Niederschlag · Morgen). Das ist
          zugleich die Reparatur eines Fehlversuchs: dieselben Zeilen
          UNTEREINANDER passen in eine kurze M-Kachel schlicht nicht (der
          Niederschlag brach ab).
          Die Morgen-Zeile ist die EINZIGE, die hier dazukommt: §3.1 gibt L
          drei Zusatzzeilen (Morgen, Regen-ab, Sonne), und „M ist kurze Liste"
          — Regen-ab und Sonne bleiben das, was L von M unterscheidet. Wie
          überall nur mit echten Rohdaten: `now.tomorrow` ist null, sobald
          eines der drei Morgen-Felder fehlt, dann bleibt die Spalte zweizeilig
          statt eine Zeile zu erfinden. */}
      {cs === 'M' && (
        <span className="idle__nowfacts">
          <span className="idle__nowspan">{now.span}</span>
          <span className="idle__nowprecip">{now.precip}</span>
          {now.tomorrow && <span className="idle__nowline">{now.tomorrow}</span>}
        </span>
      )}
      {cs === 'L' && (
        <>
          <span className="idle__nowspan">{now.span}</span>
          <span className="idle__nowprecip">{now.precip}</span>
          {now.tomorrow && <span className="idle__nowline">{now.tomorrow}</span>}
          {now.rainFrom && <span className="idle__nowline">{now.rainFrom}</span>}
          {now.sun && <span className="idle__nowline idle__nowline--sun">{now.sun}</span>}
        </>
      )}
      {cs === 'XL' && (
        <>
          {/* Dieselben fünf L-Zeilen, waagerecht statt untereinander — jede
              weiterhin EINZELN an ihr echtes Rohdatum gebunden (`tomorrow`/
              `rainFrom`/`sun` sind null, sobald ihre Felder fehlen). */}
          <span className="idle__nowfacts idle__nowfacts--row">
            <span className="idle__nowspan">{now.span}</span>
            <span className="idle__nowprecip">{now.precip}</span>
            {now.tomorrow && <span className="idle__nowline">{now.tomorrow}</span>}
            {now.rainFrom && <span className="idle__nowline">{now.rainFrom}</span>}
            {now.sun && <span className="idle__nowline idle__nowline--sun">{now.sun}</span>}
          </span>
          <WeatherHourly points={now.hourly} />
          {/* Die Mehrtage-Zeile (Andi 21.08.) — echte Tage aus dem Wire-Feld
              `outlook`, sonst gar nichts. Sie steht UNTER der Stundenkurve,
              weil sie das gröbere Bild ist: erst die nächsten Stunden, dann
              die nächsten Tage. Ein Alt-Backend ohne das Feld liefert eine
              leere Liste ⇒ die XL-Kachel sieht aus wie vor dieser Erweiterung. */}
          {outlook.length > 0 && (
            <div
              className="idle__outlook"
              role="list"
              aria-label={t.wetter.outlook.aria(outlook.length)}
            >
              {outlook.map((day) => (
                <div
                  key={day.key}
                  className="idle__outlookday"
                  role="listitem"
                  data-today={day.today ? 'true' : undefined}
                  title={day.title}
                >
                  <span className="idle__outlookwd">{day.weekday}</span>
                  <WeatherGlyph
                    category={weatherCategory(day.codeText)}
                    className="idle__outlookicon"
                  />
                  <span className="idle__outlookspan">{day.span}</span>
                  <span className="idle__outlookcond">{day.codeText}</span>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </>
  );
}

export interface StatusChip {
  text: string;
  tone: 'ok' | 'down' | 'unknown' | 'cloud' | 'local';
}

/**
 * Die stillen Text-Chips: Health immer (ehrlich: auch „offline"/„wird geprüft"),
 * der Stimme-Chip NUR wenn das BE das voice-Feld liefert — voice:null heißt
 * „wir wissen es nicht" und bleibt darum unsichtbar statt behauptet.
 * Das Glyph leitet die Ansicht aus `tone` ab ({@link chipGlyph}): Health-Töne
 * tragen den typografischen ●-Punkt (CSS-gefärbt), cloud/local ein muted
 * SVG-Glyph (Wolke/Schloss) — Emoji-Sweep 2026-07-06.
 */
export function statusChips(
  health: HealthState,
  voice: OpsVoice | null,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
): StatusChip[] {
  const chips: StatusChip[] = [];
  if (health === 'up') chips.push({ text: t.status.online, tone: 'ok' });
  else if (health === 'down') chips.push({ text: t.status.offline, tone: 'down' });
  else chips.push({ text: t.status.checking, tone: 'unknown' });
  if (voice) {
    chips.push(
      voice.cloud
        ? { text: t.status.voiceCloud, tone: 'cloud' }
        : { text: t.status.voiceLocal, tone: 'local' },
    );
  }
  return chips;
}

/* Das Ton→Glyph-Mapping (`chipGlyph`) zog mit den Chips um — es lebt jetzt in
   `components/HomeStatusBar.tsx`, der einzigen Stelle, die es noch rendert. */

/** M-Stufe der „Läuft"-Kachel (§3.5, „3 Countdowns" — exakte Andi-Zahl). */
export const LAEUFT_TILE_M_VISIBLE = 3;
/**
 * L-Stufe der „Läuft"-Kachel: §3.5 nennt nur „alle, die in die Fläche
 * passen" ohne Zahl — 6 folgt demselben M→L-Verdopplungsmuster wie Lagebild
 * (3→6) und Einkauf (4→8); ungemessen, Rate-Stelle (s. RESULT.md).
 */
export const LAEUFT_TILE_L_VISIBLE = 6;

/**
 * Der Inhalt der „Läuft"-Stage-Kachel, nach {@link HomeTileSize} (§3.5): S
 * zeigt NUR den nächsten Countdown im Verwaltungs-Ton ({@link scheduledItemPrimary},
 * „um"/„noch"), M/L die Flur-knappe Liste ({@link runningItemLine}) mit
 * wachsender Zeilenzahl + „+N weitere".
 *
 * **XL (W5, §3.5) zeigt ALLE, zweispaltig** — als einzige der vier XL-Stufen
 * ohne Deckel, weil §3.5 ihn wörtlich nicht kennt („alle zweispaltig"). Das
 * ist hier auch ehrlich: ein Countdown ist per Definition endlich und
 * verschwindet von selbst; eine Kachel, die vier Timer verschweigt, wäre
 * schlimmer als eine lange. Läuft die Liste doch über, scrollt sie IM Rahmen
 * (`.idle__tile { overflow-y: auto }`) — die Seite selbst nie. Ohne Deckel
 * gibt es folgerichtig auch keine „+N"-Zeile: es wird nichts verschwiegen.
 *
 * Leere `items` ⇒ `null` (die Kachel wird nur gebaut, wenn
 * `scheduled.length > 0`, dies ist nur die letzte Verteidigung für
 * Direktaufrufe in Tests).
 */
export function laeuftTileBody(
  items: ScheduledItem[],
  nowMs: number,
  size: HomeTileSize,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  s: ScheduledStrings = SCHEDULED_TEXTS,
  locale: string = de.locale,
): ReactNode {
  if (items.length === 0) return null;
  const cs = size;
  if (cs === 'S') {
    return <p className="idle__hometileline">{scheduledItemPrimary(items[0], nowMs, s, locale)}</p>;
  }
  const visible =
    cs === 'M' ? LAEUFT_TILE_M_VISIBLE : cs === 'XL' ? items.length : LAEUFT_TILE_L_VISIBLE;
  const shown = items.slice(0, visible);
  const rest = items.length - shown.length;
  return (
    <>
      <ul className={`idle__cardlist${cs === 'XL' ? ' idle__cardlist--two' : ''}`}>
        {shown.map((item) => (
          <li key={item.id}>{runningItemLine(item, nowMs, s, locale)}</li>
        ))}
      </ul>
      {rest > 0 && <p className="idle__cardmore">{t.einkauf.more(rest)}</p>}
    </>
  );
}

/**
 * L-Stufe der Einkaufs-Kachel (§3.6, exakte Andi-Zahl „8"). M bleibt
 * {@link SHOPPING_VISIBLE_COUNT} (unverändert 4 — das war schon immer der
 * heutige Default).
 */
export const SHOPPING_TILE_L_VISIBLE = 8;

/**
 * XL-Stufe der Einkaufs-Kachel (§3.6). Wie überall ein DECKEL, keine Vorgabe:
 * fünf Einträge bleiben fünf Einträge.
 *
 * **16 statt der Doc-Zahl 12 (Selbstabnahme W5, gemessen).** Mit 15 echten
 * Einträgen zeigte das Bild 12 und schrieb „+3 weitere" — unter einer Kachel,
 * deren untere **45 % leer** blieben. Ein Deckel, der Vorhandenes wegzählt,
 * obwohl die Fläche frei ist, ist kein Schutz, sondern ein Verlust. 16 = 8
 * Zeilen je Spalte. Doc-Zahl 12 steht so in §3.6 — **Rate-Stelle für Andi**.
 */
export const SHOPPING_TILE_XL_VISIBLE = 16;

/**
 * Der Inhalt der Einkaufs-Stage-Kachel, nach {@link HomeTileSize} (§3.6). S
 * zeigt NUR die Zahl („7 Einträge") — keine Liste (§2.3-Regel „S ist nie eine
 * Liste"). [entriesText] leiht bewusst `settings.privacyStoreEntries` (VERBOTEN:
 * i18n-Dateien anfassen — es gibt keinen `einkauf`-eigenen Schlüssel für „N
 * Einträge", s. Rate-Stellen in RESULT.md). M/L zeigen die vertraute Liste +
 * „+N weitere". **XL (W5, §3.6)** zeigt bis zu
 * {@link SHOPPING_TILE_XL_VISIBLE} Einträge zweispaltig — der Rest bleibt
 * gezählt statt zu verschwinden.
 */
export function einkaufTileBody(
  shopping: ListItem[],
  size: HomeTileSize,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  entriesText: (n: number) => string = de.settings.privacyStoreEntries,
): ReactNode {
  const cs = size;
  if (cs === 'S') {
    return <p className="idle__hometileline">{entriesText(shopping.length)}</p>;
  }
  const visible =
    cs === 'M'
      ? SHOPPING_VISIBLE_COUNT
      : cs === 'XL'
        ? SHOPPING_TILE_XL_VISIBLE
        : SHOPPING_TILE_L_VISIBLE;
  const shown = shopping.slice(0, visible);
  const rest = shopping.length - shown.length;
  return (
    <>
      <ul className={`idle__cardlist${cs === 'XL' ? ' idle__cardlist--two' : ''}`}>
        {shown.map((it) => (
          <li key={it.id}>
            {it.quantity > 1 && <span className="idle__cardqty">{it.quantity}×</span>} {it.text}
          </li>
        ))}
      </ul>
      {rest > 0 && <p className="idle__cardmore">{t.einkauf.more(rest)}</p>}
    </>
  );
}

/**
 * **Die Uhr als Kachel** (W4, Andi 19.08.: „Die Uhr soll auch verschiebbar und
 * in der Größe einstellbar werden"). Sie kennt genau drei Felder, weil es genau
 * drei gibt — `clockParts` liefert Stunden:Minuten (+ AM/PM), das Datum kommt
 * aus `toLocaleDateString`, der Gruß aus `dayPartForHour`. **Keine Sekunden**
 * (§3.7): `clockParts` liefert sie nicht, sie wurden nie gezeigt, und ein
 * Sekunden-Tick kostet auf einem dauerhaft laufenden Flur-iPad 60× mehr
 * Renders für einen Wert, den aus 3 m niemand liest.
 *
 * | Stufe | Inhalt |
 * |---|---|
 * | **S** (1×1) | nur die Zeit |
 * | **M** (2×1) | + Datum, kurz („Di., 19. Aug.") — eine Zeile, mehr trägt 2×1 nicht |
 * | **L** (2×2) | Zeit GROSS (die alte Kronen-Größe) + Datum lang + **Sonnenbogen** |
 *
 * **Der Gruß steht NICHT hier drin**, obwohl die Stufen-Tabelle ihn bei L
 * nennt: er ist laut derselben Bestellung „fester Kopf" und steht permanent
 * eine Zeile über der Bühne. Zweimal derselbe Satz auf einem Bildschirm wäre
 * kein „mehr Inhalt", sondern ein Fehler — s. RESULT.md, Eigen-Entscheidung 1.
 *
 * **Der Sonnenbogen auf L** (Andi 21.08.: „wenn man die Größe ändert, dass man
 * den Sonnenverlauf anzeigt") ist die erste Ausnahme vom W4-Satz „ihre Felder
 * sind bei L erschöpft" — und zwar die einzige zulässige Art von Ausnahme: er
 * erfindet nichts, sondern liest ein Feld, das schon über die Leitung kommt
 * (`sunriseEpochMs`/`sunsetEpochMs`, s. {@link sunTimesOf}). Fehlt es, erscheint
 * er nicht, und die L-Uhr ist byte-gleich zu vorher. **Kein XL** bleibt richtig:
 * mehr Fläche gäbe dem Bogen nur mehr Luft, keinen weiteren Inhalt.
 */
export function clockTileBody(
  nowMs: number,
  size: HomeTileSize,
  locale: string,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  sun: SunTimes | null = null,
): ReactNode {
  const step = size === 'XL' ? 'L' : size;
  const date = new Date(nowMs);
  const clock = clockParts(nowMs, locale);
  const dateText =
    step === 'M'
      ? date.toLocaleDateString(locale, { weekday: 'short', day: 'numeric', month: 'short' })
      : date.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long' });
  return (
    <div className="idle__clocktile" data-step={step}>
      <time className="idle__clock" dateTime={date.toISOString()} aria-label={t.uhr.name}>
        {clock.time}
        {clock.period && <span className="idle__clockperiod">{clock.period}</span>}
      </time>
      {step !== 'S' && <p className="idle__clockdate">{dateText}</p>}
      {step === 'L' && <SunArc nowMs={nowMs} sun={sun} strings={t.uhr.sun} locale={locale} />}
    </div>
  );
}

/**
 * **Der Wecker als Kachel** (W6, Andi 20.08.). Er war der letzte Bewohner der
 * Krone, und er stand dort teuer: eine eigene `auto`-Zeile im `.idle`-Gerüst
 * plus die zwei Grid-Lücken darum — **75 px zwischen Kopf und Bühne**, für
 * 35 px Inhalt, die kein Widget je benutzen durfte. Auf der Bühne trägt er
 * dieselbe Fläche wie jede andere Kachel und lässt sich verschieben,
 * abschalten, in der Stufe wählen.
 *
 * | Stufe | Inhalt |
 * |---|---|
 * | **S** (1×1) | nur die Zeile: „Wecker 07:00 · noch 22 h 2 min" |
 * | **M** (2×1) | + Fortschritts-Haarlinie + Vertrauens-Satz |
 *
 * **Kein L/XL** — s. `ALARM_SIZES` in `homeWidgets.ts`. Der Grund steht in den
 * Daten, nicht im Geschmack: `ScheduledItem` liefert `dueAtEpochMs` und sonst
 * nichts Lesbares; die Haarlinie rechnet der Client aus den letzten 24 h
 * (`alarmProgress`), und der Vertrauens-Satz ist ein fester Satz je Sprache.
 * Es gibt **kein `confidence`-Feld**, das eine dritte Stufe füllen könnte.
 *
 * `alarm === null` ⇒ die ehrliche Leerzeile „Kein Wecker gestellt", ohne
 * Haarlinie und ohne Vertrauens-Satz: eine Linie über nichts und ein
 * Versprechen über einen Wecker, den es nicht gibt, wären beides Behauptungen.
 * Dass die Kachel bei ausgeschaltetem Schalter GAR NICHT rendert, entscheidet
 * die Bühnen-Liste weiter unten — nicht diese Funktion.
 */
export function alarmTileBody(
  alarm: ScheduledItem | null,
  nowMs: number,
  size: HomeTileSize,
  locale: string = de.locale,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  s: ScheduledStrings = SCHEDULED_TEXTS,
): ReactNode {
  // L/XL kann der Wecker nicht — aber `HomeStage` löst die EFFEKTIVE Stufe aus
  // der echten Fläche auf, und ein sehr breites Fenster kann eine größere
  // liefern als die Registry erlaubt. Dann gilt die reichste Stufe, die es
  // gibt (M), statt eines Zustands, den diese Funktion nicht kennt.
  const step = size === 'S' ? 'S' : 'M';
  return (
    <div className="idle__alarmtile" data-step={step}>
      {alarm ? (
        <div className="idle__alarm" data-alarm="set">
          <span className="idle__alarmicon" aria-hidden="true">
            <AlarmGlyph />
          </span>
          <span className="idle__alarmtext">{alarmLineText(alarm, nowMs, t, s, locale)}</span>
          {step === 'M' && (
            <>
              <span className="idle__alarmtruth" title={t.alarmTrustTitle}>
                {t.alarmTrustText}
              </span>
              <span className="idle__alarmtrack" aria-hidden="true">
                <span
                  className="idle__alarmfill"
                  style={{ transform: `scaleX(${alarmProgress(alarm.dueAtEpochMs, nowMs)})` }}
                />
              </span>
            </>
          )}
        </div>
      ) : (
        // Ehrlich: kein Wecker ⇒ keine Haarlinie, kein Vertrauens-Satz über nichts.
        <div className="idle__alarm idle__alarm--none" data-alarm="none">
          <span className="idle__alarmicon" aria-hidden="true">
            <AlarmGlyph />
          </span>
          <span className="idle__alarmtext">{t.noAlarm}</span>
        </div>
      )}
    </div>
  );
}

/* ── die Ansicht (rein prop-getrieben) ──────────────────────────────────── */

export interface IdleFaceProps {
  /** Jetzt-Zeitpunkt (epoch ms) — von außen, damit die Ansicht pur/testbar bleibt. */
  nowMs: number;
  /* KEIN `health`/`voice` mehr (19.08.): die beiden speisten AUSSCHLIESSLICH die
     Status-Chips, und die sind zur Fußleiste geworden
     ({@link ./HomeStatusBar.tsx}). Sie hier als tote Props stehen zu lassen
     wäre eine Behauptung über diese Ansicht, die nicht mehr stimmt. */
  /** Aktive Items aus GET /api/v1/scheduled (Wecker-Zeile + „Läuft"-Karte). */
  scheduled: ScheduledItem[];
  /**
   * Heutiges Wetter aus GET /api/v1/weather/today — null = erster Fetch läuft;
   * `off`/`unreachable` sind EHRLICHE Zustände (siehe {@link weatherNowContent}).
   */
  weather: WeatherTodayState | null;
  /** Einkaufsliste aus GET /api/v1/lists — leer = Karte weg (kein Platzhalter). */
  shopping: ListItem[];
  /**
   * Registry-Snapshot fürs Sauger-/Klima-Kachelpaar (Andi-Auftrag 2026-08-11:
   * „Zuhause-Kacheln, die man sich verdient") — optional, Default `null`
   * (keine Kacheln, altes Verhalten). `null` = Fetch läuft/nicht verdrahtet;
   * `off`/`unreachable` sind ehrliche Zustände (s. `components/HomeTileCards.tsx`).
   */
  homeRegistry?: HomeRegistryState | null;
  /** Zeigt die Sauger-Kachel — nur wahr, wenn Andi sie in den Einstellungen aktiviert hat (Default aus). */
  vacuumTileEnabled?: boolean;
  /** Zeigt die Klima-Kachel — nur wahr, wenn Andi sie in den Einstellungen aktiviert hat (Default aus). */
  climateTileEnabled?: boolean;
  /**
   * W2 seam, closed by W1: display switches of the three new Bühne widgets
   * (`hoshi.homeTiles.wetter`/`.laeuft`/`.einkauf`, `hooks/useSettings.ts`),
   * default ON (they were unconditional before the switch existed, §1.2 of
   * the design doc — a new switch must not silently hide them).
   */
  wetterTileEnabled?: boolean;
  /** Siehe {@link wetterTileEnabled} — Schalter der „Läuft"-Kachel, Default AN. */
  laeuftTileEnabled?: boolean;
  /** Siehe {@link wetterTileEnabled} — Schalter der Einkaufs-Kachel, Default AN. */
  einkaufTileEnabled?: boolean;
  /**
   * "Lagebild" window (order F5, wave 1) — optional, default `null` (no
   * window, previous behaviour). It shows itself exactly when the endpoint has
   * real headlines (`FRESH`/`STALE`) and renders nothing otherwise — see
   * `components/CurrentAffairsTile.tsx`.
   */
  currentAffairs?: CurrentAffairsState | null;
  /**
   * Display switch of the "Lagebild" window (settings → "Zuhause-Kacheln"),
   * default ON — unlike the vacuum/climate flags, whose tiles stay dark until
   * they are switched on. `false` ⇒ neither the window nor its "mehr"
   * expansion ever renders, no matter how fresh [currentAffairs] is; the live
   * wiring below then also stops polling the endpoint. The switch governs the
   * EYES only: the voice path never reads it.
   */
  currentAffairsTileEnabled?: boolean;
  /**
   * Anzeige-Schalter der Uhr (`hoshi.homeTiles.uhr`, W2) — seit W4 ist die Uhr
   * ein **Bühnen-Widget**, der Schalter wirkt also auf eine Kachel wie bei den
   * anderen sechs. Aus ⇒ keine Uhr-Kachel; die Kopfzeile trägt dann Gruß UND
   * Datum, damit das Datum nicht mit der Kachel verschwindet.
   */
  uhrTileEnabled?: boolean;
  /**
   * Anzeige-Schalter des Weckers (`hoshi.homeTiles.wecker`, W2). Der Wecker
   * bleibt die **Krone** — nicht verschiebbar, keine Stufen, nur an/aus. Aus ⇒
   * die Wecker-Zeile rendert gar nicht (auch nicht als „kein Wecker gestellt");
   * ein Schalter, der nur die halbe Zeile nimmt, wäre keiner.
   */
  weckerTileEnabled?: boolean;
}

export function IdleFace({
  nowMs,
  scheduled,
  weather,
  shopping,
  homeRegistry = null,
  vacuumTileEnabled = false,
  climateTileEnabled = false,
  wetterTileEnabled = true,
  laeuftTileEnabled = true,
  einkaufTileEnabled = true,
  currentAffairs = null,
  currentAffairsTileEnabled = true,
  uhrTileEnabled = true,
  weckerTileEnabled = true,
}: IdleFaceProps) {
  const { idleFace, locale, scheduled: schedText } = useUiStrings();
  /**
   * **Welche Kachel gerade maximiert ist** (Andi 23.08.) — genau EINE oder
   * keine. Ein zweiter offener Kasten wäre zwei Modale übereinander, und
   * `Overlay` beansprucht Escape exklusiv, solange es offen ist.
   *
   * Der Zustand wohnt HIER und nicht in den Kacheln: eine `.idle__tile` ist ein
   * Größen-Container (`container-type: size`) mit `overflow: auto`, und im
   * Edit-Modus trägt sie beim Ziehen ein `transform` — jedes davon macht sie zum
   * enthaltenden Block für `position: fixed`. Ein modaler Kasten darin wäre in
   * seiner eigenen Kachel gefangen. Als Geschwister der Bühne ist er frei.
   */
  const [maximized, setMaximized] = useState<'news' | 'wetter' | null>(null);
  const date = new Date(nowMs);
  const greeting = idleFace.greeting(dayPartForHour(date.getHours()));
  const dateLine = date.toLocaleDateString(locale, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  const alarm = nextAlarm(scheduled);
  const now = weatherNowContent(weather, nowMs, idleFace, locale);
  // Die Uhr-Kachel liest die Sonnenzeiten aus DERSELBEN Antwort wie die
  // Wetter-Kachel (kein zweiter Fetch, kein zweiter Poller) — s. `sunTimesOf`.
  const sunTimes = sunTimesOf(weather);

  // W1/W2/W4 seam CLOSED: der Anzeige-Schalter je Widget, generisch — alle
  // acht lesen jetzt ihren echten `hoshi.homeTiles.*`-Schlüssel (W2,
  // `hooks/useSettings.ts`), durchgereicht von `IdleFaceLive` unten. Die
  // beiden Kronen-Schalter waren bis W3 wirkungslos verdrahtet (`true` fest im
  // Code); seit die Uhr ein Bühnen-Widget ist (Andi 19.08.) und die Kopf-
  // Mechanik nachgezogen wurde, wirken beide — ein Schalter ohne Wirkung ist
  // verboten.
  const enabled: Record<HomeWidgetId, boolean> = {
    uhr: uhrTileEnabled,
    wecker: weckerTileEnabled,
    wetter: wetterTileEnabled,
    laeuft: laeuftTileEnabled,
    einkauf: einkaufTileEnabled,
    vacuum: vacuumTileEnabled,
    climate: climateTileEnabled,
    news: currentAffairsTileEnabled,
  };
  const stageSize = (id: HomeWidgetId): HomeTileSize => homeWidget(id).defaultSize ?? 'M';

  // Das Lagebild-Fenster zählt nur mit, wenn es wirklich etwas zu zeigen hat —
  // dieselbe Frage, die die Kachel selbst stellt (renderableCurrentAffairs),
  // damit der Kachel-Block nicht wegen eines unsichtbaren Fensters aufgeht.
  const hasCurrentAffairs = enabled.news && renderableCurrentAffairs(currentAffairs) !== null;

  /*
   * The stage tiles as a LIST instead of six inline conditions in the JSX
   * ("Komposition v2", 15.08.): {@link HomeStage} has to know which tiles exist
   * before it can decide how many of them fit on the current page — a JSX
   * fragment cannot be counted, only rendered. The id/size list comes from the
   * registry (`./homeWidgets.ts`) instead of being built inline (W1).
   *
   * Order (§1.1/§5.3): Wetter leads now — it moved out of the header and
   * became the FIRST stage widget in L (Andi: "default ist die uhr und
   * wetter"), then Läuft · Einkauf · Sauger · Klima · Lagebild, unchanged.
   * The model in `homeLayout.ts` never re-sorts, so a tile keeps its place on
   * a hallway display.
   *
   * Every entry carries the same visibility condition as before — the honesty
   * rules are untouched. The Lagebild window is the one that decides for itself
   * whether it has anything to show, so it is asked here (`hasCurrentAffairs`,
   * the very question the window asks internally) instead of being handed in
   * blind: an invisible tile would otherwise claim a cell and leave a hole.
   */
  const stageTiles: HomeStageTile[] = [];
  // Die Uhr führt die Bühne an (Andi 19.08.) — bewusst OHNE `tile__head`:
  // eine Zeile „Uhr" über einer 124-px-Uhr wäre Beschriftung des
  // Offensichtlichen. Ihren Namen trägt sie für Screenreader und den
  // Edit-Modus über `aria-label` (s. `clockTileBody` / `HomeStage`).
  if (enabled.uhr) {
    stageTiles.push({
      id: 'uhr',
      size: stageSize('uhr'),
      node: (size) => (
        <article key="uhr" className="tile idle__tile idle__tile--clock" data-status="live">
          {clockTileBody(nowMs, size, locale, idleFace, sunTimes)}
        </article>
      ),
    });
  }
  // Der Wecker folgt der Uhr (W6) — wie sie OHNE `tile__head`: „Wecker" steht
  // schon im Satz selbst, eine Überschrift darüber sagte dasselbe zweimal.
  // Anders als die anderen Kacheln rendert er auch OHNE Daten: „Kein Wecker
  // gestellt" ist eine Antwort, kein Platzhalter — und es ist genau die
  // Antwort, die die Kopfzeile bis W5 an dieser Stelle gab. Wer sie nicht
  // sehen will, schaltet das Widget aus; dann ist es ganz weg.
  if (enabled.wecker) {
    stageTiles.push({
      id: 'wecker',
      size: stageSize('wecker'),
      node: (size) => (
        <article
          key="wecker"
          className="tile idle__tile idle__tile--alarm"
          data-status={alarm ? 'live' : 'idle'}
        >
          {alarmTileBody(alarm, nowMs, size, locale, idleFace, schedText)}
        </article>
      ),
    });
  }
  if (enabled.wetter) {
    const stored = stageSize('wetter');
    stageTiles.push({
      id: 'wetter',
      size: stored,
      // `size` here is the EFFECTIVE size `HomeStage` resolves per render
      // (Kurskorrektur 18.08. "Inhalt folgt der ECHTEN Fläche") — NOT `stored`,
      // which only sets the ceiling `effectiveSize` degrades from.
      node: (size) => (
        <article
          key="wetter"
          className="tile idle__tile tile--live"
          data-status={now.kind === 'live' ? 'live' : 'unreachable'}
        >
          {/* KEINE `live`-Pille (W6, Andi 20.08.) — s. `HomeTileCards.tsx`.
              Die Lücken-Zustände (`loading`/`off`/`unreachable`) sagen sich
              weiterhin selbst, mit ganzen Sätzen statt eines Abzeichens. */}
          <div className="tile__head">
            <span className="tile__name">{idleFace.wetter.name}</span>
            {/* Der Maximieren-Zugang — auch bei einer Lücke (`loading`/`off`/
                `unreachable`): die große Ansicht sagt dann denselben ehrlichen
                Satz, und ein Knopf, der je nach Netzlage verschwindet, wäre
                eine Bühne, die unter der Hand die Form wechselt. */}
            <MaximizeButton
              label={idleFace.maximieren.openAria(idleFace.wetter.name)}
              onClick={() => setMaximized('wetter')}
            />
          </div>
          {/* `data-size` trägt die EFFEKTIVE Stufe ins CSS — die Typo der
              M-Stufe darf atmen, ohne S und L mitzuziehen (Andi 19.08.).
              Seit W5 reicht auch XL durch: die Stufe hat einen eigenen
              Rumpf (Fakten-Zeile + Stunden-Verlauf) und darum eigene Regeln. */}
          <div className="idle__now" data-size={size}>
            {weatherTileBody(now, size, idleFace, locale)}
          </div>
        </article>
      ),
    });
  }
  if (enabled.laeuft && scheduled.length > 0) {
    stageTiles.push({
      id: 'laeuft',
      size: stageSize('laeuft'),
      node: (size) => (
        <article key="laeuft" className="tile idle__tile tile--live" data-status="live">
          {/* KEINE `live`-Pille (W6) — diese Kachel existiert nur, solange
              `scheduled.length > 0`; ihre bloße Anwesenheit war die Aussage,
              die die Pille wiederholte. */}
          <div className="tile__head">
            <span className="tile__name">{idleFace.laeuft.name}</span>
          </div>
          {laeuftTileBody(scheduled, nowMs, size, idleFace, schedText, locale)}
        </article>
      ),
    });
  }
  if (enabled.einkauf && shopping.length > 0) {
    stageTiles.push({
      id: 'einkauf',
      size: stageSize('einkauf'),
      node: (size) => (
        <article key="einkauf" className="tile idle__tile tile--live" data-status="live">
          {/* KEINE `live`-Pille (W6) — wie bei „Läuft": die Kachel gibt es nur
              mit echten Einträgen. */}
          <div className="tile__head">
            <span className="tile__name">{idleFace.einkauf.name}</span>
          </div>
          {einkaufTileBody(shopping, size, idleFace)}
        </article>
      ),
    });
  }
  // Zuhause-Kacheln (Andi-Auftrag 2026-08-11): nur sichtbar, wenn Andi sie im
  // SettingsPanel aktiviert hat — die Kachel selbst bleibt dann IMMER stehen
  // (auch „nicht erreichbar" ist eine ehrliche Aussage, anders als
  // Läuft/Einkauf verschwindet sie nicht bei leeren Daten).
  if (enabled.vacuum) {
    stageTiles.push({
      id: 'vacuum',
      size: stageSize('vacuum'),
      node: (size) => <VacuumTile key="vacuum" registry={homeRegistry} nowMs={nowMs} size={size} />,
    });
  }
  if (enabled.climate) {
    stageTiles.push({
      id: 'climate',
      size: stageSize('climate'),
      node: (size) => <ClimateTile key="climate" registry={homeRegistry} nowMs={nowMs} size={size} />,
    });
  }
  // Lagebild (Auftrag F5, Welle 1): kein Platzhalter — das Fenster rendert
  // NICHTS, außer der Endpoint hat echte Meldungen (FRESH/STALE). Der
  // Anzeige-Schalter davor ist der harte Riegel: aus ⇒ auch bei FRESH kein
  // Fenster (Muster Sauger/Klima, nur mit Default AN).
  if (hasCurrentAffairs) {
    stageTiles.push({
      id: 'news',
      size: stageSize('news'),
      node: (size) => (
        <CurrentAffairsTile
          key="news"
          state={currentAffairs}
          nowMs={nowMs}
          size={size}
          onMaximize={() => setMaximized('news')}
        />
      ),
    });
  }

  return (
    <section className="idle" aria-label={idleFace.sectionAria}>
      {/* 1 · Kopfzeile: nur noch der GRUSS — und jetzt wirklich nur er.
          Erst verließ das Wetter den Kopf (W1), dann die Uhr (W4, Andi 19.08.),
          mit W6 auch der Wecker (Andi 20.08.). Alle drei sind Kacheln, die Andi
          selbst legen, größer ziehen und abschalten kann. Was bleibt, ist der
          Satz, der niemandem gehört und keine Stufe braucht.
          Das DATUM steht in der Uhr-Kachel (M/L). Ist die Uhr ausgeschaltet,
          holt der Kopf es zurück — sonst verschwände es mit einem Schalter,
          der nach „Uhr" heißt und nicht nach „Datum".
          Die Zeile bleibt IMMER stehen (auch leer wäre sie eine Grid-Zeile) —
          die Kopf-Mechanik hängt an ihrer Existenz, deshalb tragen Kopf und
          Bühne ihre `grid-row` seit W4 explizit (index.css). Aus `auto auto
          1fr` sind mit dem Wecker-Umzug **zwei** Zeilen geworden: eine leere
          `auto`-Zeile wäre 0 px hoch, ihre Grid-Lücke aber nicht — der Abstand
          Kopf→Bühne wäre bei 40 statt 20 px stehen geblieben. */}
      <header className="idle__head">
        <div className="idle__headmain">
          <p className="idle__greet">
            {enabled.uhr ? greeting : `${greeting} · ${dateLine}`}
          </p>
        </div>
      </header>

      {/* 2 · Die Bühne: die Haushalts-Karten in Seiten statt in einer
          gequetschten Zeile (Komposition v2, 15.08. — Andi am iPad: „die
          Widgets sind einfach zusammengepresst"). Die Liste oben trägt die
          Reihenfolge, {@link HomeStage} misst den Rest des Fensters und
          entscheidet, wie viele davon auf Seite 1 passen. Ist die Liste leer,
          rendert die Bühne NICHTS (kein Lärm, Muster ScheduledPanel). */}
      <HomeStage tiles={stageTiles} />

      {/* 3 · Die Vollbild-Ansichten (Andi 23.08.). Sie stehen als GESCHWISTER
          der Bühne, nicht in ihren Kacheln — s. `maximized` oben. Beide bleiben
          dauerhaft montiert und schalten nur ihre Sichtbarkeit um (Idiom der
          Modal-Hülle): so gehört der Ein-/Austritt der CSS, und ein geschlossener
          Kasten ist trotzdem keine Tab-Falle. Sie lesen exakt dieselben Zustände
          wie die Kacheln — kein zweiter Fetch, kein zweiter Poller, das
          Visibility-Gating der Hooks bleibt unberührt. */}
      {/* Jede Vollbild-Ansicht existiert NUR, solange ihre Kachel existiert.
          Das ist keine Sparsamkeit, sondern dieselbe Zusage wie überall: ein
          abgeschaltetes Widget hinterlässt auf dem Zuhause-Reiter kein Wort —
          auch keins in einem geschlossenen Kasten (die Tests
          `currentaffairs.test.tsx` riegeln genau das). */}
      {hasCurrentAffairs && (
        <NewsMaxOverlay
          open={maximized === 'news'}
          onClose={() => setMaximized(null)}
          state={currentAffairs}
          nowMs={nowMs}
          idleFace={idleFace}
          locale={locale}
        />
      )}
      {enabled.wetter && (
        <WeatherMaxOverlay
          open={maximized === 'wetter'}
          onClose={() => setMaximized(null)}
          weather={weather}
          nowMs={nowMs}
          idleFace={idleFace}
          locale={locale}
        />
      )}

      {/* 5 · Die stillen Text-Chips stehen NICHT mehr hier — sie sind die
          Zuhause-Fußleiste geworden (`components/HomeStatusBar.tsx`, Andi
          19.08.: „unten links … etwas wie die Leiste oben, nur unten"). Sie
          müssen dafür UNTER dem Orb liegen, und alles, was diese Datei
          rendert, steht zwangsläufig darüber. */}

      {/* Bewusst KEINE Welle: hier hört Hoshi nichts, also leuchtet nichts
          (Korrektur 20260706-1729). Die Welle lebt nur im Chat-Voice-Flow. */}
    </section>
  );
}

/* ── Live-Verdrahtung ───────────────────────────────────────────────────── */

/**
 * Verdrahtet die echten Quellen: Scheduled (~15s), Wetter (~10 min,
 * Wetter ändert sich langsam), Einkauf (~30s), Registry (~5 min, Andi-Auftrag
 * 2026-08-11 „Zuhause-Kacheln"), Lagebild (~10 min, dieselbe Taktung wie das
 * Wetter — Auftrag F5) + Minuten-Tick für die Uhr. KEIN Diary-Hook mehr hier
 * (Flur-Display-Umbau) — die „Heute"-Kachel lebt jetzt in Aktivität/Diagnose.
 * KEIN `onOpenSettings` mehr (Andi-Korrektur 26.07) — das Jetzt-Band trug ein
 * eigenes Settings-Zahnrad, das oben rechts in der Top-Nav schon existiert.
 *
 * **KEIN `useOpsStatus()` mehr** (19.08.): der Ops-Poller speiste nur den
 * Stimme-Chip, und der ist mit der Fußleiste umgezogen. Er läuft jetzt EINMAL
 * in `views/UebersichtView.tsx#UebersichtViewLive` und speist von dort die
 * Leiste — die Hausregel „eine Poll-Quelle je Endpoint" bleibt gewahrt, sie
 * hat nur den Besitzer gewechselt (wie `health` es schon immer war).
 */
export function IdleFaceLive() {
  const { items } = useScheduledItems();
  const weather = useWeatherToday();
  const shopping = useShoppingList();
  const homeRegistry = useHomeRegistry();
  const { vacuumEnabled, climateEnabled, currentAffairsEnabled, enabled } = useHomeTiles();
  // Lagebild (~10 min) — bewusst DIESELBE Taktung wie das Wetter (langsame,
  // extern aufgefrischte Kachel-Daten), kein eigenes Intervall erfunden.
  // Poll-Ehrlichkeit: steht der Anzeige-Schalter auf AUS, pollt der Hook GAR
  // NICHT (s. hooks/useCurrentAffairs.ts) — kein 10-Minuten-Fetch für ein
  // Fenster, das ohnehin niemand sieht.
  const currentAffairs = useCurrentAffairs(CURRENT_AFFAIRS_POLL_MS, currentAffairsEnabled);

  // Uhr-Tick: sekündlich schauen, aber nur beim MINUTEN-Wechsel neu rendern
  // (die Uhr zeigt HH:MM; Countdown/Progress sind ohnehin minutengranular).
  const [nowMs, setNowMs] = useState<number>(() => Date.now());
  useEffect(() => {
    let lastMinute = new Date().getMinutes();
    const id = window.setInterval(() => {
      const d = new Date();
      if (d.getMinutes() !== lastMinute) {
        lastMinute = d.getMinutes();
        setNowMs(d.getTime());
      }
    }, 1000);
    return () => window.clearInterval(id);
  }, []);

  return (
    <IdleFace
      nowMs={nowMs}
      scheduled={items}
      weather={weather}
      shopping={shopping}
      homeRegistry={homeRegistry}
      vacuumTileEnabled={vacuumEnabled}
      climateTileEnabled={climateEnabled}
      uhrTileEnabled={enabled.uhr}
      weckerTileEnabled={enabled.wecker}
      wetterTileEnabled={enabled.wetter}
      laeuftTileEnabled={enabled.laeuft}
      einkaufTileEnabled={enabled.einkauf}
      currentAffairs={currentAffairs}
      currentAffairsTileEnabled={currentAffairsEnabled}
    />
  );
}
