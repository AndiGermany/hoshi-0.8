// Voice-Phase-2: Browser-AUFNAHME (Push-to-Talk). Das Gegenstück zu
// playback.ts (Wiedergabe). `getUserMedia` öffnet das Mikro, `MediaRecorder`
// nimmt einen WebM/Opus-Blob auf, ein `AnalyserNode` liefert einen Live-Pegel
// (0..1 RMS) für das Pegel-Meter. Die reinen, unit-testbaren Teile
// (`pickMimeType`, `rmsLevel`) sind bewusst frei von DOM/Hardware, damit sie
// ohne echtes Mikrofon getestet werden können.
//
// PRE-ROLL (Andi-Befund 26.07, Anlaut-Beschneidung — s. audio/preRoll.ts für die
// volle Herleitung): PARALLEL zum MediaRecorder läuft ab dem Moment, in dem der
// Mikro-Stream steht, eine zweite, rohe PCM-Sammlung in einen kleinen Ring
// (`setupPreRoll`). Nach `PRE_ROLL_MS` wird der Ring eingefroren — sein Inhalt
// ({@link VoiceRecorder.getPreRoll}) deckt genau das Fenster ab, in dem
// MediaRecorders eigener Encoder-Anlauf (Opus-Priming, AGC/NS-Einschwingzeit)
// Audio verschlucken oder dämpfen kann. `wav.ts` stellt ihn der eigentlichen
// Aufnahme voran, bevor daraus das Upload-WAV entsteht.

import { PRE_ROLL_MS, createPreRollRing, drainPreRoll, pushPreRoll, type PreRollRing } from './preRoll';
import { getActiveUiLanguage } from '../i18n/activeLanguageStore';
import { resolveUiStrings } from '../i18n/catalogs';

/**
 * Chunk-Größe des Pre-Roll-`ScriptProcessorNode` in Samples (Web-Audio-Vorgabe:
 * Zweierpotenz 256..16384). 2048 ≈ 43-46ms bei 44.1/48kHz — fein genug, um
 * innerhalb von {@link PRE_ROLL_MS} mehrfach zu feuern, grob genug für wenig
 * Overhead.
 */
const PRE_ROLL_CHUNK_SAMPLES = 2048;

/** Worüber eine Aufnahme scheitern kann — die UI zeigt je Fall eine warme Zeile. */
export type VoiceRecorderErrorKind =
  | 'permission-denied' // User hat das Mikro abgelehnt (NotAllowedError)
  | 'no-device' // kein Mikrofon gefunden (NotFoundError)
  | 'insecure-context' // getUserMedia nur über https/localhost
  | 'unsupported' // Browser kann kein getUserMedia/MediaRecorder
  | 'no-data' // Aufnahme lieferte 0 Bytes (zu kurz / stumm)
  | 'unknown';

/** Typisierter Aufnahme-Fehler — `kind` lässt die UI gezielt reagieren. */
export class VoiceRecorderError extends Error {
  readonly kind: VoiceRecorderErrorKind;
  constructor(kind: VoiceRecorderErrorKind, message: string) {
    super(message);
    this.name = 'VoiceRecorderError';
    this.kind = kind;
  }
}

/**
 * Bevorzugte Container/Codec-Reihenfolge. WebM/Opus zuerst (klein, von Chrome/
 * Firefox unterstützt); danach Fallbacks. Leerer Rückgabewert = „nimm den
 * Browser-Default" (Safari liefert oft `audio/mp4`).
 */
export const PREFERRED_MIME_TYPES: readonly string[] = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
];

/** Default-Support-Probe: `MediaRecorder.isTypeSupported`, robust gegen Fehlen. */
function defaultIsTypeSupported(type: string): boolean {
  const Ctor = globalThis.MediaRecorder as typeof MediaRecorder | undefined;
  return typeof Ctor?.isTypeSupported === 'function' && Ctor.isTypeSupported(type);
}

/**
 * Wählt den ersten unterstützten MIME-Typ aus `preferred`. Reine Funktion —
 * `isSupported` ist injizierbar, damit die Wahl ohne echten `MediaRecorder`
 * testbar ist. Leerer String = kein Kandidat unterstützt → Browser-Default.
 */
export function pickMimeType(
  isSupported: (type: string) => boolean = defaultIsTypeSupported,
  preferred: readonly string[] = PREFERRED_MIME_TYPES,
): string {
  for (const type of preferred) {
    if (isSupported(type)) return type;
  }
  return '';
}

/**
 * RMS-Lautstärke (0..1) aus einem Zeitbereichs-Puffer, wie ihn
 * `AnalyserNode.getByteTimeDomainData` füllt: `Uint8` mit 128 = Stille,
 * 0/255 = volle Auslenkung. Reine, deterministische Mathematik.
 */
export function rmsLevel(timeDomain: Uint8Array): number {
  const n = timeDomain.length;
  if (n === 0) return 0;
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const v = (timeDomain[i] - 128) / 128; // → -1..1
    sum += v * v;
  }
  return Math.sqrt(sum / n); // 0..1
}

/**
 * Bildet einen `getUserMedia`-Reject auf einen typisierten Fehler ab. Fünf-Sprachen-
 * Sweep 2026-07-27: die Zeilen kommen jetzt aus dem AKTIVEN UI-Katalog
 * (`UiStrings.micErrors`) statt einer hart deutschen Modul-Konstante — `recorder.ts`
 * hat keinen React-Hook-Zugriff, darum der synchrone Modul-Singleton-Read (Muster von
 * `api/chat.ts`/`api/voice.ts`). DE bleibt byte-gleich zum bisherigen Stand.
 */
function mapGetUserMediaError(err: unknown): VoiceRecorderError {
  if (err instanceof VoiceRecorderError) return err;
  const t = resolveUiStrings(getActiveUiLanguage()).micErrors;
  const name = (err as { name?: string } | null)?.name ?? '';
  switch (name) {
    case 'NotAllowedError':
    case 'PermissionDeniedError':
      return new VoiceRecorderError('permission-denied', t.permissionDenied);
    case 'NotFoundError':
    case 'DevicesNotFoundError':
      return new VoiceRecorderError('no-device', t.noDevice);
    case 'SecurityError':
      return new VoiceRecorderError('insecure-context', t.insecureContext);
    default:
      return new VoiceRecorderError('unknown', t.unknown);
  }
}

export interface VoiceRecorderOptions {
  /** MIME-Typ erzwingen; Default: {@link pickMimeType}. */
  mimeType?: string;
  /** Periodischer Live-Pegel 0..1 (für ein Meter), solange aufgenommen wird. */
  onLevel?: (level: number) => void;
  /** Pegel-Abtastintervall in ms (Default 60ms). */
  levelIntervalMs?: number;
}

/**
 * **VoiceRecorder** — kapselt Mikro-Aufnahme für Push-to-Talk.
 *
 * `start()` öffnet das Mikro und beginnt aufzunehmen, `stop()` beendet und gibt
 * den aufgenommenen Blob zurück, `cancel()` verwirft ohne Blob. Alle Tracks und
 * der (optionale) Pegel-`AudioContext` werden beim Beenden aufgeräumt.
 * Fehler kommen als {@link VoiceRecorderError} (typisiert) heraus.
 */
export class VoiceRecorder {
  private readonly opts: VoiceRecorderOptions;
  private stream: MediaStream | null = null;
  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private mimeType = '';

  // Pegel-Kette (best-effort, von der Aufnahme entkoppelt). Analyser + Puffer
  // leben als Closure im Intervall-Timer; hier halten wir nur, was `teardown()`
  // freigeben muss.
  private audioCtx: AudioContext | null = null;
  private levelTimer: ReturnType<typeof setInterval> | null = null;
  private level = 0;

  // Pre-Roll-Kette (ebenfalls best-effort, s. Datei-Kopf + preRoll.ts): eigener,
  // kurzlebiger AudioContext + ScriptProcessorNode, der NUR bis zum Einfrieren
  // (`finalizePreRoll`, spätestens `teardown()`) läuft — „Stream zu ⇒ Ring weg".
  private preRollCtx: AudioContext | null = null;
  private preRollProcessor: ScriptProcessorNode | null = null;
  private preRollRing: PreRollRing | null = null;
  private preRollTimer: ReturnType<typeof setTimeout> | null = null;
  private capturedPreRoll: Float32Array = new Float32Array(0);
  private preRollSampleRate = 0;

  constructor(opts: VoiceRecorderOptions = {}) {
    this.opts = opts;
  }

  /** true, solange eine Aufnahme läuft. */
  get isRecording(): boolean {
    return this.recorder?.state === 'recording';
  }

  /** Letzter gemessener Pegel (0..1) — auch ohne `onLevel`-Callback abfragbar. */
  getLevel(): number {
    return this.level;
  }

  /**
   * Die im Pre-Roll-Fenster eingefangenen rohen Mono-PCM-Samples (Rate s.
   * {@link getPreRollSampleRate}) — leer, wenn kein Web Audio verfügbar war oder
   * (noch) nichts eingefangen wurde. Bleibt nach `stop()`/`cancel()` gültig
   * abfragbar (erst der NÄCHSTE `start()` setzt zurück), damit der Aufrufer sie
   * in Ruhe der eigentlichen Aufnahme voranstellen kann (s. `wav.ts`).
   */
  getPreRoll(): Float32Array {
    return this.capturedPreRoll;
  }

  /** Sample-Rate der Pre-Roll-Samples — 0, wenn keine eingefangen wurden. */
  getPreRollSampleRate(): number {
    return this.preRollSampleRate;
  }

  /**
   * Mikro anfragen + Aufnahme starten. Wirft {@link VoiceRecorderError}
   * (permission-denied / no-device / unsupported / insecure-context), die die
   * UI warm anzeigt — nie ein unbehandelter Crash.
   */
  async start(): Promise<void> {
    if (this.recorder) return; // schon aktiv → idempotent
    const md = globalThis.navigator?.mediaDevices;
    if (!md || typeof md.getUserMedia !== 'function') {
      const insecure = globalThis.isSecureContext === false;
      const t = resolveUiStrings(getActiveUiLanguage()).micErrors;
      throw new VoiceRecorderError(
        insecure ? 'insecure-context' : 'unsupported',
        insecure ? t.insecureContext : t.noApi,
      );
    }

    let stream: MediaStream;
    try {
      stream = await md.getUserMedia({ audio: true });
    } catch (err) {
      throw mapGetUserMediaError(err);
    }
    this.stream = stream;

    // Frischer Pre-Roll pro Aufnahme + SOFORT starten — „sobald der Mikro-Stream
    // steht" (s. Datei-Kopf), nicht erst nach MediaRecorder-Setup/-Start.
    this.capturedPreRoll = new Float32Array(0);
    this.preRollSampleRate = 0;
    this.setupPreRoll(stream);

    if (typeof globalThis.MediaRecorder === 'undefined') {
      this.teardown();
      throw new VoiceRecorderError(
        'unsupported',
        resolveUiStrings(getActiveUiLanguage()).micErrors.noRecorder,
      );
    }

    this.mimeType = this.opts.mimeType ?? pickMimeType();
    try {
      this.recorder = this.mimeType
        ? new MediaRecorder(stream, { mimeType: this.mimeType })
        : new MediaRecorder(stream);
    } catch {
      // Browser lehnt den gewählten mimeType doch ab → Default nehmen.
      this.recorder = new MediaRecorder(stream);
      this.mimeType = this.recorder.mimeType || '';
    }

    this.chunks = [];
    this.recorder.ondataavailable = (e: BlobEvent) => {
      if (e.data && e.data.size > 0) this.chunks.push(e.data);
    };
    this.recorder.start();
    this.setupLevelMeter(stream);
  }

  /**
   * Aufnahme beenden und den aufgenommenen Blob liefern (WebM/Opus o. Default).
   * Räumt Tracks + Pegel-Context auf. Wirft, falls keine Aufnahme lief.
   */
  async stop(): Promise<Blob> {
    const recorder = this.recorder;
    if (!recorder) {
      // Dev-Assertion (Programmierfehler „stop() vor start()") — über die UI nie
      // erreichbar, s. KDoc `MicErrorStrings.stopWithoutRecording`.
      throw new VoiceRecorderError(
        'unknown',
        resolveUiStrings(getActiveUiLanguage()).micErrors.stopWithoutRecording,
      );
    }
    const type = this.mimeType || recorder.mimeType || 'audio/webm';
    const blob = await new Promise<Blob>((resolve) => {
      recorder.onstop = () => resolve(new Blob(this.chunks, { type }));
      if (recorder.state !== 'inactive') recorder.stop();
      else resolve(new Blob(this.chunks, { type }));
    });
    this.teardown();
    return blob;
  }

  /** Aufnahme abbrechen und verwerfen (Barge-in / Esc) — kein Blob. */
  cancel(): void {
    if (this.recorder && this.recorder.state !== 'inactive') {
      this.recorder.onstop = null;
      try {
        this.recorder.stop();
      } catch {
        /* schon beendet → egal */
      }
    }
    this.teardown();
  }

  /** Mikro-Pegel über `AnalyserNode` messen. Best-effort: Fehler killen die Aufnahme nicht. */
  private setupLevelMeter(stream: MediaStream): void {
    try {
      const Ctor =
        globalThis.AudioContext ??
        (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!Ctor) return;
      const ctx = new Ctor();
      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      source.connect(analyser);
      const data = new Uint8Array(analyser.fftSize);
      this.audioCtx = ctx;
      const intervalMs = this.opts.levelIntervalMs ?? 60;
      this.levelTimer = setInterval(() => {
        analyser.getByteTimeDomainData(data);
        this.level = rmsLevel(data);
        this.opts.onLevel?.(this.level);
      }, intervalMs);
    } catch {
      /* Pegel-Meter ist Beiwerk — Aufnahme läuft auch ohne weiter. */
    }
  }

  /**
   * Pre-Roll-Aufnahme starten (s. Datei-Kopf + preRoll.ts). Best-effort wie
   * {@link setupLevelMeter}: kein Web Audio → einfach kein Pre-Roll, die
   * eigentliche Aufnahme ist davon unberührt. EIGENER `AudioContext` (nicht der
   * vom Pegel-Meter geteilt) — er lebt nur bis {@link finalizePreRoll}, während
   * der Pegel-Meter-Context die ganze Aufnahme über läuft; zwei unabhängige
   * `MediaStreamSource`-Abgriffe auf denselben Track stören sich nicht.
   */
  private setupPreRoll(stream: MediaStream): void {
    try {
      const Ctor =
        globalThis.AudioContext ??
        (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!Ctor) return;
      const ctx = new Ctor();
      const source = ctx.createMediaStreamSource(stream);
      const processor = ctx.createScriptProcessor(PRE_ROLL_CHUNK_SAMPLES, 1, 1);
      const ring = createPreRollRing(ctx.sampleRate, PRE_ROLL_MS);
      processor.onaudioprocess = (e: AudioProcessingEvent) => {
        pushPreRoll(ring, e.inputBuffer.getChannelData(0).slice());
      };
      // ScriptProcessorNode zieht nur Daten, wenn er an ein Ziel angeschlossen
      // ist — über einen Null-Gain, damit nichts hörbar zurückgespiegelt wird
      // (kein Echo/Feedback fürs Mikro selbst).
      const silence = ctx.createGain();
      silence.gain.value = 0;
      source.connect(processor);
      processor.connect(silence);
      silence.connect(ctx.destination);
      this.preRollCtx = ctx;
      this.preRollProcessor = processor;
      this.preRollRing = ring;
      this.preRollTimer = setTimeout(() => this.finalizePreRoll(), PRE_ROLL_MS);
    } catch {
      /* Pre-Roll ist Beiwerk — Aufnahme läuft auch ohne weiter (Anlaut evtl. beschnitten). */
    }
  }

  /**
   * Friert den Ring EINMAL ein: entweder regulär nach `PRE_ROLL_MS` (Timer) oder
   * früher, wenn `teardown()` (stop/cancel) vor Ablauf kommt — eine sehr kurze
   * Aufnahme bekommt dann eben einen kürzeren, aber ehrlichen Pre-Roll statt gar
   * keinen. Idempotent: ein zweiter Aufruf (Timer UND teardown) findet
   * `preRollRing === null` und tut nichts mehr.
   */
  private finalizePreRoll(): void {
    if (this.preRollRing) {
      this.capturedPreRoll = drainPreRoll(this.preRollRing);
      this.preRollSampleRate = this.preRollRing.sampleRate;
      this.preRollRing = null;
    }
    if (this.preRollTimer) {
      clearTimeout(this.preRollTimer);
      this.preRollTimer = null;
    }
    if (this.preRollProcessor) {
      try {
        this.preRollProcessor.disconnect();
      } catch {
        /* ignore */
      }
      this.preRollProcessor = null;
    }
    if (this.preRollCtx) {
      try {
        void this.preRollCtx.close();
      } catch {
        /* ignore */
      }
      this.preRollCtx = null;
    }
  }

  /** Alles freigeben: Timer, Pegel-Context, Mikro-Tracks. Idempotent. */
  private teardown(): void {
    // Ring stoppt spätestens hier — „Stream zu ⇒ Ring weg" (s. preRoll.ts). Bei
    // einer sehr kurzen Aufnahme friert das den Ring VOR Ablauf von PRE_ROLL_MS
    // ein (finalizePreRoll ist idempotent, der reguläre Timer tut dann nichts mehr).
    this.finalizePreRoll();
    if (this.levelTimer) {
      clearInterval(this.levelTimer);
      this.levelTimer = null;
    }
    if (this.audioCtx) {
      try {
        void this.audioCtx.close();
      } catch {
        /* ignore */
      }
      this.audioCtx = null;
    }
    this.level = 0;
    if (this.stream) {
      for (const track of this.stream.getTracks()) {
        try {
          track.stop();
        } catch {
          /* ignore */
        }
      }
      this.stream = null;
    }
    this.recorder = null;
  }
}
