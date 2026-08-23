import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { HOME_COLUMN_MIN_WIDTH_PX, HOME_STAGE_GAP_PX } from '../components/homeLayout';

// ═════════════════════════════════════════════════════════════════════════════
//  onewindow.test — CSS-Riegel der Scheibe S1 „Ein-Fenster-Deckel"
//  (vault/tracks/DESIGN-widgets-settings-2026-08-15.md §2.2 + §4-Zeile S1)
//
//  Idiom wie `ops.test.tsx`/`themegroups.test.tsx`: die AUSGELIEFERTEN Dateien
//  lesen, statt Selektoren im Test abzuschreiben. Der Deckel ist reine Geometrie
//  — es gibt keinen Zustand, den ein Render-Test beobachten könnte, und jsdom
//  rechnet kein Layout. Was hier geprüft wird, sind darum genau die Regeln, an
//  denen die Zusage hängt: „auf Zuhause scrollt das Dokument NIE — und auf
//  keinem anderen Reiter geht dabei das Scrollen verloren."
//
//  Die zweite Hälfte dieser Zusage (der Deckel darf NUR zuhause gelten) ist der
//  eigentliche Grund für diese Datei: ein `overflow:hidden` an der nackten
//  `.app__main`-Regel würde Chat, Räume und Aktivität still unbenutzbar machen
//  — lange Verläufe wären dann einfach abgeschnitten. Genau das riegelt der
//  Test „Basis-Regel ohne overflow" ab.
// ═════════════════════════════════════════════════════════════════════════════

/** CSS ohne Kommentare — sonst zählt Fließtext („kein overflow hier") als Regel mit. */
const strip = (css: string): string => css.replace(/\/\*[\s\S]*?\*\//g, '');

const INDEX_CSS = strip(readFileSync('src/index.css', 'utf8'));
const VOICEBAR_CSS = strip(readFileSync('src/styles/voicebar.css', 'utf8'));
const APP_TSX = readFileSync('src/App.tsx', 'utf8');

const escapeRe = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Alle Deklarationsblöcke EINES Selektors (exakte Schreibweise, mehrere
 * Vorkommen werden zusammengehängt). Fehlt der Selektor ganz, schlägt der Test
 * mit dem Selektornamen fehl statt mit einem leeren String weiterzurechnen.
 *
 * Der Zeilenanfangs-Anker ist hier kein Schönheitsfehler, sondern der Kern des
 * wichtigsten Riegels dieser Datei: ohne ihn läse `.app__main` auch den Rumpf
 * von `.app[data-tab='overview'] .app__main` mit — und ausgerechnet die Frage
 * „steht das overflow an der Basis-Regel oder nur zuhause?" wäre nicht mehr
 * beantwortbar.
 */
const bodies = (css: string, selector: string, file: string): string => {
  const re = new RegExp(`(?:^|\\n)[ \\t]*${escapeRe(selector)}\\s*\\{([^}]*)\\}`, 'g');
  const found = [...css.matchAll(re)].map((m) => m[1]);
  expect(found.length, `Selektor \`${selector}\` fehlt in ${file}`).toBeGreaterThan(0);
  return found.join('\n');
};

const idx = (selector: string) => bodies(INDEX_CSS, selector, 'src/index.css');
const vb = (selector: string) => bodies(VOICEBAR_CSS, selector, 'src/styles/voicebar.css');

describe('S1 — der Deckel sitzt auf Zuhause, und NUR dort', () => {
  it('App.tsx markiert den aktiven Reiter am .app-Element (der Selektor-Anker)', () => {
    // Ohne diesen Marker greift KEINE der Regeln unten — er ist die einzige
    // TSX-Zeile der ganzen Scheibe.
    expect(APP_TSX).toMatch(/className="app"\s+data-tab=\{tab\}/);
  });

  it('.app__main deckelt auf dem Zuhause-Reiter (overflow:hidden)', () => {
    expect(idx(".app[data-tab='overview'] .app__main")).toMatch(/overflow:\s*hidden/);
  });

  it('die BASIS-Regel .app__main bleibt ohne overflow — Chat/Räume/Aktivität scrollen weiter', () => {
    expect(idx('.app__main')).not.toMatch(/overflow/);
  });

  it('SOFORT-FIX 18.08.: die Übersicht trägt keinen eigenen Breiten-Deckel mehr — dieselbe 920er-Spalte wie jeder andere Reiter', () => {
    // Bis 18.08. lüftete `.app[data-tab='overview']` die Breite auf
    // `min(1280px,100vw)` — Andis Korrektur: dieselbe Konstante wie überall,
    // keine eigene Zahl fürs Zuhause. Der Selektor darf für `max-width` gar
    // nicht mehr existieren (nicht nur „leer"), sonst schleicht sich die alte
    // Abweichung über einen zweiten Ort wieder ein.
    expect(idx('.app')).toMatch(/max-width:\s*920px/);
    expect(INDEX_CSS).not.toMatch(/\.app\[data-tab='overview'\]\s*\{[^}]*max-width/);
  });
});

describe('S1 — .idle ist das 2-Zeilen-Grid mit 1fr-Bühne', () => {
  it('ZWEI Zeilen, nur die zweite dehnt sich', () => {
    // BIS 19.08. WAREN ES VIER: die vierte trug die Status-Chips. Die sind zur
    // Fußleiste unter dem Orb geworden (Andi-Bestellung, s. `.homefoot` unten).
    // SEIT W6 SIND ES ZWEI: der Wecker war die dritte, er ist ein
    // Bühnen-Widget geworden (Andi 20.08.). Übrig sind Gruß und Bühne.
    const css = idx('.idle');
    expect(css).toMatch(/display:\s*grid/);
    expect(css).toMatch(/grid-template-rows:\s*auto\s+1fr\s*;/);
    // Der Riegel gegen ein „wir lassen die Zeile leer stehen": eine leere
    // `auto`-Zeile ist 0 px hoch, ihre Grid-LÜCKE aber nicht. Kopf→Bühne wäre
    // bei 40 statt 20 px hängen geblieben — die Hälfte des Gewinns futsch,
    // ohne dass man im Code sähe, warum.
    expect(css).not.toMatch(/grid-template-rows:\s*auto\s+auto/);
  });

  it('W6: das UNTERE Polster von .idle ist weg — der Orb bringt sein eigenes mit', () => {
    // Andi 20.08.: „Zwischen Tippen zum Sprechen und dem Orb und dem Bereich
    // für die Seiten ist echt viel ungenutzter Platz." Zwischen Bühne und Orb
    // standen zwei Polster übereinander: 4 px hier + 14 px am `.voiceorb`.
    // Ein Abstand braucht EINEN Besitzer; das ist der Orb-Block.
    expect(idx('.idle')).toMatch(/padding:\s*clamp\(10px,\s*2\.4vh,\s*20px\)\s+0\s+0\s*;/);
  });

  it('.idle darf im Fenster wachsen UND schrumpfen (flex:1 + min-height:0)', () => {
    const css = idx('.idle');
    expect(css).toMatch(/flex:\s*1\s+1\s+auto/);
    // Ohne min-height:0 wächst ein Flex-Kind nie unter seine Inhaltshöhe —
    // der Deckel würde dann außen abschneiden statt innen zu verteilen.
    expect(css).toMatch(/min-height:\s*0/);
  });

  it('die Chips brauchen kein align-self mehr — sie sind kein Grid-Kind von .idle mehr', () => {
    // Der frühere Riegel (`align-self:start`) verhinderte, dass die Chips in
    // einer leeren `1fr`-Bühnenzeile schweben. Diese Zeile gibt es nicht mehr;
    // die Regel stehen zu lassen wäre toter Code, der eine Mechanik behauptet.
    expect(idx('.idle__chips')).not.toMatch(/align-self/);
  });
});

describe('Fußleiste — die Statusmeldung sitzt unten und trägt den Boden (Andi 19.08.)', () => {
  it('.homefoot ist das Echo der Nav-Insel: dieselbe Glas-Formel, derselbe Radius', () => {
    const foot = idx('.homefoot');
    const nav = idx('.nav');
    for (const decl of [
      /border:\s*1px\s+solid\s+var\(--bg-hairline\)/,
      /border-radius:\s*18px/,
      // Seit 22.08. steht die 86 nicht mehr zweimal im CSS, sondern einmal als
      // `--surface-mix` (s. `surfacemix.test.ts`) — dieselbe Zahl, jetzt die
      // des ganzen Hauses. Die Zusage dieses Riegels ist unverändert: Leiste
      // oben und Leiste unten tragen DIESELBE Formel.
      /background:\s*color-mix\(in oklab, var\(--bg-surface\) var\(--surface-mix\), transparent\)/,
      /backdrop-filter:\s*blur\(14px\)\s+saturate\(1\.1\)/,
    ]) {
      expect(foot, `die Leiste unten weicht von der Leiste oben ab: ${decl}`).toMatch(decl);
      expect(nav, `die Leiste oben trägt ${decl} nicht (mehr)`).toMatch(decl);
    }
  });

  it('der Schatten der Fußleiste fällt nach OBEN (sie liegt auf dem Fensterboden)', () => {
    expect(idx('.homefoot')).toMatch(/box-shadow:\s*0\s+-8px/);
    // Die Nav wirft ihren nach unten — sonst wären es zwei gleiche Leisten
    // statt einer Leiste und ihres Gegenstücks.
    expect(idx('.nav')).toMatch(/box-shadow:\s*0\s+8px/);
  });

  it('die Fußleiste wird nicht gequetscht — die Bühne bleibt die dehnbare Fläche', () => {
    expect(idx('.homefoot')).toMatch(/flex:\s*none/);
  });

  it('ihre LINKE Kante liegt auf der Nav-Kante (dieselbe clamp-Zahl, gegen das Polster gerechnet)', () => {
    // `.app__main` hat 20px Innenpolster, `.nav` einen clamp-Außenrand — nur
    // die Differenz setzt beide Leisten auf eine Flucht.
    // Seit W6 gilt das NUR noch links: rechts endet die Pille am Wortende
    // (s. der Test darunter), also steht dort `0` statt der clamp-Differenz.
    // W6b: die 10 px oben heißen jetzt `--home-orb-gap` (s. die Rhythmus-
    // Gruppe unten). Die LINKE Zahl — um die es hier geht — ist unberührt.
    expect(idx('.homefoot')).toMatch(
      /margin:\s*var\(--home-orb-gap,\s*10px\)\s+0\s+2px\s+calc\(clamp\(8px,\s*2vw,\s*16px\)\s*-\s*20px\)/,
    );
    expect(idx('.nav')).toMatch(/margin:\s*10px\s+clamp\(8px,\s*2vw,\s*16px\)/);
  });

  it('W6: die Fußleiste ist so breit wie ihre Wörter — nicht wie die Spalte', () => {
    // Andi 20.08.: „Die Leiste unten mit ● online · Stimme: lokal soll nicht
    // über die komplette Breite gehen; es reicht leicht dezent auf der Länge
    // von den Worten dort." Vorher: 888 px Glas bei 1366 px Fenster für ~170 px
    // Wörter (Sonde `shots/w6/vorher-messung.json`, footWidthPct 65 bzw. 96).
    const foot = idx('.homefoot');
    // `.app__main` ist eine Flex-SPALTE: Kinder werden quer gestreckt. Wer sich
    // davon ausnimmt, wird so breit wie sein Inhalt — das ist die ganze Zusage.
    expect(foot).toMatch(/align-self:\s*start/);
    expect(idx('.app__main')).toMatch(/flex-direction:\s*column/);
    // Der Riegel nach oben: im schmalen Fenster darf sie die Spalte füllen,
    // aber nie darüber hinausragen.
    expect(foot).toMatch(/max-width:\s*100%/);
    // Und KEINE feste Breite: die Wörter sind je Sprache verschieden lang, und
    // ohne `voice`-Feld fehlt der zweite Chip ganz. Jede Zahl wäre irgendwo falsch.
    expect(foot).not.toMatch(/\n\s*width:/);
  });

  it('der Orb ist unten VERANKERT, nicht nur zufällig unten', () => {
    // `.idle` mit `flex:1` schob ihn bisher nach unten — eine Verankerung aus
    // zweiter Hand. `margin-top:auto` ist die Zusage selbst.
    expect(vb('.voiceorb')).toMatch(/margin-top:\s*auto/);
  });

  it('unter dem Orb steht die Leiste, nicht mehr sein eigenes Polster', () => {
    // 19.08. gab der Block 12 → 4 px ab, W6 die letzten 4 → 0: die Fuge zur
    // Fußleiste hat jetzt genau EINEN Besitzer, und das ist die Fußleiste.
    expect(vb('.voiceorb')).toMatch(/padding:\s*var\(--home-orb-gap,\s*10px\)\s+16px\s+0\s*;/);
  });
});

describe('S1 — Uhr und Orb sind höhenbewusst', () => {
  it('die Uhr-clamp trägt die 16vh-Komponente', () => {
    const css = idx('.idle__clock');
    expect(css).toMatch(/font-size:\s*clamp\([^;]*min\(12vw,\s*16vh\)[^;]*\)/);
  });

  /**
   * **22.08. — aus höhenbewusst wird KACHEL-bewusst.** Andi: „Die Uhr sieht in
   * klein etwas verloren aus … sich dynamisch auf der größe des widgets
   * anpasst."
   *
   * Die S1-Regel oben bleibt Wort für Wort stehen und wird hier NICHT ersetzt:
   * sie ist der Rückfall für jeden Browser ohne Container-Queries. Was neu
   * dazukommt, ist die Bezugsgröße — nicht mehr das Fenster, sondern die
   * Kachel. Der Unterschied ist keine Kosmetik: dieselbe S-Kachel ist
   * 285 × 146 px, wenn drei Zeilen auf die Seite passen, und 285 × 621 px,
   * wenn nur eine passt; das Fenster ist beide Male dasselbe.
   */
  it('22.08.: jede Kachel ist ein Container — sonst fällt JEDE cq-Einheit still aufs Fenster zurück', () => {
    const css = idx('.idle__tile');
    expect(css).toMatch(/container-type:\s*size/);
    expect(css).toMatch(/container-name:\s*kachel/);
  });

  it('22.08.: die Uhr-Typo hängt an EINER Quelle je Stufe (--clockfs), und die an der Kachel', () => {
    // Drei Stufen, drei Budgets — jedes aus cqw/cqh, keines aus vw/vh.
    for (const selector of [
      '.idle__clocktile[data-step]',
      ".idle__clocktile[data-step='S']",
      ".idle__clocktile[data-step='M']",
    ]) {
      const css = idx(selector);
      expect(css, `${selector} setzt kein --clockfs`).toMatch(/--clockfs:/);
      expect(css, `${selector} misst am Fenster statt an der Kachel`).toMatch(/cq[wh]/);
      expect(css, `${selector} misst noch am Fenster`).not.toMatch(/\dv[wh]/);
    }
    // Ziffern UND Datum lesen dieselbe Variable — zwei Zahlen, die
    // auseinanderlaufen könnten, gibt es nicht.
    expect(idx('.idle__clocktile[data-step] .idle__clock')).toMatch(/font-size:\s*var\(--clockfs\)/);
    expect(idx('.idle__clocktile[data-step] .idle__clockdate')).toMatch(/var\(--clockfs\)/);
  });

  it('22.08.: der Sonnenbogen misst ebenfalls an SEINER Kachel — daher kam der 19-px-Überlauf', () => {
    // Die Basis-Regel (13vh) bleibt als Rückfall stehen; der Nachfahren-
    // Selektor ist nötig, weil sie WEITER UNTEN in der Datei steht.
    expect(idx('.idle__sunarcplot')).toMatch(/height:\s*clamp\(56px,\s*13vh,\s*112px\)/);
    expect(idx('.idle__clocktile .idle__sunarcplot')).toMatch(/height:\s*clamp\(56px,\s*30cqh,\s*112px\)/);
  });

  /**
   * **22.08. — dasselbe fürs Wetter** („beim wetter ist das beim wetter
   * widgets auch"). Jede Stufe misst jetzt an ihrer Kachel; die Basis-Regeln
   * mit `vw` bleiben als Rückfall stehen.
   *
   * Am krassesten war der Fenster-Bezug bei XL: auf dem schmalen iPad (834 px)
   * stand die Lage-Zeile auf 28 px, auf dem breiten Laptop auf 40 px — obwohl
   * die XL-Kachel dort 794 px breit ist und hier 880 px. Fast gleich große
   * Kacheln, 43 % Typo-Unterschied, allein wegen des Fensters dahinter.
   */
  it('22.08.: jede Wetter-Stufe misst an der Kachel — S, M, L und XL', () => {
    for (const step of ['S', 'M', 'L', 'XL']) {
      const css = idx(`.idle__now[data-size='${step}'] .idle__nowcond`);
      expect(css, `Stufe ${step} misst nicht an der Kachel`).toMatch(/cq[wh]/);
    }
    // Und der RÜCKFALL bleibt: `bodies()` hängt alle Vorkommen eines Selektors
    // aneinander, also steht hier beides — die alte `vw`-Regel für Browser
    // ohne Container-Queries UND die neue `cqh`-Regel im `@container`-Block.
    // Verschwände die erste, fiele Safari < 16 auf eine 16-px-Grundschrift
    // zurück, ohne dass es jemandem auffiele.
    expect(idx(".idle__now[data-size='XL'] .idle__nowcond")).toMatch(
      /font-size:\s*clamp\(26px,\s*3\.4vw,\s*40px\)/,
    );
    expect(idx('.idle__nowcond')).toMatch(/font-size:\s*clamp\(22px,\s*4vw,\s*32px\)/);
  });

  it('22.08.: jeder Kachel-Faktor hat Boden UND Deckel — eine 621-px-Kachel darf kein Plakat werden', () => {
    // Ausnahme mit Absicht: S und M tragen EINE bzw. zwei Zeilen, dort ist das
    // `min(cqw, cqh)`-Paar selbst der Deckel (die kleinere Achse gewinnt).
    for (const selector of [
      ".idle__now[data-size='L'] .idle__nowcond",
      ".idle__now[data-size='L'] .idle__nowspan",
      ".idle__now[data-size='L'] .idle__nowline--sun",
      ".idle__now[data-size='XL'] .idle__nowcond",
    ]) {
      expect(idx(selector), `${selector} ohne clamp`).toMatch(/clamp\(\s*\d/);
    }
    for (const selector of [
      ".idle__now[data-size='S'] .idle__nowcond",
      ".idle__now[data-size='M'] .idle__nowcond",
    ]) {
      expect(idx(selector), `${selector} ohne min()-Paar`).toMatch(/min\(\s*[\d.]+cqw,\s*[\d.]+cqh\)/);
    }
  });

  it('der Home-Orb deckelt bei 13,5vh, hält 88px Fingerziel und behält seine Proportionen', () => {
    // KOMPOSITION V2 (15.08.) — bewusst geändert gegenüber S1: Andi am echten
    // iPad: „mach den Orb etwas kleiner und dezenter". Aus `min(172px,22vh)`
    // wurde `clamp(96px,18vh,132px)`; der `clamp()`-Boden ist keine Kosmetik,
    // der alte `min()` konnte auf einem flachen Fenster unter die Tap-Ziel-
    // Schwelle fallen.
    // KOMPOSITION V3 (22.08., Andi: „Der Orb nimmt fast ein Viertel des Bildes
    // ein. Mach das bitte kompakter.") → `clamp(88px,13.5vh,104px)`. Gemessen
    // wurde der BLOCK, nicht der Kreis (`tools/zuhause-probe/orb-flaeche.mjs`):
    // 22,6 % der Fensterhöhe bei 1194×745 vorher, 18,1 % nachher.
    // BEIDE Terme mussten mit: bei 745 px Höhe deckelte die 18vh (134 px), nicht
    // die 132 — den Deckel allein zu senken hätte dort nichts bewirkt.
    // Der Boden bleibt beim Doppelten des 44-px-Fingerziels.
    const css = vb('.voiceorb__tap');
    expect(css).toMatch(/--orb-size:\s*clamp\(88px,\s*13\.5vh,\s*104px\)/);
    // Kern/Ring/Bloom hängen an derselben Größe — sonst wüchse der Bloom auf
    // einem flachen Viewport über seinen eigenen Orb hinaus.
    for (const v of ['--orb-w', '--orb-h', '--orb-core', '--orb-ring', '--orb-bloom']) {
      expect(css, `${v} hängt nicht an --orb-size`).toMatch(
        new RegExp(`${escapeRe(v)}:[^;]*var\\(--orb-size\\)`),
      );
    }
  });

  it('der Orb-Block wird nicht gequetscht (die Bühne ist die dehnbare Fläche)', () => {
    expect(vb('.voiceorb')).toMatch(/flex:\s*none/);
  });

  it('die Deko-Ebenen des Home-Orbs sind gedämpft — die Compose-Bar bleibt unberührt', () => {
    // Komposition v2 („dezenter"): NUR der Home-Orb setzt `--orb-deco` herunter.
    // Der Riegel hat zwei Hälften, und die zweite ist die wichtigere:
    //  1. der Home-Orb dämpft wirklich (Wert < 1),
    //  2. `.vc-orb` selbst deklariert `--orb-deco` NICHT — eine eigene
    //     Deklaration dort würde den geerbten Wert überschreiben und die
    //     Dämpfung still wirkungslos machen (der Default lebt als
    //     `var(--orb-deco, 1)` an den Nutzungsstellen).
    const tap = vb('.voiceorb__tap');
    const deco = /--orb-deco:\s*([\d.]+)/.exec(tap);
    expect(deco, '--orb-deco fehlt am Home-Orb').not.toBeNull();
    expect(Number(deco![1])).toBeLessThan(1);
    expect(vb('.vc-orb')).not.toMatch(/--orb-deco:/);
    for (const layer of ['.vc-orb__bloom', '.vc-orb__ring']) {
      expect(vb(layer), `${layer} liest --orb-deco nicht`).toMatch(/var\(--orb-deco,\s*1\)/);
    }
    // Der KERN bleibt ungedämpft — er trägt die Zustands-Semantik.
    expect(vb('.vc-orb__core')).not.toMatch(/--orb-deco/);
  });

  it('der Orb-Block gibt Polster an die Bühne zurück (Deko schrumpft, Funktion nicht)', () => {
    // 28/20 → 14/12: die Polster allein waren 48 px des ~249-px-Blocks.
    // 19.08.: unten 12 → 4, weil die Fußleiste den Boden übernommen hat — die
    // OBEREN 14 px waren der Teil, der zur Debatte stand.
    // W6 (Andi 20.08., „mache das enger"): oben 14 → 10 und als BENANNTE
    // Konstante, unten 4 → 0. Die 4 px waren ein zweiter Besitzer für eine
    // Fuge, die schon der Fußleiste gehörte.
    expect(vb('.voiceorb')).toMatch(/padding:\s*var\(--home-orb-gap,\s*10px\)\s+16px\s+0\s*;/);
  });
});

describe('W6 — Flur-Lesbarkeit der Kachel-Zeilen gilt NUR fuer die hohe Stufe', () => {
  /**
   * Andi 20.08.: „Die Höhe der Widgets ist in der Höhe zu groß, grad beim
   * Wetter ist nichts gefüllt." Der Teil davon, der Typo ist: fünf
   * Zusatzzeilen mit 13/14 px und 2 px Abstand — die Schrift einer kleinen
   * Karte, auf einem Bildschirm, der im Flur hängt.
   *
   * Der Riegel hier ist die GRENZE, nicht die Größe. Die M-Stufe hat Andi am
   * 19.08. selbst zugeschnitten (Fakten als zweite Spalte, weil dort die
   * Fläche breit ist und nicht hoch). Wer die neuen Werte später „vereinheit-
   * licht", baut eine abgenommene Komposition um, ohne es zu merken.
   */
  it('die groesseren Wetter-Zeilen haengen an [data-size=L] — S/M/XL bleiben unberuehrt', () => {
    const scoped = /\.idle__now\[data-size='L'\][^{]*\{[^}]*\}/g;
    const rules = INDEX_CSS.match(scoped) ?? [];
    expect(rules.length).toBeGreaterThanOrEqual(3);
    // Die Basis-Regeln selbst sind NICHT angefasst worden.
    expect(idx('.idle__nowprecip')).toMatch(/font-size:\s*13px/);
    expect(idx('.idle__nowspan')).toMatch(/font-size:\s*14px/);
    // …und die M-Stufe hat keine neue Schriftgroesse bekommen.
    expect(INDEX_CSS).not.toMatch(/\.idle__now\[data-size='M'\]\s+\.idle__nowline/);
  });

  it('die Sonnen-Zeile bleibt eine Stufe leiser als ihre Nachbarn (ein Verhaeltnis, kein Wert)', () => {
    // „sehr flurtauglich, sehr klein" (Andi-Auftrag) — sie waechst mit, aber
    // sie ueberholt nie die Zeilen, neben denen sie steht.
    const sun = /\.idle__now\[data-size='L'\]\s+\.idle__nowline--sun\s*\{[^}]*font-size:\s*([\d.]+)px/.exec(INDEX_CSS);
    const line = /\.idle__now\[data-size='L'\][^{]*\.idle__nowline\s*\{[^}]*font-size:\s*([\d.]+)px/.exec(INDEX_CSS);
    expect(sun, 'die L-Regel der Sonnen-Zeile fehlt').not.toBeNull();
    expect(line, 'die L-Regel der Zusatzzeilen fehlt').not.toBeNull();
    expect(Number(sun![1])).toBeLessThan(Number(line![1]));
  });

  it('Sauger/Klima bekommen dieselbe Grenze — ueber [data-size] am Kachel-Rahmen', () => {
    expect(INDEX_CSS).toMatch(/\.idle__tile\[data-size='L'\]\s+\.idle__hometileline/);
    // Die Basis bleibt, was sie war: eine M-Kachel liest sich wie vorher.
    expect(idx('.idle__hometilesub')).toMatch(/font-size:\s*13px/);
    expect(idx('.idle__hometileline')).toMatch(/font-size:\s*18px/);
  });
});

describe('W6 — der Rhythmus unter der Bühne ist ZWEI benannte Zahlen, nicht drei Zufälle', () => {
  /**
   * Andi 20.08., wörtlich: „Zwischen Tippen zum Sprechen und dem Orb und dem
   * Bereich für die Seiten ist echt viel ungenutzter Platz. Mache das in der
   * UI enger."
   *
   * Der Riegel dieser Gruppe ist nicht die Enge — es sind die NAMEN. Drei
   * Fugen, drei Dateien-Stellen (`.idle`, `.voiceorb` in voicebar.css,
   * `.homefoot`), und vorher drei unabhängige Zahlen: 4+14, 8+2, 4+10. Wer
   * eine davon anfasst, verschiebt eine Komposition, ohne die anderen zu
   * sehen. Jetzt hängen sie an zwei Konstanten mit einer Begründung.
   */
  const overview = idx(".app[data-tab='overview'] .app__main");

  it('die beiden Konstanten stehen am Zuhause-Reiter, wo Bühne, Orb und Fußleiste sie erben', () => {
    expect(overview).toMatch(/--home-orb-gap:\s*10px/);
    expect(overview).toMatch(/--home-orb-label-gap:\s*6px/);
  });

  it('die Beschriftung steht ENGER am Orb als der Block an seinen Nachbarn', () => {
    // Der Orb und „Tippen zum Sprechen" sind EIN beschrifteter Knopf. Wären
    // beide Abstände gleich, schwebte die Beschriftung genauso weit vom Orb
    // weg wie von der Fußleiste — man müsste raten, wozu sie gehört.
    const gap = Number(/--home-orb-gap:\s*(\d+)px/.exec(overview)![1]);
    const label = Number(/--home-orb-label-gap:\s*(\d+)px/.exec(overview)![1]);
    expect(label).toBeLessThan(gap);
  });

  it('jede Fuge hat GENAU EINEN Besitzer — keine zwei Polster übereinander', () => {
    // Das war der eigentliche Befund hinter Andis Urteil: zwischen Bühne und
    // Orb standen 4 px (.idle unten) + 14 px (.voiceorb oben), zwischen
    // Hinweis und Fußleiste 4 px (.voiceorb unten) + 10 px (.homefoot oben).
    // Ein Abstand, den zwei Regeln zusammenrechnen, trägt keinen Namen.
    expect(idx('.idle')).toMatch(/padding:\s*clamp\([^)]*\)\s+0\s+0\s*;/);
    expect(vb('.voiceorb')).toMatch(/padding:[^;]*\s0\s*;/);
    expect(vb('.voiceorb__hint')).toMatch(/margin:\s*0\s*;/);
    expect(idx('.homefoot')).toMatch(/margin:\s*var\(--home-orb-gap,\s*10px\)/);
  });

  it('die Nutzungsstellen tragen denselben Wert als Fallback (ein Orb ohne den Reiter steht nicht nackt da)', () => {
    // `.voiceorb` lebt in voicebar.css und kennt den Zuhause-Reiter nicht.
    // Ein `var()` ohne Fallback ergäbe dort `padding: 16px 0` — kein Abstand,
    // und niemand sähe im Code, dass das ein Unfall ist.
    for (const decl of [vb('.voiceorb'), idx('.homefoot')]) {
      expect(decl).toMatch(/var\(--home-orb-gap,\s*10px\)/);
    }
    expect(vb('.voiceorb')).toMatch(/var\(--home-orb-label-gap,\s*6px\)/);
  });
});

describe('Komposition v2 — der Kopf ist EINE linksbündige Gruppe', () => {
  it('Uhr/Gruß und Wetter-Band stehen nebeneinander statt an den Fensterrändern', () => {
    const css = idx('.idle__head');
    expect(css).toMatch(/display:\s*flex/);
    // Das alte `minmax(0,1fr) auto`-Grid war die Ursache von Andis Befund
    // („Uhr und Wetter so weit auseinander") — es darf nicht zurückkommen.
    expect(css).not.toMatch(/grid-template-columns/);
    expect(css).toMatch(/align-items:\s*flex-end/);
    // Feste, fluide Lücke statt „so weit rechts wie möglich".
    expect(css).toMatch(/gap:\s*6px\s+clamp\(16px,\s*3vw,\s*32px\)/);
  });

  it('das Jetzt-Band ist linksbündig (kein rechter Anschlag mehr)', () => {
    const css = idx('.idle__now');
    expect(css).toMatch(/align-items:\s*flex-start/);
    expect(css).toMatch(/text-align:\s*left/);
    expect(css).not.toMatch(/align-items:\s*flex-end/);
  });

  it('auf schmalen Fenstern stapelt die Gruppe explizit', () => {
    // Die eine Breiten-Media-Query des Projekts trägt jetzt die Kopf-Gruppe.
    const media = /@media\s*\(max-width:\s*560px\)\s*\{([\s\S]*?)\n\}/.exec(INDEX_CSS);
    expect(media, 'die 560px-Media-Query fehlt').not.toBeNull();
    expect(media![1]).toMatch(/\.idle__head\s*\{[^}]*flex-direction:\s*column/);
  });
});

describe('Komposition v2 — das Gerüst gibt auf flachen Fenstern Luft an die Bühne', () => {
  /**
   * Liest ein `clamp(MINpx, Nvh, MAXpx)` aus einer Deklaration und rechnet es
   * an einer Fensterhöhe aus — genau das, was der Browser tut.
   */
  const vhClamp = (css: string, prop: string) => {
    const m = new RegExp(`${prop}:\\s*clamp\\((\\d+)px,\\s*([\\d.]+)vh,\\s*(\\d+)px\\)`).exec(css);
    expect(m, `\`${prop}\` trägt keine vh-clamp`).not.toBeNull();
    const [min, vh, max] = [Number(m![1]), Number(m![2]), Number(m![3])];
    return { min, max, at: (h: number) => Math.min(max, Math.max(min, (vh * h) / 100)) };
  };

  // Die drei Stellen, an denen das Gerüst reines Polster trägt. Der Kopf, der
  // Wecker und die Chips behalten ihre Inhaltshöhe — gekürzt wird nur LUFT.
  // SOFORT-FIX 18.08.: `.idle`s `margin-bottom` war die vierte Stelle — sie
  // trug nur die Linie zwischen Bühne und Orb und ist mit ihr zusammen raus
  // (s. Riegel weiter unten). Die verbleibenden drei bleiben unverändert.
  const airy = () => [
    vhClamp(idx('.idle'), 'gap'),
    vhClamp(idx('.idle'), 'padding'),
    vhClamp(idx(".app[data-tab='overview'] .app__main"), 'padding-block'),
  ];

  it('bei 834px Fensterhöhe ändert sich NICHTS — die iPad-Optik bleibt die abgenommene', () => {
    // Das ist der eigentliche Riegel dieser Gruppe: Die Kalibrierung ist so
    // gewählt, dass jede vh-Komponente auf dem iPad (834px, Home-Screen-App)
    // ihr Maximum erreicht. Wer eine der Zahlen später „aufräumt", verschiebt
    // sonst still die Komposition, die Andi am echten Gerät beurteilt hat.
    for (const v of airy()) expect(v.at(834)).toBe(v.max);
  });

  it('bei 745px (iPad quer MIT Safari-Leisten) gibt jede Stelle Luft zurück', () => {
    // Gemessen (headless Chrome, alle fünf Kacheln, 1180 × 745, NACH dem
    // Sofort-Fix 18.08.: Deckel 920px, keine Trennlinie/-margin mehr): die
    // Kachelzeile wächst 125 → 151px (vorher, mit Linie+Margin: 109 → 125px
    // bei 1194 × 745 unter dem alten 1280er-Deckel — beide Zahlen bestätigen
    // dieselbe Klammer, nur die Basis hat sich verschoben).
    for (const v of airy()) expect(v.at(745)).toBeLessThan(v.max);
  });

  it('nach unten ist Schluss: die Polster verschwinden nie ganz', () => {
    // Ohne Untergrenze klebten Kopf, Bühne und Orb auf einem sehr flachen
    // Fenster aneinander — dann wäre die Bühne zwar groß, aber die Trennung
    // zwischen den Bereichen verloren.
    for (const v of airy()) {
      expect(v.min).toBeGreaterThan(0);
      expect(v.at(320)).toBe(v.min);
    }
  });

  it('der Deckel bleibt am Zuhause-Reiter — die Lese-Reiter behalten ihr Polster', () => {
    // `padding-block` steht bewusst an der overview-Regel, NICHT an der Basis.
    expect(idx('.app__main')).not.toMatch(/padding-block/);
    expect(idx('.app__main')).toMatch(/padding:\s*24px\s+20px/);
  });

  it('SOFORT-FIX 18.08.: die Trennlinie zwischen Bühne und Orb ist ersatzlos raus', () => {
    // Andi-Auftrag: die Linie UND ihr Polster verschwinden — nicht nur
    // ausgeblendet. Das gesparte Pixelbudget geht an die Bühne (die drei
    // verbliebenen `airy()`-Stellen oben), nicht an einen neuen Abstand hier.
    const css = idx('.idle');
    expect(css).not.toMatch(/border-bottom/);
    expect(css).not.toMatch(/margin-bottom/);
  });
});

describe('S1 — die Bühne verteilt, statt das Dokument aufzuschieben', () => {
  it('.idle__tiles ist gedeckelt und reserviert die Punkte-Zeile fest', () => {
    // KOMPOSITION V2: `.idle__tiles` ist nicht mehr selbst das Kachel-Raster,
    // sondern die Bühne DARÜBER — zwei Zeilen: Seitenfläche + Punkte. Die
    // Punkte-Zeile steht auch bei einer einzigen Seite, und das ist kein
    // Schönheitsfehler: erschiene sie erst ab Seite 2, änderte ihr Erscheinen
    // die Schienenhöhe, damit die Zeilenzahl, damit die Seitenzahl — eine
    // Rückkopplung, die kippeln kann. Das Kachel-Raster prüft der Block
    // darunter an `.idle__page`.
    const css = idx('.idle__tiles');
    expect(css).toMatch(/min-height:\s*0/);
    expect(css).toMatch(/overflow:\s*hidden/);
    expect(css).toMatch(/grid-template-rows:\s*minmax\(0,\s*1fr\)\s+var\(--home-dots-h,\s*20px\)/);
  });

  it('die Seite ist das Raster — mit definiter Zeilenhöhe wie bisher', () => {
    const css = idx('.idle__page');
    expect(css).toMatch(/min-height:\s*0/);
    // Definite Zeilenhöhe = die Bedingung dafür, dass eine Kachel ihren Inhalt
    // überhaupt intern deckeln kann (DESIGN §2.1). Der Fallback greift vor der
    // ersten Messung; danach schreibt HomeStage.tsx explizite Zeilen inline.
    expect(css).toMatch(/grid-auto-rows:\s*minmax\(0,\s*1fr\)/);
    expect(css).toMatch(/flex:\s*0\s+0\s+100%/);
  });

  it('das Spalten-Minimum in CSS und im Modell ist DIESELBE Zahl', () => {
    // Die Rate-Stelle der Vorgänger-Scheibe: 280px verfehlte auf dem iPad quer
    // (1154px Bühne) die vierte Spalte um zwei Pixel. Jetzt 252px — und beide
    // Seiten der Naht müssen es bleiben, sonst rechnet das Modell mit einer
    // anderen Bühne, als der erste Paint zeigt.
    const css = idx('.idle__page');
    const min = /grid-template-columns:\s*repeat\(auto-fit,\s*minmax\((\d+)px,\s*1fr\)\)/.exec(css);
    expect(min, 'Spalten-Fallback fehlt an .idle__page').not.toBeNull();
    expect(Number(min![1])).toBe(HOME_COLUMN_MIN_WIDTH_PX);
    const gap = /gap:\s*(\d+)px/.exec(css);
    expect(gap, 'gap fehlt an .idle__page').not.toBeNull();
    expect(Number(gap![1])).toBe(HOME_STAGE_GAP_PX);
    // Die Rechnung selbst, damit sie nie wieder still kippt:
    expect(4 * HOME_COLUMN_MIN_WIDTH_PX + 3 * HOME_STAGE_GAP_PX).toBeLessThanOrEqual(1154);
  });

  it('die Seiten-Schiene bewegt NUR transform und gibt die vertikale Achse frei', () => {
    const css = idx('.idle__pages');
    expect(css).toMatch(/transition:\s*transform\s/);
    // `pan-y`: das Scrollen INNERHALB einer Kachel bleibt beim Browser, nur die
    // horizontale Achse gehört dem Seitenwisch.
    expect(css).toMatch(/touch-action:\s*pan-y/);
    // Kein Layout-Thrash: weder left/right noch width werden animiert.
    expect(css).not.toMatch(/transition:[^;]*(left|width)/);
    expect(idx(".idle__pages[data-dragging='true']")).toMatch(/transition:\s*none/);
  });

  it('inaktive Seiten sind wirklich weg — aber erst NACH dem Gleiten', () => {
    // `visibility` nimmt sie aus Tab-Reihenfolge und Screenreader-Baum (ein Tab
    // in eine unsichtbare Seite würde sonst den overflow:hidden-Rahmen
    // wegscrollen); die Verzögerung hält die abziehende Seite während der
    // Bewegung sichtbar.
    const css = idx('.idle__page');
    expect(css).toMatch(/visibility:\s*hidden/);
    expect(css).toMatch(/transition:\s*visibility\s+0s\s+linear\s+var\(--dur-base\)/);
    expect(INDEX_CSS).toMatch(/\.idle__page\[data-active='true'\][\s\S]{0,120}visibility:\s*visible/);
  });

  it('jede Karte lebt in ihrer Zeile und scrollt notfalls in sich selbst', () => {
    const css = idx('.idle__tile');
    expect(css).toMatch(/min-height:\s*0/);
    // Bewusst `auto`, nicht `hidden`: eine aufgeklappte Faltung darf nicht
    // lautlos abgeschnitten werden.
    expect(css).toMatch(/overflow-y:\s*auto/);
    expect(css).toMatch(/overscroll-behavior:\s*contain/);
  });

  it('die grosse (L/XL) Nachrichten-Kachel scrollt in ihrem eigenen Rahmen — nicht am Viewport gemessen (W1: size ersetzt die alte "mehr"-Expansion, `.idle__news--open` -> `.idle__news--big`)', () => {
    expect(idx('.idle__news--big')).toMatch(/overflow:\s*hidden/);
    const list = idx('.idle__news--big .idle__newslist');
    expect(list).toMatch(/flex:\s*1\s+1\s+auto/);
    expect(list).toMatch(/min-height:\s*0/);
    expect(list).toMatch(/overflow-y:\s*auto/);
    expect(list).toMatch(/overscroll-behavior:\s*contain/);
    // Der alte 52vh-Käfig kannte Uhr, Wecker, Chips und Orb nicht und sprengte
    // das Fenster auf dem iPad trotzdem — er darf nicht zurückkommen.
    expect(list).not.toMatch(/max-height/);
  });

  it('die Orb-Karte hat ihren eigenen Rahmen (eine lange Antwort drückt die Bühne nicht auf null)', () => {
    const css = vb('.voiceorb__card');
    expect(css).toMatch(/max-height:\s*min\(/);
    expect(css).toMatch(/overflow-y:\s*auto/);
  });
});
