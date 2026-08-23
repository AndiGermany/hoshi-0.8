import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { HomeStatusBar } from '../components/HomeStatusBar';
import type { HealthState } from '../hooks/useHealth';
import type { OpsVoice } from '../hooks/useOpsStatus';

// ═════════════════════════════════════════════════════════════════════════════
//  homestatusbar.test — die Zuhause-Fußleiste (Andi-Bestellung 19.08.:
//  „Ich möchte unten links die Statusmeldung mit dem ● online · Stimme: lokal.
//  Das aber auch schön eingebunden, etwas wie die Leiste oben, nur unten.")
//
//  Diese Datei ist der UMGEZOGENE Test: die Aussagen standen bis 19.08. in
//  `idleface.test.tsx` („stille Text-Chips: Health ehrlich, Stimme nur wenn
//  gemessen") und prüfen unverändert dieselbe Wahrheit — nur eben an der
//  Stelle, an der sie jetzt gerendert wird. Kein einziger Satz an Andis
//  Bildschirm hat sich geändert; die Position hat sich geändert.
//
//  Die pure Regel selbst (`statusChips`) bleibt in `idleface.test.tsx`
//  geprüft, wo sie auch wohnt.
// ═════════════════════════════════════════════════════════════════════════════

const render = (health: HealthState = 'up', voice: OpsVoice | null = null) =>
  renderToStaticMarkup(<HomeStatusBar health={health} voice={voice} />);

describe('Zuhause-Fußleiste', () => {
  it('trägt die Chips in einer eigenen Leiste — der Haken, an dem die Optik hängt', () => {
    const html = render();
    expect(html).toContain('<footer class="homefoot">');
    expect(html).toContain('idle__chips');
  });

  it('Health ehrlich: online / offline / wird geprüft — nie geraten', () => {
    expect(render('up')).toContain('online');
    expect(render('down')).toContain('offline');
    expect(render('unknown')).toContain('wird geprüft');
  });

  it('der Stimme-Chip erscheint NUR, wenn das BE das voice-Feld liefert', () => {
    expect(render('up', null)).not.toContain('Stimme:');
  });

  it('Cloud-Stimme: Text + Wolken-SVG, kein Emoji', () => {
    const cloud = render('up', { engine: 'openai', cloud: true });
    expect(cloud).toContain('Stimme: Cloud');
    expect(cloud).toContain('glyph--cloud');
    expect(cloud).not.toContain('☁');
  });

  it('lokale Stimme: Text + Schloss-SVG, kein Emoji', () => {
    const local = render('up', { engine: 'voxtral', cloud: false });
    expect(local).toContain('Stimme: lokal');
    expect(local).toContain('glyph--lock');
    expect(local).not.toContain('🔒');
  });

  it('die Leiste meldet sich als Status-Region — Vorlesen ohne Aufdringlichkeit', () => {
    const html = render();
    expect(html).toContain('role="status"');
    expect(html).toContain('aria-live="polite"');
  });
});
