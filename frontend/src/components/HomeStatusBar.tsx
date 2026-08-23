import { Fragment } from 'react';
import { CloudGlyph, LockGlyph } from './icons';
import { statusChips, type StatusChip } from './IdleFace';
import type { HealthState } from '../hooks/useHealth';
import type { OpsVoice } from '../hooks/useOpsStatus';
import { useUiStrings } from '../i18n';

/**
 * **Die Zuhause-Fußleiste** — `● online · 🔒 Stimme: lokal` unten links, als
 * Gegenstück zur Nav-Insel oben (Andi-Bestellung 19.08.: *„Ich möchte unten
 * links die Statusmeldung … das aber auch schön eingebunden, etwas wie die
 * Leiste oben, nur unten"*).
 *
 * **Nichts Neues wird behauptet.** Der Inhalt ist byte-gleich der bisherige
 * Chip-Streifen aus {@link ../components/IdleFace.tsx#IdleFace} — dieselbe
 * pure {@link statusChips}-Regel (Health immer ehrlich, der Stimme-Chip NUR bei
 * echtem `voice`-Feld), dieselben Klassen `.idle__chip*`, derselbe
 * `role="status"`. Umgezogen ist die POSITION, nicht die Aussage: der Streifen
 * war die vierte `auto`-Zeile im `.idle`-Grid und stand damit mitten in der
 * Komposition, ~230 px über der Fensterkante. Jetzt ist er der Boden der Seite.
 *
 * **Warum eine eigene Datei statt einer Zeile mehr in `IdleFace`:** die Leiste
 * muss UNTER dem Orb liegen. `IdleFace` und `VoiceOrb` sind Geschwister in der
 * `.app__main`-Spalte (`views/UebersichtView.tsx`) — alles, was `IdleFace`
 * rendert, steht zwangsläufig ÜBER dem Orb. Die Leiste ist darum das dritte
 * Geschwister.
 *
 * **Kein zweiter Poller.** `voice` kommt als Prop herein; `useOpsStatus()` läuft
 * genau EINMAL, in `UebersichtViewLive`, und speist von dort das Idle-Gesicht
 * (das den Chip nicht mehr selbst rendert) und diese Leiste. Damit bleibt die
 * Hausregel „eine Quelle je Endpoint" unangetastet.
 *
 * Prop-getrieben und hook-arm (nur der Sprach-Katalog) → per
 * `renderToStaticMarkup` testbar wie das Idle-Gesicht selbst.
 */

/** Ton → Glyph: ●-Punkt für Health (CSS färbt), Wolke/Schloss als muted SVG. */
function chipGlyph(tone: StatusChip['tone']) {
  if (tone === 'cloud') return <CloudGlyph />;
  if (tone === 'local') return <LockGlyph />;
  return '●';
}

export interface HomeStatusBarProps {
  health: HealthState;
  /** `null` = das BE liefert kein voice-Feld ⇒ kein Stimme-Chip (nie behauptet). */
  voice: OpsVoice | null;
}

export function HomeStatusBar({ health, voice }: HomeStatusBarProps) {
  const { idleFace } = useUiStrings();
  const chips = statusChips(health, voice, idleFace);
  return (
    <footer className="homefoot">
      <p className="idle__chips" role="status" aria-live="polite">
        {chips.map((c, i) => (
          <Fragment key={c.text}>
            {i > 0 && (
              <span className="idle__chipsep" aria-hidden="true">
                ·
              </span>
            )}
            <span className={`idle__chip idle__chip--${c.tone}`}>
              <span className="idle__chipglyph" aria-hidden="true">
                {chipGlyph(c.tone)}
              </span>{' '}
              {c.text}
            </span>
          </Fragment>
        ))}
      </p>
    </footer>
  );
}
