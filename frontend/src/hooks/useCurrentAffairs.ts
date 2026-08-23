import { useEffect, useRef, useState } from 'react';
import { API_BASE, TOKEN } from '../api/config';
import { startVisiblePolling } from './visiblePolling';

/**
 * Today's current-affairs headlines from `GET /api/v1/currentaffairs/today` —
 * the data source of the "Lagebild" window on the home tab
 * ({@link ../components/CurrentAffairsTile.tsx}).
 *
 * Three HONEST states instead of one silent null — same seam split as
 * {@link ./useWeatherToday.ts}, which this module mirrors deliberately (both
 * are slow, read-only, polled tile sources):
 *  - `{kind:'live'}` — the endpoint answered and the contract holds. The
 *    payload still carries its own honesty flag, {@link CurrentAffairsFreshness}:
 *    `EMPTY`/`UNAVAILABLE` mean the backend has nothing real to show, and the
 *    window renders NOTHING at all in that case (see the tile).
 *  - `{kind:'off'}` — 404: the feature is switched off for this deploy.
 *  - `{kind:'unreachable'}` — 401/5xx/network/broken JSON: the endpoint exists
 *    but is not readable right now.
 * `null` in the hook = the first fetch is still running.
 *
 * All three non-live states render nothing (no empty scaffold, no threat
 * display) — but they stay distinguishable at the seam so a later diagnostics
 * view does not have to guess.
 *
 * `parseCurrentAffairs`/`fetchCurrentAffairs` are pure/seam functions (no DOM,
 * no timers) → unit-testable without a live backend.
 *
 * **Auth note (contract deviation, deliberate):** the F5 order specified a
 * `Bearer` header, but EVERY existing call in this frontend authenticates with
 * `X-Hoshi-Token` (see `api/config.ts` and all of `api/*.ts`). Following the
 * codebase idiom is the only way this call works at all with the current
 * backend auth wall, so `X-Hoshi-Token` it is — flagged in RESULT.md.
 */

/** Wire enum of the endpoint's honesty flag. */
export type CurrentAffairsFreshness = 'FRESH' | 'STALE' | 'EMPTY' | 'UNAVAILABLE';

const FRESHNESS_VALUES: readonly string[] = ['FRESH', 'STALE', 'EMPTY', 'UNAVAILABLE'];

/** One headline. Free-text fields are BACKEND/feed data — never translated. */
export interface CurrentAffairsItem {
  id: string;
  /**
   * Feed identity as delivered (e.g. `TAGESSCHAU`). Rendered RAW: a source name
   * is a proper noun, the same rule the home tiles apply to HA room names.
   */
  source: string;
  title: string;
  /** Feed teaser; `null` when the feed carries none (never invented).
      Wire name is `snippet` (CurrentAffairsPort contract) — FE-internal name kept. */
  feedSnippet: string | null;
  /**
   * Feed-owner attribution/license note (e.g. "heise online · RSS: …"),
   * BACKEND/feed data like `source` — never translated. `null` when absent
   * (never invented); rendered as its own discreet line, see
   * `CurrentAffairsTile.tsx`.
   */
  attribution: string | null;
  /** Absolute article URL — only `http(s)` survives parsing (see below). */
  canonicalUrl: string;
  /** `publishedAt` as epoch ms. */
  publishedAtMs: number;
  /** `fetchedAt` as epoch ms; `null` when unreadable (the UI does not use it). */
  fetchedAtMs: number | null;
}

export interface CurrentAffairs {
  items: CurrentAffairsItem[];
  /** `observedAt` — when this ANSWER was assembled. NOT the "Stand" line. */
  observedAtMs: number | null;
  /**
   * `lastSuccessfulRefreshAt` — when the feed was last really pulled. THIS is
   * the "Stand HH:MM" line; `observedAt` would be a fresh timestamp on every
   * poll and would therefore lie about staleness.
   */
  lastSuccessfulRefreshAtMs: number | null;
  freshness: CurrentAffairsFreshness;
}

export type CurrentAffairsState =
  | { kind: 'live'; data: CurrentAffairs }
  | { kind: 'off' }
  | { kind: 'unreachable' };

/**
 * How many headlines the window asks the backend for. The window itself shows
 * at most `CURRENT_AFFAIRS_WINDOW_COUNT` (3, see the tile); the rest becomes visible
 * on the inline "mehr" expansion, which is the full-list view (see the tile's
 * KDoc for why that is an expansion and not a `/lagebild` route).
 */
export const CURRENT_AFFAIRS_LIMIT = 20;

/**
 * Poll cadence — the SAME cadence as {@link ./useWeatherToday.ts} (~10 min).
 * News feeds and weather are the same class of tile data here: slow-moving,
 * externally refreshed, read on a display that stays up for hours. No own
 * interval was invented for this window.
 */
export const CURRENT_AFFAIRS_POLL_MS = 10 * 60 * 1000;

/** ISO instant → epoch ms; anything unreadable (or `null`) becomes `null`. */
function parseInstant(raw: unknown): number | null {
  if (typeof raw !== 'string' || raw === '') return null;
  const ms = Date.parse(raw);
  return Number.isNaN(ms) ? null : ms;
}

/** Non-empty string or `null` — used for every free-text wire field. */
function parseText(raw: unknown): string | null {
  return typeof raw === 'string' && raw.trim() !== '' ? raw : null;
}

/**
 * Only absolute `http(s)` URLs pass. The value ends up in an `href` that opens
 * in a new tab, so a `javascript:`/`data:` value from a compromised feed must
 * never reach the DOM — dropping the whole item is the honest answer.
 */
function parseUrl(raw: unknown): string | null {
  const text = parseText(raw);
  if (text === null) return null;
  return /^https?:\/\//i.test(text) ? text : null;
}

/**
 * Drops every item that violates the contract instead of rendering a half one
 * (same rule as `parseHourlyPoints` in useWeatherToday.ts). Mandatory:
 * `id`, `source`, `title`, a usable `canonicalUrl` and a readable `publishedAt`.
 */
export function parseCurrentAffairsItems(raw: unknown[]): CurrentAffairsItem[] {
  return raw.flatMap((entry) => {
    if (!entry || typeof entry !== 'object') return [];
    const e = entry as Record<string, unknown>;
    const id = parseText(e.id);
    const source = parseText(e.source);
    const title = parseText(e.title);
    const canonicalUrl = parseUrl(e.canonicalUrl);
    const publishedAtMs = parseInstant(e.publishedAt);
    if (id === null || source === null || title === null) return [];
    if (canonicalUrl === null || publishedAtMs === null) return [];
    return [
      {
        id,
        source,
        title,
        feedSnippet: parseText(e.snippet),
        attribution: parseText(e.attribution),
        canonicalUrl,
        publishedAtMs,
        fetchedAtMs: parseInstant(e.fetchedAt),
      },
    ];
  });
}

/**
 * Validates the wire answer against `{items[], freshness}`. A missing/unknown
 * `freshness` invalidates the whole answer (`null` → the caller reports
 * `unreachable`): without that flag we cannot tell "nothing today" from
 * "feed broken", and guessing would produce exactly the threat display this
 * window is supposed to avoid.
 */
export function parseCurrentAffairs(body: unknown): CurrentAffairs | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  if (typeof b.freshness !== 'string' || !FRESHNESS_VALUES.includes(b.freshness)) return null;
  if (!Array.isArray(b.items)) return null;
  return {
    items: parseCurrentAffairsItems(b.items),
    observedAtMs: parseInstant(b.observedAt),
    lastSuccessfulRefreshAtMs: parseInstant(b.lastSuccessfulRefreshAt),
    freshness: b.freshness as CurrentAffairsFreshness,
  };
}

/**
 * Fetch with the honest state split: 404 = feature off, anything else that
 * fails = `unreachable`. Token goes as `X-Hoshi-Token` (see the file KDoc).
 */
export async function fetchCurrentAffairs(
  signal?: AbortSignal,
  limit = CURRENT_AFFAIRS_LIMIT,
): Promise<CurrentAffairsState> {
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
    const res = await fetch(`${API_BASE}/api/v1/currentaffairs/today?limit=${limit}`, {
      headers,
      signal,
    });
    if (res.status === 404) return { kind: 'off' };
    if (!res.ok) return { kind: 'unreachable' };
    const body: unknown = await res.json().catch(() => null);
    const data = parseCurrentAffairs(body);
    return data ? { kind: 'live', data } : { kind: 'unreachable' };
  } catch {
    return { kind: 'unreachable' }; // network error/abort → never invented news
  }
}

/**
 * Polls `GET /api/v1/currentaffairs/today` at the weather cadence
 * ({@link CURRENT_AFFAIRS_POLL_MS}). `null` = the first fetch is running.
 *
 * [enabled] is the display switch of the window (settings → "Zuhause-Kacheln",
 * persisted by `hooks/useSettings.ts#useHomeTiles`). Switched OFF there is no
 * initial fetch and no interval AT ALL: an invisible window must not cost a
 * request every ten minutes. The hook then reports `null` — the same value it
 * reports before the first answer — so no stale headline survives a toggle,
 * and switching it back on starts from an honest empty state.
 */
export function useCurrentAffairs(
  intervalMs = CURRENT_AFFAIRS_POLL_MS,
  enabled = true,
): CurrentAffairsState | null {
  const [state, setState] = useState<CurrentAffairsState | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    // Switched off ⇒ no fetch, no timer. Nothing to clean up either.
    if (!enabled) return;
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const next = await fetchCurrentAffairs(controller.signal);
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
  }, [intervalMs, enabled]);

  return enabled ? state : null;
}
