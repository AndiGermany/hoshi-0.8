import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchHomeRegistry,
  type HomeRegistryArea,
  type HomeRegistryEntity,
  type HomeRegistrySnapshot,
  type HomeRegistryState,
} from '../api/homeRegistry';
import {
  assignEntityArea,
  fetchHomeEditStatus,
  HomeEditLockedError,
  HomeEditValidationError,
} from '../api/homeEdit';
import { WarnGlyph } from '../components/icons';
import { RoomDeviceRow } from '../components/RoomDeviceRow';
import { RoomsToolbar } from '../components/RoomsToolbar';
import { InboxCard } from '../components/RoomsInbox';
import { matchesDomainFilter, matchesRoomSearch, type DomainFilter } from '../components/roomsFilter';
import { isRoomRelevant } from '../components/roomsRelevance';
import { sortRoomsByUsage, splitSilentRooms } from '../components/roomsSort';
import { useUiStrings } from '../i18n';

/**
 * Der Edit-Vertrag von Scheibe 2 (SCHREIBEN) lebt jetzt in `components/
 * roomsEdit.ts` (kein Zirkel-Import zwischen dieser View und den neuen
 * Scheibe-1-Komponenten `RoomsInbox`/`RoomsToolbar`) — hier nur re-exportiert,
 * damit bestehende Importe (`import type { RaeumeEdit } from '../views/
 * RaeumeView'`) unverändert gültig bleiben.
 */
export type { AreaOption, RaeumeEdit } from '../components/roomsEdit';
import type { RaeumeEdit } from '../components/roomsEdit';
import { startVisiblePolling } from '../hooks/visiblePolling';

/**
 * Räume — Andis „vom Chat zum Zuhause", die räumliche Achse.
 *
 * Scheibe 1 des Geräte-Zuordnungs-Konzepts (`.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`) machte diesen Reiter READ-ONLY echt:
 * `GET /api/v1/home/registry` liefert die echten HA-Areas mit ihren Geräten.
 *
 * Scheibe 1 des RÄUME-VERWALTUNGS-Konzepts (`.orch-bus/inbox/20260727-2223-
 * cowork-raeume-verwaltung-konzept.md`, §6) baut das bei 200 Entitäten
 * übersichtlich: Kopfzeile mit der echten Zuordnungs-Wahrheit ({@link
 * RoomsToolbar}), Suche + vier Domänen-Chips als Filter (Raum bleibt die
 * einzige Gruppierungs-Achse — Domäne ist NUR ein Filter, kein zweiter
 * Baum), und die alte „Nicht zugeordnet"-Karte wird zur „Braucht dich"-Inbox
 * ({@link InboxCard}) — vorbelegter Raum-Vorschlag, aber NIE automatisch
 * geschrieben. Läuft komplett auf den bestehenden Endpoints (`GET /home/
 * registry` + `PUT …/area`), KEINE neue Naht.
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
 *    IMMER eine „Braucht dich"-Inbox ZUERST (Reihenfolge nach Wiederkehr,
 *    Konzept §2) — genau die Entities OHNE HA-Area sichtbar statt versteckt
 *    (die „tado-Lücke").
 *
 * **Raum-Übersicht (Andi-Auftrag 2026-08-11, „so ist es nur eine lange
 * Liste"):** die Raum-Karten stehen jetzt in einem responsiven Grid statt
 * volle Breite untereinander (CSS, `.rooms` in `index.css`) und sind nach
 * Nutzung absteigend, dann Geräteanzahl + Name sortiert ({@link sortRoomsByUsage}
 * in `components/roomsSort.ts` — Konzept-Pfad 1(a): die Nutzungs-Naht liefert
 * `recentCommands` als echte 14-Tage-Zählung, s. dortiges KDoc). Räume ohne Geräte-Aktivität
 * (0/1 Gerät) falten sich in ein zugeklapptes „Stille Räume"-Fach ans Ende
 * ({@link splitSilentRooms}), NUR solange kein Filter aktiv ist — eine
 * Etagen-Gruppierung ist BEWUSST NICHT gebaut (der aktuelle HA-Registry-
 * Payload/das Jinja-Template in `HaHomeRegistryAdapter` liefert keine
 * Floor-Daten; das war im Geräte-Zuordnungs-Konzept ausdrücklich als
 * „später — Hypothese" zurückgestellt, s. Rückgabe der bauenden Scheibe).
 *
 * Rein prop-getrieben (kein Netz) → via renderToStaticMarkup ohne DOM/Fetch
 * testbar (Suche/Filter/Einklapp-Zustand sind lokaler UI-State, der
 * renderToStaticMarkup nicht stört — es wird nur der Startwert gerendert).
 * Live-Verdrahtung (der Poll-Hook): {@link RaeumeViewLive}.
 */

/** Die Skizzen-Knoten sind bewusst KONZEPT, kein Bestand. Nie als „da" gelabelt. */
/** Polar → kartesisch auf einem 200×200-Viewbox-Kreis um (100,100). */
function orbit(i: number, total: number, r: number): { x: number; y: number } {
  const a = (i / total) * Math.PI * 2 - Math.PI / 2; // Start oben
  return { x: 100 + r * Math.cos(a), y: 100 + r * Math.sin(a) };
}

function HoshiSketch() {
  const { rooms } = useUiStrings();
  const sketchRooms = Array.from({ length: 4 }, () => rooms.sketchRoom);
  const r = 74;
  return (
    <svg
      className="sketch"
      viewBox="0 0 200 200"
      role="img"
      aria-label={rooms.sketchAria}
    >
      {/* Verbindungslinien — gestrichelt = noch nicht verdrahtet. */}
      {sketchRooms.map((_, i) => {
        const p = orbit(i, sketchRooms.length, r);
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
      {sketchRooms.map((label, i) => {
        const p = orbit(i, sketchRooms.length, r);
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

/** Ab dieser Zeilenzahl klappt eine Raum-Karte den Rest ein (Konzept §6 S1, Andi-Vorgabe „ab 8 Zeilen"). */
const ROOM_COLLAPSE_AT = 8;

/**
 * Eine Raum-Karte — kompakt fürs Grid (Konzept §2 „GRID STATT LISTE"): Titel
 * + „x von y"-Pille + eingeklappte Geräte-Liste (oder ehrlich „noch keine
 * Geräte"). Die Pille zeigt die AKTUELL SICHTBARE (gefilterte) Anzahl „von"
 * der vollen Raum-Anzahl (`area.entities.length`) — ohne aktiven Filter sind
 * beide Zahlen gleich, dann bleibt die knappere `deviceCount`-Form stehen
 * statt eines redundanten „12 von 12". Ab {@link ROOM_COLLAPSE_AT} sichtbaren
 * Zeilen bleibt der Rest eingeklappt, bis Andi „die übrigen n zeigen"
 * antippt (Muster `ScheduledPanel`-Toggle: `aria-expanded` + Chevron-Glyph
 * in eigenem `aria-hidden`-Span).
 */
function RoomCard({ area, visibleEntities }: { area: HomeRegistryArea; visibleEntities: HomeRegistryEntity[] }) {
  const { rooms } = useUiStrings();
  const [expanded, setExpanded] = useState(false);
  const showAll = expanded || visibleEntities.length <= ROOM_COLLAPSE_AT;
  const shown = showAll ? visibleEntities : visibleEntities.slice(0, ROOM_COLLAPSE_AT);
  const hiddenCount = visibleEntities.length - shown.length;
  const totalCount = area.entities.length;
  const countLabel =
    visibleEntities.length === totalCount
      ? rooms.deviceCount(totalCount)
      : rooms.deviceCountOfTotal(visibleEntities.length, totalCount);
  return (
    <article className="tile room tile--live" data-status="live">
      <div className="tile__head">
        <span className="tile__name">{area.label}</span>
        <span className="tile__pill">{countLabel}</span>
      </div>
      {visibleEntities.length === 0 ? (
        <p className="room__empty">{rooms.roomEmpty}</p>
      ) : (
        <>
          <ul className="room__devices">
            {shown.map((e) => (
              <RoomDeviceRow entity={e} key={e.entityId} />
            ))}
          </ul>
          {hiddenCount > 0 && (
            <button
              type="button"
              className="room__more"
              aria-expanded={false}
              onClick={() => setExpanded(true)}
            >
              <span aria-hidden="true">▸</span> {rooms.roomShowMore(hiddenCount)}
            </button>
          )}
          {expanded && visibleEntities.length > ROOM_COLLAPSE_AT && (
            <button
              type="button"
              className="room__more"
              aria-expanded={true}
              onClick={() => setExpanded(false)}
            >
              <span aria-hidden="true">▾</span> {rooms.roomShowLess}
            </button>
          )}
        </>
      )}
    </article>
  );
}

/** Gestrichelte Leerkarte für `off`/`null` — dieselbe Karte, zwei ehrliche Texte. */
function PendingCard({ note }: { note: string }) {
  const { rooms } = useUiStrings();
  return (
    <article className="tile tile--pending empty" data-status="pending" aria-disabled="true">
      <div className="tile__head">
        <span className="tile__name">{rooms.pendingTitle}</span>
        <span className="tile__pill">{rooms.notWired}</span>
      </div>
      <div className="tile__value">—</div>
      <p className="tile__note">{note}</p>
    </article>
  );
}

/** Solide Karte für `unreachable` — die Naht existiert, HA antwortet gerade nicht. */
function UnreachableCard() {
  const { rooms } = useUiStrings();
  return (
    <article className="tile tile--unreachable" data-status="unreachable">
      <div className="tile__head">
        <span className="tile__name">{rooms.pendingTitle}</span>
        <span className="tile__pill">{rooms.unreachable}</span>
      </div>
      <div className="tile__value">
        <WarnGlyph className="room__warnicon" /> —
      </div>
      <p className="tile__note">{rooms.unreachableNote}</p>
    </article>
  );
}

/**
 * Der ganze „live"-Leib: Kopfzeile-Wahrheit + Suche/Chips ({@link RoomsToolbar}),
 * darüber die „Braucht dich"-Inbox ({@link InboxCard}), darunter die Raum-Karten.
 * Eigene Komponente (statt inline in `RaeumeView`) aus zwei Gründen: (1) Suche
 * + Domain-Filter sind lokaler UI-State, der nur hier gebraucht wird, (2) ein
 * `snapshot: HomeRegistrySnapshot`-Prop lässt TS den `live`-Fall an der
 * JSX-Aufrufstelle sauber verengen, statt sich auf Narrowing quer durch eine
 * verschachtelte Closure zu verlassen.
 *
 * Domäne ist NUR ein Filter (Andi-Vorgabe, Konzept §2.1) — Raum bleibt die
 * einzige Gruppierungs-Achse, es gibt keinen zweiten, umschaltbaren Baum. Die
 * Kopfzeilen-Wahrheit (Zuordnungs-Zahl + Raum-Zahl) ist IMMER der volle
 * Snapshot, nie vom aktiven Filter verändert — sonst würde Suchen/Filtern die
 * ehrliche Zahl selbst verfälschen.
 *
 * **Sortierung + „Stille Räume"-Fach (Andi-Auftrag 2026-08-11, Konzept §1+§4):**
 * die sichtbaren Raum-Karten sind nach Nutzung absteigend, dann Geräteanzahl +
 * Name sortiert ({@link sortRoomsByUsage} — Konzept-Pfad 1(a), s. KDoc dort).
 * NUR wenn KEIN Filter aktiv ist, wandern Räume ohne Geräte-Aktivität
 * (0/1 Gerät, {@link splitSilentRooms}) in ein zugeklapptes „Stille Räume"-
 * Fach ans Ende — während einer aktiven Suche/eines Domain-Filters bleibt
 * JEDER Treffer direkt im Raster sichtbar (kein Verstecken eines echten
 * Suchtreffers hinter einem zugeklappten `<details>`, dieselbe Ehrlichkeits-
 * Regel wie die „tado-Lücke" oben).
 */
function LiveRoomsSection({ snapshot, edit }: { snapshot: HomeRegistrySnapshot; edit?: RaeumeEdit }) {
  const { rooms } = useUiStrings();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<DomainFilter>('alle');

  const assignedCount = snapshot.areas.reduce((acc, a) => acc + a.entities.length, 0);
  const totalCount = assignedCount + snapshot.unassigned.length;
  const filterActive = query.trim() !== '' || filter !== 'alle';

  const visibleAreas = snapshot.areas.map((area) => ({
    area,
    visible: area.entities.filter(
      (e) => matchesDomainFilter(e.domain, filter) && matchesRoomSearch(e, area.label, query),
    ),
  }));
  const visibleUnassigned = snapshot.unassigned.filter(
    (e) => matchesDomainFilter(e.domain, filter) && matchesRoomSearch(e, null, query),
  );
  const totalVisible = visibleAreas.reduce((acc, r) => acc + r.visible.length, 0) + visibleUnassigned.length;
  const nothingMatches = filterActive && totalVisible === 0;

  const shownAreas = sortRoomsByUsage(
    visibleAreas.filter(({ visible }) => visible.length > 0 || !filterActive),
  );
  // Das Falten „stiller" Räume ist NUR eine Sicht auf den unveränderten
  // Standard-Rundgang (kein Filter) — s. KDoc oben.
  const { active: activeRooms, silent: silentRooms } = filterActive
    ? { active: shownAreas, silent: [] as typeof shownAreas }
    : splitSilentRooms(shownAreas);

  return (
    <>
      <RoomsToolbar
        assignedCount={assignedCount}
        totalCount={totalCount}
        roomCount={snapshot.areas.length}
        query={query}
        onQuery={setQuery}
        filter={filter}
        onFilter={setFilter}
      />
      {!filterActive && <p className="rooms__hint">{rooms.sortHint}</p>}
      {nothingMatches ? (
        <p className="rooms__nomatch">{rooms.noMatches(query)}</p>
      ) : (
        <>
          <div className="tiles rooms">
            <InboxCard
              realActionableCount={snapshot.unassigned.filter(isRoomRelevant).length}
              visible={visibleUnassigned}
              query={query}
              edit={edit}
            />
            {activeRooms.map(({ area, visible }) => (
              <RoomCard area={area} visibleEntities={visible} key={area.areaId} />
            ))}
          </div>
          {silentRooms.length > 0 && (
            <details className="rooms__silentgroup">
              <summary className="rooms__silentsummary">{rooms.silentRooms(silentRooms.length)}</summary>
              <div className="tiles rooms">
                {silentRooms.map(({ area, visible }) => (
                  <RoomCard area={area} visibleEntities={visible} key={area.areaId} />
                ))}
              </div>
            </details>
          )}
        </>
      )}
    </>
  );
}

export interface RaeumeViewProps {
  /** `null` = erster Fetch läuft; sonst der ehrliche Zustand des Registry-Reads. */
  state: HomeRegistryState | null;
  /** Scheibe 2: der Edit-Vertrag. `undefined`/`enabled:false` ⇒ read-only (kein Picker). */
  edit?: RaeumeEdit;
}

export function RaeumeView({ state, edit }: RaeumeViewProps) {
  const { rooms } = useUiStrings();
  const isLive = state !== null && state.kind === 'live';

  return (
    <section className="ueber">
      <header className="ueber__head">
        <h1 className="ueber__title">{rooms.title}</h1>
        {!isLive && (
          <p className="ueber__lede">{edit?.enabled ? rooms.ledeEditable : rooms.ledeReadOnly}</p>
        )}
      </header>

      {state === null && <PendingCard note={rooms.loading} />}

      {state !== null && state.kind === 'off' && (
        <>
          <PendingCard note={rooms.offNote} />
          <h2 className="ueber__sec">{rooms.idea}</h2>
          <p className="ueber__sechint">{rooms.ideaHint}</p>
          <div className="sketch__wrap">
            <HoshiSketch />
          </div>
        </>
      )}

      {state !== null && state.kind === 'unreachable' && <UnreachableCard />}

      {state !== null && state.kind === 'live' && <LiveRoomsSection snapshot={state.data} edit={edit} />}
    </section>
  );
}

/** Ehrliche Fehlermeldung aus einem fehlgeschlagenen Write (Server-Text bevorzugt). */
function editErrorMessage(e: unknown, assignFailed: string): string {
  if (e instanceof HomeEditLockedError || e instanceof HomeEditValidationError) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return assignFailed;
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
  const { rooms } = useUiStrings();
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
    // Gate statt Frequenz: sichtbar taktet es unveraendert, dunkles
    // Display pausiert, Sichtbarwerden holt sofort frisch nach.
    const stopPolling = startVisiblePolling(() => void reload(), intervalMs);
    return () => {
      aliveRef.current = false;
      controller.abort();
      stopPolling();
    };
  }, [reload, loadStatus, intervalMs]);

  const onAssign = useCallback(
    (entityId: string, areaId: string): void => {
      setBusyEntityId(entityId);
      setError(null);
      void assign(entityId, areaId)
        .then(() => reload()) // read-first: neu laden ⇒ die Karte wandert echt
        .catch((e: unknown) => {
          if (aliveRef.current) setError({ entityId, message: editErrorMessage(e, rooms.assignFailed) });
        })
        .finally(() => {
          if (aliveRef.current) setBusyEntityId(null);
        });
    },
    [assign, reload, rooms.assignFailed],
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
