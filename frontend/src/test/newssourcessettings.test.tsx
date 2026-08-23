import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  NewsSourcesSectionView,
  type NewsSourcesSectionViewProps,
} from '../components/NewsSourcesSection';
import {
  type NewsSourcesSetting,
  UnknownNewsSourceError,
  fetchNewsSources,
  saveNewsSources,
} from '../api/newsSources';
import { de } from '../i18n/de';

/**
 * **newssourcessettings.test** — die aktiven Nachrichten-Quellen (Tagesschau/
 * heise/Golem), Muster `languagesettings.test.tsx`: die Präsentations-
 * Komponente wird prop-getrieben via `renderToStaticMarkup` geprüft, der
 * API-Client separat gegen den Wire-Vertrag.
 */

const T = de.settings;

const setting = (over: Partial<NewsSourcesSetting> = {}): NewsSourcesSetting => ({
  aktiv: ['TAGESSCHAU', 'HEISE', 'GOLEM'],
  verfuegbar: ['TAGESSCHAU', 'HEISE', 'GOLEM'],
  ...over,
});

const render = (over: Partial<NewsSourcesSectionViewProps> = {}) =>
  renderToStaticMarkup(<NewsSourcesSectionView current={setting()} onToggle={() => {}} {...over} />);

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('NewsSourcesSectionView — Render (aus GET)', () => {
  it('zeigt alle drei Quellen als angehakte Checkboxen (Default: alle aktiv)', () => {
    const html = render();
    expect(html).toContain(T.homeTilesNewsSourcesLabel);
    expect(html).toContain('Tagesschau');
    expect(html).toContain('heise');
    expect(html).toContain('Golem');
    expect(html.match(/type="checkbox"/g)?.length).toBe(3);
    expect(html.match(/checked=""/g)?.length).toBe(3);
  });

  it('KEIN role="switch" — die Checkbox-Zeilen duerfen die HomeTiles-Schalter-Zaehlung nicht beeinflussen', () => {
    const html = render();
    expect(html).not.toContain('role="switch"');
  });

  it('jede Zeile traegt das SourceBadge-SVG vor dem Anzeigenamen (Kurs-Update: ein Bauteil, zwei Orte)', () => {
    const html = render();
    expect(html.match(/<svg/g)?.length).toBe(3);
    expect(html.indexOf('<svg')).toBeLessThan(html.indexOf('Tagesschau'));
  });

  it('eine abgewaehlte Quelle ist eine unangehakte Checkbox', () => {
    const html = render({ current: setting({ aktiv: ['TAGESSCHAU'] }) });
    expect(html.match(/checked=""/g)?.length).toBe(1);
  });

  it('explizit leeres aktiv ⇒ keine Checkbox angehakt, die Zeilen bleiben trotzdem da', () => {
    const html = render({ current: setting({ aktiv: [] }) });
    expect(html.match(/checked=""/g)).toBeNull();
    expect(html.match(/type="checkbox"/g)?.length).toBe(3);
  });

  it('laedt noch (current=null) ⇒ Lade-Hinweis, keine Checkboxen', () => {
    const html = render({ current: null, loading: true });
    expect(html).toContain(T.homeTilesNewsSourcesLoading);
    expect(html).not.toContain('type="checkbox"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert', () => {
    const html = render({ current: null, error: T.homeTilesNewsSourcesLoadError });
    expect(html).toContain(T.homeTilesNewsSourcesLoadError);
    expect(html).toContain('role="alert"');
  });

  it('Fehler-Notiz (unbekannte Quelle) steht als role=status im Panel', () => {
    const html = render({ note: T.homeTilesNewsSourcesUnknown });
    expect(html).toContain(T.homeTilesNewsSourcesUnknown);
    expect(html).toContain('role="status"');
  });

  it('busy: Checkboxen sind disabled (kein Doppel-PUT)', () => {
    const html = render({ busy: true });
    expect(html).toContain('disabled=""');
  });
});

describe('api/newsSources — Wire-Vertrag', () => {
  it('fetchNewsSources: GET auf den Settings-Pfad, parst aktiv + verfuegbar', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting(),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchNewsSources();
    expect(got.aktiv).toEqual(['TAGESSCHAU', 'HEISE', 'GOLEM']);
    expect(got.verfuegbar).toEqual(['TAGESSCHAU', 'HEISE', 'GOLEM']);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/news-sources');
  });

  it('saveNewsSources: PUT {aktiv}, Antwort traegt den AUTORITATIVEN neuen Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting({ aktiv: ['HEISE'] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveNewsSources(['HEISE']);
    expect(got.aktiv).toEqual(['HEISE']);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ aktiv: ['HEISE'] }));
  });

  it('ein explizit leeres PUT-Array wird unveraendert als leeres Array gesendet', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting({ aktiv: [] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await saveNewsSources([]);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.body).toBe(JSON.stringify({ aktiv: [] }));
  });

  it('422 (unbekannte Quelle) ⇒ UnknownNewsSourceError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 422 }));
    await expect(saveNewsSources(['BILD'])).rejects.toThrowError(UnknownNewsSourceError);
  });

  it('401 ⇒ ehrlicher Auth-Fehler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchNewsSources()).rejects.toThrow(/401/);
  });

  it('vertragswidriger Rumpf (kein Array) ⇒ wirft, statt erfundene Quellen zu zeigen', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) }),
    );
    await expect(fetchNewsSources()).rejects.toThrow();
  });
});
