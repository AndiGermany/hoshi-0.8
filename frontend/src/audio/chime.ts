// Wecker-Chime der Klingel-Naht (GET /api/v1/scheduled/fired): ein sanfter,
// Glocken-artiger Klang — Hoshi weckt FREUNDLICH, nie mit Alarm-Geschrill.
// Reine WebAudio-Synthese (kein Asset), Muster wie audio/earcon.ts: drei
// abklingende Sinus-Partialtöne (Grundton + große Terz + leise Oktave) mit
// weichem Attack und langem exponentiellem Decay klingen wie eine sacht
// angeschlagene Glocke. Ein Anschlag dauert ~1,8s ({@link CHIME_STRIKE_S}),
// das Ganze wird 2× mit Pause gespielt ({@link CHIME_STRIKES}/{@link CHIME_GAP_S}).
//
// Aufbau wie earcon.ts/playback.ts: {@link playChime} synthetisiert gegen das
// schmale {@link ChimeContext}-Interface (pur testbar, kein echtes Web-Audio);
// {@link playAlarmChime} kapselt den lazy Singleton-AudioContext + die
// Autoplay-Policy: der Chime feuert NICHT auf einer User-Geste (Timer läuft im
// Hintergrund ab!), ein frischer/suspendierter Context darf also stumm sein.
// Dann schedulen wir NICHTS (sonst staut sich der Klang und detoniert bei der
// nächsten Geste), sondern entsperren nur — visuell klingelt der Toast SOFORT,
// akustisch der erste Schleifen-Tick nach der Geste.

/** Ein Sinus-Partialton des Chimes: Frequenz (Hz) + relativer Pegel (0..1). */
export interface ChimePartial {
  freq: number;
  gain: number;
}

/**
 * Der Klang: D5-Grundton (dieselbe Tonfamilie wie das Turn-Earcon → eine
 * Klangidentität), F#5 als große Terz (Dur = freundlich, kein Moll-Drama),
 * hauchleise D6-Oktave als Glanz. Sinus-only + langer Decay = weiche Glocke.
 */
export const CHIME_PARTIALS: readonly ChimePartial[] = [
  { freq: 587.33, gain: 1 }, // D5 — warmer Grundton
  { freq: 739.99, gain: 0.35 }, // F#5 — große Terz, macht es freundlich
  { freq: 1174.66, gain: 0.15 }, // D6 — leiser Glanz obenauf
];

/** Dauer EINES Glocken-Anschlags (s) — in der 1,5–2s-Spec-Spanne. */
export const CHIME_STRIKE_S = 1.8;

/** Weicher Attack (s): deutlich sanfter als das Earcon — kein Erschrecken. */
export const CHIME_ATTACK_S = 0.015;

/** Pause zwischen den Anschlägen (s). */
export const CHIME_GAP_S = 0.6;

/** Anzahl der Anschläge: 2× wiederholt, dann ist Ruhe (kein Dauerklingeln). */
export const CHIME_STRIKES = 2;

/**
 * Master-Peak (linear). 0.12 × Partial-Summe (1+0.35+0.15) ≈ 0.18 momentaner
 * Worst-Case ≈ −15 dBFS — gut hörbar, aber ein Wecken, kein Alarm.
 */
export const CHIME_PEAK_GAIN = 0.12;

/** Nachlauf hinter der Hüllkurve, bevor die Oszillatoren stoppen (s). */
const CHIME_STOP_MARGIN_S = 0.05;

/** Gesamtdauer des Patterns (s): Anschläge + Pausen dazwischen (~4,2s). */
export const CHIME_TOTAL_S = CHIME_STRIKES * CHIME_STRIKE_S + (CHIME_STRIKES - 1) * CHIME_GAP_S;

/**
 * Wiederhol-Abstand (ms) der Klingel-Schleife: solange ein gefeuertes Item
 * UNBESTÄTIGT ist, klingelt es alle ~4s erneut (der Ring-1-Fix — ein einzelner
 * 4s-Chime, den niemand hört, ist ein verpuffter Wecker). Der 0,2s-Überlapp
 * mit dem exponentiellen Ausklang des Vorgängers ist unhörbar (~0.0001 Gain).
 */
export const CHIME_REPEAT_MS = 4_000;

/**
 * Nur die AudioContext-Teile, die der Chime braucht — als Interface, damit
 * Tests einen Fake injizieren können (Konvention aus earcon.ts/playback.ts).
 */
export interface ChimeContext {
  readonly currentTime: number;
  readonly state: AudioContextState;
  readonly destination: AudioNode;
  resume(): Promise<void>;
  createOscillator(): OscillatorNode;
  createGain(): GainNode;
}

/**
 * Ein Glocken-Anschlag ab `t0`: pro Partialton ein Oszillator → eigener
 * GainNode (Hüllkurve: 0 → Peak in {@link CHIME_ATTACK_S}, exponentiell → ~0
 * bei {@link CHIME_STRIKE_S}) → destination. Deterministisch, pur testbar.
 */
export function playChimeStrike(ctx: ChimeContext, t0: number): void {
  for (const p of CHIME_PARTIALS) {
    const osc = ctx.createOscillator();
    const env = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = p.freq;
    const peak = CHIME_PEAK_GAIN * p.gain;
    env.gain.setValueAtTime(0, t0);
    env.gain.linearRampToValueAtTime(peak, t0 + CHIME_ATTACK_S);
    // exponentialRamp darf nie exakt 0 anfahren → 0.0001 ist unhörbar.
    env.gain.exponentialRampToValueAtTime(0.0001, t0 + CHIME_STRIKE_S);
    osc.connect(env);
    env.connect(ctx.destination);
    osc.start(t0);
    osc.stop(t0 + CHIME_STRIKE_S + CHIME_STOP_MARGIN_S);
  }
}

/** Das ganze Wecker-Pattern: {@link CHIME_STRIKES} Anschläge mit Pause. */
export function playChime(ctx: ChimeContext): void {
  const t0 = ctx.currentTime;
  for (let i = 0; i < CHIME_STRIKES; i++) {
    playChimeStrike(ctx, t0 + i * (CHIME_STRIKE_S + CHIME_GAP_S));
  }
}

// ── Lazy Singleton + Autoplay-Policy fürs echte Browser-Audio ─────────────────

let shared: ChimeContext | null = null;
let gestureArmed = false;

/** Singleton verwerfen — für Tests und falls ein Context kaputt ging. */
export function resetChimeContext(): void {
  shared = null;
  gestureArmed = false;
}

/**
 * Bei der nächsten User-Geste (Klick/Taste) den suspendierten Context
 * entsperren — damit der NÄCHSTE Klingel-Tick (die Schleife läuft alle ~4s,
 * solange etwas unbestätigt ist) hörbar spielt. Ein Listener reicht, egal wie
 * viele Polls dazwischen feuern.
 *
 * Bewusst KEIN Nachholen des versäumten Klangs: entsperrt wird nur der Context,
 * nichts wird abgespielt. Ist das Klingeln bis zur Geste quittiert/gelöscht/
 * verpasst, bleibt es still — s. {@link playAlarmChime}.
 */
function armResumeOnGesture(ctx: ChimeContext): void {
  if (gestureArmed || typeof window === 'undefined') return;
  gestureArmed = true;
  const onGesture = (): void => {
    gestureArmed = false;
    window.removeEventListener('pointerdown', onGesture);
    window.removeEventListener('keydown', onGesture);
    void ctx.resume().catch(() => {});
  };
  window.addEventListener('pointerdown', onGesture);
  window.addEventListener('keydown', onGesture);
}

/**
 * Spielt den Wecker-Chime über einen lazy, geteilten AudioContext.
 * Autoplay-Policy: der Aufruf kommt vom Polling (KEINE Geste) — läuft der
 * Context nicht, wird er best-effort entsperrt (resume + Geste-Listener) und
 * **nichts** geschedult. Fehler (kein Web-Audio, Context tot) sind bewusst
 * still: der Klang ist Komfort, der Banner trägt die Nachricht.
 *
 * **Kein Ton-Stau (Andi-Live-Befund 23.08.2026: „der gelöschte Wecker ging beim
 * Ausklappen trotzdem"):** früher wurde AUCH in einen gesperrten Context
 * geschedult. Dort steht `currentTime` still, also legte jeder 4-s-Takt der
 * Klingel-Schleife weitere Anschläge auf denselben Zeitpunkt — und die erste
 * User-Geste (das Aufklappen des Displays!) entsperrte den Context und spielte
 * den ganzen Stapel auf einmal ab. Quittieren, Löschen oder Verpasst-Werden
 * konnten diesen Klang nicht mehr einfangen: er lag längst im Context. Jetzt
 * gilt: **was nicht hörbar ist, wird gar nicht erst geplant.** Ein noch
 * unbestätigter Wecker klingelt trotzdem — die Schleife ruft weiter, der erste
 * Tick nach der Geste (≤ {@link CHIME_REPEAT_MS}) spielt hörbar.
 *
 * @returns `true`, wenn der Chime JETZT hörbar spielt (Context `running`);
 *   `false`, wenn die Autoplay-Policy ihn (noch) sperrt oder Web-Audio fehlt —
 *   der Aufrufer soll dann EHRLICH visuell eskalieren (Pulsieren, Titel-Blinken)
 *   statt auf einen stummen Klang zu vertrauen.
 */
export function playAlarmChime(makeContext: () => ChimeContext = () => new AudioContext()): boolean {
  try {
    if (!shared) shared = makeContext();
    if (shared.state !== 'running') {
      // Gesperrt/tot: NICHT schedulen (sonst detoniert der Stapel bei der nächsten
      // Geste), nur entsperren — der nächste Tick der Schleife klingelt dann hörbar.
      void shared.resume().catch(() => {});
      armResumeOnGesture(shared);
      return false;
    }
    playChime(shared);
    return true;
  } catch {
    shared = null; // nächster Versuch darf frisch anlegen
    return false;
  }
}
