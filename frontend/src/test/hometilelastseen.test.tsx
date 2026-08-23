/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { ClimateTile, VacuumTile } from '../components/HomeTileCards';
import {
  HOME_TILE_LAST_SEEN_KEY_PREFIX,
  homeTileLastSeenStorageKey,
  loadHomeTileLastSeen,
  saveHomeTileLastSeen,
} from '../hooks/useSettings';
import type { HomeRegistryState } from '../api/homeRegistry';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * **hometilelastseen.test** — die SCHREIB-Seite der Scheibe S2 „Ehrliche
 * Anwesenheit" (DESIGN-widgets-settings-2026-08-15 §2.4, Weg 1: FE-only).
 *
 * Warum eine eigene Datei mit jsdom: der Stempel wird in einem `useEffect`
 * gesetzt, und Effekte laufen im SSR-Pfad (`renderToStaticMarkup`, den
 * `hometilecards.test.tsx` benutzt) bewusst NICHT — das ist die Eigenschaft,
 * die einen statischen Render garantiert nur LESEN lässt. Die Render-Goldens
 * bleiben deshalb drüben, hier wird echt gemountet.
 *
 * Der Vertrag in einem Satz: eine Kachel merkt sich den Zeitpunkt, an dem ihre
 * Quelle ZULETZT wirklich geantwortet hat — nicht den, an dem sie zum ersten
 * Mal auftauchte. Ein einmal geschriebener Stempel würde mit jeder Minute
 * unwahrer; darum wird bei jedem Tick nachgeführt.
 */

/** In-Memory-Storage in DOM-`Storage`-Form (jsdoms eigener ist pro Datei geteilt). */
function memoryStorage(seed: Record<string, string> = {}): Storage {
  const m = new Map<string, string>(Object.entries(seed));
  return {
    get length() {
      return m.size;
    },
    clear: () => m.clear(),
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    key: (i: number) => Array.from(m.keys())[i] ?? null,
    removeItem: (k: string) => {
      m.delete(k);
    },
    setItem: (k: string, v: string) => {
      m.set(k, String(v));
    },
  };
}

const NOW = Date.parse('2026-08-15T09:00:00.000Z');
const MINUTE = 60_000;

const liveVacuum: HomeRegistryState = {
  kind: 'live',
  data: {
    areas: [],
    unassigned: [{ entityId: 'vacuum.rob', domain: 'vacuum', name: 'Rob', labels: [], state: 'docked' }],
  },
};
const liveClimate: HomeRegistryState = {
  kind: 'live',
  data: {
    areas: [
      {
        areaId: 'wz',
        label: 'Wohnzimmer',
        entities: [
          {
            entityId: 'climate.wz',
            domain: 'climate',
            name: 'Thermostat',
            labels: [],
            state: 'heat',
            attrs: { current_temperature: '21', temperature: '22' },
          },
        ],
      },
    ],
    unassigned: [],
  },
};
/** Echte Antwort, aber ohne brauchbare Quelle — der Rückfall-Fall. */
const emptyLive: HomeRegistryState = { kind: 'live', data: { areas: [], unassigned: [] } };

let container: HTMLDivElement;
let root: Root | null = null;

const render = async (el: React.ReactElement): Promise<void> => {
  if (!root) root = createRoot(container);
  await act(async () => {
    root!.render(el);
  });
};

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  vi.stubGlobal('localStorage', memoryStorage());
});
afterEach(async () => {
  if (root) {
    const r = root;
    await act(async () => r.unmount());
    root = null;
  }
  container.remove();
  vi.unstubAllGlobals();
});

describe('S2 — der Anwesenheits-Stempel wird geschrieben, wenn die Quelle wirklich antwortet', () => {
  it('lebende Sauger-Kachel ⇒ `hoshi.homeTiles.lastSeen.vacuum` trägt genau den Render-Zeitpunkt', async () => {
    await render(<VacuumTile registry={liveVacuum} nowMs={NOW} />);
    expect(localStorage.getItem('hoshi.homeTiles.lastSeen.vacuum')).toBe(String(NOW));
    expect(homeTileLastSeenStorageKey('vacuum')).toBe(`${HOME_TILE_LAST_SEEN_KEY_PREFIX}vacuum`);
  });

  it('lebende Klima-Kachel schreibt unter ihrem EIGENEN Schlüssel', async () => {
    await render(<ClimateTile registry={liveClimate} nowMs={NOW} />);
    expect(localStorage.getItem('hoshi.homeTiles.lastSeen.climate')).toBe(String(NOW));
    expect(localStorage.getItem('hoshi.homeTiles.lastSeen.vacuum')).toBeNull();
  });

  it('der Stempel wird bei jedem Minuten-Tick nachgeführt (sonst altert er zur Lüge)', async () => {
    await render(<VacuumTile registry={liveVacuum} nowMs={NOW} />);
    await render(<VacuumTile registry={liveVacuum} nowMs={NOW + 5 * MINUTE} />);
    expect(loadHomeTileLastSeen('vacuum')).toBe(NOW + 5 * MINUTE);
  });

  it('unerreichbare Kachel schreibt NICHTS — die Ausfall-Uhr läuft ab der letzten echten Sichtung', async () => {
    await render(<VacuumTile registry={liveVacuum} nowMs={NOW} />);
    await render(<VacuumTile registry={emptyLive} nowMs={NOW + 90 * MINUTE} />);
    expect(loadHomeTileLastSeen('vacuum')).toBe(NOW);
  });

  it('nie gesehene Quelle ⇒ gar kein Stempel (die Erstes-Erscheinen-Regel bleibt unberührt)', async () => {
    await render(<VacuumTile registry={emptyLive} nowMs={NOW} />);
    expect(loadHomeTileLastSeen('vacuum')).toBeNull();
    expect(localStorage.length).toBe(0);
  });

  it('ganzer Weg: gesehen → weg ⇒ die Kachel sagt selbst, seit wann', async () => {
    await render(<VacuumTile registry={liveVacuum} nowMs={NOW} />);
    await render(<VacuumTile registry={emptyLive} nowMs={NOW + 3 * 60 * MINUTE} />);
    expect(container.textContent).toContain('Nicht erreichbar — zuletzt gesehen vor 3 Std.');
    expect(container.querySelector('.idle__hometilestale')).not.toBeNull();
    expect(container.querySelector('.idle__hometileunavailable')).toBeNull();
  });
});

describe('S2 — der Stempel-Speicher ist defensiv', () => {
  it('Müll, Null und Negatives lesen sich als „weiß ich nicht", nicht als Dauer', () => {
    for (const junk of ['nope', '', '0', '-5', 'NaN', 'Infinity']) {
      vi.stubGlobal(
        'localStorage',
        memoryStorage({ [homeTileLastSeenStorageKey('vacuum')]: junk }),
      );
      expect(loadHomeTileLastSeen('vacuum'), junk).toBeNull();
    }
  });

  it('unbrauchbare Zeitpunkte werden gar nicht erst geschrieben', () => {
    saveHomeTileLastSeen('vacuum', Number.NaN);
    saveHomeTileLastSeen('vacuum', 0);
    saveHomeTileLastSeen('vacuum', -1);
    expect(localStorage.length).toBe(0);
  });

  it('blockierter/fehlender Storage bricht weder Lesen noch Schreiben', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    });
    expect(() => saveHomeTileLastSeen('vacuum', NOW)).not.toThrow();
    expect(loadHomeTileLastSeen('vacuum')).toBeNull();
  });
});
