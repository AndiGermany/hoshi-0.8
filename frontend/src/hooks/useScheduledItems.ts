import { useCallback, useEffect, useRef, useState } from 'react';
import { API_BASE, TOKEN } from '../api/config';

/**
 * Sichtbarkeits-Naht der Wecker-Lane (Cowork-Befund: laufende Timer/Wecker waren
 * UNSICHTBAR — der Store persistiert, aber die UI zeigte nichts; der Voice-PE-
 * Fehler): pollt `GET /api/v1/scheduled` (~15s) und liefert die AKTIVEN (noch
 * nicht gefeuerten) Items für die ruhige Timer-Zeile über der Compose-Bar.
 * Wire-Format: `[{id, kind, label?, dueAtEpochMs}]`, `label` fehlt bei null,
 * sortiert nach Fälligkeit; leer ⇒ `[]`.
 *
 * Semantik (bewusst anders als useFiredItems):
 *  - Der Endpoint ist READ-ONLY (kein drain) — jeder Poll ERSETZT den Stand
 *    (ein stornierter Timer verschwindet), statt zu mergen. Gleicher Stand ⇒
 *    dieselbe Referenz zurück ({@link sameSchedule}) → kein React-Re-render.
 *  - BEWUSST KEIN visibility-Gate (Live-Befund 2026-07-02): Chromes Window-
 *    Occlusion meldet auch den aktiven Tab als `hidden`, sobald das Fenster
 *    verdeckt ist — das Gate blockierte dann JEDEN Tick inkl. Initial-Fetch
 *    und die Zeile erschien nie. Gepollt wird immer (wie useOpsStatus).
 *  - Ehrlichkeits-/Lärm-Achse wie useFiredItems: 401/404/5xx/Netzfehler → `[]`
 *    (still — die Zeile verschwindet, kein roter Fehler). Token als
 *    `X-Hoshi-Token` (api/config).
 *  - `nowMs` tickt minütlich → der clientseitige Countdown der Zeile rendert
 *    frisch, auch wenn sich die Items selbst nicht ändern.
 *
 * `parseScheduledItems`/`fetchScheduledItems`/`sameSchedule`/`scheduledLine`
 * sind pure/seam-Funktionen (kein DOM, keine Timer) → ohne Live-Backend
 * unit-testbar; der Hook verdrahtet nur Polling + Minuten-Tick + Cleanup.
 */

export type ScheduledKind = 'TIMER' | 'ALARM' | 'REMINDER';

export interface ScheduledItem {
  id: string;
  kind: ScheduledKind;
  label?: string;
  dueAtEpochMs: number;
  /**
   * Restsekunden gegen die SERVER-Uhr (BE-Contract, additiv, immer da; fällig ⇒
   * 0, nie negativ). Bewusst NICHT in {@link sameSchedule} verglichen — der
   * clientseitige Countdown rechnet weiter aus `dueAtEpochMs` gegen `nowMs`, das
   * neue Feld erzeugt also keinen Re-render-Churn. Fehlt bei alt-Backends.
   */
  remainingSeconds?: number;
}

const KINDS: readonly ScheduledKind[] = ['TIMER', 'ALARM', 'REMINDER'];

/** Kind-ehrliche Wörter für die Zeile: TIMER→„Timer", ALARM→„Wecker". */
export const KIND_WORD: Record<ScheduledKind, { one: string; many: string }> = {
  TIMER: { one: 'Timer', many: 'Timer' },
  ALARM: { one: 'Wecker', many: 'Wecker' },
  REMINDER: { one: 'Erinnerung', many: 'Erinnerungen' },
};

/**
 * Validiert die Wire-Antwort. Kein Array / Müll-Einträge / fehlende id →
 * still verworfen (nie eine kaputte Zeile rendern). Unbekannte `kind` fällt
 * auf TIMER zurück. Ergebnis aufsteigend nach Fälligkeit (der Server sortiert
 * schon — hier nochmal, damit „der nächste zuerst" ein FE-Invariant ist).
 */
export function parseScheduledItems(body: unknown): ScheduledItem[] {
  if (!Array.isArray(body)) return [];
  return (body as unknown[])
    .flatMap((raw) => {
      if (!raw || typeof raw !== 'object') return [];
      const r = raw as Record<string, unknown>;
      if (typeof r.id !== 'string' || r.id.length === 0) return [];
      const kind = KINDS.includes(r.kind as ScheduledKind) ? (r.kind as ScheduledKind) : 'TIMER';
      const item: ScheduledItem = {
        id: r.id,
        kind,
        dueAtEpochMs: typeof r.dueAtEpochMs === 'number' ? r.dueAtEpochMs : 0,
      };
      if (typeof r.label === 'string' && r.label.trim().length > 0) item.label = r.label;
      // remainingSeconds: additiv, nie negativ (fällt sonst auf 0). Rein informativ;
      // die Zeile rechnet den Countdown weiter aus dueAtEpochMs (nowMs-Tick).
      if (typeof r.remainingSeconds === 'number' && Number.isFinite(r.remainingSeconds)) {
        item.remainingSeconds = Math.max(0, r.remainingSeconds);
      }
      return [item];
    })
    .sort((a, b) => a.dueAtEpochMs - b.dueAtEpochMs);
}

/** Token-Header wie useFiredItems (X-Hoshi-Token, nur wenn gesetzt). */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/**
 * Best-effort-Abruf: jeder Misserfolg (401/404/5xx/Netz/Abbruch) → `[]`.
 * Token geht als `X-Hoshi-Token` (gleicher Mechanismus wie useFiredItems).
 */
export async function fetchScheduledItems(signal?: AbortSignal): Promise<ScheduledItem[]> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/scheduled`, { headers: authHeaders(), signal });
    if (!res.ok) return [];
    const body: unknown = await res.json().catch(() => null);
    return parseScheduledItems(body);
  } catch {
    return [];
  }
}

/**
 * Löscht EIN aktives Item: `DELETE /api/v1/scheduled/{id}` (id URL-encodiert).
 * BE-Contract: 204 entfernt, 404 unbekannt. Beides ist für uns „weg" (true) —
 * ein 404 heißt nur, dass es schon fort ist. `false` nur bei echtem Misserfolg
 * (401/5xx/Netz), damit der Aufrufer die optimistische Ausblendung zurücknehmen
 * kann (der nächste Poll zeigt es dann wieder — nie stilles Verschlucken).
 */
export async function deleteScheduledItem(id: string): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/scheduled/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    return res.ok || res.status === 404;
  } catch {
    return false;
  }
}

/**
 * Löscht ALLE aktiven Items: `DELETE /api/v1/scheduled` → `{count:N}` (BE-Contract:
 * rührt gefeuert-unbestätigte Items NICHT an). Liefert die Zahl der entfernten
 * Items; jeder Misserfolg → `0` (best-effort; der Poll reconciled ohnehin).
 */
export async function cancelAllScheduled(): Promise<number> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/scheduled`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    if (!res.ok) return 0;
    const body: unknown = await res.json().catch(() => null);
    const count = (body as { count?: unknown } | null)?.count;
    return typeof count === 'number' && Number.isFinite(count) ? count : 0;
  } catch {
    return 0;
  }
}

/** Gleicher Stand? Dann behält der Hook die alte Referenz (kein Re-render). */
export function sameSchedule(current: ScheduledItem[], next: ScheduledItem[]): boolean {
  if (current.length !== next.length) return false;
  return current.every((c, i) => {
    const n = next[i];
    return (
      c.id === n.id &&
      c.kind === n.kind &&
      c.label === n.label &&
      c.dueAtEpochMs === n.dueAtEpochMs
    );
  });
}

/** Restdauer human: `unter 1 min`, `44 min`, `1 h`, `1 h 12 min` (aufgerundet). */
export function fmtRemaining(ms: number): string {
  if (ms < 60_000) return 'unter 1 min';
  const totalMin = Math.ceil(ms / 60_000);
  if (totalMin < 60) return `${totalMin} min`;
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

/**
 * Die ruhige Zeile (reiner Text — ein Glyph rendert ggf. die Komponente):
 *  - keine Items → `null` (NICHTS rendern, kein Lärm),
 *  - eines → `Timer · noch 44 min` (kind-ehrlich: ALARM → „Wecker"),
 *  - mehrere → `2 Timer · nächster in 12 min` (gemischte Kinds → `Timer/Wecker`).
 * Countdown clientseitig aus `dueAtEpochMs` gegen `nowMs` (Minuten-Tick des Hooks).
 */
export function scheduledLine(items: ScheduledItem[], nowMs: number): string | null {
  if (items.length === 0) return null;
  const next = items.reduce((a, b) => (b.dueAtEpochMs < a.dueAtEpochMs ? b : a));
  const remaining = fmtRemaining(next.dueAtEpochMs - nowMs);
  if (items.length === 1) return `${KIND_WORD[next.kind].one} · noch ${remaining}`;
  const kinds = KINDS.filter((k) => items.some((i) => i.kind === k));
  const word = kinds.length === 1 ? KIND_WORD[kinds[0]].many : kinds.map((k) => KIND_WORD[k].one).join('/');
  return `${items.length} ${word} · nächster in ${remaining}`;
}

/** Fälligkeit als lokale Uhrzeit „HH:MM" (de-DE, 24h) — für Wecker. */
export function dueClock(dueAtEpochMs: number): string {
  return new Date(dueAtEpochMs).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
}

/**
 * Die primäre Zeit-Angabe EINER Verwaltungs-Zeile (kind-ehrlich):
 *  - ALARM → „um 07:00" (die Weck-Uhrzeit; eine Restzeit wäre für einen Wecker
 *    weniger greifbar),
 *  - TIMER/REMINDER → „noch 12 min" (Restdauer, aufgerundet, nie negativ).
 * Rein aus `dueAtEpochMs` gegen `nowMs` gerechnet (Minuten-Tick des Hooks).
 */
export function scheduledItemPrimary(item: ScheduledItem, nowMs: number): string {
  if (item.kind === 'ALARM') return `um ${dueClock(item.dueAtEpochMs)}`;
  return `noch ${fmtRemaining(Math.max(0, item.dueAtEpochMs - nowMs))}`;
}

export interface ScheduledItemsState {
  /** Aktive Items, nach Fälligkeit sortiert — leer = keine Zeile. */
  items: ScheduledItem[];
  /** Minütlich tickende Uhr für den clientseitigen Countdown. */
  nowMs: number;
  /**
   * EIN Item stornieren (`DELETE …/{id}`). Optimistisch: blendet es sofort aus
   * (`removedRef` hält ein Wieder-Auftauchen im nächsten Poll weg, bis der Server
   * nachzieht). Scheitert das DELETE echt (401/5xx/Netz), wird die Ausblendung
   * zurückgenommen → der nächste Poll zeigt es wieder (nie stilles Verschlucken).
   */
  remove: (id: string) => void;
  /** ALLE stornieren (`DELETE …/scheduled`). Optimistisch leeren; der Poll reconciled. */
  removeAll: () => void;
}

/** Pollt `GET /api/v1/scheduled` (~15s, unabhängig von der Tab-Sichtbarkeit) + Minuten-Tick. */
export function useScheduledItems(intervalMs = 15_000): ScheduledItemsState {
  const [items, setItems] = useState<ScheduledItem[]>([]);
  const [nowMs, setNowMs] = useState<number>(() => Date.now());
  const aliveRef = useRef(true);
  // Optimistisch stornierte ids: bis der Server das DELETE verdaut hat, darf ein
  // Poll sie nicht als „noch aktiv" zurückbringen. Aufgeräumt, sobald der Server
  // sie nicht mehr liefert (dann ist das DELETE angekommen) — oder wenn das DELETE
  // echt scheiterte (dann bewusst gelöscht, damit der Poll das Item wieder zeigt).
  const removedRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const next = await fetchScheduledItems(controller.signal);
      if (!aliveRef.current) return;
      // Optimistisch-storniertes filtern; aufräumen, was der Server eh nicht mehr nennt.
      const reported = new Set(next.map((i) => i.id));
      for (const id of [...removedRef.current]) {
        if (!reported.has(id)) removedRef.current.delete(id);
      }
      const visible = next.filter((i) => !removedRef.current.has(i.id));
      // Ersetzen statt mergen (read-only-Quelle); gleicher Stand → alte Referenz.
      setItems((cur) => (sameSchedule(cur, visible) ? cur : visible));
    };

    void tick();
    const id = window.setInterval(() => void tick(), intervalMs);
    return () => {
      aliveRef.current = false;
      controller.abort();
      window.clearInterval(id);
    };
  }, [intervalMs]);

  // Minuten-Tick: der Countdown rendert frisch, auch ohne neue Poll-Daten
  // (z.B. Poll-Fehler bei stehendem Stand). Reiner Anzeige-Tick, kein Netz.
  useEffect(() => {
    const id = window.setInterval(() => setNowMs(Date.now()), 60_000);
    return () => window.clearInterval(id);
  }, []);

  const remove = useCallback((id: string) => {
    removedRef.current.add(id);
    setItems((cur) => cur.filter((i) => i.id !== id));
    void deleteScheduledItem(id).then((ok) => {
      // Echt gescheitert (401/5xx/Netz): Ausblendung zurücknehmen → der nächste
      // Poll zeigt das Item wieder (ehrlich, statt es fälschlich „gelöscht" zu lassen).
      if (!ok) removedRef.current.delete(id);
    });
  }, []);

  const removeAll = useCallback(() => {
    // Optimistisch leeren, aber NICHT in removedRef pinnen: scheitert das Bulk-
    // DELETE, zeigt der nächste Poll (≤15s) die Items ehrlich wieder — statt sie
    // stumm für immer auszublenden. Bei Erfolg liefert der Poll [] und es bleibt leer.
    setItems([]);
    void cancelAllScheduled();
  }, []);

  return { items, nowMs, remove, removeAll };
}
