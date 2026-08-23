import { useEffect, useRef } from 'react';
import type { ReactNode, RefObject } from 'react';

/**
 * First-focus fallback: the first focusable descendant of the card. A narrow,
 * deliberate list — the shell owns no controls of its own, so anything more
 * exotic than "the close button comes first" passes {@link OverlayProps.initialFocusRef}.
 */
const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]),' +
  ' textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export interface OverlayProps {
  /**
   * Stays mounted either way — `open` only switches visibility (the `is-open`
   * class drives opacity/visibility/pointer-events, so a closed overlay is not
   * a tab trap). Same idiom as the settings drawer.
   */
  open: boolean;
  onClose: () => void;
  /** `aria-label` of the dialog card — every overlay names itself out loud. */
  label: string;
  /**
   * Class of the backdrop element; defaults to the generic `overlay`. Existing
   * overlays keep their historic BEM root so that not a single pixel moves
   * (`crew-overlay`, see {@link CrewOverlay}) — themes.css carries both names on
   * one shared rule set, so "generic" and "historic" cannot drift apart.
   */
  backdropClassName?: string;
  /** Class of the card element; defaults to the generic `overlay__card`. */
  cardClassName?: string;
  /**
   * Element that takes focus when the overlay opens. Without it the shell
   * focuses the first focusable descendant of the card — for every overlay so
   * far that is exactly the close button in the header.
   */
  initialFocusRef?: RefObject<HTMLElement>;
  children: ReactNode;
}

/**
 * **Overlay** — the ONE modal shell of the frontend, generalised out of
 * {@link CrewOverlay} (design DESIGN-widgets-settings-2026-08-15.md §3.2).
 *
 * Everything a modal owes the user lives here exactly once: a dimmed backdrop
 * that closes on click, `role="dialog"` + `aria-modal`, Escape (owned
 * exclusively while open, see the effect below), autofocus, and the card
 * geometry (`width: min(960px, 94vw); max-height: 90vh`) — the card scrolls
 * inside itself, the page underneath never does.
 *
 * Prop-driven (no hook, no network) → testable via `renderToStaticMarkup`; the
 * content is entirely the caller's, this component contributes the frame only.
 *
 * **Z-ORDER CONTRACT (fixed, see themes.css/index.css):** drawer 50 → overlay 60
 * → FiredToast 70. The alarm always wins: no overlay may ever cover a ringing
 * timer, so nothing here must climb above 60.
 */
export function Overlay({
  open,
  onClose,
  label,
  backdropClassName = 'overlay',
  cardClassName = 'overlay__card',
  initialFocusRef,
  children,
}: OverlayProps) {
  const cardRef = useRef<HTMLElement>(null);

  useEffect(() => {
    if (!open) return;
    // Autofocus: the caller's choice wins, otherwise the first focusable control
    // inside the card (the close button, by header convention).
    const target =
      initialFocusRef?.current ?? cardRef.current?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
    target?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // AN OPEN MODAL OWNS ESCAPE. Listening in the CAPTURE phase and stopping
      // propagation there is what makes `aria-modal` true for keyboard users:
      // the key never reaches a handler underneath — not even one that also sits
      // on `window`.
      //
      // The concrete accident this prevents (design §3.3/1, diagnosis §1.4/6):
      // the settings drawer has its own `window` Escape handler that closes the
      // WHOLE drawer. With both listening, one Escape mid-recording opened the
      // enrol dialog's „really cancel?" question AND unmounted the dialog under
      // it — so `cancel()`'s rollback never ran and a half-finished profile
      // stayed orphaned on the server. The overlay swallowing Escape is the fix
      // at the seam where it belongs.
      //
      // ONLY Escape is swallowed. A blanket `stopPropagation` in the capture
      // phase at `window` would cut off React's own delegated listeners (they
      // sit on the root container, i.e. deeper) and the card would go deaf to
      // typing.
      e.stopPropagation();
      onClose();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [open, onClose, initialFocusRef]);

  return (
    <div
      className={`${backdropClassName} ${open ? 'is-open' : ''}`}
      onClick={onClose}
      aria-hidden={!open}
    >
      <aside
        ref={cardRef}
        className={cardClassName}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        // The card swallows the click so that only the backdrop closes.
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </aside>
    </div>
  );
}
