/**
 * YORU (夜) — der Szenen-Generator (Regie v2, Sonderfall DEZENT)
 * ═══════════════════════════════════════════════════════════════════════════
 * DIE ERSTE FASSUNG WAR ZU ~90 % SCHWARZ. Sie hatte die Regie „dezent" als
 * „unsichtbar" gelesen: ein paar Dachkanten am unteren Rand, ein Mond bei 0,32
 * Deckkraft, sonst Grundton. Diese Fassung dreht das um — sie malt ein BILD,
 * und zwar Hoshis ZUHAUSE-GESICHT:
 *
 *   Hauptmotiv     DER ENGAWA-BLICK — man sitzt auf der Veranda und schaut
 *                  hinaus. Der dunkle Boden unter den Füßen, die Traufe über
 *                  dem Kopf, zwei Pfosten links und rechts: das Bild hat einen
 *                  INNENRAUM, aus dem heraus es schaut. Genau das kann
 *                  nagareboshi nicht — dessen Tal liegt kilometerweit weg.
 *   Tiefe          sechs Ebenen, von hinten nach vorn DUNKLER: Himmel →
 *                  ferne Dächer → nahe Dächer → Gartenmauer → Garten →
 *                  Veranda. Die Silhouette entsteht nie dadurch, dass etwas
 *                  schwarz ist, sondern dadurch, dass es sich vor etwas
 *                  Hellerem abhebt.
 *   Das Gold       EINE Laternenkette in PERSPEKTIVE: das nahe Ende hängt
 *                  groß und warm am rechten Traufbalken, die Kette läuft nach
 *                  hinten links in den Garten und wird dabei kleiner UND
 *                  dunkler. Dazu warme Fenster in den Dächern, das Licht der
 *                  Shoji auf den Dielen, eine Steinlaterne im Garten.
 *   Der Mond       eine SICHEL aus zwei Bögen (bewiesenes Motiv der ersten
 *                  Fassung, übernommen). Eine helle Scheibe auf dunklem Grund
 *                  liest sich als Statuslampe; eine Sichel liest sich als
 *                  Himmelskörper — auch dann, wenn sie leise ist.
 *   Die Katze      sitzt auf der Mauer und schaut mit. Sie ist das Detail,
 *                  an dem man merkt, dass dieses Bild NAH ist.
 *   Herzschlag     die Sterne atmen VERSETZT (acht Uhren, 21–43 s, eigene
 *                  Phasen), das Sternenband dazu sehr langsam (47 s), die
 *                  Laternen kaum merklich (drei Uhren, 26/31/37 s).
 *
 * ───────────────────────────────────────────────────────────────────────────
 * DIE HÄRTESTE ZAHL DIESES THEMAS: OKLCH-L ≤ 0.24 IN DER LESESPALTE
 * ───────────────────────────────────────────────────────────────────────────
 * Yoru ist der SONDERFALL: es bringt keine eigenen Token mit, es IST der
 * Basissatz aus `src/index.css` — und den darf niemand anfassen. Daraus folgt
 * eine Grenze, die kein anderes Thema hat. `--text-3` (#8a8580, Lrel 0.23785)
 * erreicht 4,5:1 nur gegen einen Untergrund mit Lrel ≤ 0.01397; das ist
 * OKLCH-L 0.24, in sRGB etwa #211f1c. Zum Vergleich: nagareboshi durfte seinen
 * hellsten Mittelgrund-Pixel auf 49/255 stellen, weil es sein `--text-4` selbst
 * auf L 0.74 anheben konnte. Yoru kann das nicht.
 *
 * Also wird die Grenze KONSTRUKTIV erzwungen statt hinterher gemessen:
 *   · `night(L,C,h)` wirft, sobald eine Farbe über CEIL liegt. Jede Fläche,
 *     die in der Spalte stehen KANN, geht durch diese Funktion.
 *   · `freeBoost(x)` ist der einzige Weg an der Grenze vorbei. Es liefert nur
 *     dort > 0, wo die 920-px-Spalte bei KEINER der gemessenen Breiten
 *     hinreicht (viewBox x < 201 bzw. x > 1399, s. Rechnung unten), und es
 *     rampt weich statt zu springen — sonst stünde eine senkrechte Naht im
 *     Bild, genau der Fehler, den aoi an seinem Schleier gefunden hat.
 *
 * DIE RECHNUNG HINTER 201/1399. `cover` + `center bottom`, viewBox 1600×1000:
 *   1440×900   Maßstab 0.9    kein Beschnitt   Spalte = viewBox  289 … 1311
 *   1280×800   Maßstab 0.8    kein Beschnitt   Spalte = viewBox  225 … 1375
 *   1024×768   Maßstab 0.768  133 je Seite ab  Spalte = viewBox  201 … 1399
 * Die Vereinigung ist [201, 1399] — der schmalste Randraum gehört zu 1024, und
 * genau der bestimmt, wo Licht brennen darf. (Bei 1920×1080 ist die Spalte mit
 * [417, 1183] deutlich enger, dafür fallen oben 100 viewBox-Einheiten weg; das
 * Bild ist deshalb unten verankert und trägt seine Aussage im unteren Drittel.)
 *
 * ZWEI REGELN, DIE JEDE FARBE HIER EINHÄLT
 * (1) NICHTS IST DUNKLER ALS --bg-base (OKLCH L 0.115). Über der Spalte liegt
 *     ein Schleier in eben diesem Grundton; läge ein Bildton darunter, würde
 *     der Schleier dort AUFHELLEN und als Rechteck im Himmel stehen.
 * (2) DIE ANIMATION DARF NUR ABDUNKELN. Jede Keyframe startet bei opacity:1
 *     und geht nur nach unten. Damit ist der gemalte (und der
 *     reduced-motion-) Zustand zugleich der Worst Case der Kontrastmessung —
 *     messbar, ohne die Uhren im Bild von außen anhalten zu können.
 *
 * Fester Zufalls-Startwert: derselbe Lauf liefert bitgleich dasselbe Bild.
 *
 *   node tools/theme-contrast/yoru-szene.gen.mjs
 *   → frontend/public/themes/yoru-szene.svg
 */

import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', '..', 'frontend', 'public', 'themes', 'yoru-szene.svg');

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
const R = rng(0x30fa17c2);
const between = (lo, hi) => lo + R() * (hi - lo);

/* ── OKLCH → sRGB, mit Gamut-Riegel ──────────────────────────────────────── */

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
 * OKLCH → Hex. Der Gamut-Riegel wirft, statt still zu klippen: Klippen
 * verschiebt den FARBTON, nicht nur die Helligkeit — aus einem warmen Gold
 * würde unbemerkt ein anderes Gold, während die Zahl im Quelltext richtig
 * aussieht.
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

/** WCAG-Luminanz derselben Farbe — die Zahl, an der die Lesbarkeit hängt. */
function lum(L, C, h) {
  const lin = toLinear(L, C, h);
  return lin.reduce((s, v, i) => {
    const c = Math.min(1, Math.max(0, v));
    const e = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    const d = e <= 0.04045 ? e / 12.92 : ((e + 0.055) / 1.055) ** 2.4;
    return s + d * [0.2126, 0.7152, 0.0722][i];
  }, 0);
}

/* ── DIE GRENZE ──────────────────────────────────────────────────────────────
   Der rechnerische Deckel ist Lrel 0.01397 (= text-3 bei genau 4,50:1); in
   OKLCH-L ist das je nach Buntheit 0.238…0.243. CEIL steht auf 0.235 und
   lässt damit rund einen Grauwert Luft für das, was ÜBER der Zeichnung liegt:
   Yorus Atmosphäre und die beiden CSS-Licht-Ebenen.

   ES WAR 0.225 UND IST JETZT 0.240 — begründet, nicht mutig. Die zweite Fassung
   hatte den Abstand doppelt bezahlt: einmal über CEIL und noch einmal über
   einen 0,18er Schleier. Gemessen stand der schlechteste Spaltenpixel bei
   text-3 4,77:1 statt bei den erlaubten 4,50:1 — geschenkte Helligkeit, an
   einem Thema, dem genau die fehlte.

   0.240 IST DIE RECHNERISCHE GRENZE, NICHT EINE DARÜBER. Der Deckel liegt auf
   Lrel 0.01397; das ist je nach Buntheit OKLCH-L 0.238…0.243, und `night()`
   prüft jede Farbe einzeln gegen ihn. Die Architektur bleibt damit die der
   zweiten Fassung und ihr wichtigster Satz gilt weiter: DIE ZEICHNUNG ALLEIN
   HÄLT DIE GRENZE EIN, auch mit Schleier 0. Der Schleier ist Reserve für die
   beiden Licht-Ebenen darüber, nicht Teil der Zusage — sonst würde ein
   späteres Drehen an `--yoru-veil` die Lesbarkeit still kippen. */
const CEIL = 0.24;
const BG_BASE = 0.115; // --bg-base aus src/index.css — nichts darf darunter
const T3 = 0.23785; // Lrel von --text-3 (#8a8580)

/** Die Zahl, an der wirklich alles hängt: Lrel, bei dem text-3 genau 4,50:1
    erreicht. CEIL ist nur ihre bequeme Näherung in OKLCH-L — und weil die
    Umrechnung vom Farbton abhängt (ein Blau bei L 0.240 hat eine andere
    Luminanz als ein Warmgrau bei L 0.240), prüft `night()` gegen BEIDE. Die
    Luminanz ist der Riegel, CEIL ist die Leitplanke. */
const CAP = (T3 + 0.05) / 4.5 - 0.05;

/** Farbe für alles, was in der Lesespalte stehen kann. Wirft statt zu klippen. */
function night(L, C, h) {
  const y = lum(L, C, h);
  if (y > CAP + 1e-9) {
    throw new Error(
      `night(${L} ${C} ${h}) reisst den Luminanz-Deckel: ${y.toFixed(5)} > ${CAP.toFixed(5)} ` +
        `— text-3 fiele auf ${((T3 + 0.05) / (y + 0.05)).toFixed(2)}:1`,
    );
  }
  if (L > CEIL + 1e-9) {
    const r = (T3 + 0.05) / (lum(L, C, h) + 0.05);
    throw new Error(
      `night(${L} ${C} ${h}) über CEIL ${CEIL} — text-3 fiele auf ${r.toFixed(2)}:1`,
    );
  }
  if (L < BG_BASE - 1e-9) {
    throw new Error(`night(${L} ${C} ${h}) unter --bg-base ${BG_BASE} — der Schleier hellte auf`);
  }
  return ok(L, C, h);
}

/* ── DER EINZIGE WEG AN DER GRENZE VORBEI ────────────────────────────────────
   0 überall dort, wo die 920-px-Spalte bei irgendeiner gemessenen Breite
   hinreicht; nach außen weich auf 1. Die Rampe ist 90 viewBox-Einheiten lang —
   lang genug, dass keine senkrechte Naht entsteht, kurz genug, dass bei 1024
   (sichtbar ab x = 133) überhaupt noch Rampe übrig ist. */
const FREE_L = 201;
const FREE_R = 1399;
const RAMP = 90;
function freeBoost(x) {
  const d = x < FREE_L ? FREE_L - x : x > FREE_R ? x - FREE_R : 0;
  const t = Math.min(1, d / RAMP);
  return t * t * (3 - 2 * t); // smoothstep — kein Knick am Rampenfuß
}

/**
 * Ein Licht, dessen Helligkeit vom Ort abhängt: in der Spalte auf CEIL
 * gedeckelt, im Randraum bis `hiL`. Chroma wandert mit, sonst würde das Gold
 * beim Hellerwerden ausbleichen.
 *
 * ES NIMMT EINE SPANNE, NICHT EINEN PUNKT — und das ist der ganze Witz an
 * dieser Signatur. Der erste Wurf hat `flame(x)` mit dem MITTELPUNKT der Form
 * gefüttert. Bei einer Laterne von 50 Einheiten Breite fällt das nicht auf;
 * bei einer Shoji-Lichtbahn von 360 Einheiten schon: ihre Mitte lag bei
 * x = 120 (Randraum, also hell erlaubt), ihr rechter Rand aber bei x = 300 —
 * mitten in der Lesespalte. Gemessen stand dort #6f4b26, und `--text-3` fiel
 * von 4,90:1 auf 2,12:1. Bei 1920 war davon nichts zu sehen, weil die Spalte
 * dort schmaler liegt; DREI von vier Breiten waren rot.
 *
 * Der Aufrufer kann den Fehler jetzt nicht mehr machen, weil er gar keinen
 * Punkt mehr übergeben KANN: gerechnet wird mit dem ungünstigsten Ort der
 * ganzen Form. Berührt sie die Spalte auch nur, gilt CEIL.
 */
function flame(x0, x1, loL, hiL, loC, hiC, h) {
  const a = Math.min(x0, x1);
  const b = Math.max(x0, x1);
  // Das Minimum von freeBoost über [a,b]: freeBoost ist 0 auf [FREE_L,FREE_R]
  // und wächst nach außen — also entscheidet der zur Spalte NÄCHSTE Punkt.
  const t = b < FREE_L ? freeBoost(b) : a > FREE_R ? freeBoost(a) : 0;
  const L = Math.min(loL, CEIL) + (hiL - Math.min(loL, CEIL)) * t;
  const C = loC + (hiC - loC) * t;
  return t > 0 ? ok(L, C, h) : night(Math.min(loL, CEIL), loC, h);
}

/* ── Bühne ───────────────────────────────────────────────────────────────── */

const W = 1600;
const H = 1000;

const SKY_BOTTOM = 596; //  Dachlinie der fernen Häuserzeile
const WALL_TOP = 742; //  Oberkante der Gartenmauer
const WALL_BOT = 812;
const DECK_TOP = 884; //  Vorderkante der Veranda — ab hier die Dielen
/* Die Traufe ist bewusst FLACH (70 statt 104): sie ist die dunkelste Fläche des
   Bildes, und eine dunkle Fläche am oberen Rand ist schnell einfach ein Loch.
   70 Einheiten reichen für zwei Sparrenreihen und einen Traufbalken — genug,
   damit man „da ist ein Dach über mir" liest, wenig genug, dass der Himmel die
   obere Bildhälfte behält. */
const EAVE = 70;

const out = [];
const put = (s) => out.push(s);
const n = (v) => Math.round(v);
const f = (v) => Math.round(v * 10) / 10;

/* ── Die Palette der nahen Nacht ─────────────────────────────────────────────
   ANDIS URTEIL ÜBER DIE ZWEITE FASSUNG: „Yoru ist leider sehr dunkel."

   Die zweite Fassung hatte gegen genau diesen Vorwurf schon einmal gearbeitet
   und ihn NICHT getroffen — es lohnt sich, zu verstehen warum, sonst korrigiert
   man dreimal in dieselbe Sackgasse. Sie hat den SPITZENWERT ausgeschöpft
   (der hellste Spaltenpixel stand bei text-3 4,77:1, dicht am erlaubten 4,50)
   und daraus geschlossen, das Budget sei aufgebraucht. Gemessen war das Bild
   aber:

       p50 = 12/255      p95 = 21/255      p99 = 30/255

   Der TYPISCHE Bildpunkt lag bei 12 von 255. Gefühlte Helligkeit ist der
   Median, nicht das Maximum — und der Median hatte mit dem Lesbarkeits-Deckel
   nie etwas zu tun. `measure.mjs` misst den hellsten Punkt (den Worst Case der
   Schrift); dass der am Anschlag stand, sagt über die Wirkung des Bildes
   nichts. Deshalb gibt es jetzt `helligkeit.mjs`, und deshalb steht die
   Verteilung im RESULT.

   DREI HEBEL, alle in diesen Zahlen:

   (1) DIE MASSE STEIGT, NICHT DIE SPITZE. Die Fläche, die über gefühlte
       Helligkeit entscheidet, ist der HIMMEL: er füllt y 70…596, also gut die
       Hälfte des Bildes. Er stand auf L 0.128…0.196 (Grau 9…24) — der
       dunkelste große Block im Bild war ausgerechnet das Einzige, was von
       sich aus leuchtet. Er steht jetzt auf 0.176…0.216 (Grau 16…25).

   (2) BUNTHEIT IST IN DIESEM BUDGET FAST UMSONST. Der Deckel liegt auf der
       WCAG-Luminanz, und die wiegt Blau nur mit 0,0722. Bei gleichem Lrel
       (also gleichem Preis!) reicht ein neutrales Grau bis rgb(34,31,29),
       ein sattes Nachtblau aber bis rgb(12,31,59) — derselbe Kontrastwert,
       ein sichtbar helleres Bild. Der Himmel bekommt darum die dreifache
       Buntheit (C 0.017 → 0.042). Das ist kein Nagareboshi-Blau: es steht
       gegen einen WARMEN Vordergrund (Putz, Dielen, Laternen), und genau
       dieser Gegensatz — kalt draußen, warm zu Hause — IST der Engawa.

   (3) „NAH = DUNKEL" IST HIER FALSCH HERUM (die Lehre der zweiten Fassung,
       sie bleibt gültig und wird nur konsequenter ausgeführt). Der
       Hauptdarsteller ist nah, und er ist es, weil LICHT auf ihn fällt.
       Dunkel sind nur die Dinge ohne eigene Lichtquelle: Traufe, Pfosten,
       Ziegel, die Katze — und die sind KLEIN. Die großen Flächen (Himmel,
       Mauerputz, Kies, Dielen) sind die HELLEN.

   Der Fächer spannt 8 (Katze) bis 30 (Mond, Sterne, Shoji-Licht, Laternen);
   die Spitze ist damit fast unverändert, das Bild aber ungefähr doppelt so
   hell. Die Silhouette entsteht weiterhin nie dadurch, dass etwas schwarz ist,
   sondern dadurch, dass es sich vor etwas Hellerem abhebt. */
const P = {
  /* Der Rahmen aus Holz — ohne Lichtquelle, also die Silhouette des Bildes.
     Er bleibt unten in der Leiter, aber die Sparren fangen jetzt Mond und
     Laterne (0.192 statt 0.176): so liest man Balken statt eines Lochs am
     oberen Bildrand. Die Katze bleibt darunter; ein schwarzes Fell ist das
     Einzige, was hier wirklich schwarz sein darf, sonst verliert sie ihre
     Kontur vor dem hellen Putz. */
  post: night(0.152, 0.01, 58), //  Pfosten und Traufe
  rafter: night(0.198, 0.014, 58), //  Sparren, von Mond und Laterne gestreift
  cat: night(0.134, 0.006, 60),

  /* DER HIMMEL — der Hebel. Halbe Bildfläche, dreifache Buntheit, oben tief
     genug, dass Sterne noch Sterne sind, unten das warme Stadtglühen über der
     Dachlinie. Der Farbwechsel blau→warm ist gewollt: er trennt die kalte
     Höhe von der bewohnten Stadt darunter. */
  skyTop: night(0.182, 0.044, 258),
  skyMid: night(0.208, 0.04, 254),
  /* DER ÜBERGANG WAR MATSCH. Blau (10,23,38) verläuft nach Warm (34,23,13)
     durch Neutralgrau — und über ein Drittel der Bildhöhe gestreckt liest sich
     das als schmutziger Braunschleier, nicht als Stadtglühen. Das Blau hält
     jetzt bis dicht über die Dachlinie (`skyHigh`), und das Warme steht nur
     noch in den letzten Prozent des Verlaufs, wo es hingehört: als schmaler
     heller Streifen HINTER den Dächern. */
  skyHigh: night(0.226, 0.032, 250),
  skyLow: night(0.23, 0.02, 58), //  Stadtglühen, nur noch am Horizont
  band: night(0.234, 0.016, 250), //  das Sternenband, ein Hauch

  /* MONDLICHT als eigene Farbe. `moonGlow` ist der kühle Schein am Himmel um
     die Sichel (ein Verlauf, kein Kreis — Regie-Lektion aus nagareboshi),
     `saum` ist dasselbe Licht dort, wo es auf eine KANTE trifft: Dachgrat,
     Mauerkrone, Verandakante, Pfostenflanke. Beide stehen auf dem Dach des
     Budgets, weil Mondlicht das Hellste in einer Nacht ist — und weil ein
     dünner Saum bei Grau 29 auf einer Fläche von Grau 13 als LICHT gelesen
     wird, während dieselbe Helligkeit flächig nur ein hellerer Grauton wäre. */
  /* DER MOND WAR MESSING. Bei h 78 ergibt CEIL rgb(33,29,24) — auf einem blauen
     Himmel liest das kein Mensch als Mond, sondern als olivbraunen Fleck. Ein
     Mond ist das KÜHLSTE Licht der Nacht; er steht jetzt bei h 250. Das kostet
     nichts (die Grenze liegt auf der Luminanz, nicht auf dem Farbton) und
     bringt sogar etwas ein: derselbe Preis erlaubt bei Blau einen höheren
     Spitzenkanal als bei Neutralgrau. */
  moon: night(CEIL, 0.014, 250),
  moonHof: night(0.232, 0.02, 248),
  moonGlow: night(0.236, 0.028, 246),
  saum: night(0.236, 0.022, 244),

  /* Die Lichter am Himmel liegen ganz oben im Budget. Ein Stern, der nur zwei
     Zahlenwerte über seinem Himmel liegt, ist kein Stern, sondern Rauschen —
     und weil der Himmel gestiegen ist, MUSSTEN die Sterne mitsteigen. */
  /* AUS DEMSELBEN GRUND KÜHL WIE DER MOND. Warme Sterne (h 74) auf blauem
     Himmel waren unter der Lupe orangefarbene Staubkörner — man liest sie als
     Schmutz auf dem Bildschirm, nicht als Licht. Sternenlicht ist kühl; hier
     ist es außerdem der einzige Farbton, der bei gleicher Luminanz einen
     helleren Spitzenkanal hat als der Himmel darunter. */
  starA: night(CEIL, 0.014, 250),
  starB: night(0.232, 0.014, 250),
  starC: night(0.224, 0.012, 252),
  starD: night(0.214, 0.01, 250),

  /* Die Dächer. Sie sind jetzt DUNKLER als vorher (0.198 → 0.176), und das ist
     kein Widerspruch zur Aufhellung, sondern ihre Voraussetzung: die ferne
     Dachlinie stand mit 0.198 vor einem Himmel von 0.196 — gleich hell, also
     keine Linie, sondern Matsch. Jetzt hebt sie sich mit acht Grauwerten ab.
     Der First fängt Mond; daran liest man die Wölbung einer Ziegelreihe. */
  roofFar: night(0.18, 0.016, 250),
  ridgeFar: night(0.212, 0.014, 250),
  wallFar: night(0.162, 0.01, 60),
  roofNear: night(0.16, 0.014, 250),
  ridgeNear: night(0.214, 0.012, 250),
  wallNear: night(0.146, 0.008, 58),

  /* Die Gartenmauer: heller Putz unter einer dunklen Ziegelkrone. Sie ist die
     größte helle Fläche des Bildes und damit der Grund, warum die Katze auf
     ihr als Katze zu erkennen ist. */
  fence: night(0.234, 0.012, 60),
  fenceAlt: night(0.222, 0.011, 63), //  zweiter Putzton, damit die Mauer lebt
  coping: night(0.176, 0.008, 62),
  /* KEIN GRÜN. Der erste Wurf hatte hier Farbton 132 („Garten") — bei dieser
     Helligkeit ergibt das keinen Rasen, sondern ein olivgrünes Band quer durch
     das Bild, das man sofort sieht und nicht deuten kann. Der Streifen vor der
     Mauer ist nachts schlicht Kies und Erde — und Kies ist das, WORAUF der
     Mond fällt: er steigt von 0.158 auf 0.190. */
  garden: night(0.196, 0.011, 66),
  /* Die geschnittenen Büsche. Sie hatten kurzzeitig die Dachfarbe (h 250) —
     auf hellem Kies waren das dann BLAUE Kappen, ein Beet voller Pilze. Ein
     Busch bei Nacht ist nicht blau und (Regel des Hauses) auch nicht grün:
     er ist warmes Dunkel, dunkler als der Kies, auf dem er steht. */
  busch: night(0.16, 0.01, 64),

  /* Die Veranda. Die Dielen standen auf 0.166 („dunkler Boden, die Lichtbahnen
     tragen das Drittel") — am Bild waren sie damit der zweitdunkelste große
     Block, direkt unter dem Blick. Sie stehen jetzt auf 0.190: Holz, auf das
     von hinten Zimmerlicht und von vorn Mond fällt. Die Lichtbahnen darauf
     bleiben das Hellste (CEIL), der Abstand ist immer noch zehn Grauwerte. */
  deck: night(0.196, 0.016, 62),
  deckSeam: night(0.166, 0.014, 62),
  deckEdge: night(0.156, 0.012, 62),
  deckLicht: night(CEIL, 0.03, 64), //  das Papierlicht auf den Brettern
  deckDing: night(0.14, 0.008, 60), //  was auf den Brettern steht

  wire: night(0.186, 0.009, 60),

  /* DAS EINE GOLD (REZEPT D3). `--accent` ist oklch(0.8 0.13 45); dieselbe
     Achse, nur so tief heruntergeholt, wie die Lesespalte es zulässt. Es ist
     die Farbe JEDES Hofes im Bild — Fenster wie Laternen —, damit nicht zwei
     getunte Golds nebeneinander stehen und auseinanderdriften. */
  hofGold: night(0.238, 0.042, 52),
};

/* ═══════════════════════════════════════════════════════════════════════════
   1) DER HIMMEL — und der Schein, den der Mond hineinlegt
   ═══════════════════════════════════════════════════════════════════════════ */
put(`<rect width="${W}" height="${H}" fill="url(#himmel)"/>`);

/* DER MONDSCHEIN AM HIMMEL. Er steht hier, vor Band und Sternen, damit die
   Sterne DARÜBER liegen und nicht darin ertrinken.

   Warum ein Verlauf und kein hellerer Mond: die Sichel selbst darf (wie alles
   in der Lesespalte) nur bis CEIL, also Grau 30. Ein Mond ist aber nicht
   deshalb hell, weil seine Scheibe hell IST, sondern weil er seine Umgebung
   aufhellt. Genau das kostet nichts extra: ein 520 Einheiten weiter Hof, der
   von Grau 29 weich auf den Himmel abfällt, hebt die halbe linke Bildhälfte
   an — und er darf es, weil sein hellster Punkt ebenfalls unter CEIL liegt.
   Ein „stärkerer Mond" ist in diesem Budget immer ein GRÖSSERER Mond. */
/* DER ORT IST BEI 1024 ENTSCHIEDEN WORDEN, NICHT BEI 1366. Er stand auf
   (286, 212) — bei der Galerie-Bühne perfekt, bei 1024 aber lag die untere
   Hälfte der Sichel hinter der ersten Karte. Die zweite Fassung hat das schon
   als Rate-Stelle notiert; mit dem größeren Mond (r 62 → 78) wäre daraus ein
   Fehler geworden. Gerechnet: bei 1024 (Maßstab 0.768, seitlich 133 abge-
   schnitten) beginnt die erste Karte bei viewBox y ≈ 260 und x ≈ 232. Der Mond
   steht deshalb HÖHER (176 statt 212, Unterkante 254) und etwas weiter links
   (272) — damit liegt er bei ALLEN vier Breiten frei, und bei 1366/1440 fällt
   er zusätzlich in den Randraum, wo der Schleier ihn kaum noch dämpft. */
const MOON_X = 272;
const MOON_Y = 176;
put(`<circle cx="${MOON_X}" cy="${MOON_Y}" r="520" fill="url(#mondschein)"/>`);

/* Das Sternenband — bei nagareboshi ist es das Hauptmotiv, hier ist es ein
   Flüstern. Zwei weiche Ellipsen quer über den Himmel, mehr nicht: yoru schaut
   NACH VORN über die Dächer, nicht senkrecht in die Galaxis. */
put(
  `<g class="mw" filter="url(#weich)" opacity="0.5">` +
    `<ellipse cx="560" cy="196" rx="620" ry="86" fill="${P.band}" transform="rotate(-15 560 196)"/>` +
    `<ellipse cx="1180" cy="356" rx="420" ry="62" fill="${P.band}" transform="rotate(-15 1180 356)"/>` +
    `</g>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   2) DIE STERNE — der Herzschlag
   ═══════════════════════════════════════════════════════════════════════════
   240 Punkte in vier Helligkeiten, dichter entlang des Bandes. Jeder gehört zu
   einer von acht Flimmer-Gruppen mit eigener Uhr und eigener Phase; darum
   funkelt der Himmel VERSETZT statt im Gleichtakt zu blinken. Die hellsten 26
   bekommen zusätzlich einen Hof aus dem geteilten Verlauf — flache Kreise
   sähen aus wie Münzen (Regie-Lektion aus nagareboshi). */
const bandDist = (x, y) => {
  // Abstand zur Bandachse (Gerade durch (60,300) mit Steigung -0.27 … +x)
  const yy = 300 + (x - 60) * -0.055;
  return Math.abs(y - yy);
};
/* DER HIMMEL IST GESTIEGEN, ALSO MUSS DIE AUSWAHL DER STERNE MITZIEHEN. Unten
   am Horizont steht der Himmel jetzt auf Grau 25 (Stadtglühen); ein Stern der
   Klasse D (Grau 21) wäre dort DUNKLER als sein Untergrund — ein schwarzer
   Punkt im Himmel, kein Stern. Genau so verliert man beim Aufhellen ein Motiv,
   ohne es zu merken: die Zahl im Quelltext heißt immer noch „Stern".
   Deshalb ein Horizont-Filter: je tiefer, desto nur noch die hellsten Klassen.
   Das ist obendrein richtig — dicht über einer Stadt sieht man wirklich nur
   die hellsten Sterne. */
const stars = [];
for (let i = 0; i < 620; i++) {
  const x = between(-10, W + 10);
  // Sterne stehen zwischen Traufkante und Dachlinie; darüber ist Holz, darunter
  // ist Stadt. Alles außerhalb wäre nur Dateigröße.
  const y = between(EAVE - 12, SKY_BOTTOM - 8);
  const near = Math.max(0, 1 - bandDist(x, y) / 300);
  if (R() > 0.3 + near * 0.6) continue;
  // 0 am oberen Bildrand, 1 an der Dachlinie.
  const tief = (y - (EAVE - 12)) / (SKY_BOTTOM - 8 - (EAVE - 12));
  const q = R();
  const cls = i % 8;
  if (q > 0.955) stars.push({ x, y, r: between(2.2, 3.0), c: P.starA, cls, hof: 2, spike: true });
  else if (q > 0.86) stars.push({ x, y, r: between(1.5, 2.1), c: P.starA, cls, hof: 2 });
  else if (q > 0.66) stars.push({ x, y, r: between(1.1, 1.6), c: P.starB, cls, hof: 1 });
  else if (q > 0.36 && tief < 0.8) stars.push({ x, y, r: between(0.9, 1.3), c: P.starC, cls, hof: 0 });
  else if (tief < 0.55) stars.push({ x, y, r: between(0.7, 1.0), c: P.starD, cls, hof: 0 });
}
/* WARUM SO VIELE HÖFE. In der zweiten Fassung bekamen nur die hellsten 26
   Sterne einen Hof, und das reichte, solange der Himmel bei Grau 9…14 stand:
   ein Punkt von Grau 30 auf Grau 12 ist ein Stern. Der aufgehellte Himmel steht
   bei 16…30 — derselbe Punkt hat jetzt stellenweise vier Grauwerte Vorsprung
   und verschwindet. Unter der Lupe waren die Sterne schlicht weg.

   Ein Hof löst das, ohne die Grenze anzufassen: er addiert Fläche statt
   Helligkeit. Das Auge liest die weiche Aufhellung um einen Punkt als
   Leuchten — genau der Grund, warum das REZEPT flache Kreise verbietet
   („Münzen-Effekt"). Und die hellsten bekommen zusätzlich zwei gekreuzte
   Strahlen: ein Kreuz liest man als Stern, auch wenn es nur drei Pixel misst. */
const byGroup = Array.from({ length: 8 }, () => []);
for (const s of stars) {
  if (s.hof) {
    byGroup[s.cls].push(
      `<circle cx="${n(s.x)}" cy="${n(s.y)}" r="${n(s.r * (s.hof === 2 ? 6 : 4.5))}" fill="url(#hof${s.hof})"/>`,
    );
  }
  byGroup[s.cls].push(`<circle cx="${n(s.x)}" cy="${n(s.y)}" r="${f(s.r)}" fill="${s.c}"/>`);
  if (s.spike) {
    const l = s.r * 4.2;
    byGroup[s.cls].push(
      `<path d="M${n(s.x - l)},${n(s.y)}h${n(l * 2)}M${n(s.x)},${n(s.y - l)}v${n(l * 2)}" ` +
        `stroke="${P.starB}" stroke-width="0.9" opacity="0.7"/>`,
    );
  }
}
byGroup.forEach((g, i) => put(`<g class="f${i}">${g.join('')}</g>`));

/* ═══════════════════════════════════════════════════════════════════════════
   3) DER MOND — eine Sichel aus zwei Bögen
   ═══════════════════════════════════════════════════════════════════════════
   Das bewiesene Motiv der ersten Fassung, unverändert in der Konstruktion und
   nur heller gestellt. Zwei Kreise durch dieselben zwei Punkte (0,±Rm): der
   äußere liefert die Außenkante, der größere innere schneidet die Sichel aus.
   `dx` steuert, wie schmal sie wird. Ein `<path>`, zwei `A`-Befehle. */
/* GRÖSSER STATT HELLER — der einzige Weg, der hier offen steht (s. Mondschein
   oben). Der Radius wächst von 62 auf 78, der Hof von 2,8 auf 3,6 Radien.
   Die Sichel bleibt schmal (dxm wächst mit), sonst wird aus dem Himmelskörper
   eine Statuslampe. */
const Rm = 78;
const dxm = 54;
const rin = Math.sqrt(dxm * dxm + Rm * Rm);
const sichel =
  `M0,${-Rm}A${Rm},${Rm} 0 0,0 0,${Rm}` + `A${f(rin)},${f(rin)} 0 0,1 0,${-Rm}Z`;
/* Der Ort ist gemessen, nicht geschmackvoll: bei der Galerie-Bühne (1366 px)
   beginnt die Lesekarte bei viewBox x ≈ 375. Der erste Wurf hatte den Mond auf
   392 — also GENAU hinter der Kartenkante, wo von der Sichel eine Sichel-Ecke
   übrig blieb. Er steht jetzt links davon und über der Begrüßungszeile. */
put(
  `<g transform="translate(${MOON_X} ${MOON_Y}) rotate(-28)">` +
    `<circle r="${n(Rm * 3.6)}" fill="url(#mondhof)"/>` +
    `<path d="${sichel}" fill="${P.moon}"/>` +
    // Die beleuchtete Außenkante noch einen Hauch heller als die Sichelfläche:
    // daran liest man, dass das Licht von AUSSEN kommt, nicht aus dem Mond.
    `<path d="M0,${-Rm}A${Rm},${Rm} 0 0,0 0,${Rm}" fill="none" stroke="${P.saum}" stroke-width="2.4"/>` +
    `</g>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   4) DIE DÄCHER — ferne Zeile, dann die nahen
   ═══════════════════════════════════════════════════════════════════════════
   Ein japanisches Ziegeldach ist STEIL und GERADE, mit einem dicken Firstziegel
   obendrauf; erst dieses Gefälle macht aus einem Klecks ein Haus (Lektion aus
   nagareboshis Minka). Die nahen Dächer bekommen zusätzlich Ziegelrippen —
   daran liest man, dass sie NAH sind, und genau das trennt yoru von
   nagareboshis fernem Tal. */

/** Ein gedecktes Haus: Wand, Dach, Firstziegel, optional warme Fenster. */
function haus(x, baseY, w, roofH, roofFill, ridgeFill, wallFill, rippen, fenster, saumBreite) {
  const s = [];
  const eaveY = baseY - roofH;
  const over = w * 0.12; // Dachüberstand — ohne ihn sieht es aus wie eine Kiste
  s.push(`<rect x="${n(x)}" y="${n(eaveY)}" width="${n(w)}" height="${n(baseY - eaveY)}" fill="${wallFill}"/>`);
  const peak = eaveY - roofH * 0.62;
  s.push(
    `<path d="M${n(x - over)},${n(eaveY)}L${n(x + w / 2)},${n(peak)}L${n(x + w + over)},${n(eaveY)}Z" fill="${roofFill}"/>`,
  );
  /* DER MONDSAUM. Der Mond steht links oben (x 286, y 212), also bekommt die
     LINKE Dachfläche eine Lichtkante und die rechte nicht. Das ist der
     billigste Weg, aus einer flachen Silhouette einen Körper zu machen: eine
     2 px breite Linie bei Grau 29 auf einem Dach von Grau 13 wird als LICHT
     gelesen, dieselbe Helligkeit flächig nur als hellerer Grauton.
     (Und es ist der Grund, warum in Abschnitt 3 kein „hellerer Mond" nötig
     war: ein Mond ist im Bild da, wo sein Licht LIEGT.) */
  if (saumBreite) {
    s.push(
      `<path d="M${n(x - over)},${n(eaveY)}L${n(x + w / 2)},${n(peak)}" ` +
        `stroke="${P.saum}" stroke-width="${saumBreite}" fill="none" stroke-linecap="round"/>`,
    );
  }
  s.push(
    `<path d="M${n(x + w / 2 - w * 0.09)},${n(peak + 3)}L${n(x + w / 2)},${n(peak - 5)}L${n(x + w / 2 + w * 0.09)},${n(peak + 3)}Z" fill="${ridgeFill}"/>`,
  );
  if (rippen) {
    const k = Math.max(2, Math.round(w / 26));
    const d = [];
    for (let i = 1; i < k; i++) {
      const t = i / k;
      const bx = x - over + (w + over * 2) * t;
      const tx = x + w / 2 + (bx - (x + w / 2)) * 0.04;
      d.push(`M${n(bx)},${n(eaveY)}L${n(tx)},${n(peak + (1 - Math.abs(t - 0.5) * 2) * -2 + 6)}`);
    }
    s.push(`<path d="${d.join('')}" stroke="${ridgeFill}" stroke-width="1" fill="none" opacity="0.5"/>`);
  }
  /* FENSTER MIT HOF (REZEPT D/F: Höfe als Gradient, nie als flacher Kreis).
     Ein warmes Rechteck auf einer dunklen Wand ist ein Aufkleber; erst der
     weiche Schein darum macht daraus eine Lampe hinter Papier — und er ist der
     Grund, warum aus zwanzig Fenstern eine bewohnte Stadt wird statt zwanzig
     heller Punkte. Der Hof nimmt die geteilte Verlaufs-Definition (ein einziges
     `<radialGradient>` für ALLE Fenster, sonst zahlt man ihn zwanzigmal in
     Bytes); nur der KERN geht durch `flame()` und darf im Randraum über die
     Grenze. Der Hof steht dort, wo er überall erlaubt ist. */
  for (const [fx, fy, fw, fh] of fenster || []) {
    const cx = x + w * fx;
    const fwPx = w * fw;
    const fhPx = (baseY - eaveY) * fh;
    const fyPx = eaveY + (baseY - eaveY) * fy;
    s.push(
      `<ellipse cx="${n(cx + fwPx / 2)}" cy="${n(fyPx + fhPx / 2)}" rx="${n(fwPx * 2.6)}" ry="${n(fhPx * 2.4)}" fill="url(#fensterhof)"/>`,
    );
    s.push(
      `<rect x="${n(cx)}" y="${n(fyPx)}" width="${n(fwPx)}" height="${n(fhPx)}" fill="${flame(cx, cx + fwPx, CEIL, 0.66, 0.045, 0.125, 62)}" rx="1"/>`,
    );
  }
  return s.join('');
}

/* GEGEN DIE TAPETE. Der erste Wurf reihte in beiden Zeilen dasselbe Haus mit
   leicht gewürfelter Breite aneinander — am Bild war das ein Strichcode, kein
   Viertel. Eine echte Häuserzeile hat verschiedene FIRSTHÖHEN und ein paar
   Dinge, die gar keine Häuser sind. Darum: die Dachneigung würfelt jetzt weit
   (0.5–1.1), jedes vierte Haus bekommt ein zweites Geschoss, und über die Zeile
   verteilt stehen Antennen, ein Wasserbehälter und Leitungsmasten. */

/** Eine Fernsehantenne — vier Sprossen an einem Mast, das Zeichen für „Wohnhaus". */
function antenne(x, y, s) {
  const d = [`M${n(x)},${n(y)}v${n(-26 * s)}`];
  for (let i = 0; i < 4; i++) {
    const yy = y - 10 * s - i * 5 * s;
    const ww = (10 - i * 1.6) * s;
    d.push(`M${n(x - ww)},${n(yy)}h${n(ww * 2)}`);
  }
  return `<path d="${d.join('')}" stroke="${P.wire}" stroke-width="1.2" fill="none" opacity="0.8"/>`;
}

/** Der Wasserbehälter auf dem Dach — vier Beine, ein Kasten. Sehr japanisch. */
function tank(x, y, s) {
  return (
    `<g>` +
    `<path d="M${n(x - 13 * s)},${n(y)}v${n(-14 * s)}M${n(x + 13 * s)},${n(y)}v${n(-14 * s)}` +
    `M${n(x - 5 * s)},${n(y)}v${n(-14 * s)}M${n(x + 5 * s)},${n(y)}v${n(-14 * s)}" ` +
    `stroke="${P.wallNear}" stroke-width="${f(2.4 * s)}"/>` +
    `<rect x="${n(x - 16 * s)}" y="${n(y - 30 * s)}" width="${n(32 * s)}" height="${n(17 * s)}" rx="${n(3 * s)}" fill="${P.roofNear}"/>` +
    `<rect x="${n(x - 16 * s)}" y="${n(y - 30 * s)}" width="${n(32 * s)}" height="${n(4 * s)}" fill="${P.ridgeNear}"/>` +
    `</g>`
  );
}

/* Die ferne Zeile: kleine Häuser dicht an dicht, hell genug, um Himmel zu
   fangen — sie sind der Grund, warum die Dachlinie eine LINIE ist. */
let fx = -40;
while (fx < W + 40) {
  const w = between(52, 108);
  const rh = between(20, 46);
  const drop = between(-8, 12);
  const hoch = R() > 0.74 ? between(16, 34) : 0; // zweites Geschoss
  const baseY = SKY_BOTTOM + drop;
  put(
    haus(fx, baseY, w, rh + hoch, P.roofFar, P.ridgeFar, P.wallFar, false,
      R() > 0.2 ? (R() > 0.55 ? [[0.2, 0.42, 0.15, 0.28], [0.6, 0.42, 0.15, 0.28]] : [[0.24, 0.42, 0.16, 0.28]]) : null, 1.4),
  );
  if (R() > 0.82) put(antenne(fx + w * 0.7, baseY - rh - hoch, 0.8));
  fx += w + between(4, 20);
}

/* Die nahe Zeile: größere Häuser, Ziegelrippen, mehr warme Fenster. Sie steht
   auf y ≈ 686 und läuft über die volle Breite HINTER der Lesespalte durch. */
let nx = -70;
while (nx < W + 60) {
  const w = between(96, 200);
  const rh = between(34, 78);
  const drop = between(-6, 20);
  const hoch = R() > 0.72 ? between(24, 52) : 0;
  const baseY = 686 + drop;
  const fen = [];
  const kf = 2 + Math.round(R() * 2);
  for (let i = 0; i < kf; i++) fen.push([0.14 + i * 0.28, 0.44, 0.13, 0.26]);
  if (hoch) fen.push([0.32 + R() * 0.2, 0.12, 0.15, 0.18]);
  put(haus(nx, baseY, w, rh + hoch, P.roofNear, P.ridgeNear, P.wallNear, true, fen, 2.4));
  const first = baseY - (rh + hoch) - (rh + hoch) * 0.62;
  if (R() > 0.78) put(antenne(nx + w * 0.72, first + 8, 1));
  else if (R() > 0.86) put(tank(nx + w * 0.5, first + 10, 1));
  nx += w + between(8, 34);
}

/* Zwei Leitungsmasten mit durchhängenden Drähten — sie legen sich quer über die
   Dachlinie und binden die Zeile zusammen, statt sie zu wiederholen. */
for (const [mx, my] of [
  [612, 690],
  [1246, 700],
]) {
  put(`<path d="M${mx},${my}V${my - 168}" stroke="${P.wallNear}" stroke-width="4"/>`);
  put(
    `<path d="M${mx - 26},${my - 150}h52M${mx - 20},${my - 134}h40" stroke="${P.wallNear}" stroke-width="2.6"/>`,
  );
}
put(
  `<path d="M-20,${n(560)}Q300,${n(586)} 612,${n(540)}Q940,${n(590)} 1246,${n(550)}Q1450,${n(524)} ${W + 20},${n(548)}" ` +
    `fill="none" stroke="${P.wire}" stroke-width="1.6" opacity="0.62"/>`,
);
put(
  `<path d="M-20,${n(576)}Q300,${n(604)} 612,${n(558)}Q940,${n(610)} 1246,${n(568)}Q1450,${n(544)} ${W + 20},${n(566)}" ` +
    `fill="none" stroke="${P.wire}" stroke-width="1.2" opacity="0.44"/>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   5) DIE GARTENMAUER — mit Katze
   ═══════════════════════════════════════════════════════════════════════════ */
/* GEGEN DAS BAND. Als eine einzige Fläche über die volle Breite war die Mauer
   der hellste Fleck des Bildes UND sein langweiligster: 22 von 255, exakt
   gleich von links nach rechts, ein Betonriegel. Eine echte Mauer (築地塀) ist
   in Felder geteilt, jedes Feld sitzt ein paar Zentimeter anders hoch, der Putz
   ist nicht überall gleich alt — und irgendwo ist ein TOR. Genau das Tor ist
   das Stück Arbeit, das aus dem Riegel eine Mauer macht: es ist die einzige
   Stelle, an der der Blick HINDURCH kann. */
const TOR_X = 688;
const TOR_W = 96;
{
  let x = -24;
  let i = 0;
  while (x < W + 24) {
    const w = between(132, 226);
    const lift = [0, -7, 5, -3][i % 4];
    const top = WALL_TOP + lift;
    // Das Tor bekommt sein eigenes Feld — dort steht kein Putz.
    if (!(x + w > TOR_X && x < TOR_X + TOR_W)) {
      put(
        `<rect x="${n(x)}" y="${n(top)}" width="${n(w)}" height="${n(WALL_BOT - top)}" fill="${i % 2 ? P.fence : P.fenceAlt}"/>`,
      );
      put(`<rect x="${n(x - 3)}" y="${n(top - 12)}" width="${n(w + 6)}" height="13" fill="${P.coping}"/>`);
      // Mondsaum auf der Ziegelkrone: die einzige waagerechte Fläche der Mauer,
      // die nach OBEN zeigt — also die, auf die der Mond wirklich fällt. Sie
      // zieht als durchgehende Lichtlinie quer durchs Bild und bindet die
      // Felder zusammen, die sonst als einzelne Platten auseinanderfielen.
      put(
        `<path d="M${n(x - 3)},${n(top - 11)}h${n(w + 6)}" stroke="${P.saum}" stroke-width="2" opacity="0.85"/>`,
      );
      // Ziegelfugen auf der Abdeckung
      const d = [];
      for (let k = x; k < x + w; k += 30) d.push(`M${n(k)},${n(top - 12)}v13`);
      put(`<path d="${d.join('')}" stroke="${P.fence}" stroke-width="1" opacity="0.55"/>`);
      // Eine waagerechte Putzkante auf halber Höhe — die Mauer bekommt Maßstab.
      put(
        `<path d="M${n(x)},${n(top + (WALL_BOT - top) * 0.46)}h${n(w)}" stroke="${P.coping}" stroke-width="1.4" opacity="0.4"/>`,
      );
    }
    x += w;
    i++;
  }
}
/* Das Tor: zwei Pfosten, ein kleines Satteldach, und dazwischen ein Spalt, aus
   dem Licht fällt. Es steht in der Lesespalte, hält also die Palette-Grenze —
   der Spalt ist nicht hell, er ist nur HELLER als alles um ihn herum. */
put(
  `<rect x="${TOR_X + 14}" y="${WALL_TOP - 4}" width="${TOR_W - 28}" height="${WALL_BOT - WALL_TOP + 4}" ` +
    `fill="${flame(TOR_X + 14, TOR_X + TOR_W - 14, CEIL, 0.62, 0.05, 0.12, 60)}"/>`,
);
put(
  `<rect x="${TOR_X}" y="${WALL_TOP - 22}" width="14" height="${WALL_BOT - WALL_TOP + 22}" fill="${P.post}"/>` +
    `<rect x="${TOR_X + TOR_W - 14}" y="${WALL_TOP - 22}" width="14" height="${WALL_BOT - WALL_TOP + 22}" fill="${P.post}"/>`,
);
put(
  `<path d="M${TOR_X - 18},${WALL_TOP - 22}L${TOR_X + TOR_W / 2},${WALL_TOP - 44}L${TOR_X + TOR_W + 18},${WALL_TOP - 22}Z" fill="${P.coping}"/>` +
    `<path d="M${TOR_X - 18},${WALL_TOP - 22}L${TOR_X + TOR_W / 2},${WALL_TOP - 44}" stroke="${P.saum}" stroke-width="2" fill="none"/>`,
);

/* ── DIE KATZE ───────────────────────────────────────────────────────────────
   Sie ist das Detail, an dem man merkt, dass dieses Bild NAH ist — und im
   ersten Wurf war sie ein Klecks. Zwei Dinge haben gefehlt:

   (1) FORM. Eine sitzende Katze liest man an drei Kanten: der Rundung von
       Hinterteil zu Nacken, dem senkrechten Brustabfall und den beiden
       Ohrdreiecken. Ein einziger Blob-Pfad hatte alle drei verschluckt; jetzt
       sind Körper, Kopf, Ohren und Schwanz eigene Formen.

   (2) SIE STAND VOR DER FALSCHEN WAND. Der Körper hebt sich gut vom hellen
       Mauerputz ab (0.128 gegen 0.205) — der KOPF aber ragt über die Mauer in
       die nahen Dächer (0.142/0.166), und dort war sie unsichtbar. Sie bekommt
       deshalb ein Randlicht auf der Laternenseite. Das ist nicht nur ein
       Trick, sondern richtig: die Kette hängt rechts oben, also MUSS dort eine
       Kante glühen. */
function katze(x, y, s) {
  const koerper =
    `M-21,0C-26,-20 -20,-40 -7,-49C-1,-53 6,-53 11,-48C18,-41 17,-19 15,0Z`;
  const schwanz = `M-19,-4C-33,-2 -33,12 -15,12C1,12 17,7 26,-3`;
  const ohrL = `M-6,-63L-4,-80L9,-67Z`;
  const ohrR = `M10,-67L19,-80L19,-62Z`;
  const rim = flame(x - 33 * s, x + 26 * s, CEIL, 0.62, 0.04, 0.11, 56);
  return (
    `<g transform="translate(${n(x)} ${n(y)}) scale(${f(s)})">` +
    `<path d="${schwanz}" fill="none" stroke="${P.cat}" stroke-width="7" stroke-linecap="round"/>` +
    `<path d="${koerper}" fill="${P.cat}"/>` +
    `<path d="${ohrL}" fill="${P.cat}"/><path d="${ohrR}" fill="${P.cat}"/>` +
    `<circle cx="6" cy="-58" r="13" fill="${P.cat}"/>` +
    /* ZWEI LICHTQUELLEN, ZWEI KANTEN. Links oben steht der Mond, rechts oben
       hängt die Laternenkette — eine Katze zwischen beiden hat auf JEDER Seite
       eine Kante, und erst das macht sie plastisch statt zu einem Scherenschnitt.
       Die Mondkante ist kühl, die Laternenkante warm; das ist derselbe
       Gegensatz, der das ganze Bild trägt, nur auf 40 Einheiten Breite. */
    `<path d="M-21,0C-26,-20 -20,-40 -7,-49" fill="none" stroke="${P.saum}" stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>` +
    `<path d="M-6,-63L-4,-80" fill="none" stroke="${P.saum}" stroke-width="1.2" stroke-linecap="round" opacity="0.9"/>` +
    // Das Randlicht: Rücken, Kopfrundung und Ohrkante auf der Laternenseite.
    `<path d="M11,-48C18,-41 17,-19 15,0" fill="none" stroke="${rim}" stroke-width="1.6" stroke-linecap="round"/>` +
    `<path d="M17,-64A13,13 0 0,1 14,-47" fill="none" stroke="${rim}" stroke-width="1.6" stroke-linecap="round"/>` +
    `<path d="M12,-66L19,-79" fill="none" stroke="${rim}" stroke-width="1.4" stroke-linecap="round"/>` +
    // Zwei Augen, die das Laternenlicht zurückwerfen.
    `<circle cx="2" cy="-60" r="1.7" fill="${rim}"/>` +
    `<circle cx="12" cy="-60" r="1.7" fill="${rim}"/>` +
    `</g>`
  );
}
put(katze(1104, WALL_TOP - 8, 1.25));

/* ═══════════════════════════════════════════════════════════════════════════
   6) DIE STEINLATERNE im Garten (灯籠)
   ═══════════════════════════════════════════════════════════════════════════ */
function steinlaterne(x, baseY, s) {
  const g = [];
  const P0 = (a, b, c, d) => `<rect x="${n(a)}" y="${n(b)}" width="${n(c)}" height="${n(d)}" fill="${P.post}"/>`;
  g.push(P0(x - 13 * s, baseY - 10 * s, 26 * s, 10 * s)); // Fuß
  g.push(P0(x - 5 * s, baseY - 46 * s, 10 * s, 36 * s)); // Schaft
  g.push(P0(x - 15 * s, baseY - 54 * s, 30 * s, 9 * s)); // Zwischenplatte
  // Das Lichthaus
  g.push(
    `<rect x="${n(x - 12 * s)}" y="${n(baseY - 78 * s)}" width="${n(24 * s)}" height="${n(24 * s)}" fill="${P.post}"/>`,
  );
  g.push(
    `<rect x="${n(x - 7 * s)}" y="${n(baseY - 73 * s)}" width="${n(14 * s)}" height="${n(14 * s)}" fill="${flame(x - 7 * s, x + 7 * s, CEIL, 0.68, 0.05, 0.13, 60)}" rx="1"/>`,
  );
  // Das Dach, geschwungen, plus Knauf
  g.push(
    `<path d="M${n(x - 22 * s)},${n(baseY - 78 * s)}q${n(22 * s)},${n(-17 * s)} ${n(44 * s)},0z" fill="${P.rafter}"/>`,
  );
  // Auch der Stein fängt Mond — sonst hat die Laterne ein leuchtendes Fenster
  // und darüber einen Klecks.
  g.push(
    `<path d="M${n(x - 22 * s)},${n(baseY - 78 * s)}q${n(22 * s)},${n(-17 * s)} ${n(44 * s)},0" fill="none" stroke="${P.saum}" stroke-width="1.4" opacity="0.75"/>`,
  );
  g.push(`<circle cx="${n(x)}" cy="${n(baseY - 96 * s)}" r="${f(4 * s)}" fill="${P.rafter}"/>`);
  return `<g>${g.join('')}</g>`;
}
put(steinlaterne(268, 876, 1.0));

/* Der Streifen Garten zwischen Mauer und Veranda. Er war im ersten Wurf ein
   leeres Band — und ein leeres Band zwischen zwei vollen ist genau die
   „Randstreifen um ein Loch"-Rüge, nur waagerecht. Er trägt jetzt drei Dinge:
   Kies, einen Pfad aus TRITTSTEINEN (飛び石, das Element, an dem man einen
   japanischen Garten erkennt) und ein paar niedrige Büsche. */
put(`<rect x="-20" y="${WALL_BOT}" width="${W + 40}" height="${DECK_TOP - WALL_BOT + 4}" fill="${P.garden}"/>`);
{
  /* Die Trittsteine: unregelmäßig gesetzt, nach vorn größer (Perspektive).
     SIE BRAUCHEN EINE UNTERKANTE. Im ersten Blick auf die aufgehellte Fassung
     lagen sie als blasse Ovale auf dem Kies — Pfützen, keine Steine. Der
     Unterschied ist ein Schatten: ein Stein LIEGT auf etwas, also ist unter
     ihm dunkler Kies, und oben fängt er Mond. Zwei dünne Bögen, mehr nicht. */
  const steine = [];
  for (let i = 0; i < 26; i++) {
    const t = i / 25;
    const x = -30 + t * (W + 60) + between(-18, 18);
    const y = WALL_BOT + 16 + Math.sin(t * 5.1) * 16 + between(-5, 5);
    const rx = between(15, 24) * (0.8 + (y - WALL_BOT) / 90);
    const ry = rx * 0.34;
    steine.push(
      `<ellipse cx="${n(x)}" cy="${n(y)}" rx="${n(rx)}" ry="${n(ry)}" fill="${P.fenceAlt}" opacity="0.8"/>` +
        `<path d="M${n(x - rx)},${n(y)}a${n(rx)},${n(ry)} 0 0,1 ${n(rx * 2)},0" fill="none" stroke="${P.saum}" stroke-width="1.2" opacity="0.55"/>` +
        `<path d="M${n(x - rx)},${n(y)}a${n(rx)},${n(ry)} 0 0,0 ${n(rx * 2)},0" fill="none" stroke="${P.deckSeam}" stroke-width="1.6" opacity="0.7"/>`,
    );
  }
  put(steine.join(''));
  /* Ein paar niedrige Büsche, damit der Kies nicht zur Tapete wird — GESCHNITTEN
     (刈込), nicht gewachsen: breiter als hoch, glatte Kuppe. Der erste Wurf der
     aufgehellten Fassung hatte hohe, lumpige Hügel, und auf hellem Kies lasen
     die sich als Maulwurfshaufen. Ein japanischer Garten ist geschnitten; die
     Form muss das sagen, sonst sagt es die Helligkeit falsch. */
  const b = [];
  for (let i = 0; i < 14; i++) {
    const x = between(-10, W + 10);
    const y = between(WALL_BOT + 10, DECK_TOP - 8);
    const rr = between(15, 28);
    const hh = rr * between(0.5, 0.72);
    b.push(
      `<path d="M${n(x - rr)},${n(y)}q0,${n(-hh)} ${n(rr)},${n(-hh)}q${n(rr)},0 ${n(rr)},${n(hh)}z" fill="${P.busch}"/>` +
        `<path d="M${n(x - rr)},${n(y)}q0,${n(-hh)} ${n(rr)},${n(-hh)}" fill="none" stroke="${P.saum}" stroke-width="1.3" opacity="0.7"/>`,
    );
  }
  put(b.join(''));
  // …und die Halme davor.
  const d = [];
  for (let i = 0; i < 50; i++) {
    const x = between(-10, W + 10);
    const y = between(WALL_BOT + 10, DECK_TOP - 2);
    const hh = between(7, 17);
    d.push(`M${n(x)},${n(y)}q${f(between(-4, 4))},${n(-hh * 0.6)} ${f(between(-7, 7))},${n(-hh)}`);
  }
  put(`<path d="${d.join('')}" stroke="${P.fence}" stroke-width="1" fill="none" opacity="0.5"/>`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   7) DIE VERANDA — der Boden unter den Füßen
   ═══════════════════════════════════════════════════════════════════════════
   Waagerechte Dielen, deren Fugen nach hinten enger werden: das ist die ganze
   Perspektive, die dieses Bild braucht, und sie sagt „du sitzt hier". Der
   vordere Rand ist am dunkelsten. */
put(`<rect x="-20" y="${DECK_TOP}" width="${W + 40}" height="${H - DECK_TOP + 20}" fill="${P.deck}"/>`);
put(`<rect x="-20" y="${DECK_TOP - 5}" width="${W + 40}" height="6" fill="${P.deckEdge}"/>`);
/* Die Vorderkante der Veranda fängt Mond — die durchgehendste waagerechte
   Lichtlinie des Bildes und die Kante, an der man „hier hört der Boden auf
   und der Garten fängt an" liest. Ohne sie steht die Veranda als Block. */
put(`<path d="M-20,${DECK_TOP + 1}H${W + 20}" stroke="${P.saum}" stroke-width="2.2" opacity="0.8"/>`);

/* DIE LICHTBAHNEN DER SHOJI. Hinter dem Betrachter steht die Papiertür; ihr
   Licht fällt in vier Bahnen auf die Dielen, getrennt durch die Schatten der
   Rahmenhölzer. Sie fächern leicht auf (die Lichtquelle ist NAH), sind unten
   breiter als oben und laufen nach hinten aus.

   Das ist die Antwort auf den härtesten Befund der eigenen Sichtung: bei
   1024 px war das untere Bilddrittel eine schwarze Fläche — genau das
   „unteres Drittel leer", das die Regie an der ersten Runde gerügt hat, und
   bei 1366 px war es NICHT aufgefallen. Zweite Fensterbreite ansehen, sonst
   findet man es nicht. */
/* DIE BAHNEN WAREN ZU BREIT, UM BAHNEN ZU SEIN. Mit 350 Einheiten Licht und
   50 Einheiten Schatten waren rund 88 % der Dielen „Bahn" — unter der Lupe las
   sich das als „der Boden wird nach unten heller", nicht als Licht durch eine
   Papiertür. Ein Lichtstreifen braucht die MINDERHEIT der Fläche, sonst ist er
   der Untergrund. Jetzt 300 Licht zu 120 Schatten, und die Schatten werden
   ausdrücklich GEMALT statt nur ausgespart: gegen einen gemalten Schatten hat
   die Bahn siebzehn Grauwerte Vorsprung, gegen bloßes Nichts elf. */
const bahnen = [
  [-70, 270],
  [390, 690],
  [810, 1110],
  [1230, 1570],
];
/* Jede Bahn bekommt ihren EIGENEN Verlauf, weil ihre Helligkeit vom Ort abhängt:
   die äußeren beiden liegen im Randraum und dürfen dort über CEIL gehen, die
   inneren nicht. Ein geteilter Verlauf müsste den kleinsten gemeinsamen Wert
   nehmen und würde die Wärme am Bildrand verschenken. */
const bahnDefs = [];
/* Erst die Schatten der Rahmenhölzer — sie liegen UNTER den Bahnen, damit
   deren weiche Ränder sie überlappen dürfen. Sie laufen nach hinten zusammen,
   spiegelbildlich zum Auffächern des Lichts. */
for (let i = 0; i < bahnen.length - 1; i++) {
  const a = bahnen[i][1];
  const b = bahnen[i + 1][0];
  const mid = (a + b) / 2;
  const k = -0.16;
  put(
    `<path d="M${n(a)},${H + 20}L${n(mid + (a - mid) * (1 - k))},${DECK_TOP - 2}` +
      `L${n(mid + (b - mid) * (1 - k))},${DECK_TOP - 2}L${n(b)},${H + 20}Z" ` +
      `fill="${P.deckEdge}" opacity="0.62"/>`,
  );
}
bahnen.forEach(([xb0, xb1], i) => {
  const mid = (xb0 + xb1) / 2;
  /* Die Bahn wird nach HINTEN BREITER, nicht schmaler — negatives k. Zweimal
     falsch herum gedacht, und beide Male hat es das Bild verraten: erst mit
     0.30 (vier Bühnenscheinwerfer), dann mit 0.10 (immer noch Kegel, nur
     schlankere). Der Grund ist Geometrie, nicht Geschmack: die Shoji steht
     HINTER dem Betrachter, also unterhalb des Bildrands. Ihr Licht läuft von
     dort nach hinten weg und FÄCHERT dabei auf; die Schatten der Rahmenhölzer
     laufen entsprechend nach hinten zusammen. Mit positivem k zeigten die
     Lichtkeile nach unten — das Bild behauptete eine Lampe im Garten. */
  const k = -0.16;
  const xt0 = mid + (xb0 - mid) * (1 - k);
  const xt1 = mid + (xb1 - mid) * (1 - k);
  const c = flame(Math.min(xb0, xt0), Math.max(xb1, xt1), CEIL, 0.6, 0.034, 0.11, 64);
  bahnDefs.push(
    `<linearGradient id="b${i}" x1="0" y1="1" x2="0" y2="0">` +
      `<stop offset="0" stop-color="${c}" stop-opacity="1"/>` +
      `<stop offset="0.42" stop-color="${c}" stop-opacity="0.62"/>` +
      `<stop offset="1" stop-color="${c}" stop-opacity="0"/></linearGradient>`,
  );
  put(
    `<path d="M${n(xb0)},${H + 20}L${n(xt0)},${DECK_TOP - 2}L${n(xt1)},${DECK_TOP - 2}L${n(xb1)},${H + 20}Z" ` +
      `fill="url(#b${i})"/>`,
  );
});

{
  // Fugen quer (die Dielen laufen parallel zur Vorderkante), nach hinten enger.
  const d = [];
  let y = H + 6;
  let step = 34;
  while (y > DECK_TOP + 4) {
    d.push(`M-20,${n(y)}H${W + 20}`);
    y -= step;
    step *= 0.82;
  }
  put(`<path d="${d.join('')}" stroke="${P.deckSeam}" stroke-width="1.6" fill="none" opacity="0.85"/>`);
  // …und die Stoßfugen der einzelnen Bretter, versetzt.
  const e = [];
  let yy = H + 6;
  let st = 34;
  let k = 0;
  while (yy > DECK_TOP + 4) {
    for (let x = -20 + ((k % 2) * 190); x < W + 20; x += 380) {
      e.push(`M${n(x)},${n(yy)}v${n(-st)}`);
    }
    yy -= st;
    st *= 0.82;
    k++;
  }
  put(`<path d="${e.join('')}" stroke="${P.deckSeam}" stroke-width="1" fill="none" opacity="0.5"/>`);
}

/* WAS AUF DER VERANDA STEHT. Drei Dinge, mehr nicht — sie sind der Unterschied
   zwischen „ein Boden" und „hier sitzt jemand": ein Paar Geta neben der Tür,
   eine Teeschale auf einem Tablett, und ein Zabuton, von dem gerade jemand
   aufgestanden ist. Alle drei sind dunkle Silhouetten AUF den Lichtbahnen —
   das ist die billigste und sicherste Art, im Budget etwas lesbar zu machen:
   nicht das Ding hell malen, sondern es vor etwas Helles stellen. */
/* Die Geta (下駄) — zwei Sohlen auf je zwei Zähnen. */
for (const gx of [196, 246]) {
  put(
    `<g><rect x="${gx}" y="948" width="38" height="13" rx="5" fill="${P.deckDing}"/>` +
      `<rect x="${gx + 5}" y="961" width="6" height="9" fill="${P.deckDing}"/>` +
      `<rect x="${gx + 27}" y="961" width="6" height="9" fill="${P.deckDing}"/></g>`,
  );
}
/* Das Tablett mit der Teeschale und der kleinen Kanne. */
put(
  `<g><rect x="852" y="936" width="104" height="10" rx="3" fill="${P.deckDing}"/>` +
    `<path d="M876,936q0,-20 17,-20t17,20z" fill="${P.deckDing}"/>` +
    `<path d="M920,936v-13q0,-9 10,-9t10,9v13z" fill="${P.deckDing}"/>` +
    `<path d="M940,920q9,2 9,7" fill="none" stroke="${P.deckDing}" stroke-width="3"/></g>`,
);
/* Das Zabuton — ein flaches Kissen, leicht schräg. */
put(
  `<path d="M1214,976q-8,-30 6,-34l112,-8q14,-1 18,28q2,20 -12,21l-112,7q-10,1 -12,-14z" fill="${P.deckDing}"/>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   8) TRAUFE UND PFOSTEN — der Innenraum, aus dem heraus man schaut
   ═══════════════════════════════════════════════════════════════════════════
   Das ist der Unterschied zu jedem fernen Nacht-Bild: über dem Kopf ist HOLZ.
   Bei 16:9 fällt die Traufe knapp aus dem Bild (das Motiv ist unten verankert)
   — die Pfosten und die Dielen tragen die Aussage dann allein, und beide
   stehen bei jedem Format. */
put(`<rect x="-20" y="-20" width="${W + 40}" height="${EAVE + 20}" fill="${P.post}"/>`);
{
  const d = [];
  for (let x = -10; x < W + 20; x += 62) d.push(`M${n(x)},0v${EAVE}`);
  put(`<path d="${d.join('')}" stroke="${P.rafter}" stroke-width="3" opacity="0.5"/>`);
}
put(`<rect x="-20" y="${EAVE}" width="${W + 40}" height="7" fill="${P.rafter}"/>`);
/* Die Unterkante des Traufbalkens ist die Kante, die dem Mond zugewandt ist —
   und zugleich die Linie, die den dunklen Holzblock oben vom Himmel trennt.
   Ohne sie hat das Bild ein Loch am oberen Rand (der Befund der zweiten
   Fassung, hier mit Licht gelöst statt mit einem helleren Braun). */
put(`<path d="M-20,${EAVE + 7}H${W + 20}" stroke="${P.saum}" stroke-width="2" opacity="0.7"/>`);
/* Die beiden Pfosten. Sie stehen im Randraum — dort, wo nie eine Glyphe steht.
   Der Mond steht links, also glüht ihre LINKE Flanke; ohne sie sind sie zwei
   schwarze Balken, mit ihr sind sie rund. */
for (const px of [156, 1436]) {
  put(`<rect x="${n(px)}" y="${EAVE}" width="26" height="${H - EAVE + 20}" fill="${P.post}"/>`);
  put(
    `<path d="M${n(px + 1)},${EAVE}V${H + 20}" stroke="${P.saum}" stroke-width="1.6" opacity="0.55"/>`,
  );
  put(`<rect x="${n(px - 4)}" y="${EAVE}" width="34" height="9" fill="${P.rafter}"/>`);
}
/* Ein Sudare (簾), halb aufgerollt, im rechten Feld. */
{
  const d = [];
  for (let y = EAVE + 14; y < EAVE + 96; y += 7) d.push(`M1462,${n(y)}H${W + 20}`);
  put(`<path d="${d.join('')}" stroke="${P.rafter}" stroke-width="3" opacity="0.55"/>`);
  put(`<rect x="1462" y="${EAVE + 96}" width="${W - 1442}" height="13" rx="6" fill="${P.rafter}"/>`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   9) DIE LATERNENKETTE — das nahe, warme Motiv
   ═══════════════════════════════════════════════════════════════════════════
   EINE Kette in PERSPEKTIVE: das nahe Ende hängt groß am rechten Traufbalken,
   die Kette läuft nach hinten links in den Garten. Größe UND Wärme fallen mit
   der Entfernung — und weil das nahe Ende im Randraum liegt, fällt die Wärme
   genau dort ab, wo `freeBoost` es ohnehin verlangt. Perspektive und
   Lesbarkeits-Budget zeigen hier in dieselbe Richtung; das ist kein Zufall,
   sondern der Grund, warum die Kette VON RECHTS kommt. */
function catenary(x0, y0, x1, y1, sag, steps = 30) {
  return Array.from({ length: steps + 1 }, (_, i) => {
    const t = i / steps;
    return [x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + Math.sin(Math.PI * t) * sag];
  });
}
const asLine = (pts) => pts.map(([x, y]) => `${n(x)},${n(y)}`).join('L');

/**
 * Eine Papierlaterne (提灯): Bauch aus einer Ellipse, oben und unten die
 * dunklen Fassungen, dazu drei waagerechte Rippen — ohne die Rippen ist es
 * eine Murmel, mit ihnen ist es Papier über einem Gestell.
 */
function chochin(x, y, r, idx) {
  const kern = flame(x - r, x + r, CEIL, 0.74, 0.055, 0.145, 52);
  const rand = flame(x - r, x + r, 0.216, 0.56, 0.042, 0.11, 44);
  const s = [];
  s.push(`<path d="M${n(x)},${n(y - r * 1.9)}v${n(r * 0.7)}" stroke="${P.wire}" stroke-width="1.2"/>`);
  /* Der Schein, den die Laterne in die Nachtluft wirft — derselbe geteilte
     Verlauf wie bei den Fenstern. Ohne ihn hängt eine Murmel am Draht; mit ihm
     brennt dort etwas. */
  s.push(`<ellipse cx="${n(x)}" cy="${n(y)}" rx="${n(r * 3.2)}" ry="${n(r * 3.6)}" fill="url(#fensterhof)"/>`);
  s.push(`<ellipse cx="${n(x)}" cy="${n(y)}" rx="${f(r)}" ry="${f(r * 1.24)}" fill="${rand}"/>`);
  s.push(`<ellipse cx="${n(x)}" cy="${n(y)}" rx="${f(r * 0.66)}" ry="${f(r * 0.95)}" fill="${kern}"/>`);
  s.push(
    `<path d="M${n(x - r * 0.94)},${n(y - r * 0.5)}h${f(r * 1.88)}M${n(x - r)},${n(y)}h${f(r * 2)}M${n(x - r * 0.94)},${n(y + r * 0.5)}h${f(r * 1.88)}" stroke="${rand}" stroke-width="${f(Math.max(0.6, r * 0.11))}" opacity="0.75" fill="none"/>`,
  );
  s.push(
    `<rect x="${n(x - r * 0.42)}" y="${n(y - r * 1.32)}" width="${f(r * 0.84)}" height="${f(r * 0.26)}" fill="${P.wire}"/>`,
  );
  s.push(
    `<rect x="${n(x - r * 0.34)}" y="${n(y + r * 1.1)}" width="${f(r * 0.68)}" height="${f(r * 0.22)}" fill="${P.wire}"/>`,
  );
  return `<g class="l${idx % 3}">${s.join('')}</g>`;
}

/* Der Draht: vom Traufbalken rechts (nah, hoch) nach hinten links zu einem
   Pfahl im Garten.

   DAS NAHE ENDE STAND AUF x = 1470 — UND WAR DAMIT BEI ZWEI VON VIER BREITEN
   NICHT IM BILD. `cover` schneidet bei 1024 und bei 1366 (der Galerie-Bühne!)
   seitlich ab; sichtbar ist dort nur bis viewBox 1467. Die hellste, wärmste,
   größte Laterne der ganzen Kette — das einzige Ding im Bild, das der Randraum
   über die Grenze hinaus leuchten lässt — lag genau hinter dieser Kante. Sie
   steht jetzt auf 1446: dort greift `freeBoost` noch (Grau ~46 statt der in
   der Spalte erlaubten 34), und sie ist bei JEDER gemessenen Breite zu sehen. */
const kette = catenary(1446, EAVE + 30, 452, 470, 96, 34);
put(`<path d="M${asLine(kette)}" fill="none" stroke="${P.wire}" stroke-width="1.6" opacity="0.85"/>`);
/* Der Pfahl am fernen Ende — sonst hinge die Kette im Nichts. */
put(`<path d="M452,470V${WALL_TOP + 10}" stroke="${P.wire}" stroke-width="3"/>`);
/* Zwölf Laternen, Radius nach der Perspektive: nah 26, fern 7. */
for (let i = 0; i < 12; i++) {
  const t = i / 11;
  const [px, py] = kette[Math.round(t * (kette.length - 1))];
  const r = 26 - 19 * Math.pow(t, 0.78);
  put(chochin(px, py + r * 1.5, r, i));
}

/* ═══════════════════════════════════════════════════════════════════════════
   11) DIE UHREN
   ═══════════════════════════════════════════════════════════════════════════
   Acht Stern-Uhren mit teilerfremden Perioden (21–43 s) und negativen Delays,
   drei Laternen-Uhren, eine für das Band. Alle laufen von opacity:1 nur nach
   unten — der Ruhezustand ist der hellste und damit der Messwert. */
const css = [
  '.mw{animation:mwa 47s ease-in-out infinite;animation-delay:-13s}',
  '@keyframes mwa{0%,100%{opacity:.5}50%{opacity:.33}}',
];
[21, 24, 27, 29, 32, 35, 39, 43].forEach((p, i) => {
  const delay = -(p * (i * 0.149 + 0.07)).toFixed(1);
  const mid = (0.62 + (i % 3) * 0.05).toFixed(2);
  const lo = (0.3 + (i % 4) * 0.06).toFixed(2);
  css.push(
    `.f${i}{animation:fu${i} ${p}s ease-in-out infinite;animation-delay:${delay}s}`,
    `@keyframes fu${i}{0%,100%{opacity:1}22%{opacity:${mid}}48%{opacity:${lo}}72%{opacity:${mid}}}`,
  );
});
[
  [26, 0.72],
  [31, 0.66],
  [37, 0.78],
].forEach(([p, lo], i) => {
  css.push(
    `.l${i}{animation:la${i} ${p}s ease-in-out infinite;animation-delay:${-p * 0.29 * (i + 1)}s}`,
    `@keyframes la${i}{0%,100%{opacity:1}50%{opacity:${lo}}}`,
  );
});

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
  `role="img" aria-label="Nacht von der Veranda: Mondsichel ueber nahen Daechern, eine Laternenkette und eine Katze auf der Mauer">` +
  `<style>${css.join('')}` +
  `@media(prefers-reduced-motion:reduce){*{animation:none!important}}</style>` +
  `<defs>` +
  `<linearGradient id="himmel" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${P.skyTop}"/>` +
  `<stop offset="0.38" stop-color="${P.skyMid}"/>` +
  `<stop offset="0.55" stop-color="${P.skyHigh}"/>` +
  `<stop offset="0.615" stop-color="${P.skyLow}"/>` +
  `<stop offset="1" stop-color="${P.skyLow}"/></linearGradient>` +
  /* Zwei Hof-Stärken statt einer: die hellste Klasse trägt einen deutlichen
     Hof, die mittlere einen halben. Ein einziger Hof für alle hieße entweder
     „22 Münzen" oder „nicht zu sehen". */
  `<radialGradient id="hof2">` +
  `<stop offset="0" stop-color="${P.starA}" stop-opacity="0.62"/>` +
  `<stop offset="0.28" stop-color="${P.starA}" stop-opacity="0.26"/>` +
  `<stop offset="0.6" stop-color="${P.starA}" stop-opacity="0.08"/>` +
  `<stop offset="1" stop-color="${P.starA}" stop-opacity="0"/></radialGradient>` +
  `<radialGradient id="hof1">` +
  `<stop offset="0" stop-color="${P.starB}" stop-opacity="0.4"/>` +
  `<stop offset="0.4" stop-color="${P.starB}" stop-opacity="0.13"/>` +
  `<stop offset="1" stop-color="${P.starB}" stop-opacity="0"/></radialGradient>` +
  /* Der Hof jedes warmen Lichts — EINE Definition für alle Fenster und alle
     Laternen. Sie wird ~70-mal benutzt; einzeln getönte Höfe hätten das
     Byte-Budget gekostet UND die Regel „genau ein Gold" gebrochen. */
  `<radialGradient id="fensterhof">` +
  `<stop offset="0" stop-color="${P.hofGold}" stop-opacity="0.5"/>` +
  `<stop offset="0.24" stop-color="${P.hofGold}" stop-opacity="0.24"/>` +
  `<stop offset="0.56" stop-color="${P.hofGold}" stop-opacity="0.08"/>` +
  `<stop offset="1" stop-color="${P.hofGold}" stop-opacity="0"/></radialGradient>` +
  `<radialGradient id="mondhof">` +
  `<stop offset="0" stop-color="${P.moonHof}" stop-opacity="0.62"/>` +
  `<stop offset="0.32" stop-color="${P.moonHof}" stop-opacity="0.3"/>` +
  `<stop offset="0.62" stop-color="${P.moonHof}" stop-opacity="0.1"/>` +
  `<stop offset="1" stop-color="${P.moonHof}" stop-opacity="0"/></radialGradient>` +
  /* DER MONDSCHEIN: der große, kühle Schein, den die Sichel in den Himmel legt.
     Vier Stopps und eine schnell-dann-flache Kurve — dieselbe Lehre wie beim
     Schleier (REZEPT F): zwei Stopps erzeugen einen sichtbaren Ring, weil das
     Auge im tiefen Schwarz jede Ableitungs-Unstetigkeit als Kante liest. */
  `<radialGradient id="mondschein">` +
  `<stop offset="0" stop-color="${P.moonGlow}" stop-opacity="0.5"/>` +
  `<stop offset="0.22" stop-color="${P.moonGlow}" stop-opacity="0.3"/>` +
  `<stop offset="0.48" stop-color="${P.moonGlow}" stop-opacity="0.13"/>` +
  `<stop offset="0.75" stop-color="${P.moonGlow}" stop-opacity="0.04"/>` +
  `<stop offset="1" stop-color="${P.moonGlow}" stop-opacity="0"/></radialGradient>` +
  bahnDefs.join('') +
  `<filter id="weich" x="-40%" y="-120%" width="180%" height="340%">` +
  `<feGaussianBlur stdDeviation="34"/></filter>` +
  `</defs>` +
  out.join('') +
  `</svg>\n`;

writeFileSync(OUT, svg);
const bytes = Buffer.byteLength(svg);
console.log(
  `yoru-szene.svg  ${(bytes / 1024).toFixed(1)} KB  ·  ${stars.length} Sterne  ·  ` +
    `CEIL L≤${CEIL} (text-3 ≥ ${((T3 + 0.05) / (lum(CEIL, 0.006, 66) + 0.05)).toFixed(2)}:1)  ·  ` +
    `Randraum x<${FREE_L} / x>${FREE_R}`,
);
if (bytes > 80 * 1024) {
  console.error('✗ über dem 80-KB-Budget der ORDER');
  process.exit(1);
}
