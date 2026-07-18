/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import type { StreamChatOptions } from '../api/chat';
import type { StreamVoiceOptions } from '../api/voice';

// ═════════════════════════════════════════════════════════════════════════════
//  Voice-Orb (Andi-Auftrag 19.07): „Chat rückt auf Reiter 2, Übersicht bleibt
//  Reiter 1 (Start-Ansicht)" + der Home-Orb hängt an ECHTEN Signalen derselben
//  Session, die auch der Chat-Reiter treibt. Vier Verträge:
//   A) Reiter-Reihenfolge/Default: Übersicht zuerst, Chat als zweiter Reiter —
//      und der Orb sitzt auf dem Start-Reiter.
//   B) Die Orb-Zustandsmaschine idle→listening→thinking→speaking→idle hängt an
//      ECHTEN (hier: gefakten, aber Event-für-Event zugestellten) Wire-Events —
//      keine geschätzten/erfundenen Zwischenzustände.
//   C) reduced-motion: der Orb fügt KEIN eigenes JS-Motion hinzu und reicht
//      dieselben `.vc-orb__*`-Klassen, die die bestehende reduced-motion-Regel
//      (voicebar.css) schon abdeckt — Wiederverwendung statt Duplikat.
//   D) Chat-Regression: die 578 bestehenden Tests (u. a. identity/turnanatomy/
//      emojisweep/history) laufen unverändert grün — bewiesen durch denselben
//      `npm test`-Lauf, kein gesonderter Test hier nötig.
//   E) Ausgabe-Pegel (Andi-Auftrag 19.07 Nachschlag): speaking bloomt auf
//      HOSHIS ECHTEM TTS-Ausgabepegel (AnalyserNode in audio/playback.ts) —
//      derselbe Level-Sink-Kanal wie beim Mikro (Symmetrie rein/raus). Liefert
//      der Analyser einen Pegel, füllt er `--lvl`; fehlt er (Autoplay-Policy/
//      Safari-Eigenheit), bleibt `--lvl` ehrlich 0 — kein erfundenes Wabern.
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ── Netz-/Audio-Nähte stubben (Idiom aus turnanatomy.test.tsx) — BEIDE Streams
// bewusst hängend (deferred): so lassen sich die Orb-Zwischenzustände
// (thinking/speaking) Event für Event beobachten statt nur den Endzustand. ──
let voiceOpts: StreamVoiceOptions | null = null;
let resolveVoice: (() => void) | null = null;

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_text: string, opts: StreamChatOptions) => {
    opts.onEvent({ event: 'delta', text: 'ok' });
    opts.onEvent({ event: 'done' });
    return Promise.resolve();
  }),
}));
vi.mock('../api/voice', () => ({
  streamVoice: vi.fn((_blob: Blob, opts: StreamVoiceOptions) => {
    voiceOpts = opts;
    return new Promise<void>((res) => {
      resolveVoice = res;
    });
  }),
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
import { VoiceOrb } from '../components/VoiceOrb';
import type { VoiceChatSession } from '../hooks/useVoiceChatSession';

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
  voiceOpts = null;
  resolveVoice = null;
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
    // s. Zustandsmaschine-Test unten, der genau das ausnutzt).
    const chatTab = tabs().find((b) => b.textContent === 'Chat')!;
    await act(async () => {
      chatTab.click();
      await flush();
    });
    expect(container.querySelector('.compose__input')).not.toBeNull();
    expect(container.querySelector('.voiceorb')).toBeNull();
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  B) Orb-Zustandsmaschine an ECHTEN (Event-für-Event zugestellten) Signalen
// ═════════════════════════════════════════════════════════════════════════════

describe('VoiceOrb — Zustandsmaschine idle → listening → thinking → speaking → idle', () => {
  const orbState = () => container.querySelector('.voiceorb .vc-orb')?.getAttribute('data-state');
  const tap = async () => {
    const btn = container.querySelector<HTMLButtonElement>('.voiceorb__tap')!;
    await act(async () => {
      btn.click();
      await flush();
    });
  };

  it('durchläuft die volle Kaskade an echten Wire-Events (kein Zustand ohne Signal)', async () => {
    await mount(<App />);
    expect(orbState()).toBe('idle'); // dezentes Atmen — kein Kanal offen

    await tap(); // idle → Aufnahme starten (BESTEHENDER Browser-Voice-Pfad)
    expect(orbState()).toBe('listening'); // echter Mikro-Kanal ist offen

    await tap(); // zweiter Tap → stopAndSend() → runVoiceTurn() → streamVoice()
    expect(orbState()).toBe('thinking'); // Pipeline läuft (STT/LLM), noch kein Audio
    expect(voiceOpts).not.toBeNull();

    await act(async () => {
      voiceOpts!.onEvent({ event: 'step', kind: 'transcript', message: 'hallo Hoshi' });
      await flush();
    });
    expect(orbState()).toBe('thinking'); // Transkript da, immer noch kein Audio

    await act(async () => {
      voiceOpts!.onEvent({ event: 'delta', text: 'Hallo!' });
      voiceOpts!.onEvent({ event: 'tts_audio_start', provider: 'openai' });
      await flush();
    });
    expect(orbState()).toBe('speaking'); // ECHTES tts_audio_start — nicht erraten

    await act(async () => {
      voiceOpts!.onEvent({ event: 'tts_audio_end', actualMs: 400 });
      await flush();
    });
    expect(orbState()).toBe('thinking'); // Audio ist zu Ende, der Turn läuft noch nach

    await act(async () => {
      voiceOpts!.onEvent({ event: 'done' });
      resolveVoice!();
      await flush();
    });
    expect(orbState()).toBe('idle'); // Turn fertig — zurück auf Anfang

    // Die Karte zeigt genau diesen (letzten) Turn — kein zweiter Verlauf.
    expect(container.textContent).toContain('hallo Hoshi');
    expect(container.textContent).toContain('Hallo!');
  });

  it('Tippen während "thinking" (busy, kein Audio) ist gesperrt wie der Mikro-Knopf im Chat-Reiter', async () => {
    await mount(<App />);
    await tap();
    await tap();
    expect(orbState()).toBe('thinking');
    const btn = container.querySelector<HTMLButtonElement>('.voiceorb__tap')!;
    expect(btn.disabled).toBe(true);
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  C) reduced-motion: der Orb fügt KEIN eigenes Motion hinzu — er reicht
//     dieselben .vc-orb__*-Klassen weiter, die voicebar.css (siehe
//     voicestar.test.tsx) bereits reduced-motion-sicher macht.
// ═════════════════════════════════════════════════════════════════════════════

describe('VoiceOrb — reduced-motion-Pfad (Wiederverwendung statt eigenes Motion)', () => {
  function fakeSession(over: Partial<VoiceChatSession> = {}): VoiceChatSession {
    return {
      turns: [],
      busy: false,
      activeSpeakerId: 'andi',
      activeSpeakerName: '',
      voiceOn: false,
      speaking: false,
      micState: 'idle',
      micStateRef: { current: 'idle' },
      micError: null,
      recSecs: 0,
      stepLabel: null,
      slow: false,
      send: async () => {},
      startRecording: async () => {},
      stopAndSend: async () => {},
      cancelRecording: () => {},
      bargeIn: () => {},
      toggleVoice: () => {},
      setLevelSink: () => {},
      ...over,
    };
  }

  it('rendert für jeden Zustand exakt die vc-orb__{core,ring,bloom}-Trias, die die reduced-motion-Regel greift', () => {
    for (const micState of ['idle', 'listening', 'transcribing', 'responding'] as const) {
      const html = renderToStaticMarkup(<VoiceOrb session={fakeSession({ micState })} />);
      expect(html).toContain('vc-orb__core');
      expect(html).toContain('vc-orb__ring');
      expect(html).toContain('vc-orb__bloom');
    }
  });

  it('setzt am Orb NUR die --lvl-Custom-Property inline — jede Bewegung bleibt CSS-/Klassen-getrieben, nichts wird per JS erzwungen', () => {
    const html = renderToStaticMarkup(<VoiceOrb session={fakeSession()} />);
    const styleAttr = html.match(/class="vc-orb vc-orb--idle"[^>]*style="([^"]*)"/)?.[1] ?? '';
    expect(styleAttr).toMatch(/^--lvl:\s*0\b/);
    expect(styleAttr).not.toMatch(/animation|transition/);
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  E) Ausgabe-Pegel (Analyser) treibt denselben Level-Sink wie das Mikro
// ═════════════════════════════════════════════════════════════════════════════

describe('VoiceOrb — echter TTS-Ausgabepegel (AnalyserNode) treibt --lvl beim Sprechen', () => {
  const tap = async () => {
    const btn = container.querySelector<HTMLButtonElement>('.voiceorb__tap')!;
    await act(async () => {
      btn.click();
      await flush();
    });
  };
  const orbLevel = () => {
    const el = container.querySelector<HTMLElement>('.voiceorb .vc-orb');
    return Number.parseFloat(el?.style.getPropertyValue('--lvl') || '0');
  };

  /**
   * Deterministischer rAF-Stub: sammelt angemeldete Frames, `tick()` feuert sie
   * GENAU EINMAL (kein „cb sofort aufrufen" — die Sprech-Schleife plant sich
   * am Ende jedes Frames selbst neu, das würde synchron-rekursiv den Stack
   * sprengen). Wird durch das bestehende `vi.unstubAllGlobals()` in `afterEach`
   * automatisch wieder entfernt.
   */
  function stubRaf() {
    let queue: FrameRequestCallback[] = [];
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      queue.push(cb);
      return queue.length;
    });
    vi.stubGlobal('cancelAnimationFrame', () => {});
    return {
      tick: () => {
        const due = queue;
        queue = [];
        due.forEach((cb) => cb(performance.now()));
      },
    };
  }

  it('Analyser liefert einen echten Pegel ⇒ der Level-Sink (--lvl am Orb) wird während speaking damit gefüttert', async () => {
    const raf = stubRaf();
    await mount(<App />);
    await tap(); // idle → listening
    await tap(); // listening → thinking (streamVoice())

    fakeOutputLevel.value = 0.8; // simuliert einen echten, lauten AnalyserNode-Pegel
    await act(async () => {
      voiceOpts!.onEvent({ event: 'delta', text: 'Hallo!' });
      voiceOpts!.onEvent({ event: 'tts_audio_start', provider: 'openai' });
      await flush();
    });
    expect(orbLevel()).toBe(0); // frisch zurückgesetzt (resetLevel), bevor der erste Frame lief

    await act(async () => {
      raf.tick(); // EIN Frame der Sprech-Pegel-Schleife (useVoiceChatSession)
    });
    expect(orbLevel()).toBeGreaterThan(0); // der echte Analyser-Pegel kam über pushLevel an
    expect(orbLevel()).toBeLessThanOrEqual(1);
  });

  it('kein Analyser verfügbar (Pegel bleibt ehrlich 0) ⇒ kein Fehler, speaking bleibt der dezente Fallback OHNE Pegel', async () => {
    const raf = stubRaf();
    await mount(<App />);
    await tap();
    await tap();

    fakeOutputLevel.value = 0; // Analyser-Pfad nicht verfügbar (Autoplay-Policy/Safari-Eigenheit)
    await act(async () => {
      voiceOpts!.onEvent({ event: 'delta', text: 'Hallo!' });
      voiceOpts!.onEvent({ event: 'tts_audio_start', provider: 'openai' });
      await flush();
    });
    await act(async () => {
      raf.tick();
    });

    // Kein Crash bis hierhin (der eigentliche Beweis) + Zustand bleibt korrekt
    // 'speaking' + der Pegel bleibt ehrlich 0 — kein erfundenes Wabern.
    expect(container.querySelector('.voiceorb .vc-orb')?.getAttribute('data-state')).toBe('speaking');
    expect(orbLevel()).toBe(0);
  });
});
