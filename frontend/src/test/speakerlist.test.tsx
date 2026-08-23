import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { readFileSync } from 'node:fs';
import {
  ENROLL_TOTAL_SAMPLES,
  SPEAKER_TEXTS,
  SpeakerListView,
  SpeakerRecordingsView,
  fitLabel,
  formatEnrolledDate,
  formatSampleDuration,
  formatSampleTimestamp,
  micSupport,
} from '../components/SpeakerSection';
import type {
  SpeakerDiagnostics,
  SpeakerProfileDiagnostics,
  SpeakerSummary,
} from '../api/speakers';

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
      onRecordings={() => {}}
      {...props}
    />,
  );

/**
 * Die Aufnahmen-Liste selbst — sie ist mit der Redesign-Scheibe (§3.3/3) aus der
 * Profil-Zeile ins Overlay gezogen. Die drei Behauptungen darunter sind wörtlich
 * die des Reparatur-Auftrags 07.08; nur der ORT hat sich geändert, weshalb sie
 * jetzt gegen {@link SpeakerRecordingsView} statt gegen die Liste laufen.
 */
const renderRecordings = (
  diag: SpeakerProfileDiagnostics | undefined,
  over: Partial<Parameters<typeof SpeakerRecordingsView>[0]> = {},
) =>
  renderToStaticMarkup(
    <SpeakerRecordingsView
      name="andi"
      diag={diag}
      onDeleteSample={() => {}}
      t={SPEAKER_TEXTS}
      {...over}
    />,
  );

/** Eine Profil-Diagnose bauen (nur die Felder, die die Liste wirklich liest). */
const profileDiag = (over: Partial<SpeakerProfileDiagnostics> = {}): SpeakerProfileDiagnostics => ({
  name: 'andi',
  samples: 2,
  selfCohesion: 0.9,
  leaveOneOutSimilarity: [0.75, 0.2],
  bestForeignSimilarity: {},
  sampleOrigins: [
    { recordedAt: 1720000000000, session: 1, device: 'mac', durationSeconds: 2.3, rms: 0.1 },
    { recordedAt: null, session: null, device: null, durationSeconds: null, rms: null },
  ],
  ...over,
});

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

  // ── Der Aufnahmen-EINSTIEG (§3.3/3): die Liste selbst wohnt seit dem Redesign
  //    im Overlay; in der 340-px-Spalte steht nur noch der Knopf, der sie öffnet.
  //    Genau das war die Ursache des panelweiten Querscrollbalkens (§1.4/1).

  it('Aufnahmen-Einstieg: ohne geladene Diagnose steht das zahlfreie Wort da — NIE eine geratene Zahl', () => {
    const html = render();
    expect(html).toContain(SPEAKER_TEXTS.recordingsOpen);
    expect(html).toContain(SPEAKER_TEXTS.recordingsOpenAria('andi'));
    // Kein Zähler, solange nichts gezählt wurde (der Klammer-Text kommt aus recordingsToggle).
    expect(html).not.toContain(SPEAKER_TEXTS.recordingsToggle(0));
    // …und die Aufnahmen-Zeilen selbst sind hier gar nicht mehr (sie sind im Overlay).
    expect(html).not.toContain('settings__recordingrow');
  });

  it('Aufnahmen-Einstieg: mit geladener Diagnose trägt der Knopf die ECHTE Zahl', () => {
    const diagnostics: SpeakerDiagnostics = { profiles: [profileDiag()], crossSimilarity: {} };
    const html = render({ diagnostics });
    expect(html).toContain(SPEAKER_TEXTS.recordingsToggle(2));
    // Die Zeilen bleiben trotzdem draußen — der Knopf ist der ganze Einstieg.
    expect(html).not.toContain('settings__recordingrow');
  });
});

describe('SpeakerRecordingsView — die Aufnahmen-Liste (jetzt im Overlay, §3.3/3)', () => {
  it('ohne Diagnose-Daten ehrlich „lädt…", NIE geraten (Reparatur-Auftrag 07.08)', () => {
    expect(renderRecordings(undefined)).toContain(SPEAKER_TEXTS.loading);
    // Eigener Fehlerkanal: schlug der Diagnose-GET fehl, steht sein Grund da.
    expect(renderRecordings(undefined, { diagnosticsError: 'kaputt' })).toContain('kaputt');
  });

  it('Datum · Dauer · „passt zu mir" + Rohwert im title, Einzel-Löschen offen bei zwei Aufnahmen', () => {
    const html = renderRecordings(profileDiag());
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

  it('letzte/einzige Aufnahme ⇒ Löschen-Knopf gesperrt mit Hinweis, fitUnknown ohne Vergleichswert', () => {
    const html = renderRecordings(
      profileDiag({
        samples: 1,
        selfCohesion: null,
        leaveOneOutSimilarity: [], // <2 Samples ⇒ leer, nicht geraten
        sampleOrigins: [
          { recordedAt: 1720000000000, session: null, device: null, durationSeconds: 1.5, rms: 0.05 },
        ],
      }),
    );
    expect(html).toContain(SPEAKER_TEXTS.fitUnknown);
    expect(html).toContain(SPEAKER_TEXTS.deleteRecordingLastHint);
    expect(html).toContain('disabled=""');
  });

  it('der Löschknopf ist ein ICON mit festem Fußabdruck — das Wort steht im aria-label (Querscroll-Fix §1.4/1)', () => {
    const html = renderRecordings(profileDiag());
    // Kein Textknopf mehr (`flex:none` + ~118px Label sprengten die ~63px-Spalte)…
    expect(html).toContain('class="settings__recordingdelete"');
    expect(html).not.toContain(`>${SPEAKER_TEXTS.deleteRecording}<`);
    // …sondern ein Glyph, dessen Wort im aria-label/title weiterlebt.
    expect(html).toContain('glyph--bin');
    expect(html).toContain(SPEAKER_TEXTS.deleteRecordingAria(1));
    expect(html).toContain(`title="${SPEAKER_TEXTS.deleteRecording}"`);
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

// ─────────────────────────────────────────────────────────────────────────────
//  §3.3/4 — die zwei kleinen Reparaturen, die sonst niemand bemerkt hätte
//
//  Beide sind CSS/Markup-Hygiene und darum hier als ausführbarer Vertrag statt
//  als Kommentar-Versprechen: eine Regel, die kein Element mehr trägt, hält der
//  nächste Leser für Absicht — und eine Datumszeile ohne Umbruchschutz hat in
//  einer schmalen Spalte acht Zeilen statt zwei (Befund §1.4/2).
// ─────────────────────────────────────────────────────────────────────────────

describe('Aufräumen (§3.3/4): kein totes CSS, kein ungebremstes Datum', () => {
  const source = readFileSync('src/components/SpeakerSection.tsx', 'utf8');
  const css = readFileSync('src/styles/themes.css', 'utf8');

  it('die zwei Klassen ohne jede CSS-Regel sind weg — im Markup wie im Stylesheet', () => {
    for (const dead of ['settings__enrollsession', 'settings__recordingssummary']) {
      expect(source, dead).not.toContain(dead);
      expect(css, dead).not.toContain(dead);
    }
  });

  it('die Datumszeile bricht um, statt ihre Spalte zu sprengen (§1.4/2)', () => {
    const start = css.indexOf('.settings__speakerdate {');
    expect(start).toBeGreaterThanOrEqual(0);
    // Der Name daneben (`.settings__speakername`) hatte den Schutz längst — die
    // dreiteilig konkatenierte Datumszeile bekommt ihn jetzt auch.
    expect(css.slice(start, css.indexOf('}', start))).toMatch(/overflow-wrap:\s*anywhere/);
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
