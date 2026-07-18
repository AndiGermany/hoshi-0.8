// Reine Pegel-Glättung für die lebende Welle (und einst den Voice-Stern).
//
// Die perzeptuelle ANHEBUNG des rohen 0..1-RMS lebt seit der Cowork-Korrektur
// 20260706-1729 als Wahrnehmungs-Gamma in audio/motionTokens.ts
// (`gammaLevel`, level = rms_norm^0.6) — EINE Quelle für FE-Welle und später
// die Satelliten-LED, kein Duplikat hier. Übrig bleibt die Glättung: bewusst
// frei von DOM/Audio, damit sie ohne echtes Mikrofon/Web-Audio testbar ist.

/**
 * Asymmetrisch geglättetes Folgen (EMA): schneller Anstieg (`attack`), langsames
 * Abklingen (`release`) → lebendig beim Sprechen, ruhig in Pausen (kein Zappeln).
 * Gibt den nächsten geglätteten Wert aus `prev` Richtung `target`. Rein.
 */
export function emaLevel(prev: number, target: number, attack = 0.5, release = 0.12): number {
  const a = target > prev ? attack : release;
  return prev + a * (target - prev);
}
