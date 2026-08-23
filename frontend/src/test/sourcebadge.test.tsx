import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SourceBadge } from '../components/SourceBadge';

/**
 * **sourcebadge.test** — Kurs-Update (Andi-Bestellung): ein lokales SVG-Badge
 * je bekannter Quelle, gekoppelt an die Source-ID (nicht an den freien
 * `attribution`-Text), unbekannte Id ⇒ `null` (Text-only-Fallback beim
 * Aufrufer). Kein Netz, kein Import einer echten Logo-Datei — reine
 * Kreis+Initiale-SVG, geprüft gegen den Wire-Vertrag der drei Wave-1-Quellen.
 */

describe('SourceBadge — lokales SVG-Badge je Quelle', () => {
  it.each([
    ['TAGESSCHAU', 'T'],
    ['HEISE', 'h'],
    ['GOLEM', 'G'],
  ] as const)('bekannte Quelle %s rendert ein SVG mit der Initiale %s', (sourceId, initial) => {
    const html = renderToStaticMarkup(<SourceBadge sourceId={sourceId} />);
    expect(html).toContain('<svg');
    expect(html).toContain(`>${initial}<`);
    expect(html).toContain('aria-hidden="true"');
  });

  it('unbekannte/zukuenftige Quelle ⇒ null (kein SVG, kein Crash) — Fallback ist Text-only beim Aufrufer', () => {
    const html = renderToStaticMarkup(<SourceBadge sourceId="NETZPOLITIK" />);
    expect(html).toBe('');
  });

  it('leerer/erfundener String ⇒ ebenfalls null', () => {
    expect(renderToStaticMarkup(<SourceBadge sourceId="" />)).toBe('');
    expect(renderToStaticMarkup(<SourceBadge sourceId="BILD" />)).toBe('');
  });

  it('Groesse ist em-basiert (folgt der umgebenden Schriftgroesse, kein Hardcode-Pixelwert)', () => {
    const html = renderToStaticMarkup(<SourceBadge sourceId="TAGESSCHAU" />);
    expect(html).toContain('width="1em"');
    expect(html).toContain('height="1em"');
  });

  it('die drei bekannten Quellen tragen VERSCHIEDENE Farben (Marken-Unterscheidbarkeit)', () => {
    const fills = (['TAGESSCHAU', 'HEISE', 'GOLEM'] as const).map((id) => {
      const html = renderToStaticMarkup(<SourceBadge sourceId={id} />);
      return /fill="(#[0-9a-f]{6})"/i.exec(html)?.[1];
    });
    expect(new Set(fills).size).toBe(3);
  });
});
