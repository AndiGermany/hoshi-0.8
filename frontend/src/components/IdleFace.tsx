import { Fragment, useEffect, useState } from 'react';
import { dayPartForHour } from './greeting';
import { type HealthState } from '../hooks/useHealth';
import { useOpsStatus, type OpsVoice } from '../hooks/useOpsStatus';
import {
  dueClock,
  fmtRemaining,
  useScheduledItems,
  KIND_WORD,
  type ScheduledItem,
  type ScheduledKind,
} from '../hooks/useScheduledItems';
import { useDiary, type DiaryTurn } from '../hooks/useDiary';
import {
  useWeatherToday,
  type WeatherToday,
  type WeatherTodayState,
} from '../hooks/useWeatherToday';
import { AlarmGlyph, CloudGlyph, GearGlyph, LockGlyph } from './icons';
import type { SettingsAnchorId, SettingsCategoryId } from './SettingsPanel';
import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import type { IdleFaceStrings } from '../i18n/types';

/**
 * Sprach-Katalog-Default für die exportierten PUR-Funktionen unten (Muster
 * {@link BRAIN_MODEL_TEXTS} in SettingsPanel.tsx): `idleface.test.tsx` ruft
 * `alarmLineText`/`todayTileValue`/`planTileValue`/`weatherTile`/`statusChips`
 * DIREKT mit der alten Signatur auf (kein Strings-Argument) — der Default
 * `de.idleFace` hält dieses Rendering byte-gleich zum bisherigen Stand. Die
 * echte Komponente {@link IdleFace} reicht stattdessen den LIVE-Katalog
 * (`useUiStrings().idleFace`) durch.
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

/**
 * **IdleFace** — das Aoi-Idle-/Papier-Gesicht, Andis „Zuhause"-Layout
 * (Cowork-Spec 2026-07-02 §2, von Andi abgenommen). Vier Elemente:
 *
 *  1. **Typo-first Uhr**: echte Zeit, tabular-nums, groß/fluid — dazu der
 *     tageszeitbewusste Gruß ({@link dayPartForHour} + `idleFace.greeting`
 *     aus dem UI-Sprach-Katalog) + echtes Datum.
 *  2. **Wecker-Zeile**: ⏰ + „Wecker 07:00 · noch X h" + 2px-Fortschritts-
 *     Haarlinie in accent + rechts der Vertrauens-Satz „klingelt auch offline"
 *     (Text ist Teil des Designs — und WAHR: der Wecker lebt im lokalen
 *     Backend-Store, nicht in einer Cloud; er feuert auch ohne Internet).
 *     Kein Wecker gestellt ⇒ die Zeile sagt das ehrlich (kein Fortschritt,
 *     kein Vertrauens-Satz über etwas, das es nicht gibt).
 *  3. **3 ehrliche Kacheln** (die fixierte Designsprache): LIVE = ausgefüllt
 *     mit ECHTEN Werten aus bestehenden Endpoints („Heute" aus
 *     GET /api/v1/diary/recent · „Geplant" aus GET /api/v1/scheduled ·
 *     „Wetter" aus GET /api/v1/weather/today — derselbe Open-Meteo-Datenpfad,
 *     den Hoshi im Gespräch nutzt) — kommend = gestrichelt mit ehrlichem
 *     Grund (Wetter beim Deploy aus ⇒ 404 ⇒ „kommt", wie vor dem Endpoint).
 *     Fehlt ein Datum (Diary/Wetter nicht erreichbar), zeigt die Kachel das
 *     ehrlich — nie Fake-Werte.
 *  4. **Statuszeile als stille Text-Chips** statt lauter Pillen:
 *     `● online · ☁ Stimme: Cloud` bzw. `🔒 Stimme: lokal`. Der Stimme-Chip
 *     erscheint NUR, wenn `/api/v1/ops/status` das voice-Feld ehrlich liefert
 *     (nichts behaupten, was nichts misst). Der aufklappbare Ops-Punkt bleibt
 *     unangetastet in der Top-Nav ({@link OpsStatusPill}).
 *
 *  KEINE Welle hier (Andi-Feedback 2026-07-06 + Cowork-Korrektur
 *  20260706-1729): auf der Übersicht hört Hoshi nichts — also leuchtet auch
 *  nichts (kein synthetisches Atmen, nirgends). Die Welle existiert NUR im
 *  Chat-Voice-Flow, wenn ein Audio-Kanal wirklich offen ist; ihr Erscheinen
 *  dort IST das Signal „jetzt höre ich". Hier: ruhiges Papier.
 *
 * {@link IdleFace} ist prop-getrieben (kein Netz) und braucht keine DOM-Umgebung
 * → weiter unit-testbar per `renderToStaticMarkup` (test/idleface.test.tsx).
 * Andi-Auftrag 21.07 (UI-Sprache betrifft auch den ersten Bildschirm): die
 * Komponente ruft jetzt `useUiStrings()` (Muster {@link TileCard} in
 * UebersichtView.tsx) — der EINZIGE Hook hier, kein Netz/Fetch. Die
 * exportierten PUR-Helfer (`alarmLineText`, `todayTileValue`, `planTileValue`,
 * `weatherTile`, `statusChips`, `fmtP50`) bleiben hook-frei und nehmen den
 * Katalog optional als Parameter (Default `de.idleFace`/`de.locale` — Muster
 * {@link BRAIN_MODEL_TEXTS} in SettingsPanel.tsx), damit `idleface.test.tsx`
 * unverändert byte-gleich grün bleibt. Die Live-Verdrahtung (Ops/Scheduled/
 * Diary-Hooks + Minuten-Tick) macht {@link IdleFaceLive}.
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
 * `dueClock`/`fmtRemaining` (aus `hooks/useScheduledItems.ts`, außerhalb
 * dieser Scheibe) bleiben hart de-DE/deutsche Einheiten („h"/„min") — nur der
 * umgebende Satzbau („Wecker … · noch …") folgt der aktiven UI-Sprache.
 */
export function alarmLineText(
  alarm: ScheduledItem,
  nowMs: number,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
): string {
  const remaining = fmtRemaining(Math.max(0, alarm.dueAtEpochMs - nowMs));
  return t.alarmLine(dueClock(alarm.dueAtEpochMs), remaining);
}

export interface DiaryTodayStats {
  /** Zahl der echten Turns HEUTE (lokaler Kalendertag von nowMs). */
  turns: number;
  /** Median der ttftMs heutiger Turns; null = kein Turn hatte je ein Token. */
  p50Ms: number | null;
  /** Turns mit Fehler-Stage (STT/LLM/SIDECAR/TTS) — die „Aussetzer". */
  errors: number;
}

/** Verdichtet die Diary-Zeilen auf HEUTE (Turns · p50 · Aussetzer). */
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
 * über alle Sprachen hinweg unübersetzt (Fachbegriff, Muster: `activity.
 * stageLatencyHint` trägt „p50/p95" auch in en/es/fr/it wörtlich).
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

const PLAN_KINDS: readonly ScheduledKind[] = ['TIMER', 'ALARM', 'REMINDER'];

/**
 * „2 Timer · 1 Wecker" aus den aktiven Items — leer ⇒ „Nichts geplant".
 * `KIND_WORD` (aus `hooks/useScheduledItems.ts`, außerhalb dieser Scheibe)
 * liefert die Timer/Wecker/Erinnerung-Nomen weiter hart deutsch — nur der
 * ehrliche Leer-Text folgt der aktiven UI-Sprache.
 */
export function planTileValue(items: ScheduledItem[], t: IdleFaceStrings = IDLE_FACE_TEXTS): string {
  const parts = PLAN_KINDS.flatMap((kind) => {
    const count = items.filter((i) => i.kind === kind).length;
    if (count === 0) return [];
    return [`${count} ${count === 1 ? KIND_WORD[kind].one : KIND_WORD[kind].many}`];
  });
  return parts.length === 0 ? t.geplant.nichtsGeplant : parts.join(' · ');
}

/** „18–29° · bedeckt" — kompakter Kachel-Wert aus den echten Tageswerten. */
export function weatherTileValue(w: WeatherToday): string {
  return `${w.todayMin}–${w.todayMax}° · ${w.codeText}`;
}

/**
 * Die Wetter-Kachel aus dem ehrlichen Endpoint-Zustand:
 *  - live-Daten ⇒ LIVE-Kachel mit echtem Wert + Ort-Label in der Notiz.
 *  - `off` (404, Wetter beim Deploy aus) ⇒ gestrichelt „kommt" — exakt die
 *    ehrliche Lücke von früher, nur mit dem echten Grund.
 *  - `unreachable` ⇒ „—" + „Wetter grad nicht lesbar" (Muster „Heute"-Kachel
 *    bei Diary-Ausfall: LIVE-Rahmen, ehrliche Lücke, nie Fake-Werte).
 *  - `null` (erster Fetch läuft) ⇒ gestrichelt, ehrlich „wird gelesen".
 *
 * `weatherTileValue`/`w.codeText` (der WMO-Lagen-Text) kommt vom Backend
 * (`hooks/useWeatherToday.ts`, außerhalb dieser Scheibe) und bleibt darum
 * deutsch, unabhängig von der UI-Sprache — nur Kachel-Titel/Notizen hier
 * folgen dem Katalog.
 */
export function weatherTile(
  weather: WeatherTodayState | null,
  t: IdleFaceStrings = IDLE_FACE_TEXTS,
): IdleTile {
  if (weather === null) {
    return {
      name: t.wetter.name,
      honesty: 'pending',
      value: '—',
      note: t.wetter.loadingNote,
    };
  }
  switch (weather.kind) {
    case 'live':
      return {
        name: t.wetter.name,
        honesty: 'live',
        value: weatherTileValue(weather.data),
        note: t.wetter.liveNote(weather.data.label),
      };
    case 'off':
      return {
        name: t.wetter.name,
        honesty: 'pending',
        value: '—',
        note: t.wetter.offNote,
      };
    case 'unreachable':
      return {
        name: t.wetter.name,
        honesty: 'live',
        value: '—',
        note: t.wetter.unreachableNote,
      };
  }
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

/* ── Kachel-Baustein (fixierte Designsprache: LIVE gefüllt · kommend gestrichelt) ── */

export interface IdleTile {
  name: string;
  honesty: 'live' | 'pending';
  value: string;
  note: string;
}

function IdleTileCard({ tile, onSettings }: { tile: IdleTile; onSettings?: () => void }) {
  const { idleFace } = useUiStrings();
  return (
    <article
      className={`tile idle__tile tile--${tile.honesty}`}
      data-status={tile.honesty}
      aria-disabled={tile.honesty !== 'live'}
    >
      <div className="tile__head">
        <span className="tile__name">{tile.name}</span>
        <span className="tile__pill">{tile.honesty === 'live' ? idleFace.live : idleFace.pending}</span>
        {/* Kontextueller Settings-Anker (Cowork-Spec V1): springt in „Standort &
            Integrationen"/Wetter-Standort — nur an der Wetter-Kachel, wo das
            Setting wirklich wirkt. Dezent (--text-4, .ctxgear), kein Layout-
            Sprung — sitzt einfach nach der live/kommt-Pille. */}
        {onSettings && (
          <button
            type="button"
            className="ctxgear"
            onClick={onSettings}
            aria-label={idleFace.wetter.settingsAria}
            title={idleFace.wetter.settingsTitle}
          >
            <GearGlyph className="ctxgear__icon" />
          </button>
        )}
      </div>
      <div className="tile__value">{tile.value}</div>
      <p className="tile__note">{tile.note}</p>
    </article>
  );
}

/* ── die Ansicht (rein prop-getrieben) ──────────────────────────────────── */

export interface IdleFaceProps {
  /** Jetzt-Zeitpunkt (epoch ms) — von außen, damit die Ansicht pur/testbar bleibt. */
  nowMs: number;
  health: HealthState;
  /** Aktive TTS-Engine aus /api/v1/ops/status — null = unbekannt (kein Chip). */
  voice: OpsVoice | null;
  /** Aktive Items aus GET /api/v1/scheduled (Wecker-Zeile + „Geplant"-Kachel). */
  scheduled: ScheduledItem[];
  /** Diary-Zeilen aus GET /api/v1/diary/recent — null = nicht erreichbar. */
  turns: DiaryTurn[] | null;
  /**
   * Heutiges Wetter aus GET /api/v1/weather/today — null = erster Fetch läuft;
   * `off`/`unreachable` sind EHRLICHE Zustände (siehe {@link weatherTile}).
   */
  weather: WeatherTodayState | null;
  /**
   * Öffnet den Settings-Drawer deep-gelinkt (App.tsx `openSettings`). Optional:
   * fehlt es (z. B. in Tests), rendert die Wetter-Kachel ohne Zahnrad — kein
   * Bruch, nur ein fehlender Komfort-Anker.
   */
  onOpenSettings?: (category: SettingsCategoryId, anchor?: SettingsAnchorId) => void;
}

export function IdleFace({
  nowMs,
  health,
  voice,
  scheduled,
  turns,
  weather,
  onOpenSettings,
}: IdleFaceProps) {
  const { idleFace, locale } = useUiStrings();
  const date = new Date(nowMs);
  const greeting = idleFace.greeting(dayPartForHour(date.getHours()));
  const dateLine = date.toLocaleDateString(locale, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  const alarm = nextAlarm(scheduled);
  const chips = statusChips(health, voice, idleFace);

  // Kachel „Heute": echte Diary-Zahlen — oder die ehrliche Lücke.
  const stats = turns !== null ? diaryTodayStats(turns, nowMs) : null;
  const heute: IdleTile = {
    name: idleFace.heute.name,
    honesty: 'live',
    value:
      stats === null
        ? '—'
        : stats.turns === 0
          ? idleFace.heute.noTurnYet
          : todayTileValue(stats, idleFace, locale),
    note:
      stats === null
        ? idleFace.heute.noteUnavailable
        : stats.turns === 0
          ? idleFace.heute.noteEmpty
          : idleFace.heute.noteWithData,
  };

  const geplant: IdleTile = {
    name: idleFace.geplant.name,
    honesty: 'live',
    value: planTileValue(scheduled, idleFace),
    note: idleFace.geplant.note,
  };

  // Kachel „Wetter": echte heutige Vorhersage — oder die ehrliche Lücke
  // (Deploy-OFF gestrichelt, nicht lesbar „—"). Nie Fake-Grade.
  const wetter = weatherTile(weather, idleFace);

  return (
    <section className="idle" aria-label={idleFace.sectionAria}>
      {/* 1 · Typo-first Uhr + tageszeitbewusster Gruß (beides echt). */}
      <header className="idle__head">
        <time className="idle__clock" dateTime={date.toISOString()}>
          {dueClock(nowMs)}
        </time>
        <p className="idle__greet">
          {greeting} · {dateLine}
        </p>
      </header>

      {/* 2 · Wecker-Zeile mit Fortschritts-Haarlinie + Vertrauens-Satz. */}
      {alarm ? (
        <div className="idle__alarm" data-alarm="set">
          <span className="idle__alarmicon" aria-hidden="true">
            <AlarmGlyph />
          </span>
          <span className="idle__alarmtext">{alarmLineText(alarm, nowMs, idleFace)}</span>
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

      {/* 3 · Drei ehrliche Kacheln: LIVE mit echten Werten — oder gestrichelt mit Grund.
          Nur die Wetter-Kachel trägt den Settings-Anker (Standort & Integrationen
          wirkt genau hier — „Heute"/„Geplant" haben keine zugehörige Settings-Sektion). */}
      <div className="idle__tiles">
        <IdleTileCard tile={heute} />
        <IdleTileCard tile={geplant} />
        <IdleTileCard
          tile={wetter}
          onSettings={
            onOpenSettings
              ? () => onOpenSettings('standort-integrationen', 'wetter-standort')
              : undefined
          }
        />
      </div>

      {/* 4 · Stille Text-Chips (der aufklappbare Ops-Punkt bleibt in der Nav). */}
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
 * Verdrahtet die echten Quellen: Ops (~30s), Scheduled (~15s), Diary (einmal
 * beim Mount — wie die Aktivitäts-View, kein Dauerpoll), Wetter (~10 min,
 * Wetter ändert sich langsam) + Minuten-Tick für die Uhr. Health kommt als
 * Prop herein (die Übersicht pollt /api/health bereits — keine zweite
 * Poll-Quelle für denselben Endpoint).
 */
export function IdleFaceLive({
  health,
  onOpenSettings,
}: {
  health: HealthState;
  onOpenSettings?: (category: SettingsCategoryId, anchor?: SettingsAnchorId) => void;
}) {
  const ops = useOpsStatus();
  const { items } = useScheduledItems();
  const { turns } = useDiary();
  const weather = useWeatherToday();

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
      turns={turns}
      weather={weather}
      onOpenSettings={onOpenSettings}
    />
  );
}
