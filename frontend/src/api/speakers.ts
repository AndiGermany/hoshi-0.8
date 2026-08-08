import { API_BASE, TOKEN } from './config';
import { getActiveUiLanguage } from '../i18n/activeLanguageStore';
import { resolveUiStrings } from '../i18n/catalogs';

/**
 * Typisierter Client für den Stimm-Anlern-Rand (S2a), Spiegel des BE-Contracts
 * von `de.hoshi.web.SpeakerController` (nur aktiv, wenn das Backend mit
 * `HOSHI_SPEAKER_ENROLL_ENABLED=true` bootet — Biometrie ist ein Andi-Gate):
 *
 *  - `GET    /api/v1/speakers`              → {@link SpeakerSummary}[] (NIE der Vektor)
 *  - `POST   /api/v1/speakers/enroll?name=[&sample=1..3]` (multipart, Part `audio`=WAV)
 *    → {@link SpeakerSummary}. Multi-Sample: `sample=1` (oder weggelassen) ERSETZT das
 *    Profil (frischer Start), `sample>=2` hängt die Aufnahme an — das Backend mittelt
 *    alle Samples (L2-renormalisiert). `sample>=2` ohne bestehendes Profil ⇒ 409.
 *  - `DELETE /api/v1/speakers/{name}`       → 204 (idempotent: 404 = ist schon weg)
 *
 * Auth + Base-URL exakt wie `api/chat.ts`/`api/skills.ts`: Token als
 * `X-Hoshi-Token`-Header (leer ⇒ weggelassen → die Auth-Wand greift ehrlich mit
 * 401), Pfade relativ zu `API_BASE`.
 *
 * WICHTIG (BE-Contract): das Enroll-Audio MUSS **WAV** sein (der `/embed`-Sidecar
 * dekodiert via libsndfile — kein webm/opus). Der `audio`-Part braucht einen
 * **filename**, sonst bindet Spring ihn als Feld statt als Datei. Beides erledigt
 * {@link enrollSpeaker}: es baut das WAV bereits als 3-Arg-`append` mit Dateinamen.
 */

/** Erlaubte Namen (Backend: `^[A-Za-z0-9_-]{1,64}$`; ungültig ⇒ 400). Client prüft mit. */
export const SPEAKER_NAME_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

/** Eine Sprecher-Zeile — bewusst OHNE Embedding (das Backend gibt den Vektor nie raus). */
export interface SpeakerSummary {
  name: string;
  /** Anlern-Zeitpunkt in Millisekunden seit Epoch (BE-Feld `enrolledAt`). */
  enrolledAt: number;
  /** Zahl der Roh-Aufnahmen im Profil (Multi-Sample-Enroll; fehlt bei älteren Antworten). */
  samples?: number;
}

/**
 * Herkunft + Qualität EINER Roh-Aufnahme — Spiegel von `de.hoshi.web.SpeakerSampleOrigin`.
 * Jedes Feld einzeln `null`, wenn unbekannt (Alt-Aufnahme, Client hat es nicht mitgeschickt,
 * WAV-Parsen fehlgeschlagen) — NIE erfunden.
 */
export interface SpeakerSampleOrigin {
  recordedAt: number | null;
  session: number | null;
  device: string | null;
  durationSeconds: number | null;
  rms: number | null;
}

/**
 * Diagnose-Zeile EINES Profils — Spiegel von `de.hoshi.web.SpeakerProfileDiagnostics`.
 * `leaveOneOutSimilarity`/`sampleOrigins` sind 1:1 nach Index zu den ROH-Aufnahmen
 * (`leaveOneOutSimilarity` ist LEER bei <2 Samples — nichts zu leaven, nicht geraten).
 */
export interface SpeakerProfileDiagnostics {
  name: string;
  samples: number;
  selfCohesion: number | null;
  leaveOneOutSimilarity: number[];
  bestForeignSimilarity: Record<string, number>;
  sampleOrigins: SpeakerSampleOrigin[];
}

/** `GET /api/v1/speakers/diagnostics` — Spiegel von `de.hoshi.web.SpeakerDiagnostics`. */
export interface SpeakerDiagnostics {
  profiles: SpeakerProfileDiagnostics[];
  crossSimilarity: Record<string, Record<string, number>>;
}

/** Woran ein Enroll scheitern kann — `kind` lässt die UI ehrlich + gezielt reagieren. */
export type SpeakerEnrollErrorKind =
  | 'bad-name' // 400 — Name passt nicht auf das Muster
  | 'too-short' // 422 — Audio zu kurz/leise (kein stilles Speichern)
  | 'no-embedding' // 502 — der Sidecar lieferte kein Embedding
  | 'auth' // 401 — Token fehlt/ungültig
  | 'out-of-sync' // 409 — Folge-Sample ohne bestehendes Profil (Satz 1 fehlt/verloren)
  | 'unknown'; // sonstiger !ok

/** Typisierter Enroll-Fehler — kein Fake-Erfolg, die UI zeigt je `kind` eine warme Zeile. */
export class SpeakerEnrollError extends Error {
  readonly kind: SpeakerEnrollErrorKind;
  constructor(kind: SpeakerEnrollErrorKind, message: string) {
    super(message);
    this.name = 'SpeakerEnrollError';
    this.kind = kind;
  }
}

/** Token-Header wie `api/skills.ts` — nur setzen, wenn ein Token konfiguriert ist. */
function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/** Defensiver Parse einer Wire-Zeile (kaputte/fehlende Felder → still verworfen). */
function toSummary(raw: unknown): SpeakerSummary | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.name !== 'string' || r.name.length === 0) return null;
  const summary: SpeakerSummary = {
    name: r.name,
    enrolledAt: typeof r.enrolledAt === 'number' ? r.enrolledAt : 0,
  };
  if (typeof r.samples === 'number' && r.samples > 0) summary.samples = r.samples;
  return summary;
}

/** `GET /api/v1/speakers` → `SpeakerSummary[]`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchSpeakers(signal?: AbortSignal): Promise<SpeakerSummary[]> {
  const res = await fetch(`${API_BASE}/api/v1/speakers`, { headers: authHeaders(), signal });
  if (res.status === 401) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall);
  if (!res.ok) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.httpStatus(res.status));
  const body: unknown = await res.json();
  if (!Array.isArray(body)) throw new Error('Sprecher-Antwort ist kein Array.');
  return body.map(toSummary).filter((s): s is SpeakerSummary => s !== null);
}

/** Defensiver Parse EINER Roh-Aufnahme-Herkunft (kaputte/fehlende Felder → einzeln `null`). */
function toSampleOrigin(raw: unknown): SpeakerSampleOrigin {
  const r = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  return {
    recordedAt: typeof r.recordedAt === 'number' ? r.recordedAt : null,
    session: typeof r.session === 'number' ? r.session : null,
    device: typeof r.device === 'string' ? r.device : null,
    durationSeconds: typeof r.durationSeconds === 'number' ? r.durationSeconds : null,
    rms: typeof r.rms === 'number' ? r.rms : null,
  };
}

/** Defensiver Parse EINER Profil-Diagnose-Zeile (kaputte/fehlende Felder → still verworfen/leer). */
function toProfileDiagnostics(raw: unknown): SpeakerProfileDiagnostics | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.name !== 'string' || r.name.length === 0) return null;
  const leaveOneOutSimilarity = Array.isArray(r.leaveOneOutSimilarity)
    ? r.leaveOneOutSimilarity.filter((v): v is number => typeof v === 'number')
    : [];
  const sampleOrigins = Array.isArray(r.sampleOrigins) ? r.sampleOrigins.map(toSampleOrigin) : [];
  const bestForeignSimilarity: Record<string, number> = {};
  if (r.bestForeignSimilarity && typeof r.bestForeignSimilarity === 'object') {
    for (const [k, v] of Object.entries(r.bestForeignSimilarity as Record<string, unknown>)) {
      if (typeof v === 'number') bestForeignSimilarity[k] = v;
    }
  }
  return {
    name: r.name,
    samples: typeof r.samples === 'number' ? r.samples : 0,
    selfCohesion: typeof r.selfCohesion === 'number' ? r.selfCohesion : null,
    leaveOneOutSimilarity,
    bestForeignSimilarity,
    sampleOrigins,
  };
}

/**
 * `GET /api/v1/speakers/diagnostics` → {@link SpeakerDiagnostics}. Datenquelle der aufklappbaren
 * Aufnahmen-Liste je Profil (Reparatur-Auftrag 07.08, Kreuz-Kontaminations-Vorfall): `leaveOneOutSimilarity`
 * macht eine einzelne verkorkste/kontaminierte Aufnahme sichtbar (Index 1:1 zu `sampleOrigins`),
 * bevor sie im Profil-Mittel verschwindet. Wirft bei 401/!ok (wie {@link fetchSpeakers}); ein
 * kaputter Body wird defensiv geparst (Müll-Einträge fallen still raus, NIE geraten).
 */
export async function fetchSpeakerDiagnostics(signal?: AbortSignal): Promise<SpeakerDiagnostics> {
  const res = await fetch(`${API_BASE}/api/v1/speakers/diagnostics`, { headers: authHeaders(), signal });
  if (res.status === 401) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall);
  if (!res.ok) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.httpStatus(res.status));
  const body: unknown = await res.json();
  const raw = body && typeof body === 'object' ? (body as Record<string, unknown>) : {};
  const profiles = Array.isArray(raw.profiles)
    ? raw.profiles.map(toProfileDiagnostics).filter((p): p is SpeakerProfileDiagnostics => p !== null)
    : [];
  const crossSimilarity: Record<string, Record<string, number>> = {};
  if (raw.crossSimilarity && typeof raw.crossSimilarity === 'object') {
    for (const [outer, inner] of Object.entries(raw.crossSimilarity as Record<string, unknown>)) {
      if (!inner || typeof inner !== 'object') continue;
      const row: Record<string, number> = {};
      for (const [k, v] of Object.entries(inner as Record<string, unknown>)) {
        if (typeof v === 'number') row[k] = v;
      }
      crossSimilarity[outer] = row;
    }
  }
  return { profiles, crossSimilarity };
}

/**
 * `POST /api/v1/speakers/enroll?name=<name>[&sample=<n>]` — lädt die **WAV**-Aufnahme
 * als multipart-Part `audio` (mit filename `enroll.wav`, Typ `audio/wav`) hoch. Gibt
 * die frisch angelernte Zeile zurück ({@link SpeakerSummary}).
 *
 * `sample` (1-basiert) steuert das Multi-Sample-Anlernen: `1`/weggelassen ersetzt das
 * Profil (frischer Start — heutiges Verhalten), `>=2` hängt die Aufnahme additiv an
 * (das Backend hält die Roh-Samples und mittelt L2-renormalisiert).
 *
 * Content-Type wird BEWUSST nicht gesetzt — `fetch` setzt bei `FormData` die
 * multipart-Boundary selbst. Fehler kommen typisiert als {@link SpeakerEnrollError}
 * (400/422/502/401/409), damit die UI ehrlich statt generisch reagiert.
 */
export async function enrollSpeaker(
  name: string,
  wav: Blob,
  sample?: number,
  signal?: AbortSignal,
): Promise<SpeakerSummary> {
  const form = new FormData();
  // 3-Arg-append: der dritte Parameter ist der filename — genau das, was Spring
  // braucht, um den Part als FilePart (nicht als Feld) zu binden (BE-Stolperstein a).
  form.append('audio', wav, 'enroll.wav');

  const sampleParam = typeof sample === 'number' ? `&sample=${sample}` : '';
  const res = await fetch(
    `${API_BASE}/api/v1/speakers/enroll?name=${encodeURIComponent(name)}${sampleParam}`,
    { method: 'POST', headers: authHeaders(), body: form, signal },
  );

  // Fehltexte folgen der aktiven UI-Sprache (Muster `api/chat.ts`): sie landen
  // WÖRTLICH im Anlern-Dialog (EnrollDialog zeigt `err.message` direkt).
  const t = resolveUiStrings(getActiveUiLanguage()).speaker.enrollErrors;
  if (res.status === 401) {
    throw new SpeakerEnrollError('auth', t.auth);
  }
  if (res.status === 400) {
    throw new SpeakerEnrollError('bad-name', t.badName);
  }
  if (res.status === 409) {
    throw new SpeakerEnrollError('out-of-sync', t.outOfSync);
  }
  if (res.status === 422) {
    throw new SpeakerEnrollError('too-short', t.tooShort);
  }
  if (res.status === 502) {
    throw new SpeakerEnrollError('no-embedding', t.noEmbedding);
  }
  if (!res.ok) {
    throw new SpeakerEnrollError('unknown', t.unknown(res.status));
  }
  const summary = toSummary(await res.json());
  if (!summary) throw new SpeakerEnrollError('unknown', t.unreadable);
  return summary;
}

/**
 * `DELETE /api/v1/speakers/{name}` — löscht ein Profil („dein Profil, dein
 * Löschen"). Idempotent: 204 UND 404 zählen als „ist weg" (kein Fehler, wenn ein
 * anderes Gerät schon gelöscht hat). 401/400/5xx werfen ehrlich.
 */
export async function deleteSpeaker(name: string, signal?: AbortSignal): Promise<void> {
  const res = await fetch(`${API_BASE}/api/v1/speakers/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall);
  if (res.status === 404) return; // schon weg → idempotent ok
  if (!res.ok) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.httpStatus(res.status));
}

/** Woran ein Einzel-Aufnahme-Löschen scheitern kann — `kind` lässt die UI ehrlich reagieren. */
export type SpeakerSampleDeleteErrorKind =
  | 'auth' // 401 — Token fehlt/ungültig
  | 'bad-request' // 400 — Name/Index ungültig
  | 'last-sample' // 409 — letzte Aufnahme; das Profil bleibt bewusst unangetastet
  | 'unknown'; // sonstiger !ok

/** Typisierter Fehler von {@link deleteSpeakerSample} — kein Fake-Erfolg. */
export class SpeakerSampleDeleteError extends Error {
  readonly kind: SpeakerSampleDeleteErrorKind;
  constructor(kind: SpeakerSampleDeleteErrorKind, message: string) {
    super(message);
    this.name = 'SpeakerSampleDeleteError';
    this.kind = kind;
  }
}

/**
 * `DELETE /api/v1/speakers/{name}/samples/{index}` — löscht GENAU EINE Aufnahme (Reparatur-
 * Auftrag 07.08, Kreuz-Kontaminations-Vorfall: bisher gab es nur die Ganz-Profil-Löschung,
 * obwohl oft nur EINE verkorkste Aufnahme das Problem war). Idempotent wie {@link deleteSpeaker}:
 * 404 zählt als „ist schon weg". 409 (letzte Aufnahme) wirft TYPISIERT (`kind: 'last-sample'`) —
 * der Store lässt ein Profil nie mit 0 Samples/kaputtem Zentroid zurück, die UI soll in diesem
 * Fall stattdessen das ganze Profil löschen, nicht denselben Aufruf stumm wiederholen.
 */
export async function deleteSpeakerSample(
  name: string,
  index: number,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${API_BASE}/api/v1/speakers/${encodeURIComponent(name)}/samples/${index}`, {
    method: 'DELETE',
    headers: authHeaders(),
    signal,
  });
  if (res.status === 404) return; // schon weg → idempotent ok
  const t = resolveUiStrings(getActiveUiLanguage());
  if (res.status === 401) throw new SpeakerSampleDeleteError('auth', t.apiErrors.authWall);
  if (res.status === 400) throw new SpeakerSampleDeleteError('bad-request', t.apiErrors.httpStatus(res.status));
  if (res.status === 409) throw new SpeakerSampleDeleteError('last-sample', t.speaker.deleteRecordingLastHint);
  if (!res.ok) throw new SpeakerSampleDeleteError('unknown', t.apiErrors.httpStatus(res.status));
}
