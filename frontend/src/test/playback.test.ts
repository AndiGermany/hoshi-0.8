import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  base64ToArrayBuffer,
  AudioQueue,
  loadSpeakPref,
  saveSpeakPref,
  type PlaybackContext,
} from '../audio/playback';

// ── Test-Hilfen ───────────────────────────────────────────────────────────────

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
}
function defer<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Microtask-Queue leerlaufen lassen (decodeAudioData-`.then`-Callbacks). */
const tick = () => new Promise<void>((r) => setTimeout(r, 0));

/** Marker-„AudioBuffer", der seine seq trägt — so kennt start() die Reihenfolge. */
const marker = (seq: number) => ({ seq }) as unknown as AudioBuffer;

/** base64 aus rohen Bytes (btoa ist in der DOM-lib + Node-Runtime vorhanden). */
const bytesToB64 = (bytes: number[]) => btoa(String.fromCharCode(...bytes));

/** base64 eines 3-Byte-Chunks, dessen erstes Byte die seq kodiert. */
const chunkB64 = (seq: number) => bytesToB64([seq, 0xaa, 0xbb]);

/**
 * Fake-AudioContext: registriert pro seq (= erstes Byte) ein Deferred, das der
 * Test in beliebiger Reihenfolge auflöst/ablehnt. `start()` schiebt die seq der
 * gerade laufenden Quelle in `played` und feuert `onended` synchron, damit die
 * Queue sofort den nächsten Buffer zieht.
 */
function makeFakeContext(
  decodes: Map<number, Deferred<AudioBuffer>>,
  played: number[],
  state: AudioContextState = 'running',
): PlaybackContext {
  return {
    state,
    destination: {} as AudioNode,
    resume: () => Promise.resolve(),
    close: () => Promise.resolve(),
    decodeAudioData: (data: ArrayBuffer): Promise<AudioBuffer> => {
      const seq = new Uint8Array(data)[0];
      const d = defer<AudioBuffer>();
      decodes.set(seq, d);
      return d.promise;
    },
    createBufferSource: (): AudioBufferSourceNode => {
      const node = {
        buffer: null as AudioBuffer | null,
        onended: null as null | (() => void),
        playbackRate: { value: 1 },
        connect: () => node as unknown as AudioNode,
        start: () => {
          const buf = node.buffer as unknown as { seq: number } | null;
          if (buf) played.push(buf.seq);
          node.onended?.();
        },
        stop: () => {},
      };
      return node as unknown as AudioBufferSourceNode;
    },
  };
}

// ── base64ToArrayBuffer ────────────────────────────────────────────────────────

describe('base64ToArrayBuffer', () => {
  it('dekodiert base64 byte-genau (Round-Trip)', () => {
    const bytes = [0x52, 0x49, 0x46, 0x46, 0x00, 0x01, 0x7f, 0xff];
    const out = new Uint8Array(base64ToArrayBuffer(bytesToB64(bytes)));
    expect(Array.from(out)).toEqual(bytes);
  });

  it('toleriert ein data:-URL-Präfix und Whitespace', () => {
    const bytes = [1, 2, 3, 4];
    const out = new Uint8Array(base64ToArrayBuffer(`data:audio/wav;base64,${bytesToB64(bytes)}\n`));
    expect(Array.from(out)).toEqual(bytes);
  });
});

// ── AudioQueue: seq-Reihenfolge & Fehlertoleranz ───────────────────────────────

describe('AudioQueue', () => {
  it('spielt in seq-Reihenfolge, auch wenn Decodes verkehrt fertig werden', async () => {
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const q = new AudioQueue(() => makeFakeContext(decodes, played));

    // Ankunft in seq-Reihenfolge (wie SSE liefert) …
    q.enqueue(chunkB64(0), 0);
    q.enqueue(chunkB64(1), 1);
    q.enqueue(chunkB64(2), 2);

    // … aber Decode wird verkehrt herum fertig: 2, dann 1.
    decodes.get(2)!.resolve(marker(2));
    decodes.get(1)!.resolve(marker(1));
    await tick();
    expect(played).toEqual([]); // ohne seq 0 darf nichts laufen (kein Überlappen)

    decodes.get(0)!.resolve(marker(0));
    await tick();
    expect(played).toEqual([0, 1, 2]); // lückenlos in seq-Reihenfolge
  });

  it('überspringt einen defekten Chunk und spielt den Rest weiter', async () => {
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const q = new AudioQueue(() => makeFakeContext(decodes, played));

    q.enqueue(chunkB64(0), 0);
    q.enqueue(chunkB64(1), 1);
    q.enqueue(chunkB64(2), 2);

    decodes.get(0)!.resolve(marker(0));
    await tick();
    expect(played).toEqual([0]);

    decodes.get(1)!.reject(new Error('kaputtes WAV')); // defekt → überspringen
    await tick();
    expect(played).toEqual([0]); // head rückt über 1 hinweg, wartet auf 2

    decodes.get(2)!.resolve(marker(2));
    await tick();
    expect(played).toEqual([0, 2]); // Turn lebt weiter, nur 1 fehlt
  });

  it('stop() flusht und verhindert Geister-Audio aus altem Turn', async () => {
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const q = new AudioQueue(() => makeFakeContext(decodes, played));

    q.enqueue(chunkB64(0), 0);
    q.stop(); // neue Generation → späte Decodes sind entwertet
    decodes.get(0)!.resolve(marker(0));
    await tick();
    expect(played).toEqual([]); // nichts läuft mehr

    // Nach stop() wiederverwendbar: neuer Turn beginnt sauber bei seiner ersten seq.
    q.enqueue(chunkB64(5), 5);
    decodes.get(5)!.resolve(marker(5));
    await tick();
    expect(played).toEqual([5]);
  });

  it('getOutputLevel() ist 0 ohne AnalyserNode (Fake-Context → best-effort)', () => {
    // Der Test-Fake liefert kein createAnalyser → die Queue spielt unverändert
    // (Quelle → destination) und der Pegel ist ehrlich 0 statt eines Crashs.
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const q = new AudioQueue(() => makeFakeContext(decodes, played));
    q.enqueue(chunkB64(0), 0); // erzwingt ensure() → setupAnalyser (no-op hier)
    expect(q.getOutputLevel()).toBe(0);
  });

  it('getOutputLevel() rechnet den echten RMS-Pegel aus den Analyser-Frequenzdaten (Analyser vorhanden)', () => {
    // Andi-Auftrag 19.07: die TTS-Ausgabe soll den Orb genauso echt treiben wie
    // das Mikro. Dieser Test pinnt den ANDEREN Zweig von setupAnalyser — nicht
    // nur sein (bereits getestetes) Fehlen, sondern den Erfolgsfall: ein echter
    // AnalyserNode liefert Frequenzdaten, getOutputLevel() destilliert daraus
    // den ehrlichen roh-RMS (0..1) — sqrt(mean(x²))/255, bei konstantem x exakt
    // x/255. Wahrnehmungs-Gamma/Glättung passieren bewusst NICHT hier (UI-Seite).
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const frequencyData = new Array(8).fill(128); // uniform → RMS = 128/255
    const ctx: PlaybackContext = {
      ...makeFakeContext(decodes, played),
      createAnalyser: () =>
        ({
          fftSize: 256,
          smoothingTimeConstant: 0.8,
          frequencyBinCount: frequencyData.length,
          connect: () => {},
          getByteFrequencyData: (arr: Uint8Array) => arr.set(frequencyData),
        }) as unknown as AnalyserNode,
    };
    const q = new AudioQueue(() => ctx);

    q.enqueue(chunkB64(0), 0); // erzwingt ensure() → setupAnalyser (erfolgreich hier)
    expect(q.getOutputLevel()).toBeCloseTo(128 / 255, 5);
  });

  it('createAnalyser() wirft (Safari-Eigenheit) ⇒ kein Crash, Pegel bleibt ehrlich 0, Wiedergabe läuft unverändert weiter', async () => {
    // Ehrlicher Fallback (Auftrag: „wenn der Analyser-Pfad nicht verfügbar
    // ist … kein erfundenes Wabern"): setupAnalyser() fängt einen werfenden
    // createAnalyser() ab (analyser bleibt null), die Wiedergabe verbindet
    // dann direkt Quelle → destination statt über den Analyser — der Turn
    // bleibt hörbar, nur ohne Pegel-Abgriff.
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const ctx: PlaybackContext = {
      ...makeFakeContext(decodes, played),
      createAnalyser: () => {
        throw new Error('AnalyserNode nicht verfügbar');
      },
    };
    const q = new AudioQueue(() => ctx);

    expect(() => q.enqueue(chunkB64(0), 0)).not.toThrow(); // setupAnalyser fängt den Fehler
    expect(q.getOutputLevel()).toBe(0); // ehrlich: kein Pegel ohne Analyser

    decodes.get(0)!.resolve(marker(0));
    await tick();
    expect(played).toEqual([0]); // Wiedergabe läuft trotzdem weiter
  });

  it('start() entsperrt einen suspendierten AudioContext (Autoplay-Geste)', () => {
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const ctx = makeFakeContext(decodes, played, 'suspended');
    const resume = vi.spyOn(ctx, 'resume');
    const q = new AudioQueue(() => ctx);

    q.start();
    expect(resume).toHaveBeenCalledOnce();
  });

  it('enqueue() entsperrt einen suspendierten Context (stummer Voice-Turn-Fix)', () => {
    // RCA „17 Chunks, 0 Ton": der Sprach-Turn teilt den Context mit der
    // Text-Wiedergabe; der Mikro-Teardown (recorder.ts, eigener Meter-Context)
    // kann ihn suspendieren. Landen Chunks dann per enqueue() auf der
    // eingefrorenen Uhr, feuert onended nie → Hoshi bleibt stumm. enqueue()
    // muss defensiv resumen — nicht nur start() (das der Voice-Pfad früher
    // gar nicht rief).
    const decodes = new Map<number, Deferred<AudioBuffer>>();
    const played: number[] = [];
    const ctx = makeFakeContext(decodes, played, 'suspended');
    const resume = vi.spyOn(ctx, 'resume');
    const q = new AudioQueue(() => ctx);

    q.enqueue(chunkB64(0), 0);
    expect(resume).toHaveBeenCalledOnce();
  });
});

// ── Sprich-Modus-Präferenz (localStorage) ──────────────────────────────────────

describe('Sprich-Modus-Präferenz', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('persistiert und liest aus localStorage (Default: AN — Andi 2026-07-01)', () => {
    const store = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => (store.has(k) ? store.get(k)! : null),
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    });

    expect(loadSpeakPref()).toBe(true); // nichts gespeichert → AN (Default)
    saveSpeakPref(false); // explizit aus
    expect(loadSpeakPref()).toBe(false);
    saveSpeakPref(true);
    expect(loadSpeakPref()).toBe(true);
  });
});
