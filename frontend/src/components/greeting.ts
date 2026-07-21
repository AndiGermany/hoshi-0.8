// Zeit-bewusster Gruß für den Empty-State (Yoru, awake — Slice 3). Rein &
// deterministisch (Stunde rein, Gruß raus) → ohne Uhr/DOM unit-testbar.

import type { DayPart } from '../i18n/types';

/**
 * Deutscher Tageszeit-Gruß zur gegebenen Stunde (0..23, toleriert auch Werte
 * außerhalb durch Modulo). Grenzen bewusst schlicht:
 *   00–04 Gute Nacht · 05–10 Guten Morgen · 11–17 Guten Tag ·
 *   18–21 Guten Abend · 22–23 Gute Nacht.
 */
/** Sprachneutraler Tagesabschnitt, damit der UI-Katalog den Gruß übersetzt. */
export function dayPartForHour(hour: number): DayPart {
  const h = ((Math.floor(hour) % 24) + 24) % 24;
  if (h < 5) return 'night';
  if (h < 11) return 'morning';
  if (h < 18) return 'day';
  if (h < 22) return 'evening';
  return 'night';
}

export function greetingForHour(hour: number): string {
  const greetings: Record<DayPart, string> = {
    night: 'Gute Nacht',
    morning: 'Guten Morgen',
    day: 'Guten Tag',
    evening: 'Guten Abend',
  };
  return greetings[dayPartForHour(hour)];
}
