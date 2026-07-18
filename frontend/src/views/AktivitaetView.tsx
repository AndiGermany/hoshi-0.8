import { useEffect, useRef, useState } from 'react';
import { useHealth, type HealthState } from '../hooks/useHealth';
import { useDiary, type DiaryTurn } from '../hooks/useDiary';
import { aggregateToday, stageSegments, stageSparkSeries, STAGES } from '../components/stageStats';
import { StageSparkline, isP95Elevated } from '../components/StageSparkline';
import { MutedGlyph, WarnGlyph } from '../components/icons';

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
 * Die Ansicht ist rein prop-getrieben (kein Hook, kein Netz) und dadurch ohne
 * DOM/Fetch testbar. Live-Verdrahtung (Health-Hook + Diary-Load beim Öffnen +
 * Refresh-Knopf, bewusst kein Dauerpoll): {@link AktivitaetViewLive}.
 */

export interface HealthObservation {
  state: HealthState;
  at: number;
}

const STATE_LABEL: Record<HealthState, string> = {
  up: 'online',
  down: 'offline',
  unknown: 'wird geprüft',
};

function fmtTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('de-DE');
}

/** ISO-Zeitstempel → lokale Uhrzeit; Unlesbares ehrlich als „—" statt „Invalid Date". */
function fmtTurnTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleTimeString('de-DE');
}

/** ms-Wert → „420 ms"; null ehrlich als „—" (nie eine erfundene Zahl). */
function fmtMs(v: number | null): string {
  return v === null ? '—' : `${Math.round(v)} ms`;
}

/**
 * Heutige p50/p95 je Stage — kompakte Kachel-Reihe (bestehende .tile-Sprache).
 * Client-seitig aus `recent` aggregiert (kein neuer Endpoint); Stages ohne
 * heutige Messwerte zeigen ehrlich „—".
 */
function StageSummary({ turns, now }: { turns: DiaryTurn[]; now: Date }) {
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
              <span className="tile__pill">{live ? `${s.n}×` : 'keine Daten'}</span>
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
  if (turn.stages === null) {
    return <p className="stagebar__none">keine Stage-Daten (vor 06.07.)</p>;
  }
  const segments = stageSegments(turn);
  if (segments.length === 0) {
    return <p className="stagebar__none">keine Stage-Werte in diesem Turn gemessen</p>;
  }
  return (
    <div className="stagebar">
      <div className="stagebar__track" role="img" aria-label="Stage-Zerlegung des Turns">
        {segments.map((s) => (
          <span
            key={s.key}
            className={`stagebar__seg stagebar__seg--${s.key}`}
            style={{ width: `${s.widthPct}%` }}
            title={`${s.label}: ${fmtMs(s.ms)}`}
          />
        ))}
      </div>
      <dl className="stagebar__legend">
        {segments.map((s) => (
          <div className="stagebar__item" key={s.key}>
            <dt>
              <span className={`stagebar__dot stagebar__seg--${s.key}`} aria-hidden="true" />
              {s.label}
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
          <dt>gesamt</dt>
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
  /** „Heute"-Referenz der Stage-Zusammenfassung (injizierbar für Tests). */
  now?: Date;
}

export function AktivitaetView({ observations, turns, onRefresh, now }: AktivitaetViewProps) {
  return (
    <section className="ueber">
      <header className="ueber__head">
        <h1 className="ueber__title">Aktivität</h1>
        <p className="ueber__lede">
          Was zuletzt im Zuhause passiert ist — der Turn-Feed aus Hoshis Nutzungs-Diary
          und der Health-Verlauf, beides echt.
        </p>
      </header>

      {/* Echt: heutige Stage-Latenzen (p50/p95), client-seitig aus dem Diary aggregiert. */}
      <h2 className="ueber__sec">Stage-Latenzen heute</h2>
      <p className="ueber__sechint">
        p50/p95 je Pipeline-Stage, aus denselben Diary-Zeilen aggregiert (nur heutige
        Turns; Turns ohne Messwert fallen aus der jeweiligen Stage heraus). „—" heißt
        ehrlich: heute keine Messwerte.
      </p>
      {turns === null ? (
        <p className="feed__empty stagesum__unreachable">
          Diary nicht erreichbar — keine Zusammenfassung ohne Daten.
        </p>
      ) : (
        <StageSummary turns={turns} now={now ?? new Date()} />
      )}

      {/* Echt seit dem Diary (#10): der Turn-Feed aus GET /api/v1/diary/recent. */}
      <h2 className="ueber__sec">
        Turn-Feed
        {onRefresh && (
          <button type="button" className="feed__refresh" onClick={onRefresh}>
            Aktualisieren
          </button>
        )}
      </h2>
      <p className="ueber__sechint">
        Jede Zeile ist ein realer Turn aus deinem Tagesbuch (heute + gestern, neueste
        zuerst). Geladen beim Öffnen und per „Aktualisieren" — kein Dauerpoll.
      </p>
      <ol className="feed feed--turns" data-status={turns === null ? 'unreachable' : 'live'}>
        {turns === null ? (
          <li className="feed__empty">
            Diary nicht erreichbar — hier steht nichts Erfundenes. „Aktualisieren" versucht
            es erneut.
          </li>
        ) : turns.length === 0 ? (
          <li className="feed__empty">
            Noch kein Turn im Diary (heute + gestern) — leer ist ehrlich leer.
          </li>
        ) : (
          turns.map((t, i) => (
            <li className="feed__item" key={`${t.ts}-${i}`}>
              {/* Aufklappbar per <details> (kein JS-State): zu die Zeile, auf die Stage-Zerlegung. */}
              <details className="feed__details">
                <summary className="feed__row feed__row--turn">
                  <time className="feed__time">{fmtTurnTime(t.ts)}</time>
                  <span className="feed__chip">{t.category || '—'}</span>
                  <span className="feed__persona">{t.persona || '—'}</span>
                  {t.deflected && (
                    <span
                      className="feed__flag feed__flag--deflected"
                      role="img"
                      aria-label="deflected"
                      title="Ehrliches „wusste ich nicht“ statt einer erfundenen Antwort"
                    >
                      <MutedGlyph />
                    </span>
                  )}
                  {t.error !== null && (
                    <span
                      className="feed__flag feed__flag--error"
                      role="img"
                      aria-label="Fehler"
                      title={`Fehler-Stage: ${t.error}`}
                    >
                      <WarnGlyph />
                    </span>
                  )}
                  <span className="feed__ttft">{t.ttftMs !== null ? `${t.ttftMs} ms` : '—'}</span>
                </summary>
                <TurnStageDetail turn={t} />
              </details>
            </li>
          ))
        )}
      </ol>
      <p className="feed__privacy">
        Privacy by Design: das Diary trägt bewusst keine Gesprächs-Inhalte — nur Zeitpunkt,
        Kategorie, Persona und Messwerte. Darum steht hier auch kein Text der Turns.
        Jede Zeile lässt sich aufklappen und zeigt die Stage-Zerlegung
        (stt → grounding → brain → tts, Rest = sonstiges).
      </p>

      {/* Echt: der Health-Verlauf aus GET /api/health. */}
      <h2 className="ueber__sec">Health-Verlauf</h2>
      <p className="ueber__sechint">
        Jede Zeile ist eine reale Beobachtung des Verbindungsstatus. Aufgezeichnet werden
        Zustands­wechsel, neueste zuerst.
      </p>
      <ol className="feed" data-status="live">
        {observations.length === 0 ? (
          <li className="feed__empty">
            Noch keine Beobachtung — die erste Health-Antwort steht aus. (Kein erfundener
            Verlauf.)
          </li>
        ) : (
          observations.map((o, i) => (
            <li className={`feed__row feed__row--${o.state}`} key={`${o.at}-${i}`}>
              <span className="feed__dot" aria-hidden="true" />
              <span className="feed__what">
                Backend {STATE_LABEL[o.state]}
              </span>
              <time className="feed__when">{fmtTime(o.at)}</time>
            </li>
          ))
        )}
      </ol>
    </section>
  );
}

/**
 * Live-Container: verdrahtet den echten Health-Hook (Ringpuffer über beobachtete
 * Zustands­wechsel, neueste zuerst) und das Turn-Diary (einmal beim Öffnen laden +
 * Refresh-Knopf — bewusst kein Dauerpoll).
 */
export function AktivitaetViewLive() {
  const { state, lastChecked } = useHealth();
  const [log, setLog] = useState<HealthObservation[]>([]);
  const lastStateRef = useRef<HealthState | null>(null);
  // 200 statt 50 Zeilen: die heutige p50/p95-Zusammenfassung soll einen vollen
  // Tag ehrlich abdecken (recent liefert heute+gestern; Cap des Endpoints: 500).
  const { turns, refresh } = useDiary(200);

  useEffect(() => {
    if (lastChecked === null) return; // noch nie geprüft → kein erfundener Eintrag
    if (lastStateRef.current === state) return; // nur echte Wechsel aufzeichnen
    lastStateRef.current = state;
    setLog((prev) => [{ state, at: lastChecked }, ...prev].slice(0, 12));
  }, [state, lastChecked]);

  return <AktivitaetView observations={log} turns={turns} onRefresh={refresh} />;
}
