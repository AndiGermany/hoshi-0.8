/**
 * SELBSTTEST des Harness — nicht die Messung eines neuen Themas.
 * ───────────────────────────────────────────────────────────────────────────
 * Asagiri hat seine Zahlen im Dateikopf stehen („worst pixel inside the column:
 * 4.81:1 für --text-4 und 12.73:1 für --text-1 at 1440, 1280 and 1024"). Ein
 * frisch gebautes Messwerkzeug, das diese Zahlen NICHT reproduziert, misst
 * falsch — und das will man wissen, BEVOR man ihm ein neues Thema anvertraut.
 * Genau daran ist der Messfühler vom 18.08. gescheitert (er lud die Basis-Token
 * nicht mit); dieser Testlauf ist die Gegenprobe dazu.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/asagiri.scenarios.mjs
 */
export default {
  theme: 'asagiri',
  viewports: [
    [1440, 900],
    [1280, 800],
    [1024, 768],
  ],
  probeTokens: ['--text-1', '--text-2', '--text-3', '--text-4', '--accent', '--bg-base', '--bg-surface', '--bg-subtle'],
  textTokens: ['--text-1', '--text-4'],
  floor: 4.5,
  scenarios: [{ name: 'ruhe', note: 'Erwartung laut Asagiri-Kopf: text-4 ≈ 4,81:1 · text-1 ≈ 12,73:1' }],
};
