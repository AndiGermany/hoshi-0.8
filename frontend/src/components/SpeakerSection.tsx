import { useEffect, useRef, useState } from 'react';
import { SPEAKER_ID } from '../api/config';
import {
  SPEAKER_NAME_PATTERN,
  type SpeakerSummary,
  deleteSpeaker,
  enrollSpeaker,
  fetchSpeakers,
} from '../api/speakers';
import { type EnrollCapture, createBrowserEnrollCapture } from '../audio/enrollCapture';
import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
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

/** Wie viele Aufnahmen ein komplettes Profil braucht (Ein-Satz-Embeddings streuen zu stark). */
export const ENROLL_SAMPLE_COUNT = 3;

/**
 * Drei natürliche deutsche Sätze zum Nachsprechen — je einer pro Aufnahme, jeweils
 * ~5–8 Sekunden gesprochen (Sara-Ton: warm + alltagsnah, keine Zungenbrecher).
 * Reihenfolge == Aufnahme-Reihenfolge (Satz 1/2/3).
 */
export const ENROLL_SENTENCES = [
  'Hallo Hoshi, ich bin’s — ich möchte, dass du meine Stimme ab jetzt sicher wiedererkennst.',
  'Heute war ein ganz normaler Tag, und ich erzähle dir gerade in aller Ruhe ein bisschen davon.',
  'Wenn später etwas Wichtiges ansteht, dann sag mir bitte rechtzeitig und freundlich Bescheid.',
] as const;

/** „Satz i von 3" — der eine Fortschritts-Text, überall identisch (UI + Tests). */
export function sampleProgress(i: number): string {
  return `Satz ${i} von ${ENROLL_SAMPLE_COUNT}`;
}

/**
 * Alle sichtbaren Texte an einem Ort (auch von den Tests referenziert) — jetzt
 * eine Referenz auf den `de`-Katalog in `i18n/de.ts` (byte-gleich zum
 * bisherigen Stand). Gerendert wird `useUiStrings().speaker`, s. unten.
 */
export const SPEAKER_TEXTS = de.speaker;

/** Wie lange der scharfe Zweitklick-Zustand hält, bevor er sich selbst entschärft (wie Privacy). */
const ARM_TIMEOUT_MS = 5000;

/** Anlern-Datum menschlich (nie eine erfundene Zahl — 0/fehlend ⇒ „gerade eben"). */
export function formatEnrolledDate(ms: number): string {
  if (!ms || ms <= 0) return 'gerade eben';
  try {
    return new Date(ms).toLocaleDateString('de-DE', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  } catch {
    return '';
  }
}

/** Kapazitäts-Probe: sichere Verbindung + Mikro + MediaRecorder da? Ehrlicher Grund, wenn nicht. */
export function micSupport(): { ok: boolean; reason?: string } {
  if (typeof globalThis.isSecureContext === 'boolean' && !globalThis.isSecureContext) {
    return { ok: false, reason: SPEAKER_TEXTS.insecure };
  }
  const md = globalThis.navigator?.mediaDevices;
  if (!md || typeof md.getUserMedia !== 'function') {
    return { ok: false, reason: SPEAKER_TEXTS.noMic };
  }
  if (typeof globalThis.MediaRecorder === 'undefined') {
    return { ok: false, reason: SPEAKER_TEXTS.noMic };
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

      {loading && !speakers && <p className="settings__hint">lädt…</p>}
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
                    angelernt {formatEnrolledDate(s.enrolledAt)}
                    {typeof s.samples === 'number' &&
                      ` · ${s.samples} ${s.samples === 1 ? 'Satz' : 'Sätze'}`}
                  </span>
                </div>
                <button
                  type="button"
                  className={`settings__deletebtn ${isArmed ? 'is-armed' : ''}`}
                  disabled={isBusy}
                  aria-label={`Profil ${s.name} löschen`}
                  onClick={() => onDelete(s.name)}
                >
                  {isBusy
                    ? SPEAKER_TEXTS.deleting
                    : isArmed
                      ? SPEAKER_TEXTS.confirm
                      : SPEAKER_TEXTS.delete}
                </button>
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
//  Anlern-Dialog: geführter Aufnahme-Flow über DREI Sätze (Multi-Sample-Enroll).
//  Satz 1 ersetzt das Profil (frischer Start), Satz 2+3 hängen additiv an — das
//  Backend mittelt (L2-renormalisiert). Erst nach Satz 3 ist das Profil komplett;
//  jeder Zwischenstand sagt das ehrlich. Aufnahme + Enroll + Rollback sind
//  injizierbare Props (Default: echter Browser) → im jsdom-Test ohne Mikro fahrbar.
// ─────────────────────────────────────────────────────────────────────────────

type EnrollStep = 'intro' | 'recording' | 'saving' | 'sample-done' | 'done' | 'error';

export interface EnrollDialogProps {
  onClose: () => void;
  onEnrolled: (summary: SpeakerSummary) => void;
  /** Abbruch NACH mindestens einem gespeicherten Satz (unfertiges Profil wurde verworfen). */
  onAborted?: () => void;
  defaultName?: string;
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
  defaultName = SPEAKER_ID,
  createCapture = createBrowserEnrollCapture,
  enroll = enrollSpeaker,
  removeProfile = deleteSpeaker,
  support = micSupport,
}: EnrollDialogProps) {
  const t = useUiStrings();
  const SPEAKER_TEXTS = t.speaker;
  const [step, setStep] = useState<EnrollStep>('intro');
  /** Welcher Satz als Nächstes aufgenommen wird (1-basiert). */
  const [sampleIndex, setSampleIndex] = useState(1);
  /** Wie viele Sätze der Server schon hat (ehrlicher Zwischenstand). */
  const [savedCount, setSavedCount] = useState(0);
  const [name, setName] = useState(defaultName);
  const [nameTouched, setNameTouched] = useState(false);
  const [errorText, setErrorText] = useState<string | null>(null);
  const captureRef = useRef<EnrollCapture | null>(null);
  const aliveRef = useRef(true);

  const cap = support();
  const nameValid = SPEAKER_NAME_PATTERN.test(name.trim());

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
      // sample=1 ersetzt (frischer Start), 2..n hängen an — BE-Contract.
      const summary = await enroll(name.trim(), wav, sampleIndex);
      if (!aliveRef.current) return;
      setSavedCount(sampleIndex);
      if (sampleIndex < ENROLL_SAMPLE_COUNT) {
        setSampleIndex(sampleIndex + 1);
        setStep('sample-done');
      } else {
        setStep('done');
        onEnrolled(summary);
      }
    } catch (err) {
      captureRef.current = null;
      // SpeakerEnrollError/WavConvertError tragen eine ehrliche Zeile; sonst generisch.
      fail(err instanceof Error && err.message ? err.message : SPEAKER_TEXTS.genericFail);
    }
  };

  /**
   * Abbruch — ehrlich: liegt schon mindestens ein Satz auf dem Server, wird das
   * UNFERTIGE Profil best-effort verworfen (dein Profil, dein Löschen) und der
   * Parent informiert ({@link onAborted} → Notiz + Liste neu laden = Server-Wahrheit).
   */
  const cancel = async () => {
    try {
      captureRef.current?.cancel();
    } catch {
      /* ignore */
    }
    captureRef.current = null;
    const partial = savedCount > 0 && step !== 'done';
    if (partial) {
      try {
        await removeProfile(name.trim());
      } catch {
        /* best-effort — die Liste zeigt danach die Server-Wahrheit */
      }
      onAborted?.();
    }
    onClose();
  };

  const backToIntro = () => {
    // Neustart von vorn: Satz 1 ERSETZT ein evtl. liegengebliebenes Teil-Profil.
    setErrorText(null);
    setSampleIndex(1);
    setSavedCount(0);
    setStep('intro');
  };

  /** Der Satz, der zur aktuellen Aufnahme gehört (1-basiert → Index-Shift). */
  const currentSentence = ENROLL_SENTENCES[Math.min(sampleIndex, ENROLL_SAMPLE_COUNT) - 1];

  return (
    <div className="settings__enroll" role="group" aria-label={SPEAKER_TEXTS.dialogTitle}>
      <h4 className="settings__enrolltitle">{SPEAKER_TEXTS.dialogTitle}</h4>

      {step === 'intro' && (
        <>
          <p className="settings__hint">{SPEAKER_TEXTS.dialogIntro}</p>
          <p className="settings__hint settings__consent">
            <LockGlyph /> {SPEAKER_TEXTS.consent}
          </p>

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

          <ol className="settings__enrollsentences">
            {ENROLL_SENTENCES.map((line) => (
              <li className="settings__enrollsentence" key={line}>
                „{line}“
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
              {sampleProgress(1)} {SPEAKER_TEXTS.recordSample}
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
            {sampleProgress(sampleIndex)} — {SPEAKER_TEXTS.recordingHint}
          </p>
          <p className="settings__enrollsentence">„{currentSentence}“</p>
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
          {sampleProgress(sampleIndex)})
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
          <p className="settings__enrollsentence">„{currentSentence}“</p>
          <div className="settings__enrollactions">
            <button
              type="button"
              className="settings__enrollbtn"
              onClick={() => void startRecording()}
            >
              {sampleProgress(sampleIndex)} {SPEAKER_TEXTS.recordSample}
            </button>
            <button type="button" className="settings__deletebtn" onClick={() => void cancel()}>
              {SPEAKER_TEXTS.cancel}
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

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();
    void load(controller.signal);
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
      } catch {
        if (aliveRef.current) setNote(SPEAKER_TEXTS.deleteFailed);
      } finally {
        if (aliveRef.current) setBusy(null);
      }
    })();
  };

  const handleEnrolled = () => {
    // Dialog zeigt den Erfolg selbst; hier die Liste frisch vom Server holen.
    setNote(SPEAKER_TEXTS.enrolledNote);
    void load();
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
          setDialogOpen(true);
        }}
      />
      {dialogOpen && (
        <EnrollDialog
          onClose={() => {
            setDialogOpen(false);
            // Server-Wahrheit nachladen — auch nach Abbruch (Teil-Profil verworfen?).
            void load();
          }}
          onEnrolled={handleEnrolled}
          onAborted={() => setNote(SPEAKER_TEXTS.abortedNote)}
        />
      )}
    </>
  );
}
