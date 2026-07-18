import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  SpeakerEnrollError,
  deleteSpeaker,
  enrollSpeaker,
  fetchSpeakers,
} from '../api/speakers';
import { wavBlobFromPcm } from '../audio/wav';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('fetchSpeakers — GET /api/v1/speakers + defensiver Parse', () => {
  it('GET → geparstes SpeakerSummary[] (Müll-Einträge fallen still raus, NIE Embedding)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve([
          { name: 'andi', enrolledAt: 1720000000000 },
          { name: 'mira', enrolledAt: 1720999999999 },
          null,
          'nope',
          { enrolledAt: 5 }, // ohne name → verworfen
        ]),
    });
    vi.stubGlobal('fetch', fetchMock);

    const list = await fetchSpeakers();

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(String(url)).toContain('/api/v1/speakers');
    expect(list).toHaveLength(2);
    expect(list[0]).toEqual({ name: 'andi', enrolledAt: 1720000000000 });
    // Der Wire-Vektor käme (falls je vorhanden) nie durch — SpeakerSummary hat kein Feld dafür.
    expect((list[0] as unknown as Record<string, unknown>).embedding).toBeUndefined();
  });

  it('401 → wirft (Auth-Wand wird ehrlich durchgereicht)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchSpeakers()).rejects.toThrow(/401/);
  });
});

describe('enrollSpeaker — POST-Contract-Shape (multipart, WAV, filename)', () => {
  it('POSTet den EXAKTEN BE-Contract: ?name=, multipart-Part audio=enroll.wav (audio/wav), kein Content-Type-Header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ name: 'andi', enrolledAt: 1720000000000 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const wav = wavBlobFromPcm(new Float32Array(4000).fill(0.1), 16000);
    const summary = await enrollSpeaker('andi', wav);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    // Name als Query-Param, Endpoint exakt.
    expect(String(url)).toContain('/api/v1/speakers/enroll?name=andi');
    expect(init.method).toBe('POST');

    // FormData mit genau einem audio-Part, der einen filename trägt (Spring-Stolperstein a).
    const body = init.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    const part = body.get('audio') as File;
    expect(part).toBeTruthy();
    expect(part.name).toBe('enroll.wav'); // filename gesetzt → FilePart statt Feld
    expect(part.type).toBe('audio/wav'); // WAV, NICHT webm (libsndfile-Stolperstein b)

    // Content-Type darf NICHT gesetzt sein — fetch setzt die multipart-Boundary selbst.
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(Object.keys(headers).map((k) => k.toLowerCase())).not.toContain('content-type');

    expect(summary).toEqual({ name: 'andi', enrolledAt: 1720000000000 });
  });

  it('Multi-Sample: sample=2 landet als &sample=2 im Query (additiver BE-Contract)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ name: 'andi', enrolledAt: 1, samples: 2 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const summary = await enrollSpeaker('andi', new Blob(), 2);

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(String(url)).toContain('/api/v1/speakers/enroll?name=andi&sample=2');
    expect(summary.samples).toBe(2); // Server-Zwischenstand kommt geparst an
  });

  it('ohne sample-Param bleibt der Query exakt wie heute (kein sample=)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ name: 'andi', enrolledAt: 1 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await enrollSpeaker('andi', new Blob());

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(String(url)).not.toContain('sample=');
  });

  it('409 → SpeakerEnrollError(out-of-sync) — Folge-Sample ohne Satz 1', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409 }));
    await expect(enrollSpeaker('andi', new Blob(), 2)).rejects.toMatchObject({
      kind: 'out-of-sync',
    });
  });

  it('400 → SpeakerEnrollError(bad-name)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400 }));
    const err = await enrollSpeaker('bad name', new Blob()).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(SpeakerEnrollError);
    expect((err as SpeakerEnrollError).kind).toBe('bad-name');
  });

  it('422 → SpeakerEnrollError(too-short) — kein stilles Speichern', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 422 }));
    await expect(enrollSpeaker('andi', new Blob())).rejects.toMatchObject({ kind: 'too-short' });
  });

  it('502 → SpeakerEnrollError(no-embedding) — Sidecar lieferte nichts', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 502 }));
    await expect(enrollSpeaker('andi', new Blob())).rejects.toMatchObject({ kind: 'no-embedding' });
  });

  it('401 → SpeakerEnrollError(auth)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(enrollSpeaker('andi', new Blob())).rejects.toMatchObject({ kind: 'auth' });
  });
});

describe('deleteSpeaker — DELETE-Vertrag + idempotent', () => {
  it('DELETE an /api/v1/speakers/{name}', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204 });
    vi.stubGlobal('fetch', fetchMock);

    await deleteSpeaker('andi');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/api/v1/speakers/andi');
    expect(init.method).toBe('DELETE');
  });

  it('404 → idempotent OK (ein anderes Gerät hat schon gelöscht)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    await expect(deleteSpeaker('weg')).resolves.toBeUndefined();
  });

  it('401 → wirft (Auth-Wand)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(deleteSpeaker('andi')).rejects.toThrow(/401/);
  });
});
