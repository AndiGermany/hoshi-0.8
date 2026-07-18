/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { NightWindowDial, angleToTime, timeToAngle } from '../components/NightWindowDial';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

describe('timeToAngle — HH:mm → Grad, 0 = Mitternacht oben, im Uhrzeigersinn', () => {
  it('Viertelstunden des Tages an den Kardinalpunkten', () => {
    expect(timeToAngle('00:00')).toBe(0);
    expect(timeToAngle('06:00')).toBe(90);
    expect(timeToAngle('12:00')).toBe(180);
    expect(timeToAngle('18:00')).toBe(270);
  });

  it('krumme Zeit rechnet linear (22:00 → 330°)', () => {
    expect(timeToAngle('22:00')).toBe(330);
    expect(timeToAngle('23:30')).toBe(352.5);
  });

  it('kaputte/unparsebare Eingabe → 0 statt Wurf', () => {
    expect(timeToAngle('nope')).toBe(0);
    expect(timeToAngle('')).toBe(0);
  });
});

describe('angleToTime — Grad → HH:mm, 5-Min-Snap, Mitternachts-Rollover', () => {
  it('Kardinalpunkte zurück auf die Uhrzeit', () => {
    expect(angleToTime(0)).toBe('00:00');
    expect(angleToTime(90)).toBe('06:00');
    expect(angleToTime(180)).toBe('12:00');
    expect(angleToTime(270)).toBe('18:00');
  });

  it('rastet auf 5-Minuten-Schritte (kein krummer Wert)', () => {
    // 358° = 1432 Min = 23:52 → rastet auf 23:50 (nächste 5-Min-Marke).
    expect(angleToTime(358)).toBe('23:50');
  });

  it('Rollover über Mitternacht: nahe 360° rastet sauber auf 00:00', () => {
    // 359.9° ≈ 1439.6 Min → /5 = 287.92 → gerundet 288 × 5 = 1440 → wrap → 00:00.
    expect(angleToTime(359.9)).toBe('00:00');
  });

  it('negative/übergroße Winkel werden normalisiert', () => {
    expect(angleToTime(-90)).toBe(angleToTime(270));
    expect(angleToTime(450)).toBe(angleToTime(90));
  });

  it('snapMinutes ist konfigurierbar', () => {
    // 91° = 364 Min = 06:04 → mit 15-Min-Raster auf 06:00.
    expect(angleToTime(91, 15)).toBe('06:00');
  });
});

describe('NightWindowDial — Render (SSR-fähig, prop-getrieben)', () => {
  it('rendert ein SVG mit zwei Griffen und dem Nacht-Bogen', () => {
    const html = renderToStaticMarkup(
      <NightWindowDial from="22:00" to="07:00" onFromChange={() => {}} onToChange={() => {}} />,
    );
    expect(html).toContain('nightdial__handle--from');
    expect(html).toContain('nightdial__handle--to');
    expect(html).toContain('nightdial__arc');
    expect(html).toContain('aria-hidden="true"');
  });

  it('disabled ⇒ die gedämpfte Klasse steht am SVG-Root', () => {
    const html = renderToStaticMarkup(
      <NightWindowDial from="22:00" to="07:00" onFromChange={() => {}} onToChange={() => {}} disabled />,
    );
    expect(html).toContain('nightdial is-disabled');
  });

  it('Rollover-Bogen (from > to, über Mitternacht, >12h-Fenster): großer Bogenwinkel', () => {
    // 18:00 → 09:00 überspannt Mitternacht mit 15 Std. Fensterlänge
    // (Sweep = 225° > 180 ⇒ large-arc-flag 1).
    const html = renderToStaticMarkup(
      <NightWindowDial from="18:00" to="09:00" onFromChange={() => {}} onToChange={() => {}} />,
    );
    const pathMatch = /<path[^>]*d="([^"]+)"/.exec(html);
    expect(pathMatch).not.toBeNull();
    const d = pathMatch![1];
    // „A r r 0 <largeArc> <sweepFlag> x y" — largeArc muss 1 sein (Bogen > 180°).
    expect(d).toMatch(/A [\d.]+ [\d.]+ 0 1 1/);
  });

  it('kürzeres Rollover-Fenster (22:00 → 07:00, 9h): kleiner Bogenwinkel trotz Mitternacht', () => {
    // Sweep = 135° < 180 ⇒ large-arc-flag 0, obwohl das Fenster Mitternacht umschließt —
    // der große-Bogen-Flag hängt an der FENSTERLÄNGE, nicht am Rollover selbst.
    const html = renderToStaticMarkup(
      <NightWindowDial from="22:00" to="07:00" onFromChange={() => {}} onToChange={() => {}} />,
    );
    const pathMatch = /<path[^>]*d="([^"]+)"/.exec(html);
    const d = pathMatch![1];
    expect(d).toMatch(/A [\d.]+ [\d.]+ 0 0 1/);
  });

  it('kurzer Bogen (from < to, kein Rollover): kleiner Bogenwinkel', () => {
    // 09:00 → 17:00 (Tagfenster-Analogon) — Sweep = 120° < 180 ⇒ large-arc-flag 0.
    const html = renderToStaticMarkup(
      <NightWindowDial from="09:00" to="17:00" onFromChange={() => {}} onToChange={() => {}} />,
    );
    const pathMatch = /<path[^>]*d="([^"]+)"/.exec(html);
    const d = pathMatch![1];
    expect(d).toMatch(/A [\d.]+ [\d.]+ 0 0 1/);
  });
});

describe('NightWindowDial — Ziehen synchronisiert (jsdom, Pointer-Events)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  /** Fester Mittelpunkt (0,0), Größe 240×240 — unabhängig von jsdoms (Zero-)Layout. */
  const RECT = { x: 0, y: 0, width: 240, height: 240, top: 0, left: 0, right: 240, bottom: 240, toJSON: () => ({}) } as DOMRect;

  const pointerEvt = (type: string, clientX: number, clientY: number, pointerId = 1): Event => {
    const evt = new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY });
    Object.defineProperty(evt, 'pointerId', { value: pointerId, configurable: true });
    return evt;
  };

  beforeEach(() => {
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
    vi.restoreAllMocks();
  });

  it('Ziehen des „Abend"-Griffs auf 6-Uhr-Position ⇒ onFromChange("06:00")', async () => {
    const onFromChange = vi.fn();
    root = createRoot(container);
    await act(async () => {
      root!.render(
        <NightWindowDial from="22:00" to="07:00" onFromChange={onFromChange} onToChange={() => {}} size={240} />,
      );
    });
    const svg = container.querySelector('svg.nightdial') as unknown as { getBoundingClientRect: () => DOMRect };
    svg.getBoundingClientRect = () => RECT;

    const handle = container.querySelector('.nightdial__handle--from')!;
    // Zentrum = (120,120). „6 Uhr"-Position (90°) liegt rechts vom Zentrum: (120+r, 120).
    await act(async () => {
      handle.dispatchEvent(pointerEvt('pointerdown', 220, 20));
      handle.dispatchEvent(pointerEvt('pointermove', 240, 120));
      handle.dispatchEvent(pointerEvt('pointerup', 240, 120));
    });

    expect(onFromChange).toHaveBeenCalled();
    expect(onFromChange).toHaveBeenLastCalledWith('06:00');
  });

  it('Ziehen des „Morgen"-Griffs rastet auf 5-Minuten-Schritte', async () => {
    const onToChange = vi.fn();
    root = createRoot(container);
    await act(async () => {
      root!.render(
        <NightWindowDial from="22:00" to="07:00" onFromChange={() => {}} onToChange={onToChange} size={240} />,
      );
    });
    const svg = container.querySelector('svg.nightdial') as unknown as { getBoundingClientRect: () => DOMRect };
    svg.getBoundingClientRect = () => RECT;

    const handle = container.querySelector('.nightdial__handle--to')!;
    // Punkt knapp neben der 12-Uhr-Position (unten, 180°): (120, 240) wäre exakt 12:00;
    // ein Punkt leicht rechts davon ergibt einen krummen Winkel, der gerastet wird.
    await act(async () => {
      handle.dispatchEvent(pointerEvt('pointerdown', 120, 240));
      handle.dispatchEvent(pointerEvt('pointermove', 125, 239));
      handle.dispatchEvent(pointerEvt('pointerup', 125, 239));
    });

    expect(onToChange).toHaveBeenCalled();
    const calls = onToChange.mock.calls;
    const called = calls[calls.length - 1][0] as string;
    // Ergebnis muss ein gültiges HH:mm mit Minuten-Vielfachem von 5 sein.
    const m = /^(\d{2}):(\d{2})$/.exec(called);
    expect(m).not.toBeNull();
    expect(Number(m![2]) % 5).toBe(0);
  });

  it('Bewegung ohne vorheriges PointerDown ändert nichts (kein Drag ohne Griff)', async () => {
    const onFromChange = vi.fn();
    root = createRoot(container);
    await act(async () => {
      root!.render(
        <NightWindowDial from="22:00" to="07:00" onFromChange={onFromChange} onToChange={() => {}} size={240} />,
      );
    });
    const svg = container.querySelector('svg.nightdial') as unknown as { getBoundingClientRect: () => DOMRect };
    svg.getBoundingClientRect = () => RECT;

    const handle = container.querySelector('.nightdial__handle--from')!;
    await act(async () => {
      handle.dispatchEvent(pointerEvt('pointermove', 240, 120));
    });
    expect(onFromChange).not.toHaveBeenCalled();
  });
});
