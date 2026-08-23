/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { readFileSync } from 'node:fs';
import { createRoot, type Root } from 'react-dom/client';
import { HomeStage, type HomeStageTile } from '../components/HomeStage';
import { DEFAULT_HOME_LAYOUT, stepHomeTileSize, withHomeTileSize } from '../components/homeLayout';
import { HOME_LAYOUT_STORAGE_KEY, saveHomeLayout } from '../hooks/useHomeLayout';
import type { HomeTileSize } from '../components/homeWidgets';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * **homesizer.test** — die Bühne unter dem gespeicherten Layout (W3): sie
 * liest Reihenfolge und Stufe aus `hoshi.homeTiles.layout`, statt die
 * Registry-Defaults zu nehmen.
 *
 * jsdom rechnet kein Layout, also ist `getBoundingClientRect` gestubbt — der
 * Stub IST die Messung (Idiom `homestage.test.tsx`). Die Seiten-Arithmetik
 * selbst ist in `homelayout.test.ts` bewiesen; hier geht es nur darum, dass
 * der Speicher wirklich am DOM ankommt.
 */

const rect = (width: number, height: number): DOMRect =>
  ({
    x: 0,
    y: 0,
    width,
    height,
    top: 0,
    left: 0,
    right: width,
    bottom: height,
    toJSON: () => ({}),
  }) as DOMRect;

const stubLayout = (width: number, height: number) => {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => rect(width, height));
};

/** Eine Kachel, die ihre EFFEKTIVE Stufe ins DOM schreibt — so ist sie prüfbar. */
const tile = (id: string, size?: HomeTileSize): HomeStageTile => ({
  id,
  size,
  node: (effective) => (
    <article key={id} className="tile idle__tile" data-tile={id} data-size={effective}>
      {id}
    </article>
  ),
});

/** In-Memory-Storage in DOM-`Storage`-Form (Idiom `hometilessettings.test.tsx`). */
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

/** iPad quer im 920-px-Deckel: 3 Spalten, 2 Zeilen (Kurskorrektur 18.08.). */
const STAGE_W = 880;
const STAGE_H = 300;

describe('HomeStage — das gespeicherte Layout schlägt die Registry-Defaults', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (tiles: HomeStageTile[]): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };
  const sizeOf = (id: string): string | null =>
    container.querySelector(`[data-tile="${id}"]`)?.getAttribute('data-size') ?? null;
  const styleOf = (id: string): string =>
    container.querySelector(`[data-tile="${id}"]`)?.getAttribute('style') ?? '';
  const tileOrder = (): string[] =>
    Array.from(container.querySelectorAll('[data-tile]')).map((el) => el.getAttribute('data-tile') ?? '');

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    container = document.createElement('div');
    document.body.appendChild(container);
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('ohne gespeichertes Layout gilt der Registry-Default (Wetter L = 2×2)', async () => {
    stubLayout(STAGE_W, STAGE_H);
    await mount([tile('wetter', 'L'), tile('news', 'M')]);
    expect(sizeOf('wetter')).toBe('L');
    expect(styleOf('wetter')).toContain('span 2');
    expect(sizeOf('news')).toBe('M');
  });

  it('eine gespeicherte Stufe wirkt: news XL füllt die volle Bühnenbreite (3 Spalten × 2 Zeilen)', async () => {
    saveHomeLayout(withHomeTileSize(DEFAULT_HOME_LAYOUT, 'news', 'XL'));
    stubLayout(STAGE_W, STAGE_H);
    await mount([tile('news', 'M')]);
    expect(sizeOf('news')).toBe('XL');
    expect(styleOf('news')).toContain('grid-column: 1 / span 3');
    expect(styleOf('news')).toContain('grid-row: 1 / span 2');
  });

  it('die gespeicherte Stufe ist eine DECKE, keine Zusage: eine Spalte degradiert sie zu S', async () => {
    saveHomeLayout(withHomeTileSize(DEFAULT_HOME_LAYOUT, 'news', 'XL'));
    stubLayout(400, STAGE_H); // 1 Spalte
    await mount([tile('news', 'M')]);
    expect(sizeOf('news')).toBe('S');
    // Der gespeicherte Wert bleibt unangetastet (§0.4) — nur die Anzeige gibt nach.
    expect(JSON.parse(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY) as string).order).toContainEqual({
      id: 'news',
      size: 'XL',
    });
  });

  it('die gespeicherte REIHENFOLGE sortiert die Bühne (stabil, ohne Verschiebe-UI)', async () => {
    saveHomeLayout({
      version: 1,
      order: [
        { id: 'news', size: 'M' },
        { id: 'wetter', size: 'M' },
      ],
    });
    stubLayout(STAGE_W, STAGE_H);
    await mount([tile('wetter', 'M'), tile('news', 'M')]);
    expect(tileOrder()).toEqual(['news', 'wetter']);
  });

  it('eine Kachel OHNE Stufen-Vertrag bleibt 1×1 — der Speicher zieht sie nicht ins Raster', async () => {
    saveHomeLayout(withHomeTileSize(DEFAULT_HOME_LAYOUT, 'news', 'XL'));
    stubLayout(STAGE_W, STAGE_H);
    await mount([tile('news')]);
    expect(sizeOf('news')).toBe('M'); // der Bestands-Default für Kacheln ohne `size`
    expect(styleOf('news')).toContain('span 1');
  });

  it('eine Id, die das Layout nicht kennt, verschwindet nicht — sie bleibt am Ende stehen', async () => {
    stubLayout(STAGE_W, STAGE_H);
    await mount([tile('news', 'M'), tile('fremd', 'M')]);
    expect(tileOrder()).toEqual(['news', 'fremd']);
  });

  it('kaputter gespeicherter Text ⇒ Default-Layout, die Bühne rendert weiter', async () => {
    localStorage.setItem(HOME_LAYOUT_STORAGE_KEY, 'nicht mal JSON');
    stubLayout(STAGE_W, STAGE_H);
    await mount([tile('wetter', 'L'), tile('news', 'M')]);
    expect(sizeOf('wetter')).toBe('L');
    expect(sizeOf('news')).toBe('M');
  });
});

/* ── Der Schiedsrichter ─────────────────────────────────────────────────── */

/**
 * Der Pointer-Vertrag aus Codex' Gegenprüfung §3, angenommen im Bus-Entscheid
 * 20260818 §3: EINE Zustandsmaschine, EIN Besitzer, EIN Timer. Die Fälle hier
 * sind wörtlich die geforderten — 599/600 ms, waagerecht, senkrecht,
 * interaktiver Nachfahre, Abbruch, einzelne Seite.
 */
describe('HomeStage — Long-Press ↔ Swipe: EIN Schiedsrichter (Codex §3)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (tiles: HomeStageTile[]): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };
  const track = (): HTMLElement => container.querySelector('.idle__pages') as HTMLElement;
  /** Die Hülle mit den Zeiger-Horchern — NICHT die Schiene darin. */
  const stage = (): HTMLElement => container.querySelector('.idle__stage') as HTMLElement;
  const tileEl = (id: string): HTMLElement =>
    container.querySelector(`[data-tile="${id}"]`) as HTMLElement;
  const sizer = (): HTMLElement | null => container.querySelector('.idle__sizer');
  const fire = async (target: EventTarget, type: string, x: number, y: number, pointerId = 1) => {
    const evt = new MouseEvent(type, {
      bubbles: true,
      cancelable: true,
      clientX: x,
      clientY: y,
      // **`detail: 1` = „dieser Klick kommt aus einer Zeigergeste"**, und genau
      // daran unterscheidet `onClickCapture` den nachlaufenden Klick eines
      // Long-Press von einer Tastatur-/VoiceOver-Aktivierung (`detail: 0`).
      // Ohne diese Zeile simulierte die Suite Klicks, die kein Browser so
      // schickt — und prüfte damit den falschen Zweig.
      detail: 1,
    });
    Object.defineProperty(evt, 'pointerId', { value: pointerId, configurable: true });
    await act(async () => {
      target.dispatchEvent(evt);
    });
  };
  const tick = async (ms: number) => {
    await act(async () => {
      vi.advanceTimersByTime(ms);
    });
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    stubLayout(STAGE_W, STAGE_H);
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

  it('599 ms gedrückt: noch KEIN Wähler — bei 600 ms geht er auf', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await tick(599);
    expect(sizer()).toBeNull();
    await tick(1);
    expect(sizer()).not.toBeNull();
  });

  it('waagerechte Bewegung vor 600 ms ⇒ Seiten-Swipe, Long-Press ist tot', async () => {
    await mount([tile('wetter', 'L'), tile('laeuft', 'L'), tile('news', 'L')]); // erzwingt 2 Seiten
    expect(container.querySelectorAll('.idle__dot').length).toBeGreaterThan(1);
    await fire(tileEl('wetter'), 'pointerdown', 100, 100);
    await fire(track(), 'pointermove', 60, 100);
    await tick(1000);
    expect(sizer()).toBeNull();
    expect(track().getAttribute('data-dragging')).toBe('true');
  });

  it('SENKRECHTE Bewegung über die Tap-Toleranz tötet den Timer auch ohne Swipe', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await fire(track(), 'pointermove', 100, 130);
    await tick(1000);
    expect(sizer()).toBeNull();
    // …und ein Swipe wurde daraus auch nicht: die Schiene steht still.
    expect(track().getAttribute('data-dragging')).toBe('false');
  });

  it('ein Wackeln UNTERHALB der Tap-Toleranz lässt den Long-Press leben', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await fire(track(), 'pointermove', 104, 103);
    await tick(600);
    expect(sizer()).not.toBeNull();
  });

  it('interaktiver Nachfahre (Link) startet KEINEN Long-Press', async () => {
    await mount([
      {
        id: 'news',
        size: 'M',
        node: () => (
          <article key="news" className="tile idle__tile" data-tile="news">
            <a href="https://example.invalid" data-link="1">
              Schlagzeile
            </a>
          </article>
        ),
      },
    ]);
    await fire(container.querySelector('[data-link]') as HTMLElement, 'pointerdown', 100, 100);
    await tick(1000);
    expect(sizer()).toBeNull();
  });

  it('pointercancel tötet den Timer', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await fire(track(), 'pointercancel', 100, 100);
    await tick(1000);
    expect(sizer()).toBeNull();
  });

  it('lostpointercapture AN DER BÜHNE (Scroll-/Systemübernahme) tötet den Timer', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await fire(stage(), 'lostpointercapture', 100, 100);
    await tick(1000);
    expect(sizer()).toBeNull();
  });

  /**
   * **Der iPad-Befund vom 22.08.** („ich kann nicht über die Seiten scrollen …
   * am Laptop geht das", Andi wörtlich).
   *
   * Ein Touch-Punkt hängt vom `pointerdown` an IMPLIZIT an dem Element, das er
   * getroffen hat. Holt die Bühne die Capture zu sich, verliert dieses Element
   * sie — und sein `lostpointercapture` blubbert bis zur Bühne hoch. Wer dort
   * nicht fragt, WER sie verloren hat, würgt seine eigene, gerade begonnene
   * Geste ab. Eine Maus hat keine implizite Capture; deshalb war der Fehler am
   * Laptop unsichtbar und auf dem Glas total.
   */
  it('lostpointercapture eines NACHFAHREN ist die eigene Übernahme — die Geste lebt weiter', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await fire(tileEl('news'), 'lostpointercapture', 100, 100);
    await tick(1000);
    expect(sizer()).not.toBeNull();
  });

  it('ein ZWEITER Pointer nimmt beiden die Geste weg', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100, 1);
    await fire(tileEl('news'), 'pointerdown', 200, 100, 2);
    await tick(1000);
    expect(sizer()).toBeNull();
  });

  it('EINE Seite: der Long-Press lebt trotzdem (nicht an `pageCount < 2` gekoppelt)', async () => {
    await mount([tile('news', 'M')]);
    expect(container.querySelector('.idle__dot')).toBeNull(); // wirklich nur eine Seite
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await tick(600);
    expect(sizer()).not.toBeNull();
  });

  it('nach erfolgreichem Long-Press: der Folge-Klick wird geschluckt', async () => {
    const onClick = vi.fn();
    await mount([
      {
        id: 'news',
        size: 'M',
        node: () => (
          <article key="news" className="tile idle__tile" data-tile="news" onClick={onClick}>
            news
          </article>
        ),
      },
    ]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await tick(600);
    await fire(track(), 'pointerup', 100, 100);
    await fire(tileEl('news'), 'click', 100, 100);
    expect(onClick).not.toHaveBeenCalled();
    // Der ZWEITE Klick kommt wieder durch — das Veto gilt genau einmal.
    await fire(tileEl('news'), 'click', 100, 100);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('nach erfolgreichem Long-Press: das native Kontextmenü bleibt zu', async () => {
    await mount([tile('news', 'M')]);
    await fire(tileEl('news'), 'pointerdown', 100, 100);
    await tick(600);
    const evt = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
    await act(async () => {
      tileEl('news').dispatchEvent(evt);
    });
    expect(evt.defaultPrevented).toBe(true);
  });

  it('Rechtsklick auf eine Kachel öffnet den Wähler (der Zeiger-Weg ohne Edit-Modus)', async () => {
    await mount([tile('news', 'M')]);
    const evt = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
    await act(async () => {
      tileEl('news').dispatchEvent(evt);
    });
    expect(sizer()).not.toBeNull();
    expect(evt.defaultPrevented).toBe(true);
  });
});

/* ── Der Stufen-Wähler ──────────────────────────────────────────────────── */

describe('HomeStage — der Stufen-Wähler (§4.2)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (tiles: HomeStageTile[]): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };
  const open = async (id: string): Promise<void> => {
    const el = container.querySelector(`[data-tile="${id}"]`) as HTMLElement;
    const down = new MouseEvent('pointerdown', { bubbles: true, cancelable: true, clientX: 10, clientY: 10 });
    Object.defineProperty(down, 'pointerId', { value: 1, configurable: true });
    await act(async () => {
      el.dispatchEvent(down);
    });
    await act(async () => {
      vi.advanceTimersByTime(600);
    });
    // …und loslassen: so sieht die echte Geste aus, und erst danach ist der
    // Schiedsrichter wieder frei für den nächsten Druck.
    const up = new MouseEvent('pointerup', { bubbles: true, cancelable: true, clientX: 10, clientY: 10 });
    Object.defineProperty(up, 'pointerId', { value: 1, configurable: true });
    await act(async () => {
      el.dispatchEvent(up);
    });
  };
  const buttons = (): HTMLButtonElement[] =>
    Array.from(container.querySelectorAll('.idle__sizerbtn'));
  const sizer = (): HTMLElement | null => container.querySelector('.idle__sizer');

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    stubLayout(STAGE_W, STAGE_H);
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

  /** Der `−`- bzw. `+`-Knopf (W6) — nach Richtung, nicht nach Position. */
  const step = (dir: 'down' | 'up'): HTMLButtonElement =>
    container.querySelector(`.idle__sizerbtn[data-dir="${dir}"]`) as HTMLButtonElement;

  it('W6: ZWEI Richtungs-Knöpfe mit den ARIA-Worten, dazwischen die aktuelle Stufe', async () => {
    // Andi 20.08.: „Die Größenauswahl soll ein + und − sein." Vier Stufen-
    // Knöpfe verlangten, dass man die Zuordnung Buchstabe→Größe schon kennt;
    // zwei Richtungen verlangen nur, dass man weiß, wohin man will.
    await mount([tile('news', 'M')]);
    await open('news');
    expect(buttons().map((b) => b.textContent)).toEqual(['−', '+']);
    expect(buttons().map((b) => b.getAttribute('aria-label'))).toEqual(['Kleiner', 'Größer']);
    /* **W7-D, Andi 21.08.:** *„Das M darunter verändert die Größe des
       Widgets, das macht es schwer platzierbar."* Der Buchstabe fürs AUGE ist
       weg — die Kachel selbst zeigt ihre Stufe, indem sie so groß ist. Das
       Wort fürs OHR bleibt, sonst gäbe ein Druck auf `+` gar keine hörbare
       Rückmeldung (am Knopf selbst ändert sich nichts). */
    const stepBox = container.querySelector('.idle__sizerstep') as HTMLElement;
    expect(stepBox.textContent).toBe('Mittel');
    expect(stepBox.className).toContain('idle__sronly');
    expect(stepBox.getAttribute('aria-live')).toBe('polite');
    expect(sizer()?.getAttribute('aria-label')).toBe('Größe für Nachrichten');
    expect(sizer()?.getAttribute('role')).toBe('group');
  });

  it('W6: an BEIDEN Enden graut der jeweilige Knopf aus — und nur dort', async () => {
    // Der Riegel der Bestellung: „sobald es nicht größer werden kann, sind die
    // entsprechenden Pfeile ausgegraut". Geprüft werden alle vier Kanten, die
    // es gibt: kleinste/größte Stufe bei einem Widget MIT und einem OHNE XL.
    // Der Wähler liest die GESPEICHERTE Stufe (§5.1), nicht die Kachel-Prop —
    // also wird sie hier auch gespeichert.
    saveHomeLayout(withHomeTileSize(DEFAULT_HOME_LAYOUT, 'news', 'S'));
    await mount([tile('news', 'S')]);
    await open('news');
    expect(step('down').disabled).toBe(true); // S ist die kleinste
    expect(step('up').disabled).toBe(false);

    await act(async () => {
      step('up').click(); // S → M
    });
    expect(step('down').disabled).toBe(false); // in der Mitte ist beides offen
    expect(step('up').disabled).toBe(false);

    await act(async () => {
      step('up').click(); // M → L
    });
    await act(async () => {
      step('up').click(); // L → XL, die größte
    });
    expect(container.querySelector('.idle__sizerstep')?.textContent).toBe('Sehr groß');
    expect(step('up').disabled).toBe(true);
    expect(step('down').disabled).toBe(false);
  });

  it('W6: die Uhr hat kein XL — bei ihr graut `+` schon auf L aus (Registry, §1.1)', async () => {
    // Bis W5 äußerte sich das als FEHLENDER vierter Knopf: dieselbe Leiste
    // hatte je nach Kachel drei oder vier Felder und sprang beim Wechsel.
    // Jetzt sind es immer zwei — die Grenze ist sichtbar statt spurlos.
    // (Bis 22.08. war der Sauger das Beispiel; er hat seit Andis „was passt
    // noch rein, wenn man das Widget größer macht?" ein XL.)
    await mount([tile('uhr', 'L')]);
    await open('uhr');
    expect(buttons()).toHaveLength(2);
    expect(step('up').disabled).toBe(true);
    expect(step('down').disabled).toBe(false);
  });

  it('eine Stufe wählen wirkt sofort UND wird gespeichert', async () => {
    await mount([tile('news', 'M')]);
    await open('news');
    await act(async () => {
      step('up').click(); // M → L
    });
    expect(container.querySelector('[data-tile="news"]')?.getAttribute('data-size')).toBe('L');
    expect(JSON.parse(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY) as string).order).toContainEqual({
      id: 'news',
      size: 'L',
    });
  });

  it('der Fokus bleibt nach dem Größenwechsel auf dem gedrückten Knopf', async () => {
    await mount([tile('news', 'M')]);
    await open('news');
    await act(async () => {
      step('up').click();
    });
    expect(document.activeElement?.getAttribute('data-dir')).toBe('up');
  });

  it('W6: graut der gedrückte Knopf durch seinen EIGENEN Druck aus, fängt der Gegenknopf den Fokus', async () => {
    // Ein `disabled`-Element ist nicht fokussierbar — ohne diesen Rückfall
    // fiele der Fokus ans Dokument-Ende und die Tastatur-Bedienung wäre nach
    // genau einem Schritt vorbei.
    saveHomeLayout(withHomeTileSize(DEFAULT_HOME_LAYOUT, 'uhr', 'M'));
    await mount([tile('uhr', 'M')]);
    await open('uhr');
    await act(async () => {
      step('up').click(); // M → L, und L ist bei der Uhr das Ende
    });
    expect(step('up').disabled).toBe(true);
    expect(document.activeElement?.getAttribute('data-dir')).toBe('down');
  });

  it('passt die gewählte Stufe hier nicht, sagt der Wähler es (statt still zu schrumpfen)', async () => {
    stubLayout(400, STAGE_H); // eine Spalte ⇒ alles fällt auf S
    await mount([tile('news', 'M')]);
    await open('news');
    await act(async () => {
      step('up').click(); // L gespeichert …
    });
    expect(container.querySelector('.idle__sizernote')?.textContent).toBe('Auf diesem Bildschirm: Klein');
    expect(container.querySelector('[data-tile="news"]')?.getAttribute('data-size')).toBe('S');
  });

  it('Escape schließt den Wähler', async () => {
    await mount([tile('news', 'M')]);
    await open('news');
    await act(async () => {
      (sizer() as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
      );
    });
    expect(sizer()).toBeNull();
  });

  it('ein Druck daneben schließt ihn ebenfalls', async () => {
    await mount([tile('news', 'M'), tile('wetter', 'M')]);
    await open('news');
    expect(sizer()).not.toBeNull();
    const down = new MouseEvent('pointerdown', { bubbles: true, cancelable: true, clientX: 9, clientY: 9 });
    Object.defineProperty(down, 'pointerId', { value: 2, configurable: true });
    await act(async () => {
      (container.querySelector('[data-tile="wetter"]') as HTMLElement).dispatchEvent(down);
    });
    expect(sizer()).toBeNull();
  });

  it('die Kachel unter dem offenen Wähler ist markiert (das Zucken bei 600 ms)', async () => {
    await mount([tile('news', 'M')]);
    await open('news');
    expect(container.querySelector('[data-tile="news"]')?.getAttribute('data-sizing')).toBe('true');
    expect(container.querySelector('.idle__pages')?.getAttribute('data-sizing')).toBe('true');
  });

  it('verschwindet die Kachel, verschwindet der Wähler mit ihr', async () => {
    await mount([tile('news', 'M'), tile('wetter', 'M')]);
    await open('news');
    expect(sizer()).not.toBeNull();
    await act(async () => {
      root!.render(<HomeStage tiles={[tile('wetter', 'M')]} />);
    });
    expect(sizer()).toBeNull();
  });
});

/**
 * jsdom rechnet weder CSS noch Media-Queries — die Bewegungs-Auflage lässt
 * sich hier nur an der ausgelieferten Datei prüfen (Idiom `onewindow.test.ts`,
 * das die Rasterzahlen genauso pinnt). Das reicht für die Frage, die zählt:
 * gibt es die Ausnahme überhaupt, und hebt sie das Anheben auf?
 */
describe('Stufen-Wähler — prefers-reduced-motion (Auflage des Hauses)', () => {
  const css = readFileSync('src/index.css', 'utf8');

  it('das Anheben der Kachel ist unter reduzierter Bewegung aufgehoben', () => {
    const block = css.slice(css.indexOf('@media (prefers-reduced-motion: reduce)', css.indexOf('.idle__sizer')));
    expect(block).toContain(".idle__tile[data-sizing='true']");
    expect(block.slice(0, 400)).toContain('transform: none');
    expect(block.slice(0, 400)).toContain('outline: 2px dashed');
  });

  it('die Wähler-Knöpfe sind 44 px groß (Fingerziel auf dem Flur-Display)', () => {
    const rule = css.slice(css.indexOf('.idle__sizerbtn {'), css.indexOf('.idle__sizerbtn:hover'));
    expect(rule).toContain('width: 44px');
    expect(rule).toContain('height: 44px');
  });

  it('W6: der ausgegraute Knopf ist gedämpft, nicht versteckt', () => {
    // „Ausgegraut" ist die Bestellung (Andi 20.08.) — die Grenze soll SICHTBAR
    // sein. Ein `display:none` wäre die bequeme Antwort und die falsche: dann
    // spränge die Leiste wieder in der Breite, genau wie bei den vier
    // Stufen-Knöpfen zuvor.
    const rule = css.slice(css.indexOf('.idle__sizerbtn:disabled {'), css.indexOf('.idle__sizerbtn:focus-visible'));
    expect(rule).toContain('opacity');
    expect(rule).not.toContain('display: none');
    expect(rule).not.toContain('visibility: hidden');
  });
});

/* ── W6: die reine Stufen-Leiter (ohne DOM) ─────────────────────────────── */

describe('stepHomeTileSize — eine Stufe rauf/runter, `null` heißt ausgegraut', () => {
  const full = ['S', 'M', 'L', 'XL'] as const;
  const noXl = ['S', 'M', 'L'] as const;

  it('geht innerhalb der ERLAUBTEN Stufen und meldet die Kanten mit null', () => {
    expect(stepHomeTileSize(full, 'S', -1)).toBeNull();
    expect(stepHomeTileSize(full, 'S', 1)).toBe('M');
    expect(stepHomeTileSize(full, 'L', 1)).toBe('XL');
    expect(stepHomeTileSize(full, 'XL', 1)).toBeNull();
    expect(stepHomeTileSize(full, 'XL', -1)).toBe('L');
  });

  it('die Registry-Grenze IST die Kante: ohne XL endet `+` schon auf L', () => {
    expect(stepHomeTileSize(noXl, 'L', 1)).toBeNull();
    expect(stepHomeTileSize(noXl, 'L', -1)).toBe('M');
  });

  it('rauf und runter sind zueinander invers — kein Sprung, der nur in eine Richtung geht', () => {
    for (const s of ['M', 'L'] as const) {
      const up = stepHomeTileSize(full, s, 1)!;
      expect(stepHomeTileSize(full, up, -1)).toBe(s);
    }
  });

  it('eine Stufe, die dieses Widget gar nicht kann, bewegt nichts (unerreichbar, aber total)', () => {
    // `normalizeHomeLayout` zieht jeden gespeicherten Wert auf eine erlaubte
    // Stufe — hier steht nur, dass die Funktion nicht rät, wenn sie doch je
    // anders gerufen wird.
    expect(stepHomeTileSize(noXl, 'XL', -1)).toBeNull();
    expect(stepHomeTileSize([], 'M', 1)).toBeNull();
  });
});
