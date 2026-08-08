import { useEffect, useRef, useState } from 'react';
// Alias, damit das globale DOM-`KeyboardEvent` (window.addEventListener weiter unten)
// nicht vom React-Synthetic-Event-Typ verdeckt wird.
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react';
import type { Language, Skill } from '../api/types';
import {
  type Persona,
  type SoraTheme,
  type Theme,
  type ThemeGroupId,
  DEFAULT_ESCALATION_SECONDS,
  ESCALATION_MAX_SECONDS,
  ESCALATION_MIN_SECONDS,
  LANGUAGES,
  PERSONAS,
  SORA_ROTATION,
  THEME_GROUPS,
  useResolvedTheme,
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
  type EscalationModeWire,
  type ExtendedThinkSetting,
  ESCALATION_MODES,
  EscalationLockedError,
  UnknownEscalationModeError,
  fetchExtendedThink,
  saveExtendedThinkMode,
} from '../api/extendedThink';
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
import { fetchBrainAutoSwitch, saveBrainAutoSwitch } from '../api/brainAutoSwitch';
import { de } from '../i18n/de';
import { useActiveUiLanguage, useUiStrings } from '../i18n';
import type { BrainModelStrings, FutureSkillId, SettingsPanelStrings } from '../i18n/types';
import { CloudGlyph, LockGlyph, PlayGlyph, WarnGlyph } from './icons';

// ─────────────────────────────────────────────────────────────────────────────
//  Kategorie-Navigation (Andi 15.07: „hier müssen wir zu weit scrollen, daher
//  organisiere das bitte übersichtlich neu"; IA-Referenz vault/tracks/
//  DESIGN-settings-ia-2026-06-30.md). NEU sortiert 26.07 (Andi-Auftrag: „die
//  komplette Online-Nachschau-Funktion soll über die Einstellungen in einer
//  geeigneten Gruppierung einstellbar sein — überdenke die Anordnung der
//  kompletten Einstellungen"). Zwei Änderungen gegenüber dem bisherigen Stand:
//   1. Neue Kategorie 'online-nachschlagen' bündelt ALLES Online-Verhalten an
//      einem Ort: die vier Extended-Think-Stufen ({@link ExtendedThinkSection},
//      vorher gar kein UI-Element — nur Backend) + das Nachschlag-Modell
//      ({@link LookupModelSection}, zieht aus der ehemaligen Fähigkeiten-
//      Kategorie hierher). Die Cloud-TTS-Engine bleibt bewusst bei „Sprache &
//      Stimme" — sie ist Stimme, nicht Wissen.
//   2. Die ehemalige Fähigkeiten-Kategorie ist aufgelöst: das Nachschlag-Modell
//      zieht wie oben nach 'online-nachschlagen', die Skills-Toggles und die
//      Wecker-Eskalation ziehen in die umbenannte 'zuhause-integrationen'
//      (vorher 'standort-integrationen') — beides sind Fähigkeiten IM Zuhause
//      (Smart-Home/Szenen/Timer neben Wetter-Ort/HA/Wecker). So bleiben es
//      sieben Kategorien, keine verwaist, keine doppelt.
//
//  Bewusst KEIN Unmount pro Kategorie: jede Sektion bleibt immer gemountet, der
//  Wechsel schaltet nur das native `hidden`-Attribut (kein Kill der laufenden
//  Fetches/Hooks, kein Options-Schwund in den Server-Static-Renders/Tests — die
//  ausgeblendeten Panels stehen weiter im HTML, nur `[hidden]` blendet sie aus).
// ─────────────────────────────────────────────────────────────────────────────

export type SettingsCategoryId =
  | 'darstellung'
  | 'sprache-stimme'
  | 'online-nachschlagen'
  | 'modell-leistung'
  | 'persoenlichkeit'
  | 'gedaechtnis-privatsphaere'
  | 'zuhause-integrationen';

/**
 * Die Reiter in ihrer dokumentierten REIHENFOLGE — Online-Grad als Ordnungs-
 * Prinzip in der Mitte (Darstellung/Sprache zuerst, dann Online & Nachschlagen,
 * Modell & Leistung, Persönlichkeit, dann die beiden Datenschutz-/Heim-
 * Kategorien). 'online-nachschlagen' ist neu (Andi-Auftrag 26.07); die alte
 * Reihenfolge hatte 'persoenlichkeit' vor 'modell-leistung' — bewusst getauscht,
 * damit die drei Online-/Technik-Kategorien (Sprache & Stimme · Online &
 * Nachschlagen · Modell & Leistung) zusammenstehen.
 */
export const SETTINGS_CATEGORY_IDS: readonly SettingsCategoryId[] = [
  'darstellung',
  'sprache-stimme',
  'online-nachschlagen',
  'modell-leistung',
  'persoenlichkeit',
  'gedaechtnis-privatsphaere',
  'zuhause-integrationen',
];

/**
 * Reihenfolge + DEUTSCHE Labels der Reiter. Die Labels waren bis 25.07 eine
 * hartkodierte Modul-Konstante und standen darum IMMER deutsch da, egal welche
 * UI-Sprache aktiv war; jetzt sind sie eine Referenz auf den `de`-Katalog
 * (byte-gleich zum bisherigen Stand, von Bestandstests referenziert) — gerendert
 * wird `useUiStrings().settings.categories`, s. {@link SettingsCategoryNav}.
 */
export const SETTINGS_CATEGORIES: { id: SettingsCategoryId; label: string }[] =
  SETTINGS_CATEGORY_IDS.map((id) => ({ id, label: de.settings.categories[id] }));

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
  'wetter-standort': 'zuhause-integrationen',
  sprecher: 'gedaechtnis-privatsphaere',
  'wecker-eskalation': 'zuhause-integrationen',
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
  const t = useUiStrings().settings;
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
      aria-label={t.categoryNavAria}
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
            {t.categories[c.id]}
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
 * Einstellungs-Drawer (rechts): sieben Kategorien über eine Reiter-Leiste
 * ({@link SettingsCategoryNav}) statt einer einzigen langen Scroll-Wand (Andi
 * 15.07: „hier müssen wir zu weit scrollen, daher organisiere das bitte
 * übersichtlich neu"; Neuordnung 26.07: „gruppiere sinnig, ordne alles
 * aufgeräumt"). IA-Referenz: vault/tracks/DESIGN-settings-ia-2026-06-30.md.
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
  const t = useUiStrings();
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
        aria-label={t.topNav.settingsTitle}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="settings__head">
          <h2 className="settings__title">{t.topNav.settingsTitle}</h2>
          <button
            ref={closeRef}
            type="button"
            className="settings__close"
            onClick={onClose}
            aria-label={t.topNav.closeSettingsAria}
          >
            ✕
          </button>
        </header>

        <SettingsCategoryNav active={activeCategory} onSelect={setActiveCategory} />

        {/* ═══ Darstellung ═══════════════════════════════════════════════ */}
        <SettingsCategoryPanel id="darstellung" active={activeCategory}>
          {/* ── Farbthema: drei Gruppen statt einer 8er-Liste ───────────── */}
          <ThemeSection theme={theme} onTheme={onTheme} />
        </SettingsCategoryPanel>

        {/* ═══ Sprache & Stimme ══════════════════════════════════════════ */}
        <SettingsCategoryPanel id="sprache-stimme" active={activeCategory}>
          {/* ── Sprache (Chat + STT) ────────────────────────────────────── */}
          <section className="settings__group">
            <label className="settings__label" htmlFor="settings-language">
              {t.settings.languageLabel}
            </label>
            <select
              id="settings-language"
              className="settings__select"
              value={language}
              onChange={(e) => onLanguage(e.target.value as Language)}
            >
              {LANGUAGES.map((l) => (
                <option key={l.id} value={l.id}>
                  {t.settings.languages[l.id]}
                </option>
              ))}
            </select>
            <p className="settings__hint">{t.settings.languageHint}</p>
            <p className="settings__hint">{t.settings.languageAutoHint}</p>
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

        {/* ═══ Online & Nachschlagen (Andi-Auftrag 26.07: „die komplette
            Online-Nachschau-Funktion soll über die Einstellungen in einer
            geeigneten Gruppierung einstellbar sein") ═══════════════════════
            Alles Online-Verhalten an einem Ort: erst die vier Extended-
            Think-Stufen (steuert OB Hoshi online geht), dann das Nachschlag-
            Modell (steuert WOMIT sie nachschaut, wenn sie es tut). Vorher gab
            es für die Stufen KEIN UI-Element — nur GET/PUT
            /api/v1/settings/extended-think im Backend. */}
        <SettingsCategoryPanel id="online-nachschlagen" active={activeCategory}>
          <OnlineNachschlagenGroup />
        </SettingsCategoryPanel>

        {/* ═══ Modell & Leistung (Andi-Auftrag: Brain-Modell live umschaltbar,
            seit 26.07 + automatische Modellwahl „12B für Chat, e4b für Voice") ═══ */}
        <SettingsCategoryPanel id="modell-leistung" active={activeCategory}>
          <ModelPerformanceGroup />
        </SettingsCategoryPanel>

        {/* ═══ Persönlichkeit ════════════════════════════════════════════ */}
        <SettingsCategoryPanel id="persoenlichkeit" active={activeCategory}>
          <section className="settings__group">
            <label className="settings__label" htmlFor="settings-persona">
              {t.settings.personaLabel}
            </label>
            <select
              id="settings-persona"
              className="settings__select"
              value={persona}
              onChange={(e) => onPersona(e.target.value as Persona)}
            >
              {PERSONAS.map((p) => (
                <option key={p.id} value={p.id}>
                  {t.settings.personas[p.id].label}
                </option>
              ))}
            </select>
            {/* Live-Hint: zeigt die Beschreibung der aktuell gewählten Persönlichkeit. */}
            <p className="settings__hint">{t.settings.personas[persona].description}</p>
            {/* Self-demonstrating (Sara): Text-Hörprobe — ein Beispielsatz im echten
                Ton der Auswahl (kalibriert an PersonaService.toneLineDe + Few-Shots). */}
            <p className="settings__sample">
              {t.settings.personaSample(t.settings.personas[persona].sample)}
            </p>
          </section>
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

        {/* ═══ Zuhause & Integrationen (vorher „Standort & Integrationen" —
            umbenannt + erweitert 26.07: die ehemalige Fähigkeiten-Kategorie
            ist aufgelöst, ihre zwei verbleibenden Sektionen [Skills-Toggles,
            Wecker-Eskalation] sind Fähigkeiten IM Zuhause und ziehen darum
            hierher, neben Wetter-Ort/HA/Nachtmodus) ═══════════════════════ */}
        <SettingsCategoryPanel id="zuhause-integrationen" active={activeCategory}>
          {/* ── Wetter-Ort: der Standort für Wetter-Fragen, serverseitig ─────
              Anker-Ziel des Zahnrads an der Wetter-Kachel im Idle-Gesicht. */}
          <SettingsAnchor id="wetter-standort" active={highlighted === 'wetter-standort'}>
            <WeatherLocationSection />
          </SettingsAnchor>

          {/* ── Skills (S2.3): Zwei-Stufen-Toggle, serverseitig ─────────── */}
          <SkillsSection
            skills={skills}
            loading={skillsLoading}
            error={skillsError}
            busyId={busyId}
            onToggle={toggle}
          />

          {/* ── Wecker-Eskalation: ab wann auch fremde Geräte bimmeln ─────
              Anker-Ziel des Zahnrads am Wecker-/Klingel-Banner (FiredToast). */}
          <SettingsAnchor id="wecker-eskalation" active={highlighted === 'wecker-eskalation'}>
            <EscalationSection
              seconds={escalationSeconds}
              onSeconds={onEscalationSeconds}
            />
          </SettingsAnchor>

          {/* ── Nachtmodus (Scheibe 3 von 3): pro Gerät, Nacht-Fenster-Dial ── */}
          <NightModeSection />
        </SettingsCategoryPanel>
      </aside>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Farbthema — drei Gruppen statt einer Liste (Andi 25.07: „Überlege dir ein
//  Konzept, wie man die Auswahl der Themen übersichtlicher machen kann. Das sind
//  jetzt schon einige.")
//
//  Nicht die Menge war das Problem, sondern die Ungleichartigkeit: sechs
//  Farbwelten, eine Automatik (Sora) und Nagareboshi, das seit 0.8.2 BEIDES ist.
//  Die Gruppen ({@link THEME_GROUPS}) machen die Unterschiede sichtbar:
//    „Folgt dem Tag" (die Regel) · „Tageszeiten" (der Bogen) · „Eigene Stimmung".
//
//  Zwei Kleinigkeiten mit großer Wirkung:
//   • ECHTE Farbvorschau: die Kachel setzt `data-theme` SELBST, die drei Flächen
//     lesen `--bg-surface`/`--accent`/`--text-1` — die Werte kommen also aus den
//     echten Theme-Tokens (styles/themes.css), nicht aus einer zweiten,
//     driftenden Farbliste im Picker. Sora zeigt das GERADE aufgelöste Theme.
//   • Beiwort: „Nagareboshi · Sternschnuppe" — schön bleibt schön, aber niemand
//     muss raten (Katalog: `settings.themeGlosses`, alle fünf Sprachen).
//
//  A11y: EINE Radiogroup über alle acht Karten (es ist EINE exklusive Wahl); die
//  Gruppen sind Überschriften darin, jede Karte trägt „Gruppe: Name" als
//  aria-label. Tastatur unverändert — native Buttons, Tab/Enter/Space.
// ─────────────────────────────────────────────────────────────────────────────

/** DOM-Id der Gruppen-Überschrift (stabil, damit Tests/Anker sie greifen können). */
export const themeGroupHeadingId = (id: ThemeGroupId): string => `settings-themegroup-${id}`;

export function ThemeSection({
  theme,
  onTheme,
}: {
  theme: Theme;
  onTheme: (theme: Theme) => void;
}) {
  const t = useUiStrings().settings;
  // Was Sora GERADE zeigen würde — bewusst unabhängig davon, ob Sora gewählt
  // ist: die Zeile „folgt dem Tag · jetzt Kasumi" erklärt die Regel, BEVOR man
  // sie wählt. `useResolvedTheme` hält dabei genau einen Timer auf die nächste
  // Fenstergrenze (kein Polling) — s. useSettings.
  const soraNow: Theme = useResolvedTheme('sora');
  const pinned = (SORA_ROTATION as readonly Theme[]).includes(theme);

  return (
    <section className="settings__group">
      <h3 className="settings__label">{t.themeLabel}</h3>
      <div className="settings__themegroups" role="radiogroup" aria-label={t.themeGroupAria}>
        {THEME_GROUPS.map((group) => {
          const g = t.themeGroups[group.id];
          return (
            <div key={group.id} className={`settings__themegroup settings__themegroup--${group.id}`}>
              <h4 className="settings__themegrouptitle" id={themeGroupHeadingId(group.id)}>
                {g.title}
              </h4>
              <p className="settings__hint settings__themegroupnote">{g.note}</p>

              {group.themes.map((id) => {
                const entry = t.themes[id];
                const isActive = theme === id;
                // Sora ist keine Farbe: seine Vorschau UND seine Zeile zeigen,
                // was die Regel gerade ergibt.
                const previewTheme = id === 'sora' ? soraNow : id;
                return (
                  <button
                    key={id}
                    type="button"
                    role="radio"
                    aria-checked={isActive}
                    aria-label={t.themeOptionAria(g.title, entry.label)}
                    className={`settings__theme ${isActive ? 'is-active' : ''}`}
                    onClick={() => onTheme(id)}
                    title={entry.hint}
                  >
                    {/* Echte Farbvorschau: das Element trägt `data-theme` selbst,
                        die Flächen lesen die Token dieses Themas (themes.css). */}
                    <span className="settings__swatch" data-theme={previewTheme} aria-hidden="true">
                      <span className="settings__swatchbg" />
                      <span className="settings__swatchaccent" />
                      <span className="settings__swatchtext" />
                    </span>
                    <span className="settings__themename">
                      {entry.label}
                      <span className="settings__themegloss">
                        {t.themeGlossSuffix(t.themeGlosses[id])}
                      </span>
                    </span>
                    <span className="settings__themehint">
                      {id === 'sora' ? t.themeSoraNow(t.themes[soraNow].label) : entry.hint}
                    </span>
                  </button>
                );
              })}

              {/* Der Tagesbogen als reine VORSCHAU (nicht klickbar): man sieht auf
                  einen Blick, was Sora tut — und nebenbei, dass die Sternschnuppe
                  in der tiefsten Nacht kommt. */}
              {group.id === 'automatik' && (
                <p className="settings__themearc" aria-label={t.themeArcAria}>
                  {SORA_ROTATION.map((id: SoraTheme, i) => (
                    <span
                      key={id}
                      className={`settings__themearcstep ${id === soraNow ? 'is-now' : ''}`}
                    >
                      {i > 0 ? t.themeArcSeparator : ''}
                      {t.themes[id].label}
                    </span>
                  ))}
                </p>
              )}

              {/* Leise: wer eine Tageszeit fest wählt, pausiert die Automatik. */}
              {group.id === 'tageszeiten' && pinned && (
                <p className="settings__hint settings__themepinned">
                  {t.themePinnedNote(t.themes[theme].label)}
                </p>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}

/**
 * Die leise ehrliche Zeile, wenn die Hörprobe scheitert (503/Netz/Audio-Decode)
 * — jetzt eine Referenz auf den `de`-Katalog (byte-gleich zum bisherigen Stand,
 * Andi-Sweep 24.07). Gerendert wird `useUiStrings().stimme.sampleFailed`.
 */
export const SAMPLE_ERROR_TEXT = de.stimme.sampleFailed;

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
  const t = useUiStrings().settings;
  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-escalation">
        {t.escalationLabel}
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
        <span className="settings__escunit">{t.escalationUnit}</span>
      </div>
      <p className="settings__hint">{t.escalationHint(seconds)}</p>
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
        {WEATHER_LOCATION_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">{WEATHER_LOCATION_TEXTS.loading}</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <p className="settings__hint">
          {WEATHER_LOCATION_TEXTS.current(current.label)}
          {!current.fromStore && WEATHER_LOCATION_TEXTS.seedSuffix}
        </p>
      )}
      <div className="settings__weatherrow">
        <input
          id="settings-weather-place"
          type="text"
          className="settings__text"
          placeholder={WEATHER_LOCATION_TEXTS.placeholder}
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
//  Extended-Think-Stufe — was passiert, wenn Hoshis eigenes Wissen nicht reicht
//  (Andi-Auftrag 26.07: „die Eskalations-Stufe hat KEIN UI-Element" — vorher
//  nur Backend via GET/PUT /api/v1/settings/extended-think). Vier beschriftete
//  Auswahl-Karten statt eines nackten Dropdowns, Reihenfolge nach Online-Grad
//  (Aus → Offline → Erst fragen → Automatisch, s. ESCALATION_MODES); je Karte
//  EIN Titel + EIN erklärender Satz. „Erst fragen" trägt das Empfohlen-Badge —
//  der Laufzeit-Default bei offener Decke (EscalationMode.RUNTIME_DEFAULT).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ehrliche Texte der Extended-Think-Stufenwahl (auch von Tests referenziert) —
 * Referenz auf den `de`-Katalog in `i18n/de.ts`. Gerendert wird
 * `useUiStrings().extendedThink`, s. unten.
 */
export const EXTENDED_THINK_TEXTS = de.extendedThink;

/**
 * Container der Extended-Think-Gruppe (Muster {@link BrainModelSection}): lädt
 * den Ist-Zustand EINMAL beim Mount (Server-Wahrheit, KEIN optimistisches
 * Grün), schaltet per Karten-Klick um (PUT) und liest danach den
 * AUTORITATIVEN Server-Zustand zurück (Readback). Decke zu (`current.locked`)
 * ⇒ die Karten bleiben sichtbar, aber gesperrt + ein ehrlicher Hinweis — kein
 * Fake-Schalter, der nichts schaltet.
 */
export function ExtendedThinkSection({
  onModeChange,
}: {
  /**
   * Meldet JEDEN bekannten Server-Zustand nach oben (initialer Load + jeder
   * erfolgreiche PUT/Reload) — `null` bedeutet „noch unbekannt" (lädt/Fehler,
   * NICHT dasselbe wie AUS). {@link OnlineNachschlagenGroup} nutzt das, um die
   * Nachschlag-Modell-Karte nur zu zeigen, wenn Extended Think wirklich wirken
   * kann (Muster {@link ModelPerformanceGroup}/{@link BrainAutoSwitchSection}
   * — getrennte Fetches bleiben getrennt, nur der Stufen-Wert wird geteilt).
   * Trägt `effectiveMode`, nicht das rohe `mode`: die Decke kollabiert eine
   * gewählte Stufe serverseitig ohnehin auf AUS (s. {@link ExtendedThinkSetting}).
   */
  onModeChange?: (mode: EscalationModeWire | null) => void;
} = {}) {
  const t = useUiStrings();
  const EXTENDED_THINK_TEXTS = t.extendedThink;
  const [current, setCurrent] = useState<ExtendedThinkSetting | null>(null);
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
        const next = await fetchExtendedThink(controller.signal);
        if (aliveRef.current) {
          setCurrent(next);
          setError(null);
          onModeChange?.(next.effectiveMode);
        }
      } catch {
        if (aliveRef.current) setError(EXTENDED_THINK_TEXTS.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onModeChange kann sich pro Render ändern, ein einmaliger Mount-Fetch reicht (Muster BrainAutoSwitchSection).
  }, []);

  const onSelect = (mode: EscalationModeWire) => {
    if (busy || !current || current.locked || mode === current.mode) return;
    setBusy(true);
    setNote(null);
    void (async () => {
      try {
        const updated = await saveExtendedThinkMode(mode);
        if (!aliveRef.current) return;
        setCurrent(updated);
        onModeChange?.(updated.effectiveMode);
      } catch (e) {
        if (!aliveRef.current) return;
        if (e instanceof UnknownEscalationModeError) setNote(EXTENDED_THINK_TEXTS.unknown);
        else if (e instanceof EscalationLockedError) setNote(EXTENDED_THINK_TEXTS.locked);
        else setNote(EXTENDED_THINK_TEXTS.failed);
        // Ehrlicher Ist-Stand nach einem Fehlschlag neu laden (der Server könnte
        // sich seit dem letzten GET verändert haben) — best-effort, still
        // (Muster {@link LookupModelSection}/{@link TtsAndVoiceSection}).
        try {
          const next = await fetchExtendedThink();
          if (aliveRef.current) {
            setCurrent(next);
            onModeChange?.(next.effectiveMode);
          }
        } catch {
          /* die Notiz steht schon */
        }
      } finally {
        if (aliveRef.current) setBusy(false);
      }
    })();
  };

  return (
    <ExtendedThinkSectionView
      current={current}
      loading={loading}
      error={error}
      busy={busy}
      note={note}
      onSelect={onSelect}
    />
  );
}

export interface ExtendedThinkSectionViewProps {
  current: ExtendedThinkSetting | null;
  loading?: boolean;
  error?: string | null;
  busy?: boolean;
  note?: string | null;
  onSelect: (mode: EscalationModeWire) => void;
}

/**
 * Präsentations-Sektion der Extended-Think-Stufenwahl (prop-getrieben, Muster
 * {@link BrainModelSectionView} — per `renderToStaticMarkup` testbar). Vier
 * Radio-Karten in EINER `role="radiogroup"` (Muster {@link ThemeSection}):
 * Titel + Beschreibung je Karte, die aktive Karte trägt `aria-checked`. Deploy-
 * Decke zu (`current.locked`) ⇒ jede Karte `disabled` + der ehrliche
 * Sperr-Hinweis ({@link ExtendedThinkStrings.locked}) statt eines stillen
 * Nicht-Reagierens.
 */
export function ExtendedThinkSectionView({
  current,
  loading,
  error,
  busy,
  note,
  onSelect,
}: ExtendedThinkSectionViewProps) {
  const t = useUiStrings();
  const EXTENDED_THINK_TEXTS = t.extendedThink;
  const locked = current?.locked ?? false;
  return (
    <section className="settings__group">
      <h3 className="settings__label">{EXTENDED_THINK_TEXTS.label}</h3>
      <p className="settings__hint">{EXTENDED_THINK_TEXTS.hint}</p>
      {loading && !current && <p className="settings__hint">{EXTENDED_THINK_TEXTS.loading}</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <div
          className="settings__thinkmodes"
          role="radiogroup"
          aria-label={EXTENDED_THINK_TEXTS.label}
        >
          {ESCALATION_MODES.map((mode) => {
            const entry = EXTENDED_THINK_TEXTS.modes[mode];
            const isActive = current.mode === mode;
            const recommended = mode === 'ERST_FRAGEN';
            return (
              <button
                key={mode}
                type="button"
                role="radio"
                aria-checked={isActive}
                className={`settings__thinkmode ${isActive ? 'is-active' : ''}`}
                disabled={locked || busy}
                onClick={() => onSelect(mode)}
              >
                <span className="settings__thinkmodehead">
                  <span className="settings__thinkmodetitle">{entry.title}</span>
                  {recommended && (
                    <span className="settings__badge settings__badge--recommended">
                      {EXTENDED_THINK_TEXTS.recommendedBadge}
                    </span>
                  )}
                </span>
                <span className="settings__thinkmodedesc">{entry.description}</span>
              </button>
            );
          })}
        </div>
      )}
      {locked && <p className="settings__hint">{EXTENDED_THINK_TEXTS.locked}</p>}
      {busy && <p className="settings__hint">{EXTENDED_THINK_TEXTS.switching}</p>}
      {note && (
        <p className="settings__hint settings__thinkmodenote" role="status">
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
      {loading && !current && <p className="settings__hint">{LOOKUP_MODEL_TEXTS.loading}</p>}
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

/**
 * Hält die Extended-Think-Stufenwahl UND das Nachschlag-Modell zusammen
 * (bündig-Sweep, Muster {@link ModelPerformanceGroup}): die Nachschlag-Modell-
 * Karte betrifft nur, WOMIT Hoshi online nachschaut — bei Stufe „Aus" (oder
 * effektiv „Aus" durch die Deploy-Decke, s. {@link ExtendedThinkSetting.effectiveMode})
 * schaut sie NIE nach, die Karte war bis hierher „heute beziehungslos" sichtbar.
 * Jetzt blendet sie sich aus, statt sich zu deaktivieren — Werte/Endpoint
 * bleiben unangetastet, ein späteres Einblenden zeigt wieder den echten
 * Server-Zustand (kein Reset). `null` (noch unbekannt: lädt oder Fehler) zeigt
 * die Karte weiter — nie fälschlich verstecken, solange die Stufe nicht sicher
 * feststeht. Getrennte Fetches bleiben getrennt (kein Zusammenlegen) — nur der
 * Stufen-Wert wandert als Prop von {@link ExtendedThinkSection} hoch.
 */
function OnlineNachschlagenGroup() {
  const [thinkMode, setThinkMode] = useState<EscalationModeWire | null>(null);
  const showLookup = thinkMode === null || thinkMode !== 'AUS';
  return (
    <>
      <ExtendedThinkSection onModeChange={setThinkMode} />
      {showLookup && <LookupModelSection />}
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  TTS-Engine — welcher Adapter spricht (Andi-Video-Auftrag)
// ─────────────────────────────────────────────────────────────────────────────

// Die Anzeige-Labels der vier Engines („OpenAI (Cloud)", „macOS say (lokal)" …)
// stehen jetzt im Katalog (`ttsEngine.engineLabels`, DE byte-gleich zum
// bisherigen Hardcode): „(lokal)" stand vorher auch in der englischen
// Oberfläche (EN-Sweep 25.07). Die Wire-Ids selbst sind stabil (TtsEngineIds im
// BE); unbekannte Ids rendern weiterhin as-is.

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
 * {@link LookupModelSectionView}): ein natives `<select>` — dieselbe
 * Formsprache wie Sprache/Persönlichkeit/Online-Nachschlag (Andi-Auftrag
 * „die TTS-Engine soll ein Dropdown werden … alles bündig"). Vorher eine
 * Liste von Zeilen mit Zwei-Stufen-Toggle (Muster {@link SkillsSection}) —
 * das passte für „mehrere unabhängig an/aus" (Skills), aber die TTS-Engine
 * ist eine EINZIGE exklusive Wahl aus einer festen Liste, wie Sprache/
 * Persönlichkeit auch. Nicht verfügbare Engines bleiben in der Liste, aber
 * als `<option disabled>` MIT ihrem ehrlichen Hinweis im Options-Text (z.B.
 * „OpenAI (Cloud) — Kein OPENAI_API_KEY gesetzt.") — kein Fake-Auswählbares,
 * das nichts schaltet.
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
      <label className="settings__label" htmlFor="settings-tts-engine">
        {TTS_ENGINE_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">{TTS_ENGINE_TEXTS.loading}</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {current && (
        <select
          id="settings-tts-engine"
          className="settings__select"
          value={current.aktiv}
          disabled={busy}
          onChange={(e) => onSelect(e.target.value)}
        >
          {current.engines.map((e) => {
            const label = TTS_ENGINE_TEXTS.engineLabels[e.id] ?? e.id;
            return (
              <option key={e.id} value={e.id} disabled={!e.verfuegbar}>
                {e.verfuegbar ? label : `${label} — ${e.hinweis || TTS_ENGINE_TEXTS.notStarted}`}
              </option>
            );
          })}
        </select>
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
  const engineLabel = current ? (t.ttsEngine.engineLabels[current.aktiv] ?? current.aktiv) : '';
  const selectedVoice = current?.stimmen.find((v) => v.id === activeVoice);

  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-voice">
        {STIMME_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">{STIMME_TEXTS.loading}</p>}
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
            aria-label={STIMME_TEXTS.sampleAria(activeVoice)}
            title={STIMME_TEXTS.sampleTitle}
          >
            {sampleBusy ? <span className="settings__samplespin" aria-hidden="true" /> : <PlayGlyph />}
          </button>
        </div>
      )}
      {current && current.stimmen.length === 0 && (
        <p className="settings__hint">
          {current.stimmenHinweis || STIMME_TEXTS.noVoicesFor(engineLabel)}
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
      {selectedVoice?.lizenz && (
        <p className="settings__hint">{STIMME_TEXTS.licensePrefix(selectedVoice.lizenz)}</p>
      )}
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
      setSampleError(STIMME_TEXTS.sampleFailed);
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
export function BrainModelSection({
  autoSwitchActive = false,
}: { autoSwitchActive?: boolean } = {}) {
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
      autoSwitchActive={autoSwitchActive}
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
  /**
   * Ehrlicher Zusatz-Hinweis (Andi-Auftrag „12B für Chat, e4b für Voice",
   * 2026-07-26): steht die automatische Modellwahl an, sagt ein Zusatz-Satz
   * unter [BrainModelStrings.hint], dass diese Auswahl jetzt nur noch das
   * CHAT-Modell setzt. Default `false` ⇒ byte-neutral für Bestandsaufrufer.
   */
  autoSwitchActive?: boolean;
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
  autoSwitchActive,
}: BrainModelSectionViewProps) {
  const t = useUiStrings();
  const BRAIN_MODEL_TEXTS = t.brainModel;
  return (
    <section className="settings__group">
      <label className="settings__label" htmlFor="settings-brain-model">
        {BRAIN_MODEL_TEXTS.label}
      </label>
      {loading && !current && <p className="settings__hint">{BRAIN_MODEL_TEXTS.loading}</p>}
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
                {BRAIN_MODEL_TEXTS.statusReading}
              </option>
            )}
            {current.modelle.map((m) => (
              <option key={m.id} value={m.id}>
                {m.label}
              </option>
            ))}
          </select>
          <p className="settings__hint">
            {BRAIN_MODEL_TEXTS.statusPrefix}
            {brainStatusLabel(current.status, BRAIN_MODEL_TEXTS)}
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
      {autoSwitchActive && <p className="settings__hint">{BRAIN_MODEL_TEXTS.autoSwitchNote}</p>}
      {note && (
        <p className="settings__hint settings__brainmodelnote" role="status">
          {note}
        </p>
      )}
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Automatische Modellwahl — „12B für Chat, e4b für Voice" (Andi-Auftrag
//  2026-07-26): EINE Karte unter der Brain-Modell-Auswahl, Toggle + erklärender
//  Satz. Steht sie an, setzt die manuelle Auswahl oben (BrainModelSection) nur
//  noch das CHAT-Modell — der ehrliche Zusatz-Hinweis dort kommt über
//  [ModelPerformanceGroup], die beide Sektionen zusammenhält.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Container: lädt den Ist-Zustand EINMAL beim Mount, schaltet per Klick um.
 * `onChange` (optional) meldet JEDEN bekannten Zustand nach oben (initialer
 * Load + jeder erfolgreiche Toggle) — [ModelPerformanceGroup] nutzt das, um
 * den ehrlichen Zusatz-Hinweis in der Modell-Auswahl darüber zu steuern, OHNE
 * dass [BrainModelSection] selbst je etwas von diesem Setting fetchen müsste.
 */
export function BrainAutoSwitchSection({
  onChange,
}: { onChange?: (enabled: boolean) => void } = {}) {
  const t = useUiStrings();
  const TXT = t.brainAutoSwitch;
  const [enabled, setEnabled] = useState(false);
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
        const setting = await fetchBrainAutoSwitch(controller.signal);
        if (!aliveRef.current) return;
        setEnabled(setting.enabled);
        onChange?.(setting.enabled);
        setError(null);
      } catch {
        if (aliveRef.current) setError(TXT.loadError);
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onChange/TXT sind pro Render neu, ein einmaliger Mount-Fetch reicht.
  }, []);

  const onToggle = () => {
    if (busy || loading) return;
    const next = !enabled;
    setBusy(true);
    setNote(null);
    void (async () => {
      try {
        const setting = await saveBrainAutoSwitch(next);
        if (!aliveRef.current) return;
        setEnabled(setting.enabled);
        onChange?.(setting.enabled);
      } catch {
        if (aliveRef.current) setNote(TXT.failed);
      } finally {
        if (aliveRef.current) setBusy(false);
      }
    })();
  };

  return (
    <BrainAutoSwitchSectionView
      enabled={enabled}
      loading={loading}
      error={error}
      busy={busy}
      note={note}
      onToggle={onToggle}
    />
  );
}

export interface BrainAutoSwitchSectionViewProps {
  enabled: boolean;
  loading?: boolean;
  error?: string | null;
  busy?: boolean;
  note?: string | null;
  onToggle: () => void;
}

/** Präsentations-Sektion (prop-getrieben, Muster {@link TtsEngineSectionView}): EIN Toggle + Hinweis. */
export function BrainAutoSwitchSectionView({
  enabled,
  loading,
  error,
  busy,
  note,
  onToggle,
}: BrainAutoSwitchSectionViewProps) {
  const t = useUiStrings();
  const TXT = t.brainAutoSwitch;
  return (
    <section className="settings__group">
      <div className="settings__skill">
        <div className="settings__skillmeta">
          <span className="settings__skillname">{TXT.label}</span>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={enabled}
          aria-label={TXT.label}
          className={`settings__toggle ${enabled ? 'is-on' : ''}`}
          disabled={!!busy || !!loading}
          onClick={onToggle}
        >
          <span className="settings__toggleknob" aria-hidden="true" />
        </button>
      </div>
      <p className="settings__hint">{TXT.hint}</p>
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}
      {note && (
        <p className="settings__hint" role="status">
          {note}
        </p>
      )}
    </section>
  );
}

/**
 * Hält die Brain-Modell-Auswahl UND den Auto-Switch-Toggle zusammen, DAMIT die
 * Modell-Auswahl den ehrlichen Zusatz-Hinweis zeigen kann, ohne selbst einen
 * zweiten Fetch für dieses Setting zu brauchen (kein zusätzlicher Netz-Call in
 * den bestehenden {@link BrainModelSection}-Tests — die rufen die Sektion nach
 * wie vor ohne Props auf). [BrainAutoSwitchSection] ist die EINE Quelle für den
 * Zustand, [onChange] reicht ihn hoch.
 */
function ModelPerformanceGroup() {
  const [autoSwitchOn, setAutoSwitchOn] = useState(false);
  return (
    <>
      <BrainModelSection autoSwitchActive={autoSwitchOn} />
      <BrainAutoSwitchSection onChange={setAutoSwitchOn} />
    </>
  );
}

/**
 * Ehrliche Zukunfts-Skills — statisch ausgegraut MIT Grund, bewusst OHNE Toggle
 * (kein Fake-Schalter, der nichts schaltet). Sobald ein Skill real im Backend
 * landet, kommt er über die Registry/Wire-Liste und fliegt hier raus.
 * (WEATHER ist raus: der Wetter-Ort ist jetzt eine echte Sektion, siehe
 * {@link WeatherLocationSection}.)
 *
 * Nur noch die IDs: Label und Grund stehen im Katalog (`t.skills.future`) und
 * gibt es damit in allen fünf Sprachen statt nur de/en (EN-Sweep 25.07).
 */
export const FUTURE_SKILL_IDS: readonly FutureSkillId[] = ['LISTS', 'MUSIC'];

interface SkillsSectionProps {
  skills: Skill[];
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
 *
 * Sprache (EN-Sweep 25.07): Hinweis, Lade-Zeile, Badges und Zukunfts-Skills
 * kommen aus dem Katalog der AKTIVEN UI-SPRACHE. Vorher hing die Wahl an der
 * CHAT-Sprache (`language`-Prop, Default `'auto'`) — die Skill-Zeile blieb
 * deutsch, obwohl die Oberfläche auf Englisch stand. Die Skill-NAMEN liefert
 * der Server nur zweisprachig (`labelDe`/`labelEn`); für es/fr/it bleibt darum
 * der DE-Fallback des Katalogs, bis der Draht mehr Sprachen führt.
 */
export function SkillsSection({ skills, loading, error, busyId, onToggle }: SkillsSectionProps) {
  const uiLang = useActiveUiLanguage();
  const ui = useUiStrings();
  const t = ui.skills;
  const lang: 'de' | 'en' = uiLang === 'en' ? 'en' : 'de';
  return (
    <section className="settings__group">
      <h3 className="settings__label">{ui.settings.skillsTitle}</h3>
      <p className="settings__hint">{t.hint}</p>
      {loading && skills.length === 0 && <p className="settings__hint">{t.loading}</p>}
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
                        {t.badgeEgress}
                      </span>
                    )}
                    {s.locked && (
                      <span className="settings__badge settings__badge--locked">
                        {t.badgeLocked}
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
        {FUTURE_SKILL_IDS.map((id) => (
          <div className="settings__skill settings__skill--future" key={id}>
            <div className="settings__skillmeta">
              <span className="settings__skillname">{t.future[id].label}</span>
              <span className="settings__skillreason">{t.future[id].reason}</span>
            </div>
            <span className="settings__badge settings__badge--soon">{t.badgeSoon}</span>
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

// Die Einheiten je Datenart („Eintrag"/„Einträge"/„Tages-Datei"…) stehen jetzt
// samt Satzbau im Katalog (`settings.privacyDeleted`) — vorher war die ganze
// Erfolgs-Notiz eine deutsche Modul-Konstante + ein deutsches Template und stand
// so auch in der englischen Oberfläche (Langschwanz-Sweep 25.07).

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
        setNotes((n) => ({
          ...n,
          [target]: t.settings.privacyDeleted(res.deleted, target),
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
function storeDetail(info: PrivacySummary['memory'], t: SettingsPanelStrings): string {
  if (!info.exists) return t.privacyStoreEmpty;
  const count =
    info.entries === null ? t.privacyStoreUnreadable : t.privacyStoreEntries(info.entries);
  return info.enabled ? count : t.privacyStoreDisabled(count);
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
  const s = t.settings;
  const deleteButton = (target: PrivacyTarget) => {
    const isArmed = armed === target;
    const isBusy = busy === target;
    return (
      <button
        type="button"
        className={`settings__deletebtn ${isArmed ? 'is-armed' : ''}`}
        disabled={isBusy}
        aria-label={s.privacyDeleteAria(s.privacyTargetLabels[target])}
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
      <h3 className="settings__label">{s.privacyTitle}</h3>
      <p className="settings__hint">{s.privacyIntro}</p>
      {loading && !summary && <p className="settings__hint">{s.privacyLoading}</p>}
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
                {summary.voice.cloud ? <CloudGlyph /> : <LockGlyph />}{' '}
                {s.privacyVoiceLine(summary.voice.engine)}
              </span>
              <span className="settings__privacydetail">
                {summary.voice.cloud ? s.privacyVoiceCloud : s.privacyVoiceLocal}
              </span>
            </div>
          </div>

          {/* Cloud-Maskierung: was VOR einem Cloud-Call maskiert wird. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                {summary.sanitize.enabled ? (
                  <>
                    <LockGlyph /> {s.privacySanitizeOn}
                  </>
                ) : (
                  <>
                    <WarnGlyph /> {s.privacySanitizeOff}
                  </>
                )}
              </span>
              <span className="settings__privacydetail">
                {summary.sanitize.enabled ? s.privacySanitizeOnDetail : s.privacySanitizeOffDetail}
              </span>
            </div>
          </div>

          {/* Gedächtnis (Fakten) — lokal, löschbar. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                <LockGlyph /> {s.privacyMemoryLine}
              </span>
              <span className="settings__privacydetail">
                {s.privacyLocalFile(storeDetail(summary.memory, s))}
              </span>
            </div>
            {deleteButton('memory')}
          </div>
          {note('memory')}

          {/* Episoden-Gedächtnis — lokal, löschbar. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                <LockGlyph /> {s.privacyEpisodicLine}
              </span>
              <span className="settings__privacydetail">
                {s.privacyLocalFile(storeDetail(summary.episodic, s))}
              </span>
            </div>
            {deleteButton('episodic')}
          </div>
          {note('episodic')}

          {/* Nutzungs-Diary — lokal, ohne Gesprächs-Inhalte, löschbar. */}
          <div className="settings__privacyrow">
            <div className="settings__privacymeta">
              <span className="settings__privacyline">
                <LockGlyph /> {s.privacyDiaryLine}
              </span>
              <span className="settings__privacydetail">
                {s.privacyDiaryDetail(summary.diary.days)}
              </span>
            </div>
            {deleteButton('diary')}
          </div>
          {note('diary')}
        </div>
      )}
    </section>
  );
}
