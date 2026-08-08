import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  fetchWeatherToday,
  parseWeatherToday,
  type HourlyPoint,
  type WeatherToday,
} from '../hooks/useWeatherToday';

/** Gültige Wire-Antwort von GET /api/v1/weather/today, punktuell überschreibbar. */
const wire = (over: Partial<WeatherToday> = {}): WeatherToday => ({
  label: 'Duisburg',
  todayMin: 18,
  todayMax: 29,
  codeText: 'bedeckt',
  precipMm: 0.4,
  ...over,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('parseWeatherToday — Wire-Vertrag, nie eine erfundene Zahl', () => {
  it('gültige Antwort → alle fünf Felder 1:1', () => {
    expect(parseWeatherToday(wire())).toEqual(wire());
  });

  it('fehlende/falsch typisierte Felder → null (kein Teil-Wetter)', () => {
    expect(parseWeatherToday(null)).toBeNull();
    expect(parseWeatherToday('kaputt')).toBeNull();
    expect(parseWeatherToday({})).toBeNull();
    expect(parseWeatherToday({ ...wire(), label: '' })).toBeNull();
    expect(parseWeatherToday({ ...wire(), todayMin: '18' })).toBeNull();
    expect(parseWeatherToday({ ...wire(), codeText: '' })).toBeNull();
    expect(parseWeatherToday({ ...wire(), precipMm: undefined })).toBeNull();
  });
});

// ── Neu (Flur-Fertigstellung 2026-07-27): additive Felder — Alt-Backends
// liefern sie nicht, ein einzelnes kaputtes Zusatzfeld darf NICHT die ganze
// Antwort verwerfen (anders als der Kern-Vertrag oben). ─────────────────────

const hourPoint = (over: Partial<HourlyPoint> = {}): HourlyPoint => ({
  epochMs: 1_800_000_000_000,
  tempC: 18,
  precipProbability: 15,
  ...over,
});

describe('parseWeatherToday — additive Felder (Jetzt/Morgen/Sonne/Stunden)', () => {
  it('Kern-Vertrag ohne jedes Zusatzfeld bleibt gültig — Alt-Backend-Fall', () => {
    expect(parseWeatherToday(wire())).toEqual(wire());
    expect(parseWeatherToday(wire()) as WeatherToday).not.toHaveProperty('nowTemp');
  });

  it('gültige Zusatzfelder werden 1:1 übernommen', () => {
    const body = {
      ...wire(),
      nowTemp: 22,
      nowCodeText: 'leichter Regen',
      tomorrowMin: 12,
      tomorrowMax: 22,
      tomorrowCodeText: 'sonnig',
      sunriseEpochMs: 1_000,
      sunsetEpochMs: 2_000,
      hourly: [hourPoint(), hourPoint({ epochMs: 1_800_003_600_000, precipProbability: 30 })],
    };
    expect(parseWeatherToday(body)).toEqual(body);
  });

  it('ein falsch typisiertes Zusatzfeld fehlt einzeln — der Rest bleibt gültig', () => {
    const got = parseWeatherToday({ ...wire(), nowTemp: '22', tomorrowMin: 12 });
    expect(got).not.toBeNull();
    expect(got).not.toHaveProperty('nowTemp'); // kaputt ⇒ weg
    expect(got?.tomorrowMin).toBe(12); // gültiges Nachbarfeld bleibt
  });

  it('unvollständige Morgen-Felder (nur min, kein max/codeText) bleiben trotzdem einzeln erhalten', () => {
    const got = parseWeatherToday({ ...wire(), tomorrowMin: 12 });
    expect(got?.tomorrowMin).toBe(12);
    expect(got).not.toHaveProperty('tomorrowMax');
    expect(got).not.toHaveProperty('tomorrowCodeText');
  });

  it('hourly: kaputte Einzel-Punkte werden verworfen, gültige bleiben', () => {
    const got = parseWeatherToday({
      ...wire(),
      hourly: [hourPoint(), { epochMs: 'kaputt', tempC: 1, precipProbability: 1 }, null, 42],
    });
    expect(got?.hourly).toEqual([hourPoint()]);
  });

  it('hourly: leeres/kein Array ⇒ das Feld fehlt (keine leere Liste behaupten)', () => {
    expect(parseWeatherToday({ ...wire(), hourly: [] })).not.toHaveProperty('hourly');
    expect(parseWeatherToday({ ...wire(), hourly: 'kaputt' })).not.toHaveProperty('hourly');
  });
});

describe('fetchWeatherToday — drei ehrliche Zustände', () => {
  it('200 mit gültigem Body → live mit echten Daten', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => wire(),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchWeatherToday();
    expect(got).toEqual({ kind: 'live', data: wire() });
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/weather/today');
  });

  it('404 (Wetter beim Deploy aus) → off — die Kachel bleibt gestrichelt', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }),
    );
    expect(await fetchWeatherToday()).toEqual({ kind: 'off' });
  });

  it('502/5xx (Open-Meteo weg) → unreachable, nie Fake-Wetter', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 502, json: async () => ({}) }),
    );
    expect(await fetchWeatherToday()).toEqual({ kind: 'unreachable' });
  });

  it('Netzfehler → unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    expect(await fetchWeatherToday()).toEqual({ kind: 'unreachable' });
  });

  it('200 mit kaputtem Body → unreachable (kein Vertrauensvorschuss)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ label: 'Duisburg' }), // Rest fehlt
      }),
    );
    expect(await fetchWeatherToday()).toEqual({ kind: 'unreachable' });
  });
});
