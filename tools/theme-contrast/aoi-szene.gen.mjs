/**
 * AOI (青) — Szenen-Generator, Fassung v2
 * ═══════════════════════════════════════════════════════════════════════════
 * Erzeugt `frontend/public/themes/aoi-szene.svg`.
 *
 *   node tools/theme-contrast/aoi-szene.gen.mjs
 *
 * DAS BILD: die Stille UNTER der Oberfläche. Ganz oben die Unterseite der
 * Wasserhaut mit ihrem Lichtnetz, daraus fallen neun Lichtbahnen schräg nach
 * unten durch das offene Blau. Im Mittelgrund zieht ein Fischschwarm als
 * Schatten quer durchs Bild (hinter der Lesespalte, genau da, wo v1 ein Loch
 * hatte), darüber gleitet ein Rochen als ferne Silhouette. Unten steht links
 * und rechts ein Tangwald, dazwischen liegt auf dem Sand DAS WRACK: ein
 * aufgebrochener Bootsrumpf mit freien Spanten und gebrochenem Mast, dessen
 * Oberkanten das Licht von oben fangen. Überall steigen Lichtpunkte auf.
 *
 * ── DAS HELLIGKEITSGESETZ (der Grund, warum v1 ein dunkles Gemurmel war) ───
 * Der Schleier (`--aoi-veil` = 0.88) legt die Tinte #0c1017 über die Spalte.
 * Ein Szenenwert v erscheint dort als  v·0.12 + base·0.88.  Die Kette ist
 * KURZ, weil dieses SVG deckend ist und alles Licht IN SICH trägt: es gibt
 * kein zweites Luftglühen mehr in der CSS, das sich dazumultipliziert.
 *
 * Gerechnet (nicht geschätzt): die leiseste Schriftstufe --text-4 (#848fa3)
 * braucht 4,5:1 gegen den hellsten Bildpunkt in der Spalte. Hinter diesem
 * Schleier kommt selbst REINES WEISS nur auf 4,26:1 — der Deckel ist also
 * eine echte, nahe Grenze, aber er liegt sehr viel höher, als v1 geglaubt hat:
 *
 *     #ffffff → hinter dem Schleier 4,26:1   (zu hell, knapp)
 *     #cfe4f8 → hinter dem Schleier 4,46:1   (immer noch knapp)
 *     #a8c8ea → hinter dem Schleier 4,67:1   ← DER DECKEL
 *
 * v1 hatte seinen hellsten Spaltenpunkt bei L 0.0113 — die Hälfte der
 * erlaubten 0.0215. Genau diese ungenutzte Hälfte ist die Tiefe, die man
 * nicht gesehen hat. v2 fährt bis an den Deckel und spreizt von dort nach
 * unten durch (REZEPT D2).
 *
 * DER DECKEL GILT GLOBAL, nicht nur „in der Spalte": bei 1366 px Fensterbreite
 * deckt der Schleier x 223…1143 und federt bis 113…1253 aus — es bleiben
 * 113 px echter Seitenraum. Ein Motiv, das „nur im Randstreifen" hell sein
 * dürfte, gibt es nicht. Darum ist der Deckel hier per KONSTRUKTION erzwungen:
 * `col()` wirft, sobald ein Kanal über (168, 200, 234) liegt oder eine Farbe
 * dunkler als --bg-base ist. Jede Alphamischung zweier gedeckelter Farben ist
 * wieder gedeckelt (Kanalweise Konvexkombination), also gilt die Zusage auch
 * für alles, was sich hier überlagert.
 *
 * ── EIN THEMA OHNE GEGENFARBE (die Seele bleibt stehen) ────────────────────
 * Alle 34 Farben dieses Bildes liegen im selben kalten Viertel. Die Spannung
 * kommt ausschließlich aus HELLIGKEIT — von #a8c8ea (Wasserhaut) bis #0b1420
 * (die offene Luke im Wrack, der dunkelste Punkt). Das ist keine Marotte,
 * sondern die Physik der Szene: unter Wasser verschluckt das Blau zuerst das
 * Rot, dann alles andere.
 *
 * ── HERZSCHLAG (dezent, aber nachweisbar) ─────────────────────────────────
 * Fünf Uhrenfamilien, alle nur transform/opacity, alle IM SVG (nur so treibt
 * `--virtual-time-budget` sie — REZEPT E; die CSS des Wirtsdokuments würde
 * beim Frame-Beweis stillstehen):
 *   1. Lichtbahnen atmen (19…43 s) und wandern seitlich (27…47 s)  ← Hauptuhr
 *   2. Das Lichtnetz an der Wasserhaut driftet (21…33 s)
 *   3. Der Tang wiegt sich um seinen Haftfuß (13…27 s)
 *   4. Lichtpunkte steigen auf (22…38 s, linear, kein alternate)
 *   5. Die Schwärme ziehen (37…61 s)
 * Jede Keyframe startet bei opacity 1 (der gemalte Zustand ist der hellste)
 * und geht nur RUNTER. Der eingefrorene Zustand (prefers-reduced-motion) ist
 * damit zugleich der Kontrast-Worst-Case — beweisbar ohne Pausier-Zugriff.
 *
 * Kein `--` in SVG-Kommentaren; diese Datei schreibt gar keine (Regie-Lektion
 * 2: ein doppelter Bindestrich im Kommentar lässt Chrome die Datei still
 * fallen).
 */

import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, '..', '..', 'frontend', 'public', 'themes', 'aoi-szene.svg');
const BUDGET = 80 * 1024;

/* 1600×900 (16:9) ist mit Absicht das WEITESTE gängige Fenster-Format: bei
   jedem schmaleren Fenster beschneidet `cover` nur seitlich, die Komposition
   bleibt vertikal VOLLSTÄNDIG stehen. v1 stand auf 1600×1000 und verlor bei
   16:9-Schirmen das untere Zehntel — also genau den Grund, auf dem das Wrack
   liegt. Sicher sichtbar ist bei 1366×1024 der Streifen x 200…1400. */
const W = 1600;
const H = 900;

/* ── Fester Startwert: derselbe Aufruf ergibt bitgleich dasselbe Bild. ───── */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = rng(0x0a01b17);
const rr = (lo, hi) => lo + rand() * (hi - lo);
const ri = (lo, hi) => Math.floor(rr(lo, hi + 1));

/* Die einzigen zwei Rundungen im Haus (REZEPT A). */
const n = (v) => String(Math.round(v));
const f = (v) => {
  const r = Math.round(v * 10) / 10;
  return String(r === Math.trunc(r) ? Math.trunc(r) : r);
};

/* ── Der Deckel, per Konstruktion ─────────────────────────────────────────── */
const CAP = [168, 200, 234];
const BASE = [12, 16, 23];
const srgb = (c) => (c / 255 <= 0.04045 ? c / 255 / 12.92 : ((c / 255 + 0.055) / 1.055) ** 2.4);
const lum = ([r, g, b]) => 0.2126 * srgb(r) + 0.7152 * srgb(g) + 0.0722 * srgb(b);
const FLOOR = lum(BASE);
const hex2 = (s) => [1, 3, 5].map((i) => parseInt(s.slice(i, i + 2), 16));

function col(hex) {
  const v = hex2(hex);
  for (let i = 0; i < 3; i++) {
    if (v[i] > CAP[i]) {
      throw new Error(`Deckel gerissen: ${hex} Kanal ${i} = ${v[i]} > ${CAP[i]}`);
    }
  }
  if (lum(v) < FLOOR - 1e-6) {
    throw new Error(`Dunkler als bg-base: ${hex} (L ${lum(v).toFixed(5)} < ${FLOOR.toFixed(5)})`);
  }
  return hex;
}

/* ── Palette ───────────────────────────────────────────────────────────────
   Eine einzige Farbfamilie, vierunddreißig Helligkeiten. Hinter dem Schleier
   staucht sich der ganze Umfang auf L 5,1…18,9 ‰ zusammen.

   JEDER Wert unten ist RÜCKWÄRTS aus seiner Wirkung hinter dem Schleier
   gerechnet, nicht nach Gefühl gewählt: zu einem Ziel in Promille-Leuchtdichte
   (die Zahl in Klammern) sucht ein Halbierungsverfahren die Farbe auf der
   Geraden vom Schwarzpunkt zum Deckel. Der Grund für dieses Verfahren steht
   in der ersten Messung von v2: die Spalte kam auf eine Spreizung von 1,7×
   (Amayadori, das bestandene Nachbarbild: 2,2×) — die Leiter war nicht zu
   dunkel, sie war zu ENG. Der Schleier staucht 185× Szenen-Umfang auf 3,9×;
   was vorher eng lag, ist danach eine Masse.

   Die Wasser-Familie läuft in Richtung des Themen-Akzents (#5ea0f2), leicht
   entsättigt und auf den Deckel gestreckt. Das LICHT ist weißer als das
   Wasser — es kommt von draußen und hat die Farbe des Himmels, nicht die des
   Mediums, durch das es fällt. */
const C = {
  /* Die Wasserhaut, von unten. Die hellsten Töne des Hauses. */
  hautHell: col('#9ec4ea'), // 18.5
  hautMitte: col('#8cadcf'), // 16.2
  netz: col('#96b9dd'), // 17.4

  /* Die Wassersäule, oben nach unten: elf Stufen über den vollen Umfang.
     Die Schrittweite ist bewusst gleichmäßig in der GESTAUCHTEN Skala
     (15.2 → 6.6 in Elftelschritten), nicht in der gemalten — gleichmäßig
     gemalt hieße hinter dem Schleier: unten alles gleich. */
  w0: col('#69a4e0'), // 15.2
  w1: col('#6095cc'), // 13.9
  w2: col('#5483b3'), // 12.4
  w3: col('#49719b'), // 11.0
  w4: col('#3e6084'), // 9.8
  w5: col('#34506e'), // 8.7
  w6: col('#2a425a'), // 7.8
  w7: col('#23364a'), // 7.1
  w8: col('#1d2d3e'), // 6.6
  w9: col('#172431'), // 6.1

  /* Die Lichtbahnen. */
  strahlKern: col('#9ec4ea'), // 18.5
  strahlMitte: col('#91b3d6'), // 16.8
  strahlAussen: col('#7d9bb8'), // 14.5

  /* Der Grund. Sand fängt Licht, darum ist er HELLER als das Wasser über ihm
     (die einzige Stelle, an der die Tiefen-Leiter absichtlich umkehrt — und
     der Grund, warum die Silhouetten unten überhaupt lesbar sind). */
  sandFern: col('#476e97'), // 10.8
  sandNah: col('#334f6c'), // 8.6
  sandRippel: col('#517eac'), // 12.0
  pfuetze: col('#96b9dd'), // 17.4

  /* Tang. Silhouetten sind DUNKLER als das Wasser dahinter. Drei Ebenen. */
  tangFern: col('#314c67'), // 8.4
  tangMitte: col('#24384c'), // 7.2
  tangNah: col('#1a2837'), // 6.3
  tangKante: col('#5d90c5'), // 13.5

  /* Fels, ganz vorn. */
  felsNah: col('#15202c'), // 5.9
  felsKante: col('#517eac'), // 12.0

  /* Das Wrack. */
  rumpf: col('#172431'), // 6.1
  rumpfHell: col('#2a425a'), // 7.8
  spant: col('#203143'), // 6.8
  luke: col('#101822'), // 5.5 — der dunkelste Punkt des Bildes
  kante: col('#9ec4ea'), // 18.5 — das Licht auf den Oberkanten
  kanteLeise: col('#7d9bb8'), // 14.5
  tau: col('#283f56'), // 7.6

  /* Leben. */
  fischNah: col('#1b2a39'), // 6.4
  fischFern: col('#2d455f'), // 8.0
  rochen: col('#3a5b7c'), // 9.4
  punkt: col('#9ec4ea'), // 18.5
  punktLeise: col('#598abd'), // 13.0

  /* Dunst zwischen den Ebenen. */
  dunst: col('#5584b5'), // 12.5
};

const out = [];
/* Mehrere Fragmente auf einmal. Die einargumentige Fassung hat einmal still
   das ganze Wrack verschluckt: `p(a, b)` schob nur a in die Ausgabe, und
   weil ein SVG ohne dieses eine Motiv weiterhin gültig ist, meldete nichts
   einen Fehler — es war nur plötzlich nicht mehr im Bild. */
const p = (...frag) => out.push(...frag);

/* ═══════════ 1. RAHMEN + DEFS ═══════════════════════════════════════════ */
p(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" preserveAspectRatio="xMidYMid slice">`,
);
p('<defs>');

/* Die Wassersäule als EIN Verlauf mit zehn Stufen. */
const wStops = [
  [0.0, C.w0],
  [0.06, C.w1],
  [0.14, C.w2],
  [0.24, C.w3],
  [0.36, C.w4],
  [0.5, C.w5],
  [0.64, C.w6],
  [0.78, C.w7],
  [0.9, C.w8],
  [1.0, C.w9],
];
p(
  `<linearGradient id="gw" x1="0" y1="0" x2="0" y2="1">${wStops
    .map(([o, c]) => `<stop offset="${o}" stop-color="${c}"/>`)
    .join('')}</linearGradient>`,
);

/* Die Wasserhaut. */
p(
  `<linearGradient id="gh" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${C.hautHell}"/><stop offset="0.45" stop-color="${C.hautMitte}"/><stop offset="1" stop-color="${C.w0}" stop-opacity="0"/></linearGradient>`,
);

/* Drei geteilte Bahnen-Verläufe (objectBoundingBox: EIN def für alle neun
   Bahnen, weil der Verlauf relativ zur eigenen Hüllbox läuft). */
const shaftGrad = (id, c, a) =>
  `<linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c}" stop-opacity="${a}"/><stop offset="0.42" stop-color="${c}" stop-opacity="${f(a * 0.66)}"/><stop offset="0.74" stop-color="${c}" stop-opacity="${f(a * 0.24)}"/><stop offset="1" stop-color="${c}" stop-opacity="0"/></linearGradient>`;
p(shaftGrad('ga', C.strahlAussen, 0.22));
p(shaftGrad('gb', C.strahlMitte, 0.34));
p(shaftGrad('gc', C.strahlKern, 0.6));

/* Der Sand. */
/* Der Sand wird nach UNTEN heller, nicht dunkler. Die erste Fassung lief
   von sandFern ins tiefe Wasser aus und ließ das Bild am unteren Rand
   ausfransen — Regie-Regel 1 verlangt aber ein auskomponiertes unteres
   Drittel. Physikalisch ist die neue Richtung auch die richtige: der ferne
   Grund verdunstet im Streulicht in Richtung Wasserfarbe, der nahe Grund
   liegt direkt unter den Bahnen und ist das hellste Stück Boden im Bild. */
p(
  `<linearGradient id="gs" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${C.rochen}"/><stop offset="0.3" stop-color="${C.sandFern}"/><stop offset="0.74" stop-color="${C.sandRippel}"/><stop offset="1" stop-color="${C.sandFern}"/></linearGradient>`,
);

/* Die Lichtpfützen auf dem Sand: EIN radialer Verlauf für alle. */
p(
  `<radialGradient id="gp"><stop offset="0" stop-color="${C.pfuetze}" stop-opacity="0.5"/><stop offset="0.55" stop-color="${C.pfuetze}" stop-opacity="0.16"/><stop offset="1" stop-color="${C.pfuetze}" stop-opacity="0"/></radialGradient>`,
);

/* Der Dunst zwischen Mittel- und Hintergrund (die Thermokline). */
p(
  `<linearGradient id="gd" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${C.dunst}" stop-opacity="0"/><stop offset="0.5" stop-color="${C.dunst}" stop-opacity="0.2"/><stop offset="1" stop-color="${C.dunst}" stop-opacity="0"/></linearGradient>`,
);

/* Ein einziger weicher Filter für allen Dunst und alle fernen Schatten. */
p('<filter id="fb" x="-25%" y="-25%" width="150%" height="150%"><feGaussianBlur stdDeviation="9"/></filter>');
p('<filter id="fc" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="3"/></filter>');
p('</defs>');

/* ═══════════ 2. DIE WASSERSÄULE ═════════════════════════════════════════ */
p(`<rect width="${W}" height="${H}" fill="url(#gw)"/>`);

/* ═══════════ 3. DIE WASSERHAUT UND IHR LICHTNETZ ════════════════════════ */
/* Die Haut ist keine gerade Kante: eine flache Dünung, von unten gesehen. */
{
  const pts = [];
  for (let x = 0; x <= W; x += 40) {
    pts.push([x, 116 + Math.sin(x / 155) * 12 + Math.sin(x / 61 + 1.7) * 5]);
  }
  const d = `M0 0 L${W} 0 L${W} ${f(pts[pts.length - 1][1])} ${pts
    .slice()
    .reverse()
    .map(([x, y]) => `L${n(x)} ${f(y)}`)
    .join('')} Z`;
  p(`<path d="${d}" fill="url(#gh)"/>`);

  /* Das Netz: helle Kringel an der Unterseite, wie Licht auf einer Decke.
     Sie driften seitlich — die zweite Uhr, und die einzige Bewegung, die
     man ganz oben im Bild überhaupt bemerken kann. */
  const netz = [];
  for (let i = 0; i < 48; i++) {
    const y = rr(14, 258);
    const x = rr(-60, W + 60);
    const len = rr(70, 260);
    const amp = rr(3, 10);
    const op = 0.56 - (y / 258) * 0.42;
    const d2 = `M${n(x)} ${f(y)}q${f(len * 0.25)} ${f(-amp)} ${f(len * 0.5)} 0t${f(len * 0.5)} 0`;
    netz.push(
      `<path class="n${i % 6}" d="${d2}" fill="none" stroke="${C.netz}" stroke-opacity="${f(op)}" stroke-width="${f(rr(1.2, 3.4))}" stroke-linecap="round"/>`,
    );
  }
  p(`<g>${netz.join('')}</g>`);
}

/* ═══════════ 4. DIE FERNE: ROCHEN UND DER OBERE SCHWARM ═════════════════ */
/* Der Rochen ist die zweite benennbare Figur — weit weg, fast nur Ahnung,
   aber er gibt dem offenen Wasser einen MASSSTAB. */
{
  /* Nach LINKS gerückt (v2.1): auf der Mini-Bühne stehen bei 1366 px die
     Karten über viewBox y 176…369 — genau dort schwamm er vorher und war
     damit unsichtbar. Jetzt liegt seine linke Hälfte im linken Seitenstreifen
     und man sieht ihn wirklich. */
  const rx = 292;
  const ry = 246;
  const d = `M${rx} ${ry}c-38-26-96-34-134-16c-14 7-12 15 2 20c30 11 66 14 96 10c-24 14-30 26-16 33c16 8 44-2 62-20c18 18 46 28 62 20c14-7 8-19-16-33c30 4 66 1 96-10c14-5 16-13 2-20c-38-18-96-10-134 16z`;
  p(
    `<g class="ry"><path d="${d}" fill="${C.rochen}" fill-opacity="0.66" filter="url(#fc)"/><path d="M${rx} ${ry + 14}q6 62 34 96" fill="none" stroke="${C.rochen}" stroke-opacity="0.5" stroke-width="4" filter="url(#fc)"/></g>`,
  );
}

/* Der obere, fernere Schwarm: kleiner, blasser, weiter oben. */
/* Ganzzahlige Koordinaten: bei 156 Fischen sind die Nachkommastellen 1,5 KB,
   und 1,5 KB sind bei 94 % Budget der Unterschied zwischen „geht noch" und
   „Generator bricht ab". Das Budget wird aus den SCHWÄCHSTEN Elementen
   bezahlt (REZEPT C) — eine halbe Pixelbreite an einem 3 px großen Fisch ist
   das schwächste Element, das dieses Bild hat. */
function fisch(x, y, s, fill, op) {
  const a = s;
  return `<path d="M${n(x)} ${n(y)}c${f(a * 0.9)} ${f(-a * 0.75)} ${f(a * 2.6)} ${f(-a * 0.75)} ${f(a * 3.4)} 0c${f(-a * 0.8)} ${f(a * 0.75)} ${f(-a * 2.5)} ${f(a * 0.75)} ${f(-a * 3.4)} 0zM${n(x)} ${n(y)}l${f(-a * 1.1)} ${f(-a * 0.85)}v${f(a * 1.7)}z" fill="${fill}" fill-opacity="${f(op)}"/>`;
}
{
  const arr = [];
  for (let i = 0; i < 44; i++) {
    const t = i / 43;
    const x = 900 + t * 500 + rr(-26, 26);
    const y = 300 + Math.sin(t * 3.1) * 30 + rr(-22, 22);
    arr.push(fisch(x, y, rr(1.5, 2.6), C.fischFern, rr(0.4, 0.72)));
  }
  /* Ein zweiter ferner Zug im oberen linken Wasser: das obere Drittel war
     sonst nur Verlauf, und ein Verlauf ist kein Bild. */
  for (let i = 0; i < 34; i++) {
    const t = i / 33;
    const x = 96 + t * 420 + rr(-24, 24);
    const y = 372 + Math.sin(t * 2.6 + 1.2) * 26 + rr(-20, 20);
    arr.push(fisch(x, y, rr(1.4, 2.4), C.fischFern, rr(0.36, 0.66)));
  }
  p(`<g class="sh1" filter="url(#fc)">${arr.join('')}</g>`);
}

/* ═══════════ 5. DIE LICHTBAHNEN ═════════════════════════════════════════ */
/* Neun Bahnen, alle mit derselben Neigung (die Sonne steht links oben), je
   drei ineinandergelegte Keile: außen breit und leise, innen schmal und hell.
   Kein Weichzeichner — drei Stufen ergeben denselben weichen Rand, kosten
   aber nichts beim Neuzeichnen, und das ist bei einer ATMENDEN Fläche der
   ganze Unterschied. */
const SHAFTS = [
  { x: 70, w: 34, yb: 700, k: 0 },
  { x: 232, w: 52, yb: 828, k: 1 },
  { x: 404, w: 30, yb: 640, k: 2 },
  { x: 566, w: 62, yb: 846, k: 3 },
  { x: 742, w: 38, yb: 726, k: 4 },
  { x: 908, w: 56, yb: 858, k: 5 },
  { x: 1076, w: 33, yb: 668, k: 6 },
  { x: 1248, w: 66, yb: 840, k: 7 },
  { x: 1436, w: 40, yb: 754, k: 8 },
];
const LEAN = 0.17;
function shaftPath(x, w, yb, spread) {
  const y0 = 52;
  const xb = x + (yb - y0) * LEAN;
  const wb = w * spread * 2.35;
  const wt = w * spread;
  return `M${f(x - wt)} ${y0}L${f(x + wt)} ${y0}L${f(xb + wb)} ${n(yb)}L${f(xb - wb)} ${n(yb)}Z`;
}
{
  const arr = SHAFTS.map(
    ({ x, w, yb, k }) =>
      `<g class="v${k}"><path d="${shaftPath(x, w, yb, 2.5)}" fill="url(#ga)"/><path d="${shaftPath(x, w, yb, 1.45)}" fill="url(#gb)"/><path d="${shaftPath(x, w, yb, 0.62)}" fill="url(#gc)"/></g>`,
  );
  p(`<g class="bahnen">${arr.join('')}</g>`);
}

/* ═══════════ 6. DIE THERMOKLINE ═════════════════════════════════════════ */
/* Ein liegendes Dunstband auf halber Höhe. Es trennt „fern" von „nah" und
   ist der Grund, warum der Schwarm davor überhaupt als Schatten liest. */
p(`<rect x="0" y="330" width="${W}" height="230" fill="url(#gd)"/>`);

/* ═══════════ 7. DER FERNE TANG (Hintergrund-Ebene) ══════════════════════ */
/* Ein Tangblatt ist EIN Pfad: die eine Kante hoch, die andere runter.
   `transform-origin` steht am Haftfuß, damit sich das Blatt von unten wiegt
   und nicht in der Mitte knickt. */
/* Ein Tangblatt ist ein BLATT, keine Nadel. Die erste Fassung führte beide
   Kanten mit je einer quadratischen Kurve auf denselben Spitzenpunkt zu; das
   Blatt war dadurch schon auf halber Höhe nur noch 0,42 seiner Breite und
   las sich als Schilfspitze — genau der Befund „Motive ohne Handwerk" aus der
   Regie (Fehler 3). Jetzt tragen zwei Kubiken die Breite über rund zwei
   Drittel der Länge und verjüngen erst oben, die Spitze ist gerundet, und
   `bow` biegt Kontrollpunkte UND Spitze, sodass das Blatt eine Strömung hat
   statt einer Neigung. */
function tang(x, base, h, w, fill, op, cls, bow) {
  const tipX = x + bow;
  const midX = x + bow * 0.34;
  const d =
    `M${n(x - w / 2)} ${n(base)}` +
    `C${f(x - w * 0.6)} ${f(base - h * 0.34)} ${f(midX - w * 0.56)} ${f(base - h * 0.73)} ${f(tipX - w * 0.13)} ${f(base - h)}` +
    `q${f(w * 0.13)} ${f(-h * 0.035)} ${f(w * 0.26)} ${f(h * 0.02)}` +
    `C${f(midX + w * 0.6)} ${f(base - h * 0.71)} ${f(x + w * 0.68)} ${f(base - h * 0.33)} ${n(x + w / 2)} ${n(base)}Z`;
  return `<path class="${cls}" style="transform-origin:${n(x)}px ${n(base)}px" d="${d}" fill="${fill}" fill-opacity="${f(op)}"/>`;
}

{
  const arr = [];
  for (let i = 0; i < 21; i++) {
    const x = rr(360, 1280);
    const base = rr(700, 742);
    arr.push(
      tang(x, base, rr(130, 250), rr(9, 18), C.tangFern, rr(0.5, 0.8), `t${i % 8}`, rr(-46, 46)),
    );
  }
  p(`<g filter="url(#fb)">${arr.join('')}</g>`);
}

/* ═══════════ 8. DER NAHE SCHWARM (Mittelgrund, hinter der Lesespalte) ═══ */
/* Hier stand in v1 nichts. Der Schwarm läuft von x 300 bis 1180 quer durch
   die Bildmitte — also mitten hinter die Lesespalte, genau wie Regie-Regel 1
   es verlangt. Der Schleier macht ihn dort leise; im Seitenraum bleibt er
   ein erkennbarer Schatten. */
{
  const arr = [];
  for (let i = 0; i < 78; i++) {
    const t = i / 77;
    const x = 296 + t * 880 + rr(-34, 34);
    const y = 452 + Math.sin(t * 4.4 + 0.6) * 42 + rr(-30, 30);
    arr.push(fisch(x, y, rr(2.4, 4.6), C.fischNah, rr(0.5, 0.9)));
  }
  p(`<g class="sh2">${arr.join('')}</g>`);
}

/* ═══════════ 9. DER GRUND ═══════════════════════════════════════════════ */
{
  const pts = [];
  for (let x = 0; x <= W; x += 50) {
    pts.push([x, 772 + Math.sin(x / 240 + 0.9) * 22 + Math.sin(x / 97) * 7]);
  }
  const d = `M0 ${H} L0 ${f(pts[0][1])} ${pts.map(([x, y]) => `L${n(x)} ${f(y)}`).join('')} L${W} ${H} Z`;
  p(`<path d="${d}" fill="url(#gs)"/>`);

  /* Rippel im Sand: ohne sie liest sich der Grund als Tapete (REZEPT F). */
  const rip = [];
  for (let i = 0; i < 11; i++) {
    const y = rr(792, 890);
    const x0 = rr(-40, 900);
    const len = rr(220, 640);
    rip.push(
      `<path d="M${n(x0)} ${f(y)}q${f(len * 0.3)} ${f(rr(-9, -3))} ${f(len * 0.6)} ${f(rr(-2, 2))}t${f(len * 0.4)} ${f(rr(-4, 4))}" fill="none" stroke="${C.sandRippel}" stroke-opacity="${f(rr(0.16, 0.34))}" stroke-width="${f(rr(1.4, 3))}" stroke-linecap="round"/>`,
    );
  }
  p(rip.join(''));

  /* Das KAUSTIK-NETZ auf dem Sand. Es ist der Grund, warum das untere Drittel
     überhaupt eine Tonspreizung hat: helle Bänder mit dunklem Sand dazwischen,
     über eine große Fläche. Ein einzelner heller Punkt hebt ein Perzentil
     nicht; eine Fläche tut es. Physikalisch ist es dasselbe Netz wie oben an
     der Wasserhaut, nur unten angekommen — darum trägt es dieselbe Farbe und
     dieselben Uhren wie die Bahnen. */
  const kaust = [];
  for (let i = 0; i < 30; i++) {
    const y = rr(778, 898);
    const x0 = rr(-80, 1500);
    const len = rr(180, 460);
    const amp = rr(4, 13);
    const nah = (y - 778) / 120;
    kaust.push(
      `<path class="v${i % 9}" d="M${n(x0)} ${f(y)}q${f(len * 0.24)} ${f(-amp)} ${f(len * 0.5)} ${f(-amp * 0.2)}t${f(len * 0.5)} ${f(amp * 0.4)}" fill="none" stroke="${C.pfuetze}" stroke-opacity="${f(0.2 + nah * 0.34)}" stroke-width="${f(2.5 + nah * 8)}" stroke-linecap="round"/>`,
    );
  }
  p(kaust.join(''));

  /* Wo eine Bahn den Grund trifft, liegt eine Lichtpfütze — und sie atmet
     mit derselben Uhr wie die Bahn darüber. Das ist die Stelle, an der die
     Bewegung im unteren Drittel überhaupt sichtbar wird. */
  const pf = SHAFTS.map((s) => {
    const y = 802 + (s.x % 7) * 6;
    const cx = s.x + (y - 52) * LEAN;
    const rxx = s.w * 5.2;
    return `<ellipse class="v${s.k}" cx="${f(cx)}" cy="${n(y)}" rx="${f(rxx)}" ry="${f(rxx * 0.26)}" fill="url(#gp)"/>`;
  });
  p(pf.join(''));
}

/* ═══════════ 10. DAS WRACK ══════════════════════════════════════════════ */
/* Die benennbare Hauptfigur. Gebaut in lokalen Koordinaten und als Ganzes
   gekippt: Bug links oben, Heck rechts unten, dazwischen die aufgebrochene
   Mitte mit sieben freien Spanten. Der Mast ist gebrochen und zeigt nach
   links oben ins offene Wasser — er ist die Linie, die den Mittelgrund mit
   dem Vordergrund verbindet.

   Das Handwerk liegt in den KANTEN: alles Licht kommt von oben, also fängt
   jede nach oben zeigende Fläche einen hellen Saum. Ohne ihn wäre das Wrack
   ein Fleck (der Fehler von v1). */
{
  const g = [];
  /* Bugsektion. */
  g.push(
    `<path d="M0 0c8-30 44-52 96-60l104-8v92c-52 22-128 18-176-2z" fill="${C.rumpf}"/>`,
    `<path d="M0 0c8-30 44-52 96-60l104-8" fill="none" stroke="${C.kante}" stroke-opacity="0.85" stroke-width="4.5" stroke-linecap="round"/>`,
    `<path d="M14 6c10-24 42-42 88-49l98-7" fill="none" stroke="${C.kanteLeise}" stroke-opacity="0.62" stroke-width="2.6"/>`,
    `<path d="M2 4c10 22 44 40 92 46l104 8" fill="none" stroke="${C.kanteLeise}" stroke-opacity="0.34" stroke-width="2.4"/>`,
  );
  /* Die offene Luke: der dunkelste Punkt des Bildes. */
  g.push(`<path d="M96 -40l84-6v40l-84 8z" fill="${C.luke}"/>`);
  /* Aufgebrochene Mitte: sieben Spanten. */
  for (let i = 0; i < 7; i++) {
    const x = 206 + i * 22;
    const top = -66 + i * 3.2;
    const bot = 26 + i * 2.4;
    g.push(
      `<path d="M${n(x)} ${f(bot)}C${n(x - 12)} ${f(bot - 40)} ${n(x - 10)} ${f(top + 26)} ${n(x + 2)} ${f(top)}" fill="none" stroke="${C.spant}" stroke-width="9" stroke-linecap="round"/>`,
      `<path d="M${n(x + 3)} ${f(top + 8)}C${n(x - 6)} ${f(top + 30)} ${n(x - 8)} ${f(bot - 42)} ${n(x - 1)} ${f(bot - 14)}" fill="none" stroke="${C.kante}" stroke-opacity="0.6" stroke-width="2.8" stroke-linecap="round"/>`,
    );
  }
  /* Kielbalken unter der offenen Mitte. */
  g.push(`<path d="M188 28l176 12v22l-176-14z" fill="${C.rumpf}"/>`,
    `<path d="M188 28l176 12" fill="none" stroke="${C.kanteLeise}" stroke-opacity="0.46" stroke-width="2.6"/>`);
  /* Hecksektion. */
  g.push(
    `<path d="M360 -54l84 8c28 6 40 24 34 44c-10 26-58 42-118 40z" fill="${C.rumpfHell}"/>`,
    `<path d="M360 -54l84 8c28 6 40 24 34 44" fill="none" stroke="${C.kante}" stroke-opacity="0.8" stroke-width="4.2" stroke-linecap="round"/>`,
    `<path d="M366 -20l104 12" fill="none" stroke="${C.rumpf}" stroke-opacity="0.7" stroke-width="4"/>`,
  );
  /* Der gebrochene Mast plus Rah. */
  g.push(
    `<path d="M246 -60L142 -300l16-4L268 -62z" fill="${C.rumpf}"/>`,
    `<path d="M246 -60L142 -300" fill="none" stroke="${C.kante}" stroke-opacity="0.78" stroke-width="3.4" stroke-linecap="round"/>`,
    `<path d="M170 -238l122 26" fill="none" stroke="${C.spant}" stroke-width="6" stroke-linecap="round"/>`,
    `<path d="M170 -238l122 26" fill="none" stroke="${C.kante}" stroke-opacity="0.62" stroke-width="2.4"/>`,
  );
  /* Ein Tau, das vom Mast zum Heck durchhängt. */
  g.push(
    `<path d="M158 -284C240 -150 340 -110 462 -22" fill="none" stroke="${C.tau}" stroke-opacity="0.7" stroke-width="2.4"/>`,
  );
  p(
    `<ellipse cx="1082" cy="806" rx="250" ry="30" fill="${C.w9}" fill-opacity="0.66" filter="url(#fb)"/>`,
    `<g transform="translate(838 778) rotate(-7) scale(1.13)">${g.join('')}</g>`,
  );
}

/* ═══════════ 11. DER TANGWALD (Mittel- und Vordergrund) ═════════════════ */
{
  const mid = [];
  for (let i = 0; i < 30; i++) {
    const links = i % 2 === 0;
    const x = links ? rr(150, 470) : rr(1130, 1470);
    const base = rr(766, 812);
    mid.push(
      tang(x, base, rr(200, 380), rr(12, 25), C.tangMitte, rr(0.72, 0.95), `t${i % 8}`, rr(-88, 88)),
    );
  }
  p(mid.join(''));

  /* Das MITTELFELD. Genau hier hatte v1 sein Loch: der Tang stand in den
     äußeren 20 %, die Mitte war offenes Wasser, und weil die Lesespalte
     ebendort liegt, sah man hinter dem Text nichts. Regie-Regel 1 verlangt
     das Gegenteil — die Motive laufen HINTER der Spalte durch. Dieser Gürtel
     ist niedriger als der Wald an den Rändern (er soll die Spalte nicht
     erklimmen), aber er ist da, er ist dunkel, und er wiegt sich mit. */
  const gurt = [];
  for (let i = 0; i < 19; i++) {
    const x = i % 6 === 0 ? rr(1216, 1330) : rr(408, 812);
    const base = rr(792, 848);
    gurt.push(
      tang(
        x,
        base,
        rr(110, 250),
        rr(10, 21),
        i % 3 === 0 ? C.tangNah : C.tangMitte,
        rr(0.7, 1),
        `t${(i + 5) % 8}`,
        rr(-52, 52),
      ),
    );
  }
  p(gurt.join(''));

  const nah = [];
  for (let i = 0; i < 26; i++) {
    const links = i % 2 === 0;
    const x = links ? rr(-30, 300) : rr(1392, 1660);
    const base = rr(806, 892);
    const h = rr(300, 560);
    const bow = rr(-118, 118);
    nah.push(tang(x, base, h, rr(15, 33), C.tangNah, 1, `t${(i + 3) % 8}`, bow));
    /* Ein Lichtsaum auf der SONNENSEITE des Blattes (links oben). */
    nah.push(
      `<path class="t${(i + 3) % 8}" style="transform-origin:${n(x)}px ${n(base)}px" d="M${f(x - 3)} ${f(base - 10)}Q${f(x + bow * 0.42 - 5)} ${f(base - h * 0.52)} ${f(x + bow)} ${f(base - h + 4)}" fill="none" stroke="${C.tangKante}" stroke-opacity="${f(rr(0.18, 0.4))}" stroke-width="1.8" stroke-linecap="round"/>`,
    );
  }
  p(nah.join(''));
}

/* ═══════════ 12. DIE FELSEN ═════════════════════════════════════════════ */
{
  p(
    `<path d="M-20 900L-20 794l76-46 88 28 54-24 76 50-26 56-84 42z" fill="${C.felsNah}"/>`,
    `<path d="M-20 794l76-46 88 28 54-24 76 50" fill="none" stroke="${C.felsKante}" stroke-opacity="0.5" stroke-width="3.4" stroke-linecap="round"/>`,
    `<path d="M56 748l30 152M144 776l-16 124" fill="none" stroke="${C.felsKante}" stroke-opacity="0.16" stroke-width="2"/>`,
    `<path d="M1620 900L1620 782l-70-24-58 44-64 12-12 42z" fill="${C.felsNah}"/>`,
    `<path d="M1620 782l-70-24-58 44-64 12" fill="none" stroke="${C.felsKante}" stroke-opacity="0.46" stroke-width="3" stroke-linecap="round"/>`,
    `<path d="M486 900l34-30 62-16 74 8 62 22 44 16z" fill="${C.felsNah}" fill-opacity="0.92"/>`,
    `<path d="M486 900l34-30 62-16 74 8 62 22 44 16" fill="none" stroke="${C.felsKante}" stroke-opacity="0.4" stroke-width="2.8" stroke-linecap="round"/>`,
  );
}

/* ═══════════ 13. DIE AUFSTEIGENDEN LICHTPUNKTE ══════════════════════════ */
/* Sie stehen VOR allem anderen — das ist der Trick, mit dem ein Bild „unter
   Wasser" statt „vor einer Tapete" wird: etwas schwebt zwischen Auge und
   Szene. Nahe an einer Lichtbahn sind sie hell, sonst leise. */
{
  const arr = [];
  for (let i = 0; i < 96; i++) {
    const x = rr(-20, W + 20);
    const y = rr(132, 892);
    const nahBahn = SHAFTS.some((s) => Math.abs(s.x + (y - 52) * LEAN - x) < s.w * 2.2 && y < s.yb);
    const r = nahBahn ? rr(1.4, 3.2) : rr(0.9, 2.2);
    const c = nahBahn ? C.punkt : C.punktLeise;
    const op = nahBahn ? rr(0.5, 0.9) : rr(0.2, 0.46);
    arr.push(
      `<circle class="b${i % 10}" cx="${n(x)}" cy="${n(y)}" r="${f(r)}" fill="${c}" fill-opacity="${f(op)}"/>`,
    );
  }
  p(arr.join(''));
}

/* ═══════════ 14. DER VORDERE BAHNEN-PASS ════════════════════════════════ */
/* Licht ist volumetrisch: es liegt auch VOR dem Wrack, nicht nur dahinter.
   Fünf sehr leise Keile über allem — sie binden das Bild zusammen und sind
   der Grund, warum sich beim Atmen die ganze Fläche mitbewegt und nicht nur
   das offene Wasser. */
{
  /* SIE ENDEN VOR DEM GRUND (y 620). Die erste Fassung ließ sie bis y 898
     laufen, und die Messung hat es sofort gezeigt: das 5. Perzentil der
     Lesespalte rührte sich nicht mehr, egal wie tief die Wasserstufen
     gesetzt wurden. Der Grund war dieser Pass — ein Dunstschleier über dem
     GANZEN Bild hebt jede Schwärze mit an und nimmt dem unteren Drittel
     genau die Tiefe, für die er gedacht war. Es ist der Fehler „Luftglühen ×
     Schleier multiplizieren sich" (Regie-Lektion 3) im Kleinen, und die
     Antwort ist dieselbe wie dort: die Kette kürzen. */
  const arr = [0, 3, 5, 7, 8].map((idx) => {
    const s = SHAFTS[idx];
    return `<g class="v${s.k}" opacity="0.36"><path d="${shaftPath(s.x, s.w, 620, 2.1)}" fill="url(#gb)"/></g>`;
  });
  p(arr.join(''));
}

/* ═══════════ 15. DER HERZSCHLAG ═════════════════════════════════════════ */
/* Alle Uhren stehen IM SVG (nur so treibt virtual-time sie). Perioden sind
   paarweise nicht verhältnisgleich, damit sich das Bild praktisch nie
   wiederholt; negative Verzögerungen streuen die Phasen.

   JEDE Keyframe beginnt bei opacity 1 und geht nur runter: der gemalte
   Zustand IST der hellste, also ist der eingefrorene Zustand zugleich der
   Kontrast-Worst-Case. */
const css = [];
css.push(
  '.bahnen g,.ry,.sh1,.sh2{will-change:transform,opacity}',
  '@keyframes atem{from{opacity:1}to{opacity:.46}}',
  '@keyframes wieg{from{transform:rotate(-2.6deg)}to{transform:rotate(2.6deg)}}',
  '@keyframes steig{from{transform:translateY(0);opacity:1}to{transform:translateY(-132px);opacity:0}}',
  '@keyframes netzA{from{transform:translateX(-26px)}to{transform:translateX(26px)}}',
  '@keyframes zug{from{transform:translateX(-34px)}to{transform:translateX(34px)}}',
);
/* Bahnen: Atem (19…43 s) plus seitliches Wandern (27…47 s). Das Wandern
   liegt auf einer eigenen Keyframe je Amplitude, damit die breiten Bahnen
   weiter schwingen als die schmalen. */
const AMPL = [9, 15, 22];
AMPL.forEach((a, i) =>
  css.push(`@keyframes wander${i}{from{transform:translateX(-${a}px)}to{transform:translateX(${a}px)}}`),
);
const SHAFT_CLOCK = [
  [19, 27, 0, -5, -11],
  [23, 33, 2, -13, -3],
  [26, 29, 1, -2, -19],
  [29, 41, 2, -17, -7],
  [31, 31, 0, -9, -23],
  [34, 47, 2, -21, -1],
  [37, 37, 1, -4, -15],
  [41, 43, 2, -25, -9],
  [43, 35, 0, -14, -27],
];
SHAFT_CLOCK.forEach(([pa, pw, amp, da, dw], i) => {
  css.push(
    `.v${i}{animation:atem ${pa}s ease-in-out ${da}s infinite alternate,wander${amp} ${pw}s ease-in-out ${dw}s infinite alternate}`,
  );
});
/* Das Lichtnetz: sechs Uhren, 21…33 s. */
[21, 24, 26, 29, 31, 33].forEach((s, i) =>
  css.push(`.n${i}{animation:netzA ${s}s ease-in-out -${3 + i * 4}s infinite alternate}`),
);
/* Der Tang: acht Uhren, 13…27 s. Er wiegt sich am schnellsten, weil er das
   Einzige ist, was man wirklich als BEWEGUNG erkennen soll. */
[13, 15, 17, 19, 21, 23, 25, 27].forEach((s, i) =>
  css.push(`.t${i}{animation:wieg ${s}s ease-in-out -${2 + i * 3}s infinite alternate}`),
);
/* Die Lichtpunkte: zehn Uhren, 22…38 s, LINEAR und ohne alternate — sie
   steigen, sie pendeln nicht. */
[22, 24, 26, 28, 30, 32, 34, 35, 37, 38].forEach((s, i) =>
  css.push(`.b${i}{animation:steig ${s}s linear -${i * 3 + 1}s infinite}`),
);
/* Die Schwärme und der Rochen: die langsamsten Uhren im Bild. */
css.push(
  '.sh1{animation:zug 61s ease-in-out -14s infinite alternate}',
  '.sh2{animation:zug 37s ease-in-out -6s infinite alternate}',
  '.ry{animation:zug 53s ease-in-out -25s infinite alternate}',
  '@media(prefers-reduced-motion:reduce){*{animation:none!important}}',
);
p(`<style>${css.join('')}</style>`);
p('</svg>');

const svg = out.join('');
if (svg.length > BUDGET) {
  console.error(`Budget gerissen: ${svg.length} B > ${BUDGET} B`);
  process.exit(1);
}
writeFileSync(OUT, svg);
console.log(`aoi-szene.svg  ${svg.length} B  (${((svg.length / BUDGET) * 100).toFixed(1)} % vom Budget)`);
