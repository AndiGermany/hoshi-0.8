import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  pickMimeType,
  rmsLevel,
  PREFERRED_MIME_TYPES,
  VoiceRecorder,
  VoiceRecorderError,
} from '../audio/recorder';

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
