/** @vitest-environment jsdom */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { RaeumeView, type RaeumeEdit } from '../views/RaeumeView';
import type { HomeRegistryEntity, HomeRegistrySnapshot } from '../api/homeRegistry';
import { domainBucket, matchesDomainFilter, matchesRoomSearch } from '../components/roomsFilter';
import { suggestAreaId } from '../components/roomsSuggest';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ═════════════════════════════════════════════════════════════════════════════
//  Scheibe 1 des Räume-Verwaltungs-Konzepts (`.orch-bus/inbox/20260727-2223-
//  cowork-raeume-verwaltung-konzept.md`, §6) — Kopfzeilen-Wahrheit, Suche,
//  Domänen-Chips (inkl. „switch → Rest"), Inbox-Vorbelegung, „Nichts zu tun"-
//  Ruhe-Zustand und die Einklapp-Logik der Raum-Karten. Rein-Helfer-Tests
//  zuerst (kein DOM nötig), danach Komponenten-Tests im Stil von
//  `homeedit.test.tsx` (createRoot + act für Klicks/Eingaben).
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
//  roomsFilter.ts — reine Helfer
// ─────────────────────────────────────────────────────────────────────────────

describe('roomsFilter.domainBucket — GENAU vier Eimer, switch → Rest', () => {
  it('ordnet jede entity_id-Domain ihrem Eimer zu', () => {
    expect(domainBucket('light')).toBe('licht');
    expect(domainBucket('climate')).toBe('klima');
    expect(domainBucket('cover')).toBe('klima');
    expect(domainBucket('sensor')).toBe('sensoren');
    expect(domainBucket('binary_sensor')).toBe('sensoren');
    // Andi-Vorgabe, ausdrücklich: switch gehört NICHT zu Licht, sondern zu Rest.
    expect(domainBucket('switch')).toBe('rest');
    expect(domainBucket('lock')).toBe('rest');
    expect(domainBucket('media_player')).toBe('rest');
  });

  it('matchesDomainFilter: „alle" lässt jede Domain durch, sonst muss der Eimer exakt passen', () => {
    expect(matchesDomainFilter('switch', 'alle')).toBe(true);
    expect(matchesDomainFilter('switch', 'licht')).toBe(false);
    expect(matchesDomainFilter('switch', 'rest')).toBe(true);
    expect(matchesDomainFilter('light', 'licht')).toBe(true);
    expect(matchesDomainFilter('light', 'rest')).toBe(false);
  });
});

describe('roomsFilter.matchesRoomSearch — Suche über Name, entity_id, Raumname', () => {
  const entity: HomeRegistryEntity = {
    entityId: 'light.hue_wohnzimmer_2',
    domain: 'light',
    name: 'Deckenlicht',
    labels: [],
  };

  it('Name trifft', () => {
    expect(matchesRoomSearch(entity, 'Wohnzimmer', 'decken')).toBe(true);
  });
  it('entity_id trifft, auch wenn der Name selbst nicht matcht', () => {
    expect(matchesRoomSearch(entity, 'Wohnzimmer', 'hue_wohnzimmer')).toBe(true);
  });
  it('Raumname trifft, auch wenn weder Name noch entity_id ihn enthalten', () => {
    const e: HomeRegistryEntity = { entityId: 'light.x', domain: 'light', name: 'Y', labels: [] };
    expect(matchesRoomSearch(e, 'Wohnzimmer', 'wohnzimmer')).toBe(true);
  });
  it('kein Treffer in keinem der drei Felder ⇒ false', () => {
    expect(matchesRoomSearch(entity, 'Wohnzimmer', 'kueche')).toBe(false);
  });
  it('leere/nur-Whitespace-Query ⇒ immer Treffer (kein Filter aktiv)', () => {
    expect(matchesRoomSearch(entity, 'Wohnzimmer', '   ')).toBe(true);
  });
  it('unassigned (roomLabel=null): Raumname-Suche greift naturgemäß nicht, Name/entity_id weiterhin', () => {
    expect(matchesRoomSearch(entity, null, 'decken')).toBe(true);
    expect(matchesRoomSearch(entity, null, 'kueche')).toBe(false);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  roomsSuggest.ts — Vorbelegungs-Heuristik der Inbox
// ─────────────────────────────────────────────────────────────────────────────

describe('roomsSuggest.suggestAreaId — Vorschlag, NIE automatisch geschrieben', () => {
  const areas = [
    { areaId: 'wohnzimmer', label: 'Wohnzimmer' },
    { areaId: 'kueche', label: 'Küche' },
  ];

  it('Heuristik-Treffer über den Gerätenamen', () => {
    const e: HomeRegistryEntity = { entityId: 'light.hue_2', domain: 'light', name: 'Hue Wohnzimmer 2', labels: [] };
    expect(suggestAreaId(e, areas)).toBe('wohnzimmer');
  });

  it('Heuristik-Treffer über die entity_id (der Name allein sagt nichts)', () => {
    const e: HomeRegistryEntity = { entityId: 'switch.kueche_kaffee', domain: 'switch', name: 'Kaffeemaschine', labels: [] };
    expect(suggestAreaId(e, areas)).toBe('kueche');
  });

  it('Nicht-Treffer ⇒ leerer Vorschlag (nie raten)', () => {
    const e: HomeRegistryEntity = { entityId: 'light.hue_iris', domain: 'light', name: 'Hue Iris', labels: [] };
    expect(suggestAreaId(e, areas)).toBe('');
  });

  it('mehrdeutiger Treffer (zwei Räume passen) ⇒ lieber leer als geraten', () => {
    const ambiguous = [
      { areaId: 'wohnzimmer', label: 'Wohnzimmer' },
      { areaId: 'wohnzimmer-nord', label: 'Wohnzimmer Nord' },
    ];
    const e: HomeRegistryEntity = {
      entityId: 'light.wohnzimmer_nord_lampe',
      domain: 'light',
      name: 'Lampe Wohnzimmer Nord',
      labels: [],
    };
    expect(suggestAreaId(e, ambiguous)).toBe('');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  RaeumeView (live) — Kopfzeilen-Wahrheit aus dem Fixture-Payload
// ─────────────────────────────────────────────────────────────────────────────

describe('RaeumeView — Kopfzeile sagt die Zuordnungs-Wahrheit (echte Zahlen, kein Erfinden)', () => {
  it('„n von m Geräten … · k Räume" — n/m/k kommen 1:1 aus dem Snapshot', () => {
    const snap: HomeRegistrySnapshot = {
      areas: [
        {
          areaId: 'wohnzimmer',
          label: 'Wohnzimmer',
          entities: [
            { entityId: 'light.a', domain: 'light', name: 'A', labels: [] },
            { entityId: 'light.b', domain: 'light', name: 'B', labels: [] },
          ],
        },
        { areaId: 'kueche', label: 'Küche', entities: [{ entityId: 'switch.c', domain: 'switch', name: 'C', labels: [] }] },
      ],
      unassigned: [{ entityId: 'climate.d', domain: 'climate', name: 'D', labels: [] }],
    };
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: snap }} />);
    // 3 zugeordnete Geräte (A, B, C) von 4 gesamt (+ D unassigned), 2 Räume.
    expect(out).toContain('3 von 4 Geräten einem Raum zugeordnet · 2 Räume');
  });

  it('die Kopfzeile bleibt WAHR, auch wenn ein aktiver Filter die Ansicht ausdünnt', async () => {
    const snap: HomeRegistrySnapshot = {
      areas: [
        {
          areaId: 'wohnzimmer',
          label: 'Wohnzimmer',
          entities: [{ entityId: 'light.a', domain: 'light', name: 'Deckenlicht', labels: [] }],
        },
      ],
      unassigned: [],
    };
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => {
      root.render(<RaeumeView state={{ kind: 'live', data: snap }} />);
    });
    const search = container.querySelector('.rooms__search') as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setter.call(search, 'nichts-passt-hier');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    // Kein Treffer für den Filter — aber die Kopfzeile lügt nicht plötzlich „0 von 0".
    expect(container.textContent).toContain('1 von 1 Geräten einem Raum zugeordnet · 1 Raum');
    await act(async () => root.unmount());
    container.remove();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  RaeumeView (live) — Domänen-Chips filtern korrekt (inkl. switch → Rest)
// ─────────────────────────────────────────────────────────────────────────────

function domainFixtureSnapshot(): HomeRegistrySnapshot {
  return {
    areas: [
      {
        areaId: 'wohnzimmer',
        label: 'Wohnzimmer',
        entities: [
          { entityId: 'light.deckenlicht', domain: 'light', name: 'Deckenlicht', labels: [] },
          { entityId: 'switch.wandschalter', domain: 'switch', name: 'Wandschalter', labels: [] },
          { entityId: 'climate.heizungsthermostat', domain: 'climate', name: 'Heizungsthermostat', labels: [] },
          { entityId: 'cover.jalousie', domain: 'cover', name: 'Jalousie', labels: [] },
          { entityId: 'sensor.feuchtesensor', domain: 'sensor', name: 'Feuchtesensor', labels: [] },
          { entityId: 'binary_sensor.fensterkontakt', domain: 'binary_sensor', name: 'Fensterkontakt', labels: [] },
          { entityId: 'lock.tuerschloss', domain: 'lock', name: 'Tuerschloss', labels: [] },
        ],
      },
    ],
    unassigned: [],
  };
}

const ALL_NAMES = [
  'Deckenlicht',
  'Wandschalter',
  'Heizungsthermostat',
  'Jalousie',
  'Feuchtesensor',
  'Fensterkontakt',
  'Tuerschloss',
];

describe('RaeumeView — Domänen-Chips filtern die Raum-Karten (kein zweiter Baum, nur ein Prädikat)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const clickChip = async (label: string): Promise<void> => {
    const btns = Array.from(container.querySelectorAll('.rooms__chip')) as HTMLButtonElement[];
    const btn = btns.find((b) => b.textContent === label);
    if (!btn) throw new Error(`Kein Chip „${label}" gefunden`);
    await act(async () => {
      btn.click();
    });
  };

  beforeEach(async () => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<RaeumeView state={{ kind: 'live', data: domainFixtureSnapshot() }} />);
    });
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
  });

  it('„Alle" (Default): alle sieben Geräte sind sichtbar', () => {
    for (const n of ALL_NAMES) expect(container.textContent).toContain(n);
  });

  it('„Licht": nur das Licht-Gerät, switch/climate/… fallen raus', async () => {
    await clickChip('Licht');
    expect(container.textContent).toContain('Deckenlicht');
    for (const n of ALL_NAMES.filter((n) => n !== 'Deckenlicht')) {
      expect(container.textContent).not.toContain(n);
    }
  });

  it('„Klima": climate + cover, sonst nichts', async () => {
    await clickChip('Klima');
    expect(container.textContent).toContain('Heizungsthermostat');
    expect(container.textContent).toContain('Jalousie');
    for (const n of ['Deckenlicht', 'Wandschalter', 'Feuchtesensor', 'Fensterkontakt', 'Tuerschloss']) {
      expect(container.textContent).not.toContain(n);
    }
  });

  it('„Sensoren": sensor + binary_sensor, sonst nichts', async () => {
    await clickChip('Sensoren');
    expect(container.textContent).toContain('Feuchtesensor');
    expect(container.textContent).toContain('Fensterkontakt');
    for (const n of ['Deckenlicht', 'Wandschalter', 'Heizungsthermostat', 'Jalousie', 'Tuerschloss']) {
      expect(container.textContent).not.toContain(n);
    }
  });

  it('„Rest": switch + lock — DER Beleg, dass switch NICHT unter Licht läuft', async () => {
    await clickChip('Rest');
    expect(container.textContent).toContain('Wandschalter');
    expect(container.textContent).toContain('Tuerschloss');
    for (const n of ['Deckenlicht', 'Heizungsthermostat', 'Jalousie', 'Feuchtesensor', 'Fensterkontakt']) {
      expect(container.textContent).not.toContain(n);
    }
  });

  it('zurück auf „Alle" zeigt wieder alle sieben', async () => {
    await clickChip('Licht');
    await clickChip('Alle');
    for (const n of ALL_NAMES) expect(container.textContent).toContain(n);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  RaeumeView (live) — Suche über Name/entity_id/Raumname + ehrliche Leermeldung
// ─────────────────────────────────────────────────────────────────────────────

function searchFixtureSnapshot(): HomeRegistrySnapshot {
  return {
    areas: [
      { areaId: 'wohnzimmer', label: 'Wohnzimmer', entities: [{ entityId: 'light.hue1', domain: 'light', name: 'Deckenlicht', labels: [] }] },
      { areaId: 'kueche', label: 'Küche', entities: [{ entityId: 'switch.kaffee', domain: 'switch', name: 'Kaffeemaschine', labels: [] }] },
    ],
    unassigned: [],
  };
}

describe('RaeumeView — Live-Suche filtert über Name, entity_id UND Raumname', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const search = async (query: string): Promise<void> => {
    const input = container.querySelector('.rooms__search') as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setter.call(input, query);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
  };

  beforeEach(async () => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<RaeumeView state={{ kind: 'live', data: searchFixtureSnapshot() }} />);
    });
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
  });

  it('Suche über den Gerätenamen', async () => {
    await search('decken');
    expect(container.textContent).toContain('Deckenlicht');
    expect(container.textContent).not.toContain('Kaffeemaschine');
    expect(container.textContent).not.toContain('Küche');
  });

  it('Suche über die entity_id', async () => {
    await search('switch.kaffee');
    expect(container.textContent).toContain('Kaffeemaschine');
    expect(container.textContent).not.toContain('Deckenlicht');
  });

  it('Suche über den Raumnamen — trifft auch Geräte, deren Name den Raum nicht enthält', async () => {
    await search('küche');
    expect(container.textContent).toContain('Kaffeemaschine');
    expect(container.textContent).not.toContain('Deckenlicht');
  });

  it('kein Treffer irgendwo ⇒ ehrliche Leermeldung statt leerer Fläche', async () => {
    await search('zzz-nichts-passt');
    expect(container.textContent).toContain('Kein Treffer für „zzz-nichts-passt".');
    expect(container.textContent).not.toContain('Deckenlicht');
    expect(container.textContent).not.toContain('Kaffeemaschine');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  „Braucht dich"-Inbox — Vorbelegung (Treffer + Nicht-Treffer) + „Nichts zu tun"
// ─────────────────────────────────────────────────────────────────────────────

const inboxEditProp = (over: Partial<RaeumeEdit> = {}): RaeumeEdit => ({
  enabled: true,
  areas: [
    { areaId: 'wohnzimmer', label: 'Wohnzimmer' },
    { areaId: 'kueche', label: 'Küche' },
  ],
  onAssign: () => {},
  ...over,
});

describe('InboxCard — Raum-Dropdown ist vorbelegt (Vorschlag), aber NIE automatisch geschrieben', () => {
  it('Heuristik-Treffer: der Vorschlag steht schon als ausgewählte Option da', () => {
    const snap: HomeRegistrySnapshot = {
      areas: [],
      unassigned: [{ entityId: 'light.hue_wohnzimmer_2', domain: 'light', name: 'Hue Wohnzimmer 2', labels: [] }],
    };
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: snap }} edit={inboxEditProp()} />);
    expect(out).toContain('<option value="wohnzimmer" selected="">Wohnzimmer</option>');
    // Der Platzhalter ist NICHT die ausgewählte Option, wenn ein Vorschlag greift.
    expect(out).not.toContain('<option value="" disabled selected="">');
  });

  it('kein Heuristik-Treffer: der Platzhalter „Raum wählen…" bleibt ausgewählt, nichts geraten', () => {
    const snap: HomeRegistrySnapshot = {
      areas: [],
      unassigned: [{ entityId: 'light.hue_iris', domain: 'light', name: 'Hue Iris', labels: [] }],
    };
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: snap }} edit={inboxEditProp()} />);
    expect(out).toContain('<option value="" selected="">Raum wählen…</option>');
  });

  it('„Nichts zu tun": echt keine Lücke ⇒ ruhige Zeile, kein Amber — auch mit aktivem Edit-Flag', () => {
    const snap: HomeRegistrySnapshot = {
      areas: [{ areaId: 'wohnzimmer', label: 'Wohnzimmer', entities: [{ entityId: 'light.a', domain: 'light', name: 'A', labels: [] }] }],
      unassigned: [],
    };
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: snap }} edit={inboxEditProp()} />);
    expect(out).toContain('Braucht dich');
    expect(out).toContain('Nichts zu tun.');
    expect(out).not.toContain('room__name--gap');
    expect(out).not.toContain('inbox__select');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Raum-Karte — Einklapp-Logik ab 8 Zeilen
// ─────────────────────────────────────────────────────────────────────────────

function manyDevicesSnapshot(count: number): HomeRegistrySnapshot {
  return {
    areas: [
      {
        areaId: 'wohnzimmer',
        label: 'Wohnzimmer',
        entities: Array.from({ length: count }, (_, i) => ({
          entityId: `light.g${i + 1}`,
          domain: 'light',
          name: `Gerät ${i + 1}`,
          labels: [],
        })),
      },
    ],
    unassigned: [],
  };
}

describe('RoomCard — ab 8 Zeilen einklappen, „die übrigen n zeigen"', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
  });

  it('7 Geräte (unter der Schwelle): keine Einklapp-UI, alle sieben sichtbar', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<RaeumeView state={{ kind: 'live', data: manyDevicesSnapshot(7) }} />);
    });
    expect(container.querySelectorAll('.room__device').length).toBe(7);
    expect(container.querySelector('.room__more')).toBeNull();
  });

  it('9 Geräte: erst 8 sichtbar + „die übrigen 1 zeigen", Klick zeigt alle neun, nochmal Klick klappt wieder ein', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<RaeumeView state={{ kind: 'live', data: manyDevicesSnapshot(9) }} />);
    });
    expect(container.querySelectorAll('.room__device').length).toBe(8);
    const more = container.querySelector('.room__more') as HTMLButtonElement;
    expect(more).not.toBeNull();
    expect(more.textContent).toContain('die übrigen 1 zeigen');
    expect(more.getAttribute('aria-expanded')).toBe('false');

    await act(async () => {
      more.click();
    });
    expect(container.querySelectorAll('.room__device').length).toBe(9);
    const less = container.querySelector('.room__more') as HTMLButtonElement;
    expect(less.textContent).toContain('Einklappen');
    expect(less.getAttribute('aria-expanded')).toBe('true');

    await act(async () => {
      less.click();
    });
    expect(container.querySelectorAll('.room__device').length).toBe(8);
    expect(container.querySelector('.room__more')!.textContent).toContain('die übrigen 1 zeigen');
  });
});
