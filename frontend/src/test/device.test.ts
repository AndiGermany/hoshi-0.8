import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  getDeviceId,
  generateDeviceId,
  resetDeviceIdCache,
  DEVICE_ID_STORAGE_KEY,
} from '../api/device';
import { streamChat } from '../api/chat';
import { streamVoice } from '../api/voice';

/** In-Memory-Storage in DOM-`Storage`-Form (node hat kein localStorage). */
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

/** Sofort-fertiger SSE-Body für streamChat (getReader → done, kein echtes Netz). */
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

/** Fake-Response mit einem leeren SSE-Chunk für streamVoice. */
function voiceResponse() {
  let sent = false;
  return {
    status: 200,
    ok: true,
    body: {
      getReader() {
        return {
          read: () =>
            sent
              ? Promise.resolve({ done: true, value: undefined })
              : ((sent = true), Promise.resolve({ done: false, value: new Uint8Array() })),
        };
      },
    },
  };
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe('getDeviceId — stabile Browser-Id', () => {
  beforeEach(() => resetDeviceIdCache());
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    resetDeviceIdCache();
  });

  it('erzeugt EINMAL eine UUID und persistiert sie unter hoshi.deviceId', () => {
    const store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    const id = getDeviceId();
    expect(id).toMatch(UUID_RE);
    expect(store.getItem(DEVICE_ID_STORAGE_KEY)).toBe(id);
  });

  it('liefert bei Folge-Aufrufen dieselbe Id (auch frisch aus dem Storage)', () => {
    const store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    const a = getDeviceId();
    resetDeviceIdCache(); // Cache leeren → zweiter Aufruf liest aus dem Storage
    expect(getDeviceId()).toBe(a);
  });

  it('respektiert eine bereits gespeicherte Id (kein Überschreiben)', () => {
    const store = memoryStorage();
    store.setItem(DEVICE_ID_STORAGE_KEY, 'vorhandene-geraete-id');
    vi.stubGlobal('localStorage', store);
    expect(getDeviceId()).toBe('vorhandene-geraete-id');
  });

  it('ohne localStorage (node/privat) → session-stabile Id, kein Wurf', () => {
    expect(() => getDeviceId()).not.toThrow();
    const a = getDeviceId();
    expect(a.length).toBeGreaterThan(0);
    expect(getDeviceId()).toBe(a); // Modul-Cache hält sie stabil
  });

  it('generateDeviceId liefert eine plausible UUID-Form', () => {
    expect(generateDeviceId()).toMatch(UUID_RE);
  });
});

describe('deviceId fließt in JEDEN Turn (chat.ts + voice-Pfad)', () => {
  beforeEach(() => resetDeviceIdCache());
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    resetDeviceIdCache();
  });

  it('streamChat: Body trägt die stabile deviceId (ChatRequest.deviceId)', async () => {
    const store = memoryStorage();
    store.setItem(DEVICE_ID_STORAGE_KEY, 'dev-abc');
    vi.stubGlobal('localStorage', store);
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hi', { onEvent: () => {} });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.deviceId).toBe('dev-abc');
  });

  it('streamChat: explizite opts.deviceId schlägt die Storage-Id', async () => {
    vi.stubGlobal('localStorage', memoryStorage());
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hi', { onEvent: () => {}, deviceId: 'override' });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.deviceId).toBe('override');
  });

  it('streamVoice: deviceId geht als Query-Param mit', async () => {
    const store = memoryStorage();
    store.setItem(DEVICE_ID_STORAGE_KEY, 'dev-xyz');
    vi.stubGlobal('localStorage', store);
    const fetchMock = vi.fn().mockResolvedValue(voiceResponse());
    vi.stubGlobal('fetch', fetchMock);

    await streamVoice(new Blob(['x']), { onEvent: () => {} });

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain('deviceId=dev-xyz');
  });
});
