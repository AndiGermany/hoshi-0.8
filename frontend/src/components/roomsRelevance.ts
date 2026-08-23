import type { HomeRegistryEntity } from '../api/homeRegistry';

/**
 * **roomsRelevance** — die Frage der „Braucht dich"-Inbox, neu gestellt
 * (Andi-Auftrag 2026-08-11: „warum wird eine iPhone-Verbindung angezeigt?
 * Überdenke das Thema"): nicht „was hat keinen Raum?", sondern „WOFÜR IST
 * EIN RAUM ÜBERHAUPT SINNVOLL?". Home Assistant führt person/zone/sun/
 * Wetter/TTS/Diagnose-Sensoren völlig zu Recht ohne Area — aber das ist
 * keine Lücke, die ein Mensch schließen muss: ein Handy hat keinen Raum,
 * die Sonne auch nicht. Die Inbox fragt darum nur noch nach AKTOR-Domains,
 * also Dingen, die Hoshi in einem Raum schalten oder nutzen kann. Der Rest
 * bleibt vollständig sichtbar und zuweisbar (gefaltet, s. InboxCard), wird
 * aber nicht mehr als offene Aufgabe verkleidet.
 *
 * Bewusst eine ALLOWLIST (kurz, erklärbar) statt Blocklist: unbekannte oder
 * exotische HA-Domains landen im gefalteten Rest statt fälschlich in der
 * Inbox. `sensor`/`binary_sensor` fehlen ABSICHTLICH — die tado-Temperatur-
 * Sensoren wären zwar raum-relevant, hängen aber am selben physischen Gerät
 * wie ihr `climate`-Aktor; die Geräte-Bündelung (S2, HA device registry)
 * zieht sie nach, sobald das Thermostat selbst seinen Raum hat.
 */
export const ROOM_RELEVANT_DOMAINS: ReadonlySet<string> = new Set([
  'light',
  'switch',
  'climate',
  'cover',
  'media_player',
  'fan',
  'lock',
  'vacuum',
  'humidifier',
  'valve',
  'siren',
  'camera',
]);

/** Ist ein Raum für diese Entity eine sinnvolle Frage? (s. Datei-KDoc) */
export function isRoomRelevant(entity: HomeRegistryEntity): boolean {
  return ROOM_RELEVANT_DOMAINS.has(entity.domain);
}

/**
 * Trennt eine Entity-Liste in Inbox-Kandidaten (`actionable`) und den
 * gefalteten Rest — reine Funktion, relative Reihenfolge bleibt erhalten
 * (Muster {@link splitSilentRooms} in `roomsSort.ts`).
 */
export function splitByRoomRelevance(entities: readonly HomeRegistryEntity[]): {
  actionable: HomeRegistryEntity[];
  rest: HomeRegistryEntity[];
} {
  const actionable: HomeRegistryEntity[] = [];
  const rest: HomeRegistryEntity[] = [];
  for (const e of entities) {
    (isRoomRelevant(e) ? actionable : rest).push(e);
  }
  return { actionable, rest };
}
