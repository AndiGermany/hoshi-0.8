/** @vitest-environment jsdom */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { RaeumeView } from '../views/RaeumeView';
import type { HomeRegistrySnapshot } from '../api/homeRegistry';

// ═════════════════════════════════════════════════════════════════════════════
//  RaeumeView — Grid-Sortierung + „Stille Räume"-Fach (Andi-Auftrag 2026-08-11:
//  „ich will die Räume richtig sortieren … so ist es nur eine lange Liste").
//  Konzept-Pfad 1(b) (keine Nutzungs-Naht ⇒ Geräteanzahl + Name) und §4
//  („Stille Räume" falten sich weg, außer während einer aktiven Suche/eines
//  Domain-Filters — kein Verstecken echter Treffer). Muster `raeume.test.tsx`
//  (renderToStaticMarkup) + `raeumeS1.test.tsx` (jsdom für Interaktion).
// ═════════════════════════════════════════════════════════════════════════════

function multiRoomSnapshot(): HomeRegistrySnapshot {
  return {
    areas: [
      {
        areaId: 'flur',
        label: 'Flur',
        entities: [{ entityId: 'sensor.flur_bewegung', domain: 'sensor', name: 'Bewegungsmelder Flur', labels: [] }],
      },
      { areaId: 'keller', label: 'Keller', entities: [] },
      {
        areaId: 'buero',
        label: 'Büro',
        entities: [
          { entityId: 'light.buero_1', domain: 'light', name: 'Schreibtischlampe', labels: [] },
          { entityId: 'light.buero_2', domain: 'light', name: 'Deckenlicht Büro', labels: [] },
          { entityId: 'switch.buero_3', domain: 'switch', name: 'Monitor-Steckdose', labels: [] },
          { entityId: 'sensor.buero_4', domain: 'sensor', name: 'Temperatursensor Büro', labels: [] },
          { entityId: 'binary_sensor.buero_5', domain: 'binary_sensor', name: 'Fensterkontakt Büro', labels: [] },
        ],
      },
      {
        areaId: 'kueche',
        label: 'Küche',
        entities: [
          { entityId: 'light.kueche_1', domain: 'light', name: 'Deckenlicht Küche', labels: [] },
          { entityId: 'switch.kueche_2', domain: 'switch', name: 'Kaffeemaschine', labels: [] },
          { entityId: 'sensor.kueche_3', domain: 'sensor', name: 'Feuchtesensor Küche', labels: [] },
        ],
      },
    ],
    unassigned: [],
  };
}

describe('RaeumeView — Standard-Rundgang (kein Filter): Sortierung nach Geräteanzahl + „Stille Räume"-Fach', () => {
  it('Räume mit mehr Geräten stehen VOR Räumen mit weniger — Büro(5) → Küche(3)', () => {
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: multiRoomSnapshot() }} />);
    expect(out.indexOf('Büro')).toBeGreaterThan(-1);
    expect(out.indexOf('Küche')).toBeGreaterThan(-1);
    expect(out.indexOf('Büro')).toBeLessThan(out.indexOf('Küche'));
  });

  it('Räume ohne Geräte-Aktivität (0/1 Gerät) landen im „Stille Räume"-Fach, NACH den aktiven Karten', () => {
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: multiRoomSnapshot() }} />);
    expect(out).toContain('Stille Räume (2)');
    const silentHeadingIdx = out.indexOf('Stille Räume (2)');
    // Beide aktiven Räume (Büro/Küche) stehen VOR der Stille-Räume-Kopfzeile.
    expect(out.indexOf('Büro')).toBeLessThan(silentHeadingIdx);
    expect(out.indexOf('Küche')).toBeLessThan(silentHeadingIdx);
    // Flur (1 Gerät) UND Keller (0 Geräte) stehen dahinter, im Fach selbst.
    expect(out.indexOf('Flur')).toBeGreaterThan(silentHeadingIdx);
    expect(out.indexOf('Keller')).toBeGreaterThan(silentHeadingIdx);
    // Innerhalb des Fachs gilt dieselbe Sortierung: Flur (1 Gerät) vor Keller (0).
    expect(out.indexOf('Flur')).toBeLessThan(out.indexOf('Keller'));
  });

  it('das Fach ist ein natives <details>, zugeklappt per Default (kein `open`-Attribut)', () => {
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: multiRoomSnapshot() }} />);
    expect(out).toContain('class="rooms__silentgroup"');
    expect(out).not.toContain('rooms__silentgroup" open');
  });

  it('keine stillen Räume im Snapshot ⇒ gar kein Fach (nichts Leeres angezeigt)', () => {
    const snap: HomeRegistrySnapshot = {
      areas: [
        { areaId: 'buero', label: 'Büro', entities: [{ entityId: 'light.a', domain: 'light', name: 'A', labels: [] }, { entityId: 'light.b', domain: 'light', name: 'B', labels: [] }] },
      ],
      unassigned: [],
    };
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: snap }} />);
    expect(out).not.toContain('rooms__silentgroup');
    expect(out).not.toContain('Stille Räume');
  });
});

describe('RaeumeView — aktiver Filter/Suche: „Stille Räume" faltet NICHT (kein Verstecken echter Treffer)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  beforeEach(async () => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<RaeumeView state={{ kind: 'live', data: multiRoomSnapshot() }} />);
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

  it('eine Suche, die einen Treffer im sonst „stillen" Flur liefert, zeigt ihn direkt im Raster — kein zugeklapptes Fach', async () => {
    const search = container.querySelector('.rooms__search') as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setter.call(search, 'bewegungsmelder');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(container.textContent).toContain('Bewegungsmelder Flur');
    expect(container.textContent).toContain('Flur');
    // Kein Stille-Räume-Fach während eines aktiven Filters.
    expect(container.querySelector('.rooms__silentgroup')).toBeNull();
    expect(container.textContent).not.toContain('Stille Räume');
  });
});

describe('RoomCard — Pille zeigt „x von y" NUR wenn der Filter die Karte tatsächlich ausdünnt', () => {
  function domainFixture(): HomeRegistrySnapshot {
    return {
      areas: [
        {
          areaId: 'buero',
          label: 'Büro',
          entities: [
            { entityId: 'light.a', domain: 'light', name: 'Deckenlicht', labels: [] },
            { entityId: 'light.b', domain: 'light', name: 'Stehlampe', labels: [] },
            { entityId: 'switch.c', domain: 'switch', name: 'Steckdose', labels: [] },
          ],
        },
      ],
      unassigned: [],
    };
  }

  it('ohne Filter: die knappe Form „3 Geräte" in der Karten-Pille (kein redundantes „3 von 3")', () => {
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: domainFixture() }} />);
    // Die Kopfzeilen-Wahrheit (RoomsToolbar.assignedSummary) sagt zwar ebenfalls
    // „3 von 3 …" — hier geht es NUR um die Karten-Pille (`tile__pill`).
    expect(out).toContain('<span class="tile__pill">3 Geräte</span>');
    expect(out).not.toContain('<span class="tile__pill">3 von 3');
  });

  it('mit aktivem Domain-Filter, der die Karte ausdünnt: „2 von 3 Geräten"', async () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => {
      root.render(<RaeumeView state={{ kind: 'live', data: domainFixture() }} />);
    });
    const btns = Array.from(container.querySelectorAll('.rooms__chip')) as HTMLButtonElement[];
    const lichtChip = btns.find((b) => b.textContent === 'Licht')!;
    await act(async () => {
      lichtChip.click();
    });
    expect(container.textContent).toContain('2 von 3 Geräten');
    await act(async () => root.unmount());
    container.remove();
  });
});
