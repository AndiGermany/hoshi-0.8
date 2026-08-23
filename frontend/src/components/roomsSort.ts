import type { HomeRegistryArea } from '../api/homeRegistry';

/**
 * **roomsSort** — Nutzungs-/Stille-Sortierung der Raum-Karten (Andi-Auftrag
 * 2026-08-11: „ich will die Räume richtig sortieren … so ist es nur eine
 * lange Liste"). Reine, netzfreie Helfer (Muster `roomsFilter.ts`) —
 * `RaeumeView` verdrahtet sie nur.
 *
 * **DATENLAGE (aktualisiert 2026-08-11, Nutzungs-Naht GEBAUT):** die bei der
 * Erst-Scheibe fehlende Naht existiert jetzt Ende-zu-Ende — exakt entlang der
 * hier kartierten Rate-Stelle: `TurnTrace.targetAreaId` reist additiv ins
 * Diary (`ChatEvent.Start` → `TurnDiaryTap` → `JsonlTurnTraceAdapter`), und
 * `GET /home/registry` liefert je Area die 14-Tage-Zählung `recentCommands`
 * (`AreaUsageReader`). Damit gilt Konzept-Pfad **1(a)** „Räume, mit denen
 * gesprochen wird, zuerst": Nutzung absteigend, bei Gleichstand Geräteanzahl
 * absteigend (der alte Pfad 1(b) als Gleichstand-Brecher — direkt nach dem
 * Deploy steht JEDE Zählung ehrlich auf 0, die Reihenfolge ist dann
 * unverändert die heutige), dann Name. Fehlendes `recentCommands` heißt
 * „nicht gemessen" und zählt als 0 — alte Snapshots bleiben gültig.
 */

/**
 * Ab wie vielen zugeordneten Geräten ein Raum NICHT mehr als „still" gilt
 * (Konzept §4: „weniger als 2 Geräte"). Bewusst als benannte Konstante statt
 * Magic Number — ein Raum mit 0 oder 1 Gerät fällt darunter.
 */
export const SILENT_ROOM_DEVICE_THRESHOLD = 2;

/**
 * Ein Raum gilt als „still" (Konzept §4), wenn er NIE aktiv genutzt wurde
 * UND weniger als [SILENT_ROOM_DEVICE_THRESHOLD] Geräte trägt.
 * `recentCommands` defaultet jetzt auf die gemessene Zählung der Area selbst
 * (Nutzungs-Naht, s. Klassen-KDoc) — fehlend heißt „nicht gemessen" und zählt
 * als 0. Der Parameter bleibt explizit überschreibbar; ohne zweites Argument
 * ist der Aufruf automatisch nutzungs-ehrlich.
 */
export function isSilentRoom(area: HomeRegistryArea, recentCommands = area.recentCommands ?? 0): boolean {
  return recentCommands === 0 && area.entities.length < SILENT_ROOM_DEVICE_THRESHOLD;
}

/**
 * Sortiert Zeilen mit eingebetteter Area nach Nutzung absteigend
 * (`recentCommands`, Konzept-Pfad 1(a) — s. Klassen-KDoc), bei Gleichstand
 * nach Geräteanzahl absteigend, dann nach Raumnamen (lokal-bewusst, Umlaute
 * korrekt einsortiert). Generisch über `{area, …}`, damit sowohl die reinen
 * Areas als auch die bereits mit dem aktiven Filter berechneten
 * `{area, visible}`-Zeilen aus `RaeumeView` sortierbar sind, ohne einen
 * zweiten, parallelen Datentyp zu brauchen. Reine Funktion (kopiert statt
 * zu mutieren).
 */
export function sortRoomsByUsage<T extends { area: HomeRegistryArea }>(rows: readonly T[]): T[] {
  return [...rows].sort((a, b) => {
    const byUsage = (b.area.recentCommands ?? 0) - (a.area.recentCommands ?? 0);
    if (byUsage !== 0) return byUsage;
    const byDeviceCount = b.area.entities.length - a.area.entities.length;
    if (byDeviceCount !== 0) return byDeviceCount;
    return a.area.label.localeCompare(b.area.label, undefined, { sensitivity: 'base' });
  });
}

/**
 * Trennt bereits sortierte/gefilterte Zeilen in „aktiv" und „still" (Konzept
 * §4) — die relative Reihenfolge INNERHALB jeder Gruppe bleibt erhalten
 * (reines Aufteilen, kein erneutes Sortieren).
 */
export function splitSilentRooms<T extends { area: HomeRegistryArea }>(
  rows: readonly T[],
): { active: T[]; silent: T[] } {
  const active: T[] = [];
  const silent: T[] = [];
  for (const row of rows) {
    (isSilentRoom(row.area) ? silent : active).push(row);
  }
  return { active, silent };
}
