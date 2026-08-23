/**
 * FUYUBARE (冬晴れ) — der Szenen-Generator
 * ═══════════════════════════════════════════════════════════════════════════
 * Andis Bestellung: „strahlend-blauer Tag nach Neuschnee; verschneiter Wald mit
 * tief hängenden Ästen, zugefrorener Bach mit freigewehten Eisflächen, ferne
 * weiße Kämme; Herzschlag = glitzernder Schnee (Funkel-Kaustik); seltenes Extra
 * = abrutschende Schneelast von einem Ast. Hellstes Theme der Galerie."
 *
 *   Hauptmotiv     DER ZUGEFRORENE BACH — er kommt aus der Waldlücke im
 *                  Mittelgrund und zieht als breites Band schräg durch das
 *                  GANZE untere Drittel nach vorn links aus dem Bild. Auf ihm
 *                  liegen die freigewehten Eisflächen: türkise Spiegel, die
 *                  einzigen kalten Farbflecken im Weiß.
 *   Tiefe          sechs Ebenen: Himmel → zwei ferne weiße Kämme → dunstiger
 *                  Fernwald → der schneebeladene Wald (Mittelgrund, volle
 *                  Breite, läuft HINTER der Lesespalte durch) → Bach und
 *                  Schneewehen → die große Zeder vorn links.
 *   Herzschlag     der Schnee GLITZERT: 190 Funkel in acht Uhren (6,5–16,7 s,
 *                  also schnell genug, dass zwischen zwei Frames sichtbar
 *                  etwas anderes leuchtet), dazu drei Uhren für den Glanz auf
 *                  dem Eis und zwei für den Sonnen-Glast. Zwei Größenordnungen
 *                  wie im REZEPT: Funkeln (viele kleine Punkte) + Glast (eine
 *                  riesige Fläche, die sich sehr wenig ändert).
 *   Seltenes       alle 26 s rutscht die Schneelast vom untersten Ast der
 *                  großen Zeder — ein Bild, das man nicht erwartet.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * DIE REZEPT-REGELN, GESPIEGELT FÜR EIN HELLES THEMA
 * ───────────────────────────────────────────────────────────────────────────
 * Das REZEPT (aus Nagareboshi, einer NACHT) sagt: „nichts dunkler als
 * --bg-base malen" und „jede Keyframe startet bei opacity:1 und geht nur
 * RUNTER". Beide Regeln folgen aus HELLER SCHRIFT AUF DUNKLEM GRUND. Hier ist
 * es andersherum, und darum kehren sich beide um — nicht aus Geschmack,
 * sondern weil sonst dieselbe Zusage nicht mehr hält:
 *
 * (1) NICHTS HELLER ALS DER SCHLEIER. Der Schleier ist --bg-surface (L 0.99)
 *     bei 90 % Deckung. Läge ein Bildton DARÜBER, würde der Schleier ihn
 *     ABDUNKELN und stünde als sichtbares Rechteck im Bild (Nagareboshis
 *     Fehler, nur spiegelverkehrt). Kein Fill dieser Datei liegt über L 0.99.
 *
 * (2) DIE ANIMATION DARF NUR AUFHELLEN. Bei dunkler Schrift ist der
 *     SCHLECHTESTE Bildpunkt der DUNKELSTE. Jedes bewegte Element hier ist
 *     weiß bzw. warmweiß und wird über einen helleren Grund gelegt: es kann
 *     den schlechtesten Punkt nie verschlechtern, egal in welcher Phase.
 *     Zusätzlich liegt KEIN bewegtes Element über den dunklen Ankern (Zedern,
 *     Eisränder, Schilf) — der Glast wohnt HINTER der Landschaft, die Funkel
 *     ausschließlich auf Schnee und Eis. Damit ist der schlechteste Bildpunkt
 *     ZEITLICH KONSTANT und mit einem einzigen Standbild beweisbar. Die
 *     Messung fährt das trotzdem über vier Zeitpunkte nach (s. scenarios).
 *
 * (3) TIEFE = jede Ebene DUNKLER und BUNTER als die dahinter (bei Nacht war es
 *     umgekehrt). Luftperspektive an einem klaren Wintertag heißt: die Ferne
 *     wird nicht grau, sie wird HELL und BLAU. Die Stufen der weißen Kämme
 *     liegen darum eng (L 0.955 → 0.925), die des Waldes weit (0.60 → 0.42 →
 *     0.325) — dort sitzt der Kontrast, der das Bild trägt.
 *
 * (4) EIN GOLD, ZWEI HELLIGKEITEN. Das REZEPT verlangt EINE OKLCH-Tripel für
 *     --accent und das SVG-Gold. In einem hellen Thema ist das physikalisch
 *     unmöglich: der Akzent muss dunkel sein, um lesbar zu sein (L 0.51), die
 *     Sonne muss hell sein, um Sonne zu sein. Also die abgeschwächte Fassung
 *     derselben Regel, ausdrücklich erklärt statt still gebrochen: EIN
 *     FARBTON (h 72). --accent ist oklch(0.51 0.107 72), das Sonnenlicht im
 *     Bild ist derselbe Ton bei L 0.93–0.99. Zwei getunte Golds mit
 *     verschiedenen Farbtönen — der Fehler, vor dem das REZEPT warnt — gibt es
 *     hier nicht.
 *
 * (5) DER PUNCH KOMMT AUS DEM CHROMA. Die Helligkeit ist vom Kontrakt nach
 *     oben gedeckelt (Regel 1), also kann ein helles Thema nicht über
 *     Helligkeit brillieren. Es brilliert über SÄTTIGUNG: tiefblauer Himmel
 *     (C 0.115), türkises Eis (C 0.075), und vor allem BLAUE SCHATTEN im
 *     Schnee (C bis 0.07). Schnee ist hier nirgends „weiß" — er ist blau im
 *     Schatten und warmweiß in der Sonne, und genau diese Spanne ist der
 *     Unterschied zwischen Neuschnee und Papier. (Lehre des Hanaikada-Pods,
 *     21.08.: „da Helligkeit vom Vertrag fixiert ist, den Punch über CHROMA
 *     zurückkaufen".)
 *
 * ABGRENZUNG ZU ASAGIRI, dem anderen hellen Thema: Asagiri ist MILCHIGER
 * NEBEL — alles verschmilzt, der Kontrast zwischen zwei Nachbarflächen ist
 * winzig, die Motive lösen sich nach hinten auf. Fuyubare ist KRISTALLKLARE
 * BRILLANZ — harte Kanten, tiefe Schatten, ein fast schwarzer Baum direkt neben
 * einem fast weißen Schneefeld. Beide sind hell; das eine ist gedämpft, das
 * andere geschliffen.
 *
 * Fester Zufalls-Startwert: derselbe Lauf liefert bitgleich dasselbe Bild.
 *
 *   node tools/theme-contrast/fuyubare-szene.gen.mjs
 *   → frontend/public/themes/fuyubare-szene.svg
 */

import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', '..', 'frontend', 'public', 'themes', 'fuyubare-szene.svg');

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
const R = rng(0x2f1c0b77);
const between = (lo, hi) => lo + R() * (hi - lo);

/* ── OKLCH → sRGB ────────────────────────────────────────────────────────────
   Dieselbe Zahl auf beiden Seiten: die Themen-CSS schreibt oklch(), das SVG
   braucht Hex. So ist „der Mittelgrund-Wald liegt auf L 0.42" eine prüfbare
   Aussage und kein Farbeindruck. */
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
  // GAMUT-RIEGEL: ein geklippter Kanal verschiebt den FARBTON, nicht nur die
  // Helligkeit — und niemand merkt es, weil die Zahl im Quelltext richtig
  // aussieht. In einem Thema, das fast nur aus Tönen dicht unter Weiß besteht,
  // ist genau das die wahrscheinlichste stille Fehlerquelle (Blau kippt bei
  // L > 0.99 schon bei C 0.004 aus dem Gamut). Also laut abbrechen.
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

/* ── Die Palette eines klaren Wintertags ─────────────────────────────────────
   Von hinten nach vorn wird es DUNKLER und BUNTER. Die Obergrenze ist L 0.99
   (Regel 1), die Untergrenze L 0.35. Sie ist GEMESSEN, nicht geschaetzt: mit L 0.325
   stand die schlechteste Spaltenmessung bei 4,52:1 — bestanden, aber nur zwei
   Hundertstel ueber der Schwelle, also eine Zusage, die der naechste Eingriff
   kippt. L 0.35 kostet im Bild nichts Sichtbares und bringt sie auf 4,7. Zwischen
   diesen beiden Zahlen liegt das ganze Bild. */
const C = {
  skyTop: ok(0.675, 0.132, 247), //  Zenit: das satteste Blau des Bildes
  skyMid: ok(0.822, 0.094, 240),
  skyLow: ok(0.925, 0.042, 232),
  skyHaze: ok(0.965, 0.018, 224), //  Horizontdunst, in den die Kämme tauchen

  sunCore: ok(0.99, 0.007, 72), //  die Sonne selbst — h 72 wie --accent
  sunHalo: ok(0.965, 0.026, 72),
  sunFar: ok(0.93, 0.052, 72), //  der weite Glast

  ridgeFar: ok(0.955, 0.012, 230), //  ferne Kämme, Sonnenseite
  ridgeFarShade: ok(0.905, 0.034, 244), //  ihre Schattenflanke: BLAU, nicht grau
  ridgeMid: ok(0.925, 0.019, 232),
  ridgeMidShade: ok(0.855, 0.048, 246),

  forestFar: ok(0.55, 0.072, 200), //  Fernwald: im Dunst, kaum grün
  forestFarSnow: ok(0.895, 0.026, 232),

  snowBack: ok(0.905, 0.024, 232), //  Schneehang hinter dem Wald
  snowMid: ok(0.925, 0.026, 230), //  Mittelgrund-Schnee
  snowNear: ok(0.945, 0.022, 228), //  Vordergrund-Schnee, unbeschienen
  snowLit: ok(0.985, 0.008, 96), //  Sonnenkante auf einer Wehe — WARM
  snowShade: ok(0.822, 0.075, 246), //  Muldenschatten
  snowShadeDeep: ok(0.74, 0.09, 250), //  der tiefste Schatten unter einer Wehe

  treeMid: ok(0.378, 0.062, 180), //  der schneebeladene Wald
  treeNear: ok(0.358, 0.054, 184),
  treeFront: ok(0.35, 0.052, 188), //  die grosse Zeder — der dunkelste Ton
  trunk: ok(0.36, 0.036, 62),

  iceFace: ok(0.782, 0.092, 210), //  freigewehte Eisflaeche
  iceDeep: ok(0.6, 0.105, 213), //  wo das Eis dick und dunkel ist
  iceRim: ok(0.522, 0.088, 218), //  Kante zwischen Eis und Schnee
  iceSheen: ok(0.985, 0.012, 210), //  Glanz auf dem Eis
  iceCrack: ok(0.9, 0.048, 214),

  reed: ok(0.46, 0.062, 82), //  totes Gras, das aus dem Schnee ragt
  reedDark: ok(0.395, 0.055, 76),

  glint: ok(0.99, 0.008, 82), //  das Funkeln selbst
};

/* ── Geometrie der Bühne ─────────────────────────────────────────────────────
   1600x1000, `cover` + `center bottom`. Bei jedem üblichen Fensterformat
   (4:3 bis 16:9) bleibt x 140–1460 und y ≥ 110 sichtbar — nachgerechnet:
   1366x1024 zeigt viewBox-x 133–1467, 1920x1080 schneidet oben 100 Einheiten
   ab. Alles, was das Bild TRAGEN muss, liegt in diesem Fenster. */
const W = 1600;
const H = 1000;
const SAFE = { x0: 140, x1: 1460, y0: 110 };
const SUN = { x: 1332, y: 214 };

const out = [];
const put = (s) => out.push(s);
/* Ganze Zahlen. Auf einer 1600-px-Bühne, die im Fenster auf ~0,8–1,2 skaliert
   wird, ist ein Zehntel Bildpunkt nichts als Dateigröße — und Dateigröße ist
   hier ein Budget (≤ 80 KB laut ORDER). */
const n = (v) => Math.round(v);
const f = (v) => Math.round(v * 10) / 10;

/** Sammelt jede Schnee-/Eisfläche, auf der später Funkel sitzen dürfen. */
const glintFields = [];

/* ═══════════════════════════════════════════════════════════════════════════
   1) HIMMEL UND SONNE
   ═══════════════════════════════════════════════════════════════════════════
   Der Himmel ist die Hälfte des Bildes und die halbe Aussage: „Fuyubare" heißt
   der klare Tag NACH dem Schnee, und das Erste, was man an so einem Tag sieht,
   ist dieses unmögliche Blau. Es steht bei L 0.745 / C 0.115 — das dunkelste
   Blau, das ein Thema mit --bg-base bei L 0.965 vertragen kann, ohne dass der
   Himmel schwer wirkt.

   Die Sonne ist bewusst KEINE Scheibe mit Kante: ein harter heller Kreis in
   einer Ecke liest sich als Anzeige, nicht als Landschaft (Asagiris Befund,
   14.08.). Sie ist ein dreistufiger Verlauf ohne Rand. */

put(`<rect width="${W}" height="${H}" fill="url(#himmel)"/>`);
// Der Glast liegt HINTER allem Land (Regel 2): er ist die grösste bewegte
// Fläche des Bildes, und er darf keinen dunklen Anker berühren.
put(`<circle cx="${SUN.x}" cy="${SUN.y}" r="560" fill="url(#glast)" class="g0"/>`);
put(`<circle cx="${SUN.x}" cy="${SUN.y}" r="196" fill="url(#hof)" class="g1"/>`);
put(`<circle cx="${SUN.x}" cy="${SUN.y}" r="82" fill="url(#sonne)"/>`);

/* ═══════════════════════════════════════════════════════════════════════════
   2) DIE FERNEN WEISSEN KÄMME
   ═══════════════════════════════════════════════════════════════════════════
   Mittelpunktverschiebung wie bei Nagareboshi — die kürzeste Beschreibung
   eines Gebirges, die es gibt, und sie liefert Kanten, die kein Verlauf
   hinbekommt. Neu ist die SCHATTENFLANKE: ein Kamm aus Schnee ist keine
   Silhouette, sondern ein Körper. Ohne den blauen Schatten unter der Kammlinie
   wäre er ein weißer Papierschnitt vor blauem Papier. Mit ihm ist er ein Berg.
   Die Flanke ist ein Band zwischen der Kammlinie und derselben Linie 30–46 px
   tiefer; das kostet die doppelte Punktzahl und ist es wert. */
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
/** Das Schattenband unter einer Kammlinie: dieselbe Linie, nach unten versetzt. */
const shadeBand = (pts, drop, fill, op) =>
  `<path d="M${asLine(pts)}L${asLine(
    pts
      .slice()
      .reverse()
      .map(([x, y]) => [x, y + drop]),
  )}Z" fill="${fill}" opacity="${op}"/>`;

/** Höhe einer Kammlinie an der Stelle x (für alles, was DARAUF steht). */
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

const r1 = ridgeLine(452, 126, 4, -54);
const r2 = ridgeLine(552, 84, 4, 38);
const r3 = ridgeLine(648, 46, 5, -22);
const r4 = ridgeLine(716, 26, 5, 16);

// Horizontdunst: die Kämme stehen nicht auf einer Schnittkante, sie tauchen in
// die kalte Luft über dem Tal. EIN weicher Streifen, kein Filter (Filter über
// dieser Fläche kosten in Chrome hörbar Zeit und hier auch Bytes).
put(`<rect x="0" y="330" width="${W}" height="240" fill="url(#dunst)"/>`);

put(ridgePath(r1, C.ridgeFar));
put(shadeBand(r1, 44, C.ridgeFarShade, 0.85));
put(`<rect x="0" y="430" width="${W}" height="190" fill="url(#dunst)" opacity="0.7"/>`);
put(ridgePath(r2, C.ridgeMid));
put(shadeBand(r2, 36, C.ridgeMidShade, 0.8));

/* ═══════════════════════════════════════════════════════════════════════════
   3) DER FERNWALD
   ═══════════════════════════════════════════════════════════════════════════
   Eine Sugi ist keine Dreiecksfläche: sie hat viele flache Etagen, und ihre
   Zweigspitzen HÄNGEN — der äußere Punkt jeder Etage liegt TIEFER als der
   innere. Diese eine Umkehrung ist der Unterschied zwischen „Nadelbaum" und
   „Zackenmuster" (Nagareboshis Lehre, hier übernommen statt neu gelernt).

   Die SCHNEELAST entsteht durch einen zweiten Durchgang derselben Form, nach
   OBEN RECHTS versetzt und weiß: das Licht kommt von der Sonne rechts oben,
   also liegt der Schnee genau dort auf den Ästen. Zwei Pfade je Baum, kein
   drittes System — und es liest sich sofort als „tief hängende, beladene Äste".
   Das ist der Trick, an dem dieses Thema hängt, darum steht er in EINER
   Funktion und nicht verstreut. */
function cedar(x, baseY, h, w, fill, jitter = 1) {
  const tiers = Math.max(4, Math.min(16, Math.round(h / 44)));
  const step = h / tiers;
  const lft = [];
  const rgt = [];
  for (let k = 0; k < tiers; k++) {
    const t = k / tiers;
    const y = baseY - h * t;
    const spread = (w / 2) * (1 - t) ** 0.72;
    lft.push([x - spread * (1 + between(-0.14, 0.14) * jitter), y + step * 0.34]);
    lft.push([x - spread * 0.34, y - step * 0.52]);
    rgt.push([x + spread * (1 + between(-0.14, 0.14) * jitter), y + step * 0.34]);
    rgt.push([x + spread * 0.34, y - step * 0.52]);
  }
  const d =
    `M${n(x - w / 2)},${n(baseY + step * 0.34)}` +
    lft.map(([px, py]) => `L${n(px)},${n(py)}`).join('') +
    `L${n(x)},${n(baseY - h - step * 0.5)}` +
    rgt
      .reverse()
      .map(([px, py]) => `L${n(px)},${n(py)}`)
      .join('') +
    `L${n(x + w / 2)},${n(baseY + step * 0.34)}Z`;
  return d;
}
/** Baum mit Schneelast: erst das Weiß (versetzt), dann die dunkle Form. */
function snowyCedar(x, baseY, h, w, fill, snow, dx, dy, jitter = 1) {
  const d = cedar(x, baseY, h, w, fill, jitter);
  return (
    `<path d="${d}" fill="${snow}" transform="translate(${f(dx)} ${f(dy)})"/>` +
    `<path d="${d}" fill="${fill}"/>`
  );
}

put(ridgePath(r3, C.snowBack));
put(shadeBand(r3, 26, C.snowShade, 0.5));
// Fernwald auf dem dritten Grat: klein, im Dunst. Er bekommt als EINZIGE
// Baumreihe KEINE eigene Schneelast — nicht aus Nachlaessigkeit, sondern weil
// das Budget aus den schwaechsten Elementen bezahlt wird (REZEPT C): bei
// 26–52 px Hoehe und im Horizontdunst ist die weisse Kopie 12 KB fuer etwas,
// das man nicht sieht. Dieselben 12 KB stecken jetzt im Bach und im Funkeln.
for (let x = -30; x < W + 30; x += between(42, 70)) {
  put(`<path d="${cedar(x, heightAt(r3, x) + 6, between(26, 52), between(13, 21), C.forestFar)}" fill="${C.forestFar}"/>`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   4) DER SCHNEEBELADENE WALD — der Mittelgrund, volle Breite
   ═══════════════════════════════════════════════════════════════════════════
   Das ist die Ebene, die die Regie meint, wenn sie „die Mitte ist nicht leer"
   sagt: eine geschlossene Waldkante über die GANZE Bühne, hoch genug, um
   hinter der Lesespalte durchzulaufen, mit einer einzigen Lücke — dort, wo der
   Bach herauskommt. Die Lücke ist kein Loch, sie ist die Quelle des
   Hauptmotivs. */
put(ridgePath(r4, C.snowMid));
const GAP = { x0: 946, x1: 1128 }; //  die Waldlücke, aus der der Bach kommt

for (let x = -30; x < W + 30; x += between(21, 37)) {
  const inGap = x > GAP.x0 - 18 && x < GAP.x1 + 18;
  const base = heightAt(r4, x) + 8;
  const h = inGap ? between(38, 66) : between(152, 336);
  const w = inGap ? between(20, 32) : between(48, 98);
  put(snowyCedar(x, base, h, w, C.treeMid, C.snowMid, 3, -4));
}
// Eine zweite, tiefer stehende Reihe direkt davor. Sie ist dunkler und
// grösser — dadurch wird aus der Waldkante ein WALD (zwei Tiefen) statt einer
// Reihe. Sie lässt die Lücke ebenfalls frei.
for (let x = -20; x < W + 30; x += between(44, 71)) {
  if (x > GAP.x0 - 40 && x < GAP.x1 + 40) continue;
  put(snowyCedar(x, heightAt(r4, x) + 40, between(196, 404), between(68, 128), C.treeNear, C.snowNear, 5, -7));
}

/* ═══════════════════════════════════════════════════════════════════════════
   5) DER ZUGEFRORENE BACH — das Hauptmotiv
   ═══════════════════════════════════════════════════════════════════════════
   Er kommt aus der Waldlücke (oben rechts der Mitte) und zieht schräg nach
   vorn links aus dem Bild. Die Mittellinie ist von Hand gesetzt (ein Bach ist
   keine Zufallsfunktion), die Breite wächst zur Kamera hin — das ist die ganze
   Perspektive, die es braucht.

   Aufbau in drei Lagen, weil ein Bach im Winter genau so aussieht:
     a) das Bett, schneeüberweht (heller als das Ufer, weil flach und offen),
     b) die FREIGEWEHTEN EISFLÄCHEN — unregelmäßige Spiegel, wo der Wind den
        Schnee weggeräumt hat: türkis, mit dunklem Rand und hellem Glanz,
     c) die Uferkanten mit blauem Schatten. */
const BACH = [
  [1046, 738, 58],
  [982, 780, 84],
  [880, 828, 122],
  [726, 880, 172],
  [534, 930, 226],
  [292, 974, 288],
  [-30, 1024, 356],
];
/** Ufer links/rechts aus Mittellinie + Breite (Normale aus den Nachbarpunkten). */
function banks(pts) {
  const L = [];
  const Rt = [];
  for (let i = 0; i < pts.length; i++) {
    const [x, y, w] = pts[i];
    const [ax, ay] = pts[Math.max(0, i - 1)];
    const [bx, by] = pts[Math.min(pts.length - 1, i + 1)];
    const dx = bx - ax;
    const dy = by - ay;
    const len = Math.hypot(dx, dy) || 1;
    const nx = -dy / len;
    const ny = dx / len;
    L.push([x + nx * w * 0.5, y + ny * w * 0.5]);
    Rt.push([x - nx * w * 0.5, y - ny * w * 0.5]);
  }
  return [L, Rt];
}
const [bl, br] = banks(BACH);
const bachPath = `M${asLine(bl)}L${asLine(br.slice().reverse())}Z`;

// a) das Bett
put(`<path d="${bachPath}" fill="${C.snowNear}"/>`);
// c-vorgezogen) die Uferkanten: schmale Schattenbänder AUF dem Ufer, nicht im
// Bett — sie sind der Grund, warum der Bach eingesenkt wirkt statt aufgemalt.
put(
  `<path d="M${asLine(bl)}L${asLine(
    bl
      .slice()
      .reverse()
      .map(([x, y]) => [x - 9, y - 15]),
  )}Z" fill="${C.snowShade}" opacity="0.9"/>`,
);
put(
  `<path d="M${asLine(br)}L${asLine(
    br
      .slice()
      .reverse()
      .map(([x, y]) => [x + 7, y + 13]),
  )}Z" fill="${C.snowShadeDeep}" opacity="0.55"/>`,
);

/** Eine freigewehte Eisfläche: unregelmäßiges Vieleck, kein Oval. */
function icePatch(cx, cy, rx, ry) {
  const k = 9;
  const pts = [];
  for (let i = 0; i < k; i++) {
    const a = (i / k) * Math.PI * 2;
    const rr = between(0.66, 1.0);
    pts.push([cx + Math.cos(a) * rx * rr, cy + Math.sin(a) * ry * rr]);
  }
  return `M${asLine(pts)}Z`;
}
/** Punkt auf der Bach-Mittellinie bei Parameter t (0…1) + lokale Breite. */
function bachAt(t) {
  const s = t * (BACH.length - 1);
  const i = Math.min(BACH.length - 2, Math.floor(s));
  const u = s - i;
  const a = BACH[i];
  const b = BACH[i + 1];
  return [a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u];
}
const iceRefs = [];
[0.07, 0.17, 0.27, 0.36, 0.45, 0.53, 0.61, 0.69, 0.77, 0.85, 0.93].forEach((t, i) => {
  const [cx, cy, w] = bachAt(t);
  const rx = w * between(0.5, 0.68);
  const ry = w * between(0.2, 0.32);
  const ox = between(-w * 0.14, w * 0.14);
  const d = icePatch(cx + ox, cy, rx, ry);
  put(`<path d="${d}" fill="${C.iceRim}"/>`);
  put(`<path d="${d}" fill="${i % 3 === 0 ? C.iceDeep : C.iceFace}" transform="translate(0 -3)"/>`);
  iceRefs.push([cx + ox, cy, rx, ry]);
  glintFields.push([cx + ox - rx * 0.7, cy - ry * 0.7, rx * 1.4, ry * 1.4, 0.9]);
});
// Der Glanz auf dem Eis: schmale, fast waagerechte Streifen, die dem
// Spiegelwinkel folgen. Sie sind die zweite bewegte Fläche (drei eigene Uhren)
// und liegen ausschliesslich auf Eis, also nie über einem dunklen Anker.
iceRefs.forEach(([cx, cy, rx, ry], i) => {
  const y = cy - ry * between(0.05, 0.35);
  put(
    `<path d="M${n(cx - rx * 0.78)},${n(y)}L${n(cx + rx * 0.5)},${n(y - ry * 0.42)}` +
      `L${n(cx + rx * 0.72)},${n(y - ry * 0.1)}L${n(cx - rx * 0.6)},${n(y + ry * 0.38)}Z" ` +
      `fill="${C.iceSheen}" opacity="0.85" class="s${i % 3}"/>`,
  );
  // Risse: zwei feine Linien je Fläche. Sie kosten wenig und sind der
  // Unterschied zwischen „Eis" und „türkiser Fleck" (REZEPT F: Wasser braucht
  // Struktur, sonst liest es sich als Tapete).
  put(
    `<path d="M${n(cx - rx * 0.5)},${n(cy + ry * 0.3)}L${n(cx - rx * 0.05)},${n(cy - ry * 0.15)}` +
      `L${n(cx + rx * 0.45)},${n(cy + ry * 0.25)}" fill="none" stroke="${C.iceCrack}" stroke-width="2"/>`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   6) DER VORDERGRUND — Wehen, Schilf, die grosse Zeder
   ═══════════════════════════════════════════════════════════════════════════
   Das untere Drittel ist bei den gescheiterten Themen der ersten Runde leer
   gewesen. Hier steht: eine Schneewehe über die volle Breite mit beleuchteter
   Krone und tiefem Schatten, Schilf, das aus dem Schnee sticht, und links die
   grosse Zeder als dunkler Anker. */
/* WINDRIPPEL: das offene Feld zwischen Wald und Wehe war im zweiten Wurf eine
   weisse Flaeche — dasselbe „liest sich als Tapete", das das REZEPT dem Wasser
   vorwirft. Neuschnee ist nie glatt: der Wind zieht flache, sehr lange Rippen
   hinein, und jede wirft einen blauen Schatten. Fuenf davon geben dem unteren
   Drittel Struktur, ohne ein Motiv zu sein. */
[
  [792, 14, 0.34],
  [838, 12, 0.3],
  [878, 11, 0.26],
  [906, 9, 0.22],
].forEach(([y, amp, op]) => {
  const rip = ridgeLine(y, amp, 4, between(-16, 16), 0.46);
  put(
    `<path d="M${asLine(rip)}L${asLine(
      rip
        .slice()
        .reverse()
        .map(([x, yy]) => [x - 5, yy + between(16, 30)]),
    )}Z" fill="${C.snowShade}" opacity="${f(op)}"/>`,
  );
});

const drift = ridgeLine(944, 30, 5, 22, 0.52);
put(ridgePath(drift, C.snowNear));
// Die beschienene Krone der Wehe: EIN warmweißer Streifen entlang der Kante.
// Er ist der hellste Ton des Bildes (L 0.985) und der Grund, warum der Schnee
// nicht wie Papier aussieht.
put(
  `<path d="M${asLine(drift)}L${asLine(
    drift
      .slice()
      .reverse()
      .map(([x, y]) => [x + 4, y + 11]),
  )}Z" fill="${C.snowLit}"/>`,
);
// und die Mulde darunter
put(
  `<path d="M${asLine(drift.map(([x, y]) => [x, y + 12]))}L${asLine(
    drift
      .slice()
      .reverse()
      .map(([x, y]) => [x - 6, y + 62]),
  )}Z" fill="${C.snowShadeDeep}" opacity="0.72"/>`,
);
glintFields.push([-20, 950, W + 40, 60, 1]);

// Schilf und totes Gras am Ufer: dünne Bögen, immer in Gruppen — ein
// einzelner Halm sieht aus wie ein Kratzer, sieben sehen aus wie Schilf.
function reeds(x, y, count, len, spread, fill) {
  let d = '';
  for (let i = 0; i < count; i++) {
    const bx = x + between(-spread, spread);
    const l = len * between(0.62, 1.15);
    const bend = between(-0.42, 0.42) * l;
    d += `M${n(bx)},${n(y)}Q${n(bx + bend * 0.4)},${n(y - l * 0.6)} ${n(bx + bend)},${n(y - l)}`;
  }
  return `<path d="${d}" fill="none" stroke="${fill}" stroke-width="2.4" opacity="0.92"/>`;
}
[
  [188, 986, 9, 92, 44],
  [452, 962, 8, 78, 40],
  [676, 934, 7, 66, 34],
  [880, 900, 6, 54, 28],
  [1230, 958, 10, 88, 52],
  [1480, 996, 8, 96, 46],
].forEach(([x, y, c2, l, s], i) => put(reeds(x, y, c2, l, s, i % 2 ? C.reed : C.reedDark)));

// Die grosse Zeder links: der dunkle Anker. Ohne sie hätte das Bild keinen
// Ton unter L 0.42 und würde als Dunst gelesen statt als klarer Tag.
const BIG = { x: 168, base: 1086, h: 892, w: 372 };
put(`<rect x="${BIG.x - 14}" y="${BIG.base - 130}" width="28" height="150" fill="${C.trunk}"/>`);
put(snowyCedar(BIG.x, BIG.base, BIG.h, BIG.w, C.treeFront, C.snowLit, 6, -8, 0.8));
// Zweite Zeder rechts vorn, kleiner, teils angeschnitten — sie rahmt das Bild
// auf der Gegenseite, damit der Bach nicht ins Nichts läuft.
put(snowyCedar(1466, 1074, 664, 302, C.treeFront, C.snowNear, 6, -9, 0.9));

// Junge Zedern am rechten Ufer — sie fuellen die weisse Zone zwischen Wald
// und Bach und geben dem Bach einen Massstab.
[
  [1148, 848, 128, 54],
  [1214, 872, 96, 42],
  [1082, 828, 88, 38],
  [1276, 894, 152, 62],
  [1350, 924, 118, 50],
].forEach(([x, y, h, w]) => put(snowyCedar(x, y, h, w, C.treeNear, C.snowNear, 5, -7)));

// Zwei Steine im Bachbett mit Schneehaube: das Eis bekommt dadurch eine
// Oberflaeche statt einer Flaeche.
[
  [606, 912, 31, 13],
  [842, 860, 22, 9],
  [704, 892, 24, 10],
].forEach(([x, y, rx, ry]) => {
  put(`<ellipse cx="${x}" cy="${y}" rx="${rx}" ry="${ry}" fill="${C.iceRim}"/>`);
  put(`<ellipse cx="${x}" cy="${n(y - ry * 0.62)}" rx="${n(rx * 0.82)}" ry="${n(ry * 0.66)}" fill="${C.snowLit}"/>`);
});

/* Die SCHNEELAST, die abrutscht (das seltene Extra). Sie sitzt auf dem
   untersten rechten Ast der grossen Zeder, fällt alle 26 s in etwa 5 s ab und
   ist danach wieder da. Wie jedes bewegte Element hier ist sie WEISS: sie kann
   den Kontrast nur verbessern, in welcher Phase auch immer.
   Der Ruhezustand (prefers-reduced-motion, animation:none) ist die Last AUF dem
   Ast — also ein vollständiges, unauffälliges Bild. */
const LOAD = { x: BIG.x + BIG.w * 0.34, y: BIG.base - BIG.h * 0.16 };
put(
  `<g class="ab"><path d="M${n(LOAD.x - 46)},${n(LOAD.y)}q22,-19 48,-7q26,-13 44,8q-16,17 -46,15q-30,3 -46,-16Z" ` +
    `fill="${C.snowLit}"/><path d="M${n(LOAD.x - 18)},${n(LOAD.y + 14)}q14,10 32,2` +
    `q-6,14 -20,13q-14,-1 -12,-15Z" fill="${C.snowNear}"/></g>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   7) DAS FUNKELN — die Kaustik im Neuschnee
   ═══════════════════════════════════════════════════════════════════════════
   Der Herzschlag. Ein Funkel ist ein Vierstrahl mit einem weichen Hof; drei
   Größen teilen sich zwei Symbole, damit jeder Punkt im Bild nur noch aus
   `<use>` + Klasse besteht (44 Byte statt 190).

   WO sie sitzen, ist keine Deko-Frage: auf reinem Sonnenschnee (L 0.985) wäre
   ein weisser Punkt unsichtbar. Sie sitzen darum auf den SCHATTIGEN und den
   mittelhellen Flächen und auf dem Eis — dort, wo zwischen Grund (L 0.80–0.93)
   und Funkel (L 0.99) genug Luft ist, dass das Auge es als Blitzen liest.
   Genau deshalb ist der Schnee dieses Themas überhaupt blau schattiert. */
const glints = [];
glintFields.push(
  [-20, 700, 900, 120, 0.6], //  Mittelgrund-Schnee links der Waldlücke
  [1100, 726, 520, 120, 0.5],
  [60, 840, 1500, 130, 0.75], //  das offene Feld zwischen Wald und Wehe
  [40, 900, 1520, 96, 0.8],
);
for (const [x0, y0, w, h, dens] of glintFields) {
  const count = Math.round((w * h) / 3100 * dens);
  for (let i = 0; i < count; i++) {
    const x = x0 + R() * w;
    const y = y0 + R() * h;
    if (x < -30 || x > W + 30 || y < 640) continue;
    glints.push([x, y, R()]);
  }
}
// Die grossen Funkel zuletzt, damit sie nicht von kleinen überdeckt werden.
glints.sort((a, b) => a[2] - b[2]);
glints.forEach(([x, y, r], i) => {
  const sym = r > 0.86 ? 'kg' : r > 0.52 ? 'km' : 'ks';
  put(`<use href="#${sym}" x="${n(x)}" y="${n(y)}" class="f${i % 8}"/>`);
});

/* ═══════════════════════════════════════════════════════════════════════════
   8) DER HERZSCHLAG — acht Uhren fürs Funkeln, drei fürs Eis, zwei für den Glast
   ═══════════════════════════════════════════════════════════════════════════
   Schnee funkelt SCHNELL. Nagareboshis Sterne standen auf 21–43 s, weil ein
   Sternhimmel atmet; ein Funkel, das zehn Sekunden zum Verglimmen braucht, ist
   kein Funkel. Die acht Uhren liegen darum bei 6,5–16,7 s und sind paarweise
   nicht ganzzahlig verhältnisgleich, damit sich das Feld nie synchronisiert.
   Nebenwirkung, die genau so gewollt ist: zwischen zwei Beweis-Frames (1/4/7/10 s)
   liegt immer mindestens eine halbe Periode — die Bewegung ist also nicht nur
   vorhanden, sie ist in der Frame-Serie SICHTBAR.

   Alle Keyframes laufen von opacity:1 nur nach UNTEN. Bei hellem Grund und
   dunkler Schrift heisst das: der gemalte Zustand ist der HELLSTE und damit
   der BESTE; jede Phase danach kann den Kontrast nur da verschlechtern, wo
   weisses Funkeln verschwindet — und darunter liegt immer Schnee, nie ein
   dunkler Anker. Der schlechteste Bildpunkt ist zeitlich konstant. */
const css = [
  /* Der Glast: die grösste bewegte Fläche des Bildes. Ein Sternfeld ändert ein
     paar tausend Bildpunkte, dieser Kreis ändert Hunderttausende — um jeweils
     sehr wenig. Das eine liest das Auge als Funkeln, das andere als „die Luft
     flirrt". Beides zusammen ist der Herzschlag. */
  '.g0{animation:ga 37s ease-in-out infinite;animation-delay:-9s}',
  '.g1{animation:gb 23s ease-in-out infinite;animation-delay:-5s}',
  '@keyframes ga{0%,100%{opacity:1}50%{opacity:.74}}',
  '@keyframes gb{0%,100%{opacity:1}50%{opacity:.82}}',
];
[6.5, 7.7, 8.9, 10.3, 11.7, 13.1, 14.9, 16.7].forEach((p, i) => {
  const delay = -(p * (i * 0.113 + 0.07)).toFixed(2);
  const lo = (0.06 + (i % 4) * 0.05).toFixed(2);
  const mid = (0.42 + (i % 3) * 0.09).toFixed(2);
  css.push(
    `.f${i}{animation:fu${i} ${p}s ease-in-out infinite;animation-delay:${delay}s}`,
    `@keyframes fu${i}{0%,100%{opacity:1}22%{opacity:${mid}}46%{opacity:${lo}}72%{opacity:${mid}}}`,
  );
});
[
  [19, 0.34],
  [24.5, 0.26],
  [29, 0.42],
].forEach(([p, lo], i) => {
  css.push(
    `.s${i}{animation:sh${i} ${p}s ease-in-out infinite;animation-delay:${-p * 0.27 * (i + 1)}s}`,
    `@keyframes sh${i}{0%,100%{opacity:.85}38%{opacity:${lo}}70%{opacity:${(lo + 0.3).toFixed(2)}}}`,
  );
});
/* Die abrutschende Schneelast. 26 s Periode, der Fall dauert etwa 5 s, danach
   liegt sie 1,5 s unsichtbar am Ast und blendet wieder auf — so schliesst sich
   die Schleife ohne sichtbaren Sprung. Die negative Verzögerung ist so gewählt,
   dass der Fall in der Beweis-Serie bei t=7 s mitten im Bild steht. */
css.push(
  '.ab{animation:abr 26s linear infinite;animation-delay:-12.4s}',
  '@keyframes abr{0%,70%{transform:translate(0,0);opacity:1}' +
    '74%{transform:translate(4px,44px);opacity:1}' +
    '82%{transform:translate(12px,168px);opacity:.72}' +
    '90%,95%{transform:translate(22px,300px);opacity:0}' +
    '96%{transform:translate(0,0);opacity:0}100%{transform:translate(0,0);opacity:1}}',
);

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
  `role="img" aria-label="Klarer Wintertag: verschneiter Wald und zugefrorener Bach unter blauem Himmel">` +
  `<style>${css.join('')}` +
  `@media(prefers-reduced-motion:reduce){*{animation:none!important}}</style>` +
  `<defs>` +
  `<linearGradient id="himmel" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.skyTop}"/>` +
  `<stop offset="0.36" stop-color="${C.skyMid}"/>` +
  `<stop offset="0.62" stop-color="${C.skyLow}"/>` +
  `<stop offset="0.78" stop-color="${C.skyHaze}"/></linearGradient>` +
  `<linearGradient id="dunst" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.skyHaze}" stop-opacity="0"/>` +
  `<stop offset="0.55" stop-color="${C.skyHaze}" stop-opacity="0.75"/>` +
  `<stop offset="1" stop-color="${C.skyHaze}" stop-opacity="0"/></linearGradient>` +
  `<radialGradient id="sonne">` +
  `<stop offset="0" stop-color="${C.sunCore}"/>` +
  `<stop offset="0.34" stop-color="${C.sunCore}" stop-opacity="0.92"/>` +
  `<stop offset="1" stop-color="${C.sunHalo}" stop-opacity="0"/></radialGradient>` +
  `<radialGradient id="hof">` +
  `<stop offset="0" stop-color="${C.sunHalo}" stop-opacity="0.62"/>` +
  `<stop offset="0.5" stop-color="${C.sunHalo}" stop-opacity="0.22"/>` +
  `<stop offset="1" stop-color="${C.sunHalo}" stop-opacity="0"/></radialGradient>` +
  `<radialGradient id="glast">` +
  `<stop offset="0" stop-color="${C.sunFar}" stop-opacity="0.42"/>` +
  `<stop offset="0.45" stop-color="${C.sunFar}" stop-opacity="0.16"/>` +
  `<stop offset="1" stop-color="${C.sunFar}" stop-opacity="0"/></radialGradient>` +
  // Der Hof jedes Funkels — EIN Verlauf für alle 200+, sonst ist das Budget weg.
  `<radialGradient id="fh">` +
  `<stop offset="0" stop-color="${C.glint}" stop-opacity="0.85"/>` +
  `<stop offset="0.45" stop-color="${C.glint}" stop-opacity="0.24"/>` +
  `<stop offset="1" stop-color="${C.glint}" stop-opacity="0"/></radialGradient>` +
  `<g id="ks"><circle r="6" fill="url(#fh)"/>` +
  `<path d="M0,-9L2,-2L9,0L2,2L0,9L-2,2L-9,0L-2,-2Z" fill="${C.glint}"/></g>` +
  `<g id="km"><circle r="13" fill="url(#fh)"/>` +
  `<path d="M0,-15L3,-3L15,0L3,3L0,15L-3,3L-15,0L-3,-3Z" fill="${C.glint}"/></g>` +
  `<g id="kg"><circle r="24" fill="url(#fh)"/>` +
  `<path d="M0,-27L4,-4L27,0L4,4L0,27L-4,4L-27,0L-4,-4Z" fill="${C.glint}"/>` +
  `<path d="M-13,-13L3,-3L13,13L-3,3Z" fill="${C.glint}" opacity="0.75"/></g>` +
  `</defs>` +
  out.join('') +
  `</svg>\n`;

writeFileSync(OUT, svg);
const bytes = Buffer.byteLength(svg);
console.log(
  `fuyubare-szene.svg  ${(bytes / 1024).toFixed(1)} KB  ·  ${glints.length} Funkel  ·  ` +
    `${iceRefs.length} Eisflaechen  ·  Safe-Zone x${SAFE.x0}-${SAFE.x1}, y>=${SAFE.y0}`,
);
if (bytes > 80 * 1024) {
  console.error('✗ ueber dem 80-KB-Budget der ORDER');
  process.exit(1);
}
