import { describe, it, expect, beforeEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  EARCON_PARTIALS,
  EARCON_DURATION_S,
  EARCON_ATTACK_S,
  EARCON_PEAK_GAIN,
  playEarcon,
  playTurnEarcon,
  resetEarconContext,
  type EarconContext,
} from '../audio/earcon';
import {
  ChatView,
  WaveTap,
  composeSlot,
  stepLabelText,
  SLOW_TURN_MS,
  SLOW_TURN_TEXT,
  type MicState,
} from '../components/ChatView';

// Voice-Feedback gegen die 7s-TTS-Stille (Cowork-Research: ab ~1s Stille bricht
// die Zufriedenheit): Earcon der Turn-Annahme, ehrliche Denk-Labels, >8s-Zeile,
// Barge-in per Waveform-Tap. Hier die puren Teile (Earcon-Synthese gegen einen
// Fake-Context, Label-Kürzung) + statische Render-Verträge (node-env,
// renderToStaticMarkup — Konvention wie voicewaveform.test.tsx). Das HÖREN
// (Klangcharakter, Lautstärke) und der Live-Tap brauchen Chrome/Ohr.

// ── Fake-EarconContext (Konvention wie playback.test.ts) ─────────────────────

interface FakeOsc {
  type: string;
  frequency: { value: number };
  startedAt: number | null;
  stoppedAt: number | null;
  connectedTo: unknown;
}

interface FakeGain {
  sets: [number, number][]; // setValueAtTime(value, time)
  linears: [number, number][]; // linearRampToValueAtTime
  exps: [number, number][]; // exponentialRampToValueAtTime
  connectedTo: unknown;
}

function makeFakeEarconContext(state: AudioContextState = 'running', currentTime = 0) {
  const oscs: FakeOsc[] = [];
  const gains: FakeGain[] = [];
  const destination = { __dest: true } as unknown as AudioNode;
  let resumes = 0;
  const ctx: EarconContext = {
    currentTime,
    state,
    destination,
    resume: () => {
      resumes++;
      return Promise.resolve();
    },
    createOscillator: () => {
      const o: FakeOsc = {
        type: '',
        frequency: { value: 0 },
        startedAt: null,
        stoppedAt: null,
        connectedTo: null,
      };
      oscs.push(o);
      return {
        get type() {
          return o.type;
        },
        set type(v: string) {
          o.type = v;
        },
        frequency: o.frequency,
        connect: (n: unknown) => {
          o.connectedTo = n;
          return n;
        },
        start: (t: number) => {
          o.startedAt = t;
        },
        stop: (t: number) => {
          o.stoppedAt = t;
        },
      } as unknown as OscillatorNode;
    },
    createGain: () => {
      const g: FakeGain = { sets: [], linears: [], exps: [], connectedTo: null };
      gains.push(g);
      return {
        gain: {
          setValueAtTime: (v: number, t: number) => g.sets.push([v, t]),
          linearRampToValueAtTime: (v: number, t: number) => g.linears.push([v, t]),
          exponentialRampToValueAtTime: (v: number, t: number) => g.exps.push([v, t]),
        },
        connect: (n: unknown) => {
          g.connectedTo = n;
          return n;
        },
      } as unknown as GainNode;
    },
  };
  return { ctx, oscs, gains, destination, resumes: () => resumes };
}

// ── Baustein 1: Earcon der Turn-Annahme (Sara-Spec) ──────────────────────────

describe('Earcon-Spec — kurz, leise, weich, NIE Beep', () => {
  it('ist ein Cluster aus mehreren Sinus-Partialtönen (kein einzelner Beep)', () => {
    expect(EARCON_PARTIALS.length).toBeGreaterThanOrEqual(2);
    // absteigende relative Pegel: Grundton trägt, Obertöne färben nur.
    for (let i = 1; i < EARCON_PARTIALS.length; i++) {
      expect(EARCON_PARTIALS[i].gain).toBeLessThan(EARCON_PARTIALS[i - 1].gain);
    }
  });

  it('bleibt deutlich unter der 300ms-Grenze', () => {
    expect(EARCON_DURATION_S).toBeLessThan(0.3);
    const { ctx, oscs } = makeFakeEarconContext();
    playEarcon(ctx);
    for (const o of oscs) expect(o.stoppedAt!).toBeLessThan(0.3);
  });

  it('ist leise: Worst-Case-Summenpegel ≤ ~−18 dBFS', () => {
    const sum = EARCON_PARTIALS.reduce((acc, p) => acc + EARCON_PEAK_GAIN * p.gain, 0);
    expect(sum).toBeLessThanOrEqual(0.14); // 20·log10(0.14) ≈ −17.1 dBFS Momentanwert
    expect(EARCON_PEAK_GAIN).toBeLessThanOrEqual(0.1);
  });

  it('synthetisiert pro Partialton einen Sinus-Oszillator mit Hüllkurve → destination', () => {
    const t0 = 5; // currentTime ≠ 0 → beweist relative Zeitplanung
    const { ctx, oscs, gains, destination } = makeFakeEarconContext('running', t0);
    playEarcon(ctx);

    expect(oscs.length).toBe(EARCON_PARTIALS.length);
    expect(gains.length).toBe(EARCON_PARTIALS.length);
    for (let i = 0; i < oscs.length; i++) {
      const o = oscs[i];
      const g = gains[i];
      expect(o.type).toBe('sine');
      expect(o.frequency.value).toBe(EARCON_PARTIALS[i].freq);
      // Kette: Oszillator → Hüllkurven-Gain → destination.
      expect(g.connectedTo).toBe(destination);
      // Hüllkurve: 0 bei t0 → Peak nach Attack → exponentiell gegen ~0.
      expect(g.sets).toEqual([[0, t0]]);
      expect(g.linears).toEqual([[EARCON_PEAK_GAIN * EARCON_PARTIALS[i].gain, t0 + EARCON_ATTACK_S]]);
      expect(g.exps.length).toBe(1);
      const [expVal, expTime] = g.exps[0];
      expect(expVal).toBeLessThanOrEqual(0.0001); // nie exakt 0 (Web-Audio-Regel), unhörbar
      expect(expVal).toBeGreaterThan(0);
      expect(expTime).toBeCloseTo(t0 + EARCON_DURATION_S, 10);
      expect(o.startedAt).toBe(t0);
      expect(o.stoppedAt!).toBeGreaterThan(t0 + EARCON_DURATION_S);
      expect(o.stoppedAt! - t0).toBeLessThan(0.3); // inkl. Nachlauf unter Sara-Grenze
    }
  });
});

describe('playTurnEarcon — lazy Singleton-Context', () => {
  beforeEach(() => resetEarconContext());

  it('erzeugt den Context genau einmal (lazy) und spielt bei jedem Aufruf', () => {
    const fake = makeFakeEarconContext();
    let made = 0;
    const factory = () => {
      made++;
      return fake.ctx;
    };
    playTurnEarcon(factory);
    playTurnEarcon(factory);
    expect(made).toBe(1); // Singleton
    expect(fake.oscs.length).toBe(2 * EARCON_PARTIALS.length); // 2× gespielt
  });

  it('entsperrt einen suspendierten Context (Autoplay-Policy, Geste vorhanden)', () => {
    const fake = makeFakeEarconContext('suspended');
    playTurnEarcon(() => fake.ctx);
    expect(fake.resumes()).toBe(1);
  });

  it('wirft NIE (kein Web-Audio → Turn läuft trotzdem) und versucht es beim nächsten Mal frisch', () => {
    let calls = 0;
    const throwing = () => {
      calls++;
      throw new Error('kein AudioContext');
    };
    expect(() => playTurnEarcon(throwing)).not.toThrow();
    expect(() => playTurnEarcon(throwing)).not.toThrow();
    expect(calls).toBe(2); // kaputter Versuch cached sich nicht als Singleton
  });
});

// ── Baustein 2: ehrliche Denk-Labels (step-Message-Kürzung) ──────────────────

describe('stepLabelText — echte Backend-Steps aufs proc-Label kürzen', () => {
  it('lässt kurze Messages unverändert durch', () => {
    expect(stepLabelText('suche im Kalender')).toBe('suche im Kalender');
  });

  it('kollabiert Whitespace (Zeilenumbrüche/Mehrfach-Spaces)', () => {
    expect(stepLabelText('prüfe   die\n  Sensoren')).toBe('prüfe die Sensoren');
  });

  it('kürzt lange Messages auf ~40 Zeichen mit …-Ellipse', () => {
    const long = 'x'.repeat(80);
    const out = stepLabelText(long);
    expect(out.length).toBeLessThanOrEqual(40);
    expect(out.endsWith('…')).toBe(true);
  });

  it('lässt exakt 40 Zeichen ungekürzt (Grenzfall)', () => {
    const exact = 'a'.repeat(40);
    expect(stepLabelText(exact)).toBe(exact);
  });
});

// ── Baustein 3: >8s-Ehrlichkeits-Zeile (Konstanten-Vertrag) ──────────────────

describe('>8s-Ehrlichkeits-Zeile — warm, kein Alarm', () => {
  it('feuert bei 8 Sekunden', () => {
    expect(SLOW_TURN_MS).toBe(8000);
  });

  it('klingt warm („ich bin dran"), nicht nach Fehler/Alarm', () => {
    expect(SLOW_TURN_TEXT).toContain('ich bin dran');
    expect(SLOW_TURN_TEXT.toLowerCase()).not.toContain('fehler');
    expect(SLOW_TURN_TEXT.toLowerCase()).not.toContain('timeout');
  });
});

// ── Baustein 4: Barge-in per Tap auf die Waveform ────────────────────────────

describe('WaveTap — antippbare Ausgabe-Waveform (Barge-in)', () => {
  it('rendert einen fokussierbaren role="button" mit klarem aria-label + Pointer-Cursor', () => {
    const html = renderToStaticMarkup(
      <WaveTap onTap={() => {}}>
        <span className="kind" />
      </WaveTap>,
    );
    expect(html).toContain('role="button"');
    expect(html).toContain('tabindex="0"');
    expect(html).toContain('aria-label="Antippen stoppt Hoshi"');
    expect(html).toContain('cursor:pointer');
    expect(html).toContain('class="kind"'); // Waveform-Kind bleibt unangetastet drin
  });
});

// ── Baustein 5: die Welle existiert NUR, wenn Audio fließt (20260706-1729) ───

describe('composeSlot — Welle nur bei offenem Audio-Kanal (nichts leuchtet, was nichts misst)', () => {
  it('Mikro offen (listening) ⇒ wave-in: die Welle zeigt den Nutzer-Pegel', () => {
    expect(composeSlot('listening', false)).toBe('wave-in');
  });

  it('Hoshi spricht ⇒ wave-out — Audio gewinnt über processing (responding+speaking)', () => {
    expect(composeSlot('responding', true)).toBe('wave-out');
    expect(composeSlot('idle', true)).toBe('wave-out');
  });

  it('kein Audio ⇒ NIE eine Welle: idle → input, STT/Denken → stille Punkte', () => {
    const silent: [MicState, string][] = [
      ['idle', 'input'],
      ['transcribing', 'processing'],
      ['responding', 'processing'], // Deltas streamen, aber noch kein Ton → keine Welle
    ];
    for (const [mic, slot] of silent) expect(composeSlot(mic, false)).toBe(slot);
  });
});

// ── Baustein 6: entschärfter compose__hint + Idle-Vertrag der ChatView ───────

describe('ChatView — statischer Render-Vertrag (idle)', () => {
  const render = () =>
    renderToStaticMarkup(<ChatView persona="Standard" language="de" voice="coral" />);

  it('zeigt KEINE Dev-API-Pfade mehr unter der Compose-Bar', () => {
    const html = render();
    expect(html).not.toContain('/api/v1');
    expect(html).not.toContain('POST');
  });

  it('rendert im Ruhezustand KEINE Welle — sie materialisiert erst mit offenem Kanal', () => {
    const html = render();
    expect(html).not.toContain('vc-wave');
    expect(html).not.toContain('<canvas');
  });

  it('zeigt die Ehrlichkeits-Zeile im Ruhezustand NICHT (nur bei >8s-Turns)', () => {
    expect(render()).not.toContain(SLOW_TURN_TEXT);
  });

  it('behält Composer + Mikro (Kern-Bedienung unangetastet)', () => {
    const html = render();
    expect(html).toContain('Nachricht an Hoshi…');
    expect(html).toContain('aria-label="Mikro — gedrückt halten und sprechen"');
  });
});
