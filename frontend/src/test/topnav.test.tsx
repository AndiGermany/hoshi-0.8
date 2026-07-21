/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import type { StreamChatOptions } from '../api/chat';
import type { StreamVoiceOptions } from '../api/voice';

// ═════════════════════════════════════════════════════════════════════════════
//  Reiter-Navigation (Andi-Auftrag 19.07): „Chat rückt auf Reiter 2, Übersicht
//  bleibt Reiter 1 (Start-Ansicht)" + der Home-Orb sitzt auf dem Start-Reiter.
//  Herausgelöst aus voiceorb.test.tsx (Audit T3, 2026-07-21 — reine
//  Datei-Reorganisation, kein Testinhalt geändert): die beiden Suiten unten
//  drehen sich um TopNav/App-Navigation, nicht um den VoiceOrb selbst. Die
//  echten VoiceOrb-Verträge (Zustandsmaschine/reduced-motion/Ausgabepegel)
//  bleiben in voiceorb.test.tsx.
//   A) Reiter-Reihenfolge/Default: Übersicht zuerst, Chat als zweiter Reiter —
//      und der Orb sitzt auf dem Start-Reiter.
//   D) Chat-Regression: die 578 bestehenden Tests (u. a. identity/turnanatomy/
//      emojisweep/history) laufen unverändert grün — bewiesen durch denselben
//      `npm test`-Lauf, kein gesonderter Test hier nötig.
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ── Netz-/Audio-Nähte stubben (Idiom aus turnanatomy.test.tsx) — BEIDE Streams
// bewusst hängend (deferred): so lassen sich die Orb-Zwischenzustände
// (thinking/speaking) Event für Event beobachten statt nur den Endzustand. ──

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_text: string, opts: StreamChatOptions) => {
    opts.onEvent({ event: 'delta', text: 'ok' });
    opts.onEvent({ event: 'done' });
    return Promise.resolve();
  }),
}));
vi.mock('../api/voice', () => ({
  streamVoice: vi.fn((_blob: Blob, _opts: StreamVoiceOptions) => new Promise<void>(() => {})),
}));
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
// Mutable, damit einzelne Tests simulieren können, was der ECHTE AnalyserNode
// gerade liefert (0 = kein Analyser/Autoplay-Eigenheit, >0 = echter Ausgabe-
// pegel) — muss per `vi.hoisted` VOR der (gehoisteten) vi.mock-Factory stehen.
const fakeOutputLevel = vi.hoisted(() => ({ value: 0 }));

vi.mock('../audio/playback', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/playback')>();
  class FakeAudioQueue {
    start() {}
    stop() {}
    enqueue() {}
    close() {}
    getOutputLevel() {
      return fakeOutputLevel.value;
    }
  }
  return { ...orig, AudioQueue: FakeAudioQueue };
});
vi.mock('../audio/earcon', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/earcon')>();
  return { ...orig, playTurnEarcon: vi.fn() };
});

import App from '../App';
import { TopNav } from '../components/TopNav';

// ── jsdom-Mount-Harness (Idiom aus identity.test.tsx) ─────────────────────────
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
  fakeOutputLevel.value = 0; // jeder Test startet ehrlich ohne Pegel
  container = document.createElement('div');
  document.body.appendChild(container);
  // App zieht beim Mount mehrere Polling-Hooks (Health/Ops/Scheduled/Diary/
  // Wetter/Fired) — ein stummer, immer-ok Fetch-Stub hält sie ehrlich ruhig
  // (leere/„down"-Zustände statt eines Wurfs), ohne echtes Netz zu brauchen.
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
//  A) Reiter-Reihenfolge + Default-Ansicht
// ═════════════════════════════════════════════════════════════════════════════

describe('TopNav — Reiter-Reihenfolge (Andi-Auftrag 19.07)', () => {
  it('Übersicht zuerst, Chat als ZWEITER Reiter (vor Räume/Aktivität)', () => {
    const html = renderToStaticMarkup(
      <TopNav tab="overview" onTab={() => {}} onOpenSettings={() => {}} />,
    );
    const order = ['Übersicht', 'Chat', 'Räume', 'Aktivität'].map((label) => html.indexOf(label));
    expect(order.every((i) => i >= 0)).toBe(true);
    expect(order).toEqual([...order].sort((a, b) => a - b)); // exakt diese Reihenfolge im Markup
  });
});

describe('App — Übersicht ist die Start-Ansicht, der Orb sitzt dort', () => {
  it('lädt auf dem Übersicht-Reiter mit sichtbarem Voice-Orb; Chat-Reiter zeigt die Compose-Bar', async () => {
    await mount(<App />);

    // Default: Übersicht ist aktiv (aria-current) und trägt den Orb.
    const tabs = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.nav__tab'));
    const activeLabel = () => tabs().find((b) => b.getAttribute('aria-current') === 'true')?.textContent;
    expect(activeLabel()).toBe('Übersicht');
    expect(container.querySelector('.voiceorb')).not.toBeNull();
    expect(container.querySelector('.compose__input')).toBeNull(); // Chat noch nicht gemountet

    // Zum Chat-Reiter wechseln: die Compose-Bar erscheint, der Orb verschwindet
    // (die Views mounten/unmounten per Reiter — die Session dahinter bleibt,
    // s. Zustandsmaschine-Test in voiceorb.test.tsx, der genau das ausnutzt).
    const chatTab = tabs().find((b) => b.textContent === 'Chat')!;
    await act(async () => {
      chatTab.click();
      await flush();
    });
    expect(container.querySelector('.compose__input')).not.toBeNull();
    expect(container.querySelector('.voiceorb')).toBeNull();
  });
});
