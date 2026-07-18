import { describe, it, expect } from 'vitest';
import { greetingForHour } from '../components/greeting';

// Zeit-bewusster Empty-State-Gruß. Rein (Stunde → Gruß) → ohne Uhr testbar.
describe('greetingForHour — Tageszeit-Gruß', () => {
  it('bildet die Tagesabschnitte korrekt ab', () => {
    expect(greetingForHour(0)).toBe('Gute Nacht');
    expect(greetingForHour(4)).toBe('Gute Nacht');
    expect(greetingForHour(5)).toBe('Guten Morgen');
    expect(greetingForHour(10)).toBe('Guten Morgen');
    expect(greetingForHour(11)).toBe('Guten Tag');
    expect(greetingForHour(17)).toBe('Guten Tag');
    expect(greetingForHour(18)).toBe('Guten Abend');
    expect(greetingForHour(21)).toBe('Guten Abend');
    expect(greetingForHour(22)).toBe('Gute Nacht');
    expect(greetingForHour(23)).toBe('Gute Nacht');
  });

  it('toleriert Werte außerhalb 0..23 (Modulo) und Nachkommastellen', () => {
    expect(greetingForHour(24)).toBe(greetingForHour(0));
    expect(greetingForHour(-1)).toBe(greetingForHour(23));
    expect(greetingForHour(19.9)).toBe('Guten Abend'); // floor → 19
  });

  it('liefert immer einen nicht-leeren Gruß', () => {
    for (let h = 0; h < 24; h++) {
      expect(greetingForHour(h).length).toBeGreaterThan(0);
    }
  });
});
