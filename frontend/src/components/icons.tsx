import type { ReactNode, SVGProps } from 'react';

/**
 * **Muted Inline-SVG-Glyphs** — die 0.5-Lehre „SVG-Icons statt Emojis":
 * Emojis als UI-Controls brechen die Aoi-Designsprache (bunte Plattform-Optik,
 * uneinheitliche Größen, kein currentColor). Jedes Glyph hier ist schlicht,
 * stroke-basiert (Muster von Mic/Speaker in der Compose-Bar), erbt Farbe über
 * `currentColor` und Größe über `em` (Klasse `.glyph`, index.css).
 *
 * `className` ERSETZT die Default-Größenklasse (`glyph`) — so kann die
 * Compose-Bar weiter über `.vc-ico` sizen. Der Marker `glyph--<name>` bleibt
 * immer dran (Tests + gezieltes Styling). Alle Glyphs sind `aria-hidden`:
 * der begleitende Text/aria-label trägt die Semantik, nie das Icon allein.
 */

interface GlyphProps {
  /** Ersetzt die Default-Größenklasse `glyph` (z. B. `vc-ico` in der Compose-Bar). */
  className?: string;
}

/** Gemeinsame SVG-Hülle: 24er-Viewbox, stroke currentColor — das Compose-Bar-Muster. */
function Svg({
  name,
  className,
  children,
  ...rest
}: GlyphProps & { name: string; children: ReactNode } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      className={`${className ?? 'glyph'} glyph--${name}`}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {children}
    </svg>
  );
}

/** Mikrofon — ersetzt 🎤/🎙️ (PTT-Knopf, Mikro-Fehlerzeile, Anlern-Knopf). */
export function MicGlyph({ className }: GlyphProps) {
  return (
    <Svg name="mic" className={className}>
      <rect x="9" y="2.5" width="6" height="11" rx="3" />
      <path d="M5.5 11a6.5 6.5 0 0 0 13 0" />
      <line x1="12" y1="17.5" x2="12" y2="21" />
      <line x1="8.5" y1="21" x2="15.5" y2="21" />
    </Svg>
  );
}

/** Lautsprecher — ersetzt 🔊; `on` schaltet Wellen ↔ Mute-Kreuz. */
export function SpeakerGlyph({ on, className }: GlyphProps & { on: boolean }) {
  return (
    <Svg name={on ? 'speaker' : 'speaker-off'} className={className}>
      <path d="M4 9v6h3.5L13 19V5L7.5 9H4z" />
      {on ? (
        <>
          <path d="M16 9.5a3.5 3.5 0 0 1 0 5" />
          <path d="M18.7 7a7 7 0 0 1 0 10" />
        </>
      ) : (
        <>
          <line x1="16.5" y1="9.5" x2="21.5" y2="14.5" />
          <line x1="21.5" y1="9.5" x2="16.5" y2="14.5" />
        </>
      )}
    </Svg>
  );
}

/** Durchgestrichener Lautsprecher — ersetzt 🔇 (Deflect-Flag im Turn-Feed). */
export function MutedGlyph({ className }: GlyphProps) {
  return (
    <Svg name="muted" className={className}>
      <path d="M4 9v6h3.5L13 19V5L7.5 9H4z" />
      <line x1="16.5" y1="9.5" x2="21.5" y2="14.5" />
      <line x1="21.5" y1="9.5" x2="16.5" y2="14.5" />
    </Svg>
  );
}

/** Stoppuhr — ersetzt ⏱ (Timer-Zeilen im Scheduled-Panel). */
export function ClockGlyph({ className }: GlyphProps) {
  return (
    <Svg name="clock" className={className}>
      <circle cx="12" cy="13.5" r="7" />
      <path d="M12 10v3.5l2.5 1.5" />
      <line x1="10" y1="2.5" x2="14" y2="2.5" />
      <line x1="12" y1="2.5" x2="12" y2="6.5" />
    </Svg>
  );
}

/** Wecker — ersetzt ⏰ (Wecker-Zeile, Klingel-Banner, ALARM-Zeilen). */
export function AlarmGlyph({ className }: GlyphProps) {
  return (
    <Svg name="alarm" className={className}>
      <circle cx="12" cy="13" r="7" />
      <path d="M12 9.5V13l2.5 1.5" />
      <path d="M4.5 5.5 7 3.5" />
      <path d="M19.5 5.5 17 3.5" />
    </Svg>
  );
}

/** Glocke — ersetzt 🔔 (REMINDER-Zeilen im Scheduled-Panel). */
export function BellGlyph({ className }: GlyphProps) {
  return (
    <Svg name="bell" className={className}>
      <path d="M18 11a6 6 0 1 0-12 0c0 4-1.5 5.5-1.5 5.5h15S18 15 18 11z" />
      <path d="M10.5 20a1.7 1.7 0 0 0 3 0" />
    </Svg>
  );
}

/** Wolke — ersetzt ☁️ (Cloud-Egress: TTS/Privacy-Zeilen, Ops-Banner, Idle-Chip). */
export function CloudGlyph({ className }: GlyphProps) {
  return (
    <Svg name="cloud" className={className}>
      <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z" />
    </Svg>
  );
}

/** Schloss — ersetzt 🔒 (bleibt lokal: Privacy-Zeilen, Consent, Idle-Chip). */
export function LockGlyph({ className }: GlyphProps) {
  return (
    <Svg name="lock" className={className}>
      <rect x="5" y="11" width="14" height="9.5" rx="2" />
      <path d="M8.5 11V7.5a3.5 3.5 0 0 1 7 0V11" />
    </Svg>
  );
}

/** Warn-Dreieck — ersetzt ⚠️ (Ops-Pille, Fehler-Flag, Maskierung-aus-Zeile). */
export function WarnGlyph({ className }: GlyphProps) {
  return (
    <Svg name="warn" className={className}>
      <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="16.8" x2="12.01" y2="16.8" />
    </Svg>
  );
}

/** Vierzack-Stern — ersetzt ✦ (Stellar-Bloom-Marke im Crew-Overlay), gefüllt. */
export function StarGlyph({ className }: GlyphProps) {
  return (
    <Svg name="star" className={className}>
      <path
        d="M12 3.5 14 10l6.5 2L14 14l-2 6.5L10 14l-6.5-2L10 10z"
        fill="currentColor"
        stroke="none"
      />
    </Svg>
  );
}

/** Abspiel-Dreieck — ersetzt ▶ (Hörprobe-Knopf), gefüllt. */
export function PlayGlyph({ className }: GlyphProps) {
  return (
    <Svg name="play" className={className}>
      <path d="M8.5 5.5v13l10.5-6.5z" fill="currentColor" stroke="none" />
    </Svg>
  );
}

/**
 * Info-Kreis ("i") — ersetzt ℹ️ (Quellen-Icon an einer Recherche-Antwort:
 * Andi-Auftrag 2026-07-21, Quellen strukturiert statt als Sprech-/Anzeige-Text
 * angehängt). Nur sichtbar, wenn der Turn ECHTE strukturierte Quellen trägt —
 * s. ChatView.
 */
export function InfoGlyph({ className }: GlyphProps) {
  return (
    <Svg name="info" className={className}>
      <circle cx="12" cy="12" r="9" />
      <line x1="12" y1="11" x2="12" y2="16" />
      <circle cx="12" cy="7.5" r="1.1" fill="currentColor" stroke="none" />
    </Svg>
  );
}

/**
 * Zahnrad — die kontextuellen Settings-Anker (Wetter-Kachel/Sprecher-Chip/
 * Wecker-Banner → {@link openSettings} in App.tsx), dasselbe Glyph wie das
 * Top-Nav-Zahnrad (TopNav.tsx), nur hier als wiederverwendbare Komponente.
 */
export function GearGlyph({ className }: GlyphProps) {
  return (
    <Svg name="gear" className={className}>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </Svg>
  );
}
