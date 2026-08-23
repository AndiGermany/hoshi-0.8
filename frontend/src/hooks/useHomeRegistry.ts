import { useEffect, useRef, useState } from 'react';
import { fetchHomeRegistry, type HomeRegistryState } from '../api/homeRegistry';
import { startVisiblePolling } from './visiblePolling';

/**
 * Pollt `GET /api/v1/home/registry` sanft (Default 5 min — Räume/Geräte
 * ändern sich selten, Muster {@link ./useWeatherToday.ts}). Die gemeinsame
 * Quelle für alles, was die Registry AUSSERHALB des Räume-Reiters braucht:
 * die Zuhause-Kacheln im Home-Reiter ({@link ../components/IdleFace.tsx#IdleFaceLive})
 * und die „nur wenn die Quelle real ist"-Schalter im SettingsPanel
 * (`HomeTilesSection`). {@link ../views/RaeumeView.tsx#RaeumeViewLive} pollt
 * für den Räume-Reiter weiter UNABHÄNGIG — kein gemeinsamer Store, dasselbe
 * Prinzip wie bei jedem anderen Server-Status-Hook dieser App
 * (useHealth/useOpsStatus/useWeatherToday/useShoppingList: jeder Konsument
 * pollt für sich, kein Context/Cache dazwischen).
 *
 * `null` = erster Fetch läuft (Muster {@link useWeatherToday}) — die
 * Konsumenten zeigen dafür ihre je eigene ehrliche Lade-/Leer-Ansicht.
 */
export function useHomeRegistry(intervalMs = 5 * 60 * 1000): HomeRegistryState | null {
  const [state, setState] = useState<HomeRegistryState | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const next = await fetchHomeRegistry(controller.signal);
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
