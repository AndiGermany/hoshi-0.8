import { useEffect, useRef, useState } from 'react';
import {
  type SoraTheme,
  type Theme,
  SORA_ROTATION,
  useResolvedTheme,
  visibleThemeGroups,
} from '../hooks/useSettings';
import {
  type ThemeGroupId,
  type ThemeManifestEntry,
  findTheme,
  themeGloss,
  useThemeCatalog,
} from '../styles/themeCatalog';
import type { ThemeEntryStrings } from '../i18n/types';
import { useActiveUiLanguage, useUiStrings } from '../i18n';
import { Overlay } from './Overlay';

// ─────────────────────────────────────────────────────────────────────────────
//  ThemeGallery — the design gallery (design DESIGN-widgets-settings-2026-08-15
//  §3.4). Fifteen visible themes behind four `<details>` folds in a 340 px
//  drawer were, in Andi's words, still "echt viele Designs". They move into the
//  shared 960 px {@link Overlay}: groups become HEADINGS instead of folds (at
//  960 px everything fits without a single click), the cards are a
//  `repeat(auto-fill, minmax(220px, 1fr))` grid, and the active design stands
//  PROMINENTLY in the head rather than as one card among cards.
//
//  What stays exactly as it was (this is a move, not a rewrite):
//   • ONE `role="radiogroup"` across all cards — it is one exclusive choice, and
//     the group headings live INSIDE it (a11y contract of the old picker).
//   • Swatch colours come from the manifest as inline hex, so the gallery does
//     not pull fifteen stylesheets just to show fifteen cards.
//   • Sora keeps its day arc and shows the theme its RULE resolves to right now.
//   • Name + gloss from the manifest, character line from the i18n catalogue,
//     Kanji as the honest fallback for themes the catalogue does not know.
//
//  What is new: the six SCENE themes show their REAL scene — the difference
//  between "a colour sample" and "this is what Hoshi will look like".
//
//  SORTED BY TIME OF DAY, 2026-08-21 (Andi: "Sortiere die Designs logisch und
//  gruppiere diese"). Until then all thirteen scenes sat under ONE heading
//  ("Szenen") — a heading over thirteen cards names a pile, it does not order
//  one. The groups are now `automatik` · `morgen` · `tag` · `abend-nacht` ·
//  `stimmung`, i.e. the very question you have in your head when you look for a
//  design; inside a group the cards run light → dark. Which theme belongs where
//  is a MANIFEST decision (see `styles/themeCatalog.ts`) — this component only
//  renders what it is handed.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DOM id of a group heading (stable, so tests and anchors can grab it). Lives
 * here now that the groups themselves do; `SettingsPanel` re-exports it so no
 * existing import breaks.
 */
export const themeGroupHeadingId = (id: ThemeGroupId): string => `settings-themegroup-${id}`;

/**
 * The real scene drawing of each scene theme, relative to the served
 * `public/themes/` folder.
 *
 * WHY A MAP AND NOT A MANIFEST FIELD: the scene image is not a property of the
 * theme CHOICE (the manifest is the contract between a theme file and the
 * picker, and every entry there is validated); it is a property of this
 * gallery's presentation. Adding a field would mean touching the manifest
 * schema, its validator and every theme entry for one preview surface. A test
 * (`themegroups.test.tsx`) holds every path here against a real file on disk, so
 * a renamed asset cannot rot into a broken image.
 *
 * Weight, measured: 36–212 KB each, ~483 KB together (`ukiyo-wave.svg` alone is
 * 212 KB) — hence the visibility gate in {@link SceneThumb}.
 */
export const THEME_SCENE_IMAGES: Readonly<Record<string, string>> = {
  asagiri: '/themes/asagiri-szene.svg',
  komorebi: '/themes/komorebi-kozue.svg',
  momiji: '/themes/momiji-eda.svg',
  natsumatsuri: '/themes/natsumatsuri-hanabi.svg',
  hanashigure: '/themes/hanashigure-szene.svg',
  ukiyo: '/themes/ukiyo-wave.svg',
  // Die sieben wiederbelebten Alt-Themen (Revival 19.08.) — sie heissen alle
  // gleichfoermig `<id>-szene.svg`, anders als die aeltere Generation, die
  // sich noch nach ihrem Motiv benannt hat (kozue/hanabi/eda/wave).
  amayadori: '/themes/amayadori-szene.svg',
  natsunohi: '/themes/natsunohi-szene.svg',
  hanaikada: '/themes/hanaikada-szene.svg',
  fuyubare: '/themes/fuyubare-szene.svg',
  yukiakari: '/themes/yukiakari-szene.svg',
  nagareboshi: '/themes/nagareboshi-szene.svg',
  asa: '/themes/asa-szene.svg',
  yoake: '/themes/yoake-szene.svg',
  aoi: '/themes/aoi-szene.svg',
  yoru: '/themes/yoru-szene.svg',
};

/** Does this theme have a real scene drawing to show instead of a swatch? */
export function sceneImageFor(id: string): string | undefined {
  return THEME_SCENE_IMAGES[id];
}

/**
 * The scene preview of ONE card — deliberately lazy, twice.
 *
 * The thirteen scenes weigh ~622 KB together. Opening the gallery must not pull all
 * of them: on the iPad that is the difference between "the overlay is there" and
 * "the overlay stutters". So the `<img>` is not even RENDERED until the card
 * enters the viewport ({@link IntersectionObserver}), and once rendered it still
 * carries `loading="lazy"` as the browser-side belt.
 *
 * WITHOUT an IntersectionObserver (jsdom, very old engines) the image renders
 * immediately: `loading="lazy"` alone is then the honest best effort — better a
 * visible scene than a placeholder that never resolves.
 */
function SceneThumb({ src, alt }: { src: string; alt: string }) {
  const hostRef = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(() => typeof IntersectionObserver !== 'function');

  useEffect(() => {
    if (visible) return;
    const host = hostRef.current;
    if (!host) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) setVisible(true);
      },
      // A little ahead of the fold: the scene is there when the scroll arrives.
      { rootMargin: '200px' },
    );
    observer.observe(host);
    return () => observer.disconnect();
  }, [visible]);

  return (
    <span ref={hostRef} className="themegallery__scene" aria-hidden="true">
      {visible && <img className="themegallery__sceneimg" src={src} alt={alt} loading="lazy" />}
    </span>
  );
}

export interface ThemeGalleryProps {
  open: boolean;
  onClose: () => void;
  theme: Theme;
  onTheme: (theme: Theme) => void;
}

export function ThemeGallery({ open, onClose, theme, onTheme }: ThemeGalleryProps) {
  const t = useUiStrings().settings;
  const uiLanguage = useActiveUiLanguage();
  // Die Themen-Wahrheit: public/themes/manifest.json (einmal je Session geladen,
  // s. styles/themeCatalog.ts). `null` = noch nicht da ⇒ „lädt …".
  const manifest = useThemeCatalog();
  // Was Sora GERADE zeigen würde — bewusst unabhängig davon, ob Sora gewählt ist.
  const soraNow: SoraTheme = useResolvedTheme('sora');
  const pinned = (SORA_ROTATION as readonly Theme[]).includes(theme);
  const groups = visibleThemeGroups(manifest, theme);

  /** Der Manifest-Eintrag einer Id (für Vorschau-Farben + Namen). */
  const entryOf = (id: Theme): ThemeManifestEntry | undefined => findTheme(manifest, id);
  /** Der Anzeigename einer Id — Manifest zuerst, Text-Katalog als Rückfall. */
  const nameOf = (id: Theme): string =>
    entryOf(id)?.name ?? (t.themes as Partial<Record<string, ThemeEntryStrings>>)[id]?.label ?? id;
  /** Die drei Vorschau-Flächen einer Id als Inline-Farben (lade-frei, s. Modul-Kopf). */
  const swatchTiles = (id: Theme) => {
    const sw = entryOf(id)?.swatch;
    return [
      { className: 'settings__swatchbg', style: sw ? { background: sw[0] } : undefined },
      { className: 'settings__swatchaccent', style: sw ? { background: sw[1] } : undefined },
      { className: 'settings__swatchtext', style: sw ? { background: sw[2] } : undefined },
    ];
  };

  const activeGroup = groups.find((group) => group.themes.some((e) => e.id === theme));
  const activePreviewTheme = theme === 'sora' ? soraNow : theme;
  const activeScene = sceneImageFor(activePreviewTheme);

  return (
    <Overlay
      open={open}
      onClose={onClose}
      label={t.themeGalleryTitle}
      cardClassName="overlay__card themegallery"
    >
      <header className="themegallery__head">
        {/* Zwei benannte Ausgänge statt einem stummen Kreuz (Andi 19.08.): eine
            Auswahl schließt die Galerie ABSICHTLICH nicht — man soll vergleichen
            können —, also muss der Weg hinaus sichtbar sein. „Fertig" sagt das
            aus, das Kreuz bleibt für die, die es gewohnt sind; Escape kann beides.
            DOM-Reihenfolge: das Kreuz zuerst, damit der Autofokus der Schale dort
            landet (Crew-Idiom); `row-reverse` stellt es optisch nach rechts. */}
        <div className="themegallery__headactions">
          <button
            type="button"
            className="themegallery__close"
            onClick={onClose}
            aria-label={t.themeGalleryCloseAria}
          >
            ✕
          </button>
          <button type="button" className="themegallery__done" onClick={onClose}>
            {t.themeGalleryDone}
          </button>
        </div>
        <h2 className="themegallery__title">{t.themeGalleryTitle}</h2>
      </header>

      {/* The ACTIVE design, prominently — not a card among cards (§3.4). It shows
          the same preview surface a card would (real scene or swatch), just
          larger, so "what is running" needs no searching. */}
      {activeGroup && (
        <div className="themegallery__active">
          {activeScene ? (
            <span className="themegallery__activepreview" aria-hidden="true">
              <img
                className="themegallery__sceneimg"
                src={activeScene}
                alt=""
                loading="lazy"
              />
            </span>
          ) : (
            <span
              className="themegallery__activepreview settings__activeswatch"
              data-theme={activePreviewTheme}
              aria-hidden="true"
            >
              {swatchTiles(activePreviewTheme).map((tile) => (
                <span key={tile.className} className={tile.className} style={tile.style} />
              ))}
            </span>
          )}
          <span className="themegallery__activemeta">
            <span className="settings__themeactivekicker">{t.themeActiveLabel}</span>
            <span className="themegallery__activename">
              {nameOf(theme)}
              <span className="settings__themeactivegroup">
                {t.themeGlossSuffix(t.themeGroups[activeGroup.id].title)}
              </span>
            </span>
          </span>
        </div>
      )}

      {/* Ehrlicher Ladezustand: das Manifest ist eine Datei, die unterwegs sein
          kann. Solange keine Gruppe steht, sagen wir das. */}
      {groups.length === 0 && <p className="settings__hint settings__themeloading">{t.themeLoading}</p>}

      {/* EINE Radiogroup über ALLE Karten — es ist EINE exklusive Wahl. Die
          Gruppen sind Überschriften DARIN (statt `<details>`-Falten): auf 960 px
          passt alles ohne Klick, und ein Fold, den man erst öffnen muss, ist auf
          einer Galerie-Fläche nur eine Hürde. */}
      <div className="themegallery__groups" role="radiogroup" aria-label={t.themeGroupAria}>
        {groups.map((group) => {
          const g = t.themeGroups[group.id];
          return (
            <section className={`themegallery__group themegallery__group--${group.id}`} key={group.id}>
              <h3 className="settings__themegrouptitle" id={themeGroupHeadingId(group.id)}>
                {g.title}
              </h3>
              <p className="settings__hint settings__themegroupnote">{g.note}</p>

              <div className="themegallery__grid">
                {group.themes.map((entry) => {
                  const id = entry.id;
                  // Nagoris Charakter-Zeile wohnt in ihrem eigenen Katalog-Block;
                  // Themen, die der Katalog gar nicht kennt (die Szenen), zeigen
                  // statt eines erfundenen Satzes ihre Kanji — wahr statt erfunden.
                  const catalogEntry = (t.themes as Partial<Record<string, ThemeEntryStrings>>)[id];
                  const hint = id === 'nagori' ? t.nagori.hint : (catalogEntry?.hint ?? entry.kanji);
                  const label = entry.name;
                  const gloss = themeGloss(entry, uiLanguage);
                  const isActive = theme === id;
                  // Sora ist keine Farbe: seine Vorschau UND seine Zeile zeigen,
                  // was die Regel gerade ergibt.
                  const previewTheme = id === 'sora' ? soraNow : id;
                  const scene = sceneImageFor(previewTheme);
                  return (
                    <button
                      key={id}
                      type="button"
                      role="radio"
                      aria-checked={isActive}
                      aria-label={t.themeOptionAria(g.title, label)}
                      className={`settings__theme themegallery__card ${isActive ? 'is-active' : ''}`}
                      onClick={() => onTheme(id)}
                      title={hint}
                    >
                      {/* Scenes show their real drawing; everything else keeps the
                          three-surface swatch with the manifest hexes inline
                          (`data-theme` stays on, so a LOADED theme also shows its
                          real ground). */}
                      {scene ? (
                        <SceneThumb src={scene} alt={t.themeSceneAlt(label)} />
                      ) : (
                        <span
                          className="settings__swatch themegallery__swatch"
                          data-theme={previewTheme}
                          aria-hidden="true"
                        >
                          {swatchTiles(previewTheme).map((tile) => (
                            <span key={tile.className} className={tile.className} style={tile.style} />
                          ))}
                        </span>
                      )}
                      <span className="settings__themename">
                        {label}
                        <span className="settings__themegloss">{t.themeGlossSuffix(gloss)}</span>
                      </span>
                      <span className="settings__themehint">
                        {id === 'sora' ? t.themeSoraNow(nameOf(soraNow)) : hint}
                      </span>
                    </button>
                  );
                })}
              </div>

              {/* Der Tagesbogen als reine VORSCHAU (nicht klickbar): man sieht auf
                  einen Blick, was Sora tut — und nebenbei, dass die Sternschnuppe
                  in der tiefsten Nacht kommt. */}
              {group.id === 'automatik' && (
                <p className="settings__themearc" aria-label={t.themeArcAria}>
                  {SORA_ROTATION.map((id: SoraTheme, i) => (
                    <span key={id} className={`settings__themearcstep ${id === soraNow ? 'is-now' : ''}`}>
                      {i > 0 ? t.themeArcSeparator : ''}
                      {nameOf(id)}
                    </span>
                  ))}
                </p>
              )}

              {/* Leise: wer eine Tageszeit fest wählt, pausiert die Automatik.
                  UMGEZOGEN 21.08. von der Gruppe „Klassiker" hierher: die fünf
                  Rotations-Stationen wohnen seit der Tageslage-Sortierung in
                  DREI verschiedenen Gruppen — der Satz gehört also dorthin, wo
                  die Automatik selbst steht, direkt unter ihren Tagesbogen. Er
                  erklärt genau dessen graue Schrift („die Regel läuft gerade
                  nicht"), statt am Fuß einer Gruppe zu hängen, die man gar
                  nicht mehr sieht. */}
              {group.id === 'automatik' && pinned && theme !== 'nagori' && (
                <p className="settings__hint settings__themepinned">{t.themePinnedNote(nameOf(theme))}</p>
              )}

              {/* Einordnung für den Fund: „名残 — ein Vorbote von 0.9". */}
              {group.themes.some((e) => e.id === 'nagori') && (
                <p className="settings__hint settings__nagorinote">{t.nagori.note}</p>
              )}
            </section>
          );
        })}
      </div>
    </Overlay>
  );
}
