import { describe, it, expect, vi, afterEach } from 'vitest';
import { streamChat } from '../api/chat';

// Ein sofort-fertiger SSE-Body: getReader() liefert direkt done → streamChat
// schickt nur den POST und beendet, ohne echtes Netz.
function okEmptyStream() {
  return {
    status: 200,
    ok: true,
    body: {
      getReader() {
        return { read: () => Promise.resolve({ done: true, value: undefined }) };
      },
    },
  };
}

function bodyOfLastFetch(fetchMock: ReturnType<typeof vi.fn>): Record<string, unknown> {
  const calls = fetchMock.mock.calls;
  const init = calls[calls.length - 1][1] as RequestInit;
  return JSON.parse(init.body as string);
}

describe('streamChat — Request-Body', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('sendet speakerContext.speakerId (Memory/Episodic) — Default „gast"', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hallo Hoshi', { onEvent: () => {} });

    expect(fetchMock).toHaveBeenCalledOnce();
    const body = bodyOfLastFetch(fetchMock);
    expect(body.speakerContext).toBeDefined();
    expect((body.speakerContext as { speakerId: string }).speakerId).toBe('gast');
  });

  it('behält den bestehenden Vertrag: text/speak + Sprache GROSS', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hi', { onEvent: () => {}, language: 'en' });

    const body = bodyOfLastFetch(fetchMock);
    expect(body.text).toBe('hi');
    expect(body.speak).toBe(false);
    expect(body.language).toBe('EN'); // Uppercase-Verhalten unverändert
  });

  it('default (kein speak-Opt) → speak:false (Text-only, kein TTS)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hallo', { onEvent: () => {} });

    expect(bodyOfLastFetch(fetchMock).speak).toBe(false);
  });

  it('speak:true → Body trägt speak:true (Backend synthetisiert TTS)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('sprich mit mir', { onEvent: () => {}, speak: true });

    expect(bodyOfLastFetch(fetchMock).speak).toBe(true);
  });

  it('Body trägt voice (#6) — Settings-Default coral, explizite opts schlagen', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    // Ohne localStorage (node) → DEFAULT_SETTINGS → Boot-Default coral.
    await streamChat('hi', { onEvent: () => {} });
    expect(bodyOfLastFetch(fetchMock).voice).toBe('coral');

    await streamChat('hi', { onEvent: () => {}, voice: 'nova' });
    expect(bodyOfLastFetch(fetchMock).voice).toBe('nova');
  });

  it('default (keine history) → Body trägt history:[] (backward-compatible)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hi', { onEvent: () => {} });

    expect(bodyOfLastFetch(fetchMock).history).toEqual([]);
  });

  it('displayName-Opt gesetzt (S3-Voice-Erkennung) → Body trägt speakerContext.displayName', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('weiter im Text', {
      onEvent: () => {},
      speakerId: 'mira',
      displayName: 'mira',
    });

    const body = bodyOfLastFetch(fetchMock);
    expect(body.speakerContext).toEqual({ speakerId: 'mira', displayName: 'mira' });
  });

  it('kein displayName-Opt (Default) → Body lässt das Feld WEG — byte-neutraler Backend-Default', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hallo', { onEvent: () => {} });

    const body = bodyOfLastFetch(fetchMock);
    expect(body.speakerContext).toEqual({ speakerId: 'gast' }); // KEIN displayName-Key
  });

  it('leerer displayName-String (Gast/unsicher) → ebenfalls weggelassen', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hallo', { onEvent: () => {}, displayName: '' });

    const body = bodyOfLastFetch(fetchMock);
    expect(body.speakerContext).toEqual({ speakerId: 'gast' });
  });

  it('history-Opt → Body trägt die Turns 1:1 (role/content, Reihenfolge) mit', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    const history = [
      { role: 'user' as const, content: 'Wie hoch ist der Tokyo Skytree?' },
      { role: 'assistant' as const, content: 'Der Tokyo Skytree ist 634 m hoch.' },
    ];
    await streamChat('Weißt du, wie hoch ER ist?', { onEvent: () => {}, history });

    expect(bodyOfLastFetch(fetchMock).history).toEqual(history);
  });
});
