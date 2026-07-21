import { useEffect, useState } from 'react';
import { getActiveUiLanguage, subscribeActiveUiLanguage } from './activeLanguageStore';
import { resolveUiStrings } from './catalogs';
import type { UiStrings } from './types';

/**
 * Liefert den UI-Text-Katalog der AKTIVEN Sprache (Andi-Auftrag 21.07: „Ich muss
 * die Sprache der UI auch in den Einstellungen auswählen können" — Orchestrator-
 * Entscheid: die EINE bestehende Sprachwahl, LanguageSection.tsx/
 * api/languageSettings.ts, steuert künftig AUCH die UI-Texte, kein zweiter
 * Selector).
 *
 * Codestil wie die übrigen Settings-Hooks: `useState` + Subscribe-Effekt (KEIN
 * `useSyncExternalStore`) — damit Bestandstests, die diese Sektionen per
 * `renderToStaticMarkup` rendern (Effekte laufen dort nicht), unverändert genau
 * den Default-Katalog (de) sehen. Fallback IMMER de: {@link resolveUiStrings}
 * greift bei leerem/unbekanntem Code selbst darauf zurück.
 */
export function useUiStrings(): UiStrings {
  const [lang, setLang] = useState(() => getActiveUiLanguage());
  useEffect(() => {
    // Resync direkt beim Mount (falls sich der Store zwischen erstem Render und
    // diesem Effekt schon geändert hat) + Live-Abo für spätere Wechsel.
    setLang(getActiveUiLanguage());
    return subscribeActiveUiLanguage(() => setLang(getActiveUiLanguage()));
  }, []);
  return resolveUiStrings(lang);
}
