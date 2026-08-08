import { useState } from 'react';
import type { HomeRegistryEntity } from '../api/homeRegistry';
import { DomainGlyph } from './domainGlyphs';
import { RoomDeviceRow } from './RoomDeviceRow';
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
 * **InboxCard** — die „Braucht dich"-Inbox (Konzept §6 S1 + §2 Punkt 4):
 * ERSETZT die alte `UnassignedCard`-Darstellung, nutzt aber denselben
 * Schreibweg (`edit.onAssign`). Drei ehrliche Zustände:
 *  - `realCount === 0` — echt nichts offen ⇒ ruhige Zeile „Nichts zu tun",
 *    KEIN Amber (Amber ist für eine echte Lücke reserviert, Muster der alten
 *    `UnassignedCard`).
 *  - `realCount > 0`, aber der aktuelle Filter/Suche zeigt `visible.length===0`
 *    ⇒ das ist KEIN „nichts zu tun", sondern eine Filter-Blende — eigene,
 *    ehrliche Meldung statt der Ruhe-Zeile (sonst würde die echte Lücke
 *    unsichtbar „gelöst" wirken).
 *  - sonst: eine Zeile pro sichtbarer Entity, mit Picker (editierbar) oder
 *    reiner Anzeige (read-only, Flag zu).
 */
export function InboxCard({
  realCount,
  visible,
  query,
  edit,
}: {
  /** Die UNGEFILTERTE Gesamtzahl der `unassigned`-Entities — entscheidet „echte Lücke" vs. Filter-Blende. */
  realCount: number;
  /** Die nach Suche/Domain gefilterten Entities, die gerade angezeigt werden. */
  visible: HomeRegistryEntity[];
  /** Die aktive Suchanfrage (nur für die ehrliche „kein Treffer"-Meldung). */
  query: string;
  edit?: RaeumeEdit;
}) {
  const { rooms } = useUiStrings();
  const hasGap = realCount > 0;
  const filteredAway = hasGap && visible.length === 0;
  return (
    <article className="tile room room--unassigned tile--live" data-status="live">
      <div className="tile__head">
        <span className={`tile__name${hasGap ? ' room__name--gap' : ''}`}>{rooms.inboxTitle}</span>
        <span className="tile__pill">{visible.length}</span>
      </div>
      {!hasGap && <p className="room__hint">{rooms.inboxEmpty}</p>}
      {filteredAway && <p className="room__hint">{rooms.noMatches(query)}</p>}
      {hasGap && !filteredAway && (
        <>
          <p className="room__hint">{edit?.enabled ? rooms.inboxHintEditable : rooms.inboxHintReadOnly}</p>
          <ul className="room__devices">
            {visible.map((e) =>
              edit?.enabled ? (
                <InboxRow entity={e} edit={edit} key={e.entityId} />
              ) : (
                <RoomDeviceRow entity={e} key={e.entityId} />
              ),
            )}
          </ul>
        </>
      )}
    </article>
  );
}
