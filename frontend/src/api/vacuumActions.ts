import { API_BASE, TOKEN } from './config';
import { getActiveUiLanguage } from '../i18n/activeLanguageStore';
import { resolveUiStrings } from '../i18n/catalogs';

/**
 * **vacuumActions** — typisierter Client für `POST /api/v1/home/vacuum/{action}`
 * (Andi 21.08.: „Können wir den Sauger starten und nach Hause fahren lassen?").
 * Spiegel von `de.hoshi.web.VacuumActionController`, Vertrag wörtlich aus
 * `vault/tracks/RESULT-sauger-aktionen-2026-08-21.md` §1.
 *
 * **Was eine 200 sagt — und was NICHT.** `accepted:true` heißt „Home Assistant
 * hat den Auftrag angenommen", nicht „der Sauger fährt". Der Antwort-Body trägt
 * bewusst KEIN Zustandsfeld (ein BE-Test hält das fest), und dieser Client
 * gibt deshalb auch keins zurück: `Promise<void>` bei Annahme, sonst ein
 * `Error` mit der ehrlichen Server-Meldung. **Die Kachel-Wahrheit kommt
 * weiterhin ausschließlich aus dem Registry-Polling** — kein Aufrufer darf aus
 * einer 200 einen neuen Kachel-Zustand schreiben (der BE `invalidate()`t nach
 * einer angenommenen Tat, der nächste Poll liest also frisch bei HA nach).
 *
 * **Fehler sind Text, keine Codes.** Die BE-Bodies (`SettingsError`
 * `{error,id,message}`) tragen bereits deutsche, ehrlich formulierte Sätze —
 * 409 `vacuum-off` („HA beim Deploy aus"), 409 `vacuum-not-found`, 502
 * `vacuum-action-failed` (mit HA's echtem Statuscode in `message`). Dieser
 * Client reicht `message` unverfälscht durch, statt eigene Diagnosen zu
 * erfinden; nur wenn gar kein lesbarer Body kommt, greift der generische
 * Fallback aus `apiErrors`.
 *
 * Auth + Base-URL exakt wie `api/homeEdit.ts`/`api/nightMode.ts`: Token als
 * `X-Hoshi-Token`-Header, nur gesetzt, wenn überhaupt einer konfiguriert ist.
 */

/**
 * Die zwei erlaubten Aktionen — dieselbe Allowlist wie am BE-Rand (alles andere
 * ⇒ 400 `vacuum-unknown-action`). Der Typ ist die FE-seitige Hälfte dieser
 * Wand: ein dritter String kommt hier gar nicht erst durch den Compiler.
 */
export type VacuumAction = 'start' | 'return_to_base';

/** Token-Header wie `api/homeEdit.ts` — nur setzen, wenn ein Token konfiguriert ist. */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/** Liest `message` aus einem `SettingsError`-Body, sonst der ehrliche Fallback. */
async function readErrorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: unknown };
    return typeof body.message === 'string' && body.message ? body.message : fallback;
  } catch {
    return fallback;
  }
}

/**
 * Schickt die Tat und gibt bei ANNAHME (200) nichts zurück; jeder andere
 * Ausgang wirft einen `Error`, dessen `message` direkt anzeigbar ist.
 *
 * `networkFallback` ist der Satz für „die Anfrage kam nie an" (Netz weg,
 * Abbruch) — er wird vom Aufrufer aus dem Kachel-Katalog gereicht, damit hier
 * kein zweiter Satz Fehlerwörter neben `idleFace.homeTiles.vacuum.actions`
 * entsteht.
 */
export async function sendVacuumAction(
  action: VacuumAction,
  networkFallback: string,
  signal?: AbortSignal,
): Promise<void> {
  const t = resolveUiStrings(getActiveUiLanguage()).apiErrors;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/api/v1/home/vacuum/${action}`, {
      method: 'POST',
      headers: authHeaders(),
      signal,
    });
  } catch {
    // Netzfehler/Abbruch: wir wissen NICHT, ob HA den Auftrag bekam. Genau das
    // sagt der Satz — kein „fehlgeschlagen", das mehr behauptet als wir wissen.
    throw new Error(networkFallback);
  }
  if (res.status === 401) throw new Error(t.authWall);
  if (!res.ok) throw new Error(await readErrorMessage(res, t.httpStatus(res.status)));
}
