import { de } from '../i18n/de';

/**
 * **weatherFormat** — die Niederschlags-Zahl, ausgezogen aus `IdleFace.tsx`.
 *
 * Derselbe Grund wie bei `weatherGlyph.tsx`: die Maximieren-Ansicht (Andi
 * 23.08.) zeigt dieselbe Zahl und darf `IdleFace.tsx` nicht importieren, weil
 * `IdleFace.tsx` das Overlay rendert. `IdleFace.tsx` re-exportiert `fmtPrecip`
 * unverändert weiter — jeder bestehende Import bleibt gültig.
 */

/** Dezimaltrenner je Locale — dieselbe simple toFixed+replace-Technik, pro Sprache statt hart de. */
export const DECIMAL_SEPARATOR: Record<string, string> = {
  'de-DE': ',',
  'en-US': '.',
  'es-ES': ',',
  'fr-FR': ',',
  'it-IT': ',',
};

/** mm-Niederschlag mit dem Dezimaltrenner der aktiven Sprache: 3 → „3", 1.2 → „1,2" (de) / „1.2" (en). */
export function fmtPrecip(mm: number, locale: string = de.locale): string {
  const rounded = Math.round(Math.max(0, mm) * 10) / 10;
  if (Number.isInteger(rounded)) return String(rounded);
  const sep = DECIMAL_SEPARATOR[locale] ?? '.';
  return rounded.toFixed(1).replace('.', sep);
}
