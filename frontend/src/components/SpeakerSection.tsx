import { useEffect, useRef, useState } from 'react';
import { SPEAKER_ID } from '../api/config';
import {
  SPEAKER_NAME_PATTERN,
  SpeakerSampleDeleteError,
  type SpeakerDiagnostics,
  type SpeakerProfileDiagnostics,
  type SpeakerSummary,
  deleteSpeaker,
  deleteSpeakerSample,
  enrollSpeaker,
  fetchSpeakerDiagnostics,
  fetchSpeakers,
} from '../api/speakers';
import { type EnrollCapture, createBrowserEnrollCapture } from '../audio/enrollCapture';
import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import { getActiveUiLanguage } from '../i18n/activeLanguageStore';
import { resolveUiStrings } from '../i18n/catalogs';
import type { SpeakerStrings } from '../i18n/types';
import { LockGlyph, MicGlyph } from './icons';

// ─────────────────────────────────────────────────────────────────────────────
//  Erkannte Sprecher (S2a) — ANLERNEN + Verwalten der PERSONEN, die Hoshi
//  wiedererkennt. Bewusst getrennt von „Stimme & Klang" (das ist HOSHIS Ausgabe-
//  Stimme): hier geht es um ERKANNTE PERSONEN. Consent by Design: jede/r lernt
//  die EIGENE Stimme an — dein Profil gehört dir. Kein Anlernen fremder Stimmen.
//
//  Diese Scheibe: Anlernen + Liste + Löschen. Die Erkennungs-FARBE (rosa bei Frau)
//  ist die NÄCHSTE Scheibe — die Chips hier nutzen bewusst nur das Aoi-Token-Set.
// ─────────────────────────────────────────────────────────────────────────────

/** Wie viele Sätze EINE Sitzung braucht (Ein-Satz-Embeddings streuen zu stark). */
export const ENROLL_SAMPLE_COUNT = 3;

/**
 * Wie viele UNABHÄNGIGE Sitzungen ein komplettes Profil braucht (Andi-Auftrag 25.07: die
 * Sprecher-Erkennung scheiterte, weil alle drei Aufnahmen bisher in EINER Sitzung, EINEM
 * Raum, mit DENSELBEN Sätzen entstanden — die Embeddings trugen Kanal/Sitzung statt
 * Stimme. Jede Sitzung sollte an einem anderen Tag, in einem anderen Raum, mit einem
 * anderen Mikro stattfinden).
 */
export const ENROLL_SESSION_COUNT = 3;

/** Backend-Maximum: 3 Sitzungen × 3 Sätze = 9 (der größte `sample`-Wert, den die API annimmt). */
export const ENROLL_TOTAL_SAMPLES = ENROLL_SAMPLE_COUNT * ENROLL_SESSION_COUNT;

/**
 * Startindex (1-basiert, = Backend-`sample`) einer NEUEN Anlern-Sitzung. `existingSamples`
 * ist der Zählerstand aus der Server-Liste (`GET /api/v1/speakers` → `samples`).
 *
 * 1..({@link ENROLL_TOTAL_SAMPLES} − 1) ⇒ die Sitzung HÄNGT AN (nächster offener Index).
 * 0 oder ≥ {@link ENROLL_TOTAL_SAMPLES} ⇒ frischer Start bei 1 (Profil neu beginnen —
 * `sample=1` ersetzt).
 */
export function enrollStartIndex(existingSamples: number): number {
  if (existingSamples >= 1 && existingSamples < ENROLL_TOTAL_SAMPLES) {
    return existingSamples + 1;
  }
  return 1;
}

/** Zu welcher Sitzung (1..{@link ENROLL_SESSION_COUNT}) ein absoluter Sample-Index (1..9) gehört. */
export function sessionOfIndex(absoluteIndex: number): number {
  return Math.min(ENROLL_SESSION_COUNT, Math.max(1, Math.ceil(absoluteIndex / ENROLL_SAMPLE_COUNT)));
}

/**
 * Trimmed + case-insensitiver Namensvergleich (Korrektur 25.07: das Backend-Namensmuster
 * erlaubt Groß-/Kleinschreibung — „andi" und „Andi" sind dieselbe Person, „Person B" und
 * „person-b" ebenso. Ein exakter String-Vergleich hätte am selben Browser angelernte
 * Mehrpersonen-Haushalte (Andi + Person B) auseinandergerissen).
 */
export function sameSpeakerName(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase();
}

/**
 * Sätze-Zählerstand des Profils, dessen Name zu `name` passt (trimmed + case-insensitiv,
 * s. {@link sameSpeakerName}) — kein Treffer (oder keine Liste) ⇒ 0 (frischer Start).
 * Reine Lookup-Funktion, damit {@link EnrollDialog} sie gegen den GETIPPTEN Namen aufrufen
 * kann, nicht gegen einen fest verdrahteten Default-Namen (der echte Fall: zwei Menschen,
 * Andi und Person B, lernen am selben Browser an, unterschieden nur durchs Namensfeld).
 */
export function samplesForNameIn(speakers: SpeakerSummary[] | null | undefined, name: string): number {
  return speakers?.find((s) => sameSpeakerName(s.name, name))?.samples ?? 0;
}

/**
 * „Satz i von 3" — der eine Fortschritts-Text, überall identisch (UI + Tests). Zählt
 * INNERHALB einer Sitzung. Fünf-Sprachen-Sweep 2026-07-27: liest die Vorlage jetzt aus
 * dem AKTIVEN UI-Katalog (Modul-Singleton, exakt das Muster von `getActiveUiLanguage`
 * in api/chat.ts/voice.ts) statt einer hart deutschen Vorlage — DE bleibt byte-gleich
 * zum bisherigen Stand (kein `setActiveUiLanguage`-Aufruf ⇒ Default 'de').
 */
export function sampleProgress(i: number): string {
  return resolveUiStrings(getActiveUiLanguage()).speaker.progress(i, ENROLL_SAMPLE_COUNT);
}

/**
 * Alle sichtbaren Texte an einem Ort (auch von den Tests referenziert) — jetzt
 * eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().speaker`, s. unten.
 */
export const SPEAKER_TEXTS = de.speaker;

/** Wie lange der scharfe Zweitklick-Zustand hält, bevor er sich selbst entschärft (wie Privacy). */
const ARM_TIMEOUT_MS = 5000;

/**
 * Anlern-Datum menschlich (nie eine erfundene Zahl — 0/fehlend ⇒ „gerade eben"). Fünf-
 * Sprachen-Sweep 2026-07-27: Fallback-Text UND Datums-Locale folgen jetzt dem AKTIVEN
 * UI-Katalog (s. {@link sampleProgress}) statt hart 'gerade eben'/'de-DE' — DE bleibt
 * byte-gleich zum bisherigen Stand.
 */
export function formatEnrolledDate(ms: number): string {
  const t = resolveUiStrings(getActiveUiLanguage());
  if (!ms || ms <= 0) return t.speaker.justNow;
  try {
    return new Date(ms).toLocaleDateString(t.locale, {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  } catch {
    return '';
  }
}

/**
 * Zeitpunkt EINER Aufnahme, Datum + Uhrzeit (nie eine erfundene Zahl — fehlend/0 ⇒
 * {@link SpeakerStrings.recordingUnknown}, echte Alt-Aufnahmen ohne Herkunft eingeschlossen).
 * Reparatur-Auftrag 07.08 (Diagnose-Liste je Profil).
 */
export function formatSampleTimestamp(ms: number | null | undefined): string {
  const t = resolveUiStrings(getActiveUiLanguage());
  if (!ms || ms <= 0) return t.speaker.recordingUnknown;
  try {
    return new Date(ms).toLocaleString(t.locale, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return t.speaker.recordingUnknown;
  }
}

/** Netto-Dauer EINER Aufnahme — `null` (WAV nicht sicher geparst/Alt-Aufnahme) ⇒ ehrlich „unbekannt". */
export function formatSampleDuration(seconds: number | null | undefined): string {
  const t = resolveUiStrings(getActiveUiLanguage());
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds < 0) {
    return t.speaker.recordingUnknown;
  }
  return t.speaker.recordingDuration(seconds);
}

/** Schwellen aus dem Reparatur-Auftrag 07.08 (Diagnose-Endpoint `leaveOneOutSimilarity`). */
const FIT_GOOD_THRESHOLD = 0.6;
const FIT_MEDIUM_THRESHOLD = 0.35;

/**
 * „Passt zu mir"-Text EINER Aufnahme aus dem Leave-one-out-Wert — ruhiger Text statt Rohzahl
 * (die Rohzahl kommt im `title`, s. `SpeakerListView`). `undefined` (Profil hatte beim Messen
 * <2 Samples — nichts zu leaven) ⇒ {@link SpeakerStrings.fitUnknown}, kein geratener Wert.
 */
export function fitLabel(t: SpeakerStrings, loo: number | undefined): { text: string; title?: string } {
  if (typeof loo !== 'number' || !Number.isFinite(loo)) return { text: t.fitUnknown };
  const title = loo.toFixed(3);
  if (loo > FIT_GOOD_THRESHOLD) return { text: t.fitGood, title };
  if (loo >= FIT_MEDIUM_THRESHOLD) return { text: t.fitMedium, title };
  return { text: t.fitPoor, title };
}

/**
 * Kapazitäts-Probe: sichere Verbindung + Mikro + MediaRecorder da? Ehrlicher Grund, wenn
 * nicht. Fünf-Sprachen-Sweep 2026-07-27: liest die Gründe jetzt aus dem AKTIVEN UI-Katalog
 * (s. {@link sampleProgress}) statt dem festen `SPEAKER_TEXTS`-Modulwert — DE bleibt
 * byte-gleich zum bisherigen Stand.
 */
export function micSupport(): { ok: boolean; reason?: string } {
  const t = resolveUiStrings(getActiveUiLanguage()).speaker;
  if (typeof globalThis.isSecureContext === 'boolean' && !globalThis.isSecureContext) {
    return { ok: false, reason: t.insecure };
  }
  const md = globalThis.navigator?.mediaDevices;
  if (!md || typeof md.getUserMedia !== 'function') {
    return { ok: false, reason: t.noMic };
  }
  if (typeof globalThis.MediaRecorder === 'undefined') {
    return { ok: false, reason: t.noMic };
  }
  return { ok: true };
}

// ─────────────────────────────────────────────────────────────────────────────
//  Präsentation: Liste + Consent + Anlern-Knopf (prop-getrieben → node-testbar
//  via renderToStaticMarkup, gespiegelt von PrivacySectionView/SkillsSection).
// ─────────────────────────────────────────────────────────────────────────────

export interface SpeakerListViewProps {
  speakers: SpeakerSummary[] | null;
  loading?: boolean;
  error?: string | null;
  armed?: string | null;
  busy?: string | null;
  note?: string | null;
  onDelete: (name: string) => void;
  onEnroll: () => void;
  /** Öffnet den Anlern-Dialog vorausgefüllt zum Fortsetzen EINES bestehenden Profils. */
  onContinue: (name: string) => void;
  /**
   * Aufnahmen-Diagnose je Profil (Reparatur-Auftrag 07.08) — `null` solange (noch) nicht
   * geladen; die aufklappbare Liste zeigt in dem Fall die Lade-/Fehlzeile statt Aufnahmen.
   */
  diagnostics?: SpeakerDiagnostics | null;
  diagnosticsError?: string | null;
  /** Einzel-Löschen EINER Aufnahme (nutzt `DELETE .../samples/{index}`). */
  onDeleteSample: (name: string, index: number) => void;
  /** Welche Aufnahme (Profilname + Index) gerade gelöscht wird — sperrt NUR diesen Knopf. */
  sampleBusy?: { name: string; index: number } | null;
}

/**
 * Aufklappbare Aufnahmen-Liste EINES Profils (Muster `<details>`/`<summary>` wie
 * `feed__details`/ChatView-Quellen — kein eigener JS-Auf/Zu-State nötig). Datenquelle: die
 * bereits geladene {@link SpeakerDiagnostics} (ein GET für ALLE Profile, s. `SpeakerSection`).
 * Je Aufnahme: Zeitpunkt · Dauer · „passt zu mir"-Text (Rohwert im `title`) · Einzel-Löschen
 * (gesperrt bei der letzten Aufnahme — Reparatur-Auftrag 07.08).
 */
function SpeakerRecordings({
  name,
  diag,
  diagnosticsError,
  sampleBusy,
  onDeleteSample,
  t,
}: {
  name: string;
  diag: SpeakerProfileDiagnostics | undefined;
  diagnosticsError?: string | null;
  sampleBusy?: { name: string; index: number } | null;
  onDeleteSample: (name: string, index: number) => void;
  t: SpeakerStrings;
}) {
  if (!diag) {
    return <p className="settings__hint">{diagnosticsError ?? t.loading}</p>;
  }
  const isLast = diag.sampleOrigins.length <= 1;
  return (
    <details className="settings__recordings">
      <summary className="settings__recordingssummary">{t.recordingsToggle(diag.sampleOrigins.length)}</summary>
      <ul className="settings__recordinglist">
        {diag.sampleOrigins.map((origin, i) => {
          const fit = fitLabel(t, diag.leaveOneOutSimilarity[i]);
          const busyThis = sampleBusy?.name === name && sampleBusy.index === i;
          return (
            <li className="settings__recordingrow" key={i}>
              <span className="settings__recordingmeta">
                {formatSampleTimestamp(origin.recordedAt)} · {formatSampleDuration(origin.durationSeconds)} ·{' '}
                <span title={fit.title}>{fit.text}</span>
              </span>
              <button
                type="button"
                className="settings__deletebtn"
                disabled={isLast || busyThis}
                title={isLast ? t.deleteRecordingLastHint : undefined}
                aria-label={t.deleteRecordingAria(i + 1)}
                onClick={() => onDeleteSample(name, i)}
              >
                {busyThis ? t.deleting : t.deleteRecording}
              </button>
            </li>
          );
        })}
      </ul>
    </details>
  );
}

export function SpeakerListView({
  speakers,
  loading,
  error,
  armed,
  busy,
  note,
  onDelete,
  onEnroll,
  onContinue,
  diagnostics,
  diagnosticsError,
  onDeleteSample,
  sampleBusy,
}: SpeakerListViewProps) {
  const t = useUiStrings();
  const SPEAKER_TEXTS = t.speaker;
  const isEmpty = speakers !== null && speakers.length === 0;
  return (
    <section className="settings__group">
      <h3 className="settings__label">{SPEAKER_TEXTS.groupTitle}</h3>
      <p className="settings__hint">{SPEAKER_TEXTS.intro}</p>
      {/* Consent by Design — steht bewusst sichtbar über der Liste. */}
      <p className="settings__hint settings__consent">
        <LockGlyph /> {SPEAKER_TEXTS.consent}
      </p>

      {loading && !speakers && <p className="settings__hint">{SPEAKER_TEXTS.loading}</p>}
      {error && (
        <p className="settings__hint" role="alert">
          {error}
        </p>
      )}

      {speakers && speakers.length > 0 && (
        <div className="settings__speakers">
          {speakers.map((s) => {
            const isArmed = armed === s.name;
            const isBusy = busy === s.name;
            return (
              <div className="settings__speakerrow" key={s.name}>
                <span className="settings__speakerchip" aria-hidden="true">
                  {s.name.charAt(0).toUpperCase()}
                </span>
                <div className="settings__speakermeta">
                  <span className="settings__speakername">{s.name}</span>
                  <span className="settings__speakerdate">
                    {SPEAKER_TEXTS.enrolledOn(formatEnrolledDate(s.enrolledAt))}
                    {typeof s.samples === 'number' && ` · ${SPEAKER_TEXTS.sentenceCount(s.samples)}`}
                    {/* Sitzungs-Fortschritt (Andi-Auftrag 25.07): <9 Sätze ⇒ "in Arbeit", 9 ⇒ "vollständig". */}
                    {typeof s.samples === 'number' &&
                      ` · ${
                        s.samples >= ENROLL_TOTAL_SAMPLES
                          ? SPEAKER_TEXTS.statusComplete
                          : SPEAKER_TEXTS.statusInProgress
                      }`}
                  </span>
                  {/* Aufklappbare Aufnahmen-Liste (Reparatur-Auftrag 07.08): Datum · Dauer ·
                      „passt zu mir" · Einzel-Löschen — Datenquelle GET .../diagnostics. */}
                  <SpeakerRecordings
                    name={s.name}
                    diag={diagnostics?.profiles.find((p) => sameSpeakerName(p.name, s.name))}
                    diagnosticsError={diagnosticsError}
                    sampleBusy={sampleBusy}
                    onDeleteSample={onDeleteSample}
                    t={SPEAKER_TEXTS}
                  />
                </div>
                <div className="settings__speakeractions">
                  {/* „Weiter anlernen" (Andi-Auftrag 07.08): öffnet den Anlern-Dialog
                      vorausgefüllt+gesperrt auf DIESEN Namen — die Startindex-folgt-Namen-Logik
                      (s. `enrollStartIndex`/`samplesForNameIn` oben) setzt automatisch am
                      richtigen Satz fort, ganz ohne eigene Fortsetz-Logik hier. */}
                  {/* VOLLES Profil (9/9): „Weiter anlernen" würde durch den
                      enrollStartIndex-Rücksprung auf 1 das Profil STILL ERSETZEN —
                      exakt die Fußangel-Klasse der beiden 08.08-Vorfälle
                      (versehentliche Löschung, Gleichzeitig-Anlernen). Darum hier
                      disabled mit ehrlichem Hinweis statt eines stillen Neustarts;
                      Platz schaffen geht über die Einzel-Aufnahmen-Löschung. */}
                  <button
                    type="button"
                    className="settings__enrollbtn settings__continuebtn"
                    aria-label={SPEAKER_TEXTS.continueAria(s.name)}
                    disabled={(s.samples ?? 0) >= ENROLL_TOTAL_SAMPLES}
                    title={(s.samples ?? 0) >= ENROLL_TOTAL_SAMPLES ? SPEAKER_TEXTS.continueFullHint : undefined}
                    onClick={() => onContinue(s.name)}
                  >
                    {SPEAKER_TEXTS.continueButton}
                  </button>
                  <button
                    type="button"
                    className={`settings__deletebtn ${isArmed ? 'is-armed' : ''}`}
                    disabled={isBusy}
                    aria-label={SPEAKER_TEXTS.deleteProfileAria(s.name)}
                    onClick={() => onDelete(s.name)}
                  >
                    {isBusy
                      ? SPEAKER_TEXTS.deleting
                      : isArmed
                        ? SPEAKER_TEXTS.confirm
                        : SPEAKER_TEXTS.delete}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {isEmpty && <p className="settings__hint">{SPEAKER_TEXTS.empty}</p>}

      {note && (
        <p className="settings__hint settings__privacynote" role="status">
          {note}
        </p>
      )}

      <button type="button" className="settings__enrollbtn" onClick={onEnroll}>
        <MicGlyph /> {SPEAKER_TEXTS.enrollButton}
      </button>
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Anlern-Dialog: geführter Aufnahme-Flow über DREI unabhängige Sitzungen à drei
//  Sätze (neun Aufnahmen insgesamt — das Backend-Maximum). Satz 1 (absolut) ersetzt
//  das Profil (frischer Start einer Sitzung 1), jeder weitere Satz hängt additiv an
//  — das Backend mittelt (L2-renormalisiert). Eine Sitzung endet IMMER nach drei
//  gespeicherten Sätzen; erst nach Sitzung 3 (Satz 9) ist das Profil komplett. Jeder
//  Zwischenstand sagt das ehrlich, und ein Abbruch reißt NIE bereits abgeschlossene
//  Sitzungen mit (s. {@link EnrollDialog.cancel} unten). Aufnahme + Enroll + Rollback
//  sind injizierbare Props (Default: echter Browser) → im jsdom-Test ohne Mikro fahrbar.
// ─────────────────────────────────────────────────────────────────────────────

type EnrollStep = 'intro' | 'recording' | 'saving' | 'sample-done' | 'session-done' | 'done' | 'error';

export interface EnrollDialogProps {
  onClose: () => void;
  onEnrolled: (summary: SpeakerSummary) => void;
  /** Abbruch NACH mindestens einem gespeicherten Satz EINER FRISCHEN Sitzung (Profil wurde verworfen). */
  onAborted?: () => void;
  /**
   * Abbruch mitten in einer ANGEHÄNGTEN Sitzung (Sitzung 2/3): NICHTS wird gelöscht —
   * die bereits gespeicherten Sätze früherer und dieser Sitzung bleiben erhalten, nur
   * diese eine Sitzung war unvollständig. Getrennt von {@link onAborted}, damit die
   * Notiz ehrlich zwischen „gelöscht" und „unvollständig, aber erhalten" unterscheidet.
   */
  onSessionIncomplete?: () => void;
  defaultName?: string;
  /**
   * Namensfeld von ANFANG AN gesperrt (Andi-Auftrag 07.08, „Weiter anlernen"-Knopf einer
   * Profil-Zeile) — anders als das normale Einfrieren erst NACH dem ersten gespeicherten
   * Satz (s. `nameLocked` unten): hier ist [defaultName] bereits ein BESTEHENDES Profil,
   * ein Tippen im Feld dürfte NIE versehentlich auf ein anderes/neues Profil umschalten.
   */
  lockName?: boolean;
  /**
   * Sätze-Zählerstand-Lookup für einen Namen (Default: 0 — immer frischer Start). Reales
   * Bild (Korrektur 25.07): am selben Browser lernen ZWEI Menschen an (z. B. Andi und
   * Person B), unterschieden NUR durchs Namensfeld im Dialog — ein fest verdrahteter
   * Default-Name hätte Person Bs Profil bei jeder Sitzung überschrieben (`sample=1`). Der
   * Startindex folgt darum reaktiv dem GETIPPTEN Namen (trimmed + case-insensitiv, s.
   * {@link sameSpeakerName}) — SOLANGE in dieser Sitzung noch nichts gespeichert wurde.
   * Ab der ersten gespeicherten Aufnahme ist er eingefroren (s. {@link EnrollDialog}
   * unten), das Namensfeld wird gesperrt. Production-Default: {@link samplesForNameIn}
   * gegen die geladene Sprecher-Liste (s. `SpeakerSection`).
   */
  samplesForName?: (name: string) => number;
  /** Aufnahme-Fabrik (Default: echte Browser-Aufnahme→WAV). Test speist eine Fake ein. */
  createCapture?: () => EnrollCapture;
  /** Enroll-Aufruf (Default: {@link enrollSpeaker}). Test spioniert hier. */
  enroll?: (name: string, wav: Blob, sample?: number, signal?: AbortSignal) => Promise<SpeakerSummary>;
  /** Rollback bei Abbruch mit Teil-Profil (Default: {@link deleteSpeaker}). Test spioniert hier. */
  removeProfile?: (name: string, signal?: AbortSignal) => Promise<void>;
  /** Mikro-Kapazitätsprobe (Default: {@link micSupport}). Test erzwingt „kein Mikro". */
  support?: () => { ok: boolean; reason?: string };
}

export function EnrollDialog({
  onClose,
  onEnrolled,
  onAborted,
  onSessionIncomplete,
  defaultName = SPEAKER_ID,
  lockName = false,
  samplesForName = () => 0,
  createCapture = createBrowserEnrollCapture,
  enroll = enrollSpeaker,
  removeProfile = deleteSpeaker,
  support = micSupport,
}: EnrollDialogProps) {
  const t = useUiStrings();
  const SPEAKER_TEXTS = t.speaker;
  const [step, setStep] = useState<EnrollStep>('intro');
  const [name, setName] = useState(defaultName);
  const [nameTouched, setNameTouched] = useState(false);
  const [errorText, setErrorText] = useState<string | null>(null);
  /** Wie viele Sätze DIESE Sitzung (dieses Dialog-Öffnen) schon gespeichert hat (0..3, lokal). */
  const [savedCount, setSavedCount] = useState(0);
  /**
   * Absoluter Startindex DIESER Sitzung — `null`, solange noch NICHTS gespeichert wurde
   * (der Startindex folgt dann live dem Namensfeld, s. `liveStartIndex` unten). Wird bei
   * der ERSTEN erfolgreich gespeicherten Aufnahme eingefroren (s. {@link finishRecording})
   * und ändert sich danach NIE mehr — sonst könnte ein Tippen im Namensfeld mitten in der
   * Sitzung die Abbruch-/Anhäng-Semantik umschalten (s. {@link cancel}/{@link backToIntro}).
   */
  const [frozenStartIndex, setFrozenStartIndex] = useState<number | null>(null);
  /** Welcher Satz als Nächstes aufgenommen wird, NACHDEM eingefroren wurde (absolut, 1-basiert). */
  const [frozenSampleIndex, setFrozenSampleIndex] = useState(1);
  const captureRef = useRef<EnrollCapture | null>(null);
  const aliveRef = useRef(true);

  const cap = support();
  const trimmedName = name.trim();
  const nameValid = SPEAKER_NAME_PATTERN.test(trimmedName);
  /** Solange nichts eingefroren ist: reaktiver Startindex nach dem GETIPPTEN Namen. */
  const liveStartIndex = enrollStartIndex(samplesForName(trimmedName));
  /** Absoluter Startindex DIESER Sitzung — eingefroren nach dem ersten Satz, sonst live. */
  const sessionStartIndex = frozenStartIndex ?? liveStartIndex;
  /** Welcher Satz als Nächstes aufgenommen wird (1-basiert, ABSOLUT — geht so ans Backend). */
  const sampleIndex = frozenStartIndex !== null ? frozenSampleIndex : liveStartIndex;
  /** Welche Sitzung (1..3) der aktuelle absolute Index gerade bedient — für „Sitzung i von 3". */
  const sessionNumber = sessionOfIndex(sampleIndex);
  /** Nur eine bei Satz 1 begonnene (frische) Sitzung darf im Abbruchfall löschen. */
  const isFreshStart = sessionStartIndex === 1;
  /**
   * Namensfeld wird gesperrt, sobald diese Sitzung mindestens einen Satz gespeichert hat —
   * ODER von Anfang an, wenn [lockName] gesetzt ist („Weiter anlernen" an einem bestehenden
   * Profil, s. {@link EnrollDialogProps.lockName}).
   */
  const nameLocked = savedCount > 0 || lockName;

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      // Läuft noch eine Aufnahme, wenn der Dialog verschwindet → Mikro freigeben.
      try {
        captureRef.current?.cancel();
      } catch {
        /* ignore */
      }
    };
  }, []);

  const fail = (message: string) => {
    if (!aliveRef.current) return;
    setErrorText(message);
    setStep('error');
  };

  const startRecording = async () => {
    setNameTouched(true);
    if (!cap.ok) {
      fail(cap.reason ?? SPEAKER_TEXTS.noMic);
      return;
    }
    if (!nameValid) return; // Intro-Zustand bleibt; die Inline-Notiz erklärt es.
    setErrorText(null);
    const capture = createCapture();
    captureRef.current = capture;
    try {
      await capture.start();
      if (!aliveRef.current) return;
      setStep('recording');
    } catch (err) {
      captureRef.current = null;
      // VoiceRecorderError trägt bereits eine warme Zeile (permission/no-device/…).
      fail(err instanceof Error ? err.message : SPEAKER_TEXTS.noMic);
    }
  };

  const finishRecording = async () => {
    const capture = captureRef.current;
    if (!capture) return;
    setStep('saving');
    try {
      const wav = await capture.stop();
      captureRef.current = null;
      // sample=1 ersetzt (frischer Start), 2..9 hängen an — BE-Contract.
      const summary = await enroll(name.trim(), wav, sampleIndex);
      if (!aliveRef.current) return;
      if (frozenStartIndex === null) {
        // ERSTE erfolgreich gespeicherte Aufnahme dieser Sitzung: ab jetzt eingefroren — ein
        // (jetzt gesperrtes) Namensfeld darf die Abbruch-/Anhäng-Semantik nicht mehr umschalten.
        setFrozenStartIndex(sampleIndex);
      }
      const nextSaved = savedCount + 1;
      setSavedCount(nextSaved);
      const profileComplete = sampleIndex >= ENROLL_TOTAL_SAMPLES;
      const sessionComplete = nextSaved >= ENROLL_SAMPLE_COUNT;
      if (profileComplete) {
        // Satz 9 (Sitzung 3, Satz 3): das GANZE Profil ist jetzt komplett.
        setStep('done');
        onEnrolled(summary);
      } else if (sessionComplete) {
        // Drei Sätze DIESER Sitzung sind gespeichert, aber es fehlen noch weitere
        // Sitzungen — die Sitzung endet hier bewusst (nicht erst bei neun).
        setStep('session-done');
      } else {
        setFrozenSampleIndex(sampleIndex + 1);
        setStep('sample-done');
      }
    } catch (err) {
      captureRef.current = null;
      // SpeakerEnrollError/WavConvertError tragen eine ehrliche Zeile; sonst generisch.
      fail(err instanceof Error && err.message ? err.message : SPEAKER_TEXTS.genericFail);
    }
  };

  /**
   * Abbruch — ehrlich, aber unterschiedlich je nachdem, WIE diese Sitzung begonnen hat:
   *  - Frischer Start (Satz 1): liegt schon mindestens ein Satz auf dem Server, wird das
   *    UNFERTIGE Profil best-effort verworfen (dein Profil, dein Löschen) — {@link onAborted}.
   *  - Angehängte Sitzung (Satz 4 oder 7): NICHTS wird gelöscht — ein Abbruch in Sitzung 3
   *    darf die längst abgeschlossenen Sitzungen 1+2 nie mitreißen. Nur eine ehrliche Notiz,
   *    dass DIESE Sitzung unvollständig blieb — {@link onSessionIncomplete}.
   * `isFreshStart` ist ab dem ersten gespeicherten Satz eingefroren — ein Abbruch entscheidet
   * darum IMMER anhand der Sitzung, mit der tatsächlich aufgenommen wurde, nie anhand eines
   * zwischenzeitlich (gesperrten) geänderten Namensfelds.
   */
  const cancel = async () => {
    try {
      captureRef.current?.cancel();
    } catch {
      /* ignore */
    }
    captureRef.current = null;
    const partial = savedCount > 0 && step !== 'done' && step !== 'session-done';
    if (partial) {
      if (isFreshStart) {
        try {
          await removeProfile(name.trim());
        } catch {
          /* best-effort — die Liste zeigt danach die Server-Wahrheit */
        }
        onAborted?.();
      } else {
        onSessionIncomplete?.();
      }
    }
    onClose();
  };

  const backToIntro = () => {
    setErrorText(null);
    if (frozenStartIndex === null) {
      // Noch nichts gespeichert — nichts eingefroren. Der Startindex folgt beim erneuten
      // Anzeigen des Intros weiterhin live dem (editierbaren) Namensfeld.
    } else if (frozenStartIndex === 1) {
      // Frischer Start, bereits eingefroren: Satz 1 ERSETZT ein evtl. liegengebliebenes
      // Teil-Profil (wie bisher). Das Namensfeld wird wieder frei — ein kompletter Neustart
      // darf wieder einem (evtl. geänderten) Namen folgen.
      setFrozenStartIndex(null);
      setFrozenSampleIndex(1);
      setSavedCount(0);
    } else {
      // Angehängte Sitzung, bereits eingefroren: NIE auf 1 zurück — sample=1 würde das GANZE
      // Profil ersetzen und frühere Sitzungen löschen. Stattdessen weiter am nächsten offenen
      // Index DIESER Sitzung; savedCount bleibt stehen (Namensfeld bleibt gesperrt).
      setFrozenSampleIndex(frozenStartIndex + savedCount);
    }
    setStep('intro');
  };

  /** Der Satz, der zur aktuellen Aufnahme gehört (1-basiert, ABSOLUT über alle neun). */
  const currentSentence = SPEAKER_TEXTS.sentences[Math.min(sampleIndex, ENROLL_TOTAL_SAMPLES) - 1];
  /** Die drei Sätze DIESER Sitzung (Gruppe 1/2/3 je nach {@link sessionNumber}). */
  const groupStart = (sessionNumber - 1) * ENROLL_SAMPLE_COUNT;
  const sessionSentences = SPEAKER_TEXTS.sentences.slice(groupStart, groupStart + ENROLL_SAMPLE_COUNT);

  return (
    <div className="settings__enroll" role="group" aria-label={SPEAKER_TEXTS.dialogTitle}>
      <h4 className="settings__enrolltitle">{SPEAKER_TEXTS.dialogTitle}</h4>

      {step === 'intro' && (
        <>
          <p className="settings__hint">{SPEAKER_TEXTS.dialogIntro}</p>
          <p className="settings__hint settings__consent">
            <LockGlyph /> {SPEAKER_TEXTS.consent}
          </p>
          {/* Kreuz-Kontaminations-Vorfall 07.08: zwei Haushaltsmitglieder lernten GLEICHZEITIG im selben
              Raum an — beide Profile mussten gewiped werden. Ruhiger, fester Hinweis. */}
          <p className="settings__hint">{SPEAKER_TEXTS.soloEnrollHint}</p>

          <label className="settings__label settings__enrolllabel" htmlFor="enroll-name">
            {SPEAKER_TEXTS.nameLabel}
          </label>
          <input
            id="enroll-name"
            className="settings__select settings__enrollname"
            type="text"
            value={name}
            maxLength={64}
            autoComplete="off"
            // Gesperrt, sobald DIESE Sitzung schon einen Satz gespeichert hat (Startindex ist
            // dann eingefroren) — ein Namenswechsel mitten in der Aufnahme darf nicht auf ein
            // anderes Profil umschalten (s. `frozenStartIndex` oben).
            disabled={nameLocked}
            onChange={(e) => {
              setName(e.target.value);
              setNameTouched(true);
            }}
          />
          <p className="settings__hint">{SPEAKER_TEXTS.nameHint}</p>
          {nameTouched && !nameValid && (
            <p className="settings__hint settings__enrollinvalid" role="alert">
              {SPEAKER_TEXTS.nameInvalid}
            </p>
          )}

          {/* Sitzungs-Fortschritt (Andi-Auftrag 25.07): zusätzlich zu „Satz i von 3" sichtbar. */}
          <p className="settings__hint settings__enrollsession" role="status">
            {SPEAKER_TEXTS.sessionLabel(sessionNumber)}
          </p>

          <ol className="settings__enrollsentences">
            {sessionSentences.map((line) => (
              <li className="settings__enrollsentence" key={line}>
                {SPEAKER_TEXTS.quote(line)}
              </li>
            ))}
          </ol>

          <div className="settings__enrollactions">
            <button
              type="button"
              className="settings__enrollbtn"
              disabled={!cap.ok}
              onClick={() => void startRecording()}
            >
              {sampleProgress(savedCount + 1)} {SPEAKER_TEXTS.recordSample}
            </button>
            <button type="button" className="settings__deletebtn" onClick={() => void cancel()}>
              {SPEAKER_TEXTS.cancel}
            </button>
          </div>
          {!cap.ok && (
            <p className="settings__hint settings__enrollinvalid" role="alert">
              {cap.reason}
            </p>
          )}
        </>
      )}

      {step === 'recording' && (
        <>
          <p className="settings__enrollstatus" role="status">
            <span className="settings__enrolldot" aria-hidden="true" />
            {SPEAKER_TEXTS.sessionLabel(sessionNumber)} · {sampleProgress(savedCount + 1)} —{' '}
            {SPEAKER_TEXTS.recordingHint}
          </p>
          <p className="settings__enrollsentence">{SPEAKER_TEXTS.quote(currentSentence)}</p>
          <div className="settings__enrollactions">
            <button
              type="button"
              className="settings__enrollbtn"
              onClick={() => void finishRecording()}
            >
              {SPEAKER_TEXTS.finish}
            </button>
            <button type="button" className="settings__deletebtn" onClick={() => void cancel()}>
              {SPEAKER_TEXTS.cancel}
            </button>
          </div>
        </>
      )}

      {step === 'saving' && (
        <p className="settings__enrollstatus" role="status">
          <span className="settings__samplespin" aria-hidden="true" /> {SPEAKER_TEXTS.saving} (
          {SPEAKER_TEXTS.sessionLabel(sessionNumber)} · {sampleProgress(savedCount + 1)})
        </p>
      )}

      {step === 'sample-done' && (
        <>
          <p className="settings__enrollstatus settings__enrolldone" role="status">
            ✓ {sampleProgress(savedCount)} {SPEAKER_TEXTS.sampleSaved}
          </p>
          {/* Ehrlicher Zwischenstand: es gibt noch KEIN fertiges Profil. */}
          <p className="settings__hint">{SPEAKER_TEXTS.partialHint}</p>
          <p className="settings__hint">{SPEAKER_TEXTS.nextUp}</p>
          <p className="settings__enrollsentence">{SPEAKER_TEXTS.quote(currentSentence)}</p>
          <div className="settings__enrollactions">
            <button
              type="button"
              className="settings__enrollbtn"
              onClick={() => void startRecording()}
            >
              {sampleProgress(savedCount + 1)} {SPEAKER_TEXTS.recordSample}
            </button>
            <button type="button" className="settings__deletebtn" onClick={() => void cancel()}>
              {SPEAKER_TEXTS.cancel}
            </button>
          </div>
        </>
      )}

      {step === 'session-done' && (
        <>
          {/* Sitzung 1 oder 2 fertig — das GANZE Profil ist noch nicht komplett. Ehrlich: die
              nächste Sitzung soll an einem ANDEREN Tag stattfinden (Kanal/Sitzung statt Stimme
              war der ursprüngliche Befund, s. Modul-Kommentar oben). */}
          <p className="settings__enrollstatus settings__enrolldone" role="status">
            ✓ {SPEAKER_TEXTS.sessionLabel(sessionNumber)}
          </p>
          <p className="settings__hint">{SPEAKER_TEXTS.sessionDoneHint(sessionNumber)}</p>
          <div className="settings__enrollactions">
            <button type="button" className="settings__enrollbtn" onClick={onClose}>
              {SPEAKER_TEXTS.close}
            </button>
          </div>
        </>
      )}

      {step === 'done' && (
        <>
          <p className="settings__enrollstatus settings__enrolldone" role="status">
            ✓ {SPEAKER_TEXTS.done}
          </p>
          <div className="settings__enrollactions">
            <button type="button" className="settings__enrollbtn" onClick={onClose}>
              {SPEAKER_TEXTS.close}
            </button>
          </div>
        </>
      )}

      {step === 'error' && (
        <>
          <p className="settings__hint settings__enrollinvalid" role="alert">
            {errorText ?? SPEAKER_TEXTS.genericFail}
          </p>
          {savedCount > 0 && (
            <p className="settings__hint">{SPEAKER_TEXTS.errorPartialHint}</p>
          )}
          <div className="settings__enrollactions">
            <button type="button" className="settings__enrollbtn" onClick={backToIntro}>
              {SPEAKER_TEXTS.retry}
            </button>
            {/* Schließen im Fehlerfall = Abbruch: Teil-Profil wird verworfen (cancel). */}
            <button type="button" className="settings__deletebtn" onClick={() => void cancel()}>
              {SPEAKER_TEXTS.close}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Container: lädt die Liste beim Mount (AbortController + aliveRef, Idiom von
//  PrivacySection), führt Zweitklick-Löschen und öffnet den Anlern-Dialog.
// ─────────────────────────────────────────────────────────────────────────────

export function SpeakerSection() {
  const t = useUiStrings();
  const SPEAKER_TEXTS = t.speaker;
  const [speakers, setSpeakers] = useState<SpeakerSummary[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [armed, setArmed] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  /** Profil, das per „Weiter anlernen" fortgesetzt wird — `null` ⇒ normaler Anlern-Knopf. */
  const [continueName, setContinueName] = useState<string | null>(null);
  /** Aufnahmen-Diagnose je Profil (Reparatur-Auftrag 07.08) — EIN GET fuer ALLE Profile. */
  const [diagnostics, setDiagnostics] = useState<SpeakerDiagnostics | null>(null);
  const [diagnosticsError, setDiagnosticsError] = useState<string | null>(null);
  /** Welche Aufnahme (Profilname + Index) gerade geloescht wird — sperrt NUR diesen Knopf. */
  const [sampleBusy, setSampleBusy] = useState<{ name: string; index: number } | null>(null);
  const aliveRef = useRef(true);
  const armTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const load = async (signal?: AbortSignal) => {
    try {
      const next = await fetchSpeakers(signal);
      if (aliveRef.current) {
        setSpeakers(next);
        setError(null);
      }
    } catch {
      if (aliveRef.current) {
        setError(SPEAKER_TEXTS.loadError);
        setSpeakers((cur) => cur ?? []); // ehrlich: Liste bleibt, was sie war (oder leer)
      }
    } finally {
      if (aliveRef.current) setLoading(false);
    }
  };

  /** Aufnahmen-Diagnose separat laden (eigener Fehlerkanal — die Profil-Liste bleibt lesbar). */
  const loadDiagnostics = async (signal?: AbortSignal) => {
    try {
      const next = await fetchSpeakerDiagnostics(signal);
      if (aliveRef.current) {
        setDiagnostics(next);
        setDiagnosticsError(null);
      }
    } catch {
      if (aliveRef.current) setDiagnosticsError(SPEAKER_TEXTS.recordingsLoadError);
    }
  };

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void load(controller.signal);
    void loadDiagnostics(controller.signal);
    return () => {
      aliveRef.current = false;
      controller.abort();
      if (armTimerRef.current) clearTimeout(armTimerRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleDelete = (name: string) => {
    if (busy) return;
    if (armTimerRef.current) clearTimeout(armTimerRef.current);
    // Erster Klick: nur schärfen — nichts löschen (wie Privacy).
    if (armed !== name) {
      setArmed(name);
      armTimerRef.current = setTimeout(() => {
        if (aliveRef.current) setArmed(null);
      }, ARM_TIMEOUT_MS);
      return;
    }
    // Zweiter Klick: wirklich löschen.
    setArmed(null);
    setBusy(name);
    setNote(null);
    void (async () => {
      try {
        await deleteSpeaker(name);
        if (!aliveRef.current) return;
        // Server-Wahrheit nachladen (nicht optimistisch raten).
        await load();
        void loadDiagnostics();
      } catch {
        if (aliveRef.current) setNote(SPEAKER_TEXTS.deleteFailed);
      } finally {
        if (aliveRef.current) setBusy(null);
      }
    })();
  };

  /** Einzel-Aufnahme-Löschen (Reparatur-Auftrag 07.08) — nutzt die neue `.../samples/{index}`-Naht. */
  const handleDeleteSample = (name: string, index: number) => {
    if (sampleBusy) return;
    setSampleBusy({ name, index });
    setNote(null);
    void (async () => {
      try {
        await deleteSpeakerSample(name, index);
        if (!aliveRef.current) return;
        // Server-Wahrheit nachladen (Zentroid + Aufnahmen-Zahl haben sich geändert).
        await Promise.all([load(), loadDiagnostics()]);
      } catch (err) {
        if (aliveRef.current) {
          setNote(
            err instanceof SpeakerSampleDeleteError && err.kind === 'last-sample'
              ? SPEAKER_TEXTS.deleteRecordingLastHint
              : SPEAKER_TEXTS.deleteRecordingFailed,
          );
        }
      } finally {
        if (aliveRef.current) setSampleBusy(null);
      }
    })();
  };

  const handleEnrolled = () => {
    // Dialog zeigt den Erfolg selbst; hier die Liste frisch vom Server holen.
    setNote(SPEAKER_TEXTS.enrolledNote);
    void load();
    void loadDiagnostics();
  };

  return (
    <>
      <SpeakerListView
        speakers={speakers}
        loading={loading}
        error={error}
        armed={armed}
        busy={busy}
        note={note}
        onDelete={handleDelete}
        onEnroll={() => {
          setNote(null);
          setContinueName(null);
          setDialogOpen(true);
        }}
        onContinue={(name) => {
          setNote(null);
          setContinueName(name);
          setDialogOpen(true);
        }}
        diagnostics={diagnostics}
        diagnosticsError={diagnosticsError}
        onDeleteSample={handleDeleteSample}
        sampleBusy={sampleBusy}
      />
      {dialogOpen && (
        <EnrollDialog
          defaultName={continueName ?? SPEAKER_ID}
          // „Weiter anlernen" (Andi-Auftrag 07.08): Name ist ein BESTEHENDES Profil, von
          // Anfang an schreibgeschützt — die Startindex-folgt-Namen-Logik unten setzt sich
          // damit automatisch auf den richtigen Fortsetzungs-Index (kein eigener Code nötig).
          lockName={continueName !== null}
          // Löst den Sätze-Zählerstand GEGEN DIE GELADENE LISTE auf — reaktiv nach dem im
          // Dialog GETIPPTEN Namen (trimmed + case-insensitiv), nicht gegen einen fest
          // verdrahteten Default. Sonst würde z. B. Person Bs Profil (unterschieden von „gast"
          // nur durchs Namensfeld) bei jeder Sitzung überschrieben (`sample=1`) statt ergänzt.
          samplesForName={(candidate) => samplesForNameIn(speakers, candidate)}
          onClose={() => {
            setDialogOpen(false);
            setContinueName(null);
            // Server-Wahrheit nachladen — auch nach Abbruch (Teil-Profil verworfen?).
            void load();
            void loadDiagnostics();
          }}
          onEnrolled={handleEnrolled}
          onAborted={() => setNote(SPEAKER_TEXTS.abortedNote)}
          onSessionIncomplete={() => setNote(SPEAKER_TEXTS.sessionIncompleteNote)}
        />
      )}
    </>
  );
}
