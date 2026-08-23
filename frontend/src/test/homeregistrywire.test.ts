import { describe, it, expect } from 'vitest';
import { parseHomeRegistrySnapshot } from '../api/homeRegistry';

/**
 * **homeregistrywire.test** — der additive Draht-Vertrag der Zuhause-
 * Kacheln-Scheibe (Andi-Auftrag 2026-08-11): jede Registry-Entity trägt ab S2
 * additiv `state?: string` und `attrs?: Record<string,string>` mit HÖCHSTENS
 * `battery_level`/`current_temperature`/`temperature`/`hvac_action`/
 * `unit_of_measurement` (letzteres additiv seit der Sauger-Metrik-Familie,
 * Andi-Auftrag 2026-08-13). Fehlende Felder sind kein Bruch (Alt-Backend);
 * unbekannte `attrs`-Keys werden verworfen statt durchgereicht (kein offener
 * Grab-Bag).
 */

const bareBody = () => ({
  areas: [
    {
      areaId: 'wz',
      label: 'Wohnzimmer',
      entities: [{ entityId: 'light.wz', domain: 'light', name: 'Deckenlicht', labels: [] }],
    },
  ],
  unassigned: [{ entityId: 'vacuum.rob', domain: 'vacuum', name: 'Rob', labels: [] }],
});

describe('parseHomeRegistrySnapshot — Kern-Vertrag bleibt gültig OHNE die additiven Felder', () => {
  it('kein state/attrs irgendwo ⇒ die Entities bleiben gültig, die neuen Felder fehlen', () => {
    const got = parseHomeRegistrySnapshot(bareBody());
    expect(got).not.toBeNull();
    const light = got!.areas[0].entities[0];
    const vacuum = got!.unassigned[0];
    expect(light).not.toHaveProperty('state');
    expect(light).not.toHaveProperty('attrs');
    expect(vacuum).not.toHaveProperty('state');
    expect(vacuum).not.toHaveProperty('attrs');
  });
});

describe('parseHomeRegistrySnapshot — state additiv', () => {
  it('gültiger state-String ⇒ 1:1 übernommen', () => {
    const body = bareBody();
    body.unassigned[0] = { ...body.unassigned[0], state: 'cleaning' } as typeof body.unassigned[0];
    const got = parseHomeRegistrySnapshot(body);
    expect(got!.unassigned[0].state).toBe('cleaning');
  });

  it('leerer state-String ⇒ Feld fehlt (kein leerer String behauptet)', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).state = '';
    const got = parseHomeRegistrySnapshot(body);
    expect(got!.unassigned[0]).not.toHaveProperty('state');
  });

  it('falsch typisierter state (Zahl) ⇒ Feld fehlt, der Rest der Entity bleibt gültig', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).state = 42;
    const got = parseHomeRegistrySnapshot(body);
    expect(got!.unassigned[0]).not.toHaveProperty('state');
    expect(got!.unassigned[0].entityId).toBe('vacuum.rob');
  });
});

describe('parseHomeRegistrySnapshot — attrs additiv, höchstens die bekannten Keys', () => {
  it('die vier alten Keys werden 1:1 übernommen', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).attrs = { battery_level: '87' };
    (body.areas[0].entities[0] as Record<string, unknown>).attrs = {
      current_temperature: '21.5',
      temperature: '22',
      hvac_action: 'heating',
    };
    const got = parseHomeRegistrySnapshot(body);
    expect(got!.unassigned[0].attrs).toEqual({ battery_level: '87' });
    expect(got!.areas[0].entities[0].attrs).toEqual({
      current_temperature: '21.5',
      temperature: '22',
      hvac_action: 'heating',
    });
  });

  it('unit_of_measurement (fünfter Key, additiv seit der Sauger-Metrik-Familie) wird 1:1 übernommen', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).attrs = { battery_level: '87', unit_of_measurement: '%' };
    const got = parseHomeRegistrySnapshot(body);
    expect(got!.unassigned[0].attrs).toEqual({ battery_level: '87', unit_of_measurement: '%' });
  });

  it('unbekannte Attribut-Keys werden verworfen (kein offener Grab-Bag)', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).attrs = {
      battery_level: '87',
      supported_features: '16383',
      friendly_name: 'Rob',
    };
    const got = parseHomeRegistrySnapshot(body);
    expect(got!.unassigned[0].attrs).toEqual({ battery_level: '87' });
  });

  it('nicht-String-Werte innerhalb attrs werden verworfen, gültige Nachbar-Keys bleiben', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).attrs = { battery_level: 87, hvac_action: 'heating' };
    const got = parseHomeRegistrySnapshot(body);
    // battery_level als Zahl statt String ⇒ verworfen; hvac_action bleibt (aber
    // vacuum hat semantisch kein hvac_action — der Parser prüft das nicht, das
    // ist Sache der Kachel-Helfer, s. `components/homeTiles.ts`).
    expect(got!.unassigned[0].attrs).toEqual({ hvac_action: 'heating' });
  });

  it('leeres/kaputtes attrs ⇒ das Feld fehlt (keine leere {}-Behauptung)', () => {
    const body1 = bareBody();
    (body1.unassigned[0] as Record<string, unknown>).attrs = {};
    expect(parseHomeRegistrySnapshot(body1)!.unassigned[0]).not.toHaveProperty('attrs');

    const body2 = bareBody();
    (body2.unassigned[0] as Record<string, unknown>).attrs = 'kaputt';
    expect(parseHomeRegistrySnapshot(body2)!.unassigned[0]).not.toHaveProperty('attrs');

    const body3 = bareBody();
    (body3.unassigned[0] as Record<string, unknown>).attrs = { unknown_key: 'x' };
    expect(parseHomeRegistrySnapshot(body3)!.unassigned[0]).not.toHaveProperty('attrs');
  });
});

describe('parseHomeRegistrySnapshot — recentCommands wird durchgereicht (Nutzungs-Naht)', () => {
  // Hand-Selbstanzeige 2026-08-11: der Parser verschluckte das Feld anfangs —
  // die 1(a)-Sortierung wäre still auf Geräteanzahl zurückgefallen, sobald
  // echte Zählungen existieren. Diese Fälle halten den Vertrag.
  it('gültige Zählung ⇒ 1:1 übernommen (auch 0 — „gemessen und nie genutzt" ist ein Wert)', () => {
    const body = bareBody();
    (body.areas[0] as Record<string, unknown>).recentCommands = 7;
    expect(parseHomeRegistrySnapshot(body)!.areas[0].recentCommands).toBe(7);

    const body0 = bareBody();
    (body0.areas[0] as Record<string, unknown>).recentCommands = 0;
    expect(parseHomeRegistrySnapshot(body0)!.areas[0].recentCommands).toBe(0);
  });

  it('fehlend/kaputt/negativ ⇒ Feld fehlt ehrlich (Alt-Backend bleibt gültig)', () => {
    expect(parseHomeRegistrySnapshot(bareBody())!.areas[0]).not.toHaveProperty('recentCommands');

    const bad = bareBody();
    (bad.areas[0] as Record<string, unknown>).recentCommands = 'viele';
    expect(parseHomeRegistrySnapshot(bad)!.areas[0]).not.toHaveProperty('recentCommands');

    const neg = bareBody();
    (neg.areas[0] as Record<string, unknown>).recentCommands = -3;
    expect(parseHomeRegistrySnapshot(neg)!.areas[0]).not.toHaveProperty('recentCommands');
  });
});

describe('parseHomeRegistrySnapshot — statesFetchedAt additiv (Andi-Auftrag 2026-08-13, „Sauger-Sichtbarkeits-Lücke")', () => {
  it('gültiger ISO-String ⇒ 1:1 übernommen', () => {
    const body = bareBody() as Record<string, unknown>;
    body.statesFetchedAt = '2026-08-13T20:03:00Z';
    expect(parseHomeRegistrySnapshot(body)!.statesFetchedAt).toBe('2026-08-13T20:03:00Z');
  });

  it('fehlend/leer/falsch typisiert ⇒ Feld fehlt ehrlich (Alt-Backend ODER nie erfolgreich)', () => {
    expect(parseHomeRegistrySnapshot(bareBody())).not.toHaveProperty('statesFetchedAt');

    const empty = bareBody() as Record<string, unknown>;
    empty.statesFetchedAt = '';
    expect(parseHomeRegistrySnapshot(empty)).not.toHaveProperty('statesFetchedAt');

    const wrongType = bareBody() as Record<string, unknown>;
    wrongType.statesFetchedAt = 12345;
    expect(parseHomeRegistrySnapshot(wrongType)).not.toHaveProperty('statesFetchedAt');

    const nullVal = bareBody() as Record<string, unknown>;
    nullVal.statesFetchedAt = null;
    expect(parseHomeRegistrySnapshot(nullVal)).not.toHaveProperty('statesFetchedAt');
  });
});

describe('parseHomeRegistrySnapshot — lastKnown additiv (Andi-Auftrag 2026-08-13, „Sauger-Sichtbarkeits-Lücke")', () => {
  it('vollständiges lastKnown (state + attrs + seenAt) ⇒ 1:1 übernommen, Allowlist gilt auch hier', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).lastKnown = {
      state: 'docked',
      attrs: { battery_level: '87', supported_features: '16383' },
      seenAt: '2026-08-13T20:03:00Z',
    };
    const got = parseHomeRegistrySnapshot(body)!.unassigned[0];
    expect(got.lastKnown).toEqual({ state: 'docked', attrs: { battery_level: '87' }, seenAt: '2026-08-13T20:03:00Z' });
  });

  it('lastKnown OHNE brauchbare attrs ⇒ attrs bleibt ein leeres Objekt (nie undefined, anders als das Top-Level-attrs-Feld)', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).lastKnown = { state: 'docked', attrs: {}, seenAt: '2026-08-13T20:03:00Z' };
    const got = parseHomeRegistrySnapshot(body)!.unassigned[0];
    expect(got.lastKnown?.attrs).toEqual({});
  });

  it('lastKnown fehlt ⇒ Feld fehlt ehrlich (Alt-Backend/live brauchbar/nie ein Vorerfolg)', () => {
    const got = parseHomeRegistrySnapshot(bareBody())!.unassigned[0];
    expect(got).not.toHaveProperty('lastKnown');
  });

  it('kaputtes lastKnown (state fehlt, seenAt fehlt, falscher Typ) ⇒ Feld fehlt, der Rest der Entity bleibt gültig', () => {
    const noState = bareBody();
    (noState.unassigned[0] as Record<string, unknown>).lastKnown = { attrs: {}, seenAt: '2026-08-13T20:03:00Z' };
    expect(parseHomeRegistrySnapshot(noState)!.unassigned[0]).not.toHaveProperty('lastKnown');

    const noSeenAt = bareBody();
    (noSeenAt.unassigned[0] as Record<string, unknown>).lastKnown = { state: 'docked', attrs: {} };
    expect(parseHomeRegistrySnapshot(noSeenAt)!.unassigned[0]).not.toHaveProperty('lastKnown');

    const wrongType = bareBody();
    (wrongType.unassigned[0] as Record<string, unknown>).lastKnown = 'kaputt';
    const gotWrongType = parseHomeRegistrySnapshot(wrongType)!.unassigned[0];
    expect(gotWrongType).not.toHaveProperty('lastKnown');
    expect(gotWrongType.entityId).toBe('vacuum.rob'); // der Rest der Entity bleibt gültig
  });
});

describe('parseHomeRegistrySnapshot — fromCacheSinceMs additiv (Cache-Carry, BE-Pod 21.08.)', () => {
  it('gültiger Stempel ⇒ 1:1 übernommen', () => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).fromCacheSinceMs = 1755769800000;
    expect(parseHomeRegistrySnapshot(body)!.unassigned[0].fromCacheSinceMs).toBe(1755769800000);
  });

  it('Feld fehlt ⇒ Feld fehlt (Alt-Backend ODER die Entity ist schlicht live — der Draht bleibt byte-gleich)', () => {
    expect(parseHomeRegistrySnapshot(bareBody())!.unassigned[0]).not.toHaveProperty('fromCacheSinceMs');
  });

  it.each([
    ['String statt Zahl', '1755769800000'],
    ['Null-Stempel', 0],
    ['negativ', -1],
    ['NaN', Number.NaN],
    ['Infinity', Number.POSITIVE_INFINITY],
  ])('kaputter Stempel (%s) ⇒ Feld fehlt lieber, als dass „Stand 01:00" von 1970 daraus wird', (_name, value) => {
    const body = bareBody();
    (body.unassigned[0] as Record<string, unknown>).fromCacheSinceMs = value;
    const got = parseHomeRegistrySnapshot(body)!.unassigned[0];
    expect(got).not.toHaveProperty('fromCacheSinceMs');
    // Der Rest der Entity bleibt gültig — ein kaputtes Zusatzfeld reißt sie nie mit.
    expect(got.entityId).toBe('vacuum.rob');
  });
});
