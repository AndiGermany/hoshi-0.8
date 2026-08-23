import { useEffect, useRef, useState } from 'react';
import { fetchCrew, type CrewMember } from '../api/crew';
import { Overlay } from './Overlay';
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
 *
 * The modal frame (backdrop + click-to-close, role/aria-modal, Escape,
 * autofocus, card geometry) is no longer owned here: it moved into the shared
 * {@link Overlay} shell (design 2026-08-15 §3.2). Crew is its FIRST user and
 * keeps its historic BEM roots (`crew-overlay`/`crew`) plus its markup byte for
 * byte — `test/crew.test.tsx` is the non-regression latch. Autofocus still
 * lands on the close button: the shell focuses the first focusable descendant,
 * and that is the header button below.
 */
export function CrewOverlay({ open, members, loading, error, onClose }: Props) {
  return (
    <Overlay
      open={open}
      onClose={onClose}
      label="Die Crew"
      backdropClassName="crew-overlay"
      cardClassName="crew"
    >
      <header className="crew__head">
        <h2 className="crew__title">
          <span className="crew__star" aria-hidden="true">
            <StarGlyph />
          </span>
          Stellar Bloom — die Crew
        </h2>
        <button type="button" className="crew__close" onClick={onClose} aria-label="Crew schließen">
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
    </Overlay>
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
