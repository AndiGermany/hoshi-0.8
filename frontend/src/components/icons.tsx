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

/** Wolke — ersetzt ☁️ (Cloud-Egress: TTS/Privacy-Zeilen, Ops-Banner, Idle-Chip; auch „bedeckt"/„wechselhaft" im Jetzt-Band). */
export function CloudGlyph({ className }: GlyphProps) {
  return (
    <Svg name="cloud" className={className}>
      <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z" />
    </Svg>
  );
}

/* ---- Wetter-Glyphen fürs Jetzt-Band (IdleFace, Andi-Auftrag 26.07) ----
   Dezente, dem Rest der Datei treue Linien-Icons je Lagen-Kategorie
   ({@link ../components/IdleFace.tsx#weatherCategory}) — kein buntes
   Wetter-App-Icon-Set, sondern dieselbe muted stroke-Sprache wie AlarmGlyph/
   ClockGlyph. Die Kategorie kommt aus dem deutschen WMO-Lagen-Text des
   Backends (WeatherCodeTexts.kt), IMMER Deutsch, unabhängig von der UI-Sprache. */

/** Sonne — „klar und sonnig"/„überwiegend klar". */
export function SunGlyph({ className }: GlyphProps) {
  return (
    <Svg name="sun" className={className}>
      <circle cx="12" cy="12" r="4.3" />
      <line x1="12" y1="2.3" x2="12" y2="4.6" />
      <line x1="12" y1="19.4" x2="12" y2="21.7" />
      <line x1="2.3" y1="12" x2="4.6" y2="12" />
      <line x1="19.4" y1="12" x2="21.7" y2="12" />
      <line x1="5.1" y1="5.1" x2="6.7" y2="6.7" />
      <line x1="17.3" y1="17.3" x2="18.9" y2="18.9" />
      <line x1="5.1" y1="18.9" x2="6.7" y2="17.3" />
      <line x1="17.3" y1="6.7" x2="18.9" y2="5.1" />
    </Svg>
  );
}

/** Sonne hinter Wolke — „teilweise bewölkt". */
export function CloudSunGlyph({ className }: GlyphProps) {
  return (
    <Svg name="cloud-sun" className={className}>
      <circle cx="7.8" cy="7.3" r="2.7" />
      <line x1="7.8" y1="2.3" x2="7.8" y2="3.6" />
      <line x1="2.8" y1="7.3" x2="4.1" y2="7.3" />
      <line x1="4.1" y1="3.6" x2="5" y2="4.5" />
      <path d="M17.5 20H9a4.3 4.3 0 0 1-.6-8.55A5.6 5.6 0 0 1 19 12.4a3.8 3.8 0 0 1-1.5 7.6z" />
    </Svg>
  );
}

/** Regenwolke — „Regen"/„Nieselregen"/„Regenschauer". */
export function RainCloudGlyph({ className }: GlyphProps) {
  return (
    <Svg name="rain-cloud" className={className}>
      <path d="M18 10.3h-1.05A6.3 6.3 0 1 0 8.1 16.7h9.9a3.85 3.85 0 0 0 0-7.7z" />
      <line x1="9" y1="19.2" x2="8.1" y2="21.7" />
      <line x1="13" y1="19.2" x2="12.1" y2="21.7" />
      <line x1="17" y1="19.2" x2="16.1" y2="21.7" />
    </Svg>
  );
}

/** Schneewolke — „Schneefall"/„Schneekörner"/„Schneeschauer". */
export function SnowCloudGlyph({ className }: GlyphProps) {
  return (
    <Svg name="snow-cloud" className={className}>
      <path d="M18 10.3h-1.05A6.3 6.3 0 1 0 8.1 16.7h9.9a3.85 3.85 0 0 0 0-7.7z" />
      <line x1="9" y1="18.8" x2="9" y2="21.8" />
      <line x1="7.5" y1="20.3" x2="10.5" y2="20.3" />
      <line x1="15" y1="18.8" x2="15" y2="21.8" />
      <line x1="13.5" y1="20.3" x2="16.5" y2="20.3" />
    </Svg>
  );
}

/** Gewitterwolke — „Gewitter"/„Gewitter mit Hagel". */
export function ThunderCloudGlyph({ className }: GlyphProps) {
  return (
    <Svg name="thunder-cloud" className={className}>
      <path d="M17.5 9.8h-1.05A6.3 6.3 0 1 0 8.6 16h8a3.8 3.8 0 0 0 0.9-6.2z" />
      <path d="M13 15.3 10.3 19h2.6l-1.6 3.4" />
    </Svg>
  );
}

/** Nebel — „neblig"/„gefrierender Nebel": ruhige, gestaffelte Schichten. */
export function FogGlyph({ className }: GlyphProps) {
  return (
    <Svg name="fog" className={className}>
      <line x1="3" y1="8.5" x2="21" y2="8.5" />
      <line x1="5.5" y1="12" x2="18.5" y2="12" />
      <line x1="3" y1="15.5" x2="21" y2="15.5" />
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

/* ---- Settings category glyphs (design 2026-08-15 §3.1) ---------------------
   The drawer's entry level is a grid of seven equally sized cards, each with
   one glyph. Three categories already had an honest glyph in this file and
   reuse it — mic for "language & voice", cloud for "online & lookup" (the
   egress idiom of this house), lock for "memory & privacy". The four below
   fill the gaps in the same muted stroke language; none of them is decorative
   anywhere else, they exist for the overview cards. */

/** Palette — appearance/colour theme. */
export function PaletteGlyph({ className }: GlyphProps) {
  return (
    <Svg name="palette" className={className}>
      <path d="M12 3a9 9 0 1 0 0 18 2 2 0 0 0 1.6-3.2 2 2 0 0 1 1.6-3.2h2a3.8 3.8 0 0 0 3.8-3.8C21 6.5 17 3 12 3z" />
      <circle cx="7.8" cy="12.2" r="1.05" fill="currentColor" stroke="none" />
      <circle cx="9.6" cy="8.2" r="1.05" fill="currentColor" stroke="none" />
      <circle cx="14.4" cy="7.6" r="1.05" fill="currentColor" stroke="none" />
    </Svg>
  );
}

/** Person — personality (Hoshi's tone), not a user account. */
export function PersonGlyph({ className }: GlyphProps) {
  return (
    <Svg name="person" className={className}>
      <circle cx="12" cy="8" r="3.6" />
      <path d="M4.8 20.5a7.2 7.2 0 0 1 14.4 0" />
    </Svg>
  );
}

/** House — home & integrations (rooms, weather place, alarms, skills). */
export function HomeGlyph({ className }: GlyphProps) {
  return (
    <Svg name="home" className={className}>
      <path d="M3.5 10.8 12 3.8l8.5 7" />
      <path d="M5.6 12.4v7.8h12.8v-7.8" />
      <path d="M10 20.2v-4.6h4v4.6" />
    </Svg>
  );
}

/**
 * Bin — deleting ONE recording in the enrol overlay (design 2026-08-15 §3.3/3).
 *
 * WHY AN ICON AND NOT THE WORD: the text button `.settings__deletebtn` is
 * `flex: none` and ~118 px wide; inside a per-recording row of the 340 px drawer
 * it could not shrink and pushed every row into a panel-wide horizontal scroll
 * (§1.4/1). An icon button has a FIXED, small footprint, so the row's flexible
 * meta column always wins. The word does not disappear — it moves into
 * `aria-label`/`title`, where a screen reader and a hover still read it.
 */
export function BinGlyph({ className }: GlyphProps) {
  return (
    <Svg name="bin" className={className}>
      <line x1="4.5" y1="6.5" x2="19.5" y2="6.5" />
      <path d="M9 6.5V4.6a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v1.9" />
      <path d="M6.6 6.5l.8 12.1a1.6 1.6 0 0 0 1.6 1.5h6a1.6 1.6 0 0 0 1.6-1.5l.8-12.1" />
      <line x1="10.4" y1="10" x2="10.7" y2="17" />
      <line x1="13.6" y1="10" x2="13.3" y2="17" />
    </Svg>
  );
}

/** Chip — model & performance (pure technique, "set once and forget"). */
export function ChipGlyph({ className }: GlyphProps) {
  return (
    <Svg name="chip" className={className}>
      <rect x="7.4" y="7.4" width="9.2" height="9.2" rx="1.6" />
      <line x1="10.2" y1="3.4" x2="10.2" y2="7.4" />
      <line x1="13.8" y1="3.4" x2="13.8" y2="7.4" />
      <line x1="10.2" y1="16.6" x2="10.2" y2="20.6" />
      <line x1="13.8" y1="16.6" x2="13.8" y2="20.6" />
      <line x1="3.4" y1="10.2" x2="7.4" y2="10.2" />
      <line x1="3.4" y1="13.8" x2="7.4" y2="13.8" />
      <line x1="16.6" y1="10.2" x2="20.6" y2="10.2" />
      <line x1="16.6" y1="13.8" x2="20.6" y2="13.8" />
    </Svg>
  );
}
