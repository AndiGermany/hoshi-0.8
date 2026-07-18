import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { VoiceWaveform } from '../components/VoiceWaveform';

// Statischer Render-Vertrag der lebenden Welle (node-env, kein DOM). Die
// Canvas-/rAF-Mechanik (Sinusse, Lerp, Materialisieren, reduced-motion) braucht
// echtes DOM + 2D-Context und wird live verifiziert (Chrome/Hörprobe); KURVEN,
// Gamma/Floor/FillTarget und Materialize-Timing pinnt test/motiontokens.test.ts
// headless. DASS die Welle nur bei offenem Audio-Kanal existiert, pinnt
// composeSlot (voicefeedback.test) + idleface.test (Übersicht ohne Welle).
// Hier sichern wir die Struktur (Canvas im vc-wave-Band) + a11y (dekorativ).
describe('VoiceWaveform — lebende Welle (Canvas, Spec §3)', () => {
  it('rendert das vc-wave-Band mit Canvas und ist aria-hidden', () => {
    const html = renderToStaticMarkup(<VoiceWaveform />);
    expect(html).toContain('class="vc-wave ');
    expect(html).toContain('aria-hidden="true"');
    expect(html).toContain('<canvas');
    expect(html).toContain('vc-wave__canvas');
  });

  it('reicht eine optionale className an den Container durch', () => {
    const html = renderToStaticMarkup(<VoiceWaveform className="xyz" />);
    expect(html).toMatch(/class="vc-wave xyz"/);
  });
});
