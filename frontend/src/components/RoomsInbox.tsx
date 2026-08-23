import { useState } from 'react';
import type { HomeRegistryEntity } from '../api/homeRegistry';
import { DomainGlyph } from './domainGlyphs';
import { RoomDeviceRow } from './RoomDeviceRow';
import { splitByRoomRelevance } from './roomsRelevance';
import { suggestAreaId } from './roomsSuggest';
import type { RaeumeEdit } from './roomsEdit';
import { useUiStrings } from '../i18n';

/**
 * **InboxRow** — eine Zeile der „Braucht dich"-Inbox: Glyph · Name · Domain ·
 * ein Raum-Picker, VORBELEGT mit einem Vorschlag ({@link suggestAreaId}), und
 * ein „Bestätigen"-Knopf. Bewusst ANDERS als der alte `RoomPicker` (Scheibe 2
 * vor dieser Änderung): der `<select>` schreibt NICHT mehr per `onChange`
 * selbst — er ist nur noch die Auswahl, das Schreiben löst ausschließlich ein
 * expliziter Klick auf „Bestätigen" aus (Andi-Vorgabe: „vorbelegt heißt: […]
 * der Klick auf Bestätigen schreibt ihn — aber NIE automatisch schreiben").
 * Der Schreibweg selbst (`edit.onAssign` → PUT → Registry-Reload) ist
 * UNVERÄNDERT derselbe wie zuvor.
 */
function InboxRow({
  entity,
  edit,
}: {
  entity: HomeRegistryEntity;
  edit: RaeumeEdit;
}) {
  const { rooms } = useUiStrings();
  const [value, setValue] = useState(() => suggestAreaId(entity, edit.areas));
  const busy = edit.busyEntityId === entity.entityId;
  const rowError = edit.errorEntityId === entity.entityId ? edit.errorMessage : null;
  return (
    <li className="room__device">
      <DomainGlyph domain={entity.domain} className="room__deviceicon" />
      <span className="room__devicename">{entity.name}</span>
      <span className="room__devicedomain">{entity.domain}</span>
      <span className="inbox__assign">
        <select
          className="inbox__select"
          value={value}
          disabled={busy}
          aria-label={rooms.pickerAria(entity.name)}
          onChange={(e) => setValue(e.currentTarget.value)}
        >
          <option value="">{rooms.chooseRoom}</option>
          {edit.areas.map((a) => (
            <option key={a.areaId} value={a.areaId}>
              {a.label}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="inbox__confirm"
          disabled={busy || !value}
          onClick={() => edit.onAssign(entity.entityId, value)}
        >
          {busy ? rooms.assigning : rooms.inboxConfirm}
        </button>
      </span>
      {rowError && (
        <p className="room__pickererror" role="alert">
          {rowError}
        </p>
      )}
    </li>
  );
}

/**
 * **InboxCard** — die „Braucht dich"-Inbox (Konzept §6 S1 + §2 Punkt 4,
 * neu gestellt am 2026-08-11): ERSETZT die alte `UnassignedCard`-Darstellung,
 * nutzt aber denselben Schreibweg (`edit.onAssign`).
 *
 * **Die Frage der Inbox ist seit Andis Zuruf 2026-08-11 nicht mehr „was hat
 * keinen Raum?", sondern „wofür ist ein Raum sinnvoll?"** ({@link
 * splitByRoomRelevance}): nur Aktor-Domains zählen als offene Aufgabe (und
 * treiben Zähler + Amber); System/Diagnose/Mobiles wandern in ein gefaltetes
 * „ohne Raum-Bezug"-Fach — dort weiter voll zuweisbar, aber ohne Drohkulisse
 * („90 Geräte brauchen dich" war zu ~90 % die Sonne, das iPhone und Proxmox).
 *
 * Drei ehrliche Zustände (jetzt auf die RELEVANTEN bezogen):
 *  - `realActionableCount === 0` — echt nichts offen ⇒ ruhige Zeile
 *    „Nichts zu tun", KEIN Amber (Amber ist für eine echte Lücke reserviert).
 *  - `realActionableCount > 0`, aber Filter/Suche blendet alle Relevanten weg
 *    ⇒ ehrliche „kein Treffer"-Meldung statt der Ruhe-Zeile.
 *  - sonst: eine Zeile pro sichtbarer relevanter Entity.
 * Das Rest-Fach hängt zustandsunabhängig unten an, sobald es Einträge trägt.
 */
export function InboxCard({
  realActionableCount,
  visible,
  query,
  edit,
}: {
  /** UNGEFILTERTE Zahl der raum-RELEVANTEN `unassigned`-Entities — entscheidet „echte Lücke" vs. Filter-Blende. */
  realActionableCount: number;
  /** Die nach Suche/Domain gefilterten Entities; die Relevanz-Trennung passiert hier drin. */
  visible: HomeRegistryEntity[];
  /** Die aktive Suchanfrage (nur für die ehrliche „kein Treffer"-Meldung). */
  query: string;
  edit?: RaeumeEdit;
}) {
  const { rooms } = useUiStrings();
  const { actionable, rest } = splitByRoomRelevance(visible);
  const hasGap = realActionableCount > 0;
  const filteredAway = hasGap && actionable.length === 0;
  const row = (e: HomeRegistryEntity) =>
    edit?.enabled ? <InboxRow entity={e} edit={edit} key={e.entityId} /> : <RoomDeviceRow entity={e} key={e.entityId} />;
  return (
    <article className="tile room room--unassigned tile--live" data-status="live">
      <div className="tile__head">
        <span className={`tile__name${hasGap ? ' room__name--gap' : ''}`}>{rooms.inboxTitle}</span>
        <span className="tile__pill">{actionable.length}</span>
      </div>
      {!hasGap && <p className="room__hint">{rooms.inboxEmpty}</p>}
      {filteredAway && <p className="room__hint">{rooms.noMatches(query)}</p>}
      {hasGap && !filteredAway && (
        <>
          <p className="room__hint">{edit?.enabled ? rooms.inboxHintEditable : rooms.inboxHintReadOnly}</p>
          <ul className="room__devices">{actionable.map(row)}</ul>
        </>
      )}
      {rest.length > 0 && (
        <details className="inbox__restgroup">
          <summary className="inbox__restsummary">{rooms.inboxRestSummary(rest.length)}</summary>
          <ul className="room__devices">{rest.map(row)}</ul>
        </details>
      )}
    </article>
  );
}
