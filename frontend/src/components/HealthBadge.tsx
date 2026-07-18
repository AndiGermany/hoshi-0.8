import { useEffect, useRef, useState } from 'react';
import { useHealth, type HealthState } from '../hooks/useHealth';

const LABEL: Record<HealthState, string> = {
  unknown: 'unbekannt',
  up: 'online',
  down: 'offline',
};

/**
 * Ehrliches Health-Indicator: pollt `GET /api/health`. Unbekannt = grau, nie Fake-grün.
 *
 * Dezent statt Dauertext: kein eigenes „Backend: online"-Wortband mehr in der
 * Nav — nur ein stiller Punkt (`--text-4`, Farbe je Zustand nur am Dot), der
 * Zustand + Zeitpunkt trägt der native Tooltip (`title`). Ein sr-only-Text
 * hält den Zustand für Screenreader lesbar, bewusst OHNE aria-live: die
 * Uhrzeit im Tooltip wechselt bei jedem 5s-Poll, ein Live-Announce würde also
 * ständig vorlesen, ohne dass sich der Zustand wirklich geändert hat.
 */
export function HealthBadge() {
  const { state, lastChecked } = useHealth();
  const when = lastChecked ? new Date(lastChecked).toLocaleTimeString('de-DE') : '—';
  const summary = `Backend ${LABEL[state]}`;

  // Micro-Motion: bei JEDEM echten Zustandswechsel einmalig „pingen" (Dot-Puls).
  // Farbe/Schein gleiten via CSS-Transition; der Ping ist die eine Anerkennung
  // „etwas hat sich geändert". Initial (unknown == unknown) feuert er nicht.
  const prevRef = useRef<HealthState>(state);
  const [ping, setPing] = useState(false);
  useEffect(() => {
    if (prevRef.current === state) return;
    prevRef.current = state;
    setPing(true);
    const id = setTimeout(() => setPing(false), 600);
    return () => clearTimeout(id);
  }, [state]);

  return (
    <span
      className={`badge badge--dot badge--${state} ${ping ? 'badge--ping' : ''}`}
      title={`${summary} · zuletzt geprüft ${when}`}
    >
      <span className="badge__dot" aria-hidden="true" />
      <span className="sr-only">{summary}</span>
    </span>
  );
}
