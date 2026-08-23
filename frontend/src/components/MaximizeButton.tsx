/**
 * **MaximizeButton** — der Zugang zur Vollbild-Ansicht, im Kachelkopf.
 *
 * Eigene Datei und nicht in `MaximizeOverlay.tsx`, weil `CurrentAffairsTile.tsx`
 * ihn braucht und das Overlay seinerseits `renderableCurrentAffairs` aus
 * `CurrentAffairsTile.tsx` liest — zusammen waere das ein Modul-Ring.
 */

/**
 * Der Maximieren-Knopf, wie er in `tile__head` sitzt — an derselben Stelle wie
 * der Play-Knopf des Saugers, aus demselben Grund: die Kopfzeile ist der
 * einzige Ort einer Kachel, an dem rechts verlässlich Luft ist, und ein
 * eigener Streifen hätte Zeilen gekostet, die die flachen Stufen nicht haben.
 *
 * **Im Edit-Modus ist er weg** — nicht nur wirkungslos. `HomeStage` macht alle
 * Kachelkinder `inert`, das genügte technisch; aber ein sichtbarer Knopf, der
 * nicht reagiert, ist eine Zusage, die die Kachel gerade nicht hält. Die Regel
 * dafür steht im CSS (`.idle__tile[data-edit='true'] .idle__maxbtn`), damit sie
 * für BEIDE Kacheln gilt — die Wetter-Kachel wird inline gebaut und bekommt
 * `data-edit` erst per `cloneElement` von der Bühne, kann es also selbst gar
 * nicht lesen.
 */
export function MaximizeButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" className="idle__maxbtn" aria-label={label} title={label} onClick={onClick}>
      {/* Vier Ecken, wie sie jeder Vollbild-Knopf zeigt — gezeichnet statt als
          Zeichen (⛶ fehlt in vielen Systemschriften und wird sonst zur Box). */}
      <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" focusable="false">
        <path
          d="M2 6V2h4M14 6V2h-4M2 10v4h4M14 10v4h-4"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </button>
  );
}
