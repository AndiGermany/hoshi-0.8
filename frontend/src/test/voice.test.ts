import { describe, it, expect, vi, afterEach } from 'vitest';
import { streamVoice } from '../api/voice';
import { voiceTurnUploadBlob } from '../audio/wav';
import type { ChatEvent } from '../api/types';

// Konfiguration mocken: fester API_BASE + Token, damit der X-Hoshi-Token-Header
// und die /api/v1/voice-URL deterministisch prüfbar sind.
vi.mock('../api/config', () => ({
  API_BASE: 'http://test',
  TOKEN: 'test-token',
  SPEAKER_ID: 'andi',
  hasToken: () => true,
}));

/** Fake-Response, deren Body die SSE-Frames in EINEM Chunk liefert, dann done. */
function sseResponse(text: string, status = 200) {
  const bytes = new TextEncoder().encode(text);
  let sent = false;
  return {
    status,
    ok: status >= 200 && status < 300,
    body: {
      getReader() {
        return {
          read: () =>
            sent
              ? Promise.resolve({ done: true, value: undefined })
              : ((sent = true), Promise.resolve({ done: false, value: bytes })),
        };
      },
    },
  };
}

const TRANSCRIPT_THEN_REPLY =
  'data:{"event":"step","kind":"transcript","message":"hallo Hoshi"}\n\n' +
  'data:{"event":"start","provider":"LOCAL","category":"SMALLTALK","model":"brain"}\n\n' +
  'data:{"event":"delta","text":"Hallo!"}\n\n' +
  'data:{"event":"done","provider":"LOCAL"}\n\n';

describe('streamVoice — Request + SSE-Verarbeitung', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('POSTet an /api/v1/voice mit Token, octet-stream-Body und language/speak-Query', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(TRANSCRIPT_THEN_REPLY));
    vi.stubGlobal('fetch', fetchMock);
    const blob = new Blob(['webm-bytes'], { type: 'audio/webm;codecs=opus' });

    await streamVoice(blob, { onEvent: () => {} });

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('http://test/api/v1/voice');
    expect(url).toContain('language=DE'); // Default DE, GROSS
    expect(url).toContain('speak=true'); // Sprach-Ein → Sprach-Aus
    expect(init.method).toBe('POST');
    expect(init.body).toBe(blob); // roher Blob als Body
    const headers = init.headers as Record<string, string>;
    expect(headers['X-Hoshi-Token']).toBe('test-token');
    expect(headers['Content-Type']).toBe('application/octet-stream');
  });

  it('reicht das Transkript-Step und die Delta-Sequenz an onEvent durch', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(TRANSCRIPT_THEN_REPLY));
    vi.stubGlobal('fetch', fetchMock);
    const events: ChatEvent[] = [];

    await streamVoice(new Blob(['x']), { onEvent: (e) => events.push(e) });

    expect(events.map((e) => e.event)).toEqual(['step', 'start', 'delta', 'done']);
    const step = events[0] as Extract<ChatEvent, { event: 'step' }>;
    expect(step.kind).toBe('transcript');
    expect(step.message).toBe('hallo Hoshi');
    const delta = events.find((e) => e.event === 'delta') as Extract<
      ChatEvent,
      { event: 'delta' }
    >;
    expect(delta.text).toBe('Hallo!');
  });

  it('language:en geht GROSS als Query mit', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(''));
    vi.stubGlobal('fetch', fetchMock);

    await streamVoice(new Blob(['x']), { onEvent: () => {}, language: 'en', speak: false });

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain('language=EN');
    expect(url).toContain('speak=false');
  });

  it('voice (#6) geht als Query mit — Settings-Default coral, explizite opts schlagen', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(''));
    vi.stubGlobal('fetch', fetchMock);

    // Ohne localStorage (node) → DEFAULT_SETTINGS → Boot-Default coral.
    await streamVoice(new Blob(['x']), { onEvent: () => {} });
    expect(fetchMock.mock.calls[0][0] as string).toContain('voice=coral');

    await streamVoice(new Blob(['x']), { onEvent: () => {}, voice: 'nova' });
    expect(fetchMock.mock.calls[1][0] as string).toContain('voice=nova');
  });

  it('HTTP 415 (Content-Type abgelehnt) wird sichtbar geworfen, nicht verschluckt', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse('', 415));
    vi.stubGlobal('fetch', fetchMock);

    await expect(streamVoice(new Blob(['x']), { onEvent: () => {} })).rejects.toThrow(/415/);
  });
});

// ── Voice-Upload-Vertrag: Speaker-Format-Parität (RIFF/WAV wie beim Anlernen) ──
//
// RCA Score ~0.226: der Speaker-Sidecar liest NUR RIFF/WAV und fehl-dekodiert
// webm/opus STILL als PCM16-Müll. Der Enroll-Pfad konvertierte längst
// (webmBlobToWav); der Voice-Turn schickte den ROHEN Recorder-Blob. Vertrag:
// was die ChatView-Kette (rec.stop() → voiceTurnUploadBlob → streamVoice)
// hochlädt, beginnt mit dem RIFF-Magic.

describe('Voice-Upload-Vertrag — Speaker-Format-Parität', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  /** webm/EBML-Magic (0x1A45DFA3) — womit ein echter MediaRecorder-Blob beginnt. */
  const WEBM_MAGIC = [0x1a, 0x45, 0xdf, 0xa3];

  it('der hochgeladene Blob beginnt mit RIFF (FakeRecorder liefert webm-Bytes)', async () => {
    // Fake-AudioContext: „dekodiert" die webm-Bytes zu 100ms Mono@48k — die
    // restliche Pipeline (mixToMono → downsampleTo → RIFF-Encoder) ist REAL.
    const decodedInputs: Uint8Array[] = [];
    class FakeAudioContext {
      sampleRate = 48000;
      decodeAudioData(data: ArrayBuffer): Promise<AudioBuffer> {
        decodedInputs.push(new Uint8Array(data.slice(0, 4)));
        return Promise.resolve({
          numberOfChannels: 1,
          length: 4800,
          sampleRate: 48000,
          getChannelData: () => new Float32Array(4800),
        } as unknown as AudioBuffer);
      }
      close(): Promise<void> {
        return Promise.resolve();
      }
    }
    vi.stubGlobal('AudioContext', FakeAudioContext);
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(''));
    vi.stubGlobal('fetch', fetchMock);

    // FakeRecorder im stop()-Vertrag des echten VoiceRecorder: webm/opus-Bytes.
    const fakeRecorder = {
      stop: () =>
        Promise.resolve(
          new Blob([new Uint8Array([...WEBM_MAGIC, 0x42, 0x86, 0x81, 0x01])], {
            type: 'audio/webm;codecs=opus',
          }),
        ),
    };

    // Exakt die stopAndSend-Kette: rec.stop() → voiceTurnUploadBlob → streamVoice.
    await streamVoice(await voiceTurnUploadBlob(await fakeRecorder.stop()), {
      onEvent: () => {},
    });

    // Es wurden wirklich die Recorder-Bytes dekodiert (kein Blob aus dem Nichts) …
    expect(Array.from(decodedInputs[0])).toEqual(WEBM_MAGIC);

    // … und der Upload-Body ist ein echtes RIFF/WAV (Sidecar-lesbar), kein webm.
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = init.body as Blob;
    expect(body.type).toBe('audio/wav');
    const first4 = new Uint8Array((await body.arrayBuffer()).slice(0, 4));
    expect(String.fromCharCode(...first4)).toBe('RIFF');
  });

  it('Fallback ohne Web-Audio: der ROHE Blob geht raus (STT lebt, Score leidet)', async () => {
    // Kein AudioContext gestubbt (node) → webmBlobToWav wirft WavConvertError →
    // voiceTurnUploadBlob fällt ehrlich auf die Original-Aufnahme zurück.
    const recorded = new Blob([new Uint8Array(WEBM_MAGIC)], { type: 'audio/webm' });
    await expect(voiceTurnUploadBlob(recorded)).resolves.toBe(recorded);
  });
});
