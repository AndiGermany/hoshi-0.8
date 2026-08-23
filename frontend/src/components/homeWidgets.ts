/**
 * **homeWidgets** — the pure registry of the home stage's widgets
 * (DESIGN-widget-raster-2026-08-18 §1.1): the single place that knows which
 * widgets exist, their rank (crown/stage), which size steps each one may
 * take, and its default size. Pure/net-free (pattern `roomsSort.ts`/
 * `homeLayout.ts`) — `IdleFace.tsx` reads it instead of building the widget
 * list inline.
 *
 * **Seam note (parallel pod W2, `hooks/useSettings.ts`):** the on/off switch
 * per widget is NOT modelled here. W2 owns the five new
 * `hoshi.homeTiles.*` storage keys and the generic `enabled` record; this
 * file only answers "does the widget exist, and how big may it get" — the
 * hand wires the two together at merge time.
 */

/**
 * The eight widgets Andi can place — since W6 **all eight are on the stage**
 * (§1.1). The crown started with four blocks, then the weather left (W1), then
 * the clock (W4), and now the alarm (W6). What is left above the stage is the
 * greeting — a sentence that belongs to nobody and needs no size step.
 */
export type HomeWidgetId =
  | 'uhr'
  | 'wecker'
  | 'wetter'
  | 'laeuft'
  | 'einkauf'
  | 'vacuum'
  | 'climate'
  | 'news';

/** The four size steps a stage tile may take (§0.2/§2.3). The crown has none. */
export type HomeTileSize = 'S' | 'M' | 'L' | 'XL';

/**
 * **Crown**: the fixed head — on/off only, never sized or moved (§0.3).
 * **Stage**: everything the free grid can place (§2.4).
 *
 * **Andi 19.08. (W4), wörtlich: „Die Uhr soll auch verschiebbar und in der
 * Größe einstellbar werden".** Damit war das offene Gate §7.1 („Krone oder
 * Widget?") für die Uhr beantwortet — und die Antwort galt nie nur ihr.
 *
 * **W6 (20.08.): auch der Wecker ist jetzt ein Bühnen-Widget.** Er war der
 * letzte Bewohner der Krone, und er hat dort dasselbe gekostet, was die Uhr
 * gekostet hatte: eine eigene `auto`-Zeile im `.idle`-Gerüst plus zwei Grid-
 * Lücken, zusammen **75 px zwischen Kopf und Bühne**, die kein Widget je
 * benutzen durfte. Auf der Bühne trägt er dieselbe Fläche wie jede andere
 * Kachel — verschiebbar, abschaltbar, in zwei Stufen.
 *
 * Der Rang bleibt als Typ **bestehen**, obwohl ihn heute niemand mehr trägt:
 * `isStageWidgetId` (homeLayout.ts) fragt danach, und ein Grund, ein Widget
 * wieder festzunageln, kann wiederkommen. Ein Typ, der eine echte
 * Unterscheidung beschreibt, wird nicht gelöscht, nur weil eine Seite gerade
 * leer ist — gelöscht wird er, wenn die Unterscheidung falsch wird.
 */
export type HomeWidgetRank = 'crown' | 'stage';

export interface HomeWidgetDef {
  id: HomeWidgetId;
  rank: HomeWidgetRank;
  /** Allowed size steps — empty for the crown, which has no steps at all. */
  sizes: readonly HomeTileSize[];
  /** Default size — `null` for the crown. */
  defaultSize: HomeTileSize | null;
}

const STAGE_SIZES_FULL: readonly HomeTileSize[] = ['S', 'M', 'L', 'XL'];
/**
 * Die Uhr kann S · M · L — **kein XL** (W4, Andi 19.08.). Ihre Felder sind
 * abzählbar: Zeit, Datum, Gruß. Bei L sind sie erschöpft; eine XL-Stufe
 * müsste Fläche mit Nichts füllen oder Sekunden erfinden, die `clockParts`
 * gar nicht liefert und die auf einem dauerhaft laufenden Flur-iPad 60× mehr
 * Renders kosteten (§3.7). „L erfindet niemals Inhalt" gilt hier wörtlich.
 */
const CLOCK_SIZES: readonly HomeTileSize[] = ['S', 'M', 'L'];
/**
 * Der Wecker kann **S · M — kein L, kein XL** (W6, Bestellung wörtlich).
 * Dieselbe Rechnung wie bei der Uhr, nur strenger, weil er weniger Felder hat:
 * `ScheduledItem` liefert `dueAtEpochMs` (⇒ Weckzeit + Restzeit) und sonst
 * nichts, was ein Mensch aus 3 m lesen wollte. Die Haarlinie ist kein
 * Backend-Feld, sondern die vergangene Strecke der letzten 24 h vor dem
 * Klingeln (`alarmProgress`), und der Vertrauens-Satz ist ein fester Satz je
 * Sprache — **kein `confidence` vom Server**, das hier ein L füllen könnte.
 *
 *   S — die Zeile: „Wecker 07:00 · noch 22 h 2 min"
 *   M — dieselbe Zeile + Fortschritts-Haarlinie + Vertrauens-Satz
 *
 * Ein L müsste die Fläche mit Nichts füllen oder Felder erfinden, die es nicht
 * gibt. „L erfindet niemals Inhalt" (§2.3) gilt hier genauso wörtlich wie bei
 * der Uhr — und beim Wecker ist die Grenze schon eine Stufe früher erreicht.
 */
const ALARM_SIZES: readonly HomeTileSize[] = ['S', 'M'];

/**
 * The eight widgets, in the order §1.1 lists them — jetzt mit der **Uhr an
 * der Spitze der Bühne** (Andi 19.08.: „Die Uhr soll auch verschiebbar und in
 * der Größe einstellbar werden"). `DEFAULT_HOME_LAYOUT` leitet sich aus dieser
 * Reihenfolge ab, die Uhr ist also das erste Bühnen-Widget vorn.
 *
 * Default sizes reproduce today's content exactly: **uhr L** (Zeit groß +
 * Datum + Gruß — genau die alte Krone) · **wecker M** (Zeile + Haarlinie +
 * Vertrauens-Satz — genau die alte Wecker-Zeile, s. `ALARM_SIZES`) · wetter L ·
 * laeuft L · einkauf M · vacuum L · climate L · news M.
 *
 * Die Reihenfolge ist die Lese-Reihenfolge des Kopfes von früher: erst die
 * Uhr, dann der Wecker, dann der Haushalt. Wer schon ein Layout gespeichert
 * hat, bekommt den Wecker **hinten angehängt** — `normalizeHomeLayout` tut das
 * für jedes Registry-Widget, das in der Datei fehlt („Vorwärts-Migration ohne
 * Version-Bump", §5.3). Seine gespeicherte Anordnung bleibt also, wie sie war.
 */
export const HOME_WIDGETS: readonly HomeWidgetDef[] = [
  { id: 'uhr', rank: 'stage', sizes: CLOCK_SIZES, defaultSize: 'L' },
  { id: 'wecker', rank: 'stage', sizes: ALARM_SIZES, defaultSize: 'M' },
  { id: 'wetter', rank: 'stage', sizes: STAGE_SIZES_FULL, defaultSize: 'L' },
  { id: 'laeuft', rank: 'stage', sizes: STAGE_SIZES_FULL, defaultSize: 'L' },
  { id: 'einkauf', rank: 'stage', sizes: STAGE_SIZES_FULL, defaultSize: 'M' },
  /**
   * **Der Sauger hat seit 22.08. ein XL** — §1.1 hatte es ihm verweigert („ein
   * Gerät, ein Zustand; bei L sind die Felder der `VacuumFamily` erschöpft").
   * Das war zu dem Zeitpunkt WAHR und ist es jetzt nicht mehr: Andis Frage
   * 21.08. („Was haben wir noch, was man hinzufügen kann, wenn man das Widget
   * größer macht?") hat vier Felder gehoben, die gemappt, aber nie abgeleitet
   * waren — `lastCleanEnd`/`lastCleanStart` („zuletzt fertig 14:20, 1 h 40"),
   * `moppDrying`, und die vier Wartungs-Restzeiten, die auf L noch hinter
   * einem `<details>` lagen. Dazu die zwei Tat-Knöpfe.
   *
   * Die Regel „L/XL erfindet niemals Inhalt" (§2.3) bleibt unangetastet: jede
   * dieser Zeilen erscheint weiterhin NUR bei einem brauchbaren Wert. Ein
   * Sauger, dessen HA-Integration nur `state` und `battery_level` führt, zeigt
   * auf XL genau dieselben zwei Zeilen wie auf L und viel ruhige Fläche — kein
   * Füllmaterial. Verboten war nie die Stufe, sondern das Auffüllen.
   */
  { id: 'vacuum', rank: 'stage', sizes: STAGE_SIZES_FULL, defaultSize: 'L' },
  { id: 'climate', rank: 'stage', sizes: STAGE_SIZES_FULL, defaultSize: 'L' },
  { id: 'news', rank: 'stage', sizes: STAGE_SIZES_FULL, defaultSize: 'M' },
];

const BY_ID = new Map(HOME_WIDGETS.map((w) => [w.id, w]));

/** Looks up one widget's registry entry — total over {@link HomeWidgetId}, the union has exactly these eight members. */
export function homeWidget(id: HomeWidgetId): HomeWidgetDef {
  const def = BY_ID.get(id);
  if (!def) throw new Error(`unknown home widget id: ${id}`); // unreachable for a valid HomeWidgetId
  return def;
}
