import { describe, it, expect, vi, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { OpsStatusPill } from '../components/OpsStatusPill';
import { parseOpsStatus, fetchOpsStatus, type OpsStatus } from '../hooks/useOpsStatus';
import { CATALOGS, SUPPORTED_UI_LANGUAGES, setActiveUiLanguage } from '../i18n';

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

  // Andi-Auftrag 2026-07-25/26 (Speicherdruck sichtbar statt Auto-Switch): CRITICAL
  // zeigt einen WARMEN, verständlichen Hinweis statt nur der technischen Kennzahl —
  // die Pille selbst wird zum Hinweis (der nackte Pegel bleibt zusätzlich im Panel).
  it('CRITICAL → die Pille-Kopfzeile trägt den warmen Speicher-Hinweis, nicht nur "RAM kritisch"', () => {
    const html = render(
      sample({
        overall: 'DOWN',
        memory: { level: 'CRITICAL', source: 'brain-health', detail: 'RAM kritisch — Swap aktiv.' },
      }),
    );
    expect(html).toContain('Speicher knapp — die Stimme kann gerade zäh werden.');
    // KEINE Modal-/Toast-Kaskade: genau EIN Panel, keine zweite Warn-Fläche.
    expect(html.match(/ops__panel/g)?.length).toBe(1);
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

// ─────────────────────────────────────────────────────────────────────────────
//  Home Assistant (S4 „HA wird sichtbar")
//
//  Das BE hängt HA als GEWÖHNLICHE Sidecar-Zeile an (`home-assistant`) — die
//  Pille braucht dafür keinen Sonderweg, sie zeigt jede Zeile, die der Status
//  trägt. Der Name bleibt bewusst unübersetzt, exakt wie `brain`/`whisper-stt`
//  (Vertrags- + Produktname, kein UI-Text; s. i18nsweep.test.tsx). Die Zeile
//  existiert nur, wenn HA für diese Installation konfiguriert ist — eine
//  HA-lose Box zeigt keine erfundene Zeile.
// ─────────────────────────────────────────────────────────────────────────────

describe('OpsStatusPill — Home-Assistant-Zeile', () => {
  it('HA DOWN → eigene Zeile mit dem ehrlichen Grund, VOR den OK-Zeilen', () => {
    const html = render(
      sample({
        overall: 'DOWN',
        sidecars: [
          { name: 'brain', status: 'OK', detail: 'läuft' },
          { name: 'home-assistant', status: 'DOWN', detail: 'http://ha:8123/api/ — keine Antwort' },
        ],
      }),
      true,
    );
    expect(html).toContain('home-assistant');
    expect(html).toContain('ops__sc--down');
    expect(html).toContain('http://ha:8123/api/ — keine Antwort');
    // Probleme zuerst (problemsFirst) — das tote Haus steht über dem gesunden Brain.
    expect(html.indexOf('home-assistant')).toBeLessThan(html.indexOf('brain'));
  });

  it('HA OK → ruhige Zeile im Panel, kein Alarm-Ton', () => {
    const html = render(
      sample({
        sidecars: [{ name: 'home-assistant', status: 'OK', detail: 'status=ok (API running)' }],
      }),
    );
    expect(html).toContain('home-assistant');
    expect(html).toContain('ops__sc--ok');
    expect(html).toContain('ops__pill--ok');
  });

  it('HA-lose Box (keine Zeile im Status) → nichts erfunden', () => {
    const html = render(sample());
    expect(html).not.toContain('home-assistant');
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

// ─────────────────────────────────────────────────────────────────────────────
//  Kopfzeilen-Layout (Andi-Befund 2026-08-14: „Die Speicher-knapp-Meldung
//  verrutscht in der Zeile, wenn sie kommt.")
//
//  Gemessen (headless Chrome, echtes index.css, Top-Nav 1:1): die nowrap-Pille
//  wuchs vom stillen 16px-Punkt auf 440px (de) bzw. 595px (it) — mehr als der
//  freie Platz der Zeile (~236px). Folge: die Status-Gruppe fiel auf eine zweite
//  Zeile (Nav 59px → 103px), die Reiter sprangen 72px, und auf Telefonbreiten
//  lief die Pille um bis zu 261px aus der Nav-Insel (H-Scroll der Seite).
//
//  Riegel dagegen, zweiteilig: die Kopfzeile ist das EINZIGE elastische Stück
//  (kürzbar/abschaltbar, s. CSS-Riegel unten) UND der volle Satz steht im Panel
//  plus im aria-label — gekürzt ja, verloren nie.
// ─────────────────────────────────────────────────────────────────────────────

describe('OpsStatusPill — die lange Meldung sprengt die Kopfzeile nicht', () => {
  const criticalStatus = (): OpsStatus =>
    sample({
      overall: 'DOWN',
      memory: { level: 'CRITICAL', source: 'brain-health', detail: 'RAM kritisch — Swap aktiv.' },
    });
  const warnStatus = (): OpsStatus =>
    sample({
      overall: 'DEGRADED',
      memory: { level: 'WARN', source: 'brain-health', detail: 'RAM-Druck steigt.' },
    });

  it('CRITICAL → die Pillen-Kopfzeile sitzt in der kürzbaren .ops__headline', () => {
    const html = render(criticalStatus());
    expect(html).toContain('ops__headline');
    // Der Text steckt IN dem kürzbaren Element, nicht frei in der Pille.
    expect(html).toMatch(
      /class="ops__headline">Speicher knapp — die Stimme kann gerade zäh werden\.</,
    );
  });

  it('CRITICAL → der VOLLE Satz steht im Panel (ein Tap/Klick, kein Hover-only)', () => {
    const html = render(criticalStatus(), true);
    expect(html).toMatch(
      /class="ops__hint">Speicher knapp — die Stimme kann gerade zäh werden\.</,
    );
    // Genau EIN Panel bleibt es (keine zweite Warn-Fläche, keine Toast-Kaskade).
    expect(html.match(/ops__panel/g)?.length).toBe(1);
    // Und der nackte Pegel bleibt zusätzlich in der RAM-Zeile.
    expect(html).toContain('RAM kritisch — Swap aktiv.');
  });

  it('CRITICAL → aria-label/title tragen den vollen Satz VOR dem Technik-Teil', () => {
    const html = render(criticalStatus());
    expect(html).toContain(
      'aria-label="Speicher knapp — die Stimme kann gerade zäh werden. · Ops: Gesamt DOWN · RAM CRITICAL',
    );
  });

  it('WARN → dieselbe Mechanik (kürzbare Kopfzeile + voller Text im Panel)', () => {
    const html = render(warnStatus(), true);
    expect(html).toMatch(/class="ops__headline">RAM-Druck</);
    expect(html).toMatch(/class="ops__hint">RAM-Druck</);
  });

  it('OK → weder headline noch hint: der stille Punkt bleibt unverändert', () => {
    const html = render(sample(), true);
    expect(html).not.toContain('ops__headline');
    expect(html).not.toContain('ops__hint');
    expect(html).toContain('ops__pill--ok');
  });

  // Nicht nur Deutsch: die längste Fassung (it, 78 Zeichen) muss denselben Weg
  // gehen — die Pille kürzt, das Panel trägt den ganzen Satz.
  it('alle UI-Sprachen: der volle memoryCriticalHint steht im Panel', () => {
    // React maskiert &<>"' im Text — das it-Katalogwort trägt ein Apostroph.
    const esc = (s: string): string =>
      s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#x27;');
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      setActiveUiLanguage(lang);
      const html = renderToStaticMarkup(
        <OpsStatusPill status={criticalStatus()} defaultExpanded />,
      );
      const full = esc(CATALOGS[lang].ops.memoryCriticalHint);
      expect(html, `${lang}: Kopfzeile fehlt in der Pille`).toContain(
        `class="ops__headline">${full}<`,
      );
      expect(html, `${lang}: voller Hinweis fehlt im Panel`).toContain(
        `class="ops__hint">${full}<`,
      );
    }
    setActiveUiLanguage('de');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  CSS-Riegel — die Zusagen oben hängen an echten Regeln in `src/index.css`
//  (Idiom wie themegroups.test.tsx: die ausgelieferte Datei lesen, statt
//  Selektoren im Test abzuschreiben). Ohne diesen Riegel könnte jemand die
//  Kürzung entfernen und der Befund vom 14.08. wäre lautlos zurück.
// ─────────────────────────────────────────────────────────────────────────────

describe('CSS-Riegel — die Ops-Pille darf die Nav-Zeile nicht sprengen', () => {
  const CSS = readFileSync('src/index.css', 'utf8');
  /** Alle Deklarationsblöcke eines Selektors (Basis + Media-Varianten). */
  const bodies = (selector: string): string => {
    const re = new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`, 'g');
    const found = [...CSS.matchAll(re)].map((m) => m[1]);
    expect(found.length, `Selektor ${selector} fehlt in index.css`).toBeGreaterThan(0);
    return found.join('\n');
  };

  it('.ops__headline kürzt mit Ellipse statt zu wachsen', () => {
    const css = bodies('.ops__headline');
    expect(css).toMatch(/max-width:/);
    expect(css).toMatch(/text-overflow:\s*ellipsis/);
    expect(css).toMatch(/overflow:\s*hidden/);
    expect(css).toMatch(/white-space:\s*nowrap/);
  });

  it('unter dem Tablet-Breakpoint fällt der Text ganz weg (Pille = Punkt + Glyph)', () => {
    const m = /@media \(max-width:\s*(\d+)px\)\s*\{\s*\.ops__headline\s*\{\s*display:\s*none/.exec(
      CSS,
    );
    expect(m, 'kein Media-Riegel für .ops__headline').not.toBeNull();
    // Muss die iPad-Hochkant-Breiten (768/810/834) mit abdecken — genau dort
    // hat Andi das Verrutschen gesehen.
    expect(Number(m![1])).toBeGreaterThanOrEqual(834);
  });

  it('die Flex-Kette darf schrumpfen (min-width:0 statt „nie unter Inhaltsbreite")', () => {
    for (const sel of ['.nav__status', '.ops', '.ops__pill', '.ops__headline']) {
      expect(bodies(sel), `${sel} ohne min-width:0`).toMatch(/min-width:\s*0/);
    }
  });

  it('Punkt und Warn-Glyph werden dabei nicht gequetscht', () => {
    expect(bodies('.badge__dot')).toMatch(/flex:\s*none/);
    expect(bodies('.ops__icon')).toMatch(/flex:\s*none/);
  });

  it('das Panel bricht lange Details (HA-URL, S4) um statt überzulaufen', () => {
    expect(bodies('.ops__panel')).toMatch(/overflow-wrap:\s*anywhere/);
    expect(bodies('.ops__sc')).toMatch(/flex-wrap:\s*wrap/);
    expect(bodies('.ops__scdetail')).toMatch(/min-width:\s*0/);
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
