import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den TTS-Engine-Settings-Rand (Andi-Video-Auftrag:
 * „die TTS-Engine in den Einstellungen wählbar, zur Laufzeit"), Spiegel von
 * `de.hoshi.web.TtsSettingsController`:
 *  - `GET /api/v1/settings/tts` → {@link TtsSetting} (jede Engine trägt einen
 *    EHRLICHEN Live-Status — kein Fake-grün; zusätzlich `stimmen`/
 *    `stimmenHinweis`/`aktiveStimme` — die Stimmen-Liste der AKTIVEN Engine,
 *    Andi-Live-Befund: „die Stimme-Sektion muss der aktiven Engine folgen").
 *  - `PUT /api/v1/settings/tts` Body `{id}` → autoritativer neuer Zustand;
 *    unbekannte id ⇒ 422 ⇒ {@link UnknownEngineError}; bekannte, aber gerade
 *    NICHT verfügbare Engine ⇒ 409 ⇒ {@link EngineUnavailableError} (trägt den
 *    Server-Hinweis, z.B. „nicht gestartet"). Optional `{id, voice}` ⇒
 *    validiert `voice` gegen die Live-Liste der Ziel-Engine; unbekannt ⇒ 422 ⇒
 *    {@link UnknownVoiceError} ({@link saveTtsVoice}).
 *
 * Auth + Base-URL exakt wie `api/skills.ts`.
 */

export interface TtsEngineOption {
  id: string;
  verfuegbar: boolean;
  hinweis: string;
}

/** Eine wählbare Stimme der AKTIVEN Engine. `locale`/`lizenz` nur bei say/piper gefüllt. */
export interface TtsVoiceOption {
  id: string;
  label: string;
  locale?: string;
  lizenz?: string;
}

export interface TtsSetting {
  aktiv: string;
  engines: TtsEngineOption[];
  /** Die Live-Stimmen-Liste der AKTIVEN Engine (openai: Whitelist; say/piper: Sidecar-`/voices`; leer bei voxtral/Fehler). */
  stimmen: TtsVoiceOption[];
  /** Ehrlicher Klartext-Hinweis zur Stimmen-Liste (z.B. „…und 12 weitere", „Stimmen-Liste grad nicht lesbar."); leer = nichts zu sagen. */
  stimmenHinweis: string;
  /** Die gemerkte/Boot-Default-Stimme der aktiven Engine, oder `null` (z.B. voxtral ohne Katalog). */
  aktiveStimme: string | null;
}

/** 422: die gewählte Engine-Id ist unbekannt. */
export class UnknownEngineError extends Error {
  constructor(
    public readonly id: string,
    message = 'Unbekannte Engine.',
  ) {
    super(message);
    this.name = 'UnknownEngineError';
  }
}

/** 409: die Engine ist bekannt, aber gerade nicht verfügbar (trägt den ehrlichen Server-Hinweis). */
export class EngineUnavailableError extends Error {
  constructor(
    public readonly id: string,
    message: string,
  ) {
    super(message);
    this.name = 'EngineUnavailableError';
  }
}

/** 422: die gewünschte Stimme steht NICHT in der Live-Liste der Ziel-Engine (say/piper). */
export class UnknownVoiceError extends Error {
  constructor(
    public readonly id: string,
    message = 'Unbekannte Stimme für diese Engine.',
  ) {
    super(message);
    this.name = 'UnknownVoiceError';
  }
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

function toVoiceOption(raw: unknown): TtsVoiceOption | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== 'string' || typeof r.label !== 'string') return null;
  return {
    id: r.id,
    label: r.label,
    locale: typeof r.locale === 'string' ? r.locale : undefined,
    lizenz: typeof r.lizenz === 'string' ? r.lizenz : undefined,
  };
}

function toSetting(raw: unknown): TtsSetting {
  if (!raw || typeof raw !== 'object') throw new Error('TTS-Settings-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (typeof r.aktiv !== 'string' || !Array.isArray(r.engines)) {
    throw new Error('TTS-Settings-Antwort unlesbar.');
  }
  const engines: TtsEngineOption[] = r.engines
    .map((e): TtsEngineOption | null => {
      if (!e || typeof e !== 'object') return null;
      const ee = e as Record<string, unknown>;
      if (typeof ee.id !== 'string') return null;
      return {
        id: ee.id,
        verfuegbar: ee.verfuegbar === true,
        hinweis: typeof ee.hinweis === 'string' ? ee.hinweis : '',
      };
    })
    .filter((e): e is TtsEngineOption => e !== null);
  // Additive Felder (Andi-Live-Befund „Stimme folgt der aktiven Engine"): defensiv
  // geparst, damit ein älterer Server-Stand (ohne diese Felder) nicht bricht.
  const stimmen: TtsVoiceOption[] = Array.isArray(r.stimmen)
    ? r.stimmen.map(toVoiceOption).filter((v): v is TtsVoiceOption => v !== null)
    : [];
  const stimmenHinweis = typeof r.stimmenHinweis === 'string' ? r.stimmenHinweis : '';
  const aktiveStimme = typeof r.aktiveStimme === 'string' ? r.aktiveStimme : null;
  return { aktiv: r.aktiv, engines, stimmen, stimmenHinweis, aktiveStimme };
}

/** Fehler-Body-Form von `SettingsError` (BE): `{error,id,message}`. */
async function readErrorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as Record<string, unknown>;
    return typeof body.message === 'string' ? body.message : `HTTP ${res.status}`;
  } catch {
    return `HTTP ${res.status}`;
  }
}

/** Fehler-Body vollständig (Andi-Wunsch: PUT+voice muss zwischen unknown-engine/unknown-voice unterscheiden). */
async function readErrorBody(res: Response): Promise<{ error?: string; message?: string }> {
  try {
    return (await res.json()) as { error?: string; message?: string };
  } catch {
    return {};
  }
}

/** `GET /api/v1/settings/tts`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchTtsSettings(signal?: AbortSignal): Promise<TtsSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/tts`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/tts` mit Body `{id}`. Gibt den AUTORITATIVEN neuen
 * Zustand zurück (Readback statt Behauptung — KEIN optimistisches Umschalten).
 *  - 422 (unbekannte id) ⇒ {@link UnknownEngineError},
 *  - 409 (bekannt, aber nicht verfügbar) ⇒ {@link EngineUnavailableError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveTtsEngine(id: string, signal?: AbortSignal): Promise<TtsSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/tts`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ id }),
    signal,
  });
  if (res.status === 422) throw new UnknownEngineError(id);
  if (res.status === 409) throw new EngineUnavailableError(id, await readErrorMessage(res));
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/tts` mit Body `{id, voice}` — Stimm-Wunsch FÜR DIE
 * AKTIVE Engine (say/piper; openai bleibt Client-seitig, s. `useSettings.voice`).
 * Gibt den AUTORITATIVEN neuen Zustand zurück (Readback).
 *  - 422 mit `error:"unknown-voice"` ⇒ {@link UnknownVoiceError},
 *  - 422 mit `error:"unknown-engine"` (Tippfehler in [id]) ⇒ {@link UnknownEngineError},
 *  - 409 (Engine gerade nicht verfügbar) ⇒ {@link EngineUnavailableError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveTtsVoice(id: string, voice: string, signal?: AbortSignal): Promise<TtsSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/tts`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ id, voice }),
    signal,
  });
  if (res.status === 422) {
    const body = await readErrorBody(res);
    if (body.error === 'unknown-voice') throw new UnknownVoiceError(voice, body.message);
    throw new UnknownEngineError(id, body.message);
  }
  if (res.status === 409) throw new EngineUnavailableError(id, await readErrorMessage(res));
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}
