import { SUPPORTED_UI_LANGUAGES, type UiLanguage } from './types';

// ─────────────────────────────────────────────────────────────────────────────
//  Geteilter Zustand der AKTIVEN UI-Sprache — leichter Modul-Store (kein Context
//  nötig): {@link LanguageSection} füttert ihn bei jedem erfolgreichen
//  Fetch/Save der Server-Sprachwahl (api/languageSettings.ts), {@link
//  useUiStrings} liest ihn. Default IMMER 'de' (Fallback, solange noch nichts
//  geladen wurde ODER der Code unbekannt ist) — deckungsgleich mit dem
//  Bestandsverhalten (deutscher Text, bis die echte Wahl eintrifft).
// ─────────────────────────────────────────────────────────────────────────────

let activeLanguage: UiLanguage = 'de';
const listeners = new Set<() => void>();

function isSupportedUiLanguage(code: string): code is UiLanguage {
  return (SUPPORTED_UI_LANGUAGES as readonly string[]).includes(code);
}

/** Die aktuell aktive UI-Sprache (synchron, kein Fetch). */
export function getActiveUiLanguage(): UiLanguage {
  return activeLanguage;
}

/**
 * Setzt die aktive UI-Sprache. Unbekannte/leere Codes werden ignoriert (der
 * Fallback bleibt, was er war — nie ein kaputter Zustand). Benachrichtigt
 * Abonnenten nur bei einer ECHTEN Änderung.
 */
export function setActiveUiLanguage(code: string): void {
  if (!isSupportedUiLanguage(code) || code === activeLanguage) return;
  activeLanguage = code;
  for (const listener of listeners) listener();
}

/** Abonniert Änderungen der aktiven Sprache; gibt die Abbestell-Funktion zurück. */
export function subscribeActiveUiLanguage(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
