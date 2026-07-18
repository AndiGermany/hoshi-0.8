import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { UebersichtView } from '../views/UebersichtView';
import type { HealthState } from '../hooks/useHealth';

const render = (state: HealthState, lastChecked: number | null = null) =>
  renderToStaticMarkup(<UebersichtView state={state} lastChecked={lastChecked} />);

const count = (html: string, needle: string) => html.split(needle).length - 1;

describe('UebersichtView — ehrliche, status-first Landing', () => {
  it('spiegelt jeden Health-Zustand wahrheitsgemäß im Hero', () => {
    const up = render('up', Date.now());
    expect(up).toContain('data-health="up"');
    expect(up).toContain('Hoshi ist online');

    const down = render('down');
    expect(down).toContain('data-health="down"');
    expect(down).toContain('Hoshi ist offline');

    const unknown = render('unknown');
    expect(unknown).toContain('data-health="unknown"');
    expect(unknown).toContain('Status wird geprüft');
    // unbekannt darf NIE als online (Fake-grün) erscheinen:
    expect(unknown).not.toContain('Hoshi ist online');
  });

  it('zeigt fehlende lastChecked als „—" statt einer erfundenen Zeit', () => {
    expect(render('unknown', null)).toContain('zuletzt geprüft —');
  });

  it('markiert die nicht-verdrahteten Daten als ehrliche Platzhalter (🔵)', () => {
    const html = render('up', Date.now());

    // Die vom 0.8-Backend NOCH NICHT exponierten Bereiche sind präsent …
    for (const name of ['Sidecar-Health', 'Sprach-Stats', 'Geräte']) {
      expect(html).toContain(name);
    }

    // … aber klar als „nicht verdrahtet"/„kommt" gelabelt, mit „—" statt Zustand.
    expect(count(html, 'data-status="pending"')).toBe(3);
    expect(count(html, 'tile__pill">nicht verdrahtet')).toBe(3); // genau ein Pill je Platzhalter
    expect(html).toContain('Kommt');
    expect(html).toContain('Kein erfundenes Grün.');
  });

  it('zeigt die echt-verdrahteten Kacheln als live (🟢) mit realen Werten', () => {
    const html = render('up', Date.now());
    expect(count(html, 'data-status="live"')).toBe(3);
    expect(html).toContain('Live-Streaming'); // echte Chat-Verbindung, kein Mock
    expect(html).toContain('tile--live');
    // Live- und Pending-Kacheln dürfen sich nie vermischen:
    expect(count(html, 'tile--pending')).toBe(3);
  });

  it('reflektiert den echten Token-Zustand ehrlich (Default: kein Token ⇒ „fehlt")', () => {
    // Ohne gesetztes VITE_TOKEN ist die ehrliche Antwort „fehlt", nicht „gesetzt".
    const html = render('up');
    expect(html).toContain('Auth-Token');
    expect(html).toContain('fehlt');
  });
});
