import { useCallback, useSyncExternalStore } from 'react';
import {
  DEFAULT_HOME_LAYOUT,
  parseHomeLayout,
  serializeHomeLayout,
  withHomeOrder,
  withHomePlacements,
  withHomeTileSize,
  type HomeLayoutV1,
  type HomePlacementMap,
} from '../components/homeLayout';
import type { HomeTileSize, HomeWidgetId } from '../components/homeWidgets';
import { notifyHomeTiles, safeStorage, subscribeHomeTiles } from './useSettings';

/**
 * **useHomeLayout** — der Speicher der Bühnen-Anordnung (W3,
 * `vault/tracks/DESIGN-widget-raster-2026-08-18.md` §5).
 *
 * Gespeichert wird EINE Reihenfolge mit je einer Stufe
 * (`{version:1, order:[{id,size}]}`), unter EINEM Schlüssel, auf EXAKT dem
 * bestehenden Pfad der Kachel-Schalter (§5.2): `safeStorage()` →
 * `useSyncExternalStore` → `notifyHomeTiles()`. Kein `deviceId` im Schlüssel,
 * kein Sync, kein Backend (§5.4) — `localStorage` IST schon pro Gerät und
 * Browserprofil, und das ist gewollt: das Flur-iPad darf ein anderes Layout
 * haben als das Sofa-iPad.
 *
 * **Diese Datei kennt nur Speichern und Wecken.** Was ein gültiges Layout ist
 * — Version, Duplikate, unerlaubte Stufen, fehlende Widgets — entscheiden die
 * reinen Funktionen in `components/homeLayout.ts`, damit die Härtungsfälle
 * ohne DOM prüfbar bleiben.
 *
 * **Storage tot ⇒ Default-Layout, kein Bruch** (§5.2). Bewusst KEIN
 * Gedächtnis-Ersatz für die Sitzung: die acht `hoshi.homeTiles.*`-Schalter
 * verhalten sich seit dem Bestand genauso, und eine zweite, klügere Regel für
 * genau diesen einen Schlüssel wäre die eigentliche Inkonsistenz.
 */

/** localStorage-Schlüssel der Bühnen-Anordnung (§5.2 — im Repo vorher unbelegt). */
export const HOME_LAYOUT_STORAGE_KEY = 'hoshi.homeTiles.layout';

/**
 * Zwischenspeicher des zuletzt GELESENEN Rohtexts und seines geparsten
 * Layouts. Kein Tempo-Trick, sondern Pflicht: `useSyncExternalStore` ruft
 * seinen Snapshot mehrfach pro Render und vergleicht per Identität — ein
 * jedes Mal frisch geparstes Objekt wäre jedes Mal ein neues und würde React
 * in eine Endlosschleife schicken (die Kachel-Schalter haben das Problem
 * nicht, sie geben `boolean` zurück). Der Rohtext ist der Schlüssel des
 * Vergleichs: ändert er sich nicht, ist auch das Layout dasselbe.
 */
let cachedRaw: string | null | undefined;
let cachedLayout: HomeLayoutV1 = DEFAULT_HOME_LAYOUT;

/** Rohtext aus dem Speicher — nicht vorhanden/geblockt/kaputt ⇒ `null`. */
function readRaw(): string | null {
  const store = safeStorage();
  if (!store) return null;
  try {
    return store.getItem(HOME_LAYOUT_STORAGE_KEY);
  } catch {
    return null;
  }
}

/** Das gespeicherte Layout, gehärtet und normalisiert. Wirft nie. */
export function loadHomeLayout(): HomeLayoutV1 {
  const raw = readRaw();
  if (raw === cachedRaw) return cachedLayout;
  cachedRaw = raw;
  cachedLayout = parseHomeLayout(raw);
  return cachedLayout;
}

/** Schreibt das Layout in seiner normalisierten Form. Voller/geblockter Speicher ⇒ still, kein Wurf. */
export function saveHomeLayout(layout: HomeLayoutV1): void {
  const store = safeStorage();
  if (!store) return;
  try {
    store.setItem(HOME_LAYOUT_STORAGE_KEY, serializeHomeLayout(layout));
  } catch {
    /* Speicher voll/geblockt — §5.2: kein Bruch, das Layout bleibt der Default. */
  }
}

/**
 * Löscht die gespeicherte Anordnung — der Schlüssel verschwindet, der Default
 * gilt wieder. **Fasst die Schalter nicht an** (§4.3): Anordnung und
 * Sichtbarkeit sind zwei Entscheidungen, und ein Reset, der beide zurückdreht,
 * nähme Andi seine Sauger-Entscheidung weg.
 */
export function resetHomeLayout(): void {
  const store = safeStorage();
  if (!store) return;
  try {
    store.removeItem(HOME_LAYOUT_STORAGE_KEY);
  } catch {
    /* geblockt — der Default galt dann ohnehin. */
  }
}

/* ── „Widgets anordnen" (W4) ─────────────────────────────────────────────── */

/**
 * Der Knopf in den Einstellungen und der Edit-Modus auf der Übersicht stehen
 * in zwei verschiedenen Bäumen. Statt eine Absicht durch fünf Ebenen zu
 * reichen (App → Übersicht → IdleFaceLive → IdleFace → HomeStage), liegt sie
 * hier: **ein Wunsch, der genau einmal eingelöst wird.**
 *
 * Bewusst eine EINMAL-Fahne und kein Zustand: „der Edit-Modus ist an" gehört
 * der Bühne (sie weiß als Einzige, ob es überhaupt Kacheln gibt und wann Andi
 * „Fertig" drückt). Ein globales `editing`-Flag wäre eine zweite Wahrheit
 * darüber — und die erste, die beim Reiterwechsel falsch stünde.
 *
 * Der Weg deckt beide Fälle ab: steht die Bühne schon (Andi war auf der
 * Übersicht), holt sie den Wunsch über den Listener ab; wechselt `App.tsx`
 * erst den Reiter, mountet die Bühne neu und holt ihn beim Mounten ab.
 * Abgeholt wird er genau einmal, egal auf welchem der beiden Wege.
 */
let editRequested = false;
const editListeners = new Set<() => void>();

/** „Widgets anordnen" wurde gedrückt. */
export function requestHomeEdit(): void {
  editRequested = true;
  editListeners.forEach((listener) => listener());
}

/** Holt den Wunsch ab — beim zweiten Aufruf ist er weg. */
export function takeHomeEditRequest(): boolean {
  if (!editRequested) return false;
  editRequested = false;
  return true;
}

/** Wer auf „Widgets anordnen" wartet, hört hier zu. */
export function subscribeHomeEdit(listener: () => void): () => void {
  editListeners.add(listener);
  return () => editListeners.delete(listener);
}

export interface UseHomeLayoutResult {
  /** Das aktuelle Layout, immer gültig und normalisiert. */
  layout: HomeLayoutV1;
  /**
   * Setzt die Stufe EINES Widgets (Reihenfolge unberührt — dafür gibt es
   * {@link setPlacements}). Eine Stufe, die dieses Widget nicht kann, ändert nichts.
   */
  setSize: (id: HomeWidgetId, size: HomeTileSize) => void;
  /**
   * **Setzt die ZELLEN einer Spaltenzahl** (W7-B) und, in EINEM Schreibvorgang,
   * die Lesereihenfolge, die daraus folgt.
   *
   * Warum beides zusammen: die Zellen sind die Wahrheit über das Bild, die
   * Reihenfolge die Wahrheit über alles Übrige — welchen Platz ein
   * Screenreader ansagt („Kachel 3 von 7"), wohin die Pfeiltasten gehen, und
   * womit eine Spaltenzahl gesät wird, die es noch nie gab. Liefen die zwei
   * auseinander, hörte man eine andere Bühne, als man sieht. Der Aufrufer
   * liefert `readingOrder` mit, weil nur er weiß, welche Widgets gerade
   * SICHTBAR sind (`withHomeOrder` lässt die übrigen an ihrem Platz).
   */
  setPlacements: (
    columns: number,
    cells: HomePlacementMap,
    readingOrder?: readonly HomeWidgetId[],
  ) => void;
  /** „Layout zurücksetzen" — s. {@link resetHomeLayout}. */
  reset: () => void;
}

/** React-Hook über die gespeicherte Anordnung: liest live, schreibt, weckt ALLE Instanzen. */
export function useHomeLayout(): UseHomeLayoutResult {
  // Dritter Parameter = Server-Snapshot: die Test-Suite rendert über
  // renderToStaticMarkup (SSR-Pfad), dort liest derselbe Loader.
  const layout = useSyncExternalStore(subscribeHomeTiles, loadHomeLayout, loadHomeLayout);
  const setSize = useCallback((id: HomeWidgetId, size: HomeTileSize) => {
    // Bewusst frisch gelesen statt aus dem Render geschlossen: zwischen Render
    // und Klick kann eine andere Hook-Instanz (Einstellungen) geschrieben haben.
    saveHomeLayout(withHomeTileSize(loadHomeLayout(), id, size));
    notifyHomeTiles();
  }, []);
  const setPlacements = useCallback(
    (columns: number, cells: HomePlacementMap, readingOrder?: readonly HomeWidgetId[]) => {
      // Frisch gelesen wie `setSize` — und in DIESER Reihenfolge:
      // erst die Reihenfolge, dann die Zellen darauf. Andersherum würde
      // `withHomeOrder` auf einem Layout arbeiten, dessen Zellen es nicht
      // kennt, und beim Zurückschreiben nichts kaputtmachen, aber unnötig
      // zweimal normalisieren.
      const base = loadHomeLayout();
      const withOrder = readingOrder ? withHomeOrder(base, readingOrder) : base;
      saveHomeLayout(withHomePlacements(withOrder, columns, cells));
      notifyHomeTiles();
    },
    [],
  );
  const reset = useCallback(() => {
    resetHomeLayout();
    notifyHomeTiles();
  }, []);
  return { layout, setSize, setPlacements, reset };
}
