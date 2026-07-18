/** @vitest-environment jsdom */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  assignEntityArea,
  fetchHomeEditStatus,
  HomeEditLockedError,
  HomeEditValidationError,
} from '../api/homeEdit';
import { RaeumeView, RaeumeViewLive, type RaeumeEdit } from '../views/RaeumeView';
import type { HomeRegistrySnapshot, HomeRegistryState } from '../api/homeRegistry';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ─────────────────────────────────────────────────────────────────────────────
//  api/homeEdit.ts — Wire-Vertrag (Status + PUT + Fehlerpfade)
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchHomeEditStatus — fail-closed', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('200 {editEnabled:true} → true', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ editEnabled: true }) }));
    expect(await fetchHomeEditStatus()).toBe(true);
  });

  it('200 {editEnabled:false} → false', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ editEnabled: false }) }));
    expect(await fetchHomeEditStatus()).toBe(false);
  });

  it('404/500/Netzfehler → false (kein Picker, wenn der Flag nicht bestätigt ist)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    expect(await fetchHomeEditStatus()).toBe(false);
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('net')));
    expect(await fetchHomeEditStatus()).toBe(false);
  });
});

describe('assignEntityArea — PUT-Vertrag + Fehlerpfade', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('PUT {areaId} an /home/entity/{id}/area, übernimmt die Server-Antwort', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ entityId: 'light.x', areaId: 'wohnzimmer' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await assignEntityArea('light.x', 'wohnzimmer');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/api/v1/home/entity/light.x/area');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ areaId: 'wohnzimmer' });
    expect(result).toEqual({ entityId: 'light.x', areaId: 'wohnzimmer' });
  });

  it('409 → HomeEditLockedError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: () => Promise.resolve({ error: 'home-edit-off', id: 'home-edit', message: 'Beim Deploy deaktiviert.' }),
    }));
    await expect(assignEntityArea('light.x', 'wohnzimmer')).rejects.toBeInstanceOf(HomeEditLockedError);
  });

  it('400 → HomeEditValidationError mit Server-Meldung', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: () => Promise.resolve({ error: 'unknown-area', id: 'home-edit', message: 'Unbekannte Area: keller.' }),
    }));
    await expect(assignEntityArea('light.x', 'keller')).rejects.toBeInstanceOf(HomeEditValidationError);
    await expect(assignEntityArea('light.x', 'keller')).rejects.toThrow('Unbekannte Area: keller.');
  });

  it('401 → Error (Auth-Wand)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, json: () => Promise.resolve({}) }));
    await expect(assignEntityArea('light.x', 'wohnzimmer')).rejects.toThrow(/401/);
  });

  it('502 → generischer Error (HA hat nicht bestätigt)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      json: () => Promise.resolve({ error: 'home-edit-write-failed', id: 'home-edit', message: 'HA hat nicht bestätigt.' }),
    }));
    await expect(assignEntityArea('light.x', 'wohnzimmer')).rejects.toThrow('HA hat nicht bestätigt.');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  RaeumeView — Picker-Render (flag-gated, nur auf „Nicht zugeordnet")
// ─────────────────────────────────────────────────────────────────────────────

const snapshot = (over: Partial<HomeRegistrySnapshot> = {}): HomeRegistrySnapshot => ({
  areas: [
    {
      areaId: 'wohnzimmer',
      label: 'Wohnzimmer',
      entities: [{ entityId: 'light.wohnzimmer_decke', domain: 'light', name: 'Deckenlicht', labels: [] }],
    },
  ],
  unassigned: [{ entityId: 'light.hue_iris', domain: 'light', name: 'Hue Iris', labels: [] }],
  ...over,
});

const editProp = (over: Partial<RaeumeEdit> = {}): RaeumeEdit => ({
  enabled: true,
  areas: [
    { areaId: 'wohnzimmer', label: 'Wohnzimmer' },
    { areaId: 'kueche', label: 'Küche' },
  ],
  onAssign: () => {},
  ...over,
});

describe('RaeumeView — Picker flag-gated', () => {
  it('ohne edit-Prop ⇒ KEIN Picker (byte-neutral zu Scheibe 1)', () => {
    const out = renderToStaticMarkup(<RaeumeView state={{ kind: 'live', data: snapshot() }} />);
    expect(out).not.toContain('room__pickerselect');
    expect(out).not.toContain('<select');
  });

  it('edit.enabled=false ⇒ KEIN Picker (Flag zu)', () => {
    const out = renderToStaticMarkup(
      <RaeumeView state={{ kind: 'live', data: snapshot() }} edit={editProp({ enabled: false })} />,
    );
    expect(out).not.toContain('room__pickerselect');
  });

  it('edit.enabled=true ⇒ Picker NUR auf „Nicht zugeordnet"-Zeilen, mit Area-Optionen', () => {
    const out = renderToStaticMarkup(
      <RaeumeView state={{ kind: 'live', data: snapshot() }} edit={editProp()} />,
    );
    // Genau EIN Picker (das eine unassigned-Gerät), nicht auf der Wohnzimmer-Karte.
    expect(out.split('room__pickerselect').length - 1).toBe(1);
    expect(out).toContain('Raum wählen');
    expect(out).toContain('>Wohnzimmer<');
    expect(out).toContain('>Küche<');
  });

  it('busyEntityId ⇒ der Picker dieser Zeile ist disabled', () => {
    const out = renderToStaticMarkup(
      <RaeumeView state={{ kind: 'live', data: snapshot() }} edit={editProp({ busyEntityId: 'light.hue_iris' })} />,
    );
    expect(out).toMatch(/room__pickerselect[^>]*disabled/);
    expect(out).toContain('wird zugeordnet…');
  });

  it('errorEntityId ⇒ ehrliche Zeilen-Meldung (role=alert)', () => {
    const out = renderToStaticMarkup(
      <RaeumeView
        state={{ kind: 'live', data: snapshot() }}
        edit={editProp({ errorEntityId: 'light.hue_iris', errorMessage: 'HA hat nicht bestätigt.' })}
      />,
    );
    expect(out).toContain('role="alert"');
    expect(out).toContain('HA hat nicht bestätigt.');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  RaeumeViewLive — Picker-Flow (injizierte load/assign, kein Live-Backend)
// ─────────────────────────────────────────────────────────────────────────────

describe('RaeumeViewLive — Zuordnungs-Flow (PUT → Reload → Karte wandert)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const live = (data: HomeRegistrySnapshot): HomeRegistryState => ({ kind: 'live', data });

  const mount = async (el: React.ReactElement): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(el);
    });
  };
  const flush = async (): Promise<void> => {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
  };
  const pickArea = async (value: string): Promise<void> => {
    const select = container.querySelector('.room__pickerselect') as HTMLSelectElement;
    if (!select) throw new Error('Kein Picker gerendert');
    await act(async () => {
      // Echtes Browser-Verhalten nachbilden: ein <select> feuert `change` NUR,
      // wenn sich der ausgewählte Wert wirklich ändert — dieselbe Option
      // nochmal "wählen" ist beim echten Nutzer ein No-Op (kein Event). Ein
      // simples "value setzen + Event feuern" würde den RoomPicker-Fix
      // (controlled, reset auf '' nach jedem Pick) nicht wirklich prüfen.
      if (select.value === value) return;
      select.value = value;
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 0));
    });
  };

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
    vi.restoreAllMocks();
  });

  it('Flag an: Raum wählen ⇒ assign(entityId, areaId) ⇒ Reload ⇒ Gerät wandert in den Raum', async () => {
    const before = snapshot();
    const after: HomeRegistrySnapshot = {
      areas: [
        {
          areaId: 'wohnzimmer',
          label: 'Wohnzimmer',
          entities: [
            { entityId: 'light.wohnzimmer_decke', domain: 'light', name: 'Deckenlicht', labels: [] },
            { entityId: 'light.hue_iris', domain: 'light', name: 'Hue Iris', labels: [] },
          ],
        },
      ],
      unassigned: [],
    };
    let call = 0;
    const loadRegistry = vi.fn(async () => live(call++ === 0 ? before : after));
    const assign = vi.fn().mockResolvedValue({ entityId: 'light.hue_iris', areaId: 'wohnzimmer' });

    await mount(
      <RaeumeViewLive loadRegistry={loadRegistry} loadStatus={async () => true} assign={assign} />,
    );
    await flush();
    // Vor dem Wählen: Hue Iris ist unassigned, Picker da.
    expect(container.querySelector('.room__pickerselect')).not.toBeNull();

    await pickArea('wohnzimmer');

    expect(assign).toHaveBeenCalledWith('light.hue_iris', 'wohnzimmer');
    // read-first: neu geladen ⇒ „Nicht zugeordnet" ist leer, kein Picker mehr.
    expect(container.textContent).toContain('Aktuell hat jedes gemeldete Gerät einen Raum');
    expect(container.querySelector('.room__pickerselect')).toBeNull();
  });

  it('Fehler beim Schreiben ⇒ ehrliche Meldung, Gerät bleibt unassigned', async () => {
    const loadRegistry = vi.fn(async () => live(snapshot())); // bleibt gleich (kein Wandern)
    const assign = vi.fn().mockRejectedValue(new Error('HA hat nicht bestätigt.'));

    await mount(
      <RaeumeViewLive loadRegistry={loadRegistry} loadStatus={async () => true} assign={assign} />,
    );
    await flush();
    await pickArea('wohnzimmer');

    expect(container.querySelector('[role="alert"]')).not.toBeNull();
    expect(container.textContent).toContain('HA hat nicht bestätigt.');
    // Gerät bleibt in „Nicht zugeordnet" — der Picker ist weiterhin da.
    expect(container.querySelector('.room__pickerselect')).not.toBeNull();
  });

  it('Fehlschlag ⇒ DIESELBE Area erneut wählen löst einen zweiten PUT aus (RoomPicker-Fix)', async () => {
    const loadRegistry = vi.fn(async () => live(snapshot())); // bleibt gleich (kein Wandern, Fehler)
    const assign = vi.fn().mockRejectedValue(new Error('HA hat nicht bestätigt.'));

    await mount(
      <RaeumeViewLive loadRegistry={loadRegistry} loadStatus={async () => true} assign={assign} />,
    );
    await flush();

    await pickArea('wohnzimmer');
    expect(assign).toHaveBeenCalledTimes(1);
    expect(container.querySelector('[role="alert"]')).not.toBeNull();

    // Der Picker muss sich nach dem Fehlschlag SELBST auf den Platzhalter
    // zurückgesetzt haben (controlled) — sonst würde das erneute "Wählen"
    // derselben, bereits ausgewählten Option beim echten Nutzer kein
    // change-Event auslösen (s. `pickArea`-Nachbau des Browser-Verhaltens).
    const select = container.querySelector('.room__pickerselect') as HTMLSelectElement;
    expect(select.value).toBe('');

    await pickArea('wohnzimmer');
    expect(assign).toHaveBeenCalledTimes(2);
  });

  it('Flag aus (status=false) ⇒ kein Picker, auch bei Live-Daten', async () => {
    await mount(
      <RaeumeViewLive loadRegistry={async () => live(snapshot())} loadStatus={async () => false} assign={async () => ({})} />,
    );
    await flush();
    expect(container.querySelector('.room__pickerselect')).toBeNull();
  });
});
