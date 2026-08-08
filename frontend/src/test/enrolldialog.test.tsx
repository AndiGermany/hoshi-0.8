/** @vitest-environment jsdom */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  ENROLL_SAMPLE_COUNT,
  EnrollDialog,
  SPEAKER_TEXTS,
  enrollStartIndex,
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

  /** Nimmt EINEN Satz auf (Aufnahme starten → Satz fertig) — der 3-Klick-Rhythmus jeder Sitzung. */
  const recordOneSentence = async (localIndex: number): Promise<void> => {
    await click(`${sampleProgress(localIndex)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish);
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
      />,
    );

    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(1));
    // Intro zeigt NUR Gruppe 1 (Sätze 0–2), nicht die Sätze der anderen Sitzungen.
    for (const line of SPEAKER_TEXTS.sentences.slice(0, 3)) expect(container.textContent).toContain(line);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.sentences[3]);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.sentences[6]);
    expect(container.textContent).toContain('dein Profil gehört dir');

    await recordOneSentence(1);
    expect(container.textContent).toContain(`${sampleProgress(1)} ${SPEAKER_TEXTS.sampleSaved}`);
    expect(container.textContent).toContain(SPEAKER_TEXTS.partialHint);
    expect(onEnrolled).not.toHaveBeenCalled();

    await recordOneSentence(2);
    expect(container.textContent).toContain(`${sampleProgress(2)} ${SPEAKER_TEXTS.sampleSaved}`);
    expect(onEnrolled).not.toHaveBeenCalled();

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
      />,
    );

    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(2));
    for (const line of SPEAKER_TEXTS.sentences.slice(3, 6)) expect(container.textContent).toContain(line);
    expect(container.textContent).not.toContain(SPEAKER_TEXTS.sentences[0]);

    await recordOneSentence(1); // lokal wieder „Satz 1 von 3" — absolut ist es Satz 4
    await recordOneSentence(2);
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
      />,
    );

    expect(container.textContent).toContain(SPEAKER_TEXTS.sessionLabel(3));
    for (const line of SPEAKER_TEXTS.sentences.slice(6, 9)) expect(container.textContent).toContain(line);

    await recordOneSentence(1);
    await recordOneSentence(2);
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
      />,
    );

    await recordOneSentence(1); // Satz 1 liegt jetzt auf dem Server
    await click(SPEAKER_TEXTS.cancel);

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
      />,
    );

    await click(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.cancel); // mitten in Aufnahme 1 — nichts gespeichert

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
      />,
    );

    await recordOneSentence(1); // absolut Satz 4 — liegt jetzt auf dem Server
    await click(SPEAKER_TEXTS.cancel);

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
      />,
    );

    await recordOneSentence(1); // Satz 1 ok
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish); // Satz 2 scheitert

    expect(container.textContent).toContain('nicht erreichbar'); // die ehrliche Fehler-Zeile
    expect(container.textContent).toContain(SPEAKER_TEXTS.errorPartialHint); // „noch nicht komplett"
    expect(onEnrolled).not.toHaveBeenCalled();

    // Retry ⇒ zurück zum Intro, der Flow beginnt bei Satz 1 (ersetzt das Teil-Profil — frischer Start).
    await click(SPEAKER_TEXTS.retry);
    expect(container.textContent).toContain(SPEAKER_TEXTS.dialogIntro);
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
      />,
    );

    await recordOneSentence(1); // Satz 4 ok
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish); // Satz 5 scheitert

    await click(SPEAKER_TEXTS.retry);
    // Zurück im Intro — der Knopf zeigt weiterhin LOKAL Satz 2 (kein Reset auf Satz 1).
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
        samplesForName={samplesForName}
      />,
    );

    // Default-Name „gast" ⇒ kein Treffer ⇒ Startindex 1 (frischer Start).
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
        samplesForName={samplesForName}
      />,
    );
    await act(async () => {
      setInputValue(container.querySelector<HTMLInputElement>('#enroll-name')!, 'person-b');
    });
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
        samplesForName={samplesForName}
      />,
    );

    // „person-b" (klein geschrieben) trifft „Person B" trotzdem — trimmed + case-insensitiv.
    await act(async () => {
      setInputValue(container.querySelector<HTMLInputElement>('#enroll-name')!, '  person-b  ');
    });
    await recordOneSentence(1);
    expect(enroll).toHaveBeenNthCalledWith(1, 'person-b', expect.anything(), 4); // Startindex 4, KEIN sample=1

    // Satz 2 (absolut 5) scheitert — damit wir über den Fehler-Bildschirm zurück zum Intro
    // kommen und das (jetzt gesperrte) Namensfeld sichtbar wird.
    enroll.mockRejectedValueOnce(new Error('Die Stimmerkennung ist gerade nicht erreichbar.'));
    await click(`${sampleProgress(2)} ${SPEAKER_TEXTS.recordSample}`);
    await click(SPEAKER_TEXTS.finish);
    await click(SPEAKER_TEXTS.retry);

    // NACH der ersten gespeicherten Aufnahme: das Namensfeld ist gesperrt (UI-Schutz).
    const nameInput = container.querySelector<HTMLInputElement>('#enroll-name')!;
    expect(nameInput.disabled).toBe(true);

    // …und selbst ein erzwungener Namenswechsel ändert den eingefrorenen Startindex NICHT
    // mehr: der nächste Satz geht weiterhin an Satz 5 (Fortsetzung der Person B-Sitzung), NICHT
    // an Satz 7 (was „Mira" als frischer Name ausgelöst hätte).
    await act(async () => setInputValue(nameInput, 'Mira'));
    await recordOneSentence(2);

    expect(enroll).toHaveBeenLastCalledWith(expect.any(String), expect.anything(), 5);
  });

  it('lockName ("Weiter anlernen"): Namensfeld ist von ANFANG AN vorausgefüllt+gesperrt + Solo-Anlern-Hinweis sichtbar', async () => {
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
        defaultName="andi"
        lockName
        samplesForName={() => 3} // Fortsetzungs-Index: 3 Sätze schon da ⇒ Satz 4
      />,
    );

    // Ruhiger, fester Hinweis (Kreuz-Kontaminations-Vorfall 07.08).
    expect(container.textContent).toContain(SPEAKER_TEXTS.soloEnrollHint);

    const nameInput = container.querySelector<HTMLInputElement>('#enroll-name')!;
    expect(nameInput.value).toBe('andi');
    expect(nameInput.disabled).toBe(true); // gesperrt VOR dem ersten gespeicherten Satz — anders als der normale Flow

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
