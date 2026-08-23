/** @vitest-environment jsdom */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  ENROLL_MIN_DURATION_MS,
  ENROLL_MIN_PEAK_LEVEL,
  ENROLL_MIN_VOICED_MS,
  ENROLL_SAMPLE_COUNT,
  ENROLL_TOTAL_SAMPLES,
  EnrollDialog,
  SAMPLE_DONE_ADVANCE_MS,
  SPEAKER_TEXTS,
  checkEnrollSample,
  enrollRailDots,
  enrollStartIndex,
  needsCancelConfirm,
  sameSpeakerName,
  sampleProgress,
  samplesForNameIn,
} from '../components/SpeakerSection';
import { VoiceRecorderError } from '../audio/recorder';
import { wavBlobFromPcm } from '../audio/wav';
import type { EnrollCapture } from '../audio/enrollCapture';
import type { SpeakerSummary } from '../api/speakers';

// Der Anlern-Dialog mit ECHT gemountetem State (jsdom): Aufnahme + Enroll + Rollback
// sind injizierte Props → wir fahren den ganzen MULTI-SITZUNGS-Flow (3 Sitzungen × 3
// Sätze → EIN Profil, 9 Aufnahmen) ohne Mikrofon/AudioContext und beweisen: (1) jede
// Sitzung endet ehrlich nach drei Sätzen — ERST Satz 9 (Sitzung 3) meldet den vollen
// Erfolg, (2) ein Abbruch in einer FRISCHEN Sitzung verwirft das Teil-Profil, aber ein
// Abbruch in einer ANGEHÄNGTEN Sitzung löscht NICHTS (frühere Sitzungen bleiben
// unangetastet), (3) „Nochmal von vorn" springt bei einer angehängten Sitzung NIE auf
// Satz 1 zurück (das würde `sample=1` senden und das ganze Profil ersetzen), (4) Fehler
// mitten in der Kette ⇒ ehrliche Zeile statt Fake-Erfolg, (5) die neun Sätze kommen
// gruppenweise aus dem i18n-Katalog (Gruppe 1/2/3 je nach Sitzung).

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

describe('EnrollDialog — geführter 3-Sitzungen-Anlern-Flow (9 Sätze)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  /** Mountet frisch — unmountet zuerst einen evtl. noch laufenden Root (Sitzungswechsel im Test). */
  const mount = async (el: React.ReactElement): Promise<void> => {
    if (root) {
      const prev = root;
      await act(async () => prev.unmount());
      root = null;
    }
    root = createRoot(container);
    await act(async () => {
      root!.render(el);
    });
  };
  const findButton = (text: string): HTMLButtonElement => {
    const btns = Array.from(container.querySelectorAll('button')) as HTMLButtonElement[];
    const btn = btns.find((b) => (b.textContent ?? '').includes(text));
    if (!btn) throw new Error(`Kein Button „${text}" — vorhanden: ${btns.map((b) => b.textContent).join(' | ')}`);
    return btn;
  };
  const click = async (text: string): Promise<void> => {
    const btn = findButton(text);
    await act(async () => {
      btn.click();
      await new Promise((r) => setTimeout(r, 0)); // die async-Handler-Kette (start/stop/enroll) flushen
    });
  };

  /** Eine wiederverwendbare Fake-Aufnahme, die pro stop() ein WAV liefert. */
  const makeCapture = (): EnrollCapture => ({
    start: vi.fn().mockResolvedValue(undefined),
    stop: vi.fn().mockResolvedValue(wavBlobFromPcm(new Float32Array(2000).fill(0.1), 16000)),
    cancel: vi.fn(),
  });

  /**
   * Bild ① (Name) verlassen — seit dem Redesign 2026-08-15 §3.3 ist der Name ein
   * EIGENES Bild („EIN Schritt pro Bild"); `lockName` überspringt es ganz.
   */
  const passNameStep = async (): Promise<void> => {
    await click(SPEAKER_TEXTS.nameNext);
  };

  /** Nimmt EINEN Satz auf (Aufnahme starten → Satz fertig) — der Rhythmus von Bild ②. */
  const recordOneSentence = async (localIndex: number): Promise<void> => {
    await click(`${sampleProgress(localIndex)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish);
  };

  /**
   * Bild ③ („✓ gespeichert") rückt nach {@link SAMPLE_DONE_ADVANCE_MS} SELBST zu
   * ② weiter — früher kostete das einen Extra-Klick. Der Test wartet die Ruhe
   * einfach ab (echte Timer, wie der Rest dieser Datei).
   */
  const awaitAutoAdvance = async (): Promise<void> => {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
  };

  /** Ein Satz inklusive des selbsttätigen Weiterrückens von ③ zurück nach ②. */
  const recordAndAdvance = async (localIndex: number): Promise<void> => {
    await recordOneSentence(localIndex);
    await awaitAutoAdvance();
  };

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.restoreAllMocks();
  });

  it('Happy-Path: 3 Sitzungen × 3 Sätze = 9 Aufnahmen, Sitzungsende nach je 3, Profil ERST nach Satz 9 komplett', async () => {
    const capture = makeCapture();
    const enroll = vi
      .fn()
      .mockImplementation((name: string, _wav: Blob, sample?: number) =>
        Promise.resolve({ name, enrolledAt: 42, samples: sample }),
      );
    const removeProfile = vi.fn();
    const onEnrolled = vi.fn();

    // ── Sitzung 1 (frischer Start, samplesForName-Default ⇒ 0) ──
    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={onEnrolled}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    // ① Name: Feld + Regelhinweis + Consent — und AUSDRÜCKLICH noch kein Satz.
    expect(container.textContent).toContain('dein Profil gehört dir');
    for (const line of SPEAKER_TEXTS.sentences) expect(container.textContent).not.toContain(line);
    await passNameStep();

    // ② Sprechen: GENAU EIN Satz steht da — nicht drei in einer <ol> (§3.3).
    expect(container.textContent).toContain(SPEAKER_TEXTS.sentences[0]);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.sentences[1]);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.sentences[3]);
    // Die Schiene sagt beides: welche Sitzung UND welcher Satz.
    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(1));
    expect(container.textContent).toContain(sampleProgress(1));

    await recordOneSentence(1);
    expect(container.textContent).toContain(`${sampleProgress(1)} ${SPEAKER_TEXTS.sampleSaved}`);
    expect(container.textContent).toContain(SPEAKER_TEXTS.partialHint);
    expect(onEnrolled).not.toHaveBeenCalled();
    // ③ rückt SELBST weiter — danach steht der ZWEITE Satz da.
    await awaitAutoAdvance();
    expect(container.textContent).toContain(SPEAKER_TEXTS.sentences[1]);

    await recordOneSentence(2);
    expect(container.textContent).toContain(`${sampleProgress(2)} ${SPEAKER_TEXTS.sampleSaved}`);
    expect(onEnrolled).not.toHaveBeenCalled();
    await awaitAutoAdvance();

    await recordOneSentence(3);

    // Sitzung 1 ist fertig — das GANZE Profil ist es ausdrücklich noch NICHT.
    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(1));
    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionDoneHint(1));
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.done);
    expect(onEnrolled).not.toHaveBeenCalled();
    expect(removeProfile).not.toHaveBeenCalled();

    // ── Sitzung 2 (hängt an, samplesForName ⇒ 3 → Startindex 4) ──
    await mount(
      <EnrollDialog
        samplesForName={() => 3}
        onClose={() => {}}
        onEnrolled={onEnrolled}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    // „Weiter anlernen" hat keinen Namen mehr zu klären ⇒ Bild ① entfällt hier
    // NICHT (lockName ist in diesem Fall nicht gesetzt) — der Name-Schritt kommt,
    // aber der Satz gehört schon zu Sitzung 2.
    await passNameStep();
    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(2));
    expect(container.textContent).toContain(SPEAKER_TEXTS.sentences[3]);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.sentences[0]);

    await recordAndAdvance(1); // lokal wieder „Satz 1 von 3" — absolut ist es Satz 4
    await recordAndAdvance(2);
    await recordOneSentence(3);

    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(2));
    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionDoneHint(2));
    expect(onEnrolled).not.toHaveBeenCalled();
    expect(removeProfile).not.toHaveBeenCalled();

    // ── Sitzung 3 (hängt an, samplesForName ⇒ 6 → Startindex 7) → Profil komplett ──
    await mount(
      <EnrollDialog
        samplesForName={() => 6}
        onClose={() => {}}
        onEnrolled={onEnrolled}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(3));
    expect(container.textContent).toContain(SPEAKER_TEXTS.sentences[6]);

    await recordAndAdvance(1);
    await recordAndAdvance(2);
    await recordOneSentence(3);

    // JETZT komplett: enroll lief 9× mit sample=1..9 (Satz 1 ersetzt, Rest hängt an).
    expect(enroll).toHaveBeenCalledTimes(9);
    expect(enroll.mock.calls.map((c) => c[2])).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
    for (const call of enroll.mock.calls) {
      expect(call[0]).toBe('gast'); // Default-Name = VITE_SPEAKER_ID (Guest-Fallback)
      expect((call[1] as Blob).type).toBe('audio/wav'); // WAV, nicht webm
    }
    expect(onEnrolled).toHaveBeenCalledTimes(1);
    expect(onEnrolled).toHaveBeenCalledWith({ name: 'gast', enrolledAt: 42, samples: 9 });
    expect(container.textContent).toContain(SPEAKER_TEXTS.done); // „Profil komplett …"
    expect(removeProfile).not.toHaveBeenCalled(); // kein Rollback im Happy-Path
  });

  it('Abbruch nach Satz 1 EINER FRISCHEN Sitzung ⇒ Teil-Profil wird verworfen (removeProfile) + onAborted + onClose', async () => {
    const capture = makeCapture();
    const enroll = vi.fn().mockResolvedValue({ name: 'person-a', enrolledAt: 42, samples: 1 });
    const removeProfile = vi.fn().mockResolvedValue(undefined);
    const onAborted = vi.fn();
    const onSessionIncomplete = vi.fn();
    const onClose = vi.fn();
    const onEnrolled = vi.fn();

    await mount(
      <EnrollDialog
        onClose={onClose}
        onEnrolled={onEnrolled}
        onAborted={onAborted}
        onSessionIncomplete={onSessionIncomplete}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await recordAndAdvance(1); // Satz 1 liegt jetzt auf dem Server, ③ ist durch

    // §3.3/1: mitten drin wird NACHGEFRAGT — der erste Klick löscht nichts.
    await click(SPEAKER_TEXTS.cancel);
    expect(container.textContent).toContain(SPEAKER_TEXTS.cancelConfirmTitle);
    expect(removeProfile).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    await click(SPEAKER_TEXTS.cancelConfirmYes);

    expect(removeProfile).toHaveBeenCalledWith('gast'); // ehrlich: unfertiges Profil weg
    expect(onAborted).toHaveBeenCalledTimes(1);
    expect(onSessionIncomplete).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onEnrolled).not.toHaveBeenCalled(); // nie ein Fake-Erfolg
  });

  it('Abbruch VOR dem ersten gespeicherten Satz ⇒ nur schließen, KEIN Lösch-Call', async () => {
    const capture = makeCapture();
    const removeProfile = vi.fn();
    const onAborted = vi.fn();
    const onClose = vi.fn();

    await mount(
      <EnrollDialog
        onClose={onClose}
        onEnrolled={() => {}}
        onAborted={onAborted}
        enroll={vi.fn()}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    // Laufende Aufnahme ⇒ ebenfalls Nachfrage (auch ohne gespeicherten Satz: das
    // offene Mikro ist selbst ein Zustand, den man nicht versehentlich wegwirft).
    await click(SPEAKER_TEXTS.cancel);
    expect(container.textContent).toContain(SPEAKER_TEXTS.cancelConfirmTitle);
    await click(SPEAKER_TEXTS.cancelConfirmYes);

    expect(capture.cancel).toHaveBeenCalled(); // Mikro freigegeben
    expect(removeProfile).not.toHaveBeenCalled();
    expect(onAborted).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Abbruch mitten in einer ANGEHÄNGTEN Sitzung ⇒ NICHTS wird gelöscht, nur onSessionIncomplete (frühere Sitzungen bleiben unangetastet)', async () => {
    const capture = makeCapture();
    const enroll = vi.fn().mockResolvedValue({ name: 'gast', enrolledAt: 42, samples: 4 });
    const removeProfile = vi.fn();
    const onAborted = vi.fn();
    const onSessionIncomplete = vi.fn();
    const onClose = vi.fn();

    await mount(
      <EnrollDialog
        samplesForName={() => 3} // Sitzung 2, hängt an Satz 4 an
        onClose={onClose}
        onEnrolled={() => {}}
        onAborted={onAborted}
        onSessionIncomplete={onSessionIncomplete}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await recordAndAdvance(1); // absolut Satz 4 — liegt jetzt auf dem Server
    await click(SPEAKER_TEXTS.cancel);
    // Die Nachfrage sagt hier etwas ANDERES: es geht nichts verloren.
    expect(container.textContent).toContain(SPEAKER_TEXTS.cancelConfirmAppend);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.cancelConfirmFresh);
    await click(SPEAKER_TEXTS.cancelConfirmYes);

    expect(enroll).toHaveBeenCalledWith('gast', expect.anything(), 4); // Default-Name = VITE_SPEAKER_ID
    expect(removeProfile).not.toHaveBeenCalled(); // NIE löschen bei einer angehängten Sitzung
    expect(onAborted).not.toHaveBeenCalled();
    expect(onSessionIncomplete).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Fehler bei Satz 2 einer FRISCHEN Sitzung ⇒ ehrliche Zeile + „von vorn"-Hinweis, Retry startet bei Satz 1, kein Fake-Erfolg', async () => {
    const capture = makeCapture();
    const enroll = vi
      .fn()
      .mockResolvedValueOnce({ name: 'person-a', enrolledAt: 42, samples: 1 })
      .mockRejectedValueOnce(new Error('Die Stimmerkennung ist gerade nicht erreichbar. Später erneut versuchen.'));
    const onEnrolled = vi.fn();

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={onEnrolled}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await recordAndAdvance(1); // Satz 1 ok
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish); // Satz 2 scheitert

    expect(container.textContent).toContain('nicht erreichbar'); // die ehrliche Fehler-Zeile
    expect(container.textContent).toContain(SPEAKER_TEXTS.errorPartialHint); // „noch nicht komplett"
    expect(onEnrolled).not.toHaveBeenCalled();

    // Retry ⇒ zurück auf Bild ① (frischer Start: das Namensfeld wird wieder frei),
    // der Flow beginnt bei Satz 1 und ERSETZT das Teil-Profil.
    await click(SPEAKER_TEXTS.retry);
    expect(container.textContent).toContain(SPEAKER_TEXTS.dialogIntro);
    await passNameStep();
    expect(findButton(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`)).toBeTruthy();
  });

  it('Fehler in einer ANGEHÄNGTEN Sitzung ⇒ Retry springt NICHT auf Satz 1 zurück, sondern auf den nächsten offenen Index (Profil bleibt sicher)', async () => {
    const capture = makeCapture();
    const enroll = vi
      .fn()
      .mockResolvedValueOnce({ name: 'gast', enrolledAt: 42, samples: 4 }) // Satz 4 (Sitzung 2, lokal 1) ok
      .mockRejectedValueOnce(new Error('Die Stimmerkennung ist gerade nicht erreichbar.')) // Satz 5 scheitert
      .mockResolvedValueOnce({ name: 'gast', enrolledAt: 42, samples: 5 }); // Satz 5 im Retry ok
    const removeProfile = vi.fn();

    await mount(
      <EnrollDialog
        samplesForName={() => 3}
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await recordAndAdvance(1); // Satz 4 ok
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish); // Satz 5 scheitert

    await click(SPEAKER_TEXTS.retry);
    // Der Name ist eingefroren ⇒ Bild ① entfällt, wir landen direkt auf ②; der
    // Knopf zeigt weiterhin LOKAL Satz 2 (kein Reset auf Satz 1).
    expect(findButton(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`)).toBeTruthy();

    await recordOneSentence(2); // Satz 5 erneut versucht

    expect(enroll).toHaveBeenCalledTimes(3);
    expect(enroll.mock.calls.map((c) => c[2])).toEqual([4, 5, 5]); // NIE 1 — das Profil wird nie ersetzt
    expect(removeProfile).not.toHaveBeenCalled();
  });

  it('kein Mikro (support ok:false) ⇒ ehrliche Meldung, Start gesperrt, kein Fake-Erfolg', async () => {
    const enroll = vi.fn();
    const onEnrolled = vi.fn();

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={onEnrolled}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={() => {
          throw new Error('darf nie erzeugt werden');
        }}
        support={() => ({ ok: false, reason: SPEAKER_TEXTS.insecure })}
        advanceMs={0}
      />,
    );

    expect(container.textContent).toContain(SPEAKER_TEXTS.insecure); // ehrliche Zeile
    // Schon auf Bild ① ist der Weg gesperrt — man wird nicht erst zum Satz geführt.
    expect(findButton(SPEAKER_TEXTS.nameNext).disabled).toBe(true);
    expect(enroll).not.toHaveBeenCalled();
    expect(onEnrolled).not.toHaveBeenCalled();
  });

  it('Aufnahme abgelehnt (start wirft) ⇒ ehrliche Fehlerzeile statt Crash, kein Enroll', async () => {
    const capture: EnrollCapture = {
      start: vi
        .fn()
        .mockRejectedValue(
          new VoiceRecorderError('permission-denied', 'Mikro-Zugriff abgelehnt. Erlaube das Mikrofon.'),
        ),
      stop: vi.fn(),
      cancel: vi.fn(),
    };
    const enroll = vi.fn();
    const onEnrolled = vi.fn();

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={onEnrolled}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);

    expect(container.textContent).toContain('abgelehnt'); // die warme Recorder-Zeile
    expect(enroll).not.toHaveBeenCalled();
    expect(onEnrolled).not.toHaveBeenCalled();
  });


  // ── §3.3/1: Abbruch ist ein ECHTER Abbruch ────────────────────────────────
  //
  // Der Fehler davor: Escape (SettingsPanel.tsx:374) und der Backdrop-Klick
  // (:420-422) schlossen den GANZEN Drawer mitten in der Aufnahme. Es lief nur
  // `captureRef.cancel()` (Mikro frei) — die Rollback-Semantik von `cancel()`
  // (SpeakerSection.tsx) NIE. Ein frisch begonnenes Teil-Profil blieb VERWAIST
  // auf dem Server, ohne ein Wort an den Menschen davor (§1.4/6).

  it('Escape mitten in der AUFNAHME ⇒ Nachfrage; erst „Ja" läuft durch cancel() (Rollback + onAborted)', async () => {
    const capture = makeCapture();
    const enroll = vi.fn().mockResolvedValue({ name: 'gast', enrolledAt: 42, samples: 1 });
    const removeProfile = vi.fn().mockResolvedValue(undefined);
    const onAborted = vi.fn();
    const onClose = vi.fn();

    await mount(
      <EnrollDialog
        onClose={onClose}
        onEnrolled={() => {}}
        onAborted={onAborted}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await recordAndAdvance(1); // ein Satz liegt auf dem Server
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`); // Mikro läuft

    // Escape geht durch die Overlay-Schale — und wird ABGEFANGEN, nicht ausgeführt.
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    expect(container.textContent).toContain(SPEAKER_TEXTS.cancelConfirmTitle);
    expect(container.textContent).toContain(SPEAKER_TEXTS.cancelConfirmFresh);
    expect(removeProfile).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();

    // „Doch weitermachen" räumt die Frage weg, ohne irgendetwas zu verlieren.
    await click(SPEAKER_TEXTS.cancelConfirmNo);
    expect(onClose).not.toHaveBeenCalled();
    expect(removeProfile).not.toHaveBeenCalled();

    // Und erst die Bestätigung läuft durch cancel(): Mikro frei, Teil-Profil weg.
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    await click(SPEAKER_TEXTS.cancelConfirmYes);
    expect(capture.cancel).toHaveBeenCalled();
    expect(removeProfile).toHaveBeenCalledWith('gast');
    expect(onAborted).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Backdrop-Klick während ③ („gespeichert") ⇒ ebenfalls Nachfrage statt stillem Datenverlust', async () => {
    const capture = makeCapture();
    const removeProfile = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();

    await mount(
      <EnrollDialog
        onClose={onClose}
        onEnrolled={() => {}}
        onAborted={() => {}}
        enroll={vi.fn().mockResolvedValue({ name: 'gast', enrolledAt: 42, samples: 1 })}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        // Ohne Weiterrücken bleiben wir auf ③ stehen — genau der Zustand, den
        // §3.3/1 neben `recording` ausdrücklich nennt.
        advanceMs={100000}
      />,
    );

    await passNameStep();
    await recordOneSentence(1);

    const backdrop = container.querySelector('.overlay') as HTMLElement;
    await act(async () => {
      backdrop.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(container.textContent).toContain(SPEAKER_TEXTS.cancelConfirmTitle);
    expect(onClose).not.toHaveBeenCalled();

    await click(SPEAKER_TEXTS.cancelConfirmYes);
    expect(removeProfile).toHaveBeenCalledWith('gast');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Escape VOR jeder Aufnahme schließt sofort — ohne Frage, weil nichts auf dem Spiel steht', async () => {
    const onClose = vi.fn();
    const removeProfile = vi.fn();
    await mount(
      <EnrollDialog
        onClose={onClose}
        onEnrolled={() => {}}
        enroll={vi.fn()}
        removeProfile={removeProfile}
        createCapture={() => makeCapture()}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.cancelConfirmTitle);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(removeProfile).not.toHaveBeenCalled();
  });

  // ── §3.3/2: Client-Mindestprüfung VOR dem Upload ──────────────────────────

  it('zu leise (echter Pegel gemessen) ⇒ NICHT hochgeladen, ehrliche Zeile, derselbe Satz bleibt stehen', async () => {
    // Eine Aufnahme MIT Meter, die durchgehend fast nichts liefert: früher kam
    // das erst als HTTP 422 zurück, nachdem der Mensch den Satz gesprochen hatte.
    const enroll = vi.fn();
    let push: ((level: number) => void) | undefined;
    // Steuerbare Uhr: die Aufnahme soll LANG genug sein, damit wirklich die
    // Lautstärke der Grund ist (und nicht die Dauer — die wird zuerst geprüft).
    let clock = 0;
    vi.spyOn(performance, 'now').mockImplementation(() => clock);
    const capture: EnrollCapture = {
      start: vi.fn().mockImplementation(async () => {
        for (let i = 0; i < 40; i += 1) {
          clock += 100;
          push?.(0.001); // Rauschboden, keine Sprache
        }
      }),
      stop: vi.fn().mockResolvedValue(wavBlobFromPcm(new Float32Array(2000).fill(0.1), 16000)),
      cancel: vi.fn(),
    };

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={(onLevel) => {
          push = onLevel;
          return capture;
        }}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );

    await passNameStep();
    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    clock += 3000; // drei Sekunden lang „gesprochen" — nur eben lautlos
    await click(SPEAKER_TEXTS.finish);

    expect(enroll).not.toHaveBeenCalled(); // kein verschwendeter Upload
    expect(container.textContent).toContain(SPEAKER_TEXTS.checkTooQuiet);
    // Derselbe Satz steht weiterhin da — es wurde nichts verbraucht.
    expect(container.textContent).toContain(SPEAKER_TEXTS.sentences[0]);
    expect(findButton(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`)).toBeTruthy();
  });

  it('OHNE Meter (Fake/kein Web Audio) wird NICHT geraten — es wird normal hochgeladen', async () => {
    // Die ehrliche Hälfte der Prüfung: keine Messung ⇒ kein Urteil. Der 422 des
    // Servers bleibt für diesen Fall der Auffang.
    const enroll = vi.fn().mockResolvedValue({ name: 'gast', enrolledAt: 42, samples: 1 });
    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={() => makeCapture()}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );
    await passNameStep();
    await recordOneSentence(1);
    expect(enroll).toHaveBeenCalledTimes(1);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.checkTooQuiet);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.checkTooShort);
  });

  // ── Die Schale: es IST jetzt ein Dialog (§1.4: es war keiner) ─────────────

  it('der Assistent ist ein echter modaler Dialog — nicht mehr ein <div role="group"> unter der Liste', async () => {
    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={vi.fn()}
        removeProfile={vi.fn()}
        createCapture={() => makeCapture()}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );
    const card = container.querySelector('[role="dialog"]') as HTMLElement;
    expect(card).not.toBeNull();
    expect(card.getAttribute('aria-modal')).toBe('true');
    expect(card.getAttribute('aria-label')).toBe(SPEAKER_TEXTS.dialogTitle);
    expect(card.className).toContain('settings__enroll');
    expect(container.querySelector('[role="group"]')).toBeNull();
  });

  it('die Neun-Punkte-Schiene steht über dem Satz — nicht nur „Satz 2 von 3"', async () => {
    await mount(
      <EnrollDialog
        samplesForName={() => 3} // Sitzung 2 ⇒ drei Punkte sind schon erledigt
        lockName
        defaultName="andi"
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={vi.fn()}
        removeProfile={vi.fn()}
        createCapture={() => makeCapture()}
        support={() => ({ ok: true })}
        advanceMs={0}
      />,
    );
    const dots = Array.from(container.querySelectorAll('.settings__enrolldot'));
    expect(dots).toHaveLength(ENROLL_TOTAL_SAMPLES);
    expect(dots.filter((d) => d.className.includes('--done'))).toHaveLength(3);
    expect(dots.filter((d) => d.className.includes('--current'))).toHaveLength(1);
    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(2));
  });

  it('Startindex folgt dem GETIPPTEN Namen (nicht einem festen Default) — zwei Menschen am selben Browser', async () => {
    // Der reale Fall, den diese Korrektur behebt: Person A UND Person B lernen am selben Browser an,
    // unterschieden nur durchs Namensfeld. Ein Lookup gegen einen festen Default (z. B.
    // SPEAKER_ID/„gast") hätte Person Bs Profil bei jeder Sitzung überschrieben (sample=1).
    const capture = makeCapture();
    const enroll = vi
      .fn()
      .mockImplementation((name: string, _wav: Blob, sample?: number) =>
        Promise.resolve({ name, enrolledAt: 42, samples: sample }),
      );
    const samplesForName = (n: string) => (sameSpeakerName(n, 'person-b') ? 3 : 0);

    const setInputValue = (el: HTMLInputElement, value: string) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
      setter.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
        samplesForName={samplesForName}
      />,
    );

    // Default-Name „gast" ⇒ kein Treffer ⇒ Startindex 1 (frischer Start).
    await passNameStep();
    await recordOneSentence(1);
    expect(enroll).toHaveBeenNthCalledWith(1, 'gast', expect.anything(), 1);

    // ── Neuer Dialog, diesmal wird VOR der ersten Aufnahme „Person B" eingetippt. ──
    const enroll2 = vi
      .fn()
      .mockImplementation((name: string, _wav: Blob, sample?: number) =>
        Promise.resolve({ name, enrolledAt: 42, samples: sample }),
      );
    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll2}
        removeProfile={vi.fn()}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
        samplesForName={samplesForName}
      />,
    );
    await act(async () => {
      setInputValue(container.querySelector<HTMLInputElement>('#enroll-name')!, 'person-b');
    });
    await passNameStep();
    await recordOneSentence(1); // lokal „Satz 1", aber der Name „Person B" hat 3 Sätze ⇒ absolut Satz 4

    expect(enroll2).toHaveBeenNthCalledWith(1, 'person-b', expect.anything(), 4); // hängt an, KEIN sample=1
  });

  it('Namenswechsel VOR der ersten gespeicherten Aufnahme ändert den Startindex — NACH der ersten Aufnahme ist er eingefroren (Namensfeld gesperrt)', async () => {
    const capture = makeCapture();
    const enroll = vi
      .fn()
      .mockImplementation((name: string, _wav: Blob, sample?: number) =>
        Promise.resolve({ name, enrolledAt: 42, samples: sample }),
      );
    // „Mira" hätte (wäre sie NACH dem Einfrieren noch wirksam) Satz 7 ausgelöst — genau das
    // darf nach dem ersten gespeicherten Satz nicht mehr passieren.
    const samplesForName = (n: string) => {
      if (sameSpeakerName(n, 'person-b')) return 3; // Sitzung 2 (Startindex 4)
      if (sameSpeakerName(n, 'mira')) return 6; // Sitzung 3 (Startindex 7)
      return 0;
    };
    const setInputValue = (el: HTMLInputElement, value: string) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
      setter.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
        samplesForName={samplesForName}
      />,
    );

    // „person-b" (klein geschrieben) trifft „Person B" trotzdem — trimmed + case-insensitiv.
    await act(async () => {
      setInputValue(container.querySelector<HTMLInputElement>('#enroll-name')!, '  person-b  ');
    });
    await passNameStep();
    await recordAndAdvance(1);
    expect(enroll).toHaveBeenNthCalledWith(1, 'person-b', expect.anything(), 4); // Startindex 4, KEIN sample=1

    // Satz 2 (absolut 5) scheitert — damit wir über den Fehler-Bildschirm zurück zum Intro
    // kommen und das (jetzt gesperrte) Namensfeld sichtbar wird.
    enroll.mockRejectedValueOnce(new Error('Die Stimmerkennung ist gerade nicht erreichbar.'));
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish);
    await click(SPEAKER_TEXTS.retry);

    // NACH der ersten gespeicherten Aufnahme ist der Name eingefroren — Bild ①
    // entfällt darum ganz. Für die Gegenprobe holen wir es einmal auf den Schirm:
    // das Feld ist da, aber gesperrt (UI-Schutz).
    expect(container.querySelector('#enroll-name')).toBeNull();
    await click(SPEAKER_TEXTS.cancel);
    await click(SPEAKER_TEXTS.cancelConfirmNo);
    const nameInput = container.querySelector<HTMLInputElement>('#enroll-name');
    expect(nameInput).toBeNull();

    // …und der eingefrorene Startindex bleibt: der nächste Satz geht weiterhin an
    // Satz 5 (Fortsetzung der Person-B-Sitzung), NICHT an Satz 7 (was „Mira" als
    // frischer Name ausgelöst hätte). Ohne Namensfeld ist der Wechsel gar nicht
    // mehr möglich — der Schutz ist damit sogar EINE Ebene früher geworden.
    await recordOneSentence(2);

    expect(enroll).toHaveBeenLastCalledWith(expect.any(String), expect.anything(), 5);
  });

  it('lockName ("Weiter anlernen"): Bild ① entfällt — es gibt nichts zu benennen', async () => {
    const capture = makeCapture();
    const enroll = vi.fn().mockResolvedValue({ name: 'andi', enrolledAt: 42, samples: 4 });

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll}
        removeProfile={vi.fn()}
        createCapture={() => capture}
        support={() => ({ ok: true })}
        advanceMs={0}
        defaultName="andi"
        lockName
        samplesForName={() => 3} // Fortsetzungs-Index: 3 Sätze schon da ⇒ Satz 4
      />,
    );


    // …und der Name-Schritt ① entfällt ganz: es gibt nichts mehr zu benennen
    // (§3.3, „nur bei neuem Profil"). Wir stehen direkt auf Bild ②.
    expect(container.querySelector('#enroll-name')).toBeNull();
    expect(container.textContent).toContain(SPEAKER_TEXTS.sentences[3]); // Satz 4 = Sitzung 2

    // Der Fortsetzungs-Index folgt automatisch der bestehenden Startindex-Logik (kein sample=1).
    await recordOneSentence(1);
    expect(enroll).toHaveBeenCalledWith('andi', expect.anything(), 4);
  });
});

describe('enrollStartIndex — Startindex einer neuen Anlern-Sitzung', () => {
  it('0 (kein Profil) ⇒ frischer Start bei Satz 1', () => {
    expect(enrollStartIndex(0)).toBe(1);
  });
  it('1 ⇒ hängt an, nächster offener Index ist Satz 2', () => {
    expect(enrollStartIndex(1)).toBe(2);
  });
  it('3 (Sitzung 1 komplett) ⇒ Sitzung 2 beginnt bei Satz 4', () => {
    expect(enrollStartIndex(3)).toBe(4);
  });
  it('8 (letzter offener Satz) ⇒ Sitzung 3 endet bei Satz 9', () => {
    expect(enrollStartIndex(8)).toBe(9);
  });
  it('9 (Profil bereits voll) ⇒ frischer Start bei Satz 1 (Profil neu beginnen)', () => {
    expect(enrollStartIndex(9)).toBe(1);
  });
});

describe('samplesForNameIn / sameSpeakerName — Namens-Match ist getrimmt + case-insensitiv (Person A + Person B am selben Browser)', () => {
  const speakers: SpeakerSummary[] = [
    { name: 'person-b', enrolledAt: 1, samples: 3 },
    { name: 'person-a', enrolledAt: 2, samples: 9 },
  ];

  it('Name „Person B" bei vorhandenem Profil „Person B" (3 Sätze) ⇒ Startindex 4 (hängt an, KEIN sample=1)', () => {
    expect(samplesForNameIn(speakers, 'person-b')).toBe(3);
    expect(enrollStartIndex(samplesForNameIn(speakers, 'person-b'))).toBe(4);
  });

  it('„person-b" (klein, mit Leerzeichen) trifft „Person B" ebenfalls — Backend-Muster erlaubt beide Schreibweisen', () => {
    expect(samplesForNameIn(speakers, 'person-b')).toBe(3);
    expect(samplesForNameIn(speakers, '  PERSON-B  ')).toBe(3);
    expect(sameSpeakerName('person-b', ' person-b ')).toBe(true);
    expect(sameSpeakerName('person-b', 'PERSON-B')).toBe(true);
  });

  it('unbekannter Name ⇒ 0 Sätze ⇒ Startindex 1 (frischer Start)', () => {
    expect(samplesForNameIn(speakers, 'jemand-fremdes')).toBe(0);
    expect(enrollStartIndex(samplesForNameIn(speakers, 'jemand-fremdes'))).toBe(1);
    expect(sameSpeakerName('person-b', 'Cind')).toBe(false);
  });

  it('leere/fehlende Liste ⇒ immer 0 (kein Treffer möglich, kein Crash)', () => {
    expect(samplesForNameIn(null, 'person-b')).toBe(0);
    expect(samplesForNameIn(undefined, 'person-b')).toBe(0);
    expect(samplesForNameIn([], 'person-b')).toBe(0);
  });

  it('„person-a" (9 Sätze, Profil voll) ⇒ Startindex 1 — ein neuer Name startet nie mitten in Person As Profil', () => {
    expect(samplesForNameIn(speakers, 'person-a')).toBe(9);
    expect(enrollStartIndex(samplesForNameIn(speakers, 'person-a'))).toBe(1);
  });
});

describe('EnrollDialog — neun Sätze aus dem Katalog, drei je Sitzung', () => {
  it('hat genau neun Sätze, Gruppe 1 ist byte-gleich zum alten Bestand', () => {
    expect(SPEAKER_TEXTS.sentences).toHaveLength(ENROLL_SAMPLE_COUNT * 3);
    expect(SPEAKER_TEXTS.sentences[0]).toBe(
      'Hallo Hoshi, ich bin’s — ich möchte, dass du meine Stimme ab jetzt sicher wiedererkennst.',
    );
    expect(SPEAKER_TEXTS.sentences[1]).toBe(
      'Heute war ein ganz normaler Tag, und ich erzähle dir gerade in aller Ruhe ein bisschen davon.',
    );
    expect(SPEAKER_TEXTS.sentences[2]).toBe(
      'Wenn später etwas Wichtiges ansteht, dann sag mir bitte rechtzeitig und freundlich Bescheid.',
    );
  });
});

describe('checkEnrollSample — die Client-Mindestprüfung (§3.3/2), rein und ohne i18n', () => {
  const ok = {
    durationMs: 4000,
    levelReadings: 60,
    peak: ENROLL_MIN_PEAK_LEVEL * 4,
    voicedMs: ENROLL_MIN_VOICED_MS * 3,
  };

  it('eine normale Aufnahme geht durch', () => {
    expect(checkEnrollSample(ok)).toEqual({ ok: true });
  });

  it('OHNE Messung (kein Meter) wird NIE geurteilt — Hoshi erfindet keine Belege', () => {
    // Der wichtigste Fall: ein Browser ohne Web Audio, jsdom, eine Test-Attrappe.
    // Kein Pegel gesehen ⇒ kein Einspruch; der 422 des Servers bleibt der Auffang.
    expect(checkEnrollSample({ durationMs: 0, levelReadings: 0, peak: 0, voicedMs: 0 })).toEqual({
      ok: true,
    });
  });

  it('zu kurz ⇒ „too-short" (der Satz hat ~90–100 Zeichen, das geht nicht in einer Sekunde)', () => {
    expect(checkEnrollSample({ ...ok, durationMs: ENROLL_MIN_DURATION_MS - 1 })).toEqual({
      ok: false,
      flaw: 'too-short',
    });
  });

  it('zu leise ⇒ „too-quiet" (Mikro stumm/zu weit weg)', () => {
    expect(checkEnrollSample({ ...ok, peak: ENROLL_MIN_PEAK_LEVEL / 2 })).toEqual({
      ok: false,
      flaw: 'too-quiet',
    });
  });

  it('lange offen, aber kaum gesprochen ⇒ „too-short"', () => {
    expect(checkEnrollSample({ ...ok, voicedMs: ENROLL_MIN_VOICED_MS - 1 })).toEqual({
      ok: false,
      flaw: 'too-short',
    });
  });

  it('die Schwellen bleiben unter dem, was der Server ablehnt — die Prüfung ist ein Sieb, kein Richter', () => {
    expect(ENROLL_MIN_DURATION_MS).toBeLessThan(3000);
    expect(ENROLL_MIN_PEAK_LEVEL).toBeGreaterThan(0);
    expect(ENROLL_MIN_VOICED_MS).toBeLessThan(ENROLL_MIN_DURATION_MS);
  });
});

describe('needsCancelConfirm — wann ein Schließen nachfragen MUSS (§3.3/1)', () => {
  it('während der Aufnahme und auf „gespeichert" — die beiden Fälle des Designs', () => {
    expect(needsCancelConfirm('recording', 0)).toBe(true);
    expect(needsCancelConfirm('sample-done', 1)).toBe(true);
  });

  it('…und einen Schritt später ebenfalls, solange Sätze dieser Sitzung auf dem Server liegen', () => {
    // Nach dem selbsttätigen Weiterrücken steht man wieder auf ② — ein Schließen
    // dort verlöre genau dasselbe.
    expect(needsCancelConfirm('speak', 1)).toBe(true);
    expect(needsCancelConfirm('name', 2)).toBe(true);
  });

  it('nichts gespeichert ⇒ keine Frage (Fragen, die nichts schützen, sind nur Reibung)', () => {
    expect(needsCancelConfirm('speak', 0)).toBe(false);
    expect(needsCancelConfirm('name', 0)).toBe(false);
  });

  it('Endzustände fragen nie — dort ist nichts mehr in der Schwebe', () => {
    for (const step of ['session-done', 'done', 'error', 'recordings'] as const) {
      expect(needsCancelConfirm(step, 3), step).toBe(false);
    }
  });
});

describe('enrollRailDots — die Neun-Punkte-Schiene zeigt das GANZE Profil', () => {
  it('immer neun Punkte (das Backend-Maximum)', () => {
    expect(enrollRailDots(1)).toHaveLength(ENROLL_TOTAL_SAMPLES);
  });

  it('Satz 1 einer frischen Sitzung: nichts erledigt, der erste ist dran', () => {
    expect(enrollRailDots(1)).toEqual([
      'current', 'open', 'open', 'open', 'open', 'open', 'open', 'open', 'open',
    ]);
  });

  it('Satz 4 (Sitzung 2): die drei Sätze von Sitzung 1 stehen als erledigt da', () => {
    expect(enrollRailDots(4)).toEqual([
      'done', 'done', 'done', 'current', 'open', 'open', 'open', 'open', 'open',
    ]);
  });

  it('Satz 9: acht erledigt, der letzte ist dran', () => {
    const dots = enrollRailDots(9);
    expect(dots.filter((d) => d === 'done')).toHaveLength(8);
    expect(dots[8]).toBe('current');
  });
});
