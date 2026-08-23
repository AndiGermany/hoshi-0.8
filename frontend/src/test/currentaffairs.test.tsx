import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  CURRENT_AFFAIRS_EXPANDED_COUNT,
  CURRENT_AFFAIRS_S_COUNT,
  CURRENT_AFFAIRS_WINDOW_COUNT,
  CurrentAffairsWindow,
  renderableCurrentAffairs,
} from '../components/CurrentAffairsTile';
import type { HomeTileSize } from '../components/homeWidgets';
import { IdleFace } from '../components/IdleFace';
import {
  fetchCurrentAffairs,
  parseCurrentAffairs,
  type CurrentAffairs,
  type CurrentAffairsFreshness,
  type CurrentAffairsItem,
  type CurrentAffairsState,
} from '../hooks/useCurrentAffairs';
import { dueClock } from '../hooks/useScheduledItems';
import { de } from '../i18n/de';

/**
 * **currentaffairs.test** — the "Lagebild" window (order F5, wave 1), built
 * against the JSON contract via fixtures, never against a live server. The
 * component is pure/prop-driven (`state`/`nowMs`/`size`), so
 * `renderToStaticMarkup` covers every size step without a DOM.
 *
 * **W1 rebuild (DESIGN-widget-raster-2026-08-18 §3.4/§8.3):** the old
 * `expanded`/`onToggle` API and its "mehr"/"weniger" toggle button are gone
 * WITHOUT replacement — `size` now decides how many headlines render, and
 * there is no interactive state left inside the component at all. `'M'`
 * (the registry default, byte-identical content to the old collapsed view)
 * replaces `expanded=false`; `'L'` (the registry has no widget default this
 * large for news today, but it is what W3/W4's picker will offer) replaces
 * `expanded=true`.
 *
 * The load-bearing rules proven here:
 *  - S/M/L show one/three/six cards respectively,
 *  - `EMPTY`/`UNAVAILABLE` (and every non-live state) render NOTHING,
 *  - `STALE` drops the `live` pill and adds the amber age hint,
 *  - the "Stand" line comes from `lastSuccessfulRefreshAt` and NOT from
 *    `observedAt` — the fixtures deliberately give the two different times.
 */

/* ── fixtures (contract-shaped wire JSON) ───────────────────────────────── */

const OBSERVED_AT = '2026-08-15T11:59:00Z';
const REFRESHED_AT = '2026-08-15T08:45:00Z';
const NOW_MS = Date.parse('2026-08-15T12:00:00Z');

/** ASCII only — the i18n sweep measures OUR strings, not the fixtures. */
function wireItem(n: number, over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: `item-${n}`,
    source: 'TAGESSCHAU',
    title: `Headline number ${n}`,
    snippet: `Teaser text of headline ${n}.`,
    canonicalUrl: `https://example.test/article-${n}`,
    publishedAt: '2026-08-15T08:30:00Z',
    fetchedAt: '2026-08-15T08:45:00Z',
    ...over,
  };
}

function wireBody(count: number, freshness: CurrentAffairsFreshness = 'FRESH'): unknown {
  return {
    items: Array.from({ length: count }, (_, i) => wireItem(i + 1)),
    observedAt: OBSERVED_AT,
    lastSuccessfulRefreshAt: REFRESHED_AT,
    freshness,
  };
}

/** Parses a wire fixture into the live state the component consumes. */
function live(body: unknown): CurrentAffairsState {
  const data = parseCurrentAffairs(body);
  if (data === null) throw new Error('fixture does not satisfy the contract');
  return { kind: 'live', data };
}

function render(state: CurrentAffairsState | null, size: HomeTileSize = 'M'): string {
  return renderToStaticMarkup(<CurrentAffairsWindow state={state} nowMs={NOW_MS} size={size} />);
}

/* ── 1 · the window itself ──────────────────────────────────────────────── */

describe('Lagebild-Fenster — hoechstens drei Karten aus der Fixture', () => {
  it('fuenf Meldungen ⇒ genau drei Karten, Titel + Quelle + relative Zeit', () => {
    const html = render(live(wireBody(5)));
    expect(html).toContain('Headline number 1');
    expect(html).toContain('Headline number 3');
    expect(html).not.toContain('Headline number 4');
    // Quelle + relative Zeit stehen in der Meta-Zeile (publishedAt = 3,5 h alt).
    expect(html).toContain('TAGESSCHAU');
    expect(html).toContain(de.idleFace.homeTiles.age.hoursAgo(3));
    // Genau drei Karten.
    expect(html.split('idle__newsitem').length - 1).toBe(CURRENT_AFFAIRS_WINDOW_COUNT);
  });

  it('Klick-Ziel ist die canonicalUrl in einem neuen Tab', () => {
    const html = render(live(wireBody(1)));
    expect(html).toContain('href="https://example.test/article-1"');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener"');
  });

  it('kollabiert zeigt das Fenster KEINE Teaser und KEINE Quellen-Aktion', () => {
    const html = render(live(wireBody(5)));
    expect(html).not.toContain('Teaser text');
    expect(html).not.toContain(de.idleFace.currentAffairs.openSource);
  });

  it('M zeigt bei Ueberhang KEINE „+N"-Andeutung mehr — der Knopf ist ersatzlos weg (§3.4/§8.3)', () => {
    // Frueher versprach ein "mehr"-Knopf hier die verborgenen Meldungen; die
    // Groesse IST jetzt die Expansion, es gibt keine Zwischenstufe, die auf
    // eine groessere hindeutet — Andi zieht am Griff (W3/W4), nicht am Text.
    expect(render(live(wireBody(5)))).not.toContain('idle__newsmore');
    expect(render(live(wireBody(20)))).not.toContain('idle__newsmore');
  });

  it('S zeigt genau eine Karte, unabhaengig von der Fixture-Groesse', () => {
    for (const count of [1, 5, 20]) {
      const html = render(live(wireBody(count)), 'S');
      expect(html.split('idle__newsitem').length - 1).toBe(CURRENT_AFFAIRS_S_COUNT);
    }
    expect(render(live(wireBody(5)), 'S')).toContain('Headline number 1');
  });
});

/* ── 2 · L — die grosse Stufe (Ersatz fuer Route ②/die alte „mehr"-Expansion,
   s. Komponenten-KDoc; W1 DESIGN-widget-raster-2026-08-18 §3.4/§8.3: keine
   interaktive Expansion mehr, die GROESSE ist die Expansion) ──────────────── */

describe('Lagebild — L ist die volle Liste', () => {
  it('L ⇒ alle Meldungen bis zum Deckel, mit Teaser-Zeile und „Quelle oeffnen"', () => {
    const html = render(live(wireBody(5)), 'L');
    expect(html).toContain('Headline number 5');
    expect(html).toContain('Teaser text of headline 5.');
    expect(html).toContain(de.idleFace.currentAffairs.openSource);
    expect(html.split('idle__newsitem').length - 1).toBe(5);
  });

  it('VIEWPORT-RIEGEL: 20 Meldungen ⇒ genau 6 Karten, der Rest wird ehrlich gezaehlt', () => {
    const html = render(live(wireBody(20)), 'L');
    expect(html.split('idle__newsitem').length - 1).toBe(CURRENT_AFFAIRS_EXPANDED_COUNT);
    expect(html).toContain('Headline number 6');
    expect(html).not.toContain('Headline number 7');
    expect(html).toContain(de.idleFace.currentAffairs.restNotShown(20 - CURRENT_AFFAIRS_EXPANDED_COUNT));
  });

  it('hoechstens sechs Meldungen ⇒ gar keine Rest-Zeile (nichts zu verschweigen)', () => {
    for (const count of [4, CURRENT_AFFAIRS_EXPANDED_COUNT]) {
      const html = render(live(wireBody(count)), 'L');
      expect(html.split('idle__newsitem').length - 1).toBe(count);
      expect(html).not.toContain('idle__newsrest');
    }
  });

  it('XL (W5): DIESELBEN sechs Meldungen, aber zweispaltig — der Deckel waechst nicht mit der Flaeche', () => {
    const html = render(live(wireBody(20)), 'XL');
    // Der Deckel ist ein Viewport-Riegel, kein Platz-Riegel: 6 bleiben 6.
    // Die Zahl ist Andis offenes Gate §7.4 — eine Stufe darf sie nicht
    // stillschweigend beantworten.
    expect(html.split('idle__newsitem').length - 1).toBe(CURRENT_AFFAIRS_EXPANDED_COUNT);
    expect(html).toContain(de.idleFace.currentAffairs.openSource);
    // Zweispaltig + weiterhin die Detail-Karte (Teaser).
    expect(html).toContain('idle__newslist--two');
    expect(html).toContain('idle__news--xl');
    expect(html).toContain('idle__news--big');
    expect(html).toContain('Teaser text of headline 6.');
    // „+N nicht gezeigt" gilt bei XL genauso.
    expect(html).toContain(
      de.idleFace.currentAffairs.restNotShown(20 - CURRENT_AFFAIRS_EXPANDED_COUNT),
    );
  });

  it('L bleibt EINspaltig — die zweite Spalte ist die Belohnung fuer XL', () => {
    const html = render(live(wireBody(20)), 'L');
    expect(html).not.toContain('idle__newslist--two');
    expect(html).not.toContain('idle__news--xl');
  });

  it('XL erfindet nichts: hoechstens sechs Meldungen ⇒ keine Rest-Zeile, fehlender Teaser bleibt leer', () => {
    const html = render(live(wireBody(CURRENT_AFFAIRS_EXPANDED_COUNT)), 'XL');
    expect(html.split('idle__newsitem').length - 1).toBe(CURRENT_AFFAIRS_EXPANDED_COUNT);
    expect(html).not.toContain('idle__newsrest');
  });

  it('M bleibt bei drei Karten — der L-Deckel gilt nur ab L', () => {
    const html = render(live(wireBody(20)));
    expect(html.split('idle__newsitem').length - 1).toBe(CURRENT_AFFAIRS_WINDOW_COUNT);
    expect(html).not.toContain('idle__newsrest');
  });

  it('fehlender feedSnippet ⇒ keine leere Teaser-Zeile', () => {
    const body = {
      items: [wireItem(1, { snippet: null }), wireItem(2)],
      observedAt: OBSERVED_AT,
      lastSuccessfulRefreshAt: REFRESHED_AT,
      freshness: 'FRESH',
    };
    const html = render(live(body), 'L');
    expect(html).toContain('Teaser text of headline 2.');
    expect(html.split('idle__newssnippet').length - 1).toBe(1);
  });
});

/* ── 3 · EMPTY/UNAVAILABLE ⇒ NICHTS ─────────────────────────────────────── */

describe('Lagebild — die Kachel muss sich verdienen', () => {
  it.each(['EMPTY', 'UNAVAILABLE'] as const)('freshness=%s ⇒ gar kein Markup', (freshness) => {
    expect(render(live(wireBody(5, freshness)))).toBe('');
    expect(renderableCurrentAffairs(live(wireBody(5, freshness)))).toBeNull();
  });

  it('erster Fetch laeuft / Feature aus / nicht erreichbar ⇒ ebenfalls nichts', () => {
    for (const state of [null, { kind: 'off' } as const, { kind: 'unreachable' } as const]) {
      expect(render(state)).toBe('');
      expect(renderableCurrentAffairs(state)).toBeNull();
    }
  });

  it('FRESH, aber keine brauchbare Meldung ⇒ ebenfalls nichts (kein leeres Geruest)', () => {
    expect(render(live(wireBody(0)))).toBe('');
  });
});

/* ── 3b · Die Durchreiche der Bühnen-Requisiten (Andi 22.08.) ───────────── */

describe('Lagebild — die Kachel traegt den Griff der Buehne', () => {
  /**
   * **Andi 22.08. wörtlich: „hier möchte ich, dass alle Widgets in der größe
   * veränderbar sind. bei den nachrichten geht das noch nicht."**
   *
   * Die Ursache war nicht die Registry (`news` bietet seit W5 alle vier
   * Stufen an) und auch kein Wähler-Riegel, sondern eine fehlende Durchreiche:
   * `HomeStage.placeTile` hängt `data-widget-id`, die Zelle (`style`) und die
   * Edit-A11y per `cloneElement` an das Wurzelelement — und dieses Fenster
   * lieferte eine KOMPONENTE, die alles davon stumm schluckte. Gemessen
   * (`tools/zuhause-probe/flaeche.mjs`): die einzige Kachel der Bühne ohne
   * `data-widget-id`, in jeder Szene und in beiden Fenstern. Ohne die Id
   * findet `sizableWidgetAt`/`widgetAt` sie nie — kein Long-Press, kein Tipp
   * im Edit-Modus, kein `+`/`−`.
   *
   * Exakt derselbe Fehler war W5 schon bei Sauger/Klima aufgefallen
   * (`hometilecards.test.tsx`, „Durchreiche-Pflicht"); die Nachrichten sind
   * damals nicht mitgekommen. Dieser Test ist der Riegel dagegen, dass es ein
   * drittes Mal passiert.
   */
  const cell = { gridColumn: '1 / span 3', gridRow: '1 / span 2' } as const;

  it('style/data-widget-id/Edit-A11y landen am <article>, nicht im Nirwana', () => {
    const html = renderToStaticMarkup(
      <CurrentAffairsWindow
        state={live(wireBody(5))}
        nowMs={NOW_MS}
        size="M"
        style={cell}
        data-widget-id="news"
        role="button"
        tabIndex={0}
        aria-label="Nachrichten"
      />,
    );
    expect(html).toContain('grid-column:1 / span 3');
    expect(html).toContain('grid-row:1 / span 2');
    expect(html).toContain('data-widget-id="news"');
    expect(html).toContain('role="button"');
    expect(html).toContain('tabindex="0"');
    expect(html).toContain('aria-label="Nachrichten"');
  });

  it('die Kachel behaelt ihre EIGENEN Zusagen — Klassen und data-status gewinnen', () => {
    const html = renderToStaticMarkup(
      <CurrentAffairsWindow
        state={live(wireBody(5))}
        nowMs={NOW_MS}
        size="M"
        data-widget-id="news"
        data-status="kaputt"
      />,
    );
    expect(html).toContain('data-status="live"');
    expect(html).not.toContain('data-status="kaputt"');
    expect(html).toContain('idle__news');
  });

  it('IdleFace: die Nachrichten-Kachel kommt MIT Griff aus der Buehne', () => {
    const html = renderToStaticMarkup(
      <IdleFace
        nowMs={NOW_MS}
        scheduled={[]}
        weather={null}
        shopping={[]}
        wetterTileEnabled={false}
        uhrTileEnabled={false}
        weckerTileEnabled={false}
        currentAffairs={live(wireBody(5))}
      />,
    );
    expect(html).toContain('data-widget-id="news"');
  });
});

/* ── 4 · Stand-Zeile + STALE-Alter ──────────────────────────────────────── */

describe('Lagebild — Stand-Zeile und Alter', () => {
  it('FRESH ⇒ Stand-Zeile aus lastSuccessfulRefreshAt, KEINE Pille (W6), KEIN Alter-Hinweis', () => {
    const html = render(live(wireBody(3)));
    expect(html).toContain(
      de.idleFace.currentAffairs.stand(dueClock(Date.parse(REFRESHED_AT), de.locale)),
    );
    // W6 (Andi 20.08.): die Frische-Pille ist raus. Sie stand genau dann, wenn
    // es NICHTS zu melden gab — die Stand-Zeile darüber sagt dasselbe mit einer
    // Uhrzeit statt eines Worts.
    expect(html).not.toContain('tile__pill');
    expect(html).not.toContain('idle__newsage');
  });

  it('die Stand-Zeile nimmt NICHT observedAt', () => {
    const html = render(live(wireBody(3)));
    const observedClock = dueClock(Date.parse(OBSERVED_AT), de.locale);
    const refreshedClock = dueClock(Date.parse(REFRESHED_AT), de.locale);
    expect(observedClock).not.toBe(refreshedClock); // sonst beweist der Test nichts
    expect(html).toContain(refreshedClock);
    expect(html).not.toContain(observedClock);
  });

  it('STALE ⇒ der Amber-Alter-Hinweis BLEIBT — er ist die Hälfte, die etwas kostet (W6)', () => {
    // Der Riegel der W6-Bestellung: „das Live kann raus, der Amber-STALE-
    // Hinweis bleibt". Ohne diesen Test wäre die Pillen-Entfernung ein Schritt
    // davon entfernt, auch die einzige unangenehme Auskunft mitzunehmen.
    const html = render(live(wireBody(3, 'STALE')));
    expect(html).toContain('idle__newsage');
    expect(html).toContain(
      de.idleFace.currentAffairs.staleHint(de.idleFace.homeTiles.age.hoursAgo(3)),
    );
    expect(html).not.toContain('tile__pill');
    expect(html).toContain('data-freshness="STALE"');
  });

  it('lastSuccessfulRefreshAt null ⇒ gar keine Stand-Zeile (nie eine erfundene Zeit)', () => {
    const html = render(
      live({
        items: [wireItem(1)],
        observedAt: OBSERVED_AT,
        lastSuccessfulRefreshAt: null,
        freshness: 'FRESH',
      }),
    );
    expect(html).toContain('Headline number 1');
    expect(html).not.toContain('idle__newsstand');
  });
});

/* ── 4b · Attribution-Zeile (Wire-Feld `attribution`, Codex-Rate-Stelle) ──── */

describe('Lagebild — Attribution-Zeile je Meldung', () => {
  it('M: KEINE Attribution-Zeile, auch wenn das Feld da ist (nur die Meta-Zeile mit Quelle+Alter)', () => {
    const body = {
      items: [wireItem(1, { attribution: 'tagesschau.de' })],
      observedAt: OBSERVED_AT,
      lastSuccessfulRefreshAt: REFRESHED_AT,
      freshness: 'FRESH',
    };
    const html = render(live(body));
    expect(html).not.toContain('tagesschau.de');
  });

  it('L: die Attribution steht als eigene Zeile, RAW (nicht uebersetzt)', () => {
    const body = {
      items: [wireItem(1, { attribution: 'heise online · RSS: Überschrift, Anriss und aktiver Link; keine Bilder' })],
      observedAt: OBSERVED_AT,
      lastSuccessfulRefreshAt: REFRESHED_AT,
      freshness: 'FRESH',
    };
    const html = render(live(body), 'L');
    expect(html).toContain('heise online · RSS: Überschrift, Anriss und aktiver Link; keine Bilder');
  });

  it('fehlende Attribution (Feld nicht im Wire-Body) ⇒ keine leere Zeile, kein Crash', () => {
    const html = render(live(wireBody(1)), 'L');
    expect(html).toContain('Headline number 1');
  });

  it('L, bekannte Quelle (TAGESSCHAU): das SourceBadge-SVG steht vor dem Attribution-Text (Kurs-Update)', () => {
    const body = {
      items: [wireItem(1, { source: 'TAGESSCHAU', attribution: 'tagesschau.de' })],
      observedAt: OBSERVED_AT,
      lastSuccessfulRefreshAt: REFRESHED_AT,
      freshness: 'FRESH',
    };
    const html = render(live(body), 'L');
    expect(html).toContain('<svg');
    expect(html).toContain('aria-hidden="true"');
    expect(html.indexOf('<svg')).toBeLessThan(html.indexOf('tagesschau.de'));
  });

  it('L, unbekannte Quelle: KEIN Badge-SVG, Attribution bleibt Text-only (Fallback laut Auftrag)', () => {
    const body = {
      items: [wireItem(1, { source: 'DLF', attribution: 'deutschlandfunk.de' })],
      observedAt: OBSERVED_AT,
      lastSuccessfulRefreshAt: REFRESHED_AT,
      freshness: 'FRESH',
    };
    const html = render(live(body), 'L');
    expect(html).toContain('deutschlandfunk.de');
    expect(html).not.toContain('<svg');
  });

  it('parseCurrentAffairsItems liest attribution roh, null wenn abwesend/leer', () => {
    const data = parseCurrentAffairs({
      items: [
        wireItem(1, { attribution: 'Golem.de · Atom-Feed; kommerzielle Nutzung eingeschränkt' }),
        wireItem(2, { attribution: '' }),
        wireItem(3),
      ],
      observedAt: OBSERVED_AT,
      lastSuccessfulRefreshAt: REFRESHED_AT,
      freshness: 'FRESH',
    }) as CurrentAffairs;
    expect(data.items[0].attribution).toBe('Golem.de · Atom-Feed; kommerzielle Nutzung eingeschränkt');
    expect(data.items[1].attribution).toBeNull();
    expect(data.items[2].attribution).toBeNull();
  });
});

/* ── 5 · Vertrag: parse + fetch ─────────────────────────────────────────── */

describe('parseCurrentAffairs — der Vertrag', () => {
  it('liest den vollen Vertrag inkl. Zeitstempel als epoch-ms', () => {
    const data = parseCurrentAffairs(wireBody(1)) as CurrentAffairs;
    expect(data.freshness).toBe('FRESH');
    expect(data.observedAtMs).toBe(Date.parse(OBSERVED_AT));
    expect(data.lastSuccessfulRefreshAtMs).toBe(Date.parse(REFRESHED_AT));
    const item = data.items[0] as CurrentAffairsItem;
    expect(item.source).toBe('TAGESSCHAU');
    expect(item.publishedAtMs).toBe(Date.parse('2026-08-15T08:30:00Z'));
    expect(item.fetchedAtMs).toBe(Date.parse('2026-08-15T08:45:00Z'));
  });

  it('unbekannte/fehlende freshness ⇒ null (die ganze Antwort gilt als unlesbar)', () => {
    expect(parseCurrentAffairs({ items: [], freshness: 'SOMETHING' })).toBeNull();
    expect(parseCurrentAffairs({ items: [] })).toBeNull();
    expect(parseCurrentAffairs({ freshness: 'FRESH' })).toBeNull();
    expect(parseCurrentAffairs(null)).toBeNull();
  });

  it('kaputte Einzel-Meldungen fallen raus, der Rest ueberlebt', () => {
    const data = parseCurrentAffairs({
      items: [
        wireItem(1),
        wireItem(2, { title: '' }),
        wireItem(3, { canonicalUrl: 'javascript:alert(1)' }),
        wireItem(4, { publishedAt: 'nicht-lesbar' }),
        wireItem(5, { id: undefined }),
      ],
      observedAt: OBSERVED_AT,
      lastSuccessfulRefreshAt: REFRESHED_AT,
      freshness: 'FRESH',
    }) as CurrentAffairs;
    expect(data.items.map((i) => i.id)).toEqual(['item-1']);
  });
});

describe('fetchCurrentAffairs — ehrliche Zustands-Trennung', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('404 ⇒ off, 401/5xx ⇒ unreachable, Netzfehler ⇒ unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    expect(await fetchCurrentAffairs()).toEqual({ kind: 'off' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    expect(await fetchCurrentAffairs()).toEqual({ kind: 'unreachable' });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('boom')));
    expect(await fetchCurrentAffairs()).toEqual({ kind: 'unreachable' });
  });

  it('200 mit vertragswidrigem Rumpf ⇒ unreachable (nie erfundene Meldungen)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) }),
    );
    expect(await fetchCurrentAffairs()).toEqual({ kind: 'unreachable' });
  });

  it('fragt mit ?limit=N und schickt den Bestands-Token-Header', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(wireBody(1)) });
    vi.stubGlobal('fetch', fetchMock);
    const state = await fetchCurrentAffairs(undefined, 20);
    expect(state.kind).toBe('live');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/currentaffairs/today?limit=20');
    expect(init.headers).toHaveProperty('Accept', 'application/json');
  });
});

/* ── 6 · Einhaengung ins Zuhause-Gesicht ────────────────────────────────── */

describe('IdleFace — das Fenster haengt im Zuhause-Kachelraster', () => {
  // W1 (DESIGN-widget-raster-2026-08-18 §1.1): Wetter is now the FIRST stage
  // widget, default ON — it renders a "gap" tile even with `weather: null`,
  // so it alone would keep `idle__tiles` around no matter what this suite is
  // actually testing. Switched off here to isolate exactly the Lagebild
  // question these tests ask ("does THIS widget open/close the grid").
  const base = {
    nowMs: NOW_MS,
    scheduled: [],
    weather: null,
    shopping: [],
    wetterTileEnabled: false,
    // W4 (Andi 19.08.): die Uhr ist selbst ein Buehnen-Widget geworden. Diese
    // Sonden fragen „geht das Raster NUR wegen des Lagebild-Fensters auf?" —
    // dafuer muessen die uebrigen Kacheln aus sein, sonst misst der Test die
    // Uhr statt der Nachrichten.
    uhrTileEnabled: false,
    // W6 (Andi 20.08.): derselbe Grund fuer den Wecker, und bei ihm noch
    // haerter — er rendert AUCH ohne Daten („Kein Wecker gestellt" ist eine
    // Antwort, kein Platzhalter). Er allein hielte das Raster sonst offen.
    weckerTileEnabled: false,
  };

  it('echte Meldungen ⇒ das Kachelraster geht auf und traegt das Fenster', () => {
    const html = renderToStaticMarkup(<IdleFace {...base} currentAffairs={live(wireBody(4))} />);
    expect(html).toContain('idle__tiles');
    expect(html).toContain(de.idleFace.currentAffairs.name);
    expect(html).toContain('Headline number 1');
  });

  it('ohne Lagebild-Daten bleibt das Zuhause byte-gleich zum Bestand (kein Raster)', () => {
    const withNull = renderToStaticMarkup(<IdleFace {...base} currentAffairs={null} />);
    const without = renderToStaticMarkup(<IdleFace {...base} />);
    expect(withNull).toBe(without);
    expect(withNull).not.toContain('idle__tiles');
    expect(withNull).not.toContain(de.idleFace.currentAffairs.name);
  });

  it('EMPTY ⇒ das Raster geht NICHT wegen eines unsichtbaren Fensters auf', () => {
    const html = renderToStaticMarkup(
      <IdleFace {...base} currentAffairs={live(wireBody(4, 'EMPTY'))} />,
    );
    expect(html).not.toContain('idle__tiles');
  });
});

/* ── 7 · Der Anzeige-Schalter (Einstellungen → Zuhause-Kacheln) ──────────── */

describe('IdleFace — der Lagebild-Schalter ist der harte Riegel vor dem Fenster', () => {
  // W1 (DESIGN-widget-raster-2026-08-18 §1.1): Wetter is now the FIRST stage
  // widget, default ON — it renders a "gap" tile even with `weather: null`,
  // so it alone would keep `idle__tiles` around no matter what this suite is
  // actually testing. Switched off here to isolate exactly the Lagebild
  // question these tests ask ("does THIS widget open/close the grid").
  const base = {
    nowMs: NOW_MS,
    scheduled: [],
    weather: null,
    shopping: [],
    wetterTileEnabled: false,
    // W4 (Andi 19.08.): die Uhr ist selbst ein Buehnen-Widget geworden. Diese
    // Sonden fragen „geht das Raster NUR wegen des Lagebild-Fensters auf?" —
    // dafuer muessen die uebrigen Kacheln aus sein, sonst misst der Test die
    // Uhr statt der Nachrichten.
    uhrTileEnabled: false,
    // W6 (Andi 20.08.): derselbe Grund fuer den Wecker, und bei ihm noch
    // haerter — er rendert AUCH ohne Daten („Kein Wecker gestellt" ist eine
    // Antwort, kein Platzhalter). Er allein hielte das Raster sonst offen.
    weckerTileEnabled: false,
  };

  it('Schalter AUS ⇒ trotz FRESH-Fixture kein Fenster, keine Meldung, kein Raster', () => {
    const html = renderToStaticMarkup(
      <IdleFace {...base} currentAffairs={live(wireBody(5))} currentAffairsTileEnabled={false} />,
    );
    expect(html).not.toContain(de.idleFace.currentAffairs.name);
    expect(html).not.toContain('Headline number 1');
    expect(html).not.toContain('idle__news');
    // Das Fenster war der EINZIGE Grund fürs Kachelraster ⇒ es bleibt zu.
    expect(html).not.toContain('idle__tiles');
  });

  it('Schalter AUS ⇒ auch die „mehr"-Expansion existiert nicht (kein Knopf im Markup)', () => {
    const html = renderToStaticMarkup(
      <IdleFace {...base} currentAffairs={live(wireBody(9))} currentAffairsTileEnabled={false} />,
    );
    expect(html).not.toContain('idle__newsmore');
    expect(html).not.toContain(
      de.idleFace.currentAffairs.more(CURRENT_AFFAIRS_EXPANDED_COUNT - CURRENT_AFFAIRS_WINDOW_COUNT),
    );
  });

  it('Schalter AUS bei ECHTEN Haushaltskarten ⇒ Raster bleibt, nur das Fenster fehlt', () => {
    const scheduled = [{ id: 's-1', kind: 'TIMER' as const, dueAtEpochMs: NOW_MS + 12 * 60_000 }];
    const html = renderToStaticMarkup(
      <IdleFace
        {...base}
        scheduled={scheduled}
        currentAffairs={live(wireBody(5))}
        currentAffairsTileEnabled={false}
      />,
    );
    expect(html).toContain('idle__tiles'); // „Läuft" trägt das Raster weiter
    expect(html).not.toContain(de.idleFace.currentAffairs.name);
  });

  it('Schalter AN ist der Default: ohne die Prop rendert das Fenster wie bisher', () => {
    const withProp = renderToStaticMarkup(
      <IdleFace {...base} currentAffairs={live(wireBody(5))} currentAffairsTileEnabled />,
    );
    const withoutProp = renderToStaticMarkup(<IdleFace {...base} currentAffairs={live(wireBody(5))} />);
    expect(withProp).toBe(withoutProp); // byte-gleich zum Bestandsaufruf
    expect(withoutProp).toContain(de.idleFace.currentAffairs.name);
    expect(withoutProp).toContain('Headline number 1');
  });
});
