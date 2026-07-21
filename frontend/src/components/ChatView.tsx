import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import type { Language } from '../api/types';
import { SpeakerChip } from './SpeakerChip';
import type { SettingsAnchorId, SettingsCategoryId } from './SettingsPanel';
import type { Persona } from '../hooks/useSettings';
import { scheduledLine, useScheduledItems, type ScheduledItem } from '../hooks/useScheduledItems';
import { ScheduledPanel } from './ScheduledPanel';
import {
  useVoiceChatSession,
  composeSlot,
  fmtTime,
  stepLabelText,
  SLOW_TURN_MS,
  SLOW_TURN_TEXT,
  GUEST_SPEAKER_ID,
  HISTORY_MAX_MESSAGES,
  buildHistory,
  type VoiceChatSession,
  type MicState,
  type ComposeSlot,
  type Turn,
} from '../hooks/useVoiceChatSession';
import { StreamingBody } from './StreamingText';
import { SendButton } from './SendButton';
// §4 Turn-Anatomie: Denk-Stufen-Zeile (echte Häkchen) + Chips unter der Antwort.
import { TurnChips, TurnStagesRow } from './TurnAnatomy';
import { VoiceWaveform, type VoiceWaveformHandle } from './VoiceWaveform';
import { dayPartForHour } from './greeting';
// Schlichte SVG-Glyphs (0.5-Lehre: „SVG-Icons statt Emojis") — Mic/Speaker
// sizen in der Compose-Bar weiter über die vc-ico-Klasse (voicebar.css).
import { GearGlyph, InfoGlyph, MicGlyph, SpeakerGlyph } from './icons';
import { useUiStrings } from '../i18n';
// Visuals der Sprach-Eingabe (Mic-/Speaker-Icons + scrollende Waveform). Global
// gebündelt — deckt die vc-*-Klassen der Waveform UND der Compose-Bar-Buttons ab.
import '../styles/voicebar.css';

// Re-exports: die reinen Bausteine leben seit dem Voice-Orb-Auftrag (Andi
// 19.07) in hooks/useVoiceChatSession.ts (geteilt mit dem Home-Orb) — dieser
// Re-Export hält bestehende Test-Importpfade (`from '../components/ChatView'`)
// unverändert, byte-gleiches Verhalten.
export {
  buildHistory,
  HISTORY_MAX_MESSAGES,
  GUEST_SPEAKER_ID,
  composeSlot,
  stepLabelText,
  SLOW_TURN_MS,
  SLOW_TURN_TEXT,
  useVoiceChatSession,
};
export type { MicState, ComposeSlot };

/**
 * Drei wandernde Punkte — geteilt von Compose-Bar (STT/Denken) und der
 * wartenden Du-Blase. Bewusst OHNE Icon: die Position sagt schon, wer dran ist
 * (rechts = du, links = Hoshi) — ein Mic-Glyph wäre ein Zustands-Duplikat.
 */
export function ThinkingDots() {
  return (
    <span className="thinking" aria-hidden="true">
      <span className="thinking__dot" />
      <span className="thinking__dot" />
      <span className="thinking__dot" />
    </span>
  );
}

/**
 * Anzeige-Label einer Quellen-Referenz (Quellen-Struktur-Auftrag 2026-07-21):
 * `title`, wenn vorhanden — sonst der Host aus `url` (nie die volle, oft lange
 * URL als Fließtext). Unparsbare URLs (sollte nie passieren, das BE liefert
 * echte http(s)-URLs) fallen ehrlich auf die URL selbst zurück.
 */
function sourceLabel(s: { title?: string | null; url: string }): string {
  const title = s.title?.trim();
  if (title) return title;
  try {
    return new URL(s.url).host;
  } catch {
    return s.url;
  }
}

/** Unter dieser Haltedauer (ms) zählt ein Druck als Tap → Toggle-Aufnahme. */
const HOLD_THRESHOLD_MS = 300;

/** Max-Höhe der Auto-Grow-Textarea (~6 Zeilen) — danach scrollt sie intern. */
const MAX_COMPOSE_HEIGHT = 148;

/** Toleranz (px) fürs „steht am Ende" — Subpixel-Rundung zählt noch als unten. */
const STICK_BOTTOM_TOLERANCE_PX = 48;

/** Empty-State: 3 Vorschlags-Chips → klicken füllt + sendet die Composer. */
/**
 * **WaveTap** — macht die Ausgabe-Waveform antippbar (Barge-in per Tap), ohne
 * die VoiceWaveform selbst anzufassen: ein role="button"-Wrapper, der als
 * Flex-Kind der Compose-Bar exakt den Platz der Waveform einnimmt (inline,
 * damit kein CSS anderer Lanes nötig ist). Enter/Space wirken wie der Tap.
 */
export function WaveTap({ onTap, children }: { onTap: () => void; children: ReactNode }) {
  const { chat } = useUiStrings();
  return (
    <div
      role="button"
      tabIndex={0}
      className="compose__wave-tap"
      onClick={onTap}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onTap();
        }
      }}
      aria-label={chat.waveTap}
      title={chat.waveTap}
      style={{ display: 'flex', flex: '1 1 auto', minWidth: 0, cursor: 'pointer' }}
    >
      {children}
    </div>
  );
}

/**
 * **ScheduledLine** — die ruhige Timer-Zeile direkt über der Compose-Bar
 * (Cowork-Befund: laufende Timer/Wecker waren unsichtbar — der Voice-PE-Fehler).
 * Reine Präsentation über {@link scheduledLine}: keine Items → rendert NICHTS
 * (kein Lärm); sonst dezent (`--text-3`), kein Alarm-Look. Kind-ehrlich:
 * TIMER→„Timer", ALARM→„Wecker". Der Countdown kommt clientseitig aus
 * `dueAtEpochMs` gegen `nowMs` (Minuten-Tick des Hooks).
 */
export function ScheduledLine({ items, nowMs }: { items: ScheduledItem[]; nowMs: number }) {
  const line = scheduledLine(items, nowMs);
  if (!line) return null;
  return (
    <p className="chat__scheduled" role="status">
      {line}
    </p>
  );
}

/**
 * EIN Bereich der Shell (nicht die ganze App): ein Chat-Turn LIVE gegen
 * `POST /api/v1/chat/stream` (Text) bzw. `POST /api/v1/voice` (Sprache). Rendert
 * den SSE-Strom (start/delta/done) inkrementell und zeigt `error`-Events sichtbar
 * (never-silent), statt sie zu verschlucken.
 *
 * Voice-Phase 1: ein Opt-in „Sprich-Modus" für Text-Turns (`speak:true` → TTS).
 * Voice-Phase 2: Push-to-Talk — das Mikro-Knopf nimmt auf, lädt den Blob hoch,
 * zeigt das Transkript als Du-Blase und spielt (wie der Sprich-Modus) die
 * gesprochene Antwort über die AudioQueue. Esc/erneuter Mikro-Druck während
 * der Antwort bricht den Turn ab (Barge-in).
 *
 * Der eigentliche Zustand (Verlauf, Aufnahme, Wiedergabe) lebt seit dem
 * Voice-Orb-Auftrag (Andi 19.07) in {@link useVoiceChatSession} — geteilt mit
 * dem Home-Orb, damit ein per Stimme gestarteter Turn im SELBEN Verlauf landet,
 * egal auf welchem Reiter er endet. {@link ChatViewBody} ist die reine Ansicht
 * darauf (nimmt eine `session` entgegen); {@link ChatView} bleibt der bequeme
 * Standalone-Einstieg (Tests/Storybook) — baut sich seine EIGENE Session, exakt
 * wie vor dem Auftrag, byte-gleiches Verhalten.
 */
export interface ChatViewProps {
  persona: Persona;
  language: Language;
  /** OpenAI-Voice-Name (Cloud-TTS-Stimme) — fließt wie persona in beide Requests. */
  voice: string;
  /**
   * Deep-Link in den Settings-Drawer (App.tsx `openSettings`, Cowork-Spec
   * 03-settings-einbettung.md V1). Der kontextuelle Zahnrad-Anker am „Wer
   * sprach"-Chip springt zu Gedächtnis & Privatsphäre/Sprecher. Optional:
   * fehlt es (z. B. in Tests), rendert der Chip ohne Zahnrad — kein Bruch,
   * nur ein fehlender Komfort-Anker.
   */
  onOpenSettings?: (category: SettingsCategoryId, anchor?: SettingsAnchorId) => void;
}

/** Standalone-Einstieg: baut die eigene Session (Tests/Storybook, App.tsx nutzt {@link ChatViewBody}). */
export function ChatView({ persona, language, voice, onOpenSettings }: ChatViewProps) {
  const session = useVoiceChatSession({ persona, language, voice });
  return <ChatViewBody session={session} onOpenSettings={onOpenSettings} />;
}

export interface ChatViewBodyProps {
  /** Die geteilte (oder standalone) Session aus {@link useVoiceChatSession}. */
  session: VoiceChatSession;
  onOpenSettings?: (category: SettingsCategoryId, anchor?: SettingsAnchorId) => void;
}

/** Die reine Ansicht auf eine {@link VoiceChatSession} — kein eigener Netz-/Audio-Zustand. */
export function ChatViewBody({ session, onOpenSettings }: ChatViewBodyProps) {
  const { chat, voiceChat } = useUiStrings();
  const {
    turns,
    busy,
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
  } = session;

  const [input, setInput] = useState('');
  // Laufende Timer/Wecker sichtbar machen (~15s-Poll + Minuten-Tick) — speist das
  // aufklappbare ScheduledPanel direkt über der Compose-Bar (Anzeige + Löschen).
  const {
    items: scheduledItems,
    nowMs: scheduledNow,
    remove: removeScheduled,
    removeAll: removeAllScheduled,
  } = useScheduledItems();
  const logRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);

  // Stick-to-Bottom: wer unten steht, bleibt unten (Toleranz gegen Subpixel-
  // Rundung); wer bewusst hochgescrollt hat, wird NICHT zwangsgescrollt. Ref
  // statt State — nur beim nächsten Auto-Scroll gelesen, kein Extra-Render.
  // Start bei true: ein frischer Chat steht am Ende.
  const stickToBottomRef = useRef(true);

  // Waveform-Handle für den IMPERATIVEN Pegel-Pfad: die Session schiebt Samples
  // per {@link setLevelSink} direkt in die Balken der Waveform (scaleY, kein
  // Re-render) — registriert bei jedem Mount, abgemeldet beim Unmount (der
  // Home-Orb registriert währenddessen seinen eigenen Sink).
  const waveformRef = useRef<VoiceWaveformHandle>(null);
  useEffect(() => {
    setLevelSink({
      push: (lvl) => waveformRef.current?.push(lvl),
      reset: () => waveformRef.current?.reset(),
    });
    return () => setLevelSink(null);
  }, [setLevelSink]);

  // Pointer-Gesten (Halten vs. Tap) sind reine UI-Interpretation des Mikro-
  // Knopfs — bleiben lokal in der Ansicht, nicht in der Session.
  const pointerActiveRef = useRef(false);
  const holdStartRef = useRef(0);

  // Auto-Scroll: bei jedem neuen Turn, jedem Streaming-Delta UND dem Turn-
  // Abschluss (busy → false) ans Ende ziehen — NUR, solange der User dort auch
  // steht (stickToBottomRef). Andi-Befund 20.07: die Status-Pillen (TurnChips)
  // rendern erst NACH busy→false, also einen Extra-Tick nach dem letzten
  // `turns`-Update auf „done" (siehe useVoiceChatSession: setBusy(false) im
  // `finally`, getrennt vom patchAssistant des done-Events) — ein Effekt, der
  // NUR an `turns` hängt, verpasst genau diesen Render und die Pillen wachsen
  // unten aus dem sichtbaren Bereich heraus. `busy` als zweite Dependency
  // fängt exakt diesen Render ab.
  useEffect(() => {
    const el = logRef.current;
    if (el && stickToBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [turns, busy]);

  /** Scroll-Handler des Log-Containers: pflegt stickToBottomRef (Toleranz s.o.). */
  const onLogScroll = () => {
    const el = logRef.current;
    if (!el) return;
    stickToBottomRef.current =
      el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_BOTTOM_TOLERANCE_PX;
  };

  // Auto-Grow: die Textarea wächst von 1 → ~6 Zeilen mit dem Inhalt (scrollHeight).
  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${Math.min(ta.scrollHeight, MAX_COMPOSE_HEIGHT)}px`;
  }, [input]);

  // Fokus-Rückgabe ins Eingabefeld, sobald die Bar wieder frei ist.
  useEffect(() => {
    if (!busy && micState === 'idle' && !speaking) taRef.current?.focus();
  }, [busy, micState, speaking]);

  const onMicPointerDown = (e: ReactPointerEvent) => {
    e.preventDefault();
    const s = micStateRef.current;
    if (s === 'responding') {
      bargeIn();
      return;
    }
    if (s === 'transcribing') return;
    if (s === 'listening') {
      if (!pointerActiveRef.current) void stopAndSend();
      return;
    }
    pointerActiveRef.current = true;
    holdStartRef.current = Date.now();
    void startRecording();
  };

  const onMicPointerEnd = () => {
    if (!pointerActiveRef.current) return;
    pointerActiveRef.current = false;
    if (micStateRef.current !== 'listening') return;
    const held = Date.now() - holdStartRef.current;
    if (held >= HOLD_THRESHOLD_MS) void stopAndSend();
  };

  // Enter sendet, Shift+Enter macht einen Zeilenumbruch (Auto-Grow).
  const onComposeKeyDown = (e: ReactKeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void submit();
    }
  };

  const submit = async (override?: string) => {
    const text = override ?? input;
    if (!text.trim() || busy || micState !== 'idle') return;
    setInput('');
    await send(text);
  };

  const micBusy = micState !== 'idle';
  const micTitle: Record<MicState, string> = {
    idle: chat.micIdle,
    listening: chat.micListening,
    transcribing: chat.micTranscribing,
    responding: chat.micResponding,
  };

  const slot = composeSlot(micState, speaking);
  const recording = slot === 'wave-in';
  const showWaveOut = slot === 'wave-out';
  const processing = slot === 'processing';

  const dayPart = dayPartForHour(new Date().getHours());
  const empty = turns.length === 0;
  const canSend = !busy && !micBusy && input.trim().length > 0;

  const ttsToggle = (
    <button
      type="button"
      className={`vc-tts ${voiceOn ? 'is-on' : ''}`}
      onClick={toggleVoice}
      aria-pressed={voiceOn}
      title={voiceOn ? chat.ttsOn : chat.ttsOff}
    >
      <SpeakerGlyph on={voiceOn} className="vc-ico" />
    </button>
  );

  return (
    <section className="chat">
      <div
        className={`chat__log ${empty ? 'chat__log--empty' : ''}`}
        aria-live="polite"
        ref={logRef}
        onScroll={onLogScroll}
      >
        {empty ? (
          <div className="chat__welcome">
            <h2 className="chat__greeting">{chat.greeting(dayPart)}</h2>
            <div className="chat__chips">
              {chat.suggestions.map((s) => (
                <button
                  key={s}
                  type="button"
                  className="chip"
                  onClick={() => void submit(s)}
                  disabled={busy || micBusy}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        ) : (
          turns.map((turn: Turn, i) => {
            const isLast = i === turns.length - 1;
            const isSpeakingBubble = speaking && turn.role === 'assistant' && isLast;
            const streaming = busy && turn.role === 'assistant' && isLast && !turn.error;
            return (
              <div
                key={i}
                className={`msg msg--${turn.role} ${turn.error ? 'msg--error' : ''} ${
                  isSpeakingBubble ? 'is-speaking' : ''
                }`}
              >
                {turn.speaker && (
                  <div className="msg__speakerrow">
                    <SpeakerChip speaker={turn.speaker} />
                    {onOpenSettings && (
                      <button
                        type="button"
                        className="ctxgear"
                        onClick={() => onOpenSettings('gedaechtnis-privatsphaere', 'sprecher')}
                        aria-label={chat.speakerSettingsAria}
                        title={chat.manageSpeakers}
                      >
                        <GearGlyph className="ctxgear__icon" />
                      </button>
                    )}
                  </div>
                )}
                {turn.role === 'assistant' && turn.anatomy && (
                  <TurnStagesRow anatomy={turn.anatomy} />
                )}
                {turn.role === 'user' && turn.pending ? (
                  <div
                    className="msg__body"
                    role="status"
                    aria-label={chat.recordingUnderstood}
                  >
                    <ThinkingDots />
                  </div>
                ) : (
                  <div className="msg__body">
                    {streaming ? <StreamingBody text={turn.text} /> : turn.text}
                  </div>
                )}
                {turn.role === 'assistant' && turn.anatomy && !streaming && !turn.error && (
                  <TurnChips anatomy={turn.anatomy} />
                )}
                {/* Quellen-Icon (Quellen-Struktur-Auftrag 2026-07-21): NUR bei
                    echten strukturierten Quellen (url_citation-Treffern) — ein
                    reiner Modellwissen-Fallback ohne Beleg zeigt ehrlich KEIN
                    Icon (Vera-Regel). */}
                {turn.role === 'assistant' &&
                  !streaming &&
                  !turn.error &&
                  turn.sources &&
                  turn.sources.length > 0 && (
                    <details className="msg__sources">
                      <summary className="msg__sources__summary" title={chat.sourcesTitle}>
                        <InfoGlyph className="msg__sources__icon" />
                        {chat.sources}
                      </summary>
                      <ul className="msg__sources__list">
                        {turn.sources.map((s, idx) => (
                          <li key={idx}>
                            <a href={s.url} target="_blank" rel="noopener">
                              {sourceLabel(s)}
                            </a>
                          </li>
                        ))}
                      </ul>
                    </details>
                  )}
                {turn.meta && <div className="msg__meta">{turn.meta}</div>}
              </div>
            );
          })
        )}
      </div>

      {micError && (
        <div className="chat__micerror" role="status">
          <MicGlyph /> {micError}
        </div>
      )}

      <ScheduledPanel
        items={scheduledItems}
        nowMs={scheduledNow}
        onDelete={removeScheduled}
        onDeleteAll={removeAllScheduled}
      />

      <form
        className="compose"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <div className={`compose__bar ${recording ? 'is-recording' : ''}`}>
          <button
            type="button"
            className={`vc-mic ${recording ? 'is-recording' : ''} ${micBusy ? 'is-busy' : ''}`}
            onPointerDown={onMicPointerDown}
            onPointerUp={onMicPointerEnd}
            onPointerLeave={onMicPointerEnd}
            onPointerCancel={onMicPointerEnd}
            disabled={busy || micState === 'transcribing'}
            aria-pressed={recording}
            aria-label={chat.micAria}
            title={micTitle[micState]}
          >
            <MicGlyph className="vc-ico" />
          </button>

          {recording ? (
            <>
              <VoiceWaveform ref={waveformRef} />
              <span className="compose__rectime" aria-hidden="true">
                {fmtTime(recSecs)}
              </span>
              <button
                type="button"
                className="compose__cancel"
                onClick={cancelRecording}
                aria-label={chat.discardRecording}
                title={chat.discard}
              >
                ✕
              </button>
            </>
          ) : showWaveOut ? (
            <>
              <WaveTap onTap={bargeIn}>
                <VoiceWaveform ref={waveformRef} />
              </WaveTap>
              <span className="compose__proc-label" role="status">
                {chat.speaking}
              </span>
              {ttsToggle}
            </>
          ) : processing ? (
            <>
              <div className="compose__proc" role="status" aria-label={chat.processingRecording}>
                <ThinkingDots />
                <span className="compose__proc-label">
                  {stepLabel ?? (micState === 'transcribing' ? chat.transcribing : chat.thinking)}
                </span>
              </div>
              {ttsToggle}
            </>
          ) : (
            <>
              <textarea
                ref={taRef}
                className="compose__input"
                placeholder={chat.placeholder}
                value={input}
                rows={1}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={onComposeKeyDown}
                disabled={busy || micBusy}
                autoFocus
              />
              {ttsToggle}
              <SendButton disabled={!canSend} busy={busy} />
            </>
          )}
        </div>

        {slow && (
          <p className="compose__hint compose__hint--slow" role="status">
            {voiceChat.slowTurn}
          </p>
        )}
      </form>
    </section>
  );
}
