import type { HomeRegistryEntity } from '../api/homeRegistry';

/**
 * **roomsFilter** — reine Such-/Filter-Helfer für Scheibe 1 des Räume-
 * Verwaltungs-Konzepts (`.orch-bus/inbox/20260727-2223-cowork-raeume-
 * verwaltung-konzept.md`, §6 „S1 — Das zuerst"). Beide Funktionen sind bewusst
 * hookfrei/netzfrei (reine Prädikate über den bereits geladenen Registry-
 * Snapshot) — `RoomsToolbar`/`RaeumeView` verdrahten sie nur.
 *
 * KEIN zweiter Baum: die Domäne ist NUR ein Filter-Prädikat neben der Suche,
 * die Gruppierung nach Raum bleibt die einzige Achse (Andi-Vorgabe, Konzept §2.1).
 */

/** Die genau vier Domänen-Eimer + „alle" (Andi-Vorgabe: „GENAU vier" Filter-Chips). */
export type DomainFilter = 'alle' | 'licht' | 'klima' | 'sensoren' | 'rest';

/**
 * `entity_id`-Domain-Präfix → Eimer. `switch` gehört bewusst zu `rest` (Andi-
 * Vorgabe: „light./switch.→Licht ist FALSCH — switch gehört zu Rest").
 */
export function domainBucket(domain: string): Exclude<DomainFilter, 'alle'> {
  if (domain === 'light') return 'licht';
  if (domain === 'climate' || domain === 'cover') return 'klima';
  if (domain === 'sensor' || domain === 'binary_sensor') return 'sensoren';
  return 'rest';
}

/** `alle` lässt jede Domain durch; sonst muss der Eimer exakt passen. */
export function matchesDomainFilter(domain: string, filter: DomainFilter): boolean {
  return filter === 'alle' || domainBucket(domain) === filter;
}

/**
 * Live-Suche über GENAU drei Felder (Andi-Vorgabe): Name, `entity_id`, Raumname.
 * `roomLabel` ist `null` für unassigned Entities (kein Raum zum Durchsuchen).
 * Leere/nur-Whitespace-Query ⇒ immer Treffer (kein Filter aktiv).
 */
export function matchesRoomSearch(
  entity: HomeRegistryEntity,
  roomLabel: string | null,
  query: string,
): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return (
    entity.name.toLowerCase().includes(q) ||
    entity.entityId.toLowerCase().includes(q) ||
    (roomLabel ?? '').toLowerCase().includes(q)
  );
}
