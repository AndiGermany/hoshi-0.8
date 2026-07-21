import { useEffect, useRef, useState } from 'react';
import {
  type LanguageSetting,
  UnknownLanguageError,
  fetchLanguageSettings,
  saveLanguageSetting,
} from '../api/languageSettings';
import { de } from '../i18n/de';
import { setActiveUiLanguage, useUiStrings } from '../i18n';

// ─────────────────────────────────────────────────────────────────────────────
//  Sprachpaket-Kern (Andi-Auftrag 2026-07-20): DE/EN/ES/FR/IT als SERVER-Default
//  wählbar (Spiegel der lokalen `Sprache`-Auswahl weiter oben in SettingsPanel,
//  die pro Chat-/Voice-Request mitfließt — dieser Server-Wert greift für Ränder
//  OHNE eigene Wahl, z.B. den Voice-PE-Satelliten). Eigene Datei (Muster
//  NightModeSection/SpeakerSection): SettingsPanel.tsx ist bereits sehr groß und
//  wird parallel von anderen Sektionen bearbeitet — ein Import + ein
//  Einhänge-Punkt hält den Integrations-Diff klein.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Alle sichtbaren Texte an einem Ort (auch von Tests referenziert) — jetzt eine
 * Referenz auf den `de`-Katalog in `i18n/de.ts` (Quelle der Wahrheit, byte-
 * gleich zum bisherigen Stand). Gerendert wird NICHT dieser Fixwert, sondern
 * `useUiStrings().language` (s. unten) — der hier exportierte Name bleibt aus
 * Kompatibilitätsgründen (Tests importieren ihn direkt).
 */
export const LANGUAGE_SETTINGS_TEXTS = de.language;

/**
 * Container der Sprach-Gruppe (Muster {@link LookupModelSection} in
 * SettingsPanel.tsx): lädt den Ist-Zustand einmal beim Mount, schaltet per
 * Select-Auswahl direkt um (eine Auswahl IST die Handlung) und liest danach den
 * AUTORITATIVEN Server-Zustand zurück (Readback, kein optimistisches Umschalten).
 */
export function LanguageSection() {
  const t = useUiStrings();
  const LANGUAGE_SETTINGS_TEXTS = t.language;
  const [current, setCurrent] = useState<LanguageSetting | null>(null);
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
        const next = await fetchLanguageSettings(controller.signal);
        if (aliveRef.current) {
          setCurrent(next);
          setError(null);
          // Speist die geteilte UI-Sprache (i18n/activeLanguageStore) mit dem
          // Server-Ist-Zustand — die EINE Sprachwahl steuert jetzt auch die
          // UI-Texte app-weit (Andi-Auftrag 21.07).
          setActiveUiLanguage(next.aktiv);
        }
      } catch {
        if (aliveRef.current) setError(LANGUAGE_SETTINGS_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
  }, []);

  const onSelect = (code: string) => {
    if (busy || code === current?.aktiv) return;
    setBusy(true);
    setNote(null);
    void (async () => {
      try {
        const updated = await saveLanguageSetting(code);
        if (!aliveRef.current) return;
        setCurrent(updated);
        // Readback ist die AUTORITATIVE neue Sprache — sofort in den geteilten
        // Store spiegeln, damit die gesamte UI ohne Remount umschaltet.
        setActiveUiLanguage(updated.aktiv);
      } catch (e) {
        if (!aliveRef.current) return;
        setNote(e instanceof UnknownLanguageError ? LANGUAGE_SETTINGS_TEXTS.unknown : LANGUAGE_SETTINGS_TEXTS.failed);
      } finally {
        if (aliveRef.current) setBusy(false);
      }
    })();
  };

  return (
    <LanguageSectionView current={current} loading={loading} error={error} busy={busy} note={note} onSelect={onSelect} />
  );
}

export interface LanguageSectionViewProps {
  current: LanguageSetting | null;
  loading?: boolean;
  error?: string | null;
  busy?: boolean;
  note?: string | null;
  onSelect: (code: string) => void;
}

/**
 * Präsentations-Sektion der Server-Sprachwahl (prop-getrieben, Muster
 * `LookupModelSectionView` — per `renderToStaticMarkup` testbar). Jede
 * Nicht-Deutsch-Option trägt sichtbar „(Beta)"; ist die aktive Sprache nicht
 * Deutsch, zeigt ein Hinweis ehrlich, dass Smart-Home-Befehle vorerst Deutsch
 * bleiben (Reflexe werden NICHT übersetzt).
 */
export function LanguageSectionView({ current, loading, error, busy, note, onSelect }: LanguageSectionViewProps) {
  const t = useUiStrings();
  const LANGUAGE_SETTINGS_TEXTS = t.language;
  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-server-language">
        {LANGUAGE_SETTINGS_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <select
          id="settings-server-language"
          className="settings__select"
          value={current.aktiv}
          disabled={busy}
          onChange={(e) => onSelect(e.target.value)}
        >
          {current.sprachen.map((s) => (
            <option key={s.code} value={s.code}>
              {s.endonym}
              {s.beta ? LANGUAGE_SETTINGS_TEXTS.betaSuffix : ''}
            </option>
          ))}
        </select>
      )}
      {busy && <p className="settings__hint">{LANGUAGE_SETTINGS_TEXTS.switching}</p>}
      <p className="settings__hint">{LANGUAGE_SETTINGS_TEXTS.hint}</p>
      {/* Ehrlicher Hinweis (Andi-Auftrag 21.07): diese EINE Wahl steuert jetzt
          auch die UI-Texte, nicht nur das Gespräch — Smart-Home bleibt Deutsch. */}
      <p className="settings__hint">{LANGUAGE_SETTINGS_TEXTS.uiNotice}</p>
      {current?.smartHomeHinweis && <p className="settings__hint">{current.smartHomeHinweis}</p>}
      {note && (
        <p className="settings__hint settings__languagenote" role="status">
          {note}
        </p>
      )}
    </section>
  );
}
