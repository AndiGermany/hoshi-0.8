/**
 * **sceneMotion** — der eine Schalter, der die Szene anhält.
 *
 * Andi, 23.08.2026: „ich finde das kirschblüten im fluss design unfassbar
 * schön, aber es laggt leider, besonders, wenn ich das design ausgewählt habe
 * und die widgets anpasse". Die zweite Hälfte ist die Bestellung: beim
 * Anordnen zählt Reaktion, nicht Blütenfall.
 *
 * Diese Datei ist absichtlich winzig und weiß NICHTS über Themen. Sie setzt
 * ein Attribut an `<html>`; was daraus folgt, steht in `styles/themes.css`
 * (generisch für alle Themen) und in der jeweiligen Themen-Datei (für Szenen,
 * die ihre Uhr in einem SVG mitbringen — s. `hanaikada.css`). Das ist die
 * Trennung, die verhindert, dass hier je eine Themen-Liste entsteht.
 *
 * ZÄHLEND, NICHT SCHALTEND. Wer anhält, gibt auch wieder frei; solange auch
 * nur EIN Halter offen ist, bleibt die Szene stehen. Ohne das würde eine
 * zweite Bühne (oder ein Remount unter React StrictMode, das Effekte doppelt
 * fährt) beim Aufräumen den Halt der ersten mit abräumen — die Szene liefe
 * wieder los, obwohl noch jemand anordnet. Ein Zähler ist hier billiger als
 * jede Verabredung darüber, wer der „echte" Halter ist.
 *
 * Ohne DOM (SSR, Tests im `node`-Environment) tut die Funktion nichts und
 * wirft nicht — ein Attribut an einem Dokument, das es nicht gibt, ist keine
 * Ausnahme, sondern eine Nicht-Aufgabe.
 */

/** Das Attribut an `<html>`, auf das die Themen hören. */
export const SCENE_MOTION_ATTR = 'data-scene-motion';

/** Der einzige Wert, den es heute gibt: „steht still". */
export const SCENE_MOTION_STILL = 'still';

let holders = 0;

const root = (): HTMLElement | null =>
  typeof document === 'undefined' ? null : document.documentElement;

const apply = () => {
  const el = root();
  if (!el) return;
  if (holders > 0) el.setAttribute(SCENE_MOTION_ATTR, SCENE_MOTION_STILL);
  else el.removeAttribute(SCENE_MOTION_ATTR);
};

/**
 * Hält die Szene an und gibt die Freigabe zurück. Die Freigabe ist
 * idempotent: zweimal aufgerufen zählt sie einmal — sonst könnte ein
 * doppelt gefahrenes Effekt-Cleanup den Zähler unter null drücken und den
 * Halt eines anderen Aufrufers mitnehmen.
 */
export const holdSceneMotion = (): (() => void) => {
  holders += 1;
  apply();
  let released = false;
  return () => {
    if (released) return;
    released = true;
    holders = Math.max(0, holders - 1);
    apply();
  };
};

/** Nur für Tests: der Zähler zurück auf null, das Attribut ab. */
export const resetSceneMotion = () => {
  holders = 0;
  apply();
};
