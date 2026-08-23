import { de } from '../i18n/de';
import type { WeatherOutlookStrings } from '../i18n/types';
import type { DayOutlook } from '../hooks/useWeatherToday';

/**
 * **Der Mehrtage-Ausblick der XL-Wetterkachel** — die reine Ableitungs-Schicht
 * (Muster `components/homeTiles.ts`/`stageStats.ts`: kleingeschriebenes Modul =
 * Rechnen ohne DOM; gezeichnet wird in `IdleFace.tsx`, wo das Lage-Icon schon
 * wohnt).
 *
 * Quelle ist das seit 21.08. additive Wire-Feld `outlook` (BE-RESULT
 * `RESULT-wetter-mehrtage-2026-08-21` §1.2/§3). Es kommt fertig in der
 * Anzeigesprache — **hier wird kein Lagen-Text übersetzt**, nur beschriftet.
 *
 * **Ehrlichkeits-Regeln:**
 *  - Kein `outlook` (Alt-Backend) ⇒ **leere Liste** ⇒ die Zeile erscheint gar
 *    nicht. Keine gerechneten Tage, kein Platzhalter-Raster.
 *  - Ein Tag, dessen `dateIso` kein Kalendertag ist, fällt **einzeln** raus
 *    (statt „Invalid Date" zu beschriften oder die ganze Zeile zu verwerfen).
 *  - **Keine Obergrenze.** Gezeigt werden genau so viele Tage, wie das Wire
 *    trägt — eine 7 im Code wäre eine Behauptung über das Backend, und der
 *    Horizont ist dort schon einmal gewandert.
 *  - `precipProbability` fehlt ⇒ der Prozent-Anhang bleibt weg, **nie „0 %"**.
 *
 * **Wochentags-Kürzel ohne zweite Tabelle:** `toLocaleDateString(locale,
 * {weekday:'short'})` — dieselbe Konvention, mit der die Uhr-Kachel schon ihr
 * M-Datum baut (`clockTileBody`). Fünf Sprachen kommen damit aus ICU statt aus
 * einer handgepflegten Liste; das BE hätte ein `weekdayLabel` liefern können
 * (§3 des BE-RESULTs) — gebraucht wird es nicht, und ein Feld, das man nicht
 * braucht, holt man nicht.
 */

/** Eine Spalte der Ausblick-Zeile — alles fertig beschriftet, nichts mehr zu rechnen. */
export interface OutlookColumn {
  /** Stabiler React-Key: der Kalendertag selbst. */
  key: string;
  /** 0 = heute (BE-Vertrag). */
  offset: number;
  /** Wochentags-Kürzel in der aktiven Sprache: „Fr" · „Fri" · „vie" · „ven." */
  weekday: string;
  /** Lagen-Text, schon in der Anzeigesprache (BE) — hier unverändert. */
  codeText: string;
  /** Tagesspanne im Haus-Format der Wetter-Kachel: „12–22°". */
  span: string;
  tempMin: number;
  tempMax: number;
  /** Nativer Tooltip: „Freitag, 12–22°, leichter Regen · 60 % Regen". */
  title: string;
  /** Der laufende Tag — die Zeile hebt ihn hervor, ohne ein Wort dafür zu brauchen. */
  today: boolean;
}

/**
 * `YYYY-MM-DD` → **lokales** Datum. Bewusst NICHT `new Date(iso)`: das liest ein
 * nacktes Datum als UTC-Mitternacht, und in jeder Zone westlich von Greenwich
 * wäre der Wochentag dann um einen Tag daneben — genau die Zeitzonen-Drift, die
 * der BE-Vertrag mit `dateIso` statt Epoch-ms vermeiden wollte.
 *
 * `null` bei allem, was kein Kalendertag ist (auch bei „2026-02-31": der
 * Roll-over von `Date` würde stillschweigend den 3. März daraus machen).
 */
export function parseIsoDay(dateIso: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateIso);
  if (!m) return null;
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const d = new Date(year, month - 1, day);
  if (Number.isNaN(d.getTime())) return null;
  if (d.getFullYear() !== year || d.getMonth() !== month - 1 || d.getDate() !== day) return null;
  return d;
}

/**
 * Wire-Tage → fertige Spalten. `locale` steuert Wochentag UND Reihenfolge der
 * Beschriftung; `t` sind die zwei Satz-Bausteine (Tooltip + Regen-Anhang).
 */
export function outlookColumns(
  outlook: DayOutlook[] | undefined,
  locale: string = de.locale,
  t: WeatherOutlookStrings = de.idleFace.wetter.outlook,
): OutlookColumn[] {
  return (outlook ?? []).flatMap((day) => {
    const date = parseIsoDay(day.dateIso);
    if (date === null) return []; // kein Kalendertag ⇒ keine Spalte (statt „Invalid Date")
    const span = `${day.tempMin}–${day.tempMax}°`;
    const long = date.toLocaleDateString(locale, { weekday: 'long' });
    const rain =
      day.precipProbability === undefined ? '' : t.rainChance(Math.round(day.precipProbability));
    return [
      {
        key: day.dateIso,
        offset: day.offset,
        weekday: date.toLocaleDateString(locale, { weekday: 'short' }),
        codeText: day.codeText,
        span,
        tempMin: day.tempMin,
        tempMax: day.tempMax,
        title: t.title(long, span, day.codeText) + rain,
        today: day.offset === 0,
      },
    ];
  });
}
