import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den Wetter-Ort-Settings-Rand, Spiegel von
 * `de.hoshi.web.WeatherLocationController`:
 *  - `GET /api/v1/settings/weather-location` → {@link WeatherLocationSetting}
 *  - `PUT /api/v1/settings/weather-location` Body `{place}` → Geocode ⇒ Store ⇒
 *    autoritativer neuer Zustand mit AUFGELÖSTEM Label; unbekannter Ort ⇒ 404 ⇒
 *    {@link PlaceNotFoundError}; Wetter beim Deploy aus ⇒ 409 ⇒
 *    {@link WeatherLockedError}.
 *
 * Auth + Base-URL exakt wie `api/skills.ts`: Token als `X-Hoshi-Token`-Header,
 * Pfade relativ zu `API_BASE`.
 */

export interface WeatherLocationSetting {
  /** Der wirksame Ort (Store-Wert, sonst der Deploy-Seed). */
  label: string;
  lat: number;
  lon: number;
  /** true ⇔ zur Laufzeit gespeichert (sonst zeigt label den Deploy-Seed). */
  fromStore: boolean;
  /** Ist das Wetter-Grounding beim Deploy überhaupt an? */
  weatherEnabled: boolean;
}

/** 404: die Geocoding-API kennt den Ort nicht — das FE sagt das ehrlich. */
export class PlaceNotFoundError extends Error {
  constructor(
    public readonly place: string,
    message = 'Ort nicht gefunden.',
  ) {
    super(message);
    this.name = 'PlaceNotFoundError';
  }
}

/** 409: Wetter ist beim Deploy deaktiviert — der Ort greift nicht. */
export class WeatherLockedError extends Error {
  constructor(message = 'Beim Deploy deaktiviert; greift nicht.') {
    super(message);
    this.name = 'WeatherLockedError';
  }
}

/** Token-Header wie `api/skills.ts` — nur setzen, wenn ein Token konfiguriert ist. */
function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/** Defensiver Parse der Wire-Antwort (kaputte Felder → Error statt Rate-Werte). */
function toSetting(raw: unknown): WeatherLocationSetting {
  if (!raw || typeof raw !== 'object') throw new Error('Wetter-Ort-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (typeof r.label !== 'string') throw new Error('Wetter-Ort-Antwort unlesbar.');
  return {
    label: r.label,
    lat: typeof r.lat === 'number' ? r.lat : 0,
    lon: typeof r.lon === 'number' ? r.lon : 0,
    fromStore: r.fromStore === true,
    weatherEnabled: r.weatherEnabled === true,
  };
}

/** `GET /api/v1/settings/weather-location`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchWeatherLocation(signal?: AbortSignal): Promise<WeatherLocationSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/weather-location`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/weather-location` mit Body `{place}`. Gibt den
 * AUTORITATIVEN neuen Zustand zurück (mit dem vom Geocoder aufgelösten Label).
 *  - 404 (Ort unbekannt) ⇒ {@link PlaceNotFoundError},
 *  - 409 (Wetter beim Deploy aus) ⇒ {@link WeatherLockedError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveWeatherLocation(
  place: string,
  signal?: AbortSignal,
): Promise<WeatherLocationSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/weather-location`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ place }),
    signal,
  });
  if (res.status === 404) throw new PlaceNotFoundError(place);
  if (res.status === 409) throw new WeatherLockedError();
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}
