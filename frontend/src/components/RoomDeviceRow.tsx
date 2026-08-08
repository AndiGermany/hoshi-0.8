import type { HomeRegistryEntity } from '../api/homeRegistry';
import { DomainGlyph } from './domainGlyphs';

/**
 * **RoomDeviceRow** — die reine Anzeige-Zeile einer Entity: Domain-Glyph ·
 * Name · Domain-Beschriftung · Label-Chips. KEINE Zuordnungs-UI mehr hier
 * (Scheibe 1 des Räume-Konzepts, §6 S1): die lebt jetzt ausschließlich in der
 * „Braucht dich"-Inbox (`RoomsInbox.tsx`), weil dort — und nur dort — Andi
 * überhaupt etwas zuordnen kann. Geteilt zwischen `RaeumeView` (Raum-Karten)
 * und `RoomsInbox` (Nicht-editierbarer Read-only-Zeilenfall), damit beide
 * Stellen exakt gleich aussehen statt zweimal dieselbe Zeile zu pflegen.
 */
export function RoomDeviceRow({ entity }: { entity: HomeRegistryEntity }) {
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
    </li>
  );
}
