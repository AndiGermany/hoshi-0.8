import { API_BASE, SPEAKER_ID, TOKEN } from './config';
import { SseDecoder, parseChatEvent } from './sse';
import { getDeviceId } from './device';
import { loadSettings } from '../hooks/useSettings';
import type { ChatEvent, ChatMessage, Language } from './types';

export interface StreamChatOptions {
  language?: Language;
  signal?: AbortSignal;
  onEvent: (event: ChatEvent) => void;
  /**
   * Bisherige abgeschlossene Turns (ältester zuerst) fürs Gesprächsgedächtnis.
   * Default leer → Body byte-identisch zum bisherigen Verhalten (backward-
   * compatible). Der Aufrufer (ChatView) cappt schon FE-seitig; das Backend
   * fenstert die Liste nochmal (HOSHI_MEMORY_WINDOW_TURNS).
   */
  history?: ChatMessage[];
  /**
   * Opt-in Sprich-Modus. true → Backend synthetisiert TTS und streamt `audio`-
   * Events. Default false → reiner Text-Turn (kein TTS, keine Kosten); der Body
   * ist dann byte-identisch zum bisherigen Verhalten.
   */
  speak?: boolean;
  /** Persona-Wahl (Gerüst). Default: aus den persistierten Settings. */
  persona?: string;
  /**
   * Sprecher-Identität für `speakerContext.speakerId` (Entity-/Episodic-Memory).
   * Default {@link SPEAKER_ID} (= „andi") → Alt-Body byte-identisch. Die ChatView
   * reicht hier den zuletzt per Stimme ERKANNTEN Sprecher durch (S3), sodass
   * Folge-Tipp-Turns aufs richtige Gedächtnis laufen — Gast ⇒ „gast" (kein Load/Write).
   */
  speakerId?: string;
  /**
   * Anzeigename des zuletzt per Stimme ERKANNTEN Sprechers (S3, `ChatView.activeSpeakerName`
   * aus dem `speaker`-SSE-Event). Default undefined/leer → der Body lässt das Feld
   * WEG, dann greift exakt der heutige Backend-Default (`SpeakerContext.displayName =
   * "Unbekannt"`, kollabiert im Prompt auf „du") — byte-identisch zum bisherigen
   * Verhalten. Nur ein sicher gebundener Name (kein Gast/keine Voice-Erkennung diese
   * Session) füllt es — die erkannte Person wird so auch bei getippten Folge-Turns
   * beim Namen genannt.
   */
  displayName?: string;
  /**
   * OpenAI-Voice-Name (Cloud-TTS-Stimme, Backlog #6). Default: aus den
   * persistierten Settings. Das Backend prüft gegen seine Whitelist —
   * unbekannt fällt still auf den Boot-Default (coral) zurück.
   */
  voice?: string;
  /**
   * Stabile Geräte-Id (`ChatRequest.deviceId`, Jackson-Key „deviceId"). Default:
   * {@link getDeviceId} — die pro-Browser-UUID aus localStorage. Fließt durch bis
   * `ScheduledItem.origin`, damit ein gefeuerter Wecker sein Ursprungs-Gerät kennt
   * (ursprungs-gebundenes Bimmeln + Eskalation). Alt-Backends ignorieren das Feld.
   */
  deviceId?: string;
}

/**
 * LIVE-Aufruf von `POST /api/v1/chat/stream` und Rendern des SSE-ChatEvent-Stroms.
 *
 * - Token geht als `X-Hoshi-Token`-Header (aus VITE_TOKEN). 401 wird ehrlich als
 *   Auth-Wand durchgereicht statt verschluckt.
 * - `speak:false` (Default) → reiner Text-Turn; nur bei `speak:true` spielt das
 *   FE Audio (siehe ChatView + src/audio/playback.ts).
 */
export async function streamChat(text: string, opts: StreamChatOptions): Promise<void> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;

  // Sprache/Persona: explizite opts schlagen die persistierten Settings (die das
  // Einstellungs-Panel schreibt). So fließt die Panel-Wahl auch dann mit, wenn der
  // Aufrufer (ChatView) keine Sprache durchreicht.
  const settings = loadSettings();

  // Sprach-Wahl → Wire: `languagePolicy` trägt die Wahl (AUTO/DE/EN), das Backend
  // erkennt bei AUTO pro Eingabe; `language` bleibt KONKRET (DE/EN) als Legacy-
  // Fallback — bei 'auto' fällt es auf DE, sonst auf die gewählte Sprache.
  const langChoice = opts.language ?? settings.language;
  const languagePolicy = langChoice.toUpperCase();
  const concreteLanguage = (langChoice === 'auto' ? 'de' : langChoice).toUpperCase();

  const res = await fetch(`${API_BASE}/api/v1/chat/stream`, {
    method: 'POST',
    headers,
    // Wire-Vertrag: das Backend-Enum Language ist GROSS (DE/EN) — Jackson
    // deserialisiert case-sensitiv, lowercase 'de' → HTTP 400. Darum hochstellen.
    // speakerContext.speakerId: ohne ihn legt das Backend für Browser-Turns
    // keine Entity-/Episodic-Memory an (Default „andi", via VITE_SPEAKER_ID).
    // persona: additiv mitgesendet (Persona-Gerüst). Spring Boot ignoriert unbekannte
    // Felder by default (FAIL_ON_UNKNOWN_PROPERTIES aus) → schadet nicht.
    // TODO: Personas aus Research füllen.
    body: JSON.stringify({
      text,
      // history: die bisherigen abgeschlossenen Turns (ChatMessage[], ältester
      // zuerst) → Gesprächsgedächtnis. Default []: Alt-Body byte-identisch, wenn
      // der Aufrufer keinen Verlauf reicht. Das Backend fenstert nochmal.
      history: opts.history ?? [],
      speak: opts.speak ?? false,
      languagePolicy,
      language: concreteLanguage,
      persona: opts.persona ?? settings.persona,
      // voice: die gewählte Cloud-Stimme (ChatRequest.voice → OpenAI-Adapter-
      // Whitelist). Alt-Backends ignorieren das Feld folgenlos.
      voice: opts.voice ?? settings.voice,
      // deviceId: die stabile Browser-Id (ChatRequest.deviceId) → ScheduledItem.origin.
      // So kennt ein gefeuerter Wecker sein Ursprungs-Gerät; fehlt sie (alt-Client),
      // bleibt origin=null ⇒ byte-identischer Alt-Pfad (überall bimmeln).
      deviceId: opts.deviceId ?? getDeviceId(),
      // speakerId: Default „andi" (VITE_SPEAKER_ID) — der Aufrufer überschreibt ihn
      // mit dem zuletzt per Stimme erkannten Sprecher (S3-Dynamik). displayName NUR
      // setzen, wenn er wirklich da ist (nicht leer) — fehlt er, bleibt der Body
      // byte-identisch zum bisherigen Vertrag und der Backend-Default greift.
      speakerContext: {
        speakerId: opts.speakerId ?? SPEAKER_ID,
        ...(opts.displayName ? { displayName: opts.displayName } : {}),
      },
    }),
    signal: opts.signal,
  });

  if (res.status === 401) {
    throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand). Setze VITE_TOKEN.');
  }
  if (!res.ok || !res.body) {
    throw new Error(`Backend antwortete HTTP ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  const sse = new SseDecoder();

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    const chunk = decoder.decode(value, { stream: true });
    for (const payload of sse.push(chunk)) {
      const ev = parseChatEvent(payload);
      if (ev) opts.onEvent(ev);
    }
  }
  for (const payload of sse.flush()) {
    const ev = parseChatEvent(payload);
    if (ev) opts.onEvent(ev);
  }
}
