import { forwardRef, type CSSProperties } from 'react';

/**
 * Zustände des EINEN warmen Licht-Orbs (星). Bildet die `MicState`-Maschine +
 * `speaking` auf eine lesbare Präsenz ab:
 *   idle      = ruhiges Atmen (Sprich-Modus an) bzw. stiller Punkt
 *   listening = bloomt reaktiv auf DEINEN Mikro-Pegel (gold)
 *   thinking  = Schimmern (NICHT pegel-reaktiv — es fließt kein Audio)
 *   speaking  = bloomt reaktiv auf HOSHIS Ausgabe-Pegel (volles Gold)
 */
export type StarState = 'idle' | 'listening' | 'thinking' | 'speaking';

interface Props {
  state: StarState;
  /** Geglätteter, perzeptueller Pegel 0..1 — nur bei listening/speaking genutzt. */
  level: number;
  /** Knappes a11y-Label (kein State ausschließlich über Bewegung kodieren). */
  label: string;
}

/**
 * Der atmende Voice-Orb: EIN warmer Licht-Punkt in der Compose-Bar, der jeden
 * Sprach-Zustand lesbar macht. Drei zentriert gestapelte Schichten — `bloom`
 * (weicher Pegel-Schein), `ring` (feine Kontur) und `core` (ruhiger Kern) —
 * ersetzen den alten Emoji-Glyph + den hart springenden Ausschlag. Idle atmet,
 * Hören/Sprechen bloomt pegel-reaktiv (GLATT über `--lvl` + kurze CSS-Transitions),
 * Denken schimmert. Ein knappes, visuell verstecktes `aria-live`-Label hält den
 * Zustand auch ohne Bewegung lesbar; das gesamte Styling lebt in styles/voicebar.css.
 *
 * Der `ref` zeigt auf das `.vc-orb`-Element: {@link ChatView} schreibt den LIVE-
 * Pegel imperativ als `--lvl` direkt aufs DOM (per rAF), statt ihn 60×/s durch
 * React-State zu treiben. Das `level`-Prop bleibt der deklarative Pfad (initialer
 * Render + Gate auf echten Audiofluss); der imperative Pfad überschreibt es nur,
 * solange Audio fließt — die Pegel-Optik ist identisch, nur ohne Re-render-Last.
 */
export const VoiceStar = forwardRef<HTMLSpanElement, Props>(function VoiceStar(
  { state, level, label },
  ref,
) {
  // Pegel nur dort durchreichen, wo er etwas Wahres sagt (Audio fließt wirklich).
  const reactive = state === 'listening' || state === 'speaking';
  const lvl = reactive ? Math.max(0, Math.min(1, level)) : 0;
  const style = { '--lvl': lvl } as CSSProperties;

  return (
    <span ref={ref} className={`vc-orb vc-orb--${state}`} style={style} data-state={state}>
      <span className="vc-orb__bloom" aria-hidden="true" />
      <span className="vc-orb__ring" aria-hidden="true" />
      <span className="vc-orb__core" aria-hidden="true" />
      <span className="sr-only" role="status" aria-live="polite">
        {label}
      </span>
    </span>
  );
});
