// Zeit-bewusster Gruß für den Empty-State (Yoru, awake — Slice 3). Rein &
// deterministisch (Stunde rein, Gruß raus) → ohne Uhr/DOM unit-testbar.

/**
 * Deutscher Tageszeit-Gruß zur gegebenen Stunde (0..23, toleriert auch Werte
 * außerhalb durch Modulo). Grenzen bewusst schlicht:
 *   00–04 Gute Nacht · 05–10 Guten Morgen · 11–17 Guten Tag ·
 *   18–21 Guten Abend · 22–23 Gute Nacht.
 */
export function greetingForHour(hour: number): string {
  const h = ((Math.floor(hour) % 24) + 24) % 24;
  if (h < 5) return 'Gute Nacht';
  if (h < 11) return 'Guten Morgen';
  if (h < 18) return 'Guten Tag';
  if (h < 22) return 'Guten Abend';
  return 'Gute Nacht';
}
