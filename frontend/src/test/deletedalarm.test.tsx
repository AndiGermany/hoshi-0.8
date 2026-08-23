/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useFiredItems, shouldChimeNow, type FiredItem } from '../hooks/useFiredItems';
import { playAlarmChime, CHIME_REPEAT_MS } from '../audio/chime';

/**
 * **Riegel: „Ein gelöschter/verpasster Wecker klingelt NIE nachträglich"** — Andis
 * Live-Befund 23.08.2026: „Wecker gestellt, gelöscht — und heute morgen **beim
 * Ausklappen** ging er trotzdem."
 *
 * Das „beim Ausklappen" ist die heiße Spur, und sie liegt im FE-Klang: ein Klingeln,
 * das seinen Wecker überlebt hat, wurde hier auf ZWEI Wegen nachträglich hörbar —
 *
 *  1. **Verpasst wurde trotzdem geklingelt.** Backend-Contract (KDoc
 *     `ScheduledItemFireService` + `FiredItemsController`): länger als 30 min
 *     unbestätigt bzw. nach Downtime gefeuert ⇒ `missed=true`, „das FE sagt dann
 *     ehrlich ‚hab dich nicht erreicht' **statt zu klingeln**". Der Banner hielt sich
 *     daran ({@link FiredToast} rendert die Verpasst-Zeile), die Klingel-Schleife NICHT:
 *     sie bimmelte für JEDES unbestätigte Item. Ein Wecker, der morgens um 07:00 ins
 *     Leere lief (Display dunkel/schlafend), klingelte damit Stunden später los, sobald
 *     der erste Poll des wieder aufgeklappten Displays ihn abholte.
 *  2. **Der Ton-Stau im gesperrten AudioContext.** `playAlarmChime` schedulte den Klang
 *     AUCH in einen `suspended` Context (Autoplay-Sperre, solange niemand das Display
 *     berührt hat). Dort steht `currentTime` still — jeder 4-s-Takt legte weitere
 *     Anschläge auf denselben Zeitpunkt, und die erste User-Geste (das AUFKLAPPEN!)
 *     entsperrte den Context und spielte den ganzen Stapel ab. Quittieren/Löschen
 *     konnte diesen Stau nicht mehr einfangen — der Klang lag längst im Context.
 */

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// Chime als Modul-Mock (Konvention escalation.test): deterministisch „unhörbar", zählbar.
vi.mock('../audio/chime', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/chime')>();
  return { ...orig, playAlarmChime: vi.fn(() => false) };
});

const item = (over: Partial<FiredItem> = {}): FiredItem => ({
  id: 'f-1',
  kind: 'ALARM',
  label: 'Aufstehen',
  dueAtEpochMs: 1,
  firedAtEpochMs: 2,
  missed: false,
  ...over,
});

/** Wire-Form (Server-JSON) eines gefeuerten Items. */
const wire = (over: Record<string, unknown> = {}) => ({
  id: 'f-1',
  kind: 'ALARM',
  label: 'Aufstehen',
  dueAtEpochMs: 1,
  firedAtEpochMs: 2,
  missed: false,
  ...over,
});

// ── shouldChimeNow — verpasst ist ein Bericht, kein Klingeln (pur) ────────────

describe('shouldChimeNow — `missed` klingelt NIE nach', () => {
  it('verpasstes Item: kein Ton — auch nicht auf dem Ursprungs-Gerät', () => {
    expect(shouldChimeNow(item({ missed: true, origin: 'A' }), 'A', 0, 0, 15)).toBe(false);
  });

  it('verpasstes Item ohne origin (alt-Client): auch kein Ton', () => {
    expect(shouldChimeNow(item({ missed: true }), 'A', 0, 999_999, 15)).toBe(false);
  });

  it('verpasstes Item eines fremden Geräts: auch nach der Eskalationsfrist still', () => {
    expect(shouldChimeNow(item({ missed: true, origin: 'B' }), 'A', 1_000, 999_999, 15)).toBe(false);
  });

  it('FRISCHES Item klingelt unverändert (Grundverhalten unangetastet)', () => {
    expect(shouldChimeNow(item({ origin: 'A' }), 'A', 0, 0, 15)).toBe(true);
    expect(shouldChimeNow(item(), 'A', 0, 0, 15)).toBe(true);
  });
});

// ── Hook: der verpasste Wecker zeigt sich, aber schweigt ─────────────────────

function Host({ deviceId }: { deviceId: string }) {
  const { items, silenced } = useFiredItems(60_000, { deviceId, escalationSeconds: 15 });
  return (
    <div>
      <span data-testid="ids">{items.map((i) => i.id).join(',')}</span>
      <span data-testid="silenced">{String(silenced)}</span>
    </div>
  );
}

describe('useFiredItems — der verpasste Wecker klingelt nicht nachträglich', () => {
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
  const ids = (): string => container.querySelector('[data-testid="ids"]')?.textContent ?? '';
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
    vi.mocked(playAlarmChime).mockReturnValue(false);
  });
  afterEach(async () => {
    await unmount();
    container.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('missed=true: Banner ja (ehrliche Verpasst-Meldung), Ton NEIN — auch nicht später', async () => {
    serves([wire({ missed: true, origin: 'A' })]); // eigenes Gerät, aber längst verpasst
    await mount(<Host deviceId="A" />);

    expect(ids()).toBe('f-1'); // sichtbar bleibt es (abholbar, quittierbar)
    expect(chimes()).toBe(0); // aber es klingelt NICHT nachträglich
    await advance(10 * CHIME_REPEAT_MS);
    expect(chimes()).toBe(0); // und auch keine Wiederholung
  });

  it('frischer Wecker klingelt weiter — inkl. 4-s-Wiederholung (Riegel)', async () => {
    serves([wire({ origin: 'A' })]);
    await mount(<Host deviceId="A" />);

    expect(chimes()).toBe(1);
    await advance(CHIME_REPEAT_MS);
    expect(chimes()).toBeGreaterThanOrEqual(2); // die Schleife lebt unverändert
  });

  it('gemischt: der frische Wecker bimmelt, der verpasste bleibt still dabei', async () => {
    serves([wire({ id: 'alt', missed: true, origin: 'A' }), wire({ id: 'neu', origin: 'A' })]);
    await mount(<Host deviceId="A" />);
    expect(ids()).toBe('alt,neu');
    expect(chimes()).toBe(1); // der frische zieht — der verpasste verhindert das nicht
  });
});

// ── Der Ton-Stau: was im gesperrten Context liegt, klingelt bei der Geste ─────

interface OscRecord {
  start: number;
  stop: number;
}

/**
 * Fake-ChimeContext mit UMSCHALTBAREM state, zählt Oszillatoren. `resume()` entsperrt
 * bewusst NICHT von selbst — genau wie im echten Browser: ohne User-Geste bleibt der
 * Context gesperrt, egal wie oft das Polling resume() ruft. Die Geste simulieren die
 * Tests, indem sie `state.value = 'running'` setzen (das Aufklappen des Displays).
 */
function makeSwitchableContext(oscs: OscRecord[], state: { value: AudioContextState }) {
  return {
    currentTime: 100, // steht im suspendierten Context STILL — genau der Stau-Mechanismus
    get state(): AudioContextState {
      return state.value;
    },
    destination: {} as AudioNode,
    resume: (): Promise<void> => Promise.resolve(),
    createOscillator: (): OscillatorNode => {
      const rec: OscRecord = { start: -1, stop: -1 };
      oscs.push(rec);
      return {
        type: 'sine',
        frequency: { value: 0 },
        connect: () => ({}) as AudioNode,
        start: (t: number) => {
          rec.start = t;
        },
        stop: (t: number) => {
          rec.stop = t;
        },
      } as unknown as OscillatorNode;
    },
    createGain: (): GainNode =>
      ({
        gain: {
          setValueAtTime: () => {},
          linearRampToValueAtTime: () => {},
          exponentialRampToValueAtTime: () => {},
        },
        connect: () => ({}) as AudioNode,
      }) as unknown as GainNode,
  };
}

describe('playAlarmChime — nichts stapelt sich im gesperrten Context', () => {
  it('gesperrt (kein Kontakt am Display): NICHTS wird geschedult — es gibt nichts nachzuholen', async () => {
    const { playAlarmChime: real, resetChimeContext } = await vi.importActual<
      typeof import('../audio/chime')
    >('../audio/chime');
    resetChimeContext();
    const oscs: OscRecord[] = [];
    const state = { value: 'suspended' as AudioContextState };
    const ctx = makeSwitchableContext(oscs, state);

    // Die Klingel-Schleife läuft (ein unbestätigtes Item), das Display ist unberührt:
    expect(real(() => ctx)).toBe(false); // ehrlich: JETZT hört das niemand
    expect(real(() => ctx)).toBe(false);
    expect(real(() => ctx)).toBe(false);

    expect(oscs).toHaveLength(0); // KEIN Ton-Stau — sonst detoniert er bei der ersten Geste
    resetChimeContext();
  });

  it('nach der Geste (Context läuft) klingelt der NOCH unbestätigte Wecker sofort wieder', async () => {
    const { playAlarmChime: real, resetChimeContext, CHIME_STRIKES, CHIME_PARTIALS } =
      await vi.importActual<typeof import('../audio/chime')>('../audio/chime');
    resetChimeContext();
    const oscs: OscRecord[] = [];
    const state = { value: 'suspended' as AudioContextState };
    const ctx = makeSwitchableContext(oscs, state);

    expect(real(() => ctx)).toBe(false); // gesperrt: still
    state.value = 'running'; // Andi klappt auf/tippt — DAS entsperrt den Context

    expect(real(() => ctx)).toBe(true); // der nächste Schleifen-Tick (≤4 s) klingelt hörbar
    expect(oscs).toHaveLength(CHIME_STRIKES * CHIME_PARTIALS.length);
    resetChimeContext();
  });
});
