import { useEffect, useRef, useState } from 'react';
import { fetchCrew, type CrewMember } from '../api/crew';
import { StarGlyph } from './icons';

interface Props {
  open: boolean;
  members: CrewMember[];
  loading?: boolean;
  error?: string | null;
  onClose: () => void;
}

/**
 * **CrewOverlay** — das versteckte "Stellar Bloom"-Crew-Reveal (Easter-Egg).
 *
 * Bewusst KEIN Dauer-Splash (das 0.5-Design verbat einen permanenten Banner) —
 * sondern ein dezenter, schliessbarer Overlay, der nur auf eine absichtliche
 * Geste hin aufgeht (7× auf das 星 oben rechts ODER der Konami-Code; die Geste
 * lebt in {@link TopNav}). Listet pro Mitglied name · role · mantra.
 *
 * Prop-getrieben (kein Hook/Netz hier) → via `renderToStaticMarkup` testbar; den
 * Live-Fetch verdrahtet {@link CrewOverlayLive}. Ein-/Austritt ueber die
 * `is-open`-Klasse; reduced-motion respektiert die globale Regel in index.css.
 * Esc und ein Klick auf den abgedunkelten Hintergrund schliessen.
 */
export function CrewOverlay({ open, members, loading, error, onClose }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  return (
    <div
      className={`crew-overlay ${open ? 'is-open' : ''}`}
      onClick={onClose}
      aria-hidden={!open}
    >
      <aside
        className="crew"
        role="dialog"
        aria-modal="true"
        aria-label="Die Crew"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="crew__head">
          <h2 className="crew__title">
            <span className="crew__star" aria-hidden="true">
              <StarGlyph />
            </span>
            Stellar Bloom — die Crew
          </h2>
          <button
            ref={closeRef}
            type="button"
            className="crew__close"
            onClick={onClose}
            aria-label="Crew schließen"
          >
            ✕
          </button>
        </header>

        <p className="crew__motto">warm. lokal. wach.</p>

        {loading && members.length === 0 && <p className="crew__note">lädt…</p>}
        {error && (
          <p className="crew__note" role="alert">
            {error}
          </p>
        )}

        <ul className="crew__list">
          {members.map((m) => (
            <li className="crew__member" key={m.name}>
              <div className="crew__memhead">
                <span className="crew__name">{m.name}</span>
                <span className="crew__role">{m.role}</span>
              </div>
              <p className="crew__mantra">{m.mantra}</p>
            </li>
          ))}
        </ul>

        <p className="crew__foot">captain: andi · 流れ星</p>
      </aside>
    </div>
  );
}

/**
 * Live-Container: holt `GET /api/v1/crew`, sobald der Overlay zum ersten Mal
 * aufgeht (oeffentlicher Endpoint, kein Token noetig). Fehler werden ehrlich
 * gezeigt statt verschluckt; ein laufender Fetch wird beim Schliessen/Unmount
 * abgebrochen.
 */
export function CrewOverlayLive({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [members, setMembers] = useState<CrewMember[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const loadedRef = useRef(false);

  useEffect(() => {
    // Nur beim ersten Oeffnen laden (Roster ist statisch → kein Re-Fetch).
    if (!open || loadedRef.current) return;
    loadedRef.current = true;
    const ctrl = new AbortController();
    setLoading(true);
    setError(null);
    fetchCrew(ctrl.signal)
      .then((list) => setMembers(list))
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return;
        loadedRef.current = false; // erneuter Versuch beim naechsten Oeffnen
        setError(e instanceof Error ? e.message : 'Crew konnte nicht laden.');
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
    return () => ctrl.abort();
  }, [open]);

  return (
    <CrewOverlay open={open} members={members} loading={loading} error={error} onClose={onClose} />
  );
}
