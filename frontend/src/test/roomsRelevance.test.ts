import { describe, it, expect } from 'vitest';
import type { HomeRegistryEntity } from '../api/homeRegistry';
import { ROOM_RELEVANT_DOMAINS, isRoomRelevant, splitByRoomRelevance } from '../components/roomsRelevance';

// ═════════════════════════════════════════════════════════════════════════════
//  roomsRelevance.ts — die neu gestellte Inbox-Frage (Andi 2026-08-11):
//  „wofür ist ein Raum sinnvoll?" statt „was hat keinen Raum?". Andis realer
//  Befund: 90 Einträge, davon ~8 echte Kandidaten (tado-Thermostate, TV) —
//  der Rest Sonne, Zonen, iPhone-Diagnose, Proxmox. Muster roomsSort.test.ts.
// ═════════════════════════════════════════════════════════════════════════════

const e = (entityId: string, domain: string, name = entityId): HomeRegistryEntity => ({
  entityId,
  domain,
  name,
  labels: [],
});

describe('isRoomRelevant — Aktor-Domains ja, Systemwesen nein', () => {
  it.each(['climate', 'light', 'switch', 'media_player', 'cover', 'vacuum'] as const)(
    '%s ⇒ relevant (Hoshi kann es in einem Raum schalten/nutzen)',
    (domain) => {
      expect(isRoomRelevant(e(`${domain}.x`, domain))).toBe(true);
    },
  );

  it.each([
    ['person', 'person.root'],
    ['zone', 'zone.home'],
    ['sun', 'sun.sun'],
    ['device_tracker', 'device_tracker.iphone'],
    ['sensor', 'sensor.iphone_bssid'],
    ['binary_sensor', 'binary_sensor.ct_100_ha'],
    ['weather', 'weather.forecast_home'],
    ['tts', 'tts.google_translate'],
    ['conversation', 'conversation.home_assistant'],
    ['todo', 'todo.einkaufsliste'],
    ['button', 'button.tado_identify'],
    ['select', 'select.tado_display_units'],
  ] as const)('%s ⇒ kein Raum-Bezug (Andis 90er-Liste)', (domain, id) => {
    expect(isRoomRelevant(e(id, domain))).toBe(false);
  });

  it('unbekannte/neue Domains fallen in den Rest (Allowlist, keine Blocklist)', () => {
    expect(isRoomRelevant(e('frobnicator.x', 'frobnicator'))).toBe(false);
    expect(ROOM_RELEVANT_DOMAINS.has('frobnicator')).toBe(false);
  });
});

describe('splitByRoomRelevance — reine Trennung, Reihenfolge bleibt', () => {
  it('trennt Andis Muster-Fall: tado + TV bleiben Aufgabe, iPhone/Sonne falten sich', () => {
    const { actionable, rest } = splitByRoomRelevance([
      e('sun.sun', 'sun'),
      e('climate.tado_va1', 'climate'),
      e('sensor.iphone_bssid', 'sensor'),
      e('media_player.lg_tv', 'media_player'),
      e('climate.tado_va2', 'climate'),
    ]);
    expect(actionable.map((x) => x.entityId)).toEqual(['climate.tado_va1', 'media_player.lg_tv', 'climate.tado_va2']);
    expect(rest.map((x) => x.entityId)).toEqual(['sun.sun', 'sensor.iphone_bssid']);
  });

  it('leere Liste ⇒ beide Seiten leer, nichts erfunden', () => {
    const { actionable, rest } = splitByRoomRelevance([]);
    expect(actionable).toEqual([]);
    expect(rest).toEqual([]);
  });
});
