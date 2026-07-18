/**
 * „Senden"-Affordance der Compose-Bar — der EINE Gold-CTA.
 *
 * Andi mochte den nackten Pfeil (↑) nicht: zu beiläufig, kaum als Aktion lesbar.
 * Stattdessen eine warme, eindeutige Pille — schlichtes Paper-Plane-Glyph PLUS
 * sichtbares Wort „Senden" (im Geiste von Hoshi 0.5). So ist die Aktion benannt,
 * barrierearm und intentional, statt ein einzelnes Zeichen raten zu lassen.
 *
 * a11y: trägt immer `aria-label="Senden"`; `disabled` (leeres Feld ODER laufender
 * Stream) kommt von außen. Während des Streams (`busy`) zeigen drei ruhige Punkte
 * statt des Flugzeugs „Hoshi arbeitet" — die Pillen-Breite bleibt durch das stets
 * sichtbare Label stabil (kein Layout-Springen). Hover/Active/Focus-Ring stecken
 * in der `.compose__send`-CSS; die globale prefers-reduced-motion-Regel (`*`)
 * stellt deren Transitions automatisch ruhig.
 */

/** Schlichtes Paper-Plane-Glyph (currentColor) — der Send-Kern (lucide-Geist). */
function SendGlyph() {
  return (
    <svg
      className="compose__send-ico"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="m22 2-7 20-4-9-9-4Z" />
      <path d="M22 2 11 13" />
    </svg>
  );
}

export function SendButton({ disabled, busy }: { disabled: boolean; busy: boolean }) {
  return (
    <button
      className="compose__send"
      type="submit"
      disabled={disabled}
      aria-label="Senden"
      title="Senden (Enter)"
    >
      <span className="compose__send-ico-wrap" aria-hidden="true">
        {busy ? <span className="compose__send-dots">…</span> : <SendGlyph />}
      </span>
      <span className="compose__send-label">Senden</span>
    </button>
  );
}
