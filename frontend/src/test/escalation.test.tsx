/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  useFiredItems,
  shouldChimeNow,
  parseFiredItems,
  DEFAULT_ESCALATION_SECONDS,
  type FiredItem,
} from '../hooks/useFiredItems';
import { playAlarmChime, CHIME_REPEAT_MS } from '../audio/chime';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// Chime als Modul-Mock (wie polling.test): deterministisch „unhörbar", zählbar.
vi.mock('../audio/chime', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/chime')>();
  return { ...orig, playAlarmChime: vi.fn(() => false) };
});

const item = (over: Partial<FiredItem> = {}): FiredItem => ({
  id: 'f-1',
  kind: 'ALARM',
  label: 'Kuchen',
  dueAtEpochMs: 1,
  firedAtEpochMs: 2,
  missed: false,
  ...over,
});

/** Wire-Form (Server-JSON) eines gefeuerten Items, origin optional. */
const wire = (over: Record<string, unknown> = {}) => ({
  id: 'f-1',
  kind: 'ALARM',
  label: 'Kuchen',
  dueAtEpochMs: 1,
  firedAtEpochMs: 2,
  missed: false,
  ...over,
});

// ── parseFiredItems — origin additiv ──────────────────────────────────────────

describe('parseFiredItems — origin (das stellende Gerät)', () => {
  it('übernimmt origin, wenn gesetzt; fehlt es (null/leer) → undefined', () => {
    expect(parseFiredItems([wire({ origin: 'geraet-A' })])[0].origin).toBe('geraet-A');
    expect(parseFiredItems([wire()])[0].origin).toBeUndefined();
    expect(parseFiredItems([wire({ origin: '' })])[0].origin).toBeUndefined();
  });
});

// ── shouldChimeNow — die Ursprungs-Regel (pur) ────────────────────────────────

describe('shouldChimeNow — ursprungs-gebunden mit Eskalation ab firstSeen', () => {
  it('eigenes Gerät (origin===deviceId) → SOFORT', () => {
    expect(shouldChimeNow(item({ origin: 'A' }), 'A', 0, 0, 15)).toBe(true);
  });
  it('alt-Client ohne origin → SOFORT (byte-identischer Alt-Pfad)', () => {
    expect(shouldChimeNow(item(), 'A', 0, 0, 15)).toBe(true);
    expect(shouldChimeNow(item(), null, 0, 999_999, 15)).toBe(true);
  });
  it('fremdes Gerät → erst NACH der Frist, gezählt ab firstSeen (nicht firedAt)', () => {
    // firstSeen=1000, Frist 15s → vor 16000 noch nicht, ab 16000 ja.
    expect(shouldChimeNow(item({ origin: 'B' }), 'A', 1000, 15_999, 15)).toBe(false);
    expect(shouldChimeNow(item({ origin: 'B' }), 'A', 1000, 16_000, 15)).toBe(true);
  });
});

// ── Hook-Verhalten (jsdom + fake timers) ──────────────────────────────────────

/** Host: mountet den Klingel-Hook mit deviceId/escalationSeconds. */
function Host({ deviceId, escalationSeconds }: { deviceId: string; escalationSeconds?: number }) {
  const { items, ack, silenced } = useFiredItems(60_000, { deviceId, escalationSeconds });
  return (
    <div>
      <span data-testid="ids">{items.map((i) => i.id).join(',')}</span>
      <span data-testid="silenced">{String(silenced)}</span>
      <button type="button" data-testid="ack" onClick={ack} />
    </div>
  );
}

describe('useFiredItems — ursprungs-gebundenes Bimmeln + Eskalation', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;
  let fetchMock: ReturnType<typeof vi.fn>;

  const mount = async (el: React.ReactElement): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(el);
    });
  };
  const unmount = async (): Promise<void> => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
  };
  const advance = async (ms: number): Promise<void> => {
    await act(async () => {
      vi.advanceTimersByTime(ms);
    });
  };
  const chimes = (): number => vi.mocked(playAlarmChime).mock.calls.length;
  const silenced = (): string =>
    container.querySelector('[data-testid="silenced"]')?.textContent ?? '';
  const clickAck = async (): Promise<void> => {
    await act(async () => {
      container.querySelector<HTMLButtonElement>('[data-testid="ack"]')!.click();
    });
  };
  const serves = (body: unknown): void => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'POST'
        ? Promise.resolve({ ok: true, status: 204 })
        : Promise.resolve({ ok: true, json: () => Promise.resolve(body) }),
    );
  };

  beforeEach(() => {
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    vi.stubGlobal('fetch', fetchMock);
    vi.mocked(playAlarmChime).mockClear();
    vi.mocked(playAlarmChime).mockReturnValue(false); // jsdom: kein Web-Audio
  });
  afterEach(async () => {
    await unmount();
    container.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('Default-Frist ist 15 s (der Ausbau-Default)', () => {
    expect(DEFAULT_ESCALATION_SECONDS).toBe(15);
  });

  it('EIGENES Gerät (origin===deviceId) → Chime SOFORT beim Feuern', async () => {
    serves([wire({ origin: 'A' })]);
    await mount(<Host deviceId="A" escalationSeconds={15} />);
    expect(container.querySelector('[data-testid="ids"]')?.textContent).toBe('f-1');
    expect(chimes()).toBe(1); // sofort, ohne Warten
  });

  it('alt-Client ohne origin → Chime SOFORT (überall bimmeln, Alt-Pfad)', async () => {
    serves([wire()]); // kein origin
    await mount(<Host deviceId="A" escalationSeconds={15} />);
    expect(chimes()).toBe(1);
  });

  it('FREMDES Gerät → KEIN Chime vor der Frist, Chime NACH escalationSeconds', async () => {
    serves([wire({ origin: 'B' })]); // gestellt auf Gerät B, wir sind A
    await mount(<Host deviceId="A" escalationSeconds={15} />);
    // Banner ist da (überall ackbar), aber der TON schweigt noch:
    expect(container.querySelector('[data-testid="ids"]')?.textContent).toBe('f-1');
    expect(chimes()).toBe(0);

    await advance(14_000);
    expect(chimes()).toBe(0); // noch innerhalb der Frist

    await advance(1_000); // jetzt 15 s ab erstem Sichten
    expect(chimes()).toBeGreaterThanOrEqual(1); // eskaliert: dies Gerät bimmelt jetzt auch
    expect(silenced()).toBe('true'); // unhörbar (jsdom) ⇒ ehrlich visuell eskalieren
  });

  it('die Einstellung ändert die Frist: bei 5 s bimmelt das fremde Gerät schon nach 5 s', async () => {
    serves([wire({ origin: 'B' })]);
    await mount(<Host deviceId="A" escalationSeconds={5} />);
    expect(chimes()).toBe(0);
    await advance(4_000);
    expect(chimes()).toBe(0);
    await advance(1_000); // 5 s
    expect(chimes()).toBeGreaterThanOrEqual(1);
  });

  it('ack stoppt Chime UND Eskalation (fremdes Gerät, nach dem Bimmeln)', async () => {
    serves([wire({ origin: 'B' })]);
    await mount(<Host deviceId="A" escalationSeconds={15} />);
    await advance(15_000); // eskaliert → bimmelt
    expect(chimes()).toBeGreaterThanOrEqual(1);

    // ack: der Server liefert danach [] (für alle quittiert).
    serves([]);
    await clickAck();
    expect(container.querySelector('[data-testid="ids"]')?.textContent).toBe('');
    const after = chimes();
    await advance(4 * CHIME_REPEAT_MS);
    expect(chimes()).toBe(after); // Ruhe nach ack — keine weitere Wiederholung
  });

  it('ack VOR der Eskalation verhindert das Bimmeln des fremden Geräts ganz', async () => {
    serves([wire({ origin: 'B' })]);
    await mount(<Host deviceId="A" escalationSeconds={15} />);
    expect(chimes()).toBe(0);

    serves([]);
    await clickAck(); // jemand hat am Ursprungs-Gerät abgestellt
    await advance(30_000); // deutlich über die Frist hinaus
    expect(chimes()).toBe(0); // nie eskaliert — es war ja schon quittiert
  });
});
