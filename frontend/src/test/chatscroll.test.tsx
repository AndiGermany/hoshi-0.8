/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import type { StreamChatOptions } from '../api/chat';
import type { ChatEvent } from '../api/types';

// ═════════════════════════════════════════════════════════════════════════════
//  Stick-to-Bottom (Andi-Befund 20.07 ~22:00): „Sobald ich mit meinem Chat nach
//  unten gescrollt bin, laufen ‚lokal'/‚Wissen gedeckt' beim Nachladen aus dem
//  Bild." Ursache: der bisherige Auto-Scroll-Effekt hing NUR an `turns`. Die
//  Status-Pillen (TurnChips) rendern aber erst wenn `busy` auf `false` kippt
//  (useVoiceChatSession: `setBusy(false)` sitzt im `finally`, GETRENNT vom
//  `patchAssistant` des `done`-Events) — ein Render, der `turns` unverändert
//  lässt. Diese Suite deckt beides ab: (1) der Container zieht auch bei einem
//  reinen busy→false-Render nach, solange der User unten steht; (2) wer bewusst
//  hochgescrollt hat, wird nicht zwangsweise zurückgezogen.
//
//  jsdom kennt kein echtes Layout — scrollHeight/clientHeight bleiben ohne
//  Zutun bei 0. Sie werden hier über Object.defineProperty gezielt gesteuert,
//  um genau den Effekt zu simulieren, den ein echter Browser beim Mounten der
//  Pillen hätte (Container wird höher, ohne dass `turns` sich ändert).
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

/** Simulierte Layout-Werte des `.chat__log` (kein echtes Reflow in jsdom). */
let mockScrollHeight = 100;
const CLIENT_HEIGHT = 100;

const mount = async (): Promise<HTMLDivElement> => {
  root = createRoot(container);
  await act(async () => {
    root!.render(<ChatView persona="Standard" language="de" voice="coral" />);
  });
  const logEl = container.querySelector<HTMLDivElement>('.chat__log')!;
  Object.defineProperty(logEl, 'scrollHeight', { configurable: true, get: () => mockScrollHeight });
  Object.defineProperty(logEl, 'clientHeight', { configurable: true, get: () => CLIENT_HEIGHT });
  return logEl;
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

beforeEach(() => {
  chatOpts = null;
  resolveChat = null;
  mockScrollHeight = 100;
  container = document.createElement('div');
  document.body.appendChild(container);
  // useScheduledItems pollt beim Mount → fetch stumm halten (kein Live-Netz).
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
});

describe('ChatView — Stick-to-Bottom Auto-Scroll (Andi-Befund 20.07)', () => {
  it('folgt ans Ende AUCH bei reinem busy→false-Render — genau da mounten die Pillen', async () => {
    const logEl = await mount();

    await sendText('Wie warm ist es im Wohnzimmer?');
    await feedChat({
      event: 'start',
      provider: 'LOCAL',
      model: 'gemma',
      category: 'SMART_HOME',
      grounded: true,
    });

    mockScrollHeight = 220;
    await feedChat({ event: 'delta', text: 'Es sind 21°C.' });
    expect(logEl.scrollTop).toBe(220); // Delta ändert `turns` → zieht mit.

    await feedChat({ event: 'done', provider: 'LOCAL' });
    // `done` patcht nur `turns` (meta) — `busy` ist hier noch `true`, die Pillen
    // hängen an `!streaming` und sind also NOCH nicht im DOM.
    expect(logEl.scrollTop).toBe(220);

    // Jetzt „mounten" die Pillen (Container wird höher) — GENAU der Render, in
    // dem NUR `busy` kippt (setBusy(false) im `finally`), `turns` bleibt gleich.
    mockScrollHeight = 260;
    await act(async () => {
      resolveChat!();
      await flush();
    });

    expect(logEl.scrollTop).toBe(260); // ohne den busy-Fix bliebe das bei 220.
  });

  it('zwingt NICHT zurück ans Ende, wenn der User bewusst hochgescrollt hat', async () => {
    const logEl = await mount();
    await sendText('Erzähl mir was.');
    await feedChat({ event: 'start', provider: 'LOCAL', model: 'gemma', category: 'SMALLTALK' });

    mockScrollHeight = 220;
    await feedChat({ event: 'delta', text: 'Hallo' });
    expect(logEl.scrollTop).toBe(220);

    // User scrollt bewusst weit hoch (Distanz zum Ende deutlich über der Toleranz).
    await act(async () => {
      logEl.scrollTop = 0;
      logEl.dispatchEvent(new Event('scroll', { bubbles: true }));
    });
    expect(logEl.scrollTop).toBe(0);

    // Weiterer Delta, Turn-Ende UND das Pillen-Wachstum — alle OHNE Zwangs-Scroll.
    mockScrollHeight = 240;
    await feedChat({ event: 'delta', text: ' Welt' });
    expect(logEl.scrollTop).toBe(0);

    mockScrollHeight = 280;
    await feedChat({ event: 'done', provider: 'LOCAL' });
    await act(async () => {
      resolveChat!();
      await flush();
    });
    expect(logEl.scrollTop).toBe(0);
  });
});
