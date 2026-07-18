import { useEffect, useId, useRef, useState } from 'react';
import {
  useOpsStatus,
  type OpsStatus,
  type OpsSidecar,
  type SidecarStatus,
} from '../hooks/useOpsStatus';
import { CloudGlyph, LockGlyph, WarnGlyph } from './icons';

/**
 * Dezente Ops-Status-Pille: zeigt kompakt RAM-Druck + Sidecar-Gesundheit.
 *
 * Ton (KEIN Gold — Gold bleibt der Stimme/CTA vorbehalten):
 *  - OK       → still: nur ein kleiner Punkt in Statusfarbe, KEIN Dauertext.
 *  - WARN     → bernstein/amber + ⚠ + Text (sie fällt auf, ehrlich sichtbar).
 *  - CRITICAL → rot + ⚠ + Text + ruhiger Puls.
 *
 * WARUM statt nur „Achtung" (Cowork-Befund): Im WARN/CRITICAL-Zustand ist die
 * Pille ein echter `<button>` mit `aria-expanded` — Klick/Enter/Space klappt
 * das Panel mit den EHRLICHEN Gründen auf (RAM-`detail` + Sidecar-Liste,
 * Probleme zuerst — NUR was der Status wirklich liefert, nichts erfunden).
 * Escape und Klick außerhalb schließen. Toms Cloud-Banner („Cloud nur mit
 * Banner"): `/api/v1/ops/status` trägt die GEWÄHLTE TTS-Engine
 * (`voice.engine`/`voice.cloud`, dieselbe Wahrheit wie die Settings-Sektion,
 * b4844d0) — bei `voice.cloud===true` die ☁️-Zeile, bei einer lokalen Engine
 * (say/piper/voxtral) die ehrliche Gegenzeile (Andi-Befund 2026-07-20: die
 * Cloud-Zeile stand vorher IMMER da, auch nach einem Wechsel auf eine lokale
 * Engine — die Wahrheit kam bis dahin nur aus der Boot-Config).
 *
 * Grünes Schloss (`allLocal`): steht nur, wenn der GESAMTE Sprech-Pfad lokal
 * ist (STT + Brain + gewählte TTS-Engine) — ein zusätzlicher, ruhiger Beweis
 * OBEN DRAUF, kein Ersatz für die Voice-Zeile. Fehlt eine Voraussetzung ⇒
 * einfach nichts (neutral, kein Alarm-Pendant).
 *
 * Der stille OK-Punkt bleibt unverändert (Hover/Fokus/Tap-Reveal wie gehabt).
 * Rein prop-getrieben (kein Netz) → ohne Fetch unit-testbar (Open-Zustand per
 * `defaultExpanded`); den Hook verdrahtet {@link OpsStatusPillLive}.
 */

type Tone = 'ok' | 'warn' | 'critical';

/** Schlechtester aus Gesamtstatus UND RAM-Level bestimmt den Ton. */
function toneOf(s: OpsStatus): Tone {
  if (s.overall === 'DOWN' || s.memory.level === 'CRITICAL') return 'critical';
  if (s.overall === 'DEGRADED' || s.memory.level === 'WARN') return 'warn';
  return 'ok';
}

/** Nur Warn-/Fehlerzustände tragen Text — OK bleibt der stille Punkt. */
const TONE_LABEL: Record<Exclude<Tone, 'ok'>, string> = {
  warn: 'Ops · Achtung',
  critical: 'Ops · kritisch',
};

/** Andis Kernwunsch: RAM-Druck explizit benennen, sobald er da ist. */
function headline(s: OpsStatus, tone: Exclude<Tone, 'ok'>): string {
  if (s.memory.level === 'CRITICAL') return 'RAM kritisch';
  if (s.memory.level === 'WARN') return 'RAM-Druck';
  return TONE_LABEL[tone];
}

const SC_CLASS: Record<SidecarStatus, string> = {
  OK: 'ok',
  DEGRADED: 'degraded',
  DOWN: 'down',
};

/** Panel-Reihenfolge: die Gründe (DOWN, dann DEGRADED) zuerst, OK danach. */
const SC_RANK: Record<SidecarStatus, number> = { DOWN: 0, DEGRADED: 1, OK: 2 };
function problemsFirst(sidecars: OpsSidecar[]): OpsSidecar[] {
  return [...sidecars].sort((a, b) => SC_RANK[a.status] - SC_RANK[b.status]);
}

export function OpsStatusPill({
  status,
  defaultExpanded = false,
}: {
  status: OpsStatus | null;
  /** Initialzustand des Klick-Panels (unkontrolliert) — primär für Tests. */
  defaultExpanded?: boolean;
}) {
  const [open, setOpen] = useState(defaultExpanded);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  // Offen zählt nur im WARN/CRITICAL-Zustand — kippt der Ton auf OK zurück,
  // ist das Panel automatisch wieder das stille Hover-Reveal von heute.
  const tone = status ? toneOf(status) : 'ok';
  const expanded = tone !== 'ok' && open;

  // Klick außerhalb schließt (nur solange offen; Effekt läuft nie im SSR/Test).
  useEffect(() => {
    if (!expanded) return;
    const onPointerDown = (e: PointerEvent): void => {
      const root = rootRef.current;
      if (root && e.target instanceof Node && !root.contains(e.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [expanded]);

  // enabled:false ODER Fehler/404/Netz → der Hook liefert null → NICHTS rendern.
  if (!status) return null;

  const title =
    `Ops: Gesamt ${status.overall} · RAM ${status.memory.level}` +
    (status.memory.detail ? ` — ${status.memory.detail}` : '');

  return (
    <div
      ref={rootRef}
      className={`ops${expanded ? ' ops--open' : ''}`}
      data-tone={tone}
      onKeyDown={(e) => {
        if (e.key === 'Escape' && expanded) {
          setOpen(false);
          buttonRef.current?.focus();
        }
      }}
    >
      {tone === 'ok' ? (
        // Der stille OK-Punkt von heute — unverändert (Hover/Fokus/Tap-Reveal).
        <span
          className="badge ops__pill ops__pill--ok"
          role="button"
          tabIndex={0}
          aria-label={title}
          title={title}
        >
          <span className="badge__dot" aria-hidden="true" />
        </span>
      ) : (
        // WARN/CRITICAL: ehrlich sichtbar UND klickbar — das Panel beantwortet
        // das WARUM aus den echten Statusdaten.
        <button
          ref={buttonRef}
          type="button"
          className={`badge ops__pill ops__pill--${tone}`}
          aria-expanded={expanded}
          aria-controls={panelId}
          aria-label={title}
          title={title}
          onClick={() => setOpen((v) => !v)}
        >
          <span className="badge__dot" aria-hidden="true" />
          <span className="ops__icon" aria-hidden="true">
            <WarnGlyph />
          </span>
          {headline(status, tone)}
        </button>
      )}

      <div className="ops__panel" id={panelId} role="status" aria-live="polite">
        {/* Toms Privacy-Banner: NUR wenn das BE ehrlich cloud:true meldet (aktive
            TTS-Engine ist openai — dieselbe Wahrheitsquelle wie die
            Settings-Sektion, b4844d0). */}
        {status.voice?.cloud === true && (
          <p className="ops__cloud">
            <CloudGlyph /> Stimme kommt gerade aus der Cloud (OpenAI)
          </p>
        )}
        {/* Gegenzeile bei lokaler Engine (say/piper/voxtral): ehrlich sichtbar statt
            stiller Lücke — Andi-Befund 2026-07-20 (die Cloud-Zeile blieb vorher nach
            einem Wechsel auf eine lokale Engine fälschlich stehen). */}
        {status.voice && status.voice.cloud === false && (
          <p className="ops__voicelocal">
            <LockGlyph /> Stimme ({status.voice.engine}): läuft lokal — verlässt das Gerät nicht.
          </p>
        )}

        {/* Andis Schloss-Wunsch: GRÜN nur, wenn der GESAMTE Sprech-Pfad lokal ist
            (STT + Brain + gewählte TTS-Engine, s. OpsStatus.allLocal/BE). Fehlt eine
            Voraussetzung ⇒ einfach nichts (neutral, kein Alarm-Pendant). */}
        {status.allLocal && (
          <p className="ops__lock">
            <LockGlyph /> Alles lokal — deine Stimme verlässt das Gerät nicht. Online-Recherche nur
            nach deiner Freigabe.
          </p>
        )}

        <div className={`ops__mem ops__mem--${status.memory.level.toLowerCase()}`}>
          <span className="ops__memhead">RAM</span>
          <span className="ops__memlevel">{status.memory.level}</span>
          {status.memory.detail && <p className="ops__memdetail">{status.memory.detail}</p>}
        </div>

        {status.sidecars.length > 0 && (
          <ul className="ops__sidecars">
            {problemsFirst(status.sidecars).map((sc) => (
              <li key={sc.name} className={`ops__sc ops__sc--${SC_CLASS[sc.status]}`}>
                <span className="ops__scdot" aria-hidden="true" />
                <span className="ops__scname">{sc.name}</span>
                <span className="ops__scstatus">{sc.status}</span>
                {sc.detail && <span className="ops__scdetail">{sc.detail}</span>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/** Live-Container: verdrahtet den echten Ops-Hook (`GET /api/v1/ops/status`). */
export function OpsStatusPillLive() {
  const status = useOpsStatus();
  return <OpsStatusPill status={status} />;
}
