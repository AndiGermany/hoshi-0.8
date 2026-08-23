/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { readFileSync } from 'node:fs';
import type { StreamChatOptions } from '../api/chat';
import type { StreamVoiceOptions } from '../api/voice';

// ═════════════════════════════════════════════════════════════════════════════
//  Voice-Orb (Andi-Auftrag 19.07): der Home-Orb hängt an ECHTEN Signalen
//  derselben Session, die auch der Chat-Reiter treibt. Drei Verträge (die
//  Reiter-Reihenfolge/App-Default-Suiten aus dieser Datei sind per Audit T3,
//  2026-07-21 nach test/topnav.test.tsx umgezogen — reine
//  Datei-Reorganisation, kein Testinhalt geändert):
//   B) Die Orb-Zustandsmaschine idle→listening→thinking→speaking→idle hängt an
//      ECHTEN (hier: gefakten, aber Event-für-Event zugestellten) Wire-Events —
//      keine geschätzten/erfundenen Zwischenzustände.
//   C) reduced-motion: der Orb fügt KEIN eigenes JS-Motion hinzu und reicht
//      dieselben `.vc-orb__*`-Klassen, die die bestehende reduced-motion-Regel
//      (voicebar.css) schon abdeckt — Wiederverwendung statt Duplikat.
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
import {
  VoiceOrb,
  cardTtlMs,
  CARD_TTL_MIN_MS,
  CARD_TTL_MAX_MS,
  CARD_MS_PER_CHAR,
} from '../components/VoiceOrb';
import type { Turn, VoiceChatSession } from '../hooks/useVoiceChatSession';

// ── jsdom-Mount-Harness (Idiom aus identity.test.tsx) ─────────────────────────
let container: HTMLDivElement;
let root: Root | null = null;

const mount = async (el: React.ReactElement): Promise<void> => {
  root = createRoot(container);
  await act(async () => {
    root!.render(el);
  });
};
/** Neuer Zustand in DENSELBEN Root (kein Remount — sonst stürbe die TTL-Frist). */
const rerender = async (el: React.ReactElement): Promise<void> => {
  await act(async () => {
    root!.render(el);
  });
};
const flush = () => new Promise((r) => setTimeout(r, 0));

/** Prop-getriebene Session-Attrappe — von den Sprechblasen- und reduced-motion-Suiten geteilt. */
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

// ═════════════════════════════════════════════════════════════════════════════
//  F) Die Sprechblase hat eine LEBENSDAUER (Andis iPad-Befund 14.08.: „flutet
//     die Sprechblase … die Sprechblasen sollen irgendwann verschwinden").
//     Drei Verträge:
//      F1) Lese-TTL: die Blase blendet nach cardTtlMs von SELBST aus — zeit-,
//          nicht hover-getrieben (iPad/Touch kennt kein Hover).
//      F2) Ein neuer Turn ERSETZT die eine Blase und startet die Frist neu —
//          nie ein Stapel.
//      F3) Solange der Turn ARBEITET (busy) oder Hoshi noch SPRICHT, läuft
//          keine Frist — eine lange Antwort wird nie mitten im Satz gekappt.
// ═════════════════════════════════════════════════════════════════════════════

describe('VoiceOrb — die Sprechblase verschwindet von selbst (F1–F3)', () => {
  /** Ein fertiges Paar (Du/Hoshi) wie es send()/runVoiceTurn hinterlassen. */
  const pair = (userText: string, answer: string, error = false): Turn[] => [
    { role: 'user', text: userText },
    { role: 'assistant', text: answer, meta: 'test', error: error || undefined },
  ];
  const card = () => container.querySelector('.voiceorb__card');

  it('cardTtlMs: Lese-Zeit an der Textlänge, hart gedeckelt — Fehler bekommen die Obergrenze', () => {
    expect(cardTtlMs(0)).toBe(CARD_TTL_MIN_MS); // Untergrenze: auch „Ja." bleibt lesbar
    expect(cardTtlMs(10_000)).toBe(CARD_TTL_MAX_MS); // Obergrenze: Home ist eine Blick-Fläche
    const mid = Math.round((CARD_TTL_MIN_MS + CARD_TTL_MAX_MS) / 2 / CARD_MS_PER_CHAR);
    expect(cardTtlMs(mid)).toBe(mid * CARD_MS_PER_CHAR); // dazwischen: echte Lese-Zeit
    expect(cardTtlMs(mid)).toBeGreaterThan(cardTtlMs(mid - 50)); // monoton in der Länge
    expect(cardTtlMs(1, true)).toBe(CARD_TTL_MAX_MS); // Kürze sagt nichts über Gewicht
  });

  it('F1: die Blase blendet nach ihrer Lese-TTL aus — keine Geste nötig', async () => {
    vi.useFakeTimers();
    try {
      const turns = pair('wie ist das Wetter', 'Heute 18 bis 29 Grad, trocken.');
      const chars = turns[0].text.length + turns[1].text.length;
      await mount(<VoiceOrb session={fakeSession({ turns })} />);
      expect(card()).not.toBeNull();

      await act(async () => {
        vi.advanceTimersByTime(cardTtlMs(chars) - 1);
      });
      expect(card()).not.toBeNull(); // eine Millisekunde vorher steht sie noch

      await act(async () => {
        vi.advanceTimersByTime(1);
      });
      expect(card()).toBeNull(); // …und danach ist der Flur wieder ruhig
      // Der Orb selbst bleibt — nur die Blase geht.
      expect(container.querySelector('.voiceorb__tap')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('F2: ein neuer Turn ERSETZT die Blase (kein Stapel) und startet die Frist neu', async () => {
    vi.useFakeTimers();
    try {
      const first = pair('erster Satz', 'erste Antwort');
      await mount(<VoiceOrb session={fakeSession({ turns: first })} />);
      await act(async () => {
        vi.advanceTimersByTime(CARD_TTL_MIN_MS - 500); // kurz VOR Ablauf
      });

      const second = [...first, ...pair('zweiter Satz', 'zweite Antwort')];
      await rerender(<VoiceOrb session={fakeSession({ turns: second })} />);

      // EINE Blase mit GENAU zwei Zeilen — der alte Turn ist ersetzt, nicht gestapelt.
      expect(container.querySelectorAll('.voiceorb__card')).toHaveLength(1);
      expect(container.querySelectorAll('.voiceorb__row')).toHaveLength(2);
      expect(container.textContent).toContain('zweite Antwort');
      expect(container.textContent).not.toContain('erste Antwort');
      expect(container.textContent).not.toContain('erster Satz');

      // Die Frist des ALTEN Turns läuft nicht weiter (sonst wäre die neue Blase
      // nach 500 ms weg) …
      await act(async () => {
        vi.advanceTimersByTime(600);
      });
      expect(card()).not.toBeNull();

      // …sondern beginnt neu und läuft dann ganz normal ab.
      await act(async () => {
        vi.advanceTimersByTime(CARD_TTL_MAX_MS);
      });
      expect(card()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('F3: solange der Turn arbeitet oder Hoshi spricht, läuft keine Frist', async () => {
    vi.useFakeTimers();
    try {
      const turns = pair('lange Frage', 'eine sehr lange, laut vorgelesene Antwort');
      // busy: die Antwort streamt noch.
      await mount(<VoiceOrb session={fakeSession({ turns, busy: true })} />);
      await act(async () => {
        vi.advanceTimersByTime(CARD_TTL_MAX_MS * 3);
      });
      expect(card()).not.toBeNull();

      // fertig gestreamt, aber das TTS-Audio läuft noch.
      await rerender(<VoiceOrb session={fakeSession({ turns, speaking: true })} />);
      await act(async () => {
        vi.advanceTimersByTime(CARD_TTL_MAX_MS * 3);
      });
      expect(card()).not.toBeNull();

      // Erst als wirklich nichts mehr läuft, startet die Frist — und läuft ab.
      await rerender(<VoiceOrb session={fakeSession({ turns })} />);
      await act(async () => {
        vi.advanceTimersByTime(CARD_TTL_MAX_MS);
      });
      expect(card()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  G) Die Sprech-Schicht bewegt KEINEN Pixel Layout (Andi 23.08., wörtlich:
//     „Das overlay für das eingesprochene und ausgegebene auf dem homescreen
//     verschiebt wieder die größe von allen widgets.").
//
//  Gemessen ist das anderswo — jsdom rechnet kein Layout, und der Beweis sind
//  Kachel-Rechtecke aus zwei echten Engines (`tools/zuhause-probe/sprechen.mjs`:
//  Kachel-Kasten 669 → 355 px und Seiten 3 → 6 VOR dem Fix, byte-identisch
//  DANACH, Chrome wie Firefox). Was hier geriegelt wird, sind die zwei
//  Zusagen, an denen diese Messung hängt und die ein Feinschliff sonst
//  versehentlich zurücknimmt:
//    G1) Blase und Mikro-Fehler liegen IN der Schicht, nie daneben — sonst
//        stünde wieder etwas im Fluss des Orb-Blocks.
//    G2) Die Schicht ist außerhalb des Flusses, zeigerdurchlässig und deckend
//        (sie liegt auf Kacheln), und niemand hat ihr einen `backdrop-filter`
//        untergeschoben.
// ═════════════════════════════════════════════════════════════════════════════

describe('VoiceOrb — die Sprech-Schicht nimmt der Bühne keinen Platz (G1–G2)', () => {
  const pair = (userText: string, answer: string): Turn[] => [
    { role: 'user', text: userText },
    { role: 'assistant', text: answer, meta: 'test' },
  ];

  it('G1: Blase UND Mikro-Fehler hängen in `.voiceorb__say`, nicht im Orb-Fluss', async () => {
    await mount(
      <VoiceOrb
        session={fakeSession({ turns: pair('was läuft', 'Nudeln, zehn Minuten.'), micError: 'kein Mikro' })}
      />,
    );
    const schicht = container.querySelector('.voiceorb__say');
    expect(schicht, 'die Sprech-Schicht fehlt').not.toBeNull();
    // `closest` statt `contains`: die Frage ist nicht „irgendwo im Baum",
    // sondern „in DIESER Schicht" — ein Geschwister der Schicht stünde wieder
    // im Fluss und sähe im DOM fast gleich aus.
    expect(container.querySelector('.voiceorb__card')?.closest('.voiceorb__say')).toBe(schicht);
    expect(container.querySelector('.voiceorb__error')?.closest('.voiceorb__say')).toBe(schicht);
    // Der Orb-Block selbst trägt nur noch Knopf, Beschriftung und die Schicht —
    // alles andere wäre wieder ein Kind, dessen Höhe die Bühne bezahlt.
    const kinder = [...(container.querySelector('.voiceorb')?.children ?? [])].map(
      (el) => el.className.split(/\s+/)[0],
    );
    expect(kinder).toEqual(['voiceorb__tap', 'voiceorb__hint', 'voiceorb__say']);
  });

  it('G1b: die Schicht steht IMMER im DOM — auch ohne Blase und ohne Fehler', async () => {
    // Eine Schicht, die mit ihrem Inhalt entsteht, kann die `aria-live`-Region
    // der Blase nicht tragen (dieselbe Screenreader-Falle wie in HomeEditBar) —
    // und sie wäre bei jedem Erscheinen ein neues Layout-Ereignis.
    await mount(<VoiceOrb session={fakeSession()} />);
    expect(container.querySelector('.voiceorb__say')).not.toBeNull();
    expect(container.querySelector('.voiceorb__card')).toBeNull();
  });

  it('G2: die Schicht liegt außerhalb des Flusses, lässt Zeiger durch und deckt zu', () => {
    const css = readFileSync('src/styles/voicebar.css', 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const rumpf = (sel: string): string => {
      const re = new RegExp(`(?:^|\\n)[ \\t]*${sel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'g');
      const found = [...css.matchAll(re)].map((m) => m[1]);
      expect(found.length, `Selektor \`${sel}\` fehlt in voicebar.css`).toBeGreaterThan(0);
      return found.join('\n');
    };
    // Der Bezugsrahmen: ohne ihn hinge die Schicht am Fenster statt am Orb.
    expect(rumpf('.voiceorb')).toMatch(/position:\s*relative/);
    const schicht = rumpf('.voiceorb__say');
    expect(schicht).toMatch(/position:\s*absolute/);
    // Nach OBEN wachsen: unter dem Orb ist kein Platz (er ist am Boden verankert).
    expect(schicht).toMatch(/bottom:\s*100%/);
    // Sonst läge ein unsichtbarer Deckel über den unteren Kachelreihen.
    expect(schicht).toMatch(/pointer-events:\s*none/);
    expect(rumpf('.voiceorb__say > *')).toMatch(/pointer-events:\s*auto/);
    // Deckend, weil sie auf Kacheln liegt (die Feinheit riegelt surfacemix.test) …
    expect(rumpf('.voiceorb__card')).not.toMatch(/--surface-mix/);
    // … und NIE mit einem Weichzeichner: die Transparenz-Regel des Hauses
    // verbietet ihn, und Firefox liefert ihn je nach Einstellung gar nicht.
    expect(css).not.toMatch(/backdrop-filter/);
  });
});
