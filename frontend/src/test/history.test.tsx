/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import type { StreamChatOptions } from '../api/chat';
import type { ChatMessage } from '../api/types';

// ── History-Draht (Scheibe 0, 2026-07-03) ────────────────────────────────────
// GOLD-BEFUND: das Backend konsumiert `ChatRequest.history` gebounded, aber kein
// Aufrufer BEFÜLLTE es — jeder Turn ging NACKT los → Hoshi hatte kein
// Gesprächsgedächtnis („Weißt du, wie hoch ER ist?" scheiterte, SkyTree-Kette
// brach). Diese Tests beweisen den Draht END-ZU-END durch die ECHTE ChatView:
// wir mocken NUR streamChat (die Netz-Naht), fangen die mitgeschickte `history`
// pro Turn ab und treiben echte Turns über die Compose-Bar (Enter).
//
// buildHistory ist zusätzlich pur unit-getestet (der Filter „nur fertige Paare").

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// streamChat als Modul-Mock: fängt opts.history je Turn ab UND fährt den Turn
// deterministisch fertig (delta → done), damit die Assistant-Bubble Text bekommt
// und der nächste Turn sie als fertiges Paar in die history aufnehmen kann.
const sentHistories: (ChatMessage[] | undefined)[] = [];
let assistantReply = 'geantwortet';
let failNextTurn = false;

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_text: string, opts: StreamChatOptions) => {
    sentHistories.push(opts.history);
    if (failNextTurn) {
      opts.onEvent({ event: 'error', message: 'Backend kaputt', stage: 'LLM' });
    } else {
      opts.onEvent({ event: 'delta', text: assistantReply });
      opts.onEvent({ event: 'done' });
    }
    return Promise.resolve();
  }),
}));

// Voice-Naht ist NICHT Teil dieser Scheibe — stumm stubben, damit der Import in
// ChatView nicht ins echte Netz greift.
vi.mock('../api/voice', () => ({ streamVoice: vi.fn(() => Promise.resolve()) }));

// Audio-Ausgabe (AudioContext/Earcon) gehört zur späteren Voice-Scheibe und
// existiert in jsdom nicht → als No-op-Stubs. Die pref-Funktionen bleiben echt.
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

import { ChatView, buildHistory, HISTORY_MAX_MESSAGES } from '../components/ChatView';
import type { ComponentProps } from 'react';

type Turn = Parameters<typeof buildHistory>[0][number];
const user = (text: string, error = false): Turn => ({ role: 'user', text, error });
const bot = (text: string, error = false): Turn => ({ role: 'assistant', text, error });

describe('buildHistory — nur fertige Paare, chronologisch, gecappt', () => {
  it('leerer Verlauf → leere history', () => {
    expect(buildHistory([])).toEqual([]);
  });

  it('fertige Paare → role/content in Reihenfolge (ältester zuerst)', () => {
    const turns = [user('eins'), bot('antwort eins'), user('zwei'), bot('antwort zwei')];
    expect(buildHistory(turns)).toEqual([
      { role: 'user', content: 'eins' },
      { role: 'assistant', content: 'antwort eins' },
      { role: 'user', content: 'zwei' },
      { role: 'assistant', content: 'antwort zwei' },
    ]);
  });

  it('Fehler-Paar fällt komplett raus (user + assistant)', () => {
    const turns = [user('ok'), bot('gut'), user('kaputt'), bot('Fehler', true)];
    expect(buildHistory(turns)).toEqual([
      { role: 'user', content: 'ok' },
      { role: 'assistant', content: 'gut' },
    ]);
  });

  it('leere/streamende Assistant-Bubble (noch kein Text) fällt raus', () => {
    const turns = [user('ok'), bot('gut'), user('läuft'), bot('')];
    expect(buildHistory(turns)).toEqual([
      { role: 'user', content: 'ok' },
      { role: 'assistant', content: 'gut' },
    ]);
  });

  it('cappt auf die letzten HISTORY_MAX_MESSAGES Nachrichten', () => {
    const turns: Turn[] = [];
    for (let i = 0; i < 10; i++) turns.push(user(`u${i}`), bot(`a${i}`));
    const out = buildHistory(turns);
    expect(out).toHaveLength(HISTORY_MAX_MESSAGES);
    // die JÜNGSTEN Paare bleiben (u9/a9 am Ende), die ältesten fallen weg.
    expect(out[out.length - 1]).toEqual({ role: 'assistant', content: 'a9' });
    expect(out[0]).toEqual({ role: 'user', content: 'u4' }); // 20 → letzte 12
  });
});

describe('ChatView — Verlauf fließt LIVE in streamChat (echte Sende-Naht)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const props: ComponentProps<typeof ChatView> = {
    persona: 'Standard',
    language: 'de',
    voice: 'coral',
  };

  const mount = async (): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<ChatView {...props} />);
    });
  };

  /** Text in die Composer setzen (React-controlled) und mit Enter senden. */
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
    });
  };

  beforeEach(() => {
    sentHistories.length = 0;
    assistantReply = 'geantwortet';
    failNextTurn = false;
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
  });

  it('a) erster Turn ⇒ history leer', async () => {
    await mount();
    await sendText('Hallo Hoshi');
    expect(sentHistories).toHaveLength(1);
    expect(sentHistories[0]).toEqual([]);
  });

  it('b) dritter Turn ⇒ history trägt die 2 vorigen Paare, Reihenfolge + Rollen', async () => {
    await mount();
    await sendText('Wie hoch ist der Skytree?');
    await sendText('Und der Eiffelturm?');
    await sendText('Weißt du, wie hoch ER ist?');

    expect(sentHistories).toHaveLength(3);
    expect(sentHistories[2]).toEqual([
      { role: 'user', content: 'Wie hoch ist der Skytree?' },
      { role: 'assistant', content: 'geantwortet' },
      { role: 'user', content: 'Und der Eiffelturm?' },
      { role: 'assistant', content: 'geantwortet' },
    ]);
  });

  it('c) eine Fehler-Bubble ist NICHT in der gesendeten history', async () => {
    await mount();
    await sendText('Turn eins'); // sauber
    failNextTurn = true;
    await sendText('Turn zwei'); // Assistant erhält eine Fehler-Bubble
    failNextTurn = false;
    await sendText('Turn drei'); // baut history

    // Nur das erste (saubere) Paar zählt — das Fehler-Paar fehlt komplett,
    // und der gerade laufende dritte Turn ist naturgemäß auch nicht drin.
    expect(sentHistories[2]).toEqual([
      { role: 'user', content: 'Turn eins' },
      { role: 'assistant', content: 'geantwortet' },
    ]);
  });
});
