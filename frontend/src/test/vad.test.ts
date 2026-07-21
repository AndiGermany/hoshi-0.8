import { describe, expect, it } from 'vitest';
import {
  newVadState,
  vadStep,
  vadThresholds,
  VAD_MAX_RECORD_MS,
  VAD_NOISE_PROBE_MS,
  VAD_SILENCE_MS,
  type VadState,
} from '../audio/vad';

/**
 * Sprachaktivitäts-Erkennung („es kommt kein Ton mehr") — Andi-Befund 21.07:
 * der Satellit beendet selbst, der Browser konnte es nicht („0.5 konnte das").
 *
 * Getestet wird die REINE Zustandsmaschine ohne DOM/Mikrofon — wie level.ts
 * bewusst testbar gehalten ist. Zeit wird injiziert, keine echten Wartezeiten.
 */

/** Fährt die Messphase mit einem Umgebungspegel durch; danach stehen die Schwellen. */
function probe(state: VadState, ambient: number): void {
  for (let t = 0; t <= VAD_NOISE_PROBE_MS; t += 60) vadStep(state, ambient, t);
}

describe('vadStep', () => {
  it('entscheidet während der Messphase gar nichts', () => {
    const s = newVadState(0);
    for (let t = 0; t < VAD_NOISE_PROBE_MS; t += 60) {
      expect(vadStep(s, 0.5, t)).toBeNull(); // selbst lautes Sprechen stoppt hier nichts
    }
    expect(s.probing).toBe(true);
  });

  it('Pause VOR dem ersten Wort beendet nie eine Aufnahme', () => {
    const s = newVadState(0);
    probe(s, 0.002);
    for (let t = VAD_NOISE_PROBE_MS; t <= 20_000; t += 60) {
      expect(vadStep(s, 0.002, t)).toBeNull();
    }
    expect(s.hasSpoken).toBe(false);
  });

  it('sendet nach durchgehender Stille im Anschluss ans Sprechen', () => {
    const s = newVadState(0);
    probe(s, 0.002);
    const t0 = VAD_NOISE_PROBE_MS + 60;
    vadStep(s, 0.4, t0);
    expect(s.hasSpoken).toBe(true);

    expect(vadStep(s, 0.002, t0 + 60)).toBeNull();
    expect(vadStep(s, 0.002, t0 + 60 + VAD_SILENCE_MS - 1)).toBeNull();
    expect(vadStep(s, 0.002, t0 + 60 + VAD_SILENCE_MS)).toBe('silence');
  });

  it('erneutes Sprechen setzt den Stille-Zähler zurück', () => {
    const s = newVadState(0);
    probe(s, 0.002);
    const t0 = VAD_NOISE_PROBE_MS + 60;
    vadStep(s, 0.4, t0);
    vadStep(s, 0.002, t0 + 60); // Stille beginnt
    vadStep(s, 0.4, t0 + 1000); // Denkpause vorbei, es geht weiter
    expect(s.silenceStart).toBeNull();

    expect(vadStep(s, 0.002, t0 + 1100)).toBeNull();
    expect(vadStep(s, 0.002, t0 + 1100 + VAD_SILENCE_MS)).toBe('silence');
  });

  it('BLUETOOTH-REGRESSION: leise Mikrofone lösen trotzdem aus', () => {
    // Andi, 21.07. abends: „ich bin mit bluetooth verbunden und hoshi stoppt die
    // aufnahme nicht mehr." Ursache war eine ABSOLUTE Sprech-Schwelle (0.06), die
    // ein Bluetooth-Mikro nie erreicht ⇒ die Erkennung wurde nie scharf.
    // Hier: Umgebung 0.002, Sprache nur 0.03 — weit unter der alten Schwelle.
    const s = newVadState(0);
    probe(s, 0.002);
    const t0 = VAD_NOISE_PROBE_MS + 60;

    const { speech } = vadThresholds(s);
    expect(speech).toBeLessThan(0.03); // die Schwelle MUSS unter dem leisen Sprechen liegen

    vadStep(s, 0.03, t0);
    expect(s.hasSpoken).toBe(true); // genau das schlug vorher fehl
    expect(vadStep(s, 0.002, t0 + 60)).toBeNull();
    expect(vadStep(s, 0.002, t0 + 60 + VAD_SILENCE_MS)).toBe('silence');
  });

  it('lauter Raum: die Schwellen wachsen mit dem gemessenen Grundpegel', () => {
    const leise = newVadState(0);
    probe(leise, 0.002);
    const laut = newVadState(0);
    probe(laut, 0.05); // Lüfter/Straße

    expect(vadThresholds(laut).speech).toBeGreaterThan(vadThresholds(leise).speech);
    expect(vadThresholds(laut).silence).toBeGreaterThan(vadThresholds(leise).silence);

    // Im lauten Raum gilt der Grundpegel selbst NICHT als Sprache.
    vadStep(laut, 0.05, VAD_NOISE_PROBE_MS + 60);
    expect(laut.hasSpoken).toBe(false);
  });

  it('totes Mikrofon: absoluter Boden verhindert, dass Rauschen als Sprache zählt', () => {
    const s = newVadState(0);
    probe(s, 0); // gar kein Signal
    // Ohne absoluten Boden wäre jede Schwelle 0 und ein Knacken wäre „Sprache".
    expect(vadThresholds(s).speech).toBeGreaterThan(0);
    vadStep(s, 0.001, VAD_NOISE_PROBE_MS + 60);
    expect(s.hasSpoken).toBe(false);
  });

  it('Notbremse greift, wenn ein Dauergeräusch die Stille-Schwelle nie unterschreitet', () => {
    const s = newVadState(0);
    probe(s, 0.002);
    expect(vadStep(s, 0.4, VAD_MAX_RECORD_MS - 1)).toBeNull();
    expect(vadStep(s, 0.4, VAD_MAX_RECORD_MS)).toBe('max-duration');
  });
});
