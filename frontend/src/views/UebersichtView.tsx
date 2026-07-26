import { API_BASE, hasToken } from '../api/config';
import { useHealth, type HealthState } from '../hooks/useHealth';
import { IdleFaceLive, diaryTodayStats, todayTileValue } from '../components/IdleFace';
import { VoiceOrb } from '../components/VoiceOrb';
import type { VoiceChatSession } from '../hooks/useVoiceChatSession';
import type { DiaryTurn } from '../hooks/useDiary';
import { useUiStrings } from '../i18n';

/**
 * Home — Andis Flur-Display (iPad im Flur). Bis 2026-07-26 war dieser Reiter
 * IdleFaceLive → VoiceOrb → eine komplette Entwickler-Landing (Hero + „Live
 * verdrahtet"/„Noch nicht verdrahtet"-Kacheln) — die Landing füllte die halbe
 * Seitenhöhe und war für einen Flur-Blick irrelevant. Andi-Auftrag 2026-07-26
 * (Flur-Display-Umbau): die Landing zog komplett um in {@link DiagnoseSection}
 * ans Ende von Aktivität (`views/AktivitaetView.tsx`) — Home besteht jetzt NUR
 * noch aus dem Idle-Gesicht + dem Sprach-Orb, s. {@link UebersichtViewLive}.
 *
 * Diese Datei behält den Namen (Reiter „Übersicht"/Home), trägt aber keine
 * eigene Seiten-Ansicht mehr — nur noch {@link DiagnoseSection} (die
 * umgezogene Landing, jetzt IMMER live: die drei „Noch nicht verdrahtet"-
 * Platzhalter sind ERSATZLOS gestrichen, Sidecar-Health lebt längst als
 * Ops-Pille, die anderen beiden waren leere Versprechen) + die
 * `UebersichtViewLive`-Verdrahtung des Home-Reiters.
 */

function fmtTime(ts: number | null, locale: string): string {
  return ts ? new Date(ts).toLocaleTimeString(locale) : '—';
}

interface DiagTile {
  name: string;
  value: string;
  note: string;
}

/** Eine Diagnose-Kachel — anders als die frühere `TileCard` gibt es hier nur noch „live" (kein Honesty-Wechsel mehr nötig). */
function DiagTileCard({ tile }: { tile: DiagTile }) {
  const { overview } = useUiStrings();
  return (
    <article className="tile tile--live" data-status="live">
      <div className="tile__head">
        <span className="tile__name">{tile.name}</span>
        <span className="tile__pill">{overview.live}</span>
      </div>
      <div className="tile__value">{tile.value}</div>
      <p className="tile__note">{tile.note}</p>
    </article>
  );
}

export interface DiagnoseSectionProps {
  state: HealthState;
  lastChecked: number | null;
  /** Diary-Zeilen für die ex-„Heute"-Kachel (aus IdleFace hierher umgezogen) — `null` = nicht erreichbar. */
  turns: DiaryTurn[] | null;
  nowMs: number;
}

/**
 * **Diagnose** — die umgezogene Entwickler-Landing, jetzt am Ende von
 * Aktivität statt auf Home: Verbindungs-Hero + vier echte Kacheln (Backend,
 * Chat-Turn, Auth-Token — alle drei byte-gleich zur früheren Übersicht — plus
 * „Heute" (echte Turn-Statistik, zog aus IdleFace hierher). Rein prop-getrieben
 * → ohne DOM/Fetch testbar; verdrahtet wird sie in {@link ../views/AktivitaetView.tsx#AktivitaetViewLive}
 * mit `useHealth()` (bereits dort für den Health-Verlauf verdrahtet — keine
 * zweite Poll-Quelle).
 */
export function DiagnoseSection({ state, lastChecked, turns, nowMs }: DiagnoseSectionProps) {
  const { overview, activity, idleFace, locale } = useUiStrings();
  const heroByState: Record<HealthState, { title: string; sub: string }> = {
    up: { title: overview.heroUpTitle, sub: overview.heroUpSub },
    down: { title: overview.heroDownTitle, sub: overview.heroDownSub },
    unknown: { title: overview.heroUnknownTitle, sub: overview.heroUnknownSub },
  };
  const hero = heroByState[state];

  const stats = turns !== null ? diaryTodayStats(turns, nowMs) : null;
  const heute: DiagTile = {
    name: idleFace.heute.name,
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

  const tiles: DiagTile[] = [
    { name: overview.backend, value: API_BASE, note: overview.backendNote },
    { name: overview.chatTurn, value: overview.liveStreaming, note: overview.chatTurnNote },
    {
      name: overview.authToken,
      value: hasToken() ? overview.set : overview.missing,
      note: hasToken() ? overview.authSetNote : overview.authMissingNote,
    },
    heute,
  ];

  return (
    <>
      <h2 className="ueber__sec">{activity.diagnoseTitle}</h2>
      <p className="ueber__sechint">{activity.diagnoseHint}</p>
      <div className={`hero hero--${state}`} data-health={state} role="status" aria-live="polite">
        <span className="hero__dot" aria-hidden="true" />
        <div className="hero__text">
          <strong className="hero__title">{hero.title}</strong>
          <span className="hero__sub">{hero.sub}</span>
        </div>
        <div className="hero__aside">
          <code className="hero__base">{API_BASE}</code>
          <span className="hero__time">{overview.lastChecked(fmtTime(lastChecked, locale))}</span>
        </div>
      </div>
      <div className="tiles">
        {tiles.map((t) => (
          <DiagTileCard key={t.name} tile={t} />
        ))}
      </div>
    </>
  );
}

/**
 * Live-Container des Home-Reiters: das Aoi-Idle-Gesicht + der Sprach-Orb —
 * seit dem Flur-Display-Umbau (2026-07-26) sonst NICHTS mehr (die frühere
 * Entwickler-Landing lebt jetzt in {@link DiagnoseSection} auf Aktivität).
 * EIN Health-Poller fürs Idle-Gesicht (health als Prop — keine zweite
 * Poll-Quelle). KEIN `onOpenSettings` mehr (Andi-Korrektur 26.07): das
 * Idle-Gesicht trug ein eigenes Settings-Zahnrad an der Wetterlage, das die
 * Top-Nav oben rechts schon abdeckt — die Dopplung ist weg.
 */
export function UebersichtViewLive({
  session,
}: {
  /**
   * Die geteilte Voice-Chat-Session aus App.tsx (dieselbe, die auch der
   * Chat-Reiter rendert — kein zweiter Verlauf). Optional: fehlt sie (z. B.
   * in Tests, die nur `UebersichtViewLive` isoliert prüfen), rendert diese
   * Live-Ansicht schlicht ohne Orb statt zu crashen.
   */
  session?: VoiceChatSession;
} = {}) {
  const { state } = useHealth();
  return (
    <>
      <IdleFaceLive health={state} />
      {session && <VoiceOrb session={session} />}
    </>
  );
}
