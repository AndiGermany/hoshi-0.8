/**
 * **themeCatalog** — das Manifest ist die Wahrheit über die Themen.
 *
 * Andi-Auftrag 2026-08-08: „Ich möchte, dass du die Designs in ein .old
 * verschiebst. Das soll dynamisch nachladbar sein — nicht in der CSS liegen,
 * sondern dynamisch geladen werden. Zeigen wir, was auch in 1.0 bleiben wird."
 *
 * Vorher lag die Themen-Liste an DREI Stellen gleichzeitig: als Token-Blöcke in
 * `styles/themes.css`, als `THEME_IDS`/`THEME_GROUPS` in `hooks/useSettings.ts`
 * und als Namen/Beiworte in fünf `i18n/*.ts`. Ein neues Thema hieß: vier Dateien
 * anfassen und hoffen, dass keine Liste vergessen wurde.
 *
 * Jetzt gibt es EINE Quelle — `public/themes/manifest.json`:
 *
 * ```json
 * { "version": 1,
 *   "groups": [{ "id": "szenen", "order": 1 }, …],
 *   "themes": [{ "id": "asagiri", "name": "Asagiri", "kanji": "朝霧",
 *                "gloss": { "de": "Morgennebel", … },
 *                "group": "szenen", "swatch": ["#f7fbfd", "#a23d50", "#182631"],
 *                "file": "asagiri.css", "hidden": false }] }
 * ```
 *
 * Ein neues Thema ist damit: eine CSS-Datei unter `public/themes/` plus ein
 * Eintrag hier. Kein Bundle-Eingriff, kein Frontend-Deploy, keine vierte Liste.
 * DAS ist die Naht, die auch in 1.0 bleibt.
 *
 * WAS HIER *NICHT* HINEINGEHÖRT: der Gruppen-TITEL („Szenen", „Klassiker" …).
 * Der ist Oberflächen-Text und wohnt weiterhin im i18n-Katalog
 * (`settings.themeGroups`) — sonst würde die Gruppenüberschrift beim
 * Sprachwechsel nicht mitgehen. NAME und BEIWORT eines Themas dagegen stehen im
 * Manifest (mehrsprachig), weil sie zum Thema gehören und mit ihm ausgeliefert
 * werden müssen.
 *
 * EHRLICHKEIT: Solange das Manifest nicht da ist, gibt es hier `null` — der
 * Picker zeigt dann „lädt …", keine erfundene Liste. Ein kaputter Einzeleintrag
 * fliegt raus (mit `console.warn`), statt die ganze Auswahl mitzureißen.
 */

import { useEffect, useState } from 'react';

/**
 * Die Gruppen des Pickers, in Anzeige-Reihenfolge des Manifests (`order`).
 *
 * NEU SORTIERT 2026-08-21 (Andi: „Sortiere die Designs logisch und gruppiere
 * diese"). Bis dahin lagen ALLE dreizehn Szenen in EINER Gruppe `szenen` — eine
 * Überschrift über dreizehn Karten ordnet nichts, sie benennt nur den Stapel.
 * Das Ordnungsprinzip ist jetzt die TAGESLAGE, also genau die Frage, mit der man
 * ein Design sucht („ich will was Helles" / „was für abends"):
 *
 *  • `automatik`   — nur Sora. Keine Farbe, sondern eine Regel (folgt der Uhr).
 *                    GANZ OBEN: wer nicht wählen will, ist nach einer Karte fertig.
 *  • `morgen`      — Asagiri, Asa, Yoake.
 *  • `tag`         — Komorebi, Momiji, Hanashigure, Ukiyo, Natsu no Hi, Aoi.
 *  • `abend-nacht` — Natsumatsuri, Amayadori, Yoru, Nagareboshi.
 *  • `stimmung`    — Bilder statt Tageszeiten; hier wohnt das versteckte Nagori.
 *  • `klassiker`   — der Ruhestand. Enthält nur noch {@link ThemeManifestEntry.retired}-
 *                    Themen (heute: Kasumi) und steht darum normalerweise GAR
 *                    NICHT in der Galerie — sie taucht einzig auf, wenn so ein
 *                    Thema gerade AKTIV ist (s. {@link visibleGroups}).
 *
 * INNERHALB einer Gruppe: hell → dunkel, gemessen an der WCAG-Relativluminanz
 * von `swatch[0]` (`--bg-surface`) — die Fläche, die später den Bildschirm füllt.
 * Gemessen 2026-08-21: Morgen 95,9 / 93,3 / 0,8 · Tag 92,8 / 91,7 / 90,8 / 87,2 /
 * 84,4 / 1,0 · Abend & Nacht 1,1 / 0,8 / 0,4 / 0,2.
 */
export type ThemeGroupId =
  | 'automatik'
  | 'morgen'
  | 'tag'
  | 'abend-nacht'
  | 'stimmung'
  | 'klassiker';

/** Die Gruppen-Ids, die dieses Frontend kennt (Riegel gegen Tippfehler im Manifest). */
export const THEME_GROUP_IDS: readonly ThemeGroupId[] = [
  'automatik',
  'morgen',
  'tag',
  'abend-nacht',
  'stimmung',
  'klassiker',
];

/**
 * Die Sprachen, in denen ein Manifest-Beiwort vorliegen MUSS — deckungsgleich
 * mit `SUPPORTED_UI_LANGUAGES` (i18n/types.ts). Bewusst hier dupliziert statt
 * importiert: dieses Modul hängt an keinem i18n-Modul, damit die Import-Kette
 * `i18n/types → useSettings → themeCatalog` nicht im Kreis läuft. Ein Test hält
 * beide Listen deckungsgleich.
 */
export const THEME_GLOSS_LANGUAGES = ['de', 'en', 'es', 'fr', 'it'] as const;
export type ThemeGlossLanguage = (typeof THEME_GLOSS_LANGUAGES)[number];

/** Eine Gruppe in Anzeige-Reihenfolge. */
export interface ThemeManifestGroup {
  id: ThemeGroupId;
  /** Kleiner = weiter oben im Picker. */
  order: number;
}

/** Ein Thema, wie das Manifest es beschreibt. */
export interface ThemeManifestEntry {
  /** Die persistierte Id — exakt der Wert von `data-theme` am <html>. */
  id: string;
  /** Eigenname, in jeder Sprache gleich („Nagareboshi"). */
  name: string;
  /** Die japanische Schreibung („流れ星") — Sprach-neutral, darum nicht im i18n-Katalog. */
  kanji: string;
  /** Beiwort je Sprache („Sternschnuppe" / „shooting star" / …). */
  gloss: Record<ThemeGlossLanguage, string>;
  group: ThemeGroupId;
  /** Vorschau-Farben als Hex: [Fläche (--bg-surface), Akzent, Textton (--text-1)]. */
  swatch: readonly [string, string, string];
  /**
   * Die nachzuladende CSS-Datei, relativ zu `/themes/`. FEHLT bei Themen, die
   * gar keine Farben haben — heute genau eines: `sora` ist eine Regel und löst
   * sich zur Laufzeit in ein anderes Thema auf, das dann seine Datei mitbringt.
   */
  file?: string;
  /** `true` = steht erst im Picker, wenn es gefunden wurde (heute: nagori). */
  hidden?: boolean;
  /**
   * `true` = IM RUHESTAND: das Thema existiert weiter (gespeicherte Wahl bleibt
   * gültig, CSS wird geladen, Sora darf es als Station rotieren), steht aber
   * NICHT mehr in der Galerie. Heute genau eines: `kasumi` — das letzte Theme
   * ohne eigene Szene (Andi 21.08.: „Entferne die alten, noch nicht animierten
   * Designs"), das Sora aber unverändert um 18–22 Uhr zeigt.
   *
   * WARUM NICHT EINFACH {@link hidden}: `hidden` ist die Easter-Egg-Mechanik und
   * hängt an EINEM Schalter — dem Nagori-Fund. Ein zurückgezogenes Thema mit
   * `hidden` zu markieren hieße: wer Nagori findet, bekommt Kasumi gleich mit
   * zurück in die Galerie. Zwei verschiedene Gründe, unsichtbar zu sein,
   * brauchen zwei Felder.
   *
   * AUSNAHME (dieselbe Ehrlichkeits-Regel wie bei `hidden`): ist das Thema
   * gerade AKTIV, steht seine Karte trotzdem da — sonst liefe etwas, das man
   * im Picker weder sieht noch abwählen kann.
   */
  retired?: boolean;
}

export interface ThemeManifest {
  version: number;
  groups: readonly ThemeManifestGroup[];
  themes: readonly ThemeManifestEntry[];
}

/** Eine Gruppe mit ihren sichtbaren Themen — was der Picker rendert. */
export interface VisibleThemeGroup {
  id: ThemeGroupId;
  themes: readonly ThemeManifestEntry[];
}

/** Wo das Manifest liegt (public/themes/ landet 1:1 unter /themes/). */
export const THEME_MANIFEST_URL = '/themes/manifest.json';

/** Die Manifest-Version, die dieses Frontend versteht. */
export const SUPPORTED_MANIFEST_VERSION = 1;

// ─────────────────────────────────────────────────────────────────────────────
//  Validierung — ein Manifest ist eine DATEI, also potenziell falsch
// ─────────────────────────────────────────────────────────────────────────────

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isHex(value: unknown): value is string {
  return typeof value === 'string' && /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value);
}

function isGroupId(value: unknown): value is ThemeGroupId {
  return THEME_GROUP_IDS.includes(value as ThemeGroupId);
}

/** Prüft EINEN Themen-Eintrag; `null` = unbrauchbar (fliegt aus der Liste). */
function parseEntry(raw: unknown, knownGroups: ReadonlySet<ThemeGroupId>): ThemeManifestEntry | null {
  if (!isRecord(raw)) return null;
  const { id, name, kanji, gloss, group, swatch, file, hidden, retired } = raw;
  // Die Id landet in einem CSS-Attribut-Selektor und in einem Datei-Pfad —
  // darum bewusst eng: Kleinbuchstaben, Ziffern, Bindestrich.
  if (!isNonEmptyString(id) || !/^[a-z0-9-]+$/.test(id)) return null;
  if (!isNonEmptyString(name) || !isNonEmptyString(kanji)) return null;
  if (!isGroupId(group) || !knownGroups.has(group)) return null;
  if (!isRecord(gloss) || !THEME_GLOSS_LANGUAGES.every((l) => isNonEmptyString(gloss[l]))) {
    return null;
  }
  if (!Array.isArray(swatch) || swatch.length !== 3 || !swatch.every(isHex)) return null;
  // `file` darf fehlen (sora), aber nicht aus dem Themen-Ordner ausbrechen.
  if (file !== undefined && (!isNonEmptyString(file) || !/^[a-z0-9/-]+\.css$/.test(file))) {
    return null;
  }
  if (hidden !== undefined && typeof hidden !== 'boolean') return null;
  if (retired !== undefined && typeof retired !== 'boolean') return null;

  return {
    id,
    name,
    kanji,
    gloss: Object.fromEntries(
      THEME_GLOSS_LANGUAGES.map((l) => [l, gloss[l] as string]),
    ) as Record<ThemeGlossLanguage, string>,
    group,
    swatch: [swatch[0], swatch[1], swatch[2]] as const,
    ...(file !== undefined ? { file } : {}),
    ...(hidden !== undefined ? { hidden } : {}),
    ...(retired !== undefined ? { retired } : {}),
  };
}

/**
 * Liest ein rohes JSON-Objekt als {@link ThemeManifest}.
 *
 * `null` bedeutet „damit kann der Picker nichts anfangen" (falsche Version,
 * keine Gruppen, kein einziges brauchbares Thema) — der Aufrufer zeigt dann
 * ehrlich einen Ladezustand statt einer halben Liste. EINZELNE kaputte Themen
 * werden dagegen still übergangen (mit einer Warn-Zeile): ein Tippfehler in
 * einem Eintrag soll nicht die anderen neun mitnehmen.
 */
export function parseThemeManifest(raw: unknown): ThemeManifest | null {
  if (!isRecord(raw)) return null;
  if (raw.version !== SUPPORTED_MANIFEST_VERSION) {
    console.warn(`[hoshi] Themen-Manifest hat Version ${String(raw.version)} — erwartet wird ${SUPPORTED_MANIFEST_VERSION}.`);
    return null;
  }
  if (!Array.isArray(raw.groups) || !Array.isArray(raw.themes)) return null;

  const groups: ThemeManifestGroup[] = [];
  for (const g of raw.groups) {
    if (!isRecord(g) || !isGroupId(g.id) || typeof g.order !== 'number') continue;
    if (groups.some((seen) => seen.id === g.id)) continue; // Doppelte Gruppe: die erste zählt
    groups.push({ id: g.id, order: g.order });
  }
  if (groups.length === 0) return null;
  groups.sort((a, b) => a.order - b.order);

  const known = new Set(groups.map((g) => g.id));
  const themes: ThemeManifestEntry[] = [];
  for (const t of raw.themes) {
    const entry = parseEntry(t, known);
    if (!entry) {
      console.warn('[hoshi] Themen-Manifest: ein Eintrag ist unbrauchbar und wird übergangen.', t);
      continue;
    }
    if (themes.some((seen) => seen.id === entry.id)) continue; // Doppelte Id: die erste zählt
    themes.push(entry);
  }
  if (themes.length === 0) return null;

  return { version: SUPPORTED_MANIFEST_VERSION, groups, themes };
}

// ─────────────────────────────────────────────────────────────────────────────
//  Laden — genau einmal je Session
// ─────────────────────────────────────────────────────────────────────────────

let manifest: ThemeManifest | null = null;
let inFlight: Promise<ThemeManifest | null> | null = null;
/** Abonnenten (die Hook-Instanzen), die auf das erste Eintreffen warten. */
const listeners = new Set<() => void>();

/** Das bereits geladene Manifest — oder `null`, solange keins da ist. Synchron. */
export function cachedThemeManifest(): ThemeManifest | null {
  return manifest;
}

/**
 * Lädt das Manifest (genau einmal je Session) und cacht es. Ein Fehlschlag wird
 * NICHT gecacht: der nächste Aufruf darf es erneut versuchen (Netz kann
 * zurückkommen), und bis dahin gibt es ehrlich `null`.
 */
export function loadThemeManifest(): Promise<ThemeManifest | null> {
  if (manifest) return Promise.resolve(manifest);
  if (inFlight) return inFlight;

  inFlight = (async () => {
    try {
      const res = await fetch(THEME_MANIFEST_URL, { cache: 'no-cache' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const parsed = parseThemeManifest(await res.json());
      if (parsed) {
        manifest = parsed;
        for (const listener of listeners) listener();
      }
      return parsed;
    } catch (err) {
      console.warn(`[hoshi] Themen-Manifest (${THEME_MANIFEST_URL}) nicht ladbar:`, err);
      return null;
    } finally {
      inFlight = null;
    }
  })();

  return inFlight;
}

/**
 * Setzt das Manifest direkt — für Tests (und für ein künftiges Server-Rendering,
 * das es mitliefern könnte, statt einen zweiten Roundtrip zu erzwingen).
 */
export function primeThemeManifest(next: ThemeManifest | null): void {
  manifest = next;
  inFlight = null;
  for (const listener of listeners) listener();
}

/** Vergisst das geladene Manifest — nur für Tests. */
export function resetThemeCatalog(): void {
  primeThemeManifest(null);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Abfragen
// ─────────────────────────────────────────────────────────────────────────────

/** Der Eintrag zu einer Id — `undefined`, wenn das Manifest sie nicht kennt. */
export function findTheme(
  source: ThemeManifest | null,
  id: string,
): ThemeManifestEntry | undefined {
  return source?.themes.find((t) => t.id === id);
}

/**
 * Kennt das Manifest diese Id? Ohne geladenes Manifest ist die Antwort bewusst
 * `true`: eine gespeicherte Wahl darf nicht weggeworfen werden, nur weil die
 * Datei noch unterwegs ist (Kaltstart). Sobald das Manifest da ist, zieht der
 * Riegel — s. `useSettings`.
 */
export function isKnownTheme(id: string, source: ThemeManifest | null = manifest): boolean {
  if (!source) return true;
  return source.themes.some((t) => t.id === id);
}

/**
 * Die Gruppen, wie der Picker sie zeigen soll: in `order`-Reihenfolge, jede mit
 * ihren Themen in Manifest-Reihenfolge. Leere Gruppen fallen raus.
 *
 * ZWEI Gründe, warum ein Thema fehlen kann — bewusst getrennt gehalten:
 *  • {@link ThemeManifestEntry.hidden} (Easter-Egg, heute Nagori): `nagoriUnlocked`
 *    schaltet es frei — Fund-Flag ODER „ist gerade aktiv" (s. `useSettings`).
 *  • {@link ThemeManifestEntry.retired} (Ruhestand, heute Kasumi): nie in der
 *    Galerie, egal was gefunden wurde. Das Thema bleibt trotzdem ein gültiges
 *    Thema — Sora rotiert Kasumi unverändert weiter.
 *
 * `activeId` ist die EINE Ausnahme über beide Gründe hinweg: was gerade läuft,
 * ist immer sichtbar. Sonst stünde die Galerie da, ohne dass irgendeine Karte
 * angekreuzt wäre — und der Weg zurück zu einem anderen Thema wäre erraten.
 */
export function visibleGroups(
  source: ThemeManifest | null,
  nagoriUnlocked: boolean,
  activeId?: string,
): readonly VisibleThemeGroup[] {
  if (!source) return [];
  const shows = (t: ThemeManifestEntry): boolean =>
    t.id === activeId || (!t.retired && (nagoriUnlocked || !t.hidden));
  return source.groups
    .map((group) => ({
      id: group.id,
      themes: source.themes.filter((t) => t.group === group.id && shows(t)),
    }))
    .filter((group) => group.themes.length > 0);
}

/** Das Beiwort in der gewünschten Sprache; unbekannte Sprache ⇒ Deutsch. */
export function themeGloss(entry: ThemeManifestEntry, language: string): string {
  return (
    entry.gloss[language as ThemeGlossLanguage] ?? entry.gloss[THEME_GLOSS_LANGUAGES[0]]
  );
}

/**
 * React-Hook über das Manifest. Ist es schon geladen, liefert der ERSTE Render
 * es bereits (kein Flackern, keine „lädt …"-Zeile, die sofort wieder weggeht) —
 * darum `useState`-Initialisierer statt eines reinen Effekts. Sonst stößt der
 * Effekt das Laden an und meldet sich, sobald es da ist.
 *
 * Dasselbe Muster wie `useActiveUiLanguage` (useState + Abo, kein
 * `useSyncExternalStore`): `renderToStaticMarkup`-Tests führen keine Effekte
 * aus und sehen exakt den Zustand, den sie vorher geprimt haben.
 */
export function useThemeCatalog(): ThemeManifest | null {
  const [current, setCurrent] = useState<ThemeManifest | null>(() => cachedThemeManifest());
  useEffect(() => {
    const sync = () => setCurrent(cachedThemeManifest());
    listeners.add(sync);
    sync(); // Resync: zwischen erstem Render und diesem Effekt kann es angekommen sein
    void loadThemeManifest().then(sync);
    return () => {
      listeners.delete(sync);
    };
  }, []);
  return current;
}
