/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import type { StreamChatOptions } from '../api/chat';
import type { ChatEvent } from '../api/types';

// ═════════════════════════════════════════════════════════════════════════════
//  Quellen-Struktur-Auftrag (Andi 2026-07-21): eine Recherche-Antwort trug bisher
//  ihre Quelle als angehängten Text — bei einer echten Web-Suche entartete das
//  zu „…Quelle: Quellen: https://…?utm_source=openai." (unbrauchbar, vor allem
//  gesprochen). Das BE hängt seither NICHTS mehr an den Text an; die Quellen
//  reisen strukturiert am additiven `done.escalationSources`-Feld. Diese Suite
//  beweist den FE-Teil: ein kleines „i"-Icon erscheint NUR mit echten Quellen,
//  zeigt Titel/Host + Link (target=_blank, rel=noopener) und bleibt UNSICHTBAR
//  ohne strukturierte Quellen (Modellwissen-Fallback).
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

/** Turn erst wirklich „fertig" (busy→false) — die Icon-Bedingung hängt an `!streaming`. */
const finishTurn = async (): Promise<void> => {
  await act(async () => {
    resolveChat!();
    await flush();
  });
};

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
});

describe('ChatView — Quellen-Icon einer Recherche-Antwort (Quellen-Struktur-Auftrag 2026-07-21)', () => {
  it('rendert das i-Icon mit Titel/Host + Link (target=_blank, rel=noopener), wenn done echte Quellen trägt', async () => {
    await mount();
    await sendText('Wie viele Einwohner hat Tokio?');
    await feedChat({ event: 'start', provider: 'OPENAI', model: 'gpt-5.6-sol', category: 'FACT_SHORT' });
    await feedChat({ event: 'delta', text: 'Tokio hat 14.299.726 Einwohner.' });
    await feedChat({
      event: 'done',
      provider: 'OPENAI',
      escalationSources: [
        { title: 'Tokyo Metropolitan Government', url: 'https://www.metro.tokyo.lg.jp/english/index.html' },
        { url: 'https://example.com/tokio-facts' }, // ohne Titel ⇒ Host-Fallback
      ],
    });
    await finishTurn();

    const details = container.querySelector('details.msg__sources');
    expect(details).not.toBeNull();
    const summary = details!.querySelector('summary')!;
    expect(summary.textContent).toContain('Quellen');

    const links = Array.from(details!.querySelectorAll('a'));
    expect(links).toHaveLength(2);
    expect(links[0].textContent).toBe('Tokyo Metropolitan Government');
    expect(links[0].getAttribute('href')).toBe('https://www.metro.tokyo.lg.jp/english/index.html');
    expect(links[0].getAttribute('target')).toBe('_blank');
    expect(links[0].getAttribute('rel')).toBe('noopener');
    // Ohne Titel: Host aus der URL statt der vollen, langen URL als Fließtext.
    expect(links[1].textContent).toBe('example.com');
    expect(links[1].getAttribute('href')).toBe('https://example.com/tokio-facts');
  });

  it('rendert KEIN Icon ohne strukturierte Quellen (reiner Modellwissen-Fallback, (c))', async () => {
    await mount();
    await sendText('Wie hoch ist der Eiffelturm?');
    await feedChat({ event: 'start', provider: 'OPENAI', model: 'gpt-5.4-nano', category: 'FACT_SHORT' });
    await feedChat({ event: 'delta', text: 'Der Eiffelturm ist 330 Meter hoch.' });
    await feedChat({ event: 'done', provider: 'OPENAI' }); // kein escalationSources-Feld
    await finishTurn();

    expect(container.querySelector('details.msg__sources')).toBeNull();
  });

  it('rendert KEIN Icon, wenn escalationSources eine leere Liste ist', async () => {
    await mount();
    await sendText('Wie hoch ist der Eiffelturm?');
    await feedChat({ event: 'start', provider: 'LOCAL', model: 'gemma', category: 'FACT_SHORT' });
    await feedChat({ event: 'delta', text: 'Der Eiffelturm ist 330 Meter hoch.' });
    await feedChat({ event: 'done', provider: 'LOCAL', escalationSources: [] });
    await finishTurn();

    expect(container.querySelector('details.msg__sources')).toBeNull();
  });

  it('bleibt WÄHREND des Streamens ohne Icon (die Anzeige wartet auf den fertigen Turn)', async () => {
    await mount();
    await sendText('Wie viele Einwohner hat Tokio?');
    await feedChat({ event: 'start', provider: 'OPENAI', model: 'gpt-5.6-sol', category: 'FACT_SHORT' });
    await feedChat({ event: 'delta', text: 'Tokio hat 14.299.726 Einwohner.' });
    await feedChat({
      event: 'done',
      provider: 'OPENAI',
      escalationSources: [{ url: 'https://www.metro.tokyo.lg.jp/english/index.html' }],
    });
    // `busy` kippt erst im `finally` (nach resolveChat) — genau wie beim
    // Stick-to-Bottom-Befund (chatscroll.test.tsx): VOR dem finally ist der
    // Turn noch `streaming`, das Icon darf also noch nicht im DOM stehen.
    expect(container.querySelector('details.msg__sources')).toBeNull();

    await finishTurn();
    expect(container.querySelector('details.msg__sources')).not.toBeNull();
  });
});
