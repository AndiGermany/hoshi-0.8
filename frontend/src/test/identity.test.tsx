/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ComponentProps } from 'react';
import type { ChatEvent } from '../api/types';
import type { StreamChatOptions } from '../api/chat';
import type { StreamVoiceOptions } from '../api/voice';
import {
  SpeakerChip,
  speakerHue,
  isGuestSpeaker,
  GUEST_LABEL,
} from '../components/SpeakerChip';

// Identitäts-UI (Stimm-ERKENNUNG S3). Vier Verträge:
//   1) getippter Name fließt an enroll (nicht mehr hart „andi")
//   2) der „Wer sprach"-Chip liest den erkannten Namen aus der Voice-Antwort
//   3) Gast/unsicher ⇒ grauer „Gast"-Chip (Vera-Regel: lieber grau als falsch)
//   4) getippter Turn ⇒ KEIN Chip; aber der zuletzt erkannte Sprecher steuert
//      speakerContext.speakerId der Folge-Tipp-Turns (dynamisch, Default „gast").

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ── Netz-/Audio-Nähte stubben (Idiom aus history.test.tsx) ───────────────────
// streamChat fängt opts je Turn ab (für den dynamischen speakerId-Beweis) und
// fährt den Turn deterministisch fertig; streamVoice emittiert eine skriptbare
// Event-Folge (inkl. dem vorangestellten `speaker`-Event).
const chatCalls: StreamChatOptions[] = [];
let voiceEvents: ChatEvent[] = [];

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_text: string, opts: StreamChatOptions) => {
    chatCalls.push(opts);
    opts.onEvent({ event: 'delta', text: 'ok' });
    opts.onEvent({ event: 'done' });
    return Promise.resolve();
  }),
}));
vi.mock('../api/voice', () => ({
  streamVoice: vi.fn((_blob: Blob, opts: StreamVoiceOptions) => {
    for (const ev of voiceEvents) opts.onEvent(ev);
    return Promise.resolve();
  }),
}));

// Mikro: Fake-Recorder (kein echtes getUserMedia in jsdom) — start/stop lösen auf,
// stop liefert einen nicht-leeren Blob (size>0 ⇒ runVoiceTurn läuft).
vi.mock('../audio/recorder', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/recorder')>();
  class FakeVoiceRecorder {
    constructor(_opts?: unknown) {}
    start() {
      return Promise.resolve();
    }
    stop() {
      return Promise.resolve(new Blob(['audio-bytes']));
    }
    cancel() {}
  }
  return { ...orig, VoiceRecorder: FakeVoiceRecorder };
});

// AudioContext/Earcon existieren in jsdom nicht → No-op-Stubs (wie history.test).
vi.mock('../audio/playback', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/playback')>();
  class FakeAudioQueue {
    start() {}
    stop() {}
    enqueue() {}
    close() {}
    getOutputLevel() {
      return 0;
    }
  }
  return { ...orig, AudioQueue: FakeAudioQueue };
});
vi.mock('../audio/earcon', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/earcon')>();
  return { ...orig, playTurnEarcon: vi.fn() };
});

import { ChatView } from '../components/ChatView';
import { EnrollDialog, SPEAKER_TEXTS, sampleProgress } from '../components/SpeakerSection';
import { wavBlobFromPcm } from '../audio/wav';
import type { EnrollCapture } from '../audio/enrollCapture';

// ═════════════════════════════════════════════════════════════════════════════
//  1) speakerHue — deterministisch + im Bereich (Basis des Namens-Akzents)
// ═════════════════════════════════════════════════════════════════════════════

describe('speakerHue — deterministischer, dezenter Namens-Akzent', () => {
  it('gleicher Name ⇒ gleicher Ton (wiedererkennbar)', () => {
    expect(speakerHue('andi')).toBe(speakerHue('andi'));
    expect(speakerHue('mira')).toBe(speakerHue('mira'));
  });
  it('liegt immer in [0,360)', () => {
    for (const n of ['andi', 'mira', 'x', 'Kai-42', '']) {
      const h = speakerHue(n);
      expect(h).toBeGreaterThanOrEqual(0);
      expect(h).toBeLessThan(360);
    }
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  2)+3) SpeakerChip — erkannt vs. Gast (Vera-Regel HART)
// ═════════════════════════════════════════════════════════════════════════════

describe('SpeakerChip — erkannt trägt Namen+Ton, Gast bleibt grau', () => {
  it('erkannt ⇒ Name, Namens-Ton (--spk-hue), Konfidenz im Tooltip, KEIN Gast', () => {
    const html = renderToStaticMarkup(
      <SpeakerChip speaker={{ name: 'mira', confidence: 0.97, isGuest: false }} />,
    );
    expect(html).toContain('mira');
    expect(html).toContain('--spk-hue'); // deterministischer Akzent gesetzt
    expect(html).toContain('97'); // Konfidenz-% im title
    expect(html).not.toContain('is-guest');
  });

  it('Gast (isGuest) ⇒ „Gast", is-guest (grau/gestrichelt), Vera-Regel im Tooltip', () => {
    const html = renderToStaticMarkup(
      <SpeakerChip speaker={{ name: null, confidence: 0.55, isGuest: true }} />,
    );
    expect(html).toContain(GUEST_LABEL); // „Gast"
    expect(html).toContain('is-guest');
    expect(html).toContain('falsche Person'); // lieber grau als falsch
    expect(html).not.toContain('--spk-hue'); // kein Namens-Akzent
  });

  it('Vera: Name da, aber isGuest=true ⇒ trotzdem Gast (nie geraten)', () => {
    const html = renderToStaticMarkup(
      <SpeakerChip speaker={{ name: 'mira', confidence: 0.4, isGuest: true }} />,
    );
    expect(html).toContain('is-guest');
    expect(html).toContain(GUEST_LABEL);
    expect(html).not.toContain('>mira<');
  });

  it('Vera: name=null aber isGuest=false ⇒ Gast (keine leere Zuordnung)', () => {
    expect(isGuestSpeaker({ name: null, confidence: 0.9, isGuest: false })).toBe(true);
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  jsdom-Mount-Harness (Idiom aus enrolldialog/history.test)
// ═════════════════════════════════════════════════════════════════════════════

let container: HTMLDivElement;
let root: Root | null = null;

const mount = async (el: React.ReactElement): Promise<void> => {
  root = createRoot(container);
  await act(async () => {
    root!.render(el);
  });
};
const flush = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => {
  chatCalls.length = 0;
  voiceEvents = [];
  container = document.createElement('div');
  document.body.appendChild(container);
  // useScheduledItems pollt beim Mount → fetch stumm halten (kein Live-Netz).
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) }),
  );
});
afterEach(async () => {
  if (root) {
    const r = root;
    await act(async () => r.unmount());
    root = null;
  }
  container.remove();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

// ═════════════════════════════════════════════════════════════════════════════
//  1) EnrollDialog — der GETIPPTE Name fließt an enroll (nicht hart „andi")
// ═════════════════════════════════════════════════════════════════════════════

describe('EnrollDialog — getippter Name landet 1:1 im enroll-Call', () => {
  const setInputValue = (el: HTMLInputElement, value: string) => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const clickText = async (text: string) => {
    const btn = Array.from(container.querySelectorAll('button')).find((b) =>
      (b.textContent ?? '').includes(text),
    ) as HTMLButtonElement;
    await act(async () => {
      btn.click();
      await flush();
    });
  };

  it('Name „mira" getippt ⇒ enroll(„mira", WAV) — Consent-Ton „So nennt Hoshi dich."', async () => {
    const wav = wavBlobFromPcm(new Float32Array(2000).fill(0.1), 16000);
    const capture: EnrollCapture = {
      start: vi.fn().mockResolvedValue(undefined),
      stop: vi.fn().mockResolvedValue(wav),
      cancel: vi.fn(),
    };
    const enroll = vi.fn().mockResolvedValue({ name: 'mira', enrolledAt: 7 });

    await mount(
      <EnrollDialog
        onClose={() => {}}
        onEnrolled={() => {}}
        enroll={enroll}
        createCapture={() => capture}
        support={() => ({ ok: true })}
      />,
    );

    // Sara-Consent-Ton steht am Namensfeld.
    expect(container.textContent).toContain('So nennt Hoshi dich');

    const nameInput = container.querySelector<HTMLInputElement>('#enroll-name')!;
    await act(async () => {
      setInputValue(nameInput, 'mira'); // überschreibt den „andi"-Default
    });

    // Multi-Sample-Flow: Satz 1 aufnehmen + speichern reicht für den Namens-Beweis.
    await clickText(`${sampleProgress(1)} ${SPEAKER_TEXTS.recordSample}`);
    await clickText(SPEAKER_TEXTS.finish);

    expect(enroll).toHaveBeenCalledTimes(1);
    const [nameArg, wavArg, sampleArg] = enroll.mock.calls[0] as [string, Blob, number];
    expect(nameArg).toBe('mira'); // NICHT „andi"
    expect(wavArg.type).toBe('audio/wav');
    expect(sampleArg).toBe(1); // Satz 1 ersetzt (frischer Start)
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  2)+3)+4) ChatView — Chip liest die Voice-Antwort, getippt ⇒ kein Chip,
//           dynamischer speakerId
// ═════════════════════════════════════════════════════════════════════════════

describe('ChatView — Wer-sprach-Chip + dynamischer speakerId', () => {
  const props: ComponentProps<typeof ChatView> = {
    persona: 'Standard',
    language: 'de',
    voice: 'coral',
  };

  /** Tap-to-Toggle über den Mikro-Knopf: Down→Up (kurzer Tap, bleibt hörend),
   *  Down (zweiter Tap ⇒ stopAndSend ⇒ runVoiceTurn ⇒ streamVoice). */
  const speakVoiceTurn = async (): Promise<void> => {
    const mic = container.querySelector<HTMLButtonElement>('button.vc-mic')!;
    await act(async () => {
      mic.dispatchEvent(new Event('pointerdown', { bubbles: true }));
      await flush();
    });
    await act(async () => {
      mic.dispatchEvent(new Event('pointerup', { bubbles: true }));
      await flush();
    });
    await act(async () => {
      mic.dispatchEvent(new Event('pointerdown', { bubbles: true }));
      await flush();
    });
  };

  const sendText = async (text: string): Promise<void> => {
    const ta = container.querySelector<HTMLTextAreaElement>('textarea.compose__input')!;
    const setValue = Object.getOwnPropertyDescriptor(
      HTMLTextAreaElement.prototype,
      'value',
    )!.set!;
    await act(async () => {
      setValue.call(ta, text);
      ta.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => {
      ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await flush();
    });
  };

  const chips = () => Array.from(container.querySelectorAll('.msg__speaker'));

  it('erkannt ⇒ Chip trägt den Namen aus der Voice-Antwort; getippter Folge-Turn: KEIN neuer Chip', async () => {
    voiceEvents = [
      { event: 'speaker', recognizedSpeaker: 'mira', confidence: 0.97, isGuest: false },
      { event: 'step', kind: 'transcript', message: 'hallo Hoshi' },
      { event: 'delta', text: 'Hi Mira!' },
      { event: 'done' },
    ];
    await mount(<ChatView {...props} />);
    await speakVoiceTurn();

    // Der Chip liest recognizedSpeaker → „mira", kein Gast.
    const chip = chips();
    expect(chip).toHaveLength(1);
    expect(chip[0].textContent).toContain('mira');
    expect(chip[0].className).not.toContain('is-guest');
    // Transkript sitzt in der Du-Blase.
    expect(container.textContent).toContain('hallo Hoshi');

    // Getippter Folge-Turn: KEIN neuer Chip (ehrlich — wir wissen nicht, wer tippt).
    await sendText('und du?');
    expect(chips()).toHaveLength(1); // unverändert der eine Voice-Chip
  });

  it('erkannt ⇒ speakerId der Folge-TIPP-Turns wird dynamisch (mira statt gast)', async () => {
    voiceEvents = [
      { event: 'speaker', recognizedSpeaker: 'mira', confidence: 0.95, isGuest: false },
      { event: 'step', kind: 'transcript', message: 'hi' },
      { event: 'delta', text: 'Hi!' },
      { event: 'done' },
    ];
    await mount(<ChatView {...props} />);
    await speakVoiceTurn();

    await sendText('weiter im Text');
    expect(chatCalls).toHaveLength(1);
    expect(chatCalls[0].speakerId).toBe('mira'); // dynamisch aus der Erkennung
  });

  it('Gast/unsicher ⇒ grauer „Gast"-Chip; speakerId der Folge-Turns wird „gast"', async () => {
    voiceEvents = [
      { event: 'speaker', recognizedSpeaker: null, confidence: 0.55, isGuest: true },
      { event: 'step', kind: 'transcript', message: 'wer bist du' },
      { event: 'delta', text: 'Hallo!' },
      { event: 'done' },
    ];
    await mount(<ChatView {...props} />);
    await speakVoiceTurn();

    const chip = chips();
    expect(chip).toHaveLength(1);
    expect(chip[0].className).toContain('is-guest');
    expect(chip[0].textContent).toContain('Gast');
    expect(chip[0].textContent).not.toContain('gast'); // Anzeige ist „Gast", nicht die id

    await sendText('und jetzt getippt');
    expect(chatCalls[0].speakerId).toBe('gast'); // BE-gespiegelt: kein Memory für Gast
  });

  it('reiner Text-Chat (nie gesprochen) ⇒ kein Chip, speakerId Default „gast"', async () => {
    await mount(<ChatView {...props} />);
    await sendText('nur getippt');
    expect(chips()).toHaveLength(0);
    expect(chatCalls[0].speakerId).toBe('gast'); // Ein-Nutzer-Textfall bleibt wie heute
  });

  // ── BE-Prompt-Lücke: die erkannte Person soll Hoshi auch beim TIPPEN erreichen ──
  // (ChatStreamController + SpeakerDisplayNameResolver). ChatView muss dafür den
  // Namen aus dem 'speaker'-Event NEBEN der speakerId mitführen und durchreichen.

  it('erkannt ⇒ displayName der Folge-TIPP-Turns trägt den erkannten Namen', async () => {
    voiceEvents = [
      { event: 'speaker', recognizedSpeaker: 'mira', confidence: 0.95, isGuest: false },
      { event: 'step', kind: 'transcript', message: 'hi' },
      { event: 'delta', text: 'Hi!' },
      { event: 'done' },
    ];
    await mount(<ChatView {...props} />);
    await speakVoiceTurn();

    await sendText('weiter im Text');
    expect(chatCalls).toHaveLength(1);
    expect(chatCalls[0].displayName).toBe('mira');
  });

  it('Gast/unsicher ⇒ displayName der Folge-Turns bleibt leer (kein Name geraten)', async () => {
    voiceEvents = [
      { event: 'speaker', recognizedSpeaker: null, confidence: 0.55, isGuest: true },
      { event: 'step', kind: 'transcript', message: 'wer bist du' },
      { event: 'delta', text: 'Hallo!' },
      { event: 'done' },
    ];
    await mount(<ChatView {...props} />);
    await speakVoiceTurn();

    await sendText('und jetzt getippt');
    expect(chatCalls[0].displayName).toBe('');
  });

  it('reiner Text-Chat (nie gesprochen) ⇒ ohne Event kein displayName (leer)', async () => {
    await mount(<ChatView {...props} />);
    await sendText('nur getippt');
    expect(chatCalls[0].displayName).toBe('');
  });
});
