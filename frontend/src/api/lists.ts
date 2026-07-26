import { API_BASE, TOKEN } from './config';

/**
 * Client für die Listen-Lane (`ListsController.kt`, Andi-JA 2026-07-08 „Listen
 * auf die Ring-1-Karte") — Muster {@link ../hooks/useScheduledItems}
 * (`fetchScheduledItems`): `GET /api/v1/lists` liefert ALLE Einträge der
 * Default-Liste („einkauf"), älteste zuerst. Strikt READ-ONLY hier — die
 * Einkaufs-Karte auf der Übersicht zeigt nur an, sie schreibt nichts.
 *
 * Wire-Format: `[{id, text, quantity, addedAtEpochMs}]` ({@link ListItemView}
 * im Backend). `quantity` ist der Dedupe-Zähler („2×" bei doppelt genanntem
 * Item), keine geparste Mengenangabe.
 *
 * Ehrlichkeits-/Lärm-Achse wie `fetchScheduledItems`: jeder Misserfolg
 * (401/404/5xx/Netz/kaputtes JSON) → `[]` — still, die Karte verschwindet
 * einfach (kein Fehler-Banner im Flur). Token als `X-Hoshi-Token`.
 */

export interface ListItem {
  id: string;
  text: string;
  quantity: number;
  addedAtEpochMs: number;
}

/** Token-Header wie `hooks/useScheduledItems.ts` — nur gesetzt, wenn ein Token konfiguriert ist. */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/**
 * Validiert die Wire-Antwort. Kein Array / Müll-Einträge / fehlende id oder
 * leerer Text → still verworfen (nie eine kaputte Zeile rendern). `quantity`
 * fehlt/ungültig ⇒ 1 (ein echtes Item ohne Zähler ist genau EIN Stück).
 * Ergebnis aufsteigend nach `addedAtEpochMs` (der Server liefert schon so —
 * hier nochmal, damit „ältestes zuerst" ein FE-Invariant ist, Muster
 * `parseScheduledItems`).
 */
export function parseListItems(body: unknown): ListItem[] {
  if (!Array.isArray(body)) return [];
  return (body as unknown[])
    .flatMap((raw) => {
      if (!raw || typeof raw !== 'object') return [];
      const r = raw as Record<string, unknown>;
      if (typeof r.id !== 'string' || r.id.length === 0) return [];
      if (typeof r.text !== 'string' || r.text.trim().length === 0) return [];
      const quantity =
        typeof r.quantity === 'number' && Number.isFinite(r.quantity) && r.quantity > 0
          ? r.quantity
          : 1;
      const addedAtEpochMs = typeof r.addedAtEpochMs === 'number' ? r.addedAtEpochMs : 0;
      return [{ id: r.id, text: r.text, quantity, addedAtEpochMs }];
    })
    .sort((a, b) => a.addedAtEpochMs - b.addedAtEpochMs);
}

/**
 * Best-effort-Abruf: jeder Misserfolg (401/404/5xx/Netz/Abbruch) → `[]`
 * (Muster `fetchScheduledItems`) — die Einkaufs-Karte verschwindet dann
 * einfach, statt einen Fehler im Flur zu zeigen.
 */
export async function fetchListItems(signal?: AbortSignal): Promise<ListItem[]> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/lists`, { headers: authHeaders(), signal });
    if (!res.ok) return [];
    const body: unknown = await res.json().catch(() => null);
    return parseListItems(body);
  } catch {
    return [];
  }
}
