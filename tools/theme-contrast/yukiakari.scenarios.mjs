/**
 * Der Kontrast-Beweis für YUKIAKARI (雪明かり).
 * ───────────────────────────────────────────────────────────────────────────
 * Dieses Thema hat DREI Uhrwerke, und sie müssen aus drei verschiedenen
 * Gründen in die Messung:
 *
 *   (a) DIE FLOCKEN stehen IM SVG und bewegen sich ausschließlich per
 *       `transform`. Ihre Helligkeit hängt also gar nicht von der Zeit ab —
 *       der Generator hält jede Flockenfarbe unter CAP, damit ist JEDE
 *       erreichbare Position unbedenklich. Die vier Zeitproben unten sind
 *       trotzdem drin: nicht, weil ein Verdacht bestünde, sondern weil eine
 *       Zusage, die man nur begründet hat, keine Messung ist. Sie belegen
 *       nebenbei, dass sich überhaupt etwas bewegt.
 *
 *   (b) DIE LICHTER UND PFÜTZEN stehen ebenfalls im SVG, ändern aber ihre
 *       Deckkraft — von 1 nur nach unten. Ihr hellster Zustand ist damit der
 *       gemalte, und den erwischt `--force-prefers-reduced-motion`: der
 *       Schalter hält auch die Uhren INNERHALB eines per `background-image`
 *       geladenen Bildes an (nachgewiesen bei Nagareboshi 19.08.).
 *
 *   (c) DER WOLKENZUG (`:root::after`) ist der einzige Teil, bei dem der
 *       Ruhezustand NICHT automatisch der Worst Case ist: er verschiebt sich
 *       um 5 % der Fensterbreite. Seine Deckkraft ist bei t=0 am höchsten,
 *       seine POSITION dort aber die linke — eine helle Bank kann einen
 *       bestimmten Spaltenpixel zu einer anderen Phase überstreichen. Darum
 *       vier Phasen des 61-s-Umlaufs, klassisch eingefroren
 *       (`animation-play-state: paused` + negatives `animation-delay`).
 *
 * FÜNF BREITEN statt der üblichen vier: 1366×1024 ist die Galerie-Bühne, auf
 * der die Frame-Serie und Andis Blick entstehen. Ein Thema, das dort nicht
 * gemessen wird, ist genau dort ungeprüft.
 *
 * Gemessen wird `--text-4` (die leiseste Stufe) gegen 4,50:1 — nicht `--text-3`
 * wie bei Yoru. Das ist die härtere Zusage und der Grund, warum dieses Thema
 * überhaupt hell sein darf: die eigene Textleiter hebt den Deckel für alles
 * Gemalte von Grau 33 auf Grau 60 (Rechnung im Kopf von yukiakari.css).
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/yukiakari.scenarios.mjs
 */

/** Friert den Wolkenzug auf einer exakten Sekunde seines 61-s-Umlaufs ein. */
const wolke = (sec) => `
  :root[data-theme='yukiakari']::after {
    animation-play-state: paused;
    animation-delay: -${sec}s;
  }`;

/** Beide CSS-Ebenen still auf ihrem Grundzustand — für die SVG-Zeitproben. */
const cssStill = `
  :root[data-theme='yukiakari']::before,
  :root[data-theme='yukiakari']::after { animation: none; }`;

export default {
  theme: 'yukiakari',
  viewports: [
    [1920, 1080],
    [1440, 900],
    [1366, 1024],
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
    {
      // DER MASSGEBLICHE WERT. Alle Uhren stehen — die der CSS über das eigene
      // Gate der Themen-Datei, die im Bild über dessen eigene Medienabfrage.
      // Und weil jede Deckkraft-Keyframe von 1 nur nach unten läuft, ist der
      // angehaltene Zustand zugleich der HELLSTE und der
      // prefers-reduced-motion-Beweis.
      name: 'alles-hell',
      note: 'alle Uhren still = hellster Zustand + reduced-motion-Beweis',
      flags: ['--force-prefers-reduced-motion'],
    },
    { name: 'flocken-t1', note: 'Schneefall bei 1 s virtueller Zeit', css: cssStill, vt: 1000 },
    { name: 'flocken-t4', note: 'Schneefall bei 4 s', css: cssStill, vt: 4000 },
    { name: 'flocken-t7', note: 'Schneefall bei 7 s', css: cssStill, vt: 7000 },
    { name: 'flocken-t10', note: 'Schneefall bei 10 s', css: cssStill, vt: 10000 },
    { name: 'wolken-p00', note: 'Wolkenzug bei 0 s des 61-s-Umlaufs', css: wolke(0.01) },
    { name: 'wolken-p25', note: 'Wolkenzug bei 15,25 s', css: wolke(15.25) },
    { name: 'wolken-p50', note: 'Wolkenzug bei 30,5 s (weiteste Verschiebung)', css: wolke(30.5) },
    { name: 'wolken-p75', note: 'Wolkenzug bei 45,75 s', css: wolke(45.75) },
  ],
};
