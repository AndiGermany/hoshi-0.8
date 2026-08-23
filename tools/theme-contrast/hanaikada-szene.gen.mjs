/**
 * HANAIKADA (花筏) — Kirschblütenwald am Fluss, und auf dem Fluss das Blütenfloß.
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Andis Bestellung (21.08.): „Ein Thema, was von einem Kirschblütenwald inspiriert
 * ist. Hier soll ein Fluss in der Szene vorkommen. Die Kirschblüten sollen animiert
 * fallen. Du hast die Messlatte hoch angesetzt."
 *
 * DIE ABGRENZUNG ZU HANASHIGURE ist der Bauplan, nicht die Farbe. Hanashigure ist
 * STADT im Blütenregen: Pagode vorn links, Torii im Nebel, nasser Stein. Ein
 * BAUWERK trägt das Bild, und die Blüten fallen als seltener Gast in der rechten
 * Randspalte. Hier steht kein Bauwerk. Der Hauptdarsteller ist Natur in zwei
 * Sätzen: der WALD (drei Tiefenränge Stämme unter einem Blütendach über der
 * vollen Breite) und der FLUSS, der das untere Drittel füllt — und auf ihm das
 * namensgebende Bild, das Blütenfloß.
 *
 * ZWEI HERZSCHLÄGE, beide IM SVG (das ist der Unterschied zu Hanashigure, dessen
 * vier Blüten in der CSS wohnen und darum im Frame-Beweis unbewegt bleiben —
 * `--virtual-time-budget` treibt die SVG-interne Uhr, nicht die des Wirtsdokuments,
 * REZEPT Abschnitt E):
 *   1. FALLENDE BLÜTEN über die ganze Breite, vier Größen- und Tempoklassen, jede
 *      Blüte mit eigener Fall- und eigener Taumel-Uhr. Kein Ereignis, sondern
 *      Dauerzustand.
 *   2. DAS TREIBENDE FLOSS: sieben Blütenbänder auf verschiedenen Wassertiefen,
 *      jedes eine nahtlos gekachelte Endlosschleife mit EIGENER Geschwindigkeit —
 *      fern langsam, nah schnell. Diese Parallaxe ist der Grund, warum der Fluss
 *      als liegende Fläche liest und nicht als Streifen.
 *
 * ═══ WARUM DIESES BILD HOCHTONIG IST — der teuerste Befund dieser Runde ════════
 *
 * Der erste Wurf hatte eine normale Tag-Palette: Stämme bei L 0.33–0.56, Wasser
 * bei L 0.69, nahes Ufer bei L 0.535. Gemessen war er tadellos. ANGESEHEN war er
 * unbrauchbar: der Schleier, den diese Tonwerte erzwingen (Alpha 0.90), hatte das
 * ganze Bild in Milch ertränkt. Das Blütenfloß — der NAMENSGEBER — war nur noch
 * am äußersten rechten Bildrand zu sehen, dort, wo der Schleier endet.
 *
 * Die Rechnung dahinter ist steil und war vorher nicht offensichtlich. Nötiges
 * Schleier-Alpha, damit --text-4 auf 4,5:1 kommt, je nach dunkelstem Bildpunkt:
 *     L 0.54 → 0.88     L 0.70 → 0.79     L 0.82 → 0.58
 *     L 0.60 → 0.86     L 0.74 → 0.75     L 0.86 → 0.41
 * Zwischen L 0.60 und L 0.82 liegen keine zwei Blendenstufen, aber der halbe
 * Schleier. Bei 1366 px Fenster steht die 920er Lesespalte über 67 % der Breite —
 * es gibt also keine Fassung, in der ein dunkles Bild unter der Spalte auch nur
 * ansatzweise sichtbar bliebe.
 *
 * Daraus folgt der Bauplan, nicht als Kompromiss, sondern als Motiv: ES IST EIN
 * HELLER TAG. Alles, was über die volle Breite läuft, ist hochtonig gemalt
 * (Boden L 0.80 im Wald, L 0.835 über dem Wasser). Die TIEFE kommt nicht aus
 * Helligkeit, sondern aus Farbe — Sakura-Rosa gegen Fluss-Blaugrün ist ein
 * kräftiges Paar, und Farbkontrast überlebt einen Schleier weit besser als
 * Tonwertkontrast. Und die DUNKLEN Töne bekommen einen eigenen Ort: die beiden
 * Vordergrundbäume, das Schilf, das tiefe Wasser am Bildrand stehen
 * ausschließlich in den Randspalten (x < 340, x > 1260), wo nie ein Buchstabe
 * sitzt und der Schleier bei null steht. Dort geht die Palette bis L 0.33
 * hinunter — das Bild hat seinen vollen Umfang, nur eben nicht überall.
 *
 * Der Schleier folgt dieser Bänderung: 0.72 über dem Wald, 0.63 über dem Fluss.
 * Genau dort, wo das Hauptmotiv liegt, ist er am dünnsten.
 *
 * DER VERTRAG STEHT IM GENERATOR, nicht in der Nachmessung: `claim()` kennt die
 * drei Höhenbänder und ihre Böden und bricht ab, sobald eine Form zu dunkel in
 * die Lesespalte ragt. Keine spätere Änderung kann den Vertrag still brechen.
 * Umgekehrt zu den Nacht-Themen gilt: HELL ist harmlos. Fallende Blüten und Floß
 * sind heller als der Grund, über den sie ziehen — Bewegung kann den Kontrast nur
 * VERBESSERN. Deshalb braucht keine Keyframe hier eine opacity-Klausel; die
 * Bewegung ist rein geometrisch, und der eingefrorene Zustand ist kontrastgleich.
 *
 * Fester Zufalls-Startwert: derselbe Lauf liefert bitgleich dasselbe Bild.
 *
 *   node tools/theme-contrast/hanaikada-szene.gen.mjs
 *   → frontend/public/themes/hanaikada-szene.svg
 */

import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', '..', 'frontend', 'public', 'themes', 'hanaikada-szene.svg');

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
const R = rng(0x8a17c40b);
const between = (lo, hi) => lo + R() * (hi - lo);

/* ── OKLCH → sRGB, mit Gamut-Riegel ──────────────────────────────────────────
   Themen-CSS und Zeichnung kommen aus DERSELBEN Zahl. Klippen wäre hier besonders
   heimtückisch: Rosa liegt bei hoher Helligkeit dicht am Gamut-Rand, und ein
   geklippter Blaukanal macht aus Rosa still ein Lachs. Also laut abbrechen. */
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

/* ── Die Chroma-Decke ────────────────────────────────────────────────────────
   Weil dieses Bild hochtonig ist, arbeitet es überall dicht an der Sättigungs-
   grenze von sRGB — und die ist bei Rosa brutal: bei L 0.93 im Rosaton ist schon
   bei Chroma 0.037 Schluss, ein Fünftel dessen, was ein Grün an derselben Stelle
   darf. Genau deshalb wird hier nicht geschätzt: `okc` rechnet die Decke per
   Bisektion aus und meldet, wie viel Prozent davon ein Wert benutzt. Alle Werte
   stehen bei ≤ 92 %. Wer eine Farbe satter will, sieht sofort, ob noch Luft ist.
   Praktische Folge fürs Bild: ferne Kronen KÖNNEN nicht rosa sein, sie sind fast
   weiß. Das ist keine Schwäche, das ist Luftperspektive, die die Physik schenkt. */
const CEIL = [];
function okc(L, C, h, name) {
  let lo = 0;
  let hi = 0.4;
  for (let i = 0; i < 28; i++) {
    const m = (lo + hi) / 2;
    let fits = true;
    try {
      ok(L, m, h);
    } catch {
      fits = false;
    }
    if (fits) lo = m;
    else hi = m;
  }
  CEIL.push({ name, use: C / lo });
  return ok(L, C, h);
}

/* ── Die Palette des hellen Tages ────────────────────────────────────────────
   Zwei Familien und ein Bindeglied:
     • SAKURA (h 1…14) trägt Kronen, Floß und fallende Blüte — EINE Blütenfarbe
       in fünf Helligkeiten, damit das Bild nicht drei verschiedene Rosas hat.
     • FLUSS-BLAUGRÜN (h 194…206) trägt Wasser, Strömung und Spiegelung.
     • WASHI (h 34…70) ist Himmel und Licht, das Papier, auf dem beide liegen.
   Der Ufer-Grünton (h 126…134) ist bewusst gedämpft: er soll die beiden
   Hauptfarben tragen, nicht mit ihnen konkurrieren.

   `_edge`-Töne sind die dunklen — sie dürfen NUR in den Randspalten vorkommen.
   Der Vertrag (claim) setzt das durch, nicht die Disziplin. */
const C = {
  sky: okc(0.978, 0.011, 70, 'sky'),
  skyWarm: okc(0.966, 0.016, 40, 'skyWarm'),
  skyBlue: okc(0.952, 0.026, 226, 'skyBlue'),
  hazeFar: okc(0.952, 0.022, 10, 'hazeFar'),

  crownFar: okc(0.932, 0.032, 8, 'crownFar'),
  crownFarHi: okc(0.958, 0.019, 16, 'crownFarHi'),
  crownMid: okc(0.898, 0.05, 6, 'crownMid'),
  crownMidHi: okc(0.929, 0.033, 14, 'crownMidHi'),
  crownNear: okc(0.828, 0.094, 4, 'crownNear'),
  crownNearHi: okc(0.878, 0.062, 10, 'crownNearHi'),
  crownFore: okc(0.807, 0.113, 3, 'crownFore'),
  crownForeHi: okc(0.858, 0.071, 8, 'crownForeHi'),
  /* Nur Randspalte: die satteste Blüte des Bildes, an den beiden Vordergrund-
     bäumen. Sie ist der Beweis, dass die Palette mehr kann als Pastell. */
  crownEdge: okc(0.735, 0.145, 1, 'crownEdge'),
  crownEdgeHi: okc(0.796, 0.109, 6, 'crownEdgeHi'),

  trunkFar: ok(0.876, 0.026, 58),
  trunkMid: ok(0.838, 0.044, 56),
  trunkNear: ok(0.804, 0.082, 52),
  trunkEdge: ok(0.36, 0.032, 32),
  branchEdge: ok(0.412, 0.03, 34),
  /* Die hellen Partner der Randtoene: knapp ueber dem Wald-Boden 0.80 bzw. dem
     Fluss-Boden 0.835, damit der Verlauf in der Spalte immer legal ist. Stamm und
     Ast reichen vom oberen bis zum unteren Bildrand und durchqueren damit BEIDE
     Baender — fuer sie gilt der strengere der beiden Boeden. */
  trunkHaze: ok(0.842, 0.062, 50),
  branchHaze: ok(0.845, 0.058, 50),
  reedHaze: okc(0.845, 0.1, 126, 'reedHaze'),
  bankHaze: okc(0.848, 0.105, 126, 'bankHaze'),

  bankFar: okc(0.885, 0.082, 124, 'bankFar'),
  bankFarDk: okc(0.85, 0.105, 122, 'bankFarDk'),
  bankNear: okc(0.878, 0.095, 126, 'bankNear'),
  bankNearDk: okc(0.842, 0.115, 124, 'bankNearDk'),
  bankEdge: ok(0.5, 0.085, 126),
  reedEdge: ok(0.4, 0.072, 124),

  waterFar: okc(0.912, 0.055, 222, 'waterFar'),
  waterMid: okc(0.884, 0.082, 218, 'waterMid'),
  waterNear: okc(0.86, 0.1, 214, 'waterNear'),
  waterDeep: okc(0.84, 0.112, 211, 'waterDeep'),
  current: okc(0.945, 0.03, 222, 'current'),
  currentDk: okc(0.842, 0.104, 213, 'currentDk'),
  waterEdge: ok(0.66, 0.075, 213),
  stone: okc(0.845, 0.014, 250, 'stone'),
  stoneLit: okc(0.9, 0.011, 246, 'stoneLit'),

  petalPale: okc(0.945, 0.026, 10, 'petalPale'),
  petalLight: okc(0.912, 0.043, 8, 'petalLight'),
  petalMid: okc(0.878, 0.066, 5, 'petalMid'),
  petalDeep: okc(0.845, 0.086, 3, 'petalDeep'),
  /* Nur Randspalte (Uferspülsaum ganz vorn, außerhalb der Spalte). */
  petalEdge: okc(0.73, 0.15, 1, 'petalEdge'),
};

/* ── Bühne ───────────────────────────────────────────────────────────────────
   1600×1000, gehängt mit `cover` + `center bottom`. Der Boden ist also fest, oben
   wird beschnitten: bei 1366×1024 gar nicht, bei einem breit-flachen Fenster bis
   zu ~150 px. Alles, was das Bild TRÄGT, liegt darum unterhalb y = 130.

   COL ist die Spur der 920er Lesespalte in viewBox-Einheiten (bei 1366 px Fenster
   deckt sie x 351…1249; 340…1260 ist der Sicherheitszuschlag).

   BANDS sind die drei Höhenbänder mit ihren Helligkeitsböden. Sie entsprechen den
   drei Stufen des Schleiers in hanaikada.css — die Zahlen hier und dort sind EIN
   Vertrag in zwei Dateien, und keine der beiden darf allein geändert werden. */
const W = 1600;
const H = 1000;
/* WIE BREIT DIE LESESPALTE WIRKLICH IST — die zweite teure Lektion.
   340…1260 galt nur fuer 1366x1024. Die Spalte ist 920 VIEWPORT-Pixel breit, das
   Bild haengt mit `cover`; wie viele viewBox-Einheiten sie ueberdeckt, haengt
   also von der cover-Skala s = max(B/1600, H/1000) ab:
       linke Spaltenkante = 800 - 460/s
       1600x1000  s 1.000 -> 340      1440x900  s 0.900 -> 289
       1366x1024  s 1.024 -> 351      1280x800  s 0.800 -> 225
   Auf einem gewoehnlichen 1440x900-Laptop greift die Spalte also 51 Einheiten
   weiter nach aussen, als die erste Fassung angenommen hatte — genau dort standen
   die dunklen Vordergrundaeste. Die Messung hat es gefunden (text-4 3,32:1),
   nicht das Auge.
   Solange nicht BEIDE Fenstermasse klein sind, ist s >= 0.8; die Spalte liegt
   dann garantiert in 225…1375. Darunter (Fenster schmaler als 1280 UND flacher
   als 800) uebernimmt eine Medienabfrage in hanaikada.css und hebt den Schleier
   auf 0.90 — das deckt selbst den dunkelsten Vordergrundstamm bei L 0.36. */
const COL = { x0: 225, x1: 1375 };

/* Die DUNSTZONE. Elemente, die vom Bildrand nach innen greifen wollen — Aeste,
   Vordergrundkronen, Schilf —, muessen an der Spaltenkante hell sein. Statt sie
   zu kuerzen (und dem Bild sein bestes Element zu nehmen), bekommen sie einen
   waagerechten Verlauf: dunkel am Rand, ab x 210 der helle Ton des Bandes. Das
   ist zugleich Luftperspektive und Vertrag — ein Ast, der in den Dunst der
   Bildmitte laeuft, verliert dort seine Tiefe, genau wie in Wirklichkeit. */
const HAZE = { dark: 120, light: 210 };
const hazeGrad = (id, dark, light) =>
  `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="${W}" y2="0">` +
  `<stop offset="0" stop-color="${dark}"/>` +
  `<stop offset="${f(HAZE.dark / W)}" stop-color="${dark}"/>`.replace('0.1', '.1') +
  `<stop offset="${(HAZE.light / W).toFixed(3)}" stop-color="${light}"/>` +
  `<stop offset="${(1 - HAZE.light / W).toFixed(3)}" stop-color="${light}"/>` +
  `<stop offset="${(1 - HAZE.dark / W).toFixed(3)}" stop-color="${dark}"/>` +
  `<stop offset="1" stop-color="${dark}"/></linearGradient>`;
/* Die Bandgrenze liegt am TIEFSTEN Punkt der fernen Uferlinie (y 601) zuzueglich des Stammfuss-Spiels (+16), nicht am
   höchsten: solange dort noch Ufer oder Stammfuß stehen kann, gilt der strengere
   Wald-Boden. Der erste Ansatz setzte sie auf 560 und der Vertragsprüfer fing
   prompt einen Stammfuß bei y 573, der gegen den Wasser-Boden gemessen wurde —
   genau dafür ist die Prüfung da. */
const RIVER_TOP = 622;
const FLOOR_FOREST = 0.8; //  Schleier 0.72 darüber
const FLOOR_RIVER = 0.835; //  Schleier 0.63 darüber

const out = [];
const put = (s) => out.push(s);
const n = (v) => Math.round(v);
const f = (v) => Math.round(v * 10) / 10;

/* Byte-Buchhaltung. Das 80-KB-Budget ist knapp genug, dass Trimmen ohne Zahlen
   Raten wäre — und Raten kostet am Ende das Hauptmotiv. */
const bill = [];
let lastMark = 0;
function mark(label) {
  const now = out.join('').length;
  bill.push([label, now - lastMark]);
  lastMark = now;
}

/* Der Selbstprüfer. Jede Form meldet Helligkeit, x-Bereich und Höhenband an;
   ragt sie zu dunkel in die Lesespalte, bricht der Lauf ab. */
let worst = { margin: 9, what: '—' };
function claim(L, x0, x1, what, y = 0) {
  if (x1 < COL.x0 || x0 > COL.x1) return; //  Randspalte: der Schleier ist dort null
  const floor = y >= RIVER_TOP ? FLOOR_RIVER : FLOOR_FOREST;
  if (L < floor) {
    throw new Error(
      `Lesbarkeits-Vertrag verletzt: „${what}" malt L ${L} bei x ${n(x0)}…${n(x1)}, y ${n(y)} ` +
        `in die Lesespalte (Boden ${floor} in diesem Band)`,
    );
  }
  if (L - floor < worst.margin) worst = { margin: L - floor, what, L, floor };
}

/* ── Blüten-Vorrat ───────────────────────────────────────────────────────────
   Floß und Uferspülsaum bestehen aus vielen hundert Blüten. Als eigener
   `transform` kostet jede ~80 Bytes; als Verweis auf eine fertig gedrehte und
   skalierte Variante nur ~31. Die Rundung auf sechs Größen- und fünf Drehstufen
   ist bei 2–9 Einheiten Blütengröße unsichtbar — anders als bei der Krone, wo
   jede Silhouette einzeln gezeichnet bleibt. */
const VARIANTS = [];
const SCALES = [0.03, 0.05, 0.075, 0.105, 0.145, 0.195];
const ROTS = [8, 62, 131, 204, 287];
/* Ein-Zeichen-IDs. Bei rund tausend Blueten sind drei gesparte Bytes je Verweis
   drei Kilobyte — an einem 80-KB-Budget ist das kein Geiz, sondern eine Krone
   mehr im Vordergrund. */
const IDS = 'abcdefghijklmnopqrstuvwxyzACDEF';
const vid = (s, r) => IDS[s * ROTS.length + r];
for (let s = 0; s < SCALES.length; s++)
  for (let r = 0; r < ROTS.length; r++)
    VARIANTS.push(
      `<g id="${vid(s, r)}"><use href="#pt" transform="rotate(${ROTS[r]}) scale(${SCALES[s]})"/></g>`,
    );

function petal(x, y, radius) {
  const want = radius / 50;
  let si = 0;
  for (let i = 1; i < SCALES.length; i++)
    if (Math.abs(SCALES[i] - want) < Math.abs(SCALES[si] - want)) si = i;
  return `<use href="#${vid(si, Math.floor(R() * ROTS.length))}" x="${n(x)}" y="${n(y)}"/>`;
}

/* ── Formen-Werkzeug ─────────────────────────────────────────────────────── */

/** Geschlossene, weiche Beule: Ellipse mit verrauschtem Radius, als EIN path. */
function blob(cx, cy, rx, ry, steps, rough) {
  const p = [];
  for (let i = 0; i < steps; i++) {
    const a = (i / steps) * Math.PI * 2;
    const r = 1 + (R() * 2 - 1) * rough;
    p.push([cx + Math.cos(a) * rx * r, cy + Math.sin(a) * ry * r]);
  }
  const mid = (i, j) => [n((p[i][0] + p[j][0]) / 2), n((p[i][1] + p[j][1]) / 2)];
  let d = `M${mid(steps - 1, 0).join(',')}`;
  for (let i = 0; i < steps; i++)
    d += `Q${n(p[i][0])},${n(p[i][1])} ${mid(i, (i + 1) % steps).join(',')}`;
  return d + 'Z';
}

/** Stützstellen-Sampler: y an der Stelle x, linear zwischen den Kontrollpunkten. */
function at(pts, x) {
  if (x <= pts[0][0]) return pts[0][1];
  const last = pts[pts.length - 1];
  if (x >= last[0]) return last[1];
  for (let i = 1; i < pts.length; i++)
    if (x <= pts[i][0]) {
      const [x0, y0] = pts[i - 1];
      const [x1, y1] = pts[i];
      return y0 + ((y1 - y0) * (x - x0)) / (x1 - x0);
    }
  return last[1];
}

/** Kontrollpunkte als weiche Linie (Q durch Mittelpunkte) — kein Zickzack.
    `cont` haengt die Linie an eine LAUFENDE Kontur an, statt eine neue zu
    beginnen. Das ist keine Bequemlichkeit, sondern ein gefundener Fehler: der
    Fluss-Clip setzt ferne und nahe Uferlinie zu EINER geschlossenen Flaeche
    zusammen, und ein `M` in der Mitte eines Pfades beginnt in SVG eine zweite,
    eigene Teilkontur. Der Fluss war dadurch nicht die Flaeche zwischen den
    Ufern, sondern zwei sich ueberlagernde Halbformen — im Bild sichtbar als
    grosser flacher Gruenkeil quer durch die untere Bildhaelfte, dort, wo das
    ferne Ufer unter dem kaputten Wasser hervorsah. */
function smooth(pts, cont = false) {
  let d = `${cont ? 'L' : 'M'}${n(pts[0][0])},${n(pts[0][1])}`;
  for (let i = 1; i < pts.length - 1; i++)
    d += `Q${n(pts[i][0])},${n(pts[i][1])} ${n((pts[i][0] + pts[i + 1][0]) / 2)},${n(
      (pts[i][1] + pts[i + 1][1]) / 2,
    )}`;
  const l = pts[pts.length - 1];
  return d + `T${n(l[0])},${n(l[1])}`;
}

/* ═══════════════════════════════════════════════════════════════════════════
   1) DER FLUSS ALS SKELETT
   ═══════════════════════════════════════════════════════════════════════════
   Zuerst die Geometrie des Wassers, weil ALLES andere daran hängt. Die ferne
   Uferlinie steigt nach links an und verschwindet dort zwischen den Stämmen —
   der Fluss hat einen Oberlauf, er ist kein Querstreifen (die „Tapete"-Falle aus
   dem REZEPT). Das nahe Ufer liegt tief bei y ≈ 950: der Fluss bekommt damit
   fast 400 Einheiten Höhe, und das Blütenfloß den Platz, den ein Namensgeber
   verdient. Was vom nahen Ufer bleibt, ist ein schmaler Saum am unteren Rand. */

const FAR = [
  [-40, 486],
  [130, 512],
  [310, 552],
  [530, 580],
  [770, 596],
  [1010, 601],
  [1250, 592],
  [1460, 578],
  [1640, 566],
];
const NEAR = [
  [-40, 972],
  [200, 948],
  [450, 955],
  [700, 972],
  [950, 982],
  [1200, 968],
  [1430, 950],
  [1640, 940],
];

const farY = (x) => at(FAR, x);
const nearY = (x) => at(NEAR, x);

/* ═══════════════════════════════════════════════════════════════════════════
   2) HIMMEL UND LUFT
   ═══════════════════════════════════════════════════════════════════════════ */

put(`<rect width="${W}" height="${H}" fill="${C.sky}"/>`);
put(`<rect width="${W}" height="${n(H * 0.64)}" fill="url(#luft)"/>`);
put(`<rect y="250" width="${W}" height="380" fill="${C.hazeFar}" opacity="0.32"/>`);

/* ═══════════════════════════════════════════════════════════════════════════
   3) DER WALD — drei Tiefenränge
   ═══════════════════════════════════════════════════════════════════════════
   Was einen Kirschstamm ausmacht und einen Zedernstamm nicht: er ist NICHT
   gerade. Er neigt sich, verdickt sich am Fuß und teilt sich weit unten in
   mehrere starke Äste, die auseinanderstreben. Und ein Wald ist kein Zaun:
   Stämme stehen in Gruppen, nicht im Raster. Der erste Wurf hatte gleiche
   Abstände, gleiche Breiten und gleiche Höhen und las sich prompt als
   Lattenzaun. Darum hier: Gruppen aus 2–4 Stämmen mit engem Innenabstand und
   weiten Lücken dazwischen, Breiten über den Faktor 3 gestreut, und jede Krone
   sitzt auf IHREM Stamm statt in einem gemeinsamen Band. */

/** Ein Stamm als EIN gefüllter path: Fuß breit, Spitze schmal, mit Neigung. */
function trunkPath(x, baseY, topY, w, lean) {
  const midY = (baseY + topY) / 2;
  const xm = x + lean * 0.42;
  const xt = x + lean;
  const wt = Math.max(2, w * 0.3);
  const wm = Math.max(3, w * 0.56);
  return (
    `M${n(x - w / 2)},${n(baseY)}` +
    `Q${n(xm - wm / 2)},${n(midY)} ${n(xt - wt / 2)},${n(topY)}` +
    `L${n(xt + wt / 2)},${n(topY)}` +
    `Q${n(xm + wm / 2)},${n(midY)} ${n(x + w / 2)},${n(baseY)}Z`
  );
}

/** Die Astgabel: Äste vom oberen Stammdrittel nach außen-oben. */
function branches(xt, topY, spread, up, count) {
  const ds = [];
  for (let i = 0; i < count; i++) {
    const dir = i % 2 === 0 ? 1 : -1;
    const dx = dir * between(spread * 0.45, spread);
    const dy = -between(up * 0.55, up);
    ds.push(
      `M${n(xt)},${n(topY)}Q${n(xt + dx * 0.35)},${n(topY + dy * 0.62)} ${n(xt + dx)},${n(topY + dy)}`,
    );
  }
  return ds;
}

/** Eine Krone. `lod` ist Detailstufe UND Preis: 2 = drei gezeichnete Beulen
    (~700 B), 1 = eine Beule und eine Ellipse (~270 B), 0 = zwei Ellipsen
    (~110 B). Die ferne Kulisse liegt hinter Dunst und bekommt lod 0 — dort wäre
    eine gezeichnete Silhouette bezahlte Information, die niemand sehen kann. */
function crown(cx, cy, rx, ry, fill, hi, lod = 2) {
  const s = [];
  const e = (x, y, a, b, c) =>
    `<ellipse cx="${n(x)}" cy="${n(y)}" rx="${n(a)}" ry="${n(b)}" fill="${c}"/>`;
  if (lod === 0) {
    s.push(e(cx, cy, rx, ry, fill));
    s.push(e(cx + between(-rx * 0.2, rx * 0.2), cy - ry * 0.3, rx * 0.6, ry * 0.5, hi));
    return s.join('');
  }
  s.push(`<path fill="${fill}" d="${blob(cx, cy, rx, ry, lod === 2 ? 10 : 8, 0.34)}"/>`);
  if (lod === 2) {
    s.push(
      `<path fill="${fill}" d="${blob(cx + between(-rx * 0.5, rx * 0.5), cy + between(-ry * 0.4, ry * 0.3), rx * 0.66, ry * 0.8, 8, 0.36)}"/>`,
    );
    s.push(
      `<path fill="${hi}" d="${blob(cx + between(-rx * 0.34, rx * 0.34), cy - ry * 0.34, rx * 0.54, ry * 0.52, 8, 0.38)}"/>`,
    );
  } else {
    s.push(
      `<path fill="${hi}" d="${blob(cx + between(-rx * 0.28, rx * 0.28), cy - ry * 0.3, rx * 0.54, ry * 0.5, 6, 0.36)}"/>`,
    );
  }
  return s.join('');
}

/** Stämme in Gruppen statt im Raster — der Unterschied zwischen Wald und Zaun. */
function grove(step, jitter) {
  const xs = [];
  let x = -40;
  while (x < W + 60) {
    const inGroup = 1 + Math.floor(R() * 3.4);
    for (let i = 0; i < inGroup && x < W + 60; i++) {
      xs.push(x);
      x += step * between(0.16, 0.34);
    }
    x += step * between(jitter, jitter * 1.9);
  }
  return xs;
}

const RANKS = [
  { trunk: C.trunkFar, crown: C.crownFar, hi: C.crownFarHi, L: 0.876, cL: 0.932, lod: 0 },
  { trunk: C.trunkMid, crown: C.crownMid, hi: C.crownMidHi, L: 0.838, cL: 0.898, lod: 1 },
];

for (const r of [0, 1]) {
  const rk = RANKS[r];
  const trunks = [];
  const crowns = [];
  for (const x of grove(r === 0 ? 215 : 240, 0.5)) {
    const base = farY(x) + (r === 0 ? -30 : -10);
    const h = between(r === 0 ? 160 : 220, r === 0 ? 260 : 360);
    const w = r === 0 ? between(5, 14) : between(9, 26);
    const lean = between(-20, 20);
    const topY = base - h;
    trunks.push(`<path d="${trunkPath(x, base, topY, w, lean)}"/>`);
    claim(rk.L, x - w, x + w + Math.abs(lean), `Stamm Rang ${r}`, base);
    const cw = between(58, 116) * (r === 0 ? 0.9 : 1.16);
    crowns.push({ x: x + lean, y: topY - between(2, 34), rx: cw, ry: cw * between(0.5, 0.7) });
  }
  put(`<g fill="${rk.trunk}">${trunks.join('')}</g>`);
  for (const c of crowns) {
    claim(rk.cL, c.x - c.rx, c.x + c.rx, `Krone Rang ${r}`, c.y);
    put(crown(c.x, c.y, c.rx, c.ry, rk.crown, rk.hi, rk.lod));
  }
  mark(`Kulisse Rang ${r}`);
}

/* — Das Blütendach ————————————————————————————————————————————————————
   Der Grund, warum man IM Wald steht und nicht davor: über der ganzen Breite
   hängt Blüte von oben ins Bild. Sie läuft hinter der Lesespalte durch (dafür ist
   der Schleier da) und ist hell — sie kann den Kontrast nur heben. ENTSCHEIDEND
   sind die LÜCKEN: der erste Wurf setzte die Kronen so dicht, dass ein
   geschlossener rosa Balken entstand, und ein Balken ist keine Blüte. Jetzt
   stehen weniger, größere Kronen mit echtem Himmel dazwischen. */
{
  const far = [];
  for (let x = -60; x < W + 100; x += between(300, 470)) {
    const rx = between(130, 205);
    const cy = between(-104, 30);
    claim(0.932, x - rx, x + rx, 'Blütendach fern', cy);
    far.push(crown(x, cy, rx, rx * between(0.44, 0.64), C.crownFar, C.crownFarHi, 1));
  }
  put(far.join(''));
  const mid = [];
  for (let x = -80; x < W + 120; x += between(390, 560)) {
    const rx = between(150, 240);
    const cy = between(-130, -10);
    claim(0.898, x - rx, x + rx, 'Blütendach mittel', cy);
    mid.push(crown(x, cy, rx, rx * between(0.42, 0.6), C.crownMid, C.crownMidHi, 2));
  }
  put(mid.join(''));
  mark('Blütendach');
}

/* — Das ferne Ufer: der Streifen Gras, auf dem der Hain steht ————————————— */
{
  put(`<path fill="${C.bankFar}" d="${smooth(FAR)}L${W + 60},${H}L-60,${H}Z"/>`);
  put(
    `<path fill="${C.bankFarDk}" d="${smooth(FAR.map(([x, y]) => [x, y + 10]))}L${W + 60},${H}L-60,${H}Z"/>`,
  );
  claim(0.85, -40, W + 40, 'fernes Ufer', 590);
  mark('fernes Ufer');
}

/* — Rang 2: der Hain am Ufer, der Kern des Waldes ————————————————————————
   Diese Stämme stehen vor der Kulisse und hinter dem Wasser; sie sind das, was
   man „Wald" nennt, wenn man das Bild in zwei Worten beschreibt. Sie laufen quer
   durch die Lesespalte und bleiben darum bei L 0.804 — hell, aber der dunkelste
   Ton, den das obere Band zulässt, und mit dem wärmsten Stich der drei Ränge. */
{
  const trunks = [];
  const br = [];
  const crowns = [];
  for (const x of grove(270, 0.55)) {
    const base = farY(x) + between(0, 16);
    const h = between(240, 380);
    const w = between(12, 58);
    const lean = between(-48, 48);
    const topY = base - h;
    claim(0.804, x - w - Math.abs(lean), x + w + Math.abs(lean), 'Hain-Stamm', base);
    trunks.push(`<path d="${trunkPath(x, base, topY, w, lean)}"/>`);
    for (const d of branches(x + lean, topY + between(8, 46), between(60, 140), between(80, 170), 4))
      br.push(`<path d="${d}"/>`);
    const rx = between(104, 176);
    crowns.push({ x: x + lean, y: topY - between(16, 62), rx, ry: rx * between(0.5, 0.7) });
  }
  put(`<g fill="${C.trunkNear}">${trunks.join('')}</g>`);
  put(
    `<g stroke="${C.trunkNear}" fill="none" stroke-width="8" stroke-linecap="round">${br.join('')}</g>`,
  );
  for (const c of crowns) {
    claim(0.828, c.x - c.rx, c.x + c.rx, 'Hain-Krone', c.y);
    put(crown(c.x, c.y, c.rx, c.ry, C.crownNear, C.crownNearHi, 2));
  }
  /* Blütenmasse auf Stammhoehe + Unterholz am Ufersaum. */
  const thicket = [];
  for (let x = -60; x < W + 90; x += between(105, 240)) {
    const cy = between(378, 552);
    const rx = between(62, 158);
    claim(0.828, x - rx, x + rx, 'Blütenmasse im Hain', cy);
    thicket.push(crown(x, cy, rx, rx * between(0.44, 0.82), C.crownNear, C.crownNearHi, 1));
  }
  put(thicket.join(''));
  mark('Hain (Rang 2)');
}

/* ═══════════════════════════════════════════════════════════════════════════
   4) DAS WASSER
   ═══════════════════════════════════════════════════════════════════════════
   Ein Verlauf allein wäre eine Tapete (REZEPT F). Struktur kommt aus vier
   Quellen, alle in Fließrichtung nach rechts:
     • die Spiegelung des Waldes direkt unter dem fernen Ufer — Blüte als rosa
       Wolken, Stämme als senkrechte Schlieren,
     • Strömungslinien, die nach vorn länger und breiter werden,
     • zwei Steine, an denen sich Strömung und später das Floß teilen,
     • der GLANZ: ein breites Lichtband quer über die Bildmitte. Es ist kein
       Trick zur Kontrastrettung, sondern das, was man an einem hellen Tag auf
       Wasser wirklich sieht — und es erklärt nebenbei, warum die Bildmitte
       heller sein DARF als die Ränder. Die Ränder bleiben tiefes Wasser. */

const riverClip =
  smooth(FAR) + `L${W + 60},${n(nearY(W + 60))}` + smooth([...NEAR].reverse(), true) + `Z`;
put(`<path fill="url(#fluss)" d="${riverClip}"/>`);
claim(0.84, -40, W + 40, 'Wasser (tiefster Ton)', 800);

put(`<g clip-path="url(#riv)">`);

/* Tiefes Wasser an den Bildrändern — NUR dort, und darum darf es dunkel sein.
   Es rahmt den Fluss und gibt ihm den Tonumfang, den die Mitte nicht haben kann. */
put(`<rect y="${RIVER_TOP}" width="${W}" height="${H - RIVER_TOP}" fill="url(#tief)"/>`);

/* Die Spiegelung: was am Ufer steht, liegt gestaucht im Wasser. */
{
  const refl = [];
  for (let x = -20; x < W + 40; x += between(66, 118)) {
    const y0 = farY(x);
    refl.push(
      `<ellipse cx="${n(x)}" cy="${n(y0 + between(18, 52))}" rx="${n(between(56, 118))}" ry="${n(between(18, 40))}" fill="${C.petalLight}" opacity="0.62"/>`,
    );
  }
  put(refl.join(''));
  const smears = [];
  for (let x = -10; x < W + 30; x += between(72, 124)) {
    const y0 = farY(x);
    smears.push(
      `<rect x="${n(x)}" y="${n(y0)}" width="${n(between(6, 16))}" height="${n(between(50, 150))}" fill="${C.currentDk}" opacity="${f(between(0.38, 0.7))}"/>`,
    );
  }
  put(smears.join(''));
  claim(0.842, -40, W + 40, 'Stammspiegelung', 650);
  mark('Spiegelung');
}

/* Strömungslinien auf Höhenlinien des Wassers (konstanter Tiefenparameter). */
{
  const lines = [];
  for (let i = 0; i < 28; i++) {
    const t = between(0.05, 0.98);
    const x0 = between(-60, W - 40);
    const len = between(110, 340) * (0.45 + t);
    const pts = [];
    for (let s = 0; s <= 3; s++) {
      const x = x0 + (len * s) / 3;
      pts.push([x, farY(x) + (nearY(x) - farY(x)) * (t + Math.sin(s * 1.6 + i) * 0.005)]);
    }
    const bright = R() < 0.55;
    lines.push(
      `<path d="${smooth(pts)}" stroke="${bright ? C.current : C.currentDk}" stroke-width="${f(between(1.6, 2.4) + t * 3.2)}" opacity="${f(between(0.3, 0.6))}"/>`,
    );
  }
  put(`<g fill="none" stroke-linecap="round">${lines.join('')}</g>`);
  claim(0.842, -60, W + 60, 'Strömungslinie', 750);
  mark('Strömungslinien');
}

/* Das Lichtband auf dem Wasser. */
put(`<rect y="${RIVER_TOP}" width="${W}" height="${H - RIVER_TOP}" fill="url(#glanz)"/>`);

/* Zwei Steine im Fluss: der Grund, warum das Floß Bänder bildet statt Fläche. */
for (const [sx, sy, sr] of [
  [470, 872, 44],
  [1178, 786, 30],
]) {
  claim(0.845, sx - sr * 1.5, sx + sr * 1.5, 'Stein', sy);
  put(`<path fill="${C.stone}" d="${blob(sx, sy, sr * 1.45, sr * 0.72, 8, 0.16)}"/>`);
  put(
    `<path fill="${C.stoneLit}" d="${blob(sx - sr * 0.2, sy - sr * 0.26, sr * 0.9, sr * 0.34, 7, 0.2)}"/>`,
  );
  put(
    `<ellipse cx="${n(sx + sr * 0.5)}" cy="${n(sy + sr * 0.62)}" rx="${n(sr * 1.7)}" ry="${n(sr * 0.3)}" fill="${C.current}" opacity="0.6"/>`,
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   5) DAS BLÜTENFLOSS — der Namensgeber, Herzschlag 2
   ═══════════════════════════════════════════════════════════════════════════
   Sieben Bänder auf verschiedenen Wassertiefen. Jedes Band ist EINE Kachel der
   Breite `tile`, mehrfach nebeneinander verwiesen und um genau `tile` nach links
   geschoben — dadurch ist die Schleife nahtlos und läuft ewig ohne Sprung. Die
   Uhren sind gestaffelt: das ferne Band braucht 124 s für eine Kachel, das
   nächste 38 s. Diese Parallaxe IST die Perspektive.

   Ein Band ist nicht Konfetti: zuerst ein weiches Trägerband (die Masse, die man
   aus zehn Metern sieht), darauf Nester aus einzelnen Blüten (was man aus zwei
   Metern sieht). Ohne Trägerband liest sich das Floß als Streusel, ohne die
   Einzelblüten als rosa Schmiere. Und weil das Bild hochtonig ist, trägt hier
   nicht der Tonwert, sondern der FARBTON: Rosa auf Blaugrün. */

const RAFT = [
  { t: 0.06, tile: 430, s: 0.3, dur: 124, dens: 0.3, fill: C.petalPale, hi: C.petalLight },
  { t: 0.17, tile: 470, s: 0.42, dur: 104, dens: 0.45, fill: C.petalPale, hi: C.petalLight },
  { t: 0.3, tile: 510, s: 0.56, dur: 87, dens: 0.8, fill: C.petalLight, hi: C.petalMid },
  { t: 0.45, tile: 550, s: 0.74, dur: 71, dens: 1.0, fill: C.petalLight, hi: C.petalMid },
  { t: 0.6, tile: 590, s: 0.95, dur: 58, dens: 0.95, fill: C.petalMid, hi: C.petalDeep },
  { t: 0.76, tile: 640, s: 1.2, dur: 47, dens: 0.8, fill: C.petalMid, hi: C.petalDeep },
  { t: 0.91, tile: 700, s: 1.5, dur: 38, dens: 0.55, fill: C.petalMid, hi: C.petalDeep },
];

const raftDefs = [];
RAFT.forEach((band, bi) => {
  const yAt = (x) => farY(x) + (nearY(x) - farY(x)) * band.t;
  const tile = band.tile;
  const inner = [];

  const carriers = [];
  const nCar = Math.round(4 * band.dens) + 1;
  for (let i = 0; i < nCar; i++)
    carriers.push({
      cx: (tile * (i + 0.5)) / nCar + between(-60, 60),
      rx: between(46, 132) * (0.6 + band.s * 0.6),
      ry: between(7, 16) * (0.5 + band.s),
      o: between(0.4, 0.72),
      dy: between(-19, 19),
    });

  const nests = [];
  const nNest = Math.round(8 * band.dens) + 2;
  for (let i = 0; i < nNest; i++) {
    const rx = between(40, 110) * (0.55 + band.s * 0.7);
    const ry = between(7, 17) * (0.6 + band.s);
    const seeds = [];
    const cnt = Math.round(between(8, 20) * band.dens);
    for (let k = 0; k < cnt; k++) {
      const a = R() * Math.PI * 2;
      const rr = Math.sqrt(R());
      seeds.push([
        Math.cos(a) * rx * rr,
        Math.sin(a) * ry * rr,
        between(3, 6.4) * band.s,
        R() < 0.4,
      ]);
    }
    nests.push({ cx: (tile * (i + 0.5)) / nNest + between(-46, 46), dy: between(-21, 21), seeds });
  }

  for (const c of carriers)
    inner.push(
      `<ellipse cx="${n(c.cx)}" cy="${n(yAt(c.cx) + c.dy)}" rx="${n(c.rx)}" ry="${n(c.ry)}" fill="${band.fill}" opacity="${f(c.o)}"/>`,
    );
  const deep = [];
  const pale = [];
  for (const nst of nests) {
    const by = yAt(nst.cx) + nst.dy;
    for (const [dx, dy, r, isDeep] of nst.seeds)
      (isDeep ? deep : pale).push(petal(nst.cx + dx, by + dy, r));
  }
  inner.push(`<g fill="${band.fill}">${pale.join('')}</g>`);
  inner.push(`<g fill="${band.hi}">${deep.join('')}</g>`);

  claim(0.845, -40, W + 40, 'Blütenfloß', RIVER_TOP + 200);
  raftDefs.push(`<g id="rb${bi}">${inner.join('')}</g>`);
  /* So viele Kacheln, dass die Bühne auch im ungünstigsten Moment der Schleife
     (Verschiebung um eine volle Kachel nach links) lückenlos belegt ist. */
  const uses = [];
  for (let i = 0; i < Math.ceil(W / tile) + 2; i++)
    uses.push(i === 0 ? `<use href="#rb${bi}"/>` : `<use href="#rb${bi}" x="${i * tile}"/>`);
  put(`<g class="r${bi}" transform="translate(${-tile},0)">${uses.join('')}</g>`);
});
bill.push(['Blütenfloß (Kacheln in defs)', raftDefs.join('').length]);
bill.push(['Blüten-Varianten (defs)', VARIANTS.join('').length]);
mark('Blütenfloß (Verweise)');

put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   6) DAS NAHE UFER — der Saum, auf dem der Betrachter steht
   ═══════════════════════════════════════════════════════════════════════════
   Nur noch ein schmaler Streifen am unteren Rand, damit der Fluss groß sein kann.
   Über die Breite bleibt er hell (L 0.842 — Boden des Fluss-Bandes); die dunklen
   Töne, die ihm Halt geben, stehen ausschließlich in den Randspalten: Schilf,
   Grasbüschel und der satteste Blütensaum des Bildes. */
{
  put(`<path fill="${C.bankNear}" d="${smooth(NEAR)}L${W + 60},${H}L-60,${H}Z"/>`);
  claim(0.878, -40, W + 40, 'nahes Ufer', 960);
  put(
    `<path fill="${C.bankNearDk}" d="${smooth(NEAR.map(([x, y]) => [x, y + between(16, 26)]))}L${W + 60},${H}L-60,${H}Z"/>`,
  );
  claim(0.842, -40, W + 40, 'nahes Ufer, Schatten', 980);

  /* Randspalten: dunkles Ufer, Schilf, satte Blüte. Hier hat das Bild seinen
     vollen Tonumfang — vom hellsten Licht auf dem Wasser bis L 0.36. */
  const edge = [];
  for (const [x0, x1] of [
    [-60, COL.x0 - 20],
    [COL.x1 + 20, W + 60],
  ]) {
    const pts = [];
    for (let x = x0; x <= x1; x += (x1 - x0) / 5) pts.push([x, nearY(x) + between(26, 54)]);
    edge.push(`<path d="${smooth(pts)}L${n(x1)},${H}L${n(x0)},${H}Z"/>`);
  }
  put(`<g fill="url(#gUfer)">${edge.join('')}</g>`);
  claim(0.848, -60, W + 60, 'Uferkante (im Dunst)', 975);

  const wash = [];
  for (let i = 0; i < 90; i++) {
    const x = R() < 0.5 ? between(-30, COL.x0 - 30) : between(COL.x1 + 30, W + 30);
    wash.push(petal(x, nearY(x) + between(-10, 34), between(4, 9)));
  }
  put(`<g fill="url(#gSpuel)">${wash.join('')}</g>`);
  claim(0.845, -60, W + 60, 'Spuelsaum (im Dunst)', 975);

  const reeds = [];
  for (let i = 0; i < 38; i++) {
    const x = R() < 0.5 ? between(-30, COL.x0 - 26) : between(COL.x1 + 26, W + 30);
    const h = between(80, 240);
    const bend = between(-46, 46);
    reeds.push(
      `<path d="M${n(x)},${H + 10}Q${n(x + bend * 0.35)},${n(H + 10 - h * 0.58)} ${n(x + bend)},${n(H + 10 - h)}" stroke-width="${f(between(1.8, 3.8))}"/>`,
    );
  }
  put(`<g stroke="url(#gSchilf)" fill="none" stroke-linecap="round">${reeds.join('')}</g>`);
  claim(0.845, -60, W + 60, 'Schilf (im Dunst)', 980);
  mark('nahes Ufer + Schilf');
}

/* ═══════════════════════════════════════════════════════════════════════════
   7) DIE BEIDEN VORDERGRUNDBÄUME — das Repoussoir
   ═══════════════════════════════════════════════════════════════════════════
   Zwei Stämme ganz vorn, links und rechts, beide vollständig in der Randspalte,
   beide vom unteren Bildrand bis über den oberen hinaus. Sie sind der Grund,
   warum der Wald dahinter Tiefe hat, und der Ort, an dem dieses helle Bild seine
   Dunkelheit unterbringt (L 0.36) und seine satteste Blüte (L 0.735). Ihre
   Kronen greifen weit nach innen und laufen — hell und harmlos — hinter der
   Lesespalte durch; der Stamm selbst tut das nie. */
{
  const foreTrunks = [];
  const foreBranch = [];
  for (const [x, w, lean, top] of [
    [158, 72, -30, -80],
    [1462, 58, 26, 20],
  ]) {
    foreTrunks.push(`<path d="${trunkPath(x, H + 20, top, w, lean)}"/>`);
    claim(0.842, x - w, x + w + Math.abs(lean), 'Vordergrundstamm (im Dunst)', 800);
    const bx = x + lean * 0.42;
    for (let i = 0; i < 5; i++) {
      const dir = x < W / 2 ? 1 : -1;
      const y0 = between(100, 520);
      const dx = dir * between(150, 430);
      const dy = -between(90, 230);
      foreBranch.push(
        `<path d="M${n(bx)},${n(y0)}Q${n(bx + dx * 0.4)},${n(y0 + dy * 0.7)} ${n(bx + dx)},${n(y0 + dy)}" stroke-width="${f(between(5, 13))}"/>`,
      );
    }
  }
  put(`<g fill="url(#gStamm)">${foreTrunks.join('')}</g>`);
  put(`<g stroke="url(#gAst)" fill="none" stroke-linecap="round">${foreBranch.join('')}</g>`);

  /* Die sattesten Kronen des Bildes — die inneren laufen in die Spalte und sind
     darum eine Stufe heller; der Vertrag erzwingt es, und die Luftperspektive
     rechtfertigt es. */
  const fc = [];
  for (const [cx, cy, rx, edge] of [
    /* Die satten (crownEdge) bleiben VOLLSTAENDIG in der Randspalte: cx+rx <= 340
       bzw. cx-rx >= 1260. Der Vertragspruefer hat hier zwei Kronen erwischt, die
       mit ihrem Rand in die Spalte ragten — sie sind jetzt kleiner und sitzen
       weiter aussen. Die helleren (crownFore) duerfen darueber hinweggreifen und
       verbinden den Vordergrund mit dem Blütendach, damit die Kronen nicht als
       zwei Klumpen in den Ecken haengen. */
    [60, 108, 232, true],
    [236, -26, 158, true],
    [1546, 132, 214, true],
    [1382, -8, 170, true],
    [398, 46, 176, false],
    [1156, 74, 168, false],
    [640, -34, 150, false],
  ]) {
    claim(0.807, cx - rx, cx + rx, 'Vordergrundkrone (im Dunst)', cy);
    fc.push(
      crown(
        cx,
        cy,
        rx,
        rx * between(0.44, 0.62),
        edge ? 'url(#gKrone)' : C.crownFore,
        edge ? 'url(#gKroneHi)' : C.crownForeHi,
        2,
      ),
    );
  }
  put(fc.join(''));
  mark('Vordergrundbäume');
}

/* ═══════════════════════════════════════════════════════════════════════════
   8) HERZSCHLAG 1 — DIE FALLENDEN BLÜTEN
   ═══════════════════════════════════════════════════════════════════════════
   Über die GANZE Breite, vier Tiefenklassen, jede Blüte mit eigener Uhr. Sie
   fallen ununterbrochen: ein Weg von 1220 Einheiten, linear, endlos — dadurch
   ist jeder Zeitpunkt gleich gültig und es gibt keinen sichtbaren Neustart. Der
   seitliche Schwung liegt in denselben Keyframes, das Taumeln (rotate + scaleX)
   auf einer ZWEITEN, kürzeren und teilerfremden Uhr. Zwei unabhängige Perioden
   je Blüte heißt: das Muster kehrt erst nach ihrem kleinsten gemeinsamen
   Vielfachen wieder, also nie im Blickfeld eines Menschen.

   Sechs Fallbahnen und fünf Taumel-Muster werden GETEILT; individuell bleibt je
   Blüte nur Dauer und Versatz — der Unterschied zwischen 9 KB und 3 KB CSS, ohne
   Wirkungsverlust.

   Weil sie über das gesamte Bild fallen, gilt für sie der strengere der beiden
   Böden (Fluss-Band): die kräftigste fallende Blüte steht bei L 0.845. Die
   wirklich satten Blüten fallen in der CSS, vor dem Schleier, in den Randspalten. */

const fallCss = [];
const FALL_CLASSES = [
  { s: 0.3, fill: C.petalPale, dur: [34, 44] },
  { s: 0.45, fill: C.petalLight, dur: [25, 33] },
  { s: 0.62, fill: C.petalMid, dur: [17, 23] },
  { s: 0.85, fill: C.petalDeep, dur: [12, 16] },
];

{
  [
    [26, -19, 15],
    [-22, 17, -12],
    [31, -25, 20],
    [-16, 27, -21],
    [18, -30, 24],
    [-28, 14, -17],
  ].forEach(([a, b, c], i) =>
    fallCss.push(
      `@keyframes fl${i}{0%{transform:translate(0,0)}25%{transform:translate(${a}px,305px)}50%{transform:translate(${b}px,610px)}75%{transform:translate(${c}px,915px)}100%{transform:translate(0,1220px)}}`,
    ),
  );
  [
    [-34, 28, -14, 0.3, 0.9],
    [-12, 41, -26, 0.42, 0.82],
    [-40, 16, -8, 0.26, 0.96],
    [-22, 36, -30, 0.36, 0.88],
    [-8, 45, -18, 0.46, 0.78],
  ].forEach(([r0, r1, r2, s1, s2], i) =>
    fallCss.push(
      `@keyframes tw${i}{0%,100%{transform:rotate(${r0}deg) scaleX(1)}30%{transform:rotate(${r1}deg) scaleX(${s1})}62%{transform:rotate(${r2}deg) scaleX(${s2})}}`,
    ),
  );

  const petals = [];
  let idx = 0;
  for (let k = 0; k < 4; k++) {
    const cls = FALL_CLASSES[k];
    for (let i = 0; i < [12, 13, 11, 8][k]; i++) {
      const x = between(-40, W + 40);
      const dur = between(cls.dur[0], cls.dur[1]);
      const tw = between(2.9, 6.7);
      fallCss.push(
        `.d${idx}{animation:fl${Math.floor(R() * 6)} ${f(dur)}s linear infinite ${f(-between(0, dur))}s}`,
        `.t${idx}{animation:tw${Math.floor(R() * 5)} ${f(tw)}s ease-in-out infinite ${f(-between(0, tw))}s}`,
      );
      claim(0.845, x - 30, x + 30, 'fallende Blüte', 800);
      petals.push(
        `<g class="d${idx}"><g transform="translate(${n(x)},${n(between(-160, -30))}) scale(${f(cls.s)})" fill="${cls.fill}"><use href="#pt" class="t${idx}"/></g></g>`,
      );
      idx++;
    }
  }
  put(`<g>${petals.join('')}</g>`);
  mark('fallende Blüten');
}

/* ── Die Uhren des Floßes ─────────────────────────────────────────────────── */
RAFT.forEach((band, i) =>
  fallCss.push(
    `.r${i}{animation:dr${i} ${band.dur}s linear infinite ${f(-band.dur * (i * 0.13 + 0.07))}s}`,
    `@keyframes dr${i}{from{transform:translate(${-band.tile}px,0)}to{transform:translate(0,0)}}`,
  ),
);

/* ═══════════════════════════════════════════════════════════════════════════
   9) ZUSAMMENBAU
   ═══════════════════════════════════════════════════════════════════════════ */

const petalD =
  'M50 97C33 88 15 71 13 47C11 27 24 11 38 5C42 9 46 15 50 19C54 15 58 9 62 5C76 11 89 27 87 47C85 71 67 88 50 97Z';

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
  `role="img" aria-label="Kirschbluetenwald am Fluss: fallende Blueten und ein treibendes Bluetenfloss auf dem Wasser">` +
  `<style>${fallCss.join('')}` +
  /* ── DER STILL-SCHALTER (23.08.2026) ──────────────────────────────────────
     Andi: „es laggt leider, besonders, wenn ich das design ausgewaehlt habe
     und die widgets anpasse". Gemessen (tools/theme-contrast/szene-perf.mjs,
     1600x1000): Firefox 35 fps bei 782 % CPU, weil Gecko ein ANIMIERTES SVG
     als `background-image` in jedem Bild vollstaendig neu rastert — die
     ganze Zeichnung, nicht nur das Bewegte. Der Beweis liegt im Messprotokoll:
     eine Fassung mit GENAU EINEM bewegten Knoten kostet exakt dasselbe wie
     die mit fuenfundneunzig.

     Ein Wirtsdokument kann die Uhr eines Hintergrundbildes nicht anhalten —
     `animation-play-state` von aussen erreicht sie nicht. Was es kann, ist
     das Bild unter einem FRAGMENT anfordern: `…-szene.svg#still`. Dann greift
     `:target`, und die Zeichnung haelt sich selbst an. Aus 782 % werden 13 %.

     Warum `animation-play-state:paused` und NICHT `animation:none`: `none`
     setzt jede Blüte auf ihre Grundstellung zurueck — und die liegt oberhalb
     des Bildrands. Das Bild verloere genau das, wofuer Andi es bestellt hat
     („Die Kirschblueten sollen animiert fallen"), naemlich die Blueten IN DER
     LUFT. `paused` haelt sie dort an, wo ihr negativer `animation-delay` sie
     gerade hat: vierundvierzig Blueten ueber den ganzen Fall verteilt, das
     Floss mitten auf seiner Bahn. Das Ergebnis ist eine Fotografie der Szene,
     keine ausgeraeumte Fassung. Mit eigenen Augen gegen den laufenden Frame
     geprueft (shots 1/4/7/10 s).

     Dieselbe Begruendung gilt fuer `prefers-reduced-motion`: auch dort stand
     `animation:none` und liess die Luft leer. Wer Bewegung abbestellt, hat
     kein leeres Bild bestellt. */
  `#still:target~*,#still:target~* *{animation-play-state:paused!important}` +
  `@media(prefers-reduced-motion:reduce){*{animation-play-state:paused!important}}</style>` +
  `<defs>` +
  `<path id="pt" d="${petalD}" transform="translate(-50,-50)"/>` +
  VARIANTS.join('') +
  raftDefs.join('') +
  `<linearGradient id="luft" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.skyBlue}"/>` +
  `<stop offset="0.55" stop-color="${C.skyWarm}" stop-opacity="0.75"/>` +
  `<stop offset="1" stop-color="${C.sky}" stop-opacity="0"/></linearGradient>` +
  `<linearGradient id="fluss" x1="0" y1="0" x2="0.12" y2="1">` +
  `<stop offset="0" stop-color="${C.waterFar}"/>` +
  `<stop offset="0.42" stop-color="${C.waterMid}"/>` +
  `<stop offset="0.78" stop-color="${C.waterNear}"/>` +
  `<stop offset="1" stop-color="${C.waterDeep}"/></linearGradient>` +
  /* Tiefes Wasser NUR an den Bildrändern — die Randspalten tragen den Tonumfang. */
  `<linearGradient id="tief" x1="0" y1="0" x2="1" y2="0">` +
  `<stop offset="0" stop-color="${C.waterEdge}" stop-opacity="0.72"/>` +
  `<stop offset="0.131" stop-color="${C.waterEdge}" stop-opacity="0"/>` +
  `<stop offset="0.869" stop-color="${C.waterEdge}" stop-opacity="0"/>` +
  `<stop offset="1" stop-color="${C.waterEdge}" stop-opacity="0.72"/></linearGradient>` +
  /* Das Lichtband quer über die Bildmitte. */
  `<linearGradient id="glanz" x1="0" y1="0" x2="1" y2="0">` +
  `<stop offset="0" stop-color="${C.current}" stop-opacity="0"/>` +
  `<stop offset="0.5" stop-color="${C.current}" stop-opacity="0.16"/>` +
  `<stop offset="1" stop-color="${C.current}" stop-opacity="0"/></linearGradient>` +
  hazeGrad('gStamm', C.trunkEdge, C.trunkHaze) +
  hazeGrad('gAst', C.branchEdge, C.branchHaze) +
  hazeGrad('gKrone', C.crownEdge, C.crownFore) +
  hazeGrad('gKroneHi', C.crownEdgeHi, C.crownForeHi) +
  hazeGrad('gSchilf', C.reedEdge, C.reedHaze) +
  hazeGrad('gUfer', C.bankEdge, C.bankHaze) +
  hazeGrad('gSpuel', C.petalEdge, C.petalDeep) +
  `<clipPath id="riv"><path d="${riverClip}"/></clipPath>` +
  `</defs>` +
  /* Der Anker des Still-Schalters. Er zeichnet nichts; er steht nur VOR allem
     anderen, damit `#still:target ~ *` jedes folgende Geschwister und
     `~ * *` jeden ihrer Nachfahren erreicht. */
  `<g id="still"/>` +
  out.join('') +
  `</svg>\n`;

writeFileSync(OUT, svg);
const bytes = Buffer.byteLength(svg);
const tight = CEIL.slice().sort((a, b) => b.use - a.use)[0];
console.log(
  `hanaikada-szene.svg  ${(bytes / 1024).toFixed(1)} KB\n` +
    `  knappste Reserve zum Lesbarkeits-Boden: ${worst.what} (L ${worst.L} gegen Boden ${worst.floor})\n` +
    `  knappste Gamut-Reserve: ${tight.name} bei ${(tight.use * 100).toFixed(0)} % der Chroma-Decke\n` +
    bill
      .slice()
      .sort((a, b) => b[1] - a[1])
      .map(([l, b]) => `    ${(b / 1024).toFixed(1).padStart(6)} KB  ${l}`)
      .join('\n'),
);
if (bytes > 80 * 1024) {
  console.error('✗ über dem 80-KB-Budget der ORDER');
  process.exit(1);
}
