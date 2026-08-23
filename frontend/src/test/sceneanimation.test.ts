import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

// ═════════════════════════════════════════════════════════════════════════════
//  SCENE MOTION (2026-08-14) — Ukiyo breathes, Asagiri drifts
//
//  Andi's brief: the scenes may move, but only on a minute scale, and the
//  picture must never turn into wallpaper. That leaves four promises which are
//  invisible in a screenshot and easy to break by a later "small" edit — this
//  file pins exactly those, straight from the shipped CSS (same idiom as the
//  CSS bolt in themegroups.test.tsx / nagori.test.tsx):
//
//   1. PERFORMANCE — compositor properties only (transform/translate/scale/
//      opacity), no filter, no background-position, no width. Two animated
//      nodes per scene, not more; the iPad renders this 24/7.
//   2. SLOWNESS — every period ≥ 20 s, and the periods of one scene are
//      pairwise different, otherwise the loop becomes visible.
//   3. REDUCED MOTION — every animated node is switched off in the file's own
//      gate (these files are loaded at runtime and must be correct alone).
//   4. READABILITY — the measured contrast contracts stay untouched: Ukiyo's
//      print/veil opacities are not animated, its spray is masked out of the
//      app column with slack for its own drift, and Asagiri's scene layer
//      (which carries the veil) does not move at all.
// ═════════════════════════════════════════════════════════════════════════════

/** Comments may legally mention selectors and old values — parse RULES only. */
const strip = (css: string): string => css.replace(/\/\*[\s\S]*?\*\//g, '');

const UKIYO = strip(readFileSync('public/themes/ukiyo.css', 'utf8'));
const ASAGIRI = strip(readFileSync('public/themes/asagiri.css', 'utf8'));
const SCENES: Array<[string, string]> = [
  ['ukiyo', UKIYO],
  ['asagiri', ASAGIRI],
];

/** The balanced `{…}` block that starts at or after `from`, without braces. */
function blockAt(css: string, from: number): string {
  const open = css.indexOf('{', from);
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth += 1;
    else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) return css.slice(open + 1, i);
    }
  }
  throw new Error(`unbalanced block at ${from}`);
}

/** Declaration block of the FIRST rule whose selector text contains `selector`. */
function rule(css: string, selector: string): string {
  const at = css.indexOf(selector);
  expect(at, selector).toBeGreaterThan(-1);
  return blockAt(css, at);
}

/** `@keyframes` of a file, by name. */
function keyframes(css: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of css.matchAll(/@keyframes\s+([\w-]+)/g)) out[m[1]] = blockAt(css, m.index ?? 0);
  return out;
}

/** Every `animation:` shorthand that names a keyframe (i.e. not `animation: none`). */
const animationDecls = (css: string): string[] =>
  Array.from(css.matchAll(/animation:\s*([^;]+);/g), (m) => m[1].replace(/\s+/g, ' ').trim()).filter(
    (decl) => decl !== 'none',
  );

/** The seconds in an `animation:` shorthand — one per named keyframe. */
const periods = (css: string): number[] =>
  animationDecls(css).flatMap((d) => Array.from(d.matchAll(/(\d+(?:\.\d+)?)s\b/g), (m) => +m[1]));

// ─────────────────────────────────────────────────────────────────────────────
//  1) Performance — compositor properties, few nodes
// ─────────────────────────────────────────────────────────────────────────────

describe('Bewegte Szenen — nur GPU-Eigenschaften, wenige Knoten', () => {
  const ALLOWED = ['transform', 'translate', 'scale', 'rotate', 'opacity'];

  it.each(SCENES)('%s: kein Keyframe rührt etwas an, das ein Layout/Paint auslöst', (id, css) => {
    const frames = keyframes(css);
    expect(Object.keys(frames).length, id).toBeGreaterThan(0);
    for (const [name, body] of Object.entries(frames)) {
      const props = Array.from(body.matchAll(/([a-z-]+)\s*:/g), (m) => m[1]);
      expect(props.length, name).toBeGreaterThan(0);
      for (const prop of props) expect(ALLOWED, `${id}/${name}: ${prop}`).toContain(prop);
    }
  });

  it.each(SCENES)('%s: genau zwei animierte Knoten (die Szene ist Bild, keine Bühne)', (id, css) => {
    expect(animationDecls(css), id).toHaveLength(2);
  });

  it.each(SCENES)('%s: kein Skript, keine SMIL-Animation in der Szene', (id, css) => {
    // Beides würde die Datei aus dem „reines CSS"-Vertrag heben (und SMIL wäre
    // in einer per background-image geladenen SVG ohnehin ein Rasterisierer).
    expect(css, id).not.toContain('<script');
    expect(css, id).not.toContain('animateTransform');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  2) Langsamkeit — Minuten-Skala, und keine zwei gleichen Uhren
// ─────────────────────────────────────────────────────────────────────────────

describe('Bewegte Szenen — Minuten, keine Sekunden', () => {
  it.each(SCENES)('%s: jede Periode ist ≥ 20 s', (id, css) => {
    const found = periods(css);
    expect(found.length, id).toBeGreaterThanOrEqual(2);
    for (const s of found) expect(s, `${id}: ${s}s`).toBeGreaterThanOrEqual(20);
  });

  it.each(SCENES)('%s: alle Perioden verschieden — sonst ist die Wiederkehr sichtbar', (id, css) => {
    const found = periods(css);
    expect(new Set(found).size, id).toBe(found.length);
  });

  it('Ukiyo atmet auf zwei Uhren (Dünung 73 s, Atem 109 s) plus Gischt — Andi-Regie 14.08.: sichtbar ruhig', () => {
    expect(periods(UKIYO).sort((a, b) => a - b)).toEqual([59, 73, 83, 109]);
  });

  it('Asagiri zieht mit vier Uhren, die Bänke gegenläufig', () => {
    expect(periods(ASAGIRI).sort((a, b) => a - b)).toEqual([97, 131, 165, 236]);
    // Gegenläufig: die nahe Bank startet links, die ferne rechts.
    expect(keyframes(ASAGIRI)['asagiri-drift']).toContain('translate3d(-2.5%');
    expect(keyframes(ASAGIRI)['asagiri-drift-far']).toContain('translate3d(2.9%');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  3) Reduced motion — jede Datei schaltet sich selbst still
// ─────────────────────────────────────────────────────────────────────────────

describe('Bewegte Szenen — eigenes reduced-motion-Gate', () => {
  it.each(SCENES)('%s: das Gate nennt beide animierten Knoten', (id, css) => {
    const gate = rule(css, '@media (prefers-reduced-motion: reduce)');
    expect(gate, id).toContain('animation: none');
    expect(gate, id).toContain('body::before');
    expect(gate, `${id}: die zweite Ebene (:root::after) fehlt im Gate`).toContain(
      `:root[data-theme='${id}']::after`,
    );
  });

  it.each(SCENES)('%s: die Szene bleibt sichtbar — das Gate löscht nichts', (id, css) => {
    const gate = rule(css, '@media (prefers-reduced-motion: reduce)');
    expect(gate, id).not.toContain('display: none');
    expect(gate, id).not.toContain('opacity: 0');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  4) Lesbarkeit — die gerechneten Kontraste bleiben unberührt
// ─────────────────────────────────────────────────────────────────────────────

describe('Ukiyo — der Druck atmet, die Lesefläche nicht', () => {
  const print = rule(UKIYO, ":root[data-theme='ukiyo'] body::before");
  const spray = rule(UKIYO, ":root[data-theme='ukiyo']::after");
  const veil = rule(UKIYO, ":root[data-theme='ukiyo'] body::after");

  it('die zwei Regler der Kontrast-Rechnung stehen unverändert', () => {
    expect(UKIYO).toContain('--ukiyo-print: 0.78');
    expect(UKIYO).toContain('--ukiyo-veil: 0.82');
    expect(print).toContain('opacity: var(--ukiyo-print)');
  });

  it('der Papier-Schleier trägt keine Animation (er ist Lesbarkeit, keine Deko)', () => {
    expect(veil).not.toContain('animation');
    expect(veil).toContain('var(--ukiyo-veil)');
  });

  it('keine der Druck-Animationen rührt die Deckkraft an', () => {
    for (const name of ['ukiyo-swell', 'ukiyo-breath']) {
      expect(keyframes(UKIYO)[name], name).not.toContain('opacity');
    }
  });

  it('die Ruhelage IST der `from`-Frame — ohne Animation springt nichts', () => {
    const swell = keyframes(UKIYO)['ukiyo-swell'];
    const breath = keyframes(UKIYO)['ukiyo-breath'];
    expect(print).toContain('translate: -2% -2.8%');
    expect(swell.slice(0, swell.indexOf('to')), 'swell from').toContain('translate: -2% -2.8%');
    expect(print).toContain('scale: 1.07');
    expect(breath.slice(0, breath.indexOf('to')), 'breath from').toContain('scale: 1.07');
  });

  it('der Rand kann nie aufreißen: |Hub| < Überstand aus der Skalierungs-Untergrenze', () => {
    // Der Layer ist viewport-groß; der Überstand kommt allein aus `scale`. Beide
    // Uhren laufen unabhängig, also muss der SCHLECHTESTE Fall passen (Hub
    // maximal, Skalierung minimal).
    const hub = Math.max(
      ...Array.from(keyframes(UKIYO)['ukiyo-swell'].matchAll(/(-?\d+(?:\.\d+)?)%/g), (m) =>
        Math.abs(+m[1]),
      ),
    );
    const minScale = Math.min(
      ...Array.from(keyframes(UKIYO)['ukiyo-breath'].matchAll(/scale:\s*(\d+(?:\.\d+)?)/g), (m) =>
        Number(m[1]),
      ),
    );
    expect(minScale).toBeGreaterThan(1);
    expect(hub, `Hub ${hub}% vs. Überstand ${((minScale - 1) / 2) * 100}%`).toBeLessThan(
      ((minScale - 1) / 2) * 100,
    );
  });

  it('die Gischt liegt NUR im Seitenraum — mit Reserve für ihre eigene Drift', () => {
    // Die Maske wandert mit dem Element; darum ist die Drift in px begrenzt und
    // die Maske hört 40 px VOR der App-Spalte auf.
    expect(spray).toContain('mask-image');
    expect(spray).toContain('calc(50% - var(--ukiyo-column) - 40px)');
    const drift = keyframes(UKIYO)['ukiyo-spray-drift'];
    const px = Array.from(drift.matchAll(/(-?\d+(?:\.\d+)?)px/g), (m) => Math.abs(+m[1]));
    expect(px.length).toBeGreaterThan(0);
    expect(Math.max(...px), 'Drift muss in der 40-px-Reserve bleiben').toBeLessThan(40);
    // …und sie steht auf der rechten Bildhälfte gar nicht erst (dort ist kein Wasser).
    expect(drift).not.toContain('%');
  });

  it('die Gischt hängt am :root — die Vorschau-Kachel bekommt keine fixe Ebene', () => {
    expect(UKIYO).toContain(":root[data-theme='ukiyo']::after");
    expect(/(^|[^:\w\]])\[data-theme='ukiyo'\]::after/m.test(UKIYO)).toBe(false);
    expect(spray).toContain('z-index: -1');
    expect(spray).toContain('pointer-events: none');
  });
});

describe('Asagiri — nur das Wetter bewegt sich', () => {
  const near = rule(ASAGIRI, ":root[data-theme='asagiri'] body::before");
  const far = rule(ASAGIRI, ":root[data-theme='asagiri']::after");
  const scene = rule(ASAGIRI, ":root[data-theme='asagiri'] body::after");

  it('die Zeichnung steht still (sie trägt den Schleier)', () => {
    expect(scene).not.toContain('animation');
    expect(scene).toContain("url('asagiri-szene.svg')");
    expect(scene).toContain('z-index: -2');
  });

  it('alle vier Bänke existieren weiter — aufgeteilt, nicht neu erfunden', () => {
    const banks = ['at 22% 57%', 'at 44% 79%', 'at 69% 67%', 'at 82% 89%'];
    for (const bank of banks) expect(ASAGIRI.split(bank), bank).toHaveLength(2); // genau 1×
    expect(near).toContain(banks[0]);
    expect(near).toContain(banks[1]);
    expect(far).toContain(banks[2]);
    expect(far).toContain(banks[3]);
  });

  it('beide Ebenen sind deckungsgleich aufgehängt — gleiche Bänke, gleicher Ort', () => {
    for (const layer of [near, far]) {
      expect(layer).toContain('inset: -10% -30%');
      expect(layer).toContain('z-index: -1');
      expect(layer).toContain(
        'mask-image: linear-gradient(to bottom, transparent 26%, #000 56%, #000 100%)',
      );
    }
  });

  it('der Nebel hellt nur auf: volle Deckung ist der `from`-Frame, nie mehr', () => {
    for (const name of ['asagiri-thicken', 'asagiri-thicken-far']) {
      const body = keyframes(ASAGIRI)[name];
      const values = Array.from(body.matchAll(/opacity:\s*(\d+(?:\.\d+)?)/g), (m) => +m[1]);
      expect(values, name).toHaveLength(2);
      expect(Math.max(...values), name).toBe(1);
      expect(Math.min(...values), name).toBeGreaterThan(0.5);
    }
  });
});
