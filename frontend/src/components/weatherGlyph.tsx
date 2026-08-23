import {
  CloudGlyph,
  CloudSunGlyph,
  FogGlyph,
  RainCloudGlyph,
  SnowCloudGlyph,
  SunGlyph,
  ThunderCloudGlyph,
} from './icons';

/**
 * **weatherGlyph** — die Lagen→Icon-Zuordnung, ausgezogen aus `IdleFace.tsx`.
 *
 * Sie stand dort seit dem 26.07. und war richtig aufgehoben, solange nur die
 * Wetter-Kachel sie brauchte. Mit der Maximieren-Ansicht (Andi 23.08.) braucht
 * sie ein ZWEITER Ort — und `MaximizeOverlay.tsx` aus `IdleFace.tsx` zu
 * importieren, das seinerseits das Overlay rendert, wäre ein Modul-Ring.
 * Ringe funktionieren in ESM meistens; „meistens" ist bei der Reihenfolge von
 * Modul-Auswertung keine Eigenschaft, auf die man baut.
 *
 * `IdleFace.tsx` re-exportiert beides unverändert weiter — jeder bestehende
 * Import (und jeder Test) bleibt Wort für Wort gültig.
 */

/** Icon-Kategorie einer Wetterlage — s. {@link weatherCategory}. */
export type WeatherCategory = 'clear' | 'partly' | 'cloudy' | 'fog' | 'rain' | 'snow' | 'thunder';

/**
 * Ordnet den deutschen WMO-Lagen-Text (`codeText`, IMMER Deutsch — s. KDoc
 * `weatherNowContent`) einer Icon-Kategorie zu (Andi-Auftrag 26.07:
 * „dezente Regenwolken bei Regen etc."). Feste, erschöpfende Zuordnung gegen
 * die 28 bekannten Backend-Strings (`WeatherCodeTexts.kt`, WMO-Codes 0–99) +
 * deren Fallback „wechselhaft"; Reihenfolge ist absichtlich (z. B. „Schnee-
 * schauer" enthält kein „regen", aber „Regenschauer" enthält „regen" — daher
 * Schnee VOR Regen geprüft). Unbekannter/neuer Text ⇒ 'cloudy', die
 * neutralste ehrliche Annäherung statt eines geratenen Sonnen-/Regen-Icons.
 */
export function weatherCategory(codeText: string): WeatherCategory {
  const t = codeText.toLowerCase();
  if (t.includes('gewitter')) return 'thunder';
  if (t.includes('schnee')) return 'snow';
  if (t.includes('neb')) return 'fog'; // „neblig" UND „gefrierender Nebel" (kein gemeinsames „nebel")
  if (t.includes('regen')) return 'rain'; // Regen, Nieselregen, Regenschauer
  if (t.includes('klar')) return 'clear'; // klar und sonnig, überwiegend klar
  if (t.includes('teilweise bewölkt')) return 'partly';
  return 'cloudy'; // bedeckt, wechselhaft (Fallback), unbekannt
}

/** Das dezente Lage-Icon je Kategorie — muted stroke-SVGs (components/icons.tsx), kein Emoji/Farb-Icon-Set. */
export function WeatherGlyph({ category, className }: { category: WeatherCategory; className?: string }) {
  switch (category) {
    case 'clear':
      return <SunGlyph className={className} />;
    case 'partly':
      return <CloudSunGlyph className={className} />;
    case 'fog':
      return <FogGlyph className={className} />;
    case 'rain':
      return <RainCloudGlyph className={className} />;
    case 'snow':
      return <SnowCloudGlyph className={className} />;
    case 'thunder':
      return <ThunderCloudGlyph className={className} />;
    case 'cloudy':
      return <CloudGlyph className={className} />;
  }
}
