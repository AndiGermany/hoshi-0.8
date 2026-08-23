import { useEffect, useRef, useState } from 'react';
import { API_BASE, TOKEN } from '../api/config';
import { startVisiblePolling } from './visiblePolling';

/**
 * Heutiges Wetter aus `GET /api/v1/weather/today` — die Datenquelle der
 * Wetter-Kachel im Idle-Gesicht ({@link IdleFace}). Das BE liest den EXAKTEN
 * Datenpfad des Wetter-Groundings (Open-Meteo, Store-Ort gewinnt) — die Kachel
 * zeigt also dieselbe Wahrheit, die Hoshi im Gespräch nutzt.
 *
 * Drei EHRLICHE Zustände statt einem stummen null:
 *  - `{kind:'live'}` — echte heutige Vorhersage (nie erfunden).
 *  - `{kind:'off'}` — 404: Wetter ist beim Deploy deaktiviert
 *    (`HOSHI_WEATHER_ENABLED`) ⇒ die Kachel bleibt gestrichelt („kommt"),
 *    exakt wie vor dem Endpoint.
 *  - `{kind:'unreachable'}` — 401/5xx/Netz/kaputtes JSON: der Endpoint
 *    existiert, liefert aber grad nichts Lesbares ⇒ „—" + ehrliche Notiz.
 * `null` im Hook = noch nicht geladen (der erste Fetch läuft).
 *
 * `parseWeatherToday`/`fetchWeatherToday` sind pure/seam-Funktionen (kein DOM,
 * keine Timer) → ohne Live-Backend unit-testbar (Muster `useOpsStatus`). Der
 * Hook pollt SANFT (~10 min — Wetter ändert sich langsam, das Idle-Gesicht
 * lebt lange).
 *
 * **Flur-Fertigstellung 2026-07-27** — additive Felder (Muster
 * `ScheduledItem.label?`/`remainingSeconds?`: optionale Keys statt `| null`,
 * damit ALT-Backends, die die neuen Keys noch nicht kennen, weiter ein gültiges
 * `WeatherToday` liefern): Jetzt-Temperatur/-Lage aus dem `current`-Node (den
 * das BE schon immer anfragte, aber bis heute verwarf), Morgen (Offset 1),
 * Sonnenauf-/-untergang und ein kompakter `hourly`-Verlauf. Jedes Feld fehlt
 * EINZELN, wenn Open-Meteo/BE es nicht liefert — {@link IdleFace} lässt die
 * jeweilige Zeile dann ehrlich weg statt einen Platzhalter zu zeigen.
 */

export interface WeatherToday {
  /** Wirksames Orts-Label (Store-Wert, sonst Deploy-Seed) — z.B. „Duisburg". */
  label: string;
  /** Heutige Min-Temperatur, gerundet (°C). */
  todayMin: number;
  /** Heutige Max-Temperatur, gerundet (°C). */
  todayMax: number;
  /** Lagen-Text aus dem WMO-Code (Anzeigesprache) — z.B. „bedeckt". */
  codeText: string;
  /** Heutige Niederschlags-Summe in mm. */
  precipMm: number;
  /** Jetzt-Temperatur, gerundet (°C) — fehlt, wenn `current` beim BE nicht lesbar war. */
  nowTemp?: number;
  /** Jetzt-Lage-Text (Anzeigesprache) — fehlt zusammen mit {@link nowTemp}. */
  nowCodeText?: string;
  /** Morgige Min-Temperatur — fehlt, wenn der Tag nicht im Horizont steckt. */
  tomorrowMin?: number;
  /** Morgige Max-Temperatur. */
  tomorrowMax?: number;
  /** Morgige Lage-Text (Anzeigesprache). */
  tomorrowCodeText?: string;
  /** Sonnenaufgang heute, Epoch-ms. */
  sunriseEpochMs?: number;
  /** Sonnenuntergang heute, Epoch-ms. */
  sunsetEpochMs?: number;
  /** Die nächsten ~12 h, kompakt — leer/fehlend, wenn Open-Meteo keine `hourly`-Daten liefert. */
  hourly?: HourlyPoint[];
  /**
   * Sieben-Tage-Ausblick (BE-Vertrag `RESULT-wetter-mehrtage-2026-08-21` §1.2,
   * additiv ans Ende) — **fehlt bei Alt-Backends**; dann bleibt die Zeile auf
   * der XL-Kachel ehrlich weg. Enthält HEUTE als `offset = 0` und ist damit
   * selbsttragend.
   */
  outlook?: DayOutlook[];
}

/**
 * Ein Tag des Mehrtage-Ausblicks ({@link WeatherToday.outlook}).
 *
 * **Wire-Namen ausgeschrieben** (`tempMin`/`tempMax`, nicht `tMin`/`tMax`): das
 * BE hat unterwegs gelernt, dass Jackson `tMin` → `getTMin()` → `tmin`
 * serialisiert hätte — die Namen hier sind die, die wirklich über die Leitung
 * gehen (BE-RESULT §1.2, „Wire-Fund unterwegs").
 *
 * **`dateIso` statt Epoch-ms** (anders als {@link HourlyPoint.epochMs}): ein Tag
 * ist ein KALENDERDATUM. Über Epoch-ms müsste das FE eine Zeitzone raten, um
 * „welcher Wochentag ist das?" zu beantworten — genau die Drift, die bei
 * Sonnenauf-/-untergang schon einmal korrigiert werden musste.
 */
export interface DayOutlook {
  /** 0 = heute, 6 = letzter Tag des Horizonts. */
  offset: number;
  /** Kalendertag, „2026-06-28" — KEIN Zeitpunkt. */
  dateIso: string;
  tempMin: number;
  tempMax: number;
  /** WMO-Text, schon in der Anzeigesprache. */
  codeText: string;
  precipMm: number;
  /** Fehlt = keine Angabe ⇒ Prozent weglassen, **nie „0 %"** (BE-Vertrag). */
  precipProbability?: number;
}

/** Ein Stunden-Punkt des kompakten Verlaufs ({@link WeatherToday.hourly}). */
export interface HourlyPoint {
  /** Epoch-ms dieser Stunde (lokal Europe/Berlin, vom BE bereits aufgelöst). */
  epochMs: number;
  /** Temperatur dieser Stunde, gerundet (°C). */
  tempC: number;
  /** Regenwahrscheinlichkeit dieser Stunde, 0–100. */
  precipProbability: number;
}

export type WeatherTodayState =
  | { kind: 'live'; data: WeatherToday }
  | { kind: 'off' }
  | { kind: 'unreachable' };

/**
 * Validiert die Wire-Antwort gegen den Kern-Vertrag `{label, todayMin, todayMax,
 * codeText, precipMm}`. Fehlt/falsch typisiert ⇒ `null` (nie eine erfundene Zahl)
 * — UNVERÄNDERT zum bisherigen Verhalten (DE byte-identisch für diese Felder).
 *
 * Die additiven Felder (Flur-Fertigstellung 2026-07-27, s. {@link WeatherToday})
 * werden EINZELN geprüft und nur bei korrektem Typ übernommen — ein einzelnes
 * kaputtes/fehlendes Zusatzfeld invalidiert NICHT die ganze Antwort (anders als
 * der Kern-Vertrag oben): das BE liefert sie additiv, ein Alt-Backend lässt sie
 * schlicht weg.
 */
export function parseWeatherToday(body: unknown): WeatherToday | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  if (typeof b.label !== 'string' || b.label === '') return null;
  if (typeof b.todayMin !== 'number' || typeof b.todayMax !== 'number') return null;
  if (typeof b.codeText !== 'string' || b.codeText === '') return null;
  if (typeof b.precipMm !== 'number') return null;

  const result: WeatherToday = {
    label: b.label,
    todayMin: b.todayMin,
    todayMax: b.todayMax,
    codeText: b.codeText,
    precipMm: b.precipMm,
  };
  if (typeof b.nowTemp === 'number') result.nowTemp = b.nowTemp;
  if (typeof b.nowCodeText === 'string' && b.nowCodeText !== '') result.nowCodeText = b.nowCodeText;
  if (typeof b.tomorrowMin === 'number') result.tomorrowMin = b.tomorrowMin;
  if (typeof b.tomorrowMax === 'number') result.tomorrowMax = b.tomorrowMax;
  if (typeof b.tomorrowCodeText === 'string' && b.tomorrowCodeText !== '') {
    result.tomorrowCodeText = b.tomorrowCodeText;
  }
  if (typeof b.sunriseEpochMs === 'number') result.sunriseEpochMs = b.sunriseEpochMs;
  if (typeof b.sunsetEpochMs === 'number') result.sunsetEpochMs = b.sunsetEpochMs;
  if (Array.isArray(b.hourly)) {
    const hourly = parseHourlyPoints(b.hourly);
    if (hourly.length > 0) result.hourly = hourly;
  }
  if (Array.isArray(b.outlook)) {
    const outlook = parseOutlookDays(b.outlook);
    if (outlook.length > 0) result.outlook = outlook;
  }
  return result;
}

/**
 * Verwirft jeden Tag, der nicht dem Pflichtteil des `DayOutlook`-Vertrags folgt
 * — nie ein Teil-Tag (Muster {@link parseHourlyPoints}). `precipProbability`
 * ist der EINZIGE optionale Wert: fehlt er, bleibt er weg, statt zu „0 %" zu
 * werden — „keine Angabe" und „ganz sicher trocken" sind zwei verschiedene
 * Aussagen, und nur eine davon steht in den Daten.
 */
function parseOutlookDays(raw: unknown[]): DayOutlook[] {
  return raw.flatMap((entry) => {
    if (!entry || typeof entry !== 'object') return [];
    const e = entry as Record<string, unknown>;
    if (
      typeof e.offset !== 'number' ||
      typeof e.dateIso !== 'string' ||
      e.dateIso === '' ||
      typeof e.tempMin !== 'number' ||
      typeof e.tempMax !== 'number' ||
      typeof e.codeText !== 'string' ||
      e.codeText === '' ||
      typeof e.precipMm !== 'number'
    ) {
      return [];
    }
    const day: DayOutlook = {
      offset: e.offset,
      dateIso: e.dateIso,
      tempMin: e.tempMin,
      tempMax: e.tempMax,
      codeText: e.codeText,
      precipMm: e.precipMm,
    };
    if (typeof e.precipProbability === 'number') day.precipProbability = e.precipProbability;
    return [day];
  });
}

/** Verwirft jeden Punkt, der nicht dem `{epochMs, tempC, precipProbability}`-Vertrag folgt — nie ein Teil-Punkt. */
function parseHourlyPoints(raw: unknown[]): HourlyPoint[] {
  return raw.flatMap((entry) => {
    if (!entry || typeof entry !== 'object') return [];
    const e = entry as Record<string, unknown>;
    if (
      typeof e.epochMs !== 'number' ||
      typeof e.tempC !== 'number' ||
      typeof e.precipProbability !== 'number'
    ) {
      return [];
    }
    return [{ epochMs: e.epochMs, tempC: e.tempC, precipProbability: e.precipProbability }];
  });
}

/**
 * Abruf mit ehrlicher Zustands-Trennung: 404 = Feature aus (`off`), jeder
 * andere Misserfolg (401/5xx/Netz/kein Vertrag) = `unreachable`. Token geht
 * als `X-Hoshi-Token` (gleicher Mechanismus wie `useDiary`).
 */
export async function fetchWeatherToday(signal?: AbortSignal): Promise<WeatherTodayState> {
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
    const res = await fetch(`${API_BASE}/api/v1/weather/today`, { headers, signal });
    if (res.status === 404) return { kind: 'off' }; // Wetter beim Deploy aus — ehrlich „kommt"
    if (!res.ok) return { kind: 'unreachable' }; // 401/502/5xx → grad nicht lesbar
    const body: unknown = await res.json().catch(() => null);
    const data = parseWeatherToday(body);
    return data ? { kind: 'live', data } : { kind: 'unreachable' };
  } catch {
    return { kind: 'unreachable' }; // Netzfehler/Abbruch → nie Fake-Wetter
  }
}

/** Pollt `GET /api/v1/weather/today` sanft (~10 min). `null` = erster Fetch läuft. */
export function useWeatherToday(intervalMs = 10 * 60 * 1000): WeatherTodayState | null {
  const [state, setState] = useState<WeatherTodayState | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const next = await fetchWeatherToday(controller.signal);
      if (aliveRef.current) setState(next);
    };

    void tick();
    // Gate statt Frequenz: sichtbar taktet es unveraendert, dunkles
    // Display pausiert, Sichtbarwerden holt sofort frisch nach.
    const stopPolling = startVisiblePolling(() => void tick(), intervalMs);
    return () => {
      aliveRef.current = false;
      controller.abort();
      stopPolling();
    };
  }, [intervalMs]);

  return state;
}
