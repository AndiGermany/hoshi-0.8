// Voice-Phase-2: Browser-AUFNAHME (Push-to-Talk). Das Gegenstück zu
// playback.ts (Wiedergabe). `getUserMedia` öffnet das Mikro, `MediaRecorder`
// nimmt einen WebM/Opus-Blob auf, ein `AnalyserNode` liefert einen Live-Pegel
// (0..1 RMS) für das Pegel-Meter. Die reinen, unit-testbaren Teile
// (`pickMimeType`, `rmsLevel`) sind bewusst frei von DOM/Hardware, damit sie
// ohne echtes Mikrofon getestet werden können.

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

/** Bildet einen `getUserMedia`-Reject auf einen typisierten Fehler ab. */
function mapGetUserMediaError(err: unknown): VoiceRecorderError {
  if (err instanceof VoiceRecorderError) return err;
  const name = (err as { name?: string } | null)?.name ?? '';
  switch (name) {
    case 'NotAllowedError':
    case 'PermissionDeniedError':
      return new VoiceRecorderError(
        'permission-denied',
        'Mikro-Zugriff abgelehnt. Erlaube das Mikrofon, dann sprich mit Hoshi.',
      );
    case 'NotFoundError':
    case 'DevicesNotFoundError':
      return new VoiceRecorderError(
        'no-device',
        'Kein Mikrofon gefunden. Schließ eines an, dann probier es erneut.',
      );
    case 'SecurityError':
      return new VoiceRecorderError(
        'insecure-context',
        'Mikro-Zugriff braucht eine sichere Verbindung (https oder localhost).',
      );
    default:
      return new VoiceRecorderError(
        'unknown',
        'Das Mikrofon ließ sich nicht öffnen. Probier es noch einmal.',
      );
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
   * Mikro anfragen + Aufnahme starten. Wirft {@link VoiceRecorderError}
   * (permission-denied / no-device / unsupported / insecure-context), die die
   * UI warm anzeigt — nie ein unbehandelter Crash.
   */
  async start(): Promise<void> {
    if (this.recorder) return; // schon aktiv → idempotent
    const md = globalThis.navigator?.mediaDevices;
    if (!md || typeof md.getUserMedia !== 'function') {
      const insecure = globalThis.isSecureContext === false;
      throw new VoiceRecorderError(
        insecure ? 'insecure-context' : 'unsupported',
        insecure
          ? 'Mikro-Zugriff braucht eine sichere Verbindung (https oder localhost).'
          : 'Dieser Browser unterstützt keine Mikro-Aufnahme.',
      );
    }

    let stream: MediaStream;
    try {
      stream = await md.getUserMedia({ audio: true });
    } catch (err) {
      throw mapGetUserMediaError(err);
    }
    this.stream = stream;

    if (typeof globalThis.MediaRecorder === 'undefined') {
      this.teardown();
      throw new VoiceRecorderError('unsupported', 'Dieser Browser kann kein Audio aufnehmen.');
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
      throw new VoiceRecorderError('unknown', 'stop() ohne aktive Aufnahme.');
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

  /** Alles freigeben: Timer, Pegel-Context, Mikro-Tracks. Idempotent. */
  private teardown(): void {
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
