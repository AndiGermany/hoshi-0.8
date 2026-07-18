import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { PRIVACY_TEXTS, PrivacySectionView } from '../components/SettingsPanel';
import {
  PrivacyNotYetError,
  deletePrivacyData,
  fetchPrivacySummary,
  type PrivacySummary,
} from '../api/privacy';

/** Gültige Summary (lokale Boot-Config), per `over` punktuell überschreibbar. */
const summary = (over: Partial<PrivacySummary> = {}): PrivacySummary => ({
  voice: { engine: 'voxtral', cloud: false },
  sanitize: { enabled: false },
  memory: { enabled: true, path: '/x/entity-memory.db', exists: true, sizeBytes: 12288, entries: 5 },
  episodic: { enabled: false, path: '/x/episodic-memory.db', exists: false, sizeBytes: 0, entries: null },
  diary: { enabled: true, dir: '/x/diary', days: 3 },
  ...over,
});

const render = (props: Partial<Parameters<typeof PrivacySectionView>[0]> = {}) =>
  renderToStaticMarkup(
    <PrivacySectionView summary={summary()} onDelete={() => {}} {...props} />,
  );

describe('PrivacySectionView — Render', () => {
  it('lokale Engine: Schloss-Glyph-Zeile, kein Cloud-Text', () => {
    const html = render();
    expect(html).toContain('Privatsphäre');
    expect(html).toContain('Stimme (TTS):');
    expect(html).toContain('voxtral');
    expect(html).toContain('glyph--lock'); // muted SVG statt 🔒
    expect(html).not.toContain('glyph--cloud'); // lokal ⇒ nirgends eine Wolke
    expect(html).toContain('kein Text verlässt die Box');
    expect(html).not.toContain('geht für die Sprachausgabe zu OpenAI');
  });

  it('Cloud-Engine: Wolken-Glyph-Zeile sagt ehrlich, dass Text zu OpenAI geht', () => {
    const html = render({ summary: summary({ voice: { engine: 'openai', cloud: true } }) });
    expect(html).toContain('Stimme (TTS):');
    expect(html).toContain('openai');
    expect(html).toContain('glyph--cloud'); // muted SVG statt ☁️
    expect(html).toContain('geht für die Sprachausgabe zu OpenAI');
  });

  it('Maskierung aus: Warn-Glyph + „Was maskiert wird"-Erklärung (Namen bleiben)', () => {
    const html = render();
    expect(html).toContain('Cloud-Maskierung: aus');
    expect(html).toContain('glyph--warn'); // muted SVG statt ⚠️
    expect(html).toContain('unmaskiert');
    expect(html).toContain('Tokens, URLs, IP-Adressen, UUIDs und Smart-Home-IDs');
    expect(html).toContain('Namen bleiben');
  });

  it('Maskierung an: Schloss-Glyph aktiv + Erklärung, kein Warn-Glyph', () => {
    const html = render({ summary: summary({ sanitize: { enabled: true } }) });
    expect(html).toContain('Cloud-Maskierung: aktiv');
    expect(html).not.toContain('glyph--warn');
    expect(html).toContain('Namen und normaler Inhalt bleiben');
  });

  it('Emoji-Sweep: das Panel-Chrome trägt KEINE Emojis mehr (🔒/☁️/⚠️)', () => {
    for (const html of [
      render(),
      render({ summary: summary({ voice: { engine: 'openai', cloud: true } }) }),
      render({ summary: summary({ sanitize: { enabled: true } }) }),
    ]) {
      expect(html).not.toContain('🔒');
      expect(html).not.toContain('☁');
      expect(html).not.toContain('⚠');
    }
  });

  it('Stores ehrlich: echter Count, fehlende Datei = „noch nichts gespeichert"', () => {
    const html = render();
    expect(html).toContain('5 Einträge');
    expect(html).toContain('noch nichts gespeichert'); // episodic exists=false
    expect(html).toContain('3 Tages-Dateien');
    expect(html).toContain('keine Gesprächs-Inhalte');
  });

  it('entries=null wird NIE zur Zahl erfunden', () => {
    const html = render({
      summary: summary({
        memory: { enabled: true, path: '/x', exists: true, sizeBytes: 1, entries: null },
      }),
    });
    expect(html).toContain('Anzahl grad nicht lesbar');
  });

  it('drei Lösch-Knöpfe; unscharf = „Löschen"', () => {
    const html = render();
    const matches = html.match(/settings__deletebtn/g) ?? [];
    expect(matches.length).toBe(3);
    expect(html).toContain(PRIVACY_TEXTS.delete);
    expect(html).not.toContain(PRIVACY_TEXTS.confirm);
  });

  it('scharf (erster Klick): „Wirklich? Klick nochmal" nur am gewählten Knopf', () => {
    const html = render({ armed: 'memory' });
    expect(html).toContain(PRIVACY_TEXTS.confirm);
    expect(html).toContain('is-armed');
    // Nur EIN Knopf ist scharf.
    expect((html.match(/is-armed/g) ?? []).length).toBe(1);
  });

  it('501-Notiz („kommt noch") und Fehler-Notiz werden ehrlich gerendert', () => {
    const html = render({ notes: { memory: PRIVACY_TEXTS.notYet, diary: PRIVACY_TEXTS.failed } });
    expect(html).toContain('Kommt noch — serverseitig noch nicht gebaut.');
    expect(html).toContain('Löschen fehlgeschlagen');
  });

  it('lädt…/Fehler-Zustände ohne Summary', () => {
    expect(render({ summary: null, loading: true })).toContain('lädt…');
    expect(render({ summary: null, error: PRIVACY_TEXTS.loadError })).toContain(
      PRIVACY_TEXTS.loadError,
    );
  });
});

describe('fetchPrivacySummary — Wire + defensiver Parse', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('GET → geparste Summary (kaputte Felder → ehrliche Defaults)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            voice: { engine: 'openai', cloud: true },
            sanitize: { enabled: true },
            memory: { enabled: false, path: '/p', exists: true, sizeBytes: 4096, entries: 2 },
            episodic: { exists: false, entries: null },
            diary: { enabled: false, dir: '/d', days: 0 },
          }),
      }),
    );
    const s = await fetchPrivacySummary();
    expect(s.voice.cloud).toBe(true);
    expect(s.memory.entries).toBe(2);
    expect(s.episodic.entries).toBeNull(); // null bleibt null — nie erfunden
    expect(s.diary.days).toBe(0);
  });

  it('401 → wirft (Auth-Wand wird ehrlich durchgereicht)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchPrivacySummary()).rejects.toThrow(/401/);
  });
});

describe('deletePrivacyData — DELETE-Vertrag + 501-Ehrlichkeit', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('DELETE an /api/v1/privacy/{target} → bewiesene Lösch-Zahl', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ target: 'memory', deleted: 7 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const res = await deletePrivacyData('memory');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/api/v1/privacy/memory');
    expect(init.method).toBe('DELETE');
    expect(res.deleted).toBe(7);
  });

  it('501 → PrivacyNotYetError (die UI sagt „kommt noch", kein Fake-Erfolg)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 501 }));
    await expect(deletePrivacyData('diary')).rejects.toBeInstanceOf(PrivacyNotYetError);
  });

  it('401 → wirft', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(deletePrivacyData('episodic')).rejects.toThrow(/401/);
  });
});
