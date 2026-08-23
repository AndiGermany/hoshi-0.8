/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { VacuumTile } from '../components/HomeTileCards';
import { CATALOGS } from '../i18n';
import type { HomeRegistryEntity, HomeRegistrySnapshot, HomeRegistryState } from '../api/homeRegistry';

/**
 * **vacuumactions.test** — die zwei Tat-Knöpfe der Sauger-Kachel (Andi 21.08.:
 * „Können wir den Sauger starten und nach Hause fahren lassen?"), gegen den
 * BE-Vertrag aus `vault/tracks/RESULT-sauger-aktionen-2026-08-21.md` §1/§3.
 *
 * **Was hier bewiesen wird, ist vor allem, was NICHT passiert:**
 *  - eine angenommene Tat schreibt den Kachel-Zustand NICHT um (200 heißt „HA
 *    hat den Auftrag", nicht „der Sauger fährt"),
 *  - ein Fehler erfindet keinen Grund, sondern zeigt die Server-Meldung,
 *  - im Edit-Modus löst kein Klick etwas aus.
 *
 * `fetch` ist gestubbt statt der Client — so läuft `api/vacuumActions.ts` mit
 * (URL, Methode, Fehler-Body-Auswertung), statt an einer Attrappe vorbei.
 * Texte kommen aus dem Katalog, nie abgeschrieben (Muster `homeeditmode.test`).
 */

const T = CATALOGS['de'].idleFace.homeTiles.vacuum;

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

const entity = (over: Partial<HomeRegistryEntity> = {}): HomeRegistryEntity => ({
  entityId: 'vacuum.rob',
  domain: 'vacuum',
  name: 'Rob',
  labels: [],
  ...over,
});

const registryWith = (state: string): HomeRegistryState => ({
  kind: 'live',
  data: { areas: [], unassigned: [entity({ state })] } satisfies HomeRegistrySnapshot,
});

let container: HTMLDivElement;
let root: Root;
let fetchMock: ReturnType<typeof vi.fn>;

const ok = () => new Response('{"action":"start","entityId":"vacuum.rob","accepted":true,"haStatus":200}', { status: 200 });
const fail = (status: number, message: string) =>
  new Response(JSON.stringify({ error: 'vacuum-action-failed', id: 'vacuum-action', message }), { status });

async function mount(state: string, extra: Record<string, string> = {}) {
  await act(async () => {
    root.render(<VacuumTile registry={registryWith(state)} size="L" {...extra} />);
  });
}

const buttons = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.idle__hometileaction'));
const byLabel = (label: string) => buttons().find((b) => b.textContent === label);
const note = () => container.querySelector('.idle__hometileactionnote')?.textContent ?? '';
const headline = () => container.querySelector('.idle__hometileline')?.textContent ?? '';

async function click(button: HTMLButtonElement | undefined) {
  await act(async () => {
    button?.click();
  });
}

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  fetchMock = vi.fn().mockResolvedValue(ok());
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Sauger-Knöpfe — die Tat geht an den richtigen Rand', () => {
  it('„Start" ruft POST /api/v1/home/vacuum/start OHNE Body (die Ziel-Entity findet der BE selbst)', async () => {
    await mount('docked');
    await click(byLabel(T.actions.start));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/home/vacuum/start');
    expect(init.method).toBe('POST');
    expect(init.body).toBeUndefined();
  });

  it('„Zur Basis" ruft dieselbe Wand mit `return_to_base`', async () => {
    await mount('cleaning');
    await click(byLabel(T.actions.returnToBase));
    expect((fetchMock.mock.calls[0] as [string, RequestInit])[0]).toContain('/api/v1/home/vacuum/return_to_base');
  });
});

describe('Sauger-Knöpfe — ehrliches Feedback, kein optimistisches Umschreiben', () => {
  it('200 ⇒ „angenommen"-Satz — UND der Kachel-Zustand bleibt exakt, wie er war', async () => {
    await mount('docked');
    expect(headline()).toBe(T.status.docked);
    await click(byLabel(T.actions.start));
    expect(note()).toBe(T.actions.accepted);
    // Der Kern des Vertrags: keine Zeile behauptet, der Sauger fahre jetzt.
    // Die Wahrheit kommt beim nächsten Registry-Poll, nicht von diesem Klick.
    expect(headline()).toBe(T.status.docked);
    expect(byLabel(T.actions.start)).toBeDefined();
    expect(byLabel(T.actions.returnToBase)).toBeUndefined();
  });

  it('502 ⇒ die Server-Meldung im Klartext, KEIN erfundener Grund und keine Bestätigung', async () => {
    fetchMock.mockResolvedValue(fail(502, 'Home Assistant hat die Tat nicht angenommen (HTTP 500).'));
    await mount('docked');
    await click(byLabel(T.actions.start));
    expect(note()).toBe('Home Assistant hat die Tat nicht angenommen (HTTP 500).');
    expect(note()).not.toContain(T.actions.accepted);
  });

  it('409 „HA beim Deploy aus" ⇒ ebenfalls wörtlich die Server-Meldung', async () => {
    fetchMock.mockResolvedValue(fail(409, 'Home Assistant ist bei diesem Deploy abgeschaltet.'));
    await mount('docked');
    await click(byLabel(T.actions.start));
    expect(note()).toBe('Home Assistant ist bei diesem Deploy abgeschaltet.');
  });

  it('Fehler OHNE lesbaren Body ⇒ der generische Statuscode-Satz, nichts Erfundenes', async () => {
    fetchMock.mockResolvedValue(new Response('<html>bad gateway</html>', { status: 502 }));
    await mount('docked');
    await click(byLabel(T.actions.start));
    expect(note()).toContain('502');
  });

  it('Netzfehler ⇒ „nicht übergeben" — wir wissen nicht, ob HA es bekam, und sagen genau das', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await mount('docked');
    await click(byLabel(T.actions.start));
    expect(note()).toBe(T.actions.networkError);
  });

  it('ein neuer Versuch räumt die alte Fehlermeldung weg (statt zwei Wahrheiten übereinander)', async () => {
    fetchMock.mockResolvedValue(fail(502, 'Erster Fehlschlag.'));
    await mount('docked');
    await click(byLabel(T.actions.start));
    expect(note()).toBe('Erster Fehlschlag.');
    fetchMock.mockResolvedValue(ok());
    await click(byLabel(T.actions.start));
    expect(note()).toBe(T.actions.accepted);
  });

  it('solange eine Tat läuft, sind BEIDE Knöpfe gesperrt (kein Wettrennen am selben Gerät)', async () => {
    let release: (r: Response) => void = () => {};
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => { release = resolve; }));
    await mount('paused'); // der einzige Zustand mit beiden Knöpfen
    expect(buttons()).toHaveLength(2);
    await act(async () => {
      byLabel(T.actions.start)?.click();
    });
    expect(buttons().every((b) => b.disabled)).toBe(true);
    expect(note()).toBe(T.actions.sending);
    await act(async () => {
      release(ok());
    });
    expect(buttons().every((b) => b.disabled)).toBe(false);
  });
});

describe('Sauger-Knöpfe — im EDIT-Modus inert (W4/W7)', () => {
  it('`data-edit="true"` ⇒ beide Knöpfe disabled und aus dem Tab-Fluss', async () => {
    await mount('paused', { 'data-edit': 'true' });
    expect(buttons()).toHaveLength(2);
    expect(buttons().every((b) => b.disabled)).toBe(true);
    expect(buttons().every((b) => b.tabIndex === -1)).toBe(true);
  });

  it('ein Klick im Edit-Modus schickt NICHTS — ein Zug an der Kachel darf keinen Roboter losschicken', async () => {
    await mount('docked', { 'data-edit': 'true' });
    await click(byLabel(T.actions.start));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('die Knöpfe VERSCHWINDEN im Edit nicht — eine Kachel, die beim Anordnen anders aussieht, ist die falsche Vorlage', async () => {
    await mount('docked', { 'data-edit': 'true' });
    expect(byLabel(T.actions.start)).toBeDefined();
    // Und `data-edit` bleibt am `<article>` — die CSS-Regeln des Edit-Modus
    // (Wackeln, `pointer-events`) hängen daran, die Kachel darf es nicht
    // schlucken, nur weil sie es selbst mitliest.
    expect(container.querySelector('article')?.getAttribute('data-edit')).toBe('true');
  });
});
