import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SkillsSection } from '../components/SettingsPanel';
import { SkillLockedError, fetchSkills, setSkill } from '../api/skills';
import type { Language, Skill } from '../api/types';

/** Gültige Skill-Zeile (togglebarer LOCAL-Skill), per `over` punktuell überschreibbar. */
const skill = (over: Partial<Skill> = {}): Skill => ({
  id: 'SMART_HOME',
  labelDe: 'Smart-Home',
  labelEn: 'Smart home',
  tier: 'LOCAL',
  ceilingOpen: true,
  enabled: true,
  effective: true,
  locked: false,
  ...over,
});

const render = (skills: Skill[], language: Language = 'de') =>
  renderToStaticMarkup(<SkillsSection skills={skills} language={language} onToggle={() => {}} />);

describe('SkillsSection — Render', () => {
  it('normale Zeile: Switch aktiv (NICHT disabled), spiegelt enabled', () => {
    const html = render([skill({ enabled: true })]);
    expect(html).toContain('role="switch"');
    expect(html).toContain('aria-checked="true"');
    expect(html).toContain('settings__toggle is-on');
    expect(html).not.toContain('disabled'); // togglebar → kein disabled-Attribut
    expect(html).toContain('Smart-Home');
  });

  it('enabled:false → Switch aus (kein is-on)', () => {
    const html = render([skill({ enabled: false, effective: false })]);
    expect(html).toContain('aria-checked="false"');
    expect(html).not.toContain('is-on');
  });

  it('locked: Switch disabled + Deploy-Badge; KEIN online-Badge bei LOCAL', () => {
    const html = render([
      skill({ id: 'SCENES', labelDe: 'Szenen', locked: true, ceilingOpen: false, effective: false }),
    ]);
    expect(html).toContain('disabled');
    expect(html).toContain('deaktiviert beim Deploy');
    expect(html).not.toContain('geht online');
  });

  it('EGRESS: „geht online"-Badge (Vorbau für CURRENCY/ONLINE_LOOKUP)', () => {
    const html = render([skill({ id: 'CURRENCY', labelDe: 'Währung', tier: 'EGRESS' })]);
    expect(html).toContain('geht online');
  });

  it('Sprache EN: englische Labels + englische Badges', () => {
    const html = render([skill({ tier: 'EGRESS', locked: true })], 'en');
    expect(html).toContain('Smart home');
    expect(html).toContain('goes online');
    expect(html).toContain('disabled at deploy');
  });

  it('Zukunfts-Skills: ausgegraut mit ehrlichem Grund, KEIN Fake-Toggle', () => {
    // Leere Server-Liste ⇒ jede switch-Rolle käme von den Future-Zeilen — es darf keine geben.
    // WEATHER ist hier BEWUSST raus: der Wetter-Ort ist eine echte Sektion geworden
    // (WeatherLocationSection, siehe weatherlocation.test.tsx) — kein Platzhalter mehr.
    const html = render([]);
    expect(html).not.toContain('Wetter');
    expect(html).toContain('Listen');
    expect(html).toContain('Andi-Gabel offen');
    expect(html).toContain('Musik');
    expect(html).toContain('Track startet');
    expect(html).toContain('settings__skill--future');
    expect(html).toContain('kommt noch');
    expect(html).not.toContain('role="switch"');
  });

  it('Zukunfts-Skills EN: übersetzte Labels + Gründe', () => {
    const html = render([], 'en');
    expect(html).toContain('Lists');
    expect(html).toContain('decision with Andi still open');
    expect(html).toContain('coming');
  });
});

describe('fetchSkills — Wire + defensiver Parse', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('GET → geparstes Skill[] (Müll-Einträge fallen still raus)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve([
            {
              id: 'SMART_HOME',
              labelDe: 'Smart-Home',
              labelEn: 'Smart home',
              tier: 'LOCAL',
              ceilingOpen: true,
              enabled: true,
              effective: true,
              locked: false,
            },
            null,
            'nope',
            { tier: 'LOCAL' }, // ohne id → verworfen
          ]),
      }),
    );
    const list = await fetchSkills();
    expect(list).toHaveLength(1);
    expect(list[0].id).toBe('SMART_HOME');
    expect(list[0].tier).toBe('LOCAL');
  });

  it('401 → wirft (Auth-Wand wird ehrlich durchgereicht)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchSkills()).rejects.toThrow(/401/);
  });
});

describe('setSkill — PUT-Vertrag + 409-Lock', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('PUT {enabled} an /skills/{id} und merged den autoritativen Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          id: 'TIMER',
          labelDe: 'Timer',
          labelEn: 'Timer',
          tier: 'LOCAL',
          ceilingOpen: true,
          enabled: false,
          effective: false,
          locked: false,
        }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const updated = await setSkill('TIMER', false);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/api/v1/settings/skills/TIMER');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ enabled: false });
    expect(updated.enabled).toBe(false); // FE übernimmt die Server-Wahrheit, rät nicht
  });

  it('409 → SkillLockedError (die UI klappt NICHT um)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: () => Promise.resolve({ error: 'deploy-disabled', id: 'SCENES' }),
      }),
    );
    await expect(setSkill('SCENES', true)).rejects.toBeInstanceOf(SkillLockedError);
  });
});
