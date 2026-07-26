import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import {
  pickMimeType,
  rmsLevel,
  PREFERRED_MIME_TYPES,
  VoiceRecorder,
  VoiceRecorderError,
} from '../audio/recorder';
import { PRE_ROLL_MS } from '../audio/preRoll';

// ── pickMimeType (rein, isSupported injizierbar) ───────────────────────────────

describe('pickMimeType', () => {
  it('bevorzugt webm/opus, wenn unterstützt', () => {
    const supported = new Set(['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']);
    expect(pickMimeType((t) => supported.has(t))).toBe('audio/webm;codecs=opus');
  });

  it('fällt auf den nächsten unterstützten Typ zurück (kein opus)', () => {
    const supported = new Set(['audio/mp4']);
    expect(pickMimeType((t) => supported.has(t))).toBe('audio/mp4');
  });

  it('liefert leeren String, wenn nichts unterstützt wird (Browser-Default)', () => {
    expect(pickMimeType(() => false)).toBe('');
  });

  it('respektiert die dokumentierte Präferenzreihenfolge', () => {
    expect(PREFERRED_MIME_TYPES[0]).toBe('audio/webm;codecs=opus');
  });
});

// ── rmsLevel (reine Mathematik) ────────────────────────────────────────────────

describe('rmsLevel', () => {
  it('ist 0 bei Stille (alles 128)', () => {
    expect(rmsLevel(new Uint8Array(16).fill(128))).toBe(0);
  });

  it('ist ~1 bei voller Auslenkung (alles 0 oder 255)', () => {
    expect(rmsLevel(new Uint8Array(8).fill(0))).toBeCloseTo(1, 5);
    expect(rmsLevel(new Uint8Array(8).fill(255))).toBeCloseTo(255 / 128 - 1, 5);
  });

  it('ist 0 bei leerem Puffer (kein NaN)', () => {
    expect(rmsLevel(new Uint8Array(0))).toBe(0);
  });

  it('liegt zwischen Stille und Vollausschlag für einen Halbpegel', () => {
    const buf = new Uint8Array([128, 192, 64, 128]); // ±0.5 Auslenkung
    const lvl = rmsLevel(buf);
    expect(lvl).toBeGreaterThan(0);
    expect(lvl).toBeLessThan(1);
  });
});

// ── VoiceRecorder: Fehlerpfade + Happy-Path (Browser-APIs gemockt) ─────────────

class FakeMediaRecorder {
  static supported = new Set<string>(['audio/webm;codecs=opus']);
  static isTypeSupported(t: string): boolean {
    return FakeMediaRecorder.supported.has(t);
  }
  state: 'inactive' | 'recording' = 'inactive';
  mimeType: string;
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  constructor(_stream: unknown, opts?: { mimeType?: string }) {
    this.mimeType = opts?.mimeType ?? '';
  }
  start(): void {
    this.state = 'recording';
  }
  stop(): void {
    this.state = 'inactive';
    this.ondataavailable?.({ data: new Blob(['hörprobe'], { type: this.mimeType || 'audio/webm' }) });
    this.onstop?.();
  }
}

function stubMic(getUserMedia: () => Promise<unknown>): void {
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } });
  vi.stubGlobal('MediaRecorder', FakeMediaRecorder);
  // AudioContext bewusst NICHT stubben → setupLevelMeter wird übersprungen
  // (try/catch), die Aufnahme läuft trotzdem.
}

describe('VoiceRecorder — Fehlerpfade', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('mappt NotAllowedError auf VoiceRecorderError(permission-denied)', async () => {
    stubMic(() => Promise.reject(Object.assign(new Error('nope'), { name: 'NotAllowedError' })));
    const rec = new VoiceRecorder();
    await expect(rec.start()).rejects.toMatchObject({
      name: 'VoiceRecorderError',
      kind: 'permission-denied',
    });
  });

  it('mappt NotFoundError auf VoiceRecorderError(no-device)', async () => {
    stubMic(() => Promise.reject(Object.assign(new Error('weg'), { name: 'NotFoundError' })));
    const rec = new VoiceRecorder();
    await expect(rec.start()).rejects.toMatchObject({ kind: 'no-device' });
  });

  it('ohne mediaDevices → unsupported (kein Crash)', async () => {
    vi.stubGlobal('navigator', {});
    const rec = new VoiceRecorder();
    const err = await rec.start().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(VoiceRecorderError);
    expect((err as VoiceRecorderError).kind).toBe('unsupported');
  });
});

describe('VoiceRecorder — Happy-Path', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('start() → stop() liefert einen Blob im gewählten webm/opus-Typ', async () => {
    const tracks = [{ stop: vi.fn() }];
    stubMic(() => Promise.resolve({ getTracks: () => tracks }));

    const rec = new VoiceRecorder();
    await rec.start();
    expect(rec.isRecording).toBe(true);

    const blob = await rec.stop();
    expect(blob.size).toBeGreaterThan(0);
    expect(blob.type).toBe('audio/webm;codecs=opus'); // pickMimeType-Wahl
    expect(rec.isRecording).toBe(false);
    expect(tracks[0].stop).toHaveBeenCalled(); // Mikro-Track freigegeben
  });
});

// ── Pre-Roll (Andi-Befund 26.07, Anlaut-Beschneidung: "Wie zieht eine Kuh die
// Hose an" → "Zieht eine Kuh eine Hose an") ─────────────────────────────────
//
// Die reine Ring-/Prefix-Logik ist in preroll.test.ts geprüft. Hier geht es NUR
// um die VERKABELUNG in VoiceRecorder: startet der Ring, sobald der Stream
// steht (vor MediaRecorder.start()), friert er nach PRE_ROLL_MS ein, und stoppt
// er spätestens beim Stream-Ende (teardown), auch wenn der Timer noch nicht
// abgelaufen ist? Ein Fake-AudioContext liefert einen kontrollierbaren
// ScriptProcessor, dessen `onaudioprocess` der Test manuell auslöst — echte
// Audio-Hardware ist dafür nicht nötig.

interface FakeProcessor {
  onaudioprocess: ((e: { inputBuffer: { getChannelData: () => Float32Array } }) => void) | null;
  connect: () => void;
  disconnect: ReturnType<typeof vi.fn>;
}

class FakeAudioContext {
  static instances: FakeAudioContext[] = [];
  sampleRate = 48000;
  destination = {};
  closed = false;
  processors: FakeProcessor[] = [];
  constructor() {
    FakeAudioContext.instances.push(this);
  }
  createMediaStreamSource() {
    return { connect: () => {} };
  }
  createScriptProcessor(): FakeProcessor {
    const p: FakeProcessor = { onaudioprocess: null, connect: () => {}, disconnect: vi.fn() };
    this.processors.push(p);
    return p;
  }
  createGain() {
    return { gain: { value: 0 }, connect: () => {} };
  }
  createAnalyser() {
    return { fftSize: 1024, connect: () => {}, getByteTimeDomainData: () => {} };
  }
  close() {
    this.closed = true;
    return Promise.resolve();
  }
}

/** Simuliert einen Audio-Callback: `samples` fließen als EIN Chunk in den Ring. */
function fireAudioProcess(p: FakeProcessor, samples: number[]): void {
  p.onaudioprocess?.({ inputBuffer: { getChannelData: () => new Float32Array(samples) } });
}

describe('VoiceRecorder — Pre-Roll (Ring-Verkabelung)', () => {
  beforeEach(() => {
    FakeAudioContext.instances = [];
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  /** stubMic + AudioContext (anders als die Fehlerpfad-/Happy-Path-Tests oben,
   * die AudioContext bewusst weglassen — hier ist er der Punkt der Übung). */
  function stubMicWithAudio(): void {
    const tracks = [{ stop: vi.fn() }];
    vi.stubGlobal('navigator', {
      mediaDevices: { getUserMedia: () => Promise.resolve({ getTracks: () => tracks }) },
    });
    vi.stubGlobal('MediaRecorder', FakeMediaRecorder);
    vi.stubGlobal('AudioContext', FakeAudioContext);
  }

  it('Ring läuft, sobald der Stream steht — VOR MediaRecorder.start()', async () => {
    stubMicWithAudio();
    const rec = new VoiceRecorder();
    await rec.start();
    // GENAU EIN ScriptProcessor wurde angelegt (der Pre-Roll-Ring; der
    // Pegel-Meter nutzt einen Analyser, keinen ScriptProcessor).
    const allProcessors = FakeAudioContext.instances.flatMap((c) => c.processors);
    expect(allProcessors.length).toBe(1);
    rec.cancel();
  });

  it('friert den Ring nach PRE_ROLL_MS ein und stellt ihn per getPreRoll() bereit', async () => {
    stubMicWithAudio();
    const rec = new VoiceRecorder();
    await rec.start();
    const [processor] = FakeAudioContext.instances.flatMap((c) => c.processors);

    fireAudioProcess(processor, [0.1, 0.2]);
    fireAudioProcess(processor, [0.3]);
    expect(rec.getPreRoll().length).toBe(0); // noch nicht eingefroren

    vi.advanceTimersByTime(PRE_ROLL_MS);

    expect(rec.getPreRoll()).toEqual(new Float32Array([0.1, 0.2, 0.3]));
    expect(rec.getPreRollSampleRate()).toBe(48000);
    expect(processor.disconnect).toHaveBeenCalled(); // Ring hat sich abgehängt
    rec.cancel();
  });

  it('Ring stoppt bei Stream-Ende — cancel() VOR Ablauf friert früh ein statt weiterzulaufen', async () => {
    stubMicWithAudio();
    const rec = new VoiceRecorder();
    await rec.start();
    const [processor] = FakeAudioContext.instances.flatMap((c) => c.processors);

    fireAudioProcess(processor, [0.5]);
    rec.cancel(); // deutlich vor PRE_ROLL_MS

    expect(processor.disconnect).toHaveBeenCalled(); // sofort abgehängt, nicht erst nach 500ms
    expect(rec.getPreRoll()).toEqual(new Float32Array([0.5])); // trotzdem: was da war, zählt
  });

  it('stop() liefert denselben früh eingefrorenen Pre-Roll wie cancel()', async () => {
    stubMicWithAudio();
    const rec = new VoiceRecorder();
    await rec.start();
    const [processor] = FakeAudioContext.instances.flatMap((c) => c.processors);
    fireAudioProcess(processor, [0.7, 0.8]);

    await rec.stop();

    expect(rec.getPreRoll()).toEqual(new Float32Array([0.7, 0.8]));
  });

  it('ein NEUER start() setzt den Pre-Roll der vorigen Aufnahme zurück', async () => {
    stubMicWithAudio();
    const rec = new VoiceRecorder();

    await rec.start();
    const [first] = FakeAudioContext.instances.flatMap((c) => c.processors);
    fireAudioProcess(first, [0.9]);
    vi.advanceTimersByTime(PRE_ROLL_MS);
    expect(rec.getPreRoll().length).toBe(1);

    await rec.stop();
    await rec.start(); // zweite Aufnahme — frischer Ring, kein Rest vom ersten Turn

    expect(rec.getPreRoll().length).toBe(0);
    rec.cancel();
  });

  it('ohne Web-Audio (kein AudioContext) bleibt der Pre-Roll leer — Aufnahme läuft trotzdem', async () => {
    const tracks = [{ stop: vi.fn() }];
    vi.stubGlobal('navigator', {
      mediaDevices: { getUserMedia: () => Promise.resolve({ getTracks: () => tracks }) },
    });
    vi.stubGlobal('MediaRecorder', FakeMediaRecorder);
    // AudioContext bewusst NICHT gestubbt (wie im bestehenden Happy-Path-Test).

    const rec = new VoiceRecorder();
    await rec.start();
    expect(rec.isRecording).toBe(true);
    expect(rec.getPreRoll().length).toBe(0);
    expect(rec.getPreRollSampleRate()).toBe(0);
    await rec.stop();
  });
});
