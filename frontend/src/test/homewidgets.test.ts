import { describe, it, expect } from 'vitest';
import { HOME_WIDGETS, homeWidget, type HomeWidgetId } from '../components/homeWidgets';
import { de } from '../i18n/de';
import { en } from '../i18n/en';
import { es } from '../i18n/es';
import { fr } from '../i18n/fr';
// ACHTUNG: der italienische Katalog heisst `it` — genau wie Vitests `it()`.
// Unaliasiert importiert verdeckt er den Test-Runner, und die Datei stirbt
// mit „it is not a function", bevor ein einziger Test laeuft.
import { it as itCatalog } from '../i18n/it';

/**
 * **homewidgets.test** — the widget registry (W1, DESIGN-widget-raster-
 * 2026-08-18 §1.1): the single place that knows which widgets exist, their
 * rank (crown/stage), which size steps each one may take, and its default
 * size. Pure/net-free, no DOM — a plain data-table test, exactly like
 * `roomsSort.test.ts` pins `roomsSort.ts`.
 */

// W4 (Andi 19.08.): die UHR ist ein Bühnen-Widget geworden — „Die Uhr soll
// auch verschiebbar und in der Größe einstellbar werden".
// W6 (Andi 20.08.): der WECKER ist ihr gefolgt. Die Krone ist damit LEER — der
// Gruß ist Kopf-Text, kein Widget, und alle acht Registry-Einträge liegen auf
// der Bühne.
const STAGE_IDS: HomeWidgetId[] = [
  'uhr',
  'wecker',
  'wetter',
  'laeuft',
  'einkauf',
  'vacuum',
  'climate',
  'news',
];
/** Die Widgets, die S/M/L tragen — Uhr und Wecker hören eine Stufe früher auf. */
const FULL_STEP_IDS: HomeWidgetId[] = ['wetter', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news'];

describe('HOME_WIDGETS — the eight entries (§1.1)', () => {
  it('carries exactly eight widgets, in the §1.1 table order — die UHR führt jetzt die Bühne an (W4)', () => {
    expect(HOME_WIDGETS.map((w) => w.id)).toEqual([
      'uhr',
      'wecker',
      'wetter',
      'laeuft',
      'einkauf',
      'vacuum',
      'climate',
      'news',
    ]);
  });

  it('die Krone ist LEER — der Wecker war ihr letzter Bewohner und liegt seit W6 auf der Bühne', () => {
    expect(HOME_WIDGETS.filter((x) => x.rank === 'crown')).toEqual([]);
  });

  it('der Wecker kann S · M und startet bei M — Zeile + Haarlinie + Vertrauens-Satz, genau die alte Kopfzeile (W6)', () => {
    const w = homeWidget('wecker');
    expect(w.rank).toBe('stage');
    expect(w.sizes).toEqual(['S', 'M']);
    expect(w.defaultSize).toBe('M');
  });

  it('der Wecker hat KEIN L und kein XL — es gibt kein drittes Feld, das eine dritte Stufe füllen könnte', () => {
    // `ScheduledItem` liefert `dueAtEpochMs` und sonst nichts Lesbares; die
    // Haarlinie rechnet der Client, der Vertrauens-Satz ist ein fester Satz je
    // Sprache. Es gibt KEIN `confidence`-Feld vom Backend. Eine L-Stufe müsste
    // Fläche mit Nichts füllen — „L erfindet niemals Inhalt" (§2.3).
    expect(homeWidget('wecker').sizes).not.toContain('L');
    expect(homeWidget('wecker').sizes).not.toContain('XL');
  });

  it('die Uhr kann S · M · L und startet bei L — Zeit groß + Datum + Gruß, genau die alte Krone (W4)', () => {
    const w = homeWidget('uhr');
    expect(w.rank).toBe('stage');
    expect(w.sizes).toEqual(['S', 'M', 'L']);
    expect(w.defaultSize).toBe('L');
  });

  it('die Uhr hat KEIN XL — ihre Felder sind bei L erschöpft (Zeit/Datum/Gruß, keine Sekunden)', () => {
    expect(homeWidget('uhr').sizes).not.toContain('XL');
  });

  it('every widget is rank "stage" now — and every one of them carries at least S and M', () => {
    for (const id of STAGE_IDS) {
      const w = homeWidget(id);
      expect(w.rank).toBe('stage');
      expect(w.sizes).toEqual(expect.arrayContaining(['S', 'M']));
    }
    expect(STAGE_IDS).toHaveLength(HOME_WIDGETS.length);
  });

  it('KEIN Widget hat nur EINE Stufe — Andi 22.08.: „alle Widgets in der größe veränderbar"', () => {
    // `sizableWidgetAt`/`renderSizer` (HomeStage.tsx) fragen genau das:
    // `homeWidget(id).sizes.length > 1`. Ein Widget mit einer einzigen Stufe
    // bekäme einen Wähler ohne Wahl — die Bühne blendet ihn deshalb aus, und
    // Andis Satz wäre für dieses Widget still gebrochen. Der Riegel steht hier
    // und nicht in der Bühne, weil die REGISTRY die Zusage macht.
    for (const w of HOME_WIDGETS) {
      expect(w.sizes.length, `${w.id} bietet nur eine Stufe an`).toBeGreaterThan(1);
    }
  });

  it('alle außer Uhr und Wecker tragen zusätzlich L', () => {
    for (const id of FULL_STEP_IDS) {
      expect(homeWidget(id).sizes).toContain('L');
    }
    expect(homeWidget('uhr').sizes).toContain('L'); // die Uhr kann L, nur kein XL
    expect(homeWidget('wecker').sizes).not.toContain('L');
  });

  it('the vacuum tile HAS XL since 22.08. — Andi asked what more fits when the widget grows, and four mapped-but-underived fields answered', () => {
    // §1.1 denied it („one device, one state; L exhausts VacuumFamily") and
    // that was TRUE then. `lastCleanEnd`/`lastCleanStart`, `moppDrying` and the
    // four maintenance timers were mapped but never derived — the ceiling was
    // the derivations, not the device. The „never invent content" rule is
    // untouched: each of those rows still needs a real value to appear.
    expect(homeWidget('vacuum').sizes).toEqual(['S', 'M', 'L', 'XL']);
  });

  it('wetter/laeuft/einkauf/climate/news all carry XL (Gate 3 GRÜN, §1.1)', () => {
    for (const id of ['wetter', 'laeuft', 'einkauf', 'climate', 'news'] as const) {
      expect(homeWidget(id).sizes).toContain('XL');
    }
  });

  it('default sizes reproduce exactly what today\'s JSX showed — DEFAULT_HOME_LAYOUT, §5.3', () => {
    expect(homeWidget('wecker').defaultSize).toBe('M');
    expect(homeWidget('wetter').defaultSize).toBe('L');
    expect(homeWidget('laeuft').defaultSize).toBe('L');
    expect(homeWidget('einkauf').defaultSize).toBe('M');
    expect(homeWidget('vacuum').defaultSize).toBe('L');
    expect(homeWidget('climate').defaultSize).toBe('L');
    expect(homeWidget('news').defaultSize).toBe('M');
  });

  it('every default size is itself an allowed size for that widget (no widget defaults outside its own menu)', () => {
    for (const w of HOME_WIDGETS) {
      // Seit W6 hat JEDES Widget eine Default-Stufe — die Krone, die früher
      // `null` trug, gibt es nicht mehr. Der Zweig bleibt als Riegel stehen:
      // käme je wieder ein rangfestes Widget dazu, soll dieser Test nicht
      // daran scheitern, sondern es überspringen wie vorher.
      if (w.defaultSize === null) continue;
      expect(w.sizes).toContain(w.defaultSize);
    }
    expect(HOME_WIDGETS.every((w) => w.defaultSize !== null)).toBe(true);
  });
});

describe('jedes Buehnen-Widget hat einen NAMEN in allen fuenf Sprachen', () => {
  /**
   * Der Edit-Modus zeigt den Namen an drei Stellen: im Fach („Verfügbar"), in
   * der Ansage beim Verschieben und im `aria-label` der Kachel. Der Aufloeser
   * (`HomeStage.tsx#widgetName`) hat ein `default: return id` — ein Widget
   * ohne Katalog-Eintrag faellt also nicht auf, es steht dann einfach
   * `wecker` auf dem Bildschirm. Genau diese Falle hat W6 aufgemacht, als der
   * Wecker von der Krone (die nie einen Namen brauchte) auf die Buehne kam.
   */
  const catalogs = { de, en, es, fr, it: itCatalog };
  const nameOf = (t: (typeof catalogs)['de'], id: HomeWidgetId): string | undefined => {
    const f = t.idleFace;
    switch (id) {
      case 'uhr':
        return f.uhr.name;
      case 'wecker':
        return f.wecker.name;
      case 'wetter':
        return f.wetter.name;
      case 'laeuft':
        return f.laeuft.name;
      case 'einkauf':
        return f.einkauf.name;
      case 'vacuum':
        return f.homeTiles.vacuum.name;
      case 'climate':
        return f.homeTiles.climate.name;
      case 'news':
        return f.currentAffairs.name;
    }
  };

  it('kein Widget faellt auf seine rohe Id zurueck', () => {
    for (const [lang, cat] of Object.entries(catalogs)) {
      for (const w of HOME_WIDGETS) {
        const name = nameOf(cat, w.id);
        expect(name, `${lang}: ${w.id} hat keinen Namen`).toBeTruthy();
        expect(name, `${lang}: ${w.id} zeigt seine rohe Id`).not.toBe(w.id);
      }
    }
  });

  it('der Wecker heisst in jeder Sprache etwas anderes als auf Deutsch (echte Uebersetzung, keine Kopie)', () => {
    expect(nameOf(de, 'wecker')).toBe('Wecker');
    for (const lang of ['en', 'es', 'fr', 'it'] as const) {
      expect(nameOf(catalogs[lang], 'wecker')).not.toBe(nameOf(de, 'wecker'));
    }
  });
});

describe('homeWidget() — total lookup over the eight ids', () => {
  it('returns the SAME object HOME_WIDGETS carries (no copy, no drift between the array and the lookup)', () => {
    for (const w of HOME_WIDGETS) {
      expect(homeWidget(w.id)).toBe(w);
    }
  });

  it('an id outside the union throws instead of silently returning undefined (defensive — unreachable for a real HomeWidgetId)', () => {
    expect(() => homeWidget('sofa' as unknown as HomeWidgetId)).toThrow(/unknown home widget id/);
  });
});
