/**
 * NAGAREBOSHI — der Szenen-Generator (zweite Fassung, Regie v2 vom 19.08.)
 * ═══════════════════════════════════════════════════════════════════════════
 * DIE ERSTE FASSUNG WAR EIN SCHWARZES BILD. Sie zeichnete Grate und ein Haus in
 * Tönen dicht über dem Grundton, ließ den Himmel mit Ansage LEER („keine
 * Sterne") und war am Ende zu ~95 % Schwarz mit einem einzigen orangen
 * Fensterpixel. Die Regie v2 kehrt das um: eine Nacht ist REICH. Dieses Skript
 * malt darum ein BILD und keinen Rahmen.
 *
 *   Hauptmotiv     DIE STERNENSTRASSE — ein Milchstraßen-Band, das von links
 *                  oben quer über die ganze Bühne zum rechten Horizont zieht,
 *                  mit Dunkelriss und ~400 Sternen in vier Helligkeiten, die
 *                  sich an seinen Rändern verdichten.
 *   Tiefe          fünf Ebenen in Luftperspektive: ferne Kette (hell/dunstig) →
 *                  Mittelkette → Zedern-Grat → Talflanken → Vordergrund (fast
 *                  schwarz). Jede Stufe ist deutlich dunkler als die dahinter;
 *                  das ist die ganze Rezeptur von „räumlich".
 *   Das Gold       ein Dorf aus fünfzehn Minka am Ufer, eine Laternenkette
 *                  darüber, ein Torii im Reisfeld, eine Steinlaterne ganz vorn
 *                  — und jedes dieser Lichter bricht sich im Wasser. Das untere
 *                  Viertel ist damit der hellste Teil des Bildes.
 *   Herzschlag     die Sterne funkeln VERSETZT: acht Flimmer-Gruppen mit
 *                  eigenen Uhren (21–43 s) und eigenen Phasen, dazu drei Uhren
 *                  für die Laternen und drei für die Spiegelungen.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * WAS DIE EIGENE SICHTUNG DER FRAME-SERIE NOCH KORRIGIERT HAT
 * ───────────────────────────────────────────────────────────────────────────
 * Der erste Wurf dieser Fassung hatte Sterne und Licht, aber schlechtes
 * Handwerk — sichtbar im Bild, nicht in irgendeiner Zahl:
 *   · Die Minka waren PILZE. Ein Dach aus zwei Quadratkurven wölbt sich; ein
 *     Minka-Dach ist steil und GERADE. Und es ist HELLER als die Wand, weil
 *     Stroh Sternlicht fängt — erst dieses Gefälle macht aus einem Klecks ein
 *     Haus. Der fette Lichthof um jedes Fenster musste dafür weichen.
 *   · Die Zedern waren RAUTEN. Sechs Etagen auf 640 px sind keine Äste, sondern
 *     Zickzack. Jetzt richtet sich die Etagenzahl nach der Höhe (bis 16), und
 *     die Zweigspitzen hängen NACH UNTEN — daran erkennt man eine Sugi.
 *   · Zwischen Grat und Dorf lag ein totes Band. Das Wasser ist nach oben
 *     gewachsen (auf ~24 % der Bildhöhe), das Dorf sitzt jetzt AM Ufer, die
 *     Ketten stehen dichter beieinander.
 *   · Das Land war zu dunkel, um Land zu sein. Alle Grattöne sind angehoben,
 *     die ferne Kette am stärksten (L 0.196 → 0.27).
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ZWEI REGELN, DIE JEDE FARBE HIER EINHÄLT
 * ───────────────────────────────────────────────────────────────────────────
 * (1) NICHTS IST DUNKLER ALS --bg-base. Der Lesbarkeits-Schleier der Themen-CSS
 *     deckt die Spalte mit eben diesem Grundton ab; läge ein Bildton DARUNTER,
 *     würde der Schleier dort AUFHELLEN und als sichtbares Rechteck im Himmel
 *     stehen (der Fehler, den die erste Fassung schon einmal hatte). Alle
 *     Flächen werden aus OKLCH-L ≥ 0.107 gemischt, --bg-base liegt auf 0.105.
 *     Die Silhouette entsteht nicht dadurch, dass das Land schwarz ist, sondern
 *     dadurch, dass der Himmel dahinter HELLER ist.
 * (2) DIE ANIMATION DARF NUR ABDUNKELN. Der gemalte Grundzustand jedes Sterns
 *     ist sein HELLSTER; die Keyframes gehen von opacity:1 nur nach unten. Damit
 *     ist der statische Zustand (und der bei prefers-reduced-motion) zugleich
 *     der Worst Case der Kontrastmessung — man kann den schlechtesten Pixel
 *     messen, ohne die Animation von außen anhalten zu können.
 *
 * Fester Zufalls-Startwert: derselbe Lauf liefert bitgleich dasselbe Bild.
 *
 *   node tools/theme-contrast/nagareboshi-szene.gen.mjs
 *   → frontend/public/themes/nagareboshi-szene.svg
 */

import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', '..', 'frontend', 'public', 'themes', 'nagareboshi-szene.svg');

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
const R = rng(0x5e1f0a3d);
const between = (lo, hi) => lo + R() * (hi - lo);

/* ── OKLCH → sRGB ────────────────────────────────────────────────────────────
   Die Themen-CSS denkt in OKLCH (gleichmäßige Helligkeitsschritte), die SVG
   braucht Hex. Beide Seiten kommen aus DERSELBEN Zahl, damit „der Zedern-Grat
   liegt auf L 0.155" eine prüfbare Aussage ist und kein Farbeindruck. */
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
  // GAMUT-RIEGEL. Liegt ein Kanal außerhalb 0…1, wird er beim Runden geklippt —
  // und Klippen verschiebt den FARBTON, nicht nur die Helligkeit. Aus einem
  // warmen Gold wird dann still ein anderes Gold, und niemand merkt es, weil
  // die Zahl im Quelltext ja richtig aussieht. Also lieber laut abbrechen:
  // wer eine Farbe hier nicht darstellen kann, soll das Chroma senken.
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

/* ── Die Palette der Nacht ───────────────────────────────────────────────────
   Von hinten nach vorn wird es DUNKLER. Die Stufen sind bewusst groß genug, um
   bei 20 % Restdurchlass unter dem Schleier NOCH unterscheidbar zu sein; der
   erste Wurf hatte sie zu eng und das ganze Land verschmolz zu einer Masse. */
/* WAS DIESE ZAHLEN GEKOSTET HABEN. Die erste Fassung hatte die ganze Landschaft
   zwischen L 0.107 und L 0.29 gestapelt — auf dem Papier eine saubere Leiter mit
   fünf Stufen. Am gemessenen Bild war sie keine: über die 128 Bildzeilen des
   Mittelgrunds lag der Mittelwert bei 11 von 255 und der HELLSTE Bildpunkt bei
   24. Fünf Stufen, die sich um zwei oder drei Zahlenwerte unterscheiden, sind
   für das Auge eine Fläche. Genau dort hat Ukiyo den Fuji stehen.
   Der Grund ist Kette, nicht Farbe: OKLCH-L ist wahrnehmungsgleichmäßig auf
   einem HELLEN Schirm, aber über die Szene liegen noch zwei Luftglühen und der
   Schleier, und beide multiplizieren. Wer im Dunkeln Stufen will, muss sie
   VORHER weiter auseinanderziehen, als die Rechnung nahelegt.
   Also: dieselben fünf Stufen, gespreizt. Der Vordergrund (`near`) bleibt fast,
   wo er war — er ist der Anker, und eine Silhouette liest man nicht daran, wie
   dunkel sie ist, sondern woran sie sich abhebt. Alles HINTER ihr steigt. */
const C = {
  ridge1: ok(0.255, 0.034, 264), //  ferne Kette
  ridge1Rim: ok(0.38, 0.032, 258), //  Sternlicht auf der Kammlinie
  ridge2: ok(0.212, 0.034, 262),
  ridge3: ok(0.173, 0.032, 260), //  Zedern-Grat
  ridge4: ok(0.142, 0.026, 260), //  Talflanken
  water: ok(0.275, 0.036, 262), //  Reisfeld am Ufer — es spiegelt den hellen Himmel
  waterFar: ok(0.205, 0.03, 262), //  und weiter vorn, dunkler
  near: ok(0.112, 0.02, 258), //  Vordergrund: Zeder, Schilf, Torii, Steinlaterne
  stone: ok(0.112, 0.02, 258), //  identisch mit `near` — Vordergrund ist Silhouette
  wall: ok(0.126, 0.02, 258), //  Hauswand im Schatten
  roof: ok(0.29, 0.028, 262), //  Strohdach mit Sternlicht — HELLER als die Wand

  haze: ok(0.56, 0.052, 272), //  Milchstraßen-Dunst
  mist: ok(0.31, 0.03, 262), //  Talnebel: die Schicht, die Ketten trennt
  hazeCore: ok(0.6, 0.045, 66), //  der wärmere, hellere Kern des Bandes
  glowHorizon: ok(0.36, 0.06, 58), //  Restlicht des Dorfes in der Luft

  dust: ok(0.66, 0.035, 262), //  Sternstaub
  starS: ok(0.8, 0.028, 258),
  starM: ok(0.91, 0.02, 250),
  starL: ok(0.97, 0.008, 240),

  gold: ok(0.82, 0.13, 72), //  Fenster- und Laternenlicht — BUCHSTAEBLICH --accent
  goldDeep: ok(0.75, 0.15, 56), //  der satte Kern
  goldGlow: ok(0.82, 0.12, 68), //  Hof um jedes Licht
};

/* ── Geometrie der Bühne ─────────────────────────────────────────────────────
   Die Höhenlinien sind das Skelett. Sie liegen so, dass NIRGENDWO ein Band ohne
   Inhalt entsteht — der Vorwurf der Regie („Randstreifen um ein Loch") gilt
   auch senkrecht. */
const W = 1600;
const H = 1000;
/** Sichtbar bei JEDEM Fensterformat (cover + center bottom, 4:3 bis 16:9). */
const SAFE = { x0: 140, x1: 1460, y0: 110 };
const SHORE = 762; //  Uferlinie: davor Wasser, dahinter Dorf
const SKY_BOTTOM = 560;

const out = [];
const put = (s) => out.push(s);
/* Ganze Zahlen. Auf einer 1600-px-Bühne, die im Fenster auf ~0,8–1,2 skaliert
   wird, ist ein Zehntel Bildpunkt nichts als Dateigröße — und Dateigröße ist
   hier ein Budget (≤ 80 KB laut ORDER). */
const n = (v) => Math.round(v);
const f = (v) => Math.round(v * 10) / 10;

/** Sammelt jedes Licht, damit das Wasser es zurückwerfen kann. */
const lights = [];

/* ═══════════════════════════════════════════════════════════════════════════
   1) DIE STERNENSTRASSE — Dunstband + Sternfeld
   ═══════════════════════════════════════════════════════════════════════════
   Das Band ist eine Gerade von (120,-70) nach (1660,540). Alles hängt an ihr:
   der Dunst liegt auf ihr, die Sterndichte fällt mit dem Abstand, und der
   Dunkelriss ist ein VERZICHT auf Dunst (kein dunkler Anstrich — der wäre nach
   Regel (1) verboten). */

const BAND = { x0: 120, y0: -70, x1: 1660, y1: 540 };
const bandLen = Math.hypot(BAND.x1 - BAND.x0, BAND.y1 - BAND.y0);
const bandDir = [(BAND.x1 - BAND.x0) / bandLen, (BAND.y1 - BAND.y0) / bandLen];
/** Vorzeichenbehafteter Abstand zum Band (positiv = unterhalb/rechts). */
const bandDist = (x, y) => (x - BAND.x0) * bandDir[1] - (y - BAND.y0) * bandDir[0];
/** Lage ENTLANG des Bandes, 0…1 — das Band ist an den Enden dünner. */
const bandT = (x, y) => ((x - BAND.x0) * bandDir[0] + (y - BAND.y0) * bandDir[1]) / bandLen;

{
  const ang = (Math.atan2(BAND.y1 - BAND.y0, BAND.x1 - BAND.x0) * 180) / Math.PI;
  const mx = (BAND.x0 + BAND.x1) / 2;
  const my = (BAND.y0 + BAND.y1) / 2;
  const el = (cy, rx, ry, fill, op) =>
    `<ellipse cx="0" cy="${cy}" rx="${n(rx)}" ry="${ry}" fill="${fill}" opacity="${op}"/>`;
  put(
    `<g class="mw" transform="translate(${n(mx)} ${n(my)}) rotate(${f(ang)})" filter="url(#weich)">` +
      // Zwei Flanken mit einem Spalt dazwischen: der Spalt IST der Dunkelriss.
      el(-116, bandLen * 0.46, 148, C.haze, 0.92) +
      el(78, bandLen * 0.44, 116, C.haze, 0.8) +
      el(-134, bandLen * 0.3, 66, C.hazeCore, 0.72) +
      el(96, bandLen * 0.24, 48, C.hazeCore, 0.48) +
      `<ellipse cx="${n(-bandLen * 0.2)}" cy="-40" rx="200" ry="128" fill="${C.haze}" opacity="0.46"/>` +
      `<ellipse cx="${n(bandLen * 0.22)}" cy="46" rx="240" ry="100" fill="${C.haze}" opacity="0.42"/>` +
      `</g>`,
  );
}

/* Sternfeld. Dichte = Grundrauschen + Verdichtung am Band; der Riss bleibt
   sternärmer, aber nicht leer. */
function starWeight(x, y) {
  const d = bandDist(x, y);
  const t = bandT(x, y);
  const along = Math.exp(-(((t - 0.5) / 0.62) ** 2));
  const core = Math.exp(-((d / 210) ** 2)) * along;
  const rift = 1 - 0.6 * Math.exp(-(((d + 4) / 42) ** 2));
  return (0.22 + 1.5 * core) * rift;
}

const TWINKLE = 8; // acht Uhren — die Versetzung, die „funkeln" ausmacht
const classes = [
  { key: 'a', count: 148, r: 0.8, fill: C.dust, op: 0.55 },
  { key: 'b', count: 112, r: 1.2, fill: C.starS, op: 0.75 },
  { key: 'c', count: 68, r: 1.8, fill: C.starM, op: 0.9 },
];

const buckets = new Map();
for (const cl of classes) {
  let placed = 0;
  let guard = 0;
  while (placed < cl.count && guard++ < cl.count * 60) {
    const x = between(-20, W + 20);
    const y = between(-30, SKY_BOTTOM);
    if (R() > starWeight(x, y)) continue;
    const key = `${cl.key}${Math.floor(R() * TWINKLE)}`;
    if (!buckets.has(key)) buckets.set(key, { cl, dots: [] });
    buckets.get(key).dots.push([Math.round(x), Math.round(y)]);
    placed++;
  }
}
for (const [key, { cl, dots }] of buckets) {
  put(
    `<g class="f${key.slice(1)}" fill="${cl.fill}" opacity="${cl.op}">` +
      dots.map(([x, y]) => `<circle cx="${x}" cy="${y}" r="${f(cl.r)}"/>`).join('') +
      `</g>`,
  );
}

/* Die hellen Sterne: eigener Auftritt mit Hof und Kreuzstrahl. Der Hof ist ein
   VERLAUF, kein Kreis mit fester Deckkraft — und das ist kein Detail: ein
   flacher Kreis hat eine Kante, und sobald er groß genug ist, um sichtbar zu
   funkeln, liest man ihn als MÜNZE statt als Licht. Genau so sahen diese Sterne
   aus, nachdem der Hof für die Sichtbarkeit vergrößert worden war — im Frame
   bei 10 s standen zweiundzwanzig kleine Ringe im Himmel. Sie sind die
   Ankerpunkte, an denen das Auge den Himmel liest — ihre Plätze sind gesetzt,
   nicht gewürfelt, damit sie sich über die ganze Breite verteilen. */
const BRIGHT = [
  [232, 148], [389, 268], [452, 92], [612, 196], [705, 356], [806, 128],
  [884, 262], [968, 74], [1042, 330], [1136, 168], [1238, 286], [1305, 116],
  [1392, 372], [1468, 214], [1540, 96], [176, 350], [318, 452], [560, 428],
  [1010, 452], [1196, 470], [1420, 498], [96, 208],
];
BRIGHT.forEach(([x, y], i) => {
  const rr = 2.2 + (i % 3) * 0.4;
  put(
    `<g class="h${i % TWINKLE}">` +
      `<circle cx="${x}" cy="${y}" r="${n(rr * 5.4)}" fill="url(#hof)"/>` +
      `<path d="M${n(x - rr * 6)} ${y}H${n(x + rr * 6)}M${x} ${n(y - rr * 6)}V${n(y + rr * 6)}" ` +
      `stroke="${C.starL}" stroke-width="1" opacity="0.55"/>` +
      `<circle cx="${x}" cy="${y}" r="${f(rr)}" fill="${C.starL}"/>` +
      `</g>`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   2) DIE KETTEN — Luftperspektive in vier Stufen
   ═══════════════════════════════════════════════════════════════════════════
   Jede Kammlinie entsteht durch Mittelpunktverschiebung: eine Strecke wird
   rekursiv geteilt und der neue Punkt ausgelenkt, die Auslenkung mit jeder
   Stufe gedämpft. Das ist die kürzeste Beschreibung eines Gebirges, die es gibt
   — und sie liefert Kanten, die kein Verlauf je hinbekommt. Die Dämpfung liegt
   bei 0.58 statt 0.5: die hohen Frequenzen überleben länger, der Kamm bleibt
   zackig statt weich. */
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

const r1 = ridgeLine(424, 132, 5, -46);
const r2 = ridgeLine(536, 82, 5, 34);
const r3 = ridgeLine(646, 40, 5, -18);

put(ridgePath(r1, C.ridge1));
put(
  `<path d="M${asLine(r1)}" fill="none" stroke="${C.ridge1Rim}" stroke-width="2.6" opacity="0.95"/>`,
);
/* Auch die ZWEITE Kette bekommt eine Kammlinie. Ohne sie hat der Mittelgrund
   genau eine Kante, und eine Kante ist keine Tiefe — erst die zweite sagt dem
   Auge, dass zwischen den beiden Ketten ein Tal liegt. Schwächer als die erste,
   weil sie näher steht und weniger Himmel hinter sich hat. */
put(
  `<ellipse cx="700" cy="524" rx="900" ry="34" fill="${C.mist}" opacity="0.5" filter="url(#weich)"/>`,
);
put(ridgePath(r2, C.ridge2));
put(
  `<path d="M${asLine(r2)}" fill="none" stroke="${C.ridge1Rim}" stroke-width="1.8" opacity="0.55"/>`,
);
put(
  `<ellipse cx="900" cy="632" rx="820" ry="28" fill="${C.mist}" opacity="0.5" filter="url(#weich)"/>`,
);
/* Die DRITTE Kette bekommt bewusst KEINE Kammlinie. Nicht aus Geschmack: der
   Generator verteidigt ein 80-KB-Budget, eine Kammlinie kostet gut 350 Byte,
   und von den drei möglichen ist diese die schwächste (sie hätte bei 0,32
   Deckkraft gestanden). Ihre Aufgabe — sagen, wo die Kette anfängt — erledigt
   der Talnebel darüber ohnehin, und der stand schon da. */
put(ridgePath(r3, C.ridge3));

/* ═══════════════════════════════════════════════════════════════════════════
   3) DIE ZEDERN
   ═══════════════════════════════════════════════════════════════════════════
   Eine Sugi ist keine Dreiecksfläche und erst recht kein Rautenstapel. Sie hat
   viele flache Etagen, und ihre Zweigspitzen HÄNGEN — der äußere Punkt jeder
   Etage liegt TIEFER als der innere. Diese eine Umkehrung ist der Unterschied
   zwischen „Nadelbaum" und „Zackenmuster". Die Etagenzahl richtet sich nach der
   Höhe: der 700-px-Baum im Vordergrund bekommt sechzehn, ein 40-px-Bäumchen am
   Grat vier. */
function cedar(x, baseY, h, w, fill, jitter = 1) {
  const tiers = Math.max(4, Math.min(16, Math.round(h / 44)));
  const step = h / tiers;
  const lft = [];
  const rgt = [];
  for (let k = 0; k < tiers; k++) {
    const t = k / tiers;
    const y = baseY - h * t;
    const spread = (w / 2) * (1 - t) ** 0.72;
    lft.push([x - spread * (1 + between(-0.14, 0.14) * jitter), y + step * 0.3]);
    lft.push([x - spread * 0.34, y - step * 0.55]);
    rgt.push([x + spread * (1 + between(-0.14, 0.14) * jitter), y + step * 0.3]);
    rgt.push([x + spread * 0.34, y - step * 0.55]);
  }
  const d =
    `M${n(x - w / 2)},${n(baseY + step * 0.3)}` +
    lft.map(([px, py]) => `L${n(px)},${n(py)}`).join('') +
    `L${n(x)},${n(baseY - h - step * 0.5)}` +
    rgt
      .reverse()
      .map(([px, py]) => `L${n(px)},${n(py)}`)
      .join('') +
    `L${n(x + w / 2)},${n(baseY + step * 0.3)}Z`;
  return `<path d="${d}" fill="${fill}"/>`;
}

/** Höhe einer Kammlinie an der Stelle x (für Bäume, die AUF dem Grat stehen). */
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

// Waldsaum auf der Mittelkette: kleiner, dunkler, weiter weg — er gibt dem
// großen Mittelband Textur, statt es als Fläche stehen zu lassen.
for (let x = -30; x < W + 30; x += between(44, 84)) {
  put(cedar(x, heightAt(r2, x) + 6, between(18, 38), between(9, 17), C.ridge2));
}

// Waldlinie auf dem dritten Grat.
for (let x = -30; x < W + 30; x += between(34, 64)) {
  put(cedar(x, heightAt(r3, x) + 8, between(30, 68), between(14, 26), C.ridge3));
}

/* Die Talflanken: zwei Hügel, die von links und rechts ins Bild schieben und
   das Dorf einfassen. Sie sind die vorderste LANDschicht — alles Weitere steht
   auf ihnen oder im Wasser davor. */
put(
  `<path d="M-40,${H + 10}L-40,660C130,634 268,668 380,712C452,740 500,752 540,${SHORE}L-40,${SHORE}Z" fill="${C.ridge4}"/>`,
);
put(
  `<path d="M${W + 40},${H + 10}L${W + 40},626C1478,604 1352,640 1244,690C1168,726 1114,748 1070,${SHORE}L${W + 40},${SHORE}Z" fill="${C.ridge4}"/>`,
);
for (let i = 0; i < 15; i++) {
  const t = i / 14;
  put(
    cedar(
      -20 + t * 560,
      662 + (t * 560 * 0.2) ** 1.15 + between(-4, 10),
      between(36, 82),
      between(17, 31),
      C.ridge4,
    ),
  );
}
for (let i = 0; i < 14; i++) {
  const t = i / 13;
  put(
    cedar(
      W + 20 - t * 540,
      628 + (t * 540 * 0.21) ** 1.15 + between(-4, 10),
      between(34, 78),
      between(16, 30),
      C.ridge4,
    ),
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   4) DAS DORF — fünfzehn Minka am Ufer, und in jedem brennt Licht
   ═══════════════════════════════════════════════════════════════════════════
   Ein Minka ist im Umriss vor allem DACH: steil, gerade, weit über die Wand
   hinausstehend. Wand und Fenster sind klein dagegen. Und das Dach ist HELLER
   als die Wand — Stroh fängt Sternlicht, die beschattete Wand nicht. Ohne
   dieses Gefälle bleibt jedes Haus ein Klecks, egal wie exakt die Geometrie
   ist; das war der Befund der eigenen Sichtung. */
function minka(x, baseY, w) {
  const wallH = w * 0.36;
  const roofH = w * 0.5;
  const eave = w * 0.24;
  const ry = baseY - wallH;
  const s = [
    `<path d="M${n(x - w / 2)},${n(baseY)}h${n(w)}v${n(-wallH)}h${n(-w)}Z" fill="${C.wall}"/>`,
    `<path d="M${n(x - w / 2 - eave)},${n(ry + 6)}L${n(x - w * 0.05)},${n(ry - roofH)}` +
      `h${n(w * 0.1)}L${n(x + w / 2 + eave)},${n(ry + 6)}Z" fill="${C.roof}"/>`,
    `<path d="M${n(x - w * 0.09)},${n(ry - roofH)}h${n(w * 0.18)}v3h${n(-w * 0.18)}Z" fill="${C.ridge1Rim}" opacity="0.5"/>`,
  ];
  const nWin = w > 56 ? 2 : 1;
  for (let i = 0; i < nWin; i++) {
    const wx = x + (nWin === 1 ? 0 : (i - 0.5) * w * 0.44);
    const ww = w * 0.15;
    const wh = wallH * 0.46;
    const wy = baseY - wallH * 0.7;
    s.push(
      `<ellipse cx="${n(wx)}" cy="${n(wy + wh / 2)}" rx="${n(ww * 1.05)}" ry="${n(wh * 0.95)}" fill="${C.goldGlow}" opacity="0.3"/>`,
      `<rect x="${n(wx - ww / 2)}" y="${n(wy)}" width="${n(ww)}" height="${n(wh)}" fill="${C.gold}"/>`,
    );
    lights.push({ x: wx, y: wy + wh / 2, r: ww * 0.6, op: 0.34, thin: true });
  }
  return s.join('');
}

put(
  `<ellipse cx="760" cy="722" rx="880" ry="24" fill="${C.mist}" opacity="0.4" filter="url(#weich)"/>`,
);

/* Die Standorte: über die ganze Breite verteilt, in zwei Reihen gestaffelt (die
   hintere kleiner und höher = weiter weg). Die MITTE ist bewusst besetzt — dort
   läuft die Lesespalte, und genau dort soll das Bild weitergehen. */
put(
  `<ellipse cx="800" cy="${SHORE - 6}" rx="620" ry="66" fill="${C.glowHorizon}" opacity="0.18" filter="url(#weich)"/>`,
);
[
  [258, 728, 70], [344, 744, 54], [430, 756, 88], [528, 740, 60], [598, 758, 104],
  [700, 746, 68], [772, 760, 80], [852, 742, 52], [922, 758, 94], [1020, 744, 62],
  [1094, 760, 78], [1176, 740, 56], [1244, 756, 84], [1326, 738, 64], [1402, 752, 48],
].forEach(([x, y, w]) => put(minka(x, y, w)));

/* Die obere Reihe: das Dorf klettert den Hang hinauf. Kleiner, höher, weniger
   Fenster — und genau dort, wo sonst zwischen Waldlinie und Ufer ein leeres
   Band stünde. Ein Dorf hat Tiefe, keine Bauflucht. */
[
  [300, 700, 40], [386, 692, 34], [486, 704, 44], [660, 696, 38], [836, 702, 42],
  [980, 692, 36], [1136, 700, 40], [1290, 694, 34], [560, 690, 32], [1206, 706, 38],
].forEach(([x, y, w]) => put(minka(x, y, w)));

/* ═══════════════════════════════════════════════════════════════════════════
   5) DIE LATERNENKETTE — das Gold der Seele, quer über das ganze Dorf
   ═══════════════════════════════════════════════════════════════════════════
   Zwei Katenaren zwischen drei Masten. Eine Kette hängt durch, weil sie hängt;
   eine Gerade mit Punkten darauf ist eine Lichterleiste aus dem Baumarkt. Der
   Draht ist bewusst im Steinton statt im Vordergrundschwarz — eine unsichtbare
   Schnur macht aus der Kette wieder Streulichter. */
function catenary(x0, y0, x1, y1, sag, steps = 34) {
  return Array.from({ length: steps + 1 }, (_, i) => {
    const t = i / steps;
    return [x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + Math.sin(Math.PI * t) * sag];
  });
}
function lanternChain(pts, count, idx) {
  const s = [
    `<path d="M${asLine(pts)}" fill="none" stroke="${C.ridge1Rim}" stroke-width="2" opacity="0.9"/>`,
  ];
  for (let i = 1; i < count; i++) {
    const [px, py] = pts[Math.round((i / count) * (pts.length - 1))];
    const rr = 5.6;
    s.push(
      `<g class="l${(i + idx) % 3}">` +
        `<ellipse cx="${n(px)}" cy="${n(py + 14)}" rx="${n(rr * 1.6)}" ry="${n(rr * 1.5)}" fill="${C.goldGlow}" opacity="0.3"/>` +
        `<path d="M${n(px)},${n(py)}v7" stroke="${C.ridge1Rim}" stroke-width="1.2"/>` +
        `<ellipse cx="${n(px)}" cy="${n(py + 14)}" rx="${f(rr)}" ry="${f(rr * 1.3)}" fill="${C.gold}"/>` +
        `<ellipse cx="${n(px)}" cy="${n(py + 14)}" rx="${f(rr * 0.4)}" ry="${f(rr * 0.72)}" fill="${C.goldDeep}" opacity="0.6"/>` +
        `</g>`,
    );
    lights.push({ x: px, y: py + 14, r: rr * 0.95, op: 0.44 });
  }
  return s.join('');
}
for (const [mx, my] of [
  [206, 646],
  [800, 612],
  [1400, 640],
]) {
  put(`<path d="M${mx},${my}V${my + 130}" stroke="${C.ridge3}" stroke-width="3.4"/>`);
}
put(lanternChain(catenary(206, 646, 800, 612, 68), 14, 0));
put(lanternChain(catenary(800, 612, 1400, 640, 74), 14, 1));

/* ═══════════════════════════════════════════════════════════════════════════
   6) DAS REISFELD — der Spiegel, der das untere Viertel trägt
   ═══════════════════════════════════════════════════════════════════════════
   Jedes Licht kommt hier ein zweites Mal vor, gebrochen: nicht als Abbild,
   sondern als Stapel kurzer Striche, die nach unten breiter und schwächer
   werden. So sieht eine Spiegelung auf bewegtem Wasser aus — und sie ist der
   Grund, warum das untere Viertel dieses Bildes der hellste Teil ist statt der
   leerste. */
put(`<path d="M-40,${SHORE}H${W + 40}V${H + 10}H-40Z" fill="url(#wasser)"/>`);
// Die Uferlinie selbst: ein heller Streifen, wo das Wasser den Himmel fängt.
put(
  `<path d="M-40,${SHORE + 1}H${W + 40}" stroke="${C.ridge1Rim}" stroke-width="3" opacity="0.72"/>`,
);
put(
  `<ellipse cx="840" cy="850" rx="500" ry="76" fill="${C.haze}" opacity="0.34" filter="url(#weich)"/>`,
);
put(`<g fill="${C.starS}">`);
for (let i = 0; i < 34; i++) {
  put(
    `<circle cx="${n(between(40, W - 40))}" cy="${n(between(SHORE + 14, H - 24))}" r="${f(between(0.7, 1.5))}" opacity="${f(between(0.2, 0.5))}"/>`,
  );
}
put(`</g>`);
lights.forEach((L, i) => {
  if (L.thin && i % 3 !== 0) return; // Fensterlichter nur jedes dritte spiegeln
  const s = [];
  const top = SHORE + 5 + (SHORE - L.y) * 0.12;
  for (let k = 0; k < 7; k++) {
    const y = top + k * 22;
    if (y > H - 8) break;
    s.push(
      `<ellipse cx="${n(L.x + between(-5, 5))}" cy="${n(y)}" rx="${n(L.r * (1.3 + k * 0.62))}" ry="2" opacity="${f(L.op * (1 - k / 7.8))}"/>`,
    );
  }
  put(`<g class="w${i % 3}" fill="${C.gold}">${s.join('')}</g>`);
});
/* DIE DÄMME (畦). Ohne sie ist das hier ein SEE, und ein See im unteren Viertel
   liest sich als graue Platte — genau der Vorwurf „Tapete" aus der Regie. Ein
   Reisfeld ist in Becken geteilt, und die schmalen Erdwälle dazwischen fangen
   das Sternlicht: vier durchgehende helle Linien, nach vorn enger gestaffelt,
   weil Perspektive das so macht. Sie kosten vier Zeilen und geben dem unteren
   Viertel eine Tiefenachse, die keine Aufhellung ersetzen kann.
   Bezahlt sind sie aus den ruhigen Wasserzuegen (30 -> 23): das 80-KB-Budget des
   Generators ist echt, und sieben Zuege weniger sieht niemand. */
put(`<g stroke="${C.ridge1Rim}" fill="none" stroke-width="1.6" opacity="0.42">`);
[26, 74, 136, 212].forEach((dy) => {
  const y = SHORE + dy;
  put(`<path d="M-40,${n(y)}Q${n(W * 0.5)},${n(y - 7)} ${W + 40},${n(y + 4)}"/>`);
});
put(`</g>`);
// Ruhige waagerechte Züge — Wasser hat Richtung.
put(`<g stroke="${C.water}" stroke-width="1.2" opacity="0.55">`);
for (let i = 0; i < 23; i++) {
  put(
    `<path d="M${n(between(-20, W - 200))},${n(SHORE + 16 + i * 9.6 + between(-2, 2))}h${n(between(160, 680))}"/>`,
  );
}
put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   7) DER VORDERGRUND — Torii im Wasser, große Zeder links, Steinlaterne rechts
   ═══════════════════════════════════════════════════════════════════════════
   Ohne Vordergrund gibt es keine Tiefe, nur eine Tapete. Diese drei Dinge
   stehen NAH: groß, dunkel, und sie schneiden die Bühne an. */

// Das Torii steht IM Reisfeld und setzt seine Pfosten als Spiegelung fort.
{
  const x = 1136;
  const base = 916;
  const h = 196;
  const w = 156;
  put(
    `<g fill="${C.stone}">` +
      `<path d="M${x - w / 2 - 3},${base}l4,${-h}h9l4,${h}Z"/>` +
      `<path d="M${x + w / 2 + 3},${base}l-4,${-h}h-9l-4,${h}Z"/>` +
      `<rect x="${x - w / 2 - 8}" y="${n(base - h * 0.64)}" width="${w + 16}" height="9"/>` +
      `<path d="M${x - w / 2 - 38},${n(base - h + 6)}Q${x},${n(base - h - 20)} ${x + w / 2 + 38},${n(base - h + 6)}` +
      `l0,11Q${x},${n(base - h - 6)} ${x - w / 2 - 38},${n(base - h + 17)}Z"/>` +
      `</g>` +
      `<g opacity="0.34" fill="${C.stone}">` +
      `<path d="M${x - w / 2 - 3},${base}l4,46h9l4,-46Z"/>` +
      `<path d="M${x + w / 2 + 3},${base}l-4,46h-9l-4,-46Z"/>` +
      `</g>`,
  );
}

// Die große Zeder: der linke Pfeiler des Bildes (die Rolle, die bei Hanashigure
// die Pagode spielt). Sie reicht bis weit in den Sternenhimmel.
put(`<path d="M206,1015V760h14v255Z" fill="${C.near}"/>`);
put(cedar(213, 1015, 700, 224, C.near, 0.7));
put(cedar(322, 1010, 430, 140, C.near, 0.8));

// Die Steinlaterne am rechten Ufer: der eine Punkt Licht ganz vorn. Ihr Stein
// ist NICHT schwarz — sonst bleibt vom Umriss nur der leuchtende Kasten übrig.
{
  const x = 1394;
  const b = 962;
  put(
    `<g fill="${C.stone}">` +
      `<path d="M${x - 46},${b}h92l-13,-21h-66Z"/>` +
      `<path d="M${x - 13},${b - 21}h26v-48h-26Z"/>` +
      `<path d="M${x - 31},${b - 69}h62v-9h-62Z"/>` +
      `<path d="M${x - 28},${b - 78}h56v-42h-56Z"/>` +
      `<path d="M${x - 56},${b - 120}q28,-31 56,0l-9,-31q-19,-14 -38,0Z"/>` +
      `<path d="M${x - 7},${b - 155}q7,-17 14,0Z"/>` +
      `</g>` +
      `<ellipse cx="${x}" cy="${b - 99}" rx="24" ry="21" fill="${C.goldGlow}" opacity="0.3"/>` +
      `<rect x="${x - 15}" y="${b - 111}" width="30" height="25" fill="${C.gold}"/>`,
  );
  lights.push({ x, y: b - 99, r: 13, op: 0.3 });
}

// Schilf am unteren Rand — über die volle Breite, damit der Bildrand nicht als
// gerade Linie endet.
put(`<g stroke="${C.near}" fill="none">`);
for (let i = 0; i < 30; i++) {
  const x = between(-20, W + 20);
  const h = between(40, 132);
  const bend = between(-28, 28);
  put(
    `<path d="M${n(x)},${H + 8}Q${n(x + bend * 0.4)},${n(H + 8 - h * 0.6)} ${n(x + bend)},${n(H + 8 - h)}" stroke-width="${f(between(1.2, 2.6))}"/>`,
  );
  if (R() < 0.42) {
    put(`<ellipse cx="${n(x + bend)}" cy="${n(H + 4 - h)}" rx="2" ry="7" fill="${C.near}" stroke="none"/>`);
  }
}
put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   8) DER HERZSCHLAG — acht Uhren für die Sterne, drei fürs Gold, drei fürs Wasser
   ═══════════════════════════════════════════════════════════════════════════
   Alle Keyframes laufen von 1 NACH UNTEN und zurück (Regel (2)): der gemalte
   Zustand ist der hellste, die Animation nimmt nur weg. Die Kurven sind bewusst
   UNSYMMETRISCH — ein kurzes Aufklaren in einer langen ruhigen Phase liest sich
   als Funkeln, ein Sinus liest sich als Pulsschlag.

   Warum das hier steht und nicht in der Themen-CSS: die Ebene wird per
   `background-image: url(...)` geladen, und darin läuft nur, was IM Bild steht.
   Chrome animiert SVG-Bilder deklarativ mit (nachgemessen, s. RESULT.md); fällt
   das irgendwo aus, bleibt das Bild in seinem HELLSTEN Zustand stehen — also
   vollständig und kontrastgeprüft, nur eben still. Der zweite, gröbere
   Herzschlag (das Flimmern der CSS-Ebene) läuft davon unabhängig. */
const css = [
  /* Das Band selbst atmet. Es ist die mit Abstand GRÖSSTE bewegte Fläche des
     Bildes, und genau deshalb steht es hier: ein Sternfeld ändert ein paar
     hundert Pixel, das Band ändert Zehntausende — um jeweils sehr wenig. Das
     eine liest das Auge als Funkeln, das andere als „der Himmel lebt". Beides
     zusammen ist der Herzschlag; eins allein wäre entweder Konfetti oder Nebel.
     47 s, teilerfremd zu allen anderen Uhren, und wie überall: nur nach unten. */
  '.mw{animation:mwa 47s ease-in-out infinite;animation-delay:-11s}',
  '@keyframes mwa{0%,100%{opacity:1}50%{opacity:.66}}',
];
[21, 24, 27, 29, 32, 35, 39, 43].forEach((p, i) => {
  const delay = -(p * (i * 0.137 + 0.05)).toFixed(1);
  const mid = (0.5 + (i % 3) * 0.06).toFixed(2);
  const lo = (0.16 + (i % 4) * 0.05).toFixed(2);
  css.push(
    `.f${i}{animation:fu${i} ${p}s ease-in-out infinite;animation-delay:${delay}s}`,
    `.h${i}{animation:hu${i} ${p + 6}s ease-in-out infinite;animation-delay:${delay}s}`,
    `@keyframes fu${i}{0%,100%{opacity:1}18%{opacity:${mid}}42%{opacity:${lo}}64%{opacity:${mid}}}`,
    `@keyframes hu${i}{0%,100%{opacity:1}12%{opacity:.6}30%{opacity:${(0.24 + (i % 3) * 0.07).toFixed(2)}}55%{opacity:.78}}`,
  );
});
[
  [26, 0.5],
  [31, 0.42],
  [37, 0.58],
].forEach(([p, lo], i) => {
  css.push(
    `.l${i}{animation:la${i} ${p}s ease-in-out infinite;animation-delay:${-p * 0.31 * (i + 1)}s}`,
    `@keyframes la${i}{0%,100%{opacity:1}50%{opacity:${lo}}}`,
  );
});
[
  [23, 0.28],
  [28, 0.2],
  [33, 0.36],
].forEach(([p, lo], i) => {
  css.push(
    `.w${i}{animation:sp${i} ${p}s ease-in-out infinite;animation-delay:${-p * 0.23 * (i + 1)}s}`,
    `@keyframes sp${i}{0%,100%{opacity:1}34%{opacity:${lo}}68%{opacity:${(lo + 0.28).toFixed(2)}}}`,
  );
});

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
  `role="img" aria-label="Sternschnuppen-Nacht: die Milchstrasse ueber einem Dorf am Reisfeld">` +
  `<style>${css.join('')}` +
  `@media(prefers-reduced-motion:reduce){*{animation:none!important}}</style>` +
  `<defs>` +
  // Der Hof der hellen Sterne — ein Verlauf, den sich alle zweiundzwanzig teilen.
  `<radialGradient id="hof">` +
  `<stop offset="0" stop-color="${C.starM}" stop-opacity="0.42"/>` +
  `<stop offset="0.45" stop-color="${C.starM}" stop-opacity="0.14"/>` +
  `<stop offset="1" stop-color="${C.starM}" stop-opacity="0"/>` +
  `</radialGradient>` +
  `<filter id="weich" x="-30%" y="-70%" width="160%" height="240%">` +
  `<feGaussianBlur stdDeviation="30"/></filter>` +
  `<linearGradient id="wasser" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.water}"/>` +
  `<stop offset="1" stop-color="${C.waterFar}"/></linearGradient></defs>` +
  out.join('') +
  `</svg>\n`;

writeFileSync(OUT, svg);
const bytes = Buffer.byteLength(svg);
console.log(
  `nagareboshi-szene.svg  ${(bytes / 1024).toFixed(1)} KB  ·  ${lights.length} Lichter  ·  Safe-Zone x${SAFE.x0}–${SAFE.x1}, y≥${SAFE.y0}`,
);
if (bytes > 80 * 1024) {
  console.error('✗ über dem 80-KB-Budget der ORDER');
  process.exit(1);
}
