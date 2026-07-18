import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { OpsStatusPill } from '../components/OpsStatusPill';
import { parseOpsStatus, fetchOpsStatus, type OpsStatus } from '../hooks/useOpsStatus';

const render = (status: OpsStatus | null, defaultExpanded = false) =>
  renderToStaticMarkup(<OpsStatusPill status={status} defaultExpanded={defaultExpanded} />);

/** Gültiger Status (overall+memory+sidecars), per `over` punktuell überschreibbar. */
const sample = (over: Partial<OpsStatus> = {}): OpsStatus => ({
  overall: 'OK',
  memory: { level: 'OK', source: 'brain-health', detail: 'RAM entspannt.' },
  sidecars: [
    { name: 'brain', status: 'OK', detail: 'läuft' },
    { name: 'whisper-stt', status: 'OK', detail: 'läuft' },
  ],
  voice: null,
  allLocal: false,
  ts: 1234567890123,
  ...over,
});

describe('parseOpsStatus — Feature-Flag + Ehrlichkeit', () => {
  it('enabled:false → null (Feature aus → die Pille bleibt still)', () => {
    expect(
      parseOpsStatus({
        enabled: false,
        overall: 'OK',
        memory: { level: 'OK', source: 'brain-health', detail: '' },
        sidecars: [],
        ts: 1,
      }),
    ).toBeNull();
  });

  it('fehlendes/ungültiges enabled oder Müll → null', () => {
    expect(parseOpsStatus({ overall: 'OK' })).toBeNull(); // enabled fehlt
    expect(parseOpsStatus({ enabled: true })).toBeNull(); // overall fehlt
    expect(parseOpsStatus(null)).toBeNull();
    expect(parseOpsStatus('nope')).toBeNull();
  });

  it('enabled:true mit gültigem Vertrag → geparst', () => {
    const s = parseOpsStatus({
      enabled: true,
      overall: 'DEGRADED',
      memory: { level: 'WARN', source: 'brain-health', detail: 'RAM-Druck steigt.' },
      sidecars: [{ name: 'brain', status: 'OK', detail: 'ok' }],
      ts: 42,
    });
    expect(s).not.toBeNull();
    expect(s!.overall).toBe('DEGRADED');
    expect(s!.memory.level).toBe('WARN');
    expect(s!.sidecars).toHaveLength(1);
  });

  it('voice-Feld (T2-Vertrag) → geparst; fehlend/ungültig → voice:null, Status bleibt gültig', () => {
    const base = {
      enabled: true,
      overall: 'OK',
      memory: { level: 'OK', source: 'brain-health', detail: '' },
      sidecars: [],
      ts: 1,
    };
    // Gültig: engine string + cloud boolean → 1:1 übernommen.
    expect(parseOpsStatus({ ...base, voice: { engine: 'openai', cloud: true } })!.voice).toEqual({
      engine: 'openai',
      cloud: true,
    });
    expect(parseOpsStatus({ ...base, voice: { engine: 'voxtral', cloud: false } })!.voice).toEqual({
      engine: 'voxtral',
      cloud: false,
    });
    // Additiv-tolerant: älteres BE ohne voice / Müll → voice:null, KEIN Parse-Fail.
    expect(parseOpsStatus(base)!.voice).toBeNull();
    expect(parseOpsStatus({ ...base, voice: 'openai' })!.voice).toBeNull();
    expect(parseOpsStatus({ ...base, voice: { engine: 'openai', cloud: 'yes' } })!.voice).toBeNull();
  });

  it('allLocal-Feld (Andi-Schloss-Wunsch) → geparst; fehlend/ungültig → false, NIE ein optimistisches Gruen', () => {
    const base = {
      enabled: true,
      overall: 'OK',
      memory: { level: 'OK', source: 'brain-health', detail: '' },
      sidecars: [],
      ts: 1,
    };
    expect(parseOpsStatus({ ...base, allLocal: true })!.allLocal).toBe(true);
    expect(parseOpsStatus({ ...base, allLocal: false })!.allLocal).toBe(false);
    // Additiv-tolerant: älteres BE ohne allLocal / Müll → false, KEIN Parse-Fail.
    expect(parseOpsStatus(base)!.allLocal).toBe(false);
    expect(parseOpsStatus({ ...base, allLocal: 'yes' })!.allLocal).toBe(false);
  });
});

describe('OpsStatusPill — Render', () => {
  it('status null → rendert NICHTS (graceful hidden, kein Lärm)', () => {
    expect(render(null)).toBe('');
  });

  it('OK → still: nur der Punkt, KEIN Dauertext/⚠ (kein amber/rot, KEIN Gold)', () => {
    const html = render(sample());
    expect(html).toContain('ops__pill--ok');
    expect(html).toContain('badge__dot'); // der stille Punkt bleibt
    // Kein Dauertext, kein Warn-Icon im ruhigen Zustand:
    expect(html).not.toContain('ops__icon');
    expect(html).not.toContain('⚠');
    expect(html).not.toContain('Ops ·');
    expect(html).not.toContain('ops__pill--warn');
    expect(html).not.toContain('ops__pill--critical');
    // Die Ops-Pille darf nie Gold tragen (Gold = nur Stimme/CTA):
    expect(html).not.toContain('accent');
    // Details bleiben erreichbar: title/aria + Panel mit memory.detail.
    expect(html).toContain('Ops: Gesamt OK');
    expect(html).toContain('ops__panel');
    expect(html).toContain('RAM entspannt.');
  });

  it('WARN → fällt auf (amber), benennt RAM-Druck', () => {
    const html = render(
      sample({
        overall: 'DEGRADED',
        memory: { level: 'WARN', source: 'brain-health', detail: 'RAM-Druck steigt.' },
      }),
    );
    expect(html).toContain('ops__pill--warn');
    expect(html).not.toContain('ops__pill--critical');
    expect(html).toContain('RAM-Druck');
    expect(html).toContain('glyph--warn'); // Warn-SVG statt ⚠-Zeichen
    expect(html).not.toContain('⚠');
  });

  it('CRITICAL → fällt auf (rot), nennt RAM + listet die Sidecars im Detail', () => {
    const html = render(
      sample({
        overall: 'DOWN',
        memory: { level: 'CRITICAL', source: 'brain-health', detail: 'RAM kritisch — Swap aktiv.' },
        sidecars: [
          { name: 'brain', status: 'DOWN', detail: 'OOM' },
          { name: 'bridge', status: 'DEGRADED', detail: 'langsam' },
        ],
      }),
    );
    expect(html).toContain('ops__pill--critical');
    expect(html).toContain('RAM'); // RAM wird benannt
    expect(html).toContain('RAM kritisch — Swap aktiv.'); // memory.detail im Panel
    expect(html).toContain('brain');
    expect(html).toContain('bridge');
    expect(html).toContain('ops__sc--down');
    expect(html).toContain('ops__sc--degraded');
  });
});

describe('OpsStatusPill — WARN/CRITICAL klickbar (Panel = das WARUM)', () => {
  const warnStatus = (sidecars: OpsStatus['sidecars']): OpsStatus =>
    sample({
      overall: 'DEGRADED',
      memory: { level: 'WARN', source: 'brain-health', detail: 'RAM-Druck steigt.' },
      sidecars,
    });

  it('WARN → echte <button>-Pille, zu: aria-expanded="false" + aria-controls', () => {
    const html = render(warnStatus([{ name: 'voxtral-tts', status: 'DOWN', detail: 'keine Antwort auf :8042' }]));
    expect(html).toContain('<button');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('aria-controls=');
    expect(html).not.toContain('ops--open');
  });

  it('offen (defaultExpanded) → aria-expanded="true" + Panel trägt die ehrlichen Gründe aus den Daten', () => {
    const html = render(
      warnStatus([
        { name: 'brain', status: 'OK', detail: 'läuft' },
        { name: 'voxtral-tts', status: 'DOWN', detail: 'keine Antwort auf :8042' },
      ]),
      true,
    );
    expect(html).toContain('aria-expanded="true"');
    expect(html).toContain('ops--open');
    // Die Gründe kommen 1:1 aus den Fake-Daten …
    expect(html).toContain('voxtral-tts');
    expect(html).toContain('keine Antwort auf :8042');
    expect(html).toContain('RAM-Druck steigt.');
    // … Probleme stehen zuerst (DOWN vor OK) …
    expect(html.indexOf('voxtral-tts')).toBeLessThan(html.indexOf('brain'));
    // … und NICHTS wird erfunden: kein Cloud-Banner, solange der Ops-Status
    // kein voice-Feld trägt (voice:null = ehrliches „weiß ich nicht").
    expect(html).not.toContain('Cloud');
    expect(html).not.toContain('OpenAI');
  });

  it('OK bleibt still: kein <button>, kein aria-expanded — der Punkt von heute', () => {
    const html = render(sample());
    expect(html).not.toContain('<button');
    expect(html).not.toContain('aria-expanded');
    expect(html).toContain('ops__pill--ok');
    expect(html).toContain('ops__panel'); // Details weiter per Hover/Fokus erreichbar
  });

  it('defaultExpanded wirkt im OK-Zustand NICHT (offen zählt nur bei WARN/CRITICAL)', () => {
    const html = render(sample(), true);
    expect(html).not.toContain('ops--open');
    expect(html).not.toContain('aria-expanded');
  });
});

describe('OpsStatusPill — Toms ☁️-Cloud-Banner („Cloud nur mit Banner")', () => {
  it('voice.cloud:true (OpenAI) → Banner-Zeile im Panel (Wolken-SVG statt ☁️)', () => {
    const html = render(sample({ voice: { engine: 'openai', cloud: true } }));
    expect(html).toContain('ops__cloud');
    expect(html).toContain('Stimme kommt gerade aus der Cloud (OpenAI)');
    expect(html).toContain('glyph--cloud'); // muted SVG-Glyph …
    expect(html).not.toContain('☁'); // … kein Emoji im Chrome
  });

  it('voice.cloud:true → Banner auch im WARN-Panel (unabhängig vom Ton)', () => {
    const html = render(
      sample({
        overall: 'DEGRADED',
        memory: { level: 'WARN', source: 'brain-health', detail: 'RAM-Druck steigt.' },
        voice: { engine: 'openai', cloud: true },
      }),
      true,
    );
    expect(html).toContain('Stimme kommt gerade aus der Cloud (OpenAI)');
    expect(html).toContain('glyph--cloud');
  });

  it('lokale Engine (voxtral, cloud:false) → KEIN Cloud-Banner, stattdessen die ehrliche Lokal-Zeile', () => {
    const html = render(sample({ voice: { engine: 'voxtral', cloud: false } }));
    expect(html).not.toContain('ops__cloud');
    expect(html).not.toContain('Cloud');
    expect(html).not.toContain('OpenAI');
    // Andi-Befund 2026-07-20: statt Stille steht jetzt die ehrliche Gegenzeile.
    expect(html).toContain('ops__voicelocal');
    expect(html).toContain('Stimme (voxtral): läuft lokal — verlässt das Gerät nicht.');
    expect(html).toContain('glyph--lock');
  });

  it('gewählte Engine say → dieselbe Lokal-Zeile, nur mit dem Engine-Namen', () => {
    const html = render(sample({ voice: { engine: 'say', cloud: false } }));
    expect(html).not.toContain('ops__cloud');
    expect(html).toContain('Stimme (say): läuft lokal — verlässt das Gerät nicht.');
  });

  it('gewählte Engine piper → dieselbe Lokal-Zeile, nur mit dem Engine-Namen', () => {
    const html = render(sample({ voice: { engine: 'piper', cloud: false } }));
    expect(html).not.toContain('ops__cloud');
    expect(html).toContain('Stimme (piper): läuft lokal — verlässt das Gerät nicht.');
  });

  it('voice:null (BE liefert das Feld nicht) → weder Banner noch Lokal-Zeile (nichts behaupten)', () => {
    const html = render(sample({ voice: null }));
    expect(html).not.toContain('ops__cloud');
    expect(html).not.toContain('ops__voicelocal');
    expect(html).not.toContain('Cloud');
  });
});

describe('OpsStatusPill — grünes Schloss (allLocal, Andi-Wunsch 2026-07-20)', () => {
  it('allLocal:true → grünes Schloss mit dem ehrlichen Gesamt-Text', () => {
    const html = render(sample({ allLocal: true, voice: { engine: 'voxtral', cloud: false } }));
    expect(html).toContain('ops__lock');
    expect(html).toContain(
      'Alles lokal — deine Stimme verlässt das Gerät nicht. Online-Recherche nur nach deiner Freigabe.',
    );
    expect(html).toContain('glyph--lock');
  });

  it('allLocal:false → kein Schloss, kein Alarm-Pendant (einfach nichts)', () => {
    const html = render(sample({ allLocal: false }));
    expect(html).not.toContain('ops__lock');
    expect(html).not.toContain('Alles lokal');
  });

  it('Schloss und Lokal-Zeile können gemeinsam stehen (zwei ehrliche, unabhängige Aussagen)', () => {
    const html = render(sample({ allLocal: true, voice: { engine: 'piper', cloud: false } }));
    expect(html).toContain('ops__voicelocal'); // die TTS-Engine-Zeile (Teilaussage) …
    expect(html).toContain('ops__lock'); // … UND das Gesamt-Schloss (Rundum-Aussage).
  });
});

describe('fetchOpsStatus — best-effort, graceful', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('404 → null (Feature aus / Endpoint fehlt → still, kein roter Fehler)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    expect(await fetchOpsStatus()).toBeNull();
  });

  it('Netzfehler → null', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    expect(await fetchOpsStatus()).toBeNull();
  });

  it('200 + enabled:true → geparster Status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            enabled: true,
            overall: 'OK',
            memory: { level: 'OK', source: 'brain-health', detail: 'ok' },
            sidecars: [],
            ts: 1,
          }),
      }),
    );
    const s = await fetchOpsStatus();
    expect(s).not.toBeNull();
    expect(s!.overall).toBe('OK');
  });
});
