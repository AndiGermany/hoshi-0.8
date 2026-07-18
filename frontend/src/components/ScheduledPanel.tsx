import { useState, type ReactNode } from 'react';
import {
  KIND_WORD,
  scheduledItemPrimary,
  scheduledLine,
  type ScheduledItem,
  type ScheduledKind,
} from '../hooks/useScheduledItems';
import { AlarmGlyph, BellGlyph, ClockGlyph } from './icons';

/** Kind-ehrliches SVG-Glyph je Zeile (Uhr/Wecker/Glocke — muted, kein Emoji). */
const KIND_GLYPH: Record<ScheduledKind, ReactNode> = {
  TIMER: <ClockGlyph />,
  ALARM: <AlarmGlyph />,
  REMINDER: <BellGlyph />,
};

/**
 * **ScheduledPanel** — „Aktive Timer & Wecker" als aufklappbare, kompakte
 * Sektion direkt über der Compose-Bar (Andi-Ausbau Timer/Wecker-Verwaltung).
 *
 * WARUM HIER (statt eigener Übersicht-Kachel): Timer & Wecker werden IM Chat
 * gestellt (getippt oder per Stimme). Die Verwaltung gehört an dieselbe Stelle —
 * „Timer 10 Minuten" sagen, ihn eine Zeile höher erscheinen sehen, ihn dort auch
 * wieder wegnehmen: null Tab-Wechsel, null Kontext-Sprung. Die Übersicht ist
 * status-first/read-only (ehrliche Platzhalter) — eine mutierende Lösch-Liste
 * passt schlecht dorthin. Und wir hängen uns an den bereits laufenden Scheduled-
 * Poll ({@link useScheduledItems} in ChatView), keine zweite Poll-Quelle.
 *
 * RUHIG PER DEFAULT: eingeklappt zeigt sie exakt die bisherige leise Zeile
 * (`scheduledLine` — „2 Timer · nächster in 12 min"), nur als Knopf zum Aufklappen.
 * Kein Lärm, wenn nichts läuft: leer UND zu ⇒ rendert NICHTS. Aufgeklappt kommt
 * die Verwaltung: je Zeile Icon (Uhr/Wecker/Glocke, {@link KIND_GLYPH}), Zeit
 * (Restzeit bzw. Weck-Uhrzeit),
 * Label und ein ✕-Löschen-Knopf; darunter „alle löschen". Leer, aber offen ⇒
 * dezent „nichts aktiv".
 *
 * Rein prop-getrieben (Items + nowMs + onDelete/onDeleteAll); der Netz-/Poll-Teil
 * lebt im Hook. Der Auf/Zu-Zustand ist lokaler UI-State.
 */
export function ScheduledPanel({
  items,
  nowMs,
  onDelete,
  onDeleteAll,
}: {
  items: ScheduledItem[];
  nowMs: number;
  onDelete: (id: string) => void;
  onDeleteAll?: () => void;
}) {
  const [open, setOpen] = useState(false);

  // Leer UND eingeklappt → NICHTS rendern (kein Lärm, Konvention wie ScheduledLine).
  if (items.length === 0 && !open) return null;

  const summary = scheduledLine(items, nowMs);

  return (
    <section className="sched" aria-label="Aktive Timer und Wecker">
      <button
        type="button"
        className="sched__toggle"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        title={open ? 'Zuklappen' : 'Aufklappen — verwalten'}
      >
        <span className="sched__toggleft">
          <span className="sched__icon" aria-hidden="true">
            <ClockGlyph />
          </span>{' '}
          {summary ?? 'Aktive Timer & Wecker'}
        </span>
        <span className="sched__chevron" aria-hidden="true">
          {open ? '▾' : '▸'}
        </span>
      </button>

      {open && (
        <div className="sched__body">
          {items.length === 0 ? (
            <p className="sched__empty" role="status">
              nichts aktiv
            </p>
          ) : (
            <ul className="sched__list">
              {items.map((item) => (
                <li key={item.id} className="sched__row">
                  <span className="sched__icon" aria-hidden="true">
                    {KIND_GLYPH[item.kind]}
                  </span>
                  <span className="sched__time">{scheduledItemPrimary(item, nowMs)}</span>
                  <span className="sched__label">
                    {item.label ?? KIND_WORD[item.kind].one}
                  </span>
                  <button
                    type="button"
                    className="sched__del"
                    onClick={() => onDelete(item.id)}
                    aria-label={`${KIND_WORD[item.kind].one}${
                      item.label ? ` „${item.label}"` : ''
                    } löschen`}
                    title="Löschen"
                  >
                    ✕
                  </button>
                </li>
              ))}
            </ul>
          )}

          {items.length > 1 && onDeleteAll && (
            <button type="button" className="sched__delall" onClick={onDeleteAll}>
              alle löschen
            </button>
          )}
        </div>
      )}
    </section>
  );
}
