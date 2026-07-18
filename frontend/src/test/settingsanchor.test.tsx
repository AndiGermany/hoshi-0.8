/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ChatEvent } from '../api/types';
import type { StreamChatOptions } from '../api/chat';
import type { StreamVoiceOptions } from '../api/voice';
import {
  SettingsPanel,
  SETTINGS_ANCHOR_CATEGORY,
  ANCHOR_HIGHLIGHT_MS,
  settingsAnchorId,
  type SettingsAnchorId,
  type SettingsCategoryId,
} from '../components/SettingsPanel';
import { IdleFace } from '../components/IdleFace';
import { FiredToast } from '../components/FiredToast';
import type { FiredItem } from '../hooks/useFiredItems';

// ─────────────────────────────────────────────────────────────────────────────
//  Kontextuelle Settings-Anker (Cowork-Spec cowork-research-2026-07-15/03-
//  settings-einbettung.md, V1): openSettings(category, anchor?) + drei
//  verdrahtete Zahnräder (Wetter-Kachel/Sprecher-Chip/Wecker-Banner). Deckt:
//   1. Anker→Kategorie-Mapping (Regressionsschutz gegen Anker-Drift)
//   2. SettingsPanel: category öffnet die richtige Kategorie, anchor pulst +
//      räumt sich selbst auf (Timer gemockt) — inkl. Schnell-Schließen-Fix.
//   3. Die drei Zahnräder selbst: rendern nur mit onOpenSettings, tragen ein
//      aria-label, rufen openSettings mit den korrekten Argumenten, und
//      bleiben SVG (kein Emoji — emojisweep.test.tsx deckt den Sweep ab).
// ─────────────────────────────────────────────────────────────────────────────

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ── ChatView braucht Netz-/Audio-Stubs (Idiom aus identity.test.tsx) ─────────
let voiceEvents: ChatEvent[] = [];

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_text: string, opts: StreamChatOptions) => {
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

function memoryStorage(): Storage {
  const m = new Map<string, string>();
  return {
    get length() {
      return m.size;
    },
    clear: () => m.clear(),
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    key: (i: number) => Array.from(m.keys())[i] ?? null,
    removeItem: (k: string) => {
      m.delete(k);
    },
    setItem: (k: string, v: string) => {
      m.set(k, String(v));
    },
  };
}

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
  voiceEvents = [];
  container = document.createElement('div');
  document.body.appendChild(container);
  vi.stubGlobal('localStorage', memoryStorage());
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
//  1) Anker→Kategorie-Mapping — Regressionsschutz gegen Anker-Drift
// ═════════════════════════════════════════════════════════════════════════════

describe('SETTINGS_ANCHOR_CATEGORY — jeder Anker zeigt auf die richtige Kategorie', () => {
  it('genau die drei verdrahteten Anker, korrekt zugeordnet', () => {
    expect(SETTINGS_ANCHOR_CATEGORY).toEqual({
      'wetter-standort': 'standort-integrationen',
      sprecher: 'gedaechtnis-privatsphaere',
      'wecker-eskalation': 'faehigkeiten',
    });
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  2) SettingsPanel — openSettings(category, anchor?) Deep-Link-Mechanik
// ═════════════════════════════════════════════════════════════════════════════

describe('SettingsPanel — Deep-Link öffnet die richtige Kategorie + pulst den Anker', () => {
  const baseProps = {
    open: true,
    onClose: () => {},
    theme: 'yoru' as const,
    language: 'de' as const,
    persona: 'Standard' as const,
    voice: 'coral',
    onTheme: () => {},
    onLanguage: () => {},
    onPersona: () => {},
    onVoice: () => {},
  };

  beforeEach(() => {
    // Kind-Sektionen (Skills/Speaker/Privacy/Weather/NightMode) fangen Netzfehler
    // längst ehrlich ab (eigene Tests decken das) — hier geht es nur um die
    // Deep-Link-Mechanik selbst (Konvention settingsnav.test.tsx).
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('settingsanchor-test: kein Netz')));
  });

  const panel = (id: SettingsCategoryId) =>
    container.querySelector(`#settings-panel-${id}`) as HTMLElement;

  it('category ohne anchor: springt in die Kategorie, aber pulst nichts', async () => {
    await mount(<SettingsPanel {...baseProps} category="standort-integrationen" />);
    await flush();
    expect(panel('standort-integrationen').hidden).toBe(false);
    expect(panel('darstellung').hidden).toBe(true);
    expect(container.querySelector('.is-anchor-highlight')).toBeNull();
  });

  it('category + anchor: springt UND pulst genau den einen Anker einmalig, räumt sich nach ANCHOR_HIGHLIGHT_MS selbst auf', async () => {
    vi.useFakeTimers();
    try {
      await mount(
        <SettingsPanel {...baseProps} category="standort-integrationen" anchor="wetter-standort" />,
      );
      const anchorEl = container.querySelector(`#${settingsAnchorId('wetter-standort')}`)!;
      expect(anchorEl.className).toContain('is-anchor-highlight');
      // Kein zweiter Anker pulst gleichzeitig mit.
      expect(container.querySelectorAll('.is-anchor-highlight')).toHaveLength(1);

      await act(async () => {
        vi.advanceTimersByTime(ANCHOR_HIGHLIGHT_MS);
      });
      expect(anchorEl.className).not.toContain('is-anchor-highlight');
    } finally {
      vi.useRealTimers();
    }
  });

  it('Schnell-Schließen MITTEN im Puls + normales Wieder-Öffnen (ohne category) hinterlässt keinen hängenden Highlight-Rest', async () => {
    vi.useFakeTimers();
    try {
      function Host() {
        const [open, setOpen] = useState(true);
        const [category, setCategory] = useState<SettingsCategoryId | undefined>(
          'standort-integrationen',
        );
        const [anchor, setAnchor] = useState<SettingsAnchorId | undefined>('wetter-standort');
        return (
          <>
            <button
              type="button"
              data-testid="reopen-plain"
              onClick={() => {
                setCategory(undefined);
                setAnchor(undefined);
                setOpen(true);
              }}
            />
            <SettingsPanel
              {...baseProps}
              open={open}
              category={category}
              anchor={anchor}
              onClose={() => setOpen(false)}
            />
          </>
        );
      }

      await mount(<Host />);
      const anchorEl = container.querySelector(`#${settingsAnchorId('wetter-standort')}`)!;
      expect(anchorEl.className).toContain('is-anchor-highlight');

      // Weit vor Ablauf schließen (der 1600ms-Timer feuert nie natürlich).
      await act(async () => {
        vi.advanceTimersByTime(200);
      });
      const closeBtn = container.querySelector('.settings__close') as HTMLButtonElement;
      await act(async () => {
        closeBtn.click();
      });
      expect(anchorEl.className).not.toContain('is-anchor-highlight'); // Cleanup räumt sofort auf

      // Normaler Top-Nav-Wiedereinstieg (kein Deep-Link) — bleibt sauber.
      const reopen = container.querySelector('[data-testid="reopen-plain"]') as HTMLButtonElement;
      await act(async () => {
        reopen.click();
      });
      expect(anchorEl.className).not.toContain('is-anchor-highlight');
    } finally {
      vi.useRealTimers();
    }
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  3) Die drei Zahnräder — Render-Vertrag + Klick-Verdrahtung
// ═════════════════════════════════════════════════════════════════════════════

describe('IdleFace — Zahnrad NUR an der Wetter-Kachel → Standort & Integrationen/Wetter-Standort', () => {
  const idleProps = {
    nowMs: Date.now(),
    health: 'up' as const,
    voice: null,
    scheduled: [],
    turns: [],
    weather: null,
  };

  it('ohne onOpenSettings: keine der drei Kacheln trägt ein Zahnrad (kein Bruch)', () => {
    const html = renderToStaticMarkup(<IdleFace {...idleProps} />);
    expect(html).not.toContain('ctxgear');
  });

  it('mit onOpenSettings: GENAU EIN Zahnrad (an der Wetter-Kachel), SVG statt Emoji, mit aria-label', () => {
    const html = renderToStaticMarkup(<IdleFace {...idleProps} onOpenSettings={() => {}} />);
    expect((html.match(/class="ctxgear"/g) ?? []).length).toBe(1);
    expect(html).toContain('glyph--gear');
    expect(html).toContain('aria-label="Wetter-Einstellungen öffnen (Standort');
    expect(html).toContain('Integrationen)"');
  });

  it('Klick ruft onOpenSettings genau mit (standort-integrationen, wetter-standort) auf', async () => {
    const onOpenSettings = vi.fn();
    await mount(<IdleFace {...idleProps} onOpenSettings={onOpenSettings} />);
    const gear = container.querySelector('.ctxgear') as HTMLButtonElement;
    expect(gear).not.toBeNull();
    await act(async () => {
      gear.click();
    });
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    expect(onOpenSettings).toHaveBeenCalledWith('standort-integrationen', 'wetter-standort');
  });
});

describe('FiredToast — Zahnrad am Wecker-/Klingel-Banner → Fähigkeiten/Wecker-Eskalation', () => {
  const oneItem: FiredItem[] = [
    { id: 'f-1', kind: 'TIMER', dueAtEpochMs: 1_000, firedAtEpochMs: 1_100, missed: false },
  ];

  it('ohne onOpenSettings: kein Zahnrad, Ack-Button bleibt unverändert bedienbar', () => {
    const html = renderToStaticMarkup(<FiredToast items={oneItem} onAck={() => {}} />);
    expect(html).not.toContain('fired-toast__gear');
    expect(html).toContain('<button');
  });

  it('mit onOpenSettings: Zahnrad trägt aria-label + SVG-Glyph, kein Emoji', () => {
    const html = renderToStaticMarkup(
      <FiredToast items={oneItem} onAck={() => {}} onOpenSettings={() => {}} />,
    );
    expect(html).toContain('fired-toast__gear');
    expect(html).toContain('glyph--gear');
    expect(html).toMatch(/aria-label="[^"]*Fähigkeiten[^"]*"/);
  });

  it('Zahnrad-Klick ruft openSettings(faehigkeiten, wecker-eskalation) OHNE den Ack auszulösen', async () => {
    const onAck = vi.fn();
    const onOpenSettings = vi.fn();
    await mount(<FiredToast items={oneItem} onAck={onAck} onOpenSettings={onOpenSettings} />);
    const gear = container.querySelector('.fired-toast__gear') as HTMLButtonElement;
    await act(async () => {
      gear.click();
    });
    expect(onOpenSettings).toHaveBeenCalledWith('faehigkeiten', 'wecker-eskalation');
    expect(onAck).not.toHaveBeenCalled();
  });

  it('Ack-Klick bestätigt weiterhin unabhängig vom (Geschwister-)Zahnrad', async () => {
    const onAck = vi.fn();
    await mount(<FiredToast items={oneItem} onAck={onAck} onOpenSettings={() => {}} />);
    const ack = container.querySelector('.fired-toast__ack') as HTMLButtonElement;
    await act(async () => {
      ack.click();
    });
    expect(onAck).toHaveBeenCalledTimes(1);
  });
});

describe('ChatView — Zahnrad am „Wer sprach"-Chip → Gedächtnis & Privatsphäre/Sprecher', () => {
  /** Tap-to-Toggle über den Mikro-Knopf (Idiom aus identity.test.tsx). */
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

  const withRecognizedSpeaker = () => {
    voiceEvents = [
      { event: 'speaker', recognizedSpeaker: 'mira', confidence: 0.97, isGuest: false },
      { event: 'step', kind: 'transcript', message: 'hallo Hoshi' },
      { event: 'delta', text: 'Hi Mira!' },
      { event: 'done' },
    ];
  };

  it('ohne onOpenSettings: der Chip steht da, aber ohne Zahnrad (kein Bruch)', async () => {
    withRecognizedSpeaker();
    await mount(<ChatView persona="Standard" language="de" voice="coral" />);
    await speakVoiceTurn();
    expect(container.querySelector('.msg__speaker')).not.toBeNull();
    expect(container.querySelector('.msg__speakerrow .ctxgear')).toBeNull();
  });

  it('mit onOpenSettings: das Zahnrad trägt ein aria-label und ruft openSettings(gedaechtnis-privatsphaere, sprecher)', async () => {
    withRecognizedSpeaker();
    const onOpenSettings = vi.fn();
    await mount(
      <ChatView persona="Standard" language="de" voice="coral" onOpenSettings={onOpenSettings} />,
    );
    await speakVoiceTurn();

    const gear = container.querySelector('.msg__speakerrow .ctxgear') as HTMLButtonElement;
    expect(gear).not.toBeNull();
    expect(gear.getAttribute('aria-label')).toContain('Gedächtnis');

    await act(async () => {
      gear.click();
    });
    expect(onOpenSettings).toHaveBeenCalledWith('gedaechtnis-privatsphaere', 'sprecher');
  });
});
