import { useEffect, useRef } from 'react';
import { VoiceStar, type StarState } from './VoiceStar';
import { ThinkingDots } from './ChatView';
import { StreamingBody } from './StreamingText';
import { MicGlyph } from './icons';
import { fmtTime, type MicState, type VoiceChatSession } from '../hooks/useVoiceChatSession';
import { useUiStrings } from '../i18n';
import type { ChatStrings, VoiceOrbStrings } from '../i18n/types';
import '../styles/voicebar.css';

/**
 * **VoiceOrb** — der stimmig integrierte Sprach-Orb im Home-Screen (Andi-
 * Auftrag 19.07): derselbe atmende Licht-Orb wie die Compose-Bar ({@link
 * VoiceStar}), nur prominent groß, plus eine ruhige Karte mit dem letzten Turn.
 *
 * Zustände hängen STRIKT an echten Signalen der geteilten {@link
 * VoiceChatSession} — dieselbe Maschine, die auch die Compose-Bar treibt:
 *   idle      → `micState==='idle' && !speaking` — dezentes Atmen (Idle-Glimmen).
 *   listening → `micState==='listening'` — bloomt auf DEINEN echten Mikro-Pegel.
 *   thinking  → `micState` ist `transcribing`/`responding`, aber noch KEIN Audio
 *               spielt — Schimmern, solange die Pipeline (STT/LLM) wirklich läuft.
 *   speaking  → `speaking===true` — bloomt auf HOSHIS echten TTS-Ausgabepegel,
 *               NUR während tatsächlich Audio läuft (kein optimistisches „spricht").
 *
 * Tap nutzt exakt {@link VoiceChatSession.startRecording}/`stopAndSend`/
 * `bargeIn` — denselben Browser-Voice-Pfad wie der Mikro-Knopf im Chat-Reiter,
 * kein neuer Endpoint. Der ECHTE Pegel kommt über {@link
 * VoiceChatSession.setLevelSink} — derselbe imperative Pfad wie die Waveform
 * in ChatView (Wiederverwendung statt Duplikat), hier auf den `--lvl`-Custom-
 * Property des großen Orbs geschrieben statt auf ein Canvas.
 *
 * Die Karte zeigt NUR den letzten Turn (Transkript + Antwort) — kein zweiter
 * Verlauf: die volle Historie bleibt im Chat-Reiter, gespeist aus DERSELBEN
 * `session.turns` (App.tsx reicht eine einzige Session an beide Ansichten).
 */

function orbState(micState: MicState, speaking: boolean): StarState {
  if (speaking) return 'speaking';
  if (micState === 'listening') return 'listening';
  if (micState === 'transcribing' || micState === 'responding') return 'thinking';
  return 'idle';
}

/**
 * Sichtbarer Hinweistext unter dem Orb — dieselben Worte wie die Compose-Bar
 * (Andi-Sweep 24.07, README-Screenshot-Befund: „Tippen zum Sprechen" war
 * hartkodiert Deutsch, egal welche UI-Sprache aktiv war; jetzt aus dem
 * Katalog — `chat.speaking`/`chat.transcribing`/`chat.thinking` sind BYTE-GLEICH
 * mit den vorherigen Literalen, `orb.idleHint`/`orb.listening` sind neu).
 */
function orbHint(session: VoiceChatSession, chat: ChatStrings, orb: VoiceOrbStrings): string {
  const { micState, speaking, stepLabel, recSecs } = session;
  if (speaking) return chat.speaking;
  if (micState === 'listening') return orb.listening(fmtTime(recSecs));
  if (micState === 'transcribing') return stepLabel ?? chat.transcribing;
  if (micState === 'responding') return stepLabel ?? chat.thinking;
  return orb.idleHint;
}

export function VoiceOrb({ session }: { session: VoiceChatSession }) {
  const { turns, busy, micState, speaking, micError, startRecording, stopAndSend, bargeIn, setLevelSink } =
    session;
  const { chat, voiceOrb: orb } = useUiStrings();

  const orbRef = useRef<HTMLSpanElement>(null);

  // Derselbe imperative Pegel-Pfad wie ChatViews VoiceWaveform (60×/s, kein
  // Re-render) — hier auf `--lvl` des großen Orbs geschrieben statt aufs
  // Waveform-Canvas. Registriert bei jedem Mount, abgemeldet beim Unmount
  // (die ChatView-Waveform registriert währenddessen ihren eigenen Sink).
  useEffect(() => {
    setLevelSink({
      push: (lvl) => {
        const el = orbRef.current;
        if (el) el.style.setProperty('--lvl', String(Math.max(0, Math.min(1, lvl))));
      },
      reset: () => orbRef.current?.style.setProperty('--lvl', '0'),
    });
    return () => setLevelSink(null);
  }, [setLevelSink]);

  const state = orbState(micState, speaking);
  const hint = orbHint(session, chat, orb);
  // Deckt GENAU die Disabled-Logik von ChatViews Mikro-Knopf (`busy ||
  // micState === 'transcribing'`) plus dessen separaten WaveTap ab: läuft
  // gerade Audio (`speaking`, egal ob der Turn per Stimme oder Tipp-Text kam),
  // bleibt der Orb IMMER tippbar (Barge-in) — sonst gesperrt, solange irgendein
  // Turn arbeitet (Text ODER Sprache) oder gerade transkribiert wird. Esc
  // bricht zusätzlich session-weit ab (siehe useVoiceChatSession).
  const disabled = !speaking && (busy || micState === 'transcribing');

  const onTap = () => {
    if (disabled) return;
    if (speaking) {
      bargeIn();
      return;
    }
    if (micState === 'listening') {
      void stopAndSend();
      return;
    }
    if (micState === 'idle') void startRecording();
  };

  // Ein ehrliches Label pro echtem Zustand — dieselben Worte wie `hint`, nur
  // als vollständiger Satz fürs Screenreader-/Titel-Attribut.
  const tapLabel = speaking
    ? orb.speakingTapLabel
    : micState === 'listening'
      ? orb.listeningTapLabel
      : micState === 'idle'
        ? orb.idleTapLabel
        : hint; // transcribing/denkt nach — gesperrt, der Hinweistext erklärt warum

  // Der letzte Turn (egal ob per Stimme oder getippt entstanden) — kein
  // zweiter Verlauf, nur ein Fenster auf `session.turns`. send()/runVoiceTurn
  // pushen Paare atomar, darum ist das Paar entweder vollständig da oder
  // (frischer Start) noch gar nicht.
  const lastAssistant = turns.length > 0 ? turns[turns.length - 1] : null;
  const lastUser = turns.length > 1 ? turns[turns.length - 2] : null;
  const showCard = !!lastAssistant && lastAssistant.role === 'assistant' && lastUser?.role === 'user';
  const streamingAnswer = busy && showCard && !lastAssistant?.error;

  return (
    <section className="voiceorb" aria-label={orb.sectionAria}>
      <button
        type="button"
        className="voiceorb__tap"
        onClick={onTap}
        disabled={disabled}
        aria-pressed={state === 'listening'}
        aria-label={tapLabel}
        title={tapLabel}
      >
        <VoiceStar ref={orbRef} state={state} level={0} label={hint} />
      </button>
      <p className="voiceorb__hint" aria-hidden="true">
        {hint}
      </p>

      {micError && (
        <div className="chat__micerror voiceorb__error" role="status">
          <MicGlyph /> {micError}
        </div>
      )}

      {showCard && lastUser && lastAssistant && (
        <div className="voiceorb__card" aria-live="polite">
          <div className="voiceorb__row voiceorb__row--user">
            {lastUser.pending ? <ThinkingDots /> : lastUser.text || '…'}
          </div>
          <div className="voiceorb__row voiceorb__row--assistant">
            {streamingAnswer ? <StreamingBody text={lastAssistant.text} /> : lastAssistant.text}
          </div>
        </div>
      )}
    </section>
  );
}
