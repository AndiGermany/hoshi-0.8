// Voice-Phase-1: Browser-Wiedergabe von Hoshis gesprochener Antwort (TTS).
//
// Das Backend streamt bei `speak:true` über denselben SSE-Strom:
//   `tts_audio_start` → mehrere `audio`{data: base64-WAV, seq} → `tts_audio_end`.
// Diese Datei kapselt die reine, unit-testbare Logik (base64→ArrayBuffer,
// Reihenfolge nach `seq`, Flush) und die Web-Audio-Wiedergabe getrennt vom DOM,
// damit sie ohne echtes Audio/Netz getestet werden kann.

/** Abspiel-Tempo der TTS-Wiedergabe (1.0 = Original). Andi: „etwas schneller". */
export const PLAYBACK_RATE = 1.12;

/**
 * Dekodiert eine base64-Payload (optional mit `data:…;base64,`-Präfix) in einen
 * `ArrayBuffer`. Reine Funktion — kein DOM, kein Netz, deterministisch.
 */
export function base64ToArrayBuffer(base64: string): ArrayBuffer {
  const comma = base64.indexOf(',');
  const head = base64.slice(0, comma);
  const raw =
    comma !== -1 && head.startsWith('data:') ? base64.slice(comma + 1) : base64;
  const clean = raw.replace(/\s/g, ''); // tolerant ggü. Zeilenumbrüchen
  const binary = atob(clean);
  const len = binary.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

/**
 * Nur die `AudioContext`-Teile, die die Queue braucht. Als Interface, damit
 * Tests einen Fake injizieren können (kein echtes Web-Audio nötig).
 */
export interface PlaybackContext {
  readonly state: AudioContextState;
  readonly destination: AudioNode;
  resume(): Promise<void>;
  close(): Promise<void>;
  decodeAudioData(audioData: ArrayBuffer): Promise<AudioBuffer>;
  createBufferSource(): AudioBufferSourceNode;
  /**
   * Optional: ein `AnalyserNode` für den Ausgabe-Pegel (Voice-Stern beim
   * Sprechen). Echte `AudioContext` liefern ihn; Test-Fakes lassen ihn weg →
   * die Queue läuft dann ohne Pegel weiter (best-effort, kein Bruch).
   */
  createAnalyser?(): AnalyserNode;
}

/** localStorage-Schlüssel für die Sprich-Modus-Präferenz. */
const SPEAK_PREF_KEY = 'hoshi.voiceOutput';

/**
 * Liest die persistierte Sprich-Modus-Präferenz. **Default: AN** (Audio-Ausgabe
 * an — Andis Wunsch 2026-07-01). Nur ein explizit gespeichertes `'0'` (User hat
 * bewusst ausgeschaltet) schaltet sie aus; unbelegt oder `'1'` ⇒ an.
 */
export function loadSpeakPref(): boolean {
  try {
    return globalThis.localStorage?.getItem(SPEAK_PREF_KEY) !== '0';
  } catch {
    return true;
  }
}

/** Persistiert die Sprich-Modus-Präferenz. Fehler werden geschluckt. */
export function saveSpeakPref(on: boolean): void {
  try {
    globalThis.localStorage?.setItem(SPEAK_PREF_KEY, on ? '1' : '0');
  } catch {
    /* localStorage nicht verfügbar (z. B. privater Modus) → ignorieren */
  }
}

/**
 * Spielt eingehende base64-WAV-Chunks **lückenlos und in `seq`-Reihenfolge** ab.
 *
 * Warum nötig: `decodeAudioData` ist asynchron und kann in anderer Reihenfolge
 * fertig werden, als die Chunks ankommen. Die Queue puffert dekodierte Buffer
 * nach `seq` und spielt streng aufsteigend ab `head` (= `seq` des ersten Chunks)
 * — nie überlappend. Defekte Chunks werden übersprungen, statt den Turn zu töten.
 *
 * Eine `generation` (Epoche) entwertet Decodes aus einem alten Turn: `stop()`
 * erhöht sie, sodass spät eintreffende Decodes desselben Turns nichts mehr
 * abspielen (kein „Geister-Audio").
 */
export class AudioQueue {
  private readonly makeContext: () => PlaybackContext;
  private ctx: PlaybackContext | null = null;

  /** Dekodierte (oder als defekt markierte) Buffer, nach seq. null = überspringen. */
  private readonly ready = new Map<number, AudioBuffer | null>();
  /** Nächste abzuspielende seq; null, bis der erste Chunk anliegt. */
  private head: number | null = null;
  private current: AudioBufferSourceNode | null = null;
  private playing = false;
  private generation = 0;

  // Ausgabe-Pegel-Abgriff für den Voice-Stern (beim Sprechen). Best-effort: ein
  // geteilter AnalyserNode zwischen den Quellen und der Destination. Fehlt
  // `createAnalyser` (Test-Fake) oder scheitert es, bleibt er null → kein Pegel,
  // aber die Wiedergabe läuft unverändert (Quelle → destination).
  private analyser: AnalyserNode | null = null;

  constructor(makeContext: () => PlaybackContext = () => new AudioContext()) {
    this.makeContext = makeContext;
  }

  private ensure(): PlaybackContext {
    if (!this.ctx) {
      this.ctx = this.makeContext();
      this.setupAnalyser(this.ctx);
    }
    return this.ctx;
  }

  /** Einen geteilten AnalyserNode aufsetzen (best-effort, einmal pro Context). */
  private setupAnalyser(ctx: PlaybackContext): void {
    if (typeof ctx.createAnalyser !== 'function') return; // Test-Fake → kein Pegel
    try {
      const a = ctx.createAnalyser();
      a.fftSize = 256; // frequencyBinCount = 128
      a.smoothingTimeConstant = 0.8;
      a.connect(ctx.destination); // Pegel-Abgriff → hörbar weiter an die Ausgabe
      this.analyser = a;
    } catch {
      this.analyser = null; // ohne Pegel weiterspielen
    }
  }

  /**
   * Aktueller Ausgabe-Pegel (roh-RMS 0..1) aus dem AnalyserNode — der ECHTE
   * TTS-Ausgabepegel, der die Welle beim Sprechen treibt (nichts leuchtet,
   * was nichts misst). 0, wenn kein Analyser existiert oder nichts läuft.
   * Wahrnehmungs-Gamma (motionTokens.gammaLevel) + Glättung (audio/level.ts)
   * passieren in der UI — hier nur der ehrliche Roh-Pegel.
   */
  getOutputLevel(): number {
    const a = this.analyser;
    if (!a) return 0;
    const data = new Uint8Array(a.frequencyBinCount);
    a.getByteFrequencyData(data);
    let sum = 0;
    for (let i = 0; i < data.length; i++) sum += data[i] * data[i];
    return Math.sqrt(sum / data.length) / 255; // 0..1
  }

  /**
   * Auf einer User-Geste aufrufen (Toggle-/Senden-Klick): erzeugt den
   * AudioContext und entsperrt ihn (Autoplay-Policy verlangt eine Geste).
   */
  start(): void {
    const ctx = this.ensure();
    if (ctx.state === 'suspended') void ctx.resume().catch(() => {});
  }

  /**
   * Reiht einen base64-WAV-Chunk ein. Dekodiert asynchron; spielt ab, sobald
   * der Buffer für die laufende `head`-Position bereit ist.
   */
  enqueue(base64: string, seq: number): void {
    const ctx = this.ensure();
    // Defensiv entsperren (stummer Voice-Turn, RCA „17 Chunks, 0 Ton"): der
    // Sprach-Turn teilt diesen Context mit der Text-Wiedergabe, und der Mikro-
    // Teardown (recorder.ts, eigener Meter-Context + getUserMedia-Abbau) kann
    // ihn suspendieren. Auf einer suspendierten Uhr startet `src.start()` zwar,
    // aber es läuft keine Zeit → `onended` feuert nie, alles bleibt stumm.
    // resume() ist hier legitim: jeder Turn beginnt mit einer User-Geste
    // (Mikro/Senden), Chromes Sticky Activation erlaubt das Resume danach.
    if (ctx.state === 'suspended') void ctx.resume().catch(() => {});
    if (this.head === null) this.head = seq; // erster Chunk setzt den Startpunkt
    const myGen = this.generation;

    let data: ArrayBuffer;
    try {
      data = base64ToArrayBuffer(base64);
    } catch {
      // Schon das base64 ist Müll → als defekt markieren, head darf vorrücken.
      this.ready.set(seq, null);
      this.pump();
      return;
    }

    ctx.decodeAudioData(data).then(
      (decoded) => {
        if (myGen !== this.generation) return; // alter Turn → verwerfen
        this.ready.set(seq, decoded);
        this.pump();
      },
      () => {
        if (myGen !== this.generation) return;
        this.ready.set(seq, null); // defekter Chunk → überspringen
        this.pump();
      },
    );
  }

  /** Spielt den nächsten fertigen Buffer, falls gerade nichts läuft. */
  private pump(): void {
    if (this.playing || this.head === null) return;
    while (this.ready.has(this.head)) {
      const buffer = this.ready.get(this.head);
      this.ready.delete(this.head);
      this.head++;
      if (!buffer) continue; // null = defekter Chunk übersprungen
      this.playSource(buffer);
      return;
    }
  }

  private playSource(buffer: AudioBuffer): void {
    const ctx = this.ensure();
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    // Etwas schneller abspielen — OpenAI-coral wirkt im Originaltempo (1.0) einen
    // Tick zu gemächlich. ~1.12 strafft die Kadenz; der leichte Pitch-Anstieg ist
    // unauffällig (für pitch-neutrales Stretchen bräuchte es Time-Stretching).
    src.playbackRate.value = PLAYBACK_RATE;
    // Über den Analyser (falls vorhanden) → er ist mit der Destination verbunden,
    // sodass der Ausgabe-Pegel abgreifbar ist; sonst direkt an die Ausgabe.
    src.connect((this.analyser ?? ctx.destination) as AudioNode);
    this.current = src;
    this.playing = true;
    src.onended = () => {
      this.playing = false;
      this.current = null;
      this.pump();
    };
    src.start();
  }

  /**
   * Stoppt die laufende Wiedergabe und verwirft alle gepufferten/laufenden
   * Decodes (neuer Turn, Sprich-Modus aus, oder Unmount). Danach wiederverwendbar.
   */
  stop(): void {
    this.generation++;
    if (this.current) {
      try {
        this.current.onended = null;
        this.current.stop();
      } catch {
        /* schon beendet → egal */
      }
      this.current = null;
    }
    this.ready.clear();
    this.head = null;
    this.playing = false;
  }

  /** Vollständiger Teardown: stoppt und schließt den AudioContext. */
  close(): void {
    this.stop();
    if (this.ctx) {
      try {
        void this.ctx.close();
      } catch {
        /* ignore */
      }
      this.ctx = null;
    }
    this.analyser = null; // mit dem Context entsorgt
  }
}
