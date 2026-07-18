import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { RaeumeView } from '../views/RaeumeView';
import type { HomeRegistrySnapshot } from '../api/homeRegistry';

const html = (props: Parameters<typeof RaeumeView>[0]) => renderToStaticMarkup(<RaeumeView {...props} />);
const count = (s: string, needle: string) => s.split(needle).length - 1;

const snapshot = (over: Partial<HomeRegistrySnapshot> = {}): HomeRegistrySnapshot => ({
  areas: [
    {
      areaId: 'wohnzimmer',
      label: 'Wohnzimmer',
      entities: [
        { entityId: 'light.wohnzimmer_deckenlampe', domain: 'light', name: 'Deckenlampe', labels: ['hoshi:leselampen'] },
        { entityId: 'switch.wohnzimmer_stehlampe', domain: 'switch', name: 'Stehlampe', labels: [] },
      ],
    },
    { areaId: 'schlafzimmer', label: 'Schlafzimmer', entities: [] },
  ],
  unassigned: [{ entityId: 'climate.tado_wohnzimmer', domain: 'climate', name: 'Tado Wohnzimmer', labels: [] }],
  ...over,
});

describe('RaeumeView — Ladezustand (erster Fetch läuft)', () => {
  it('zeigt dieselbe gestrichelte Leerkarte wie „off", mit „wird gerade gelesen"', () => {
    const out = html({ state: null });
    expect(out).toContain('data-status="pending"');
    expect(out).toContain('Wird gerade gelesen.');
    expect(out).not.toContain('data-status="live"');
  });
});

describe('RaeumeView — HOSHI_HA_ENABLED aus (off): die bestehende ehrliche Skizze bleibt', () => {
  it('zeigt die ehrliche 🔵-Leerkarte statt erfundener Räume', () => {
    const out = html({ state: { kind: 'off' } });
    expect(out).toContain('data-status="pending"');
    expect(out).toContain('Räume kommen, sobald Home Assistant verdrahtet ist');
    expect(out).toContain('nicht verdrahtet');
    expect(out).not.toContain('data-status="live"');
    expect(out).not.toContain('tile--live');
  });

  it('skizziert weiterhin die Verbindungs-Idee mit Hoshi im Zentrum', () => {
    const out = html({ state: { kind: 'off' } });
    expect(out).toContain('Hoshi');
    expect(out).toContain('class="sketch"');
    expect(count(out, 'sketch__room"')).toBe(4);
    expect(out).toContain('ruhige Skizze');
  });
});

describe('RaeumeView — HA gerade nicht erreichbar (unreachable)', () => {
  it('zeigt einen ehrlichen „nicht erreichbar"-Zustand statt Fake-Räumen', () => {
    const out = html({ state: { kind: 'unreachable' } });
    expect(out).toContain('data-status="unreachable"');
    expect(out).toContain('nicht erreichbar');
    expect(out).toContain('Home Assistant ist gerade nicht erreichbar');
    // Kein Platzhalter-Ton („nicht verdrahtet") und keine Live-Raum-Karte.
    expect(out).not.toContain('nicht verdrahtet');
    expect(out).not.toContain('class="tile room');
  });
});

describe('RaeumeView — live: echte Raum-Karten aus GET /api/v1/home/registry', () => {
  it('rendert je Area eine Karte mit Name + Geräte-Anzahl', () => {
    const out = html({ state: { kind: 'live', data: snapshot() } });
    expect(out).toContain('Wohnzimmer');
    expect(out).toContain('Schlafzimmer');
    expect(out).toContain('Deckenlampe');
    expect(out).toContain('Stehlampe');
    expect(out).toContain('data-status="live"');
  });

  it('zeigt Domain-Glyphs (SVG, kein Emoji) je Gerät', () => {
    const out = html({ state: { kind: 'live', data: snapshot() } });
    expect(out).toContain('glyph--domain-light');
    expect(out).toContain('glyph--domain-switch');
  });

  it('zeigt Label-Chips für Geräte mit HA-Labels', () => {
    const out = html({ state: { kind: 'live', data: snapshot() } });
    expect(out).toContain('room__labelchip');
    expect(out).toContain('hoshi:leselampen');
  });

  it('eine leere Area (0 Geräte) ist ehrlich sichtbar, kein Fehler', () => {
    const out = html({ state: { kind: 'live', data: snapshot() } });
    expect(out).toContain('Noch keine Geräte in diesem Raum.');
  });

  it('„Nicht zugeordnet" ist die LETZTE Karte und listet Entities ohne Area (die tado-Lücke)', () => {
    const out = html({ state: { kind: 'live', data: snapshot() } });
    const unassignedIdx = out.indexOf('Nicht zugeordnet');
    expect(unassignedIdx).toBeGreaterThan(-1);
    // Die "Nicht zugeordnet"-Überschrift kommt NACH allen Raum-Karten-Inhalten.
    expect(out.indexOf('Wohnzimmer')).toBeLessThan(unassignedIdx);
    expect(out.indexOf('Schlafzimmer')).toBeLessThan(unassignedIdx);
    expect(out).toContain('Tado Wohnzimmer');
    expect(out).toContain('glyph--domain-climate');
  });

  it('„Nicht zugeordnet" bestätigt ehrlich, wenn es aktuell keine Lücke gibt', () => {
    const out = html({ state: { kind: 'live', data: snapshot({ unassigned: [] }) } });
    expect(out).toContain('Aktuell hat jedes gemeldete Gerät einen Raum in Home Assistant.');
  });

  it('Leerzustand: HA meldet weder Areas noch Geräte — ehrlich leer statt Fehler', () => {
    const out = html({ state: { kind: 'live', data: { areas: [], unassigned: [] } } });
    expect(out).toContain('data-status="live"');
    expect(out).toContain('Nicht zugeordnet');
    expect(out).toContain('Aktuell hat jedes gemeldete Gerät einen Raum in Home Assistant.');
    // Nur die eine Unassigned-Karte, keine einzige Raum-Karte (areas ist leer).
    expect(count(out, 'tile--live')).toBe(1);
  });
});
