import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  WEATHER_LOCATION_TEXTS,
  WeatherLocationSectionView,
  type WeatherLocationSectionViewProps,
} from '../components/SettingsPanel';
import {
  type WeatherLocationSetting,
  PlaceNotFoundError,
  WeatherLockedError,
  fetchWeatherLocation,
  saveWeatherLocation,
} from '../api/weatherLocation';

/** Gültiger Wire-Zustand (Store-Wert Duisburg), per `over` punktuell überschreibbar. */
const setting = (over: Partial<WeatherLocationSetting> = {}): WeatherLocationSetting => ({
  label: 'Duisburg',
  lat: 51.43247,
  lon: 6.76516,
  fromStore: true,
  weatherEnabled: true,
  ...over,
});

const render = (over: Partial<WeatherLocationSectionViewProps> = {}) =>
  renderToStaticMarkup(
    <WeatherLocationSectionView
      current={setting()}
      place=""
      onPlace={() => {}}
      onSave={() => {}}
      {...over}
    />,
  );

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('WeatherLocationSectionView — Render', () => {
  it('zeigt den aufgelösten Ort, ein Eingabefeld und den Speichern-Knopf', () => {
    const html = render();
    expect(html).toContain('Wetter-Ort');
    expect(html).toContain('Aktuell: Duisburg');
    expect(html).toContain('settings-weather-place');
    expect(html).toContain(WEATHER_LOCATION_TEXTS.save);
    // Store-Wert ⇒ KEIN Deploy-Seed-Zusatz.
    expect(html).not.toContain(WEATHER_LOCATION_TEXTS.seedSuffix);
  });

  it('Deploy-Seed (nichts gespeichert): ehrlicher Zusatz am Label', () => {
    const html = render({ current: setting({ label: 'Berlin', fromStore: false }) });
    expect(html).toContain('Aktuell: Berlin');
    expect(html).toContain(WEATHER_LOCATION_TEXTS.seedSuffix);
  });

  it('leerer Ort ⇒ Speichern disabled; getippter Ort ⇒ aktiv', () => {
    expect(render({ place: '' })).toContain('disabled');
    const withPlace = render({ place: 'Duisburg' });
    // Der Knopf ist aktiv (das Input-Feld trägt nie disabled außer busy).
    expect(withPlace).not.toContain('disabled');
  });

  it('busy: „speichert…" + alles disabled (kein Doppel-PUT)', () => {
    const html = render({ place: 'Duisburg', busy: true });
    expect(html).toContain(WEATHER_LOCATION_TEXTS.saving);
    expect(html).toContain('disabled');
  });

  it('Fehlerpfad: die ehrliche 404-Notiz steht als role=status im Panel', () => {
    const html = render({ note: WEATHER_LOCATION_TEXTS.notFound });
    expect(html).toContain('Ort nicht gefunden.');
    expect(html).toContain('role="status"');
  });

  it('Wetter beim Deploy aus: sichtbarer ehrlicher Hinweis', () => {
    const html = render({ current: setting({ weatherEnabled: false }) });
    expect(html).toContain(WEATHER_LOCATION_TEXTS.locked);
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert', () => {
    const html = render({ current: null, error: WEATHER_LOCATION_TEXTS.loadError });
    expect(html).toContain('Wetter-Ort grad nicht lesbar.');
    expect(html).toContain('role="alert"');
  });
});

describe('api/weatherLocation — Wire-Vertrag', () => {
  it('fetchWeatherLocation: GET auf den Settings-Pfad, parst den Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting({ label: 'Berlin', fromStore: false }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchWeatherLocation();
    expect(got.label).toBe('Berlin');
    expect(got.fromStore).toBe(false);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/weather-location');
  });

  it('saveWeatherLocation: PUT {place}, Antwort trägt das AUFGELÖSTE Label', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting({ label: 'Duisburg' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveWeatherLocation('duisburg');
    expect(got.label).toBe('Duisburg');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ place: 'duisburg' }));
  });

  it('404 ⇒ PlaceNotFoundError mit ehrlicher Meldung', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    await expect(saveWeatherLocation('Xyzzyburg')).rejects.toThrowError(PlaceNotFoundError);
    await expect(saveWeatherLocation('Xyzzyburg')).rejects.toThrow('Ort nicht gefunden.');
  });

  it('409 ⇒ WeatherLockedError (Wetter beim Deploy aus)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409 }));
    await expect(saveWeatherLocation('Duisburg')).rejects.toThrowError(WeatherLockedError);
  });

  it('5xx ⇒ generischer Error mit Status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 502 }));
    await expect(saveWeatherLocation('Duisburg')).rejects.toThrow('502');
  });
});
