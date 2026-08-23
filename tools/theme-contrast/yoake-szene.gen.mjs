/**
 * YOAKE — 夜明け, „Tagesanbruch". Der Szenen-Generator.
 * ═══════════════════════════════════════════════════════════════════════════
 * Gebaut nach REZEPT-theme-szenen-generator-2026-08-20.md (destilliert aus
 * nagareboshi v2) und der REGIE v2. Was diese Datei ausspuckt, ist EIN Bild:
 *
 *   Der Übergang. Nacht oben, Tag unten. Ein glühender Horizont hinter einer
 *   Stadt aus Ziegeldächern, in der die ersten Fenster angehen, und das Licht
 *   der aufgehenden Sonne flutet in breiten Bahnen über die ganze Bühne.
 *
 * ABGRENZUNG (ausdrücklich bestellt): nagareboshi ist die TIEFE Nacht. Yoake ist
 * ihr ENDE. Deshalb liegen hier nur noch sieben Sterne, allesamt links oben, wo
 * die Nacht am längsten hält — und sie verlöschen. Alles andere im Bild gehört
 * schon dem Tag.
 *
 * DIE FEHLER, GEGEN DIE HIER GEBAUT WIRD (v1, Andi-Verdikt „echt nicht gut"):
 * die erste Fassung war ein dünner Dämmerstreifen über Leere — 8 KB, ein
 * einziges Höhenband am unteren Rand, Mitte tot, und über der Lesespalte auf
 * 9 % maskiert, also gar nicht vorhanden. Diese Fassung besetzt jede Bildzeile:
 * Sternrest oben, Wolkenbänke im oberen Drittel, Lichtbahnen quer über alles,
 * Bergkette und ferne Stadt am Horizont, drei Dachreihen im Mittelgrund, ein
 * naher Dachfirst mit Antennen und Oberleitung im Vordergrund.
 *
 * DIE UMKEHRUNG GEGENÜBER DER NACHT: bei nagareboshi wird jede Ebene nach vorn
 * DUNKLER, weil im Dunkeln nur der Himmel Licht hat. Bei Tagesanbruch gilt die
 * echte Luftperspektive: was weit weg ist, ist HELLER (es liegt mehr leuchtende
 * Luft davor), was nah ist, wird zur Silhouette. Die Tonleiter läuft also von
 * L 0.50 (ferne Kette) auf L 0.168 (Vordergrund) — 0,33 Spannweite statt der
 * 0,14 der Nacht. Gespreizt, wie die Regie es verlangt.
 *
 * Fester Startwert ⇒ derselbe Lauf liefert bitgleich dasselbe Bild.
 *
 *   node tools/theme-contrast/yoake-szene.gen.mjs
 *   → frontend/public/themes/yoake-szene.svg
 */

import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', '..', 'frontend', 'public', 'themes', 'yoake-szene.svg');

/* ── Zufall mit Gedächtnis ───────────────────────────────────────────────── */

/** mulberry32 — kurz, gut genug, und vor allem reproduzierbar. */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const R = rng(0x1f6a2c05);
const between = (lo, hi) => lo + R() * (hi - lo);

/* ── OKLCH → sRGB, mit Riegel ────────────────────────────────────────────────
   Themen-CSS und Zeichnung kommen aus DERSELBEN Zahl. Liegt ein Kanal außerhalb
   des Gamuts, wird abgebrochen statt geklippt — Klippen verschiebt den FARBTON,
   und genau das fällt niemandem auf, weil die Zahl im Quelltext ja stimmt. */
function ok(L, C, h) {
  const a = C * Math.cos((h * Math.PI) / 180);
  const b = C * Math.sin((h * Math.PI) / 180);
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const [l, m, s] = [l_ ** 3, m_ ** 3, s_ ** 3];
  const lin = [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
  if (lin.some((v) => v < -0.0005 || v > 1.0005)) {
    throw new Error(`oklch(${L} ${C} ${h}) liegt ausserhalb des sRGB-Gamuts — Chroma senken`);
  }
  return (
    '#' +
    lin
      .map((v) => {
        const c = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(Math.max(v, 0), 1 / 2.4) - 0.055;
        return Math.round(Math.min(1, Math.max(0, c)) * 255)
          .toString(16)
          .padStart(2, '0');
      })
      .join('')
  );
}

/* ── DIE PALETTE DES ÜBERGANGS ───────────────────────────────────────────────
   Der Himmel läuft in NEUN Stufen von Indigo nach Orange. Neun statt der
   üblichen vier, weil genau das die Bestellung ist: „Orange → Rosa → Violett →
   Indigo, Tonstufen GESPREIZT". Vier Stopps über dieselbe Strecke ergeben
   Bänder mit sichtbaren Knicken; neun ergeben einen Verlauf, in dem das Auge
   keine Kante findet und trotzdem VIER Farben benennen kann:
     #391a5a Indigo · #843077 Violett · #dd556f Rosa · #fea065 Orange.

   GESPREIZT WIRD AUCH AUF DER FARBACHSE, nicht nur auf der Helligkeitsachse.
   Der erste Wurf war perzeptuell sauber gestuft (Chroma 0.085…0.12) und am
   gerenderten Bild trotzdem ein mattes Mauve. Der Grund ist derselbe wie bei
   nagareboshis Tonleiter: unter dem Schleier bleiben rund 30 % übrig, und 30 %
   von „dezent" ist nichts. Wer im Ergebnis Farbe will, muss VORHER mehr davon
   hinstellen, als die Rechnung nahelegt — die Chroma-Werte liegen jetzt bei
   0.11…0.17 und damit dicht am sRGB-Rand (der Riegel in `ok()` bewacht ihn).

   Untergrenze für ALLES: --bg-base steht auf oklch(0.155 0.042 292). Kein Fill
   darunter — sonst zeichnet der Schleier über der Lesespalte ein sichtbar
   aufgehelltes Rechteck genau dort, wo Text steht. Der dunkelste Wert hier ist
   `near` auf L 0.168. */
const C = {
  sky0: ok(0.225, 0.06, 286), //  ganz oben: die Nacht, die noch nicht weg ist
  sky1: ok(0.26, 0.08, 292),
  sky2: ok(0.3, 0.11, 302), //  Indigo kippt in Violett
  sky3: ok(0.375, 0.13, 316),
  sky4: ok(0.455, 0.145, 334), //  Violett kippt in Rosa
  sky5: ok(0.545, 0.15, 350),
  sky6: ok(0.635, 0.17, 12), //  Rosa kippt in Koralle
  sky7: ok(0.72, 0.165, 34),
  sky8: ok(0.79, 0.135, 52), //  Horizont: Orange

  glutHot: ok(0.9, 0.078, 74), //  der Kern der Glut, das Hellste im Bild
  glutWarm: ok(0.76, 0.145, 52),
  glutRose: ok(0.68, 0.16, 14),
  glutViolet: ok(0.55, 0.145, 332),

  wolkeHigh: ok(0.43, 0.075, 316), //  Zirren im oberen Drittel: HELLER als der
  //                                     Himmel dort, sonst ist das obere Bilddrittel
  //                                     eine schwarze Fläche mit Sternen darin
  wolkeDark: ok(0.335, 0.07, 312), //  Wolkenbank von unten unbeleuchtet
  wolkeMid: ok(0.46, 0.09, 340),
  wolkeLit: ok(0.66, 0.145, 22), //  ihr angeleuchteter Bauch
  wolkeRim: ok(0.86, 0.085, 56), //  die Kante, die die Sonne trifft

  /* Die Luftperspektive-Leiter. Von hinten nach vorn DUNKLER — das ist die
     Umkehrung gegenüber der Nacht, und sie ist der Grund, warum dieses Bild
     Tiefe hat, ohne dass irgendwo Nebel gemalt werden müsste. */
  ridgeFar: ok(0.5, 0.055, 318), //  ferne Kette, fast im Himmel aufgelöst
  ridgeMid: ok(0.4, 0.055, 308),
  stadtFar: ok(0.335, 0.05, 300), //  ferne Blöcke am Horizont
  roofFar: ok(0.3, 0.048, 298), //  Dachreihe A
  wallFar: ok(0.265, 0.045, 296),
  roofMid: ok(0.245, 0.045, 294), //  Dachreihe B
  wallMid: ok(0.212, 0.042, 293),
  roofNear: ok(0.2, 0.042, 292), //  Dachreihe C
  wallNear: ok(0.175, 0.04, 292),
  near: ok(0.168, 0.04, 292), //  Vordergrund: Silhouette, knapp über bg-base

  /* GLAS SPIEGELT. Der erste Wurf hat unbeleuchtete Fenster DUNKLER als die
     Wand gemalt — die naheliegende Annahme („da ist kein Licht"), und am Bild
     der Grund dafür, dass die großen nahen Häuser schwarze Rechtecke waren:
     Wand L 0.175 und Fenster L 0.168 sind für das Auge dieselbe Fläche. Eine
     Scheibe bei Dämmerung ist aber keine Öffnung, sie ist ein SPIEGEL, und was
     sie spiegelt, ist der hellste Gegenstand am Himmel. Also liegt jedes dunkle
     Fenster ÜBER seiner Wand — und plötzlich hat jede Wand Fenster. */
  glassFar: ok(0.345, 0.05, 300),
  glassMid: ok(0.288, 0.048, 298),
  glassNear: ok(0.246, 0.046, 296),

  /* Kanten fangen Himmelslicht. Ohne sie ist ein Dach eine Fläche; mit ihnen
     ist es ein Dach. Jede Reihe bekommt ihre eigene, weil eine gemeinsame
     Kantenfarbe die Tiefenleiter wieder einebnen würde. */
  rimFar: ok(0.62, 0.075, 340),
  rimMid: ok(0.5, 0.08, 350),
  rimNear: ok(0.4, 0.085, 358),
  rimFore: ok(0.3, 0.055, 320),

  /* DAS HELLSTE IM BILD MUSS DIE SONNE SEIN. Der erste Wurf hatte die Sterne
     auf L 0.95 — fast weiß, so hell wie ein Stern eben ist. Die Messung hat
     sie sofort gefunden: schlechtester Bildpunkt der Lesespalte #4c4a5a bei
     3,70:1, und er war BLAUGRAU, also weder Glut noch Fenster, sondern ein
     Sternpunkt unter dem Schleier. Drei Bildpunkte unter einem Glyphen, und
     niemand hätte es je gesehen.
     Sie stehen jetzt auf L 0.76. Das ist keine Notbremse, sondern das
     richtigere Bild: dieses Thema ist das ENDE der Nacht, seine Sterne sind
     am Verlöschen, und der hellste Gegenstand am Himmel gehört dem Tag. */
  stern: ok(0.76, 0.022, 286), //  was von der Nacht übrig ist — und geht
  sternDim: ok(0.72, 0.025, 288),

  /* GENAU EIN GOLD. `--accent` steht in yoake.css auf oklch(0.775 0.135 38);
     dieselbe Tripel, kein zweites getuntes Warm. Zwei Golds driften auseinander
     und niemand merkt es, bis die Fenster neben dem Sprechen-Knopf falsch
     aussehen. Kern und Hof sind Ableitungen DESSELBEN Tons. */
  gold: ok(0.775, 0.135, 38),
  goldDeep: ok(0.66, 0.15, 34),
  goldGlow: ok(0.8, 0.12, 44),
};

/* ── Geometrie der Bühne ─────────────────────────────────────────────────────
   1600×1000, wie bei den beiden angenommenen Nachbarn — nur so sind die Bilder
   in der Galerie vergleichbar. `cover` + `center bottom` schneidet OBEN ab; die
   Horizontlinie bei y=700 (70 %) steht deshalb in JEDEM Fensterformat, und der
   Himmel darüber verliert nur das, was die Themen-CSS ohnehin fortsetzt. */
const W = 1600;
const H = 1000;
const HORIZON = 700;
const SAFE = { x0: 140, x1: 1460, y0: 120 };

/** Wo die Sonne steht — noch UNTER dem Horizont, hinter der Stadt. Alles Licht
    im Bild rechnet gegen diesen einen Punkt: die Bahnen fächern von ihm auf,
    die Wolkenbäuche leuchten zu ihm hin, die Glut sitzt um ihn. */
const SUN = { x: 1096, y: 668 };

const out = [];
const put = (s) => out.push(s);
const n = (v) => Math.round(v);
const f = (v) => Math.round(v * 10) / 10;

/** Jedes brennende Fenster; am Ende steht die Zahl im Log. */
const lights = [];

/* Ein weicher Kurvenzug durch Stützpunkte. Quadratische Segmente zwischen den
   Mittelpunkten — ein Trick, der aus fünf Zahlen eine Wolkenkante macht, ohne
   je einen Kontrollpunkt von Hand zu setzen. */
const smooth = (pts) => {
  let d = `${n(pts[0][0])},${n(pts[0][1])}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const mx = (pts[i][0] + pts[i + 1][0]) / 2;
    const my = (pts[i][1] + pts[i + 1][1]) / 2;
    d += `Q${n(pts[i][0])},${n(pts[i][1])} ${n(mx)},${n(my)}`;
  }
  d += `L${n(pts.at(-1)[0])},${n(pts.at(-1)[1])}`;
  return d;
};

/* ═══════════════════════════════════════════════════════════════════════════
   1) DER HIMMEL — neun Stufen von Indigo nach Orange
   ═══════════════════════════════════════════════════════════════════════════ */
put(`<rect width="${W}" height="${H}" fill="url(#himmel)"/>`);

/* Die Sonne selbst steht unter dem Horizont, aber ihr Hof steht darüber: eine
   sehr breite, sehr flache Ellipse. Sie ist der Grund, warum der Himmel rechts
   heller ist als links — und damit der Grund, warum das Bild eine Richtung hat. */
put(
  `<ellipse cx="${SUN.x}" cy="${SUN.y}" rx="820" ry="330" fill="url(#hof)"/>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   3) DIE LETZTEN STERNE — links oben, wo die Nacht am längsten hält
   ═══════════════════════════════════════════════════════════════════════════
   Nur sieben, und alle im linken oberen Viertel. Das ist die Abgrenzung zu
   nagareboshi als BILD statt als Behauptung: dort ist der Himmel voll, hier ist
   er fast leer geräumt. Sie verlöschen auf drei eigenen Uhren, und weil auch
   sie nur nach unten laufen, ist „alle sieben stehen" der gemessene Fall. */
[
  [118, 96, 2.6, 0],
  [246, 168, 2.0, 1],
  [88, 236, 1.7, 2],
  [62, 148, 2.2, 1],
  [186, 322, 1.5, 0],
  [148, 62, 1.8, 2],
  [300, 268, 1.3, 1],
].forEach(([x, y, r, cl]) => {
  put(
    `<g class="s${cl}">` +
      `<ellipse cx="${x}" cy="${y}" rx="${f(r * 5)}" ry="${f(r * 5)}" fill="url(#sternhof)"/>` +
      `<circle cx="${x}" cy="${y}" r="${f(r)}" fill="${C.stern}"/>` +
      `</g>`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   4) DIE WOLKENBÄNKE — der Mittelgrund DES HIMMELS
   ═══════════════════════════════════════════════════════════════════════════
   Der Vorwurf „Randstreifen um ein Loch" gilt auch für den Himmel: ein reiner
   Verlauf ist eine leere Fläche, egal wie schön er ist. Also liegen hier neun
   lange Streifenwolken über die ganze Breite, in drei Tiefen. Jede hat einen
   ANGELEUCHTETEN BAUCH — die Sonne steht unter ihnen, also ist ihre Unterkante
   hell und ihr Rücken dunkel. Diese eine Umkehrung (unten hell, oben dunkel)
   ist der ganze Unterschied zwischen „Dämmerungswolke" und „grauer Fleck". */
function wolke(cx, cy, w, h, body, lit, rim, op, cls) {
  const half = w / 2;
  const steps = 7;
  const top = [];
  const bot = [];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const x = cx - half + w * t;
    const bulge = Math.sin(Math.PI * t) ** 0.55;
    top.push([x, cy - h * bulge * between(0.55, 1.05)]);
    bot.push([x, cy + h * bulge * between(0.12, 0.34)]);
  }
  const d = `M${smooth(top)}L${smooth([...bot].reverse())}Z`;
  const s = [
    `<g class="${cls}" opacity="${op}">`,
    `<path d="${d}" fill="${body}"/>`,
    // Der Bauch: dieselbe Unterkante, nur ein Stück nach oben versetzt gefüllt.
    `<path d="M${smooth(bot.map(([x, y]) => [x, y - h * 0.1]))}L${smooth([...bot].reverse())}Z" fill="${lit}" opacity="0.85"/>`,
    `<path d="M${smooth(bot)}" fill="none" stroke="${rim}" stroke-width="${f(between(1.6, 2.8))}" opacity="0.8"/>`,
    `</g>`,
  ];
  return s.join('');
}

// Hinterste Lage: hoch, breit, blass — sie gehört fast noch zum Himmel.
[
  [420, 214, 760, 30],
  [1210, 172, 700, 26],
  [820, 300, 900, 34],
  [220, 118, 620, 20],
  [1320, 250, 640, 22],
  [700, 78, 720, 18],
].forEach(([cx, cy, w, h], i) =>
  put(wolke(cx, cy, w, h, C.wolkeHigh, C.wolkeMid, C.rimFar, 0.62, `c${i % 3}`)),
);

// Mittlere Lage: die Bänke, die das Bild in der Höhe gliedern.
[
  [300, 396, 820, 40],
  [1140, 372, 880, 36],
  [700, 470, 940, 44],
].forEach(([cx, cy, w, h], i) =>
  put(wolke(cx, cy, w, h, C.wolkeDark, C.wolkeLit, C.wolkeRim, 0.72, `c${(i + 1) % 3}`)),
);

// Vorderste Lage: dicht über dem Horizont, am stärksten angeleuchtet — hier
// liegt die Sonne fast in der Wolke, deshalb die volle Randkante.
[
  [520, 556, 900, 34],
  [1230, 528, 820, 30],
  [900, 604, 1000, 26],
].forEach(([cx, cy, w, h], i) =>
  put(wolke(cx, cy, w, h, C.wolkeMid, C.wolkeLit, C.wolkeRim, 0.8, `c${(i + 2) % 3}`)),
);

/* ═══════════════════════════════════════════════════════════════════════════
   5) DIE VÖGEL — der erste Flug des Tages
   ═══════════════════════════════════════════════════════════════════════════
   Zwölf Striche, zusammen keine 900 Byte, und sie tun etwas, das keine Fläche
   kann: sie geben dem leeren oberen Himmelsdrittel einen Maßstab. Ein Vogel ist
   klein, also ist der Himmel groß. */
put(`<g fill="none" stroke="${C.ridgeMid}" stroke-width="2" stroke-linecap="round">`);
[
  [612, 236, 1],
  [648, 214, 0.85],
  [690, 244, 0.9],
  [672, 186, 0.7],
  [726, 210, 0.75],
  [1372, 296, 0.9],
  [1418, 274, 0.7],
  [1330, 268, 0.65],
  [214, 452, 0.8],
  [268, 430, 0.6],
  [1046, 158, 0.75],
  [1094, 180, 0.6],
].forEach(([x, y, k]) => {
  const a = 11 * k;
  put(
    `<path d="M${n(x - a * 2)},${n(y)}q${f(a)},${f(-a * 0.85)} ${f(a * 2)},0q${f(a)},${f(-a * 0.85)} ${f(a * 2)},0"/>`,
  );
});
put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   6) DIE FERNE KETTE — zwei Bergrücken, in Luft aufgelöst
   ═══════════════════════════════════════════════════════════════════════════
   Midpoint-Displacement, gedämpft mit 0.58 statt 0.5: die hohen Frequenzen
   überleben länger, der Kamm bleibt zackig statt weich. Beide Ketten sind
   HELLER als alles davor — das ist Luftperspektive bei Tag, und sie ist der
   Grund, warum der Horizont hinter der Stadt wirklich weit weg wirkt. */
function ridgeLine(y0, amp, steps, tilt = 0, damp = 0.58) {
  let pts = [
    [-40, y0 + between(-amp, amp) * 0.4],
    [W + 40, y0 + tilt + between(-amp, amp) * 0.4],
  ];
  for (let s = 0; s < steps; s++) {
    const next = [pts[0]];
    for (let i = 1; i < pts.length; i++) {
      const [ax, ay] = pts[i - 1];
      const [bx, by] = pts[i];
      next.push([(ax + bx) / 2, (ay + by) / 2 + between(-amp, amp)]);
      next.push([bx, by]);
    }
    pts = next;
    amp *= damp;
  }
  return pts;
}
const asLine = (pts) => pts.map(([x, y]) => `${n(x)},${n(y)}`).join('L');
const ridgePath = (pts, fill) =>
  `<path d="M-40,${H + 10}L${asLine(pts)}L${W + 40},${H + 10}Z" fill="${fill}"/>`;

const rFar = ridgeLine(524, 76, 5, -36);
const rMid = ridgeLine(582, 44, 5, 26);

put(ridgePath(rFar, C.ridgeFar));
/* Die Kammlinie der fernen Kette bekommt Sonnenlicht — sie ist die einzige
   Kante im Bild, hinter der die Glut direkt steht. */
put(
  `<path d="M${asLine(rFar)}" fill="none" stroke="${C.wolkeRim}" stroke-width="2.4" opacity="0.55"/>`,
);
put(ridgePath(rMid, C.ridgeMid));
put(
  `<path d="M${asLine(rMid)}" fill="none" stroke="${C.rimFar}" stroke-width="1.8" opacity="0.6"/>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   DAS GLUTBAND — der glühende Horizont, und der Grund für die Malreihenfolge
   ═══════════════════════════════════════════════════════════════════════════
   ES STEHT HIER UND NICHT WEITER OBEN, und das ist der eine Befund der eigenen
   Sichtung, der dieses Bild gerettet hat: eine Kammlinie füllt nach UNTEN (von
   ihrem Grat bis zum Bildrand, sonst wäre sie kein Berg, sondern ein Strich).
   Beide Ketten deckten damit exakt das Band zu, in dem die warmen Himmelsstufen
   liegen — im ersten Wurf war der ganze Horizont ein mattes Violett, und die
   Orange-Stufen des Verlaufs waren zwar da, aber unter einer Bergflanke und
   drei Dachreihen begraben. Man kann nicht messen, ob eine Farbe leuchtet: man
   muss das Bild ansehen.

   Also liegt die Glut jetzt ÜBER den Ketten und UNTER der Stadt. Das ist kein
   Trick, es ist Physik: der Dunst über dem Horizont wird von hinten angestrahlt
   und wäscht alles aus, was in ihm steht. Die Berge verlieren dadurch ihren Fuß
   und behalten ihren Grat — genau so sieht eine Kette gegen die aufgehende
   Sonne aus. */
put(`<rect x="0" y="560" width="${W}" height="200" fill="url(#glutband)"/>`);
put(`<ellipse cx="${SUN.x}" cy="${SUN.y}" rx="360" ry="132" fill="url(#kern)"/>`);
/* ═══════════════════════════════════════════════════════════════════════════
   2) DIE WANDERNDE GLUT — der eine Herzschlag, den man nicht übersehen kann
   ═══════════════════════════════════════════════════════════════════════════
   Bestellt war: „der Verlauf wandert SICHTBAR langsam". Ein Verlauf lässt sich
   in einem per background-image geladenen SVG nicht verschieben (transform auf
   einem Gradienten wäre eine SMIL-Attributanimation, und die Regel der Regie
   heißt transform/opacity auf ELEMENTEN). Also wandert er anders, und zwar
   ehrlicher: SECHS breite Glutbänder liegen versetzt über dem Horizont, jedes
   auf einer eigenen langen Uhr. Während das eine verglimmt, steht das nächste —
   der Schwerpunkt des Leuchtens verschiebt sich dadurch tatsächlich über den
   Himmel, seitlich UND in der Höhe.

   Es ist außerdem die mit Abstand GRÖSSTE bewegte Fläche des Bildes: sechs
   Ellipsen von je ~600×200 Bildpunkten. Ein Sternfeld bewegt ein paar hundert
   Pixel, das hier bewegt Hunderttausende — um jeweils wenig. Genau das liest
   das Auge als „es dämmert", nicht als „da blinkt was".

   Jede Keyframe startet bei opacity:1 und geht NUR RUNTER (Regel des Rezepts):
   der gemalte Zustand ist der hellste, also ist der eingefrorene Zustand
   zugleich der Kontrast-Worst-Case. Das ist beweisbar, ohne die Uhr anhalten zu
   können. */
const GLUT = [
  [520, 648, 540, 150, 'glutRose', 0.55],
  [860, 632, 620, 175, 'glutWarm', 0.66],
  [1160, 660, 660, 155, 'glutHot', 0.44],
  [1420, 616, 540, 145, 'glutWarm', 0.44],
  [240, 664, 480, 130, 'glutViolet', 0.5],
  [1000, 566, 720, 125, 'glutRose', 0.38],
  [700, 656, 560, 140, 'glutWarm', 0.42],
];
GLUT.forEach(([cx, cy, rx, ry, tone, op], i) => {
  put(
    `<ellipse class="g${i}" cx="${cx}" cy="${cy}" rx="${rx}" ry="${ry}" fill="url(#glut${tone})" opacity="${op}"/>`,
  );
});


/** Höhe einer Kammlinie an der Stelle x. */
function heightAt(pts, x) {
  for (let i = 1; i < pts.length; i++) {
    if (pts[i][0] >= x) {
      const [ax, ay] = pts[i - 1];
      const [bx, by] = pts[i];
      return ay + ((by - ay) * (x - ax)) / (bx - ax || 1);
    }
  }
  return pts.at(-1)[1];
}

/* ═══════════════════════════════════════════════════════════════════════════
   7) DIE FERNE STADT — Türme und Blöcke am Fuß der Kette
   ═══════════════════════════════════════════════════════════════════════════
   Zwischen Bergkante (y≈650) und erster Dachreihe (y≈700) läge sonst ein leeres
   Band von fünfzig Bildzeilen quer durch das Bild — genau die Sorte Loch, die
   die Regie verbietet. Also steht dort die Stadt: flache Blöcke, ein paar
   Türme, ein Schornstein mit Fahne. Winzige Fenster, aber sie sind da. */
put(`<g>`);
let cx = -30;
while (cx < W + 30) {
  const bw = between(26, 78);
  const bh = between(18, 58);
  const by = HORIZON + between(2, 12);
  const top = by - bh;
  put(`<rect x="${n(cx)}" y="${n(top)}" width="${n(bw)}" height="${n(by - top)}" fill="${C.stadtFar}"/>`);
  put(
    `<path d="M${n(cx)},${n(top)}h${n(bw)}" stroke="${C.rimFar}" stroke-width="1.4" opacity="0.5"/>`,
  );
  // Ein paar der Blöcke bekommen Fenster — nicht alle, sonst wird es ein Raster.
  if (R() < 0.55) {
    const cols = Math.max(1, Math.floor(bw / 16));
    const rows = Math.max(1, Math.floor((by - top) / 20));
    for (let a = 0; a < cols; a++) {
      for (let b = 0; b < rows; b++) {
        if (R() > 0.34) continue;
        const wx = cx + 5 + a * 16;
        const wy = top + 8 + b * 20;
        if (wy > by - 8) continue;
        put(`<rect x="${n(wx)}" y="${n(wy)}" width="4" height="6" fill="${C.gold}" opacity="0.7"/>`);
      }
    }
  }
  // Gelegentlich ein Turm, der über die Silhouette hinausragt.
  if (R() < 0.14) {
    const tw = between(10, 20);
    const th = between(26, 62);
    put(
      `<rect x="${n(cx + bw / 2 - tw / 2)}" y="${n(top - th)}" width="${n(tw)}" height="${n(th + 4)}" fill="${C.stadtFar}"/>`,
      `<path d="M${n(cx + bw / 2)},${n(top - th)}v-${n(between(10, 26))}" stroke="${C.stadtFar}" stroke-width="2"/>`,
    );
  }
  cx += bw + between(-6, 16);
}
put(`</g>`);

/* Ein Dunstzug auf der Höhe der Stadt: er trennt Ferne von Mittelgrund, ohne
   dass irgendwo eine Linie gezogen werden müsste. */
put(
  `<ellipse cx="820" cy="${HORIZON + 6}" rx="1000" ry="22" fill="${C.rimFar}" opacity="0.2" filter="url(#weich)"/>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   8) DIE DÄCHER — das Hauptmotiv
   ═══════════════════════════════════════════════════════════════════════════
   Ein Machiya-Dach ist im Umriss vor allem ZIEGEL: zwei flache Schrägen, ein
   dicker Firstziegel obendrauf, und Traufen, die weit über die Wand hinaus
   stehen. Wand und Fenster sind klein dagegen. Der First und die Traufkante
   sind HELLER als die Dachfläche (sie zeigen zum Himmel), die Wand ist dunkler
   (sie steht im Schatten). Ohne dieses Gefälle bleibt jedes Haus ein Klecks —
   das war der Befund an der eigenen v1-Sichtung.

   `lit` sagt, ob in diesem Haus schon jemand wach ist. Die Fenster, die brennen,
   sind auf Uhren gelegt: gemalt sind sie AN (der hellste, also gemessene
   Zustand), und die Animation nimmt sie zeitweise weg. Zwischen zwei Frames
   gehen dadurch sichtbar einzelne Fenster an und andere aus — genau die
   Bestellung „einzelne Fenster gehen an", ohne die Kontrastregel zu brechen. */
function haus(x, baseY, w, T, lit, clock) {
  const wallH = w * 0.46;
  const roofH = w * 0.26;
  const eave = w * 0.14;
  const ry = baseY - wallH;
  const s = [];
  s.push(`<path d="M${n(x - w / 2)},${n(baseY)}h${n(w)}v${n(-wallH)}h${n(-w)}Z" fill="${T.wall}"/>`);
  // Die beiden Dachschrägen als EIN Pfad — ein Tag statt vier.
  s.push(
    `<path d="M${n(x - w / 2 - eave)},${n(ry + 5)}L${n(x - w * 0.06)},${n(ry - roofH)}` +
      `h${n(w * 0.12)}L${n(x + w / 2 + eave)},${n(ry + 5)}Z" fill="${T.roof}"/>`,
  );
  // Der Firstziegel: der helle Balken, an dem man ein Ziegeldach erkennt.
  s.push(
    `<path d="M${n(x - w * 0.1)},${n(ry - roofH)}h${n(w * 0.2)}v${Math.max(2, n(w * 0.035))}h${n(-w * 0.2)}Z" fill="${T.rim}"/>`,
  );
  // Die Traufkante fängt Himmelslicht.
  s.push(
    `<path d="M${n(x - w / 2 - eave)},${n(ry + 5)}L${n(x + w / 2 + eave)},${n(ry + 5)}" stroke="${T.rim}" stroke-width="${f(Math.max(1.2, w * 0.022))}" opacity="0.8"/>`,
  );
  // Ziegelrillen: drei kurze Striche je Schräge. Sie kosten wenig und sind der
  // Unterschied zwischen einer Dachfläche und einem Ziegeldach.
  if (w > 62) {
    for (let k = 1; k <= 3; k++) {
      const t = k / 4;
      s.push(
        `<path d="M${n(x - w * 0.06 - (w / 2 + eave - w * 0.06) * t)},${n(ry - roofH + (roofH + 5) * t)}l${n(w * 0.03)},${n(-roofH * 0.06)}" stroke="${T.rim}" stroke-width="1" opacity="0.4"/>`,
      );
    }
  }
  /* Kōshi — die senkrechte Lattung, die eine Machiya-Fassade ausmacht. Nur auf
     den großen nahen Wänden: dort ist Platz dafür, und dort fehlt sonst jede
     Struktur. Sechs Striche, keine 200 Byte. */
  if (w > 150) {
    for (let k = 1; k < 7; k++) {
      const lx = x - w / 2 + (w * k) / 7;
      s.push(
        `<path d="M${n(lx)},${n(baseY)}V${n(ry + 4)}" stroke="${T.rim}" stroke-width="1.2" opacity="0.28"/>`,
      );
    }
  }
  const nWin = w > 92 ? 3 : w > 54 ? 2 : 1;
  for (let i = 0; i < nWin; i++) {
    const wx = x + (nWin === 1 ? 0 : (i - (nWin - 1) / 2) * w * (nWin === 3 ? 0.3 : 0.42));
    const ww = w * (nWin === 3 ? 0.17 : 0.2);
    const wh = wallH * 0.44;
    const wy = baseY - wallH * 0.74;
    if (!lit[i % lit.length]) {
      // Dunkles Fenster: hier schläft noch jemand. Es ist trotzdem gezeichnet —
      // eine Wand ohne Öffnungen ist keine Wand.
      s.push(
        `<rect x="${n(wx - ww / 2)}" y="${n(wy)}" width="${n(ww)}" height="${n(wh)}" fill="${T.dark}"/>`,
      );
      continue;
    }
    const cls = clock < 0 ? '' : ` class="w${clock}"`;
    s.push(
      `<g${cls}>` +
        `<ellipse cx="${n(wx)}" cy="${n(wy + wh / 2)}" rx="${f(ww * 2.2)}" ry="${f(wh * 2.0)}" fill="url(#fensterhof)"/>` +
        `<rect x="${n(wx - ww / 2)}" y="${n(wy)}" width="${n(ww)}" height="${n(wh)}" fill="${C.gold}"/>` +
        `<rect x="${n(wx - ww / 2)}" y="${n(wy + wh * 0.42)}" width="${n(ww)}" height="${Math.max(1, n(wh * 0.16))}" fill="${C.goldDeep}" opacity="0.55"/>` +
        `</g>`,
    );
    lights.push({ x: wx, y: wy + wh / 2 });
  }
  return s.join('');
}

const TONE_FAR = { roof: C.roofFar, wall: C.wallFar, rim: C.rimFar, dark: C.glassFar };
const TONE_MID = { roof: C.roofMid, wall: C.wallMid, rim: C.rimMid, dark: C.glassMid };
const TONE_NEAR = { roof: C.roofNear, wall: C.wallNear, rim: C.rimNear, dark: C.glassNear };

/* REIHE A — die ferne Stadt, dicht an dicht über die volle Breite. Sie schließt
   den Spalt zwischen Horizont und Mittelgrund. */
let ax = -40;
let ai = 0;
while (ax < W + 40) {
  const w = between(42, 78);
  const y = HORIZON + between(26, 44);
  put(haus(ax + w / 2, y, w, TONE_FAR, [R() < 0.42, R() < 0.3], R() < 0.5 ? ai % 6 : -1));
  ax += w + between(2, 14);
  ai++;
}

/* REIHE B — der Mittelgrund, und damit die Zeile, die genau HINTER der
   Lesespalte durchläuft. Die Regie: „Motive laufen hinter der Lesespalte durch
   — der Schleier schützt die Spalte, genau dafür existiert er." */
let bx = -60;
let bi = 0;
while (bx < W + 60) {
  const w = between(78, 132);
  const y = between(788, 812);
  put(haus(bx + w / 2, y, w, TONE_MID, [R() < 0.6, R() < 0.5, R() < 0.4], R() < 0.55 ? bi % 6 : -1));
  bx += w + between(4, 22);
  bi++;
}

/* Ein Lichtsaum auf Höhe der mittleren Traufen: das Restlicht der Stadt in der
   Luft. Ohne ihn stehen die beiden Dachreihen wie ausgeschnitten übereinander. */
put(
  `<ellipse cx="900" cy="798" rx="900" ry="34" fill="${C.glutRose}" opacity="0.1" filter="url(#weich)"/>`,
);

/* REIHE C — die nahe Reihe, deutlich größer und dunkler. Sie ist der Übergang
   zur Silhouette des Vordergrunds. */
let dx = -80;
let di = 0;
while (dx < W + 80) {
  const w = between(168, 268);
  const y = between(902, 936);
  put(haus(dx + w / 2, y, w, TONE_NEAR, [R() < 0.58, R() < 0.66, R() < 0.44], R() < 0.6 ? di % 6 : -1));
  dx += w + between(14, 46);
  di++;
}

/* ═══════════════════════════════════════════════════════════════════════════
   9) DER VORDERGRUND — der Dachfirst, auf dem man selbst steht
   ═══════════════════════════════════════════════════════════════════════════
   Das untere Bilddrittel ist bei v1 leer geblieben. Hier steht jetzt ein naher
   Ziegelfirst quer über die ganze Breite, mit echter Ziegeltextur (eine Reihe
   flacher Bögen — so enden Kawara nun mal), Wassertank, Antennen und der
   Oberleitung. Alles in `near`: Silhouette, knapp über --bg-base. */
const FORE = 938;
put(
  `<path d="M-40,${H + 10}L-40,${FORE + 16}L${asLine(ridgeLine(FORE, 12, 3, -8))}L${W + 40},${FORE + 10}L${W + 40},${H + 10}Z" fill="${C.near}"/>`,
);
// Der Firstbalken.
put(
  `<path d="M-40,${FORE + 2}h${W + 80}v10h${-(W + 80)}Z" fill="${C.rimFore}" opacity="0.85"/>`,
);
// Die Ziegelreihe: flache Bögen, einer je 26 Bildpunkte.
put(`<g fill="none" stroke="${C.rimFore}" stroke-width="1.4" opacity="0.5">`);
for (let x = -30; x < W + 30; x += 26) {
  put(`<path d="M${n(x)},${FORE + 14}q13,${f(between(9, 14))} 26,0"/>`);
}
put(`</g>`);

/* Antennen und Masten. Eine Fernsehantenne ist ein Mast mit fünf bis acht
   Querstreben, die nach oben kürzer werden — mehr braucht es nicht, und sie
   ist das Zeichen, an dem eine japanische Dachlandschaft sofort kenntlich ist. */
function antenne(x, baseY, h) {
  const s = [`<path d="M${n(x)},${n(baseY)}v${n(-h)}" stroke="${C.near}" stroke-width="3"/>`];
  const arms = Math.round(h / 22);
  for (let k = 0; k < arms; k++) {
    const t = k / arms;
    const y = baseY - h * 0.35 - h * 0.6 * t;
    const aw = 26 * (1 - t * 0.62);
    s.push(
      `<path d="M${n(x - aw)},${n(y)}h${n(aw * 2)}" stroke="${C.near}" stroke-width="2.2"/>`,
    );
  }
  return s.join('');
}
[
  [212, 168],
  [498, 122],
  [742, 196],
  [1024, 140],
  [1312, 174],
  [1508, 116],
].forEach(([x, h]) => put(antenne(x, FORE + 6, h)));

// Der Wassertank auf vier Beinen — das andere unverkennbare Dachmöbel.
[
  [372, 86, 52],
  [1180, 74, 46],
].forEach(([x, h, w]) => {
  put(
    `<path d="M${n(x - w / 2)},${FORE + 6}v${n(-h + 22)}h${n(w)}v${n(h - 22)}" fill="none" stroke="${C.near}" stroke-width="3"/>`,
    `<rect x="${n(x - w / 2 - 6)}" y="${n(FORE + 6 - h)}" width="${n(w + 12)}" height="24" rx="4" fill="${C.near}"/>`,
    `<path d="M${n(x - w * 0.3)},${FORE + 6 - h + 24}v${n(h - 24)}M${n(x + w * 0.3)},${FORE + 6 - h + 24}v${n(h - 24)}" stroke="${C.near}" stroke-width="2.4"/>`,
  );
});

/* DIE OBERLEITUNG. Vier durchhängende Drähte zwischen zwei Masten, quer über
   das GANZE Bild. Sie ist das eine Motiv, das die Bühne wirklich von Rand zu
   Rand bindet — und sie hängt durch, weil Drähte durchhängen; eine Gerade wäre
   eine Linie im Bild, kein Kabel. */
function catenary(x0, y0, x1, y1, sag, steps = 24) {
  return Array.from({ length: steps + 1 }, (_, i) => {
    const t = i / steps;
    return [x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + Math.sin(Math.PI * t) * sag];
  });
}
function mast(x, topY, botY) {
  const s = [`<path d="M${n(x)},${n(botY)}V${n(topY)}" stroke="${C.near}" stroke-width="7"/>`];
  [0, 26, 52].forEach((d, k) => {
    const aw = 44 - k * 6;
    s.push(
      `<path d="M${n(x - aw)},${n(topY + 12 + d)}h${n(aw * 2)}" stroke="${C.near}" stroke-width="4"/>`,
    );
    [-aw * 0.6, aw * 0.6].forEach((o) =>
      s.push(
        `<path d="M${n(x + o)},${n(topY + 12 + d)}v-7" stroke="${C.near}" stroke-width="2.4"/>`,
      ),
    );
  });
  return s.join('');
}
put(mast(148, 700, FORE + 10));
put(mast(1392, 684, FORE + 10));
put(`<g fill="none" stroke="${C.near}" stroke-width="2.6">`);
[
  [724, 736, 62],
  [750, 762, 74],
  [776, 788, 88],
  [802, 814, 96],
].forEach(([y0, y1, sag]) => {
  put(`<path d="M-40,${n(y0 - 14)}L${asLine(catenary(148, y0, 1392, y1, sag))}L${W + 40},${n(y1 + 6)}"/>`);
});
put(`</g>`);

/* Vögel auf dem Draht. Fünf Punkte, 200 Byte, und der Vordergrund hat eine
   Geschichte statt nur einer Silhouette. */
put(`<g fill="${C.near}">`);
[
  [386, 782],
  [412, 785],
  [430, 786],
  [980, 800],
  [1012, 799],
].forEach(([x, y]) => put(`<ellipse cx="${x}" cy="${y}" rx="4" ry="6"/>`));
put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   10) DIE LICHTBAHNEN — „das Licht flutet die Bühne wie Komorebis Bahnen"
   ═══════════════════════════════════════════════════════════════════════════
   Zwölf Keile, die von der Sonne aus über die GANZE Bühne aufgefächert werden —
   über den Himmel UND über die Stadt, denn Licht, das an den Dächern haltmacht,
   ist kein Licht, sondern eine Tapete. Sie liegen deshalb als LETZTE Ebene über
   allem.

   Zwei geteilte defs tragen die Weichheit: EINE Maske mit radialem Abfall um
   die Sonne (die Bahnen enden nicht, sie verlaufen sich) und EIN Gauß-Filter
   für die Kanten. Zwölf einzelne Verläufe wären zwölfmal so teuer und sähen
   schlechter aus.

   Drei Uhrenfamilien, teilerfremde Perioden: die Bahnen atmen gegeneinander,
   und weil sie zusammen den halben Bildschirm bedecken, ist das nach der Glut
   die zweitgrößte bewegte Fläche. */
const rad = (a) => (a * Math.PI) / 180;
put(`<g mask="url(#bahnfade)" filter="url(#weich2)">`);
[
  [-38, 2.6],
  [-22, 1.8],
  [-8, 3.4],
  [6, 2.0],
  [19, 4.2],
  [33, 2.4],
  [46, 3.0],
  [60, 1.9],
  [73, 3.6],
  [87, 2.2],
  [101, 2.8],
  [116, 1.7],
].forEach(([a, hw], i) => {
  const L = 2100;
  const p = (ang) => [SUN.x - Math.sin(rad(ang)) * L, SUN.y - Math.cos(rad(ang)) * L];
  const [x1, y1] = p(a - hw);
  const [x2, y2] = p(a + hw);
  const tone = i % 3 === 0 ? C.glutHot : i % 3 === 1 ? C.glutWarm : C.glutRose;
  put(
    `<path class="b${i % 3}" d="M${SUN.x},${SUN.y}L${n(x1)},${n(y1)}L${n(x2)},${n(y2)}Z" fill="${tone}" opacity="${f(between(0.3, 0.52))}"/>`,
  );
});
put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   11) DER HERZSCHLAG — die Uhren
   ═══════════════════════════════════════════════════════════════════════════
   Perioden mit nicht-ganzzahligen Verhältnissen (23…79 s), negative Delays je
   Index. Jede Keyframe beginnt bei opacity:1 und geht NUR nach unten: der
   gemalte Zustand ist der hellste, also ist der eingefrorene Zustand zugleich
   der Kontrast-Worst-Case — beweisbar, ohne die Uhr anhalten zu müssen.

   Warum das hier steht und nicht in yoake.css: die Ebene wird per
   `background-image: url(...)` geladen, und darin läuft nur, was IM Bild steht.
   Chrome animiert SVG-Bilder deklarativ mit; fällt das irgendwo aus, bleibt das
   Bild in seinem hellsten Zustand stehen — vollständig und kontrastgeprüft, nur
   eben still. */
const css = [];

// Die Glut: sechs sehr lange Uhren. Sie sind der wandernde Verlauf.
[
  [53, 0.34],
  [61, 0.28],
  [67, 0.42],
  [71, 0.3],
  [79, 0.38],
  [59, 0.26],
  [73, 0.32],
].forEach(([p, lo], i) => {
  css.push(
    `.g${i}{animation:ga${i} ${p}s ease-in-out infinite;animation-delay:${f(-p * (0.13 * i + 0.07))}s}`,
    `@keyframes ga${i}{0%,100%{opacity:1}34%{opacity:${lo}}62%{opacity:${f(lo + 0.34)}}}`,
  );
});

// Die Lichtbahnen: drei Uhren, weiter Hub — Licht durch Dunst ist nie stetig.
[
  [37, 0.24],
  [43, 0.36],
  [47, 0.18],
].forEach(([p, lo], i) => {
  css.push(
    `.b${i}{animation:ba${i} ${p}s ease-in-out infinite;animation-delay:${f(-p * 0.29 * (i + 1))}s}`,
    `@keyframes ba${i}{0%,100%{opacity:1}28%{opacity:${lo}}55%{opacity:${f(lo + 0.3)}}80%{opacity:${f(lo + 0.12)}}}`,
  );
});

// Die Wolken atmen kaum — sie sollen ziehen, nicht flackern.
[
  [83, 0.72],
  [97, 0.66],
  [89, 0.78],
].forEach(([p, lo], i) => {
  css.push(
    `.c${i}{animation:ca${i} ${p}s ease-in-out infinite;animation-delay:${f(-p * 0.21 * (i + 1))}s}`,
    `@keyframes ca${i}{0%,100%{opacity:1}50%{opacity:${lo}}}`,
  );
});

// Die letzten Sterne verlöschen — tief runter, lange unten, kurz wieder da.
[
  [64, 0.06],
  [73, 0.1],
  [81, 0.04],
].forEach(([p, lo], i) => {
  css.push(
    `.s${i}{animation:sa${i} ${p}s ease-in-out infinite;animation-delay:${f(-p * 0.37 * (i + 1))}s}`,
    `@keyframes sa${i}{0%{opacity:1}22%{opacity:${lo}}72%{opacity:${lo}}100%{opacity:1}}`,
  );
});

/* DIE FENSTER. Sechs Uhren, und ihre Kurven sind mit Absicht KANTIG: ein
   Fenster geht an, es dimmt nicht auf. Die Sprünge liegen bei je ~4 % der
   Periode — bei 23 bis 41 s sind das unter zwei Sekunden, also schnell genug,
   dass man den Schalter sieht, und langsam genug, dass nichts blitzt. Die
   Dunkelphasen sind unterschiedlich lang, damit die Reihe nie im Gleichtakt
   läuft. */
[
  [23, 0.05, 0.44],
  [26, 0.07, 0.3],
  [29, 0.04, 0.52],
  [31, 0.06, 0.26],
  [37, 0.05, 0.4],
  [41, 0.08, 0.34],
].forEach(([p, lo, dark], i) => {
  const a = 8 + i * 3;
  const b = a + 4;
  const c = a + dark * 100;
  const d = c + 4;
  css.push(
    `.w${i}{animation:wa${i} ${p}s linear infinite;animation-delay:${f(-p * (0.11 * i + 0.03))}s}`,
    `@keyframes wa${i}{0%,${f(a)}%{opacity:1}${f(b)}%{opacity:${lo}}${f(c)}%{opacity:${lo}}${f(d)}%,100%{opacity:1}}`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   12) ZUSAMMENBAU
   ═══════════════════════════════════════════════════════════════════════════
   KEIN einziger Kommentar im ausgegebenen SVG: ein `--` darin lässt Chrome die
   GANZE Datei still verwerfen (Piloten-Lektion 2). Die Erklärungen bleiben hier. */
const stop = (o, col, op) => `<stop offset="${o}" stop-color="${col}" stop-opacity="${op}"/>`;

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
  `role="img" aria-label="Tagesanbruch: ein gluehender Horizont hinter einer Stadt aus Ziegeldaechern, in der die ersten Fenster angehen">` +
  `<style>${css.join('')}` +
  `@media(prefers-reduced-motion:reduce){*{animation:none!important}}</style>` +
  `<defs>` +
  `<linearGradient id="himmel" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.sky0}"/>` +
  `<stop offset="0.14" stop-color="${C.sky1}"/>` +
  `<stop offset="0.26" stop-color="${C.sky2}"/>` +
  `<stop offset="0.37" stop-color="${C.sky3}"/>` +
  `<stop offset="0.46" stop-color="${C.sky4}"/>` +
  `<stop offset="0.545" stop-color="${C.sky5}"/>` +
  `<stop offset="0.6" stop-color="${C.sky6}"/>` +
  `<stop offset="0.645" stop-color="${C.sky7}"/>` +
  `<stop offset="0.685" stop-color="${C.sky8}"/>` +
  `</linearGradient>` +
  `<linearGradient id="glutband" x1="0" y1="0" x2="0" y2="1">` +
  `${stop(0, C.glutRose, 0)}${stop(0.22, C.glutRose, 0.34)}${stop(0.5, C.glutWarm, 0.54)}` +
  `${stop(0.68, C.glutHot, 0.42)}${stop(1, C.glutWarm, 0)}</linearGradient>` +
  `<radialGradient id="hof">${stop(0, C.glutWarm, 0.72)}${stop(0.5, C.glutRose, 0.3)}${stop(1, C.glutRose, 0)}</radialGradient>` +
  `<radialGradient id="kern">${stop(0, C.glutHot, 0.76)}${stop(0.55, C.glutWarm, 0.4)}${stop(1, C.glutWarm, 0)}</radialGradient>` +
  `<radialGradient id="glutglutHot">${stop(0, C.glutHot, 0.9)}${stop(0.45, C.glutWarm, 0.4)}${stop(1, C.glutWarm, 0)}</radialGradient>` +
  `<radialGradient id="glutglutWarm">${stop(0, C.glutWarm, 0.85)}${stop(0.45, C.glutWarm, 0.34)}${stop(1, C.glutRose, 0)}</radialGradient>` +
  `<radialGradient id="glutglutRose">${stop(0, C.glutRose, 0.8)}${stop(0.45, C.glutRose, 0.3)}${stop(1, C.glutViolet, 0)}</radialGradient>` +
  `<radialGradient id="glutglutViolet">${stop(0, C.glutViolet, 0.7)}${stop(0.45, C.glutViolet, 0.26)}${stop(1, C.glutViolet, 0)}</radialGradient>` +
  `<radialGradient id="fensterhof">${stop(0, C.goldGlow, 0.5)}${stop(0.4, C.goldGlow, 0.2)}${stop(1, C.goldGlow, 0)}</radialGradient>` +
  `<radialGradient id="sternhof">${stop(0, C.sternDim, 0.46)}${stop(0.45, C.sternDim, 0.14)}${stop(1, C.sternDim, 0)}</radialGradient>` +
  `<radialGradient id="bahnrad" cx="${SUN.x}" cy="${SUN.y}" r="1180" gradientUnits="userSpaceOnUse">` +
  `<stop offset="0" stop-color="#fff" stop-opacity="0.95"/>` +
  `<stop offset="0.3" stop-color="#fff" stop-opacity="0.6"/>` +
  `<stop offset="0.66" stop-color="#fff" stop-opacity="0.22"/>` +
  `<stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>` +
  `<mask id="bahnfade"><rect width="${W}" height="${H}" fill="url(#bahnrad)"/></mask>` +
  `<filter id="weich" x="-30%" y="-140%" width="160%" height="380%"><feGaussianBlur stdDeviation="26"/></filter>` +
  `<filter id="weich2" x="-6%" y="-6%" width="112%" height="112%"><feGaussianBlur stdDeviation="13"/></filter>` +
  `</defs>` +
  out.join('') +
  `</svg>\n`;

writeFileSync(OUT, svg);
const bytes = Buffer.byteLength(svg);
console.log(
  `yoake-szene.svg  ${(bytes / 1024).toFixed(1)} KB  ·  ${lights.length} brennende Fenster  ·  ` +
    `Safe-Zone x${SAFE.x0}–${SAFE.x1}, y≥${SAFE.y0}  ·  Horizont y=${HORIZON}`,
);
if (bytes > 80 * 1024) {
  console.error('✗ über dem 80-KB-Budget der ORDER');
  process.exit(1);
}
