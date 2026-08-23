/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { readFileSync } from 'node:fs';
import { createRoot, type Root } from 'react-dom/client';
import { HomeStage, type HomeStageTile } from '../components/HomeStage';
import { loadHomeLayout, saveHomeLayout } from '../hooks/useHomeLayout';
import {
  DEFAULT_HOME_LAYOUT,
  homeDropCell,
  homePlanPlacements,
  moveHomePlacement,
  normalizeHomeLayout,
  parseHomeLayout,
  planHomeStage,
  serializeHomeLayout,
  withHomePlacements,
  withHomeTileSize,
  type HomeCell,
  type HomeLayoutV1,
  type HomePlacementMap,
} from '../components/homeLayout';
import type { HomeTileSize, HomeWidgetId } from '../components/homeWidgets';
import { CATALOGS, SUPPORTED_UI_LANGUAGES } from '../i18n';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * **homefreiesraster.test** — die Scheibe W7 (Andi 21.08., wörtlich beim
 * Livetest):
 *
 *   *„Das mit den Kacheln wird besser, aber noch nicht gut. Diese Hilfe,
 *   welche eingeblendet wird, verändert die Größen der anderen Elemente. Dann
 *   verschiebt es andere Widgets aus der Seite. Ich möchte, dass ich die
 *   Widgets anordnen kann. Das soll nicht automatisch bündig werden, sondern
 *   nur, wenn ich es verschiebe."*
 *
 * …und, aus demselben Livetest nachgereicht:
 *
 *   *„Vereinheitliche die Steuerung bei allen Widgets, dass sie nur noch über
 *   das +/−-Prinzip steuerbar sind — einfach durch dezentere Icons. Das M
 *   darunter verändert die Größe des Widgets, das macht es schwer
 *   platzierbar."*
 *
 * Drei Zusagen, drei Teile dieser Datei:
 *
 *  **A — Die Edit-Bedienung verändert die BÜHNEN-Geometrie NIE.** Die Leiste
 *  liegt in einer eigenen Schicht über der Bühne statt als Zeile davor im
 *  Fluss. (Ein Fach „Verfügbar" stand bis zum 22.08. dahinter — Andi hat es
 *  abbestellt; ein- und ausschalten tun die Einstellungen.)
 *
 *  **D — Dieselbe Regel eine Ebene tiefer: die KACHEL-Geometrie auch nicht.**
 *  Der +/−-Wähler liegt absolut positioniert in der Zelle seiner Kachel; die
 *  Griffecke (der zweite Weg zur Größe, 44 × 44 in genau der Ecke, an der man
 *  zum Verschieben greift) ist ganz weg.
 *
 *  **B — Kein Auto-Bündig.** Kacheln behalten ihre Zelle; Lücken sind erlaubt;
 *  nur ein Nutzer-Zug vergibt Zellen neu.
 *
 * **Warum der Stub hier RECHNET statt eine Zahl zurückzugeben** (Unterschied
 * zu `homestage.test.tsx`/`homeeditmode.test.tsx`, wo jedes Element dieselbe
 * Kiste meldet): Zusage A IST eine Aussage über Höhen. Ein Stub, der jedem
 * Element dieselbe Höhe gibt, kann sie weder verletzen noch beweisen — er
 * würde die Prüfung stillstellen. Der Stub unten macht darum das, was ein
 * Browser täte: er zieht von der Bühnenhöhe ab, was in ihr noch IM FLUSS
 * steht. Und ob ein Kind im Fluss steht, wird nicht behauptet, sondern in
 * `index.css` NACHGESCHLAGEN (`position: absolute` ⇒ eigene Schicht ⇒ kostet
 * nichts). Damit hängt der Test an der echten Regel: nimmt jemand der Schicht
 * ihr `position: absolute`, wird er hier rot.
 */

const CSS = readFileSync('src/index.css', 'utf8');

/** Der Rumpf EINER CSS-Regel, exakt wie in `homeeditmode.test.tsx` gelesen. */
const ruleOf = (selector: string): string => {
  const esc = selector.replace(/[.[\]='*>~+ ]/g, (c) => `\\${c}`);
  // Der Selektor darf auch EINER VON MEHREREN sein: `.idle__editsr` steht in
  // der geteilten sr-only-Regel (`.idle__sronly,\n.idle__editsr,\n…`). Ohne
  // die Gruppen-Variante läse dieser Helfer dort nichts und der Test hielte
  // eine `position: absolute` für nicht vorhanden — er würde also grün oder
  // rot aus dem falschen Grund.
  const m =
    new RegExp(`\\n${esc}\\s*\\{([^}]*)\\}`).exec(CSS) ??
    new RegExp(`\\n(?:[^{};]*,\\s*\\n)*${esc},?\\s*(?:\\n[^{};]*,?)*?\\s*\\{([^}]*)\\}`).exec(CSS);
  // Kommentare raus — sonst hinge eine Deklaration, der ein `/* … */`
  // vorangeht, nicht mehr an einem `;` und würde übersehen.
  return m ? m[1].replace(/\/\*[\s\S]*?\*\//g, '') : '';
};
const declOf = (selector: string, prop: string): string | null => {
  const m = new RegExp(`(?:^|;)\\s*${prop}:\\s*([^;]+)`).exec(ruleOf(selector));
  return m ? m[1].trim() : null;
};

const rect = (left: number, top: number, width: number, height: number): DOMRect =>
  ({
    x: left,
    y: top,
    width,
    height,
    top,
    left,
    right: left + width,
    bottom: top + height,
    toJSON: () => ({}),
  }) as DOMRect;

/**
 * Die Bühne: 900 × 300 an (0,0). Ungestört sind das 3 Spalten × 2 Zeilen
 * (`fitCount(300,132,12) = 2`) — also sechs Zellen je Seite. Kostete der
 * Edit-Modus wieder seine 72 px (62 Leiste + 10 Abstand), bliebe die Bühne
 * knapp über der Kante — und mit dem alten Fach (56 px + 10) fiele sie auf
 * EINE Zeile: drei Zellen je Seite. Genau diesen Sprung hat Andi als
 * „verschiebt andere Widgets aus der Seite" gesehen.
 */
const STAGE_W = 900;
const STAGE_H = 300;
const STAGE_GAP = 10;
const COL_X = [100, 400, 700];
const ROW_Y = [50, 200];

/** `min-height` einer Regel als Zahl — die natürliche Höhe der Leiste. */
const minHeightPx = (selector: string): number => {
  const v = declOf(selector, 'min-height');
  return v ? Number.parseFloat(v) : 0;
};

/** Steht dieses Kind der Bühne im FLUSS? (Eigene Schicht ⇒ nein.) */
const outOfFlow = (el: Element): boolean => {
  for (const cls of Array.from(el.classList)) {
    const pos = declOf(`.${cls}`, 'position');
    if (pos === 'absolute' || pos === 'fixed') return true;
  }
  return false;
};

/**
 * Was dieses Kind der Bühne an Höhe wegnimmt.
 *
 * Bis zum 23.08. war das eine echte Rechnung: die Edit-Leiste stand im Fluss
 * und kostete ihre `min-height` plus die Fuge. Seit sie gefallen ist, gibt es
 * NICHTS mehr, was der Bühne Höhe nimmt — und genau das ist die Zusage, die
 * diese Funktion jetzt misst. Ein Kind außerhalb des Flusses kostet nichts;
 * jedes andere kostet mindestens die Fuge, und wäre damit ein Rückfall.
 */
const flowCost = (el: Element): number => {
  if (el.classList.contains('idle__tiles')) return 0;
  if (outOfFlow(el)) return 0;
  return minHeightPx(`.${el.classList[0]}`) + STAGE_GAP;
};

/** Alle angemeldeten ResizeObserver — jsdom hat keinen, also stellen wir einen. */
let observers: (() => void)[] = [];

const stubFlexLayout = () => {
  observers = [];
  class FakeResizeObserver {
    constructor(private readonly cb: () => void) {}
    observe() {
      observers.push(this.cb);
    }
    unobserve() {}
    disconnect() {
      observers = observers.filter((c) => c !== this.cb);
    }
  }
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement,
  ) {
    const stage = document.querySelector('.idle__stage');
    const taken = stage
      ? Array.from(stage.children).reduce((sum, child) => sum + flowCost(child), 0)
      : 0;
    const boxH = STAGE_H - taken;
    return rect(0, 0, STAGE_W, boxH);
  });
};

const tile = (id: string, size?: HomeTileSize): HomeStageTile => ({
  id,
  size,
  node: (effective) => (
    <article key={id} className="tile idle__tile" data-tile={id} data-size={effective}>
      {id}
    </article>
  ),
});

const ALL: HomeWidgetId[] = ['uhr', 'wetter', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news'];
const allSmall = (): HomeLayoutV1 => ({
  version: 1,
  order: ALL.map((id) => ({ id, size: 'S' as HomeTileSize })),
});

function memoryStorage(): Storage {
  const m = new Map<string, string>();
  return {
    get length() {
      return m.size;
    },
    clear: () => m.clear(),
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    key: (i: number) => Array.from(m.keys())[i] ?? null,
    removeItem: (k: string) => {
      m.delete(k);
    },
    setItem: (k: string, v: string) => {
      m.set(k, String(v));
    },
  };
}

// W7-A hieß diese Suite „die Edit-Bedienung ist eine Schicht, keine Zeile".
// Am 23.08. ist die Bedienung ganz gefallen (Andi: „nimm die UI oben, wenn man
// etwas bearbeitet raus"). Die ZUSAGE ist dieselbe geblieben und nur einfacher
// zu halten — darum bleiben die Tests, nur ihr Name stimmt jetzt wieder.
describe('Der Edit-Modus kostet die Bühne keinen Pixel (W7-A … 23.08.)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (tiles: HomeStageTile[]) => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };
  const tileEl = (id: string): HTMLElement =>
    container.querySelector(`[data-tile="${id}"]`) as HTMLElement;
  const stage = (): HTMLElement => container.querySelector('.idle__stage') as HTMLElement;
  const pages = () => Array.from(container.querySelectorAll('.idle__page'));
  /** Auf welcher SEITE liegt jede Kachel? Die Zahl, die Andi gesehen hat. */
  const pageMap = (): Record<string, number> => {
    const out: Record<string, number> = {};
    pages().forEach((p, index) => {
      p.querySelectorAll('[data-tile]').forEach((t) => {
        out[t.getAttribute('data-tile') as string] = index;
      });
    });
    return out;
  };
  const fire = async (target: EventTarget, type: string, x: number, y: number, pointerId = 1) => {
    const evt = new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y });
    Object.defineProperty(evt, 'pointerId', { value: pointerId, configurable: true });
    await act(async () => {
      target.dispatchEvent(evt);
    });
  };
  /** Was ein echter Browser täte, wenn sich die Schiene ändert. */
  const remeasure = async () => {
    await act(async () => {
      observers.forEach((cb) => cb());
    });
  };
  const enterEdit = async (id: string) => {
    await fire(tileEl(id), 'pointerdown', 10, 10);
    await act(async () => {
      vi.advanceTimersByTime(600);
    });
    await fire(stage(), 'pointerup', 10, 10);
    await remeasure();
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    stubFlexLayout();
    saveHomeLayout(allSmall());
    localStorage.setItem('hoshi.homeTiles.vacuum', 'true');
    localStorage.setItem('hoshi.homeTiles.climate', 'true');
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('ANDIS BEFUND, gepinnt: die Seitenzuordnung JEDER Kachel ist im Edit dieselbe wie davor', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    const before = pageMap();
    // Vorbedingung: die Bühne trägt zwei Zeilen, sonst prüft der Test nichts.
    expect(pages()).toHaveLength(2);
    expect(Object.keys(before)).toHaveLength(7);

    await enterEdit('uhr');
    expect(stage().getAttribute('data-edit')).toBe('true');
    // Seit dem 23.08. ist der Beweis, DASS der Modus läuft, die unsichtbare
    // Gruppe — die Leiste, die früher hier stand, ist gefallen.
    expect(container.querySelector('.idle__editsr')).not.toBeNull();
    expect(container.querySelector('.idle__editbar')).toBeNull();

    // DIE Zusage: dieselbe Anzahl Seiten, dieselbe Kachel auf derselben Seite.
    expect(pages()).toHaveLength(2);
    expect(pageMap()).toEqual(before);
  });

  it('die Bühnenhöhe selbst ist im Edit unverändert — die Zeilenrechnung bekommt exakt dieselbe Zahl', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    const railBefore = container.querySelector('.idle__pages')!.getBoundingClientRect().height;
    await enterEdit('uhr');
    const railAfter = container.querySelector('.idle__pages')!.getBoundingClientRect().height;
    expect(railAfter).toBe(railBefore);
    expect(railAfter).toBe(STAGE_H);
  });

  it('im Fluss der Bühne steht NUR der Kachel-Kasten — im Edit wie außerhalb', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    const inFlow = () =>
      Array.from(stage().children)
        .filter((c) => !outOfFlow(c))
        .map((c) => c.className);
    expect(inFlow()).toEqual(['idle__tiles']);
    await enterEdit('uhr');
    // DIE Zusage, jetzt aus dem einfachsten Grund: es gibt gar keine Bedienung
    // mehr, die im Fluss stehen könnte (Andi 23.08.: „nimm die UI oben … raus").
    expect(inFlow()).toEqual(['idle__tiles']);
    // Die unsichtbare sr-Gruppe ist DAS Gegenbeispiel, das leicht zurückkommt:
    // 0 px hoch, aber als Flex-Kind trotzdem eine 10-px-Fuge teuer. Sie muss
    // außerhalb des Flusses liegen.
    const sr = stage().querySelector('.idle__editsr');
    expect(sr).not.toBeNull();
    expect(outOfFlow(sr!)).toBe(true);
    // Und unter wie über der Bühne liegt nichts mehr: Fach (22.08.) und
    // Leiste (23.08.) sind gefallen, die Schicht mit ihnen.
    expect(stage().querySelector('.idle__editlayer')).toBeNull();
    expect(stage().querySelector('.idle__tray')).toBeNull();
  });

  /* ── Andis zweiter Befund vom 22.08., und sein Ende am 23.08. ───────────
   *
   * Wörtlich (22.08.): *„Im bearbeitungsmodus überlagern die beiden overlays …
   * das kann als dezente hilfe oben stehen, soll aber nicht die fläche zum
   * bearbeiten überlagern."* W7-As Schicht kostete die Bühne keinen Pixel
   * Höhe — sie lag dafür AUF den Kacheln (Sonde 834×1112: 49 228 px² Leiste +
   * 28 584 px² Fach = 13,8 % der Fläche). Die Antwort war ein gemessenes Band:
   * die Seite rückte im Edit um die Höhe der Leiste nach unten.
   *
   * Am 23.08. sagte Andi den Satz, der beides erledigt: *„nimm die UI oben,
   * wenn man etwas bearbeitet raus."* Ohne Leiste gibt es nichts zu
   * überlagern und nichts, wovor die Fläche ausweichen müsste — die
   * Bearbeitungsfläche IST die Bühne. Der Riegel ist darum die Abwesenheit
   * beider Mechanismen.
   */
  it('23.08.: es gibt kein Edit-Band mehr — die Fläche im Edit ist die ganze Bühne', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    expect(stage().style.getPropertyValue('--home-editband-top')).toBe('');
    await enterEdit('uhr');
    // Weder oben noch unten wird noch etwas gemessen und angeschrieben.
    expect(stage().style.getPropertyValue('--home-editband-top')).toBe('');
    expect(stage().style.getPropertyValue('--home-editband-bottom')).toBe('');
    // Kommentare raus: die Geschichte des Bandes steht als Fließtext im Blatt
    // und darf nicht als Regel zählen (dasselbe `strip` wie in onewindow.test).
    expect(CSS.replace(/\/\*[\s\S]*?\*\//g, '')).not.toContain('--home-editband');
    // Und keine Regel rückt die Seite im Edit ein.
    expect(ruleOf(".idle__pages[data-edit='true'] .idle__page")).toBe('');
    // Die Schiene ist im Edit so hoch wie außerhalb — die Zeilenrechnung
    // bekommt dieselbe Zahl (derselbe Beweis wie im Test darüber, hier als
    // Zusage über die FLÄCHE statt über die Seitenzahl).
    expect(container.querySelector('.idle__pages')!.getBoundingClientRect().height).toBe(STAGE_H);
  });

  it('CSS-Zusage: die Edit-Schicht ist weg — der Bezugsrahmen bleibt für den Wähler', () => {
    // Die Schicht trug Leiste und Fach; beide sind gefallen, sie mit ihnen.
    expect(ruleOf('.idle__editlayer')).toBe('');
    expect(ruleOf('.idle__editlayer > *')).toBe('');
    // `position: relative` bleibt — daran hängt jetzt der Stufen-Wähler. Ohne
    // die Zeile hinge er am Fenster statt an seiner Kachel.
    expect(declOf('.idle__stage', 'position')).toBe('relative');
  });

  /**
   * **Andi 22.08. nachts:** *„unten ist auch eine leiste. die soll weg."*
   *
   * Der Abbau ist erst dann einer, wenn auch die Fläche darunter wieder der
   * Bühne gehört: der Zug, der eine Kachel früher ins Fach legte, ist jetzt
   * ein ganz normaler Zug.
   */
  it('unter der Bühne liegt nichts mehr — der Zug nach unten legt die Kachel ab statt sie auszuschalten', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    await enterEdit('uhr');
    expect(container.querySelector('.idle__tray')).toBeNull();
    expect(container.querySelector('[data-tray]')).toBeNull();
    await fire(tileEl('news'), 'pointerdown', COL_X[2], ROW_Y[1]);
    await fire(stage(), 'pointermove', 400, STAGE_H - 20);
    await act(async () => {
      vi.advanceTimersByTime(20);
    });
    await fire(stage(), 'pointerup', 400, STAGE_H - 20);
    expect(localStorage.getItem('hoshi.homeTiles.currentAffairs')).not.toBe('false');
    expect(container.querySelector('[data-tile="news"]')).not.toBeNull();
  });
});

describe('W7-D — die Kachel-Bedienung liegt AUF der Kachel, nicht in ihrem Fluss', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (tiles: HomeStageTile[]) => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };
  const tileEl = (id: string): HTMLElement =>
    container.querySelector(`[data-tile="${id}"]`) as HTMLElement;
  const stage = (): HTMLElement => container.querySelector('.idle__stage') as HTMLElement;
  /** Die Rasterfläche EINER Kachel — Zelle plus Spanne, aus ihrem Inline-Stil. */
  const tileArea = (id: string): string => {
    const el = container.querySelector<HTMLElement>(`[data-widget-id="${id}"]`);
    return el ? `${el.style.gridColumn}|${el.style.gridRow}` : '';
  };
  const areas = (): Record<string, string> =>
    Object.fromEntries(
      Array.from(container.querySelectorAll<HTMLElement>('[data-widget-id]')).map((el) => [
        el.getAttribute('data-widget-id') as string,
        `${el.style.gridColumn}|${el.style.gridRow}`,
      ]),
    );
  const fire = async (target: EventTarget, type: string, x: number, y: number) => {
    const evt = new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y });
    Object.defineProperty(evt, 'pointerId', { value: 1, configurable: true });
    await act(async () => {
      target.dispatchEvent(evt);
    });
  };
  const enterEdit = async (id: string) => {
    await fire(tileEl(id), 'pointerdown', 10, 10);
    await act(async () => {
      vi.advanceTimersByTime(600);
    });
    await fire(stage(), 'pointerup', 10, 10);
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    stubFlexLayout();
    saveHomeLayout(allSmall());
    localStorage.setItem('hoshi.homeTiles.vacuum', 'true');
    localStorage.setItem('hoshi.homeTiles.climate', 'true');
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('ANDIS KERNBEFUND: die Kachelfläche ist im Edit — und mit offenem Wähler — dieselbe wie davor', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    const davor = areas();
    expect(Object.keys(davor)).toHaveLength(7);

    await enterEdit('uhr');
    // Der lange Druck öffnet den Modus UND den Wähler der gedrückten Kachel.
    expect(container.querySelector('.idle__sizer')).not.toBeNull();
    expect(areas()).toEqual(davor);

    // Und auch der Wähler auf einer ANDEREN Kachel verrückt nichts.
    await fire(tileEl('news'), 'pointerdown', 700, 200);
    await fire(stage(), 'pointerup', 700, 200);
    expect(container.querySelector('.idle__sizer')).not.toBeNull();
    expect(areas()).toEqual(davor);
  });

  it('der Wähler belegt KEINE eigene Rasterzelle — er liegt in der Zelle seiner Kachel', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    await enterEdit('uhr');
    const sizer = container.querySelector<HTMLElement>('.idle__sizer')!;
    // Dieselbe Zelle wie die Uhr — nicht die nächste freie.
    expect(`${sizer.style.gridColumn}|${sizer.style.gridRow}`).toBe(tileArea('uhr'));
    // CSS-Zusage: er ist absolut positioniert, also aus der Spur-Berechnung
    // draußen; seine Zelle ist sein Bezugsrahmen, weil die Seite positioniert ist.
    expect(declOf('.idle__sizer', 'position')).toBe('absolute');
    expect(declOf('.idle__page', 'position')).toBe('relative');
    // Der Streifen lässt den Zeiger durch, nur die Pille fängt ihn — sonst
    // wäre die untere Kachelkante im Edit tot, und dort greift die Hand.
    expect(declOf('.idle__sizer', 'pointer-events')).toBe('none');
    expect(declOf('.idle__sizer > *', 'pointer-events')).toBe('auto');
  });

  it('NUR +/−: es gibt keine Griffecke mehr und keinen sichtbaren Stufen-Buchstaben', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    await enterEdit('uhr');
    // Der zweite Weg zur Größe ist weg — samt seiner 44 × 44 in der
    // Kachelecke, an der man zum Verschieben greift.
    expect(container.querySelectorAll('.idle__grip')).toHaveLength(0);
    expect(CSS).not.toContain('.idle__grip');
    // Bedienelemente im Wähler: genau zwei, und beide sind +/−.
    const buttons = Array.from(container.querySelectorAll('.idle__sizer button'));
    expect(buttons.map((b) => b.textContent)).toEqual(['−', '+']);
    // Der Buchstabe fürs Auge ist weg, das Wort fürs Ohr steht.
    const step = container.querySelector('.idle__sizerstep') as HTMLElement;
    expect(step.textContent).toBe('Klein');
    expect(step.className).toContain('idle__sronly');
    expect(step.getAttribute('aria-live')).toBe('polite');
  });

  it('die ARIA-Namen sind vollständig geblieben — dezenter heißt nicht stummer', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    await enterEdit('uhr');
    const sizer = container.querySelector('.idle__sizer')!;
    expect(sizer.getAttribute('role')).toBe('group');
    expect(sizer.getAttribute('aria-label')).toContain('Uhr');
    const buttons = Array.from(container.querySelectorAll('.idle__sizer button'));
    for (const b of buttons) expect(b.getAttribute('aria-label')).toBeTruthy();
    // Und die Kachel bleibt ein benanntes Bedienelement (W4).
    expect(tileEl('wetter').getAttribute('aria-label')).toMatch(/Wetter/);
    expect(tileEl('wetter').getAttribute('role')).toBe('button');
  });
});

/**
 * **W7-C hieß: „die Leiste verspricht, was die Bühne wirklich kann."**
 *
 * Der Weg dieser Zeile in vier Schritten: W4 zeigte die ganze Tastatur-
 * Belegung, W6 nahm sie heraus (sie brach um und kostete Kachelzeilen), W7-C
 * ersetzte die alte Packer-Zusage durch die ehrliche neue, W7-A/22.08. rückte
 * sie nach links. Dann sah Andi sie (22.08. nachts): *„Entferne bitte die
 * hinweise beim bearbeiten der widgets."*
 *
 * Damit ist die Frage nicht mehr, ob der Satz stimmt, sondern dass es ihn
 * nicht gibt. Eine Bühne, auf der alle Kacheln wackeln, sagt bereits, was sie
 * ist — Prosa daneben erklärt nichts mehr, sie belegt nur Platz.
 */
describe('Der Edit-Modus erklärt sich durch das Wackeln, nicht durch einen Satz', () => {
  it('es gibt in keinem Katalog mehr einen Edit-Hilfetext', () => {
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      expect(CATALOGS[lang].idleFace.stage.edit, lang).not.toHaveProperty('hint');
    }
  });

  it('was fürs OHR bleibt, ist vollständig — und verspricht nichts Gefallenes', () => {
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      const keys = CATALOGS[lang].idleFace.stage.edit.keyHint;
      expect(keys.length, lang).toBeGreaterThan(20);
    }
    // Fünf Sprachen, fünf verschiedene Sätze — nichts durchgereicht.
    const alle = SUPPORTED_UI_LANGUAGES.map((l) => CATALOGS[l].idleFace.stage.edit.keyHint);
    expect(new Set(alle).size).toBe(SUPPORTED_UI_LANGUAGES.length);
    // Sie sagt „tauschen", nicht mehr „verschieben" (W7) …
    expect(CATALOGS['de'].idleFace.stage.edit.keyHint).toContain('tauschen');
    // … und nennt kein Entf mehr: die Taste ist mit dem Fach gefallen.
    expect(CATALOGS['de'].idleFace.stage.edit.keyHint).not.toMatch(/Entf/);
    expect(CATALOGS['en'].idleFace.stage.edit.keyHint).not.toMatch(/Delete/);
  });
});

/* ═══ W7-B · Die reinen Verträge (kein DOM) ════════════════════════════════ */

describe('W7-B — planHomeStage platziert auf gespeicherte Zellen statt zu packen', () => {
  const t = (id: string, cols = 1, rows = 1) => ({ id, cols, rows });
  /** 3 Spalten × 2 Zeilen — dasselbe Raster wie oben. */
  const box = { width: 900, height: 300 };
  const idsOn = (page: { cells: { tile: { id: string } }[] }) => page.cells.map((c) => c.tile.id);
  const cellOf = (plan: ReturnType<typeof planHomeStage>, id: string) => {
    for (let i = 0; i < plan.pages.length; i += 1) {
      const c = plan.pages[i].cells.find((x) => x.tile.id === id);
      if (c) return { col: c.col, row: c.row + i * plan.rowsPerPage };
    }
    return null;
  };

  it('OHNE Zellen packt der Bestands-Packer weiter — genau das ist die Saat', () => {
    const plan = planHomeStage([t('a'), t('b'), t('c')], box);
    expect(idsOn(plan.pages[0])).toEqual(['a', 'b', 'c']);
    expect(homePlanPlacements(plan)).toEqual({
      a: { col: 0, row: 0 },
      b: { col: 1, row: 0 },
      c: { col: 2, row: 0 },
    });
  });

  it('MIT Zellen steht jede Kachel dort, wo sie steht — auch mit Löchern dazwischen', () => {
    const plan = planHomeStage([t('a'), t('b'), t('c')], box, {
      placements: { a: { col: 2, row: 1 }, b: { col: 0, row: 0 }, c: { col: 2, row: 0 } },
    });
    // Die Liste ist a,b,c — das Bild ist b,c,a. Die Reihenfolge entscheidet nichts mehr.
    expect(cellOf(plan, 'a')).toEqual({ col: 2, row: 1 });
    expect(cellOf(plan, 'b')).toEqual({ col: 0, row: 0 });
    expect(cellOf(plan, 'c')).toEqual({ col: 2, row: 0 });
    // Und die Zellen (1,0), (0,1), (1,1) bleiben LEER. Niemand rückt nach.
    expect(plan.pages[0].cells).toHaveLength(3);
  });

  it('DIE VERDIEN-LÜCKE: eine stille Kachel behält ihre Zelle, niemand stopft sie', () => {
    const placements = { a: { col: 0, row: 0 }, b: { col: 1, row: 0 }, c: { col: 2, row: 0 } };
    // `b` wird still (der Aufrufer liefert sie nicht mehr) — aber sie ist
    // eingeschaltet, also reserviert die Bühne ihren Fußabdruck.
    const withGap = planHomeStage([t('a'), t('c')], box, {
      placements,
      reserved: [{ col: 1, row: 0, cols: 1, rows: 1 }],
    });
    expect(cellOf(withGap, 'a')).toEqual({ col: 0, row: 0 });
    expect(cellOf(withGap, 'c')).toEqual({ col: 2, row: 0 });

    // Eine NEUE Kachel darf sich nicht in die Lücke setzen — sie kommt darunter.
    const withNewcomer = planHomeStage([t('a'), t('c'), t('neu')], box, {
      placements,
      reserved: [{ col: 1, row: 0, cols: 1, rows: 1 }],
    });
    expect(cellOf(withNewcomer, 'neu')).toEqual({ col: 0, row: 1 });

    // Und kommt `b` zurück, steht sie GENAU wieder da, wo sie war.
    const back = planHomeStage([t('a'), t('b'), t('c'), t('neu')], box, {
      placements: { ...placements, neu: { col: 0, row: 1 } },
    });
    expect(cellOf(back, 'b')).toEqual({ col: 1, row: 0 });
  });

  it('ohne Reservierung (Widget AUSGESCHALTET) ist die Zelle frei — das war eine Entscheidung, kein Schweigen', () => {
    // Bewiesen an der Kachel, die AUSWEICHEN muss: `gross` ist zwei Zellen
    // breit und liegt gespeichert bei Spalte 2 — dort ist nur noch eine
    // Spalte übrig, sie sucht sich also den nächsten freien Platz von oben.
    const stored = { gross: { col: 2, row: 0 }, b: { col: 1, row: 0 } };
    const frei = planHomeStage([t('gross', 2, 1)], box, { placements: stored, reserved: [] });
    // `b` ist ausgeschaltet: seine Zelle blockiert nichts, (1,0)+(2,0) sind frei.
    expect(cellOf(frei, 'gross')).toEqual({ col: 1, row: 0 });

    // Dasselbe, aber `b` ist EINGESCHALTET und nur gerade still: jetzt hält
    // seine Lücke stand, und `gross` weicht in die nächste Zeile aus.
    const belegt = planHomeStage([t('gross', 2, 1)], box, {
      placements: stored,
      reserved: [{ col: 1, row: 0, cols: 2, rows: 1 }],
    });
    expect(cellOf(belegt, 'gross')).toEqual({ col: 0, row: 1 });
  });

  it('eine Kachel OHNE Zelle reiht sich HINTEN an — sie drängelt sich in keine Lücke', () => {
    // Die Lücke bei (1,0) gehört niemandem (nichts reserviert) und bleibt
    // trotzdem frei: wer keine gespeicherte Zelle hat, kommt hinter alles
    // Bekannte. Sonst wäre jede Ankunft aus dem Netz ein Umbau der Bühne.
    const plan = planHomeStage([t('a'), t('c'), t('neu')], box, {
      placements: { a: { col: 0, row: 0 }, c: { col: 2, row: 0 } },
    });
    expect(cellOf(plan, 'neu')).toEqual({ col: 0, row: 1 });
  });

  it('eine Seite ohne einzige Kachel gibt es nicht — kein Punkt, der ins Leere führt', () => {
    // Die Lücke einer stillen Kachel liegt weit hinten (Zeile 6 = Seite 3).
    // Sie ist nicht vergessen — sie erzeugt nur keine leere Seite mit Punkt.
    const plan = planHomeStage([t('a')], box, {
      placements: { a: { col: 0, row: 0 }, weitweg: { col: 0, row: 6 } },
      reserved: [{ col: 0, row: 6, cols: 1, rows: 1 }],
    });
    expect(plan.pages).toHaveLength(1);
    expect(cellOf(plan, 'a')).toEqual({ col: 0, row: 0 });
  });

  it('eine gewachsene Kachel weicht aus — und NUR sie', () => {
    // `a` ist auf 2×1 gewachsen und passt an (2,0) nicht mehr (nur eine Spalte
    // übrig). `b`/`c` bleiben, wo sie sind.
    const plan = planHomeStage([t('a', 2, 1), t('b'), t('c')], box, {
      placements: { a: { col: 2, row: 0 }, b: { col: 0, row: 0 }, c: { col: 1, row: 0 } },
    });
    expect(cellOf(plan, 'b')).toEqual({ col: 0, row: 0 });
    expect(cellOf(plan, 'c')).toEqual({ col: 1, row: 0 });
    expect(cellOf(plan, 'a')).toEqual({ col: 0, row: 1 });
  });

  /**
   * **Andis Livetest 23.08.**, wörtlich: „die uhr wird über die komplette höhe
   * einer seite angezeigt. ich kann kein widgent auf die linke seite der ersten
   * seite verschieben" — zwei Sätze, EINE Wurzel.
   *
   * Die Seite zeichnete so viele Zeilen, wie gerade belegt waren. Wer die
   * unterste Zeile frei ließ (und genau das tut ein Mensch, der selbst
   * anordnet), bekam eine Seite mit einer Zeile weniger: jede Kachel darauf
   * wuchs um diesen Anteil — die Uhr-L gemessen auf **583 × 525 px statt
   * 583 × 346** (`tools/zuhause-probe/zellen.mjs`, Saat `luecke`, 1366×900,
   * Chrome UND Firefox) —, und die fehlende Zeile war nicht nur unsichtbar,
   * sondern unerreichbar: `dropCellAt` teilt die Seitenhöhe durch die
   * GEZEICHNETEN Zeilen und klemmt. Der gemessene Zug auf (0,2) landete auf
   * (0,1).
   */
  it('die Lücke, die ein Mensch lässt, bleibt eine Lücke — die Seite behält ihre Zeilen', () => {
    // 900 × 300 ⇒ 3 Spalten × 2 Zeilen. Die Uhr-Lage: 2×2 links, unterste
    // Zeile frei — hier auf einer 3-Zeilen-Bühne, damit die Lücke sichtbar ist.
    const hoch = { width: 900, height: 450 };
    const plan = planHomeStage([t('uhr', 2, 2), t('klein')], hoch, {
      placements: { uhr: { col: 0, row: 0 }, klein: { col: 2, row: 0 } },
    });
    expect(plan.rowsPerPage).toBe(3);
    expect(plan.pages[0].rows).toBe(3); // NICHT 2, obwohl nur zwei Zeilen belegt sind

    // Und damit ist die unterste Zeile auch wirklich zu treffen: derselbe
    // Zeigerpunkt, gegen dasselbe Raster gerechnet, das die Seite zeichnet.
    const seite = { width: 900, height: 450, columns: plan.columns, rows: plan.pages[0].rows };
    expect(homeDropCell({ x: 100, y: 430 }, seite)).toEqual({ col: 0, row: 2 });

    // Der PACKER bleibt, wie er war: er packt dicht, seine einzige Lücke liegt
    // am Ende der letzten Seite, und drei Kacheln auf einer hohen Bühne sollen
    // drei bequeme Zeilen bekommen statt drei dünner plus leerer Fläche.
    const gepackt = planHomeStage([t('a'), t('b'), t('c')], hoch);
    expect(gepackt.rowsPerPage).toBe(3);
    expect(gepackt.pages[0].rows).toBe(1);
  });

  it('eine Zelle jenseits der Spaltenzahl (nach dem Drehen) wird geklemmt, nie fallengelassen', () => {
    const plan = planHomeStage([t('a')], { width: 600, height: 300 }, {
      placements: { a: { col: 3, row: 0 } }, // 600px ⇒ 2 Spalten
    });
    expect(plan.columns).toBe(2);
    expect(cellOf(plan, 'a')).toEqual({ col: 1, row: 0 });
  });

  it('eine 2-Zeilen-Kachel wird nie über die Seitengrenze gelegt (halb hier, halb dort gibt es nicht)', () => {
    // Gespeichert bei Zeile 1: auf einer 2-Zeilen-Bühne läge sie halb auf
    // Seite 1 und halb auf Seite 2. Sie bekommt stattdessen den nächsten
    // Platz, an dem sie GANZ steht — hier den obersten freien.
    const plan = planHomeStage([t('gross', 1, 2)], box, {
      placements: { gross: { col: 0, row: 1 } },
    });
    const at = cellOf(plan, 'gross')!;
    expect(Math.floor(at.row / plan.rowsPerPage)).toBe(Math.floor((at.row + 1) / plan.rowsPerPage));
    expect(at).toEqual({ col: 0, row: 0 });

    // Und ist oben kein Platz mehr, wandert sie ganz auf die nächste Seite —
    // nicht in die halbe Lücke darüber.
    const eng = planHomeStage([t('a'), t('b'), t('c'), t('gross', 1, 2)], box, {
      placements: {
        a: { col: 0, row: 0 },
        b: { col: 1, row: 0 },
        c: { col: 2, row: 0 },
        gross: { col: 0, row: 1 },
      },
    });
    expect(cellOf(eng, 'gross')).toEqual({ col: 0, row: 2 });
  });

  it('ein NUTZER-Ziel auf der Seitengrenze rutscht auf den Anfang der nächsten Seite', () => {
    // Der Unterschied zum Fall darüber: hier hat jemand gezielt hingezogen.
    // Ihn nach OBEN zu schieben wäre das Gegenteil dessen, was er wollte.
    const out = moveHomePlacement(
      { gross: { col: 0, row: 0 } },
      'gross',
      { col: 0, row: 1 },
      { gross: { cols: 1, rows: 2 } },
      { columns: 3, rowsPerPage: 2 },
    );
    expect(out.gross).toEqual({ col: 0, row: 2 });
  });
});

describe('W7-B — moveHomePlacement: der einzige Zug, der Zellen neu vergibt', () => {
  const geo = { columns: 3, rowsPerPage: 2 };
  const small = { a: { cols: 1, rows: 1 }, b: { cols: 1, rows: 1 }, c: { cols: 1, rows: 1 } };

  it('auf eine LEERE Zelle: nur die gezogene Kachel bewegt sich', () => {
    const out = moveHomePlacement(
      { a: { col: 0, row: 0 }, b: { col: 1, row: 0 } },
      'a',
      { col: 2, row: 1 },
      small,
      geo,
    );
    expect(out).toEqual({ a: { col: 2, row: 1 }, b: { col: 1, row: 0 } });
  });

  it('auf eine BELEGTE Zelle gleicher Größe: TAUSCH — nicht einfügen und nachschieben', () => {
    const out = moveHomePlacement(
      { a: { col: 0, row: 0 }, b: { col: 1, row: 0 }, c: { col: 2, row: 0 } },
      'a',
      { col: 2, row: 0 },
      small,
      geo,
    );
    // `b` in der Mitte wird NICHT angefasst — das ist der ganze Unterschied.
    expect(out).toEqual({
      a: { col: 2, row: 0 },
      b: { col: 1, row: 0 },
      c: { col: 0, row: 0 },
    });
  });

  it('bei ungleichen Größen weichen nur die VERDRÄNGTEN aus, der Rest steht', () => {
    const out = moveHomePlacement(
      { breit: { col: 0, row: 1 }, b: { col: 0, row: 0 }, c: { col: 1, row: 0 }, d: { col: 2, row: 0 } },
      'breit',
      { col: 0, row: 0 },
      { breit: { cols: 2, rows: 1 }, b: small.a, c: small.a, d: small.a },
      geo,
    );
    expect(out.breit).toEqual({ col: 0, row: 0 });
    expect(out.d).toEqual({ col: 2, row: 0 }); // unbeteiligt, bleibt
    // b und c mussten weichen — auf die freie Zeile darunter, nicht irgendwohin.
    expect([out.b, out.c]).toEqual([
      { col: 0, row: 1 },
      { col: 1, row: 1 },
    ]);
  });

  it('idempotent: dieselbe Kachel nochmal auf ihre eigene Zelle ⇒ unverändert', () => {
    const start = { a: { col: 1, row: 1 }, b: { col: 0, row: 0 } };
    expect(moveHomePlacement(start, 'a', { col: 1, row: 1 }, small, geo)).toEqual(start);
  });

  it('ein Ziel außerhalb der Bühne wird geklemmt, nicht gerechnet', () => {
    const out = moveHomePlacement({ a: { col: 0, row: 0 } }, 'a', { col: 99, row: -5 }, small, geo);
    expect(out.a).toEqual({ col: 2, row: 0 });
  });
});

describe('W7-B — homeDropCell: Zeigerpunkt ⇒ Zelle', () => {
  const geo = { width: 900, height: 300, columns: 3, rows: 2 };

  it('die Zelle unter dem Finger, seitenlokal', () => {
    expect(homeDropCell({ x: COL_X[0], y: ROW_Y[0] }, geo)).toEqual({ col: 0, row: 0 });
    expect(homeDropCell({ x: COL_X[2], y: ROW_Y[1] }, geo)).toEqual({ col: 2, row: 1 });
  });

  it('außerhalb wird geklemmt — der Finger darf über den Rand', () => {
    expect(homeDropCell({ x: -500, y: -500 }, geo)).toEqual({ col: 0, row: 0 });
    expect(homeDropCell({ x: 5000, y: 5000 }, geo)).toEqual({ col: 2, row: 1 });
  });

  it('die Lücke zwischen zwei Zellen gehört der Zelle davor', () => {
    expect(homeDropCell({ x: 296, y: ROW_Y[0] }, geo).col).toBe(0);
  });
});

describe('W7-B — der Speicher: additiv, gehärtet, und niemand verliert etwas', () => {
  it('MIGRATION: eine Datei ohne `placements` bleibt gültig — Stufen und Reihenfolge überleben', () => {
    const alt = '{"version":1,"order":[{"id":"news","size":"XL"},{"id":"uhr","size":"S"}]}';
    const out = parseHomeLayout(alt);
    expect(out.order[0]).toEqual({ id: 'news', size: 'XL' });
    expect(out.order[1]).toEqual({ id: 'uhr', size: 'S' });
    expect(out.placements).toBeUndefined();
  });

  it('eine unangetastete Anordnung schreibt EXAKT denselben Text wie vor W7 (kein leeres Feld)', () => {
    expect(serializeHomeLayout(DEFAULT_HOME_LAYOUT)).not.toContain('placements');
  });

  it('Zellen überleben die Rundreise durch JSON', () => {
    const withCells = withHomePlacements(DEFAULT_HOME_LAYOUT, 4, { uhr: { col: 3, row: 1 } });
    expect(parseHomeLayout(serializeHomeLayout(withCells)).placements).toEqual({
      '4': { uhr: { col: 3, row: 1 } },
    });
  });

  it('Härtung: Müll-Spaltenzahl, Fremd-Id, kaputte/negative/zu weit rechts liegende Zelle ⇒ verworfen', () => {
    const out = normalizeHomeLayout({
      version: 1,
      order: DEFAULT_HOME_LAYOUT.order,
      placements: {
        '9': { uhr: { col: 0, row: 0 } }, // neun Spalten gibt es nicht
        abc: { uhr: { col: 0, row: 0 } }, // keine Zahl
        '3': {
          jellyfin: { col: 0, row: 0 }, // kein Bühnen-Widget
          uhr: { col: 3, row: 0 }, // Spalte 3 bei 3 Spalten
          wetter: { col: -1, row: 0 },
          laeuft: { col: 0, row: 1.5 },
          einkauf: 'nein',
          news: { col: 1, row: 1 }, // der einzig gültige
        },
      },
    });
    expect(out.placements).toEqual({ '3': { news: { col: 1, row: 1 } } });
  });

  it('eine Stufe zu ändern lässt die Zellen unangetastet — Größe ist kein Umzug', () => {
    const start = withHomePlacements(DEFAULT_HOME_LAYOUT, 3, { uhr: { col: 2, row: 1 } });
    expect(withHomeTileSize(start, 'uhr', 'S').placements).toEqual({
      '3': { uhr: { col: 2, row: 1 } },
    });
  });

  it('ROTATION: quer und hoch sind zwei getrennte Anordnungen — ein Zug am einen fasst das andere nicht an', () => {
    const quer = withHomePlacements(DEFAULT_HOME_LAYOUT, 4, { uhr: { col: 3, row: 0 } });
    const beides = withHomePlacements(quer, 3, { uhr: { col: 0, row: 2 } });
    expect(beides.placements).toEqual({
      '4': { uhr: { col: 3, row: 0 } },
      '3': { uhr: { col: 0, row: 2 } },
    });
  });
});

/* ═══ W7-B · Am echten Bauteil ═════════════════════════════════════════════ */

describe('W7-B — die Bühne merkt sich die Zellen', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (tiles: HomeStageTile[]) => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };
  const tileEl = (id: string): HTMLElement =>
    container.querySelector(`[data-tile="${id}"]`) as HTMLElement;
  const stage = (): HTMLElement => container.querySelector('.idle__stage') as HTMLElement;
  const cells = (): Record<string, HomePlacementMap> =>
    (loadHomeLayout().placements ?? {}) as Record<string, HomePlacementMap>;
  /** Die Zelle, an der eine Kachel WIRKLICH gerendert steht (aus ihrem Inline-Stil). */
  const renderedCell = (id: string): HomeCell | null => {
    const el = container.querySelector<HTMLElement>(`[data-widget-id="${id}"]`);
    if (!el) return null;
    const page = el.closest('.idle__page');
    const pageIndex = Array.from(container.querySelectorAll('.idle__page')).indexOf(page!);
    const col = Number.parseInt(el.style.gridColumn, 10) - 1;
    const row = Number.parseInt(el.style.gridRow, 10) - 1;
    return { col, row: row + pageIndex * 2 }; // 2 Zeilen je Seite in diesem Raster
  };
  const fire = async (target: EventTarget, type: string, x: number, y: number) => {
    const evt = new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y });
    Object.defineProperty(evt, 'pointerId', { value: 1, configurable: true });
    await act(async () => {
      target.dispatchEvent(evt);
    });
  };
  const frame = async () => {
    await act(async () => {
      vi.advanceTimersByTime(20);
    });
  };
  const enterEdit = async (id: string) => {
    await fire(tileEl(id), 'pointerdown', 10, 10);
    await act(async () => {
      vi.advanceTimersByTime(600);
    });
    await fire(stage(), 'pointerup', 10, 10);
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    stubFlexLayout();
    saveHomeLayout(allSmall());
    localStorage.setItem('hoshi.homeTiles.vacuum', 'true');
    localStorage.setItem('hoshi.homeTiles.climate', 'true');
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('SAAT: die erste Messung friert das Bild ein, das der Packer ohnehin malte', async () => {
    // Vorher steht KEINE Zelle in der Datei (Migration: sie kommt aus W6).
    expect(cells()).toEqual({});
    await mount(ALL.map((id) => tile(id, 'S')));
    // Danach genau das, was zu sehen war — 3 Spalten, row-major.
    expect(cells()['3']).toEqual({
      uhr: { col: 0, row: 0 },
      wetter: { col: 1, row: 0 },
      laeuft: { col: 2, row: 0 },
      einkauf: { col: 0, row: 1 },
      vacuum: { col: 1, row: 1 },
      climate: { col: 2, row: 1 },
      news: { col: 0, row: 2 },
    });
  });

  it('ANDIS ORDER: ein Zug überlebt den Reload — und NUR die zwei beteiligten Kacheln bewegen sich', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    await enterEdit('uhr');
    // Uhr (0,0) auf den Platz von Klima (2,1) ziehen.
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', COL_X[2], ROW_Y[1]);
    await frame();
    await fire(stage(), 'pointerup', COL_X[2], ROW_Y[1]);

    const nach = cells()['3'];
    expect(nach.uhr).toEqual({ col: 2, row: 1 });
    expect(nach.climate).toEqual({ col: 0, row: 0 });
    // Alle übrigen stehen unverändert — kein Nachrücken.
    expect(nach.wetter).toEqual({ col: 1, row: 0 });
    expect(nach.laeuft).toEqual({ col: 2, row: 0 });
    expect(nach.einkauf).toEqual({ col: 0, row: 1 });
    expect(nach.vacuum).toEqual({ col: 1, row: 1 });
    expect(nach.news).toEqual({ col: 0, row: 2 });

    // RELOAD: neu montieren, derselbe Speicher — dasselbe Bild.
    await act(async () => root!.unmount());
    root = null;
    await mount(ALL.map((id) => tile(id, 'S')));
    expect(renderedCell('uhr')).toEqual({ col: 2, row: 1 });
    expect(renderedCell('climate')).toEqual({ col: 0, row: 0 });
  });

  it('ANDIS ORDER: eine unverdiente Kachel hinterlässt ihre LÜCKE — und kommt an ihren Platz zurück', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    expect(renderedCell('vacuum')).toEqual({ col: 1, row: 1 });

    // „Läuft" wird still (keine Countdowns) — der Aufrufer liefert sie nicht mehr.
    await act(async () => {
      root!.render(<HomeStage tiles={ALL.filter((id) => id !== 'laeuft').map((id) => tile(id, 'S'))} />);
    });
    // Die Zelle (2,0) bleibt leer, und NIEMAND rückt hinein.
    expect(tileEl('laeuft')).toBeNull();
    expect(renderedCell('einkauf')).toEqual({ col: 0, row: 1 });
    expect(renderedCell('vacuum')).toEqual({ col: 1, row: 1 });
    expect(renderedCell('news')).toEqual({ col: 0, row: 2 });
    // Der Speicher hat sie nicht vergessen.
    expect(cells()['3'].laeuft).toEqual({ col: 2, row: 0 });

    // Der Countdown ist zurück — und mit ihm die Kachel, am alten Platz.
    await act(async () => {
      root!.render(<HomeStage tiles={ALL.map((id) => tile(id, 'S'))} />);
    });
    expect(renderedCell('laeuft')).toEqual({ col: 2, row: 0 });
  });

  /**
   * **Andi 23.08. (Livetest):** *„ich konnte die widgets nicht verschieben,
   * wenn ich sie nach links und rechts geschoben habe."* — senkrecht ging.
   *
   * Der Riss läuft zwischen SPEICHER und BILD. `normalizeHomeLayout` prüft nur
   * `col < columns`; den Fußabdruck kennt es nicht. Eine 2 Spalten breite
   * Kachel auf `col: 2` ist damit gültig gespeichert und trotzdem unzeichenbar
   * (2 + 2 > 3) — genau der Zustand, in den Andi mit + und − gerät, denn eine
   * Stufenänderung lässt die Zelle bewusst stehen. `placeByCells` klemmt und
   * weicht aus, schreibt aber nichts zurück; rechnete ein Zug weiter gegen die
   * gespeicherte Zelle, rechnete er gegen ein Phantom.
   *
   * Die ZEILE übersteht das (Zeilen wachsen nach unten beliebig), die SPALTE
   * nicht — sie endet hart bei `columns − Fußabdruck`. Daher „senkrecht geht,
   * waagerecht nicht".
   */
  it('ANDIS LIVETEST: nach einem Zug sagt der Speicher dasselbe wie das Bild — sonst rechnet der nächste Zug gegen ein Phantom', async () => {
    // `wetter` ist 2 Spalten breit und steht auf Spalte 2: gültig gespeichert
    // (2 < 3), unzeichenbar (2 + 2 > 3). Genau der Zustand, in den ein Mensch
    // mit + und − gerät — eine Stufenänderung lässt die Zelle bewusst stehen.
    saveHomeLayout({
      version: 1,
      order: ALL.map((id) => ({ id, size: (id === 'wetter' ? 'M' : 'S') as HomeTileSize })),
      placements: {
        3: {
          uhr: { col: 0, row: 0 },
          laeuft: { col: 1, row: 0 },
          wetter: { col: 2, row: 0 },
          einkauf: { col: 0, row: 1 },
          vacuum: { col: 1, row: 1 },
          climate: { col: 2, row: 1 },
          news: { col: 0, row: 2 },
        },
      },
    });
    await mount(ALL.map((id) => tile(id, id === 'wetter' ? 'M' : 'S')));

    // Der Riss ist da: gespeichert Spalte 2, gezeichnet woanders.
    expect(cells()['3'].wetter).toEqual({ col: 2, row: 0 });
    expect(renderedCell('wetter')!.col).not.toBe(2);

    // EIN Zug — irgendeiner. Danach muss die Datei das Bild beschreiben.
    await enterEdit('uhr');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', COL_X[2], ROW_Y[1]);
    await frame();
    await fire(stage(), 'pointerup', COL_X[2], ROW_Y[1]);

    const gespeichert = cells()['3'];
    for (const id of ALL) {
      const gezeichnet = renderedCell(id);
      if (!gezeichnet) continue;
      expect({ id, ...gespeichert[id] }).toEqual({ id, ...gezeichnet });
    }
  });

  it('ROTATION am echten Bauteil: die 4-Spalten-Anordnung lässt die 3-Spalten-Anordnung in Ruhe', async () => {
    await mount(ALL.map((id) => tile(id, 'S')));
    expect(Object.keys(cells())).toEqual(['3']);
    // Breiter: 4 Spalten. Der Stub misst die Bühne über `STAGE_W` — hier wird
    // er einmal ausgetauscht, wie beim Drehen des iPads.
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
      this: HTMLElement,
    ) {
      return rect(0, 0, 1200, STAGE_H);
    });
    await act(async () => {
      observers.forEach((cb) => cb());
    });
    expect(Object.keys(cells()).sort()).toEqual(['3', '4']);
    // Die alte Anordnung steht unversehrt daneben.
    expect(cells()['3'].uhr).toEqual({ col: 0, row: 0 });
    expect(cells()['4'].news).toEqual({ col: 2, row: 1 });
  });
});
