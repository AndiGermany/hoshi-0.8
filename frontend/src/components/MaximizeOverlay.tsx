import { useMemo, useState } from 'react';
import type { CurrentAffairsState } from '../hooks/useCurrentAffairs';
import type { WeatherTodayState } from '../hooks/useWeatherToday';
import { Overlay } from './Overlay';
import { SourceBadge } from './SourceBadge';
import { WeatherHourly } from './WeatherHourly';
import { WeatherGlyph, weatherCategory } from './weatherGlyph';
import { outlookColumns } from './weatherOutlook';
import { fmtPrecip } from './weatherFormat';
import { renderableCurrentAffairs } from './CurrentAffairsTile';
import { formatRelativeAge } from './homeTiles';
import { dueClock } from '../hooks/useScheduledItems';
import type { IdleFaceStrings } from '../i18n/types';

export { MaximizeButton } from './MaximizeButton';

/**
 * **Maximieren** — die Vollbild-Ansicht für Nachrichten und Wetter.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * **Andi 23.08., wörtlich:** „Bei den Nachrichten habe ich ‚+14 weitere, hier
 * nicht gezeigt'. ich habe keine möglichkeit diese anzuzeigen oder die
 * nachrichten zu filtern. füge einen ‚maximieren' hier und beim wetter an, wo
 * man alle auswahlen, alle informationen vernünftig angezeigt bekommt :)"
 *
 * Das war die Antwort auf eine Zeile, die es seit dem 15.08. gibt und die schon
 * damals eine Schuld war: `CurrentAffairsTile` zählt, was über dem Deckel
 * liegt, statt es still fallen zu lassen — richtig — aber der Weg dorthin
 * fehlte. Sein KDoc sagt es selbst: „The full list belongs into a coming
 * overlay, not into this size step." Dies ist dieses Overlay.
 *
 * **KEIN NEUES MODAL-GERÜST.** Rahmen, abgedunkelter Grund, Klick-daneben,
 * `role="dialog"` + `aria-modal`, Escape und der erste Fokus kommen aus der
 * EINEN Modal-Hülle des Hauses ({@link Overlay}, DESIGN-widgets-settings §3.2)
 * — dieselbe, die die Themen-Galerie und die Crew tragen. Was hier neu ist,
 * ist ausschließlich der Inhalt.
 *
 * **KEIN NEUES EINSTELLUNGS-KONZEPT.** Die Quellen-Filter sind die
 * {@link SourceBadge}s, die in der aufgeklappten Karte ohnehin schon stehen,
 * nur als Knöpfe. Sie filtern die ANSICHT und speichern nichts: welche Quellen
 * der Server überhaupt vorhält, bleibt die Sache der Einstellungen
 * (`NewsSourcesSection`). Ein Filter, der sich merkt, wäre eine zweite,
 * versteckte Einstellung an einem Ort, an dem niemand sie suchen würde.
 *
 * **KEIN ZWEITER DATENWEG.** Beide Ansichten sind rein prop-getrieben und lesen
 * denselben Zustand, den die Kacheln lesen — kein eigener Fetch, kein eigener
 * Timer, kein WebSocket. Damit bleibt auch das Visibility-Gating der Hooks
 * unberührt: ein offenes Overlay pollt nicht, es zeigt nur mehr von dem, was
 * ohnehin schon da ist. (Und `renderToStaticMarkup` kann beide prüfen.)
 *
 * **NICHTS ERFUNDEN.** Jede Zeile hängt an einem echten Feld. Fehlt es, fehlt
 * die Zeile — dieselbe Verdien-Regel wie auf den Kacheln (§2.3). Das Wetter
 * zeigt darum genau das, was `WeatherToday` hergibt: Jetzt-Werte, Tagesspanne,
 * Niederschlag, Morgen, Stundenverlauf, Mehrtage, Sonnenzeiten.
 */

/** Kopfzeile beider Vollbild-Ansichten: Titel links, ein klarer Ausgang rechts. */
function MaxHead({ title, closeLabel, onClose }: { title: string; closeLabel: string; onClose: () => void }) {
  return (
    <header className="widgetmax__head">
      <h2 className="widgetmax__title">{title}</h2>
      <button type="button" className="widgetmax__close" onClick={onClose} aria-label={closeLabel}>
        ✕
      </button>
    </header>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   Nachrichten maximiert
   ═══════════════════════════════════════════════════════════════════════════ */

export interface NewsMaxOverlayProps {
  open: boolean;
  onClose: () => void;
  state: CurrentAffairsState | null;
  nowMs: number;
  idleFace: IdleFaceStrings;
  locale: string;
}

/**
 * ALLE Meldungen, vollständig lesbar: Titel, Teaser, Quelle mit Abzeichen,
 * Attribution und Alter — scrollbar, gefiltert über die Quellen-Chips.
 *
 * Der Deckel der Kachel (`CURRENT_AFFAIRS_EXPANDED_COUNT` = 6) gilt hier
 * **nicht**: er ist ein Flächen-Riegel für eine Kachel auf einer Bühne, die
 * nicht scrollen darf. Ein Vollbild-Kasten, der in sich scrollt, hat dieses
 * Problem nicht — und genau deshalb hat Andi ihn bestellt.
 */
export function NewsMaxOverlay({ open, onClose, state, nowMs, idleFace, locale }: NewsMaxOverlayProps) {
  const t = idleFace.currentAffairs;
  const view = renderableCurrentAffairs(state);
  const items = view?.items ?? [];

  /**
   * Die Quellen in der Reihenfolge ihres ersten Auftretens — nicht alphabetisch
   * und nicht aus einer festen Liste: gezeigt wird, was WIRKLICH da ist. Eine
   * Quelle, die heute nichts geliefert hat, bekommt keinen Knopf, der auf eine
   * leere Liste führt.
   */
  const sources = useMemo(() => {
    const seen: string[] = [];
    for (const item of items) if (!seen.includes(item.source)) seen.push(item.source);
    return seen;
  }, [items]);

  const [filter, setFilter] = useState<string | null>(null);
  /**
   * Ein Filter auf eine Quelle, die es nicht mehr gibt (der Poll hat den Feed
   * gewechselt, während das Fenster offen war), zeigt ALLES statt nichts. Der
   * Zustand wird dabei bewusst NICHT zurückgeschrieben — ein `setState` im
   * Render wäre eine Schleife, und die Wahl ist mit dem nächsten Klick ohnehin
   * wieder gültig.
   */
  const active = filter !== null && sources.includes(filter) ? filter : null;
  const shown = active === null ? items : items.filter((i) => i.source === active);

  return (
    <Overlay
      open={open}
      onClose={onClose}
      label={t.name}
      backdropClassName="overlay"
      cardClassName="overlay__card widgetmax"
    >
      <MaxHead title={t.name} closeLabel={idleFace.maximieren.close} onClose={onClose} />

      {/* Die Quellen-Chips. Sie erscheinen erst ab ZWEI Quellen: bei einer
          einzigen wäre „Alle" neben „Tagesschau" eine Wahl ohne Unterschied. */}
      {sources.length > 1 && (
        <div className="widgetmax__chips" role="group" aria-label={t.sourceFilterAria}>
          <button
            type="button"
            className="widgetmax__chip"
            aria-pressed={active === null}
            onClick={() => setFilter(null)}
          >
            {t.allSources}
          </button>
          {sources.map((source) => (
            <button
              key={source}
              type="button"
              className="widgetmax__chip"
              aria-pressed={active === source}
              onClick={() => setFilter(active === source ? null : source)}
            >
              <SourceBadge sourceId={source} /> {source}
            </button>
          ))}
        </div>
      )}

      <p className="widgetmax__count">{t.countInfo(shown.length, items.length)}</p>

      <ul className="widgetmax__news">
        {shown.map((item) => (
          <li key={item.id} className="widgetmax__newsitem">
            <a
              className="widgetmax__newstitle"
              href={item.canonicalUrl}
              target="_blank"
              rel="noopener"
              aria-label={t.openAria(item.title)}
            >
              {item.title}
            </a>
            {/* Quelle + Alter — dieselbe Zeile wie auf der Kachel, damit die
                große Ansicht keine zweite Sprache spricht. */}
            <p className="widgetmax__newsmeta">
              <SourceBadge sourceId={item.source} />{' '}
              {t.meta(item.source, formatRelativeAge(item.publishedAtMs, nowMs, idleFace.homeTiles.age))}
              {' · '}
              {dueClock(item.publishedAtMs, locale)}
            </p>
            {/* VOLLSTÄNDIG, ohne `-webkit-line-clamp`: „vernünftig angezeigt"
                heißt hier ganz gelesen. Die Kachel klemmt den Teaser, weil sie
                eine Rasterzelle ist; dieser Kasten ist keine. */}
            {item.feedSnippet !== null && <p className="widgetmax__newssnippet">{item.feedSnippet}</p>}
            {item.attribution !== null && <p className="widgetmax__newsattr">{item.attribution}</p>}
            <a className="widgetmax__newsopen" href={item.canonicalUrl} target="_blank" rel="noopener">
              {t.openSource}
            </a>
          </li>
        ))}
      </ul>

      {view !== null && view.refreshedAtMs !== null && (
        <p className="widgetmax__stand">{t.stand(dueClock(view.refreshedAtMs, locale))}</p>
      )}
    </Overlay>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   Wetter maximiert
   ═══════════════════════════════════════════════════════════════════════════ */

export interface WeatherMaxOverlayProps {
  open: boolean;
  onClose: () => void;
  weather: WeatherTodayState | null;
  nowMs: number;
  idleFace: IdleFaceStrings;
  locale: string;
}

/**
 * Alles, was der Vertrag hergibt — und keine Zeile mehr. `WeatherToday` führt
 * neun optionale Felder; jedes einzelne kann fehlen (altes Backend, Open-Meteo
 * ohne `daily.sunrise`, ein Feed ohne Stundenraster), und jedes fehlende Feld
 * lässt hier seinen Block einfach weg, statt ihn mit einem Strich zu füllen.
 *
 * Vier Abschnitte, vom Feinen zum Groben — dieselbe Leserichtung wie auf der
 * XL-Kachel: Jetzt · Stundenverlauf · Nächste Tage · Sonne.
 */
export function WeatherMaxOverlay({ open, onClose, weather, nowMs, idleFace, locale }: WeatherMaxOverlayProps) {
  const t = idleFace.wetter;
  const live = weather !== null && weather.kind === 'live' ? weather.data : null;
  const gap =
    weather === null
      ? t.loadingNote
      : weather.kind === 'off'
        ? t.offNote
        : weather.kind === 'unreachable'
          ? t.unreachableNote
          : null;

  const cond = live === null ? '' : (live.nowCodeText ?? live.codeText);
  const outlook = live === null ? [] : outlookColumns(live.outlook ?? [], locale, t.outlook);
  const hourly = live?.hourly ?? [];

  return (
    <Overlay
      open={open}
      onClose={onClose}
      label={t.name}
      backdropClassName="overlay"
      cardClassName="overlay__card widgetmax"
    >
      <MaxHead
        // Der Ort steht im Titel, nicht in einer eigenen Zeile: er ist die
        // Antwort auf „wessen Wetter ist das", und die gehört zur Überschrift.
        title={live === null ? t.name : `${t.name} · ${live.label}`}
        closeLabel={idleFace.maximieren.close}
        onClose={onClose}
      />

      {gap !== null && <p className="widgetmax__gap">{gap}</p>}

      {live !== null && (
        <>
          <section className="widgetmax__sec" aria-label={t.sections.now}>
            <h3 className="widgetmax__sectitle">{t.sections.now}</h3>
            <p className="widgetmax__nowline">
              <WeatherGlyph category={weatherCategory(cond)} className="widgetmax__nowicon" />
              {live.nowTemp !== undefined && <span className="widgetmax__nowtemp">{live.nowTemp}°</span>}
              <span className="widgetmax__nowcond">{cond}</span>
            </p>
            <dl className="widgetmax__facts">
              <div className="widgetmax__fact">
                <dt>{t.sections.span}</dt>
                <dd>{`${live.todayMin}–${live.todayMax}°`}</dd>
              </div>
              <div className="widgetmax__fact">
                <dt>{t.sections.precip}</dt>
                <dd>{live.precipMm > 0 ? t.precipSome(fmtPrecip(live.precipMm, locale)) : t.precipNone}</dd>
              </div>
              {live.tomorrowMin !== undefined &&
                live.tomorrowMax !== undefined &&
                live.tomorrowCodeText !== undefined && (
                  <div className="widgetmax__fact">
                    <dt>{t.sections.tomorrow}</dt>
                    <dd>{`${live.tomorrowMin}–${live.tomorrowMax}° · ${live.tomorrowCodeText}`}</dd>
                  </div>
                )}
            </dl>
          </section>

          {hourly.length > 0 && (
            <section className="widgetmax__sec" aria-label={t.sections.hourly}>
              <h3 className="widgetmax__sectitle">{t.sections.hourly}</h3>
              <WeatherHourly points={hourly} />
            </section>
          )}

          {outlook.length > 0 && (
            <section className="widgetmax__sec" aria-label={t.sections.days}>
              <h3 className="widgetmax__sectitle">{t.sections.days}</h3>
              <div className="idle__outlook widgetmax__outlook" role="list" aria-label={t.outlook.aria(outlook.length)}>
                {outlook.map((day) => (
                  <div
                    key={day.key}
                    className="idle__outlookday"
                    role="listitem"
                    data-today={day.today ? 'true' : undefined}
                    title={day.title}
                  >
                    <span className="idle__outlookwd">{day.weekday}</span>
                    <WeatherGlyph category={weatherCategory(day.codeText)} className="idle__outlookicon" />
                    <span className="idle__outlookspan">{day.span}</span>
                    {/* Hier NICHT gekürzt: der Kasten ist breit genug für
                        „mäßiger Schneefall", die Kachel-Spalte war es nie. */}
                    <span className="idle__outlookcond widgetmax__outlookcond">{day.codeText}</span>
                  </div>
                ))}
              </div>
            </section>
          )}

          {/* BEIDE Sonnenzeiten — die Kachel zeigt je nach Tageszeit nur eine
              („hell bis 20:48"), weil sie eine Zeile hat. Hier ist Platz für
              den ganzen Tag. Ein einzelner Wert erscheint nicht: Auf- und
              Untergang sind ein Paar (dieselbe Regel wie beim Sonnenbogen). */}
          {live.sunriseEpochMs !== undefined && live.sunsetEpochMs !== undefined && (
            <section className="widgetmax__sec" aria-label={t.sections.sun}>
              <h3 className="widgetmax__sectitle">{t.sections.sun}</h3>
              <dl className="widgetmax__facts">
                <div className="widgetmax__fact">
                  <dt>{t.sections.sunrise}</dt>
                  <dd>{dueClock(live.sunriseEpochMs, locale)}</dd>
                </div>
                <div className="widgetmax__fact">
                  <dt>{t.sections.sunset}</dt>
                  <dd>{dueClock(live.sunsetEpochMs, locale)}</dd>
                </div>
                <div className="widgetmax__fact">
                  <dt>{t.sections.daylight}</dt>
                  <dd>{t.sections.daylightValue(daylight(live.sunriseEpochMs, live.sunsetEpochMs))}</dd>
                </div>
              </dl>
              {/* `nowMs` ist der Grund, warum diese Ansicht nicht statisch ist:
                  vor Sonnenaufgang sagt die Kachel „hell ab", danach „hell bis"
                  — dieselbe Aussage steht hier als ruhiger Nachsatz. */}
              <p className="widgetmax__sunnote">
                {nowMs < live.sunriseEpochMs
                  ? t.sunFrom(dueClock(live.sunriseEpochMs, locale))
                  : t.sunUntil(dueClock(live.sunsetEpochMs, locale))}
              </p>
            </section>
          )}
        </>
      )}
    </Overlay>
  );
}

/** Tageslänge als „14 h 26 min" — gerechnet aus zwei echten Zeitpunkten, nicht geschätzt. */
export function daylight(sunriseEpochMs: number, sunsetEpochMs: number): { h: number; min: number } {
  const total = Math.max(0, Math.round((sunsetEpochMs - sunriseEpochMs) / 60000));
  return { h: Math.floor(total / 60), min: total % 60 };
}
