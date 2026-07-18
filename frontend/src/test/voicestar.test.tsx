import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { VoiceStar, type StarState } from '../components/VoiceStar';

// Render-Vertrag des neu gestalteten Voice-Orbs (Static-Markup, kein DOM nötig).
// Sichert: Zustands-Klasse, gestapelte Schichten, das a11y-Label und vor allem
// die Pegel-Durchreichung als --lvl (nur bei echtem Audiofluss, geklemmt 0..1).
const render = (state: StarState, level: number, label: string) =>
  renderToStaticMarkup(<VoiceStar state={state} level={level} label={label} />);

describe('VoiceStar — atmender Voice-Orb', () => {
  it('rendert den Zustand als vc-orb--{state} und trägt das a11y-Label', () => {
    const html = render('listening', 0.5, 'höre zu…');
    expect(html).toContain('vc-orb vc-orb--listening');
    expect(html).toContain('data-state="listening"');
    expect(html).toContain('höre zu…');
    expect(html).toContain('role="status"');
    expect(html).toContain('aria-live="polite"');
  });

  it('enthält die drei gestapelten Orb-Schichten (bloom/ring/core)', () => {
    const html = render('idle', 0, '');
    expect(html).toContain('vc-orb__bloom');
    expect(html).toContain('vc-orb__ring');
    expect(html).toContain('vc-orb__core');
  });

  it('reicht den Pegel NUR bei listening/speaking als --lvl durch', () => {
    expect(render('listening', 0.5, 'x')).toMatch(/--lvl:\s*0\.5\b/);
    expect(render('speaking', 0.8, 'x')).toMatch(/--lvl:\s*0\.8\b/);
    // idle/thinking: kein echter Audiofluss → --lvl bleibt 0 (kein Fake-Ausschlag).
    expect(render('idle', 0.9, '')).toMatch(/--lvl:\s*0(?![.\d])/);
    expect(render('thinking', 0.9, 'x')).toMatch(/--lvl:\s*0(?![.\d])/);
  });

  it('klemmt den Pegel auf 0..1 (kein Über-/Unterlauf in der CSS-Var)', () => {
    expect(render('speaking', 2, 'x')).toMatch(/--lvl:\s*1\b/);
    expect(render('listening', -1, 'x')).toMatch(/--lvl:\s*0(?![.\d])/);
  });
});
