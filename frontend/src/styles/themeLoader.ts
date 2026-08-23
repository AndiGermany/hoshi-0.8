/**
 * **themeLoader** — lädt eine Themen-CSS-Datei zur Laufzeit nach.
 *
 * Andi-Auftrag 2026-08-08: „Die Designs sollen dynamisch nachladbar sein — nicht
 * in der CSS liegen, sondern dynamisch geladen werden." Genau das macht diese
 * Datei, und sie macht NUR das: ein `<link rel="stylesheet">` je Thema, einmal
 * geladen bleibt es im DOM.
 *
 * Warum `<link>` und nicht `fetch` + `<style>`: der Browser cacht Stylesheets,
 * kennt das `load`/`error`-Ereignis nativ, parst sie außerhalb des Main-Threads
 * und dedupliziert identische URLs von selbst. Ein zweites Cache-System wäre ein
 * zweiter Fehlerfall.
 *
 * DREI ZUSAGEN — an ihnen hängt, dass der Umzug niemandem etwas kaputt macht:
 *
 *  1. **Genau einmal.** Jede Id wird höchstens ein Mal eingehängt; parallele
 *     Aufrufe bekommen dasselbe Promise (kein doppeltes `<link>` beim schnellen
 *     Hin-und-Her im Picker). Ein bereits im DOM stehendes `<link>` (z. B. per
 *     SSR/Preload gesetzt) wird übernommen statt verdoppelt.
 *  2. **Ehrlich beim Scheitern.** Kein Netz, 404, kaputte Datei ⇒ `false`, eine
 *     `console.warn`-Zeile und das tote `<link>` fliegt wieder raus. Der Aufrufer
 *     setzt `data-theme` dann NICHT — es bleibt beim Basis-Look (styles/themes.css).
 *     Nie eine weiße oder halb gestylte Seite; ein Fehlschlag ist außerdem
 *     wiederholbar (der Cache-Eintrag wird gelöscht).
 *  3. **Kein FOUC.** Das Promise löst erst NACH dem `load`-Event auf. Wer
 *     `data-theme` erst danach setzt (App.tsx), sieht nie ein halb angezogenes
 *     Thema — die alte Farbwelt steht bis die neue vollständig da ist.
 *
 * Ohne DOM (Node/SSR/Tests im `node`-Environment) ist das Nachladen sinnlos:
 * die Funktion meldet dann ehrlich `false`, statt zu werfen.
 */

import { useEffect } from 'react';
import { findTheme, loadThemeManifest } from './themeCatalog';

/** Basis-Pfad der Themen-Dateien (public/themes/ landet 1:1 unter /themes/). */
export const THEME_BASE_PATH = '/themes/';

/** Attribut, an dem ein von uns eingehängtes Themen-<link> erkennbar ist. */
export const THEME_LINK_ATTR = 'data-hoshi-theme';

/** Laufende/abgeschlossene Ladevorgänge je Theme-Id (der eine Cache). */
const loading = new Map<string, Promise<boolean>>();

/**
 * Build-Stempel fürs Cache-Busting: die Theme-Dateien tragen keinen Hash im
 * Dateinamen, der Browser cached sie darum über Deploys hinweg — Andi sah am
 * 2026-08-11 nach dem Ukiyo-Fix den alten Stand, obwohl der Server längst die
 * neue Datei lieferte. Der `?v=`-Anker ändert sich pro BUILD (vite-define),
 * nicht pro Version — Theme-Fixes kommen auch innerhalb derselben 0.8.x an.
 * `typeof`-Guard, weil vitest vite-defines nicht zieht (Muster TopNav).
 */
function buildId(): string {
  return typeof __HOSHI_BUILD_ID__ !== 'undefined' ? __HOSHI_BUILD_ID__ : 'dev';
}

/** Gibt es überhaupt ein Dokument? (Node/SSR: nein.) */
function hasDom(): boolean {
  return typeof document !== 'undefined' && !!document.head;
}

/** Das bereits eingehängte <link> dieses Themas, falls vorhanden. */
function existingLink(id: string): HTMLLinkElement | null {
  if (!hasDom()) return null;
  return document.querySelector<HTMLLinkElement>(`link[${THEME_LINK_ATTR}="${id}"]`);
}

/**
 * Sorgt dafür, dass die CSS-Datei `file` (relativ zu {@link THEME_BASE_PATH})
 * für das Thema `id` geladen ist.
 *
 * @returns `true`, sobald das Stylesheet wirklich angewendet werden kann;
 *          `false`, wenn es nicht geladen werden konnte (oder es kein DOM gibt).
 *          NIE ein rejectetes Promise — ein fehlendes Thema ist kein Absturz.
 */
export function ensureThemeLoaded(id: string, file: string): Promise<boolean> {
  const cached = loading.get(id);
  if (cached) return cached;

  if (!hasDom()) return Promise.resolve(false);

  // Schon im DOM (Preload/HMR/zweite App-Instanz): übernehmen statt verdoppeln.
  // `sheet !== null` heißt „fertig geparst"; sonst noch auf sein load warten.
  const already = existingLink(id);
  if (already) {
    const promise: Promise<boolean> = already.sheet
      ? Promise.resolve(true)
      : new Promise((resolve) => {
          already.addEventListener('load', () => resolve(true), { once: true });
          already.addEventListener('error', () => resolve(false), { once: true });
        });
    loading.set(id, promise);
    return promise;
  }

  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = `${THEME_BASE_PATH}${file}?v=${buildId()}`;
  link.setAttribute(THEME_LINK_ATTR, id);

  const promise = new Promise<boolean>((resolve) => {
    link.addEventListener('load', () => resolve(true), { once: true });
    link.addEventListener('error', () => {
      // Ehrlich scheitern: das tote <link> raus, den Cache-Eintrag raus (damit
      // ein späterer Versuch — z. B. nach Netz-Rückkehr — es nochmal darf), und
      // eine Zeile in die Konsole, die sagt, WAS fehlt.
      link.remove();
      loading.delete(id);
      console.warn(
        `[hoshi] Thema „${id}" konnte nicht geladen werden (${THEME_BASE_PATH}${file}) — es bleibt beim aktuellen Look.`,
      );
      resolve(false);
    });
  });

  loading.set(id, promise);
  document.head.appendChild(link);
  return promise;
}

/** Ist dieses Thema bereits eingehängt? (Für Tests + Diagnose.) */
export function isThemeLinked(id: string): boolean {
  return existingLink(id) !== null;
}

/**
 * Wirft alle nachgeladenen Themen weg — NUR für Tests (jeder Fall soll auf einem
 * frischen Dokument starten). Im Produktivpfad gibt es bewusst kein Entladen:
 * ein einmal geladenes Thema bleibt im DOM, damit das Zurückwechseln sofort ist.
 */
export function resetThemeLoader(): void {
  loading.clear();
  if (!hasDom()) return;
  for (const link of Array.from(document.querySelectorAll(`link[${THEME_LINK_ATTR}]`))) {
    link.remove();
  }
}

/**
 * **useAppliedTheme** — die FOUC-Naht: erst laden, dann anschalten.
 *
 * Die Reihenfolge ist die ganze Strategie:
 *   1. Manifest holen (einmal je Session) → welche Datei gehört zu dieser Id?
 *   2. Datei nachladen und auf ihr `load`-Ereignis WARTEN.
 *   3. ERST DANN `data-theme` am <html> setzen.
 *
 * Bis Schritt 3 steht der Basis-Look aus `styles/themes.css` (Aoi, im Bundle) —
 * bzw. beim WECHSEL das bisherige Thema. Es gibt also nie einen Moment, in dem
 * ein halb angezogenes Thema rendert, und nie eine weiße Seite.
 *
 * Scheitert Schritt 1 oder 2 (kein Netz, 404, kaputte Datei), passiert bewusst
 * NICHTS: der aktuelle Look bleibt, der Loader schreibt eine Warn-Zeile. Lieber
 * die alte Farbwelt behalten als eine behaupten, die gar nicht geladen ist.
 *
 * Auch die Sora-Rotation läuft hier durch: springt die Automatik ins nächste
 * Tagesfenster, ändert sich `themeId` — und der Wechsel nimmt exakt denselben
 * Weg wie eine Wahl von Hand.
 */
export function useAppliedTheme(themeId: string): void {
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const manifest = await loadThemeManifest();
      if (cancelled) return;
      const entry = findTheme(manifest, themeId);
      if (!entry) return; // Manifest fehlt/kennt die Id nicht → Basis-Look behalten
      // Ein Thema ohne Datei ist eine Regel, keine Farbe ('sora' ist an dieser
      // Stelle längst aufgelöst). Dann gibt es nichts zu laden.
      if (entry.file && !(await ensureThemeLoaded(entry.id, entry.file))) return;
      if (cancelled) return;
      document.documentElement.dataset.theme = entry.id;
    })();
    return () => {
      cancelled = true;
    };
  }, [themeId]);
}
