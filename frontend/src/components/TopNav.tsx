import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { OpsStatusPillLive } from './OpsStatusPill';
import { CrewOverlayLive } from './CrewOverlay';
import { GearGlyph } from './icons';
import { useUiStrings } from '../i18n';

export type Tab = 'overview' | 'rooms' | 'activity' | 'chat';

interface Props {
  tab: Tab;
  onTab: (tab: Tab) => void;
  onOpenSettings: () => void;
}

/**
 * Reihenfolge der status-first Navigation — Andi-Auftrag 19.07: „Chat rückt
 * auf Reiter 2, Übersicht bleibt/wird Reiter 1 (Start-Ansicht)". Zuhause ist
 * die Landing (App.tsx defaultet `tab` weiter auf 'overview'); Chat folgt
 * direkt danach, weil der neue Home-Orb (VoiceOrb) den schnellen Sprach-Weg
 * abdeckt und Chat jetzt der zweite, nicht mehr der letzte Reiter ist.
 *
 * Die Labels selbst kommen aus `useUiStrings().topNav` (Video-Tag-Befund
 * 21.07: die Reiter riefen den Hook bisher NICHT auf und blieben deutsch,
 * egal welche Sprache aktiv war) — darum baut {@link TopNav} das Array jetzt
 * INNERHALB der Komponente, die `id`-Schlüssel (Programmier-Werte) bleiben
 * unverändert.
 */

/** 7× auf das 星 (leise Marke links vor dem Wortmark) in diesem Fenster öffnet die Crew. */
const TAP_WINDOW_MS = 3000;
const TAP_GOAL = 7;

/** Konami-Code (↑↑↓↓←→←→ b a) — die alternative, klassische Geste. */
const KONAMI: readonly string[] = [
  'ArrowUp',
  'ArrowUp',
  'ArrowDown',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'ArrowLeft',
  'ArrowRight',
  'b',
  'a',
];

/** Schlanke status-first Top-Nav: Übersicht / Räume / Aktivität / Chat + Health-Badge. */
export function TopNav({ tab, onTab, onOpenSettings }: Props) {
  const { topNav } = useUiStrings();
  const TABS: { id: Tab; label: string }[] = useMemo(
    () => [
      { id: 'overview', label: topNav.overview },
      { id: 'chat', label: topNav.chat },
      { id: 'rooms', label: topNav.rooms },
      { id: 'activity', label: topNav.activity },
    ],
    [topNav],
  );
  const [crewOpen, setCrewOpen] = useState(false);
  // Tap-Zähler aufs 星 (Refs → kein Re-Render pro Klick, kein Timer-Leak).
  const tapsRef = useRef(0);
  const firstTapRef = useRef(0);
  // Fortschritt im Konami-Code.
  const konamiRef = useRef(0);

  const openCrew = useCallback(() => setCrewOpen(true), []);

  // 星-Tap: 7× in 3 s → Crew. Außerhalb des Fensters zählt der Klick als
  // neuer erster Tap (kein „nie zurücksetzen"-Stau).
  const onStarTap = useCallback(() => {
    const now = Date.now();
    if (now - firstTapRef.current > TAP_WINDOW_MS) {
      firstTapRef.current = now;
      tapsRef.current = 0;
    }
    tapsRef.current += 1;
    if (tapsRef.current >= TAP_GOAL) {
      tapsRef.current = 0;
      openCrew();
    }
  }, [openCrew]);

  // Konami-Code global lauschen (case-tolerant für b/a). Tippt man daneben,
  // springt der Fortschritt nicht auf 0, sondern erkennt einen Neustart der
  // Sequenz (gängige Implementierung: bei Fehlpass auf 1 prüfen).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
      const expected = KONAMI[konamiRef.current];
      if (key === expected) {
        konamiRef.current += 1;
        if (konamiRef.current === KONAMI.length) {
          konamiRef.current = 0;
          openCrew();
        }
      } else {
        konamiRef.current = key === KONAMI[0] ? 1 : 0;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [openCrew]);

  return (
    <>
      <header className="nav">
        <div className="nav__brand">
          {/* 星 (Hoshi) — die Marke, leise links VOR dem Wortmark: gedämpft,
              klein, kein Leuchten. Trägt das versteckte Crew-Easter-Egg:
              7× tippen in 3 s öffnet das Overlay (bewusst kein Hinweis im UI);
              Tastatur-Nutzer erreichen die Crew via Konami-Code. */}
          <button
            type="button"
            className="nav__hoshi"
            onClick={onStarTap}
            aria-label="Hoshi (星)"
            title="Hoshi"
          >
            星
          </button>
          <span className="nav__title" title="Hoshi">
            Hoshi
          </span>
          <span className="nav__ver">0.8 · Nagareboshi</span>
        </div>

        <nav className="nav__tabs" aria-label={topNav.mainNav}>
          {TABS.map((t) => (
            <button
              key={t.id}
              className={`nav__tab ${tab === t.id ? 'is-active' : ''}`}
              onClick={() => onTab(t.id)}
              aria-current={tab === t.id}
            >
              {t.label}
            </button>
          ))}
        </nav>

        {/* Status-Gruppe rechts: Backend-Health + dezente Ops-Pille (RAM/Sidecars).
            Ein gemeinsamer Wrapper hält beide gepaart; die Nav bricht bei schmaler
            Breite um (flex-wrap), darum kein Header-Overflow. Die Ops-Pille rendert
            bei enabled:false/Fehler nichts → byte-neutral, wenn das Flag aus ist. */}
        <div className="nav__status">
          {/* EIN Status-Element in der Nav: die Ops-Pille (reagiert, trägt Detail).
              Der frühere zweite HealthBadge-Punkt daneben war redundant und ohne
              Interaktion — Backend-Gesundheit wohnt weiter in der Übersicht. */}
          <OpsStatusPillLive />
          {/* Dezentes Zahnrad (Inline-SVG, kein Emoji) öffnet das Einstellungs-Panel.
              Sitzt in der status-Gruppe → bricht mit der Nav um, kein Header-Overflow. */}
          <button
            type="button"
            className="nav__settings"
            onClick={onOpenSettings}
            aria-label={topNav.openSettingsAria}
            title={topNav.settingsTitle}
          >
            <GearGlyph className="nav__settingsicon" />
          </button>
        </div>
      </header>

      {/* Verstecktes Crew-Reveal (Easter-Egg): bleibt gemountet, blendet über
          is-open ein/aus. Lädt /api/v1/crew beim ersten Öffnen. */}
      <CrewOverlayLive open={crewOpen} onClose={() => setCrewOpen(false)} />
    </>
  );
}
