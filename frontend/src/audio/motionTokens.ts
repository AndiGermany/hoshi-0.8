/**
 * **Bewegungs-Token der Hoshi-Welle** — die Kurven/Timings aus der Cowork-Spec
 * §3 (2026-07-02), KORRIGIERT nach Andis Live-Feedback (2026-07-06, Cowork-
 * Korrektur 20260706-1729), als GETEILTES Token-Set: das FE (VoiceWaveform,
 * Canvas) nutzt sie heute, die Satelliten-LED-Zeile (Voice-PE) soll später
 * DIESELBEN Kurven/Konstanten fahren.
 *
 * Gesetz (verschärft): **Nichts leuchtet, was nichts misst.**
 *  - Die Welle EXISTIERT nur, wenn Audio fließt: Mikro offen → Nutzer-Pegel ·
 *    Hoshi spricht → echter TTS-Ausgabepegel · kein Audio → KEINE Welle.
 *  - Es gibt KEIN synthetisches Idle-Atmen mehr — der frühere idle-Zustand
 *    (Ziel 0,16, Faktor ×1,6, Zeitschritt 0,045 als Dauerlauf ohne Audio)
 *    ist ersatzlos ENTFALLEN. Die Übersicht zeigt ruhiges Papier.
 *  - Das Erscheinen der Welle IST das Signal „jetzt höre ich".
 *
 * Prinzipien (Form unverändert aus der Spec):
 *  - Grundform = 3 überlagerte Sinusse; beim Sprechen kommt ein vierter,
 *    schnellerer dazu ({@link SPEAK_SINE}).
 *  - Halbwellen-Envelope sin(u·π): die Enden laufen flach aus (kein „Balken").
 *  - Zustands-Übergang per Lerp: level += (ziel − level)·0,06 pro Frame.
 *
 * Pegel-Kette (Treiber, alle Konstanten HIER — kein Duplikat im Renderer):
 *  1. {@link gammaLevel}: roh-RMS (0..1) → rms^{@link LEVEL_GAMMA}
 *     (Wahrnehmungs-Gamma 0,6 — leise, häufige Werte werden sichtbar).
 *  2. {@link openLevelTarget}: sobald ein Kanal OFFEN ist, gilt der Floor
 *     {@link LEVEL_FLOOR_OPEN} (0,35) — auch in Sprechpausen: der Kanal IST
 *     offen, Stille bleibt sichtbar, aber ruhiger als Sprache.
 *  3. {@link waveOffsetFraction}: Form × Faktor × Pegel, normiert auf
 *     {@link WAVE_AMP_MAX}, geklemmt auf ±1 (= halbe Bandhöhe).
 *
 * Kalibrierung (der Vertragstest pinnt sie BEHAVIORAL, nicht nur als Zahl):
 *  normale Sprechlautstärke (rms_norm ≈ 0,3 mit Browser-AGC → gammaLevel
 *  ≈ 0,49) soll ~{@link SPEECH_FILL_TARGET} (70 %) der Canvas-Höhe füllen.
 *  Daraus sind {@link BASE_AMP_FACTOR}/{@link SPEAK_AMP_FACTOR} abgeleitet
 *  (angehoben von den alten ×1,6/×2,2 — Andi: „Ausschlag zu gering", <15 %).
 *  Die ECHTE Mikro-Amplitude kalibriert am Ende Andis Auge/Ohr am Live-Gerät.
 *
 * Reine Konstanten + pure Funktionen (kein DOM, kein Canvas) → headless
 * testbar; der Vertragstest pinnt die Werte (test/motiontokens.test.ts).
 */

/** Ein Sinus-Bestandteil der Welle: sin(u·freq + t·speed) · amp. */
export interface WaveSine {
  /** Räumliche Frequenz über die Breite (u = 0..1). */
  freq: number;
  /** Zeit-Multiplikator (negativ = läuft nach links). */
  speed: number;
  /** Amplitude (relative Einheit; der Renderer normiert auf seine Höhe). */
  amp: number;
}

/** Grundform: die 3 überlagerten Sinusse (Spec §3, exakt — Form bleibt). */
export const WAVE_SINES: readonly WaveSine[] = [
  { freq: 11, speed: 2.1, amp: 10 },
  { freq: 23, speed: -3.3, amp: 6 },
  { freq: 5, speed: 1.2, amp: 8 },
] as const;

/** Der zusätzliche Speak-Sinus: sin(u·47 + t·7)·5 (Spec §3, exakt). */
export const SPEAK_SINE: WaveSine = { freq: 47, speed: 7, amp: 5 } as const;

/** Lerp-Faktor pro Frame: level += (ziel − level) · 0,06. */
export const LEVEL_LERP_PER_FRAME = 0.06;

/**
 * Wahrnehmungs-Gamma des Pegel-Mappings: level = rms_norm^0,6.
 * (Korrektur 20260706-1729 — ersetzt das alte pow(raw, 0.4)·1,2 aus level.ts;
 * eine Quelle für FE-Welle UND später die Satelliten-LED.)
 */
export const LEVEL_GAMMA = 0.6;

/**
 * Pegel-Floor, sobald ein Audio-Kanal OFFEN ist (Mikro hört / TTS läuft):
 * auch in Sprechpausen bleibt die Welle sichtbar auf 0,35 — der Kanal IST
 * offen. Ohne offenen Kanal existiert die Welle gar nicht (kein Idle-Ziel!).
 */
export const LEVEL_FLOOR_OPEN = 0.35;

/** Voller Pegel (Sprech-Vollausschlag) — obere Referenz des Mappings. */
export const SPEAK_LEVEL_TARGET = 1.0;

/**
 * Kalibrier-Ziel: normale Sprechlautstärke füllt ~70 % der Canvas-Höhe.
 * Kein Renderer-Multiplikator, sondern der VERTRAG, aus dem die Amplituden-
 * Faktoren abgeleitet sind — der Vertragstest rechnet ihn nach.
 */
export const SPEECH_FILL_TARGET = 0.7;

/**
 * Amplituden-Faktor bei offenem, stillem Kanal (früher „idle ×1,6" — angehoben,
 * damit schon der Floor-Sockel sichtbar ist statt einer Quasi-Null-Linie).
 */
export const BASE_AMP_FACTOR = 2.2;

/**
 * Amplituden-Faktor bei vollem Sprech-Pegel (früher ×2,2 — angehoben, damit
 * normale Sprache das {@link SPEECH_FILL_TARGET} erreicht; Herleitung im
 * Kalibrier-Vertragstest).
 */
export const SPEAK_AMP_FACTOR = 3.4;

/** Zeitschritt pro Frame bei stillem offenem Kanal (t += 0,045 — ruhiger Lauf). */
export const BASE_TIME_STEP = 0.045;
/** Zeitschritt pro Frame bei vollem Sprech-Pegel (t += 0,11 — lebendiger Lauf). */
export const SPEAK_TIME_STEP = 0.11;

/** Linienstärke der Welle in CSS-px (~1,5px, lineCap round, accent, kein Glow). */
export const WAVE_LINE_WIDTH_PX = 1.5;

/**
 * Materialisieren der Welle beim Kanal-Öffnen: Amplitude wächst ~200 ms weich
 * von 0 auf voll (Canvas-Skalierung, KEINE opacity — Chromes Occlusion-Lehre).
 * `prefers-reduced-motion` ⇒ sofort da, kein Fade ({@link materializePresence}).
 */
export const MATERIALIZE_MS = 200;

/**
 * Maximale Amplituden-Summe der Form (3 Sinusse + Speak-Sinus) — die
 * Normierungs-Basis, mit der ein Renderer die relative Einheit auf seine
 * Pixel-Höhe skaliert.
 */
export const WAVE_AMP_MAX =
  WAVE_SINES.reduce((sum, w) => sum + w.amp, 0) + SPEAK_SINE.amp;

/** Halbwellen-Envelope sin(u·π): 0 an den Enden, 1 in der Mitte. */
export function envelope(u: number): number {
  return Math.sin(u * Math.PI);
}

/** Ein Lerp-Schritt des Pegels Richtung Ziel (Spec: ·0,06 pro Frame). */
export function lerpLevel(level: number, target: number): number {
  return level + (target - level) * LEVEL_LERP_PER_FRAME;
}

/**
 * Wahrnehmungs-Gamma-Mapping: roh-RMS (0..1) → Anzeige-Pegel rms^0,6,
 * geklemmt auf 0..1. NaN/negative Eingaben → 0 (nie NaN in den Renderer).
 */
export function gammaLevel(rmsNorm: number): number {
  if (!(rmsNorm > 0)) return 0;
  return Math.min(Math.pow(rmsNorm, LEVEL_GAMMA), 1);
}

/**
 * Ziel-Pegel bei OFFENEM Kanal: der echte (gamma-gemappte) Pegel, aber nie
 * unter {@link LEVEL_FLOOR_OPEN} — Stille an offenem Mikro bleibt ein
 * sichtbarer Sockel. Gilt NUR solange der Kanal offen ist; ohne Kanal wird
 * die Welle gar nicht gerendert.
 */
export function openLevelTarget(level: number): number {
  const l = Number.isFinite(level) ? Math.min(SPEAK_LEVEL_TARGET, Math.max(0, level)) : 0;
  return Math.max(LEVEL_FLOOR_OPEN, l);
}

/**
 * Sprech-Anteil 0..1 aus dem Pegel: 0 am Floor (offener, stiller Kanal),
 * 1 bei Vollausschlag. Blendet Speak-Sinus, Amplituden-Faktor und
 * Zeitschritt zwischen Basis- und Sprech-Charakter.
 */
export function speakMix(level: number): number {
  return Math.max(
    0,
    Math.min(1, (level - LEVEL_FLOOR_OPEN) / (SPEAK_LEVEL_TARGET - LEVEL_FLOOR_OPEN)),
  );
}

/** Amplituden-Faktor beim gegebenen Pegel: Basis → Speak entlang {@link speakMix}. */
export function waveAmpFactor(level: number): number {
  return BASE_AMP_FACTOR + (SPEAK_AMP_FACTOR - BASE_AMP_FACTOR) * speakMix(level);
}

/** Zeitschritt beim gegebenen Pegel: ruhig → lebendig entlang {@link speakMix}. */
export function waveTimeStep(level: number): number {
  return BASE_TIME_STEP + (SPEAK_TIME_STEP - BASE_TIME_STEP) * speakMix(level);
}

/**
 * Anteil der HALBEN Bandhöhe (−1..1), den ein Wellen-Sample einnimmt:
 * sample · {@link waveAmpFactor} · level, normiert auf {@link WAVE_AMP_MAX},
 * hart geklemmt auf ±1 (lautes Sprechen darf die Spitzen kappen — WhatsApp-
 * Meter-Verhalten — statt aus dem Band zu laufen). Der Renderer multipliziert
 * mit seiner halben Pixel-Höhe.
 */
export function waveOffsetFraction(sample: number, level: number): number {
  const f = (sample * waveAmpFactor(level) * level) / WAVE_AMP_MAX;
  return Math.max(-1, Math.min(1, f));
}

/**
 * Materialisierungs-Faktor 0..1 der Welle nach dem Kanal-Öffnen: linear über
 * {@link MATERIALIZE_MS}. `reducedMotion` ⇒ sofort 1 (kein Fade, keine
 * Wachs-Animation — die Welle ist einfach da).
 */
export function materializePresence(elapsedMs: number, reducedMotion: boolean): number {
  if (reducedMotion) return 1;
  if (!(elapsedMs > 0)) return 0;
  return Math.min(1, elapsedMs / MATERIALIZE_MS);
}

/**
 * Wellen-Sample an Position u (0..1) zur Zeit t, inkl. Envelope.
 * `speakMix` (0..1) blendet den Speak-Sinus ein — 0 = reine Grundform,
 * 1 = volle Sprech-Form. Ergebnis in relativen Amplituden-Einheiten
 * (±{@link WAVE_AMP_MAX} theoretisches Maximum).
 */
export function waveSample(u: number, t: number, speakMix = 0): number {
  let y = 0;
  for (const w of WAVE_SINES) y += Math.sin(u * w.freq + t * w.speed) * w.amp;
  if (speakMix > 0) {
    y += Math.sin(u * SPEAK_SINE.freq + t * SPEAK_SINE.speed) * SPEAK_SINE.amp * speakMix;
  }
  return y * envelope(u);
}
