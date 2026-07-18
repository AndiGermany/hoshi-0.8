import { describe, it, expect } from 'vitest';
import { emaLevel } from '../audio/level';

// Reine Pegel-Glättung für die lebende Welle. Die perzeptuelle ANHEBUNG
// (Wahrnehmungs-Gamma rms^0,6) lebt seit der Cowork-Korrektur 20260706-1729
// in audio/motionTokens.ts (gammaLevel) und wird dort im Vertragstest
// gepinnt (test/motiontokens.test.ts) — hier nur noch die EMA.

describe('emaLevel — asymmetrische Glättung (schneller Anstieg, ruhiges Abklingen)', () => {
  it('steigt schneller als es fällt (attack > release)', () => {
    const rise = emaLevel(0, 1); // 0 → 1: schneller Anstieg
    const fall = 1 - emaLevel(1, 0); // 1 → 0: langsames Abklingen (Distanz)
    expect(rise).toBeCloseTo(0.5, 5); // default attack 0.5
    expect(1 - fall).toBeCloseTo(0.88, 5); // default release 0.12 → bleibt bei 0.88
    expect(rise).toBeGreaterThan(fall); // Anstieg bewegt mehr als Abklingen
  });

  it('konvergiert bei wiederholter Anwendung gegen das Ziel', () => {
    let v = 0;
    for (let i = 0; i < 50; i++) v = emaLevel(v, 1);
    expect(v).toBeCloseTo(1, 3);
    for (let i = 0; i < 200; i++) v = emaLevel(v, 0);
    expect(v).toBeCloseTo(0, 3);
  });

  it('lässt einen ruhenden Pegel auf dem Ziel unverändert', () => {
    expect(emaLevel(0.4, 0.4)).toBeCloseTo(0.4, 10);
  });
});
