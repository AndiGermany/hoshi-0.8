/**
 * Der Kontrast-Beweis für YORU (zweite Fassung, Regie v2).
 * ───────────────────────────────────────────────────────────────────────────
 * YORU IST DER SONDERFALL, und das bestimmt, was hier überhaupt zugesichert
 * werden KANN. Das Thema bringt keine eigenen Token mit — es IST der
 * Basis-Satz aus `src/index.css`, und der darf nicht angefasst werden. Seine
 * Textleiter ist damit gegeben:
 *
 *   --text-1 Lrel 0.884 · --text-2 0.474 · --text-3 0.238 · --text-4 0.103
 *
 * `--text-4` erreicht 4,5:1 gegen NICHTS, auch nicht gegen reines Schwarz:
 * (0.10345 + 0.05) / 0.05 = 3,07:1 ist sein Maximum. Es steht deshalb NICHT in
 * `textTokens` — ein Riegel, den zu erfüllen mathematisch unmöglich ist, ist
 * kein Riegel, sondern ein dauerhaft rotes Licht, an dem sich niemand mehr
 * stört. Es steht stattdessen in `probeTokens` und wird im RESULT aus dem
 * jeweils schlechtesten Pixel ausgerechnet.
 *
 * DAS IST KEINE AUSREDE, SONDERN NACHRECHENBAR: in der ausgelieferten App,
 * ohne jede Szene, steht `--text-4` auf `--bg-subtle` bei 2,17:1 und
 * `--text-3` bei 4,07:1. Was diese Szene schuldet, ist deshalb (a) 4,5:1 für
 * die drei Stufen, die es erreichen KÖNNEN, und (b) der Nachweis, dass sie
 * `--text-4` nicht schlechter macht als Yorus Atmosphäre allein. Für (b) sind
 * die beiden A/B-Szenarien am Ende da.
 *
 * ZWEI UHRWERKE, wie bei nagareboshi — und beide müssen in die Messung:
 *
 *   (a) STERNE UND LATERNEN stehen IM SVG (acht Stern-Gruppen 21–43 s, drei
 *       Laternen-Uhren 26/31/37 s, das Band 47 s). Von außen ist das nicht
 *       anzuhalten; `--force-prefers-reduced-motion` erreicht diese Uhren
 *       aber, und weil dort JEDE Keyframe von opacity:1 nur nach unten läuft,
 *       ist der angehaltene Zustand der HELLSTE. `alles-hell` misst damit den
 *       echten oberen Rand statt einer Stichprobe.
 *
 *   (b) DIE BEIDEN LICHT-EBENEN stehen in der Themen-CSS (Laternenschein
 *       71/163 s, Shoji-Licht 97 s) und werden klassisch eingefroren
 *       (`animation-play-state: paused` + `animation-delay: 0s` = der
 *       `from`-Frame = volle Deckkraft).
 *
 * Zusätzlich vier Stichproben bei 1/4/7/10 s virtueller Zeit: sie belegen
 * nebenbei, dass sich überhaupt etwas bewegt, und fangen Phasen, in denen
 * andere Sterne oben sind.
 *
 * VIER BREITEN statt drei. 1920 fiel schon in der ersten Fassung aus der
 * Reihe, und zwar erklärbar: dort schneidet `cover` weniger Himmel ab, also
 * steht mehr Sternenfeld in der Spalte, während die Laternen weiter aus dem
 * Bild wandern — der schlimmste Pixel wechselt mit der Breite den Ort UND die
 * Ursache. Genau deshalb misst dieses Harness vier Fenster und nicht eines.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/yoru.scenarios.mjs
 */

/** Friert beide Licht-Ebenen auf ihrem `from`-Frame ein — der hellste Zustand. */
const lichtHell = `
  :root[data-theme='yoru'] body::before,
  :root[data-theme='yoru']::after {
    animation-play-state: paused;
    animation-delay: 0s;
  }`;

/** Nimmt die ZEICHNUNG heraus — übrig bleiben Atmosphäre + Licht-Ebenen. */
const ohneSzene = `
  ${lichtHell}
  :root[data-theme='yoru'] body::after { display: none; }`;

/** Nimmt alles heraus, was diese Datei hinzufügt: nur noch die Atmosphäre. */
const nurAtmosphaere = `
  :root[data-theme='yoru'] body::after,
  :root[data-theme='yoru'] body::before,
  :root[data-theme='yoru']::after { display: none; }`;

export default {
  theme: 'yoru',
  viewports: [
    [1920, 1080],
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
  // --text-4 fehlt hier mit Ansage, s. Kopf. --text-3 ist die scharfe Kante:
  // es erreicht 4,5:1 nur über Lrel ≤ 0.01397, und genau daran hängt die
  // Palette-Grenze des Generators (OKLCH-L ≤ 0.225).
  textTokens: ['--text-1', '--text-2', '--text-3'],
  floor: 4.5,
  scenarios: [
    {
      // DER MASSGEBLICHE WERT. Alle Uhren stehen — die der CSS über das eigene
      // Gate der Themen-Datei, die im Bild über dessen eigene Medienabfrage.
      // Und weil überall der Ruhezustand der hellste ist, ist dies zugleich der
      // prefers-reduced-motion-Beweis UND der obere Rand von Sternfeld und
      // Laternenschein.
      name: 'alles-hell',
      note: 'alle Uhren still = hellster Zustand + reduced-motion-Beweis',
      flags: ['--force-prefers-reduced-motion'],
    },
    { name: 'atem-t1', note: 'Sterne/Laternen bei 1 s virtueller Zeit', css: lichtHell, vt: 1000 },
    { name: 'atem-t4', note: 'bei 4 s', css: lichtHell, vt: 4000 },
    { name: 'atem-t7', note: 'bei 7 s', css: lichtHell, vt: 7000 },
    { name: 'atem-t10', note: 'bei 10 s', css: lichtHell, vt: 10000 },
    {
      name: 'ohne-szene',
      note: 'A/B: Zeichnung aus, Atmosphäre + Licht-Ebenen an',
      css: ohneSzene,
    },
    {
      name: 'nur-atmosphaere',
      note: 'A/B: alles aus, was diese Datei hinzufügt — der Boden',
      css: nurAtmosphaere,
    },
  ],
};
