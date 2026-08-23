/**
 * Kontrast-Szenarien für HANAIKADA.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/hanaikada.scenarios.mjs
 *
 * WAS HIER BEWIESEN WERDEN MUSS, und warum es anders liegt als bei den
 * Nacht-Themen: dort bewegt sich DECKUNG (Sterne funkeln, Laternen atmen), und
 * die Regel „jede Keyframe startet bei opacity 1 und geht nur runter" macht den
 * eingefrorenen Zustand automatisch zum Kontrast-Worst-Case. Hier bewegt sich
 * GEOMETRIE: Blüten fallen quer durchs Bild, das Blütenfloß treibt flussabwärts.
 * Keine einzige Keyframe fasst opacity an. Damit ist der eingefrorene Zustand
 * NICHT automatisch der schlechteste — eine Blüte kann in Sekunde 7 über einer
 * hellen Wasserstelle stehen, wo in Sekunde 1 nichts war.
 *
 * Deshalb wird nicht ein Zustand gemessen, sondern die BEWEGUNG abgetastet: vier
 * Zeitpunkte über zehn Sekunden, dazu der reduced-motion-Zustand. Das Argument,
 * dass Bewegung hier nicht schaden KANN, steht zusätzlich im Generator (jede
 * bewegte Form ist heller als der Boden ihres Höhenbands) — aber ein Argument
 * ist kein Beweis, und vier Abtastungen sind billiger als ein Irrtum.
 *
 * `--virtual-time-budget` treibt die SVG-interne Uhr (Blüten und Floß leben
 * dort), NICHT die Animationen des Wirtsdokuments. Die beiden nahen Blüten aus
 * hanaikada.css brauchen darum die Pausier-Technik der Piloten: play-state
 * paused plus negativer delay stellt sie auf eine gewählte Sekunde. Sie fallen
 * ohnehin ausschließlich in den Randspalten — das Szenario `nahe-blueten` prüft
 * genau das nach, statt es zu behaupten.
 */

/** Hält die beiden CSS-Blüten bei Sekunde `sec` ihrer eigenen Uhr an. */
const halte = (sec) => `
  :root[data-theme='hanaikada']::before,
  :root[data-theme='hanaikada'] #root::before {
    animation-play-state: paused !important;
    animation-delay: -${sec}s !important;
  }`;

export default {
  theme: 'hanaikada',
  /* Breiteste reale Bühne, die Regie-Standardbühne, und der Fall, in dem der
     Schleier-calc auf 0 fällt und alles deckt (unter ~1064 px). */
  viewports: [
    [1600, 1000],
    [1366, 1024],
    [1024, 768],
  ],
  probeTokens: [
    '--text-1',
    '--text-2',
    '--text-3',
    '--text-4',
    '--accent',
    '--bg-base',
    '--bg-surface',
    '--bg-subtle',
  ],
  textTokens: ['--text-1', '--text-4'],
  floor: 4.5,
  scenarios: [
    {
      name: 'reduced-motion',
      note: 'alles still — der gemalte Zustand, zugleich der reduced-motion-Beweis',
      flags: ['--force-prefers-reduced-motion'],
    },
    { name: 'floss-t1', note: 'Floß und Blütenfall bei 1 s', css: halte(9), vt: 1000 },
    { name: 'floss-t4', note: 'Floß und Blütenfall bei 4 s', css: halte(21), vt: 4000 },
    { name: 'floss-t7', note: 'Floß und Blütenfall bei 7 s', css: halte(34), vt: 7000 },
    { name: 'floss-t10', note: 'Floß und Blütenfall bei 10 s', css: halte(47), vt: 10000 },
    {
      name: 'nahe-blueten',
      note: 'die zwei CSS-Blüten mitten im Fall — sie dürfen die Spalte nie berühren',
      css: halte(16),
      vt: 2000,
    },
  ],
};
