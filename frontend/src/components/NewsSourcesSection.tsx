import { useEffect, useRef, useState } from 'react';
import {
  type NewsSourcesSetting,
  UnknownNewsSourceError,
  fetchNewsSources,
  saveNewsSources,
} from '../api/newsSources';
import { useUiStrings } from '../i18n';
import { SourceBadge } from './SourceBadge';

/**
 * **NewsSourcesSection** — die aktiven Nachrichten-Quellen (Tagesschau/heise/
 * Golem), gehängt unter den bestehenden Lagebild-Anzeige-Schalter in
 * `HomeTilesSection` (SettingsPanel.tsx). Muster {@link LanguageSection}: lädt
 * den Ist-Zustand einmal beim Mount, ein Checkbox-Klick PUTtet direkt (kein
 * Sammel-Speichern-Knopf) und liest danach den AUTORITATIVEN Server-Zustand
 * zurück (Readback, kein optimistisches Häkchen).
 *
 * **Bewusst native `<input type="checkbox">`, kein `role="switch"`:** die
 * Nachbar-Schalter in `HomeTilesSection` (Sauger/Klima/Lagebild) sind über
 * `[role="switch"]`-Zählungen in `hometilessettings.test.tsx` fest verdrahtet
 * ("Sauger + Klima + Lagebild" = genau 3) — drei weitere `role="switch"`-
 * Elemente hätten diese bestehenden Assertions gebrochen. Eine echte Checkbox
 * ist semantisch ohnehin die richtigere Rolle für "mehrere aus einer Menge
 * wählen" (im Gegensatz zum binären An/Aus-Schalter). Reuses
 * `.settings__skill(meta|name)` (Zeilen-Layout) — keine neue CSS-Klasse.
 *
 * Diese Quellen-Wahl ist unabhängig vom Lagebild-ANZEIGE-Schalter (lokal,
 * `useHomeTiles`): sie steuert, was der SERVER überhaupt vorhält (auch für
 * den Sprach-Weg), nicht nur die Kachel — deshalb rendert sie unabhängig
 * davon, ob die Anzeige gerade an/aus ist.
 *
 * **Kurs-Update (Andi-Bestellung):** jede Zeile trägt vor dem Anzeigenamen
 * dasselbe {@link SourceBadge}, das auch die Attribution-Zeile in
 * `CurrentAffairsTile.tsx` trägt — ein Bauteil, zwei Orte.
 */
export function NewsSourcesSection() {
  const t = useUiStrings().settings;
  const [current, setCurrent] = useState<NewsSourcesSetting | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void (async () => {
      try {
        const next = await fetchNewsSources(controller.signal);
        if (aliveRef.current) {
          setCurrent(next);
          setError(null);
        }
      } catch {
        if (aliveRef.current) setError(t.homeTilesNewsSourcesLoadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
  }, []);

  const onToggle = (id: string) => {
    if (busy || current === null) return;
    const active = new Set(current.aktiv);
    if (active.has(id)) active.delete(id);
    else active.add(id);
    const next = current.verfuegbar.filter((v) => active.has(v));
    setBusy(true);
    setNote(null);
    void (async () => {
      try {
        const updated = await saveNewsSources(next);
        if (!aliveRef.current) return;
        setCurrent(updated);
      } catch (e) {
        if (!aliveRef.current) return;
        setNote(e instanceof UnknownNewsSourceError ? t.homeTilesNewsSourcesUnknown : t.homeTilesNewsSourcesFailed);
      } finally {
        if (aliveRef.current) setBusy(false);
      }
    })();
  };

  return (
    <NewsSourcesSectionView
      current={current}
      loading={loading}
      error={error}
      busy={busy}
      note={note}
      onToggle={onToggle}
    />
  );
}

export interface NewsSourcesSectionViewProps {
  current: NewsSourcesSetting | null;
  loading?: boolean;
  error?: string | null;
  busy?: boolean;
  note?: string | null;
  onToggle: (id: string) => void;
}

/**
 * Proper-Noun-Anzeigenamen der bekannten Quellen — wie `source` in den
 * Nachrichten-Karten selbst (s. `CurrentAffairsTile.tsx`) bewusst NICHT über
 * i18n übersetzt (Markennamen, keine UI-Sprache); Fallback ist die rohe Id,
 * falls ein Build eine Quelle kennt, für die diese Karte noch keinen
 * hübschen Namen trägt.
 */
const SOURCE_DISPLAY_NAMES: Record<string, string> = {
  TAGESSCHAU: 'Tagesschau',
  HEISE: 'heise',
  GOLEM: 'Golem',
};

function displayName(id: string): string {
  return SOURCE_DISPLAY_NAMES[id] ?? id;
}

/**
 * Präsentations-Sektion (prop-getrieben, Muster `LanguageSectionView` — per
 * `renderToStaticMarkup` testbar).
 */
export function NewsSourcesSectionView({
  current,
  loading,
  error,
  busy,
  note,
  onToggle,
}: NewsSourcesSectionViewProps) {
  const t = useUiStrings().settings;
  return (
    <div className="settings__group">
      <h3 className="settings__label">{t.homeTilesNewsSourcesLabel}</h3>
      {loading && !current && <p className="settings__hint">{t.homeTilesNewsSourcesLoading}</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <div className="settings__skills">
          {current.verfuegbar.map((id) => (
            <div className="settings__skill" key={id}>
              <label className="settings__skillmeta" htmlFor={`news-source-${id}`}>
                <span className="settings__skillname">
                  <SourceBadge sourceId={id} /> {displayName(id)}
                </span>
              </label>
              <input
                id={`news-source-${id}`}
                type="checkbox"
                checked={current.aktiv.includes(id)}
                disabled={busy}
                onChange={() => onToggle(id)}
              />
            </div>
          ))}
        </div>
      )}
      <p className="settings__hint">{t.homeTilesNewsSourcesHint}</p>
      {note && (
        <p className="settings__hint" role="status">
          {note}
        </p>
      )}
    </div>
  );
}
