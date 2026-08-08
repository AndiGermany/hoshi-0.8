import { useUiStrings } from '../i18n';
import type { DomainFilter } from './roomsFilter';

/**
 * **RoomsToolbar** — die neue Kopfzeile von Scheibe 1 (Konzept §6 S1 + §2):
 * die Zuordnungs-Wahrheit als EIN Satz (echte Zahlen aus dem Registry-Snapshot,
 * nie geschätzt), darunter die Suche (primäre Navigation) und GENAU vier
 * Domänen-Filter-Chips plus „Alle". Rein prop-getrieben/kontrolliert — der
 * Zustand (Query/Filter) lebt einen Stock höher in `RaeumeView`, damit sowohl
 * die Raum-Karten als auch die Inbox denselben Filter sehen.
 *
 * EHRLICHKEITS-REGEL (Andi-Auftrag): der Registry-Payload trägt (Stand S1)
 * WEDER ein „Hoshi hört"-/Expose-Flag NOCH einen Snapshot-Zeitstempel (s.
 * `api/homeRegistry.ts` — `HomeRegistryEntity`/`HomeRegistrySnapshot` haben
 * keine solchen Felder). Darum zeigt die Kopfzeile NUR die Zuordnungs-Wahrheit
 * („x von y Geräten einem Raum zugeordnet · n Räume") — keine erfundene
 * Ohren-Zahl, kein geschätztes Alter. Beides kommt erst mit Scheibe S2/S3,
 * sobald der Payload die Felder wirklich trägt.
 */
export function RoomsToolbar({
  assignedCount,
  totalCount,
  roomCount,
  query,
  onQuery,
  filter,
  onFilter,
}: {
  assignedCount: number;
  totalCount: number;
  roomCount: number;
  query: string;
  onQuery: (query: string) => void;
  filter: DomainFilter;
  onFilter: (filter: DomainFilter) => void;
}) {
  const { rooms } = useUiStrings();
  const chips: { id: DomainFilter; label: string }[] = [
    { id: 'alle', label: rooms.domainAll },
    { id: 'licht', label: rooms.domainLight },
    { id: 'klima', label: rooms.domainClimate },
    { id: 'sensoren', label: rooms.domainSensors },
    { id: 'rest', label: rooms.domainOther },
  ];
  return (
    <div className="rooms__toolbar">
      <p className="rooms__summary">{rooms.assignedSummary(assignedCount, totalCount, roomCount)}</p>
      <input
        type="search"
        className="rooms__search"
        placeholder={rooms.searchPlaceholder}
        aria-label={rooms.searchAria}
        value={query}
        onChange={(e) => onQuery(e.currentTarget.value)}
      />
      <div className="rooms__chips" role="group" aria-label={rooms.domainFilterAria}>
        {chips.map((c) => (
          <button
            key={c.id}
            type="button"
            className={`rooms__chip${filter === c.id ? ' is-active' : ''}`}
            aria-pressed={filter === c.id}
            onClick={() => onFilter(c.id)}
          >
            {c.label}
          </button>
        ))}
      </div>
    </div>
  );
}
