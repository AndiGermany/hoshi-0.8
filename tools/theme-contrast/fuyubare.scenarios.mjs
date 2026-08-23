/**
 * FUYUBARE — die Kontrast-Messung am echten Render.
 * ═══════════════════════════════════════════════════════════════════════════
 * WAS HIER BEWIESEN WERDEN MUSS, ist bei einem HELLEN Thema eine andere
 * Aussage als bei den dunklen. Dort ist der schlechteste Bildpunkt der
 * HELLSTE, und die Szenen halten ihn fest, indem jede Keyframe bei opacity:1
 * beginnt und nur nach unten geht. Hier ist der schlechteste Punkt der
 * DUNKELSTE — also muss gelten: keine Animation darf irgendwo abdunkeln.
 *
 * Fuyubare erfüllt das durch Konstruktion (s. Kopf des Generators, Regel 2):
 * jedes bewegte Element ist weiß bzw. warmweiß, liegt über einem helleren
 * Grund und berührt keinen dunklen Anker — der Sonnen-Glast wohnt HINTER der
 * Landschaft, die Funkel ausschließlich auf Schnee und Eis. Der schlechteste
 * Bildpunkt sollte damit ZEITLICH KONSTANT sein.
 *
 * „Sollte" ist keine Zusage, darum fährt diese Datei es nach: dieselbe Spalte
 * wird zu VIER virtuellen Zeitpunkten gemessen (1/4/7/10 s — dieselben, in
 * denen die Frame-Serie den Herzschlag zeigt) plus einmal mit erzwungenem
 * prefers-reduced-motion, das AUCH die Uhren im SVG anhält. Liefern alle fünf
 * Läufe denselben Wert, ist die Konstruktions-Behauptung gemessen und nicht
 * bloß behauptet. Weichen sie ab, ist irgendwo ein bewegtes Element über einen
 * dunklen Anker geraten.
 *
 * Der bindende Bildpunkt ist die dunkelste Zeder (OKLCH L 0,325) dort, wo sie
 * die Spaltenkante berührt, gegen die leiseste Schrift (--text-4). Gerechnet
 * ergibt der Schleier (0,82) darüber 4,66:1.
 *
 * Die Breiten sind die der übrigen Themen (Vergleichbarkeit), 1024 ist
 * zusätzlich der Fall UNTER der Schwelle, ab der `--veil` auf 0 fällt und der
 * Schleier alles deckt.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/fuyubare.scenarios.mjs
 */
export default {
  theme: 'fuyubare',
  viewports: [
    [1440, 900],
    [1280, 800],
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
    { name: 't1s', vt: 1000, note: 'Herzschlag-Phase 1 s' },
    { name: 't4s', vt: 4000, note: 'Herzschlag-Phase 4 s' },
    { name: 't7s', vt: 7000, note: 'Herzschlag-Phase 7 s — hier faellt die Schneelast' },
    { name: 't10s', vt: 10000, note: 'Herzschlag-Phase 10 s' },
    {
      name: 'ruhe',
      vt: 3000,
      flags: ['--force-prefers-reduced-motion'],
      note: 'alle Uhren still, auch die IM SVG — der gemalte Grundzustand',
    },
  ],
};
