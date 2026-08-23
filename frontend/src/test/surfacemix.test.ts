import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

// ═════════════════════════════════════════════════════════════════════════════
//  surfacemix.test — der Glas-Grad des Hauses ist EINE Zahl
//  (Andi 22.08.: „ich möchte den inneren teil etwas transparenter, damit man
//   den hintergrund etwas besser sieht. mach bitte auch die hintergründe der
//   widgets etwas transparenter.")
//
//  WAS HIER WIRKLICH GERIEGELT WIRD, ist nicht die Zahl 86, sondern dass es nur
//  EINE gibt. Vor dieser Runde stand `86%` zweimal als Literal im CSS (Nav-Insel
//  und Fußleiste); käme jetzt für jede Fläche eine weitere dazu, hätte Andis
//  nächstes „etwas transparenter" sechs Fundstellen statt einer — und beim
//  Feinschliff liefen sie garantiert auseinander. Der Auftrag sagt es wörtlich:
//  „eine Wahrheit, nicht 16 Einzel-CSS-Hacks".
//
//  Idiom wie `onewindow.test.ts`: die AUSGELIEFERTEN Dateien lesen. jsdom
//  rechnet kein Layout und löst kein `color-mix()` auf — die Deckkraft selbst
//  ist darum NICHT hier bewiesen, sondern gemessen:
//  `tools/theme-contrast/flaechen.mjs` legt eine Kachel über die volle
//  Lesespalte und sucht über allen 16 Szenen den schlechtesten Bildpunkt.
// ═════════════════════════════════════════════════════════════════════════════

/** CSS ohne Kommentare — sonst zählt ein Prozentwert im Fließtext als Regel mit. */
const strip = (css: string): string => css.replace(/\/\*[\s\S]*?\*\//g, '');

const INDEX_CSS = strip(readFileSync('src/index.css', 'utf8'));
const VOICEBAR_CSS = strip(readFileSync('src/styles/voicebar.css', 'utf8'));
const THEMES_CSS = strip(readFileSync('src/styles/themes.css', 'utf8'));

const escapeRe = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const bodies = (css: string, selector: string, file: string): string => {
  const re = new RegExp(`(?:^|\\n)[ \\t]*${escapeRe(selector)}\\s*\\{([^}]*)\\}`, 'g');
  const found = [...css.matchAll(re)].map((m) => m[1]);
  expect(found.length, `Selektor \`${selector}\` fehlt in ${file}`).toBeGreaterThan(0);
  return found.join('\n');
};

const idx = (selector: string) => bodies(INDEX_CSS, selector, 'src/index.css');
const vb = (selector: string) => bodies(VOICEBAR_CSS, selector, 'src/styles/voicebar.css');

/** Die Flächen, die über einer Szene liegen — sie alle tragen denselben Grad. */
const GLAS_FLAECHEN: ReadonlyArray<readonly [string, (s: string) => string]> = [
  ['.nav', idx], // die schwebende Insel oben
  ['.homefoot', idx], // die Fußleiste unter dem Orb
  ['.hero', idx], // der eine große Zustand der Übersicht
  ['.tile', idx], // JEDES Widget — Übersicht wie Zuhause (`.idle__tile` erbt)
  ['.compose__bar', idx], // die zentrale Fläche des Chat-Reiters
];

describe('Glas-Grad — eine Zahl für alle Flächen über der Szene', () => {
  it('`--surface-mix` ist genau EINMAL deklariert und ist ein Prozentwert', () => {
    // Genau einmal: zwei Deklarationen wären wieder zwei Wahrheiten, nur
    // besser versteckt als die zwei Literale vorher.
    const decls = [...INDEX_CSS.matchAll(/--surface-mix:\s*([^;]+);/g)].map((m) => m[1].trim());
    expect(decls, 'Deklarationen von --surface-mix in index.css').toHaveLength(1);
    // Er MUSS eine <percentage> sein: er wird direkt in die Mischungsstelle von
    // `color-mix()` substituiert. Eine bloße Zahl (0.86) wäre dort ungültig und
    // ließe die ganze Deklaration still fallen — der Fallback darüber sähe dann
    // wie „hat funktioniert" aus, nur eben deckend.
    expect(decls[0]).toMatch(/^\d+(\.\d+)?%$/);
    // Kein Theme darf ihn still umbiegen: dann wäre der gemessene AA-Beweis
    // (flaechen.mjs, alle 16 Szenen bei EINEM Grad) für dieses Theme wertlos.
    expect(THEMES_CSS).not.toMatch(/--surface-mix:/);
  });

  it('jede Fläche über der Szene liest den Token — und keine schreibt ihre eigene Zahl', () => {
    for (const [sel, read] of GLAS_FLAECHEN) {
      const css = read(sel);
      expect(css, `${sel} liest --surface-mix nicht`).toMatch(
        /background:\s*color-mix\(in oklab,\s*var\(--bg-surface\)\s*var\(--surface-mix\),\s*transparent\)/,
      );
      // Der Fallback DAVOR ist Teil der Zusage: ein Browser ohne `color-mix`
      // verwirft die zweite Zeile und zeigt die ruhige, deckende Fläche —
      // niemals gar keinen Grund (der Text stünde dann direkt auf der Szene).
      expect(css, `${sel} hat keinen deckenden Fallback vor der Mischung`).toMatch(
        /background:\s*var\(--bg-surface\)\s*;[\s\S]*color-mix/,
      );
      // Und keine zweite Wahrheit: kein Literal-Prozent in einer Mischung.
      expect(css, `${sel} mischt mit einer eigenen Zahl statt mit dem Token`).not.toMatch(
        /color-mix\([^)]*var\(--bg-surface\)\s*\d/,
      );
    }
  });

  it('modale Flächen bleiben deckend — Durchblick endet, wo etwas zudecken soll', () => {
    // Cowork-Fund 2026-07-02: halb durchsichtiger Panel-Text über den Kacheln
    // ist zwei Texte übereinander. Drawer, Ops-Panel und die Bedienelemente der
    // Edit-Schicht (sie LIEGEN auf einer Kachel) tragen darum weiterhin die
    // volle Fläche. Das ist eine Entscheidung, kein Vergessen.
    // (`.idle__tray` stand hier bis zum 22.08. — das Fach „Verfügbar" ist auf
    // Andis Zuruf gefallen, ein- und ausschalten tun die Einstellungen.)
    //
    // `.voiceorb__card` ist am 23.08. von OBEN nach hier gewandert: solange die
    // Sprech-Blase im Fluss unter dem Orb stand, hatte sie nur die Szene hinter
    // sich und trug zu Recht den Glas-Grad. Seit sie als Schicht ÜBER der Bühne
    // liegt (sonst verschob sie alle Kacheln), hätte sie den Wetter-Text hinter
    // dem Antworttext. Sie fällt damit unter dieselbe Regel wie der Drawer.
    for (const [sel, read] of [
      ['.settings', () => bodies(THEMES_CSS, '.settings', 'src/styles/themes.css')],
      ['.ops__panel', idx],
      // (`.idle__editbar` stand hier bis zum 23.08. — die Edit-Leiste ist auf
      // Andis Zuruf gefallen; ihr Platz in dieser Liste ist mit ihr weg.)
      ['.idle__sizerrow', idx],
      ['.voiceorb__card', vb],
    ] as const) {
      expect(read(sel), `${sel} ist durchscheinend geworden`).not.toMatch(/--surface-mix/);
    }
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  Rahmen-Grad — dieselbe Bauart eine Schicht weiter außen
//  (Andi 23.08.: „Und mach bitte die Rahmen um die Widgets noch transparenter.")
//
//  Geriegelt wird wieder nicht die Zahl, sondern dass es EINE gibt. Und der
//  Rückfall: ohne `color-mix` MUSS die volle Linie stehen bleiben — eine Kachel
//  ohne jede Kante zerfällt in die Szene. Die Deckkraft selbst ist auch hier
//  nicht in jsdom bewiesen, sondern gemessen:
//  `tools/theme-contrast/rahmen.mjs` legt eine echte `.tile` über die echte
//  Szene und misst den Kontrast der Linie gegen beide Seiten, über alle Szenen.
// ═════════════════════════════════════════════════════════════════════════════
describe('Rahmen-Grad — eine Zahl für alle Widget-Kanten', () => {
  it('`--hairline-mix` ist genau EINMAL deklariert und ist ein Prozentwert', () => {
    const decls = [...INDEX_CSS.matchAll(/--hairline-mix:\s*([^;]+);/g)].map((m) => m[1].trim());
    expect(decls, 'Deklarationen von --hairline-mix in index.css').toHaveLength(1);
    // Wie bei --surface-mix: eine bloße Zahl wäre in der Mischungsstelle von
    // `color-mix()` ungültig und ließe die Deklaration still fallen.
    expect(decls[0]).toMatch(/^\d+(\.\d+)?%$/);
    // Kein Theme biegt ihn um — sonst wäre die Messung über alle Szenen bei
    // EINEM Grad für dieses Theme wertlos.
    expect(THEMES_CSS).not.toMatch(/--hairline-mix:/);
  });

  it('der Widget-Rahmen liest den Token — mit deckender Linie als Rückfall', () => {
    const css = idx('.tile');
    expect(css, '.tile liest --hairline-mix nicht').toMatch(
      /border-color:\s*color-mix\(in oklab,\s*var\(--bg-hairline\)\s*var\(--hairline-mix\),\s*transparent\)/,
    );
    expect(css, '.tile hat keine deckende Linie als Rückfall').toMatch(
      /border:\s*1px solid var\(--bg-hairline\)\s*;[\s\S]*border-color:\s*color-mix/,
    );
  });
});
