import { describe, it, expect } from 'vitest';
import * as tokens from '../audio/motionTokens';
import {
  WAVE_SINES,
  SPEAK_SINE,
  LEVEL_LERP_PER_FRAME,
  LEVEL_GAMMA,
  LEVEL_FLOOR_OPEN,
  SPEAK_LEVEL_TARGET,
  SPEECH_FILL_TARGET,
  BASE_AMP_FACTOR,
  SPEAK_AMP_FACTOR,
  BASE_TIME_STEP,
  SPEAK_TIME_STEP,
  WAVE_LINE_WIDTH_PX,
  MATERIALIZE_MS,
  WAVE_AMP_MAX,
  envelope,
  gammaLevel,
  lerpLevel,
  materializePresence,
  openLevelTarget,
  speakMix,
  waveAmpFactor,
  waveOffsetFraction,
  waveSample,
  waveTimeStep,
} from '../audio/motionTokens';

/**
 * Vertragstest des geteilten Bewegungs-Token-Sets — Spec §3 (2026-07-02),
 * KORRIGIERT nach Andis Live-Feedback (Cowork-Korrektur 20260706-1729):
 * die Welle existiert nur bei fließendem Audio, der Idle-Zustand ist
 * ersatzlos entfallen, Gamma/Floor/FillTarget sind der neue Vertrag —
 * später auch mit der Satelliten-LED-Zeile. Wer hier etwas ändert, ändert
 * die abgenommene Animation — bewusst gepinnt, Verhalten inklusive.
 */
describe('motionTokens — Form der Welle (Spec §3, unverändert)', () => {
  it('Grundform: 3 überlagerte Sinusse — sin(u·11+t·2,1)·10 + sin(u·23−t·3,3)·6 + sin(u·5+t·1,2)·8', () => {
    expect(WAVE_SINES).toEqual([
      { freq: 11, speed: 2.1, amp: 10 },
      { freq: 23, speed: -3.3, amp: 6 },
      { freq: 5, speed: 1.2, amp: 8 },
    ]);
  });

  it('Speak-Sinus: sin(u·47 + t·7)·5', () => {
    expect(SPEAK_SINE).toEqual({ freq: 47, speed: 7, amp: 5 });
  });

  it('Halbwellen-Envelope sin(u·π): Enden flach (0), Mitte voll (1)', () => {
    expect(envelope(0)).toBeCloseTo(0, 12);
    expect(envelope(0.5)).toBeCloseTo(1, 12);
    expect(envelope(1)).toBeCloseTo(0, 12);
  });

  it('waveSample: Enden laufen aus, Speak-Mix blendet exakt den Speak-Sinus ein', () => {
    expect(waveSample(0, 1.23)).toBeCloseTo(0, 12);
    expect(waveSample(1, 4.56)).toBeCloseTo(0, 12);
    const u = 0.31;
    const t = 2.7;
    const expected = Math.sin(u * 47 + t * 7) * 5 * envelope(u);
    expect(waveSample(u, t, 1) - waveSample(u, t, 0)).toBeCloseTo(expected, 12);
    expect(WAVE_AMP_MAX).toBe(29); // Normierungs-Basis: 10+6+8+5
  });

  it('Linie ~1,5px (lineCap round, accent, kein Glow — Optik pinnt der Renderer)', () => {
    expect(WAVE_LINE_WIDTH_PX).toBe(1.5);
  });
});

describe('motionTokens — der Idle-Zustand ist ERSATZLOS entfallen (Korrektur 20260706-1729)', () => {
  it('exportiert KEINE IDLE_*-Token mehr — kein synthetisches Atmen, nirgends', () => {
    const idleKeys = Object.keys(tokens).filter((k) => /IDLE/i.test(k));
    expect(idleKeys).toEqual([]);
  });

  it('Lerp bleibt: level += (ziel − level)·0,06 pro Frame', () => {
    expect(LEVEL_LERP_PER_FRAME).toBe(0.06);
    expect(lerpLevel(0.35, 1.0)).toBeCloseTo(0.35 + (1.0 - 0.35) * 0.06, 12);
  });

  it('Zeitschritte: still 0,045 → Sprechen 0,11, entlang des Pegels', () => {
    expect(BASE_TIME_STEP).toBe(0.045);
    expect(SPEAK_TIME_STEP).toBe(0.11);
    expect(waveTimeStep(LEVEL_FLOOR_OPEN)).toBeCloseTo(BASE_TIME_STEP, 12); // still = ruhig
    expect(waveTimeStep(1)).toBeCloseTo(SPEAK_TIME_STEP, 12); // voll = lebendig
    expect(waveTimeStep(0.6)).toBeGreaterThan(BASE_TIME_STEP);
    expect(waveTimeStep(0.6)).toBeLessThan(SPEAK_TIME_STEP);
  });
});

describe('motionTokens — Pegel-Vertrag: Gamma 0,6 · Floor 0,35 · FillTarget 0,7', () => {
  it('pinnt die Korrektur-Konstanten exakt', () => {
    expect(LEVEL_GAMMA).toBe(0.6);
    expect(LEVEL_FLOOR_OPEN).toBe(0.35);
    expect(SPEECH_FILL_TARGET).toBe(0.7);
    expect(SPEAK_LEVEL_TARGET).toBe(1.0);
    expect(MATERIALIZE_MS).toBe(200);
  });

  it('gammaLevel: level = rms_norm^0,6 — leise Werte sichtbar, 0/NaN sicher', () => {
    expect(gammaLevel(0.3)).toBeCloseTo(Math.pow(0.3, LEVEL_GAMMA), 12); // konsumiert das Token
    expect(gammaLevel(0)).toBe(0);
    expect(gammaLevel(1)).toBe(1);
    expect(gammaLevel(Number.NaN)).toBe(0);
    expect(gammaLevel(-0.5)).toBe(0);
    // monoton steigend, im Bereich 0..1
    let prev = -1;
    for (const r of [0, 0.05, 0.2, 0.4, 0.6, 0.8, 1]) {
      const v = gammaLevel(r);
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThanOrEqual(1);
      expect(v).toBeGreaterThanOrEqual(prev);
      prev = v;
    }
    // Anhebung: rms 0,1 → 0,25 (statt „tot" bei 0,1)
    expect(gammaLevel(0.1)).toBeGreaterThan(0.1);
  });

  it('openLevelTarget: offener Kanal fällt NIE unter den Floor — Stille bleibt ein Sockel', () => {
    expect(openLevelTarget(0)).toBe(LEVEL_FLOOR_OPEN); // Sprechpause ⇒ Floor, keine Null-Linie
    expect(openLevelTarget(0.1)).toBe(LEVEL_FLOOR_OPEN); // leise unter dem Floor ⇒ Floor
    expect(openLevelTarget(0.62)).toBe(0.62); // echter Pegel gewinnt über dem Floor
    expect(openLevelTarget(7)).toBe(1); // geklemmt auf Vollausschlag
    expect(openLevelTarget(Number.NaN)).toBe(LEVEL_FLOOR_OPEN); // nie NaN in den Renderer
  });

  it('speakMix: 0 am Floor (still) · 1 bei Vollausschlag · geklemmt', () => {
    expect(speakMix(LEVEL_FLOOR_OPEN)).toBe(0);
    expect(speakMix(1)).toBe(1);
    expect(speakMix(0)).toBe(0); // unterm Floor nie negativ
    expect(speakMix(0.675)).toBeCloseTo(0.5, 12); // Mitte zwischen 0,35 und 1,0
  });

  it('waveAmpFactor: angehobene Faktoren (Basis 2,2 → Speak 3,4), Pegel-gesteuert', () => {
    expect(BASE_AMP_FACTOR).toBe(2.2);
    expect(SPEAK_AMP_FACTOR).toBe(3.4);
    expect(BASE_AMP_FACTOR).toBeGreaterThan(1.6); // Andi: Ausschlag war zu gering
    expect(SPEAK_AMP_FACTOR).toBeGreaterThan(2.2);
    expect(waveAmpFactor(LEVEL_FLOOR_OPEN)).toBeCloseTo(BASE_AMP_FACTOR, 12);
    expect(waveAmpFactor(1)).toBeCloseTo(SPEAK_AMP_FACTOR, 12);
  });

  it('waveOffsetFraction: konsumiert Faktor+Normierung und klemmt hart auf ±1 (nie aus dem Band)', () => {
    const level = 0.5;
    const expected = (10 * waveAmpFactor(level) * level) / WAVE_AMP_MAX;
    expect(waveOffsetFraction(10, level)).toBeCloseTo(expected, 12);
    expect(waveOffsetFraction(WAVE_AMP_MAX, 1)).toBe(1); // 29·3,4·1/29 = 3,4 → geklemmt
    expect(waveOffsetFraction(-WAVE_AMP_MAX, 1)).toBe(-1);
  });
});

/**
 * KALIBRIER-VERTRAG (Verhalten, nicht nur Zahlen): normale Sprechlautstärke
 * füllt ~70 % der Canvas-Höhe; Stille an offenem Mikro bleibt sichtbar, aber
 * deutlich ruhiger. Der typische Frame-Peak der Form wird hier NUMERISCH aus
 * waveSample selbst ermittelt (Median über viele Frames) — ändert jemand
 * Sinusse, Faktoren, Gamma oder Floor inkonsistent, bricht dieser Test.
 */
describe('motionTokens — Kalibrier-Vertrag: Sprache füllt ~70 %, Stille ruhiger', () => {
  /** Median des Frame-Peaks |waveSample| über u∈[0,1] bei gegebenem Mix. */
  function medianFramePeak(mix: number): number {
    const peaks: number[] = [];
    for (let ti = 0; ti < 500; ti++) {
      const t = ti * 0.137; // inkommensurabel zu den Sinus-Speeds → fairer Querschnitt
      let p = 0;
      for (let ui = 0; ui <= 100; ui++) {
        p = Math.max(p, Math.abs(waveSample(ui / 100, t, mix)));
      }
      peaks.push(p);
    }
    peaks.sort((a, b) => a - b);
    return peaks[Math.floor(peaks.length / 2)];
  }

  /** Anteil der GANZEN Canvas-Höhe, den ein typischer Frame beim Pegel füllt. */
  function typicalFill(level: number): number {
    return waveOffsetFraction(medianFramePeak(speakMix(level)), level);
  }

  it('normale Sprechlautstärke (rms≈0,3 mit Browser-AGC) füllt ~SPEECH_FILL_TARGET', () => {
    const speechLevel = openLevelTarget(gammaLevel(0.3)); // ≈ 0,49
    expect(typicalFill(speechLevel)).toBeCloseTo(SPEECH_FILL_TARGET, 1); // ±0,05
  });

  it('Stille an offenem Mikro: sichtbarer Sockel, aber deutlich ruhiger als Sprache', () => {
    const silenceFill = typicalFill(LEVEL_FLOOR_OPEN);
    const speechFill = typicalFill(openLevelTarget(gammaLevel(0.3)));
    expect(silenceFill).toBeGreaterThan(0.25); // sichtbar — der Kanal IST offen
    expect(silenceFill).toBeLessThan(speechFill - 0.15); // aber klar unter Sprache
  });
});

describe('motionTokens — Materialisieren ~200 ms · reduced-motion sofort (kein Fade)', () => {
  it('wächst linear über MATERIALIZE_MS von 0 auf 1', () => {
    expect(materializePresence(0, false)).toBe(0);
    expect(materializePresence(100, false)).toBeCloseTo(0.5, 12);
    expect(materializePresence(MATERIALIZE_MS, false)).toBe(1);
    expect(materializePresence(9999, false)).toBe(1);
  });

  it('prefers-reduced-motion ⇒ sofort voll da — kein Fade, keine Wachs-Animation', () => {
    expect(materializePresence(0, true)).toBe(1);
    expect(materializePresence(50, true)).toBe(1);
  });
});
