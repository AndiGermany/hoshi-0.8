import { useEffect, useRef, useState } from 'react';
import { fetchListItems, type ListItem } from '../api/lists';
import { startVisiblePolling } from './visiblePolling';

/**
 * Poll-Hook der Einkaufs-Karte auf der Übersicht (Andi-JA 2026-07-08 „Listen
 * auf die Ring-1-Karte") — Muster {@link ../hooks/useOpsStatus}: pollt
 * `GET /api/v1/lists` sanft (~30s, ein Haushalt ändert die Liste nicht
 * sekündlich) und liefert die aktiven Einträge, älteste zuerst.
 *
 * Best-effort wie `fetchListItems`: jeder Misserfolg liefert `[]` — die Karte
 * verschwindet dann einfach (kein Fehler-Banner im Flur, s. IdleFace).
 */
export function useShoppingList(intervalMs = 30_000): ListItem[] {
  const [items, setItems] = useState<ListItem[]>([]);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const next = await fetchListItems(controller.signal);
      if (aliveRef.current) setItems(next);
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

  return items;
}
