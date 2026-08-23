/**
 * Der Kontrast-Beweis für NAGAREBOSHI (zweite Fassung).
 * ───────────────────────────────────────────────────────────────────────────
 * Ein Thema, dessen Bild sich bewegt, hat seinen schlechtesten Moment NICHT bei
 * t=0. Nagareboshi bewegt sich auf ZWEI Uhrwerken, und beide müssen in die
 * Messung:
 *
 *   (a) DAS FUNKELN steht IM SVG (acht Gruppen, 21–43 s, versetzte Phasen).
 *       Von außen ist es nicht anzuhalten — ein `animation-delay` erreicht
 *       Uhren in einem per `background-image` geladenen Bild nicht. Zwei Wege
 *       führen trotzdem zu einer belastbaren Zahl:
 *         · `--force-prefers-reduced-motion` hält auch die Uhren im Bild an.
 *           Und weil dort JEDER Keyframe von opacity:1 nur nach unten läuft,
 *           ist der angehaltene Zustand der HELLSTE. Das Szenario `sterne-hell`
 *           misst damit den echten oberen Rand, nicht eine Stichprobe.
 *         · Zusätzlich vier Stichproben bei 1/4/7/10 s virtueller Zeit
 *           (`funkeln-t*`) — sie belegen nebenbei, dass sich überhaupt etwas
 *           bewegt, und fangen Phasen, in denen andere Sterne oben sind.
 *
 *   (b) DIE METEORE stehen in der Themen-CSS und werden klassisch eingefroren
 *       (`animation-play-state: paused` + negatives `animation-delay`), also
 *       am echten Render zu einer exakten Sekunde gemessen.
 *
 * EIN ZEITPUNKT JE EINSATZ REICHT NICHT: der Meteor wandert in seinen ~1,4 s
 * über Himmel, Dunstband und Grat und tritt bei Einsatz 1 und 2 unterwegs aus
 * der Lesespalte in den Randraum. Sein schlechtester Beitrag liegt IN der
 * Bewegung, nicht in ihrer Mitte. Darum drei Phasen je Einsatz; der Beweis ist
 * das Minimum über alle Messungen.
 *
 * Die Sekunden kommen aus den Keyframes in nagareboshi.css (Umlauf 60 s); volle
 * Deckkraft liegt jeweils zwischen dem zweiten und dritten Keyframe:
 *   Einsatz 1  10,8–12,3 %  →  6,48–7,38 s
 *   Einsatz 2  43,4–44,9 %  → 26,04–26,94 s
 *   Einsatz 3  76,4–77,9 %  → 45,84–46,74 s
 *
 * Die Szenario-CSS steht im <style> NACH den drei <link>s des Harness, gewinnt
 * bei gleicher Spezifität also über die Regel aus der Themen-Datei.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/nagareboshi.scenarios.mjs
 */

/** Friert die Meteor-Ebene auf einer exakten Sekunde des 60-s-Umlaufs ein. */
const freeze = (sec) => `
  :root[data-theme='nagareboshi']::after {
    animation-play-state: paused;
    animation-delay: -${sec}s;
  }`;

/** Kein Meteor — der Ruhezustand, den 93 % der Zeit gilt. */
const still = `
  :root[data-theme='nagareboshi']::after { animation: none; }`;

export default {
  theme: 'nagareboshi',
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
    {
      // DER MASSGEBLICHE RUHE-WERT. Alle Uhren stehen — die der CSS über das
      // eigene Gate der Themen-Datei, die im Bild über dessen eigene
      // Medienabfrage. Und weil dort der Ruhezustand der hellste ist, ist dies
      // zugleich der prefers-reduced-motion-Beweis UND der obere Rand des
      // Sternfelds. Es bleibt kein Meteor im Bild stehen (Grundzustand der
      // Regel ist opacity: 0).
      name: 'sterne-hell',
      note: 'alle Uhren still = Sterne auf ihrem HELLSTEN + reduced-motion-Beweis',
      flags: ['--force-prefers-reduced-motion'],
    },
    { name: 'funkeln-t1', note: 'Funkeln bei 1 s virtueller Zeit', css: still, vt: 1000 },
    { name: 'funkeln-t4', note: 'Funkeln bei 4 s', css: still, vt: 4000 },
    { name: 'funkeln-t7', note: 'Funkeln bei 7 s', css: still, vt: 7000 },
    { name: 'funkeln-t10', note: 'Funkeln bei 10 s', css: still, vt: 10000 },
    { name: 'meteor-1a', note: 'Einsatz 1, Anfang (t = 6,55 s)', css: freeze(6.55) },
    { name: 'meteor-1b', note: 'Einsatz 1, Mitte  (t = 6,93 s)', css: freeze(6.93) },
    { name: 'meteor-1c', note: 'Einsatz 1, Ende   (t = 7,31 s)', css: freeze(7.31) },
    { name: 'meteor-2a', note: 'Einsatz 2, Anfang (t = 26,11 s)', css: freeze(26.11) },
    { name: 'meteor-2b', note: 'Einsatz 2, Mitte  (t = 26,49 s)', css: freeze(26.49) },
    { name: 'meteor-2c', note: 'Einsatz 2, Ende   (t = 26,87 s)', css: freeze(26.87) },
    { name: 'meteor-3a', note: 'Einsatz 3, Anfang (t = 45,91 s)', css: freeze(45.91) },
    { name: 'meteor-3b', note: 'Einsatz 3, Mitte  (t = 46,29 s)', css: freeze(46.29) },
    { name: 'meteor-3c', note: 'Einsatz 3, Ende   (t = 46,67 s)', css: freeze(46.67) },
  ],
};
