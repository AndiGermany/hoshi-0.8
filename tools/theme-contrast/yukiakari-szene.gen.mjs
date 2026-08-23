/**
 * YUKIAKARI (雪明かり, „Schneelicht") — der Szenen-Generator
 * ═══════════════════════════════════════════════════════════════════════════
 * DER SATZ, AUS DEM DIESES BILD BESTEHT: der Schnee ist die Lichtquelle. Nicht
 * der Mond, nicht der Himmel — der Boden. Eine Winternacht mit Schneedecke ist
 * die hellste Nacht, die es gibt, weil jedes Fenster, jede Laterne, jeder
 * Lichtrest von der weissen Flaeche zurueckgeworfen und in die tief haengende
 * Wolkendecke gespiegelt wird. Darum ist hier NICHTS schwarz, und darum ist der
 * groesste zusammenhaengende Bereich des Bildes zugleich sein hellster.
 *
 *   Hauptmotiv   DIE SCHNEELATERNE — eine Yukimi-doro (雪見灯籠, woertlich
 *                „Schneeschau-Laterne") rechts im Vordergrund: der breite
 *                flache Hut, auf dem eine Schneehaube liegt, die kurzen
 *                gebogenen Beine, der warme Lichtkasten. Sie ist in zwei
 *                Worten benennbar und sie ist der Grund, warum das Bild einen
 *                Namen hat.
 *   Tiefe        sechs Ebenen: ferne Schneekaemme → naeherer Kamm → Waldhang
 *                mit schneebeladenen Zedern → fernes Dorf → nahe Dorfzeile
 *                links und rechts einer Gasse → Vordergrund (Laterne, Kiefer,
 *                Schneewall). Die Mitte ist besetzt, das untere Drittel traegt
 *                das Bild.
 *   Das Gold     zwoelf warme Fenster und die Laterne — und JEDES dieser
 *                Lichter faellt als Pfuetze auf den Schnee. Das ist der
 *                Unterschied zwischen „ein Fenster brennt" und „es hat
 *                geschneit".
 *   Herzschlag   FALLENDE FLOCKEN IN VIER TIEFEN, jede mit eigener Fall- und
 *                eigener Seitwaerts-Uhr. Dazu das leise Flackern der Lichter
 *                und das Atmen der Lichtpfuetzen.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ABGRENZUNG (Regie v2 verlangt sie ausdruecklich)
 * ───────────────────────────────────────────────────────────────────────────
 *   yoru         der Blick von der eigenen Engawa nach draussen — nah, warm
 *                innen, kalt aussen, EIN Haus.
 *   nagareboshi  das ferne Tal von oben — der Himmel ist die Hauptsache, das
 *                Dorf ist Massstab.
 *   yukiakari    DU STEHST IM DORF, in der Gasse, im Schnee. Der Himmel ist
 *                zu (Wolkendecke), das Licht kommt von unten. Kein Stern ist
 *                das Motiv, sondern eine Steinlaterne auf Armlaenge.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * DIE MEDIAN-LEHRE, VON ANFANG AN EINGEBAUT (RESULT-theme-yoru-hell, §1)
 * ───────────────────────────────────────────────────────────────────────────
 * Gefuehlte Helligkeit ist der MEDIAN, nicht der Spitzenwert. `measure.mjs`
 * sieht nur den hellsten Spaltenpixel und kann darum „gruen" melden, waehrend
 * das Bild als schwarzes Rechteck wirkt. Dieses Thema wurde deshalb nicht auf
 * seinen Spitzenwert gebaut, sondern auf seine VERTEILUNG:
 *
 *   Ziel   p50 = 26…29 von 255 (oberes Ende des Galerie-Feldes: aoi 29,
 *          yoake 24, amayadori 21, yoru 20, nagareboshi 11) — das hellste
 *          Nachtthema der Galerie, aber im Feld, nicht darueber.
 *   Weg    die MASSE hebt den Median, nicht die Spitze: der Schneegrund ist
 *          40 % der Bildflaeche und steht bei Grau 46…56. Die Lichter heben
 *          p99, nicht p50.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * DREI REGELN, DIE JEDE FARBE HIER EINHAELT
 * ───────────────────────────────────────────────────────────────────────────
 * (1) NICHTS DUNKLER ALS --bg-base (L 0.18). Der Lesbarkeits-Schleier deckt die
 *     Spalte mit eben diesem Grundton ab; laege ein Bildton darunter, wuerde er
 *     dort AUFHELLEN und als sichtbares Rechteck im Bild stehen.
 * (2) NICHTS HELLER ALS DER LUMINANZ-DECKEL. `night()` prueft jede Farbe gegen
 *     CAP — den Wert, bei dem --text-4 genau 4,50:1 erreicht. DIE ZEICHNUNG
 *     ALLEIN HAELT DIE ZUSAGE EIN, auch bei Schleier 0. Der Schleier ist
 *     Reserve fuer die CSS-Lichtebenen, nicht Teil der Zusage — sonst kippte
 *     ein spaeteres Drehen an `--yukiakari-veil` die Lesbarkeit still.
 * (3) DIE ANIMATION DARF NUR ABDUNKELN. Jede Deckkraft-Keyframe startet bei 1
 *     und geht nur nach unten; der gemalte (und der reduced-motion-) Zustand
 *     ist damit zugleich der Kontrast-Worst-Case. Die Flocken bewegen sich
 *     ausschliesslich per `transform` — ihre Helligkeit haengt nicht vom
 *     Zeitpunkt ab, sie sind per Konstruktion ueberall unter CAP.
 *
 * Fester Zufalls-Startwert: derselbe Lauf liefert bitgleich dasselbe Bild.
 *
 *   node tools/theme-contrast/yukiakari-szene.gen.mjs
 *   → frontend/public/themes/yukiakari-szene.svg
 */

import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', '..', 'frontend', 'public', 'themes', 'yukiakari-szene.svg');

/* ── Zufall mit Gedaechtnis ──────────────────────────────────────────────── */

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
const R = rng(0x59756b69);
const between = (lo, hi) => lo + R() * (hi - lo);

/* ── OKLCH → sRGB, und dieselbe Farbe als WCAG-Luminanz ──────────────────── */

function toLinear(L, C, h) {
  const a = C * Math.cos((h * Math.PI) / 180);
  const b = C * Math.sin((h * Math.PI) / 180);
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const [l, m, s] = [l_ ** 3, m_ ** 3, s_ ** 3];
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
}

/**
 * OKLCH → Hex mit GAMUT-RIEGEL. Liegt ein Kanal ausserhalb, wird er beim Runden
 * geklippt — und Klippen verschiebt den FARBTON, nicht nur die Helligkeit. Aus
 * einem warmen Gold wird dann still ein anderes Gold, und niemand merkt es,
 * weil die Zahl im Quelltext ja richtig aussieht. Also lieber laut abbrechen.
 */
function ok(L, C, h) {
  const lin = toLinear(L, C, h);
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

/** WCAG-Luminanz derselben Farbe — die Groesse, an der die Lesbarkeit haengt. */
function lum(L, C, h) {
  return toLinear(L, C, h).reduce((s, v, i) => {
    const c = Math.min(1, Math.max(0, v));
    const e = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    const d = e <= 0.04045 ? e / 12.92 : ((e + 0.055) / 1.055) ** 2.4;
    return s + d * [0.2126, 0.7152, 0.0722][i];
  }, 0);
}

/** Grauwert 0…255 zu einer Luminanz — die Skala, in der man am Schirm URTEILT. */
const grau = (y) =>
  Math.round(
    255 * (y <= 0.0031308 ? 12.92 * y : 1.055 * Math.pow(Math.max(y, 0), 1 / 2.4) - 0.055),
  );

/* ── DIE GRENZE ──────────────────────────────────────────────────────────────
   T4 ist die Luminanz von --text-4 (oklch(0.725 0.012 254) = #a2a8b2). CAP ist
   der Untergrund, bei dem dieser Token genau 4,50:1 erreicht.

   YUKIAKARI HAT MEHR PLATZ ALS YORU, UND DAS IST KEIN ZUFALL. Yoru IST der
   Basis-Tokensatz aus index.css und muss mit dessen --text-3 (#8a8580, Lrel
   0.238) auskommen — sein Deckel liegt bei Grau 33. Dieses Thema setzt seine
   eigene Textleiter (wie Nagareboshi) und hebt --text-4 auf L 0.725; damit
   liegt der Deckel bei Grau ~60. Ein Schneethema, dessen hellste erlaubte
   Flaeche Grau 33 waere, koennte kein Schnee sein — die Textleiter kauft dem
   Bild also genau das, wovon es lebt. Bezahlt wird es damit, dass die leiseste
   Textstufe lauter ist als anderswo; in einer Winternacht ist das richtig. */
const BG_BASE = 0.18; //  --bg-base — nichts darf darunter
const T4 = lum(0.725, 0.012, 254);
const CAP = (T4 + 0.05) / 4.5 - 0.05;

/** Farbe fuer alles, was in der Lesespalte stehen kann. Wirft statt zu klippen. */
function night(L, C, h) {
  const y = lum(L, C, h);
  if (y > CAP + 1e-9) {
    throw new Error(
      `night(${L} ${C} ${h}) reisst den Luminanz-Deckel: ${y.toFixed(5)} > ${CAP.toFixed(5)} ` +
        `— text-4 fiele auf ${((T4 + 0.05) / (y + 0.05)).toFixed(2)}:1`,
    );
  }
  if (L < BG_BASE - 1e-9) {
    throw new Error(`night(${L} ${C} ${h}) unter --bg-base ${BG_BASE} — der Schleier hellte auf`);
  }
  return ok(L, C, h);
}

/* ── DER EINZIGE WEG AN DER GRENZE VORBEI ────────────────────────────────────
   0 ueberall dort, wo die 920-px-Spalte bei irgendeiner gemessenen Breite
   hinreicht; nach aussen weich auf 1.

   DIE GRENZEN SIND GERECHNET, NICHT GERATEN. `cover` + `center bottom` auf einer
   viewBox 1600×1000 (1,6:1) ergibt je Fensterformat einen anderen sichtbaren
   Ausschnitt und damit eine andere Lage der zentrierten 920-px-Spalte in
   viewBox-Koordinaten:
       1024×768   Massstab 0.768, seitlich 133 ab  →  Spalte x 184…1082
       1280×800   Massstab 0.800, voll sichtbar    →  Spalte x 225…1375
       1366×1024  Massstab 1.024, seitlich 133 ab  →  Spalte x 350…1249
       1440×900   Massstab 0.900, voll sichtbar    →  Spalte x 289…1311
       1920×1080  Massstab 1.200, oben 100 ab      →  Spalte x 417…1183
   Die Vereinigung ist x 184…1375. FREE_L/FREE_R stehen mit Reserve davor. */
const FREE_L = 180;
const FREE_R = 1380;
const RAMP = 90;
function freeBoost(x) {
  const d = x < FREE_L ? FREE_L - x : x > FREE_R ? x - FREE_R : 0;
  const t = Math.min(1, d / RAMP);
  return t * t * (3 - 2 * t); // smoothstep — kein Knick am Rampenfuss
}

/**
 * Ein Licht, dessen Helligkeit vom ORT abhaengt: in der Spalte auf CAP
 * gedeckelt, im Randraum bis `hiL`. Das Chroma wandert mit, sonst bleichte das
 * Gold beim Hellerwerden aus.
 *
 * ES NIMMT EINE SPANNE, NICHT EINEN PUNKT (Lehre aus yoru §Rate-Stellen): wer
 * den MITTELPUNKT einer breiten Form uebergibt, misst am falschen Ort. Ein
 * 360 Einheiten breites Licht, dessen Mitte im Randraum liegt, ragt mit seinem
 * Rand in die Spalte. Gerechnet wird darum mit dem der Spalte NAECHSTEN Punkt
 * der ganzen Form; beruehrt sie die Spalte auch nur, gilt CAP.
 */
function flame(x0, x1, loL, hiL, loC, hiC, h) {
  const a = Math.min(x0, x1);
  const b = Math.max(x0, x1);
  const t = b < FREE_L ? freeBoost(b) : a > FREE_R ? freeBoost(a) : 0;
  if (t > 0) {
    // Im Randraum: hochfahren, aber ohne den Gamut zu reissen.
    return ok(loL + (hiL - loL) * t, loC + (hiC - loC) * t, h);
  }
  return night(loL, loC, h);
}

/* ── Buehne ──────────────────────────────────────────────────────────────────
   Die Hoehenlinien sind das Skelett. Sie liegen so, dass NIRGENDWO ein Band
   ohne Inhalt entsteht — der Vorwurf „Randstreifen um ein Loch" gilt auch
   senkrecht. */
const W = 1600;
const H = 1000;
/** Sichtbar bei JEDEM Fensterformat (cover + center bottom, 4:3 bis 16:9). */
const SAFE = { x0: 140, x1: 1460, y0: 110 };
const HILL_FAR = 402; //  Kamm der fernen Schneeberge
const HILL_NEAR = 452; //  zweiter Kamm
const WOOD = 512; //  Oberkante des Waldhangs
const SNOW_TOP = 596; //  hier beginnt die Schneeflaeche des Dorfes

const out = [];
const put = (s) => out.push(s);
/* Ganze Zahlen. Auf einer 1600-px-Buehne, die im Fenster auf ~0,8–1,2 skaliert
   wird, ist ein Zehntel Bildpunkt nichts als Dateigroesse — und Dateigroesse ist
   hier ein Budget (≤ 80 KB laut ORDER). */
const n = (v) => Math.round(v);
const f = (v) => Math.round(v * 10) / 10;

/** Jedes Licht, damit der Schnee es als Pfuetze zurueckwerfen kann. */
const lights = [];

/* ═══════════════════════════════════════════════════════════════════════════
   1) DIE PALETTE
   ═══════════════════════════════════════════════════════════════════════════
   Von hinten nach vorn wird der SCHNEE heller und das HOLZ dunkler. Beides
   zugleich — das ist die ganze Rezeptur von „raeumlich" in einem Winterbild:
   die Luftperspektive nimmt dem fernen Schnee den Kontrast (er naehert sich dem
   Himmel an), waehrend der nahe Schnee grell und das nahe Holz fast schwarz
   wird. Ein Bild, in dem nur die Helligkeit gestuft ist, sieht aus wie eine
   Treppe; ein Bild, in dem der ABSTAND zwischen hell und dunkel waechst, sieht
   aus wie Tiefe.

   Die Toene sind WEITER gespreizt als perzeptuell noetig (REZEPT D2): ueber der
   Zeichnung liegen noch das Schneelicht der CSS-Ebene und der Schleier, und
   beide multiplizieren. */
const C = {
  hillFar: night(0.262, 0.017, 258), //  ferne Schneekaemme, im Dunst
  hillFarRim: night(0.335, 0.012, 250), //  ihre Schneekante gegen die Wolke
  hillNear: night(0.228, 0.020, 260),
  hillNearRim: night(0.312, 0.014, 252),
  wood: night(0.206, 0.021, 262), //  der Waldhang als Masse
  cedar: night(0.188, 0.022, 264), //  eine einzelne Zeder, dunkel
  cedarSnow: night(0.306, 0.013, 254), //  ihre Schneelast

  snowFar: night(0.284, 0.014, 256), //  Schnee am Dorfrand, noch im Dunst
  snowMid: night(0.305, 0.012, 254),
  snowNear: night(0.324, 0.011, 250), //  Schnee vor den Fuessen
  snowLane: night(0.335, 0.012, 68), //  die getretene Gasse, warm angeleuchtet
  snowShadow: night(0.246, 0.022, 262), //  Mulden, Spuren, Schattenseiten
  snowRoof: night(0.328, 0.010, 250), //  Schnee auf einem Dach: das Hellste am Haus
  snowCrest: night(0.348, 0.009, 246), //  die Schneehaube der Laterne, ganz vorn

  wall: night(0.193, 0.018, 62), //  Holzwand im Schatten
  wallFar: night(0.214, 0.016, 60),
  thatch: night(0.222, 0.020, 58), //  Dachkante unter dem Schnee
  stone: night(0.196, 0.014, 258), //  Stein der Laterne
  stoneRim: night(0.268, 0.012, 250), //  ihre vom Schnee angeleuchtete Kante
  bark: night(0.190, 0.024, 52), //  Stamm der Kiefer im Vordergrund

  flakeFar: night(0.286, 0.008, 250),
  flakeMid: night(0.312, 0.007, 250),
  flakeNear: night(0.332, 0.006, 250),

  /* DAS GOLD DER HOEFE UND PFUETZEN — und der Grund, warum es NICHT --accent ist.
     Der zweite Wurf hat die Verlaufsstopps mit dem echten Akzentgold gefuellt
     (#fdb569, Grau 195). Das war ein Vertragsbruch mit Ansage: ein Stopp bei
     40 % Deckkraft ueber einem Schnee von Grau 50 ergibt Grau ~108 — fast das
     Doppelte des Deckels. Dass die Messung trotzdem gruen gewesen waere, haette
     nur am 0,30er Schleier gelegen, und genau davon soll die Zusage NICHT
     abhaengen.

     Warum das Deckeln der Stopps GENUEGT: Alphamischung rechnet in sRGB, und
     die Linearisierung x^2.4 ist konvex. Nach Jensen ist die Luminanz einer
     Mischung hoechstens die Mischung der Luminanzen, also hoechstens das Maximum
     der beiden. Liegt JEDER Stopp unter CAP und jeder Untergrund auch, liegt
     jedes gemischte Pixel darunter — ohne dass man einen einzigen Verlauf
     ausrechnen muss.

     Bei CAP ist bei Farbton 64 ein Chroma von 0.08 das Aeusserste, was sRGB
     hergibt (darueber reisst der Gamut). #5a3303 ist also das buntest moegliche
     Licht dieses Bildes — und Buntheit ist hier die Waehrung: die WCAG-Luminanz
     wiegt Blau nur mit 0,0722, ein warmer Ton kauft im selben Budget mehr
     sichtbare Waerme als jedes Aufhellen (yoru §3.2). Auf dem kalten Schnee ist
     der Lichtschein darum kein HELLERER, sondern ein WAERMERER Fleck — und genau
     so sieht Lampenlicht auf Schnee in Wirklichkeit aus. */
  goldSoft: night(0.363, 0.08, 64), //  #5a3303 — Hoefe, Pfuetzen, Lichtsaeume
  glow: night(0.34, 0.07, 62), //  der warme Rest auf Stein und Traufe
};
/** Nur zur Dokumentation: das echte --accent. Es steht NIRGENDS in der Zeichnung
    (s. oben) und ist hier, damit die Verwandtschaft nachprüfbar bleibt. */
const ACCENT = ok(0.825, 0.125, 66); //  #fdb569

/* ═══════════════════════════════════════════════════════════════════════════
   2) DIE FERNEN KAEMME — zwei Schneeberge in Luftperspektive
   ═══════════════════════════════════════════════════════════════════════════
   Mittelpunktverschiebung: eine Strecke wird rekursiv geteilt und der neue
   Punkt ausgelenkt, die Auslenkung je Stufe gedaempft. Daempfung 0.58 statt
   0.5 — die hohen Frequenzen ueberleben laenger, der Kamm bleibt zackig. Bei
   Schneebergen ist die KANTE die ganze Aussage: darunter ist alles gleich
   hell, nur die Silhouette sagt, dass da ein Berg steht. */
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

/** Hoehe einer Kammlinie an der Stelle x (fuer alles, was DARAUF steht). */
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

const rFar = ridgeLine(HILL_FAR, 74, 5, -34);
const rNear = ridgeLine(HILL_NEAR, 46, 5, 26);

put(ridgePath(rFar, C.hillFar));
put(
  `<path d="M${asLine(rFar)}" fill="none" stroke="${C.hillFarRim}" stroke-width="2.4" opacity="0.9"/>`,
);
/* Der Dunst zwischen den Ketten. Ohne ihn stehen zwei Silhouetten aufeinander;
   mit ihm liegt zwischen ihnen Luft. In einer Schneenacht ist diese Luft nicht
   grau, sondern selbst hell — sie traegt das zurueckgeworfene Licht. */
put(
  `<ellipse cx="720" cy="446" rx="920" ry="30" fill="${C.hillFarRim}" opacity="0.32" filter="url(#weich)"/>`,
);
put(ridgePath(rNear, C.hillNear));
put(
  `<path d="M${asLine(rNear)}" fill="none" stroke="${C.hillNearRim}" stroke-width="2" opacity="0.72"/>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   3) DER WALDHANG — schneebeladene Zedern
   ═══════════════════════════════════════════════════════════════════════════
   Eine Sugi ist keine Dreiecksflaeche und kein Rautenstapel: sie hat viele
   flache Etagen, und ihre Zweigspitzen HAENGEN — der aeussere Punkt jeder Etage
   liegt TIEFER als der innere. Diese eine Umkehrung ist der Unterschied
   zwischen „Nadelbaum" und „Zackenmuster".

   DIE SCHNEELAST IST EIN ZWEITER, IDENTISCHER BAUM. Sie wird ZUERST gemalt,
   leicht groesser und nach oben/links versetzt; der dunkle Baum deckt sie dann
   bis auf einen Saum ab. So entsteht genau das, was man im Winterwald sieht:
   Schnee liegt oben und auf der dem Licht zugewandten Seite, der Rest ist
   Schatten. Damit beide Formen deckungsgleich sind, bekommt `cedar` einen
   eigenen Zufallsstrom je Baum — sonst waeren es zwei verschiedene Baeume
   uebereinander, und der Saum liefe wild. */
function cedar(x, baseY, h, w, fill, seed, jitter = 1) {
  const r = rng(seed);
  const jit = (a, b) => a + r() * (b - a);
  const tiers = Math.max(4, Math.min(14, Math.round(h / 46)));
  const step = h / tiers;
  const lft = [];
  const rgt = [];
  for (let k = 0; k < tiers; k++) {
    const t = k / tiers;
    const y = baseY - h * t;
    const spread = (w / 2) * (1 - t) ** 0.72;
    lft.push([x - spread * (1 + jit(-0.14, 0.14) * jitter), y + step * 0.3]);
    lft.push([x - spread * 0.34, y - step * 0.55]);
    rgt.push([x + spread * (1 + jit(-0.14, 0.14) * jitter), y + step * 0.3]);
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

/**
 * Ein Baum mit Schneelast: heller Zwilling zuerst, dunkler Baum darueber.
 *
 * DER VERSATZ MUSS KLEIN SEIN. Der erste Wurf gab dem hellen Zwilling 10 %
 * mehr Breite und 4,5 % mehr Hoehe — bei einem 230 Einheiten breiten
 * Vordergrundbaum sind das 23 Einheiten Ueberstand, und statt eines Saums stand
 * eine WEISSE PYRAMIDE hinter jedem Baum. Ein Schneesaum ist ein paar
 * Zentimeter breit, nicht ein Zehntel des Baumes. Jetzt 3 % Breite und ein
 * fester Versatz von 5 Einheiten nach oben: sichtbar auf jeder Etage, aber nie
 * mehr als ein Rand.
 */
function snowyCedar(x, baseY, h, w, dark, snow, seed) {
  return (
    cedar(x - w * 0.035, baseY - 5, h, w * 1.03, snow, seed) + cedar(x, baseY, h, w, dark, seed)
  );
}

/* Der Hang selbst: eine dunkle Masse zwischen dem zweiten Kamm und dem Dorf.
   Ohne sie steht der Wald auf nichts, und zwischen Kamm und Dach klafft ein
   Band. Die Regie nennt so ein Band beim Namen: ein Loch. */
put(
  `<path d="M-40,${WOOD + 78}C220,${WOOD + 26} 520,${WOOD + 54} 800,${WOOD + 20}` +
    `C1080,${WOOD - 12} 1360,${WOOD + 40} ${W + 40},${WOOD + 6}L${W + 40},${SNOW_TOP + 30}L-40,${SNOW_TOP + 30}Z" fill="${C.wood}"/>`,
);

/* DER ERSTE WURF WAR EIN LATTENZAUN. Gleicher Abstand, gleiche Hoehe, gleiche
   Standlinie — sechzig Zedern in Reih und Glied. Ein Wald sieht anders aus, und
   der Unterschied hat einen Namen: KLUMPEN. Baeume stehen in Gruppen, dazwischen
   sind Luecken, und die Hoehe wechselt nicht von Baum zu Baum, sondern von
   Gruppe zu Gruppe (sie wachsen auf demselben Boden, in derselben Windlage).

   Also drei ueberlagerte Groessen statt einer Schleife mit Zufallsschritt:
     · die STANDLINIE wellt sich (zwei Sinus, teilerfremde Wellenlaengen),
     · die GRUNDHOEHE folgt einer langsamen Welle ueber die Bildbreite,
     · und der SCHRITT wechselt zwischen „im Klumpen" (eng) und „Luecke" (weit).
   Erst dann liest man Wald statt Kamm. */
let seed = 1;
const bodenY = (x) => WOOD + 64 + Math.sin(x / 173) * 21 + Math.sin(x / 61) * 7;
const grundH = (x) => 74 + Math.sin(x / 211 + 1.3) * 22 + Math.sin(x / 97) * 9;

// Waldsaum auf dem zweiten Kamm: klein, weit weg, nur Textur — hier darf es
// gleichmaessiger sein, Entfernung nimmt jeder Gruppe ihre Gruppenhaftigkeit.
for (let x = -30; x < W + 30; x += between(58, 98)) {
  put(snowyCedar(x, heightAt(rNear, x) + 10, between(22, 44), between(11, 20), C.wood, C.snowFar, seed++));
}

// Der eigentliche Wald, in Klumpen.
{
  let x = -40;
  while (x < W + 40) {
    const klumpen = 2 + Math.floor(R() * 4); //  zwei bis fuenf Baeume je Gruppe
    const hBasis = grundH(x);
    for (let k = 0; k < klumpen && x < W + 40; k++) {
      const h = hBasis * between(0.72, 1.24);
      put(
        snowyCedar(
          x,
          bodenY(x) + between(-6, 18),
          h,
          h * between(0.32, 0.46),
          C.cedar,
          C.cedarSnow,
          seed++,
        ),
      );
      x += h * between(0.26, 0.42); //  im Klumpen stehen sie dicht
    }
    x += between(26, 78); //  und dann kommt eine Luecke
  }
}

/* Kahle Laubbaeume am Gassenrand. Sie sind der Gegenrhythmus: ein Winterbild
   nur aus Koniferen liest sich als Saegeblatt. Ein kahler Baum ist im Schnee
   ausserdem das Einzige, was FEIN ist — er gibt dem Auge eine Textur, gegen die
   die grossen weissen Flaechen ueberhaupt erst gross wirken. Vier Stueck, je
   ein Stamm und sechs Gabelungen aus einem einzigen path. */
/* DER ZWEITE WURF WAR EINE FERNSEHANTENNE: ein gerader Stamm, sieben gleich
   geformte Buegel abwechselnd links und rechts, alle gleich lang. Ein kahler
   Baum verzweigt sich REKURSIV — jeder Ast teilt sich wieder, und jede Teilung
   ist kuerzer als die vorige. Das ist der ganze Unterschied, und er kostet acht
   Zeilen.

   Der Baum wird EINMAL als Pfad in die defs gelegt und ZWEIMAL benutzt: dunkel
   in voller Staerke, darueber duenn und hell und um drei Einheiten versetzt —
   das ist der Schnee, der auf der Oberseite jedes Astes liegt. Zwei `<use>`
   kosten 60 Byte, ein zweiter Pfad haette 800 gekostet (REZEPT C). */
const baumDefs = [];
function kahlerBaum(x, baseY, h, seedN) {
  const r = rng(seedN);
  const seg = [];
  const topX = x + h * 0.07 * (r() - 0.5);
  const topY = baseY - h * 0.5;
  seg.push(`M${n(x)},${n(baseY)}Q${n(x + h * 0.02)},${n(baseY - h * 0.3)} ${n(topX)},${n(topY)}`);
  const ast = (px, py, ang, len, depth) => {
    const ex = px + Math.cos(ang) * len;
    const ey = py + Math.sin(ang) * len;
    seg.push(
      `M${n(px)},${n(py)}Q${n(px + Math.cos(ang + 0.34) * len * 0.56)},${n(py + Math.sin(ang + 0.34) * len * 0.56)} ${n(ex)},${n(ey)}`,
    );
    if (depth === 0) return;
    ast(ex, ey, ang - 0.4 - r() * 0.32, len * (0.58 + r() * 0.16), depth - 1);
    ast(ex, ey, ang + 0.38 + r() * 0.34, len * (0.56 + r() * 0.18), depth - 1);
  };
  const UP = -Math.PI / 2;
  ast(topX, topY, UP - 0.4, h * 0.25, 2);
  ast(topX, topY, UP + 0.02, h * 0.29, 2);
  ast(topX, topY, UP + 0.42, h * 0.24, 2);
  // Zwei tiefere Aeste am Stamm, damit die Krone nicht wie ein Besen aufsitzt.
  ast(n(x + h * 0.01), baseY - h * 0.34, UP - 0.86, h * 0.19, 1);
  ast(n(x + h * 0.015), baseY - h * 0.42, UP + 0.84, h * 0.17, 1);
  const id = `b${seedN}`;
  baumDefs.push(`<path id="${id}" d="${seg.join('')}"/>`);
  return (
    `<g fill="none" stroke-linecap="round">` +
    `<g stroke="${C.bark}" stroke-width="${f(h * 0.026)}"><use href="#${id}"/></g>` +
    `<g stroke="${C.snowRoof}" stroke-width="${f(h * 0.013)}" opacity="0.72" transform="translate(-1 -3)">` +
    `<use href="#${id}"/></g></g>`
  );
}
// Gepflanzt werden sie erst weiter unten (Abschnitt 7a): sie stehen IM Schnee,
// und der ist zu diesem Zeitpunkt noch nicht gemalt.

/* ═══════════════════════════════════════════════════════════════════════════
   4) DER SCHNEEGRUND — die groesste und hellste Flaeche des Bildes
   ═══════════════════════════════════════════════════════════════════════════
   Er traegt den Median (s. Kopf). Er ist KEINE gleichmaessige Flaeche: ein
   Verlauf von hinten (dunstig) nach vorn (hell), darauf die getretene Gasse,
   Wehen, Mulden und Spuren. Eine glatte weisse Flaeche laese sich als Papier —
   erst die Struktur macht daraus Schnee. */
/* DIE OBERKANTE IST WELLIG, NICHT GERADE. Der zweite Wurf hatte hier ein
   Rechteck, und quer durch das ganze Bild lief auf y 596 eine messerscharfe
   waagerechte Linie — der Uebergang vom Wald zum Feld sah aus wie eine Naht
   zwischen zwei Bildern. Eine Schneedecke endet nicht, sie laeuft zwischen die
   Staemme aus. Also eine Wellenlinie, und darueber ein Dunstband, das die
   Fugen schliesst. */
put(
  `<path d="M-40,${SNOW_TOP + 12}` +
    `Q160,${SNOW_TOP - 10} 380,${SNOW_TOP + 8}Q560,${SNOW_TOP + 22} 760,${SNOW_TOP - 2}` +
    `Q940,${SNOW_TOP - 18} 1140,${SNOW_TOP + 10}Q1360,${SNOW_TOP + 26} ${W + 40},${SNOW_TOP - 4}` +
    `V${H + 10}H-40Z" fill="url(#grund)"/>`,
);
put(
  `<ellipse cx="760" cy="${SNOW_TOP + 4}" rx="960" ry="26" fill="${C.snowFar}" opacity="0.42" filter="url(#weich)"/>`,
);

/* DIE GASSE. Sie ist die einzige Perspektivlinie des Bildes und der Grund,
   warum man IM Dorf steht statt es anzusehen.

   DER ERSTE WURF WAR EIN SCHEINWERFERKEGEL: ein Trapez mit zwei geraden,
   scharfen Kanten, das sich als Teppich oder als Lichtkegel las, aber nicht als
   Weg. Zwei Dinge fehlten, und beide sind physikalisch:
     (1) SCHNEE HAT KEINE KANTEN. Ein getretener Weg franst aus — die Kante ist
         die Stelle, an der weniger Leute gegangen sind, nicht eine Linie.
         Darum liegt die Flaeche jetzt unter einem eigenen, milden Weichzeichner
         (`saum`, stdDeviation 14 — der grosse Dunst-Filter mit 26 waere zu viel
         und haette sie auf die Haeuser geschmiert).
     (2) EIN WEG IST NICHT GLATT. Er bekommt drei laengs verlaufende Spuren
         (Schlittenkufen, Trampelrinnen), die mit der Perspektive
         zusammenlaufen, und einen dunkleren Saum an beiden Raendern, wo der
         Schnee aufgeschoben liegt. */
put(
  `<g filter="url(#saum)">` +
    `<path d="M716,${SNOW_TOP + 6}C702,${SNOW_TOP + 128} 596,${H - 186} 372,${H + 12}` +
    `H1286C1096,${H - 196} 934,${SNOW_TOP + 134} 890,${SNOW_TOP + 6}Z" fill="${C.snowLane}" opacity="0.66"/>` +
    `</g>`,
);
// Die aufgeschobenen Raender: dunkler, weil dort der Schnee hoch liegt und
// seine eigene Schattenseite hat.
put(
  `<g fill="none" stroke="${C.snowShadow}" stroke-width="9" opacity="0.34" filter="url(#saum)">` +
    `<path d="M714,${SNOW_TOP + 10}C700,${SNOW_TOP + 128} 594,${H - 186} 370,${H + 12}"/>` +
    `<path d="M892,${SNOW_TOP + 10}C936,${SNOW_TOP + 134} 1098,${H - 196} 1288,${H + 12}"/>` +
    `</g>`,
);
// Die Rinnen: drei Laengsspuren, die mit der Perspektive zusammenlaufen.
put(
  `<g fill="none" stroke="${C.snowShadow}" stroke-width="4" opacity="0.3" filter="url(#saum)">` +
    [
      [786, 560, 3],
      [806, 700, 5],
      [826, 848, 4],
    ]
      .map(
        ([xt, xb, w]) =>
          `<path d="M${xt},${SNOW_TOP + 24}Q${n((xt + xb) / 2 - 20)},${n(SNOW_TOP + 260)} ${xb},${H + 10}" stroke-width="${w}"/>`,
      )
      .join('') +
    `</g>`,
);

/* Wehen: flache Ellipsen quer zur Blickrichtung, hell oben / Schatten unten.
   Sie sind das, was einer Schneeflaeche ihre Modellierung gibt. */
put(`<g opacity="0.5">`);
for (let i = 0; i < 16; i++) {
  const y = between(SNOW_TOP + 26, H - 20);
  const t = (y - SNOW_TOP) / (H - SNOW_TOP);
  const x = between(-40, W + 40);
  const rx = between(90, 300) * (0.6 + t);
  const ry = between(7, 18) * (0.6 + t);
  put(
    `<ellipse cx="${n(x)}" cy="${n(y)}" rx="${n(rx)}" ry="${n(ry)}" fill="${C.snowNear}"/>` +
      `<ellipse cx="${n(x + rx * 0.1)}" cy="${n(y + ry * 1.15)}" rx="${n(rx * 0.86)}" ry="${n(ry * 0.6)}" fill="${C.snowShadow}" opacity="0.55"/>`,
  );
}
put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   5) DAS DORF — Minka mit Schneedaechern, und in jedem brennt Licht
   ═══════════════════════════════════════════════════════════════════════════
   Ein Minka ist im Umriss vor allem DACH: steil, gerade, weit ueber die Wand
   hinausstehend. Im Winter kommt eine dritte Flaeche dazu, und sie ist die
   wichtigste: die Schneehaube auf dem oberen Dachdrittel. Sie ist das HELLSTE
   am Haus, heller als jedes Fenster gross ist — dadurch liest man „verschneit",
   bevor man irgendein Detail erkennt.

   Die Unterkante der Haube ist WELLIG (zwei Quadratbogen). Eine gerade Kante
   laese sich als zweifarbiges Dach; erst die Welle sagt „hier ist etwas
   daraufgefallen". */
function minka(x, baseY, w, opts = {}) {
  const wallH = w * 0.38;
  const roofH = w * 0.44;
  const eave = w * 0.3;
  const ry = baseY - wallH;
  const wall = opts.far ? C.wallFar : C.wall;
  const snow = opts.far ? C.snowFar : C.snowRoof;
  const apexY = ry - roofH;
  const Lx = x - w / 2 - eave;
  const Rx = x + w / 2 + eave;
  const Ly = ry + 6;
  /* `t` IST DER ANTEIL DER SCHRAEGE, DEN DER SCHNEE FREI LAESST — und das habe
     ich zwei Runden lang falsch herum gelesen. Erst stand t auf 0.34, das Dach
     sah aus wie ein Partyhut; also habe ich t auf 0.55 GEHOBEN und damit die
     Haube VERKLEINERT, worauf sie vollends zum Kegel wurde. Die Zahl misst vom
     Traufpunkt nach oben: t = 0.55 heisst „Schnee nur auf dem obersten
     Fuenfundvierzigstel-Anteil", also ein schmales Dreieck auf einem breiten.
     Genau das liest das Auge als aufgesetzten Hut.

     Ein verschneites Dach ist in Wirklichkeit FAST GANZ weiss; dunkel bleiben
     die Traufe und ihr Schattensaum. Bei t = 0.16 hat die Schneeflaeche
     annaehernd die Silhouette des Daches — und damit liest man nicht „Dach mit
     Hut", sondern „Dach unter Schnee". Ein Zahlendreher, den keine Messung je
     gefunden haette; sichtbar wurde er erst unter der Lupe. */
  const t = 0.16;
  const p1x = Lx + (x - w * 0.05 - Lx) * t;
  const p1y = Ly + (apexY - Ly) * t;
  const p2x = Rx + (x + w * 0.05 - Rx) * t;
  const s = [
    `<path d="M${n(x - w / 2)},${n(baseY)}h${n(w)}v${n(-wallH)}h${n(-w)}Z" fill="${wall}"/>`,
    `<path d="M${n(Lx)},${n(Ly)}L${n(x - w * 0.05)},${n(apexY)}h${n(w * 0.1)}L${n(Rx)},${n(Ly)}Z" fill="${C.thatch}"/>`,
    /* DIE HAUBE IST EIN DACH, KEINE KUPPEL. Der dritte Wurf zog die Oberkante
       mit zwei weit aussen liegenden Kontrollpunkten hoch — daraus wurde eine
       Woelbung, und zwanzig Haeuser mit Woelbung lesen sich als Iglus. Auf einem
       steilen Strohdach folgt der Schnee der SCHRAEGE; gewoelbt ist nur der
       First, wo sich ein Wulst haelt. Also gerade Linien die Schraege hinauf und
       nur oben ein kleiner Bogen. */
    `<path d="M${n(p1x)},${n(p1y)}` +
      `L${n(x - w * 0.09)},${n(apexY - w * 0.035)}` +
      `Q${n(x)},${n(apexY - w * 0.078)} ${n(x + w * 0.09)},${n(apexY - w * 0.035)}` +
      `L${n(p2x)},${n(p1y)}` +
      `Q${n(x + w * 0.2)},${n(p1y + w * 0.042)} ${n(x)},${n(p1y + w * 0.018)}` +
      `Q${n(x - w * 0.2)},${n(p1y - w * 0.008)} ${n(p1x)},${n(p1y)}Z" fill="${snow}"/>`,
    /* Die Traufkante traegt eine eigene, duenne Schneelippe, die ueber das Dach
       hinaussteht. Ohne sie endet das Dach mit einer messerscharfen Linie; mit
       ihr haengt der Schnee ueber — und das ist die Silhouette, an der man ein
       verschneites Haus auf hundert Meter erkennt. */
    `<path d="M${n(Lx - 3)},${n(Ly)}Q${n(x)},${n(Ly + w * 0.03)} ${n(Rx + 3)},${n(Ly)}` +
      `Q${n(x)},${n(Ly - w * 0.014)} ${n(Lx - 3)},${n(Ly)}Z" fill="${snow}" opacity="0.9"/>`,
  ];
  const nWin = w > 58 ? 2 : 1;
  for (let i = 0; i < nWin; i++) {
    const wx = x + (nWin === 1 ? 0 : (i - 0.5) * w * 0.44);
    /* KLEINER ALS IM DRITTEN WURF (0.15 w statt 0.20, 0.44 wallH statt 0.56).
       Ein Fenster, das ein Fuenftel der Hauswand einnimmt, liest sich als
       Garagentor. Und ab einer gewissen Groesse bekommt es einen Mittelsteg:
       erst der macht aus einer bernsteinfarbenen Flaeche ein SHOJI. */
    const ww = w * 0.15;
    const wh = wallH * 0.44;
    const wy = baseY - wallH * 0.72;
    const col = flame(wx - ww / 2, wx + ww / 2, 0.33, 0.68, 0.055, 0.14, 64);
    s.push(
      `<ellipse cx="${n(wx)}" cy="${n(wy + wh / 2)}" rx="${n(ww * 2.4)}" ry="${n(wh * 2)}" fill="url(#hof)" opacity="${opts.far ? 0.6 : 0.95}"/>`,
      `<rect x="${n(wx - ww / 2)}" y="${n(wy)}" width="${n(ww)}" height="${n(wh)}" fill="${col}"/>`,
    );
    if (w > 100) {
      s.push(
        `<path d="M${n(wx)},${n(wy)}v${n(wh)}M${n(wx - ww / 2)},${n(wy + wh * 0.45)}h${n(ww)}" ` +
          `stroke="${wall}" stroke-width="${f(Math.max(1, w * 0.012))}"/>`,
      );
    }
    if (!opts.far) lights.push({ x: wx, y: baseY, w: ww * 3.6, op: 0.55 });
  }
  return s.join('');
}

/* Das FERNE Dorf: zwischen Waldhang und Schneeflaeche, klein und dunstig, in
   die Baumgrenze geschoben. Es besetzt genau das Band, in dem sonst nichts
   stuende — aber es steht NICHT auf einer Linie. Der zweite Wurf hatte zwoelf
   gleich grosse Haeuser auf derselben Hoehe: das las sich als Briefmarkenreihe.
   Acht Stueck auf verschiedenen Hoehen, in zwei Gruppen links und rechts, und
   die Mitte bleibt frei — dort kommt die Gasse heraus. */
[
  [136, 598, 40], [230, 588, 34], [348, 604, 44], [456, 590, 36],
  [1218, 596, 42], [1346, 586, 34], [1462, 602, 44], [1556, 590, 36],
].forEach(([x, y, w]) => put(minka(x, y, w, { far: true })));

/* Die NAHE ZEILE, links und rechts der Gasse. Sie wachsen zum Betrachter hin —
   das ist die zweite Perspektivlinie und der Grund, warum die Gasse Tiefe hat
   statt nur schmaler zu werden.

   VIER STATT FUENF, UND JEDES DEUTLICH GROESSER. Der zweite Wurf hatte zehn
   Haeuser mit Breiten von 58 bis 176 — die Staffelung war da, aber der Sprung
   zwischen den Stufen zu klein, und ein Dorf aus zehn aehnlich grossen Kaesten
   liest sich als Muster. Jetzt verdoppelt sich die Breite ueber die Reihe
   (78 → 198), und das vorderste Haus ist gross genug, dass man seine Bretter
   sieht. */
[
  [648, 652, 78], [534, 692, 112], [420, 748, 152], [300, 826, 198],
].forEach(([x, y, w]) => put(minka(x, y, w)));
[
  [1000, 656, 82], [1150, 704, 116], [1330, 772, 158], [1540, 858, 208],
].forEach(([x, y, w]) => put(minka(x, y, w)));

/* ═══════════════════════════════════════════════════════════════════════════
   6) DIE LICHTPFUETZEN — was ein Fenster mit Schnee macht
   ═══════════════════════════════════════════════════════════════════════════
   EIN FENSTER OHNE PFUETZE IST EIN AUFKLEBER. Der ganze Titel des Themas haengt
   an dieser Schleife: „Schneelicht" heisst, dass das Licht nicht IM Fenster
   bleibt, sondern auf dem Boden liegt. Jede Pfuetze ist ein Halbrund vor der
   Hauswand, warm, weich, und sie atmet (drei Uhren, damit nicht alle zugleich).
   Sie sind ADDITIV gemalt, aber ihre Farbe liegt unter CAP — hell werden sie
   nicht durch Weiss, sondern durch WAERME (die WCAG-Luminanz wiegt Blau nur mit
   0,0722, ein warmer Ton kauft im selben Budget mehr sichtbare Helligkeit;
   yoru §3.2). */
lights.forEach((L, i) => {
  put(
    `<ellipse class="p${i % 3}" cx="${n(L.x)}" cy="${n(L.y + L.w * 0.16)}" rx="${n(L.w)}" ry="${n(L.w * 0.34)}" ` +
      `fill="url(#pfuetze)" opacity="${f(L.op)}"/>`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   7) SPUREN IM SCHNEE — der Beleg, dass hier jemand wohnt
   ═══════════════════════════════════════════════════════════════════════════
   Zwei Faehrten die Gasse hinauf. Ein Fussabdruck ist im Schnee kein dunkler
   Fleck, sondern eine MULDE: unten Schatten, oben ein heller Wall. Beides
   zusammen kostet zwei Ellipsen und ist der Unterschied zwischen „Punkte" und
   „Spuren". */
put(`<g>`);
for (const [x0, y0, x1, y1, count] of [
  [640, 990, 792, 636, 15],
  [980, 986, 852, 640, 14],
]) {
  for (let k = 0; k < count; k++) {
    const t = k / (count - 1);
    const e = t ** 1.25;
    const x = x0 + (x1 - x0) * e + (k % 2 ? 1 : -1) * (16 - 11 * e);
    const y = y0 + (y1 - y0) * e;
    const rx = f(12 - 8.4 * e);
    const ry = f(4.6 - 3.2 * e);
    put(
      `<ellipse cx="${n(x)}" cy="${n(y)}" rx="${rx}" ry="${ry}" fill="${C.snowShadow}" opacity="0.72"/>` +
        `<ellipse cx="${n(x)}" cy="${n(y - ry * 0.9)}" rx="${rx}" ry="${f(ry * 0.5)}" fill="${C.snowNear}" opacity="0.5"/>`,
    );
  }
}
put(`</g>`);

/* ── 7b) VERSCHNEITE BUESCHE — was unter dem Schnee noch da ist ───────────────
   Sechs Haufen an den Gassenraendern. Ein Busch unter Schnee ist eine Kuppe mit
   einer Schattenseite, aus der ein paar Zweige stehen — mehr braucht es nicht,
   und es ist genau das, was die grosse helle Flaeche zwischen Gasse und Haeusern
   davon abhaelt, ein leeres Feld zu sein. */
[
  [498, 848, 62], [372, 916, 78], [1206, 902, 70],
  [846, 726, 40], [660, 762, 48], [1002, 692, 34],
].forEach(([bx, by, br]) => {
  /* DER ERSTE WURF WAR EINE PFUETZE. Der Schatten lag als 86-%-Ellipse MITTEN
     auf der Kuppe und hat sie zugedeckt — sechs dunkle Flecken auf hellem
     Schnee, und das Auge liest dunkel-auf-hell im Freien als Wasser. Ein
     Schneehaufen hat seinen Schatten NEBEN sich, nicht auf sich: die Kuppe
     bleibt der hellste Ton des Bildes, der Schatten ist eine schmale Sichel an
     ihrem Fuss. */
  put(
    `<ellipse cx="${n(bx + br * 0.12)}" cy="${n(by + br * 0.4)}" rx="${n(br * 0.94)}" ry="${n(br * 0.19)}" fill="${C.snowShadow}" opacity="0.42"/>` +
      `<ellipse cx="${n(bx)}" cy="${n(by)}" rx="${n(br)}" ry="${n(br * 0.52)}" fill="${C.snowCrest}"/>` +
      `<g fill="none" stroke="${C.bark}" stroke-width="${f(br * 0.035)}" stroke-linecap="round">` +
      `<path d="M${n(bx - br * 0.3)},${n(by - br * 0.3)}q${n(-br * 0.16)},${n(-br * 0.4)} ${n(-br * 0.1)},${n(-br * 0.62)}` +
      `M${n(bx + br * 0.16)},${n(by - br * 0.42)}q${n(br * 0.2)},${n(-br * 0.3)} ${n(br * 0.16)},${n(-br * 0.56)}` +
      `M${n(bx + br * 0.44)},${n(by - br * 0.24)}q${n(br * 0.18)},${n(-br * 0.24)} ${n(br * 0.3)},${n(-br * 0.34)}"/></g>`,
  );
});

/* ── 7c) DER GLITZER ─────────────────────────────────────────────────────────
   Frischer Schnee ist nicht matt: er besteht aus Kristallflaechen, und jede,
   die zufaellig richtig steht, wirft ein Licht zurueck. Ohne diese Koernung
   liest sich eine grosse weisse Flaeche als PAPIER — das ist derselbe Befund,
   den Yoru an seinem Kiesstreifen hatte, nur umgekehrt.

   Drei Deckkraft-Gruppen statt neunzig Einzelwerten: die Deckkraft steht am
   `<g>`, nicht am Punkt, und das spart bei neunzig Kreisen gut ein Kilobyte
   (REZEPT C — bezahlt wird aus den schwaechsten Elementen, und ein
   Attributname ist das schwaechste, das es gibt). Die Koerner werden nach
   vorn groesser, weil dort dieselbe Kristallgroesse mehr Bildpunkte einnimmt. */
[0.9, 0.62, 0.36].forEach((op, gi) => {
  const dots = [];
  for (let i = 0; i < 30; i++) {
    const y = between(SNOW_TOP + 24, H - 8);
    const t = (y - SNOW_TOP) / (H - SNOW_TOP);
    dots.push(
      `<circle cx="${n(between(-20, W + 20))}" cy="${n(y)}" r="${f(0.7 + t * 1.4 + gi * 0.2)}"/>`,
    );
  }
  put(`<g fill="${C.snowCrest}" opacity="${op}">${dots.join('')}</g>`);
});

/* ── 7a) DIE KAHLEN BAEUME ────────────────────────────────────────────────────
   Erst hier gepflanzt, weil sie IM Schnee stehen. Vier Stueck, links und rechts
   der Gasse, gestaffelt — der naechste ist der groesste. Sie sind der
   Gegenrhythmus zu den Zedern und die einzige feine Textur im Bild. */
[
  [612, 792, 182, 8101],
  [992, 748, 150, 8102],
  [688, 672, 98, 8103],
].forEach(([bx, by, bh, bs]) => put(kahlerBaum(bx, by, bh, bs)));

/* ═══════════════════════════════════════════════════════════════════════════
   8) DIE SCHNEELATERNE — das Motiv, das man in zwei Worten nennen kann
   ═══════════════════════════════════════════════════════════════════════════
   Eine Yukimi-doro ist an genau drei Dingen erkennbar, und alle drei stehen
   hier: der BREITE, FLACHE HUT (breiter als die Laterne hoch ist, mit
   aufgebogenen Ecken), die KURZEN GEBOGENEN BEINE statt eines Sockels, und der
   SCHNEE, den dieser Hut faengt — dafuer ist er gebaut, daher der Name
   („Schneeschau-Laterne").

   Sie steht rechts der Gasse, im Vordergrund, gross genug, dass man ihre Form
   liest, und so gesetzt, dass sie bei JEDER gemessenen Breite im Bild ist
   (x 1120…1382 gegen die sichere Zone x 140…1460).

   IHR LICHT IST GEDECKELT, UND DAS IST KEIN VERLUST. Der Kasten liegt bei
   x ~1250 und damit in der Lesespalte; `flame` haelt ihn auf CAP (Grau ~60).
   Eine Flaeche von Grau 60 auf einem Schnee von Grau 50 waere allerdings kaum
   ein Licht — deshalb ist der Kasten klein und sein HOF gross, und die
   Schneehaube darueber ist der hellste Ton des ganzen Bildes. Ein Licht wird
   nicht dadurch hell, dass seine Scheibe hell IST, sondern dadurch, dass es
   seine Umgebung aufhellt (yoru §3.4). */
/* DER ERSTE WURF WAR EIN UFO AUF EINEM TISCH. Aufgefallen ist das nicht beim
   Lesen des Codes, sondern erst unter der Lupe: der Hut war eine Linse (die
   Unterseite woelbte sich nach OBEN in ihn hinein), die Schneehaube schwebte als
   zweite, groessere Untertasse darueber, und zwischen Lichtkasten und Beinen
   klaffte ein Loch von 30 Einheiten — drei Teile, die nichts miteinander zu tun
   hatten. Ursache war, dass die Hoehen relativ zu drei verschiedenen Ankern
   gerechnet waren.

   Die Fassung hier rechnet ALLE Hoehen von EINEM Anker (der Standlinie `b`) nach
   oben und laesst jedes Teil das darunter UEBERLAPPEN. Ein Stapel ohne Fugen
   kann nicht auseinanderfallen — und ein Bauteil, dessen Oberkante die
   Unterkante des naechsten schneidet, sieht gebaut aus statt montiert.

       b            Standlinie im Schnee
       b−66         Oberkante der drei Beine
       b−78         Unterkante des Bettes            (ueberlappt die Beine)
       b−872…       Lichtkasten (hibukuro), 66 hoch
       b−154        Deckplatte, ueberkragend
       b−162…b−202  der Hut (kasa), Spitzen bei b−168 aufgebogen
       b−188…b−240  die Schneehaube
       b−240…b−256  das Juwel (hoju), das aus dem Schnee schaut
*/
{
  const x = 1096; //  am rechten Rand der Gasse, nicht in der Haeuserzeile
  const b = 982; //  Standlinie im Schnee — EINZIGER Anker aller Hoehen
  const HW = 168; //  halbe Hutbreite
  const yLeg = b - 108; //  Oberkante der Beine
  const yBed = b - 126; //  Bett ueber den Beinen
  const yBox = b - 216; //  Oberkante des Lichtkastens
  const yLid = b - 232; //  Deckplatte
  const yUnder = b - 240; //  Unterseite des Hutes in der Mitte
  const yTip = b - 252; //  die aufgebogenen Ecken
  const yTop = b - 300; //  Scheitel der Hutoberseite
  const boxW = 116;

  /* SIE IST JETZT DOPPELT SO GROSS WIE IM ZWEITEN WURF, und das war der
     eigentliche Fehler dieser Figur: sie stand mit 240 Einheiten Hoehe im
     selben Groessenbereich wie die Haeuser hinter ihr und las sich damit als
     eines von vielen Dingen. Ein Hauptmotiv ist nicht dadurch Hauptmotiv, dass
     es besonders schoen gezeichnet ist, sondern dadurch, dass es GROSS ist —
     Ukiyos Welle fuellt die ganze Buehne, Hanashigures Pagode ein Drittel davon.
     322 Einheiten Hoehe auf 1000: sie nimmt jetzt ein knappes Drittel der
     Bildhoehe ein und ist das Erste, was man sieht. */

  put(
    `<g fill="${C.stone}">` +
      /* Drei kurze, nach aussen gebogene Beine. Kein Sockel, keine Saeule — das
         ist neben dem breiten Hut das zweite Erkennungsmerkmal einer
         Yukimi-doro, und der Grund, warum sie im Schnee nicht einsinkt. Sie
         sind BREIT (36 Einheiten): der zweite Wurf hatte sie 20 breit, und drei
         duenne Striche unter einem massigen Koerper lesen sich als Tischbeine. */
      [-1, 0, 1]
        .map((k) => {
          const bx = x + k * 92;
          const flare = 16 * k;
          return (
            `<path d="M${n(bx - 18 - flare)},${n(b + 6)}` +
            `Q${n(bx - 17)},${n(b - 56)} ${n(bx - 14)},${n(yLeg)}` +
            `h28Q${n(bx + 17)},${n(b - 56)} ${n(bx + 18 + flare)},${n(b + 6)}Z"/>`
          );
        })
        .join('') +
      // Das Bett fasst die Beine zusammen und traegt den Lichtkasten.
      `<path d="M${n(x - 106)},${n(yLeg + 8)}h212l-18,${n(yBed - yLeg - 8)}h-176Z"/>` +
      // Der Lichtkasten als RAHMEN: zwei Pfosten und ein Sturz; dazwischen
      // bleibt das Papier frei, dort sitzt gleich die Flamme.
      `<path d="M${n(x - boxW / 2)},${n(yBed)}v${n(yBox - yBed)}h19v${n(yBed - yBox)}Z"/>` +
      `<path d="M${n(x + boxW / 2 - 19)},${n(yBed)}v${n(yBox - yBed)}h19v${n(yBed - yBox)}Z"/>` +
      `<path d="M${n(x - boxW / 2 - 8)},${n(yBox)}h${n(boxW + 16)}v-13h${n(-boxW - 16)}Z"/>` +
      // Die Deckplatte, ueberkragend — sie traegt den Hut.
      `<path d="M${n(x - 82)},${n(yBox - 13)}h164l-14,${n(yLid - yBox + 13)}h-136Z"/>` +
      `</g>`,
  );

  /* DER HOF STEHT NACH DEM STEIN, NICHT DAVOR. Im dritten Wurf lag er darunter
     und wurde vom Rahmen, den Pfosten und dem Bett vollstaendig zugedeckt —
     eine Laterne mit einer braunen Scheibe und ohne einen Funken Schein. Licht
     legt sich UEBER die Kanten, die es beleuchtet; erst dadurch werden aus einer
     hellen Flaeche und einem dunklen Rahmen eine Lampe und ihr Gehaeuse. */
  put(
    `<ellipse cx="${n(x)}" cy="${n(yBox + 44)}" rx="214" ry="176" fill="url(#hof)" opacity="0.95"/>`,
  );
  put(
    `<rect class="g0" x="${n(x - boxW / 2 + 19)}" y="${n(yBox + 3)}" width="${n(boxW - 38)}" height="${n(yBed - yBox - 6)}" ` +
      `fill="${flame(x - boxW / 2, x + boxW / 2, 0.352, 0.7, 0.05, 0.14, 62)}"/>`,
  );

  /* DER HUT (kasa). Ein geschlossener Umriss im Uhrzeigersinn: linke Ecke →
     Oberseite → Scheitel → rechte Ecke → Unterseite zurueck. Die Ecken liegen
     HOEHER als die Mitte der Unterseite; das sind die aufgebogenen Ecken, und
     genau daran scheiterte der erste Wurf, der beides vertauscht hatte.

     DER ZWEITE WURF SCHEITERTE AN ETWAS ANDEREM: er war eine glatte LINSE. Ein
     Kasa ist sechseckig, keine Schuessel — er hat Grate, und ohne sie liest das
     Auge die Form als Untertasse. Die Oberseite laeuft darum in zwei Stufen
     (Q ueber einen Zwischenpunkt bei 0.52 HW), und zwei Gratlinien laufen vom
     Scheitel zu den vorderen Ecken. Zwei Striche, 90 Byte, und die Form kippt
     von „Ufo" zu „Dach". */
  put(
    `<path d="M${n(x - HW)},${n(yTip)}` +
      `Q${n(x - HW * 0.78)},${n(yTip - 16)} ${n(x - HW * 0.52)},${n(yTop + 30)}` +
      `Q${n(x - HW * 0.24)},${n(yTop + 2)} ${n(x)},${n(yTop)}` +
      `Q${n(x + HW * 0.24)},${n(yTop + 2)} ${n(x + HW * 0.52)},${n(yTop + 30)}` +
      `Q${n(x + HW * 0.78)},${n(yTip - 16)} ${n(x + HW)},${n(yTip)}` +
      `Q${n(x + HW * 0.6)},${n(yUnder + 4)} ${n(x + HW * 0.32)},${n(yUnder + 10)}` +
      `H${n(x - HW * 0.32)}` +
      `Q${n(x - HW * 0.6)},${n(yUnder + 4)} ${n(x - HW)},${n(yTip)}Z" fill="${C.stone}"/>`,
  );
  put(
    `<g fill="none" stroke="${C.stoneRim}" stroke-width="2" opacity="0.5">` +
      `<path d="M${n(x)},${n(yTop + 4)}L${n(x - HW * 0.86)},${n(yTip - 4)}"/>` +
      `<path d="M${n(x)},${n(yTop + 4)}L${n(x + HW * 0.86)},${n(yTip - 4)}"/></g>`,
  );
  // Die Unterseite faengt das eigene Licht der Lampe. Ein 5 px schmaler Saum
  // auf einem Stein von Grau 20 wird als LICHT gelesen; dieselbe Helligkeit
  // flaechig nur als hellerer Grauton (yoru §3.4).
  put(
    `<path d="M${n(x - HW * 0.5)},${n(yUnder + 6)}Q${n(x)},${n(yUnder + 18)} ${n(x + HW * 0.5)},${n(yUnder + 6)}" ` +
      `fill="none" stroke="${C.glow}" stroke-width="5" opacity="0.85"/>`,
  );

  /* DIE SCHNEEHAUBE — der hellste Ton des Bildes und der Namensgeber des Themas.
     Sie liegt AUF der Hutoberseite: ihre Unterkante folgt deren Kurve (dieselben
     Kontrollpunkte, 12 Einheiten tiefer), ihre Oberkante ist ein unregelmaessiger
     Wulst mit drei Buckeln. Und sie reicht NICHT bis zu den Ecken: an der
     steilen Aussenkante rutscht der Schnee ab, dort bleibt der dunkle Stein
     stehen. Genau dieser dunkle Rand ringsum ist es, der die Haube als etwas
     AUFLIEGENDES lesbar macht statt als weisse Hutoberseite. */
  /* DER DRITTE WURF WAR EINE WOLKE HINTER DEM HUT. Unter der Lupe stand die
     Haube vollstaendig OBERHALB der Hutoberkante — ihre Unterkante lag bei
     yTop−10, die Oberkante des Hutes bei yTop. Zehn Einheiten Luft, und schon
     schwebt der Schnee. Eine aufliegende Schicht muss ihre Unterlage
     UEBERLAPPEN, sichtbar und deutlich: die Unterkante hier folgt der
     Hutoberseite und liegt in der Mitte SECHS Einheiten unter ihr.

     Und sie endet bei ±0.62 HW, nicht an den Ecken: an der steilen Aussenkante
     rutscht der Schnee ab. Der dunkle Stein, der dort stehen bleibt, ist es,
     der die Haube als etwas AUFLIEGENDES lesbar macht statt als weiss
     gestrichene Hutoberseite. */
  put(
    `<path d="M${n(x - HW * 0.62)},${n(yTip - 6)}` +
      `Q${n(x - HW * 0.42)},${n(yTop + 12)} ${n(x - HW * 0.2)},${n(yTop - 18)}` +
      `Q${n(x)},${n(yTop - 34)} ${n(x + HW * 0.22)},${n(yTop - 14)}` +
      `Q${n(x + HW * 0.44)},${n(yTop + 14)} ${n(x + HW * 0.62)},${n(yTip - 4)}` +
      `Q${n(x + HW * 0.36)},${n(yTop + 26)} ${n(x + HW * 0.14)},${n(yTop + 6)}` +
      `Q${n(x - HW * 0.06)},${n(yTop + 2)} ${n(x - HW * 0.3)},${n(yTop + 14)}` +
      `Q${n(x - HW * 0.5)},${n(yTop + 28)} ${n(x - HW * 0.62)},${n(yTip - 6)}Z" fill="${C.snowCrest}"/>`,
  );
  // Das Juwel (hoju) stoesst durch die Haube — der Beweis, dass darunter ein
  // Bauwerk steckt und nicht nur ein Schneehaufen. Es steht NACH dem Schnee,
  // sonst waere es darunter verschwunden (im dritten Wurf war es das).
  put(
    `<path d="M${n(x - 13)},${n(yTop - 26)}Q${n(x)},${n(yTop - 64)} ${n(x + 13)},${n(yTop - 26)}Z" fill="${C.stoneRim}"/>` +
      `<path d="M${n(x - 9)},${n(yTop - 44)}Q${n(x - 3)},${n(yTop - 58)} ${n(x + 2)},${n(yTop - 52)}" ` +
      `fill="none" stroke="${C.snowCrest}" stroke-width="4" opacity="0.85"/>`,
  );

  // Die Pfuetze, die sie in den Schnee legt: das groesste warme Feld unten.
  put(
    `<ellipse class="p2" cx="${n(x - 12)}" cy="${n(b + 26)}" rx="286" ry="92" fill="url(#pfuetze)" opacity="0.9"/>`,
  );
  // Schnee haeuft sich an ihrem Fuss — sonst steht sie AUF dem Schnee statt
  // DARIN, und eine Laterne, die auf dem Schnee steht, hat keinen Winter gesehen.
  put(
    `<path d="M${n(x - 146)},${n(b + 30)}Q${n(x - 98)},${n(b - 26)} ${n(x - 22)},${n(b - 20)}` +
      `Q${n(x + 82)},${n(b - 28)} ${n(x + 146)},${n(b + 28)}Z" fill="${C.snowNear}"/>`,
  );
  put(
    `<ellipse cx="${n(x + 10)}" cy="${n(b + 34)}" rx="124" ry="16" fill="${C.snowShadow}" opacity="0.4"/>`,
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   9) DER VORDERGRUND LINKS — die Kiefer, und der Schneewall am Bildrand
   ═══════════════════════════════════════════════════════════════════════════
   Ohne einen dunklen Anker ganz vorn ist ein helles Bild eine Tapete. Die
   Kiefer links ist dieser Anker (die Rolle, die bei Hanashigure die Pagode
   spielt und bei Nagareboshi die Zeder) — sie schneidet die Buehne an, sie
   reicht bis in den Himmel, und ihre Schneelast ist das Gegenstueck zur
   Schneehaube der Laterne rechts. Das Bild steht damit auf zwei Beinen. */
put(`<path d="M168,${H + 10}V620h20v${H + 10 - 620}Z" fill="${C.bark}"/>`);
put(snowyCedar(178, H + 12, 520, 232, C.cedar, C.cedarSnow, 7001));
put(snowyCedar(322, H + 8, 306, 138, C.cedar, C.cedarSnow, 7002));

/* Der Schneewall am unteren Rand: laeuft ueber die volle Breite, damit das Bild
   nicht auf einer geraden Linie endet, und schiebt sich vor die Laterne — erst
   dadurch steht sie IM Schnee statt darauf. */
{
  const pts = [];
  for (let x = -40; x <= W + 40; x += 40) {
    pts.push([x, H - 34 + Math.sin(x / 118) * 20 + Math.sin(x / 47) * 8 + between(-5, 5)]);
  }
  put(
    `<path d="M-40,${H + 10}L${asLine(pts)}L${W + 40},${H + 10}Z" fill="${C.snowNear}"/>` +
      `<path d="M${asLine(pts)}" fill="none" stroke="${C.snowCrest}" stroke-width="2.6" opacity="0.6"/>`,
  );
  // Halme, die durch die Wehe stossen — Massstab und Leben am unteren Rand.
  put(`<g stroke="${C.bark}" fill="none">`);
  for (let i = 0; i < 26; i++) {
    const x = between(-20, W + 20);
    const h = between(18, 62);
    const bend = between(-20, 20);
    const y0 = H - 30 + Math.sin(x / 118) * 20;
    put(
      `<path d="M${n(x)},${n(y0)}Q${n(x + bend * 0.4)},${n(y0 - h * 0.6)} ${n(x + bend)},${n(y0 - h)}" stroke-width="${f(between(1.1, 2.2))}"/>`,
    );
  }
  put(`</g>`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   10) DER HERZSCHLAG — fallende Flocken in vier Tiefen
   ═══════════════════════════════════════════════════════════════════════════
   Andis Bestellung woertlich: „Herzschlag = fallende Flocken in mehreren
   Tiefen, dezent aber sichtbar".

   WIE EIN FALL NAHTLOS LAEUFT. Jede Tiefe traegt ein Feld von Flocken, dessen
   Hoehe GENAU der Fallstrecke entspricht. Das Feld steht dreimal untereinander
   (`<use>` mit y = -LOOP, 0, +LOOP) und wandert um genau LOOP nach unten: nach
   einem Umlauf steht das mittlere Feld dort, wo das obere stand — pixelgleich,
   weil das Muster die Periode LOOP hat. Kein Ausblenden, kein Sprung, und die
   Flocken sind nur EINMAL in der Datei (drei `<use>` kosten 90 Byte, ein
   zweites Feld haette Kilobyte gekostet).

   ZWEI UHREN JE TIEFE, INEINANDER GESCHACHTELT: die aeussere Gruppe faellt, die
   innere schwankt seitwaerts. Waere das Seitwaerts Teil derselben Bewegung,
   spraenge jede Flocke am Umlaufende um den Driftbetrag zur Seite. So schwingt
   sie stattdessen um ihre eigene Bahn, und weil die beiden Perioden
   teilerfremd zueinander stehen, wiederholt sich das sichtbare Muster
   praktisch nie.

   PARALLAXE: nah faellt schnell und gross, fern langsam und klein. Das ist die
   einzige Tiefeninformation, die eine Flocke hat — ohne sie ist Schneefall ein
   flimmernder Vorhang.

   KEINE DECKKRAFT IM SPIEL. Regel (3) verlangt, dass der eingefrorene Zustand
   der hellste ist; bei einer reinen Verschiebung ist jeder Zustand gleich hell,
   und weil `night()` jede Flockenfarbe unter CAP haelt, ist jede erreichbare
   Position unbedenklich. Der reduced-motion-Fall ist ein vollstaendiges Bild
   mit stehendem Schnee. */
const flakeDefs = [];
[
  /* DIE RADIEN SIND DER EINZIGE FREIE HEBEL. Gemessen (blick.mjs) bewegte der
     erste Schneefall 1,5 % der Bildpunkte um ≥2 Stufen — sichtbar, aber sehr
     leise. Mehr Flocken haetten Bytes gekostet (76,8 von 80 KB waren schon
     verbraucht), GROESSERE Flocken kosten keine: die Zahl im `r`-Attribut ist
     dieselbe Laenge. Die Flaeche waechst quadratisch, die Datei gar nicht.
     Und es ist auch das ehrlichere Mittel — dichterer Schneefall waere ein
     anderes Wetter, groessere Flocken sind nur naeher. */
  { key: 'a', count: 250, loop: 520, r: [1.1, 1.9], fill: C.flakeFar, op: 0.66 },
  { key: 'b', count: 170, loop: 640, r: [1.8, 2.9], fill: C.flakeMid, op: 0.78 },
  { key: 'c', count: 96, loop: 800, r: [2.9, 4.4], fill: C.flakeNear, op: 0.85 },
  { key: 'd', count: 40, loop: 1000, r: [5.4, 8.4], fill: C.flakeNear, op: 0.44 },
].forEach(({ key, count, loop, r, fill, op }) => {
  const dots = [];
  for (let i = 0; i < count; i++) {
    dots.push(
      `<circle cx="${n(between(-30, W + 30))}" cy="${n(between(0, loop))}" r="${f(between(r[0], r[1]))}"/>`,
    );
  }
  flakeDefs.push(`<g id="s${key}" fill="${fill}" opacity="${op}">${dots.join('')}</g>`);
  put(
    `<g class="fall${key}"><g class="sway${key}">` +
      `<use href="#s${key}" y="${-loop}"/><use href="#s${key}"/><use href="#s${key}" y="${loop}"/>` +
      `</g></g>`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   11) DIE UHREN
   ═══════════════════════════════════════════════════════════════════════════
   Fall und Schwanken sind paarweise teilerfremd gewaehlt (37/31/26/22 gegen
   43/38/33/29), damit kein gemeinsamer Takt entsteht. Die Lichter flackern auf
   drei eigenen Uhren, die Pfuetzen atmen auf drei weiteren — alle nur nach
   unten (Regel 3).

   Warum das hier steht und nicht in der Themen-CSS: die Ebene wird per
   `background-image: url(...)` geladen, und darin laeuft nur, was IM Bild
   steht. Chrome animiert SVG-Bilder deklarativ mit (REZEPT B, empirisch
   belegt); faellt das irgendwo aus, bleibt das Bild in seinem HELLSTEN Zustand
   stehen — vollstaendig und kontrastgeprueft, nur eben still. */
const css = [];
[
  ['a', 37, 520, 43, 22],
  ['b', 31, 640, 38, -30],
  ['c', 26, 800, 33, 40],
  ['d', 22, 1000, 29, -52],
].forEach(([k, fallSec, loop, swaySec, drift]) => {
  css.push(
    `.fall${k}{animation:fa${k} ${fallSec}s linear infinite}`,
    `@keyframes fa${k}{from{transform:translateY(0)}to{transform:translateY(${loop}px)}}`,
    `.sway${k}{animation:sw${k} ${swaySec}s ease-in-out infinite;animation-delay:${-swaySec * 0.37}s}`,
    `@keyframes sw${k}{0%,100%{transform:translateX(0)}50%{transform:translateX(${drift}px)}}`,
  );
});
[
  [24, 0.72],
  [29, 0.8],
  [35, 0.66],
].forEach(([p, lo], i) => {
  css.push(
    `.g${i}{animation:gl${i} ${p}s ease-in-out infinite;animation-delay:${-p * 0.29 * (i + 1)}s}`,
    `@keyframes gl${i}{0%,100%{opacity:1}38%{opacity:${lo}}64%{opacity:${(lo + 0.14).toFixed(2)}}}`,
  );
});
[
  [27, 0.74],
  [33, 0.82],
  [39, 0.68],
].forEach(([p, lo], i) => {
  css.push(
    `.p${i}{animation:pf${i} ${p}s ease-in-out infinite;animation-delay:${-p * 0.23 * (i + 1)}s}`,
    `@keyframes pf${i}{0%,100%{opacity:1}46%{opacity:${lo}}}`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   12) AUSGABE
   ═══════════════════════════════════════════════════════════════════════════
   Geteilte defs (REZEPT C): EIN Hof-Verlauf fuer alle Fenster und die Laterne,
   EIN Pfuetzen-Verlauf fuer alle Lichter auf dem Schnee, EIN Gauss-Filter fuer
   allen Dunst, EIN Verlauf fuer den ganzen Schneegrund.

   KEINE KOMMENTARE IM SVG: ein `--` in einem SVG-Kommentar laesst Chrome die
   GANZE Datei still verwerfen (REZEPT F). Die Erklaerung steht hier. */
const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
  `role="img" aria-label="Winternacht im Schnee: ein Dorf mit warmen Fenstern, eine Yukimi-doro mit Schneehaube, fallende Flocken">` +
  `<style>${css.join('')}` +
  `@media(prefers-reduced-motion:reduce){*{animation:none!important}}</style>` +
  `<defs>` +
  `<radialGradient id="hof">` +
  // Die Deckkraft darf HOCH sein, seit die Stopps unter CAP liegen — der
  // Vertrag haengt an der Farbe, nicht an der Staerke.
  `<stop offset="0" stop-color="${C.goldSoft}" stop-opacity="0.78"/>` +
  `<stop offset="0.34" stop-color="${C.goldSoft}" stop-opacity="0.4"/>` +
  `<stop offset="0.68" stop-color="${C.goldSoft}" stop-opacity="0.13"/>` +
  `<stop offset="1" stop-color="${C.goldSoft}" stop-opacity="0"/>` +
  `</radialGradient>` +
  `<radialGradient id="pfuetze">` +
  `<stop offset="0" stop-color="${C.goldSoft}" stop-opacity="0.62"/>` +
  `<stop offset="0.3" stop-color="${C.goldSoft}" stop-opacity="0.34"/>` +
  `<stop offset="0.64" stop-color="${C.goldSoft}" stop-opacity="0.12"/>` +
  `<stop offset="1" stop-color="${C.goldSoft}" stop-opacity="0"/>` +
  `</radialGradient>` +
  `<linearGradient id="grund" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.snowFar}"/>` +
  `<stop offset="0.42" stop-color="${C.snowMid}"/>` +
  `<stop offset="1" stop-color="${C.snowNear}"/></linearGradient>` +
  `<filter id="weich" x="-30%" y="-70%" width="160%" height="240%">` +
  `<feGaussianBlur stdDeviation="26"/></filter>` +
  // Der milde Weichzeichner der Gasse. 14 statt 26: mit dem grossen Dunstfilter
  // waere der Weg auf die Haeuser geschmiert, mit gar keinem bleibt er ein
  // Scheinwerferkegel. Beides ausprobiert, beides am Bild gesehen.
  `<filter id="saum" x="-14%" y="-10%" width="128%" height="124%">` +
  `<feGaussianBlur stdDeviation="14"/></filter>` +
  baumDefs.join('') +
  flakeDefs.join('') +
  `</defs>` +
  out.join('') +
  `</svg>\n`;

writeFileSync(OUT, svg);
const bytes = Buffer.byteLength(svg);
console.log(
  `yukiakari-szene.svg  ${(bytes / 1024).toFixed(1)} KB  ·  ${lights.length} Fensterlichter  ·  ` +
    `CAP Lrel ${CAP.toFixed(5)} (Grau ${grau(CAP)})  ·  Safe-Zone x${SAFE.x0}–${SAFE.x1}, y≥${SAFE.y0}`,
);
/** Grauwert eines fertigen Hex — so kann die Zeile nicht mehr von der Palette
    abdriften (sie tat es: nach dem Nachziehen der Kaemme nannte sie noch die
    alten OKLCH-Werte, weil sie ihre eigene Kopie davon hatte). */
const grauOf = (hex) => {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const l = (e) => (e <= 0.04045 ? e / 12.92 : ((e + 0.055) / 1.055) ** 2.4);
  return grau(0.2126 * l(r) + 0.7152 * l(g) + 0.0722 * l(b));
};
console.log(
  `  Toene (Grau/255): Kamm ${grauOf(C.hillFar)} · Wald ${grauOf(C.wood)} · ` +
    `Schnee fern ${grauOf(C.snowFar)} \u2192 nah ${grauOf(C.snowNear)} · Gasse ${grauOf(C.snowLane)} · ` +
    `Haube ${grauOf(C.snowCrest)} · Wand ${grauOf(C.wall)} · Licht ${grauOf(C.goldSoft)} (--accent ${ACCENT} steht nicht im Bild)`,
);
if (bytes > 80 * 1024) {
  console.error('✗ ueber dem 80-KB-Budget der ORDER');
  process.exit(1);
}
