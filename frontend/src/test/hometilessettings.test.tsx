/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { HomeTilesSection } from '../components/SettingsPanel';
import {
  ALARM_TILE_STORAGE_KEY,
  CLIMATE_TILE_STORAGE_KEY,
  CLOCK_TILE_STORAGE_KEY,
  CURRENT_AFFAIRS_TILE_STORAGE_KEY,
  SCHEDULED_TILE_STORAGE_KEY,
  SHOPPING_TILE_STORAGE_KEY,
  VACUUM_TILE_STORAGE_KEY,
  WEATHER_TILE_STORAGE_KEY,
  loadAlarmTileEnabled,
  loadClimateTileEnabled,
  loadClockTileEnabled,
  loadCurrentAffairsTileEnabled,
  loadScheduledTileEnabled,
  loadShoppingTileEnabled,
  loadVacuumTileEnabled,
  loadWeatherTileEnabled,
  useHomeTiles,
} from '../hooks/useSettings';
import { HOME_LAYOUT_STORAGE_KEY } from '../hooks/useHomeLayout';
import type { HomeRegistryEntity, HomeRegistrySnapshot } from '../api/homeRegistry';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * **hometilessettings.test** — der neue „Zuhause-Kacheln"-Block im
 * SettingsPanel (Andi-Auftrag 2026-08-11): je Kachel EIN Schalter, der NUR
 * rendert, wenn seine Datenquelle real ist (vacuum gefunden / ≥1 Raum mit
 * climate) — UND die Persistenz (localStorage, Muster
 * `escalationsettings.test.tsx`/`useEscalationSeconds`), Default AUS.
 *
 * NACHTRAG (Lagebild-Schalter): der dritte Schalter der Gruppe hat KEIN
 * Quellen-Gate — seine Quelle ist ein fester Endpoint, über den ein
 * Registry-Snapshot nichts aussagen kann, und ein Schalter, der genau dann
 * verschwindet, wenn man ihn sucht, beantwortet die Frage „wo aktiviere ich
 * die Nachrichten?" gerade nicht. Er ist darum IMMER da und steht per Default
 * auf AN. Die Zähl-Erwartungen unten sind entsprechend um genau diesen einen
 * Schalter erhöht; die HA-Gates selbst sind unverändert scharf.
 *
 * NACHTRAG W2 (`vault/tracks/DESIGN-widget-raster-2026-08-18.md` §4.3): fünf
 * weitere, quellenlose Schalter (Uhr, Wecker, Wetter, Läuft, Einkauf) — alle
 * Default AN, alle IMMER da. Die Zähl-Erwartungen unten sind um genau diese
 * fünf erhöht; die zwei HA-Gates (Sauger/Klima) sind unverändert scharf.
 */

/**
 * Die Schalter-Labels (de-Katalog, wörtlich). Der Code-Name bleibt
 * `currentAffairs`/„Lagebild"; nutzersichtbar heißt es „Nachrichten"
 * (Andi-Korrektur 2026-08-15).
 */
const NEWS_SWITCH = 'Nachrichten';
const CLOCK_SWITCH = 'Uhr';
const ALARM_SWITCH = 'Wecker';
const WEATHER_SWITCH = 'Wetter';
const SCHEDULED_SWITCH = 'Läuft';
const SHOPPING_SWITCH = 'Einkauf';
/** Die fünf Schalter, die IMMER da sind (kein Quellen-Gate), Default AN. */
const ALWAYS_ON_SWITCHES = [CLOCK_SWITCH, ALARM_SWITCH, WEATHER_SWITCH, SCHEDULED_SWITCH, SHOPPING_SWITCH, NEWS_SWITCH];

/** In-Memory-Storage in DOM-`Storage`-Form (node kennt kein echtes localStorage). */
function memoryStorage(): Storage {
  const m = new Map<string, string>();
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

const entity = (over: Partial<HomeRegistryEntity> = {}): HomeRegistryEntity => ({
  entityId: 'vacuum.rob',
  domain: 'vacuum',
  name: 'Rob',
  labels: [],
  ...over,
});

const snapshot = (over: Partial<HomeRegistrySnapshot> = {}): HomeRegistrySnapshot => ({
  areas: [],
  unassigned: [],
  ...over,
});

function okRegistry(data: HomeRegistrySnapshot) {
  return { ok: true, status: 200, json: () => Promise.resolve(data) };
}

describe('HomeTilesSection — HA-Schalter NUR bei realer Quelle, Default AUS, persistiert', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeTilesSection />);
    });
    // die Registry-Fetch-Kette (useHomeRegistry) läuft async — einmal flushen
    // (Muster `homeedit.test.tsx#flush`).
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
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
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('weder Sauger noch Klima-Raum ⇒ KEIN Geräte-Schalter (nur die sechs quellenlosen Schalter)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(snapshot())));
    await mount();
    expect(container.textContent).not.toContain('Sauger-Kachel');
    expect(container.textContent).not.toContain('Klima-Kachel');
    for (const label of ALWAYS_ON_SWITCHES) expect(container.textContent).toContain(label);
    expect(container.querySelectorAll('[role="switch"]')).toHaveLength(6); // Uhr/Wecker/Wetter/Läuft/Einkauf/Lagebild
  });

  it('nur ein Sauger real ⇒ zusätzlich der Sauger-Schalter erscheint', async () => {
    const data = snapshot({ unassigned: [entity()] });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(data)));
    await mount();
    expect(container.textContent).toContain('Sauger-Kachel');
    expect(container.textContent).not.toContain('Klima-Kachel');
    expect(container.querySelectorAll('[role="switch"]')).toHaveLength(7); // 6 quellenlose + Sauger
  });

  it('nur ein Raum mit climate real ⇒ zusätzlich der Klima-Schalter erscheint', async () => {
    const data = snapshot({
      areas: [
        {
          areaId: 'wz',
          label: 'Wohnzimmer',
          entities: [entity({ entityId: 'climate.wz', domain: 'climate', state: 'heat' })],
        },
      ],
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(data)));
    await mount();
    expect(container.textContent).toContain('Klima-Kachel');
    expect(container.textContent).not.toContain('Sauger-Kachel');
    expect(container.querySelectorAll('[role="switch"]')).toHaveLength(7); // 6 quellenlose + Klima
  });

  it('climate-Entity OHNE Raum (unassigned) zählt NICHT — die Kachel ist erst sinnvoll, wenn Räume zugewiesen sind', async () => {
    const data = snapshot({
      unassigned: [entity({ entityId: 'climate.lose', domain: 'climate', state: 'heat' })],
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(data)));
    await mount();
    expect(container.textContent).not.toContain('Klima-Kachel');
  });

  it('beide Quellen real ⇒ acht Schalter insgesamt, Sauger/Klima Default AUS, ein Klick persistiert an', async () => {
    const data = snapshot({
      areas: [
        {
          areaId: 'wz',
          label: 'Wohnzimmer',
          entities: [entity({ entityId: 'climate.wz', domain: 'climate', state: 'heat' })],
        },
      ],
      unassigned: [entity()],
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(data)));
    await mount();

    const switches = container.querySelectorAll<HTMLButtonElement>('[role="switch"]');
    expect(switches).toHaveLength(8); // Uhr/Wecker/Wetter/Läuft/Einkauf/Sauger/Klima/Lagebild
    for (const sw of Array.from(switches)) {
      // Nur die beiden HA-Kacheln starten AUS; die sechs quellenlosen sind Default AN.
      const expected = sw.getAttribute('aria-label') === 'Sauger-Kachel' || sw.getAttribute('aria-label') === 'Klima-Kachel' ? 'false' : 'true';
      expect(sw.getAttribute('aria-checked')).toBe(expected);
    }
    expect(loadVacuumTileEnabled()).toBe(false);
    expect(loadClimateTileEnabled()).toBe(false);

    const vacuumSwitch = container.querySelector<HTMLButtonElement>('[aria-label="Sauger-Kachel"]')!;
    await act(async () => {
      vacuumSwitch.click();
    });
    expect(loadVacuumTileEnabled()).toBe(true);
    expect(loadClimateTileEnabled()).toBe(false); // der zweite Schalter bleibt unberührt
    expect(vacuumSwitch.getAttribute('aria-checked')).toBe('true');
  });

  it('alle acht Schalter sind einzeln schaltbar, ohne einander zu berühren', async () => {
    const data = snapshot({
      areas: [
        {
          areaId: 'wz',
          label: 'Wohnzimmer',
          entities: [entity({ entityId: 'climate.wz', domain: 'climate', state: 'heat' })],
        },
      ],
      unassigned: [entity()],
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(data)));
    await mount();

    const byLabel = (label: string) => container.querySelector<HTMLButtonElement>(`[aria-label="${label}"]`)!;
    const clockSwitch = byLabel(CLOCK_SWITCH);
    const weatherSwitch = byLabel(WEATHER_SWITCH);

    expect(loadClockTileEnabled()).toBe(true);
    await act(async () => {
      clockSwitch.click();
    });
    expect(loadClockTileEnabled()).toBe(false);
    expect(clockSwitch.getAttribute('aria-checked')).toBe('false');
    // die restlichen sieben Schalter bleiben unberührt.
    expect(loadWeatherTileEnabled()).toBe(true);
    expect(loadScheduledTileEnabled()).toBe(true);
    expect(loadShoppingTileEnabled()).toBe(true);
    expect(loadAlarmTileEnabled()).toBe(true);
    expect(loadCurrentAffairsTileEnabled()).toBe(true);
    expect(loadVacuumTileEnabled()).toBe(false);
    expect(loadClimateTileEnabled()).toBe(false);

    await act(async () => {
      weatherSwitch.click();
    });
    expect(loadWeatherTileEnabled()).toBe(false);
    expect(loadClockTileEnabled()).toBe(false); // bleibt vom ersten Klick
    expect(loadScheduledTileEnabled()).toBe(true); // unberührt
  });

  it('Krone-Gruppe (Uhr/Wecker) ist von der Bühne-Gruppe abgesetzt', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(snapshot())));
    await mount();

    expect(container.textContent).toContain('Kopfzeile');
    expect(container.textContent).toContain('Bühne');

    const groups = container.querySelectorAll('.settings__widgetgroup');
    expect(groups).toHaveLength(2);
    const crown = groups[0] as HTMLElement;
    const stage = groups[1] as HTMLElement;
    expect(crown.textContent).toContain(CLOCK_SWITCH);
    expect(crown.textContent).toContain(ALARM_SWITCH);
    // Die Krone trägt NICHT die Bühnen-Widgets.
    expect(crown.textContent).not.toContain(WEATHER_SWITCH);
    expect(crown.querySelectorAll('[role="switch"]')).toHaveLength(2);
    expect(stage.textContent).toContain(WEATHER_SWITCH);
    expect(stage.textContent).toContain(NEWS_SWITCH);
    expect(stage.textContent).not.toContain(CLOCK_SWITCH);
  });

  it('die fünf neuen localStorage-Keys sind stabil benannt (Muster VACUUM_TILE_STORAGE_KEY)', () => {
    expect(CLOCK_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.uhr');
    expect(ALARM_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.wecker');
    expect(WEATHER_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.wetter');
    expect(SCHEDULED_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.laeuft');
    expect(SHOPPING_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.einkauf');
  });

  it('Registry aus (404)/nicht erreichbar (5xx) ⇒ ebenfalls kein Geräte-Schalter (nie „auf Verdacht")', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    await mount();
    expect(container.textContent).not.toContain('Sauger-Kachel');
    expect(container.textContent).not.toContain('Klima-Kachel');
    expect(container.querySelectorAll('[role="switch"]')).toHaveLength(6); // die sechs quellenlosen

    root && (await act(async () => root!.unmount()));
    root = null;
    container.remove();
    container = document.createElement('div');
    document.body.appendChild(container);

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 502 }));
    await mount();
    expect(container.textContent).not.toContain('Sauger-Kachel');
    expect(container.querySelectorAll('[role="switch"]')).toHaveLength(6);
  });

  it('Lagebild-Schalter: OHNE jede HA-Quelle da, Default AN, ein Klick persistiert AUS', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(snapshot())));
    await mount();

    const newsSwitch = container.querySelector<HTMLButtonElement>(`[aria-label="${NEWS_SWITCH}"]`)!;
    expect(newsSwitch.getAttribute('aria-checked')).toBe('true');
    expect(loadCurrentAffairsTileEnabled()).toBe(true);

    await act(async () => {
      newsSwitch.click();
    });
    expect(newsSwitch.getAttribute('aria-checked')).toBe('false');
    expect(loadCurrentAffairsTileEnabled()).toBe(false);
    // Die beiden HA-Flags bleiben unberührt (drei unabhängige Schlüssel).
    expect(loadVacuumTileEnabled()).toBe(false);
    expect(loadClimateTileEnabled()).toBe(false);
  });

  it('der Lagebild-Hinweis sagt ehrlich, was der Schalter NICHT tut (Stimme + Server-Feature)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(snapshot())));
    await mount();
    const text = container.textContent ?? '';
    expect(text).toContain('regelt nur die Anzeige');
    expect(text).toContain('nachfragen kannst du Hoshi weiterhin jederzeit');
    expect(text).toContain('entscheidet der Server');
  });

  it('localStorage-Keys sind stabil benannt (Regression, Muster ESCALATION_STORAGE_KEY)', () => {
    expect(VACUUM_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.vacuum');
    expect(CLIMATE_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.climate');
    expect(CURRENT_AFFAIRS_TILE_STORAGE_KEY).toBe('hoshi.homeTiles.currentAffairs');
  });
});

describe('useHomeTiles — Hook ändert + persistiert beide Flags unabhängig (Muster useEscalationSeconds)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  function Host() {
    const {
      vacuumEnabled,
      setVacuumEnabled,
      climateEnabled,
      setClimateEnabled,
      currentAffairsEnabled,
      setCurrentAffairsEnabled,
    } = useHomeTiles();
    return (
      <div>
        <span data-testid="vac">{String(vacuumEnabled)}</span>
        <span data-testid="cli">{String(climateEnabled)}</span>
        <span data-testid="news">{String(currentAffairsEnabled)}</span>
        <button type="button" data-testid="toggleVac" onClick={() => setVacuumEnabled(!vacuumEnabled)} />
        <button type="button" data-testid="toggleCli" onClick={() => setClimateEnabled(!climateEnabled)} />
        <button
          type="button"
          data-testid="toggleNews"
          onClick={() => setCurrentAffairsEnabled(!currentAffairsEnabled)}
        />
      </div>
    );
  }

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
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
    vi.unstubAllGlobals();
  });

  it('Default AUS für beide; jeder Schalter persistiert unabhängig vom anderen', async () => {
    root = createRoot(container);
    await act(async () => root!.render(<Host />));
    const vac = () => container.querySelector('[data-testid="vac"]')?.textContent;
    const cli = () => container.querySelector('[data-testid="cli"]')?.textContent;
    expect(vac()).toBe('false');
    expect(cli()).toBe('false');

    await act(async () => container.querySelector<HTMLButtonElement>('[data-testid="toggleVac"]')!.click());
    expect(vac()).toBe('true');
    expect(cli()).toBe('false'); // unberührt
    expect(loadVacuumTileEnabled()).toBe(true);
    expect(loadClimateTileEnabled()).toBe(false);

    await act(async () => container.querySelector<HTMLButtonElement>('[data-testid="toggleCli"]')!.click());
    expect(cli()).toBe('true');
    expect(loadClimateTileEnabled()).toBe(true);
  });

  it('Lagebild-Flag: Default AN, schaltet unabhängig ab und wieder an', async () => {
    root = createRoot(container);
    await act(async () => root!.render(<Host />));
    const news = () => container.querySelector('[data-testid="news"]')?.textContent;
    const toggle = async () =>
      act(async () => container.querySelector<HTMLButtonElement>('[data-testid="toggleNews"]')!.click());

    expect(news()).toBe('true'); // Default AN — anders als Sauger/Klima
    expect(loadCurrentAffairsTileEnabled()).toBe(true);

    await toggle();
    expect(news()).toBe('false');
    expect(loadCurrentAffairsTileEnabled()).toBe(false);
    expect(loadVacuumTileEnabled()).toBe(false); // unberührt
    expect(loadClimateTileEnabled()).toBe(false);

    await toggle();
    expect(news()).toBe('true');
    expect(loadCurrentAffairsTileEnabled()).toBe(true);
  });
});

describe('useHomeTiles — generisches enabled/setEnabled-Paar (W2, alle acht Widgets)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  function Host() {
    const { enabled, setEnabled } = useHomeTiles();
    return (
      <div>
        {(Object.keys(enabled) as Array<keyof typeof enabled>).map((id) => (
          <span key={id} data-testid={`enabled-${id}`}>
            {String(enabled[id])}
          </span>
        ))}
        <button type="button" data-testid="toggle-wetter" onClick={() => setEnabled('wetter', !enabled.wetter)} />
        <button type="button" data-testid="toggle-vacuum" onClick={() => setEnabled('vacuum', !enabled.vacuum)} />
      </div>
    );
  }

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
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
    vi.unstubAllGlobals();
  });

  it('das Record trägt alle acht Ids mit den richtigen Defaults (sechs AN, Sauger/Klima AUS)', async () => {
    root = createRoot(container);
    await act(async () => root!.render(<Host />));
    const value = (id: string) => container.querySelector(`[data-testid="enabled-${id}"]`)?.textContent;
    expect(value('uhr')).toBe('true');
    expect(value('wecker')).toBe('true');
    expect(value('wetter')).toBe('true');
    expect(value('laeuft')).toBe('true');
    expect(value('einkauf')).toBe('true');
    expect(value('news')).toBe('true');
    expect(value('vacuum')).toBe('false');
    expect(value('climate')).toBe('false');
  });

  it('setEnabled(id, on) schaltet genau EINEN Eintrag und persistiert unter dem passenden Key', async () => {
    root = createRoot(container);
    await act(async () => root!.render(<Host />));
    await act(async () => container.querySelector<HTMLButtonElement>('[data-testid="toggle-wetter"]')!.click());

    expect(container.querySelector('[data-testid="enabled-wetter"]')?.textContent).toBe('false');
    expect(loadWeatherTileEnabled()).toBe(false);
    // der Rest bleibt unberührt.
    expect(container.querySelector('[data-testid="enabled-uhr"]')?.textContent).toBe('true');
    expect(container.querySelector('[data-testid="enabled-vacuum"]')?.textContent).toBe('false');
  });

  it('setEnabled bleibt in Sync mit den benannten Alias-Feldern (vacuumEnabled bleibt die gleiche Quelle)', async () => {
    root = createRoot(container);
    await act(async () => root!.render(<Host />));
    expect(loadVacuumTileEnabled()).toBe(false);

    await act(async () => container.querySelector<HTMLButtonElement>('[data-testid="toggle-vacuum"]')!.click());

    expect(container.querySelector('[data-testid="enabled-vacuum"]')?.textContent).toBe('true');
    expect(loadVacuumTileEnabled()).toBe(true); // dieselbe Quelle wie das Alias-Feld aus useHomeTiles().vacuumEnabled
  });
});

/**
 * W3: „Layout zurücksetzen" — scharf erst beim zweiten Klick, und es fasst
 * ausschließlich `hoshi.homeTiles.layout` an (DESIGN §4.3). Der Nachweis, dass
 * die SCHALTER dabei stehen bleiben, ist der eigentliche Punkt: Anordnung und
 * Sichtbarkeit sind zwei Entscheidungen.
 */
describe('HomeTilesSection — Layout zurücksetzen (W3)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeTilesSection />);
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
  };
  const resetButton = (): HTMLButtonElement =>
    Array.from(container.querySelectorAll('button')).find((b) =>
      (b.textContent ?? '').includes('Layout zurücksetzen') || (b.textContent ?? '').includes('Wirklich'),
    ) as HTMLButtonElement;

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okRegistry(snapshot())));
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
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('die Copy verspricht eine REIHENFOLGE, keine freie Zellplatzierung', async () => {
    await mount();
    expect(container.textContent).toContain('Reihenfolge festlegen');
    expect(container.textContent).toContain('passend zur Bildschirmbreite');
  });

  it('ein Klick schärft nur — erst der zweite setzt zurück', async () => {
    localStorage.setItem(HOME_LAYOUT_STORAGE_KEY, JSON.stringify({ version: 1, order: [{ id: 'news', size: 'XL' }] }));
    await mount();

    await act(async () => resetButton().click());
    expect(resetButton().textContent).toContain('Wirklich');
    expect(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY)).not.toBeNull();

    await act(async () => resetButton().click());
    expect(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY)).toBeNull();
    expect(container.textContent).toContain('Layout zurückgesetzt');
  });

  it('das Zurücksetzen fasst die SCHALTER nicht an (§4.3)', async () => {
    localStorage.setItem(HOME_LAYOUT_STORAGE_KEY, JSON.stringify({ version: 1, order: [{ id: 'news', size: 'XL' }] }));
    localStorage.setItem(VACUUM_TILE_STORAGE_KEY, 'true');
    localStorage.setItem(CURRENT_AFFAIRS_TILE_STORAGE_KEY, 'false');
    await mount();

    await act(async () => resetButton().click());
    await act(async () => resetButton().click());

    expect(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY)).toBeNull();
    expect(loadVacuumTileEnabled()).toBe(true);
    expect(loadCurrentAffairsTileEnabled()).toBe(false);
  });
});
