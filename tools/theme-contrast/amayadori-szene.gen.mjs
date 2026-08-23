/**
 * AMAYADORI (雨宿り) — Szenen-Generator, Fassung v2
 * ═══════════════════════════════════════════════════════════════════════════
 * Erzeugt `frontend/public/themes/amayadori-szene.svg`.
 *
 *   node tools/theme-contrast/amayadori-szene.gen.mjs
 *
 * DAS BILD: man steht unter einem Vordach und schaut in den nächtlichen
 * Wolkenbruch hinaus. Oben rahmt die Unterseite des Vordachs die Lesespalte,
 * darunter öffnet sich die Gasse: die gegenüberliegende Häuserzeile mit warmen
 * Fenstern, eine Laternenkette über den Läden, Regen der über die ganze Breite
 * fällt, und im unteren Drittel die nasse Straße, in der jedes Licht als
 * senkrechter Streifen zurückkommt. Vorn links die Papierlaterne im eigenen
 * Licht, rechts der Automat in seinem kalten. In der Mitte, hinter der
 * Lesespalte, eilt jemand mit Schirm vorbei. Ganz unten: der trockene Fleck.
 *
 * ── DAS HELLIGKEITSGESETZ (der Grund, warum v1 schwarz war) ────────────────
 * Der Schleier (`--amayadori-veil`) legt sich mit Deckkraft α über die Spalte.
 * Ein Szenen-Wert v erscheint dort als  v·(1−α) + base·α.  Die Kette ist kurz,
 * weil dieses SVG DECKEND ist: es gibt nur Szene × Schleier, kein Luftglühen
 * mehr dazwischen (die Regie-Lektion „die Kette messen" ist hier durch
 * „die Kette kürzen" beantwortet).
 *
 * Die Obergrenze setzt NICHT der Geschmack, sondern --text-4 (#9a8c74, die
 * leiseste Schriftstufe): sie braucht 4,5:1 gegen den HELLSTEN Bildpunkt in
 * der Spalte. Das erlaubt dort eine Leuchtdichte von höchstens L≈0,0179.
 *
 * Und jetzt der Hebel, der v1 gefehlt hat: L ist KEIN Grauwert. Grün zählt
 * 71,5 %, Rot 21,3 %, Blau 7,2 %. Ein kaltes Regenblau darf darum viel heller
 * sein als ein warmes Fensterlicht, bevor es dieselbe Leuchtdichte hat:
 *
 *     warm  #d2a570 (210,165,112) → hinter dem Schleier 4,66:1   ← Deckel WARM
 *     kalt  #96b2c4 (150,178,196) → hinter dem Schleier 4,65:1   ← Deckel KALT
 *
 * Beide Deckel liegen also auf demselben Kontrast, aber der kalte ist im
 * Grauwert-Empfinden deutlich heller. Das Bild holt seine Tiefe deshalb aus
 * dem FARBABSTAND warm↔kalt, nicht aus Helligkeitsstufen allein — genau das,
 * was eine Regennacht ohnehin tut: nah und geborgen ist warm, fern und nass
 * ist blau.
 *
 * Die Deckel gelten GLOBAL, nicht nur „in der Spalte". Grund: bei 1024 px
 * Fensterbreite ist die Spalte (min(920px,100vw)) fast das ganze Bild — die
 * Randstreifen schrumpfen auf 52 px. Ein Motiv, das „im Randstreifen" hell sein
 * darf, gibt es nicht; es gibt nur Motive, die bei JEDER Breite tragen.
 *
 * ── HERZSCHLAG ────────────────────────────────────────────────────────────
 * Drei Uhren, alle nur transform/opacity:
 *   1. Fern-Regen — zwei Musterflächen, die fallen (im Bild, also auch HINTER
 *      der Lesespalte sichtbar; die kräftige Nahwand steht in der CSS und ist
 *      dort aus der Spalte ausmaskiert, weil das Vordach sie abhält).
 *   2. Pfützen-Ringe — Kreise, die aufgehen und verlöschen.
 *   3. Spiegelungen — die Lichtstreifen auf dem Asphalt atmen.
 * Jede Animation läuft von ihrem AUTORIERTEN Zustand aus nur nach unten. Bei
 * `prefers-reduced-motion` (und damit unter `--force-prefers-reduced-motion`)
 * steht das Bild deshalb auf seinem HELLSTEN Moment — die Messung trifft den
 * echten oberen Rand und nicht eine Stichprobe.
 *
 * Kein `--` in SVG-Kommentaren; dieses Bild hat gar keine (Regie-Lektion 2:
 * ein doppelter Bindestrich im Kommentar lässt Chrome die Datei still fallen).
 */

import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, '..', '..', 'frontend', 'public', 'themes', 'amayadori-szene.svg');
const BUDGET = 80 * 1024;

const W = 1600;
const H = 1000;

/* Fester Startwert: derselbe Aufruf ergibt bitgleich dasselbe Bild. */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = rng(0x1ff0d2a);
const rr = (lo, hi) => lo + rand() * (hi - lo);
const ri = (lo, hi) => Math.floor(rr(lo, hi + 1));
const pick = (xs) => xs[Math.floor(rand() * xs.length)];
const n = (v) => {
  const r = Math.round(v * 10) / 10;
  return String(r === Math.trunc(r) ? Math.trunc(r) : r);
};

/* ── Palette ───────────────────────────────────────────────────────────────
   WARM_MAX und KALT_MAX sind die gerechneten Deckel von oben. Nichts in
   diesem Bild ist heller. */
const WARM_MAX = '#d2a570';
const KALT_MAX = '#96b2c4';

const C = {
  himmelHoch: '#141c27',
  himmelTief: '#233043',
  stadtGlut: '#3a3b3c',
  fernDach: '#101720',
  hausA: '#1a2431',
  hausB: '#141c26',
  hausC: '#0f151d',
  dach: '#0c1117',
  traufe: '#090d12',
  fensterHell: WARM_MAX,
  fensterMittel: '#8d6d4b',
  fensterTief: '#57432f',
  fensterKalt: KALT_MAX,
  fensterKaltTief: '#5a6f7e',
  schildRot: '#b45f45',
  laterne: WARM_MAX,
  /* Nasser Asphalt ist NICHT braun. Er ist der Himmel, den man von unten
     sieht — kalt, und genau darum trägt er die warmen Spiegelungen. Die erste
     Fassung hatte hier einen warmen Ton plus ein warmes Bodenglühen über die
     volle Breite; das Ergebnis war ein sepiafarbener Teppich, auf dem kein
     Licht mehr auffiel. */
  strasse: '#101822',
  strasseHell: '#1b2733',
  bord: '#18202a',
  spiegelWarm: '#a3763f',
  /* Heller als der erste Ansatz (#6a8a9d). Die Tonspreizung eines Nacht-Themes
     muss von IRGENDWO kommen (REZEPT D2); solange die hellsten Punkte im Bild
     die warmen Spiegelungen waren, war die Strasse zwangslaeufig sepia. Jetzt
     traegt die kalte Seite die Helligkeit — nasser Asphalt unter bedecktem
     Himmel ist genau das: hell und kalt. */
  spiegelKalt: '#7c9db1',
  /* Das Glanzlicht auf einer Wasserflaeche: der hellste Punkt des Bildes, und
     er ist KALT. Ohne ihn bleibt die Nacht eine Masse. */
  glanz: '#b8cedd',
  pfuetze: '#2f4658',
  ringe: '#6a8ba1',
  trocken: '#241e18',
  vordach: '#0a0d11',
  vordachLicht: '#1d1a14',
  regen: '#9fb6c8',
  figur: '#080b0f',
};

const out = [];
const put = (s) => out.push(s);

/* ═══ KOPF + UHRWERKE ═══════════════════════════════════════════════════════ */
put(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" ` +
    `role="img" aria-label="Regennacht: von unter einem Vordach in eine nasse Gasse mit erleuchteten Fenstern">`,
);

const style = [
  /* Fern-Regen: die Kachel ist so geschnitten, dass eine Verschiebung um genau
     eine Kachelhoehe das Gitter auf sich selbst abbildet. Die Schleife schliesst
     ohne Sprung. */
  `.rg{animation:rgf 26s linear infinite}`,
  `@keyframes rgf{from{transform:translateY(0)}to{transform:translateY(520px)}}`,
  `.rh{animation:rhf 17s linear infinite}`,
  `@keyframes rhf{from{transform:translateY(0)}to{transform:translateY(340px)}}`,
  /* Pfuetzen-Ringe: der autorierte Zustand ist der volle Ring; die Uhr laesst
     ihn von klein nach gross laufen und dabei verloeschen. */
  `.k{transform-box:fill-box;transform-origin:50% 50%}`,
];
for (let i = 0; i < 7; i++) {
  const dur = 3.4 + i * 0.53;
  style.push(
    `.k${i}{animation:kr${i} ${n(dur)}s linear infinite;animation-delay:-${n(i * 0.77)}s}`,
    `@keyframes kr${i}{0%{transform:scale(.18);opacity:.9}70%{opacity:.35}100%{transform:scale(1);opacity:0}}`,
  );
}
/* Spiegelungen: sie atmen und zittern leicht in der Laenge, wie Licht auf
   bewegtem Wasser. Der autorierte Zustand ist der hellste. */
for (let i = 0; i < 4; i++) {
  const dur = 6.5 + i * 1.7;
  style.push(
    `.s${i}{transform-box:fill-box;transform-origin:50% 0;animation:sp${i} ${n(dur)}s ease-in-out infinite;animation-delay:-${n(i * 1.3)}s}`,
    `@keyframes sp${i}{0%,100%{opacity:1;transform:scaleY(1)}50%{opacity:.55;transform:scaleY(.82)}}`,
  );
}
style.push(
  `.lz{transform-box:fill-box;transform-origin:50% 0;animation:lzw 14s ease-in-out infinite}`,
  `@keyframes lzw{0%,100%{transform:rotate(-1.1deg)}50%{transform:rotate(1.1deg)}}`,
  `@media(prefers-reduced-motion:reduce){*{animation:none!important}}`,
);
put(`<style>${style.join('')}</style>`);

/* ═══ DEFS ═════════════════════════════════════════════════════════════════ */
const defs = [];

defs.push(
  `<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">` +
    `<stop offset="0" stop-color="${C.himmelHoch}"/>` +
    `<stop offset=".62" stop-color="${C.himmelTief}"/>` +
    `<stop offset="1" stop-color="${C.stadtGlut}"/></linearGradient>`,
);
defs.push(
  `<linearGradient id="str" x1="0" y1="0" x2="0" y2="1">` +
    `<stop offset="0" stop-color="${C.strasseHell}"/>` +
    `<stop offset=".55" stop-color="${C.strasse}"/>` +
    `<stop offset="1" stop-color="#0f141a"/></linearGradient>`,
);
/* Ein Verlauf, den sich ALLE Spiegelungen teilen: oben satt, unten aus. Als
   Maske in objectBoundingBox, damit jede Spiegelung ihre eigene Groesse haben
   darf und trotzdem dieselbe Maske benutzt. */
defs.push(
  `<linearGradient id="fadeg" x1="0" y1="0" x2="0" y2="1">` +
    `<stop offset="0" stop-color="#fff" stop-opacity=".95"/>` +
    `<stop offset=".35" stop-color="#fff" stop-opacity=".5"/>` +
    `<stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>`,
  `<mask id="fade" maskUnits="objectBoundingBox" maskContentUnits="objectBoundingBox">` +
    `<rect width="1" height="1" fill="url(#fadeg)"/></mask>`,
);
/* Die Himmelsbahn im Asphalt: seitlich WEICH. Eine Wolkendecke hat keine
   Kanten, also darf ihre Spiegelung auch keine haben — ein Rechteck mit
   senkrechter Kante liest sich sofort als Tapete statt als Wasser (REZEPT F).
   Ein Verlauf in objectBoundingBox skaliert sich auf jede Bahn selbst. */
defs.push(
  `<linearGradient id="sky" x1="0" y1="0" x2="1" y2="0">` +
    `<stop offset="0" stop-color="${C.spiegelKalt}" stop-opacity="0"/>` +
    `<stop offset=".5" stop-color="${C.spiegelKalt}" stop-opacity="1"/>` +
    `<stop offset="1" stop-color="${C.spiegelKalt}" stop-opacity="0"/></linearGradient>`,
);
/* Das Glanzlicht als VERLAUF, nicht als Scheibe. Dieselbe Lehre wie bei den
   Stern-Hoefen in nagareboshi (REZEPT F, „Muenzen"-Effekt): eine flache
   Ellipse mit harter Kante liegt als Gegenstand auf der Strasse — sie sah
   in der Frame-Sichtung aus wie hingeworfene Pastillen. Licht auf Wasser hat
   keine Kante, es hat einen Rand, der ins Nichts laeuft. EIN def fuer alle. */
defs.push(
  `<radialGradient id="glz">` +
    `<stop offset="0" stop-color="${C.glanz}" stop-opacity=".8"/>` +
    `<stop offset=".45" stop-color="${C.glanz}" stop-opacity=".3"/>` +
    `<stop offset="1" stop-color="${C.glanz}" stop-opacity="0"/></radialGradient>`,
);
/* Glut um ein Licht herum. Wird auf jedes Fenster skaliert. */
defs.push(
  `<radialGradient id="glw">` +
    `<stop offset="0" stop-color="${WARM_MAX}" stop-opacity=".5"/>` +
    `<stop offset=".45" stop-color="#a97f4e" stop-opacity=".2"/>` +
    `<stop offset="1" stop-color="#7a5a38" stop-opacity="0"/></radialGradient>`,
  `<radialGradient id="glk">` +
    `<stop offset="0" stop-color="${KALT_MAX}" stop-opacity=".42"/>` +
    `<stop offset=".45" stop-color="#6f8b9c" stop-opacity=".18"/>` +
    `<stop offset="1" stop-color="#4c6272" stop-opacity="0"/></radialGradient>`,
);
/* Der Regen. Kachelbreite : Kachelhoehe = 1 : 20, also knapp 3 Grad aus der
   Senkrechten, dieselbe Achse wie die Nahwand in der CSS (93deg). */
defs.push(
  `<pattern id="rf" patternUnits="userSpaceOnUse" width="26" height="520">` +
    `<path d="M26 0 0 520" stroke="${C.regen}" stroke-width="1.15" fill="none" opacity=".33"/></pattern>`,
  `<pattern id="rn" patternUnits="userSpaceOnUse" width="17" height="340">` +
    `<path d="M17 0 0 340" stroke="${C.regen}" stroke-width=".9" fill="none" opacity=".2"/></pattern>`,
);
/* Waagerechte Kraeuselung, die die Spiegelungen bricht. */
defs.push(
  `<pattern id="kr" patternUnits="userSpaceOnUse" width="9" height="7">` +
    `<rect y="3.2" width="9" height="1.3" fill="#0a0e13" opacity=".55"/></pattern>`,
);
/* Der Regen soll oben am dichtesten sein und ueber der Strasse ausduennen. */
defs.push(
  `<linearGradient id="rmg" x1="0" y1="0" x2="0" y2="1">` +
    `<stop offset="0" stop-color="#fff" stop-opacity=".25"/>` +
    `<stop offset=".22" stop-color="#fff" stop-opacity="1"/>` +
    `<stop offset=".76" stop-color="#fff" stop-opacity=".78"/>` +
    `<stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>`,
  `<mask id="rm"><rect y="150" width="${W}" height="640" fill="url(#rmg)"/></mask>`,
);
/* Der trockene Fleck: warm, matt, nach aussen auslaufend. */
defs.push(
  `<radialGradient id="dry" cx=".5" cy="1" r=".82">` +
    `<stop offset="0" stop-color="#4a3b2c" stop-opacity=".98"/>` +
    `<stop offset=".55" stop-color="${C.trocken}" stop-opacity=".82"/>` +
    `<stop offset="1" stop-color="#171310" stop-opacity="0"/></radialGradient>`,
);
/* Das Licht, das aus dem Unterstand auf die nasse Strasse faellt. */
defs.push(
  `<radialGradient id="spill" cx=".5" cy="0" r=".9">` +
    `<stop offset="0" stop-color="#b3854f" stop-opacity=".3"/>` +
    `<stop offset=".55" stop-color="#8a6740" stop-opacity=".12"/>` +
    `<stop offset="1" stop-color="#6a5030" stop-opacity="0"/></radialGradient>`,
);

put(`<defs>${defs.join('')}</defs>`);

/* ═══ 1 · HIMMEL ═══════════════════════════════════════════════════════════ */
put(`<rect width="${W}" height="${H}" fill="url(#sky)"/>`);
/* Tief haengende Wolken: flache Ellipsen, kaum heller als der Himmel. */
for (let i = 0; i < 9; i++) {
  const cx = rr(-60, W + 60);
  const cy = rr(60, 300);
  put(
    `<ellipse cx="${n(cx)}" cy="${n(cy)}" rx="${n(rr(180, 380))}" ry="${n(rr(28, 62))}" ` +
      `fill="#26313f" opacity="${n(rr(0.12, 0.3))}"/>`,
  );
}
/* Das Stadtglühen ueber der Dachlinie: der Himmel ist ueber einer Stadt nachts
   NIE schwarz, und dieses Glühen ist es, das die Dachkanten lesbar macht. */
put(
  `<rect x="0" y="300" width="${W}" height="180" fill="${C.stadtGlut}" opacity=".22" mask="url(#fade)"/>`,
);

/* ═══ 2 · FERNE DACHLINIE ══════════════════════════════════════════════════ */
{
  let x = -60;
  const seg = [];
  seg.push(`M-60 ${H}`);
  let y = 400;
  while (x < W + 60) {
    const w = rr(50, 130);
    y = Math.max(352, Math.min(432, y + rr(-26, 26)));
    seg.push(`L${n(x)} ${n(y)}`);
    x += w;
    seg.push(`L${n(x)} ${n(y)}`);
  }
  seg.push(`L${n(x)} ${H}Z`);
  put(`<path d="${seg.join('')}" fill="${C.fernDach}"/>`);
}

/* ═══ 3 · DIE GEGENÜBERLIEGENDE HÄUSERZEILE ════════════════════════════════
   Der Mittelgrund, und damit der Grund, warum die Lesespalte kein Loch mehr
   ist: diese Zeile laeuft ueber die volle Breite HINTER der Spalte durch. */
/* Alles zwischen hier und dem Regen steckt in EINER Gruppe mit Namen. Grund:
   die grosse Pfuetze im Vordergrund spiegelt diese Gruppe als Ganzes (ein
   <use>, gestaucht und gekippt) — die Haeuserzeile steht also ein zweites Mal
   im Bild, kopfueber im Wasser, genau hinter der Lesespalte. Das ist der
   billigste ehrliche Fuellstoff, den es gibt: keine zweite Zeichnung, dieselbe
   Wahrheit. */
put(`<g id="hz">`);
const lichter = []; /* {x,y,w,h,warm} — Quellen fuer Glut und Spiegelung */
const haeuser = [];
{
  let x = -70;
  /* Die Gasse: eine bewusst gesetzte Luecke, in der der Blick tiefer faellt. */
  const gasseAb = 470;
  const gasseBis = 530;
  while (x < W + 40) {
    if (x < gasseAb && x + 150 > gasseAb) {
      x = gasseBis;
      continue;
    }
    const w = rr(104, 208);
    /* Zwei hoehere Haeuser in der Bildmitte, damit die Lesespalte einen Anker
       bekommt und die Dachlinie nicht als eine gerade Wand liest. Die erste
       Fassung streute nur zwischen 352 und 432 — 80 px auf 1600 px Breite sind
       fuer das Auge eine Linie. Jetzt sind es 170. */
    const hoch = (x > 636 && x < 812) || (x > 1074 && x < 1200);
    const dachY = hoch ? rr(286, 320) : rr(346, 456);
    haeuser.push({ x, w, dachY, ton: pick([C.hausA, C.hausB, C.hausC, C.hausA, C.hausB]) });
    x += w + rr(3, 9);
  }
}
for (const b of haeuser) {
  const { x, w, dachY, ton } = b;
  /* Korpus */
  put(`<rect x="${n(x)}" y="${n(dachY)}" width="${n(w)}" height="${n(700 - dachY)}" fill="${ton}"/>`);
  /* Ziegeldach mit ueberstehender Traufe */
  put(
    `<path d="M${n(x - 12)} ${n(dachY + 4)}L${n(x + w / 2)} ${n(dachY - 17)}L${n(x + w + 12)} ${n(dachY + 4)}Z" fill="${C.dach}"/>`,
  );
  put(`<rect x="${n(x - 14)}" y="${n(dachY + 2)}" width="${n(w + 28)}" height="5" fill="${C.traufe}"/>`);
  /* Mondlicht bzw. Stadtlicht auf der nassen Dachkante: die einzige helle
     Linie im oberen Mittelgrund, sie zeichnet die Silhouette. */
  put(
    `<rect x="${n(x - 14)}" y="${n(dachY + 1.4)}" width="${n(w + 28)}" height="1.4" fill="#5a7186" opacity=".7"/>`,
  );
  /* Dachaufbauten: Wassertank, Antenne, Schornstein. Ohne sie ist eine
     Dachlinie eine Kante, mit ihnen ist sie eine Stadt. */
  if (rand() < 0.55) {
    const tx = x + rr(0.2, 0.62) * w;
    if (rand() < 0.5) {
      put(`<rect x="${n(tx)}" y="${n(dachY - 44)}" width="34" height="26" rx="3" fill="#0b0f14"/>`);
      put(`<rect x="${n(tx + 4)}" y="${n(dachY - 18)}" width="5" height="18" fill="#0b0f14"/>`);
      put(`<rect x="${n(tx + 25)}" y="${n(dachY - 18)}" width="5" height="18" fill="#0b0f14"/>`);
      put(`<rect x="${n(tx)}" y="${n(dachY - 45)}" width="34" height="1.4" fill="#5a7186" opacity=".55"/>`);
    } else {
      put(`<rect x="${n(tx)}" y="${n(dachY - 58)}" width="2.6" height="58" fill="#0b0f14"/>`);
      put(`<path d="M${n(tx - 13)} ${n(dachY - 50)}h29M${n(tx - 10)} ${n(dachY - 40)}h23M${n(tx - 7)} ${n(dachY - 31)}h17" stroke="#0b0f14" stroke-width="2.2"/>`);
    }
  }

  /* Fenster: Raster ueber die Stockwerke. */
  const stock = Math.max(1, Math.floor((690 - dachY) / 96));
  const spalten = Math.max(2, Math.round(w / 62));
  const fw = (w - 16) / spalten - 12;
  for (let s = 0; s < stock; s++) {
    const fy = dachY + 26 + s * 96;
    if (fy + 46 > 690) break;
    for (let c = 0; c < spalten; c++) {
      const fx = x + 14 + c * ((w - 16) / spalten);
      const p = rand();
      let farbe = null;
      let warm = true;
      if (p < 0.4) farbe = C.fensterHell;
      else if (p < 0.58) farbe = C.fensterMittel;
      else if (p < 0.68) {
        farbe = C.fensterKalt;
        warm = false;
      } else if (p < 0.78) farbe = C.fensterTief;
      const fh = rr(38, 52);
      if (!farbe) {
        put(`<rect x="${n(fx)}" y="${n(fy)}" width="${n(fw)}" height="${n(fh)}" fill="#0d1219"/>`);
        continue;
      }
      /* Glut zuerst, damit das Fenster selbst obenauf sitzt und scharf bleibt. */
      const gr = fw * 2.6;
      put(
        `<ellipse cx="${n(fx + fw / 2)}" cy="${n(fy + fh / 2)}" rx="${n(gr)}" ry="${n(gr * 0.8)}" fill="url(#${warm ? 'glw' : 'glk'})"/>`,
      );
      put(`<rect x="${n(fx)}" y="${n(fy)}" width="${n(fw)}" height="${n(fh)}" fill="${farbe}"/>`);
      /* Sprossenkreuz: ohne es ist ein Fenster ein Rechteck, mit ihm ein Fenster. */
      put(
        `<path d="M${n(fx)} ${n(fy + fh / 2)}h${n(fw)}M${n(fx + fw / 2)} ${n(fy)}v${n(fh)}" stroke="#1a1c1e" stroke-width="1.6" opacity=".7"/>`,
      );
      if (fy + fh > 470) lichter.push({ x: fx, y: fy, w: fw, h: fh, warm });
    }
  }
}
/* In der Gasse, tief hinten: ein Automat, der die Luecke fuellt statt sie zu
   zeigen. Er ist der fernste Punkt des Bildes. */
put(`<rect x="470" y="470" width="60" height="230" fill="#0a0f15"/>`);
put(`<ellipse cx="500" cy="560" rx="86" ry="120" fill="url(#glk)"/>`);
put(`<rect x="486" y="516" width="30" height="72" fill="${C.fensterKaltTief}"/>`);
lichter.push({ x: 486, y: 516, w: 30, h: 72, warm: false });

/* ═══ 4 · LADENZEILE + LATERNENKETTE ═══════════════════════════════════════ */
/* Die Ladenfront unter den Wohnungen: dunkle Bank mit leuchtenden Eingaengen. */
put(`<rect x="0" y="668" width="${W}" height="62" fill="#0b1015"/>`);
for (let i = 0; i < 11; i++) {
  const dx = -40 + i * 152 + rr(-16, 16);
  const dw = rr(46, 86);
  if (rand() < 0.26) continue;
  const warm = rand() < 0.74;
  put(
    `<ellipse cx="${n(dx + dw / 2)}" cy="700" rx="${n(dw * 1.9)}" ry="64" fill="url(#${warm ? 'glw' : 'glk'})"/>`,
  );
  put(
    `<rect x="${n(dx)}" y="672" width="${n(dw)}" height="52" fill="${warm ? C.fensterHell : C.fensterKalt}" opacity=".92"/>`,
  );
  /* Noren: der Stoffvorhang schneidet das Licht oben ab. */
  put(`<rect x="${n(dx - 3)}" y="672" width="${n(dw + 6)}" height="17" fill="${C.schildRot}" opacity=".85"/>`);
  lichter.push({ x: dx, y: 672, w: dw, h: 52, warm });
}
/* Senkrechte Schilder: zwei, damit die Zeile eine Stimme bekommt. */
for (const [sx, sy, sh, col] of [
  [352, 520, 128, C.schildRot],
  [1042, 496, 146, C.fensterHell],
]) {
  put(`<ellipse cx="${n(sx + 13)}" cy="${n(sy + sh / 2)}" rx="72" ry="${n(sh * 0.8)}" fill="url(#glw)"/>`);
  put(`<rect x="${n(sx)}" y="${n(sy)}" width="26" height="${n(sh)}" fill="${col}" opacity=".9"/>`);
  put(`<rect x="${n(sx)}" y="${n(sy)}" width="26" height="${n(sh)}" fill="none" stroke="#0d1218" stroke-width="2.4"/>`);
  for (let k = 0; k < 4; k++) {
    put(
      `<rect x="${n(sx + 7)}" y="${n(sy + 12 + k * (sh - 20) / 4)}" width="12" height="12" fill="#12161b" opacity=".8"/>`,
    );
  }
  lichter.push({ x: sx, y: sy, w: 26, h: sh, warm: true });
}
/* Die Laternenkette: sie zieht ueber die VOLLE Breite und bindet die Zeile
   zusammen. Ihre Durchhaenge sind das, was das Auge als Gasse liest. */
{
  const stuetzen = [-30, 300, 640, 980, 1320, 1640];
  for (let i = 0; i < stuetzen.length - 1; i++) {
    const x0 = stuetzen[i];
    const x1 = stuetzen[i + 1];
    const y0 = 618 + rr(-6, 6);
    const sag = rr(30, 46);
    put(
      `<path d="M${n(x0)} ${n(y0)}Q${n((x0 + x1) / 2)} ${n(y0 + sag * 2)} ${n(x1)} ${n(y0)}" ` +
        `fill="none" stroke="#0a0e12" stroke-width="1.8"/>`,
    );
    const anz = 6;
    for (let k = 1; k < anz; k++) {
      const t = k / anz;
      const lx = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * ((x0 + x1) / 2) + t * t * x1;
      const ly =
        (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * (y0 + sag * 2) + t * t * y0;
      put(`<ellipse cx="${n(lx)}" cy="${n(ly + 9)}" rx="26" ry="22" fill="url(#glw)"/>`);
      put(`<ellipse cx="${n(lx)}" cy="${n(ly + 9)}" rx="6.4" ry="8.2" fill="${C.laterne}"/>`);
      put(`<rect x="${n(lx - 2)}" y="${n(ly + 1)}" width="4" height="3" fill="#0a0e12"/>`);
      lichter.push({ x: lx - 6.4, y: ly + 1, w: 12.8, h: 16, warm: true });
    }
  }
}

/* ═══ 5 · MASTEN + LEITUNGEN ═══════════════════════════════════════════════
   Senkrechten gegen die vielen Waagerechten, und der Beweis, dass es eine
   Strasse ist und kein Bühnenbild. */
for (const px of [214, 786, 1352]) {
  put(`<rect x="${n(px)}" y="322" width="7" height="392" fill="#0a0d12"/>`);
  put(`<rect x="${n(px - 20)}" y="352" width="47" height="4.5" fill="#0a0d12"/>`);
  put(`<rect x="${n(px - 15)}" y="380" width="37" height="4" fill="#0a0d12"/>`);
}
put(
  `<path d="M-40 366Q217 404 217 366Q217 404 789 372Q789 410 1355 366Q1355 404 1640 380" ` +
    `fill="none" stroke="#0b0f14" stroke-width="2.2" opacity=".9"/>`,
);
put(
  `<path d="M-40 392Q217 428 217 392Q217 430 789 398Q789 434 1355 392Q1355 428 1640 404" ` +
    `fill="none" stroke="#0b0f14" stroke-width="1.8" opacity=".8"/>`,
);
put(`</g>`);

/* ═══ 6 · DER REGEN IM BILD ════════════════════════════════════════════════
   Zwei Wände, die HINTER der Lesespalte durchfallen. Die kräftige Nahwand
   steht in der CSS und hört an der Traufkante auf; diese hier ist der Regen
   drüben über der Gasse, den das Vordach nicht abhält. */
put(
  `<g mask="url(#rm)">` +
    `<g class="rg"><rect x="-40" y="-560" width="${W + 80}" height="${H + 700}" fill="url(#rf)"/></g>` +
    `<g class="rh"><rect x="-40" y="-380" width="${W + 80}" height="${H + 520}" fill="url(#rn)"/></g>` +
    `</g>`,
);

/* ═══ 7 · DIE NASSE STRASSE ════════════════════════════════════════════════ */
put(`<rect x="0" y="706" width="${W}" height="${H - 706}" fill="url(#str)"/>`);
/* Bordstein und Rinne: die Kante, an der das Wasser laeuft. Ein durchgehender
   Strich ueber 1600 px schneidet das Bild entzwei — darum ist die Rinne aus
   Stuecken gesetzt und die Kante wird von den Spiegelungen ueberlaufen. */
put(`<rect x="0" y="706" width="${W}" height="9" fill="${C.bord}"/>`);
for (let i = 0; i < 26; i++) {
  const gx = -20 + i * 64 + rr(-8, 8);
  put(`<rect x="${n(gx)}" y="715" width="${n(rr(28, 56))}" height="3.4" fill="#33445a" opacity="${n(rr(0.4, 0.8))}"/>`);
}

/* DIE GROSSE PFUETZE. Sie spiegelt die Haeuserzeile kopfueber — dieselbe
   Gruppe, an der Wasserlinie y=712 gekippt und auf 45 % gestaucht, wie es die
   flache Aufsicht auf eine Wasserflaeche tut. Sie liegt bewusst in der
   Bildmitte, also hinter der Lesespalte: das Loch der ersten Fassung ist jetzt
   die Stelle mit dem meisten Inhalt. */
put(
  `<defs><clipPath id="pud"><ellipse cx="812" cy="806" rx="486" ry="102"/></clipPath></defs>`,
);
/* Das Wasser der Pfuetze SELBST liegt unten — die Spiegelung kommt darueber. */
put(
  `<ellipse cx="812" cy="806" rx="486" ry="102" fill="${C.pfuetze}" opacity=".28"/>`,
);

/* Die Spiegelungen — der eigentliche Fuellstoff des unteren Drittels. Jedes
   Licht von oben kommt als senkrechter Streifen zurueck, je tiefer desto
   laenger und desto weicher. */
{
  /* DIE STRASSE SPIEGELT DEN HIMMEL, NICHT DIE FENSTER.
     Nasser Asphalt ist eine waagerechte Flaeche unter einem offenen Himmel —
     was er hauptsaechlich zurueckgibt, ist dieser Himmel, und der ist bei
     Regen blau-grau. Fensterlicht ist demgegenueber eine kleine, punktfoermige
     Quelle: es macht einen SCHMALEN, hellen Streifen direkt unter sich, es
     tuencht nicht die Strasse.
     Die Vorgaenger-Fassung hatte das umgedreht. Nachgezaehlt in der
     ausgelieferten SVG: 46 warme Spiegelstreifen gegen 3 kalte. 46 Streifen
     à bis zu 52 px auf 1600 px Breite ueberlappen zwangslaeufig zu einer
     durchgehenden warmen Flaeche — das ist der „wieder sepia"-Befund, den die
     Vorgaenger-Hand selbst notiert hat, als Zahl.
     Jetzt: KALT ist der Grund, WARM ist die Ausnahme. */
  let i = 0;
  let warmIdx = 0;
  for (const L of lichter) {
    const cx = L.x + L.w / 2;
    if (cx < -80 || cx > W + 80) continue;
    /* Nur die tiefstehenden Lichter stehen im Wasser — ein Licht im vierten
       Stock streift den Asphalt zu flach, um dort noch anzukommen. ACHTUNG,
       das ist die Falle der Vorgaenger-Fassung: dieser Filter waehlt
       ungewollt die WARMEN aus, denn Ladenzeile und Laternen haengen tief,
       waehrend die kalten Fenster oben liegen. Er allein macht das Bild also
       waermer, nicht kuehler. Darum wird warm danach ausgeduennt. */
    if (L.y < 560) continue;
    /* Nur jedes dritte warme Licht kommt ins Wasser. Das ist keine Willkuer:
       Spiegelung braucht eine ungebrochene Flaeche, und eine Strasse voller
       Pfuetzen, Kraeuselung und Fussabdruecke hat die nur stellenweise. */
    if (L.warm && warmIdx++ % 3 !== 0) continue;
    /* Warm: SCHMAL und kurz — ein Streifen, der auffaellt, weil er allein
       steht. Kalt: breit und lang — der Grundton, der tragen soll. */
    const breite = L.warm ? Math.min(26, L.w * 0.7 + 5) : Math.min(58, L.w * 1.3 + 9);
    const laenge = 168 + (L.warm ? 84 : 152) + rr(0, 140);
    const y0 = 710;
    put(
      `<rect class="s${i % 4}" x="${n(cx - breite / 2)}" y="${n(y0)}" width="${n(breite)}" height="${n(laenge)}" ` +
        `fill="${L.warm ? C.spiegelWarm : C.spiegelKalt}" ` +
        `opacity="${n(L.warm ? rr(0.5, 0.7) : rr(0.42, 0.6))}" mask="url(#fade)"/>`,
    );
    i++;
  }
  /* DER HIMMEL IM ASPHALT. Breite, weiche, kalte Bahnen ueber die volle
     Breite — die Rueckgabe der bedeckten Wolkendecke. Sie sind diffus (eine
     Wolkendecke hat keine Kanten) und darum breit und schwach statt schmal
     und kraeftig. Sie sind der Grund, aus dem die Strasse blau-grau liest;
     ohne sie waeren die Fensterspiegelungen das Einzige im Wasser, und dann
     ist das Wasser zwangslaeufig warm. */
  for (let k = 0; k < 13; k++) {
    /* Breit, ueberlappend, mit weichen Flanken (url(#sky)) und JE EIGENEM
       Ansatzpunkt: hingen alle an derselben Oberkante, zoege das eine zweite
       waagerechte Linie unter den Bordstein, und genau daran ist die erste
       Fassung als „Teppich" gescheitert. */
    const bx = -90 + k * 132 + rr(-38, 38);
    const bw = rr(150, 280);
    const bh = 200 + rr(0, 210);
    put(
      `<rect class="s${k % 4}" x="${n(bx)}" y="${n(rr(709, 736))}" width="${n(bw)}" height="${n(bh)}" ` +
        `fill="url(#sky)" opacity="${n(rr(0.2, 0.36))}" mask="url(#fade)"/>`,
    );
  }
}
/* DIE GESPIEGELTE HAEUSERZEILE — jetzt ZULETZT, also OBEN AUF.
   Sie stand vorher unter den Spiegelstreifen und Himmelsbahnen und war
   dadurch fast weggewaschen; genau in der Bildmitte, wo die Lesespalte den
   meisten Inhalt braucht, war am wenigsten zu sehen. Eine Spiegelung liegt
   optisch AUF dem Wasser, nicht darunter — die richtige Reihenfolge ist also
   zugleich die richtige Physik. Es ist derselbe Baum von Formen, an der
   Wasserlinie gekippt und auf 45 % gestaucht: kein zweites Zeichnen, dieselbe
   Wahrheit, und der billigste ehrliche Fuellstoff fuer das untere Drittel. */
put(
  `<g clip-path="url(#pud)" opacity=".72"><use href="#hz" transform="matrix(1 0 0 -0.45 0 1032.4)"/></g>`,
);
/* Der helle Rand einer Pfuetze ist das, was sie als Pfuetze lesbar macht. */
put(
  `<ellipse cx="812" cy="806" rx="486" ry="102" fill="none" stroke="#4e6b82" stroke-width="1.6" opacity=".3"/>`,
);
/* Die Kraeuselung bricht die Streifen: ohne sie waeren es Balken, mit ihr ist
   es Wasser. */
put(`<rect x="0" y="712" width="${W}" height="${H - 712}" fill="url(#kr)" opacity=".85"/>`);

/* Pfuetzen: flache Ellipsen, in denen der Himmel steht — kalt gegen den warmen
   Asphalt, und darum auch unter dem Schleier noch als eigene Sache lesbar. */
const pfuetzen = [
  [176, 806, 132, 21],
  [432, 866, 178, 27],
  [726, 828, 152, 23],
  [968, 906, 214, 32],
  [1236, 842, 146, 22],
  [560, 962, 196, 26],
  [1420, 918, 168, 25],
];
pfuetzen.forEach(([px, py, prx, pry], i) => {
  put(`<ellipse cx="${n(px)}" cy="${n(py)}" rx="${n(prx)}" ry="${n(pry)}" fill="${C.pfuetze}" opacity=".38"/>`);
  put(
    `<ellipse cx="${n(px)}" cy="${n(py)}" rx="${n(prx * 0.86)}" ry="${n(pry * 0.8)}" fill="#4b6478" opacity=".2"/>`,
  );
  /* Drei Ringe je Pfuetze, versetzt: DAS ist der sichtbare Herzschlag in der
     Bildmitte, dort wo die Lesespalte steht. */
  for (let k = 0; k < 3; k++) {
    const idx = (i * 3 + k) % 7;
    put(
      `<ellipse class="k k${idx}" cx="${n(px + rr(-prx * 0.4, prx * 0.4))}" cy="${n(py + rr(-pry * 0.4, pry * 0.4))}" ` +
        `rx="${n(prx * 0.6)}" ry="${n(pry * 0.62)}" fill="none" stroke="${C.ringe}" stroke-width="1.7" opacity=".9"/>`,
    );
  }
  /* DER SCHIMMER. Auf jeder Pfuetze steht ein flaches Glanzlicht — die Stelle,
     an der die Wasserflaeche den bedeckten Himmel direkt zurueckwirft. Es sind
     die HELLSTEN Punkte des ganzen Bildes und sie sind kalt; sie liefern die
     Tonspreizung, die vorher von den warmen Spiegelungen kam, und weil sie auf
     der langsamen Spiegel-Uhr (s0…s3) liegen, atmen sie sichtbar mit. */
  put(
    `<ellipse class="s${i % 4}" cx="${n(px + rr(-prx * 0.3, prx * 0.3))}" cy="${n(py - pry * 0.18)}" ` +
      `rx="${n(prx * rr(0.42, 0.66))}" ry="${n(Math.max(3, pry * 0.42))}" fill="url(#glz)" ` +
      `opacity="${n(rr(0.5, 0.78))}"/>`,
  );
});

/* ═══ 8 · WER DRAUSSEN IST ═════════════════════════════════════════════════
   Zwei Menschen mit Schirm, beide in der Bildmitte — also HINTER der
   Lesespalte. Sie geben dem unteren Mittelgrund einen Massstab. */
function passant(cx, fussY, hoehe, schirmR, deckung) {
  const g = [];
  const kopfY = fussY - hoehe;
  g.push(
    `<path d="M${n(cx - schirmR)} ${n(kopfY - 12)}Q${n(cx)} ${n(kopfY - 12 - schirmR * 0.72)} ${n(cx + schirmR)} ${n(kopfY - 12)}Z" fill="${C.figur}" opacity="${n(deckung)}"/>`,
  );
  g.push(
    `<path d="M${n(cx - schirmR)} ${n(kopfY - 12)}q${n(schirmR * 0.5)} 9 ${n(schirmR)} 0q${n(schirmR * 0.5)} -9 ${n(schirmR)} 0" fill="none" stroke="${C.figur}" stroke-width="2.4" opacity="${n(deckung)}"/>`,
  );
  g.push(`<rect x="${n(cx - 1)}" y="${n(kopfY - 16)}" width="2.4" height="${n(hoehe * 0.42)}" fill="${C.figur}" opacity="${n(deckung)}"/>`);
  g.push(`<circle cx="${n(cx)}" cy="${n(kopfY + 7)}" r="${n(hoehe * 0.082)}" fill="${C.figur}" opacity="${n(deckung)}"/>`);
  g.push(
    `<path d="M${n(cx - hoehe * 0.1)} ${n(fussY)}L${n(cx - hoehe * 0.085)} ${n(kopfY + hoehe * 0.17)}q${n(hoehe * 0.09)} -${n(hoehe * 0.05)} ${n(hoehe * 0.185)} 0L${n(cx + hoehe * 0.105)} ${n(fussY)}Z" fill="${C.figur}" opacity="${n(deckung)}"/>`,
  );
  return g.join('');
}
put(passant(690, 786, 128, 52, 0.94));
put(passant(1092, 754, 92, 38, 0.78));
/* Ihre Spiegelungen: dunkle Loecher im hellen Wasser, kein Licht. */
put(`<rect x="662" y="788" width="56" height="86" fill="#0b0f14" opacity=".5" mask="url(#fade)"/>`);
put(`<rect x="1070" y="756" width="44" height="62" fill="#0b0f14" opacity=".42" mask="url(#fade)"/>`);

/* ═══ 9 · DER TROCKENE FLECK ═══════════════════════════════════════════════
   Er ist ein GEGENSTAND, kein Loch: mattes, warmes Pflaster, das die nasse
   Strasse unterbricht, mit einer sichtbaren Nass/Trocken-Kante und der
   Tropfreihe der Traufe darauf. */
/* Das Licht aus dem Unterstand faellt als Trapez nach vorn auf das nasse
   Pflaster — schmal, gerichtet, von hinten kommend. Die erste Fassung legte
   hier stattdessen ein warmes Glühen über 1360 x 240 px; das war der Grund,
   warum die ganze untere Haelfte sepiafarben und flach war. */
put(
  `<path d="M470 742L1150 742L1360 1000L262 1000Z" fill="url(#spill)" opacity=".4"/>`,
);
/* Der trockene Fleck. Warmes, MATTES Pflaster: kein Glanz, keine Spiegelung,
   sichtbare Fugen — das Gegenteil der nassen Strasse, und dadurch als eigener
   Gegenstand lesbar statt als dunkle Stelle. */
put(`<ellipse cx="800" cy="1016" rx="612" ry="146" fill="url(#dry)"/>`);
for (let i = 0; i < 9; i++) {
  const fy = 926 + i * 11;
  const halb = 300 + i * 36;
  put(
    `<path d="M${n(800 - halb)} ${n(fy)}h${n(halb * 2)}" stroke="#0d0b09" stroke-width="1.2" opacity=".5"/>`,
  );
}
for (let i = 0; i < 7; i++) {
  const jx = 396 + i * 136;
  put(`<path d="M${n(jx)} 934L${n(800 + (jx - 800) * 1.5)} 1000" stroke="#0d0b09" stroke-width="1.2" opacity=".42"/>`);
}
/* Die Nass/Trocken-Kante: wo der Regen aufhoert, hoert der Glanz auf. Ein
   heller Saum, weil das Wasser sich dort sammelt, bevor es versickert. */
put(
  `<path d="M148 1000q104 -84 258 -92q192 -10 306 6q176 14 348 -8q140 -18 252 82" ` +
    `fill="none" stroke="#5d7182" stroke-width="2.6" opacity=".42"/>`,
);
/* Die Tropfen der Traufe schlagen genau auf dieser Kante ein. */
for (let i = 0; i < 22; i++) {
  const tx = 168 + i * 62 + rr(-9, 9);
  const ty = 934 + Math.abs(800 - tx) * 0.055 + rr(-6, 6);
  put(`<ellipse cx="${n(tx)}" cy="${n(ty)}" rx="${n(rr(8, 17))}" ry="${n(rr(2, 3.8))}" fill="#6d8496" opacity="${n(rr(0.22, 0.44))}"/>`);
}

/* ═══ 10 · VORDERGRUND LINKS: DIE PAPIERLATERNE ════════════════════════════ */
put(`<g transform="translate(0,-42)"><g class="lz">`);
put(`<rect x="232" y="196" width="3.4" height="56" fill="#0a0d11"/>`);
put(`<ellipse cx="234" cy="312" rx="150" ry="168" fill="url(#glw)"/>`);
put(`<rect x="204" y="250" width="60" height="7" rx="2" fill="#171410"/>`);
put(`<path d="M204 257q-14 46 0 92h60q14 -46 0 -92Z" fill="${C.laterne}"/>`);
for (let i = 0; i < 5; i++) {
  put(`<rect x="${n(200 - i * 0.6)}" y="${n(266 + i * 16)}" width="${n(68 + i * 1.2)}" height="2.2" fill="#8a6237" opacity=".5"/>`);
}
put(`<rect x="216" y="284" width="36" height="42" fill="#8c4a33" opacity=".72"/>`);
put(`<rect x="204" y="345" width="60" height="7" rx="2" fill="#171410"/>`);
put(`<rect x="228" y="352" width="12" height="15" fill="#171410"/>`);
put(`</g></g>`);
/* Eine Bank und ein abgestellter Schirm im Laternenlicht. */
put(`<rect x="132" y="812" width="196" height="12" rx="4" fill="#241d16"/>`);
put(`<rect x="150" y="824" width="10" height="62" fill="#1c1610"/>`);
put(`<rect x="300" y="824" width="10" height="62" fill="#1c1610"/>`);
put(`<path d="M338 886l16 -96" stroke="#2a2119" stroke-width="7" fill="none" stroke-linecap="round"/>`);

/* ═══ 11 · VORDERGRUND RECHTS: DER AUTOMAT ═════════════════════════════════
   Das kalte Gegengewicht zur Laterne. Warme Geborgenheit links, kaltes
   Fremdlicht rechts, und dazwischen die Gasse. */
put(`<ellipse cx="1358" cy="672" rx="210" ry="238" fill="url(#glk)"/>`);
put(`<rect x="1286" y="536" width="146" height="272" rx="5" fill="#10161c"/>`);
put(`<rect x="1294" y="546" width="130" height="150" fill="${C.fensterKalt}" opacity=".9"/>`);
for (let r = 0; r < 4; r++) {
  for (let c = 0; c < 5; c++) {
    put(
      `<rect x="${n(1300 + c * 25)}" y="${n(552 + r * 36)}" width="19" height="29" rx="2" ` +
        `fill="${pick(['#b4703f', '#5f8f6a', '#8aa8bd', '#a8592f', '#7f93a8'])}" opacity=".92"/>`,
    );
  }
}
put(`<rect x="1294" y="704" width="130" height="46" fill="#0b1015"/>`);
put(`<rect x="1302" y="714" width="52" height="26" fill="${C.fensterKaltTief}" opacity=".8"/>`);
lichter.push({ x: 1294, y: 546, w: 130, h: 150, warm: false });
/* Die Spiegelung des Automaten: der laengste Streifen im Bild. */
put(`<rect class="s2" x="1276" y="712" width="164" height="242" fill="${C.spiegelKalt}" opacity=".44" mask="url(#fade)"/>`);
put(`<rect x="1276" y="712" width="164" height="242" fill="url(#kr)" opacity=".9"/>`);

/* ═══ 12 · DAS VORDACH — DER RAHMEN DER SPALTE ═════════════════════════════
   Es liegt ganz vorn und ganz oben: die dunkelste Flaeche des Bildes, und
   damit genau dort, wo Uhrzeit und Begruessung stehen. Dass die Spalte oben
   dunkel ist, ist hier kein Mangel, sondern das Motiv. */
/* Die erste Fassung machte es 186 px tief und fuellte es mit einem Ton — im
   Bild war das ein schwarzer Balken über dem oberen Fuenftel, kein Vordach.
   Jetzt: 132 px, und die Unterseite ist GEBAUT. Die Sparren laufen in
   Fluchtlinie auf einen Punkt hinter dem Betrachter zu (an der Vorderkante
   stehen sie weiter auseinander als hinten an der Wand), die Pfetten liegen
   quer darüber, und das Laternenlicht streift von links über das Holz.
   Dass die Spalte hier oben dunkel ist, ist damit kein Mangel mehr, sondern
   der Grund, aus dem man trocken steht. */
const VD = 132;
const SAG = 22;
put(`<path d="M0 0h${W}v${VD}q-${W / 2} ${SAG} -${W} 0Z" fill="${C.vordach}"/>`);
/* Das Holz nimmt links das Laternenlicht an. */
put(`<ellipse cx="228" cy="${VD}" rx="300" ry="128" fill="${C.vordachLicht}" opacity=".62"/>`);
put(`<ellipse cx="1352" cy="${VD}" rx="230" ry="104" fill="#12181e" opacity=".5"/>`);
/* Sparren in Flucht. */
for (let i = 0; i <= 17; i++) {
  const vorn = -60 + i * ((W + 120) / 17);
  const hinten = W / 2 + (vorn - W / 2) * 0.72;
  const sag = SAG * (1 - Math.abs(vorn - W / 2) / (W / 2)) ** 1.4;
  put(
    `<path d="M${n(hinten - 4)} 0L${n(vorn - 6)} ${n(VD + sag)}L${n(vorn + 6)} ${n(VD + sag)}L${n(hinten + 4)} 0Z" ` +
      `fill="#161c22" opacity=".7"/>`,
  );
}
/* Pfetten quer: nach hinten enger, das macht die Tiefe. */
for (let i = 0; i < 6; i++) {
  const t = (i + 1) / 7;
  const py = VD * (t ** 1.9);
  const dip = SAG * (t ** 1.9);
  put(
    `<path d="M0 ${n(py)}q${n(W / 2)} ${n(dip)} ${n(W)} 0" fill="none" stroke="#212930" stroke-width="${n(1.4 + t * 2.2)}" opacity=".6"/>`,
  );
}
/* Die Traufkante: das Stirnbrett, und darunter die nasse Lippe, an der das
   Wasser sammelt und abreisst. Die schaerfste Kante des Bildes. */
put(`<path d="M0 ${VD}q${W / 2} ${SAG} ${W} 0v14q-${W / 2} ${SAG} -${W} 0Z" fill="#070a0d"/>`);
put(
  `<path d="M0 ${VD}q${W / 2} ${SAG} ${W} 0" fill="none" stroke="#6b8193" stroke-width="2.2" opacity=".5"/>`,
);
put(
  `<path d="M0 ${VD + 14}q${W / 2} ${SAG} ${W} 0" fill="none" stroke="#8ea6b8" stroke-width="2.8" opacity=".72"/>`,
);
/* Haengende Tropfen an der Kante: der Moment vor dem Fall. */
for (let i = 0; i < 26; i++) {
  const tx = 22 + i * 61 + rr(-8, 8);
  const ty = VD + 14 + SAG * (1 - ((tx - W / 2) / (W / 2)) ** 2);
  put(`<path d="M${n(tx)} ${n(ty)}q3.6 5.6 0 11.2q-3.6 -5.6 0 -11.2Z" fill="#9db3c4" opacity="${n(rr(0.36, 0.72))}"/>`);
}

put(`</svg>`);

const svg = out.join('');
const bytes = Buffer.byteLength(svg, 'utf8');
/* Regie-Lektion 2: ein `- -` in einem SVG-Kommentar laesst Chrome die GANZE
   Datei still verwerfen. Dieses Bild hat gar keine Kommentare — der Riegel
   sorgt dafuer, dass das so bleibt. */
if (svg.includes('<!')) {
  console.error('✗ Kommentar/Deklaration im SVG — Regie-Lektion 2, bitte entfernen.');
  process.exit(1);
}
if (bytes > BUDGET) {
  console.error(`✗ ${bytes} B über dem Budget von ${BUDGET} B.`);
  process.exit(1);
}
writeFileSync(OUT, svg);
console.log(`✓ ${OUT}`);
console.log(`  ${bytes} B  (${((bytes / BUDGET) * 100).toFixed(1)} % des 80-KB-Budgets)`);
console.log(`  ${out.length} Knoten · ${lichter.length} Lichtquellen mit Spiegelung`);
