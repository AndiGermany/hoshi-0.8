import { de } from './de';
import { en } from './en';
import { es } from './es';
import { fr } from './fr';
import { it } from './it';
import type { UiLanguage, UiStrings } from './types';

export const CATALOGS: Record<UiLanguage, UiStrings> = { de, en, es, fr, it };

/** Katalog der gewünschten Sprache; unbekannter/leerer Code ⇒ IMMER de (Fallback). */
export function resolveUiStrings(lang: string): UiStrings {
  return CATALOGS[lang as UiLanguage] ?? CATALOGS.de;
}
