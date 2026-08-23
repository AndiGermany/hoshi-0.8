import { describe, expect, it } from 'vitest';
import {
  SYNODIC_MONTH_DAYS,
  moonLitPath,
  moonPhase,
  moonPhaseName,
  type MoonPhase,
} from '../components/moonPhase';

/**
 * **Die Mondphase der L-Uhr** (Andi 23.08.: „Bei dem goßen sonnenstand möchte
 * ich in der nacht die mondphase angezeigt haben :)").
 *
 * Eine gerechnete Zahl darf man nicht gegen sich selbst prüfen. Die Riegel hier
 * stehen deshalb gegen **echte Himmelsereignisse**: eine Sonnenfinsternis ist
 * per Definition ein Neumond, eine Mondfinsternis ein Vollmond, und beides ist
 * auf die Minute veröffentlicht. Vier davon fallen 2026 an — zwei
 * Sonnen-, zwei Mondfinsternisse —, und sie sind die Referenzdaten dieses
 * Tests. Wäre die Formel um die 14 Stunden daneben, die eine „Tage seit
 * Referenz-Neumond modulo 29,53"-Näherung kostet, fiele jeder dieser Riegel.
 *
 * Alle Zeiten in UTC — der Mond kennt keine Zeitzone.
 */

const H = 3600000;

/** Die vier Finsternisse 2026, Zeitpunkt der größten Verfinsterung (UTC). */
const NEUMOND_RINGFOERMIGE_SOFI = Date.UTC(2026, 1, 17, 12, 12); // 17.02.2026, Antarktis
const NEUMOND_TOTALE_SOFI = Date.UTC(2026, 7, 12, 17, 46); // 12.08.2026, Island/Spanien
const VOLLMOND_TOTALE_MOFI = Date.UTC(2026, 2, 3, 11, 34); // 03.03.2026, Pazifik/Asien/Amerika
const VOLLMOND_PARTIELLE_MOFI = Date.UTC(2026, 7, 28, 4, 13); // 28.08.2026

const phaseAt = (ms: number): MoonPhase => {
  const p = moonPhase(ms);
  expect(p).not.toBeNull();
  return p!;
};

describe('moonPhase — gegen echte Finsternis-Daten 2026, nicht gegen sich selbst', () => {
  it('bei einer SONNENfinsternis ist Neumond: 0 % beleuchtet', () => {
    for (const [name, ms] of [
      ['ringförmige SoFi 17.02.2026', NEUMOND_RINGFOERMIGE_SOFI],
      ['totale SoFi 12.08.2026', NEUMOND_TOTALE_SOFI],
    ] as const) {
      const p = phaseAt(ms);
      expect(p.illumination, name).toBeLessThan(0.001);
      expect(p.phaseAngleDeg, name).toBeGreaterThan(179); // 180° = exakt Neumond
      expect(p.name, name).toBe('new');
    }
  });

  it('bei einer MONDfinsternis ist Vollmond: 100 % beleuchtet', () => {
    for (const [name, ms] of [
      ['totale MoFi 03.03.2026', VOLLMOND_TOTALE_MOFI],
      ['partielle MoFi 28.08.2026', VOLLMOND_PARTIELLE_MOFI],
    ] as const) {
      const p = phaseAt(ms);
      expect(p.illumination, name).toBeGreaterThan(0.999);
      expect(p.phaseAngleDeg, name).toBeLessThan(1); // 0° = exakt Vollmond
      expect(p.name, name).toBe('full');
    }
  });

  /**
   * Der Fehler der Näherung, gegen die diese Formel antritt, ist RICHTUNGSLOS —
   * sie liegt mal vor, mal hinter dem echten Termin. Deshalb wird hier nicht nur
   * der Treffer geprüft, sondern auch, dass eine Woche später wirklich das
   * erste Viertel steht: 7,4 Tage nach Neumond ist der Mond halb, und das ist
   * eine Aussage, die eine falsche Phasenlage sofort verriete.
   */
  it('eine Woche nach Neumond steht der Halbmond — und er nimmt ZU', () => {
    const p = phaseAt(NEUMOND_TOTALE_SOFI + 7.38 * 24 * H);
    expect(p.illumination).toBeGreaterThan(0.45);
    expect(p.illumination).toBeLessThan(0.55);
    expect(p.waxing).toBe(true);
    expect(p.name).toBe('firstQuarter');
  });

  it('eine Woche nach Vollmond steht derselbe Halbmond — aber ABnehmend', () => {
    const p = phaseAt(VOLLMOND_PARTIELLE_MOFI + 7.38 * 24 * H);
    expect(p.illumination).toBeGreaterThan(0.45);
    expect(p.illumination).toBeLessThan(0.55);
    expect(p.waxing).toBe(false);
    expect(p.name).toBe('lastQuarter');
  });

  it('zwischen Neumond und Vollmond nimmt die Beleuchtung monoton zu', () => {
    let vorher = -1;
    for (let d = 0.5; d <= 14; d += 0.5) {
      const p = phaseAt(NEUMOND_TOTALE_SOFI + d * 24 * H);
      expect(p.illumination, `Tag ${d}`).toBeGreaterThan(vorher);
      expect(p.waxing, `Tag ${d}`).toBe(true);
      vorher = p.illumination;
    }
  });

  it('ein voller Zyklus später steht (fast) dieselbe Phase — der synodische Monat stimmt', () => {
    const a = phaseAt(NEUMOND_TOTALE_SOFI + 5 * 24 * H);
    const b = phaseAt(NEUMOND_TOTALE_SOFI + (5 + SYNODIC_MONTH_DAYS) * 24 * H);
    expect(Math.abs(a.illumination - b.illumination)).toBeLessThan(0.06);
    expect(b.waxing).toBe(true);
  });

  it('ein unbrauchbarer Zeitpunkt ergibt null — lieber kein Bild als ein falsches', () => {
    expect(moonPhase(Number.NaN)).toBeNull();
    expect(moonPhase(Number.POSITIVE_INFINITY)).toBeNull();
  });
});

describe('moonPhaseName — die vier exakten Phasen sind schmale Fenster, keine Achtel', () => {
  it('Neumond gilt an BEIDEN Enden des Zyklus (0 und 1 sind derselbe Punkt)', () => {
    expect(moonPhaseName(0)).toBe('new');
    expect(moonPhaseName(0.02)).toBe('new');
    expect(moonPhaseName(0.98)).toBe('new');
    expect(moonPhaseName(1)).toBe('new');
  });

  it('die vier Viertelpunkte treffen ihren Namen', () => {
    expect(moonPhaseName(0.25)).toBe('firstQuarter');
    expect(moonPhaseName(0.5)).toBe('full');
    expect(moonPhaseName(0.75)).toBe('lastQuarter');
  });

  /**
   * Der Grund für die schmalen Fenster: ein Achtel des Zyklus wären 3,7 Tage
   * „Vollmond" am Stück — an Tag drei sähe man die Scheibe sichtbar angeknabbert
   * und läse trotzdem „Vollmond". Das Wort widerspräche dem Bild darüber.
   */
  it('zwei Tage nach Vollmond heißt es NICHT mehr Vollmond', () => {
    const zweiTage = 2 / SYNODIC_MONTH_DAYS;
    expect(moonPhaseName(0.5 + zweiTage)).toBe('waningGibbous');
    expect(moonPhaseName(0.5 - zweiTage)).toBe('waxingGibbous');
  });

  it('die Sichel-Namen liegen zwischen den Viertelpunkten, richtig herum', () => {
    expect(moonPhaseName(0.12)).toBe('waxingCrescent');
    expect(moonPhaseName(0.38)).toBe('waxingGibbous');
    expect(moonPhaseName(0.62)).toBe('waningGibbous');
    expect(moonPhaseName(0.88)).toBe('waningCrescent');
  });
});

describe('moonLitPath — die Form sagt dasselbe wie die Zahl', () => {
  const pfad = (illumination: number, waxing: boolean) =>
    moonLitPath(
      { illumination, waxing, phaseAngleDeg: 0, cyclePosition: 0, ageDays: 0, name: 'full' },
      100,
      50,
      40,
    );

  it('Neumond zeichnet NICHTS — ein Strich wäre kein Mond', () => {
    expect(pfad(0, true)).toBe('');
    expect(pfad(0.004, false)).toBe('');
  });

  it('Vollmond ist ein voller Kreis: beide Bögen haben denselben Radius', () => {
    // rx des Terminators = r · |2k − 1| = 40 bei k = 1.
    expect(pfad(1, true)).toContain('A 40,40');
  });

  it('der exakte Halbmond hat einen GERADEN Terminator (rx = 0)', () => {
    expect(pfad(0.5, true)).toContain('A 0,40');
    expect(pfad(0.5, false)).toContain('A 0,40');
  });

  /**
   * Nordhalbkugel-Probe, und zwar an dem einen Flag, das sie entscheidet: der
   * beleuchtete Rand liegt beim zunehmenden Mond RECHTS (SVG-`sweep` 1 = im
   * Uhrzeigersinn von oben nach unten), beim abnehmenden LINKS. Wer das
   * vertauscht, malt einen Mond, den es auf dieser Erdhälfte nicht gibt.
   */
  it('zunehmend leuchtet rechts, abnehmend links', () => {
    const zu = pfad(0.3, true);
    const ab = pfad(0.3, false);
    expect(zu.startsWith('M 100,10 A 40,40 0 0 1 100,90')).toBe(true);
    expect(ab.startsWith('M 100,10 A 40,40 0 0 0 100,90')).toBe(true);
  });

  /**
   * Sichel gegen „mehr als halb": bei k < 0,5 wölbt sich der Terminator zur
   * hellen Seite (Sichel), bei k > 0,5 in die dunkle. Das steckt allein im
   * zweiten `sweep`-Flag — und genau daran erkennt ein Mensch die Phase.
   */
  it('Sichel und Gibbous unterscheiden sich im Terminator-Flag', () => {
    const sichelZu = pfad(0.3, true).split('A')[2].trim();
    const gibbousZu = pfad(0.7, true).split('A')[2].trim();
    expect(sichelZu.endsWith('0 100,10 Z')).toBe(true);
    expect(gibbousZu.endsWith('1 100,10 Z')).toBe(true);

    const sichelAb = pfad(0.3, false).split('A')[2].trim();
    const gibbousAb = pfad(0.7, false).split('A')[2].trim();
    expect(sichelAb.endsWith('1 100,10 Z')).toBe(true);
    expect(gibbousAb.endsWith('0 100,10 Z')).toBe(true);
  });
});
