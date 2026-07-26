import { Fragment, useEffect, useState } from 'react';
import { dayPartForHour } from './greeting';
import { type HealthState } from '../hooks/useHealth';
import { useOpsStatus, type OpsVoice } from '../hooks/useOpsStatus';
import {
  clockParts,
  dueClock,
  fmtRemaining,
  runningItemLine,
  useScheduledItems,
  SCHEDULED_TEXTS,
  type ScheduledItem,
} from '../hooks/useScheduledItems';
import type { DiaryTurn } from '../hooks/useDiary';
import {
  useWeatherToday,
  type WeatherToday,
  type WeatherTodayState,
} from '../hooks/useWeatherToday';
import { useShoppingList } from '../hooks/useShoppingList';
import type { ListItem } from '../api/lists';
import {
  AlarmGlyph,
  CloudGlyph,
  CloudSunGlyph,
  FogGlyph,
  LockGlyph,
  RainCloudGlyph,
  SnowCloudGlyph,
  SunGlyph,
  ThunderCloudGlyph,
} from './icons';
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

/** Dezimaltrenner je Locale — dieselbe simple toFixed+replace-Technik wie zuvor, nur jetzt pro Sprache statt hart de. */
const DECIMAL_SEPARATOR: Record<string, string> = {
  'de-DE': ',',
  'en-US': '.',
  'es-ES': ',',
  'fr-FR': ',',
  'it-IT': ',',
};

/** Höchstens so viele Einkaufs-Einträge sichtbar, Rest hinter „+N weitere" (Andi-Vorgabe „3-4 Einträge"). */
export const SHOPPING_VISIBLE_COUNT = 4;

/**
 * **IdleFace** — das Aoi-Idle-/Papier-Gesicht, Andis Flur-Display-Layout
 * (ursprünglich Cowork-Spec 2026-07-02 §2; grundlegend neu geschnitten beim
 * Flur-Display-Umbau, Andi-Auftrag 2026-07-26 — Home lief bei Andi als
 * iPad-Display im Flur und verschenkte Fläche/Informationen). Fünf Elemente:
 *
 *  1. **Kopfzeile**: links die typo-first Uhr + tageszeitbewusster Gruß
 *     ({@link dayPartForHour} + `idleFace.greeting`) + echtes Datum; RECHTS
 *     das **Jetzt-Band** — die frühere „Wetter"-Kachel ist keine Kachel mehr,
 *     sondern läuft prominent neben der Uhr: ein dezentes Lage-Icon
 *     ({@link weatherCategory}/{@link WeatherGlyph} — feste Zuordnung auf den
 *     deutschen WMO-Text, kein buntes Wetter-App-Icon) + Wetterlage (groß),
 *     Tagesspanne, und ENDLICH `precipMm` gerendert (kam im FE an, wurde nie
 *     gezeigt) als warme Zeile „3 mm Regen heute" / „trocken"
 *     ({@link weatherNowContent}). KEIN Settings-Zahnrad mehr hier (Andi-
 *     Korrektur 26.07: das Zahnrad oben rechts in der Top-Nav reicht — ein
 *     zweites an derselben Stelle war redundant).
 *  2. **Wecker-Zeile**: ⏰ + „Wecker 07:00 · noch X h" + 2px-Fortschritts-
 *     Haarlinie in accent + rechts der Vertrauens-Satz „klingelt auch offline".
 *     Kein Wecker gestellt ⇒ die Zeile sagt das ehrlich.
 *  3. **„Läuft"-Karte** (ex-„Geplant"): zeigt jetzt ECHTE Countdowns mit Labels
 *     statt nur einer Zählung („12:04 Nudeln" · „38 min Wäsche",
 *     {@link runningItemLine} aus `hooks/useScheduledItems.ts` — dieselbe
 *     Zeit-Logik wie ScheduledPanel.tsx, nur im knappen Flur-Ton statt
 *     „um"/„noch"). Läuft nichts ⇒ die Karte VERSCHWINDET (kein „Nichts
 *     geplant"-Platzhalter mehr — Lärm-Vermeidung, Muster ScheduledPanel).
 *  4. **Einkaufs-Karte** (neu, `GET /api/v1/lists`, Andi-JA 2026-07-08): die
 *     ersten {@link SHOPPING_VISIBLE_COUNT} Einträge + „+N weitere", Menge als
 *     „2×". Leere Liste ⇒ Karte weg; Fetch-Fehler liefert still `[]`
 *     (`api/lists.ts`) ⇒ dieselbe Ehrlichkeits-/Lärm-Achse, kein Fehler-Banner
 *     im Flur.
 *  5. **Statuszeile** als stille Text-Chips: `● online · ☁ Stimme: Cloud` bzw.
 *     `🔒 Stimme: lokal`. Der Stimme-Chip erscheint NUR, wenn `/api/v1/ops/status`
 *     das voice-Feld ehrlich liefert.
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

/** ms-Niederschlag mit dem Dezimaltrenner der aktiven Sprache: 3 → „3", 1.2 → „1,2" (de) / „1.2" (en). */
export function fmtPrecip(mm: number, locale: string = de.locale): string {
  const rounded = Math.round(Math.max(0, mm) * 10) / 10;
  if (Number.isInteger(rounded)) return String(rounded);
  const sep = DECIMAL_SEPARATOR[locale] ?? '.';
  return rounded.toFixed(1).replace('.', sep);
}

/** Icon-Kategorie einer Wetterlage — s. {@link weatherCategory}. */
export type WeatherCategory = 'clear' | 'partly' | 'cloudy' | 'fog' | 'rain' | 'snow' | 'thunder';

/**
 * Ordnet den deutschen WMO-Lagen-Text (`codeText`, IMMER Deutsch — s. KDoc
 * {@link weatherNowContent}) einer Icon-Kategorie zu (Andi-Auftrag 26.07:
 * „dezente Regenwolken bei Regen etc."). Feste, erschöpfende Zuordnung gegen
 * die 28 bekannten Backend-Strings (`WeatherCodeTexts.kt`, WMO-Codes 0–99) +
 * deren Fallback „wechselhaft"; Reihenfolge ist absichtlich (z. B. „Schnee-
 * schauer" enthält kein „regen", aber „Regenschauer" enthält „regen" — daher
 * Schnee VOR Regen geprüft). Unbekannter/neuer Text ⇒ 'cloudy', die
 * neutralste ehrliche Annäherung statt eines geratenen Sonnen-/Regen-Icons.
 */
export function weatherCategory(codeText: string): WeatherCategory {
  const t = codeText.toLowerCase();
  if (t.includes('gewitter')) return 'thunder';
  if (t.includes('schnee')) return 'snow';
  if (t.includes('neb')) return 'fog'; // „neblig" UND „gefrierender Nebel" (kein gemeinsames „nebel")
  if (t.includes('regen')) return 'rain'; // Regen, Nieselregen, Regenschauer
  if (t.includes('klar')) return 'clear'; // klar und sonnig, überwiegend klar
  if (t.includes('teilweise bewölkt')) return 'partly';
  return 'cloudy'; // bedeckt, wechselhaft (Fallback), unbekannt
}

/** Das dezente Lage-Icon je Kategorie — muted stroke-SVGs (components/icons.tsx), kein Emoji/Farb-Icon-Set. */
function WeatherGlyph({ category, className }: { category: WeatherCategory; className?: string }) {
  switch (category) {
    case 'clear':
      return <SunGlyph className={className} />;
    case 'partly':
      return <CloudSunGlyph className={className} />;
    case 'fog':
      return <FogGlyph className={className} />;
    case 'rain':
      return <RainCloudGlyph className={className} />;
    case 'snow':
      return <SnowCloudGlyph className={className} />;
    case 'thunder':
      return <ThunderCloudGlyph className={className} />;
    case 'cloudy':
      return <CloudGlyph className={className} />;
  }
}

/** Der Inhalt des Jetzt-Bands: entweder eine ehrliche Lücke (Text) oder die echten Wetter-Zeilen + Icon-Kategorie. */
export type WeatherNowContent =
  | { kind: 'gap'; text: string }
  | { kind: 'live'; cond: string; span: string; precip: string; category: WeatherCategory };

/**
 * Leitet den Inhalt des Jetzt-Bands aus dem ehrlichen Wetter-Endpoint-Zustand
 * ab (ex-`weatherTile`, jetzt kein `.tile` mehr):
 *  - `null` (erster Fetch läuft) / `off` (404, Wetter beim Deploy aus) /
 *    `unreachable` (401/5xx/Netz) ⇒ EINE ehrliche Lücken-Zeile — exakt dieselben
 *    Texte wie die frühere gestrichelte Kachel, nur ohne Kachel-Rahmen.
 *  - `live` ⇒ Icon-Kategorie ({@link weatherCategory}) + Wetterlage
 *    (`codeText`), Tagesspanne („18–29°"), und die warme Niederschlags-Zeile
 *    (`precipMm > 0` ⇒ „3 mm Regen heute" via {@link fmtPrecip}, sonst
 *    „trocken" — der Wert kam im FE zwar an, wurde vor diesem Umbau aber nie
 *    gerendert). `codeText` bleibt deutsch, unabhängig von der UI-Sprache
 *    (kommt vom Backend, s. `hooks/useWeatherToday.ts`) — nur die Icon-
 *    Zuordnung liest ihn, nichts wird übersetzt.
 */
export function weatherNowContent(
  weather: WeatherTodayState | null,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
  locale: string = de.locale,
): WeatherNowContent {
  if (weather === null) return { kind: 'gap', text: t.wetter.loadingNote };
  if (weather.kind === 'off') return { kind: 'gap', text: t.wetter.offNote };
  if (weather.kind === 'unreachable') return { kind: 'gap', text: t.wetter.unreachableNote };
  const w: WeatherToday = weather.data;
  const precip = w.precipMm > 0 ? t.wetter.precipSome(fmtPrecip(w.precipMm, locale)) : t.wetter.precipNone;
  return {
    kind: 'live',
    cond: w.codeText,
    span: `${w.todayMin}–${w.todayMax}°`,
    precip,
    category: weatherCategory(w.codeText),
  };
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

/** Ton → Glyph: ●-Punkt für Health (CSS färbt), Wolke/Schloss als muted SVG. */
function chipGlyph(tone: StatusChip['tone']) {
  if (tone === 'cloud') return <CloudGlyph />;
  if (tone === 'local') return <LockGlyph />;
  return '●';
}

/* ── die Ansicht (rein prop-getrieben) ──────────────────────────────────── */

export interface IdleFaceProps {
  /** Jetzt-Zeitpunkt (epoch ms) — von außen, damit die Ansicht pur/testbar bleibt. */
  nowMs: number;
  health: HealthState;
  /** Aktive TTS-Engine aus /api/v1/ops/status — null = unbekannt (kein Chip). */
  voice: OpsVoice | null;
  /** Aktive Items aus GET /api/v1/scheduled (Wecker-Zeile + „Läuft"-Karte). */
  scheduled: ScheduledItem[];
  /**
   * Heutiges Wetter aus GET /api/v1/weather/today — null = erster Fetch läuft;
   * `off`/`unreachable` sind EHRLICHE Zustände (siehe {@link weatherNowContent}).
   */
  weather: WeatherTodayState | null;
  /** Einkaufsliste aus GET /api/v1/lists — leer = Karte weg (kein Platzhalter). */
  shopping: ListItem[];
}

export function IdleFace({ nowMs, health, voice, scheduled, weather, shopping }: IdleFaceProps) {
  const { idleFace, locale, scheduled: schedText } = useUiStrings();
  const date = new Date(nowMs);
  const greeting = idleFace.greeting(dayPartForHour(date.getHours()));
  const dateLine = date.toLocaleDateString(locale, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  const alarm = nextAlarm(scheduled);
  const chips = statusChips(health, voice, idleFace);
  const now = weatherNowContent(weather, idleFace, locale);
  const hasHouseholdCards = scheduled.length > 0 || shopping.length > 0;
  const clock = clockParts(nowMs, locale);

  return (
    <section className="idle" aria-label={idleFace.sectionAria}>
      {/* 1 · Kopfzeile: Uhr + Gruß links, das Jetzt-Band (Wetter) rechts. */}
      <header className="idle__head">
        <div className="idle__headmain">
          <time className="idle__clock" dateTime={date.toISOString()}>
            {clock.time}
            {clock.period && <span className="idle__clockperiod">{clock.period}</span>}
          </time>
          <p className="idle__greet">
            {greeting} · {dateLine}
          </p>
        </div>
        <div className="idle__now">
          <span className="sr-only">{idleFace.wetter.name}: </span>
          {/* Bewusst KEIN Settings-Zahnrad mehr hier (Andi-Korrektur 26.07):
              die Top-Nav trägt schon eines oben rechts — ein zweites direkt
              daneben an der Wetterlage war eine unnötige Dopplung. */}
          {now.kind === 'gap' ? (
            <span className="idle__nowgap">{now.text}</span>
          ) : (
            <>
              <span className="idle__nowcond">
                <WeatherGlyph category={now.category} className="idle__nowicon" />
                {now.cond}
              </span>
              <span className="idle__nowspan">{now.span}</span>
              <span className="idle__nowprecip">{now.precip}</span>
            </>
          )}
        </div>
      </header>

      {/* 2 · Wecker-Zeile mit Fortschritts-Haarlinie + Vertrauens-Satz. */}
      {alarm ? (
        <div className="idle__alarm" data-alarm="set">
          <span className="idle__alarmicon" aria-hidden="true">
            <AlarmGlyph />
          </span>
          <span className="idle__alarmtext">
            {alarmLineText(alarm, nowMs, idleFace, schedText, locale)}
          </span>
          <span className="idle__alarmtruth" title={idleFace.alarmTrustTitle}>
            {idleFace.alarmTrustText}
          </span>
          <span className="idle__alarmtrack" aria-hidden="true">
            <span
              className="idle__alarmfill"
              style={{ transform: `scaleX(${alarmProgress(alarm.dueAtEpochMs, nowMs)})` }}
            />
          </span>
        </div>
      ) : (
        // Ehrlich: kein Wecker ⇒ keine Haarlinie, kein Vertrauens-Satz über nichts.
        <div className="idle__alarm idle__alarm--none" data-alarm="none">
          <span className="idle__alarmicon" aria-hidden="true">
            <AlarmGlyph />
          </span>
          <span className="idle__alarmtext">{idleFace.noAlarm}</span>
        </div>
      )}

      {/* 3+4 · Die Haushalts-Karten: „Läuft" (echte Timer/Wecker/Erinnerungen mit
          Label, größter/dringlichster zuerst — die Items kommen aus dem Hook
          bereits aufsteigend nach Fälligkeit sortiert) und „Einkauf". Beide
          verschwinden einzeln, wenn sie nichts zu zeigen haben; der ganze Block
          verschwindet, wenn BEIDE leer sind (kein Lärm, Muster ScheduledPanel). */}
      {hasHouseholdCards && (
        <div className="idle__tiles">
          {scheduled.length > 0 && (
            <article className="tile idle__tile tile--live" data-status="live">
              <div className="tile__head">
                <span className="tile__name">{idleFace.laeuft.name}</span>
                <span className="tile__pill">{idleFace.live}</span>
              </div>
              <ul className="idle__cardlist">
                {scheduled.map((item) => (
                  <li key={item.id}>{runningItemLine(item, nowMs, schedText, locale)}</li>
                ))}
              </ul>
            </article>
          )}
          {shopping.length > 0 && (
            <article className="tile idle__tile tile--live" data-status="live">
              <div className="tile__head">
                <span className="tile__name">{idleFace.einkauf.name}</span>
                <span className="tile__pill">{idleFace.live}</span>
              </div>
              <ul className="idle__cardlist">
                {shopping.slice(0, SHOPPING_VISIBLE_COUNT).map((it) => (
                  <li key={it.id}>
                    {it.quantity > 1 && <span className="idle__cardqty">{it.quantity}×</span>}{' '}
                    {it.text}
                  </li>
                ))}
              </ul>
              {shopping.length > SHOPPING_VISIBLE_COUNT && (
                <p className="idle__cardmore">
                  {idleFace.einkauf.more(shopping.length - SHOPPING_VISIBLE_COUNT)}
                </p>
              )}
            </article>
          )}
        </div>
      )}

      {/* 5 · Stille Text-Chips (der aufklappbare Ops-Punkt bleibt in der Nav). */}
      <p className="idle__chips" role="status" aria-live="polite">
        {chips.map((c, i) => (
          <Fragment key={c.text}>
            {i > 0 && (
              <span className="idle__chipsep" aria-hidden="true">
                ·
              </span>
            )}
            <span className={`idle__chip idle__chip--${c.tone}`}>
              <span className="idle__chipglyph" aria-hidden="true">
                {chipGlyph(c.tone)}
              </span>{' '}
              {c.text}
            </span>
          </Fragment>
        ))}
      </p>

      {/* Bewusst KEINE Welle: hier hört Hoshi nichts, also leuchtet nichts
          (Korrektur 20260706-1729). Die Welle lebt nur im Chat-Voice-Flow. */}
    </section>
  );
}

/* ── Live-Verdrahtung ───────────────────────────────────────────────────── */

/**
 * Verdrahtet die echten Quellen: Ops (~30s), Scheduled (~15s), Wetter (~10 min,
 * Wetter ändert sich langsam), Einkauf (~30s) + Minuten-Tick für die Uhr.
 * Health kommt als Prop herein (die Übersicht pollt /api/health bereits —
 * keine zweite Poll-Quelle für denselben Endpoint). KEIN Diary-Hook mehr hier
 * (Flur-Display-Umbau) — die „Heute"-Kachel lebt jetzt in Aktivität/Diagnose.
 * KEIN `onOpenSettings` mehr (Andi-Korrektur 26.07) — das Jetzt-Band trug ein
 * eigenes Settings-Zahnrad, das oben rechts in der Top-Nav schon existiert.
 */
export function IdleFaceLive({ health }: { health: HealthState }) {
  const ops = useOpsStatus();
  const { items } = useScheduledItems();
  const weather = useWeatherToday();
  const shopping = useShoppingList();

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
      health={health}
      voice={ops?.voice ?? null}
      scheduled={items}
      weather={weather}
      shopping={shopping}
    />
  );
}
