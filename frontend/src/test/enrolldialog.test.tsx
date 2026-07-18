/** @vitest-environment jsdom */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  ENROLL_SAMPLE_COUNT,
  ENROLL_SENTENCES,
  EnrollDialog,
  SPEAKER_TEXTS,
  sampleProgress,
} from '../components/SpeakerSection';
import { VoiceRecorderError } from '../audio/recorder';
import { wavBlobFromPcm } from '../audio/wav';
import type { EnrollCapture } from '../audio/enrollCapture';

// Der Anlern-Dialog mit ECHT gemountetem State (jsdom): Aufnahme + Enroll + Rollback
// sind injizierte Props → wir fahren den ganzen MULTI-SAMPLE-Flow (3 Sätze → EIN
// Profil) ohne Mikrofon/AudioContext und beweisen: (1) drei Aufnahmen gehen als
// sample=1..3 ans Enroll und ERST Satz 3 meldet Erfolg, (2) Abbruch ist ehrlich
// (Teil-Profil wird verworfen), (3) Fehler mitten in der Kette ⇒ ehrliche Zeile
// statt Fake-Erfolg, Neustart von vorn.

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

describe('EnrollDialog — geführter 3-Satz-Anlern-Flow (Multi-Sample)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (el: React.ReactElement): Promise<void> => {
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

  it('Happy-Path: 3 Aufnahmen → enroll(sample=1..3), Zwischenstand ehrlich, Erfolg ERST nach Satz 3', async () => {
    const capture = makeCapture();
    const enroll = vi
      .fn()
      .mockImplementation((name: string, _wav: Blob, sample?: number) =>
        Promise.resolve({ name, enrolledAt: 42, samples: sample }),
      );
    const removeProfile = vi.fn();
    const onEnrolled = vi.fn();

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={onEnrolled}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
      />,
    );

    // Intro zeigt alle 3 Nachsprech-Sätze + Consent.
    for (const line of ENROLL_SENTENCES) expect(container.textContent).toContain(line);
    expect(container.textContent).toContain('dein Profil gehört dir');

    // ── Satz 1 ──
    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    expect(container.textContent).toContain(SPEAKER_TEXTS.recordingHint);
    expect(container.textContent).toContain(ENROLL_SENTENCES[0]); // nur der aktuelle Satz
    expect(container.textContent).not.toContain(ENROLL_SENTENCES[1]);
    await click(SPEAKER_TEXTS.finish);

    // Zwischenstand: gespeichert, aber EHRLICH „noch nicht fertig" — kein Erfolg.
    expect(container.textContent).toContain(`${sampleProgress(1)} ${SPEAKER_TEXTS.sampleSaved}`);
    expect(container.textContent).toContain(SPEAKER_TEXTS.partialHint);
    expect(onEnrolled).not.toHaveBeenCalled();

    // ── Satz 2 ──
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    expect(container.textContent).toContain(ENROLL_SENTENCES[1]);
    await click(SPEAKER_TEXTS.finish);
    expect(container.textContent).toContain(`${sampleProgress(2)} ${SPEAKER_TEXTS.sampleSaved}`);
    expect(onEnrolled).not.toHaveBeenCalled();

    // ── Satz 3 ──
    await click(`${sampleProgress(3)} ${SPEAKER_TEXTS.recordSample}`);
    expect(container.textContent).toContain(ENROLL_SENTENCES[2]);
    await click(SPEAKER_TEXTS.finish);

    // JETZT komplett: enroll lief 3× mit sample=1,2,3 (Satz 1 ersetzt, 2+3 hängen an).
    expect(enroll).toHaveBeenCalledTimes(ENROLL_SAMPLE_COUNT);
    expect(enroll.mock.calls.map((c) => c[2])).toEqual([1, 2, 3]);
    for (const call of enroll.mock.calls) {
      expect(call[0]).toBe('gast'); // Default-Name = VITE_SPEAKER_ID (Guest-Fallback)
      expect((call[1] as Blob).type).toBe('audio/wav'); // WAV, nicht webm
    }
    expect(onEnrolled).toHaveBeenCalledTimes(1);
    expect(onEnrolled).toHaveBeenCalledWith({ name: 'gast', enrolledAt: 42, samples: 3 });
    expect(container.textContent).toContain(SPEAKER_TEXTS.done); // „Profil komplett …"
    expect(removeProfile).not.toHaveBeenCalled(); // kein Rollback im Happy-Path
  });

  it('Abbruch nach Satz 1 ⇒ Teil-Profil wird verworfen (removeProfile) + onAborted + onClose', async () => {
    const capture = makeCapture();
    const enroll = vi.fn().mockResolvedValue({ name: 'andi', enrolledAt: 42, samples: 1 });
    const removeProfile = vi.fn().mockResolvedValue(undefined);
    const onAborted = vi.fn();
    const onClose = vi.fn();
    const onEnrolled = vi.fn();

    await mount(
      <EnrollDialog
        onClose={onClose}
        onEnrolled={onEnrolled}
        onAborted={onAborted}
        enroll={enroll}
        removeProfile={removeProfile}
        createCapture={() => capture}
        support={() => ({ ok: true })}
      />,
    );

    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish); // Satz 1 liegt jetzt auf dem Server
    await click(SPEAKER_TEXTS.cancel);

    expect(removeProfile).toHaveBeenCalledWith('gast'); // ehrlich: unfertiges Profil weg
    expect(onAborted).toHaveBeenCalledTimes(1);
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
      />,
    );

    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.cancel); // mitten in Aufnahme 1 — nichts gespeichert

    expect(capture.cancel).toHaveBeenCalled(); // Mikro freigegeben
    expect(removeProfile).not.toHaveBeenCalled();
    expect(onAborted).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Fehler bei Satz 2 ⇒ ehrliche Zeile + „von vorn"-Hinweis, Retry startet bei Satz 1, kein Fake-Erfolg', async () => {
    const capture = makeCapture();
    const enroll = vi
      .fn()
      .mockResolvedValueOnce({ name: 'andi', enrolledAt: 42, samples: 1 })
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
      />,
    );

    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish); // Satz 1 ok
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish); // Satz 2 scheitert

    expect(container.textContent).toContain('nicht erreichbar'); // die ehrliche Fehler-Zeile
    expect(container.textContent).toContain(SPEAKER_TEXTS.errorPartialHint); // „noch nicht komplett"
    expect(onEnrolled).not.toHaveBeenCalled();

    // Retry ⇒ zurück zum Intro, der Flow beginnt bei Satz 1 (ersetzt das Teil-Profil).
    await click(SPEAKER_TEXTS.retry);
    expect(container.textContent).toContain(SPEAKER_TEXTS.dialogIntro);
    expect(findButton(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`)).toBeTruthy();
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
      />,
    );

    expect(container.textContent).toContain(SPEAKER_TEXTS.insecure); // ehrliche Zeile
    expect(findButton(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`).disabled).toBe(true);
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
      />,
    );

    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);

    expect(container.textContent).toContain('abgelehnt'); // die warme Recorder-Zeile
    expect(enroll).not.toHaveBeenCalled();
    expect(onEnrolled).not.toHaveBeenCalled();
  });
});
