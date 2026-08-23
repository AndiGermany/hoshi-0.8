import type { ReactElement } from 'react';

/**
 * **SourceBadge** — Kurs-Update (Andi-Bestellung, via die Hand, nachträglich
 * zur Ur-Order): ein LOKALES SVG-Badge je Nachrichten-Quelle statt reinem
 * Text. Bewusst KEIN Hotlinking/Import echter Anbieter-Logos (CSP +
 * lokal-first + Andi-Gate „Marken in der Distribution" — s. Rate-Stellen in
 * RESULT.md): nur ein eingefärbter Kreis mit dem Anfangsbuchstaben, in der
 * gleichen Schreibweise wie {@link SOURCE_DISPLAY_NAMES} in
 * `NewsSourcesSection.tsx` (T/h/G). Die Farben sind eine bewusst GEDÄMPFTE
 * Annäherung an den Marken-Farbeindruck, kein exaktes Marken-Farbzitat.
 *
 * **Gekoppelt an die Source-ID** (`CurrentAffairsSourceId`-Wire-Wert, z.B.
 * `"TAGESSCHAU"`), NICHT an ein String-Matching des freien `attribution`-
 * Texts — eine unbekannte/zukünftige Id liefert `null`, der Aufrufer fällt
 * dann automatisch auf Text-only zurück (Fallback laut Auftrag).
 *
 * **Größe**: `1em`/`1em` — folgt der Schriftgröße der jeweiligen
 * Sekundär-Text-Klasse (`.idle__hometilesub`/`.settings__skillname`), kein
 * hartcodierter Pixelwert, der von der einen oder anderen abdriften könnte.
 *
 * **Theme-Tauglichkeit**: die Kreisfarbe ist bewusst FEST (Marken-Identität
 * soll nicht mit dem Theme wechseln), aber der dünne Trennring nutzt
 * `var(--bg-base)` — dieselbe Karten-Hintergrundfarbe, die auch
 * `.settings__skill` trägt — damit das Badge in JEDEM der sechs
 * Szenen-Themes sauber vom Untergrund abgesetzt bleibt statt zu "schweben".
 *
 * Rein dekorativ (`aria-hidden`): der Quellenname steht ohnehin als Text
 * daneben (Meta-Zeile bzw. Checkbox-Label) — kein doppelter Screenreader-
 * Ansage-Ballast.
 */

interface SourceBadgeSpec {
  /** Erster Buchstabe, Schreibweise wie {@link SOURCE_DISPLAY_NAMES}. */
  initial: string;
  /** Gedämpfte Marken-Farb-Anmutung — s. Klassen-KDoc. */
  fill: string;
}

const SOURCE_BADGES: Record<string, SourceBadgeSpec> = {
  TAGESSCHAU: { initial: 'T', fill: '#33507a' },
  HEISE: { initial: 'h', fill: '#7d5450' },
  GOLEM: { initial: 'G', fill: '#3d5c43' },
};

export function SourceBadge({ sourceId }: { sourceId: string }): ReactElement | null {
  const spec = SOURCE_BADGES[sourceId];
  if (!spec) return null;
  return (
    <svg
      className="source-badge"
      width="1em"
      height="1em"
      viewBox="0 0 16 16"
      aria-hidden="true"
      focusable="false"
      style={{ verticalAlign: '-0.15em', flex: 'none' }}
    >
      <circle cx="8" cy="8" r="7.25" fill={spec.fill} stroke="var(--bg-base)" strokeWidth="1" />
      <text
        x="8"
        y="8.5"
        textAnchor="middle"
        dominantBaseline="middle"
        fontSize="9"
        fontWeight="700"
        fill="#f5f5f5"
      >
        {spec.initial}
      </text>
    </svg>
  );
}
