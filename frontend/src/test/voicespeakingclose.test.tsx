/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import type { StreamChatOptions } from '../api/chat';
import type { ChatEvent } from '../api/types';

// ═════════════════════════════════════════════════════════════════════════════
//  Andi-Befund 20.07 ~23:35: nach einer RECHENAUFGABE blieb die Compose-Bar
//  dauerhaft auf „spricht…" stehen — Text UND Audio waren längst komplett, der
//  letzte Schritt schloss nie. Ursache: `speaking` (useVoiceChatSession) schloss
//  ausschließlich am `tts_audio_end`-SSE-Event; blieb dieses (Fastpath-Turn mit
//  Sonderpfad) aus, blieb die Compose-Bar für immer im `wave-out`-Slot hängen
//  (composeSlot(micState, speaking) hängt NUR an `speaking`, unabhängig von
//  busy/micState).
//
//  Der Fix macht das Schließen redundant robust: `speaking` schließt jetzt an
//  DREI Stellen — (1) `tts_audio_end` (Normalfall), (2) `done` (jeder Turn
//  endet damit, auch wenn (1) mal ausblieb), (3) der `finally`-Block, sobald
//  der Stream WIRKLICH zu Ende ist (Erfolg/Fehler/Abbruch) — plus ein groß
//  bemessenes Sicherheitsnetz (SPEAKING_WATCHDOG_MS), falls selbst das ausbleibt.
//  Diese Suite beweist (2), (3) und das Netz einzeln.
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

let chatOpts: StreamChatOptions | null = null;
let resolveChat: (() => void) | null = null;

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_text: string, opts: StreamChatOptions) => {
    chatOpts = opts;
    return new Promise<void>((res) => {
      resolveChat = res;
    });
  }),
}));
vi.mock('../api/voice', () => ({ streamVoice: vi.fn() }));
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

let container: HTMLDivElement;
let root: Root | null = null;
const flush = () => new Promise((r) => setTimeout(r, 0));

const mount = async (): Promise<void> => {
  root = createRoot(container);
  await act(async () => {
    root!.render(<ChatView persona="Standard" language="de" voice="coral" />);
  });
};

/** Text in die Composer setzen (React-controlled) und mit Enter senden. */
const sendText = async (text: string): Promise<void> => {
  const ta = container.querySelector<HTMLTextAreaElement>('textarea.compose__input')!;
  const setValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!;
  await act(async () => {
    setValue.call(ta, text);
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await act(async () => {
    ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  });
};

const feedChat = async (ev: ChatEvent): Promise<void> => {
  await act(async () => {
    chatOpts!.onEvent(ev);
  });
};

/** true, solange die Compose-Bar im „spricht…"-Wave-Out-Slot hängt. */
const showsSpeaking = (): boolean =>
  container.querySelector('.compose__proc-label')?.textContent === 'spricht…';
/** Die Textarea ist nur sichtbar, wenn die Compose-Bar wieder im `input`-Slot ist. */
const hasTextarea = (): boolean => container.querySelector('textarea.compose__input') !== null;

beforeEach(() => {
  chatOpts = null;
  resolveChat = null;
  container = document.createElement('div');
  document.body.appendChild(container);
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) }));
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
  vi.useRealTimers();
});

describe('useVoiceChatSession — „spricht" schließt zuverlässig (Andi-Befund 20.07)', () => {
  it('ein FASTPATH-Turn ohne `tts_audio_end` (Rechenaufgabe) bleibt NICHT für immer auf „spricht…" stehen — `done` schließt mit', async () => {
    await mount();
    await sendText('Was ist 5 mal 3?');
    await feedChat({ event: 'start', provider: 'LOCAL', model: 'policy', category: 'MATH' });
    await feedChat({ event: 'delta', text: '5 mal 3 ist 15.' });
    await feedChat({ event: 'tts_audio_start', provider: 'voxtral' });
    expect(showsSpeaking()).toBe(true); // Hoshi spricht die Antwort gerade

    // GENAU der Andi-Befund: `tts_audio_end` bleibt aus (BE-Sonderpfad),
    // aber der Turn endet trotzdem mit `done` — Text UND Audio sind fertig.
    await feedChat({ event: 'done', provider: 'LOCAL' });

    // schließt SOFORT am `done`, nicht erst am Watchdog (micState bleibt beim
    // getippten Turn die ganze Zeit 'idle' — composeSlot hängt hier nur an
    // `speaking`, die Textarea kommt also sofort zurück, obwohl `busy` intern
    // noch true ist).
    expect(showsSpeaking()).toBe(false);
    expect(hasTextarea()).toBe(true);

    await act(async () => {
      resolveChat!();
      await flush();
    });
    expect(hasTextarea()).toBe(true); // Turn komplett zu, Compose-Bar bleibt im Eingabe-Slot
  });

  it('Sicherheitsnetz: auch wenn WEDER `tts_audio_end` NOCH `done` je ankommen, schließt der `finally`-Block beim Stream-Ende', async () => {
    await mount();
    await sendText('Was ist 7 plus 8?');
    await feedChat({ event: 'start', provider: 'LOCAL', model: 'policy', category: 'MATH' });
    await feedChat({ event: 'delta', text: '7 plus 8 ist 15.' });
    await feedChat({ event: 'tts_audio_start', provider: 'voxtral' });
    expect(showsSpeaking()).toBe(true);

    // Kein `tts_audio_end`, kein `done` — der Stream bricht einfach ab (z. B.
    // Verbindungsfehler). `finally` in send() muss trotzdem schließen.
    await act(async () => {
      resolveChat!();
      await flush();
    });

    expect(showsSpeaking()).toBe(false);
    expect(hasTextarea()).toBe(true);
  });

  it('Sicherheitsnetz-Timer (SPEAKING_WATCHDOG_MS): schließt „spricht" auch wenn der Stream NIE zu Ende geht', async () => {
    vi.useFakeTimers();
    await mount();
    await sendText('Was ist 9 mal 9?');
    await act(async () => {
      chatOpts!.onEvent({ event: 'start', provider: 'LOCAL', model: 'policy', category: 'MATH' });
      chatOpts!.onEvent({ event: 'delta', text: '9 mal 9 ist 81.' });
      chatOpts!.onEvent({ event: 'tts_audio_start', provider: 'voxtral' });
    });
    expect(showsSpeaking()).toBe(true);

    // Der Stream hängt (Promise löst sich nie auf, keine weiteren Events) —
    // NUR der Watchdog darf hier retten.
    await act(async () => {
      vi.advanceTimersByTime(19999);
    });
    expect(showsSpeaking()).toBe(true); // noch nicht — knapp unter der Schwelle

    await act(async () => {
      vi.advanceTimersByTime(2);
    });
    expect(showsSpeaking()).toBe(false); // Watchdog hat „spricht" zwangsgeschlossen
  });

  it('ein NORMALER Turn mit `tts_audio_end` schließt weiterhin sofort daran (kein Regressions-Verhalten)', async () => {
    await mount();
    await sendText('Erzähl mir etwas.');
    await feedChat({ event: 'start', provider: 'LOCAL', model: 'gemma', category: 'SMALLTALK' });
    await feedChat({ event: 'delta', text: 'Hallo!' });
    await feedChat({ event: 'tts_audio_start', provider: 'voxtral' });
    expect(showsSpeaking()).toBe(true);

    await feedChat({ event: 'tts_audio_end', actualMs: 500 });
    expect(showsSpeaking()).toBe(false);

    await feedChat({ event: 'done', provider: 'LOCAL' });
    await act(async () => {
      resolveChat!();
      await flush();
    });
    expect(hasTextarea()).toBe(true);
  });
});
