/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import type { StreamChatOptions } from '../api/chat';
import type { StreamVoiceOptions } from '../api/voice';
import type { ChatEvent } from '../api/types';

// ═════════════════════════════════════════════════════════════════════════════
//  §4 TURN-ANATOMIE (Cowork-Aoi-Spec 20260702-2201): Denk-Stufen-Zeile mit
//  ECHTEN Häkchen über der Antwort + Chips unter der Antwort. Ehrlichkeits-
//  Gesetz: jede Stufe/jeder Chip hängt an einem echten Wire-Event — nichts wird
//  vorab versprochen, nichts erfunden (kein Nachhör-Fenster, kein Ziel/Volume,
//  keine Entity-Korrektur — die gibt der 0.8-Draht nicht her).
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ── Netz-/Audio-Nähte stubben (Idiom aus emojisweep.test.tsx) ────────────────
// BEIDE Streams bewusst hängend (deferred): so lassen sich die Zwischenzustände
// der wachsenden Stufen-Zeile Event für Event beobachten.
let chatOpts: StreamChatOptions | null = null;
let resolveChat: (() => void) | null = null;
let voiceOpts: StreamVoiceOptions | null = null;
let resolveVoice: (() => void) | null = null;

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_text: string, opts: StreamChatOptions) => {
    chatOpts = opts;
    return new Promise<void>((res) => {
      resolveChat = res;
    });
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

import {
  anatomyOnEvent,
  emptyAnatomy,
  providerChipText,
  turnStages,
  TurnChips,
  TurnStagesRow,
  type TurnAnatomyState,
} from '../components/TurnAnatomy';
import { ChatView } from '../components/ChatView';

// ── Baustein 1: der pure Event-Reducer ───────────────────────────────────────

describe('anatomyOnEvent — faltet NUR echte Wire-Events in die Anatomie', () => {
  const start: ChatEvent = {
    event: 'start',
    provider: 'LOCAL',
    model: 'gemma',
    category: 'SMART_HOME',
    grounded: true,
  };

  it('voice beginnt mit „gehört", text ohne jede Stufe', () => {
    expect(emptyAnatomy('voice').heard).toBe(true);
    expect(emptyAnatomy('text').heard).toBe(false);
    expect(turnStages(emptyAnatomy('text'))).toEqual([]);
  });

  it('speaker → erkannter Sprecher; step transcript → verstanden; start → Weg', () => {
    let a = emptyAnatomy('voice');
    a = anatomyOnEvent(a, {
      event: 'speaker',
      recognizedSpeaker: 'andi',
      confidence: 0.81,
      isGuest: false,
    });
    expect(a.speaker).toEqual({ name: 'andi', confidence: 0.81, isGuest: false });
    a = anatomyOnEvent(a, { event: 'step', kind: 'transcript', message: 'mach Licht an' });
    expect(a.understood).toBe(true);
    a = anatomyOnEvent(a, start);
    expect(a.route).toEqual({
      provider: 'LOCAL',
      model: 'gemma',
      category: 'SMART_HOME',
      grounded: true,
    });
  });

  it('grounded zählt NUR bei echtem true (fehlend/undefined = ehrlich nein)', () => {
    const a = anatomyOnEvent(emptyAnatomy('text'), {
      event: 'start',
      provider: 'OPENAI',
      model: 'gpt',
      category: 'NEEDS_WEB',
    });
    expect(a.route!.grounded).toBe(false);
  });

  it('erstes delta → antwortet; weitere deltas geben die SELBE Referenz zurück', () => {
    const a1 = anatomyOnEvent(emptyAnatomy('text'), { event: 'delta', text: 'Hal' });
    expect(a1.answering).toBe(true);
    const a2 = anatomyOnEvent(a1, { event: 'delta', text: 'lo' });
    expect(a2).toBe(a1); // kein unnötiger Patch pro Delta
  });

  it('tts_audio_start → spricht; error → errorStage (Default LLM)', () => {
    let a = anatomyOnEvent(emptyAnatomy('text'), { event: 'tts_audio_start', provider: 'openai' });
    expect(a.speaking).toBe(true);
    a = anatomyOnEvent(a, { event: 'error', message: 'kaputt', stage: 'TTS' });
    expect(a.errorStage).toBe('TTS');
    expect(anatomyOnEvent(emptyAnatomy('text'), { event: 'error', message: 'x' }).errorStage).toBe(
      'LLM',
    );
  });

  it('Events ohne Anatomie-Wirkung geben die SELBE Referenz zurück', () => {
    const a = emptyAnatomy('voice');
    const neutral: ChatEvent[] = [
      { event: 'audio', data: 'AAA', seq: 0 },
      { event: 'tts_audio_end', actualMs: 12 },
      { event: 'done', provider: 'LOCAL' },
      { event: 'step', kind: 'progress', message: 'suche…' }, // kein transcript
    ];
    for (const ev of neutral) expect(anatomyOnEvent(a, ev)).toBe(a);
  });
});

// ── Baustein 2: die Stufen-Ableitung (append-only, ehrlich) ──────────────────

describe('turnStages — nur passierte Stufen, in Pipeline-Reihenfolge', () => {
  it('volle Voice-Kaskade: gehört → erkannt → verstanden → Weg → antwortet → spricht', () => {
    let a = emptyAnatomy('voice');
    a = anatomyOnEvent(a, {
      event: 'speaker',
      recognizedSpeaker: 'andi',
      confidence: 0.8,
      isGuest: false,
    });
    a = anatomyOnEvent(a, { event: 'step', kind: 'transcript', message: 'hallo' });
    a = anatomyOnEvent(a, { event: 'start', provider: 'LOCAL', model: 'm', category: 'SMALLTALK' });
    a = anatomyOnEvent(a, { event: 'delta', text: 'Hi' });
    a = anatomyOnEvent(a, { event: 'tts_audio_start', provider: 'voxtral' });
    expect(turnStages(a).map((s) => s.label)).toEqual([
      'gehört',
      'erkannt: andi',
      'verstanden',
      'Weg gewählt',
      'antwortet',
      'spricht',
    ]);
    expect(turnStages(a).every((s) => !s.failed)).toBe(true);
  });

  it('Gast/unsicher wird NIE ein geratener Name (Vera-Regel)', () => {
    const guest = anatomyOnEvent(emptyAnatomy('voice'), {
      event: 'speaker',
      recognizedSpeaker: null,
      confidence: 0.31,
      isGuest: true,
    });
    expect(turnStages(guest).map((s) => s.label)).toContain('erkannt: Gast');
    // Auch ein mitgelieferter Name zählt bei isGuest nicht (defensive Ehrlichkeit):
    const oddGuest = anatomyOnEvent(emptyAnatomy('voice'), {
      event: 'speaker',
      recognizedSpeaker: 'vielleicht-andi',
      confidence: 0.31,
      isGuest: true,
    });
    expect(turnStages(oddGuest).map((s) => s.label)).toContain('erkannt: Gast');
  });

  it('„Weg gewählt" trägt provider · model · category als title-Detail', () => {
    const a = anatomyOnEvent(emptyAnatomy('text'), {
      event: 'start',
      provider: 'OPENAI',
      model: 'gpt-nano',
      category: 'NEEDS_WEB',
    });
    const route = turnStages(a).find((s) => s.key === 'route')!;
    expect(route.title).toBe('OPENAI · gpt-nano · NEEDS_WEB');
  });

  it('error → letzte Stufe failed mit der ECHTEN Stage (nie erfunden)', () => {
    const a = anatomyOnEvent(emptyAnatomy('voice'), {
      event: 'error',
      message: 'STT down',
      stage: 'STT',
    });
    const stages = turnStages(a);
    const last = stages[stages.length - 1];
    expect(last).toMatchObject({ key: 'error', label: 'STT', failed: true });
  });

  it('Text-Turn vor dem start-Event: KEINE Stufen (nichts wird versprochen)', () => {
    expect(turnStages(emptyAnatomy('text'))).toEqual([]);
  });
});

// ── Baustein 3: der Quelle/Egress-Chip-Text ──────────────────────────────────

describe('providerChipText — lokal bleibt lokal, Cloud heißt ehrlich „ging online"', () => {
  it('LOCAL → „lokal" (ohne Provider-Geplapper)', () => {
    expect(providerChipText('LOCAL')).toBe('lokal');
  });
  it('bekannte Cloud-Provider → warmes Label + „ging online"', () => {
    expect(providerChipText('OPENAI')).toBe('OpenAI · ging online');
    expect(providerChipText('ANTHROPIC')).toBe('Anthropic · ging online');
  });
  it('unbekannter Provider → as-is + „ging online" (nie stillschweigend lokal)', () => {
    expect(providerChipText('NEU')).toBe('NEU · ging online');
  });
});

// ── Baustein 4: statische Render-Verträge (SVG-Glyphs, keine Emojis) ─────────

const routed = (provider: string, grounded = false): TurnAnatomyState => ({
  ...emptyAnatomy('text'),
  route: { provider, model: 'm', category: 'FACT_SHORT', grounded },
  answering: true,
});

describe('TurnStagesRow/TurnChips — Markup-Vertrag', () => {
  it('Stufen-Zeile: ✓ pro passierter Stufe, ✕ + Fehlerton am Riss', () => {
    const a = anatomyOnEvent(routed('LOCAL'), { event: 'error', message: 'x', stage: 'TTS' });
    const html = renderToStaticMarkup(<TurnStagesRow anatomy={a} />);
    expect(html).toContain('✓');
    expect(html).toContain('✕');
    expect(html).toContain('turnstage--failed');
    expect(html).toContain('TTS');
  });

  it('leere Anatomie (Text-Turn vor start) rendert NICHTS', () => {
    expect(renderToStaticMarkup(<TurnStagesRow anatomy={emptyAnatomy('text')} />)).toBe('');
    expect(renderToStaticMarkup(<TurnChips anatomy={emptyAnatomy('text')} />)).toBe('');
  });

  it('Chips lokal: Schloss-SVG + „lokal" — keine Wolke, kein Emoji', () => {
    const html = renderToStaticMarkup(<TurnChips anatomy={routed('LOCAL')} />);
    expect(html).toContain('glyph--lock');
    expect(html).toContain('lokal');
    expect(html).not.toContain('glyph--cloud');
    for (const emoji of ['☁', '🔒', '📚', '⚠']) expect(html).not.toContain(emoji);
  });

  it('Chips Cloud: Wolken-SVG + „OpenAI · ging online"', () => {
    const html = renderToStaticMarkup(<TurnChips anatomy={routed('OPENAI')} />);
    expect(html).toContain('glyph--cloud');
    expect(html).toContain('OpenAI · ging online');
    expect(html).not.toContain('glyph--lock');
  });

  it('Grounding-Chip NUR bei echtem grounded=true', () => {
    expect(renderToStaticMarkup(<TurnChips anatomy={routed('LOCAL', true)} />)).toContain(
      'Wissen gedeckt',
    );
    expect(renderToStaticMarkup(<TurnChips anatomy={routed('LOCAL', false)} />)).not.toContain(
      'Wissen gedeckt',
    );
  });

  it('ehrlich ausgelassen: kein Nachhör-Fenster, kein Ziel/Volume, keine Korrektur-Zeile', () => {
    const full = anatomyOnEvent(routed('LOCAL', true), {
      event: 'tts_audio_start',
      provider: 'voxtral',
    });
    const html =
      renderToStaticMarkup(<TurnStagesRow anatomy={full} />) +
      renderToStaticMarkup(<TurnChips anatomy={full} />);
    for (const invented of ['hört noch', 'Nachhör', '%', 'korrigiert']) {
      expect(html, `erfundenes „${invented}" im Markup`).not.toContain(invented);
    }
  });
});

// ── Baustein 5: LIVE durch die echte ChatView (jsdom) ────────────────────────

let container: HTMLDivElement;
let root: Root | null = null;

const mount = async (): Promise<void> => {
  root = createRoot(container);
  await act(async () => {
    root!.render(<ChatView persona="Standard" language="de" voice="coral" />);
  });
};
const flush = () => new Promise((r) => setTimeout(r, 0));

/** Tap-to-Toggle (Idiom aus emojisweep): Down→Up (hören), Down (senden). */
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

const feedVoice = async (ev: ChatEvent): Promise<void> => {
  await act(async () => {
    voiceOpts!.onEvent(ev);
  });
};
const feedChat = async (ev: ChatEvent): Promise<void> => {
  await act(async () => {
    chatOpts!.onEvent(ev);
  });
};

const stageLabels = (): string[] =>
  Array.from(container.querySelectorAll('.msg--assistant .turnstage')).map(
    (li) => li.textContent!.replace(/[✓✕]/g, '').trim(),
  );

beforeEach(() => {
  chatOpts = null;
  resolveChat = null;
  voiceOpts = null;
  resolveVoice = null;
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

describe('ChatView §4 — die Stufen-Zeile wächst LIVE mit den echten Events', () => {
  it('Voice-Turn: Kaskade Event für Event, Chips erst NACH dem Turn-Ende', async () => {
    await mount();
    await speakVoiceTurn();

    // Upload angenommen → genau EINE ehrliche Stufe: „gehört".
    expect(stageLabels()).toEqual(['gehört']);
    expect(container.querySelector('.turnchips')).toBeNull();

    await feedVoice({ event: 'speaker', recognizedSpeaker: 'andi', confidence: 0.8, isGuest: false });
    expect(stageLabels()).toEqual(['gehört', 'erkannt: andi']);

    await feedVoice({ event: 'step', kind: 'transcript', message: 'mach das Licht an' });
    expect(stageLabels()).toEqual(['gehört', 'erkannt: andi', 'verstanden']);

    await feedVoice({
      event: 'start',
      provider: 'LOCAL',
      model: 'gemma',
      category: 'SMART_HOME',
      grounded: true,
    });
    await feedVoice({ event: 'delta', text: 'Mach ich.' });
    await feedVoice({ event: 'tts_audio_start', provider: 'voxtral' });
    expect(stageLabels()).toEqual([
      'gehört',
      'erkannt: andi',
      'verstanden',
      'Weg gewählt',
      'antwortet',
      'spricht',
    ]);
    // Chips springen NICHT unter der laufenden Antwort herum:
    expect(container.querySelector('.turnchips')).toBeNull();

    await feedVoice({ event: 'tts_audio_end', actualMs: 900 });
    await feedVoice({ event: 'done', provider: 'LOCAL' });
    await act(async () => {
      resolveVoice!();
      await flush();
    });

    // Turn fertig → Chips unter der Antwort: lokal (Schloss) + Wissen gedeckt.
    const chips = container.querySelector('.turnchips')!;
    expect(chips).not.toBeNull();
    expect(chips.textContent).toContain('lokal');
    expect(chips.textContent).toContain('Wissen gedeckt');
    expect(chips.querySelector('.glyph--lock')).not.toBeNull();
    // Die Stufen-Zeile bleibt als stilles Protokoll stehen:
    expect(stageLabels()).toContain('verstanden');
  });

  it('Text-Turn: Weg → antwortet; Cloud-Chip „OpenAI · ging online" nach done', async () => {
    await mount();
    await sendText('Wie hoch ist der Eiffelturm?');

    // Vor dem start-Event: KEINE Stufen-Zeile (nichts versprochen).
    expect(container.querySelector('.turnstages')).toBeNull();

    await feedChat({ event: 'start', provider: 'OPENAI', model: 'gpt-nano', category: 'NEEDS_WEB' });
    expect(stageLabels()).toEqual(['Weg gewählt']);
    await feedChat({ event: 'delta', text: '330 Meter.' });
    expect(stageLabels()).toEqual(['Weg gewählt', 'antwortet']);
    // Ein Tipp-Turn erfindet KEINE Voice-Stufen:
    expect(stageLabels()).not.toContain('gehört');

    await feedChat({ event: 'done', provider: 'OPENAI' });
    await act(async () => {
      resolveChat!();
      await flush();
    });

    const chips = container.querySelector('.turnchips')!;
    expect(chips.textContent).toContain('OpenAI · ging online');
    expect(chips.querySelector('.glyph--cloud')).not.toBeNull();
    expect(chips.textContent).not.toContain('Wissen gedeckt'); // kein grounded=true
  });

  it('Fehler-Turn: ✕ an der ECHTEN Stage, KEINE Chips (meta erklärt das Warum)', async () => {
    await mount();
    await speakVoiceTurn();
    await feedVoice({ event: 'error', message: 'Whisper nicht erreichbar', stage: 'STT' });
    await act(async () => {
      resolveVoice!();
      await flush();
    });

    const failed = container.querySelector('.turnstage--failed')!;
    expect(failed).not.toBeNull();
    expect(failed.textContent).toContain('✕');
    expect(failed.textContent).toContain('STT');
    expect(container.querySelector('.turnchips')).toBeNull();
    // never-silent: die Fehler-Bubble trägt weiter die sichtbare meta-Zeile.
    expect(container.querySelector('.msg--error .msg__meta')!.textContent).toContain('STT');
  });
});
