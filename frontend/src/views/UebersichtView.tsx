import { API_BASE, hasToken } from '../api/config';
import { useHealth, type HealthState } from '../hooks/useHealth';
import { IdleFaceLive } from '../components/IdleFace';
import { VoiceOrb } from '../components/VoiceOrb';
import type { VoiceChatSession } from '../hooks/useVoiceChatSession';
import type { SettingsAnchorId, SettingsCategoryId } from '../components/SettingsPanel';

/**
 * Status-first Landing — Andis „vom Chat zum Zuhause".
 *
 * Ehrlichkeits-Achse, strikt:
 *  - 🟢 `live`    = echt verdrahtet, der Wert kommt aus einem realen Endpoint.
 *                  (Ein echter Wert darf auch „schlecht" sein — offline/fehlt.)
 *  - 🔵 `pending` = ehrlicher Platzhalter. Das 0.8-Backend liefert die Daten
 *                  NOCH NICHT. Solche Kacheln zeigen bewusst KEINEN Zustand
 *                  („—"), nie erfundenes Grün.
 *
 * Diese Komponente ist rein prop-getrieben (kein Hook, kein Netz) und dadurch
 * ohne DOM/Fetch unit-testbar. Den echten Health-Hook verdrahtet
 * {@link UebersichtViewLive}.
 */

export type Honesty = 'live' | 'pending';

interface Tile {
  name: string;
  honesty: Honesty;
  /** live: echter Wert · pending: bewusst „—" (kein Fake). */
  value: string;
  note: string;
}

const HERO: Record<HealthState, { title: string; sub: string }> = {
  up: { title: 'Hoshi ist online', sub: 'Verbindung steht.' },
  down: { title: 'Hoshi ist offline', sub: 'Verbindung steht gerade nicht.' },
  unknown: { title: 'Status wird geprüft …', sub: 'erste Health-Antwort steht noch aus' },
};

/** Ehrliche Platzhalter — was das 0.8-Backend noch NICHT über die API exponiert. */
const PENDING: Tile[] = [
  {
    name: 'Sidecar-Health',
    honesty: 'pending',
    value: '—',
    note: 'Supervisor-/Sidecar-Status ist noch nicht über die API exponiert. Kommt, sobald das Backend ihn liefert.',
  },
  {
    name: 'Sprach-Stats',
    honesty: 'pending',
    value: '—',
    note: 'Voice/TTS-Telemetrie (Latenz, Sprecher) ist noch nicht angebunden. Kommt später.',
  },
  {
    name: 'Geräte',
    honesty: 'pending',
    value: '—',
    note: 'Geräte-/Satelliten-Registry ist noch nicht verdrahtet. Kommt, sobald die Route steht.',
  },
];

const PILL: Record<Honesty, string> = { live: 'live', pending: 'nicht verdrahtet' };

function fmtTime(ts: number | null): string {
  return ts ? new Date(ts).toLocaleTimeString('de-DE') : '—';
}

/**
 * Bewusst OHNE 🟢/🔵-Emoji-Icon (Emoji-Sweep 2026-07-06): das `tile__pill`
 * („live"/„nicht verdrahtet") + der gestrichelte tile--pending-Rahmen sagen den
 * Zustand schon — das Emoji war ein Zustands-Duplikat (Muster: IdleTileCard).
 */
function TileCard({ tile }: { tile: Tile }) {
  const live = tile.honesty === 'live';
  return (
    <article className={`tile tile--${tile.honesty}`} data-status={tile.honesty} aria-disabled={!live}>
      <div className="tile__head">
        <span className="tile__name">{tile.name}</span>
        <span className="tile__pill">{PILL[tile.honesty]}</span>
      </div>
      <div className="tile__value">{tile.value}</div>
      <p className="tile__note">{tile.note}</p>
    </article>
  );
}

export interface UebersichtViewProps {
  state: HealthState;
  lastChecked: number | null;
}

export function UebersichtView({ state, lastChecked }: UebersichtViewProps) {
  const hero = HERO[state];

  // 🟢 Echt verdrahtete Kacheln — Werte stammen aus realen Endpoints/Config.
  const live: Tile[] = [
    {
      name: 'Backend',
      honesty: 'live',
      value: API_BASE,
      note: 'Adresse, mit der sich diese Oberfläche verbindet.',
    },
    {
      name: 'Chat-Turn',
      honesty: 'live',
      value: 'Live-Streaming',
      note: 'Echte Antwort in Echtzeit, kein Mock.',
    },
    {
      name: 'Auth-Token',
      honesty: 'live',
      value: hasToken() ? 'gesetzt' : 'fehlt',
      note: hasToken()
        ? 'Dein Gerät ist angemeldet — geschützte Bereiche sind freigeschaltet.'
        : 'Nicht angemeldet — geschützte Bereiche bleiben gesperrt.',
    },
  ];

  return (
    <section className="ueber">
      <header className="ueber__head">
        <h1 className="ueber__title">Übersicht</h1>
        <p className="ueber__lede">
          Status-first: ein ehrlicher Blick aufs Zuhause. Was läuft, kommt LIVE aus den
          echten Quellen — was noch fehlt, ist klar als „noch nicht verdrahtet" markiert,
          nie grün gefärbt.
        </p>
      </header>

      <div className={`hero hero--${state}`} data-health={state} role="status" aria-live="polite">
        <span className="hero__dot" aria-hidden="true" />
        <div className="hero__text">
          <strong className="hero__title">{hero.title}</strong>
          <span className="hero__sub">{hero.sub}</span>
        </div>
        <div className="hero__aside">
          <code className="hero__base">{API_BASE}</code>
          <span className="hero__time">zuletzt geprüft {fmtTime(lastChecked)}</span>
        </div>
      </div>

      <h2 className="ueber__sec">Live verdrahtet</h2>
      <div className="tiles">
        {live.map((t) => (
          <TileCard key={t.name} tile={t} />
        ))}
      </div>

      <h2 className="ueber__sec">Noch nicht verdrahtet</h2>
      <p className="ueber__sechint">
        Diese Kacheln zeigen bewusst keinen Zustand — das 0.8-Backend exponiert die Daten
        noch nicht. Kein erfundenes Grün.
      </p>
      <div className="tiles">
        {PENDING.map((t) => (
          <TileCard key={t.name} tile={t} />
        ))}
      </div>
    </section>
  );
}

/**
 * Live-Container: das Aoi-Idle-Gesicht (Spec §2, „Zuhause"-Layout) oben, dann
 * der Sprach-Orb (Andi-Auftrag 19.07 — Übersicht ist jetzt Reiter 1/Start-
 * Ansicht, der Orb macht sie zum stimmig integrierten Sprach-Einstieg),
 * darunter die ehrliche Status-Landing. EIN Health-Poller für beide (health
 * als Prop ans Idle-Gesicht — keine zweite Poll-Quelle).
 */
export function UebersichtViewLive({
  onOpenSettings,
  session,
}: {
  /** Deep-Link in den Settings-Drawer (App.tsx `openSettings`) — reicht an die
   * Wetter-Kachel im Idle-Gesicht durch, wo der Standort-Anker sitzt. */
  onOpenSettings?: (category: SettingsCategoryId, anchor?: SettingsAnchorId) => void;
  /**
   * Die geteilte Voice-Chat-Session aus App.tsx (dieselbe, die auch der
   * Chat-Reiter rendert — kein zweiter Verlauf). Optional: fehlt sie (z. B.
   * in Tests, die nur `UebersichtView` isoliert prüfen), rendert diese Live-
   * Ansicht schlicht ohne Orb statt zu crashen.
   */
  session?: VoiceChatSession;
} = {}) {
  const { state, lastChecked } = useHealth();
  return (
    <>
      <IdleFaceLive health={state} onOpenSettings={onOpenSettings} />
      {session && <VoiceOrb session={session} />}
      <UebersichtView state={state} lastChecked={lastChecked} />
    </>
  );
}
