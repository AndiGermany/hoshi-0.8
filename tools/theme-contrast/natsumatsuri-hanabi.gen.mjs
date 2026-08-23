/**
 * NATSUMATSURI — der Szenen-Generator (v2, „Hanabi-Taikai", Regie v2 vom 19.08.)
 * ═══════════════════════════════════════════════════════════════════════════
 * ANDIS VERDIKT ÜBER DIE ERSTE FASSUNG: „Das ist leider echt nicht soo gut
 * geworden. […] Inspiriert soll es aber von einem Feuerwerk sein, was dezent
 * ein Festival zeigt."
 *
 * Die erste Fassung war genau der Fehler, den die Regie v2 „Randstreifen um ein
 * Loch" nennt: zehn Laternen als CSS-Verläufe am oberen Rand, eine 3,9-KB-
 * Strichzeichnung eines Feuerwerks klein oben RECHTS, dazwischen nichts. Das
 * Feuerwerk war ein Aufkleber am Bildrand, kein Hauptdarsteller — und ein Fest
 * fand nicht statt.
 *
 * Diese Fassung kehrt die Gewichte um:
 *
 *   Hauptmotiv     DER HIMMEL BLÜHT. Sieben Hanabi über der ganzen Bühne, in
 *                  vier Farben, die größten mit 270 px Radius — Kiku-Blüten mit
 *                  gebogenen, schwer hängenden Strahlen, hellem Kern und einem
 *                  andersfarbigen Herz (芯). Sie laufen HINTER der Lesespalte
 *                  durch; dafür gibt es den Schleier.
 *   Das Fest       DEZENT: ein schmales warmes Band auf 12 % der Bildhöhe —
 *                  vierzehn Yatai-Stände als Silhouetten mit glühender
 *                  Theke, drei Laternenketten darüber, ein Torii, Baumgruppen,
 *                  und davor die ruhige Köpfe-Linie der Menschen am Ufer.
 *                  Kulisse, nicht Konkurrenz.
 *   Das Wasser     der Fluss trägt das untere Viertel: jede Blüte und jede
 *                  Laterne bricht sich darin als Strichstapel (klassisches
 *                  Fluss-Hanabi), zwei Boote mit eigenem Licht.
 *   Vordergrund    die Menschen, von hinten: eine Reihe dunkler Köpfe am
 *                  unteren Bildrand, ein paar gehobene Uchiwa-Fächer. Sie
 *                  schauen nach oben, wie man.
 *   Herzschlag     die Blüten gehen VERSETZT auf — sieben Uhren (23–45 s), jede
 *                  mit schnellem Aufblühen und langem Verglühen. Zu jedem
 *                  Zeitpunkt stehen zwei bis drei im Licht, die übrigen sind
 *                  fort. Dazu drei Uhren fürs Laternen-Atmen, drei fürs Wasser,
 *                  zwei für aufsteigende Schüsse, eine für den Rauch.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * DER KONTRAST-KNIFF (Regel (2) des Rezepts, für ein Feuerwerk gelesen)
 * ───────────────────────────────────────────────────────────────────────────
 * Das Rezept verlangt: jede Keyframe beginnt bei opacity:1 und geht nur RUNTER,
 * damit der eingefrorene Zustand zugleich der hellste und damit der messbare
 * Worst Case ist. Für ein Feuerwerk heißt das etwas Bestimmtes: der GEMALTE
 * Zustand zeigt ALLE SIEBEN Blüten gleichzeitig in voller Pracht — das große
 * Finale. Das ist
 *   · das Bild bei prefers-reduced-motion (und es ist ein schönes),
 *   · der obere Rand jeder Kontrastmessung, ohne dass man die Uhren im Bild
 *     von außen anhalten müsste,
 *   · und trotzdem nicht das, was man im Betrieb sieht: dort verteilen die
 *     versetzten Sichtbarkeitsfenster dieselben sieben Blüten über die Zeit.
 * „Aufgehen" entsteht also NICHT dadurch, dass etwas heller wird, sondern
 * dadurch, dass die anderen gerade fort sind.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ZWEI REGELN, DIE JEDE FARBE HIER EINHÄLT
 * ───────────────────────────────────────────────────────────────────────────
 * (1) NICHTS IST DUNKLER ALS --bg-base (L 0.185). Der Schleier deckt die
 *     Lesespalte mit eben diesem Ton ab; läge ein Bildton darunter, würde der
 *     Schleier dort AUFHELLEN und als sichtbares Rechteck im Himmel stehen.
 *     Dieses Thema hat einen ungewöhnlich HELLEN Grundton (L 0.185 gegen
 *     Nagareboshis 0.105) — es ist die blaue Stunde, keine tiefe Nacht. Die
 *     Silhouetten (Stände, Köpfe, Torii) liegen darum knapp darüber bei
 *     L 0.187–0.205 und heben sich nicht dadurch ab, dass sie schwarz wären,
 *     sondern dadurch, dass Himmel und Wasser dahinter deutlich HELLER sind.
 * (2) DIE ANIMATION DARF NUR ABDUNKELN — siehe oben.
 *
 * Genau EIN Gold: `gold` ist buchstäblich --accent, oklch(0.855 0.17 92).
 *
 * Fester Zufalls-Startwert: derselbe Lauf liefert bitgleich dasselbe Bild.
 *
 *   node tools/theme-contrast/natsumatsuri-hanabi.gen.mjs
 *   → frontend/public/themes/natsumatsuri-hanabi.svg
 *
 * DER DATEINAME BLEIBT. `natsumatsuri-hanabi.svg` ist in
 * frontend/src/components/ThemeGallery.tsx als Vorschaubild eingetragen und
 * wird von themegroups.test.tsx gegen die Platte geprüft; frontend/src/ ist für
 * diesen Pod gesperrt. Also: neuer Inhalt, alter Name (und er passt jetzt
 * sogar besser — die Datei zeigt endlich wirklich ein Hanabi).
 */

import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', '..', 'frontend', 'public', 'themes', 'natsumatsuri-hanabi.svg');

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
const R = rng(0x7a4b1e09);
const between = (lo, hi) => lo + R() * (hi - lo);

/* ── OKLCH → sRGB, mit Gamut-Riegel ──────────────────────────────────────────
   Die Themen-CSS denkt in OKLCH, die SVG braucht Hex. Beide Seiten kommen aus
   DERSELBEN Zahl. Wer klippt, verschiebt den Farbton still — also lieber laut
   abbrechen und das Chroma senken. */
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

/* ── Die Palette der Festnacht ───────────────────────────────────────────────
   Der Himmel wird nach UNTEN heller (Reststreulicht der Stadt), das Land wird
   nach VORN dunkler. Beide Leitern sind bewusst weit gespreizt: über der Szene
   liegt noch der Schleier, und der staucht Unterschiede (Piloten-Lektion 3). */
const C = {
  skyTop: ok(0.205, 0.05, 272),
  skyMid: ok(0.245, 0.058, 268),
  skyLow: ok(0.3, 0.062, 264),
  airGlow: ok(0.44, 0.07, 54), //  das Licht des Festes in der Luft
  smoke: ok(0.36, 0.03, 60), //  Pulverrauch, warm und sehr schwach

  hillFar: ok(0.245, 0.038, 266), //  ferne Hügel, noch dunstig
  hillNear: ok(0.215, 0.036, 264),
  trees: ok(0.202, 0.03, 262), //  Baumgruppen am Ufer
  bank: ok(0.198, 0.028, 262), //  die Uferkante selbst
  stall: ok(0.194, 0.026, 260), //  Yatai-Körper
  stallLit: ok(0.4, 0.055, 62), //  die Plane, von innen angeleuchtet
  crowdFar: ok(0.192, 0.024, 260),
  near: ok(0.187, 0.022, 258), //  Vordergrund: die Köpfe am unteren Rand

  water: ok(0.295, 0.05, 266), //  der Fluss fängt den hellen Horizont
  waterLow: ok(0.228, 0.045, 266),
  waterRim: ok(0.42, 0.045, 260), //  die Uferlinie, wo das Licht bricht

  gold: ok(0.855, 0.17, 92), //  BUCHSTAEBLICH --accent
  goldDeep: ok(0.75, 0.15, 78),
  red: ok(0.655, 0.185, 38), //  Chochin-Papier, das Deko-Zinnober des Themas
  redDeep: ok(0.55, 0.16, 34),

  star: ok(0.86, 0.02, 250), //  die paar Sterne, die neben dem Feuerwerk bleiben
  starDim: ok(0.7, 0.02, 252),
};

/* ── Die vier Feuerwerksfarben ───────────────────────────────────────────────
   Ein Hanabi-Taikai ist bunt — das ist keine Freiheit, die man sich nimmt,
   sondern das Motiv selbst. Die Auswahl hält trotzdem die Rot-Regel der
   Themen-CSS ein (Deko-Rot darf nie als Fehler-Rot lesbar sein) und erweitert
   sie um dieselbe Frage für die kalten Töne:
     gold     H 92  — der Akzent selbst, das Laternengold
     vermilion H 34 — das dokumentierte Deko-Zinnober (warm, orangeseitig),
                      30° entfernt vom kalten --error (H 8, pinkseitig)
     iris     H 278 — Blauviolett. Kommt in KEINER Statusfarbe vor und ist die
                      klassische Kupfer-/Strontium-Farbe echter Hanabi.
     cyan     H 198 — Türkis, 46° entfernt von --success (H 152, klar grün).
   Kein Grün, kein Pink: die beiden Hues, an denen sich Deko und Semantik im
   Fest-Bild wirklich beißen könnten, bleiben unbesetzt.
   Jede Farbe kommt in drei Stufen: Kern (hell), Blüte (satt), Saum (dunkel). */
/* Die Chroma-Werte der hellen Stufe sind NICHT gefühlt, sondern am Gamut-Riegel
   ausgemessen: bei L 0.94 trägt der sRGB-Raum auf H 92 nur noch C 0.082, auf
   H 44 sogar nur 0.054. Das ist keine Einschränkung, sondern Physik — ein
   glühender Funke IST fast weiß, seine Farbe steckt in der Blüte um ihn herum. */
/* DIE HELLIGKEITEN SIND NACH DER ERSTEN SICHTUNG ANGEHOBEN WORDEN, und das ist
   der wichtigste Eingriff dieser Datei. Im ersten Lauf standen Iris (L 0.74)
   und Cyan (L 0.82) auf einem Nachthimmel von L 0.21–0.30 — und waren
   praktisch UNSICHTBAR: von vier Farben trugen zwei das Bild, die kalten
   beiden waren dunkle Schlieren. Auf dunklem Grund entscheidet nicht das
   Chroma, ob eine Farbe da ist, sondern die HELLIGKEIT. Alle vier liegen jetzt
   dicht beieinander (mid L 0.75–0.86, Saum L 0.66–0.75), so wie es auch physisch
   stimmt: ein brennender Feuerwerksstern ist immer hell, seine Farbe ist ein
   Tonfall, keine Abdunklung. */
const FW = {
  gold: { hi: ok(0.94, 0.072, 92), mid: ok(0.855, 0.17, 92), lo: ok(0.72, 0.145, 76), id: 'g' },
  vermilion: { hi: ok(0.9, 0.048, 44), mid: ok(0.75, 0.145, 36), lo: ok(0.66, 0.17, 34), id: 'v' },
  iris: { hi: ok(0.9, 0.046, 290), mid: ok(0.8, 0.096, 280), lo: ok(0.72, 0.12, 276), id: 'i' },
  cyan: { hi: ok(0.93, 0.06, 198), mid: ok(0.86, 0.095, 198), lo: ok(0.75, 0.1, 200), id: 'c' },
};
/* Der Magnesium-Kern, warm gebrochen. L 0.93 UND NICHT 0.97 — das ist keine
   Farbwahl, das ist der Kontrast-Vertrag, und er wurde gemessen, nicht geraten.
   Der Kern ist der hellste Bildpunkt des ganzen Themas und damit derjenige, an
   dem die Lesbarkeitsrechnung hängt: bei L 0.97 (248,245,236) verlangte er
   einen Schleier von 0.86 über der Lesespalte, bei L 0.93 genügen 0.80 — ein
   Fünftel mehr Bild, das hinter der Spalte stehen bleibt. Unter L 0.93 gewinnt
   man nichts mehr: dann bindet `cyan.hi`, und der Kern wäre nur noch matter.
   Ein Feuerwerksfunke ist ohnehin nicht weiß, sondern gleißend warm. */
const WHITE = ok(0.93, 0.012, 90);

/* ── Geometrie der Bühne ─────────────────────────────────────────────────────
   viewBox 1600×1000, in der CSS mit `cover` + `center bottom` aufgehängt. Der
   Boden ist damit IMMER zu sehen, oben wird bei breiten Fenstern beschnitten:
   die Sichere Zone ist x 140–1460 und y ≥ 110, geprüft für 4:3 bis 16:9.
   Waagerecht wie senkrecht gilt: es gibt kein Band ohne Inhalt. */
const W = 1600;
const H = 1000;
const SAFE = { x0: 140, x1: 1460, y0: 110 };
const HORIZON = 620; //  wo Himmel auf Land trifft
const SHORE = 748; //  Uferlinie: davor der Fluss

const out = [];
const put = (s) => out.push(s);
/* Ganze Zahlen. Ein Zehntel Bildpunkt auf einer 1600er Bühne ist nichts als
   Dateigröße — und Dateigröße ist hier ein Budget (≤ 80 KB laut ORDER). */
const n = (v) => Math.round(v);
const f = (v) => Math.round(v * 10) / 10;

/** Sammelt jedes Licht des Festes, damit der Fluss es zurückwerfen kann. */
const lights = [];

/* ═══════════════════════════════════════════════════════════════════════════
   1) DER HIMMEL — die blaue Stunde, unten warm
   ═══════════════════════════════════════════════════════════════════════════ */

put(`<rect x="-40" y="-40" width="${W + 80}" height="${H + 80}" fill="url(#himmel)"/>`);
// Das Streulicht des Festes steht in der Luft über dem Ufer — es ist der Grund,
// warum die Silhouetten der Stände überhaupt Silhouetten sind.
put(
  `<ellipse cx="800" cy="${HORIZON + 20}" rx="1020" ry="150" fill="${C.airGlow}" opacity="0.22" filter="url(#weich)"/>`,
);
put(
  `<ellipse cx="470" cy="${HORIZON - 10}" rx="330" ry="86" fill="${C.airGlow}" opacity="0.14" filter="url(#weich)"/>`,
);
put(
  `<ellipse cx="1180" cy="${HORIZON - 4}" rx="360" ry="92" fill="${C.airGlow}" opacity="0.13" filter="url(#weich)"/>`,
);

/* Ein paar Sterne — mehr nicht. Heute Nacht gehört der Himmel dem Feuerwerk,
   und ein voller Sternenhimmel wäre die Handschrift des Nachbarn (Nagareboshi
   ist die stille Sternnacht; dies ist die eine Nacht im Jahr, in der der
   Himmel blüht). Sie stehen hoch und dünn, damit man sie zwischen den Blüten
   überhaupt bemerkt. */
{
  // Zwei Gruppen statt einer Deckkraft je Stern: das spart die Hälfte der
  // Attribute und ist genauso wahr — Sterne haben zwei Klassen, nicht 62.
  const hell = [];
  const matt = [];
  for (let i = 0; i < 62; i++) {
    const x = n(between(-20, W + 20));
    const y = n(between(-20, 470));
    const big = R() < 0.24;
    (big ? hell : matt).push(
      `<circle cx="${x}" cy="${y}" r="${f(big ? between(1.2, 1.9) : between(0.6, 1.1))}"/>`,
    );
  }
  put(`<g fill="${C.star}" opacity="0.66">${hell.join('')}</g>`);
  put(`<g fill="${C.starDim}" opacity="0.44">${matt.join('')}</g>`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   2) DIE HANABI — der Hauptdarsteller
   ═══════════════════════════════════════════════════════════════════════════
   Eine Kiku-Blüte (菊) ist kein Stern-Sprite. Sie besteht aus Dutzenden
   Strahlen, die vom Kern nach außen laufen, unterwegs von der Schwerkraft nach
   unten GEBOGEN werden und in einem hellen Funken enden. Genau diese Biegung
   ist der Unterschied zwischen „Feuerwerk" und „Sonne aus dem Malprogramm":
   der Strahl startet gerade und hängt am Ende durch.

   Gebaut aus vier Teilen, in dieser Reihenfolge:
     · ein Hof (geteilter radialGradient je Farbe — nie ein flacher Kreis, der
       hätte eine Kante und läse sich als Münze),
     · EIN path für ALLE Strahlen (quadratische Kurven; ein Element statt
       sechzig spart auf sieben Blüten mehrere Kilobyte),
     · die Funken an den Spitzen, plus ein zweiter, kleinerer Kranz auf halbem
       Weg (die Doppelblüte),
     · das Herz (芯) in einer ZWEITEN Farbe — der Zug, an dem man ein echtes
       Hanabi erkennt.
   Jede Blüte liefert zusätzlich ihre Wasser-Spiegelung zurück; die trägt
   DIESELBE Klasse und läuft damit auf derselben Uhr wie die Blüte selbst.
   Ein Feuerwerk und sein Spiegelbild gehen gemeinsam auf. */
/* Der Pulverrauch: was von den vorigen Blüten übrig ist. Sehr groß, sehr
   schwach, sehr warm — er verbindet die Blüten miteinander, statt sie als
   Einzelstücke im Leeren stehen zu lassen.

   ER STEHT HIER, VOR den Blüten, und das ist eine Korrektur aus der eigenen
   Sichtung: im ersten Lauf wurde er DANACH gemalt und lag damit ÜBER ihnen.
   Zwei Fehler auf einmal — er dämpfte ausgerechnet die ohnehin blasse
   Iris-Blüte, und weil eine unscharfe graue Ellipse über einer Zeichnung nicht
   wie Rauch aussieht, sondern wie Schmutz, stand oben rechts ein Fleck im Bild.
   Rauch liegt HINTER dem, was gerade brennt. */
/* UND ER STEHT NEBEN DEN BLÜTEN, NICHT AUF IHNEN — die zweite Korrektur aus
   der Sichtung. Zuerst lag je eine Rauchwolke am Mittelpunkt jeder Blüte (das
   klang logisch: dort hat es ja gebrannt). Im Bild ergab das in jedem Blütenkern
   eine graue Scheibe — ein Loch genau dort, wo der weiße Funke sitzen soll.
   Rauch zieht ab: er sammelt sich TIEF über dem Fest, und oben bleiben nur zwei
   Schlieren. Das ist wahr, es füllt den leeren Himmel an den Rändern, und es
   fasst kein einziges Hauptmotiv mehr an. */
put(`<g class="sm" opacity="0.5" filter="url(#weich)" fill="${C.smoke}">`);
[
  [300, 545, 260, 62, 0.16],
  [820, 570, 300, 58, 0.15],
  [1330, 550, 260, 60, 0.14],
  [190, 130, 200, 96, 0.12],
  [1520, 160, 180, 88, 0.11],
].forEach(([x, y, rx, ry, op]) => {
  put(`<ellipse cx="${x}" cy="${y}" rx="${rx}" ry="${ry}" opacity="${op}"/>`);
});
put(`</g>`);

function hanabi(cx, cy, rad, color, pistil, rays, cls) {
  const g = [];
  const tips = [];
  const inner = [];
  const trails = [];
  const r0 = rad * 0.12;

  for (let i = 0; i < rays; i++) {
    const th = (i / rays) * Math.PI * 2 + between(-0.022, 0.022);
    const len = rad * between(0.82, 1.0);
    const ca = Math.cos(th);
    const sa = Math.sin(th);
    // Die Schwerkraft: die Spitze sackt ab, der Kontrollpunkt nur zu einem
    // Viertel — daraus entsteht die Kurve statt einer schrägen Geraden.
    const drop = rad * 0.19 * (0.45 + 0.55 * ((sa + 1) / 2));
    const tx = cx + ca * len;
    const ty = cy + sa * len + drop;
    const mx = cx + ca * len * 0.58;
    const my = cy + sa * len * 0.58 + drop * 0.22;
    trails.push(`M${n(cx + ca * r0)},${n(cy + sa * r0)}Q${n(mx)},${n(my)} ${n(tx)},${n(ty)}`);
    tips.push(`<circle cx="${n(tx)}" cy="${n(ty)}" r="${f(between(1.6, 3.0))}"/>`);
    if (i % 2 === 0) {
      inner.push(
        `<circle cx="${n(mx)}" cy="${n(my)}" r="${f(between(1.0, 1.8))}"/>`,
      );
    }
  }

  g.push(
    `<circle cx="${n(cx)}" cy="${n(cy)}" r="${n(rad * 0.72)}" fill="url(#hof${color.id})"/>`,
    `<path d="${trails.join('')}" fill="none" stroke="${color.lo}" stroke-width="${f(rad > 180 ? 1.7 : 1.3)}" opacity="0.5"/>`,
    `<g fill="${color.mid}">${tips.join('')}</g>`,
    `<g fill="${color.hi}" opacity="0.62">${inner.join('')}</g>`,
  );

  // Das Herz: ein kompakter Kranz in der zweiten Farbe, plus der weiße Kern.
  const heart = [];
  const hr = rad * 0.26;
  for (let i = 0; i < Math.round(rays * 0.42); i++) {
    const th = (i / Math.round(rays * 0.42)) * Math.PI * 2 + 0.3;
    heart.push(
      `<circle cx="${n(cx + Math.cos(th) * hr * between(0.78, 1.05))}" ` +
        `cy="${n(cy + Math.sin(th) * hr * between(0.78, 1.05) + hr * 0.16)}" r="${f(between(1.4, 2.4))}"/>`,
    );
  }
  g.push(`<g fill="${pistil.mid}">${heart.join('')}</g>`);
  g.push(`<circle cx="${n(cx)}" cy="${n(cy)}" r="${f(rad > 180 ? 4.6 : 3.2)}" fill="${WHITE}"/>`);

  put(`<g class="${cls}">${g.join('')}</g>`);
  return { cx, rad, color, cls };
}

/* Die Standorte. Sie sind GESETZT, nicht gewürfelt: sieben Blüten müssen die
   ganze Bühne tragen, ohne sich zu stapeln und ohne ein Loch zu lassen. Die
   beiden großen stehen links und rechts der Mitte (die Lesespalte läuft
   dazwischen hindurch, und der Schleier trägt sie); die kleinen sitzen tief und
   fern, damit der Himmel Tiefe bekommt statt sieben gleich weit entfernter
   Kreise. Alle Mittelpunkte liegen in der Sicheren Zone. */
const BLOOMS = [
  { x: 420, y: 268, r: 268, c: 'gold', p: 'vermilion', rays: 64, cls: 'b0' },
  { x: 1156, y: 232, r: 244, c: 'iris', p: 'gold', rays: 60, cls: 'b1' },
  { x: 790, y: 158, r: 176, c: 'vermilion', p: 'gold', rays: 48, cls: 'b2' },
  { x: 232, y: 452, r: 138, c: 'cyan', p: 'gold', rays: 44, cls: 'b3' },
  { x: 1408, y: 424, r: 152, c: 'gold', p: 'iris', rays: 46, cls: 'b4' },
  { x: 946, y: 456, r: 104, c: 'vermilion', p: 'cyan', rays: 36, cls: 'b5' },
  { x: 612, y: 484, r: 88, c: 'iris', p: 'gold', rays: 32, cls: 'b6' },
];
const blooms = BLOOMS.map((b) => hanabi(b.x, b.y, b.r, FW[b.c], FW[b.p], b.rays, b.cls));

/* Ferne Blüten am Horizont: winzig, blass, ohne eigene Uhr. Sie sagen dem Auge,
   dass das Fest größer ist als dieser Ausschnitt — anderswo am Fluss steigen
   auch welche. Drei Punkte Aufwand, ein ganzer Bildraum Gewinn. */
[
  [104, 520, 34, 'gold'],
  [1524, 546, 30, 'vermilion'],
  [1310, 560, 24, 'cyan'],
].forEach(([x, y, r, c]) => {
  const col = FW[c];
  const s = [];
  for (let i = 0; i < 14; i++) {
    const th = (i / 14) * Math.PI * 2;
    s.push(
      `<circle cx="${n(x + Math.cos(th) * r)}" cy="${n(y + Math.sin(th) * r + r * 0.2)}" r="1.4"/>`,
    );
  }
  put(
    `<g opacity="0.42"><circle cx="${x}" cy="${y}" r="${n(r * 1.5)}" fill="url(#hof${col.id})"/>` +
      `<g fill="${col.mid}">${s.join('')}</g></g>`,
  );
});

/* Aufsteigende Schüsse: zwei dünne Goldspuren mit einem Funken an der Spitze.
   Sie sind das Versprechen der nächsten Blüte. Im eingefrorenen Bild stehen sie
   ruhig da (sie dürfen, sie sind hell und schmal); in Bewegung verglimmen sie. */
/* KURZ UND LEISE. Der erste Wurf ließ sie 230 px hoch steigen und mitten durch
   die Bühne laufen — bei voller Deckung (dem eingefrorenen Zustand!) standen
   dann zwei dünne senkrechte Goldlinien quer über den Blüten und lasen sich als
   Kratzer im Bild. Jetzt sind sie halb so hoch, deutlich blasser und stehen in
   Himmelsstücken, in denen keine Blüte liegt: ein Funke, der aufsteigt, kein
   Strich, der das Bild teilt. */
[
  [742, 600, 752, 452, 'r0'],
  [1258, 606, 1268, 510, 'r1'],
].forEach(([x0, y0, x1, y1, cls]) => {
  put(
    `<g class="${cls}">` +
      `<path d="M${x0},${y0}Q${n((x0 + x1) / 2 - 6)},${n((y0 + y1) / 2)} ${x1},${y1}" ` +
      `fill="none" stroke="${C.goldDeep}" stroke-width="1.4" opacity="0.32"/>` +
      `<circle cx="${x1}" cy="${y1}" r="2.6" fill="${C.gold}"/>` +
      `<circle cx="${x1}" cy="${y1}" r="9" fill="url(#hofg)" opacity="0.6"/>` +
      `</g>`,
  );
});

/* ═══════════════════════════════════════════════════════════════════════════
   3) DAS LAND — Hügel, Baumgruppen, Ufer
   ═══════════════════════════════════════════════════════════════════════════
   Mittelpunktverschiebung: eine Strecke wird rekursiv geteilt und der neue
   Punkt ausgelenkt, die Auslenkung mit jeder Stufe gedämpft. Kürzeste
   Beschreibung eines Höhenzugs, die es gibt. */
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

put(ridgePath(ridgeLine(HORIZON - 78, 54, 5, -34), C.hillFar));
put(ridgePath(ridgeLine(HORIZON - 22, 26, 5, 18), C.hillNear));

/* Baumgruppen auf dem Ufer: kein Nadelwald (das ist Nagareboshis Handschrift),
   sondern runde sommerliche Kronen — Zelkoven und Kiefern am Flussufer. Eine
   Krone ist ein Bogen aus fünf bis acht Kuppen, kein Kreis. */
function crown(x, baseY, w, h, fill) {
  const bumps = Math.max(5, Math.round(w / 16));
  const d = [`M${n(x - w / 2)},${n(baseY)}`];
  for (let i = 0; i <= bumps; i++) {
    const t = i / bumps;
    const px = x - w / 2 + w * t;
    const arc = Math.sin(Math.PI * t) ** 0.62;
    const py = baseY - h * arc * between(0.82, 1.06);
    d.push(`Q${n(px - w / bumps / 2)},${n(py - h * 0.12)} ${n(px)},${n(py)}`);
  }
  d.push(`L${n(x + w / 2)},${n(baseY)}Z`);
  return `<path d="${d.join('')}" fill="${fill}"/>`;
}
for (let x = -30; x < W + 30; x += between(58, 128)) {
  put(crown(x, HORIZON + between(6, 22), between(56, 132), between(28, 62), C.trees));
}

/* ═══════════════════════════════════════════════════════════════════════════
   4) DAS FEST — vierzehn Yatai, drei Laternenketten, ein Torii
   ═══════════════════════════════════════════════════════════════════════════
   DEZENT heißt hier eine Zahl: das ganze Fest liegt zwischen y 580 und y 745,
   also auf 16 % der Bildhöhe. Es ist ein warmes Band, kein zweiter
   Hauptdarsteller — aber innerhalb dieses Bandes ist es sauber gebaut, sonst
   wäre es Matsch statt Kulisse.

   Ein Yatai ist im Umriss: flaches Dach mit Überstand, zwei dünne Pfosten, und
   dazwischen die THEKE — der einzige wirklich helle Teil, denn das Licht steht
   im Stand und fällt nach vorn heraus. Darüber hängt oft ein Noren-Streifen
   und daneben ein schmales Nobori-Banner. */
function yatai(x, baseY, w, h, v = 0) {
  const s = [];
  // Drei Bauarten im Wechsel (v). Nach der ersten Sichtung: sechzehn identisch
  // gebaute Stände lesen sich als EIN Stempel, sechzehnmal gesetzt — das Auge
  // erkennt die Wiederholung schneller als die Form. Der Unterschied muss
  // nicht groß sein, nur vorhanden: mal ein roter, mal ein indigoblauer
  // Noren, jeder dritte mit einem schmalen Nobori-Banner daneben.
  const noren = v % 3 === 1 ? C.stall : C.redDeep;
  const roofY = baseY - h;
  const eave = w * 0.12;
  // Die Theke: das Licht. Zuerst gemalt, damit Dach und Pfosten davor liegen.
  s.push(
    `<rect x="${n(x - w * 0.4)}" y="${n(roofY + h * 0.3)}" width="${n(w * 0.8)}" height="${n(h * 0.46)}" fill="${C.stallLit}"/>`,
    `<rect x="${n(x - w * 0.34)}" y="${n(roofY + h * 0.36)}" width="${n(w * 0.68)}" height="${n(h * 0.26)}" fill="${C.goldDeep}" opacity="0.75"/>`,
  );
  lights.push({ x, y: roofY + h * 0.5, r: w * 0.3, op: 0.3, warm: true });
  // Dach mit Überstand, leicht geneigt — ein Yatai ist gebaut, nicht gegossen.
  s.push(
    `<path d="M${n(x - w / 2 - eave)},${n(roofY + 5)}L${n(x - w * 0.42)},${n(roofY - h * 0.16)}` +
      `H${n(x + w * 0.42)}L${n(x + w / 2 + eave)},${n(roofY + 5)}Z" fill="${C.stall}"/>`,
    `<rect x="${n(x - w / 2 - eave)}" y="${n(roofY + 5)}" width="${n(w + eave * 2)}" height="4" fill="${C.stall}"/>`,
    `<path d="M${n(x - w * 0.42)},${n(baseY)}v${n(-h * 0.72)}h3v${n(h * 0.72)}Z" fill="${C.stall}"/>`,
    `<path d="M${n(x + w * 0.42)},${n(baseY)}v${n(-h * 0.72)}h3v${n(h * 0.72)}Z" fill="${C.stall}"/>`,
  );
  // Der Noren-Streifen unter der Traufe.
  s.push(
    `<rect x="${n(x - w * 0.4)}" y="${n(roofY + 9)}" width="${n(w * 0.8)}" height="${n(h * 0.17)}" fill="${noren}" opacity="0.8"/>`,
  );
  // Das Nobori: ein schmales, hohes Banner an einer Stange neben dem Stand.
  // Es ist der einzige senkrechte Strich in dieser Reihe und bricht damit die
  // waagerechte Monotonie der Dächer.
  if (v % 3 === 2) {
    const bx = x + w * 0.62;
    s.push(
      `<path d="M${n(bx)},${n(baseY)}V${n(roofY - h * 0.5)}h2V${n(baseY)}Z" fill="${C.stall}"/>`,
      `<rect x="${n(bx + 2)}" y="${n(roofY - h * 0.46)}" width="${n(w * 0.12)}" height="${n(h * 0.62)}" fill="${C.redDeep}" opacity="0.7"/>`,
    );
  }
  return s.join('');
}

/* Erst die hintere, kleinere Reihe (weiter weg = kleiner und höher), dann die
   vordere. Zwei Reihen sind der Unterschied zwischen einer Budenflucht und
   einer Gasse. Die MITTE ist ausdrücklich besetzt: dort läuft die Lesespalte,
   und genau dort soll das Bild weitergehen. */
[
  [186, 686, 62, 42], [352, 682, 56, 38], [520, 688, 60, 40], [700, 684, 54, 38],
  [880, 688, 58, 40], [1058, 682, 56, 38], [1236, 686, 60, 40], [1414, 684, 54, 38],
].forEach(([x, y, w, h], i) => put(yatai(x, y, w, h, i)));

/* Ein Torii am Ufer — der eine Umriss, an dem man das Fest als japanisches
   erkennt, ohne dass ein Schriftzeichen nötig wäre. Es steht rechts der Mitte,
   also NICHT hinter der Lesespalte, damit sein Umriss ganz zu sehen ist. */
{
  const x = 1148;
  const b = 712;
  const h = 96;
  const w = 82;
  put(
    `<g fill="${C.bank}">` +
      `<path d="M${n(x - w / 2 - 2)},${b}l3,${-h}h6l3,${h}Z"/>` +
      `<path d="M${n(x + w / 2 + 2)},${b}l-3,${-h}h-6l-3,${h}Z"/>` +
      `<rect x="${n(x - w / 2 - 5)}" y="${n(b - h * 0.68)}" width="${w + 10}" height="6"/>` +
      `<path d="M${n(x - w / 2 - 20)},${n(b - h + 4)}Q${x},${n(b - h - 12)} ${n(x + w / 2 + 20)},${n(b - h + 4)}` +
      `l0,7Q${x},${n(b - h - 3)} ${n(x - w / 2 - 20)},${n(b - h + 11)}Z"/>` +
      `</g>`,
  );
}

// Die vordere Reihe: größer, tiefer, dichter.
[
  [96, 726, 84, 56], [268, 730, 76, 52], [446, 728, 88, 58], [634, 732, 72, 50],
  [820, 730, 84, 56], [996, 728, 76, 52], [1330, 730, 86, 56], [1502, 726, 74, 50],
].forEach(([x, y, w, h], i) => put(yatai(x, y, w, h, i)));

/* ── DIE LATERNENKETTEN (提灯の列) ────────────────────────────────────────────
   Drei Katenaren über der Gasse. Eine Kette hängt durch, WEIL sie hängt; eine
   Gerade mit Punkten darauf ist eine Lichterleiste aus dem Baumarkt. Rot und
   Gold wechseln sich ab, Rot führt — so hängen sie an echten Festgassen.

   ES STELLT NICHTS DAR (Hoshi-Regel: nichts Dekoratives, das Daten vortäuscht).
   Die Zahl ändert sich nie, die Positionen ändern sich nie. */
function catenary(x0, y0, x1, y1, sag, steps = 30) {
  return Array.from({ length: steps + 1 }, (_, i) => {
    const t = i / steps;
    return [x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + Math.sin(Math.PI * t) * sag];
  });
}
function lanternChain(pts, count, idx) {
  const s = [
    `<path d="M${asLine(pts)}" fill="none" stroke="${C.bank}" stroke-width="1.6" opacity="0.9"/>`,
  ];
  for (let i = 1; i < count; i++) {
    const [px, py] = pts[Math.round((i / count) * (pts.length - 1))];
    const red = (i + idx) % 2 === 0;
    const body = red ? C.red : C.gold;
    const rr = 4.6;
    s.push(
      // Der heiße Kern (rr*0.36 ≈ 1,7 px) ist auf der ausgelieferten Skalierung
      // rund drei Bildpunkte groß und damit nicht zu sehen — er hat 2,7 KB
      // gekostet und wurde aus dem Budget gestrichen. Der Farbunterschied
      // zwischen rotem und goldenem Papier trägt die Kette ohnehin allein.
      `<g class="l${(i + idx) % 3}">` +
        `<ellipse cx="${n(px)}" cy="${n(py + 11)}" rx="${n(rr * 1.9)}" ry="${n(rr * 1.8)}" fill="${body}" opacity="0.24"/>` +
        `<path d="M${n(px)},${n(py)}v6" stroke="${C.bank}" stroke-width="1"/>` +
        `<ellipse cx="${n(px)}" cy="${n(py + 11)}" rx="${f(rr)}" ry="${f(rr * 1.24)}" fill="${body}"/>` +
        `</g>`,
    );
    lights.push({ x: px, y: py + 11, r: rr, op: 0.4, red });
  }
  return s.join('');
}
for (const [mx, my] of [
  [110, 636],
  [640, 604],
  [1130, 610],
  [1560, 640],
]) {
  put(`<path d="M${mx},${my}V${my + 104}" stroke="${C.bank}" stroke-width="2.6"/>`);
}
put(lanternChain(catenary(110, 636, 640, 604, 46), 13, 0));
put(lanternChain(catenary(640, 604, 1130, 610, 42), 12, 1));
put(lanternChain(catenary(1130, 610, 1560, 640, 40), 11, 0));

/* ── DIE MENSCHEN AM UFER — die ruhige Köpfe-Linie ───────────────────────────
   Neunzig Silhouetten vor den Ständen, alle klein, alle dunkel, alle gleich
   ruhig. Ein Kopf ist ein Kreis, Schultern sind ein flacher Bogen; mehr braucht
   es bei dieser Größe nicht, und mehr wäre auch falsch — dies ist eine MENGE,
   keine Versammlung von Personen. Die Höhen streuen leicht, sonst stünde dort
   eine Perlenkette. */
{
  /* EIN durchgehender Pfad für alle Schultern statt einer Figur je Person.
     Das ist nicht nur billiger (rund 5 KB, die das Feuerwerk behalten darf),
     es ist auch richtiger: eine Menge ist bei dieser Größe genau das — eine
     wellige dunkle Kante, aus der Köpfe ragen. Einzeln freigestellte Figürchen
     hätten Lücken dazwischen, und Lücken machen aus einer Menge eine Reihe. */
  const ridge = [`M-30,${SHORE + 8}`];
  const heads = [];
  for (let x = -30; x < W + 30; x += between(10, 19)) {
    const r = between(4.2, 6.6);
    const hh = between(16, 27);
    const top = SHORE - between(1, 8) - hh;
    ridge.push(`Q${n(x - r)},${n(top)} ${n(x + r * 1.6)},${n(top + between(2, 7))}`);
    heads.push(`<circle cx="${n(x)}" cy="${n(top - r * 0.75)}" r="${f(r)}"/>`);
  }
  ridge.push(`L${W + 30},${SHORE + 8}Z`);
  put(`<g fill="${C.crowdFar}"><path d="${ridge.join('')}"/>${heads.join('')}</g>`);
}

// Die Uferkante: der helle Riss, an dem Land aufhört und Wasser anfängt.
put(`<path d="M-40,${SHORE}H${W + 40}v6H-40Z" fill="${C.bank}"/>`);
put(
  `<path d="M-40,${SHORE + 6}H${W + 40}" stroke="${C.waterRim}" stroke-width="2.4" opacity="0.75"/>`,
);

/* ═══════════════════════════════════════════════════════════════════════════
   5) DER FLUSS — das untere Viertel, und der zweite Auftritt jeder Farbe
   ═══════════════════════════════════════════════════════════════════════════
   Ein Fluss-Hanabi ist deshalb DAS klassische Bild, weil alles zweimal
   vorkommt. Eine Spiegelung auf bewegtem Wasser ist aber kein Abbild, sondern
   ein STAPEL kurzer waagerechter Striche, der nach unten breiter und schwächer
   wird — genau so ist er hier gebaut. Die Spiegelung einer Blüte trägt deren
   Klasse: sie erscheint und vergeht mit ihr. */
put(`<path d="M-40,${SHORE + 6}H${W + 40}V${H + 10}H-40Z" fill="url(#fluss)"/>`);

// Die Spiegelungen der Blüten — die größte, farbigste Fläche des unteren Teils.
blooms.forEach((b, bi) => {
  const s = [];
  // Zehn Stufen, nicht dreizehn: ab der elften liegt der Strichstapel hinter
  // der Köpfe-Linie des Vordergrunds und war nie zu sehen — reines Gewicht.
  //
  // DER VERSATZ (`phase`) IST DER GANZE UNTERSCHIED. Im ersten Lauf begannen
  // alle sieben Stapel auf DERSELBEN Höhe und mit demselben Abstand — und weil
  // Ellipsen, die sich in einer Zeile treffen, zu einer durchgehenden Linie
  // verschmelzen, lag über dem Fluss eine Jalousie statt einer Spiegelung.
  // Eine eigene Anfangshöhe und ein eigener Zeilenabstand je Blüte kosten
  // nichts und lösen es vollständig.
  const phase = (bi % 5) * 4;
  const step = 15 + (bi % 3) * 3;
  for (let k = 0; k < 10; k++) {
    const y = SHORE + 14 + phase + k * step;
    if (y > H - 6) break;
    const spread = b.rad * (0.3 + k * 0.04);
    s.push(
      `<ellipse cx="${n(b.cx + between(-14, 14))}" cy="${n(y)}" rx="${n(spread)}" ry="${f(between(1.4, 2.6))}" ` +
        `opacity="${f(0.38 * (1 - k / 15))}"/>`,
    );
  }
  put(`<g class="${b.cls}" fill="${b.color.mid}">${s.join('')}</g>`);
});

// …und die der Laternen und Theken: schmal, warm, dicht am Ufer.
lights.forEach((L, i) => {
  // Jedes DRITTE Licht. Zwei Gründe, und der zweite ist der bessere: ein Fluss,
  // der jede Laterne zurückwirft, wird Suppe statt Wasser — und die gesparten
  // gut 5 KB stehen jetzt in den Blüten. Budget zahlt man aus dem Schwächsten.
  if (i % 3 !== 0) return;
  const s = [];
  // Auch hier: eigener Anfang und eigener Abstand je Licht. Diese Stapel waren
  // die Hauptschuldigen an der Jalousie — ein Yatai-Licht wirft einen bis zu
  // 200 px breiten Strich, und zwanzig davon auf derselben Höhe sind eine
  // durchgezogene Linie über den ganzen Fluss.
  const ph = (i % 7) * 3;
  for (let k = 0; k < 5; k++) {
    const y = SHORE + 10 + ph + k * (16 + (i % 4) * 2);
    if (y > H - 8) break;
    s.push(
      `<ellipse cx="${n(L.x + between(-7, 7))}" cy="${n(y)}" rx="${n(L.r * (1.1 + k * 0.5))}" ry="1.7" ` +
        `opacity="${f(L.op * (1 - k / 7))}"/>`,
    );
  }
  put(`<g class="w${i % 3}" fill="${L.red ? C.red : C.gold}">${s.join('')}</g>`);
});

/* Zwei Yakatabune — Ausflugsboote mit eigener Laterne. Sie geben dem Wasser
   einen Maßstab; ohne sie ist eine große helle Fläche unten nur eine Fläche. */
[
  [386, 826, 118, 1],
  [1204, 878, 146, 0],
].forEach(([x, y, w, idx]) => {
  const h = w * 0.17;
  put(
    `<g><path d="M${n(x - w / 2)},${n(y)}q${n(w * 0.06)},${n(h)} ${n(w * 0.16)},${n(h)}` +
      `h${n(w * 0.68)}q${n(w * 0.1)},0 ${n(w * 0.16)},${n(-h)}Z" fill="${C.near}"/>` +
      `<rect x="${n(x - w * 0.3)}" y="${n(y - h * 1.7)}" width="${n(w * 0.6)}" height="${n(h * 1.7)}" fill="${C.near}"/>` +
      `<rect x="${n(x - w * 0.26)}" y="${n(y - h * 1.4)}" width="${n(w * 0.52)}" height="${n(h * 0.8)}" fill="${C.goldDeep}" opacity="0.7"/>` +
      `<g class="l${idx}"><ellipse cx="${n(x + w * 0.38)}" cy="${n(y - h * 1.9)}" rx="4" ry="5" fill="${C.red}"/>` +
      `<ellipse cx="${n(x + w * 0.38)}" cy="${n(y - h * 1.9)}" rx="10" ry="11" fill="${C.red}" opacity="0.22"/></g>` +
      `</g>`,
  );
});

/* Ruhige waagerechte Züge — Wasser hat Richtung, und ohne Struktur liest sich
   der ganze Fluss als Tapete (Gotcha F des Rezepts). */
put(`<g stroke="${C.waterRim}" stroke-width="1.1" opacity="0.3">`);
for (let i = 0; i < 26; i++) {
  put(
    `<path d="M${n(between(-20, W - 220))},${n(SHORE + 22 + i * 9.4 + between(-2, 2))}h${n(between(180, 700))}"/>`,
  );
}
put(`</g>`);

/* ═══════════════════════════════════════════════════════════════════════════
   6) DER VORDERGRUND — die Menschen, von hinten, am unteren Bildrand
   ═══════════════════════════════════════════════════════════════════════════
   Ohne Vordergrund gibt es keine Tiefe, nur eine Tapete. Diese Reihe steht NAH:
   groß, fast so dunkel wie der Grundton, und sie schneidet die Bühne unten an.
   Sie ist außerdem der Grund, warum das Bild funktioniert — man sieht das
   Feuerwerk nicht allein, sondern über den Köpfen der anderen, und ein paar
   gehobene Uchiwa-Fächer sagen in drei Kreisen „Sommer", was kein Farbton
   sagen könnte. */
{
  const s = [];
  let x = -30;
  while (x < W + 30) {
    const r = between(15, 24);
    const b = H + 16;
    const hh = between(44, 76);
    const cy = b - hh - r * 0.75;
    // Schultern als breiter Bogen, Kopf als Kreis, gelegentlich ein Knoten.
    s.push(
      `<path d="M${n(x - r * 2.5)},${n(b)}q${f(r * 0.7)},${n(-hh)} ${f(r * 2.5)},${n(-hh)}` +
        `q${f(r * 1.8)},0 ${f(r * 2.5)},${n(hh)}Z"/>`,
      `<circle cx="${n(x)}" cy="${n(cy)}" r="${f(r)}"/>`,
    );
    if (R() < 0.3) {
      s.push(`<circle cx="${n(x + between(-4, 4))}" cy="${n(cy - r * 0.92)}" r="${f(r * 0.36)}"/>`);
    }
    if (R() < 0.24) {
      const fx = x + between(-r * 2.2, r * 2.2);
      const fy = cy - between(38, 76);
      s.push(
        `<circle cx="${n(fx)}" cy="${n(fy)}" r="${f(between(13, 19))}"/>`,
        `<path d="M${n(fx - 2)},${n(fy)}l4,0l1,${n(between(24, 38))}l-6,0Z"/>`,
      );
    }
    x += between(46, 82);
  }
  put(`<g fill="${C.near}">${s.join('')}</g>`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   7) DER HERZSCHLAG
   ═══════════════════════════════════════════════════════════════════════════
   Alle Keyframes laufen von 1 NACH UNTEN und zurück: der gemalte Zustand ist
   der hellste, die Animation nimmt nur weg (Regel (2) oben).

   DIE BLÜTEN. Jede hat ihre eigene Uhr (23–45 s, paarweise verschieden und
   nicht ganzzahlig verhältnisgleich, damit nie zwei dauerhaft im Gleichschritt
   laufen) und ihre eigene Phase über ein negatives Delay. Die Kurve ist die
   eines echten Feuerwerks und ausdrücklich UNSYMMETRISCH: ein kurzes Aufblühen
   (die letzten ~13 % des Umlaufs, in denen die Deckung von 0,06 auf 1 steigt),
   dann ein langes Verglühen und eine lange Pause bei fast null. Ein Sinus
   dagegen läse sich als Pulsschlag, und ein Fest pulsiert nicht.
   Die Spiegelung im Fluss trägt dieselbe Klasse — sie geht mit auf.

   Warum das hier steht und nicht in der Themen-CSS: die Ebene wird per
   `background-image: url(...)` geladen, und darin läuft nur, was IM Bild steht.
   Ein `animation-delay` von außen erreicht diese Uhren nicht. Fällt die
   Animation irgendwo aus, bleibt das Bild in seinem HELLSTEN Zustand stehen —
   also vollständig, gemessen und schön, nur eben still. */
const css = [];
[
  [23, -3.1],
  [29, -18.4],
  [31, -9.7],
  [34, -26.2],
  [37, -14.8],
  [41, -33.5],
  [45, -21.9],
].forEach(([p, delay], i) => {
  // Etwas verschiedene Verglüh-Tiefen, damit die sieben nicht wie eine
  // Maschine wirken: die großen sinken tiefer und bleiben länger fort.
  const rest = (0.05 + (i % 3) * 0.02).toFixed(2);
  const mid = (0.3 + (i % 4) * 0.05).toFixed(2);
  css.push(
    `.b${i}{animation:hb${i} ${p}s linear infinite;animation-delay:${delay}s}`,
    `@keyframes hb${i}{0%{opacity:1}9%{opacity:.86}24%{opacity:${mid}}` +
      `46%{opacity:${rest}}80%{opacity:${rest}}87%{opacity:.22}94%{opacity:.74}100%{opacity:1}}`,
  );
});
// Die Schüsse: kurz sichtbar, lange fort — sie steigen ja nur gelegentlich.
[
  [26, -7.3],
  [33, -19.6],
].forEach(([p, delay], i) => {
  css.push(
    `.r${i}{animation:ru${i} ${p}s linear infinite;animation-delay:${delay}s}`,
    `@keyframes ru${i}{0%{opacity:1}8%{opacity:.4}20%{opacity:.06}88%{opacity:.06}96%{opacity:.6}100%{opacity:1}}`,
  );
});
// Papierlaternen atmen — ruhig, tief, auf drei eigenen Uhren.
[
  [28, 0.58],
  [35, 0.5],
  [43, 0.64],
].forEach(([p, lo], i) => {
  css.push(
    `.l${i}{animation:la${i} ${p}s ease-in-out infinite;animation-delay:${f(-p * 0.29 * (i + 1))}s}`,
    `@keyframes la${i}{0%,100%{opacity:1}50%{opacity:${lo}}}`,
  );
});
// Das Wasser bricht die warmen Lichter unruhiger als die Luft sie hergibt.
[
  [24, 0.26],
  [30, 0.18],
  [39, 0.34],
].forEach(([p, lo], i) => {
  css.push(
    `.w${i}{animation:sp${i} ${p}s ease-in-out infinite;animation-delay:${f(-p * 0.23 * (i + 1))}s}`,
    `@keyframes sp${i}{0%,100%{opacity:1}34%{opacity:${lo}}68%{opacity:${(lo + 0.26).toFixed(2)}}}`,
  );
});
// Der Rauch zieht ab: eine sehr lange, sehr flache Uhr.
css.push('.sm{animation:smk 53s ease-in-out infinite;animation-delay:-17s}');
css.push('@keyframes smk{0%,100%{opacity:.5}50%{opacity:.2}}');

/* ── Die geteilten defs ──────────────────────────────────────────────────────
   Ein Hof je Feuerwerksfarbe, den sich alle Blüten dieser Farbe teilen — nie
   ein flacher Kreis (der hätte eine Kante und läse sich als Münze). */
const hoefe = Object.values(FW)
  .map(
    (c) =>
      `<radialGradient id="hof${c.id}">` +
      `<stop offset="0" stop-color="${c.hi}" stop-opacity="0.34"/>` +
      `<stop offset="0.38" stop-color="${c.mid}" stop-opacity="0.13"/>` +
      `<stop offset="1" stop-color="${c.mid}" stop-opacity="0"/></radialGradient>`,
  )
  .join('');

const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
  `role="img" aria-label="Sommerfest: Feuerwerk ueber einem Fluss, darunter Laternen, Staende und Menschen">` +
  `<style>${css.join('')}` +
  `@media(prefers-reduced-motion:reduce){*{animation:none!important}}</style>` +
  `<defs>${hoefe}` +
  `<filter id="weich" x="-40%" y="-80%" width="180%" height="260%">` +
  `<feGaussianBlur stdDeviation="34"/></filter>` +
  `<linearGradient id="himmel" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.skyTop}"/>` +
  `<stop offset="0.42" stop-color="${C.skyMid}"/>` +
  `<stop offset="0.66" stop-color="${C.skyLow}"/>` +
  `<stop offset="1" stop-color="${C.skyLow}"/></linearGradient>` +
  `<linearGradient id="fluss" x1="0" y1="0" x2="0" y2="1">` +
  `<stop offset="0" stop-color="${C.water}"/>` +
  `<stop offset="1" stop-color="${C.waterLow}"/></linearGradient></defs>` +
  out.join('') +
  `</svg>\n`;

writeFileSync(OUT, svg);
const bytes = Buffer.byteLength(svg);
console.log(
  `natsumatsuri-hanabi.svg  ${(bytes / 1024).toFixed(1)} KB  ·  ${blooms.length} Blüten  ·  ` +
    `${lights.length} Fest-Lichter  ·  Safe-Zone x${SAFE.x0}–${SAFE.x1}, y≥${SAFE.y0}`,
);
if (bytes > 80 * 1024) {
  console.error('✗ über dem 80-KB-Budget der ORDER');
  process.exit(1);
}
