import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  FiredToast,
  firedLine,
  missedLine,
  dueTimeLabel,
  FIRED_HEADLINE,
} from '../components/FiredToast';
import {
  parseFiredItems,
  fetchFiredItems,
  ackFiredItem,
  reconcileFired,
  type FiredItem,
} from '../hooks/useFiredItems';
import {
  playChime,
  playAlarmChime,
  resetChimeContext,
  CHIME_PARTIALS,
  CHIME_STRIKES,
  CHIME_STRIKE_S,
  CHIME_GAP_S,
  CHIME_PEAK_GAIN,
  CHIME_TOTAL_S,
  CHIME_REPEAT_MS,
  type ChimeContext,
} from '../audio/chime';

// ── Test-Hilfen ───────────────────────────────────────────────────────────────

/** Gültiges gefeuertes Item, per `over` punktuell überschreibbar. */
const item = (over: Partial<FiredItem> = {}): FiredItem => ({
  id: 'f-1',
  kind: 'TIMER',
  dueAtEpochMs: 1_000,
  firedAtEpochMs: 1_100,
  missed: false,
  ...over,
});

/** Wire-Form eines Items (wie der Server sie schickt, label optional). */
const wire = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
  id: 'f-1',
  kind: 'TIMER',
  dueAtEpochMs: 1_000,
  firedAtEpochMs: 1_100,
  missed: false,
  ...over,
});

// ── parseFiredItems — Wire-Vertrag ────────────────────────────────────────────

describe('parseFiredItems — Wire-Vertrag', () => {
  it('leeres Array / kein Array / Müll → [] (still, nie ein kaputtes Klingeln)', () => {
    expect(parseFiredItems([])).toEqual([]);
    expect(parseFiredItems(null)).toEqual([]);
    expect(parseFiredItems('nope')).toEqual([]);
    expect(parseFiredItems({ id: 'x' })).toEqual([]);
  });

  it('gültige Items werden geparst; label fehlt bei null (Optional-Contract)', () => {
    const items = parseFiredItems([wire(), wire({ id: 'f-2', kind: 'ALARM', label: 'Aufstehen' })]);
    expect(items).toHaveLength(2);
    expect(items[0]).toEqual(item());
    expect(items[0].label).toBeUndefined();
    expect(items[1].kind).toBe('ALARM');
    expect(items[1].label).toBe('Aufstehen');
  });

  it('missed wird geparst; fehlt es (alter Server), gilt ehrlich false', () => {
    expect(parseFiredItems([wire({ missed: true })])[0].missed).toBe(true);
    const legacy = { id: 'f-1', kind: 'TIMER', dueAtEpochMs: 1, firedAtEpochMs: 2 };
    expect(parseFiredItems([legacy])[0].missed).toBe(false);
    expect(parseFiredItems([wire({ missed: 'ja' })])[0].missed).toBe(false);
  });

  it('Müll-Einträge/fehlende id werden verworfen, der Rest überlebt', () => {
    const items = parseFiredItems([null, 42, wire({ id: '' }), wire({ id: 'ok' })]);
    expect(items).toHaveLength(1);
    expect(items[0].id).toBe('ok');
  });

  it('unbekannte kind fällt auf TIMER zurück (gefeuert ist gefeuert)', () => {
    expect(parseFiredItems([wire({ kind: 'POMODORO' })])[0].kind).toBe('TIMER');
  });
});

// ── fetchFiredItems — dreiwertig ehrlich (Items / leer / null=Fehl-Poll) ──────

describe('fetchFiredItems — Fehl-Poll ist NICHT leer', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('401/404 → null (Fehl-Poll: der Aufrufer hält den Zustand — kein Wecker-Wegräumen)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    expect(await fetchFiredItems()).toBeNull();
  });

  it('Netzfehler → null', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    expect(await fetchFiredItems()).toBeNull();
  });

  it('200 + kaputter Body (kein Array) → null (nicht als „leer" lügen)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.reject(new Error('kaputt')) }),
    );
    expect(await fetchFiredItems()).toBeNull();
  });

  it('200 + Items → geparst; ruft den fired-Endpoint mit Accept-Header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([wire({ label: 'Tee' })]),
    });
    vi.stubGlobal('fetch', fetchMock);
    const items = await fetchFiredItems();
    expect(items).toHaveLength(1);
    expect(items![0].label).toBe('Tee');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/scheduled/fired');
    expect((init.headers as Record<string, string>).Accept).toBe('application/json');
  });

  it('200 + leeres Array → [] (Server-Wahrheit: nichts unbestätigt — z.B. anderswo quittiert)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) }));
    expect(await fetchFiredItems()).toEqual([]);
  });
});

// ── ackFiredItem — die Quittungs-Naht ─────────────────────────────────────────

describe('ackFiredItem — POST …/fired/{id}/ack', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('POSTet auf den ack-Pfad (id URL-encodiert) und liefert true bei 2xx', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204 });
    vi.stubGlobal('fetch', fetchMock);
    expect(await ackFiredItem('f 1/x')).toBe(true);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/scheduled/fired/f%201%2Fx/ack');
    expect(init.method).toBe('POST');
  });

  it('404 (schon anderswo quittiert) / Netzfehler → false, wirft nie', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    expect(await ackFiredItem('f-1')).toBe(false);
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    expect(await ackFiredItem('f-1')).toBe(false);
  });
});

// ── reconcileFired — Server-Wahrheit, referenz-stabil ─────────────────────────

describe('reconcileFired — der Server ist die Wahrheit', () => {
  it('identische ids + missed → DIESELBE Referenz (kein React-Re-Render)', () => {
    const current = [item()];
    expect(reconcileFired(current, [item()])).toBe(current);
  });

  it('neue/weggefallene Items → Server-Sicht ersetzt den State', () => {
    const current = [item()];
    const incoming = [item(), item({ id: 'f-2' })];
    expect(reconcileFired(current, incoming)).toBe(incoming);
    expect(reconcileFired(current, [])).toEqual([]); // anderswo quittiert ⇒ Banner weg
  });

  it('missed-Flip (frisch → verpasst) schlägt durch', () => {
    const current = [item()];
    const incoming = [item({ missed: true })];
    expect(reconcileFired(current, incoming)).toBe(incoming);
  });
});

// ── Chime — pure Synthese gegen Fake-Context ──────────────────────────────────

/** Aufgezeichneter Oszillator: Frequenz + start/stop-Zeiten. */
interface OscRecord {
  freq: number;
  start: number;
  stop: number;
}

/** Fake-ChimeContext (Konvention playback.test.ts): zeichnet die Synthese auf. */
function makeFakeChimeContext(
  oscs: OscRecord[],
  peaks: number[],
  state: AudioContextState = 'running',
  resumeCalls = { count: 0 },
): ChimeContext {
  return {
    currentTime: 100,
    state,
    destination: {} as AudioNode,
    resume: () => {
      resumeCalls.count += 1;
      return Promise.resolve();
    },
    createOscillator: (): OscillatorNode => {
      const rec: OscRecord = { freq: 0, start: -1, stop: -1 };
      oscs.push(rec);
      const node = {
        type: 'sine',
        frequency: {
          get value() {
            return rec.freq;
          },
          set value(v: number) {
            rec.freq = v;
          },
        },
        connect: () => ({}) as AudioNode,
        start: (t: number) => {
          rec.start = t;
        },
        stop: (t: number) => {
          rec.stop = t;
        },
      };
      return node as unknown as OscillatorNode;
    },
    createGain: (): GainNode => {
      const node = {
        gain: {
          setValueAtTime: () => {},
          linearRampToValueAtTime: (v: number) => {
            peaks.push(v);
          },
          exponentialRampToValueAtTime: () => {},
        },
        connect: () => ({}) as AudioNode,
      };
      return node as unknown as GainNode;
    },
  };
}

describe('playChime — sanfte Glocke, 2× mit Pause', () => {
  it('Spec-Zahlen: Anschlag 1,5–2s, 2 Anschläge, 2–3 Partialtöne, leiser Peak', () => {
    expect(CHIME_STRIKE_S).toBeGreaterThanOrEqual(1.5);
    expect(CHIME_STRIKE_S).toBeLessThanOrEqual(2);
    expect(CHIME_STRIKES).toBe(2);
    expect(CHIME_PARTIALS.length).toBeGreaterThanOrEqual(2);
    expect(CHIME_PARTIALS.length).toBeLessThanOrEqual(3);
    expect(CHIME_GAP_S).toBeGreaterThan(0); // die Pause dazwischen
    expect(CHIME_PEAK_GAIN).toBeLessThanOrEqual(0.2); // wecken, nicht schrillen
    expect(CHIME_TOTAL_S).toBe(CHIME_STRIKES * CHIME_STRIKE_S + CHIME_GAP_S);
    // Wiederhol-Takt: ~4s — dicht genug für „klingelt weiter", kein Dauerbrei.
    expect(CHIME_REPEAT_MS).toBeGreaterThanOrEqual(3_000);
    expect(CHIME_REPEAT_MS).toBeLessThanOrEqual(6_000);
  });

  it('erzeugt Anschläge × Partialtöne Oszillatoren mit den Spec-Frequenzen', () => {
    const oscs: OscRecord[] = [];
    playChime(makeFakeChimeContext(oscs, []));
    expect(oscs).toHaveLength(CHIME_STRIKES * CHIME_PARTIALS.length);
    const wanted = CHIME_PARTIALS.map((p) => p.freq).sort();
    for (let s = 0; s < CHIME_STRIKES; s++) {
      const strike = oscs.slice(s * CHIME_PARTIALS.length, (s + 1) * CHIME_PARTIALS.length);
      expect(strike.map((o) => o.freq).sort()).toEqual(wanted);
    }
  });

  it('zweiter Anschlag startet nach Decay + Pause; jeder klingt ~STRIKE_S aus', () => {
    const oscs: OscRecord[] = [];
    playChime(makeFakeChimeContext(oscs, []));
    const t0 = 100;
    const first = oscs[0];
    const second = oscs[CHIME_PARTIALS.length];
    expect(first.start).toBe(t0);
    expect(second.start).toBeCloseTo(t0 + CHIME_STRIKE_S + CHIME_GAP_S, 5);
    for (const o of oscs) {
      expect(o.stop).toBeGreaterThanOrEqual(o.start + CHIME_STRIKE_S);
      expect(o.stop).toBeLessThan(o.start + CHIME_STRIKE_S + 0.2);
    }
  });

  it('Hüllkurven-Peaks respektieren Master-Gain × Partial-Pegel (kein Alarm)', () => {
    const peaks: number[] = [];
    playChime(makeFakeChimeContext([], peaks));
    expect(Math.max(...peaks)).toBeLessThanOrEqual(CHIME_PEAK_GAIN);
    expect(peaks.every((p) => p > 0)).toBe(true);
  });
});

describe('playAlarmChime — lazy Singleton, Autoplay-Policy, ehrlicher Rückgabewert', () => {
  afterEach(() => resetChimeContext());

  it('suspendierter Context (keine Geste): schedult + resume(), meldet aber false (JETZT unhörbar)', () => {
    const oscs: OscRecord[] = [];
    const resumeCalls = { count: 0 };
    const ctx = makeFakeChimeContext(oscs, [], 'suspended', resumeCalls);
    expect(playAlarmChime(() => ctx)).toBe(false); // ehrlich: niemand hört das gerade
    expect(oscs).toHaveLength(CHIME_STRIKES * CHIME_PARTIALS.length); // Klang steht bereit
    expect(resumeCalls.count).toBeGreaterThanOrEqual(1); // best-effort entsperren
  });

  it('running Context: spielt hörbar (true) ohne resume(); Singleton wird wiederverwendet', () => {
    const oscs: OscRecord[] = [];
    const resumeCalls = { count: 0 };
    const ctx = makeFakeChimeContext(oscs, [], 'running', resumeCalls);
    const factory = vi.fn(() => ctx);
    expect(playAlarmChime(factory)).toBe(true);
    expect(playAlarmChime(factory)).toBe(true);
    expect(factory).toHaveBeenCalledTimes(1); // lazy Singleton
    expect(resumeCalls.count).toBe(0);
    expect(oscs).toHaveLength(2 * CHIME_STRIKES * CHIME_PARTIALS.length);
  });

  it('kaputte Factory wirft nie und meldet false (Klang ist Komfort, tötet nichts)', () => {
    expect(
      playAlarmChime(() => {
        throw new Error('kein Web-Audio');
      }),
    ).toBe(false);
  });
});

// ── FiredToast — Render-Vertrag ───────────────────────────────────────────────

const render = (items: FiredItem[], silenced = false) =>
  renderToStaticMarkup(<FiredToast items={items} onAck={() => {}} silenced={silenced} />);

describe('FiredToast — Render-Vertrag', () => {
  it('keine Items → rendert NICHTS (kein Lärm)', () => {
    expect(render([])).toBe('');
  });

  it('TIMER → „Timer ist fertig" als role=alert, klickbar, mit Wecker-SVG statt ⏰', () => {
    const html = render([item()]);
    expect(html).toContain('fired-toast');
    expect(html).toContain('role="alert"');
    expect(html).toContain('<button');
    expect(html).toContain('glyph--alarm'); // muted SVG-Glyph …
    expect(html).not.toContain('⏰'); // … kein Emoji im Banner-Chrome
    expect(html).toContain('Timer ist fertig');
  });

  it('Label wird angehängt, Kind-ehrliche Überschriften (Wecker/Erinnerung)', () => {
    const html = render([
      item({ id: 'a', kind: 'ALARM', label: 'Aufstehen' }),
      item({ id: 'b', kind: 'REMINDER' }),
    ]);
    expect(html).toContain('Wecker klingelt — Aufstehen');
    expect(html).toContain('Erinnerung');
    expect(firedLine(item({ kind: 'TIMER', label: 'Tee' }))).toBe('Timer ist fertig — Tee');
    expect(firedLine(item({ kind: 'TIMER' }))).toBe(FIRED_HEADLINE.TIMER);
  });

  it('missed → ehrliche Verpasst-Meldung mit Fälligkeits-Uhrzeit statt frischem Klingeln', () => {
    const due = new Date(2026, 6, 3, 7, 0).getTime(); // lokal 07:00
    const missed = item({ kind: 'TIMER', dueAtEpochMs: due, missed: true });
    expect(dueTimeLabel(due)).toBe('07:00');
    expect(missedLine(missed)).toBe('Timer war um 07:00 fällig — hab dich nicht erreicht');
    expect(firedLine(missed)).toBe(missedLine(missed)); // missed gewinnt über die frische Zeile
    expect(missedLine(item({ kind: 'ALARM', label: 'Aufstehen', dueAtEpochMs: due, missed: true }))).toBe(
      'Wecker „Aufstehen" war um 07:00 fällig — hab dich nicht erreicht',
    );
    const html = render([missed]);
    expect(html).toContain('hab dich nicht erreicht');
    expect(html).not.toContain('Timer ist fertig');
  });

  it('silenced (Autoplay-Sperre) → Banner pulsiert sichtbar (fired-toast--pulse)', () => {
    expect(render([item()], true)).toContain('fired-toast--pulse');
    expect(render([item()], false)).not.toContain('fired-toast--pulse');
  });

  it('mehrere Items → eine Zeile pro Klingeln (nichts verschluckt)', () => {
    const html = render([item({ id: 'a' }), item({ id: 'b', label: 'Nudeln' })]);
    const lines = html.match(/fired-toast__line/g) ?? [];
    expect(lines).toHaveLength(2);
    expect(html).toContain('Nudeln');
  });
});
