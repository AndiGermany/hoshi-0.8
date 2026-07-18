// Earcon der Turn-Annahme (Sara-Spec): ein sehr kurzes, leises, weich gedämpftes
// „angenommen"-Geräusch — Glas/Holz-Charakter, NIE ein Beep. Reine WebAudio-
// Synthese (kein Asset): drei Sinus-Partialtöne (Grundton + Quinte + leise
// Oktave) mit Blitz-Attack und exponentiellem Decay klingen wie ein sanft
// angetippter Klangstab, nicht wie ein Piepser.
//
// Warum überhaupt: Cowork-Research — ab ~1s Stille nach der Eingabe bricht die
// Zufriedenheit. Das Earcon bestätigt in <300ms „Hoshi hat dich gehört", lange
// bevor STT/LLM/TTS liefern.
//
// Aufbau wie audio/playback.ts: die synthetisierende Logik ({@link playEarcon})
// arbeitet gegen ein schmales {@link EarconContext}-Interface und ist damit ohne
// echtes Web-Audio testbar; {@link playTurnEarcon} kapselt den lazy Singleton-
// AudioContext (entsteht erst auf einer User-Geste — send/mic IST eine Geste,
// die Autoplay-Policy blockiert also nicht).

/** Ein Sinus-Partialton des Earcons: Frequenz (Hz) + relativer Pegel (0..1). */
export interface EarconPartial {
  freq: number;
  gain: number;
}

/**
 * Der Klang: D5-Grundton, A5-Quinte, hauchleise D6-Oktave. Der Quint-Cluster
 * mit schnellem Decay liest sich als weiches „Glas/Holz-Tock" — ein einzelner
 * Sinus wäre genau der Beep, den die Spec verbietet.
 */
export const EARCON_PARTIALS: readonly EarconPartial[] = [
  { freq: 587.33, gain: 1 }, // D5 — warmer Grundton
  { freq: 880.0, gain: 0.45 }, // A5 — Quinte, macht den Cluster
  { freq: 1174.66, gain: 0.16 }, // D6 — leiser Glanz obenauf
];

/** Gesamtdauer der Hüllkurve (s) — deutlich unter der 300ms-Sara-Grenze. */
export const EARCON_DURATION_S = 0.15;

/** Blitz-Attack (s): schnell genug für „sofort", langsam genug gegen Klick-Knacksen. */
export const EARCON_ATTACK_S = 0.006;

/**
 * Master-Peak (linear). 0.08 × Partial-Summe (1+0.45+0.16) ≈ 0.13 momentaner
 * Worst-Case ≈ −18 dBFS — leise Bestätigung, nie ein Alarm.
 */
export const EARCON_PEAK_GAIN = 0.08;

/** Nachlauf hinter der Hüllkurve, bevor die Oszillatoren stoppen (s). */
const EARCON_STOP_MARGIN_S = 0.03;

/**
 * Nur die AudioContext-Teile, die das Earcon braucht — als Interface, damit
 * Tests einen Fake injizieren können (Konvention aus playback.ts).
 */
export interface EarconContext {
  readonly currentTime: number;
  readonly state: AudioContextState;
  readonly destination: AudioNode;
  resume(): Promise<void>;
  createOscillator(): OscillatorNode;
  createGain(): GainNode;
}

/**
 * Synthetisiert das Earcon auf `ctx`: pro Partialton ein Oszillator → eigener
 * GainNode (Hüllkurve: 0 → Peak in {@link EARCON_ATTACK_S}, exponentiell → ~0
 * bei {@link EARCON_DURATION_S}) → destination. Deterministisch gegen das
 * Interface — die eigentliche „Klang-Logik", pur testbar.
 */
export function playEarcon(ctx: EarconContext): void {
  const t0 = ctx.currentTime;
  for (const p of EARCON_PARTIALS) {
    const osc = ctx.createOscillator();
    const env = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = p.freq;
    const peak = EARCON_PEAK_GAIN * p.gain;
    env.gain.setValueAtTime(0, t0);
    env.gain.linearRampToValueAtTime(peak, t0 + EARCON_ATTACK_S);
    // exponentialRamp darf nie exakt 0 anfahren → 0.0001 ist unhörbar.
    env.gain.exponentialRampToValueAtTime(0.0001, t0 + EARCON_DURATION_S);
    osc.connect(env);
    env.connect(ctx.destination);
    osc.start(t0);
    osc.stop(t0 + EARCON_DURATION_S + EARCON_STOP_MARGIN_S);
  }
}

// ── Lazy Singleton fürs echte Browser-Audio ───────────────────────────────────

let shared: EarconContext | null = null;

/** Singleton verwerfen — für Tests und falls ein Context kaputt ging. */
export function resetEarconContext(): void {
  shared = null;
}

/**
 * Spielt das Turn-Annahme-Earcon über einen lazy, geteilten AudioContext.
 * Auf einer User-Geste aufrufen (send/mic) — dann entsperrt `resume()` einen
 * suspendierten Context. Fehler (kein Web-Audio, Context tot) sind bewusst
 * still: das Earcon ist Komfort, nie kritisch — es darf keinen Turn töten.
 */
export function playTurnEarcon(
  makeContext: () => EarconContext = () => new AudioContext(),
): void {
  try {
    if (!shared) shared = makeContext();
    if (shared.state === 'suspended') void shared.resume().catch(() => {});
    playEarcon(shared);
  } catch {
    shared = null; // nächster Versuch darf frisch anlegen
  }
}
