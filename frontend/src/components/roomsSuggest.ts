import type { HomeRegistryEntity } from '../api/homeRegistry';
import type { AreaOption } from './roomsEdit';

/**
 * HA-`entity_id`s sind ASCII-Slugs — „Küche" wird dort zu `kueche`, nicht zu
 * `küche` (HA transliteriert Umlaute beim Slugifizieren, dokumentiertes
 * Verhalten). Ein reiner `.toLowerCase()`-Substring-Vergleich würde also
 * GENAU die Räume verfehlen, deren Namen einen Umlaut/ß tragen — für ein
 * deutschsprachiges Zuhause der Normalfall, nicht die Ausnahme. Darum faltet
 * `fold` Umlaute/ß auf ihre ASCII-Transliteration, BEVOR verglichen wird.
 */
function fold(s: string): string {
  return s
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss');
}

/**
 * **suggestAreaId** — die Vorbelegungs-Heuristik der „Braucht dich"-Inbox
 * (Konzept §6 S1, §2 Punkt 4: „vorbelegt statt leer"). Reine Funktion, kein
 * Netz/State: kommt im Geräte-Namen ODER der `entity_id` ein bekannter
 * Raumname oder ein eindeutiges Wort-Fragment davon vor, ist DAS der
 * Vorschlag — sonst bleibt der Vorschlag leer (Andi-Vorgabe: „sonst leer").
 *
 * „Eindeutig" wird hier ernst genommen: matchen MEHRERE Räume (z.B. weil ein
 * kurzes Fragment zufällig in zwei Raum-Namen steckt), ist das KEIN
 * eindeutiger Treffer mehr — dann lieber leer als eine geratene Raum-Zeile
 * vorschlagen. Vorbelegt heißt vorschlagen, nie automatisch schreiben: der
 * Aufrufer (`InboxRow`) schreibt den Vorschlag nur, wenn Andi „Bestätigen"
 * antippt.
 */
export function suggestAreaId(entity: HomeRegistryEntity, areas: readonly AreaOption[]): string {
  const hay = fold(`${entity.name} ${entity.entityId}`);
  const candidates = new Set<string>();

  for (const area of areas) {
    const label = fold(area.label.trim());
    if (label.length < 3) continue; // zu kurz, um zufälligen Treffern zu entgehen
    if (hay.includes(label)) {
      candidates.add(area.areaId);
      continue;
    }
    // Wort-Fragmente ab 4 Zeichen (z.B. "Wohnzimmer" trifft auch als Teil
    // eines längeren Namens; kurze Wörter wie "im"/"an" bleiben außen vor).
    const words = label.split(/[^\p{L}\p{N}]+/u).filter((w) => w.length >= 4);
    if (words.some((w) => hay.includes(w))) candidates.add(area.areaId);
  }

  return candidates.size === 1 ? [...candidates][0]! : '';
}
