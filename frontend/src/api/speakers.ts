import { API_BASE, TOKEN } from './config';

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
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const body: unknown = await res.json();
  if (!Array.isArray(body)) throw new Error('Sprecher-Antwort ist kein Array.');
  return body.map(toSummary).filter((s): s is SpeakerSummary => s !== null);
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

  if (res.status === 401) {
    throw new SpeakerEnrollError('auth', '401 — Token fehlt oder ist ungültig (Auth-Wand).');
  }
  if (res.status === 400) {
    throw new SpeakerEnrollError('bad-name', 'Name ungültig — erlaubt sind Buchstaben, Ziffern, _ und -.');
  }
  if (res.status === 409) {
    throw new SpeakerEnrollError(
      'out-of-sync',
      'Die Aufnahmen sind durcheinandergeraten — bitte von vorn beginnen.',
    );
  }
  if (res.status === 422) {
    throw new SpeakerEnrollError('too-short', 'Die Aufnahme war zu kurz oder zu leise. Sprich den Satz noch einmal.');
  }
  if (res.status === 502) {
    throw new SpeakerEnrollError('no-embedding', 'Die Stimmerkennung ist gerade nicht erreichbar. Später erneut versuchen.');
  }
  if (!res.ok) {
    throw new SpeakerEnrollError('unknown', `Anlernen fehlgeschlagen (HTTP ${res.status}).`);
  }
  const summary = toSummary(await res.json());
  if (!summary) throw new SpeakerEnrollError('unknown', 'Anlern-Antwort unlesbar.');
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
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (res.status === 404) return; // schon weg → idempotent ok
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
}
