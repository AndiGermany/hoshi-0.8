import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchHomeRegistry,
  type HomeRegistryArea,
  type HomeRegistryEntity,
  type HomeRegistryState,
} from '../api/homeRegistry';
import {
  assignEntityArea,
  fetchHomeEditStatus,
  HomeEditLockedError,
  HomeEditValidationError,
} from '../api/homeEdit';
import { DomainGlyph } from '../components/domainGlyphs';
import { WarnGlyph } from '../components/icons';

/** Eine Raum-Option im Picker (aus dem Registry-Snapshot abgeleitet). */
export interface AreaOption {
  areaId: string;
  label: string;
}

/**
 * Der Edit-Vertrag von Scheibe 2 (SCHREIBEN), den {@link RaeumeView} prop-getrieben
 * bekommt — der Reiter selbst bleibt so ohne Hook/Netz testbar (die Live-Naht sitzt
 * in {@link RaeumeViewLive}). `enabled:false`/`undefined` ⇒ KEIN Picker (Flag zu,
 * byte-neutral). KEIN optimistisches UI: `onAssign` löst PUT + Registry-Reload aus,
 * die Karte wandert erst mit dem frischen Read.
 */
export interface RaeumeEdit {
  enabled: boolean;
  areas: AreaOption[];
  onAssign: (entityId: string, areaId: string) => void;
  /** Die Entity, deren Zuordnung gerade läuft (Picker disabled) — sonst `null`. */
  busyEntityId?: string | null;
  /** Die Entity mit dem letzten Fehler (ehrliche Meldung an der Zeile) — sonst `null`. */
  errorEntityId?: string | null;
  errorMessage?: string | null;
}

/**
 * Räume — Andis „vom Chat zum Zuhause", die räumliche Achse.
 *
 * Scheibe 1 des Geräte-Zuordnungs-Konzepts (`.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`) macht diesen Reiter READ-ONLY echt:
 * `GET /api/v1/home/registry` liefert die echten HA-Areas mit ihren Geräten —
 * „HA bleibt die eine Wahrheit, Hoshi wird ihr freundlicher Editor". Diese
 * Scheibe editiert NICHTS (kein Schreib-Endpoint) — nur Ansicht.
 *
 * Ehrlichkeit, strikt (wie {@link UebersichtView}) — VIER Zustände, nie erfunden:
 *  - `null` (erster Fetch läuft) — dieselbe gestrichelte Leerkarte wie `off`,
 *    nur mit „wird gerade geladen"-Text (Muster `weatherTile`-Ladezustand).
 *  - `off` (404, `HOSHI_HA_ENABLED` beim Deploy aus) — EXAKT die bestehende
 *    ehrliche Skizze („kommt, sobald Home Assistant verdrahtet ist") bleibt
 *    unverändert bestehen, bis die Naht scharf ist.
 *  - `unreachable` (401/502/5xx/Netz) — die Naht ist verdrahtet, aber HA
 *    antwortet gerade nicht: „gerade nicht erreichbar", nie Fake-Räume.
 *  - `live` — echte Raum-Karten (Name, Geräte-Liste mit Domain-Glyph) +
 *    IMMER eine „Nicht zugeordnet"-Karte zuletzt — genau die Entities OHNE
 *    HA-Area sichtbar statt versteckt (die „tado-Lücke").
 *
 * Rein prop-getrieben (kein Hook, kein Netz) → via renderToStaticMarkup ohne
 * DOM/Fetch testbar. Live-Verdrahtung (der Poll-Hook): {@link RaeumeViewLive}.
 */

/** Die Skizzen-Knoten sind bewusst KONZEPT, kein Bestand. Nie als „da" gelabelt. */
const SKETCH_ROOMS = ['Raum', 'Raum', 'Raum', 'Raum'] as const;

/** Polar → kartesisch auf einem 200×200-Viewbox-Kreis um (100,100). */
function orbit(i: number, total: number, r: number): { x: number; y: number } {
  const a = (i / total) * Math.PI * 2 - Math.PI / 2; // Start oben
  return { x: 100 + r * Math.cos(a), y: 100 + r * Math.sin(a) };
}

function HoshiSketch() {
  const r = 74;
  return (
    <svg
      className="sketch"
      viewBox="0 0 200 200"
      role="img"
      aria-label="Skizze: Hoshi im Zentrum, noch leere Raum-Platzhalter ringsum"
    >
      {/* Verbindungslinien — gestrichelt = noch nicht verdrahtet. */}
      {SKETCH_ROOMS.map((_, i) => {
        const p = orbit(i, SKETCH_ROOMS.length, r);
        return (
          <line
            key={`l${i}`}
            className="sketch__link"
            x1={100}
            y1={100}
            x2={p.x}
            y2={p.y}
          />
        );
      })}

      {/* Raum-Platzhalter — gestrichelt, leer, generisch. */}
      {SKETCH_ROOMS.map((label, i) => {
        const p = orbit(i, SKETCH_ROOMS.length, r);
        return (
          <g key={`n${i}`}>
            <circle className="sketch__room" cx={p.x} cy={p.y} r={20} />
            <text className="sketch__roomlabel" x={p.x} y={p.y + 4} textAnchor="middle">
              {label}
            </text>
          </g>
        );
      })}

      {/* Hoshi — das eine, das es schon gibt. */}
      <circle className="sketch__hub" cx={100} cy={100} r={26} />
      <text className="sketch__hublabel" x={100} y={104} textAnchor="middle">
        Hoshi
      </text>
    </svg>
  );
}

/**
 * Ein dezenter Raum-Picker (Select mit Areas, SVG-frei, Yoru-Tokens) — nur
 * gerendert, wenn der Edit-Flag an ist. `onChange` löst `onAssign` aus; während
 * der Zuweisung ist er disabled (kein zweiter Klick, kein optimistisches Wandern).
 *
 * **Controlled, nicht uncontrolled** (bewusst KEIN `defaultValue`): der Select
 * setzt sich SOFORT im `onChange` selbst auf den Platzhalter zurück, statt den
 * gewählten Wert hängen zu lassen. Grund: ein natives `<select>` feuert `change`
 * nur, wenn sich der gewählte Index wirklich ändert — bliebe die zuletzt
 * gewählte Area sichtbar ausgewählt (uncontrolled Default-Verhalten), würde ein
 * ERNEUTES Wählen DERSELBEN Area nach einem fehlgeschlagenen Write STUMM
 * bleiben (kein zweites `change`-Event, kein Retry-PUT) — genau der Fall, den
 * ein Nutzer nach einem Fehler zuerst versucht.
 */
function RoomPicker({ entity, edit }: { entity: HomeRegistryEntity; edit: RaeumeEdit }) {
  const busy = edit.busyEntityId === entity.entityId;
  const [value, setValue] = useState('');
  return (
    <span className="room__picker">
      <select
        className="room__pickerselect"
        value={value}
        disabled={busy}
        aria-label={`Raum für ${entity.name} wählen`}
        onChange={(e) => {
          const areaId = e.currentTarget.value;
          setValue(''); // sofort zurück auf den Platzhalter (s. KDoc) — vor dem Assign-Aufruf.
          if (areaId) edit.onAssign(entity.entityId, areaId);
        }}
      >
        <option value="" disabled>
          {busy ? 'wird zugeordnet…' : 'Raum wählen…'}
        </option>
        {edit.areas.map((a) => (
          <option key={a.areaId} value={a.areaId}>
            {a.label}
          </option>
        ))}
      </select>
    </span>
  );
}

/**
 * Eine Geräte-Zeile: Domain-Glyph · Name · Domain-Beschriftung · Label-Chips.
 * `assignable` + aktiver Edit-Flag ⇒ zusätzlich ein {@link RoomPicker}; ein
 * Zeilen-Fehler (letzter fehlgeschlagener Write dieser Entity) erscheint ehrlich
 * als `role="alert"` unter der Zeile.
 */
function DeviceRow({
  entity,
  edit,
  assignable = false,
}: {
  entity: HomeRegistryEntity;
  edit?: RaeumeEdit;
  assignable?: boolean;
}) {
  const showPicker = assignable && edit?.enabled === true;
  const rowError = edit && edit.errorEntityId === entity.entityId ? edit.errorMessage : null;
  return (
    <li className="room__device">
      <DomainGlyph domain={entity.domain} className="room__deviceicon" />
      <span className="room__devicename">{entity.name}</span>
      <span className="room__devicedomain">{entity.domain}</span>
      {entity.labels.length > 0 && (
        <span className="room__devicelabels">
          {entity.labels.map((l) => (
            <span className="room__labelchip" key={l}>
              {l}
            </span>
          ))}
        </span>
      )}
      {showPicker && edit && <RoomPicker entity={entity} edit={edit} />}
      {rowError && (
        <p className="room__pickererror" role="alert">
          {rowError}
        </p>
      )}
    </li>
  );
}

/** Eine Raum-Karte: Name, Geräte-Anzahl-Pille, Geräte-Liste (oder ehrlich „noch keine Geräte"). */
function RoomCard({ area }: { area: HomeRegistryArea }) {
  return (
    <article className="tile room tile--live" data-status="live">
      <div className="tile__head">
        <span className="tile__name">{area.label}</span>
        <span className="tile__pill">{area.entities.length} Gerät{area.entities.length === 1 ? '' : 'e'}</span>
      </div>
      {area.entities.length === 0 ? (
        <p className="room__empty">Noch keine Geräte in diesem Raum.</p>
      ) : (
        <ul className="room__devices">
          {area.entities.map((e) => (
            <DeviceRow entity={e} key={e.entityId} />
          ))}
        </ul>
      )}
    </article>
  );
}

/**
 * Die „Nicht zugeordnet"-Karte — IMMER zuletzt, IMMER sichtbar (auch bei 0
 * Einträgen): sie macht die HA-Zuordnungs-Lücke ehrlich sichtbar statt sie zu
 * verstecken, und bestätigt ehrlich, wenn es gerade keine Lücke gibt.
 */
function UnassignedCard({ entities, edit }: { entities: HomeRegistryEntity[]; edit?: RaeumeEdit }) {
  const hint =
    entities.length === 0
      ? 'Aktuell hat jedes gemeldete Gerät einen Raum in Home Assistant.'
      : edit?.enabled
        ? 'Diese Geräte haben in Home Assistant noch keinen Raum. Wähle rechts einen Raum — gespeichert wird direkt in Home Assistant, dort jederzeit sichtbar und umkehrbar.'
        : 'Diese Geräte haben in Home Assistant noch keinen Raum. Zuordnen geht bislang nur direkt in Home Assistant — Hoshi zeigt die Lücke hier nur ehrlich an.';
  return (
    <article className="tile room room--unassigned tile--live" data-status="live">
      <div className="tile__head">
        {/* Amber NUR bei einer echten Lücke (Mockup 11b, Andi-abgenommen) — ohne
            Lücke bleibt der Titel neutral, sonst wäre „aufmerksam" ein Dauerzustand. */}
        <span className={`tile__name${entities.length > 0 ? ' room__name--gap' : ''}`}>Nicht zugeordnet</span>
        <span className="tile__pill">{entities.length}</span>
      </div>
      <p className="room__hint">{hint}</p>
      {entities.length > 0 && (
        <ul className="room__devices">
          {entities.map((e) => (
            <DeviceRow entity={e} key={e.entityId} edit={edit} assignable />
          ))}
        </ul>
      )}
    </article>
  );
}

/** Gestrichelte Leerkarte für `off`/`null` — dieselbe Karte, zwei ehrliche Texte. */
function PendingCard({ note }: { note: string }) {
  return (
    <article className="tile tile--pending empty" data-status="pending" aria-disabled="true">
      <div className="tile__head">
        <span className="tile__name">Räume &amp; Geräte</span>
        <span className="tile__pill">nicht verdrahtet</span>
      </div>
      <div className="tile__value">—</div>
      <p className="tile__note">{note}</p>
    </article>
  );
}

/** Solide Karte für `unreachable` — die Naht existiert, HA antwortet gerade nicht. */
function UnreachableCard() {
  return (
    <article className="tile tile--unreachable" data-status="unreachable">
      <div className="tile__head">
        <span className="tile__name">Räume &amp; Geräte</span>
        <span className="tile__pill">nicht erreichbar</span>
      </div>
      <div className="tile__value">
        <WarnGlyph className="room__warnicon" /> —
      </div>
      <p className="tile__note">
        Home Assistant ist gerade nicht erreichbar — hier steht nichts Erfundenes. Es
        versucht es automatisch erneut.
      </p>
    </article>
  );
}

export interface RaeumeViewProps {
  /** `null` = erster Fetch läuft; sonst der ehrliche Zustand des Registry-Reads. */
  state: HomeRegistryState | null;
  /** Scheibe 2: der Edit-Vertrag. `undefined`/`enabled:false` ⇒ read-only (kein Picker). */
  edit?: RaeumeEdit;
}

export function RaeumeView({ state, edit }: RaeumeViewProps) {
  return (
    <section className="ueber">
      <header className="ueber__head">
        <h1 className="ueber__title">Räume</h1>
        <p className="ueber__lede">
          {edit?.enabled
            ? 'Das Zuhause, räumlich gedacht — direkt aus Home Assistant gelesen. Nicht zugeordnete Geräte kannst du hier einem Raum geben; gespeichert wird in Home Assistant, dort jederzeit sichtbar und umkehrbar.'
            : 'Das Zuhause, räumlich gedacht — direkt aus Home Assistant gelesen. HA bleibt die eine Wahrheit; Räume ändern geht bislang nur dort (Zuordnen im Hoshi-UI kommt in einer späteren Scheibe).'}
        </p>
      </header>

      {state === null && <PendingCard note="Wird gerade gelesen." />}

      {state !== null && state.kind === 'off' && (
        <>
          <PendingCard note="Räume kommen, sobald Home Assistant verdrahtet ist. Das 0.8-Backend exponiert heute noch keine Geräte- oder Raum-Registry — darum steht hier bewusst nichts Erfundenes." />
          <h2 className="ueber__sec">Die Idee</h2>
          <p className="ueber__sechint">
            Eine ruhige Skizze der Verbindung — kein echter Bestand. Die Platzhalter sind
            gestrichelt und leer; echte Räume erscheinen erst, wenn die Registry steht.
          </p>
          <div className="sketch__wrap">
            <HoshiSketch />
          </div>
        </>
      )}

      {state !== null && state.kind === 'unreachable' && <UnreachableCard />}

      {state !== null && state.kind === 'live' && (
        <div className="tiles rooms">
          {state.data.areas.map((area) => (
            <RoomCard area={area} key={area.areaId} />
          ))}
          <UnassignedCard entities={state.data.unassigned} edit={edit} />
        </div>
      )}
    </section>
  );
}

/** Ehrliche Fehlermeldung aus einem fehlgeschlagenen Write (Server-Text bevorzugt). */
function editErrorMessage(e: unknown): string {
  if (e instanceof HomeEditLockedError || e instanceof HomeEditValidationError) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return 'Zuordnung fehlgeschlagen — bitte später erneut versuchen.';
}

export interface RaeumeViewLiveProps {
  /** Injizierbar für Tests; Default = die echten Clients (Netz). */
  loadRegistry?: (signal?: AbortSignal) => Promise<HomeRegistryState>;
  loadStatus?: (signal?: AbortSignal) => Promise<boolean>;
  assign?: (entityId: string, areaId: string) => Promise<unknown>;
  /** Sanfter Registry-Poll (Default 5 min, wie zuvor der Hook). */
  intervalMs?: number;
}

/**
 * Live-Container von Scheibe 1+2: pollt die Registry, liest den Edit-Flag und
 * verdrahtet die Zuweisung. **KEIN optimistisches UI** — `onAssign` ruft PUT und
 * lädt bei Erfolg die Registry neu (read-first: die Karte wandert erst mit dem
 * frischen HA-Stand). Fehler ⇒ ehrliche Zeilen-Meldung, die Karte bleibt.
 * API-Funktionen sind injizierbar (Muster {@link NightModeSection}), damit der
 * Flow ohne Live-Backend testbar ist.
 */
export function RaeumeViewLive({
  loadRegistry = fetchHomeRegistry,
  loadStatus = fetchHomeEditStatus,
  assign = assignEntityArea,
  intervalMs = 5 * 60 * 1000,
}: RaeumeViewLiveProps = {}) {
  const [state, setState] = useState<HomeRegistryState | null>(null);
  const [editEnabled, setEditEnabled] = useState(false);
  const [busyEntityId, setBusyEntityId] = useState<string | null>(null);
  const [error, setError] = useState<{ entityId: string; message: string } | null>(null);
  const aliveRef = useRef(true);

  const reload = useCallback(
    async (signal?: AbortSignal): Promise<void> => {
      const next = await loadRegistry(signal);
      if (aliveRef.current) setState(next);
    },
    [loadRegistry],
  );

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void reload(controller.signal);
    void loadStatus(controller.signal).then((on) => {
      if (aliveRef.current) setEditEnabled(on);
    });
    const id = window.setInterval(() => void reload(), intervalMs);
    return () => {
      aliveRef.current = false;
      controller.abort();
      window.clearInterval(id);
    };
  }, [reload, loadStatus, intervalMs]);

  const onAssign = useCallback(
    (entityId: string, areaId: string): void => {
      setBusyEntityId(entityId);
      setError(null);
      void assign(entityId, areaId)
        .then(() => reload()) // read-first: neu laden ⇒ die Karte wandert echt
        .catch((e: unknown) => {
          if (aliveRef.current) setError({ entityId, message: editErrorMessage(e) });
        })
        .finally(() => {
          if (aliveRef.current) setBusyEntityId(null);
        });
    },
    [assign, reload],
  );

  const edit: RaeumeEdit | undefined =
    editEnabled && state?.kind === 'live'
      ? {
          enabled: true,
          areas: state.data.areas.map((a) => ({ areaId: a.areaId, label: a.label })),
          onAssign,
          busyEntityId,
          errorEntityId: error?.entityId ?? null,
          errorMessage: error?.message ?? null,
        }
      : undefined;

  return <RaeumeView state={state} edit={edit} />;
}
