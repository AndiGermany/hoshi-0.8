// Laufzeit-Config aus Vite-Env. Schlank + ehrlich: keine versteckten Defaults
// außer dem dokumentierten 0.8-Backend-Port :8090.

const rawBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8090';

/** Basis-URL des Backends, ohne abschließenden Slash. */
export const API_BASE: string = rawBase.replace(/\/+$/, '');

/** Token für den `X-Hoshi-Token`-Header. Leer = kein Token (Auth-Wand greift). */
export const TOKEN: string = import.meta.env.VITE_TOKEN ?? '';

export const hasToken = (): boolean => TOKEN.trim().length > 0;

/**
 * Stabile Sprecher-Identität für `speakerContext.speakerId`. Ohne sie legt das
 * Backend für Browser-Turns keine Entity-/Episodic-Memory an. Default „gast"
 * (die Backend-Konvention für nicht-personalisierte Turns, siehe `GUEST_SPEAKER_ID`
 * in `ChatView.tsx`) — überschreibbar via VITE_SPEAKER_ID, z. B. in einer lokalen,
 * nie committeten `.env.local` mit deinem eigenen Sprechernamen.
 */
export const SPEAKER_ID: string = import.meta.env.VITE_SPEAKER_ID || 'gast';
