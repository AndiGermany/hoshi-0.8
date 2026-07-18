/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  DEFAULT_ESCALATION_SECONDS,
  ESCALATION_MAX_SECONDS,
  ESCALATION_MIN_SECONDS,
  ESCALATION_STORAGE_KEY,
  clampEscalationSeconds,
  loadEscalationSeconds,
  saveEscalationSeconds,
  useEscalationSeconds,
} from '../hooks/useSettings';
import { EscalationSection } from '../components/SettingsPanel';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/** In-Memory-Storage in DOM-`Storage`-Form. */
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

describe('clampEscalationSeconds — sinnvoller Bereich, gerundet', () => {
  it('klemmt unter Min / über Max, rundet, NaN → Default', () => {
    expect(clampEscalationSeconds(3)).toBe(ESCALATION_MIN_SECONDS); // 5
    expect(clampEscalationSeconds(999)).toBe(ESCALATION_MAX_SECONDS); // 120
    expect(clampEscalationSeconds(15.4)).toBe(15);
    expect(clampEscalationSeconds(Number.NaN)).toBe(DEFAULT_ESCALATION_SECONDS);
  });
});

describe('load/saveEscalationSeconds — Persistenz (Standalone-Pref)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('unbelegt → Default 15', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    expect(loadEscalationSeconds()).toBe(DEFAULT_ESCALATION_SECONDS);
  });

  it('speichert (geklemmt) unter dem erwarteten Key und stellt wieder her', () => {
    const store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    saveEscalationSeconds(40);
    expect(store.getItem(ESCALATION_STORAGE_KEY)).toBe('40');
    expect(loadEscalationSeconds()).toBe(40);
    saveEscalationSeconds(3); // unter Min → 5
    expect(loadEscalationSeconds()).toBe(5);
  });

  it('ohne localStorage → Default, kein Wurf', () => {
    expect(() => loadEscalationSeconds()).not.toThrow();
    expect(loadEscalationSeconds()).toBe(DEFAULT_ESCALATION_SECONDS);
  });
});

describe('EscalationSection — Render-Vertrag (Sara-Ton)', () => {
  it('Zahl-Input mit Wert + Einheit + „nach X Sekunden auf allen"', () => {
    const html = renderToStaticMarkup(<EscalationSection seconds={20} onSeconds={() => {}} />);
    expect(html).toContain('Wecker-Eskalation');
    expect(html).toContain('settings__number');
    expect(html).toContain('type="number"');
    expect(html).toContain('value="20"');
    expect(html).toContain('Sekunden');
    expect(html).toContain('nach 20 Sekunden auf allen');
    // KEIN <option> — die Option-Zählung anderer Settings-Tests bleibt unberührt.
    expect(html).not.toContain('<option');
  });

  it('ohne onSeconds → Input schreibgeschützt (disabled) statt kaputt', () => {
    const html = renderToStaticMarkup(<EscalationSection seconds={15} />);
    expect(html).toContain('disabled');
  });
});

describe('useEscalationSeconds — Hook ändert + persistiert die Sekunden', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  function Host() {
    const { seconds, setSeconds } = useEscalationSeconds();
    return (
      <div>
        <span data-testid="sec">{seconds}</span>
        <button type="button" data-testid="set40" onClick={() => setSeconds(40)} />
        <button type="button" data-testid="set999" onClick={() => setSeconds(999)} />
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

  it('initial Default; setSeconds ändert die Anzeige und klemmt (999 → 120)', async () => {
    root = createRoot(container);
    await act(async () => root!.render(<Host />));
    const sec = () => container.querySelector('[data-testid="sec"]')?.textContent;
    expect(sec()).toBe(String(DEFAULT_ESCALATION_SECONDS));

    await act(async () => container.querySelector<HTMLButtonElement>('[data-testid="set40"]')!.click());
    expect(sec()).toBe('40');
    expect(loadEscalationSeconds()).toBe(40); // persistiert

    await act(async () =>
      container.querySelector<HTMLButtonElement>('[data-testid="set999"]')!.click(),
    );
    expect(sec()).toBe(String(ESCALATION_MAX_SECONDS)); // geklemmt auf 120
  });
});
