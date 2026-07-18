import { Fragment, useEffect, useState } from 'react';
import { greetingForHour } from './greeting';
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

/**
 * **IdleFace** — das Aoi-Idle-/Papier-Gesicht, Andis „Zuhause"-Layout
 * (Cowork-Spec 2026-07-02 §2, von Andi abgenommen). Vier Elemente:
 *
 *  1. **Typo-first Uhr**: echte Zeit, tabular-nums, groß/fluid — dazu der
 *     tageszeitbewusste Gruß ({@link greetingForHour}) + echtes Datum.
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
 * {@link IdleFace} ist rein prop-getrieben (kein Hook, kein Netz) → ohne
 * DOM/Fetch unit-testbar (test/idleface.test.tsx); die Live-Verdrahtung
 * (Ops/Scheduled/Diary-Hooks + Minuten-Tick) macht {@link IdleFaceLive}.
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

/** „Wecker 07:00 · noch 7 h 12 min" — Weck-Uhrzeit + Restzeit (nie negativ). */
export function alarmLineText(alarm: ScheduledItem, nowMs: number): string {
  const remaining = fmtRemaining(Math.max(0, alarm.dueAtEpochMs - nowMs));
  return `Wecker ${dueClock(alarm.dueAtEpochMs)} · noch ${remaining}`;
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

/** ms → Sekunden mit deutscher Dezimal-Komma-Stelle: 1800 → „1,8 s". */
export function fmtP50(ms: number): string {
  return `${(ms / 1000).toFixed(1).replace('.', ',')} s`;
}

/** „14 Turns · p50 1,8 s · 0 Aussetzer" — nur echte Diary-Zahlen. */
export function todayTileValue(stats: DiaryTodayStats): string {
  const word = stats.turns === 1 ? 'Turn' : 'Turns';
  const p50 = stats.p50Ms !== null ? fmtP50(stats.p50Ms) : '—';
  return `${stats.turns} ${word} · p50 ${p50} · ${stats.errors} Aussetzer`;
}

const PLAN_KINDS: readonly ScheduledKind[] = ['TIMER', 'ALARM', 'REMINDER'];

/** „2 Timer · 1 Wecker" aus den aktiven Items — leer ⇒ „Nichts geplant". */
export function planTileValue(items: ScheduledItem[]): string {
  const parts = PLAN_KINDS.flatMap((kind) => {
    const count = items.filter((i) => i.kind === kind).length;
    if (count === 0) return [];
    return [`${count} ${count === 1 ? KIND_WORD[kind].one : KIND_WORD[kind].many}`];
  });
  return parts.length === 0 ? 'Nichts geplant' : parts.join(' · ');
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
 */
export function weatherTile(weather: WeatherTodayState | null): IdleTile {
  if (weather === null) {
    return {
      name: 'Wetter',
      honesty: 'pending',
      value: '—',
      note: 'Wird gerade gelesen.',
    };
  }
  switch (weather.kind) {
    case 'live':
      return {
        name: 'Wetter',
        honesty: 'live',
        value: weatherTileValue(weather.data),
        note: `Echte Messwerte von Open-Meteo für ${weather.data.label}.`,
      };
    case 'off':
      return {
        name: 'Wetter',
        honesty: 'pending',
        value: '—',
        note: 'Kommt — ehrlich leer statt erfunden. Wetter ist bei diesem Deploy ausgeschaltet.',
      };
    case 'unreachable':
      return {
        name: 'Wetter',
        honesty: 'live',
        value: '—',
        note: 'Wetter grad nicht lesbar — hier steht nichts Erfundenes.',
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
export function statusChips(health: HealthState, voice: OpsVoice | null): StatusChip[] {
  const chips: StatusChip[] = [];
  if (health === 'up') chips.push({ text: 'online', tone: 'ok' });
  else if (health === 'down') chips.push({ text: 'offline', tone: 'down' });
  else chips.push({ text: 'wird geprüft', tone: 'unknown' });
  if (voice) {
    chips.push(
      voice.cloud
        ? { text: 'Stimme: Cloud', tone: 'cloud' }
        : { text: 'Stimme: lokal', tone: 'local' },
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
  return (
    <article
      className={`tile idle__tile tile--${tile.honesty}`}
      data-status={tile.honesty}
      aria-disabled={tile.honesty !== 'live'}
    >
      <div className="tile__head">
        <span className="tile__name">{tile.name}</span>
        <span className="tile__pill">{tile.honesty === 'live' ? 'live' : 'kommt'}</span>
        {/* Kontextueller Settings-Anker (Cowork-Spec V1): springt in „Standort &
            Integrationen"/Wetter-Standort — nur an der Wetter-Kachel, wo das
            Setting wirklich wirkt. Dezent (--text-4, .ctxgear), kein Layout-
            Sprung — sitzt einfach nach der live/kommt-Pille. */}
        {onSettings && (
          <button
            type="button"
            className="ctxgear"
            onClick={onSettings}
            aria-label="Wetter-Einstellungen öffnen (Standort & Integrationen)"
            title="Wetter-Ort einstellen"
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
  const date = new Date(nowMs);
  const greeting = greetingForHour(date.getHours());
  const dateLine = date.toLocaleDateString('de-DE', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  const alarm = nextAlarm(scheduled);
  const chips = statusChips(health, voice);

  // Kachel „Heute": echte Diary-Zahlen — oder die ehrliche Lücke.
  const stats = turns !== null ? diaryTodayStats(turns, nowMs) : null;
  const heute: IdleTile = {
    name: 'Heute',
    honesty: 'live',
    value: stats === null ? '—' : stats.turns === 0 ? 'Noch kein Turn' : todayTileValue(stats),
    note:
      stats === null
        ? 'Diary nicht erreichbar — hier steht nichts Erfundenes.'
        : stats.turns === 0
          ? 'Ehrlich leer — nichts erfunden.'
          : 'Echte Zahlen aus deinem heutigen Verlauf.',
  };

  const geplant: IdleTile = {
    name: 'Geplant',
    honesty: 'live',
    value: planTileValue(scheduled),
    note: 'Echte aktive Timer, Wecker und Erinnerungen.',
  };

  // Kachel „Wetter": echte heutige Vorhersage — oder die ehrliche Lücke
  // (Deploy-OFF gestrichelt, nicht lesbar „—"). Nie Fake-Grade.
  const wetter = weatherTile(weather);

  return (
    <section className="idle" aria-label="Zuhause">
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
          <span className="idle__alarmtext">{alarmLineText(alarm, nowMs)}</span>
          <span
            className="idle__alarmtruth"
            title="Der Wecker lebt im lokalen Backend-Store, nicht in einer Cloud — er feuert auch ohne Internet."
          >
            klingelt auch offline
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
          <span className="idle__alarmtext">Kein Wecker gestellt</span>
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
