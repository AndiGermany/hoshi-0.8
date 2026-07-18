import type { CSSProperties } from 'react';
import type { RecognizedSpeaker } from '../api/types';

// ─────────────────────────────────────────────────────────────────────────────
//  „Wer sprach"-Chip (Stimm-ERKENNUNG S3) — der dezente Beleg am Sprach-Turn,
//  wen Hoshi an der Stimme erkannt hat. Bewusst zurückhaltend + im Aoi-Token-Set.
//
//  Vera-Regel HART sichtbar: erkannt ⇒ Name in einem deterministischen, leisen
//  Namens-Akzent (aus dem Name-Hash); Gast/unsicher ⇒ „Gast" in Neutralgrau,
//  GESTRICHELT — lieber grau als eine falsche Person. Unter der Konfidenz-Schwelle
//  bindet das Backend gar keinen Namen (recognizedSpeaker=null/isGuest) → hier Gast.
// ─────────────────────────────────────────────────────────────────────────────

/** Sichtbarer Text für einen nicht (sicher) erkannten Sprecher. */
export const GUEST_LABEL = 'Gast';

/** Tooltip am Gast-Chip — macht die Vera-Regel für neugierige Mäuse lesbar. */
export const GUEST_TITLE = 'Nicht sicher erkannt — lieber Gast als die falsche Person.';

/**
 * Deterministischer, dezenter Farbton (0..359) aus dem Namen. Gleicher Name ⇒
 * immer derselbe Akzent (wiedererkennbar), ohne eine Palette pflegen zu müssen.
 * Reiner 32-bit-Rolling-Hash (FNV-ähnlich) → Modulo 360. Rein informativ; die
 * Sättigung/Helligkeit sind in der CSS fixiert, damit jeder Ton AA-lesbar bleibt.
 */
export function speakerHue(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return h % 360;
}

/** Ist dieser Sprecher als Gast/unsicher zu behandeln? (Vera: im Zweifel Gast.) */
export function isGuestSpeaker(s: RecognizedSpeaker): boolean {
  return s.isGuest || !s.name;
}

/** Konfidenz als runde Prozentzahl fürs Tooltip (0..1 → „97 %"); leer bei Unsinn. */
function confidencePct(confidence: number): string | null {
  if (!Number.isFinite(confidence) || confidence <= 0) return null;
  return `${Math.round(Math.min(1, confidence) * 100)} %`;
}

export interface SpeakerChipProps {
  speaker: RecognizedSpeaker;
}

/**
 * Der Chip selbst. Erkannt ⇒ Name + Namens-Akzent (über die CSS-Var `--spk-hue`,
 * die styles die fixierte oklch-Rezeptur füttert) + Konfidenz im Tooltip. Gast ⇒
 * `is-guest` (grau, gestrichelt), fester Text {@link GUEST_LABEL}. Rein
 * präsentational → auch via renderToStaticMarkup prüfbar.
 */
export function SpeakerChip({ speaker }: SpeakerChipProps) {
  if (isGuestSpeaker(speaker)) {
    return (
      <span className="msg__speaker is-guest" title={GUEST_TITLE}>
        {GUEST_LABEL}
      </span>
    );
  }
  const name = speaker.name as string; // isGuestSpeaker schließt null aus
  const pct = confidencePct(speaker.confidence);
  // „Ähnlichkeit“ statt „sicher“: der Wert ist rohe Cosine-Stimm-Ähnlichkeit, keine
  // kalibrierte Wahrscheinlichkeit — „% sicher“ würde Konfidenz als Gewissheit verkaufen.
  const title = pct ? `Erkannt als ${name} · Stimm-Ähnlichkeit ${pct}` : `Erkannt als ${name}`;
  // Nur den Ton als Custom-Prop setzen (Idiom wie VoiceStar `--lvl`); die
  // oklch-Rezeptur lebt in der CSS, damit Helligkeit/Sättigung AA-fest bleiben.
  const style = { '--spk-hue': speakerHue(name) } as CSSProperties;
  return (
    <span className="msg__speaker" style={style} title={title}>
      {name}
    </span>
  );
}
