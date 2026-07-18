import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { TopNav, type Tab } from './components/TopNav';
import { ChatViewBody } from './components/ChatView';
import { UebersichtViewLive } from './views/UebersichtView';
import { RaeumeViewLive } from './views/RaeumeView';
import { AktivitaetViewLive } from './views/AktivitaetView';
import {
  SettingsPanel,
  type SettingsAnchorId,
  type SettingsCategoryId,
} from './components/SettingsPanel';
import { FiredToast } from './components/FiredToast';
import { useSettings, useEscalationSeconds, useResolvedTheme } from './hooks/useSettings';
import { useFiredItems } from './hooks/useFiredItems';
import { useVoiceChatSession } from './hooks/useVoiceChatSession';
import { getDeviceId } from './api/device';
import './styles/themes.css';

export default function App() {
  const [tab, setTab] = useState<Tab>('overview');
  const [settingsOpen, setSettingsOpen] = useState(false);
  // Deep-Link-Ziel des Settings-Drawers (Cowork-Spec 03-settings-einbettung.md
  // V1): `undefined` heißt „keine Vorgabe" — der normale Top-Nav-Zahnrad-Aufruf
  // öffnet weiter auf der zuletzt gewählten Kategorie (unverändertes Verhalten).
  const [settingsCategory, setSettingsCategory] = useState<SettingsCategoryId | undefined>(
    undefined,
  );
  const [settingsAnchor, setSettingsAnchor] = useState<SettingsAnchorId | undefined>(undefined);
  /**
   * Die EINE Öffnungs-Naht für den Settings-Drawer — ersetzt das bloße
   * `setSettingsOpen(true)`. Ohne Argumente (Top-Nav-Zahnrad): öffnet, wie
   * heute, auf der zuletzt gewählten Kategorie. Mit `category` (+ optionalem
   * `anchor`): kontextuelle Zahnräder (Wetter-Kachel/Sprecher-Chip/Wecker-
   * Banner) springen direkt dorthin — SettingsPanel scrollt zum Anker und
   * pulst ihn einmal (reduced-motion-konform über die globale Regel).
   */
  const openSettings = useCallback((category?: SettingsCategoryId, anchor?: SettingsAnchorId) => {
    setSettingsCategory(category);
    setSettingsAnchor(anchor);
    setSettingsOpen(true);
  }, []);
  const { theme, language, persona, voice, setTheme, setLanguage, setPersona, setVoice } =
    useSettings();
  // Die EINE Voice-Chat-Session der App (Andi-Auftrag 19.07): App.tsx ruft sie
  // genau einmal auf und reicht sie an den Chat-Reiter UND den Home-Orb durch —
  // beide sehen denselben `turns`-Verlauf, dieselbe Aufnahme/Wiedergabe. Sie
  // lebt hier (nicht in den Views), damit ein Reiter-Wechsel mitten in einem
  // Turn ihn NICHT abbricht (die Views mounten/unmounten per `key={tab}`, die
  // Session nicht).
  const session = useVoiceChatSession({ persona, language, voice });
  // Eskalations-Frist (persistent) — steuert, ab wann auch FREMDE Geräte bimmeln.
  const { seconds: escalationSeconds, setSeconds: setEscalationSeconds } = useEscalationSeconds();
  // Stabile Geräte-Id dieses Browsers (aus localStorage; einmal erzeugt) — das
  // Ursprungs-Urteil des Klingel-Hooks hängt daran.
  const deviceId = getDeviceId();
  // Klingel-Naht: pollt /api/v1/scheduled/fired (~5s, idempotent — jeder Tab
  // sieht dasselbe) und klingelt ursprungs-gebunden: der Banner erscheint überall
  // (Tap = ack-POST, dann weg — für alle Tabs), der TON aber sofort nur am
  // Ursprungs-Gerät (origin===deviceId) — fremde Geräte erst nach escalationSeconds
  // ab dem ersten Sichten. Bei Autoplay-Sperre pulsiert der Banner + Titel-Blink.
  const {
    items: firedItems,
    ack: ackFired,
    silenced: firedSilenced,
  } = useFiredItems(5000, { deviceId, escalationSeconds });

  // Sora (Arbeitsname) löst sich zur Laufzeit in eins der vier Rotations-Themes
  // auf (siehe useSettings.ts); jedes andere Theme geht unverändert durch. Am
  // <html> landet IMMER das aufgelöste, sichtbare Theme — gespeichert bleibt
  // weiterhin die Original-Wahl (`theme`, inkl. 'sora').
  const resolvedTheme = useResolvedTheme(theme);

  // Farbthema am <html> spiegeln → die [data-theme]-Overrides in styles/themes.css
  // greifen app-weit. Yoru braucht keinen Override (= :root-Default → byte-neutral).
  useEffect(() => {
    document.documentElement.dataset.theme = resolvedTheme;
  }, [resolvedTheme]);

  // Views hier (nicht auf Modulebene) bauen: `session` selbst bekommt die
  // aktuelle Persona/Sprache/Stimme aus useSettings (oben) — so fließt die
  // Panel-Wahl EXPLIZIT in jeden Chat-/Voice-Request (genau wie theme/language
  // ans SettingsPanel), egal ob der Turn im Chat-Reiter oder am Home-Orb startet.
  const views: Record<Tab, ReactNode> = {
    overview: <UebersichtViewLive onOpenSettings={openSettings} session={session} />,
    rooms: <RaeumeViewLive />,
    activity: <AktivitaetViewLive />,
    chat: <ChatViewBody session={session} onOpenSettings={openSettings} />,
  };

  return (
    <div className="app">
      <FiredToast
        items={firedItems}
        onAck={ackFired}
        silenced={firedSilenced}
        onOpenSettings={openSettings}
      />
      <TopNav tab={tab} onTab={setTab} onOpenSettings={() => openSettings()} />
      {/* key={tab} → der Inhalt mountet beim Tab-Wechsel neu und spielt einmal die
          ruhige `view-in`-Einblendung (Cross-Fade/leichtes Hochgleiten). Die Views
          remounten ohnehin (anderer Komponententyp); das key macht es nur explizit. */}
      <main className="app__main" key={tab}>
        {views[tab]}
      </main>

      <SettingsPanel
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        theme={theme}
        language={language}
        persona={persona}
        voice={voice}
        onTheme={setTheme}
        onLanguage={setLanguage}
        onPersona={setPersona}
        onVoice={setVoice}
        escalationSeconds={escalationSeconds}
        onEscalationSeconds={setEscalationSeconds}
        category={settingsCategory}
        anchor={settingsAnchor}
      />
    </div>
  );
}
