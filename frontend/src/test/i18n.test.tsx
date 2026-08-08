/** @vitest-environment jsdom */
import { describe, it, expect, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  CATALOGS,
  getActiveUiLanguage,
  resolveUiStrings,
  setActiveUiLanguage,
  useUiStrings,
} from '../i18n';
import { LanguageSectionView } from '../components/LanguageSection';
import {
  EnrollDialog,
  SpeakerListView,
  formatEnrolledDate,
  micSupport,
  sampleProgress,
} from '../components/SpeakerSection';
import { NightModeDeviceListView } from '../components/NightModeSection';
import { SettingsPanel, WeatherLocationSectionView } from '../components/SettingsPanel';
import { FiredToast } from '../components/FiredToast';
import type { FiredItem } from '../hooks/useFiredItems';
import { IdleFace } from '../components/IdleFace';
import { TopNav } from '../components/TopNav';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * Sprach-UI-Tests (Andi-Auftrag 21.07: „Ich muss die Sprache der UI auch in den
 * Einstellungen auswählen können" — die EINE bestehende Sprachwahl steuert jetzt
 * auch die UI-Texte). Drei Beweise, wie im Auftrag gefordert:
 *  1. de-Default ist BYTE-GLEICH zum bisherigen Stand (Sample-Assertions gegen
 *     hart getippte Erwartungswerte, nicht nur Selbstreferenz).
 *  2. Ein Sprachwechsel rendert echte en-Strings — sowohl beim ersten Mount
 *     (renderToStaticMarkup, Konvention der Bestandstests) als auch LIVE ohne
 *     Remount (useUiStrings' Subscribe-Effekt, per createRoot/act geprüft).
 *  3. Ein unbekannter/leerer Sprachcode fällt IMMER auf de zurück.
 *
 * Store-Hygiene: {@link setActiveUiLanguage} ist ein Modul-Singleton — jeder
 * Test, der ihn verändert, setzt ihn in `afterEach` zurück. Andere Testdateien
 * bekommen ohnehin ein frisches Modul (Vitest isoliert per Datei).
 *
 * NACHTRAG 21.07 (Video-Tag-Befund): `IdleFace` (Startbildschirm) und `TopNav`
 * (Reiter) riefen `useUiStrings` bisher gar nicht auf — die App blieb komplett
 * deutsch, egal welche Sprache aktiv war. Die beiden Suiten unten schließen
 * genau diese Lücke: (a) das deutsche Rendering bleibt Bestand, (b) ein
 * Sprachwechsel macht Reiter + Idle-Gruß wirklich englisch.
 */

const item = (over: Partial<FiredItem> = {}): FiredItem => ({
  id: 'f-1',
  kind: 'ALARM',
  dueAtEpochMs: 1_000,
  firedAtEpochMs: 1_100,
  missed: false,
  ...over,
});

afterEach(() => {
  setActiveUiLanguage('de');
});

describe('i18n — de ist byte-gleich zum bisherigen Stand', () => {
  it('Katalog-Werte matchen hart getippte Erwartungswerte (Sample über alle neun Dateien)', () => {
    const de = resolveUiStrings('de');
    expect(de.weatherLocation.notFound).toBe('Ort nicht gefunden.');
    expect(de.lookupModel.label).toBe('Online-Nachschlag');
    expect(de.ttsEngine.label).toBe('TTS-Engine');
    expect(de.stimme.label).toBe('Stimme');
    expect(de.brainModel.statusOk).toBe('läuft');
    expect(de.privacy.delete).toBe('Löschen');
    expect(de.speaker.enrollButton).toBe('Meine Stimme anlernen');
    expect(de.nightMode.groupTitle).toBe('Nachtmodus');
    expect(de.language.label).toBe('Hoshi spricht (Server-Standard)');
    expect(de.firedToast.headline.ALARM).toBe('Wecker klingelt');
    expect(de.firedToast.missedNoun.TIMER).toBe('Timer');
    expect(de.activity.title).toBe('Aktivität');
    expect(de.activity.rest).toBe('sonstiges');
    expect(de.locale).toBe('de-DE');
    expect(de.rooms.offNote).toBe(
      'Räume kommen, sobald Home Assistant verdrahtet ist. Das 0.8-Backend exponiert heute noch keine Geräte- oder Raum-Registry — darum steht hier bewusst nichts Erfundenes.',
    );
    expect(de.overview.liveStreaming).toBe('Live-Streaming');
    expect(de.chat.placeholder).toBe('Nachricht an Hoshi…');
    expect(de.chat.greeting('morning')).toBe('Guten Morgen — was darf ich tun?');
    expect(de.voiceChat.slowTurn).toBe('dauert grad länger als sonst — ich bin dran.');
  });

  it('gerenderte Sektionen zeigen im Default (de) exakt den Bestandstext', () => {
    expect(getActiveUiLanguage()).toBe('de');

    const lang = renderToStaticMarkup(<LanguageSectionView current={null} onSelect={() => {}} />);
    expect(lang).toContain('Hoshi spricht (Server-Standard)');
    expect(lang).toContain(
      'UI-Texte und Gespräch folgen dieser Auswahl — Smart-Home-Befehle bleiben vorerst Deutsch.',
    );

    const speaker = renderToStaticMarkup(
      <SpeakerListView
        speakers={null}
        onDelete={() => {}}
        onEnroll={() => {}}
        onContinue={() => {}}
        onDeleteSample={() => {}}
      />,
    );
    expect(speaker).toContain('Erkannte Sprecher');
    expect(speaker).toContain('Meine Stimme anlernen');

    const night = renderToStaticMarkup(
      <NightModeDeviceListView
        rows={[]}
        selectedId={null}
        manualId=""
        onManualId={() => {}}
        onManualSubmit={() => {}}
        onSelect={() => {}}
      />,
    );
    expect(night).toContain('Noch kein Satellit verbunden.');

    const weather = renderToStaticMarkup(
      <WeatherLocationSectionView current={null} place="" onPlace={() => {}} onSave={() => {}} />,
    );
    expect(weather).toContain('Der Ort für Wetter-Fragen');

    const toast = renderToStaticMarkup(<FiredToast items={[item()]} onAck={() => {}} />);
    expect(toast).toContain('Wecker klingelt');
  });
});

describe('i18n — ein Sprachwechsel rendert die andere Sprache', () => {
  it('setActiveUiLanguage("en") vor dem Mount ⇒ die Sektionen zeigen Englisch', () => {
    setActiveUiLanguage('en');

    const lang = renderToStaticMarkup(<LanguageSectionView current={null} onSelect={() => {}} />);
    expect(lang).toContain('Hoshi speaks (server default)');
    expect(lang).toContain(
      'UI text and conversation follow this choice — smart-home commands stay German for now.',
    );

    const speaker = renderToStaticMarkup(
      <SpeakerListView
        speakers={null}
        onDelete={() => {}}
        onEnroll={() => {}}
        onContinue={() => {}}
        onDeleteSample={() => {}}
      />,
    );
    expect(speaker).toContain('Enroll my voice');

    const night = renderToStaticMarkup(
      <NightModeDeviceListView
        rows={[]}
        selectedId={null}
        manualId=""
        onManualId={() => {}}
        onManualSubmit={() => {}}
        onSelect={() => {}}
      />,
    );
    expect(night).toContain('No satellite connected yet.');

    const toast = renderToStaticMarkup(<FiredToast items={[item()]} onAck={() => {}} />);
    expect(toast).toContain('Alarm is ringing');

    const en = resolveUiStrings('en');
    expect(en.activity.title).toBe('Activity');
    expect(en.activity.rest).toBe('other');
    expect(en.locale).toBe('en-US');
    expect(en.rooms.title).toBe('Rooms');
    expect(en.overview.backend).toBe('Backend');
    expect(en.chat.placeholder).toBe('Message Hoshi…');
    expect(en.chat.greeting('morning')).toBe('Good morning — what can I do for you?');
    expect(en.chat.greeting('morning')).not.toContain('Guten Morgen');
    expect(en.voiceChat.slowTurn).toContain('longer than usual');
  });

  it('useUiStrings folgt einem LIVE-Wechsel ohne Remount (Subscribe-Effekt)', async () => {
    function Probe() {
      const t = useUiStrings();
      return <span>{t.language.label}</span>;
    }

    const container = document.createElement('div');
    let root: Root;
    await act(async () => {
      root = createRoot(container);
      root.render(<Probe />);
    });
    expect(container.textContent).toBe('Hoshi spricht (Server-Standard)');

    await act(async () => {
      setActiveUiLanguage('en');
    });
    expect(container.textContent).toBe('Hoshi speaks (server default)');

    await act(async () => {
      setActiveUiLanguage('es');
    });
    expect(container.textContent).toBe('Hoshi habla (valor por defecto del servidor)');

    act(() => {
      root!.unmount();
    });
  });
});

describe('i18n — Fallback ist IMMER de', () => {
  it('unbekannter/leerer Sprachcode löst auf den de-Katalog auf (Referenzgleichheit)', () => {
    expect(resolveUiStrings('xx')).toBe(CATALOGS.de);
    expect(resolveUiStrings('')).toBe(CATALOGS.de);
    expect(resolveUiStrings('DE')).toBe(CATALOGS.de); // Codes sind case-sensitiv, 'DE' ist nicht gelistet
  });

  it('setActiveUiLanguage ignoriert unbekannte Codes — der Store bleibt unverändert', () => {
    setActiveUiLanguage('fr');
    expect(getActiveUiLanguage()).toBe('fr');
    setActiveUiLanguage('xx-nonsense');
    expect(getActiveUiLanguage()).toBe('fr');
    setActiveUiLanguage('');
    expect(getActiveUiLanguage()).toBe('fr');
  });
});

// Dienstag, 21. Juli 2026, 07:04 Ortszeit (lokal konstruiert, TZ-unabhängig) —
// derselbe „Video-Tag" wie der Auftrag: 07 Uhr ⇒ Morgen-Gruß, Wochentag „Dienstag"/„Tuesday".
const IDLE_NOW = new Date(2026, 6, 21, 7, 4).getTime();

describe('i18n — IdleFace/TopNav (Video-Tag-Befund 21.07): deutsches Rendering bleibt Bestand', () => {
  it('IdleFace rendert im Default (de) exakt den bisherigen deutschen Text', () => {
    expect(getActiveUiLanguage()).toBe('de');
    const html = renderToStaticMarkup(
      <IdleFace
        nowMs={IDLE_NOW}
        health="up"
        voice={null}
        scheduled={[]}
        weather={null}
        shopping={[]}
      />,
    );
    expect(html).toContain('Guten Morgen'); // Tageszeit-Gruß
    expect(html).toContain('Dienstag, 21. Juli'); // Datum folgt de-DE
    expect(html).toContain('Kein Wecker gestellt');
    expect(html).toContain('Wird gerade gelesen.'); // Jetzt-Band: erster Fetch läuft (weather=null)
    expect(html).toContain('Zuhause'); // section aria-label
    expect(html).toContain('online'); // Status-Chip
  });

  it('TopNav rendert im Default (de) die vier deutschen Reiter-Labels + Bedienelemente', () => {
    const html = renderToStaticMarkup(
      <TopNav tab="overview" onTab={() => {}} onOpenSettings={() => {}} />,
    );
    const order = ['Übersicht', 'Chat', 'Räume', 'Aktivität'].map((label) => html.indexOf(label));
    expect(order.every((i) => i >= 0)).toBe(true);
    expect(order).toEqual([...order].sort((a, b) => a - b));
    expect(html).toContain('Hauptnavigation');
    expect(html).toContain('Einstellungen öffnen');
    expect(html).toContain('Einstellungen');
  });
});

describe('i18n — setActiveUiLanguage("en") macht den ersten Bildschirm wirklich englisch', () => {
  it('IdleFace: Gruß, Wochentag und alle Kachel-/Statustexte werden englisch', () => {
    setActiveUiLanguage('en');
    const html = renderToStaticMarkup(
      <IdleFace
        nowMs={IDLE_NOW}
        health="up"
        voice={null}
        scheduled={[]}
        weather={null}
        shopping={[]}
      />,
    );
    expect(html).toContain('Good morning');
    expect(html).toContain('Tuesday, July 21'); // Wochentag „Dienstag" → „Tuesday" (Punkt c des Auftrags)
    expect(html).toContain('No alarm set');
    expect(html).toContain('Reading now.');
    expect(html).toContain('Home'); // section aria-label
    expect(html).not.toContain('Guten Morgen');
    expect(html).not.toContain('Dienstag');
    expect(html).not.toContain('Kein Wecker gestellt');
  });

  it('TopNav: die vier Reiter + Bedienelemente werden englisch', () => {
    setActiveUiLanguage('en');
    const html = renderToStaticMarkup(
      <TopNav tab="overview" onTab={() => {}} onOpenSettings={() => {}} />,
    );
    expect(html).toContain('Overview');
    expect(html).toContain('Chat');
    expect(html).toContain('Rooms');
    expect(html).toContain('Activity');
    expect(html).toContain('Main navigation');
    expect(html).toContain('Open settings');
    expect(html).not.toContain('Übersicht');
    expect(html).not.toContain('Räume');
    expect(html).not.toContain('Aktivität');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Andi-Auftrag 2026-07-27 „fünf Sprachen ohne Sternchen": Español/Français/
//  Italiano waren als UI-Katalog schon vollständig (CATALOGS.es/fr/it), aber die
//  lokale Chat-/STT-Sprachwahl (Settings → Sprache & Stimme) bot bisher nur
//  Automatisch/Deutsch/English an. Stichproben über mehrere Bildschirme, dass
//  ein Wechsel der UI-Sprache (activeLanguageStore, wie bisher NUR über „Hoshi
//  spricht (Server-Standard)" gesetzt) auch in den drei neuen Sprachen wirklich
//  übersetzten Text zeigt — UND dass das SettingsPanel-Sprache-Select (Chat+STT)
//  jetzt sechs Optionen mit den korrekten Endonymen führt.
// ─────────────────────────────────────────────────────────────────────────────

describe('i18n — Español/Français/Italiano rendern echte Übersetzungen (Stichproben)', () => {
  const renderPanel = () =>
    renderToStaticMarkup(
      <SettingsPanel
        open={true}
        onClose={() => {}}
        theme="yoru"
        language="auto"
        persona="Standard"
        voice="coral"
        onTheme={() => {}}
        onLanguage={() => {}}
        onPersona={() => {}}
        onVoice={() => {}}
      />,
    );

  it('Español (es): IdleFace, TopNav und das Sprache-Select zeigen echtes Spanisch', () => {
    setActiveUiLanguage('es');

    const idle = renderToStaticMarkup(
      <IdleFace nowMs={IDLE_NOW} health="up" voice={null} scheduled={[]} weather={null} shopping={[]} />,
    );
    expect(idle).toContain('Buenos días');

    const nav = renderToStaticMarkup(
      <TopNav tab="overview" onTab={() => {}} onOpenSettings={() => {}} />,
    );
    expect(nav).toContain('Actividad');

    const panel = renderPanel();
    expect(panel).toContain('>Idioma y voz<'); // Reiter „Sprache & Stimme"
    expect(panel).toContain('Automático (alemán / inglés)'); // Chat-/STT-Select, Option 'auto'
    expect(panel).toContain('>Español<');
    expect(panel).toContain('>Français<');
    expect(panel).toContain('>Italiano<');
    expect(panel).toContain('Hoshi habla (valor por defecto del servidor)'); // „Hoshi spricht"-Sektion
  });

  it('Français (fr): IdleFace, TopNav und das Sprache-Select zeigen echtes Französisch', () => {
    setActiveUiLanguage('fr');

    const idle = renderToStaticMarkup(
      <IdleFace nowMs={IDLE_NOW} health="up" voice={null} scheduled={[]} weather={null} shopping={[]} />,
    );
    expect(idle).toContain('Bonjour');

    const nav = renderToStaticMarkup(
      <TopNav tab="overview" onTab={() => {}} onOpenSettings={() => {}} />,
    );
    expect(nav).toContain('Activité');

    const panel = renderPanel();
    expect(panel).toContain('>Langue et voix<');
    expect(panel).toContain('Automatique (allemand / anglais)');
    expect(panel).toContain('>Español<');
    expect(panel).toContain('>Français<');
    expect(panel).toContain('>Italiano<');
    expect(panel).toContain('Hoshi parle (valeur par défaut du serveur)');
  });

  it('Italiano (it): IdleFace, TopNav und das Sprache-Select zeigen echtes Italienisch', () => {
    setActiveUiLanguage('it');

    const idle = renderToStaticMarkup(
      <IdleFace nowMs={IDLE_NOW} health="up" voice={null} scheduled={[]} weather={null} shopping={[]} />,
    );
    expect(idle).toContain('Buongiorno');

    const nav = renderToStaticMarkup(
      <TopNav tab="overview" onTab={() => {}} onOpenSettings={() => {}} />,
    );
    expect(nav).toContain('Attività');

    const panel = renderPanel();
    expect(panel).toContain('>Lingua e voce<');
    expect(panel).toContain('Automatico (tedesco / inglese)');
    expect(panel).toContain('>Español<');
    expect(panel).toContain('>Français<');
    expect(panel).toContain('>Italiano<');
    expect(panel).toContain('Hoshi parla (predefinito del server)');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Sprecher-Anlern-Flow (Teil 2 des Auftrags 2026-07-27): ENROLL_SENTENCES waren
//  schon fünfsprachig, aber `formatEnrolledDate`/`sampleProgress`/`micSupport`
//  (SpeakerSection.tsx) lasen bisher hart Deutsch bzw. eine feste Modul-
//  Referenz, unabhängig von der aktiven UI-Sprache. Jetzt lesen alle drei aus
//  dem aktiven Katalog (Muster: `getActiveUiLanguage` in api/chat.ts).
// ─────────────────────────────────────────────────────────────────────────────

describe('i18n — Sprecher-Anlern-Flow folgt der aktiven Sprache (Stichproben)', () => {
  const speaker = { name: 'andi', enrolledAt: 1720000000000, samples: 3 };

  it('Español: Fortschritt, Anlern-Datum, Profil-Zeile, Anführungszeichen und Mikro-Hinweis', () => {
    setActiveUiLanguage('es');
    expect(sampleProgress(1)).toBe('Frase 1 de 3');
    expect(formatEnrolledDate(0)).toBe('justo ahora');

    const list = renderToStaticMarkup(
      <SpeakerListView
        speakers={[speaker]}
        onDelete={() => {}}
        onEnroll={() => {}}
        onContinue={() => {}}
        onDeleteSample={() => {}}
      />,
    );
    expect(list).toContain('registrado el'); // enrolledOn-Präfix
    expect(list).toContain('3 frases'); // sentenceCount
    expect(list).toContain('Eliminar perfil andi'); // deleteProfileAria

    expect(micSupport().ok).toBe(false); // jsdom kennt kein navigator.mediaDevices
    const dialog = renderToStaticMarkup(<EnrollDialog onClose={() => {}} onEnrolled={() => {}} />);
    expect(dialog).toContain('«'); // Anführungszeichen der Anlern-Sätze
    expect(dialog).toContain(CATALOGS.es.speaker.noMic);
  });

  it('Français : progression, date d’enregistrement, ligne de profil, guillemets et message micro', () => {
    setActiveUiLanguage('fr');
    expect(sampleProgress(1)).toBe('Phrase 1 sur 3');
    expect(formatEnrolledDate(0)).toBe('à l’instant');

    const list = renderToStaticMarkup(
      <SpeakerListView
        speakers={[speaker]}
        onDelete={() => {}}
        onEnroll={() => {}}
        onContinue={() => {}}
        onDeleteSample={() => {}}
      />,
    );
    expect(list).toContain('enregistré le');
    expect(list).toContain('3 phrases');
    expect(list).toContain('Supprimer le profil andi');

    expect(micSupport().ok).toBe(false);
    const dialog = renderToStaticMarkup(<EnrollDialog onClose={() => {}} onEnrolled={() => {}} />);
    expect(dialog).toContain('«');
    expect(dialog).toContain(CATALOGS.fr.speaker.noMic);
  });

  it('Italiano: avanzamento, data di registrazione, riga del profilo, virgolette e messaggio microfono', () => {
    setActiveUiLanguage('it');
    expect(sampleProgress(1)).toBe('Frase 1 di 3');
    expect(formatEnrolledDate(0)).toBe('proprio ora');

    const list = renderToStaticMarkup(
      <SpeakerListView
        speakers={[speaker]}
        onDelete={() => {}}
        onEnroll={() => {}}
        onContinue={() => {}}
        onDeleteSample={() => {}}
      />,
    );
    expect(list).toContain('registrato il');
    expect(list).toContain('3 frasi');
    expect(list).toContain('Elimina il profilo andi');

    expect(micSupport().ok).toBe(false);
    const dialog = renderToStaticMarkup(<EnrollDialog onClose={() => {}} onEnrolled={() => {}} />);
    expect(dialog).toContain('«');
    expect(dialog).toContain(CATALOGS.it.speaker.noMic);
  });

  it('Deutsch (Gegenprobe): Fortschritt/Datum/Zähler/Aria bleiben byte-gleich zum Bestand', () => {
    expect(getActiveUiLanguage()).toBe('de');
    expect(sampleProgress(1)).toBe('Satz 1 von 3');
    expect(formatEnrolledDate(0)).toBe('gerade eben');

    const list = renderToStaticMarkup(
      <SpeakerListView
        speakers={[speaker]}
        onDelete={() => {}}
        onEnroll={() => {}}
        onContinue={() => {}}
        onDeleteSample={() => {}}
      />,
    );
    expect(list).toContain('angelernt');
    expect(list).toContain('3 Sätze');
    expect(list).toContain('Profil andi löschen');
  });
});
