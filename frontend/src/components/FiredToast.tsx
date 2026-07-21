import type { FiredItem, FiredKind } from '../hooks/useFiredItems';
import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import { AlarmGlyph, GearGlyph } from './icons';
import type { SettingsAnchorId, SettingsCategoryId } from './SettingsPanel';

/**
 * Persistenter Klingel-Banner oben mittig: „⏰ Timer ist fertig" (+ Label, falls
 * vorhanden). Er bleibt, bis der Mensch ihn ANTIPPT — der Tap quittiert
 * (ack-POST via useFiredItems), erst dann ist das Klingeln weg, für ALLE Tabs.
 * Rein prop-getrieben (kein Hook, kein Netz) → per renderToStaticMarkup
 * unit-testbar (Konvention OpsStatusPill); die Live-Verdrahtung macht App.tsx.
 *
 * Ehrlichkeit:
 *  - `missed=true` (Downtime-Nachzügler oder >30 min unbestätigt) wird NICHT
 *    als frisches Klingeln verkauft, sondern als Verpasst-Meldung: „Timer war
 *    um HH:MM fällig — hab dich nicht erreicht" ({@link missedLine}).
 *  - `silenced` (Autoplay-Sperre: der Chime ist JETZT unhörbar) lässt den
 *    Banner sichtbar pulsieren (`fired-toast--pulse`) — visuell laut, wenn
 *    akustisch nichts geht; der Ton kommt bei der ersten Geste nach.
 *
 * A11y: der Wrapper ist die `role="alert"`-Live-Region (Screenreader melden
 * das Klingeln sofort), der Inhalt ein natives <button> — Klick UND Tastatur
 * quittieren. Kein Auto-Timeout: ein Wecker verschwindet NIE von selbst.
 *
 * Kontextueller Settings-Anker (Cowork-Spec 03-settings-einbettung.md V1):
 * ein zweiter, eigenständiger Zahnrad-Knopf („Eskalation ändern") springt in
 * Fähigkeiten/Wecker-Eskalation — bewusst NICHT im Ack-Button verschachtelt
 * (kein Button-in-Button), sondern als Geschwister-Knopf im selben Rahmen.
 */

/**
 * Kind-ehrliche Überschrift — ein Wecker ist kein Timer. Jetzt eine Referenz
 * auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum bisherigen Stand);
 * {@link FiredToast} rendert stattdessen `useUiStrings().firedToast.headline`.
 */
export const FIRED_HEADLINE: Record<FiredKind, string> = de.firedToast.headline;

/** Kind-Nomen für die Verpasst-Meldung — dieselbe Referenz-Regel wie oben. */
export const MISSED_NOUN: Record<FiredKind, string> = de.firedToast.missedNoun;

/** Fälligkeit als lokale Uhrzeit „HH:MM" (de-DE, 24h). */
export function dueTimeLabel(dueAtEpochMs: number): string {
  return new Date(dueAtEpochMs).toLocaleTimeString('de-DE', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Ehrliche Verpasst-Zeile: „Timer „Tee" war um 07:00 fällig — hab dich nicht
 * erreicht". `missedNoun` ist injizierbar (Default: der DE-Katalog, s. oben) —
 * {@link FiredToast} reicht den Katalog der AKTIVEN UI-Sprache durch; Tests, die
 * ohne zweites Argument aufrufen, sehen unverändert Deutsch.
 */
export function missedLine(item: FiredItem, missedNoun: Record<FiredKind, string> = MISSED_NOUN): string {
  const noun = missedNoun[item.kind];
  const subject = item.label ? `${noun} „${item.label}"` : noun;
  return `${subject} war um ${dueTimeLabel(item.dueAtEpochMs)} fällig — hab dich nicht erreicht`;
}

/**
 * Eine Banner-Zeile pro Item: frisch = Überschrift (+ Label), verpasst =
 * ehrliche Meldung. `headline`/`missedNoun` injizierbar (Default: DE) — siehe
 * {@link missedLine}.
 */
export function firedLine(
  item: FiredItem,
  headline: Record<FiredKind, string> = FIRED_HEADLINE,
  missedNoun: Record<FiredKind, string> = MISSED_NOUN,
): string {
  if (item.missed) return missedLine(item, missedNoun);
  return item.label ? `${headline[item.kind]} — ${item.label}` : headline[item.kind];
}

export function FiredToast({
  items,
  onAck,
  silenced = false,
  onOpenSettings,
}: {
  items: FiredItem[];
  onAck: () => void;
  silenced?: boolean;
  /**
   * Deep-Link in den Settings-Drawer (App.tsx `openSettings`). Optional:
   * fehlt es (z. B. in Tests), rendert der Banner ohne Zahnrad — kein Bruch,
   * nur ein fehlender Komfort-Anker.
   */
  onOpenSettings?: (category: SettingsCategoryId, anchor?: SettingsAnchorId) => void;
}) {
  const t = useUiStrings();
  // Nichts gefeuert → NICHTS rendern (kein Lärm, Konvention wie OpsStatusPill).
  if (items.length === 0) return null;

  return (
    <div className="fired-toast-wrap" role="alert">
      <div className={silenced ? 'fired-toast fired-toast--pulse' : 'fired-toast'}>
        <button
          type="button"
          className="fired-toast__ack"
          onClick={onAck}
          title="Tippen zum Bestätigen"
        >
          <span className="fired-toast__icon" aria-hidden="true">
            <AlarmGlyph />
          </span>
          <span className="fired-toast__body">
            {items.map((item) => (
              <span key={item.id} className="fired-toast__line">
                {firedLine(item, t.firedToast.headline, t.firedToast.missedNoun)}
              </span>
            ))}
          </span>
        </button>
        {onOpenSettings && (
          <button
            type="button"
            className="ctxgear fired-toast__gear"
            onClick={() => onOpenSettings('faehigkeiten', 'wecker-eskalation')}
            aria-label="Wecker-Eskalation-Einstellungen öffnen (Fähigkeiten)"
            title="Eskalation ändern"
          >
            <GearGlyph className="ctxgear__icon" />
          </button>
        )}
      </div>
    </div>
  );
}
