import { describe, it, expect } from 'vitest';
import type { HomeRegistryArea } from '../api/homeRegistry';
import {
  SILENT_ROOM_DEVICE_THRESHOLD,
  isSilentRoom,
  sortRoomsByUsage,
  splitSilentRooms,
} from '../components/roomsSort';

// ═════════════════════════════════════════════════════════════════════════════
//  roomsSort.ts — reine Helfer (Andi-Auftrag 2026-08-11: Konzept-Pfad 1(a)
//  „Räume, mit denen gesprochen wird, zuerst" — die Nutzungs-Naht liefert
//  `recentCommands`; Geräteanzahl + Name als Gleichstand-Brecher + §4 „Stille
//  Räume". Muster `raeumeS1.test.tsx` §„roomsFilter.ts — reine Helfer".
// ═════════════════════════════════════════════════════════════════════════════

const area = (over: Partial<HomeRegistryArea> & { areaId: string; label: string; deviceCount: number }): HomeRegistryArea => ({
  areaId: over.areaId,
  label: over.label,
  entities: Array.from({ length: over.deviceCount }, (_, i) => ({
    entityId: `light.${over.areaId}_${i + 1}`,
    domain: 'light',
    name: `Gerät ${i + 1}`,
    labels: [],
  })),
  recentCommands: over.recentCommands,
});

describe('isSilentRoom — Schwelle §4: recentCommands===0 UND < SILENT_ROOM_DEVICE_THRESHOLD Geräte', () => {
  it('die Konstante ist 2 (Konzept-Wortlaut „weniger als 2 Geräte")', () => {
    expect(SILENT_ROOM_DEVICE_THRESHOLD).toBe(2);
  });

  it('0 Geräte ⇒ still', () => {
    expect(isSilentRoom(area({ areaId: 'keller', label: 'Keller', deviceCount: 0 }))).toBe(true);
  });

  it('1 Gerät ⇒ still', () => {
    expect(isSilentRoom(area({ areaId: 'flur', label: 'Flur', deviceCount: 1 }))).toBe(true);
  });

  it('2 Geräte ⇒ NICHT mehr still (Schwelle ist exklusiv)', () => {
    expect(isSilentRoom(area({ areaId: 'kueche', label: 'Küche', deviceCount: 2 }))).toBe(false);
  });

  it('viele Geräte ⇒ nicht still', () => {
    expect(isSilentRoom(area({ areaId: 'buero', label: 'Büro', deviceCount: 5 }))).toBe(false);
  });

  it('recentCommands > 0 ⇒ NIE still, auch bei 0 Geräten (explizit übergeben)', () => {
    expect(isSilentRoom(area({ areaId: 'keller', label: 'Keller', deviceCount: 0 }), 3)).toBe(false);
  });

  it('Nutzung kommt ohne zweites Argument aus der Area SELBST (Nutzungs-Naht)', () => {
    expect(isSilentRoom(area({ areaId: 'flur', label: 'Flur', deviceCount: 1, recentCommands: 2 }))).toBe(false);
  });

  it('recentCommands fehlt (alte Snapshots) ⇒ wie 0 behandelt — „nicht gemessen" ist nicht „genutzt"', () => {
    expect(isSilentRoom(area({ areaId: 'keller', label: 'Keller', deviceCount: 1 }))).toBe(
      isSilentRoom(area({ areaId: 'keller', label: 'Keller', deviceCount: 1 }), 0),
    );
  });
});

describe('sortRoomsByUsage — Nutzung absteigend, dann Geräteanzahl, dann Name (Konzept-Pfad 1a)', () => {
  it('Nutzung schlägt Geräteanzahl — der meistgenutzte Raum steht vorn, egal wie klein', () => {
    const rows = [
      { area: area({ areaId: 'buero', label: 'Büro', deviceCount: 5 }) },
      { area: area({ areaId: 'flur', label: 'Flur', deviceCount: 1, recentCommands: 4 }) },
      { area: area({ areaId: 'kueche', label: 'Küche', deviceCount: 3, recentCommands: 2 }) },
    ];
    expect(sortRoomsByUsage(rows).map((r) => r.area.areaId)).toEqual(['flur', 'kueche', 'buero']);
  });

  it('alle Zählungen 0/fehlend (frisch nach Deploy) ⇒ Reihenfolge wie bisher: Geräteanzahl + Name', () => {
    const rows = [
      { area: area({ areaId: 'kueche', label: 'Küche', deviceCount: 3, recentCommands: 0 }) },
      { area: area({ areaId: 'buero', label: 'Büro', deviceCount: 5 }) },
    ];
    expect(sortRoomsByUsage(rows).map((r) => r.area.areaId)).toEqual(['buero', 'kueche']);
  });

  it('sortiert nach Geräteanzahl absteigend', () => {
    const rows = [
      { area: area({ areaId: 'kueche', label: 'Küche', deviceCount: 3 }) },
      { area: area({ areaId: 'buero', label: 'Büro', deviceCount: 5 }) },
      { area: area({ areaId: 'flur', label: 'Flur', deviceCount: 1 }) },
    ];
    expect(sortRoomsByUsage(rows).map((r) => r.area.areaId)).toEqual(['buero', 'kueche', 'flur']);
  });

  it('gleiche Geräteanzahl ⇒ Name entscheidet, umlaut-bewusst', () => {
    const rows = [
      { area: area({ areaId: 'zzz', label: 'Zimmer', deviceCount: 2 }) },
      { area: area({ areaId: 'aaa', label: 'Ärztezimmer', deviceCount: 2 }) },
      { area: area({ areaId: 'bbb', label: 'Büro', deviceCount: 2 }) },
    ];
    expect(sortRoomsByUsage(rows).map((r) => r.area.areaId)).toEqual(['aaa', 'bbb', 'zzz']);
  });

  it('mutiert das Eingabe-Array nicht (reine Funktion)', () => {
    const rows = [
      { area: area({ areaId: 'a', label: 'A', deviceCount: 1 }) },
      { area: area({ areaId: 'b', label: 'B', deviceCount: 5 }) },
    ];
    const copy = [...rows];
    sortRoomsByUsage(rows);
    expect(rows).toEqual(copy);
  });
});

describe('splitSilentRooms — trennt in aktiv/still, Reihenfolge bleibt erhalten', () => {
  it('trennt korrekt und behält die relative Reihenfolge je Gruppe', () => {
    const sorted = sortRoomsByUsage([
      { area: area({ areaId: 'buero', label: 'Büro', deviceCount: 5 }) },
      { area: area({ areaId: 'kueche', label: 'Küche', deviceCount: 3 }) },
      { area: area({ areaId: 'flur', label: 'Flur', deviceCount: 1 }) },
      { area: area({ areaId: 'keller', label: 'Keller', deviceCount: 0 }) },
    ]);
    const { active, silent } = splitSilentRooms(sorted);
    expect(active.map((r) => r.area.areaId)).toEqual(['buero', 'kueche']);
    expect(silent.map((r) => r.area.areaId)).toEqual(['flur', 'keller']);
  });

  it('keine stillen Räume ⇒ leeres silent-Array, nichts geraten', () => {
    const rows = [{ area: area({ areaId: 'buero', label: 'Büro', deviceCount: 5 }) }];
    const { active, silent } = splitSilentRooms(rows);
    expect(active).toHaveLength(1);
    expect(silent).toHaveLength(0);
  });

  it('ein genutzter 1-Geräte-Raum bleibt aktiv — die Nutzung kommt aus der Area selbst', () => {
    const { active, silent } = splitSilentRooms([
      { area: area({ areaId: 'flur', label: 'Flur', deviceCount: 1, recentCommands: 2 }) },
    ]);
    expect(active.map((r) => r.area.areaId)).toEqual(['flur']);
    expect(silent).toHaveLength(0);
  });
});
