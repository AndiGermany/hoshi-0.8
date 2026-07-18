import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  fetchWeatherToday,
  parseWeatherToday,
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
