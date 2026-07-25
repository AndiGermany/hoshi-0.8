/** @vitest-environment jsdom */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { clockParts, dueClock } from '../hooks/useScheduledItems';
import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { setActiveUiLanguage } from '../i18n';
import { SendButton } from '../components/SendButton';
import { VoiceOrb } from '../components/VoiceOrb';
import { TurnChips, TurnStagesRow, type TurnAnatomyState } from '../components/TurnAnatomy';
import { FiredToast } from '../components/FiredToast';
import {
  NightModeDeviceCard,
  NightModeDeviceListView,
  type NightModeDeviceRow,
} from '../components/NightModeSection';
import {
  BrainModelSectionView,
  EscalationSection,
  LookupModelSectionView,
  PrivacySectionView,
  SettingsCategoryNav,
  SettingsPanel,
  SkillsSection,
  StimmeSectionView,
  ThemeSection,
  TtsEngineSectionView,
  WeatherLocationSectionView,
} from '../components/SettingsPanel';
import { ScheduledPanel } from '../components/ScheduledPanel';
import { OpsStatusPill } from '../components/OpsStatusPill';
import { SpeakerChip } from '../components/SpeakerChip';
import { StageSparkline } from '../components/StageSparkline';
import { streamChat } from '../api/chat';
import { streamVoice } from '../api/voice';
import type { VoiceChatSession } from '../hooks/useVoiceChatSession';
import type { FiredItem } from '../hooks/useFiredItems';
import type { ScheduledItem } from '../hooks/useScheduledItems';
import type { OpsStatus } from '../hooks/useOpsStatus';
import type { PrivacySummary } from '../api/privacy';
import type { Skill } from '../api/types';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ═════════════════════════════════════════════════════════════════════════════
//  EN-RENDERING-SWEEP (Testqualitäts-Befund 0.8.1 zu Commit 380c779)
//
//  380c779 hat 41 deutsche UI-Reste in den Katalog überführt — aber KEINEN Test
//  geändert. Die deutschen Bestandstests belegen nur die sichere Hälfte (DE ist
//  byte-gleich geblieben); die Hälfte, die wirklich kaputt war — ENGLISCH — war
//  völlig ungeprüft. Diese Datei schließt genau diese Lücke:
//
//   1. Jede der sechs reparierten Komponenten (SendButton, VoiceOrb,
//      TurnAnatomy, FiredToast, NightModeSection, SettingsPanel) wird mit
//      `setActiveUiLanguage('en')` GERENDERT — und im gerenderten Markup darf
//      kein deutscher Rest mehr stehen. Geprüft wird das AUSGABE-Markup
//      (`renderToStaticMarkup`, inkl. aria-label/title/placeholder), NICHT der
//      Katalog: ein Katalog-Vergleich würde nur beweisen, dass Daten Daten sind.
//   2. Gegenprobe je Komponente: dasselbe Bild auf Deutsch enthält die
//      deutschen Wörter wörtlich. Ohne sie wäre ein grüner Sweep auch dann
//      grün, wenn gar nichts gerendert wurde.
//
//  Der Detektor ist doppelt: (a) die KONKRETEN Wörter, die vor 380c779 hart
//  im Markup standen („Senden", „Tippen zum Sprechen", „lokal", „lädt…",
//  „Gerät", „Modus", „löschen" …), und (b) generisch jedes Umlaut-/ß-Zeichen im
//  Markup — dort, wo die Komponente vollständig katalogisiert ist. Alle
//  Fixtures sind bewusst ASCII/englisch gehalten, damit (b) unsere Texte misst
//  und nicht die Testdaten.
//
//  NACHTRAG 25.07 (Langschwanz-Scheibe): der in 380c779 EHRLICH als vertagt
//  gemeldete Rest ist jetzt geholt — Kategorie-Reiter, Farbthema/Sprache/
//  Persönlichkeit (THEMES/LANGUAGES/PERSONAS), Wecker-Eskalation, Skills-
//  Überschrift und PrivacySection im Panel; dazu die Timer-/Wecker-Zeile über
//  der Eingabe (ScheduledPanel), die Ops-Pille der Kopfzeile, der Sprecher-Chip,
//  die Sparkline-Tooltips und die API-Fehlertexte, die WÖRTLICH als Chat-Blase
//  im Gespräch landen. Der volle SettingsPanel-Render trägt darum ab jetzt den
//  SCHARFEN Umlaut-Sweep (vorher bewusst abgeschaltet) — er ist der Riegel
//  gegen ein Zurückfallen des ganzen Panels.
// ═════════════════════════════════════════════════════════════════════════════

/** Generischer Deutsch-Detektor fürs Markup (Fixtures sind ASCII). */
const UMLAUT = /[äöüÄÖÜß]/;

/** Rendert `el` in der gegebenen UI-Sprache (Store ist ein Modul-Singleton). */
function renderIn(lang: 'de' | 'en', el: ReactElement): string {
  setActiveUiLanguage(lang);
  return renderToStaticMarkup(el);
}

/** Kein deutsches Wort und (optional) kein Umlaut im gerenderten Markup. */
function expectNoGerman(
  html: string,
  where: string,
  words: readonly string[],
  { umlauts = true } = {},
): void {
  for (const w of words) {
    expect(html.includes(w), `${where}: deutscher Rest „${w}" im gerenderten Markup`).toBe(false);
  }
  if (umlauts) {
    const hit = UMLAUT.exec(html);
    expect(hit?.[0], `${where}: Umlaut/ß im gerenderten Markup`).toBeUndefined();
  }
}

/** Alle Wörter kommen im Markup wirklich vor (Gegenprobe: es wurde etwas gerendert). */
function expectAll(html: string, where: string, words: readonly string[]): void {
  for (const w of words) {
    expect(html.includes(w), `${where}: „${w}" fehlt im gerenderten Markup`).toBe(true);
  }
}

afterEach(() => {
  setActiveUiLanguage('de');
});

// ─────────────────────────────────────────────────────────────────────────────
//  1) SendButton — „Senden" stand im README-Screenshot auf dem englischen CTA
// ─────────────────────────────────────────────────────────────────────────────

describe('EN-Sweep — SendButton', () => {
  const button = <SendButton disabled={false} busy={false} />;

  it('englisch: Label, aria-label und title sind Englisch — kein „Senden" mehr', () => {
    const html = renderIn('en', button);
    expectNoGerman(html, 'SendButton', ['Senden']);
    expectAll(html, 'SendButton', ['aria-label="Send"', 'title="Send (Enter)"', '>Send<']);
  });

  it('deutsch (Gegenprobe): „Senden" steht weiterhin wörtlich da', () => {
    const html = renderIn('de', button);
    expectAll(html, 'SendButton', ['aria-label="Senden"', 'title="Senden (Enter)"', '>Senden<']);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  2) VoiceOrb — „Tippen zum Sprechen" stand unter dem englischen Orb
// ─────────────────────────────────────────────────────────────────────────────

const session = (over: Partial<VoiceChatSession> = {}): VoiceChatSession => ({
  turns: [],
  busy: false,
  activeSpeakerId: 'andi',
  activeSpeakerName: '',
  voiceOn: true,
  speaking: false,
  micState: 'idle',
  micStateRef: { current: 'idle' },
  micError: null,
  recSecs: 0,
  stepLabel: null,
  slow: false,
  send: async () => {},
  startRecording: async () => {},
  stopAndSend: async () => {},
  cancelRecording: () => {},
  bargeIn: () => {},
  toggleVoice: () => {},
  setLevelSink: () => {},
  ...over,
});

const ORB_GERMAN = [
  'Tippen zum Sprechen',
  'Tippen und sprechen',
  'Sprich mit Hoshi',
  'hört zu',
  'Nochmal tippen zum Senden',
  'Tippen bricht',
  'spricht…',
];

describe('EN-Sweep — VoiceOrb (idle/listening/speaking)', () => {
  const idle = <VoiceOrb session={session()} />;
  const listening = <VoiceOrb session={session({ micState: 'listening', recSecs: 5 })} />;
  const speaking = <VoiceOrb session={session({ speaking: true })} />;

  it('englisch: Hinweistext, Sektions-aria und Tap-Label sind Englisch — in allen drei Zuständen', () => {
    const idleHtml = renderIn('en', idle);
    expectNoGerman(idleHtml, 'VoiceOrb (idle)', ORB_GERMAN);
    expectAll(idleHtml, 'VoiceOrb (idle)', ['aria-label="Talk to Hoshi"', 'Tap to speak', 'Tap and speak']);

    const listeningHtml = renderIn('en', listening);
    expectNoGerman(listeningHtml, 'VoiceOrb (listening)', ORB_GERMAN);
    expectAll(listeningHtml, 'VoiceOrb (listening)', [
      'listening… 0:05',
      'Tap again to send — or press Esc to discard',
    ]);

    const speakingHtml = renderIn('en', speaking);
    expectNoGerman(speakingHtml, 'VoiceOrb (speaking)', ORB_GERMAN);
    expectAll(speakingHtml, 'VoiceOrb (speaking)', ['speaking…', 'Tap to stop Hoshi']);
  });

  it('deutsch (Gegenprobe): die deutschen Hinweise stehen weiterhin wörtlich da', () => {
    expectAll(renderIn('de', idle), 'VoiceOrb (idle)', [
      'aria-label="Sprich mit Hoshi"',
      'Tippen zum Sprechen',
      'Tippen und sprechen',
    ]);
    expectAll(renderIn('de', listening), 'VoiceOrb (listening)', [
      'hört zu… 0:05',
      'Nochmal tippen zum Senden — oder Esc zum Verwerfen',
    ]);
    expectAll(renderIn('de', speaking), 'VoiceOrb (speaking)', [
      'spricht…',
      'Tippen bricht Hoshis Antwort ab',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  3) TurnAnatomy — „lokal" stand als Chip unter der englischen Antwort
// ─────────────────────────────────────────────────────────────────────────────

const anatomy = (over: Partial<TurnAnatomyState> = {}): TurnAnatomyState => ({
  kind: 'voice',
  heard: true,
  speaker: { name: 'Andi', confidence: 0.92, isGuest: false },
  understood: true,
  route: { provider: 'LOCAL', model: 'gemma-4-e2b', category: 'FACT_SHORT', grounded: true },
  answering: true,
  speaking: true,
  errorStage: null,
  ...over,
});

const ANATOMY_GERMAN = [
  'gehört',
  'verstanden',
  'Weg gewählt',
  'antwortet',
  'erkannt:',
  'Gast',
  'Was dieser Turn wirklich getan hat',
  'lokal',
  'ging online',
  'Wissen gedeckt',
  'Diese Antwort blieb auf dem Gerät',
  'Diese Antwort kam über einen Cloud-Provider',
];

describe('EN-Sweep — TurnAnatomy (Stufen-Zeile + Chips)', () => {
  const stagesLocal = <TurnStagesRow anatomy={anatomy()} />;
  const chipsLocal = <TurnChips anatomy={anatomy()} />;
  const chipsCloud = (
    <TurnChips
      anatomy={anatomy({
        route: { provider: 'OPENAI', model: 'gpt', category: 'FACT_SHORT', grounded: false },
        speaker: { name: '', confidence: 0.2, isGuest: true },
      })}
    />
  );

  it('englisch: jede Stufe und jeder Chip ist Englisch — kein „lokal"/„ging online" mehr', () => {
    const stages = renderIn('en', stagesLocal);
    expectNoGerman(stages, 'TurnStagesRow', ANATOMY_GERMAN);
    expectAll(stages, 'TurnStagesRow', [
      'aria-label="What this turn actually did"',
      'heard',
      'recognised: Andi',
      'understood',
      'route chosen',
      'answering',
      'speaking',
    ]);

    const local = renderIn('en', chipsLocal);
    expectNoGerman(local, 'TurnChips (LOCAL)', ANATOMY_GERMAN);
    expectAll(local, 'TurnChips (LOCAL)', [
      '>local<',
      'title="This answer stayed on the device"',
      'Backed by knowledge',
    ]);

    const cloud = renderIn('en', chipsCloud);
    expectNoGerman(cloud, 'TurnChips (Cloud)', ANATOMY_GERMAN);
    expectAll(cloud, 'TurnChips (Cloud)', [
      'OpenAI · went online',
      'title="This answer came via a cloud provider"',
    ]);
  });

  it('deutsch (Gegenprobe): Stufen und Chips sind weiterhin wörtlich deutsch', () => {
    expectAll(renderIn('de', stagesLocal), 'TurnStagesRow', [
      'aria-label="Was dieser Turn wirklich getan hat"',
      'gehört',
      'erkannt: Andi',
      'verstanden',
      'Weg gewählt',
      'antwortet',
      'spricht',
    ]);
    expectAll(renderIn('de', chipsLocal), 'TurnChips (LOCAL)', [
      '>lokal<',
      'title="Diese Antwort blieb auf dem Gerät"',
      'Wissen gedeckt',
    ]);
    expectAll(renderIn('de', chipsCloud), 'TurnChips (Cloud)', ['OpenAI · ging online']);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  4) FiredToast — Banner-Zeilen, Quittier-title und Eskalations-Zahnrad
// ─────────────────────────────────────────────────────────────────────────────

/** 07:00 Ortszeit (lokal konstruiert, TZ-unabhängig) — dieselbe Uhrzeit wie fired.test. */
const DUE = new Date(2026, 6, 25, 7, 0).getTime();

const fired = (over: Partial<FiredItem> = {}): FiredItem => ({
  id: 'f-1',
  kind: 'ALARM',
  dueAtEpochMs: DUE,
  firedAtEpochMs: DUE + 1_000,
  missed: false,
  ...over,
});

const TOAST_GERMAN = [
  'Wecker klingelt',
  'Timer ist fertig',
  'Erinnerung',
  'Tippen zum Bestätigen',
  'war um',
  'fällig',
  'hab dich nicht erreicht',
  'Eskalation ändern',
  'Wecker-Eskalation-Einstellungen öffnen',
];

describe('EN-Sweep — FiredToast (frisch + verpasst)', () => {
  const toast = (
    <FiredToast
      items={[fired(), fired({ id: 'f-2', kind: 'TIMER', label: 'Tea', missed: true })]}
      onAck={() => {}}
      onOpenSettings={() => {}}
    />
  );

  it('englisch: Überschrift, Verpasst-Satz, Quittier-title und Zahnrad sind Englisch', () => {
    const html = renderIn('en', toast);
    expectNoGerman(html, 'FiredToast', TOAST_GERMAN);
    expectAll(html, 'FiredToast', [
      'Alarm is ringing',
      'title="Tap to confirm"',
      'was due at 07:00 — I could not reach you',
      'aria-label="Open alarm escalation settings (Skills)"',
      'title="Change escalation"',
    ]);
  });

  it('deutsch (Gegenprobe): die deutschen Banner-Sätze stehen weiterhin wörtlich da', () => {
    const html = renderIn('de', toast);
    expectAll(html, 'FiredToast', [
      'Wecker klingelt',
      'title="Tippen zum Bestätigen"',
      'war um 07:00 fällig — hab dich nicht erreicht',
      'title="Eskalation ändern"',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  5) NightModeSection — Liste (lädt…/Gerät) + Karte (Modus)
// ─────────────────────────────────────────────────────────────────────────────

const nightRow = (): NightModeDeviceRow => ({
  device: {
    satelliteId: 'voice-pe-livingroom',
    connected: true,
    enabled: true,
    mode: 'SCHEDULE',
    from: '22:00',
    to: '07:00',
    dim: 0.3,
    nightModeEnabled: true,
  },
  lastSeenLabel: null,
});

const NIGHT_GERMAN = [
  'lädt…',
  'aria-label="Gerät"',
  'aria-label="Modus"',
  'Noch kein Satellit verbunden.',
  'Geräte-Id manuell hinterlegen',
  'Übernehmen',
  'Zeitplan',
  'Immer an',
  'Dimmen',
  'Speichern',
  'verbunden',
];

describe('EN-Sweep — NightModeSection (Liste + Karte)', () => {
  const listLoading = (
    <NightModeDeviceListView
      rows={[]}
      selectedId={null}
      loading
      manualId=""
      onManualId={() => {}}
      onManualSubmit={() => {}}
      onSelect={() => {}}
    />
  );
  const listWithRows = (
    <NightModeDeviceListView
      rows={[nightRow()]}
      selectedId="voice-pe-livingroom"
      manualId=""
      onManualId={() => {}}
      onManualSubmit={() => {}}
      onSelect={() => {}}
    />
  );
  const card = (
    <NightModeDeviceCard
      draft={{ enabled: true, mode: 'SCHEDULE', from: '22:00', to: '07:00', dim: 0.3 }}
      onToggleEnabled={() => {}}
      onMode={() => {}}
      onFrom={() => {}}
      onTo={() => {}}
      onDim={() => {}}
      onSave={() => {}}
    />
  );

  it('englisch: Lade-Zeile, Leerzustand, Radiogroup-Labels und Karte sind Englisch', () => {
    const loading = renderIn('en', listLoading);
    expectNoGerman(loading, 'NightModeDeviceListView (lädt)', NIGHT_GERMAN);
    expectAll(loading, 'NightModeDeviceListView (lädt)', ['loading…']);

    const rows = renderIn('en', listWithRows);
    expectNoGerman(rows, 'NightModeDeviceListView (Geräte)', NIGHT_GERMAN);
    expectAll(rows, 'NightModeDeviceListView (Geräte)', ['aria-label="Device"', 'connected']);

    const cardHtml = renderIn('en', card);
    expectNoGerman(cardHtml, 'NightModeDeviceCard', NIGHT_GERMAN);
    expectAll(cardHtml, 'NightModeDeviceCard', ['aria-label="Mode"', 'Schedule', 'Always on', 'Save']);
  });

  it('deutsch (Gegenprobe): „lädt…", „Gerät" und „Modus" stehen weiterhin wörtlich da', () => {
    expectAll(renderIn('de', listLoading), 'NightModeDeviceListView (lädt)', ['lädt…']);
    expectAll(renderIn('de', listWithRows), 'NightModeDeviceListView (Geräte)', [
      'aria-label="Gerät"',
      'verbunden',
    ]);
    expectAll(renderIn('de', card), 'NightModeDeviceCard', [
      'aria-label="Modus"',
      'Zeitplan',
      'Immer an',
      'Speichern',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  6) SettingsPanel — Dialograhmen + die katalogisierten Sektionen
// ─────────────────────────────────────────────────────────────────────────────

const skill = (over: Partial<Skill> = {}): Skill => ({
  id: 'SMART_HOME',
  labelDe: 'Smart-Home',
  labelEn: 'Smart home',
  tier: 'LOCAL',
  ceilingOpen: true,
  enabled: true,
  effective: true,
  locked: false,
  ...over,
});

const SETTINGS_GERMAN = [
  'lädt…',
  'Wetter-Ort',
  'z. B. Duisburg',
  'Aktuell:',
  // „Status: " selbst ist in DE und EN identisch — nur die gelesene Zeile dahinter unterscheidet sich.
  '(Status wird gelesen…)',
  'läuft',
  'Hörprobe',
  'Lizenz:',
  'stehen aktuell keine Stimmen zur Auswahl',
  '(lokal)',
  '(Cloud)',
  'nicht gestartet',
  'verfügbar',
  'deaktiviert beim Deploy',
  'geht online',
  'kommt noch',
  'Andi-Gabel offen',
  'Track startet',
  'Schaltet einzelne',
];

describe('EN-Sweep — SettingsPanel: Wetter-Ort / Lookup / TTS-Engine / Stimme / Brain', () => {
  const weather = (
    <WeatherLocationSectionView
      current={{ label: 'Duisburg', lat: 51.4, lon: 6.7, fromStore: true, weatherEnabled: true }}
      place=""
      onPlace={() => {}}
      onSave={() => {}}
    />
  );
  const weatherLoading = (
    <WeatherLocationSectionView current={null} loading place="" onPlace={() => {}} onSave={() => {}} />
  );
  const lookupLoading = <LookupModelSectionView current={null} loading onSelect={() => {}} />;
  const engines = (
    <TtsEngineSectionView
      current={{
        aktiv: 'say',
        engines: [
          { id: 'openai', verfuegbar: false, hinweis: '' },
          { id: 'say', verfuegbar: true, hinweis: '' },
          { id: 'piper', verfuegbar: false, hinweis: '' },
        ],
        stimmen: [],
        stimmenHinweis: '',
        aktiveStimme: null,
      }}
      onSelect={() => {}}
    />
  );
  const voices = (
    <StimmeSectionView
      current={{
        aktiv: 'piper',
        engines: [{ id: 'piper', verfuegbar: true, hinweis: '' }],
        stimmen: [{ id: 'thorsten', label: 'Thorsten', lizenz: 'CC-BY 4.0' }],
        stimmenHinweis: '',
        aktiveStimme: 'thorsten',
      }}
      activeVoice="thorsten"
      onSelectVoice={() => {}}
      onPlaySample={() => {}}
    />
  );
  const brain = (
    <BrainModelSectionView
      current={{
        aktiv: 'e2b',
        modelle: [{ id: 'e2b', label: 'Gemma-4 E2B', repo: 'mlx-community/gemma-4-e2b-it-4bit' }],
        status: 'ok',
      }}
      onSelect={() => {}}
    />
  );

  it('englisch: Labels, Platzhalter, Lade-Zeilen, Engine-Namen und Badges sind Englisch', () => {
    const weatherHtml = renderIn('en', weather);
    expectNoGerman(weatherHtml, 'WeatherLocationSectionView', SETTINGS_GERMAN);
    expectAll(weatherHtml, 'WeatherLocationSectionView', [
      'Weather location',
      'placeholder="e.g. Duisburg"',
      'Current: Duisburg',
    ]);

    expectAll(renderIn('en', weatherLoading), 'WeatherLocationSectionView (lädt)', ['loading…']);
    expectNoGerman(renderIn('en', weatherLoading), 'WeatherLocationSectionView (lädt)', SETTINGS_GERMAN);

    const lookupHtml = renderIn('en', lookupLoading);
    expectNoGerman(lookupHtml, 'LookupModelSectionView (lädt)', SETTINGS_GERMAN);
    expectAll(lookupHtml, 'LookupModelSectionView (lädt)', ['Online lookup', 'loading…']);

    const engineHtml = renderIn('en', engines);
    expectNoGerman(engineHtml, 'TtsEngineSectionView', SETTINGS_GERMAN);
    expectAll(engineHtml, 'TtsEngineSectionView', [
      'macOS say (local)',
      'Piper (local)',
      'OpenAI (cloud)',
      'not started',
    ]);

    const voiceHtml = renderIn('en', voices);
    expectNoGerman(voiceHtml, 'StimmeSectionView', SETTINGS_GERMAN);
    expectAll(voiceHtml, 'StimmeSectionView', [
      'aria-label="Play voice sample of thorsten"',
      'title="Play voice sample"',
      'License: CC-BY 4.0',
    ]);

    const brainHtml = renderIn('en', brain);
    expectNoGerman(brainHtml, 'BrainModelSectionView', SETTINGS_GERMAN);
    expectAll(brainHtml, 'BrainModelSectionView', ['Status: ', 'running']);
  });

  it('deutsch (Gegenprobe): dieselben Stellen sind weiterhin wörtlich deutsch', () => {
    expectAll(renderIn('de', weather), 'WeatherLocationSectionView', [
      'Wetter-Ort',
      'placeholder="z. B. Duisburg"',
      'Aktuell: Duisburg',
    ]);
    expectAll(renderIn('de', weatherLoading), 'WeatherLocationSectionView (lädt)', ['lädt…']);
    expectAll(renderIn('de', lookupLoading), 'LookupModelSectionView (lädt)', ['lädt…']);
    expectAll(renderIn('de', engines), 'TtsEngineSectionView', [
      'macOS say (lokal)',
      'Piper (lokal)',
      'OpenAI (Cloud)',
      'nicht gestartet',
    ]);
    expectAll(renderIn('de', voices), 'StimmeSectionView', [
      'aria-label="Hörprobe der Stimme thorsten abspielen"',
      'title="Hörprobe abspielen"',
      'Lizenz: CC-BY 4.0',
    ]);
    expectAll(renderIn('de', brain), 'BrainModelSectionView', ['Status: ', 'läuft']);
  });
});

describe('EN-Sweep — SkillsSection folgt der UI-Sprache, NICHT der Chat-Sprache', () => {
  const skills = (
    <SkillsSection
      skills={[skill({ tier: 'EGRESS' }), skill({ id: 'SCENES', labelDe: 'Szenen', labelEn: 'Scenes', locked: true })]}
      onToggle={() => {}}
    />
  );

  it('englisch: Skill-Namen, Badges, Hinweis und Zukunfts-Skills sind Englisch', () => {
    const html = renderIn('en', skills);
    expectNoGerman(html, 'SkillsSection', [...SETTINGS_GERMAN, 'Szenen', 'Smart-Home', 'Musik']);
    expectAll(html, 'SkillsSection', [
      'Smart home',
      'Scenes',
      'goes online',
      'disabled at deploy',
      'Lists',
      'decision with Andi still open',
      'Music',
      'coming',
      'Turns individual skills on and off at runtime',
    ]);
  });

  it('deutsch (Gegenprobe): dieselbe Sektion ist weiterhin wörtlich deutsch', () => {
    const html = renderIn('de', skills);
    expectAll(html, 'SkillsSection', [
      'Smart-Home',
      'Szenen',
      'geht online',
      'deaktiviert beim Deploy',
      'Listen',
      'Andi-Gabel offen',
      'Musik',
      'kommt noch',
      'Schaltet einzelne Fähigkeiten zur Laufzeit ein/aus',
    ]);
  });

  it('lädt…: die Lade-Zeile der Skills-Liste ist im englischen Modus Englisch', () => {
    const loading = <SkillsSection skills={[]} loading onToggle={() => {}} />;
    expectAll(renderIn('en', loading), 'SkillsSection (lädt)', ['loading…']);
    expectNoGerman(renderIn('en', loading), 'SkillsSection (lädt)', ['lädt…'], { umlauts: false });
    expectAll(renderIn('de', loading), 'SkillsSection (lädt)', ['lädt…']);
  });
});

describe('EN-Sweep — SettingsPanel: Dialograhmen + der lang-Bug im vollen Panel', () => {
  /**
   * Der REGRESSIONS-Kern von (c): die Chat-Sprache steht auf 'auto' (der
   * Default!), die UI auf Englisch. Vor dem Fix las `SkillsSection` die
   * Chat-Sprache — die Skill-Zeile blieb deutsch, obwohl die Oberfläche
   * Englisch war. Jetzt entscheidet die UI-Sprache.
   */
  const panel = (
    <SettingsPanel
      open
      onClose={() => {}}
      theme="aoi"
      language="auto"
      persona="Standard"
      voice="coral"
      onTheme={() => {}}
      onLanguage={() => {}}
      onPersona={() => {}}
      onVoice={() => {}}
    />
  );

  /**
   * EHRLICHE GRENZE dieses einen Falls: der Static-Render führt KEINE Effekte
   * aus, die Sub-Sektionen stehen also in ihrem Lade-/Leer-Zustand. Die
   * Sprecher-LISTE (`· N Sätze`, „Profil X löschen", „angelernt {Datum}") und
   * der Anlern-Dialog (drei deutsche Nachsprech-Sätze, „Satz i von 3") sind
   * darum hier NICHT im Markup — sie sind der letzte bekannte deutsche Rest im
   * Panel und bewusst nicht Teil dieser Scheibe.
   */
  it('englisch: das GANZE Panel ist Englisch — kein Umlaut, kein deutscher Rest mehr', () => {
    const html = renderIn('en', panel);
    expectNoGerman(html, 'SettingsPanel (voll)', [
      'aria-label="Einstellungen"',
      '>Einstellungen<',
      'aria-label="Einstellungen schließen"',
      // Skills-Sektion mit Chat-Sprache 'auto' — vor dem Fix deutsch:
      'kommt noch',
      'Andi-Gabel offen',
      'Track startet',
      'Musik',
      'Schaltet einzelne',
      // Der Langschwanz aus dieser Scheibe (vorher Modul-Konstanten/JSX):
      ...PANEL_LONGTAIL_GERMAN,
    ]);
    expectAll(html, 'SettingsPanel (voll)', [
      'aria-label="Settings"',
      '>Settings<',
      'aria-label="Close settings"',
      'Lists',
      'Music',
      'coming',
      // Kategorie-Reiter (waren eine deutsche Modul-Konstante):
      '>Appearance<',
      '>Language &amp; voice<',
      '>Personality<',
      '>Memory &amp; privacy<',
      // Farbthema / Sprache / Persönlichkeit (THEMES/LANGUAGES/PERSONAS):
      'Colour theme',
      'Morning blue on ink',
      // Die drei Gruppen des Pickers (Scheibe 25.07) inkl. Beiwort + Sora-Zeile:
      '>Follows the day<',
      '>Times of day<',
      '>Your own mood<',
      'aria-label="Times of day: Nagareboshi"',
      ' · shooting star',
      'follows the day · now ',
      'Automatic (German / English)',
      'This is how I sound',
      // Wecker-Eskalation + Privatsphäre:
      'Alarm escalation',
      '>seconds<',
      '>Privacy<',
    ]);
  });

  it('deutsch (Gegenprobe): Titel, Reiter, Themen und Skill-Zeile bleiben wörtlich deutsch', () => {
    const html = renderIn('de', panel);
    expectAll(html, 'SettingsPanel (voll)', [
      'aria-label="Einstellungen"',
      '>Einstellungen<',
      'aria-label="Einstellungen schließen"',
      'Listen',
      'Musik',
      'kommt noch',
      'aria-label="Einstellungs-Kategorien"',
      '>Darstellung<',
      '>Sprache &amp; Stimme<',
      '>Persönlichkeit<',
      '>Gedächtnis &amp; Privatsphäre<',
      'Farbthema',
      'Morgenblau auf Tinte',
      '>Folgt dem Tag<',
      '>Tageszeiten<',
      '>Eigene Stimmung<',
      'aria-label="Tageszeiten: Nagareboshi"',
      ' · Sternschnuppe',
      'folgt dem Tag · jetzt ',
      'Automatisch (Deutsch / Englisch)',
      'So klinge ich',
      'Wecker-Eskalation',
      '>Sekunden<',
      '>Privatsphäre<',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  6b) ThemeSection — der gruppierte Farbthema-Picker (Scheibe 25.07). Eigener
//      Sweep, weil hier die MEISTEN neuen Texte liegen: drei Gruppen-
//      Überschriften + Einordnungs-Zeilen, die Beiworte der japanischen Namen,
//      die Sora-Zeile und der Pin-Hinweis.
// ─────────────────────────────────────────────────────────────────────────────

describe('EN-Sweep — ThemeSection (Farbthema in drei Gruppen)', () => {
  // 'aoi' ist eine Tageszeit ⇒ der Pin-Hinweis rendert mit.
  const picker = <ThemeSection theme="aoi" onTheme={() => {}} />;

  it('englisch: Gruppen, Beiworte, Sora-Zeile und Pin-Hinweis sind Englisch', () => {
    const html = renderIn('en', picker);
    expectNoGerman(html, 'ThemeSection', PANEL_LONGTAIL_GERMAN);
    expectAll(html, 'ThemeSection', [
      'aria-label="Colour theme"',
      '>Follows the day<',
      '>Times of day<',
      '>Your own mood<',
      'aria-label="Follows the day: Sora"',
      'aria-label="Your own mood: Natsu no Hi"',
      'follows the day · now ',
      ' · daybreak',
      ' · summer day',
      'is pinned right now',
    ]);
  });

  it('deutsch (Gegenprobe): dieselben Stellen stehen wörtlich deutsch da', () => {
    expectAll(renderIn('de', picker), 'ThemeSection', [
      'aria-label="Farbthema"',
      '>Folgt dem Tag<',
      '>Tageszeiten<',
      '>Eigene Stimmung<',
      'aria-label="Folgt dem Tag: Sora"',
      'aria-label="Eigene Stimmung: Natsu no Hi"',
      'folgt dem Tag · jetzt ',
      ' · Morgengrauen',
      ' · Sommertag',
      'die Automatik pausiert',
    ]);
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  LANGSCHWANZ-SCHEIBE 25.07 — die in 380c779 ehrlich vertagten Stellen
// ═════════════════════════════════════════════════════════════════════════════

/** Was im Panel VOR dieser Scheibe hart deutsch im Markup stand. */
const PANEL_LONGTAIL_GERMAN = [
  'Einstellungs-Kategorien',
  'Darstellung',
  'Sprache &amp; Stimme',
  'Persönlichkeit',
  'Modell &amp; Leistung',
  'Fähigkeiten',
  'Gedächtnis &amp; Privatsphäre',
  'Standort &amp; Integrationen',
  'Farbthema',
  'Morgenblau auf Tinte',
  'folgt dem Tag',
  // Die drei Gruppen des Pickers + ihre Einordnungs-Zeilen (Scheibe 25.07):
  'Folgt dem Tag',
  'Tageszeiten',
  'Eigene Stimmung',
  'Keine Farbe, sondern eine Regel',
  'wechseln normalerweise von selbst',
  'Bilder statt Tageszeiten',
  'die Automatik pausiert',
  // …und die Beiworte der japanischen Namen:
  'Sternschnuppe',
  'Morgengrauen',
  'Sommertag',
  'Automatisch (Deutsch / Englisch)',
  'Steuert die Chat-Sprache',
  'So klinge ich',
  'Hoshis Grundton',
  'Wecker-Eskalation',
  '>Sekunden<',
  'bimmelt erst am Gerät',
  '>Privatsphäre<',
  'Ehrlicher Ist-Stand',
  'Cloud-Maskierung',
  'Nutzungs-Diary',
];

// ─────────────────────────────────────────────────────────────────────────────
//  7) SettingsCategoryNav — die sieben Reiter waren eine deutsche Modul-Konstante
// ─────────────────────────────────────────────────────────────────────────────

describe('EN-Sweep — SettingsCategoryNav (Reiter-Leiste)', () => {
  const nav = <SettingsCategoryNav active="faehigkeiten" onSelect={() => {}} />;

  it('englisch: aria-label und alle sieben Reiter-Labels sind Englisch', () => {
    const html = renderIn('en', nav);
    expectNoGerman(html, 'SettingsCategoryNav', PANEL_LONGTAIL_GERMAN);
    expectAll(html, 'SettingsCategoryNav', [
      'aria-label="Settings categories"',
      '>Appearance<',
      '>Language &amp; voice<',
      '>Personality<',
      '>Model &amp; performance<',
      '>Skills<',
      '>Memory &amp; privacy<',
      '>Location &amp; integrations<',
    ]);
  });

  it('deutsch (Gegenprobe): dieselben sieben Reiter stehen weiterhin wörtlich deutsch da', () => {
    expectAll(renderIn('de', nav), 'SettingsCategoryNav', [
      'aria-label="Einstellungs-Kategorien"',
      '>Darstellung<',
      '>Sprache &amp; Stimme<',
      '>Persönlichkeit<',
      '>Modell &amp; Leistung<',
      '>Fähigkeiten<',
      '>Gedächtnis &amp; Privatsphäre<',
      '>Standort &amp; Integrationen<',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  8) EscalationSection — Label, Einheit („Sekunden") und der ganze Hinweistext
// ─────────────────────────────────────────────────────────────────────────────

describe('EN-Sweep — EscalationSection (Wecker-Eskalation)', () => {
  const section = <EscalationSection seconds={20} onSeconds={() => {}} />;

  it('englisch: Label, Einheit und Hinweis sind Englisch — die Zahl bleibt die echte', () => {
    const html = renderIn('en', section);
    expectNoGerman(html, 'EscalationSection', [
      'Wecker-Eskalation',
      'Sekunden',
      'bimmelt erst am Gerät',
      'wenn niemand reagiert',
    ]);
    expectAll(html, 'EscalationSection', [
      'Alarm escalation',
      '>seconds<',
      'after 20 seconds on all of them',
    ]);
  });

  it('deutsch (Gegenprobe): „Sekunden" + „nach 20 Sekunden auf allen" stehen wörtlich da', () => {
    expectAll(renderIn('de', section), 'EscalationSection', [
      'Wecker-Eskalation',
      '>Sekunden<',
      'nach 20 Sekunden auf allen',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  9) PrivacySectionView — Überschrift, TTS-Zeilen, Maskierung, Lösch-Labels
// ─────────────────────────────────────────────────────────────────────────────

const privacySummary = (over: Partial<PrivacySummary> = {}): PrivacySummary => ({
  voice: { engine: 'voxtral', cloud: false },
  sanitize: { enabled: false },
  memory: { enabled: true, path: '/x/entity-memory.db', exists: true, sizeBytes: 12288, entries: 5 },
  episodic: { enabled: false, path: '/x/episodic-memory.db', exists: false, sizeBytes: 0, entries: null },
  diary: { enabled: true, dir: '/x/diary', days: 3 },
  ...over,
});

const PRIVACY_GERMAN = [
  'Privatsphäre',
  'Ehrlicher Ist-Stand',
  'Stimme (TTS)',
  'kein Text verlässt die Box',
  'geht für die Sprachausgabe zu OpenAI',
  'Cloud-Maskierung',
  'Gedächtnis',
  'Episoden-Gedächtnis',
  'Nutzungs-Diary',
  'lokale Datei',
  'Einträge',
  'Tages-Dateien',
  'löschen',
  'lädt…',
];

describe('EN-Sweep — PrivacySectionView (Vertrauens-Screen)', () => {
  const local = <PrivacySectionView summary={privacySummary()} onDelete={() => {}} />;
  const cloud = (
    <PrivacySectionView
      summary={privacySummary({ voice: { engine: 'openai', cloud: true }, sanitize: { enabled: true } })}
      onDelete={() => {}}
    />
  );
  const loading = <PrivacySectionView summary={null} loading onDelete={() => {}} />;

  it('englisch: Überschrift, Store-Zeilen, Maskierung und Lösch-aria sind Englisch', () => {
    const localHtml = renderIn('en', local);
    expectNoGerman(localHtml, 'PrivacySectionView (lokal)', PRIVACY_GERMAN);
    expectAll(localHtml, 'PrivacySectionView (lokal)', [
      '>Privacy<',
      'Voice (TTS): voxtral',
      'no text leaves the box',
      'Cloud masking: off',
      'Memory (facts about you)',
      'local file · 5 entries',
      'nothing stored yet',
      '3 daily files',
      'aria-label="Delete Memory"',
      'aria-label="Delete Episodic memory"',
      'aria-label="Delete Usage diary"',
    ]);

    const cloudHtml = renderIn('en', cloud);
    expectNoGerman(cloudHtml, 'PrivacySectionView (Cloud)', PRIVACY_GERMAN);
    expectAll(cloudHtml, 'PrivacySectionView (Cloud)', [
      'Voice (TTS): openai',
      'goes to OpenAI for speech output',
      'Cloud masking: on',
    ]);

    const loadingHtml = renderIn('en', loading);
    expectNoGerman(loadingHtml, 'PrivacySectionView (lädt)', PRIVACY_GERMAN);
    expectAll(loadingHtml, 'PrivacySectionView (lädt)', ['loading…']);
  });

  it('deutsch (Gegenprobe): dieselben Zeilen stehen weiterhin wörtlich deutsch da', () => {
    expectAll(renderIn('de', local), 'PrivacySectionView (lokal)', [
      '>Privatsphäre<',
      'Stimme (TTS): voxtral',
      'kein Text verlässt die Box',
      'Cloud-Maskierung: aus',
      'Gedächtnis (Fakten über dich)',
      'lokale Datei · 5 Einträge',
      'noch nichts gespeichert',
      '3 Tages-Dateien',
      'aria-label="Gedächtnis löschen"',
      'aria-label="Nutzungs-Diary löschen"',
    ]);
    expectAll(renderIn('de', loading), 'PrivacySectionView (lädt)', ['lädt…']);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  10) ScheduledPanel — die Zeile DIREKT über der Eingabe (Timer/Wecker)
// ─────────────────────────────────────────────────────────────────────────────

const NOW = new Date(2026, 6, 25, 6, 0).getTime();
const sched = (over: Partial<ScheduledItem> = {}): ScheduledItem => ({
  id: 's-1',
  kind: 'TIMER',
  dueAtEpochMs: NOW + 12 * 60_000,
  ...over,
});

const SCHED_GERMAN = [
  'Aktive Timer und Wecker',
  'Aktive Timer &amp; Wecker',
  'Aufklappen',
  'Zuklappen',
  'nichts aktiv',
  'alle löschen',
  'Wecker',
  'Erinnerung',
  'noch ',
  'nächster in',
  'löschen',
];

/**
 * Klappt das Panel in jsdom wirklich auf und liefert das GERENDERTE Markup.
 * `then` re-rendert nach dem Aufklappen mit einer anderen Item-Liste (leer UND
 * eingeklappt rendert NICHTS — der Leer-Zustand ist nur nach dem Aufklappen zu
 * sehen, exakt wie in scheduledpanel.test.tsx).
 */
async function renderExpandedSchedule(
  lang: 'de' | 'en',
  items: ScheduledItem[],
  then?: ScheduledItem[],
): Promise<string> {
  setActiveUiLanguage(lang);
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  const render = (list: ScheduledItem[]) =>
    root.render(
      <ScheduledPanel items={list} nowMs={NOW} onDelete={() => {}} onDeleteAll={() => {}} />,
    );
  try {
    await act(async () => render(items));
    await act(async () => {
      container.querySelector<HTMLButtonElement>('.sched__toggle')!.click();
    });
    if (then) await act(async () => render(then));
    return container.innerHTML;
  } finally {
    await act(async () => root.unmount());
    container.remove();
  }
}

describe('EN-Sweep — ScheduledPanel (eingeklappt + aufgeklappt)', () => {
  const collapsed = (
    <ScheduledPanel items={[sched(), sched({ id: 's-2' })]} nowMs={NOW} onDelete={() => {}} />
  );

  it('englisch (eingeklappt): aria-label, title und Zusammenfassung sind Englisch', () => {
    const html = renderIn('en', collapsed);
    expectNoGerman(html, 'ScheduledPanel (zu)', SCHED_GERMAN);
    expectAll(html, 'ScheduledPanel (zu)', [
      'aria-label="Active timers and alarms"',
      'title="Expand — manage"',
      '2 Timers · next one in 12 min',
    ]);
  });

  it('deutsch (Gegenprobe, eingeklappt): dieselbe Zeile bleibt wörtlich deutsch', () => {
    expectAll(renderIn('de', collapsed), 'ScheduledPanel (zu)', [
      'aria-label="Aktive Timer und Wecker"',
      'title="Aufklappen — verwalten"',
      '2 Timer · nächster in 12 min',
    ]);
  });

  it('englisch (aufgeklappt): Zeilen, ✕-aria und „alle löschen" sind Englisch', async () => {
    const html = await renderExpandedSchedule('en', [
      sched({ id: 'a', label: 'Tea' }),
      sched({ id: 'b', kind: 'REMINDER' }),
    ]);
    expectNoGerman(html, 'ScheduledPanel (offen)', SCHED_GERMAN);
    expectAll(html, 'ScheduledPanel (offen)', [
      '12 min left',
      '>Reminder<',
      'aria-label="Delete Timer &quot;Tea&quot;"',
      'title="Delete"',
      '>delete all<',
    ]);
  });

  it('englisch (aufgeklappt, leer): „nothing active" statt „nichts aktiv"', async () => {
    const html = await renderExpandedSchedule('en', [sched()], []);
    expectNoGerman(html, 'ScheduledPanel (leer)', SCHED_GERMAN);
    expectAll(html, 'ScheduledPanel (leer)', ['nothing active']);
  });

  it('deutsch (Gegenprobe, aufgeklappt): die deutschen Zeilen stehen weiterhin wörtlich da', async () => {
    const html = await renderExpandedSchedule('de', [
      sched({ id: 'a', label: 'Tee' }),
      sched({ id: 'b', kind: 'REMINDER' }),
    ]);
    expectAll(html, 'ScheduledPanel (offen)', [
      'noch 12 min',
      '>Erinnerung<',
      'title="Löschen"',
      '>alle löschen<',
    ]);
  });

  it('Uhrzeit folgt der Sprache: ein Wecker rendert de-DE 24h, en-US 12h (t.locale statt hart de-DE)', async () => {
    const alarm = sched({ id: 'w', kind: 'ALARM', dueAtEpochMs: new Date(2026, 6, 25, 19, 5).getTime() });
    const deHtml = await renderExpandedSchedule('de', [alarm, sched({ id: 'z' })]);
    expect(deHtml).toContain('um 19:05');
    const enHtml = await renderExpandedSchedule('en', [alarm, sched({ id: 'z' })]);
    expect(enHtml).not.toContain('um 19:05');
    expect(enHtml).toMatch(/at 07:05\s?PM/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  11) OpsStatusPill — immer sichtbar in der Kopfzeile
// ─────────────────────────────────────────────────────────────────────────────

const ops = (over: Partial<OpsStatus> = {}): OpsStatus => ({
  overall: 'DEGRADED',
  memory: { level: 'WARN', source: 'brain-health', detail: 'swap active' },
  sidecars: [{ name: 'brain', status: 'OK', detail: 'ok' }],
  voice: { engine: 'openai', cloud: true },
  allLocal: false,
  ts: 1,
  ...over,
});

const OPS_GERMAN = [
  'Ops · Achtung',
  'Ops · kritisch',
  'RAM kritisch',
  'RAM-Druck',
  'Gesamt',
  'Stimme kommt gerade aus der Cloud',
  'läuft lokal',
  'verlässt das Gerät nicht',
  'Alles lokal',
  'deiner Freigabe',
];

describe('EN-Sweep — OpsStatusPill (Kopfzeile)', () => {
  const warnCloud = <OpsStatusPill status={ops()} defaultExpanded />;
  const criticalLocal = (
    <OpsStatusPill
      status={ops({
        overall: 'DOWN',
        memory: { level: 'CRITICAL', source: 'brain-health', detail: 'swap active' },
        voice: { engine: 'piper', cloud: false },
        allLocal: true,
      })}
      defaultExpanded
    />
  );

  it('englisch: Ton-Label, RAM-Wort, Cloud-/Lokal-Zeile und Schloss-Satz sind Englisch', () => {
    const warnHtml = renderIn('en', warnCloud);
    expectNoGerman(warnHtml, 'OpsStatusPill (WARN/Cloud)', OPS_GERMAN);
    expectAll(warnHtml, 'OpsStatusPill (WARN/Cloud)', [
      'RAM pressure',
      'Ops: overall DEGRADED · RAM WARN',
      'Voice is currently coming from the cloud (OpenAI)',
    ]);

    const critHtml = renderIn('en', criticalLocal);
    expectNoGerman(critHtml, 'OpsStatusPill (CRITICAL/lokal)', OPS_GERMAN);
    expectAll(critHtml, 'OpsStatusPill (CRITICAL/lokal)', [
      'RAM critical',
      'Voice (piper): runs locally — never leaves the device.',
      'All local — your voice never leaves the device.',
    ]);
  });

  it('deutsch (Gegenprobe): dieselben Zeilen bleiben wörtlich deutsch', () => {
    expectAll(renderIn('de', warnCloud), 'OpsStatusPill (WARN/Cloud)', [
      'RAM-Druck',
      'Ops: Gesamt DEGRADED · RAM WARN',
      'Stimme kommt gerade aus der Cloud (OpenAI)',
    ]);
    expectAll(renderIn('de', criticalLocal), 'OpsStatusPill (CRITICAL/lokal)', [
      'RAM kritisch',
      'Stimme (piper): läuft lokal — verlässt das Gerät nicht.',
      'Alles lokal — deine Stimme verlässt das Gerät nicht. Online-Recherche nur nach deiner Freigabe.',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  12) SpeakerChip — „Gast" stand unter jedem englischen Sprach-Turn
// ─────────────────────────────────────────────────────────────────────────────

describe('EN-Sweep — SpeakerChip (erkannt + Gast)', () => {
  const guest = <SpeakerChip speaker={{ name: null, confidence: 0.2, isGuest: true }} />;
  const known = <SpeakerChip speaker={{ name: 'Andi', confidence: 0.97, isGuest: false }} />;

  it('englisch: Gast-Label, Vera-Tooltip und Erkannt-Tooltip sind Englisch', () => {
    const guestHtml = renderIn('en', guest);
    expectNoGerman(guestHtml, 'SpeakerChip (Gast)', ['>Gast<', 'Nicht sicher erkannt', 'Erkannt als']);
    expectAll(guestHtml, 'SpeakerChip (Gast)', [
      '>Guest<',
      'title="Not recognised for sure — better a guest than the wrong person."',
    ]);

    const knownHtml = renderIn('en', known);
    expectNoGerman(knownHtml, 'SpeakerChip (erkannt)', ['Erkannt als', 'Stimm-Ähnlichkeit']);
    expectAll(knownHtml, 'SpeakerChip (erkannt)', ['Recognised as Andi · voice similarity 97%']);
  });

  it('deutsch (Gegenprobe): „Gast" und der Vera-Satz stehen weiterhin wörtlich da', () => {
    expectAll(renderIn('de', guest), 'SpeakerChip (Gast)', [
      '>Gast<',
      'title="Nicht sicher erkannt — lieber Gast als die falsche Person."',
    ]);
    expectAll(renderIn('de', known), 'SpeakerChip (erkannt)', [
      'Erkannt als Andi · Stimm-Ähnlichkeit 97 %',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  13) StageSparkline — Tooltips + aria-label der Aktivitäts-Kacheln
// ─────────────────────────────────────────────────────────────────────────────

describe('EN-Sweep — StageSparkline (Tooltips + aria)', () => {
  const spark = (
    <StageSparkline
      label="STT"
      points={[
        { ms: 120, ts: '2026-07-25T08:00:00.000Z' },
        { ms: 200, ts: '2026-07-25T08:05:00.000Z', error: true },
        { ms: 99_000, ts: '2026-07-25T08:10:00.000Z' },
      ]}
      p50={160}
      p95={400}
    />
  );

  it('englisch: „(outlier)"/„· error" und der aria-Satz sind Englisch', () => {
    const html = renderIn('en', spark);
    expectNoGerman(html, 'StageSparkline', ['Ausreißer', '· Fehler', 'heute:', 'Messwert', 'Median']);
    expectAll(html, 'StageSparkline', [
      '(outlier)',
      '· error',
      'STT today: 3 measurements',
      'median 160 ms',
      'p95 400 ms',
    ]);
  });

  it('deutsch (Gegenprobe): „(Ausreißer)"/„· Fehler"/„Messwerte" stehen weiterhin da', () => {
    expectAll(renderIn('de', spark), 'StageSparkline', [
      '(Ausreißer)',
      '· Fehler',
      'STT heute: 3 Messwerte',
      'Median 160 ms',
    ]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  14) api/chat + api/voice — die Fehlertexte landen WÖRTLICH als Chat-Blase
// ─────────────────────────────────────────────────────────────────────────────

describe('EN-Sweep — API-Fehlertexte (werden als Chat-Blase gerendert)', () => {
  const respond = (status: number) =>
    vi.fn().mockResolvedValue({ ok: false, status, body: null, json: () => Promise.resolve({}) });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('englisch: 401/415/HTTP-Fehler kommen auf Englisch aus der API-Schicht', async () => {
    setActiveUiLanguage('en');
    vi.stubGlobal('fetch', respond(401));
    await expect(streamChat('hi', { onEvent: () => {} })).rejects.toThrow(
      '401 — token missing or invalid (auth wall). Set VITE_TOKEN.',
    );
    vi.stubGlobal('fetch', respond(415));
    await expect(streamVoice(new Blob(['x']), { onEvent: () => {} })).rejects.toThrow(
      '415 — backend rejects the audio content type (/api/v1/voice).',
    );
    vi.stubGlobal('fetch', respond(503));
    await expect(streamChat('hi', { onEvent: () => {} })).rejects.toThrow(
      'Backend responded HTTP 503',
    );
  });

  it('deutsch (Gegenprobe): dieselben Fehler bleiben wörtlich deutsch', async () => {
    setActiveUiLanguage('de');
    vi.stubGlobal('fetch', respond(401));
    await expect(streamChat('hi', { onEvent: () => {} })).rejects.toThrow(
      '401 — Token fehlt oder ist ungültig (Auth-Wand). Setze VITE_TOKEN.',
    );
    vi.stubGlobal('fetch', respond(415));
    await expect(streamVoice(new Blob(['x']), { onEvent: () => {} })).rejects.toThrow(
      '415 — Backend lehnt den Audio-Content-Type ab (/api/v1/voice).',
    );
    vi.stubGlobal('fetch', respond(503));
    await expect(streamChat('hi', { onEvent: () => {} })).rejects.toThrow(
      'Backend antwortete HTTP 503',
    );
  });
});

/* ── Uhr: der Tagesabschnitt sitzt separat und leiser ─────────────────────── */
describe('IdleFace-Uhr — „PM" springt nicht mehr an', () => {
  it('englisch: Ziffern und Tagesabschnitt sind GETRENNTE Elemente', () => {
    const { time, period } = clockParts(Date.UTC(2026, 6, 25, 17, 5), 'en-US');
    expect(period).toBeTruthy(); // en-US hat einen Tagesabschnitt
    expect(time).not.toMatch(/[AP]M/i); // die Ziffern tragen ihn NICHT mehr mit
    expect(time).toMatch(/\d/);
  });

  it('deutsch: 24-Stunden-Uhr ⇒ kein Tagesabschnitt, Ziffern unverändert', () => {
    const { time, period } = clockParts(Date.UTC(2026, 6, 25, 17, 5), 'de-DE');
    expect(period).toBeNull();
    expect(time).toBe(dueClock(Date.UTC(2026, 6, 25, 17, 5), 'de-DE'));
  });

  it('die beiden Teile ergeben zusammen wieder die volle Uhrzeit', () => {
    const ms = Date.UTC(2026, 6, 25, 17, 5);
    const { time, period } = clockParts(ms, 'en-US');
    const full = dueClock(ms, 'en-US');
    expect(full).toContain(time);
    if (period) expect(full).toContain(period);
  });
});
