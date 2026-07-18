import { useCallback, useEffect, useRef, useState } from 'react';
import { streamChat } from '../api/chat';
import { streamVoice } from '../api/voice';
import { SPEAKER_ID } from '../api/config';
import type { ChatEvent, ChatMessage, EscalationSourceRef, Language, RecognizedSpeaker } from '../api/types';
import type { Persona } from './useSettings';
import { AudioQueue, loadSpeakPref, saveSpeakPref } from '../audio/playback';
import { playTurnEarcon } from '../audio/earcon';
import { voiceTurnUploadBlob } from '../audio/wav';
import { VoiceRecorder, VoiceRecorderError } from '../audio/recorder';
import { emaLevel } from '../audio/level';
import { gammaLevel } from '../audio/motionTokens';
import { anatomyOnEvent, emptyAnatomy, type TurnAnatomyState } from '../components/TurnAnatomy';

/**
 * **useVoiceChatSession** — die EINE Quelle des Chat-/Sprach-Zustands, aus
 * {@link ../components/ChatView} herausgezogen (Andi-Auftrag 19.07: Voice-Orb
 * im Home-Screen braucht denselben Verlauf + denselben Browser-Voice-Pfad wie
 * der Chat-Reiter — kein zweiter Verlauf, keine Duplikation von Chat-State).
 *
 * Wer den Hook aufruft, bestimmt die LEBENSDAUER des Verlaufs: `App.tsx` ruft
 * ihn EINMAL auf und reicht die eine Session an {@link ChatViewBody} (Chat-
 * Reiter) UND {@link VoiceOrb} (Home-Reiter) durch — beide sehen denselben
 * `turns`-Verlauf, dieselbe Aufnahme/Wiedergabe. Die Standalone-`ChatView`
 * (Tests/Storybook) ruft ihn selbst auf und bleibt dadurch BYTE-GLEICH zum
 * bisherigen Verhalten (eigene Session, stirbt mit dem Unmount).
 *
 * DOM-lokale Dinge (Textarea-Ref, Auto-Scroll, Compose-Input) bleiben bewusst
 * in den Views — nur der eigentliche Sprach-/Gedächtnis-Zustand wandert hierher.
 * Der ECHTE Pegel (Hören: recorder.onLevel · Sprechen: AnalyserNode) fließt
 * weiter imperativ (kein 60×/s-Re-render) — über {@link setLevelSink} registriert
 * die jeweils gemountete Ansicht (VoiceWaveform in ChatView, der große Orb im
 * Home-Screen), WOHIN der Pegel gezeichnet wird.
 */

export interface Turn {
  role: 'user' | 'assistant';
  text: string;
  meta?: string;
  error?: boolean;
  /**
   * Wer diesen Turn per Stimme gesprochen hat (S3). NUR an Sprach-Turns gesetzt,
   * wenn das Backend ein `speaker`-Event vorangestellt hat — getippte Turns bleiben
   * bewusst OHNE (wir wissen nicht, wer tippt). Trägt den „Wer sprach"-Chip.
   */
  speaker?: RecognizedSpeaker;
  /**
   * Sprach-Turn wartet noch aufs Transkript: die Du-Blase rendert dann eine
   * schlichte Punkte-Pille und verwandelt sich beim `transcript`-Step in den
   * Text. Nur der Voice-Pfad setzt das Flag.
   */
  pending?: boolean;
  /**
   * §4 Turn-Anatomie — NUR an Assistant-Turns: welche echten Wire-Events dieser
   * Turn gesehen hat (Denk-Stufen-Zeile + Chips).
   */
  anatomy?: TurnAnatomyState;
  /**
   * Strukturierte Recherche-Quellen (Quellen-Struktur-Auftrag 2026-07-21) — aus
   * dem additiven `done.escalationSources`-Feld, NUR bei echten `url_citation`-
   * Treffern gesetzt. Speist das kleine „i"-Icon unter der Antwort (ChatView);
   * fehlt es (Modellwissen-Fallback ohne echte Quellen), rendert KEIN Icon.
   */
  sources?: EscalationSourceRef[];
}

/**
 * Gast-Sprecher-Id für `speakerContext.speakerId`, wenn der zuletzt per Stimme
 * Erkannte ein Gast/unsicher war — gespiegelt zum BE (`GUEST_SPEAKER_ID="gast"`,
 * kein Memory-Load/-Write). Vera-Regel: lieber „gast" als eine falsche Person.
 */
export const GUEST_SPEAKER_ID = 'gast';

/**
 * FE-Deckel für den mitgeschickten Verlauf: die letzten 12 Nachrichten = 6 Paare.
 * Wir schicken keine Megabytes — das Backend fenstert per HOSHI_MEMORY_WINDOW_TURNS
 * (Default 12 Turns) sowieso nochmal; das hier hält bloß den Request schlank.
 */
export const HISTORY_MAX_MESSAGES = 12;

/**
 * Baut aus dem gerenderten Turn-Verlauf die `history: ChatMessage[]` fürs
 * Gesprächsgedächtnis (matcht die BE-DTO `ChatMessage(role, content)`).
 *
 * NUR fertige Paare: die Turns liegen paarweise vor (send() pusht user+assistant
 * zusammen). Ein Paar fließt nur ein, wenn BEIDE Bubbles sauber sind —
 * kein Fehler, kein leerer/streamender Platzhalter (leerer Text = noch nicht
 * fertig). So landen weder die gerade streamende Antwort noch eine Fehler-Bubble
 * im Verlauf. Reihenfolge chronologisch (ältester zuerst), FE-seitig gecappt.
 */
export function buildHistory(turns: Turn[], cap = HISTORY_MAX_MESSAGES): ChatMessage[] {
  const msgs: ChatMessage[] = [];
  for (let i = 0; i + 1 < turns.length; i += 2) {
    const user = turns[i];
    const assistant = turns[i + 1];
    // Defensiv gegen unerwartete Reihenfolge (send() hält user→assistant ein).
    if (user.role !== 'user' || assistant.role !== 'assistant') continue;
    if (user.error || assistant.error) continue; // gescheitertes Paar → raus
    if (!user.text.trim() || !assistant.text.trim()) continue; // leer/streamend → raus
    msgs.push({ role: 'user', content: user.text });
    msgs.push({ role: 'assistant', content: assistant.text });
  }
  return msgs.slice(-cap); // letzte N Nachrichten (N/2 Paare) — kein Mega-Body
}

/**
 * Zustände der Sprach-Eingabe (Push-to-Talk):
 *   idle → listening (Mikro nimmt auf, Pegel-Meter) → transcribing (hochgeladen,
 *   wartet auf Transkript) → responding (Deltas streamen + Audio spielt) → idle.
 */
export type MicState = 'idle' | 'listening' | 'transcribing' | 'responding';

/** Was im Wave-Slot der Compose-Bar lebt — genau EIN Zustand zur Zeit. */
export type ComposeSlot = 'wave-in' | 'wave-out' | 'processing' | 'input';

/**
 * Der Compose-Bar-Slot als PURE Funktion (headless testbar): die Welle
 * existiert NUR, wenn ein Audio-Kanal offen ist — Mikro hört (`wave-in`,
 * Nutzer-Pegel) oder Hoshi spricht (`wave-out`, echter TTS-Ausgabepegel).
 * STT/Denken OHNE laufendes Audio ⇒ Punkte (`processing`), sonst Textarea
 * (`input`). Gesetz: nichts leuchtet, was nichts misst — kein Zustand ohne
 * Audio rendert eine Welle. `wave-out` hat Vorrang vor `processing`, weil
 * beim Sprach-Turn `responding` UND `speaking` gleichzeitig gelten — sobald
 * Audio läuft, gewinnt die Welle.
 */
export function composeSlot(micState: MicState, speaking: boolean): ComposeSlot {
  if (micState === 'listening') return 'wave-in';
  if (speaking) return 'wave-out';
  if (micState === 'transcribing' || micState === 'responding') return 'processing';
  return 'input';
}

/** Mappt einen Aufnahme-Fehler auf eine warme, sichtbare Zeile (never-silent). */
function humanMicError(err: unknown): string {
  if (err instanceof VoiceRecorderError) return err.message;
  return err instanceof Error ? err.message : String(err);
}

/** Sekunden → `m:ss` für den Aufnahme-Timer (tabular, springt nicht). */
export function fmtTime(totalSec: number): string {
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/** Ab dieser Wartezeit ohne erstes Delta/Audio erscheint die Ehrlichkeits-Zeile. */
export const SLOW_TURN_MS = 8000;

/** Die Ehrlichkeits-Zeile für lange Turns — warm, kein Alarm. */
export const SLOW_TURN_TEXT = 'dauert grad länger als sonst — ich bin dran.';

/**
 * Sicherheitsnetz für den „spricht"-Zustand (Andi-Befund 20.07 ~23:35: nach
 * einer Rechenaufgabe blieb die Compose-Bar dauerhaft auf „spricht…" stehen,
 * obwohl Text UND Audio längst fertig waren — der Turn hatte kein `tts_audio_end`
 * bekommen). `speaking` schließt PRIMÄR an zwei echten Signalen (s. `closeSpeaking`-
 * Aufrufstellen): `tts_audio_end` (der Normalfall) ODER `done` (das Turn-Ende
 * selbst — jeder Turn endet mit `done`, auch wenn `tts_audio_end` aus irgendeinem
 * Grund fehlte). Dieser Timer ist NUR das zusätzliche Netz für den Fall, dass
 * SELBST das nicht durchkommt (z. B. der Stream hängt komplett) — er darf eine
 * echte, lange Sprachausgabe nicht kappen, darum großzügig bemessen.
 */
export const SPEAKING_WATCHDOG_MS = 20000;

/**
 * Kürzt eine `step`-Message aufs proc-Label: Whitespace kollabiert, ab `max`
 * Zeichen mit …-Ellipse. Zeigt NUR, was das Backend wirklich meldet — keine
 * erfundenen Stages.
 */
export function stepLabelText(message: string, max = 40): string {
  const clean = message.replace(/\s+/g, ' ').trim();
  return clean.length <= max ? clean : `${clean.slice(0, max - 1).trimEnd()}…`;
}

/**
 * Wohin der ECHTE Pegel gerade gezeichnet wird — genau EIN Sink zur Zeit,
 * registriert von der gerade gemounteten Ansicht (ChatViews VoiceWaveform
 * ODER der Home-Orb). `push` treibt die Optik pro Frame, `reset` fährt sie
 * beim Kanal-/Turn-Wechsel zurück auf den Ausgangspunkt.
 */
export interface LevelSink {
  push: (level: number) => void;
  reset: () => void;
}

export interface VoiceChatSessionArgs {
  persona: Persona;
  language: Language;
  /** OpenAI-Voice-Name (Cloud-TTS-Stimme) — fließt in beide Requests. */
  voice: string;
}

export interface VoiceChatSession {
  turns: Turn[];
  busy: boolean;
  activeSpeakerId: string;
  activeSpeakerName: string;
  voiceOn: boolean;
  speaking: boolean;
  micState: MicState;
  /** Ref-Spiegel von `micState` — für Handler, die keine Stale-Closure wollen. */
  micStateRef: { readonly current: MicState };
  micError: string | null;
  recSecs: number;
  stepLabel: string | null;
  slow: boolean;
  send: (text: string) => Promise<void>;
  startRecording: () => Promise<void>;
  stopAndSend: () => Promise<void>;
  cancelRecording: () => void;
  bargeIn: () => void;
  toggleVoice: () => void;
  /** Registriert (oder löscht mit `null`), wohin der Live-Pegel gezeichnet wird. */
  setLevelSink: (sink: LevelSink | null) => void;
}

export function useVoiceChatSession({
  persona,
  language,
  voice,
}: VoiceChatSessionArgs): VoiceChatSession {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [busy, setBusy] = useState(false);
  // Dynamische Sprecher-Identität (S3): steuert `speakerContext.speakerId` für
  // Folge-TIPP-Turns. Default = SPEAKER_ID („andi") → Ein-Nutzer-Textfall bleibt
  // wie heute; die erste Stimm-Erkennung setzt ihn auf den erkannten Namen (bzw.
  // GUEST_SPEAKER_ID bei Gast). Der Voice-Pfad selbst ist BE-autoritativ.
  const [activeSpeakerId, setActiveSpeakerId] = useState<string>(SPEAKER_ID);
  const [activeSpeakerName, setActiveSpeakerName] = useState<string>('');
  const [voiceOn, setVoiceOn] = useState<boolean>(() => loadSpeakPref());
  const [speaking, setSpeaking] = useState(false);
  const [micState, setMicState] = useState<MicState>('idle');
  const [micError, setMicError] = useState<string | null>(null);
  const [recSecs, setRecSecs] = useState(0); // Aufnahme-Dauer (für den Waveform-Timer)
  const [stepLabel, setStepLabel] = useState<string | null>(null);
  const [slow, setSlow] = useState(false);
  const slowTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Sicherheitsnetz-Timer fürs „spricht" — s. SPEAKING_WATCHDOG_MS-KDoc.
  const speakingWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const abortRef = useRef<AbortController | null>(null);

  // Der EINE Pegel-Sink: die gerade gemountete Ansicht registriert sich hier
  // (ChatView-Waveform ODER Home-Orb) — imperativ, kein Re-render pro Frame.
  const levelSinkRef = useRef<LevelSink | null>(null);
  const setLevelSink = useCallback((sink: LevelSink | null) => {
    levelSinkRef.current = sink;
  }, []);
  const pushLevel = (lvl: number) => levelSinkRef.current?.push(lvl);
  const resetLevel = () => levelSinkRef.current?.reset();

  // Geglättete Pegel-Spuren: `displayLevelRef` folgt deinem Mikro-Pegel
  // (Hören), `outLevelRef` Hoshis Ausgabe-Pegel (Sprechen). Asymmetrische EMA
  // (schneller Anstieg, ruhiges Abklingen).
  const displayLevelRef = useRef(0);
  const outLevelRef = useRef(0);

  // Eine einzige AudioQueue über die gesamte Lebensdauer der Session. Lazy: der
  // echte AudioContext entsteht erst auf eine User-Geste (start()).
  const queueRef = useRef<AudioQueue | null>(null);
  if (!queueRef.current) queueRef.current = new AudioQueue();
  const queue = queueRef.current;

  const recorderRef = useRef<VoiceRecorder | null>(null);
  const micStateRef = useRef<MicState>('idle');
  const setMic = (s: MicState) => {
    micStateRef.current = s;
    setMicState(s);
  };

  const clearSpeakingWatchdog = () => {
    if (speakingWatchdogRef.current) {
      clearTimeout(speakingWatchdogRef.current);
      speakingWatchdogRef.current = null;
    }
  };

  /**
   * Schließt „spricht" — von JEDER Stelle aufrufbar, die weiß, dass der
   * Audio-Kanal jetzt sicher zu ist: `tts_audio_end`, `done` (Turn-Ende, auch
   * wenn `tts_audio_end` mal fehlte), Barge-in, Turn-Start des NÄCHSTEN Turns
   * und der `finally`-Block (Stream wirklich zu Ende, Erfolg wie Fehler). Kappt
   * IMMER das Sicherheitsnetz mit — sonst könnte ein alter Watchdog mitten im
   * NÄCHSTEN Turn feuern und dessen „spricht" fälschlich abwürgen.
   */
  const closeSpeaking = () => {
    clearSpeakingWatchdog();
    setSpeaking(false);
  };

  /** Öffnet „spricht" (`tts_audio_start`) UND armiert das Sicherheitsnetz neu. */
  const openSpeaking = () => {
    clearSpeakingWatchdog();
    setSpeaking(true);
    speakingWatchdogRef.current = setTimeout(() => setSpeaking(false), SPEAKING_WATCHDOG_MS);
  };

  // Aufnahme-Timer: zählt Sekunden, solange das Mikro hört. 500ms-Tick, aber
  // setRecSecs auf den GERUNDETEN Wert → React bailt bei gleicher Sekunde.
  useEffect(() => {
    if (micState !== 'listening') return;
    setRecSecs(0);
    const t0 = Date.now();
    const id = setInterval(() => setRecSecs(Math.floor((Date.now() - t0) / 1000)), 500);
    return () => clearInterval(id);
  }, [micState]);

  // Ausgabe-Pegel: nur WÄHREND Hoshi spricht eine rAF-Schleife, die den
  // ECHTEN AnalyserNode-Pegel der Queue liest, per Gamma anhebt und glättet —
  // keine Simulation. Endet (cancel + Reset) bei tts_audio_end/Barge-in/Unmount.
  useEffect(() => {
    if (!speaking) return;
    let raf = 0;
    resetLevel(); // frischer Verlauf, wenn Hoshi zu sprechen beginnt
    const loop = () => {
      outLevelRef.current = emaLevel(outLevelRef.current, gammaLevel(queue.getOutputLevel()));
      pushLevel(outLevelRef.current);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => {
      cancelAnimationFrame(raf);
      outLevelRef.current = 0;
      resetLevel();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [speaking, queue]);

  // Esc: während der Antwort → Barge-in (Turn abbrechen); während der Aufnahme
  // → verwerfen. EIN window-Listener über die gesamte Session-Lebensdauer
  // (nicht pro Ansicht) — funktioniert also auch, wenn Andi mitten in der
  // Aufnahme vom Home-Orb in den Chat-Reiter wechselt.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      const s = micStateRef.current;
      if (s === 'responding') bargeIn();
      else if (s === 'listening') cancelRecording();
    };
    globalThis.addEventListener('keydown', onKey);
    return () => globalThis.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Session-Ende: laufenden Stream abbrechen + Audio stoppen/Context schließen +
  // eine evtl. laufende Aufnahme verwerfen + den 8s-Ehrlichkeits-Timer kappen.
  useEffect(
    () => () => {
      abortRef.current?.abort();
      queue.close();
      recorderRef.current?.cancel();
      if (slowTimerRef.current) clearTimeout(slowTimerRef.current);
      clearSpeakingWatchdog();
    },
    [queue],
  );

  const toggleVoice = () => {
    setVoiceOn((on) => {
      const next = !on;
      saveSpeakPref(next);
      if (next) {
        queue.start();
      } else {
        queue.stop();
        closeSpeaking();
      }
      return next;
    });
  };

  // ── Ehrliches Turn-Feedback (Denk-Label + >8s-Zeile) ─────────────────────

  const armTurnFeedback = () => {
    setStepLabel(null);
    setSlow(false);
    if (slowTimerRef.current) clearTimeout(slowTimerRef.current);
    slowTimerRef.current = setTimeout(() => setSlow(true), SLOW_TURN_MS);
  };

  const markFirstResponse = () => {
    if (slowTimerRef.current) {
      clearTimeout(slowTimerRef.current);
      slowTimerRef.current = null;
    }
    setSlow(false);
  };

  const clearTurnFeedback = () => {
    markFirstResponse();
    setStepLabel(null);
  };

  const patchAssistant = (fn: (prev: Turn) => Turn) =>
    setTurns((t) => {
      const next = [...t];
      next[next.length - 1] = fn(next[next.length - 1]);
      return next;
    });

  const withAnatomy = (p: Turn, ev: ChatEvent): Turn => {
    if (!p.anatomy) return p;
    const next = anatomyOnEvent(p.anatomy, ev);
    return next === p.anatomy ? p : { ...p, anatomy: next };
  };

  /** Sendet `text` als Text-Turn (Chat-Compose-Bar oder Vorschlags-Chip). */
  const send = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busy || micStateRef.current !== 'idle') return;

    queue.stop();
    closeSpeaking();
    if (voiceOn) {
      queue.start();
      playTurnEarcon();
    }

    const history = buildHistory(turns);

    setBusy(true);
    armTurnFeedback();
    setTurns((t) => [
      ...t,
      { role: 'user', text: trimmed },
      { role: 'assistant', text: '', meta: '…', anatomy: emptyAnatomy('text') },
    ]);

    const ctrl = new AbortController();
    abortRef.current = ctrl;

    const onEvent = (ev: ChatEvent) => {
      switch (ev.event) {
        case 'start':
          patchAssistant((p) =>
            withAnatomy({ ...p, meta: `${ev.provider} · ${ev.model} · ${ev.category}` }, ev),
          );
          break;
        case 'delta':
          markFirstResponse();
          patchAssistant((p) => withAnatomy({ ...p, text: p.text + ev.text }, ev));
          break;
        case 'tts_audio_start':
          openSpeaking();
          patchAssistant((p) => withAnatomy(p, ev));
          break;
        case 'audio':
          markFirstResponse();
          queue.enqueue(ev.data, ev.seq);
          break;
        case 'tts_audio_end':
          closeSpeaking();
          break;
        case 'error':
          patchAssistant((p) =>
            withAnatomy(
              { ...p, error: true, meta: `Fehler · ${ev.stage ?? 'LLM'}`, text: p.text || ev.message },
              ev,
            ),
          );
          break;
        case 'done':
          patchAssistant((p) => ({
            ...p,
            meta: p.error ? p.meta : (ev.provider || p.meta),
            // Additives Wire-Feld (s. Turn.sources-KDoc): nur bei echten Citations
            // gesetzt, sonst bleibt p.sources unverändert (i.d.R. undefined).
            sources: ev.escalationSources ?? p.sources,
          }));
          // Turn-Ende schließt „spricht" MIT — auch wenn `tts_audio_end` (aus
          // welchem Grund auch immer) ausblieb (Andi-Befund 20.07, Rechenaufgabe).
          closeSpeaking();
          break;
        default:
          break;
      }
    };

    try {
      await streamChat(trimmed, {
        onEvent,
        signal: ctrl.signal,
        speak: voiceOn,
        persona,
        language,
        voice,
        history,
        speakerId: activeSpeakerId,
        displayName: activeSpeakerName,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      patchAssistant((p) => ({ ...p, error: true, meta: 'Verbindung', text: p.text || message }));
    } finally {
      setBusy(false);
      clearTurnFeedback();
      // Letztes Netz: der Stream ist jetzt WIRKLICH zu Ende (Erfolg, Fehler
      // oder Abbruch) — „spricht" darf danach nie mehr offen bleiben, egal was
      // vorher an Events (nicht) ankam.
      closeSpeaking();
      abortRef.current = null;
    }
  };

  // ── Sprach-Eingabe (Push-to-Talk / Tap-to-Toggle) ────────────────────────

  const bargeIn = () => {
    abortRef.current?.abort();
    queue.stop();
    closeSpeaking();
  };

  const cancelRecording = () => {
    recorderRef.current?.cancel();
    recorderRef.current = null;
    displayLevelRef.current = 0;
    resetLevel();
    setMic('idle');
  };

  const startRecording = async () => {
    setMicError(null);
    queue.stop();
    closeSpeaking();
    queue.start();

    displayLevelRef.current = 0;
    resetLevel(); // leerer Verlauf zum Aufnahme-Start
    const rec = new VoiceRecorder({
      onLevel: (raw) => {
        displayLevelRef.current = emaLevel(displayLevelRef.current, gammaLevel(raw));
        pushLevel(displayLevelRef.current);
      },
    });
    recorderRef.current = rec;
    setMic('listening');
    try {
      await rec.start();
    } catch (err) {
      recorderRef.current = null;
      displayLevelRef.current = 0;
      resetLevel();
      setMic('idle');
      setMicError(humanMicError(err));
    }
  };

  const stopAndSend = async () => {
    const rec = recorderRef.current;
    if (!rec || micStateRef.current !== 'listening') return;
    recorderRef.current = null;
    displayLevelRef.current = 0;
    resetLevel();
    setMic('transcribing');
    queue.start();

    let blob: Blob;
    try {
      blob = await rec.stop();
    } catch (err) {
      setMic('idle');
      setMicError(humanMicError(err));
      return;
    }
    if (blob.size === 0) {
      setMic('idle');
      setMicError('Ich habe nichts gehört — halt das Mikro gedrückt und sprich.');
      return;
    }
    await runVoiceTurn(await voiceTurnUploadBlob(blob));
  };

  /** Lädt die Aufnahme hoch und rendert den SSE-Strom (Transkript → Antwort). */
  const runVoiceTurn = async (blob: Blob) => {
    setBusy(true);
    setMic('responding');
    if (voiceOn) playTurnEarcon();
    armTurnFeedback();
    setTurns((t) => [
      ...t,
      { role: 'user', text: '', pending: true },
      { role: 'assistant', text: '', meta: '…', anatomy: emptyAnatomy('voice') },
    ]);

    const ctrl = new AbortController();
    abortRef.current = ctrl;

    const patchUser = (fn: (prev: Turn) => Turn) =>
      setTurns((t) => {
        const next = [...t];
        next[next.length - 2] = fn(next[next.length - 2]); // Du-Blase = vorletzte
        return next;
      });

    const onEvent = (ev: ChatEvent) => {
      switch (ev.event) {
        case 'speaker': {
          const recognized: RecognizedSpeaker = {
            name: ev.recognizedSpeaker,
            confidence: ev.confidence,
            isGuest: ev.isGuest,
          };
          patchUser((p) => ({ ...p, speaker: recognized }));
          const recognizedName = !ev.isGuest && ev.recognizedSpeaker ? ev.recognizedSpeaker : '';
          setActiveSpeakerId(recognizedName || GUEST_SPEAKER_ID);
          setActiveSpeakerName(recognizedName);
          patchAssistant((p) => withAnatomy(p, ev));
          break;
        }
        case 'step':
          if (ev.kind === 'transcript') {
            patchUser((p) => ({ ...p, text: ev.message, pending: false }));
            patchAssistant((p) => withAnatomy(p, ev));
          } else if (ev.message) setStepLabel(stepLabelText(ev.message));
          break;
        case 'start':
          patchAssistant((p) =>
            withAnatomy({ ...p, meta: `${ev.provider} · ${ev.model} · ${ev.category}` }, ev),
          );
          break;
        case 'delta':
          markFirstResponse();
          patchAssistant((p) => withAnatomy({ ...p, text: p.text + ev.text }, ev));
          break;
        case 'tts_audio_start':
          openSpeaking();
          patchAssistant((p) => withAnatomy(p, ev));
          break;
        case 'audio':
          markFirstResponse();
          queue.enqueue(ev.data, ev.seq);
          break;
        case 'tts_audio_end':
          closeSpeaking();
          break;
        case 'error':
          patchAssistant((p) =>
            withAnatomy(
              { ...p, error: true, meta: `Fehler · ${ev.stage ?? 'STT'}`, text: p.text || ev.message },
              ev,
            ),
          );
          break;
        case 'done':
          patchAssistant((p) => ({
            ...p,
            meta: p.error ? p.meta : (ev.provider || p.meta),
            // Additives Wire-Feld (s. Turn.sources-KDoc): nur bei echten Citations
            // gesetzt, sonst bleibt p.sources unverändert (i.d.R. undefined).
            sources: ev.escalationSources ?? p.sources,
          }));
          // Turn-Ende schließt „spricht" MIT — auch wenn `tts_audio_end` (aus
          // welchem Grund auch immer) ausblieb (Andi-Befund 20.07, Rechenaufgabe).
          closeSpeaking();
          break;
        default:
          break;
      }
    };

    try {
      await streamVoice(blob, { onEvent, signal: ctrl.signal, speak: true, persona, language, voice });
    } catch (err) {
      if (!(err instanceof DOMException && err.name === 'AbortError')) {
        const message = err instanceof Error ? err.message : String(err);
        patchAssistant((p) => ({ ...p, error: true, meta: 'Verbindung', text: p.text || message }));
      }
    } finally {
      patchUser((p) => (p.pending ? { ...p, pending: false, text: p.text || '…' } : p));
      setBusy(false);
      setMic('idle');
      clearTurnFeedback();
      // Letztes Netz: der Stream ist jetzt WIRKLICH zu Ende (Erfolg, Fehler
      // oder Abbruch) — „spricht" darf danach nie mehr offen bleiben, egal was
      // vorher an Events (nicht) ankam.
      closeSpeaking();
      abortRef.current = null;
    }
  };

  return {
    turns,
    busy,
    activeSpeakerId,
    activeSpeakerName,
    voiceOn,
    speaking,
    micState,
    micStateRef,
    micError,
    recSecs,
    stepLabel,
    slow,
    send,
    startRecording,
    stopAndSend,
    cancelRecording,
    bargeIn,
    toggleVoice,
    setLevelSink,
  };
}
