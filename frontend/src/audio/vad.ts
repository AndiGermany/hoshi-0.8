// Sprachaktivitäts-Erkennung („es kommt kein Ton mehr") für den Browser-Rand.
//
// WARUM: Der Satellit beendet seine Aufnahme selbst — die Firmware erkennt on-device,
// wann jemand aufgehört hat zu sprechen. Im Browser gab es das in 0.8 nicht: dort musste
// man tippen, um zu senden. Andi (21.07.): „die 'es kommt kein Ton mehr erkennung' klappt
// nicht im browser" — „0.5 konnte das".
//
// PORT-HINWEIS (wichtig, nicht wegkürzen): Die MECHANIK stammt aus 0.5
// (`useVoiceSession.ts`, Iter-6-VAD): erst wenn wirklich gesprochen WURDE, startet bei
// Unterschreiten der Stille-Schwelle ein Timer; ununterbrochene Stille über
// [VAD_SILENCE_MS] beendet die Aufnahme. Zwischen den Schwellen liegt eine bewusste
// Hysterese-Zone, die den Timer weder zurücksetzt noch neu startet.
//
// Die KONSTANTEN aus 0.5 sind NICHT übernommen: 0.5 maß den Mittelwert von
// `getByteFrequencyData` × 1.6, 0.8 misst echtes RMS aus `getByteTimeDomainData`
// ({@link rmsLevel}) — verschiedene Skalen. Übernommen ist nur [VAD_SILENCE_MS], weil
// eine Zeitdauer skalenfrei ist.
//
// ── LEHRE AUS DEM ERSTEN ANLAUF (Andi, 21.07. abends) ────────────────────────────────
// Die erste Fassung hatte die STILLE-Schwelle relativ zur Stimme, die SPRECH-Schwelle
// aber absolut (0.06). Über Bluetooth kommen deutlich leisere Pegel an: dieser Wert wurde
// nie erreicht, die Erkennung wurde nie scharf, die Aufnahme stoppte nie. Andi wörtlich:
// „ich bin mit bluetooth verbunden und hoshi stoppt die aufnahme nicht mehr."
//
// Konsequenz: BEIDE Schwellen leiten sich jetzt aus dem GEMESSENEN Rauschboden dieser
// Aufnahme ab. Wir nehmen nicht mehr an, wie laut ein Mikrofon ist — wir messen die
// ersten [VAD_NOISE_PROBE_MS] und rechnen ab da relativ. Damit ist es gleichgültig, ob
// eingebautes Mikro, Headset oder Bluetooth: die Kette funktioniert, solange Sprache
// überhaupt lauter ist als die Umgebung. Die absoluten Böden bleiben nur als Schutz
// gegen ein totes Mikrofon (Rauschboden ~0 ⇒ jedes Knacken wäre sonst „Sprache").

/** Wie lange zu Beginn der Aufnahme der Rauschboden gemessen wird, bevor gewertet wird. */
export const VAD_NOISE_PROBE_MS = 400;

/** Sprache gilt als erkannt ab diesem Vielfachen des gemessenen Rauschbodens. */
export const VAD_SPEECH_OVER_NOISE = 4.0;

/** Still ist es unterhalb dieses Vielfachen des Rauschbodens. */
export const VAD_SILENCE_OVER_NOISE = 1.8;

/** Absoluter Mindest-Pegel für „gesprochen" — Schutz gegen ein totes/stummes Mikrofon. */
export const VAD_SPEECH_FLOOR = 0.012;

/** Absoluter Boden für „still". */
export const VAD_SILENCE_FLOOR = 0.006;

/** Zusätzlich still, wenn deutlich unter dem lautesten Pegel dieser Aufnahme. */
export const VAD_SILENCE_RATIO = 0.18;

/** Wie lange ununterbrochen still, bis automatisch gesendet wird. Aus 0.5 übernommen. */
export const VAD_SILENCE_MS = 2500;

/**
 * Notbremse: nach dieser Dauer wird auch ohne erkannte Stille gesendet. Schützt vor einer
 * offenen Leitung, wenn ein Dauergeräusch (Lüfter, Musik, Straße) die Stille-Schwelle nie
 * unterschreitet — sonst nähme Hoshi endlos auf.
 */
export const VAD_MAX_RECORD_MS = 45_000;

/** Veränderlicher Zustand einer laufenden Messung. Der Aufrufer hält ihn, die Logik ist rein. */
export interface VadState {
  /** Wurde in dieser Aufnahme schon einmal die Sprech-Schwelle überschritten? */
  hasSpoken: boolean;
  /** Zeitstempel des Stille-Beginns (ms), oder `null` wenn gerade nicht still. */
  silenceStart: number | null;
  /** Lautester Pegel dieser Aufnahme. */
  peak: number;
  /** Zeitstempel des Aufnahme-Starts (ms). */
  startedAt: number;
  /** Leisester Pegel der Messphase = Rauschboden dieses Mikrofons, in dieser Umgebung. */
  noiseFloor: number;
  /** Solange `true`, wird nur gemessen und nie gestoppt. */
  probing: boolean;
}

/** Frischer Zustand für eine neue Aufnahme. */
export function newVadState(now: number): VadState {
  return {
    hasSpoken: false,
    silenceStart: null,
    peak: 0,
    startedAt: now,
    noiseFloor: Number.POSITIVE_INFINITY,
    probing: true,
  };
}

/** Warum die Aufnahme beendet wurde — für ehrliche Logs/Tests, nicht nur ein `true`. */
export type VadStop = 'silence' | 'max-duration';

/** Die aus dem Rauschboden abgeleiteten Schwellen — exportiert, damit Tests sie prüfen können. */
export function vadThresholds(state: VadState): { speech: number; silence: number } {
  const floor = Number.isFinite(state.noiseFloor) ? state.noiseFloor : 0;
  return {
    speech: Math.max(VAD_SPEECH_FLOOR, floor * VAD_SPEECH_OVER_NOISE),
    silence: Math.max(VAD_SILENCE_FLOOR, floor * VAD_SILENCE_OVER_NOISE),
  };
}

/**
 * Ein Messschritt. Gibt zurück, ob JETZT gesendet werden soll (und warum) — der Aufrufer
 * entscheidet, was das heißt. Mutiert [state] bewusst in-place (wird alle ~60 ms
 * aufgerufen; ein neues Objekt je Tick wäre Müll ohne Nutzen).
 *
 * @param level roher RMS 0..1, wie ihn {@link rmsLevel} liefert — NICHT der geglättete
 *              Anzeige-Pegel: Glättung würde die Stille verschleppen.
 * @param now   monotone Zeit in ms (`performance.now()`).
 */
export function vadStep(state: VadState, level: number, now: number): VadStop | null {
  const elapsed = now - state.startedAt;
  if (elapsed >= VAD_MAX_RECORD_MS) return 'max-duration';

  if (level > state.peak) state.peak = level;

  // Messphase: Rauschboden bestimmen, nichts entscheiden. Wer sofort losredet, hebt den
  // Boden — deshalb zusätzlich der Peak-Anteil unten, der laute Sprecher wieder einfängt.
  if (state.probing) {
    if (level < state.noiseFloor) state.noiseFloor = level;
    if (elapsed < VAD_NOISE_PROBE_MS) return null;
    state.probing = false;
    if (!Number.isFinite(state.noiseFloor)) state.noiseFloor = 0;
  }

  const { speech, silence } = vadThresholds(state);

  if (level >= speech) {
    state.hasSpoken = true;
    state.silenceStart = null;
    return null;
  }

  if (!state.hasSpoken) return null; // Pause VOR dem ersten Wort beendet nie etwas.

  // Still ist es unter der Rausch-Schwelle ODER deutlich unter dem eigenen Lautesten —
  // Letzteres fängt den Fall „laut gesprochen, Umgebung ebenfalls laut" ein.
  const quiet = level < silence || level < state.peak * VAD_SILENCE_RATIO;
  if (quiet) {
    if (state.silenceStart == null) {
      state.silenceStart = now;
    } else if (now - state.silenceStart >= VAD_SILENCE_MS) {
      return 'silence';
    }
  }
  // Dazwischen: Hysterese — Zähler weder zurücksetzen noch neu starten (0.5-Verhalten).
  return null;
}
