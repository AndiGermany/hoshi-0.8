import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SendButton } from '../components/SendButton';

// Render-Vertrag des „Senden"-CTA (Static-Markup, kein DOM nötig — wie voicestar).
// Sichert Andis Wunsch #1: KEIN nackter Pfeil mehr, sondern eine benannte,
// barrierearme Pille (Paper-Plane + sichtbares „Senden", aria-label, sauberer
// Disabled-Vertrag bei leerem Feld / laufendem Stream).
const render = (props: { disabled: boolean; busy: boolean }) =>
  renderToStaticMarkup(<SendButton {...props} />);

describe('SendButton — benannter Senden-CTA', () => {
  it('trägt das a11y-Label „Senden" und das sichtbare Wort statt eines nackten Pfeils', () => {
    const html = render({ disabled: false, busy: false });
    expect(html).toContain('aria-label="Senden"');
    expect(html).toContain('compose__send-label');
    expect(html).toContain('Senden');
    expect(html).toContain('type="submit"');
    // kein nackter ↑-Pfeil mehr.
    expect(html).not.toContain('↑');
  });

  it('zeigt im Ruhezustand das Paper-Plane-Glyph (svg), nicht die Denk-Punkte', () => {
    const html = render({ disabled: false, busy: false });
    expect(html).toContain('compose__send-ico');
    expect(html).toContain('<svg');
    expect(html).not.toContain('compose__send-dots');
  });

  it('ist disabled, wenn das Eingabefeld leer / nicht sendbar ist (disabled=true)', () => {
    expect(render({ disabled: true, busy: false })).toContain('disabled');
  });

  it('ist NICHT disabled, wenn sendbar (disabled=false)', () => {
    expect(render({ disabled: false, busy: false })).not.toContain('disabled');
  });

  it('zeigt während des Streams (busy) ruhige Denk-Punkte statt des Flugzeugs', () => {
    const html = render({ disabled: true, busy: true });
    expect(html).toContain('compose__send-dots');
    expect(html).not.toContain('<svg');
    // Label + Label-Vertrag bleiben (stabile Pillenbreite, weiter benannt).
    expect(html).toContain('aria-label="Senden"');
    expect(html).toContain('Senden');
  });
});
