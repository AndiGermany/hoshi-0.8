import { useEffect, useRef, useState } from 'react';
// Alias, damit das globale DOM-`KeyboardEvent` (window.addEventListener weiter unten)
// nicht vom React-Synthetic-Event-Typ verdeckt wird.
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react';
import type { Language, Skill } from '../api/types';
import {
  type Persona,
  type Theme,
  DEFAULT_ESCALATION_SECONDS,
  ESCALATION_MAX_SECONDS,
  ESCALATION_MIN_SECONDS,
  LANGUAGES,
  PERSONAS,
  THEMES,
} from '../hooks/useSettings';
import { useSkills } from '../hooks/useSkills';
import { fetchVoiceSample } from '../api/ttsSample';
import { SpeakerSection } from './SpeakerSection';
import { NightModeSection } from './NightModeSection';
import { LanguageSection } from './LanguageSection';
import {
  type PrivacySummary,
  type PrivacyTarget,
  PrivacyNotYetError,
  deletePrivacyData,
  fetchPrivacySummary,
} from '../api/privacy';
import {
  type WeatherLocationSetting,
  PlaceNotFoundError,
  WeatherLockedError,
  fetchWeatherLocation,
  saveWeatherLocation,
} from '../api/weatherLocation';
import {
  type LookupModelSetting,
  UnknownLookupModelError,
  fetchLookupModel,
  saveLookupModel,
} from '../api/lookupModel';
import {
  type TtsSetting,
  EngineUnavailableError,
  UnknownEngineError,
  UnknownVoiceError,
  fetchTtsSettings,
  saveTtsEngine,
  saveTtsVoice,
} from '../api/ttsSettings';
import {
  type BrainSetting,
  BrainSwitchUnavailableError,
  UnknownBrainModelError,
  fetchBrainSettings,
  saveBrainModel,
} from '../api/brainSettings';
import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import type { BrainModelStrings } from '../i18n/types';
import { CloudGlyph, LockGlyph, PlayGlyph, WarnGlyph } from './icons';

// ─────────────────────────────────────────────────────────────────────────────
//  Kategorie-Navigation (Andi 15.07: „hier müssen wir zu weit scrollen, daher
//  organisiere das bitte übersichtlich neu"). IA-Referenz:
//  vault/tracks/DESIGN-settings-ia-2026-06-30.md — sechs der acht dort skizzierten
//  Kategorien sind heute im FE mit echtem Inhalt gefüllt (Modell & Leistung /
//  Hintergrunddienste sind noch komplett NEEDS_ENDPOINT/ANDI_GATED und bekommen
//  darum bewusst KEINEN leeren Reiter). Zwei Sektionen sind neuer als das Dokument
//  und wurden sinngemäß eingereiht: Wecker-Eskalation → Fähigkeiten (Timer-/Wecker-
//  Verhalten, neben den Skills), Nachtmodus → Standort & Integrationen (pro
//  physischem Gerät/Ort, wie der Wetter-Ort).
//
//  Bewusst KEIN Unmount pro Kategorie: jede Sektion bleibt immer gemountet, der
//  Wechsel schaltet nur das native `hidden`-Attribut (kein Kill der laufenden
//  Fetches/Hooks, kein Options-Schwund in den Server-Static-Renders/Tests — die
//  ausgeblendeten Panels stehen weiter im HTML, nur `[hidden]` blendet sie aus).
// ─────────────────────────────────────────────────────────────────────────────

export type SettingsCategoryId =
  | 'darstellung'
  | 'sprache-stimme'
  | 'persoenlichkeit'
  | 'modell-leistung'
  | 'faehigkeiten'
  | 'gedaechtnis-privatsphaere'
  | 'standort-integrationen';

export const SETTINGS_CATEGORIES: { id: SettingsCategoryId; label: string }[] = [
  { id: 'darstellung', label: 'Darstellung' },
  { id: 'sprache-stimme', label: 'Sprache & Stimme' },
  { id: 'persoenlichkeit', label: 'Persönlichkeit' },
  // Neu (Andi-Auftrag): das Backlog-Feld „Modell & Leistung" aus der IA
  // (vault/tracks/DESIGN-settings-ia-2026-06-30.md) war bisher ABSICHTLICH
  // leer (kein Reiter ohne echten Inhalt) — jetzt gefüllt mit dem Brain-
  // Modell-Umschalter (GET/PUT /api/v1/settings/brain).
  { id: 'modell-leistung', label: 'Modell & Leistung' },
  { id: 'faehigkeiten', label: 'Fähigkeiten' },
  { id: 'gedaechtnis-privatsphaere', label: 'Gedächtnis & Privatsphäre' },
  { id: 'standort-integrationen', label: 'Standort & Integrationen' },
];

export const settingsTabId = (id: SettingsCategoryId): string => `settings-tab-${id}`;
export const settingsPanelId = (id: SettingsCategoryId): string => `settings-panel-${id}`;

// ─────────────────────────────────────────────────────────────────────────────
//  Kontextuelle Anker (Cowork-Spec cowork-research-2026-07-15/03-settings-
//  einbettung.md, V1 „Drawer bleibt + kontextuelle Anker"): der Drawer bleibt
//  die EINE Wahrheit, bekommt aber `openSettings(category, anchorId?)` als
//  Deep-Link — Zahnräder am Ort der Wirkung (Wetter-Kachel/Sprecher-Chip/
//  Wecker-Banner) springen direkt in die richtige Kategorie und pulsen kurz
//  den Anker. Anker-Ids sind hier als exportierte Konstanten neben den
//  Kategorien geführt (Risiko 2 im Report: „Anker-Drift bei Section-Umbau" —
//  ein Umbenennen der Ziel-Sektion bricht dann sichtbar den Typ, nicht still
//  einen String an drei Stellen).
// ─────────────────────────────────────────────────────────────────────────────

export type SettingsAnchorId = 'wetter-standort' | 'sprecher' | 'wecker-eskalation';

/** Welche Kategorie ein Anker aufschlägt — die einzige Quelle für dieses Mapping. */
export const SETTINGS_ANCHOR_CATEGORY: Record<SettingsAnchorId, SettingsCategoryId> = {
  'wetter-standort': 'standort-integrationen',
  sprecher: 'gedaechtnis-privatsphaere',
  'wecker-eskalation': 'faehigkeiten',
};

export const settingsAnchorId = (id: SettingsAnchorId): string => `settings-anchor-${id}`;

/** Wie lange der Anker nach dem Sprung ruhig einmalig pulst (ms). */
export const ANCHOR_HIGHLIGHT_MS = 1600;

/**
 * **SettingsCategoryNav** — die Reiter-Leiste (WAI-ARIA-Tabs-Muster: `role="tablist"`
 * + `role="tab"` je Knopf, `aria-selected`/`aria-controls` auf den zugehörigen
 * `tabpanel`). Roving Tabindex: nur der aktive Reiter ist per Tab erreichbar,
 * ←/→ (bzw. ↑/↓) wandern durchs Set und nehmen den Fokus mit — Klick wählt direkt.
 */
export function SettingsCategoryNav({
  active,
  onSelect,
}: {
  active: SettingsCategoryId;
  onSelect: (id: SettingsCategoryId) => void;
}) {
  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const idx = SETTINGS_CATEGORIES.findIndex((c) => c.id === active);
    let nextIdx: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') nextIdx = (idx + 1) % SETTINGS_CATEGORIES.length;
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp')
      nextIdx = (idx - 1 + SETTINGS_CATEGORIES.length) % SETTINGS_CATEGORIES.length;
    if (nextIdx === null) return;
    e.preventDefault();
    const next = SETTINGS_CATEGORIES[nextIdx];
    onSelect(next.id);
    // Fokus wandert mit (roving tabindex) — der Knopf existiert schon im DOM,
    // nur seine Attribute ändern sich beim nächsten Render.
    document.getElementById(settingsTabId(next.id))?.focus();
  };

  return (
    <div
      className="settings__catnav"
      role="tablist"
      aria-label="Einstellungs-Kategorien"
      onKeyDown={onKeyDown}
    >
      {SETTINGS_CATEGORIES.map((c) => {
        const isActive = c.id === active;
        return (
          <button
            key={c.id}
            type="button"
            role="tab"
            id={settingsTabId(c.id)}
            aria-selected={isActive}
            aria-controls={settingsPanelId(c.id)}
            tabIndex={isActive ? 0 : -1}
            className={`settings__cattab ${isActive ? 'is-active' : ''}`}
            onClick={() => onSelect(c.id)}
          >
            {c.label}
          </button>
        );
      })}
    </div>
  );
}

/** Ein Kategorie-Panel: bleibt immer gemountet, `hidden` blendet es nur aus. */
function SettingsCategoryPanel({
  id,
  active,
  children,
}: {
  id: SettingsCategoryId;
  active: SettingsCategoryId;
  children: ReactNode;
}) {
  return (
    <div
      id={settingsPanelId(id)}
      role="tabpanel"
      aria-labelledby={settingsTabId(id)}
      hidden={active !== id}
      className="settings__category"
    >
      {children}
    </div>
  );
}

/**
 * Ein Anker-Ziel innerhalb einer Kategorie: trägt die stabile DOM-Id
 * ({@link settingsAnchorId}), auf die kontextuelle Zahnräder deep-linken, und
 * blendet — solange {@link SettingsPanel} ihn gerade als Ziel führt — einen
 * ruhigen, EINMALIGEN Puls ein (`is-anchor-highlight`, reduced-motion stellt
 * die globale Regel in index.css still). Umschließt die Ziel-Sektion nur von
 * AUSSEN (kein Eingriff in deren Inneres — Wetter-Ort/Sprecher/Eskalation
 * bleiben unangetastete, unabhängig testbare Komponenten).
 */
function SettingsAnchor({
  id,
  active,
  children,
}: {
  id: SettingsAnchorId;
  active: boolean;
  children: ReactNode;
}) {
  return (
    <div
      id={settingsAnchorId(id)}
      className={`settings__anchor ${active ? 'is-anchor-highlight' : ''}`}
    >
      {children}
    </div>
  );
}

interface Props {
  open: boolean;
  onClose: () => void;
  theme: Theme;
  language: Language;
  persona: Persona;
  voice: string;
  onTheme: (theme: Theme) => void;
  onLanguage: (language: Language) => void;
  onPersona: (persona: Persona) => void;
  onVoice: (voice: string) => void;
  /**
   * Eskalations-Frist (s) der Wecker-Ursprungs-Lane. Optional (Default
   * {@link DEFAULT_ESCALATION_SECONDS}) — so bleiben bestehende Aufrufer/Tests, die
   * das Paar nicht reichen, unverändert lauffähig; App verdrahtet es via
   * {@link useEscalationSeconds}.
   */
  escalationSeconds?: number;
  onEscalationSeconds?: (seconds: number) => void;
  /**
   * Deep-Link-Ziel eines kontextuellen Zahnrads (openSettings in App.tsx):
   * gesetzt ⇒ springt bei jedem Öffnen (open: false→true) in diese Kategorie.
   * Fehlt es (der normale Top-Nav-Zahnrad-Aufruf), bleibt die zuletzt gewählte
   * Kategorie stehen — unverändertes Verhalten von heute.
   */
  category?: SettingsCategoryId;
  /**
   * Anker INNERHALB der Kategorie ({@link category}), der kurz pulst + in den
   * Sichtbereich rückt. Nur wirksam zusammen mit `category`.
   */
  anchor?: SettingsAnchorId;
}

/**
 * Einstellungs-Drawer (rechts): sechs Kategorien über eine Reiter-Leiste
 * ({@link SettingsCategoryNav}) statt einer einzigen langen Scroll-Wand (Andi
 * 15.07: „hier müssen wir zu weit scrollen, daher organisiere das bitte
 * übersichtlich neu"). IA-Referenz: vault/tracks/DESIGN-settings-ia-2026-06-30.md.
 *
 * Bleibt gemountet und blendet über `is-open` ein/aus (sanfter Ein-/Austritt,
 * reduced-motion wird durch die globale Regel in index.css respektiert). Esc und
 * ein Klick auf den abgedunkelten Hintergrund schließen; beim Öffnen wandert der
 * Fokus auf den Schließen-Button (a11y). Die Felder sind kontrolliert — der
 * Owner-State lebt in App via useSettings. Jede Sektion bleibt IMMER gemountet
 * (nur `hidden` schaltet die Sichtbarkeit) — Hooks/Fetches laufen unverändert
 * weiter, unabhängig von der gewählten Kategorie.
 */
export function SettingsPanel({
  open,
  onClose,
  theme,
  language,
  persona,
  voice,
  onTheme,
  onLanguage,
  onPersona,
  onVoice,
  escalationSeconds = DEFAULT_ESCALATION_SECONDS,
  onEscalationSeconds,
  category,
  anchor,
}: Props) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const asideRef = useRef<HTMLElement>(null);
  const [activeCategory, setActiveCategory] = useState<SettingsCategoryId>('darstellung');
  // Skills: Server-State (KEIN localStorage — Satellit/Browser/ct-106 teilen die Wahrheit).
  const { skills, loading: skillsLoading, error: skillsError, busyId, toggle } = useSkills();
  // Deep-Link-Puls: welcher Anker (falls einer) gerade den einmaligen
  // Highlight-Puls trägt — s. {@link SettingsAnchor}.
  const [highlighted, setHighlighted] = useState<SettingsAnchorId | null>(null);
  const highlightTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!open) return;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Deep-Link-Mechanik (openSettings(category, anchor?) in App.tsx): bei jedem
  // Öffnen mit einer Ziel-Kategorie dorthin springen; trägt der Aufruf zudem
  // einen Anker, kurz zu ihm scrollen (Container-scrollTop statt scrollIntoView
  // — jsdom wirft dort „not implemented", Muster aus ChatView's Auto-Scroll)
  // und ihn einmalig pulsen lassen. Ohne `category` (der normale Top-Nav-
  // Zahnrad-Aufruf) bleibt die zuletzt gewählte Kategorie unangetastet stehen.
  useEffect(() => {
    if (!open || !category) return;
    setActiveCategory(category);
    if (highlightTimerRef.current) clearTimeout(highlightTimerRef.current);
    if (anchor) {
      setHighlighted(anchor);
      const el = document.getElementById(settingsAnchorId(anchor));
      if (asideRef.current && el) {
        asideRef.current.scrollTop = Math.max(0, el.offsetTop - 12);
      }
      highlightTimerRef.current = setTimeout(() => {
        highlightTimerRef.current = null;
        setHighlighted(null);
      }, ANCHOR_HIGHLIGHT_MS);
    } else {
      setHighlighted(null);
    }
    // Aufräumen bei jedem Verlassen dieses Zustands (Deps ändern sich ODER der
    // Drawer schließt, bevor der Timer natürlich abläuft): Timer löschen UND
    // den Highlight-State selbst zurücksetzen — sonst bliebe `highlighted` bei
    // einem Schnell-Schließen mitten im Puls hängen, und ein späterer normaler
    // Top-Nav-Aufruf (ohne category/anchor) würde den alten Anker stumm weiter
    // als „aktiv" führen, ohne dass je wieder ein Timer ihn zurücksetzt.
    return () => {
      if (highlightTimerRef.current) {
        clearTimeout(highlightTimerRef.current);
        highlightTimerRef.current = null;
      }
      setHighlighted(null);
    };
  }, [open, category, anchor]);

  return (
    <div
      className={`settings-overlay ${open ? 'is-open' : ''}`}
      onClick={onClose}
      aria-hidden={!open}
    >
      <aside
        ref={asideRef}
        className="settings"
        role="dialog"
        aria-modal="true"
        aria-label="Einstellungen"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="settings__head">
          <h2 className="settings__title">Einstellungen</h2>
          <button
            ref={closeRef}
            type="button"
            className="settings__close"
            onClick={onClose}
            aria-label="Einstellungen schließen"
          >
            ✕
          </button>
        </header>

        <SettingsCategoryNav active={activeCategory} onSelect={setActiveCategory} />

        {/* ═══ Darstellung ═══════════════════════════════════════════════ */}
        <SettingsCategoryPanel id="darstellung" active={activeCategory}>
          {/* ── Farbthema ───────────────────────────────────────────────── */}
          <section className="settings__group">
            <h3 className="settings__label">Farbthema</h3>
            <div className="settings__themes" role="radiogroup" aria-label="Farbthema">
              {THEMES.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  role="radio"
                  aria-checked={theme === t.id}
                  className={`settings__theme ${theme === t.id ? 'is-active' : ''}`}
                  onClick={() => onTheme(t.id)}
                  title={t.hint}
                >
                  <span className={`settings__swatch settings__swatch--${t.id}`} aria-hidden="true" />
                  <span className="settings__themename">{t.label}</span>
                  <span className="settings__themehint">{t.hint}</span>
                </button>
              ))}
            </div>
          </section>
        </SettingsCategoryPanel>

        {/* ═══ Sprache & Stimme ══════════════════════════════════════════ */}
        <SettingsCategoryPanel id="sprache-stimme" active={activeCategory}>
          {/* ── Sprache (Chat + STT) ────────────────────────────────────── */}
          <section className="settings__group">
            <label className="settings__label" htmlFor="settings-language">
              Sprache
            </label>
            <select
              id="settings-language"
              className="settings__select"
              value={language}
              onChange={(e) => onLanguage(e.target.value as Language)}
            >
              {LANGUAGES.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.label}
                </option>
              ))}
            </select>
            <p className="settings__hint">Steuert die Chat-Sprache und die Spracherkennung (STT).</p>
            <p className="settings__hint">
              Automatisch erkennt pro Nachricht Deutsch oder Englisch und antwortet passend.
            </p>
          </section>

          {/* ── Server-Sprach-Standard (Sprachpaket-Kern, Andi-Auftrag 2026-07-20):
              DE/EN/ES/FR/IT als Fallback für Geräte ohne eigene Sprach-Wahl (z.B.
              den Voice-Satelliten) — eigene Datei/Komponente, s. LanguageSection.tsx. */}
          <LanguageSection />

          {/* ── TTS-Engine + Stimme: EIN gemeinsamer Fetch/Zustand (Andi-Live-
              Befund 20.07: „die Stimme-Sektion muss der aktiven Engine
              folgen" — vorher zeigte die Stimme-Sektion IMMER die OpenAI-
              Cloud-Stimmen + den Cloud-Hinweis, auch bei piper/say gewählt).
              Engine zuerst wählen, direkt darunter folgt die Stimmen-Liste
              DER GERADE AKTIVEN Engine (openai/say/piper/leer-bei-voxtral). */}
          <TtsAndVoiceSection voice={voice} onVoice={onVoice} />
        </SettingsCategoryPanel>

        {/* ═══ Modell & Leistung (Andi-Auftrag: Brain-Modell live umschaltbar) ═══ */}
        <SettingsCategoryPanel id="modell-leistung" active={activeCategory}>
          <BrainModelSection />
        </SettingsCategoryPanel>

        {/* ═══ Persönlichkeit ════════════════════════════════════════════ */}
        <SettingsCategoryPanel id="persoenlichkeit" active={activeCategory}>
          <section className="settings__group">
            <label className="settings__label" htmlFor="settings-persona">
              Persönlichkeit
            </label>
            <select
              id="settings-persona"
              className="settings__select"
              value={persona}
              onChange={(e) => onPersona(e.target.value as Persona)}
            >
              {PERSONAS.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                </option>
              ))}
            </select>
            {/* Live-Hint: zeigt die Beschreibung der aktuell gewählten Persönlichkeit. */}
            <p className="settings__hint">
              {PERSONAS.find((p) => p.id === persona)?.description}
            </p>
            {/* Self-demonstrating (Sara): Text-Hörprobe — ein Beispielsatz im echten
                Ton der Auswahl (kalibriert an PersonaService.toneLineDe + Few-Shots). */}
            <p className="settings__sample">
              So klinge ich: „{PERSONAS.find((p) => p.id === persona)?.sample}“
            </p>
          </section>
        </SettingsCategoryPanel>

        {/* ═══ Fähigkeiten ═══════════════════════════════════════════════
            Wecker-Eskalation ist neuer als die IA (DESIGN-settings-ia-2026-06-30.md
            kennt sie noch nicht) — sinngemäß hier eingereiht: sie steuert, wie
            der Wecker-/Timer-Skill sich über Geräte hinweg verhält. */}
        <SettingsCategoryPanel id="faehigkeiten" active={activeCategory}>
          {/* ── Skills (S2.3): Zwei-Stufen-Toggle, serverseitig ─────────── */}
          <SkillsSection
            skills={skills}
            language={language}
            loading={skillsLoading}
            error={skillsError}
            busyId={busyId}
            onToggle={toggle}
          />

          {/* ── Online-Nachschlag: welches Modell fürs schnelle Lookup ────
              Andi-Video-Auftrag — reiht sich hier ein (wie „Wikipedia-Wissen"
              in der IA-Doc: eine Nachschlage-Fähigkeit neben den Skills). */}
          <LookupModelSection />

          {/* ── Wecker-Eskalation: ab wann auch fremde Geräte bimmeln ─────
              Anker-Ziel des Zahnrads am Wecker-/Klingel-Banner (FiredToast). */}
          <SettingsAnchor id="wecker-eskalation" active={highlighted === 'wecker-eskalation'}>
            <EscalationSection
              seconds={escalationSeconds}
              onSeconds={onEscalationSeconds}
            />
          </SettingsAnchor>
        </SettingsCategoryPanel>

        {/* ═══ Gedächtnis & Privatsphäre ═════════════════════════════════ */}
        <SettingsCategoryPanel id="gedaechtnis-privatsphaere" active={activeCategory}>
          {/* ── Erkannte Sprecher (S2a): Anlernen + Verwalten (getrennt von HOSHIS Stimme) ──
              Anker-Ziel des Zahnrads am „Wer sprach"-Chip im Chat (SpeakerChip). */}
          <SettingsAnchor id="sprecher" active={highlighted === 'sprecher'}>
            <SpeakerSection />
          </SettingsAnchor>

          {/* ── Privatsphäre (Toms Vertrauens-Screen): ehrliche Übersicht + Lösch-API ── */}
          <PrivacySection />
        </SettingsCategoryPanel>

        {/* ═══ Standort & Integrationen ══════════════════════════════════
            Nachtmodus ist neuer als die IA — sinngemäß hier eingereiht: pro
            physischem Gerät/Ort, wie der Wetter-Ort direkt darüber. */}
        <SettingsCategoryPanel id="standort-integrationen" active={activeCategory}>
          {/* ── Wetter-Ort: der Standort für Wetter-Fragen, serverseitig ─────
              Anker-Ziel des Zahnrads an der Wetter-Kachel im Idle-Gesicht. */}
          <SettingsAnchor id="wetter-standort" active={highlighted === 'wetter-standort'}>
            <WeatherLocationSection />
          </SettingsAnchor>

          {/* ── Nachtmodus (Scheibe 3 von 3): pro Gerät, Nacht-Fenster-Dial ── */}
          <NightModeSection />
        </SettingsCategoryPanel>
      </aside>
    </div>
  );
}

/** Die leise ehrliche Zeile, wenn die Hörprobe scheitert (503/Netz/Audio-Decode). */
export const SAMPLE_ERROR_TEXT = 'Hörprobe grad nicht möglich.';

/**
 * **EscalationSection** — die Zahl-Einstellung „Eskalation nach … Sekunden" der
 * Wecker-Ursprungs-Lane. Sara-Ton: ein Wecker bimmelt zuerst nur am Gerät, wo er
 * gestellt wurde; klingt ihn dort niemand ab, ziehen nach X Sekunden ALLE Geräte
 * nach. Ein schlichter Zahl-Input (kein Select — die Option-Zählung der Settings-
 * Tests bleibt so unberührt), geklemmt auf {@link ESCALATION_MIN_SECONDS}–{@link
 * ESCALATION_MAX_SECONDS}. `onSeconds` optional: fehlt es (Panel ohne Verdrahtung),
 * ist der Input schreibgeschützt statt kaputt.
 */
export function EscalationSection({
  seconds,
  onSeconds,
}: {
  seconds: number;
  onSeconds?: (seconds: number) => void;
}) {
  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-escalation">
        Wecker-Eskalation
      </label>
      <div className="settings__escrow">
        <input
          id="settings-escalation"
          type="number"
          className="settings__number"
          min={ESCALATION_MIN_SECONDS}
          max={ESCALATION_MAX_SECONDS}
          step={1}
          value={seconds}
          disabled={!onSeconds}
          onChange={(e) => onSeconds?.(Number(e.target.value))}
        />
        <span className="settings__escunit">Sekunden</span>
      </div>
      <p className="settings__hint">
        Ein Wecker bimmelt erst am Gerät, wo du ihn gestellt hast — nach {seconds} Sekunden auf
        allen. So weckt dich dein Wecker zuerst leise dort, wo du bist, und wird erst laut überall,
        wenn niemand reagiert.
      </p>
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Wetter-Ort — der Standort für Wetter-Fragen (serverseitig, ein PUT pro Save)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ehrliche Texte des Wetter-Ort-Settings (auch von den Tests referenziert) —
 * jetzt eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().weatherLocation`, s. unten.
 */
export const WEATHER_LOCATION_TEXTS = de.weatherLocation;

/**
 * Container der Wetter-Ort-Gruppe: lädt den Ist-Zustand EINMAL beim Mount
 * (Idiom gespiegelt von {@link PrivacySection} — AbortController + aliveRef)
 * und führt den Speichern-Flow: PUT `{place}` ⇒ der Server geocodet und
 * antwortet mit dem AUFGELÖSTEN Label (Server-Wahrheit, nicht geraten).
 * 404 ⇒ ehrlich „Ort nicht gefunden.", 409 ⇒ „beim Deploy deaktiviert".
 */
export function WeatherLocationSection() {
  const t = useUiStrings();
  const WEATHER_LOCATION_TEXTS = t.weatherLocation;
  const [current, setCurrent] = useState<WeatherLocationSetting | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [place, setPlace] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void (async () => {
      try {
        const next = await fetchWeatherLocation(controller.signal);
        if (aliveRef.current) {
          setCurrent(next);
          setError(null);
        }
      } catch {
        if (aliveRef.current) setError(WEATHER_LOCATION_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
  }, []);

  const save = () => {
    const wanted = place.trim();
    if (busy || !wanted) return;
    setBusy(true);
    setNote(null);
    void (async () => {
      try {
        const updated = await saveWeatherLocation(wanted);
        if (!aliveRef.current) return;
        setCurrent(updated);
        setPlace('');
        setNote(WEATHER_LOCATION_TEXTS.saved(updated.label));
      } catch (e) {
        if (!aliveRef.current) return;
        if (e instanceof PlaceNotFoundError) setNote(WEATHER_LOCATION_TEXTS.notFound);
        else if (e instanceof WeatherLockedError) setNote(WEATHER_LOCATION_TEXTS.locked);
        else setNote(WEATHER_LOCATION_TEXTS.failed);
      } finally {
        if (aliveRef.current) setBusy(false);
      }
    })();
  };

  return (
    <WeatherLocationSectionView
      current={current}
      loading={loading}
      error={error}
      place={place}
      busy={busy}
      note={note}
      onPlace={setPlace}
      onSave={save}
    />
  );
}

export interface WeatherLocationSectionViewProps {
  current: WeatherLocationSetting | null;
  loading?: boolean;
  error?: string | null;
  place: string;
  busy?: boolean;
  note?: string | null;
  onPlace: (place: string) => void;
  onSave: () => void;
}

/**
 * Präsentations-Sektion des Wetter-Orts (prop-getrieben, gespiegelt von
 * {@link SkillsSection} — so im `node`-Vitest via `renderToStaticMarkup`
 * testbar). Regeln (ehrlich):
 *  - der aktuell wirksame Ort steht sichtbar da; kommt er noch aus dem Deploy
 *    (nichts gespeichert), sagt der Zusatz „(Standard aus dem Deploy)" das dazu.
 *  - Wetter beim Deploy aus ⇒ sichtbarer Hinweis — das Feld bleibt bedienbar,
 *    aber niemand glaubt an einen wirkenden Schalter (kein Fake-Zustand).
 *  - Speichern nur mit nicht-leerem Ort; während des PUT „speichert…" + disabled.
 */
export function WeatherLocationSectionView({
  current,
  loading,
  error,
  place,
  busy,
  note,
  onPlace,
  onSave,
}: WeatherLocationSectionViewProps) {
  const t = useUiStrings();
  const WEATHER_LOCATION_TEXTS = t.weatherLocation;
  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-weather-place">
        Wetter-Ort
      </label>
      {loading && !current && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <p className="settings__hint">
          Aktuell: {current.label}
          {!current.fromStore && WEATHER_LOCATION_TEXTS.seedSuffix}
        </p>
      )}
      <div className="settings__weatherrow">
        <input
          id="settings-weather-place"
          type="text"
          className="settings__text"
          placeholder="z. B. Duisburg"
          value={place}
          disabled={busy}
          onChange={(e) => onPlace(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') onSave();
          }}
        />
        <button
          type="button"
          className="settings__savebtn"
          disabled={busy || !place.trim()}
          onClick={onSave}
        >
          {busy ? WEATHER_LOCATION_TEXTS.saving : WEATHER_LOCATION_TEXTS.save}
        </button>
      </div>
      <p className="settings__hint">{WEATHER_LOCATION_TEXTS.hint}</p>
      {current && !current.weatherEnabled && (
        <p className="settings__hint">{WEATHER_LOCATION_TEXTS.locked}</p>
      )}
      {note && (
        <p className="settings__hint settings__weathernote" role="status">
          {note}
        </p>
      )}
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Online-Nachschlag — welches Modell fürs schnelle Lookup (Andi-Video-Auftrag)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ehrliche Texte des Lookup-Modell-Settings (auch von Tests referenziert) —
 * jetzt eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().lookupModel`, s. unten.
 */
export const LOOKUP_MODEL_TEXTS = de.lookupModel;

/**
 * Container der Lookup-Modell-Gruppe (Muster {@link WeatherLocationSection}):
 * lädt den Ist-Zustand EINMAL beim Mount, schaltet per Select-Auswahl direkt um
 * (Muster {@link SkillsSection} — eine Auswahl IST die Handlung, kein
 * zusätzlicher Speichern-Knopf) und liest danach den AUTORITATIVEN Server-
 * Zustand zurück (Readback, kein optimistisches Umschalten).
 */
export function LookupModelSection() {
  const t = useUiStrings();
  const LOOKUP_MODEL_TEXTS = t.lookupModel;
  const [current, setCurrent] = useState<LookupModelSetting | null>(null);
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
        const next = await fetchLookupModel(controller.signal);
        if (aliveRef.current) {
          setCurrent(next);
          setError(null);
        }
      } catch {
        if (aliveRef.current) setError(LOOKUP_MODEL_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
  }, []);

  const onSelect = (id: string) => {
    if (busy || id === current?.aktiv) return;
    setBusy(true);
    setNote(null);
    void (async () => {
      try {
        const updated = await saveLookupModel(id);
        if (!aliveRef.current) return;
        setCurrent(updated);
      } catch (e) {
        if (!aliveRef.current) return;
        setNote(e instanceof UnknownLookupModelError ? LOOKUP_MODEL_TEXTS.unknown : LOOKUP_MODEL_TEXTS.failed);
      } finally {
        if (aliveRef.current) setBusy(false);
      }
    })();
  };

  return (
    <LookupModelSectionView current={current} loading={loading} error={error} busy={busy} note={note} onSelect={onSelect} />
  );
}

export interface LookupModelSectionViewProps {
  current: LookupModelSetting | null;
  loading?: boolean;
  error?: string | null;
  busy?: boolean;
  note?: string | null;
  onSelect: (id: string) => void;
}

/**
 * Präsentations-Sektion des Lookup-Modells (prop-getrieben, Muster
 * {@link WeatherLocationSectionView} — per `renderToStaticMarkup` testbar).
 * Jede Option trägt Label + ca.-Preis-Info direkt im sichtbaren Text (Andis
 * Auftrag: „die kleine Preis-Info je Auswahl").
 */
export function LookupModelSectionView({
  current,
  loading,
  error,
  busy,
  note,
  onSelect,
}: LookupModelSectionViewProps) {
  const t = useUiStrings();
  const LOOKUP_MODEL_TEXTS = t.lookupModel;
  const selected = current?.modelle.find((m) => m.id === current.aktiv);
  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-lookup-model">
        {LOOKUP_MODEL_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <select
          id="settings-lookup-model"
          className="settings__select"
          value={current.aktiv}
          disabled={busy}
          onChange={(e) => onSelect(e.target.value)}
        >
          {current.modelle.map((m) => (
            <option key={m.id} value={m.id}>
              {m.label} — {LOOKUP_MODEL_TEXTS.priceSuffix(m.centsProLookup)}
            </option>
          ))}
        </select>
      )}
      {busy && <p className="settings__hint">{LOOKUP_MODEL_TEXTS.switching}</p>}
      {selected && !busy && (
        <p className="settings__hint">{LOOKUP_MODEL_TEXTS.priceSuffix(selected.centsProLookup)}</p>
      )}
      <p className="settings__hint">{LOOKUP_MODEL_TEXTS.hint}</p>
      {note && (
        <p className="settings__hint settings__lookupmodelnote" role="status">
          {note}
        </p>
      )}
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  TTS-Engine — welcher Adapter spricht (Andi-Video-Auftrag)
// ─────────────────────────────────────────────────────────────────────────────

/** Anzeige-Labels der vier Engines (die Wire-Ids selbst sind stabil, s. TtsEngineIds im BE). */
const TTS_ENGINE_LABELS: Record<string, string> = {
  openai: 'OpenAI (Cloud)',
  say: 'macOS say (lokal)',
  piper: 'Piper (lokal)',
  voxtral: 'Voxtral (lokal)',
};

/**
 * Ehrliche Texte des TTS-Engine-Settings (auch von Tests referenziert) — jetzt
 * eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().ttsEngine`, s. unten.
 */
export const TTS_ENGINE_TEXTS = de.ttsEngine;

export interface TtsEngineSectionViewProps {
  current: TtsSetting | null;
  loading?: boolean;
  error?: string | null;
  busy?: boolean;
  note?: string | null;
  onSelect: (id: string) => void;
}

/**
 * Präsentations-Sektion der TTS-Engine (prop-getrieben, Muster
 * {@link SkillsSection}): eine Zeile pro Engine mit ehrlichem Status-Badge
 * (aktiv/verfügbar/nicht gestartet) — Auswahl NUR bei `verfuegbar`, kein
 * Fake-Schalter, der nichts schaltet.
 */
export function TtsEngineSectionView({
  current,
  loading,
  error,
  busy,
  note,
  onSelect,
}: TtsEngineSectionViewProps) {
  const t = useUiStrings();
  const TTS_ENGINE_TEXTS = t.ttsEngine;
  return (
    <section className="settings__group">
      <h3 className="settings__label">{TTS_ENGINE_TEXTS.label}</h3>
      {loading && !current && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <div className="settings__skills">
          {current.engines.map((e) => {
            const isActive = e.id === current.aktiv;
            return (
              <div className="settings__skill" key={e.id}>
                <div className="settings__skillmeta">
                  <span className="settings__skillname">{TTS_ENGINE_LABELS[e.id] ?? e.id}</span>
                  <span className="settings__skillbadges">
                    {isActive && (
                      <span className="settings__badge settings__badge--egress">{TTS_ENGINE_TEXTS.active}</span>
                    )}
                    {!isActive && e.verfuegbar && (
                      <span className="settings__badge settings__badge--locked">{TTS_ENGINE_TEXTS.available}</span>
                    )}
                    {!e.verfuegbar && (
                      <span className="settings__badge settings__badge--locked">
                        {e.hinweis || TTS_ENGINE_TEXTS.notStarted}
                      </span>
                    )}
                  </span>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={isActive}
                  aria-label={TTS_ENGINE_LABELS[e.id] ?? e.id}
                  className={`settings__toggle ${isActive ? 'is-on' : ''}`}
                  disabled={isActive || !e.verfuegbar || busy}
                  onClick={() => onSelect(e.id)}
                >
                  <span className="settings__toggleknob" aria-hidden="true" />
                </button>
              </div>
            );
          })}
        </div>
      )}
      {busy && <p className="settings__hint">{TTS_ENGINE_TEXTS.switching}</p>}
      <p className="settings__hint">{TTS_ENGINE_TEXTS.hint}</p>
      {note && (
        <p className="settings__hint settings__ttsenginenote" role="status">
          {note}
        </p>
      )}
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Stimme — folgt der AKTIVEN Engine (Andi-Live-Befund 20.07): die alte
//  Stimme-Sektion zeigte den OpenAI-Cloud-Hinweis + die 13 OpenAI-Stimmen
//  IMMER, auch wenn piper/say gewählt war. Jetzt: EIN gemeinsamer Zustand mit
//  der TTS-Engine-Sektion ({@link TtsAndVoiceSection}) — die Stimmen-Liste
//  UND der Privacy-Hinweis richten sich nach `current.aktiv`.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ehrliche Texte der Stimmen-Sektion (auch von Tests referenziert) — jetzt eine
 * Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum bisherigen
 * Stand). Gerendert wird `useUiStrings().stimme`, s. unten.
 */
export const STIMME_TEXTS = de.stimme;

export interface StimmeSectionViewProps {
  current: TtsSetting | null;
  loading?: boolean;
  error?: string | null;
  /** Der Wert des Stimmen-Selects: bei openai die Client-Stimme, sonst `current.aktiveStimme`. */
  activeVoice: string;
  voiceBusy?: boolean;
  voiceNote?: string | null;
  sampleBusy?: boolean;
  sampleError?: string | null;
  onSelectVoice: (voice: string) => void;
  onPlaySample: () => void;
}

/**
 * Präsentations-Sektion der Stimmen-Wahl (prop-getrieben, Muster
 * {@link TtsEngineSectionView}): rendert die Stimmen DER AKTIVEN Engine
 * ({@link StimmeSectionViewProps.current}.stimmen) statt einer festen
 * OpenAI-Liste. Cloud-Privacy-Hinweis NUR bei `aktiv === 'openai'`; bei
 * say/piper/voxtral steht stattdessen ehrlich „läuft lokal — verlässt das
 * Gerät nicht". Ist die Stimmen-Liste leer (voxtral, oder ein Sidecar gerade
 * nicht erreichbar), steht nur der Server-Hinweis da (kein leeres `<select>`).
 * Der Hörprobe-Knopf bleibt unverändert (spricht ohnehin über die aktive
 * Engine, s. `TtsSampleController`/`DelegatingTtsPort`).
 */
export function StimmeSectionView({
  current,
  loading,
  error,
  activeVoice,
  voiceBusy,
  voiceNote,
  sampleBusy,
  sampleError,
  onSelectVoice,
  onPlaySample,
}: StimmeSectionViewProps) {
  const t = useUiStrings();
  const STIMME_TEXTS = t.stimme;
  const isOpenAi = current?.aktiv === 'openai';
  const engineLabel = current ? (TTS_ENGINE_LABELS[current.aktiv] ?? current.aktiv) : '';
  const selectedVoice = current?.stimmen.find((v) => v.id === activeVoice);

  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-voice">
        {STIMME_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && current.stimmen.length > 0 && (
        <div className="settings__voicerow">
          <select
            id="settings-voice"
            className="settings__select"
            value={activeVoice}
            disabled={voiceBusy}
            onChange={(e) => onSelectVoice(e.target.value)}
          >
            {current.stimmen.map((v) => (
              <option key={v.id} value={v.id}>
                {v.label}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="settings__samplebtn"
            onClick={onPlaySample}
            disabled={sampleBusy}
            aria-label={`Hörprobe der Stimme ${activeVoice} abspielen`}
            title="Hörprobe abspielen"
          >
            {sampleBusy ? <span className="settings__samplespin" aria-hidden="true" /> : <PlayGlyph />}
          </button>
        </div>
      )}
      {current && current.stimmen.length === 0 && (
        <p className="settings__hint">
          {current.stimmenHinweis || `Für ${engineLabel} stehen aktuell keine Stimmen zur Auswahl.`}
        </p>
      )}
      {current && (
        isOpenAi ? (
          <>
            <p className="settings__hint">
              {STIMME_TEXTS.cloudLine}{' '}
              <span className="settings__badge settings__badge--egress">{STIMME_TEXTS.cloudBadge}</span>
            </p>
            <p className="settings__hint">{STIMME_TEXTS.cloudPrivacy}</p>
          </>
        ) : (
          <p className="settings__hint">
            {STIMME_TEXTS.localLine} {STIMME_TEXTS.localPrivacy}
          </p>
        )
      )}
      {current && current.stimmen.length > 0 && current.stimmenHinweis && (
        <p className="settings__hint">{current.stimmenHinweis}</p>
      )}
      {selectedVoice?.lizenz && <p className="settings__hint">Lizenz: {selectedVoice.lizenz}</p>}
      {voiceBusy && <p className="settings__hint">{STIMME_TEXTS.switching}</p>}
      {voiceNote && (
        <p className="settings__hint settings__voicenote" role="status">
          {voiceNote}
        </p>
      )}
      {sampleError && (
        <p className="settings__hint settings__hint--sample-error" role="status">
          {sampleError}
        </p>
      )}
    </section>
  );
}

/**
 * **TtsAndVoiceSection** — der gemeinsame Container von TTS-Engine-Wahl UND
 * Stimmen-Wahl (Andi-Live-Befund: „Stimme muss der aktiven Engine folgen").
 * EIN Fetch/Zustand ({@link TtsSetting}) treibt BEIDE Präsentations-Sektionen
 * ({@link TtsEngineSectionView}/{@link StimmeSectionView}): schaltet die
 * Engine-Sektion die Engine um, liefert die PUT-Antwort (Readback) bereits die
 * Stimmen-Liste DER NEUEN Engine mit — kein zweiter Fetch nötig, die
 * Stimmen-Sektion liest denselben `current`-Zustand und zeigt sie sofort.
 *
 * **openai bleibt Client-seitig** (unverändertes Bestandsverhalten): die
 * OpenAI-Stimme wird weiterhin über `voice`/`onVoice` (localStorage,
 * `useSettings`) gewählt — sie fließt PRO TURN in den Chat-Request
 * (`ChatRequest.voice`), unabhängig vom Server-Store. `say`/`piper` haben
 * dagegen KEINE Per-Turn-Voice (die Adapter ignorieren sie ehrlich) — ihre
 * Stimme geht über `PUT /api/v1/settings/tts {id, voice}` und wird server-
 * seitig gemerkt ([JsonFileTtsEngineStore.setVoice]).
 */
export function TtsAndVoiceSection({
  voice,
  onVoice,
}: {
  voice: string;
  onVoice: (voice: string) => void;
}) {
  const t = useUiStrings();
  const TTS_ENGINE_TEXTS = t.ttsEngine;
  const STIMME_TEXTS = t.stimme;
  const [current, setCurrent] = useState<TtsSetting | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [engineBusy, setEngineBusy] = useState(false);
  const [engineNote, setEngineNote] = useState<string | null>(null);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const [voiceNote, setVoiceNote] = useState<string | null>(null);
  const [sampleBusy, setSampleBusy] = useState(false);
  const [sampleError, setSampleError] = useState<string | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void (async () => {
      try {
        const next = await fetchTtsSettings(controller.signal);
        if (aliveRef.current) {
          setCurrent(next);
          setError(null);
        }
      } catch {
        if (aliveRef.current) setError(TTS_ENGINE_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
  }, []);

  const onSelectEngine = (id: string) => {
    if (engineBusy || id === current?.aktiv) return;
    setEngineBusy(true);
    setEngineNote(null);
    void (async () => {
      try {
        // Die Readback-Antwort trägt bereits `stimmen`/`aktiveStimme` DER NEUEN
        // Engine — die Stimmen-Sektion (liest denselben `current`) zeigt sie
        // sofort, ganz ohne einen zweiten Fetch.
        const updated = await saveTtsEngine(id);
        if (!aliveRef.current) return;
        setCurrent(updated);
      } catch (e) {
        if (!aliveRef.current) return;
        if (e instanceof EngineUnavailableError) setEngineNote(TTS_ENGINE_TEXTS.unavailable(e.message));
        else if (e instanceof UnknownEngineError) setEngineNote(TTS_ENGINE_TEXTS.unknown);
        else setEngineNote(TTS_ENGINE_TEXTS.failed);
        // Ehrlicher Ist-Stand nach einem Fehlschlag neu laden (der Server könnte
        // sich seit dem letzten GET verändert haben) — best-effort, still.
        try {
          const next = await fetchTtsSettings();
          if (aliveRef.current) setCurrent(next);
        } catch {
          /* die Notiz steht schon */
        }
      } finally {
        if (aliveRef.current) setEngineBusy(false);
      }
    })();
  };

  const onSelectVoice = (value: string) => {
    if (!current) return;
    // openai bleibt Client-seitig (localStorage via useSettings) — kein PUT,
    // die Stimme fließt stattdessen pro Turn mit dem Chat-Request.
    if (current.aktiv === 'openai') {
      onVoice(value);
      return;
    }
    if (voiceBusy || value === current.aktiveStimme) return;
    setVoiceBusy(true);
    setVoiceNote(null);
    void (async () => {
      try {
        const updated = await saveTtsVoice(current.aktiv, value);
        if (!aliveRef.current) return;
        setCurrent(updated);
      } catch (e) {
        if (!aliveRef.current) return;
        if (e instanceof UnknownVoiceError) setVoiceNote(STIMME_TEXTS.unknownVoice);
        else setVoiceNote(STIMME_TEXTS.failed);
        try {
          const next = await fetchTtsSettings();
          if (aliveRef.current) setCurrent(next);
        } catch {
          /* die Notiz steht schon */
        }
      } finally {
        if (aliveRef.current) setVoiceBusy(false);
      }
    })();
  };

  // Der Select-Wert der Stimmen-Sektion: bei openai die Client-Stimme (dieselbe,
  // die auch in den Chat-Request fließt), sonst die Server-Wahrheit (aktiveStimme).
  const activeVoice = current ? (current.aktiv === 'openai' ? voice : current.aktiveStimme ?? '') : voice;

  const playSample = async () => {
    if (sampleBusy) return;
    setSampleBusy(true);
    setSampleError(null);
    try {
      const blob = await fetchVoiceSample(activeVoice);
      const url = URL.createObjectURL(blob);
      const audio = new Audio(url);
      const release = () => URL.revokeObjectURL(url);
      audio.onended = release;
      audio.onerror = release;
      await audio.play();
    } catch {
      setSampleError(SAMPLE_ERROR_TEXT);
    } finally {
      setSampleBusy(false);
    }
  };

  return (
    <>
      <TtsEngineSectionView
        current={current}
        loading={loading}
        error={error}
        busy={engineBusy}
        note={engineNote}
        onSelect={onSelectEngine}
      />
      <StimmeSectionView
        current={current}
        loading={loading}
        error={error}
        activeVoice={activeVoice}
        voiceBusy={voiceBusy}
        voiceNote={voiceNote}
        sampleBusy={sampleBusy}
        sampleError={sampleError}
        onSelectVoice={onSelectVoice}
        onPlaySample={() => void playSample()}
      />
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Brain (LLM) — welches Modell der Brain-Sidecar live fährt (Scope-Erweiterung)
// ─────────────────────────────────────────────────────────────────────────────

/** Wie oft (ms) nach einem PUT auf `status=ok` mit dem neuen Modell gepollt wird. */
export const BRAIN_POLL_INTERVAL_MS = 4000;
/** Wie lange (ms) maximal gepollt wird, bevor ehrlich „dauert länger als erwartet" steht. */
export const BRAIN_POLL_TIMEOUT_MS = 130000;

/**
 * Ehrliche Texte des Brain-Modell-Settings (auch von Tests referenziert) —
 * jetzt eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().brainModel`, s. unten.
 */
export const BRAIN_MODEL_TEXTS = de.brainModel;

/**
 * Ehrlicher Klartext zum rohen `status`-Feld (roh durchgereicht, wenn
 * unbekannt). Nimmt den aktiven Text-Katalog explizit entgegen (Default: der
 * DE-Katalog) — der Aufrufer (eine Komponente, {@link useUiStrings}) reicht die
 * aktive Sprache durch.
 */
function brainStatusLabel(status: string, texts: BrainModelStrings = BRAIN_MODEL_TEXTS): string {
  if (status === 'ok') return texts.statusOk;
  if (status === 'loading') return texts.statusLoading;
  if (status === 'unreachable') return texts.statusUnreachable;
  return status;
}

/**
 * Container der Brain-Modell-Gruppe: lädt den Ist-Zustand EINMAL beim Mount,
 * schaltet per Select-Auswahl um und POLLT danach `GET` weiter (alle
 * {@link BRAIN_POLL_INTERVAL_MS}), bis `status=ok` MIT dem neu gewählten Modell
 * steht oder {@link BRAIN_POLL_TIMEOUT_MS} verstreicht (ehrlicher Timeout-
 * Hinweis, kein endloses stilles Warten). KEIN optimistisches UI: `current`
 * zeigt IMMER den zuletzt vom Server gelesenen Zustand, nie das erhoffte Ziel.
 */
export function BrainModelSection() {
  const t = useUiStrings();
  const BRAIN_MODEL_TEXTS = t.brainModel;
  const [current, setCurrent] = useState<BrainSetting | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [pending, setPending] = useState<{ id: string; label: string } | null>(null);
  const aliveRef = useRef(true);
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollDeadlineRef = useRef(0);

  const stopPolling = () => {
    if (pollTimerRef.current) {
      clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  };

  const pollOnce = (targetId: string) => {
    void (async () => {
      try {
        const next = await fetchBrainSettings();
        if (!aliveRef.current) return;
        setCurrent(next);
        if (next.status === 'ok' && next.aktiv === targetId) {
          setPending(null);
          stopPolling();
          return;
        }
      } catch {
        /* ein einzelner Poll-Fehlschlag reisst den Poll nicht ab — best-effort. */
      }
      if (!aliveRef.current) return;
      if (Date.now() >= pollDeadlineRef.current) {
        setNote(BRAIN_MODEL_TEXTS.timeout);
        setPending(null);
        stopPolling();
        return;
      }
      pollTimerRef.current = setTimeout(() => pollOnce(targetId), BRAIN_POLL_INTERVAL_MS);
    })();
  };

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void (async () => {
      try {
        const next = await fetchBrainSettings(controller.signal);
        if (aliveRef.current) {
          setCurrent(next);
          setError(null);
        }
      } catch {
        if (aliveRef.current) setError(BRAIN_MODEL_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
      stopPolling();
    };
  }, []);

  const onSelect = (id: string) => {
    if (pending || id === current?.aktiv) return;
    const label = current?.modelle.find((m) => m.id === id)?.label ?? id;
    setNote(null);
    setPending({ id, label });
    void (async () => {
      try {
        const updated = await saveBrainModel(id);
        if (!aliveRef.current) return;
        setCurrent(updated);
        if (updated.status === 'ok' && updated.aktiv === id) {
          // Seltener Sofort-Fall (z.B. Fake/Test) — kein Poll nötig.
          setPending(null);
          return;
        }
        pollDeadlineRef.current = Date.now() + BRAIN_POLL_TIMEOUT_MS;
        pollTimerRef.current = setTimeout(() => pollOnce(id), BRAIN_POLL_INTERVAL_MS);
      } catch (e) {
        if (!aliveRef.current) return;
        setPending(null);
        if (e instanceof UnknownBrainModelError) setNote(BRAIN_MODEL_TEXTS.unknown);
        else if (e instanceof BrainSwitchUnavailableError) setNote(BRAIN_MODEL_TEXTS.switchUnavailable);
        else setNote(BRAIN_MODEL_TEXTS.failed);
      }
    })();
  };

  return (
    <BrainModelSectionView
      current={current}
      loading={loading}
      error={error}
      pending={pending}
      note={note}
      onSelect={onSelect}
    />
  );
}

export interface BrainModelSectionViewProps {
  current: BrainSetting | null;
  loading?: boolean;
  error?: string | null;
  pending?: { id: string; label: string } | null;
  note?: string | null;
  onSelect: (id: string) => void;
}

/**
 * Präsentations-Sektion des Brain-Modells (prop-getrieben, Muster
 * {@link LookupModelSectionView}). Während {@link BrainModelSectionViewProps.pending}
 * gesetzt ist, steht der ehrliche „wechselt… 60-120s"-Hinweis UND das Select ist
 * gesperrt — kein zweiter Wechsel-Anstoß mitten in einem laufenden.
 */
export function BrainModelSectionView({
  current,
  loading,
  error,
  pending,
  note,
  onSelect,
}: BrainModelSectionViewProps) {
  const t = useUiStrings();
  const BRAIN_MODEL_TEXTS = t.brainModel;
  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-brain-model">
        {BRAIN_MODEL_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <>
          <select
            id="settings-brain-model"
            className="settings__select"
            value={current.aktiv || pending?.id || ''}
            disabled={!!pending}
            onChange={(e) => onSelect(e.target.value)}
          >
            {!current.aktiv && (
              <option value="" disabled>
                (Status wird gelesen…)
              </option>
            )}
            {current.modelle.map((m) => (
              <option key={m.id} value={m.id}>
                {m.label}
              </option>
            ))}
          </select>
          <p className="settings__hint">
            Status: {brainStatusLabel(current.status, BRAIN_MODEL_TEXTS)}
            {current.aktiv && ` (${current.modelle.find((m) => m.id === current.aktiv)?.label ?? current.aktiv})`}
          </p>
        </>
      )}
      {pending && (
        <p className="settings__hint" role="status">
          {BRAIN_MODEL_TEXTS.switching(pending.label)}
        </p>
      )}
      <p className="settings__hint">{BRAIN_MODEL_TEXTS.hint}</p>
      {note && (
        <p className="settings__hint settings__brainmodelnote" role="status">
          {note}
        </p>
      )}
    </section>
  );
}

/** Zweisprachige Badge-Texte (Skill-Labels selbst kommen aus der Registry/Wire). */
const SKILL_BADGES = {
  locked: { de: 'deaktiviert beim Deploy', en: 'disabled at deploy' },
  egress: { de: 'geht online', en: 'goes online' },
  soon: { de: 'kommt noch', en: 'coming' },
} as const;

/**
 * Ehrliche Zukunfts-Skills — statisch ausgegraut MIT Grund, bewusst OHNE Toggle
 * (kein Fake-Schalter, der nichts schaltet). Sobald ein Skill real im Backend
 * landet, kommt er über die Registry/Wire-Liste und fliegt hier raus.
 * (WEATHER ist raus: der Wetter-Ort ist jetzt eine echte Sektion, siehe
 * {@link WeatherLocationSection}.)
 */
export const FUTURE_SKILLS: {
  id: string;
  label: { de: string; en: string };
  reason: { de: string; en: string };
}[] = [
  {
    id: 'LISTS',
    label: { de: 'Listen', en: 'Lists' },
    reason: { de: 'Andi-Gabel offen', en: 'decision with Andi still open' },
  },
  {
    id: 'MUSIC',
    label: { de: 'Musik', en: 'Music' },
    reason: { de: 'Track startet', en: 'first slice: a track starts' },
  },
];

interface SkillsSectionProps {
  skills: Skill[];
  language: Language;
  loading?: boolean;
  error?: string | null;
  busyId?: string | null;
  onToggle: (id: string) => void;
}

/**
 * Präsentations-Sektion der Skill-Toggles (eine Zeile pro Skill), bewusst als
 * eigene, prop-getriebene Komponente (gespiegelt von {@link OpsStatusPill}) — so
 * im `node`-Vitest via `renderToStaticMarkup` testbar, ohne Live-Backend.
 *
 * Regeln (ehrlich):
 *  - `locked` ⇒ Toggle disabled + Badge „deaktiviert beim Deploy".
 *  - `tier === 'EGRESS'` ⇒ Badge „geht online" (greift, sobald CURRENCY/ONLINE_LOOKUP kommen).
 *  - Der Schalter spiegelt `enabled`; die Decke (`locked`/`effective`) sagt das Badge.
 */
export function SkillsSection({
  skills,
  language,
  loading,
  error,
  busyId,
  onToggle,
}: SkillsSectionProps) {
  const lang: 'de' | 'en' = language === 'en' ? 'en' : 'de';
  return (
    <section className="settings__group">
      <h3 className="settings__label">Skills</h3>
      <p className="settings__hint">
        Schaltet einzelne Fähigkeiten zur Laufzeit ein/aus — serverseitig, gilt für alle Geräte.
      </p>
      {loading && skills.length === 0 && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      <div className="settings__skills">
        {skills.map((s) => {
          const name = lang === 'en' ? s.labelEn : s.labelDe;
          const busy = busyId === s.id;
          return (
            <div className="settings__skill" key={s.id}>
              <div className="settings__skillmeta">
                <span className="settings__skillname">{name}</span>
                {(s.tier === 'EGRESS' || s.locked) && (
                  <span className="settings__skillbadges">
                    {s.tier === 'EGRESS' && (
                      <span className="settings__badge settings__badge--egress">
                        {SKILL_BADGES.egress[lang]}
                      </span>
                    )}
                    {s.locked && (
                      <span className="settings__badge settings__badge--locked">
                        {SKILL_BADGES.locked[lang]}
                      </span>
                    )}
                  </span>
                )}
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={s.enabled}
                aria-label={name}
                className={`settings__toggle ${s.enabled ? 'is-on' : ''}`}
                disabled={s.locked || busy}
                onClick={() => onToggle(s.id)}
              >
                <span className="settings__toggleknob" aria-hidden="true" />
              </button>
            </div>
          );
        })}
        {/* Zukunfts-Skills: ausgegraut mit ehrlichem Grund — KEIN Fake-Toggle. */}
        {FUTURE_SKILLS.map((f) => (
          <div className="settings__skill settings__skill--future" key={f.id}>
            <div className="settings__skillmeta">
              <span className="settings__skillname">{f.label[lang]}</span>
              <span className="settings__skillreason">{f.reason[lang]}</span>
            </div>
            <span className="settings__badge settings__badge--soon">{SKILL_BADGES.soon[lang]}</span>
          </div>
        ))}
      </div>
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Privatsphäre (Toms Vertrauens-Screen) — ehrliche Übersicht + Lösch-API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ehrliche Notiz-Texte des Lösch-Flows (auch von den Tests referenziert) —
 * jetzt eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().privacy`, s. unten.
 */
export const PRIVACY_TEXTS = de.privacy;

/** Wie lange der scharfe Zweitklick-Zustand hält, bevor er sich selbst entschärft. */
const PRIVACY_ARM_TIMEOUT_MS = 5000;

/** Einheit je Datenart für die Erfolgs-Notiz („Gelöscht: N …"). */
const PRIVACY_UNITS: Record<PrivacyTarget, [string, string]> = {
  memory: ['Eintrag', 'Einträge'],
  episodic: ['Eintrag', 'Einträge'],
  diary: ['Tages-Datei', 'Tages-Dateien'],
};

/**
 * Container der Privatsphäre-Gruppe: lädt die Summary EINMAL beim Mount (Idiom
 * gespiegelt von {@link useSkills} — AbortController + aliveRef) und führt den
 * Zweitklick-Lösch-Flow. Erster Klick SCHÄRFT nur („Wirklich? Klick nochmal",
 * entschärft sich nach {@link PRIVACY_ARM_TIMEOUT_MS} von selbst); erst der
 * zweite Klick ruft DELETE. Nach Erfolg wird die Summary NEU vom Server geladen
 * (die Zahlen bleiben Server-Wahrheit, nicht geraten). 501 ⇒ ehrlich „kommt noch".
 */
export function PrivacySection() {
  const t = useUiStrings();
  const PRIVACY_TEXTS = t.privacy;
  const [summary, setSummary] = useState<PrivacySummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [armed, setArmed] = useState<PrivacyTarget | null>(null);
  const [busy, setBusy] = useState<PrivacyTarget | null>(null);
  const [notes, setNotes] = useState<Partial<Record<PrivacyTarget, string>>>({});
  const aliveRef = useRef(true);
  const armTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void (async () => {
      try {
        const next = await fetchPrivacySummary(controller.signal);
        if (aliveRef.current) {
          setSummary(next);
          setError(null);
        }
      } catch {
        if (aliveRef.current) setError(PRIVACY_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
      if (armTimerRef.current) clearTimeout(armTimerRef.current);
    };
  }, []);

  const handleDelete = (target: PrivacyTarget) => {
    if (busy) return;
    if (armTimerRef.current) clearTimeout(armTimerRef.current);
    // Erster Klick: nur schärfen — nichts löschen.
    if (armed !== target) {
      setArmed(target);
      armTimerRef.current = setTimeout(() => {
        if (aliveRef.current) setArmed(null);
      }, PRIVACY_ARM_TIMEOUT_MS);
      return;
    }
    // Zweiter Klick: wirklich löschen.
    setArmed(null);
    setBusy(target);
    setNotes((n) => ({ ...n, [target]: undefined }));
    void (async () => {
      try {
        const res = await deletePrivacyData(target);
        if (!aliveRef.current) return;
        const [one, many] = PRIVACY_UNITS[target];
        setNotes((n) => ({
          ...n,
          [target]: `Gelöscht: ${res.deleted} ${res.deleted === 1 ? one : many}.`,
        }));
        // Server-Wahrheit nachladen (Counts/exists), best-effort.
        try {
          const next = await fetchPrivacySummary();
          if (aliveRef.current) setSummary(next);
        } catch {
          /* Notiz steht schon — eine misslungene Auffrischung kippt sie nicht. */
        }
      } catch (e) {
        if (!aliveRef.current) return;
        setNotes((n) => ({
          ...n,
          [target]: e instanceof PrivacyNotYetError ? PRIVACY_TEXTS.notYet : PRIVACY_TEXTS.failed,
        }));
      } finally {
        if (aliveRef.current) setBusy(null);
      }
    })();
  };

  return (
    <PrivacySectionView
      summary={summary}
      loading={loading}
      error={error}
      armed={armed}
      busy={busy}
      notes={notes}
      onDelete={handleDelete}
    />
  );
}

export interface PrivacySectionViewProps {
  summary: PrivacySummary | null;
  loading?: boolean;
  error?: string | null;
  armed?: PrivacyTarget | null;
  busy?: PrivacyTarget | null;
  notes?: Partial<Record<PrivacyTarget, string>>;
  onDelete: (target: PrivacyTarget) => void;
}

/** Eine Store-Zeile ehrlich in Worte gefasst (nie eine erfundene Zahl). */
function storeDetail(info: PrivacySummary['memory']): string {
  if (!info.exists) return 'noch nichts gespeichert';
  const count = info.entries === null ? 'Anzahl grad nicht lesbar' : `${info.entries} Einträge`;
  return info.enabled ? count : `${count} — Aufzeichnung ist aus, alte Einträge liegen noch da`;
}

/**
 * Präsentations-Sektion der Privatsphäre (prop-getrieben, gespiegelt von
 * {@link SkillsSection} — so im `node`-Vitest via `renderToStaticMarkup` testbar).
 *
 * Regeln (ehrlich):
 *  - Jede Zeile trägt ihr Schloss-Glyph (bleibt lokal) oder Wolken-Glyph (geht
 *    online) — muted SVG statt Emoji, direkt aus der Server-Summary, nichts
 *    behauptet.
 *  - „Was maskiert wird" steht dabei: Tokens/URLs/IPs/UUIDs/Smart-Home-IDs — Namen
 *    und normaler Inhalt bleiben (warmes Audio, keine Zensur).
 *  - Löschen nur per Zweitklick; der scharfe Knopf sagt „Wirklich? Klick nochmal".
 *  - 501 ⇒ „kommt noch", Fehler ⇒ ehrliche Fehlzeile — nie stilles Scheitern.
 */
export function PrivacySectionView({
  summary,
  loading,
  error,
  armed,
  busy,
  notes,
  onDelete,
}: PrivacySectionViewProps) {
  const t = useUiStrings();
  const PRIVACY_TEXTS = t.privacy;
  const deleteButton = (target: PrivacyTarget, label: string) => {
    const isArmed = armed === target;
    const isBusy = busy === target;
    return (
      <button
        type="button"
        className={`settings__deletebtn ${isArmed ? 'is-armed' : ''}`}
        disabled={isBusy}
        aria-label={`${label} löschen`}
        onClick={() => onDelete(target)}
      >
        {isBusy ? PRIVACY_TEXTS.deleting : isArmed ? PRIVACY_TEXTS.confirm : PRIVACY_TEXTS.delete}
      </button>
    );
  };

  const note = (target: PrivacyTarget) =>
    notes?.[target] ? (
      <p className="settings__hint settings__privacynote" role="status">
        {notes[target]}
      </p>
    ) : null;

  return (
    <section className="settings__group">
      <h3 className="settings__label">Privatsphäre</h3>
      <p className="settings__hint">
        Ehrlicher Ist-Stand, direkt vom Server gelesen — das Schloss bleibt auf der Box, die
        Wolke geht online.
      </p>
      {loading && !summary && <p className="settings__hint">lädt…</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {summary && (
        <div className="settings__privacy">
          {/* Stimme: der einzige Egress-Pfad — ehrlich benannt. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                {summary.voice.cloud ? <CloudGlyph /> : <LockGlyph />} Stimme (TTS):{' '}
                {summary.voice.engine}
              </span>
              <span className="settings__privacydetail">
                {summary.voice.cloud
                  ? 'Antworttext geht für die Sprachausgabe zu OpenAI.'
                  : 'läuft lokal — kein Text verlässt die Box.'}
              </span>
            </div>
          </div>

          {/* Cloud-Maskierung: was VOR einem Cloud-Call maskiert wird. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                {summary.sanitize.enabled ? (
                  <>
                    <LockGlyph /> Cloud-Maskierung: aktiv
                  </>
                ) : (
                  <>
                    <WarnGlyph /> Cloud-Maskierung: aus
                  </>
                )}
              </span>
              <span className="settings__privacydetail">
                {summary.sanitize.enabled
                  ? 'Vor jedem Cloud-Call maskiert: Tokens, URLs, IP-Adressen, UUIDs, Smart-Home-IDs. Namen und normaler Inhalt bleiben.'
                  : 'Der Antworttext geht unmaskiert in die Cloud. Aktiviert maskiert Hoshi Tokens, URLs, IP-Adressen, UUIDs und Smart-Home-IDs — Namen bleiben.'}
              </span>
            </div>
          </div>

          {/* Gedächtnis (Fakten) — lokal, löschbar. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                <LockGlyph /> Gedächtnis (Fakten über dich)
              </span>
              <span className="settings__privacydetail">
                lokale Datei · {storeDetail(summary.memory)}
              </span>
            </div>
            {deleteButton('memory', 'Gedächtnis')}
          </div>
          {note('memory')}

          {/* Episoden-Gedächtnis — lokal, löschbar. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                <LockGlyph /> Episoden-Gedächtnis (frühere Gespräche)
              </span>
              <span className="settings__privacydetail">
                lokale Datei · {storeDetail(summary.episodic)}
              </span>
            </div>
            {deleteButton('episodic', 'Episoden-Gedächtnis')}
          </div>
          {note('episodic')}

          {/* Nutzungs-Diary — lokal, ohne Gesprächs-Inhalte, löschbar. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                <LockGlyph /> Nutzungs-Diary (Technik-Protokoll)
              </span>
              <span className="settings__privacydetail">
                {summary.diary.days === 1 ? '1 Tages-Datei' : `${summary.diary.days} Tages-Dateien`} ·
                enthält keine Gesprächs-Inhalte, nur Timing/Kategorie
              </span>
            </div>
            {deleteButton('diary', 'Nutzungs-Diary')}
          </div>
          {note('diary')}
        </div>
      )}
    </section>
  );
}
