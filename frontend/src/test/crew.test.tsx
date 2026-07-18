import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { CrewOverlay } from '../components/CrewOverlay';
import { TopNav } from '../components/TopNav';
import { fetchCrew, type CrewMember } from '../api/crew';

/** Gültige Crew-Zeile, per `over` punktuell überschreibbar. */
const member = (over: Partial<CrewMember> = {}): CrewMember => ({
  name: 'mira',
  role: 'PO + Persona-Wärme',
  mantra: 'Andi-Faktor schlägt Latenz.',
  ...over,
});

const render = (open: boolean, members: CrewMember[]) =>
  renderToStaticMarkup(<CrewOverlay open={open} members={members} onClose={() => {}} />);

describe('CrewOverlay — Render', () => {
  it('offen: trägt Team-Titel, Motto und je Mitglied name · role · mantra', () => {
    const html = render(true, [
      member(),
      member({ name: 'andi', role: 'Captain', mantra: "Wenn's hängt: voice-probe.py." }),
    ]);
    expect(html).toContain('crew-overlay is-open');
    expect(html).toContain('Stellar Bloom');
    expect(html).toContain('warm. lokal. wach.');
    expect(html).toContain('mira');
    expect(html).toContain('PO + Persona-Wärme');
    expect(html).toContain('Andi-Faktor schlägt Latenz.');
    expect(html).toContain('andi');
    expect(html).toContain('Captain');
  });

  it('geschlossen: kein is-open, aria-hidden gesetzt (kein Tab-Fang)', () => {
    const html = render(false, [member()]);
    expect(html).not.toContain('is-open');
    expect(html).toContain('aria-hidden="true"');
  });

  it('zeigt einen Fehler ehrlich statt ihn zu verschlucken', () => {
    const html = renderToStaticMarkup(
      <CrewOverlay open members={[]} error="Backend antwortete HTTP 500" onClose={() => {}} />,
    );
    expect(html).toContain('role="alert"');
    expect(html).toContain('HTTP 500');
  });
});

describe('TopNav — 星-Marke leise links im Brand (Render-Vertrag)', () => {
  const render = () =>
    renderToStaticMarkup(<TopNav tab="chat" onTab={() => {}} onOpenSettings={() => {}} />);

  it('星 sitzt als Button links VOR dem Wortmark (und vor dem Zahnrad), mit a11y-Label', () => {
    const html = render();
    expect(html).toContain('星');
    expect(html).toContain('nav__hoshi');
    expect(html).toContain('aria-label="Hoshi (星)"');
    // „leise links": im Markup kommt das 星 VOR dem Wortmark und VOR dem Zahnrad.
    expect(html.indexOf('nav__hoshi')).toBeLessThan(html.indexOf('nav__title'));
    expect(html.indexOf('nav__hoshi')).toBeLessThan(html.indexOf('nav__settings'));
  });

  it('rechts steht KEIN 星 mehr (genau eine Marke); ✦ bleibt draußen, Wortmark bleibt', () => {
    const html = render();
    const header = html.slice(0, html.indexOf('</header>'));
    expect(header.match(/nav__hoshi/g)).toHaveLength(1); // nur die linke Marke
    expect(header).not.toContain('✦'); // (das ✦ im Crew-Overlay ist ein anderes)
    expect(html).not.toContain('nav__star');
    expect(header).toContain('Hoshi');
    expect(header).toContain('0.8 · Nagareboshi');
  });
});

describe('fetchCrew — Wire + defensiver Parse', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('GET /api/v1/crew → geparstes CrewMember[] (Müll fällt still raus)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve([
          { name: 'mira', role: 'PO + Persona-Wärme', mantra: 'Andi-Faktor schlägt Latenz.' },
          null,
          'nope',
          { role: 'ohne name' }, // ohne name → verworfen
        ]),
    });
    vi.stubGlobal('fetch', fetchMock);

    const list = await fetchCrew();
    expect(list).toHaveLength(1);
    expect(list[0].name).toBe('mira');

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(String(url)).toContain('/api/v1/crew');
  });

  it('!ok → wirft ehrlich (Easter-Egg kann „konnte nicht laden" zeigen)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(fetchCrew()).rejects.toThrow(/500/);
  });
});
