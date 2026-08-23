/**
 * **startVisiblePolling** — ein `setInterval`, das pausiert, solange das Display
 * dunkel bzw. der Tab verdeckt ist, und beim Sichtbarwerden SOFORT einmal
 * nachholt.
 *
 * ## Warum
 * Bei dunklem Display lief die FE mit ~46.900 Requests/Tag weiter — jeder
 * Poller tickte stur durch, obwohl niemand hinsah. Das Gate schneidet den
 * Leerlauf weg, ohne eine einzige Poll-Frequenz zu ändern: sichtbar pollt alles
 * exakt wie vorher.
 *
 * ## Die Falle, in die das hier schon einmal gelaufen ist (Live-Befund 2026-07-02)
 * Ein früheres Gate saß IM tick (`if (document.hidden) return;`) und blockierte
 * damit auch den Initial-Fetch. Unter Chromes Window-Occlusion meldet der
 * aktive Tab `hidden`, sobald das Fenster verdeckt ist — die Timer-Zeile
 * erschien live nie, weil der erste Fetch nie durchkam.
 *
 * Darum gatet dieser Helfer **nur das Intervall, nie den Fetch selbst**:
 *  - Der Initial-Fetch bleibt beim Aufrufer und feuert IMMER, auch hidden.
 *  - `hidden` stoppt nur das Nachtakten.
 *  - `visible` holt sofort einen frischen Stand und startet das Intervall neu.
 *
 * Ein verdecktes Fenster zeigt also weiterhin sofort Daten; es hört nur auf
 * nachzufragen, bis es wieder zu sehen ist.
 *
 * ## Ehrlichkeit bleibt
 * Der Helfer fälscht nichts: er unterdrückt Requests, er erfindet keine
 * Aktualisierungen. Stand-Marker (`lastChecked` & Co.) zeigen weiterhin das
 * echte Alter der Daten — pausiert heißt sichtbar älter, nicht heimlich frisch.
 *
 * ## Bewusst NICHT hier verdrahtet
 * `useFiredItems` ist der Weg, auf dem ein Wecker klingelt. Ein verdecktes
 * Fenster darf keinen Alarm verschlucken — dieser Hook pollt weiter, immer.
 *
 * @param tick        was pro Intervall-Tick laufen soll (der Aufrufer ruft es
 *                    für den Initial-Fetch selbst schon einmal auf)
 * @param intervalMs  unveränderte Poll-Frequenz des Aufrufers
 * @returns Cleanup — Intervall stoppen und Listener abmelden
 */
export function startVisiblePolling(tick: () => void, intervalMs: number): () => void {
  // Kein document (Node/SSR-Kontext): dann eben stur takten wie bisher.
  if (typeof document === 'undefined') {
    const id = setInterval(tick, intervalMs);
    return () => clearInterval(id);
  }

  let intervalId: number | null = null;
  // Nur ECHTE hidden->visible-Wechsel holen nach. Ohne dieses Flag wuerde jedes
  // visibilitychange-Event im sichtbaren Zustand einen Extra-Fetch ausloesen.
  let paused = document.hidden;

  const stop = (): void => {
    if (intervalId !== null) {
      window.clearInterval(intervalId);
      intervalId = null;
    }
  };
  const start = (): void => {
    if (intervalId === null) intervalId = window.setInterval(tick, intervalMs);
  };

  const onVisibilityChange = (): void => {
    if (document.hidden) {
      paused = true;
      stop();
      return;
    }
    if (!paused) return; // war schon sichtbar — es gibt nichts nachzuholen
    // Sichtbar geworden: erst frisch nachholen, dann wieder takten. Ohne das
    // Sofort-Nachholen stünde bis zum nächsten regulären Tick ein alter Stand.
    paused = false;
    tick();
    start();
  };

  if (!document.hidden) start();
  document.addEventListener('visibilitychange', onVisibilityChange);

  return () => {
    stop();
    document.removeEventListener('visibilitychange', onVisibilityChange);
  };
}
