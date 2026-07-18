/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  NightModeLockedError,
  NightModeValidationError,
  fetchNightModeDevice,
  fetchNightModeDevices,
  saveNightModeDevice,
  type NightModeConfig,
  type NightModeDevice,
} from '../api/nightMode';
import {
  NIGHT_MODE_TEXTS,
  NightModeDeviceCard,
  NightModeDeviceListView,
  NightModeSection,
  formatLastSeen,
  type NightModeDeviceCardProps,
  type NightModeDeviceListViewProps,
} from '../components/NightModeSection';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/** Gültiges Gerät (Store-Wert, verbunden), per `over` punktuell überschreibbar. */
const device = (over: Partial<NightModeDevice> = {}): NightModeDevice => ({
  satelliteId: 'voice-pe-wohnzimmer',
  connected: true,
  enabled: true,
  mode: 'SCHEDULE',
  from: '22:00',
  to: '07:00',
  dim: 0.3,
  nightModeEnabled: true,
  ...over,
});

const draft = (over: Partial<NightModeConfig> = {}): NightModeConfig => ({
  enabled: true,
  mode: 'SCHEDULE',
  from: '22:00',
  to: '07:00',
  dim: 0.3,
  ...over,
});

// ─────────────────────────────────────────────────────────────────────────────
//  api/nightMode.ts — Wire-Vertrag
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchNightModeDevices — GET, defensiver Parse', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('GET /api/v1/night-mode/devices → geparstes NightModeDevice[] (Müll fällt raus)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve([
          {
            satelliteId: 'voice-pe-wohnzimmer',
            connected: true,
            enabled: true,
            mode: 'SCHEDULE',
            from: '22:00',
            to: '07:00',
            dim: 0.3,
            nightModeEnabled: true,
          },
          null,
          { connected: true }, // ohne satelliteId → verworfen
        ]),
    });
    vi.stubGlobal('fetch', fetchMock);

    const list = await fetchNightModeDevices();
    expect(list).toHaveLength(1);
    expect(list[0].satelliteId).toBe('voice-pe-wohnzimmer');
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/night-mode/devices');
  });

  it('401 → wirft (Auth-Wand ehrlich durchgereicht)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchNightModeDevices()).rejects.toThrow(/401/);
  });
});

describe('fetchNightModeDevice — GET Einzelgerät (nie 404, auch unkonfiguriert)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('parst den Default-Zustand eines unkonfigurierten Geräts', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          satelliteId: 'noch-nie-verbunden',
          connected: false,
          enabled: false,
          mode: 'SCHEDULE',
          from: '22:00',
          to: '07:00',
          dim: 0.3,
          nightModeEnabled: true,
        }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchNightModeDevice('noch-nie-verbunden');
    expect(got.satelliteId).toBe('noch-nie-verbunden');
    expect(got.enabled).toBe(false);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/night-mode/noch-nie-verbunden');
  });
});

describe('saveNightModeDevice — PUT-Vertrag + 400/409-Fehlerpfade', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('PUT den flachen Config-Body (enabled/mode/from/to/dim) an /night-mode/{id}', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(device({ enabled: false })),
    });
    vi.stubGlobal('fetch', fetchMock);

    const cfg = draft({ enabled: false, mode: 'ALWAYS', from: '20:00', to: '06:30', dim: 0.6 });
    const updated = await saveNightModeDevice('voice-pe-wohnzimmer', cfg);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/api/v1/night-mode/voice-pe-wohnzimmer');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual(cfg);
    expect(updated.enabled).toBe(false); // FE übernimmt die Server-Antwort, rät nicht
  });

  it('409 → NightModeLockedError (Deploy-Decke zu)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: () =>
          Promise.resolve({ error: 'deploy-disabled', satelliteId: 'x', message: 'Beim Deploy deaktiviert; greift nicht.' }),
      }),
    );
    await expect(saveNightModeDevice('x', draft())).rejects.toBeInstanceOf(NightModeLockedError);
  });

  it('400 → NightModeValidationError mit Server-Meldung', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        json: () =>
          Promise.resolve({ error: 'invalid-mode', satelliteId: 'x', message: 'mode muss SCHEDULE oder ALWAYS sein.' }),
      }),
    );
    await expect(saveNightModeDevice('x', draft())).rejects.toBeInstanceOf(NightModeValidationError);
    await expect(saveNightModeDevice('x', draft())).rejects.toThrow('mode muss SCHEDULE oder ALWAYS sein.');
  });

  it('500 → generischer Error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, json: () => Promise.resolve({}) }));
    await expect(saveNightModeDevice('x', draft())).rejects.toThrow('500');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  formatLastSeen — ehrlich: nie ein erfundener Zeitpunkt
// ─────────────────────────────────────────────────────────────────────────────

describe('formatLastSeen', () => {
  it('unbekannt (undefined/0) → null (KEIN erfundener Zeitpunkt)', () => {
    expect(formatLastSeen(undefined)).toBeNull();
    expect(formatLastSeen(0)).toBeNull();
  });

  it('< 1 Minute → „gerade eben"', () => {
    const now = 1_000_000;
    expect(formatLastSeen(now - 10_000, now)).toBe('gerade eben');
  });

  it('Minuten/Stunden/Tage — grob gerundet, menschlich', () => {
    const now = 2_000_000_000; // groß genug, dass „vor N Tagen" nie ins Negative rutscht
    expect(formatLastSeen(now - 5 * 60_000, now)).toBe('vor 5 Min.');
    expect(formatLastSeen(now - 3 * 3_600_000, now)).toBe('vor 3 Std.');
    expect(formatLastSeen(now - 2 * 86_400_000, now)).toBe('vor 2 Tagen');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  NightModeDeviceCard — Render-Verträge (Toggle/Modus/Dimmen)
// ─────────────────────────────────────────────────────────────────────────────

const renderCard = (over: Partial<NightModeDeviceCardProps> = {}) =>
  renderToStaticMarkup(
    <NightModeDeviceCard
      draft={draft()}
      onToggleEnabled={() => {}}
      onMode={() => {}}
      onFrom={() => {}}
      onTo={() => {}}
      onDim={() => {}}
      onSave={() => {}}
      {...over}
    />,
  );

describe('NightModeDeviceCard — Master-Toggle blendet Details aus', () => {
  it('enabled:true → Details NICHT ausgegraut, Steuerelemente aktiv', () => {
    const html = renderCard({ draft: draft({ enabled: true }) });
    expect(html).toContain('settings__nightdetails ');
    expect(html).not.toContain('settings__nightdetails is-disabled');
  });

  it('enabled:false → Details ausgegraut (is-disabled) + Steuerelemente disabled', () => {
    const html = renderCard({ draft: draft({ enabled: false }) });
    expect(html).toContain('settings__nightdetails is-disabled');
    expect(html).toContain('settings__nightmodebtn ');
    // Modus-Buttons UND Zeitfelder sind disabled, wenn der Master aus ist.
    const modeBtnDisabled = /settings__nightmodebtn[^"]*"[^>]*disabled/.test(html);
    expect(modeBtnDisabled).toBe(true);
  });
});

describe('NightModeDeviceCard — Modus „Immer an" versteckt den Dial', () => {
  it('SCHEDULE → Dial + Von/Bis-Zeitfelder sichtbar', () => {
    const html = renderCard({ draft: draft({ mode: 'SCHEDULE' }) });
    expect(html).toContain('nightdial');
    expect(html).toContain('settings__nighttimes');
    expect(html).toContain(NIGHT_MODE_TEXTS.fromLabel);
  });

  it('ALWAYS → kein Dial, keine Von/Bis-Felder', () => {
    const html = renderCard({ draft: draft({ mode: 'ALWAYS' }) });
    expect(html).not.toContain('nightdial');
    expect(html).not.toContain('settings__nighttimes');
  });
});

describe('NightModeDeviceCard — Dimmen 0–100 ↔ 0.0–1.0', () => {
  it('dim=0.42 → Anzeige „42%", Range-Value „42", Swatch-Opacity 0.42', () => {
    const html = renderCard({ draft: draft({ dim: 0.42 }) });
    expect(html).toContain('42%');
    expect(html).toContain('value="42"');
    expect(html).toContain('opacity:0.42');
  });

  it('dim=0 → 0%, dim=1 → 100%', () => {
    expect(renderCard({ draft: draft({ dim: 0 }) })).toContain('0%');
    expect(renderCard({ draft: draft({ dim: 1 }) })).toContain('100%');
  });
});

describe('NightModeDeviceCard — Speichern-Knopf + Notiz', () => {
  it('busy → „speichert…" statt „Speichern"', () => {
    const html = renderCard({ busy: true });
    expect(html).toContain(NIGHT_MODE_TEXTS.saving);
  });

  it('409-Hinweis erscheint als role=status, NICHT als Fehler', () => {
    const html = renderCard({ note: NIGHT_MODE_TEXTS.locked });
    expect(html).toContain(NIGHT_MODE_TEXTS.locked);
    expect(html).toContain('role="status"');
    expect(html).not.toContain('role="alert"');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  NightModeDeviceListView — Geräte-Liste, leerer Zustand, manuelles Targeting
// ─────────────────────────────────────────────────────────────────────────────

const renderList = (over: Partial<NightModeDeviceListViewProps> = {}) =>
  renderToStaticMarkup(
    <NightModeDeviceListView
      rows={[]}
      selectedId={null}
      manualId=""
      onSelect={() => {}}
      onManualId={() => {}}
      onManualSubmit={() => {}}
      {...over}
    />,
  );

describe('NightModeDeviceListView — leerer Zustand', () => {
  it('kein Gerät ⇒ freundlicher Erklärtext + manuelles Targeting-Feld', () => {
    const html = renderList({ rows: [] });
    expect(html).toContain(NIGHT_MODE_TEXTS.empty);
    expect(html).toContain(NIGHT_MODE_TEXTS.emptyHint);
    expect(html).toContain('nightmode-manual-id');
    expect(html).toContain(NIGHT_MODE_TEXTS.manualButton);
  });

  it('loading (ohne Geräte) ⇒ „lädt…", KEIN leerer-Zustand-Text', () => {
    const html = renderList({ rows: [], loading: true });
    expect(html).toContain('lädt…');
    expect(html).not.toContain(NIGHT_MODE_TEXTS.empty);
  });
});

describe('NightModeDeviceListView — Geräte-Zeilen (online/offline/nie gesehen)', () => {
  it('verbunden ⇒ „verbunden"-Hinweis', () => {
    const html = renderList({ rows: [{ device: device({ connected: true }), lastSeenLabel: null }] });
    expect(html).toContain(NIGHT_MODE_TEXTS.onlineHint);
  });

  it('offline mit bekanntem „zuletzt gesehen" ⇒ der Hinweis trägt die Zeit', () => {
    const html = renderList({
      rows: [{ device: device({ connected: false }), lastSeenLabel: 'vor 5 Min.' }],
    });
    expect(html).toContain(NIGHT_MODE_TEXTS.offlineHint('vor 5 Min.'));
    expect(html).toContain('settings__nightdevice--offline');
  });

  it('offline, nie beobachtet ⇒ ehrlicher Hinweis ohne erfundene Zeit', () => {
    const html = renderList({ rows: [{ device: device({ connected: false }), lastSeenLabel: null }] });
    expect(html).toContain(NIGHT_MODE_TEXTS.neverSeenHint);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  NightModeSection — Container mit injizierten API-Funktionen (kein Live-Backend)
// ─────────────────────────────────────────────────────────────────────────────

describe('NightModeSection — Container-Flow (injizierte fetch/save)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

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
  const findButton = (text: string): HTMLButtonElement => {
    const btns = Array.from(container.querySelectorAll('button')) as HTMLButtonElement[];
    const btn = btns.find((b) => (b.textContent ?? '').includes(text));
    if (!btn) {
      throw new Error(`Kein Button „${text}" — vorhanden: ${btns.map((b) => b.textContent).join(' | ')}`);
    }
    return btn;
  };
  const setInputValue = (input: HTMLInputElement, value: string): void => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!;
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
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

  it('kein Gerät ⇒ leerer Zustand rendert (freundlicher Text + manuelles Feld)', async () => {
    await mount(<NightModeSection fetchDevices={async () => []} />);
    await flush();
    expect(container.textContent).toContain(NIGHT_MODE_TEXTS.empty);
    expect(container.querySelector('#nightmode-manual-id')).not.toBeNull();
  });

  it('manuelles Targeting: Id eintragen + „Übernehmen" lädt das Gerät und zeigt das Kärtchen', async () => {
    const fetchDevice = vi.fn().mockResolvedValue(device({ satelliteId: 'kueche', connected: false, enabled: false }));
    await mount(<NightModeSection fetchDevices={async () => []} fetchDevice={fetchDevice} />);
    await flush();

    const input = container.querySelector('#nightmode-manual-id') as HTMLInputElement;
    await act(async () => {
      setInputValue(input, 'kueche');
    });
    await act(async () => {
      findButton(NIGHT_MODE_TEXTS.manualButton).click();
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(fetchDevice).toHaveBeenCalledWith('kueche');
    expect(container.textContent).toContain('kueche');
    expect(container.querySelector('.settings__nightcard')).not.toBeNull();
  });

  it('Speichern ruft saveDevice mit dem aktuellen Entwurf auf (mode/from/to/dim/enabled)', async () => {
    const initial = device({ satelliteId: 'wohnzimmer', mode: 'SCHEDULE', from: '22:00', to: '07:00', dim: 0.3, enabled: true });
    const saveDevice = vi.fn().mockResolvedValue({ ...initial, dim: 0.5 });
    await mount(
      <NightModeSection fetchDevices={async () => [initial]} saveDevice={saveDevice} />,
    );
    await flush();

    await act(async () => {
      findButton(NIGHT_MODE_TEXTS.save).click();
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(saveDevice).toHaveBeenCalledTimes(1);
    const [id, cfg] = saveDevice.mock.calls[0] as [string, NightModeConfig];
    expect(id).toBe('wohnzimmer');
    expect(cfg).toEqual({ enabled: true, mode: 'SCHEDULE', from: '22:00', to: '07:00', dim: 0.3 });
    expect(container.textContent).toContain(NIGHT_MODE_TEXTS.saved);
  });

  it('409 beim Speichern ⇒ freundlicher „serverseitig noch aus"-Hinweis statt Fehler', async () => {
    const initial = device({ satelliteId: 'wohnzimmer' });
    const saveDevice = vi.fn().mockRejectedValue(new NightModeLockedError('wohnzimmer'));
    await mount(<NightModeSection fetchDevices={async () => [initial]} saveDevice={saveDevice} />);
    await flush();

    await act(async () => {
      findButton(NIGHT_MODE_TEXTS.save).click();
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(container.textContent).toContain(NIGHT_MODE_TEXTS.locked);
    expect(container.querySelector('[role="alert"]')).toBeNull();
  });

  it('Ladefehler der Geräteliste ⇒ ehrliche Zeile statt stillem Bruch', async () => {
    await mount(<NightModeSection fetchDevices={async () => Promise.reject(new Error('net'))} />);
    await flush();
    expect(container.textContent).toContain(NIGHT_MODE_TEXTS.loadError);
  });
});
