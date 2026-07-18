import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  SPEAKER_TEXTS,
  SpeakerListView,
  formatEnrolledDate,
  micSupport,
} from '../components/SpeakerSection';
import type { SpeakerSummary } from '../api/speakers';

const speaker = (over: Partial<SpeakerSummary> = {}): SpeakerSummary => ({
  name: 'andi',
  enrolledAt: 1720000000000, // 2024-07-03
  ...over,
});

const render = (props: Partial<Parameters<typeof SpeakerListView>[0]> = {}) =>
  renderToStaticMarkup(
    <SpeakerListView speakers={[speaker()]} onDelete={() => {}} onEnroll={() => {}} {...props} />,
  );

describe('SpeakerListView — Liste rendert aus GET-Daten', () => {
  it('zeigt Titel, getrennt von HOSHIS Stimme, + Consent-by-Design-Zeile', () => {
    const html = render();
    expect(html).toContain('Erkannte Sprecher');
    // Consent by Design steht sichtbar (Sara-Regel: keine Verwechslung mit HOSHIS Stimme).
    expect(html).toContain('Jede/r lernt die EIGENE Stimme an — dein Profil gehört dir.');
  });

  it('rendert je Person Name + Anlern-Datum + Avatar-Initiale', () => {
    const html = render({
      speakers: [speaker({ name: 'andi' }), speaker({ name: 'mira' })],
    });
    expect(html).toContain('andi');
    expect(html).toContain('mira');
    expect(html).toContain('angelernt'); // Datum-Zeile
    expect(html).toContain('>A<'); // Initiale von andi (Chip)
    expect(html).toContain('>M<'); // Initiale von mira
  });

  it('zeigt die Sample-Zahl ehrlich (1 Satz / 3 Sätze) — und lässt sie weg, wenn unbekannt', () => {
    const html = render({
      speakers: [speaker({ name: 'andi', samples: 3 }), speaker({ name: 'alt', samples: 1 })],
    });
    expect(html).toContain('3 Sätze'); // Multi-Sample-Profil
    expect(html).toContain('1 Satz'); // Alt-Profil (Ein-Satz) bleibt ehrlich sichtbar
    expect(render()).not.toContain('Satz'); // ohne samples-Feld: nichts erfinden
  });

  it('gibt je Person genau EINEN Löschen-Knopf (dein Profil, dein Löschen)', () => {
    const html = render({ speakers: [speaker({ name: 'andi' }), speaker({ name: 'mira' })] });
    const buttons = html.match(/settings__deletebtn/g) ?? [];
    expect(buttons.length).toBe(2);
    expect(html).toContain('Löschen');
    expect(html).not.toContain(SPEAKER_TEXTS.confirm); // unscharf
  });

  it('scharf (erster Klick): „Wirklich? Klick nochmal" NUR an der gewählten Person', () => {
    const html = render({
      speakers: [speaker({ name: 'andi' }), speaker({ name: 'mira' })],
      armed: 'andi',
    });
    expect(html).toContain(SPEAKER_TEXTS.confirm);
    expect((html.match(/is-armed/g) ?? []).length).toBe(1);
  });

  it('leere Liste ⇒ ehrliche „noch niemand"-Zeile (kein Fake-Eintrag)', () => {
    const html = render({ speakers: [] });
    expect(html).toContain(SPEAKER_TEXTS.empty);
    expect(html).not.toContain('settings__deletebtn');
  });

  it('Anlern-Knopf ist immer da; lädt…/Fehler ehrlich', () => {
    expect(render()).toContain(SPEAKER_TEXTS.enrollButton);
    expect(render({ speakers: null, loading: true })).toContain('lädt…');
    expect(render({ speakers: null, error: SPEAKER_TEXTS.loadError })).toContain(
      SPEAKER_TEXTS.loadError,
    );
  });

  it('Notiz (angelernt / Löschen fehlgeschlagen) wird ehrlich gerendert', () => {
    expect(render({ note: SPEAKER_TEXTS.enrolledNote })).toContain(SPEAKER_TEXTS.enrolledNote);
    expect(render({ note: SPEAKER_TEXTS.deleteFailed })).toContain('unverändert');
  });
});

describe('formatEnrolledDate — nie eine erfundene Zahl', () => {
  it('0/fehlend ⇒ „gerade eben" (statt 1970)', () => {
    expect(formatEnrolledDate(0)).toBe('gerade eben');
  });
  it('echter Zeitstempel ⇒ nicht-leeres, deutsches Datum', () => {
    const s = formatEnrolledDate(1720000000000);
    expect(s.length).toBeGreaterThan(0);
    expect(s).not.toBe('gerade eben');
  });
});

describe('micSupport — ehrliche Kapazitätsprobe (kein Fake)', () => {
  it('kein Mikro/kein mediaDevices ⇒ ok:false mit ehrlichem Grund', () => {
    // node-Env: kein navigator.mediaDevices, kein MediaRecorder → ehrlicher no-mic-Grund.
    const cap = micSupport();
    expect(cap.ok).toBe(false);
    expect(cap.reason).toBeTruthy();
  });
});
