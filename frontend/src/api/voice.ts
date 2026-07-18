import { API_BASE, TOKEN } from './config';
import { SseDecoder, parseChatEvent } from './sse';
import { getDeviceId } from './device';
import { loadSettings } from '../hooks/useSettings';
import type { ChatEvent, Language } from './types';

export interface StreamVoiceOptions {
  language?: Language;
  signal?: AbortSignal;
  onEvent: (event: ChatEvent) => void;
  /**
   * TTS-Antwort sprechen. Default true: wer per Sprache fragt, will i. d. R. eine
   * gesprochene Antwort (Sprach-Ein impliziert Sprach-Aus). Geht als `speak`-
   * Query-Param mit (Backend-Default ist ebenfalls true).
   */
  speak?: boolean;
  /** Persona-Wahl (analog zum Text-Chat). Default: aus den persistierten Settings. */
  persona?: string;
  /**
   * OpenAI-Voice-Name (Cloud-TTS-Stimme, Backlog #6; analog zum Text-Chat, hier
   * als Query-Param). Default: aus den persistierten Settings.
   */
  voice?: string;
  /**
   * Stabile Geräte-Id (analog zum Text-Chat, hier als Query-Param `deviceId`).
   * Default: {@link getDeviceId}. Fließt bis `ScheduledItem.origin`, damit ein
   * per Stimme gestellter Wecker sein Ursprungs-Gerät kennt. Unbekannte Query-
   * Params ignoriert Spring folgenlos (byte-neutral für den Alt-Pfad).
   */
  deviceId?: string;
}

/**
 * LIVE-Aufruf von `POST /api/v1/voice`: lädt eine Mikro-Aufnahme hoch, das
 * Backend transkribiert sie (Whisper) und antwortet mit demselben SSE-
 * {@link ChatEvent}-Strom wie der Text-Chat — plus einem vorangestellten
 * `step{kind:"transcript"}`-Event mit dem erkannten Text.
 *
 * **Content-Type:** `application/octet-stream`. Der {@link VoiceInboundController}
 * akzeptiert auf dem Roh-Pfad bewusst `application/octet-stream` (+ `audio/wav`-
 * Varianten), **nicht** `audio/webm` — würde man `audio/webm` schicken, lehnte
 * Spring mit HTTP 415 ab. Die ChatView schickt seit dem Speaker-Paritäts-Fix
 * ein **16-kHz-Mono-WAV** (via `voiceTurnUploadBlob`, exakt wie der Enroll-Pfad
 * — der Speaker-Sidecar liest nur RIFF/WAV); scheitert die Konvertierung, kommen
 * die rohen Recorder-Bytes, die der STT-Sidecar (ffmpeg) weiterhin dekodiert.
 * Diese Funktion selbst ist format-agnostisch: sie streamt den Blob byte-treu.
 *
 * Token geht als `X-Hoshi-Token`-Header (wie im Text-Chat); `language`/`speak`
 * als Query-Param. 401 wird ehrlich durchgereicht statt verschluckt.
 */
export async function streamVoice(audio: Blob, opts: StreamVoiceOptions): Promise<void> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/octet-stream',
    Accept: 'text/event-stream',
  };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;

  // STT-Sprache: explizite opts schlagen die persistierten Settings (Einstellungs-
  // Panel) — so steuert die Panel-Wahl die Spracherkennung, auch ohne Durchreichen.
  const settings = loadSettings();

  // Sprach-Wahl → Query, gespiegelt zum Text-Chat: `languagePolicy` trägt die Wahl
  // (AUTO/DE/EN, Backend erkennt bei AUTO), `language` bleibt KONKRET (DE/EN) als
  // Legacy-Fallback — 'auto' fällt auf DE. fromCode() ist case-tolerant; GROSS.
  const langChoice = opts.language ?? settings.language;
  const concreteLanguage = (langChoice === 'auto' ? 'de' : langChoice).toUpperCase();

  // persona → Query-Param, gespiegelt zum Text-Chat (dort im JSON-Body). Explizite
  // opts schlagen die persistierten Settings; unbekannte Query-Params ignoriert
  // Spring folgenlos. Ohne dies liefe JEDER Sprach-Turn als STANDARD (Feld fehlte ganz).
  const params = new URLSearchParams({
    languagePolicy: langChoice.toUpperCase(),
    language: concreteLanguage,
    speak: String(opts.speak ?? true),
    persona: opts.persona ?? settings.persona,
    // voice → Query-Param, exakt das persona-Muster: die gewählte Cloud-Stimme
    // für die gesprochene Antwort (Whitelist prüft der OpenAI-Adapter).
    voice: opts.voice ?? settings.voice,
    // deviceId → Query-Param (gespiegelt zum Text-Chat-Body): die stabile Browser-
    // Id fürs Wecker-Ursprungs-Urteil. Unbekannte Params ignoriert Spring folgenlos.
    deviceId: opts.deviceId ?? getDeviceId(),
  });

  const res = await fetch(`${API_BASE}/api/v1/voice?${params.toString()}`, {
    method: 'POST',
    headers,
    body: audio,
    signal: opts.signal,
  });

  if (res.status === 401) {
    throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand). Setze VITE_TOKEN.');
  }
  if (res.status === 415) {
    throw new Error('415 — Backend lehnt den Audio-Content-Type ab (/api/v1/voice).');
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
