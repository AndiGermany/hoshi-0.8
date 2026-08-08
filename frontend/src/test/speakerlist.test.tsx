import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  ENROLL_TOTAL_SAMPLES,
  SPEAKER_TEXTS,
  SpeakerListView,
  fitLabel,
  formatEnrolledDate,
  formatSampleDuration,
  formatSampleTimestamp,
  micSupport,
} from '../components/SpeakerSection';
import type { SpeakerDiagnostics, SpeakerSummary } from '../api/speakers';

const speaker = (over: Partial<SpeakerSummary> = {}): SpeakerSummary => ({
  name: 'andi',
  enrolledAt: 1720000000000, // 2024-07-03
  ...over,
});

const render = (props: Partial<Parameters<typeof SpeakerListView>[0]> = {}) =>
  renderToStaticMarkup(
    <SpeakerListView
      speakers={[speaker()]}
      onDelete={() => {}}
      onEnroll={() => {}}
      onContinue={() => {}}
      onDeleteSample={() => {}}
      {...props}
    />,
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

  it('Status-Badge: <9 Sätze ⇒ „in Arbeit", genau 9 ⇒ „vollständig" (Andi-Auftrag 25.07: drei Sitzungen)', () => {
    expect(ENROLL_TOTAL_SAMPLES).toBe(9);
    const html = render({
      speakers: [
        speaker({ name: 'halb', samples: 3 }),
        speaker({ name: 'fast', samples: 8 }),
        speaker({ name: 'voll', samples: 9 }),
      ],
    });
    expect(html).toContain(SPEAKER_TEXTS.statusInProgress);
    expect(html).toContain(SPEAKER_TEXTS.statusComplete);
    // „voll" (9 Sätze) ist die einzige mit dem Vollständig-Badge — Rest ist noch in Arbeit.
    const inProgressCount = (html.match(new RegExp(SPEAKER_TEXTS.statusInProgress, 'g')) ?? []).length;
    expect(inProgressCount).toBe(2);
    expect(render()).not.toContain(SPEAKER_TEXTS.statusInProgress); // ohne samples-Feld: nichts erfinden
    expect(render()).not.toContain(SPEAKER_TEXTS.statusComplete);
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

  it('„Weiter anlernen"-Knopf steht an JEDER Profil-Zeile (Andi-Auftrag 07.08)', () => {
    const html = render({ speakers: [speaker({ name: 'andi' }), speaker({ name: 'mira' })] });
    expect((html.match(new RegExp(SPEAKER_TEXTS.continueButton, 'g')) ?? []).length).toBe(2);
    expect(html).toContain(SPEAKER_TEXTS.continueAria('andi'));
    expect(html).toContain(SPEAKER_TEXTS.continueAria('mira'));
  });

  it('Aufnahmen-Liste (Reparatur-Auftrag 07.08): ohne Diagnose-Daten ehrlich „lädt…", NIE geraten', () => {
    // diagnostics-Prop fehlt (noch nicht geladen) ⇒ jede Zeile zeigt die Lade-Zeile statt Unsinn.
    expect(render()).toContain(SPEAKER_TEXTS.loading);
  });

  it('Aufnahmen-Liste: Datum · Dauer · „passt zu mir" + Rohwert im title, Einzel-Löschen gesperrt bei der letzten Aufnahme', () => {
    const diagnostics: SpeakerDiagnostics = {
      profiles: [
        {
          name: 'andi',
          samples: 2,
          selfCohesion: 0.9,
          leaveOneOutSimilarity: [0.75, 0.2],
          bestForeignSimilarity: {},
          sampleOrigins: [
            { recordedAt: 1720000000000, session: 1, device: 'mac', durationSeconds: 2.3, rms: 0.1 },
            { recordedAt: null, session: null, device: null, durationSeconds: null, rms: null },
          ],
        },
      ],
      crossSimilarity: {},
    };
    const html = render({ diagnostics });
    expect(html).toContain(SPEAKER_TEXTS.recordingsToggle(2));
    expect(html).toContain(SPEAKER_TEXTS.fitGood); // 0.75 > 0.6
    expect(html).toContain(SPEAKER_TEXTS.fitPoor); // 0.2 < 0.35
    expect(html).toContain('title="0.750"');
    expect(html).toContain(formatSampleDuration(2.3));
    expect(html).toContain(formatSampleTimestamp(1720000000000));
    // Unbekannte Herkunft (Alt-Aufnahme) ⇒ ehrlich „unbekannt", nie geraten.
    expect(html).toContain(SPEAKER_TEXTS.recordingUnknown);
    // 2 Aufnahmen (nicht die letzte) ⇒ KEIN Löschen-Knopf gesperrt.
    expect(html).not.toContain('disabled=""');
  });

  it('Aufnahmen-Liste: letzte/einzige Aufnahme ⇒ Löschen-Knopf gesperrt mit Hinweis, fitUnknown ohne Vergleichswert', () => {
    const diagnostics: SpeakerDiagnostics = {
      profiles: [
        {
          name: 'andi',
          samples: 1,
          selfCohesion: null,
          leaveOneOutSimilarity: [], // <2 Samples ⇒ leer, nicht geraten
          bestForeignSimilarity: {},
          sampleOrigins: [
            { recordedAt: 1720000000000, session: null, device: null, durationSeconds: 1.5, rms: 0.05 },
          ],
        },
      ],
      crossSimilarity: {},
    };
    const html = render({ diagnostics });
    expect(html).toContain(SPEAKER_TEXTS.fitUnknown);
    expect(html).toContain(SPEAKER_TEXTS.deleteRecordingLastHint);
    expect(html).toContain('disabled=""');
  });
});

describe('fitLabel — „passt zu mir"-Text aus leaveOneOutSimilarity, Rohwert im title', () => {
  it('> 0.6 ⇒ fitGood, 0.35..0.6 ⇒ fitMedium, < 0.35 ⇒ fitPoor, undefined ⇒ fitUnknown ohne title', () => {
    expect(fitLabel(SPEAKER_TEXTS, 0.75).text).toBe(SPEAKER_TEXTS.fitGood);
    expect(fitLabel(SPEAKER_TEXTS, 0.5).text).toBe(SPEAKER_TEXTS.fitMedium);
    expect(fitLabel(SPEAKER_TEXTS, 0.6).text).toBe(SPEAKER_TEXTS.fitMedium); // Grenzwert: inklusive
    expect(fitLabel(SPEAKER_TEXTS, 0.1).text).toBe(SPEAKER_TEXTS.fitPoor);
    const unknown = fitLabel(SPEAKER_TEXTS, undefined);
    expect(unknown.text).toBe(SPEAKER_TEXTS.fitUnknown);
    expect(unknown.title).toBeUndefined();
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
