/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import type { StreamChatOptions } from '../api/chat';
import type { StreamVoiceOptions } from '../api/voice';

// ═════════════════════════════════════════════════════════════════════════════
//  EMOJI-SWEEP (Andi-Feedback 2026-07-06 + Cowork-Korrektur 20260706-1738):
//  1) Die 🎤-Emoji-Pille beim Einsprechen ist WEG — die Du-Blase trägt eine
//     schlichte Punkte-Pille (Position sagt „du") und verwandelt sich beim
//     Transkript in den Text.
//  2) Kein Emoji-Zeichen mehr als UI-CONTROL im FE-Chrome (0.5-Lehre:
//     „SVG-Icons statt Emojis") — muted SVG-Glyphs (components/icons.tsx)
//     oder ersatzlos gestrichen, wo das Emoji nur den Zustand duplizierte.
//  Typografische Glyphs (✕ ✓ ▸ ▾ ● ·) sind KEINE Emojis und bleiben erlaubt.
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ── Netz-/Audio-Nähte stubben (Idiom aus identity.test.tsx) ──────────────────
// streamVoice ist hier BEWUSST hängend (deferred): so lässt sich der Zustand
// „Aufnahme hochgeladen, Transkript steht noch aus" wirklich beobachten.
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
import { FiredToast } from '../components/FiredToast';
import { ScheduledPanel } from '../components/ScheduledPanel';
import { OpsStatusPill } from '../components/OpsStatusPill';
import { SpeakerListView } from '../components/SpeakerSection';
import { CrewOverlay } from '../components/CrewOverlay';
import { UebersichtView } from '../views/UebersichtView';
import { AktivitaetView } from '../views/AktivitaetView';
import { RaeumeView } from '../views/RaeumeView';
import type { ScheduledItem } from '../hooks/useScheduledItems';
import type { DiaryTurn } from '../hooks/useDiary';

/**
 * Die gesweepten Zeichen: Emojis (bzw. emoji-präsentierende Dingbats), die als
 * UI-Controls im Chrome standen. `☁` matcht auch `☁️`, `🎙` auch `🎙️` usw.
 */
const BANNED = ['🎤', '🎙', '🔊', '🔇', '🔒', '☁', '⚠', '✦', '⏰', '⏱', '🔔', '🟢', '🔵', '▶'];

function expectClean(html: string, wo: string): void {
  for (const ch of BANNED) {
    expect(html.includes(ch), `${wo} enthält verbotenes Emoji ${ch}`).toBe(false);
  }
}

// ── jsdom-Mount-Harness (Idiom aus identity/history.test) ────────────────────

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

/** Tap-to-Toggle: Down→Up (kurzer Tap, bleibt hörend), Down (senden). */
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

const chatProps = { persona: 'Standard' as const, language: 'de' as const, voice: 'coral' };

// ═════════════════════════════════════════════════════════════════════════════
//  1) Die STT-Punkte-Pille an der Du-Position (statt der 🎤-Emoji-Pille)
// ═════════════════════════════════════════════════════════════════════════════

describe('ChatView — Einsprechen: Punkte-Pille an der Du-Blase, KEIN 🎤', () => {
  it('wartend: Du-Blase rechts trägt die Punkte — kein 🎤 im gesamten Markup', async () => {
    await mount(<ChatView {...chatProps} />);
    await speakVoiceTurn();

    // Der Turn hängt (streamVoice deferred) → der Warte-Zustand ist sichtbar.
    expect(voiceOpts).not.toBeNull();
    const userBubble = container.querySelector('.msg--user')!;
    expect(userBubble).not.toBeNull();
    expect(userBubble.querySelector('.thinking')).not.toBeNull(); // Punkte-Pille
    expect(userBubble.querySelectorAll('.thinking__dot')).toHaveLength(3);
    // Die Position sagt „du" — es gibt KEIN Icon und KEIN Emoji:
    expect(container.innerHTML).not.toContain('🎤');
    expect(userBubble.querySelector('svg')).toBeNull();
  });

  it('Transkript trifft ein → die Punkte verwandeln sich in den Text', async () => {
    await mount(<ChatView {...chatProps} />);
    await speakVoiceTurn();

    await act(async () => {
      voiceOpts!.onEvent({ event: 'step', kind: 'transcript', message: 'hallo Hoshi' });
    });
    const userBubble = container.querySelector('.msg--user')!;
    expect(userBubble.textContent).toContain('hallo Hoshi');
    expect(userBubble.querySelector('.thinking')).toBeNull(); // Punkte sind Text geworden

    // Turn sauber zu Ende fahren (kein hängender act-Rest).
    await act(async () => {
      voiceOpts!.onEvent({ event: 'delta', text: 'Hi!' });
      voiceOpts!.onEvent({ event: 'done' });
      resolveVoice!();
      await flush();
    });
    expect(container.querySelector('.msg--user')!.textContent).toContain('hallo Hoshi');
  });

  it('kein Transkript (Fehler/Abbruch) → stille „…"-Zeile statt ewiger Punkte', async () => {
    await mount(<ChatView {...chatProps} />);
    await speakVoiceTurn();

    await act(async () => {
      resolveVoice!(); // Stream endet, ohne dass je ein Transkript kam
      await flush();
    });
    const userBubble = container.querySelector('.msg--user')!;
    expect(userBubble.querySelector('.thinking')).toBeNull();
    expect(userBubble.textContent).toContain('…');
    expect(container.innerHTML).not.toContain('🎤');
  });

  it('Hoshis Denk-Punkte links (streamende Antwort) bleiben unverändert', async () => {
    await mount(<ChatView {...chatProps} />);
    await speakVoiceTurn();
    // Solange keine Deltas da sind, denkt die Hoshi-Blase mit denselben Punkten.
    expect(container.querySelector('.msg--assistant .thinking')).not.toBeNull();
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  2) Sweep übers FE-Chrome: keine Emoji-Zeichen als UI-Controls mehr
// ═════════════════════════════════════════════════════════════════════════════

const sched = (kind: ScheduledItem['kind'], id: string): ScheduledItem => ({
  id,
  kind,
  dueAtEpochMs: Date.now() + 10 * 60_000,
});

const diaryTurn = (over: Partial<DiaryTurn> = {}): DiaryTurn => ({
  ts: '2026-07-06T08:05:00Z',
  category: 'FACT_SHORT',
  persona: 'hoshi',
  ttftMs: 420,
  totalMs: 1200,
  deflected: false,
  error: null,
  stages: null,
  ...over,
});

describe('Emoji-Sweep — Chrome/Controls emoji-frei (SVG-Glyphs oder gestrichen)', () => {
  it('ChatView (idle): emoji-frei', () => {
    expectClean(renderToStaticMarkup(<ChatView {...chatProps} />), 'ChatView');
  });

  it('FiredToast (Timer/Wecker/Erinnerung, silenced): Wecker-SVG statt ⏰', () => {
    const html = renderToStaticMarkup(
      <FiredToast
        items={[
          { id: 'a', kind: 'TIMER', dueAtEpochMs: 1, firedAtEpochMs: 2, missed: false },
          { id: 'b', kind: 'ALARM', dueAtEpochMs: 1, firedAtEpochMs: 2, missed: true },
          { id: 'c', kind: 'REMINDER', dueAtEpochMs: 1, firedAtEpochMs: 2, missed: false },
        ]}
        onAck={() => {}}
        silenced
      />,
    );
    expect(html).toContain('glyph--alarm');
    expectClean(html, 'FiredToast');
  });

  it('ScheduledPanel (aufgeklappt, alle Arten): Uhr/Wecker/Glocke als SVG', async () => {
    await mount(
      <ScheduledPanel
        items={[sched('TIMER', 't'), sched('ALARM', 'a'), sched('REMINDER', 'r')]}
        nowMs={Date.now()}
        onDelete={() => {}}
        onDeleteAll={() => {}}
      />,
    );
    const toggle = container.querySelector<HTMLButtonElement>('.sched__toggle')!;
    await act(async () => {
      toggle.click();
    });
    const html = container.innerHTML;
    expect(html).toContain('glyph--clock');
    expect(html).toContain('glyph--alarm');
    expect(html).toContain('glyph--bell');
    expectClean(html, 'ScheduledPanel');
  });

  it('OpsStatusPill (WARN + Cloud-Banner, offen): Warn-/Wolken-SVG', () => {
    const html = renderToStaticMarkup(
      <OpsStatusPill
        defaultExpanded
        status={{
          overall: 'DEGRADED',
          memory: { level: 'WARN', source: 'brain-health', detail: 'RAM-Druck steigt.' },
          sidecars: [{ name: 'brain', status: 'DEGRADED', detail: 'langsam' }],
          voice: { engine: 'openai', cloud: true },
          allLocal: false,
          ts: 1,
        }}
      />,
    );
    expect(html).toContain('glyph--warn');
    expect(html).toContain('glyph--cloud');
    expectClean(html, 'OpsStatusPill');
  });

  it('SpeakerListView: Mic-SVG im Anlern-Knopf, Schloss-SVG am Consent', () => {
    const html = renderToStaticMarkup(
      <SpeakerListView
        speakers={[{ name: 'andi', enrolledAt: 1, samples: 3 }]}
        onDelete={() => {}}
        onEnroll={() => {}}
      />,
    );
    expect(html).toContain('glyph--mic');
    expect(html).toContain('glyph--lock');
    expectClean(html, 'SpeakerListView');
  });

  it('CrewOverlay: Stern-SVG statt ✦', () => {
    const html = renderToStaticMarkup(
      <CrewOverlay
        open
        members={[{ name: 'Sara', role: 'Ton', mantra: 'warm' }]}
        onClose={() => {}}
      />,
    );
    expect(html).toContain('glyph--star');
    expectClean(html, 'CrewOverlay');
  });

  it('Übersicht/Aktivität/Räume: 🟢/🔵-Ampel-Emojis gestrichen (Pill+Rahmen sagen es)', () => {
    const ueber = renderToStaticMarkup(<UebersichtView state="up" lastChecked={null} />);
    expectClean(ueber, 'UebersichtView');
    expect(ueber).toContain('nicht verdrahtet'); // die ehrliche Achse bleibt — als Text

    const aktiv = renderToStaticMarkup(
      <AktivitaetView
        observations={[{ state: 'up', at: Date.now() }]}
        turns={[diaryTurn({ deflected: true }), diaryTurn({ error: 'TTS' })]}
        now={new Date('2026-07-06T09:00:00Z')}
      />,
    );
    expectClean(aktiv, 'AktivitaetView');

    expectClean(renderToStaticMarkup(<RaeumeView state={null} />), 'RaeumeView (Ladezustand)');
    expectClean(renderToStaticMarkup(<RaeumeView state={{ kind: 'off' }} />), 'RaeumeView (off)');
    expectClean(renderToStaticMarkup(<RaeumeView state={{ kind: 'unreachable' }} />), 'RaeumeView (unreachable)');
    expectClean(
      renderToStaticMarkup(
        <RaeumeView
          state={{
            kind: 'live',
            data: {
              areas: [
                {
                  areaId: 'wohnzimmer',
                  label: 'Wohnzimmer',
                  entities: [{ entityId: 'light.x', domain: 'light', name: 'Deckenlampe', labels: ['hoshi:leselampen'] }],
                },
              ],
              unassigned: [{ entityId: 'climate.tado', domain: 'climate', name: 'Tado', labels: [] }],
            },
          }}
        />,
      ),
      'RaeumeView (live)',
    );
  });
});
