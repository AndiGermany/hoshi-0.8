/** @vitest-environment jsdom */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  DEFAULT_SETTINGS,
  SORA_AOI_START_HOUR,
  SORA_ASA_START_HOUR,
  SORA_KASUMI_START_HOUR,
  SORA_YORU_START_HOUR,
  SORA_ROTATION,
  THEMES,
  loadSettings,
  msUntilNextSoraBoundary,
  resolveSoraTheme,
  saveSettings,
  useResolvedTheme,
  type Theme,
} from '../hooks/useSettings';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/** In-Memory-Storage in DOM-`Storage`-Form (wie in settings.test.ts). */
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

/** Baut ein lokales Datum an einer bestimmten Stunde/Minute (Tag ist egal — nur die Uhrzeit zählt). */
function atLocalTime(hour: number, minute = 0): Date {
  return new Date(2026, 6, 19, hour, minute, 0, 0);
}

describe('resolveSoraTheme — Mapping je Tagesfenster', () => {
  it('06:00–09:59 → Asa', () => {
    expect(resolveSoraTheme(atLocalTime(SORA_ASA_START_HOUR, 0))).toBe('asa');
    expect(resolveSoraTheme(atLocalTime(9, 59))).toBe('asa');
  });

  it('10:00–17:59 → Aoi', () => {
    expect(resolveSoraTheme(atLocalTime(SORA_AOI_START_HOUR, 0))).toBe('aoi');
    expect(resolveSoraTheme(atLocalTime(17, 59))).toBe('aoi');
  });

  it('18:00–21:59 → Kasumi', () => {
    expect(resolveSoraTheme(atLocalTime(SORA_KASUMI_START_HOUR, 0))).toBe('kasumi');
    expect(resolveSoraTheme(atLocalTime(21, 59))).toBe('kasumi');
  });

  it('22:00–05:59 (über Mitternacht) → Yoru', () => {
    expect(resolveSoraTheme(atLocalTime(SORA_YORU_START_HOUR, 0))).toBe('yoru');
    expect(resolveSoraTheme(atLocalTime(23, 59))).toBe('yoru');
    expect(resolveSoraTheme(atLocalTime(0, 0))).toBe('yoru');
    expect(resolveSoraTheme(atLocalTime(5, 59))).toBe('yoru');
  });

  it('Nagareboshi ist NICHT Teil der Rotation (Marken-Theme, bewusst ausgenommen)', () => {
    expect(SORA_ROTATION).toEqual(['asa', 'aoi', 'kasumi', 'yoru']);
    expect(SORA_ROTATION).not.toContain('nagareboshi');
  });
});

describe('msUntilNextSoraBoundary — Timer-Ziel für den nächsten Sprung', () => {
  it('kurz vor einer Grenze: wenige Minuten bis zum Sprung', () => {
    const ms = msUntilNextSoraBoundary(atLocalTime(9, 55));
    expect(ms).toBe(5 * 60 * 1000);
  });

  it('nach der letzten Grenze des Tages (22:00): Ziel ist 06:00 am nächsten Tag', () => {
    const from = atLocalTime(23, 0);
    const ms = msUntilNextSoraBoundary(from);
    const target = new Date(from.getTime() + ms);
    expect(target.getHours()).toBe(SORA_ASA_START_HOUR);
    expect(target.getDate()).toBe(from.getDate() + 1);
  });

  it('genau auf einer Grenze: zählt zur NÄCHSTEN (nicht der aktuellen)', () => {
    const ms = msUntilNextSoraBoundary(atLocalTime(SORA_AOI_START_HOUR, 0));
    expect(ms).toBe((SORA_KASUMI_START_HOUR - SORA_AOI_START_HOUR) * 60 * 60 * 1000);
  });
});

describe('THEMES-Katalog — Sora ist wählbar', () => {
  it('Sora steht im Panel-Katalog', () => {
    expect(THEMES.map((t) => t.id)).toContain('sora');
  });
});

describe('Persistenz — Sora wird wie jedes andere Theme gespeichert', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('speichert + stellt sora wieder her', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ ...DEFAULT_SETTINGS, theme: 'sora' });
    expect(loadSettings().theme).toBe('sora');
  });

  it('bestehende manuelle Themes bleiben unberührt (byte-gleiche Wahl)', () => {
    // 'yoru' bewusst ausgenommen: das ist die vorbestehende Einmal-Aoi-Migration
    // (settings.test.ts), keine Sora-Sache — hier geht es nur um NEUE Themes.
    for (const theme of ['aoi', 'asa', 'kasumi', 'nagareboshi'] as const) {
      vi.stubGlobal('localStorage', memoryStorage());
      saveSettings({ ...DEFAULT_SETTINGS, theme });
      expect(loadSettings().theme).toBe(theme);
    }
  });
});

describe('useResolvedTheme — Auflösung + Grenzwechsel-Timer', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  function Host({ theme }: { theme: Theme }) {
    const resolved = useResolvedTheme(theme);
    return <div data-testid="resolved">{resolved}</div>;
  }

  afterEach(async () => {
    if (root) {
      await act(async () => root!.unmount());
      root = null;
    }
    container.remove();
    vi.useRealTimers();
  });

  it('manuelle Themes gehen unverändert durch (Identität)', async () => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => root!.render(<Host theme="kasumi" />));
    expect(container.querySelector('[data-testid="resolved"]')?.textContent).toBe('kasumi');
  });

  it('sora löst beim Laden korrekt zur aktuellen (gefakten) Uhrzeit auf', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(atLocalTime(8, 0)); // 08:00 → Asa-Fenster
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => root!.render(<Host theme="sora" />));
    expect(container.querySelector('[data-testid="resolved"]')?.textContent).toBe('asa');
  });

  it('Grenzwechsel feuert: Timer springt exakt beim Fenster-Wechsel auf das nächste Theme', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(atLocalTime(9, 59)); // 09:59 — noch im Asa-Fenster, 1 Minute vor der Grenze
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => root!.render(<Host theme="sora" />));
    expect(container.querySelector('[data-testid="resolved"]')?.textContent).toBe('asa');

    // Eine Minute weiter → über die 10:00-Grenze (Asa → Aoi).
    await act(async () => {
      vi.setSystemTime(atLocalTime(10, 0));
      vi.advanceTimersByTime(60 * 1000);
    });
    expect(container.querySelector('[data-testid="resolved"]')?.textContent).toBe('aoi');
  });
});
