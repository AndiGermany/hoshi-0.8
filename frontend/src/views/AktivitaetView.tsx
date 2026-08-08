import { useEffect, useRef, useState } from 'react';
import { useHealth, type HealthState } from '../hooks/useHealth';
import { useDiary, type DiaryTurn } from '../hooks/useDiary';
import { aggregateToday, stageSegments, stageSparkSeries, STAGES } from '../components/stageStats';
import { FEED_PAGE_SIZE, groupByDay, type DaySegment } from '../components/feedDays';
import { StageSparkline, isP95Elevated } from '../components/StageSparkline';
import { MutedGlyph, WarnGlyph } from '../components/icons';
import { useUiStrings } from '../i18n';
import type { ActivityStrings } from '../i18n/types';
import { DiagnoseSection } from './UebersichtView';

/**
 * Aktivität — der verdichtete Feed des Zuhauses.
 *
 * Ehrlichkeit, strikt:
 *  - 🟢 Der Turn-Feed ist seit dem Backend-Diary (#10) ECHT: `GET /api/v1/diary/recent`
 *       liefert die JSONL-Zeilen des Turn-Diaries (heute + gestern, neueste zuerst).
 *       Die Hülle von früher („kommt, sobald das Backend ein Event-Log liefert")
 *       hat damit ihre Datenquelle — die Kachel ist erweckt.
 *       Privacy by Design: das Diary trägt bewusst KEINE Gesprächs-Inhalte,
 *       nur Zeitpunkt, Kategorie, Persona und Messwerte — der Feed zeigt also
 *       auch keine. 🔇 markiert ehrliche „wusste ich nicht"-Deflects, ⚠ Fehler.
 *  - 🟢 Weiterhin echt: der Health-Verlauf aus `GET /api/health`.
 *
 * Andi-Befund 2026-07-27: „das listet sich alle Turns — das bringt nichts,
 * wenn die Liste einfach nur wächst" (nach Wochen Betrieb eine ungegliederte
 * Endlos-Liste). Der Feed zeigt darum standardmäßig nur die letzten
 * {@link FEED_PAGE_SIZE} Turns, mit Tages-Trennern („Heute"/„Gestern"/Datum,
 * s. `components/feedDays.ts`); ältere Turns kommen erst hinter einem ruhigen
 * „Frühere laden"-Knopf dazu (KEIN Endlos-Scroll — ein Diagnose-Tab ist kein
 * Social-Feed). Die Stage-Latenzen-Zusammenfassung oben (`StageSummary`) UND
 * die „Heute"-Kachel der Diagnose-Sektion bleiben davon UNBERÜHRT: beide
 * bekommen weiterhin das volle geladene `turns`-Array (nicht die sichtbare
 * Feed-Seite) und aggregieren wie bisher NUR den heutigen Kalendertag.
 *
 * Die Ansicht ist rein prop-getrieben (kein Netz-Hook) und dadurch ohne
 * DOM/Fetch testbar; die Cap/Nachlade-Anzeige lebt in lokalem `useState`
 * (kein Netz, nur „wie viele der bereits geladenen Turns zeige ich"). Live-
 * Verdrahtung (Health-Hook + Diary-Load beim Öffnen + Refresh-Knopf + das
 * eigentliche Nachladen älterer Seiten, bewusst kein Dauerpoll):
 * {@link AktivitaetViewLive}.
 *
 * Trägt seit dem Flur-Display-Umbau (Andi-Auftrag 2026-07-26) am ENDE zusätzlich
 * die „Diagnose"-Sektion ({@link DiagnoseSection}) — die von Home umgezogene
 * Entwickler-Landing (Hero + Backend/Chat-Turn/Auth-Token/Heute-Kacheln). Home
 * ist jetzt reines Flur-Display (nur IdleFace + Orb); Diagnostik lebt hier.
 */

export interface HealthObservation {
  state: HealthState;
  at: number;
}

function fmtTime(ts: number, locale: string): string {
  return new Date(ts).toLocaleTimeString(locale);
}

/** ISO-Zeitstempel → lokale Uhrzeit; Unlesbares ehrlich als „—" statt „Invalid Date". */
function fmtTurnTime(iso: string, locale: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleTimeString(locale);
}

/** ms-Wert → „420 ms"; null ehrlich als „—" (nie eine erfundene Zahl). */
function fmtMs(v: number | null): string {
  return v === null ? '—' : `${Math.round(v)} ms`;
}

/** Tages-Trenner-Text eines {@link DaySegment}: „Heute"/„Gestern"/lokalisiertes Datum. */
function dayLabelText(seg: DaySegment, locale: string, activity: ActivityStrings): string {
  switch (seg.kind) {
    case 'today':
      return activity.dayToday;
    case 'yesterday':
      return activity.dayYesterday;
    case 'earlier':
      return seg.date ? seg.date.toLocaleDateString(locale) : activity.dayUnknown;
    case 'unknown':
      return activity.dayUnknown;
  }
}

/**
 * Heutige p50/p95 je Stage — kompakte Kachel-Reihe (bestehende .tile-Sprache).
 * Client-seitig aus `recent` aggregiert (kein neuer Endpoint); Stages ohne
 * heutige Messwerte zeigen ehrlich „—".
 */
function StageSummary({ turns, now }: { turns: DiaryTurn[]; now: Date }) {
  const { activity } = useUiStrings();
  const stats = aggregateToday(turns, now);
  return (
    <div className="tiles tiles--stages">
      {STAGES.map(({ key, label }) => {
        const s = stats[key];
        const live = s.n > 0;
        const p95Warn = isP95Elevated(s.p50, s.p95);
        return (
          <div className={`tile${live ? ' tile--live' : ' tile--pending'}`} key={key}>
            <div className="tile__head">
              <span className="tile__name">{label}</span>
              <span className="tile__pill">{live ? `${s.n}×` : activity.noData}</span>
            </div>
            <div className="tile__value stagesum__value">
              <span>
                p50 <strong>{fmtMs(s.p50)}</strong>
              </span>
              <span>
                p95{' '}
                <strong className={p95Warn ? 'stagesum__p95--warn' : undefined}>{fmtMs(s.p95)}</strong>
              </span>
            </div>
            {/* Tages-Verlauf als Sparkline — nur wenn heute mind. 1 Messwert vorliegt
                (0 Punkte ⇒ Kachel bleibt exakt wie bisher, keine leere Fläche). */}
            {live && (
              <StageSparkline
                label={label}
                points={stageSparkSeries(turns, key, now)}
                p50={s.p50}
                p95={s.p95}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Aufgeklappte Stage-Zerlegung eines Turns: horizontale Segment-Leiste
 * stt→grounding→brain→tts (+ Rest „sonstiges" bis totalMs) mit ms-Labels.
 * Alt-Zeilen (ohne Stage-Keys) sagen ehrlich „keine Stage-Daten (vor 06.07.)".
 */
function TurnStageDetail({ turn }: { turn: DiaryTurn }) {
  const { activity } = useUiStrings();
  if (turn.stages === null) {
    return <p className="stagebar__none">{activity.noStageData}</p>;
  }
  const segments = stageSegments(turn);
  if (segments.length === 0) {
    return <p className="stagebar__none">{activity.noStageValues}</p>;
  }
  return (
    <div className="stagebar">
      <div className="stagebar__track" role="img" aria-label={activity.stageBreakdown}>
        {segments.map((s) => (
          <span
            key={s.key}
            className={`stagebar__seg stagebar__seg--${s.key}`}
            style={{ width: `${s.widthPct}%` }}
            title={`${s.key === 'rest' ? activity.rest : s.label}: ${fmtMs(s.ms)}`}
          />
        ))}
      </div>
      <dl className="stagebar__legend">
        {segments.map((s) => (
          <div className="stagebar__item" key={s.key}>
            <dt>
              <span className={`stagebar__dot stagebar__seg--${s.key}`} aria-hidden="true" />
              {s.key === 'rest' ? activity.rest : s.label}
            </dt>
            <dd>{fmtMs(s.ms)}</dd>
          </div>
        ))}
        {turn.stages.admissionWaitMs !== null && (
          <div className="stagebar__item" key="admission">
            <dt>admission</dt>
            <dd>{fmtMs(turn.stages.admissionWaitMs)}</dd>
          </div>
        )}
        <div className="stagebar__item stagebar__item--total" key="total">
          <dt>{activity.total}</dt>
          <dd>{fmtMs(turn.totalMs)}</dd>
        </div>
      </dl>
    </div>
  );
}

export interface AktivitaetViewProps {
  /** Reale Health-Beobachtungen, neueste zuerst. Leer = noch nichts beobachtet. */
  observations: HealthObservation[];
  /** Turn-Diary-Zeilen, neueste zuerst. `null` = Diary (noch) nicht erreichbar. */
  turns: DiaryTurn[] | null;
  /** Manuelles Nachladen des Turn-Feeds (kein Dauerpoll — bewusst ein Knopf). */
  onRefresh?: () => void;
  /**
   * Nachladen ÄLTERER Turns über den `?before=`-Vertrag (Andi-Auftrag „Frühere
   * laden" 2026-07-27) — hängt weitere Zeilen ans Ende von `turns` an, statt
   * es zu ersetzen. Ohne diese Prop bleibt „Frühere laden" auf das bereits
   * geladene `turns`-Array beschränkt (kein Fehler, nur weniger Reichweite).
   */
  onLoadMore?: () => void;
  /**
   * Gibt es (soweit bekannt) noch ältere Turns jenseits von `turns`? `true`
   * ist der ehrliche Default vor der ersten `before`-Anfrage (unbekannt, NICHT
   * „keine weiteren") — erst eine leere `before`-Antwort macht daraus `false`.
   */
  hasMoreOlder?: boolean;
  /** „Heute"-Referenz der Stage-Zusammenfassung (injizierbar für Tests). */
  now?: Date;
  /** Aktueller Health-Zustand für die Diagnose-Sektion (Hero-Banner am Ende). */
  state: HealthState;
  /** Letzter Health-Check-Zeitpunkt für die Diagnose-Sektion; `null` = noch nie geprüft. */
  lastChecked: number | null;
}

export function AktivitaetView({
  observations,
  turns,
  onRefresh,
  onLoadMore,
  hasMoreOlder = false,
  now,
  state,
  lastChecked,
}: AktivitaetViewProps) {
  const { activity, locale } = useUiStrings();
  const stateLabel: Record<HealthState, string> = {
    up: activity.stateOnline,
    down: activity.stateOffline,
    unknown: activity.stateChecking,
  };
  // Cap+Nachladen (Andi-Befund 2026-07-27): wie viele der bereits geladenen
  // `turns` zeigt der Feed gerade? Start bei FEED_PAGE_SIZE, +FEED_PAGE_SIZE
  // je Klick auf „Frühere laden" — reine Anzeige-Zahl, KEIN Netz hier.
  const [shown, setShown] = useState(FEED_PAGE_SIZE);
  const total = turns?.length ?? 0;
  const bufferedMore = shown < total;
  const canLoadEarlier = total > 0 && (bufferedMore || (hasMoreOlder && !!onLoadMore));

  function handleLoadEarlier() {
    setShown((s) => {
      const next = s + FEED_PAGE_SIZE;
      // Der bereits geladene Puffer reicht nicht für die nächste Seite ⇒ jetzt
      // im Hintergrund nachladen (kein Doppel-Klick-Schutz nötig — `useDiary`
      // deckt das intern ab). Sobald `turns` wächst, zeigt der nächste Render
      // die neuen Zeilen automatisch (kein zweiter Klick nötig).
      if (turns && next >= turns.length) onLoadMore?.();
      return next;
    });
  }
  return (
    <section className="ueber">
      <header className="ueber__head">
        <h1 className="ueber__title">{activity.title}</h1>
        <p className="ueber__lede">{activity.lede}</p>
      </header>

      {/* Echt: heutige Stage-Latenzen (p50/p95), client-seitig aus dem Diary aggregiert. */}
      <h2 className="ueber__sec">{activity.stageLatencyTitle}</h2>
      <p className="ueber__sechint">{activity.stageLatencyHint}</p>
      {turns === null ? (
        <p className="feed__empty stagesum__unreachable">
          {activity.diaryUnavailable}
        </p>
      ) : (
        <StageSummary turns={turns} now={now ?? new Date()} />
      )}

      {/* Echt seit dem Diary (#10): der Turn-Feed aus GET /api/v1/diary/recent. */}
      <h2 className="ueber__sec">
        {activity.turnFeedTitle}
        {onRefresh && (
          <button type="button" className="feed__refresh" onClick={onRefresh}>
            {activity.refresh}
          </button>
        )}
      </h2>
      <p className="ueber__sechint">{activity.turnFeedHint}</p>
      <ol className="feed feed--turns" data-status={turns === null ? 'unreachable' : 'live'}>
        {turns === null ? (
          <li className="feed__empty">
            {activity.diaryUnavailableRetry}
          </li>
        ) : turns.length === 0 ? (
          <li className="feed__empty">
            {activity.diaryEmpty}
          </li>
        ) : (
          // Cap+Tages-Trenner: nur die ersten `shown` Turns, nach Kalendertag
          // gruppiert („Heute"/„Gestern"/Datum) — kein Endlos-Scroll.
          groupByDay(turns.slice(0, shown), now ?? new Date()).flatMap((seg) => [
            <li className="feed__daysep" key={`day-${seg.key}`}>
              {dayLabelText(seg, locale, activity)}
            </li>,
            ...seg.turns.map((t, i) => (
              <li className="feed__item" key={`${t.ts}-${i}`}>
                {/* Aufklappbar per <details> (kein JS-State): zu die Zeile, auf die Stage-Zerlegung. */}
                <details className="feed__details">
                  <summary className="feed__row feed__row--turn">
                    <time className="feed__time">{fmtTurnTime(t.ts, locale)}</time>
                    <span className="feed__chip">{t.category || '—'}</span>
                    <span className="feed__persona">{t.persona || '—'}</span>
                    {t.deflected && (
                      <span
                        className="feed__flag feed__flag--deflected"
                        role="img"
                        aria-label={activity.deflected}
                        title={activity.deflectedTitle}
                      >
                        <MutedGlyph />
                      </span>
                    )}
                    {t.error !== null && (
                      <span
                        className="feed__flag feed__flag--error"
                        role="img"
                        aria-label={activity.error}
                        title={activity.errorStage(t.error)}
                      >
                        <WarnGlyph />
                      </span>
                    )}
                    <span className="feed__ttft">{t.ttftMs !== null ? `${t.ttftMs} ms` : '—'}</span>
                  </summary>
                  <TurnStageDetail turn={t} />
                </details>
              </li>
            )),
          ])
        )}
      </ol>
      {canLoadEarlier && (
        <button type="button" className="feed__loadmore" onClick={handleLoadEarlier}>
          {activity.loadEarlier}
        </button>
      )}
      <p className="feed__privacy">{activity.privacy}</p>

      {/* Echt: der Health-Verlauf aus GET /api/health. */}
      <h2 className="ueber__sec">{activity.healthTitle}</h2>
      <p className="ueber__sechint">{activity.healthHint}</p>
      <ol className="feed" data-status="live">
        {observations.length === 0 ? (
          <li className="feed__empty">
            {activity.noObservation}
          </li>
        ) : (
          observations.map((o, i) => (
            <li className={`feed__row feed__row--${o.state}`} key={`${o.at}-${i}`}>
              <span className="feed__dot" aria-hidden="true" />
              <span className="feed__what">
                {activity.backendState(stateLabel[o.state])}
              </span>
              <time className="feed__when">{fmtTime(o.at, locale)}</time>
            </li>
          ))
        )}
      </ol>

      {/* Diagnose (Flur-Display-Umbau 2026-07-26): die umgezogene Entwickler-
          Landing von Home — Hero + Backend/Chat-Turn/Auth-Token/Heute. */}
      <DiagnoseSection state={state} lastChecked={lastChecked} turns={turns} nowMs={(now ?? new Date()).getTime()} />
    </section>
  );
}

/**
 * Live-Container: verdrahtet den echten Health-Hook (Ringpuffer über beobachtete
 * Zustands­wechsel, neueste zuerst) und das Turn-Diary (einmal beim Öffnen laden +
 * Refresh-Knopf — bewusst kein Dauerpoll). `loadMore`/`hasMoreOlder` reichen das
 * additive `?before=`-Nachladen (Andi-Auftrag „Frühere laden" 2026-07-27) an die
 * pure View durch, OHNE das initiale `turns`-Array (Stage-Summary/Diagnose-
 * „Heute" hängen weiter am vollen Array, nicht an der sichtbaren Feed-Seite).
 */
export function AktivitaetViewLive() {
  const { state, lastChecked } = useHealth();
  const [log, setLog] = useState<HealthObservation[]>([]);
  const lastStateRef = useRef<HealthState | null>(null);
  // 200 statt 50 Zeilen: die heutige p50/p95-Zusammenfassung soll einen vollen
  // Tag ehrlich abdecken (recent liefert heute+gestern; Cap des Endpoints: 500).
  const { turns, refresh, loadMore, hasMoreOlder } = useDiary(200);

  useEffect(() => {
    if (lastChecked === null) return; // noch nie geprüft → kein erfundener Eintrag
    if (lastStateRef.current === state) return; // nur echte Wechsel aufzeichnen
    lastStateRef.current = state;
    setLog((prev) => [{ state, at: lastChecked }, ...prev].slice(0, 12));
  }, [state, lastChecked]);

  return (
    <AktivitaetView
      observations={log}
      turns={turns}
      onRefresh={refresh}
      onLoadMore={loadMore}
      hasMoreOlder={hasMoreOlder}
      state={state}
      lastChecked={lastChecked}
    />
  );
}
