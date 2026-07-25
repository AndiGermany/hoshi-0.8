/** @vitest-environment jsdom */
import { describe, it, expect, afterEach } from 'vitest';
import type { ReactElement } from 'react';
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
  LookupModelSectionView,
  SettingsPanel,
  SkillsSection,
  StimmeSectionView,
  TtsEngineSectionView,
  WeatherLocationSectionView,
} from '../components/SettingsPanel';
import type { VoiceChatSession } from '../hooks/useVoiceChatSession';
import type { FiredItem } from '../hooks/useFiredItems';
import type { Skill } from '../api/types';

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
//  EHRLICHE GRENZE: der SettingsPanel-RAHMEN trägt noch den in 380c779 bewusst
//  vertagten Langschwanz (Kategorie-Reiter, Farbthema/Sprache/Persönlichkeit
//  aus Modul-Konstanten, PrivacySection). Für den vollen Panel-Render wird
//  darum gezielt der Dialograhmen geprüft (Titel/Schließen) statt eines
//  Umlaut-Sweeps über alles — die katalogisierten Sektionen bekommen ihren
//  eigenen, scharfen Sweep.
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

  it('englisch: Dialogtitel/Schließen sind Englisch und die Skill-Badges folgen der UI-Sprache', () => {
    const html = renderIn('en', panel);
    expectNoGerman(
      html,
      'SettingsPanel (Rahmen)',
      [
        'aria-label="Einstellungen"',
        '>Einstellungen<',
        'aria-label="Einstellungen schließen"',
        // Skills-Sektion mit Chat-Sprache 'auto' — vor dem Fix deutsch:
        'kommt noch',
        'Andi-Gabel offen',
        'Track startet',
        'Musik',
        'Schaltet einzelne',
      ],
      // Der Rahmen trägt noch den bewusst vertagten Langschwanz (Kategorie-
      // Reiter, Farbthema/Sprache/Persönlichkeit, PrivacySection) — ein
      // Umlaut-Sweep über ALLES wäre hier schlicht gelogen.
      { umlauts: false },
    );
    expectAll(html, 'SettingsPanel (Rahmen)', [
      'aria-label="Settings"',
      '>Settings<',
      'aria-label="Close settings"',
      'Lists',
      'Music',
      'coming',
    ]);
  });

  it('deutsch (Gegenprobe): Titel, Schließen-Label und Skill-Zeile bleiben deutsch', () => {
    const html = renderIn('de', panel);
    expectAll(html, 'SettingsPanel (Rahmen)', [
      'aria-label="Einstellungen"',
      '>Einstellungen<',
      'aria-label="Einstellungen schließen"',
      'Listen',
      'Musik',
      'kommt noch',
    ]);
  });
});
