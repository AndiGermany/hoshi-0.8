import { useCallback, useEffect, useRef, useState } from 'react';
import { API_BASE, TOKEN } from '../api/config';
import { CHIME_REPEAT_MS, playAlarmChime } from '../audio/chime';

/**
 * Klingel-Naht zum Backend-Fire-Service: pollt `GET /api/v1/scheduled/fired`
 * (~5s) und klingelt, bis der Mensch bestätigt. Wire-Format:
 * `[{id, kind, label?, dueAtEpochMs, firedAtEpochMs, missed}]`, `label` fehlt
 * bei null; leer ⇒ `[]`.
 *
 * Semantik (Ring-1-Fix 2026-07-03, „der Timer hat heute nicht geklappt"):
 *  - Der Server ist jetzt IDEMPOTENT (kein consume-once mehr): jeder Poll —
 *    aus JEDEM Tab — sieht ALLE unbestätigten Klingel-Events. Der Server ist
 *    die Wahrheit: der State wird pro Poll ERSETZT ({@link reconcileFired},
 *    referenz-stabil bei Gleichstand). Ein leerer Poll heißt ehrlich „nichts
 *    unbestätigt" (z.B. von einem anderen Tab quittiert) ⇒ Banner weg. Nur
 *    FEHL-Polls (Netz/401/…) ändern NICHTS ({@link fetchFiredItems} ⇒ null) —
 *    ein Funkloch darf einen klingelnden Wecker nicht wegräumen.
 *  - Bestätigt wird EXPLIZIT: Tap auf den Banner ⇒ `POST …/fired/{id}/ack`
 *    ({@link ackFiredItem}) — erst dann ist das Klingeln weg, für alle Tabs.
 *    Optimistisch: lokal sofort leeren; bis der Server das Ack verdaut hat,
 *    filtert `ackedRef` ein Wieder-Auftauchen im nächsten Poll weg.
 *  - Ursprungs-gebundenes Bimmeln (Andi-Ausbau): der BANNER erscheint überall
 *    (man ackt auf jedem Gerät), der TON aber ist gebunden — nur das Ursprungs-
 *    Gerät (`origin === deviceId`) bzw. ein alt-Client ohne origin bimmelt sofort;
 *    ein FREMDES Gerät erst nach `escalationSeconds` ab dem ersten lokalen Sichten
 *    ({@link shouldChimeNow}, gegen Uhr-Skew). ack/Löschen räumt für alle.
 *  - Klingel-Schleife: solange etwas (bimmel-berechtigt) unbestätigt ist,
 *    wiederholt der Chime alle ~4s ({@link CHIME_REPEAT_MS}). Autoplay-ehrlich: meldet
 *    {@link playAlarmChime} „nicht hörbar" (Context gesperrt, keine Geste),
 *    liefert der Hook `silenced=true` (Banner pulsiert visuell) und blinkt
 *    den Tab-Titel ({@link FIRED_TITLE}) — der Klang wird bei der nächsten
 *    Geste nachgeholt (audio/chime.ts), die Schleife merkt das am nächsten Tick.
 *  - `missed=true` (Server: >5 min Downtime-Nachzügler ODER >30 min
 *    unbestätigt) rendert das FE als ehrliche Verpasst-Meldung („… war um
 *    HH:MM fällig — hab dich nicht erreicht", components/FiredToast).
 *  - BEWUSST KEIN visibility-Gate (Live-Befund 2026-07-02): Chromes Window-
 *    Occlusion meldet auch den aktiven Tab als `hidden` — gepollt wird immer.
 *  - Ehrlichkeits-/Lärm-Achse wie useOpsStatus: Fehler still (kein roter
 *    Fehler), Token als `X-Hoshi-Token` (api/config).
 *
 * `parseFiredItems`/`fetchFiredItems`/`ackFiredItem`/`reconcileFired` sind
 * pure/seam-Funktionen (kein DOM, keine Timer) → ohne Live-Backend
 * unit-testbar; der Hook verdrahtet Polling + Klingel-Schleife + Cleanup.
 */

export type FiredKind = 'TIMER' | 'ALARM' | 'REMINDER';

export interface FiredItem {
  id: string;
  kind: FiredKind;
  label?: string;
  dueAtEpochMs: number;
  firedAtEpochMs: number;
  /** Ehrlich verpasst: erst nach Downtime gefeuert oder >30 min unbestätigt. */
  missed: boolean;
  /**
   * Die Geräte-/Session-Id, die diesen Wecker STELLTE (BE-Contract, NEU, additiv,
   * fehlt bei null). Steuert das ursprungs-gebundene Bimmeln: nur das Ursprungs-
   * Gerät (`origin === deviceId`) — oder ein alt-Client ohne origin (`null`) —
   * bimmelt SOFORT; fremde Geräte erst nach der Eskalationsfrist ({@link shouldChimeNow}).
   */
  origin?: string;
}

/** Default-Eskalationsfrist (s), falls die Einstellung nichts vorgibt. */
export const DEFAULT_ESCALATION_SECONDS = 15;

const KINDS: readonly FiredKind[] = ['TIMER', 'ALARM', 'REMINDER'];

/** Tab-Titel während eines unbestätigten, UNHÖRBAREN Klingelns (Autoplay-Fallback). */
export const FIRED_TITLE = '⏰ Timer!';

/** Blink-Takt (ms) des Tab-Titels im Autoplay-Fallback. */
export const TITLE_BLINK_MS = 1_000;

/**
 * Validiert die Wire-Antwort. Kein Array / Müll-Einträge / fehlende id →
 * still verworfen (nie ein kaputtes Klingeln rendern). Unbekannte `kind`
 * fällt auf TIMER zurück — gefeuert ist gefeuert, das Klingeln zählt.
 */
export function parseFiredItems(body: unknown): FiredItem[] {
  if (!Array.isArray(body)) return [];
  return (body as unknown[]).flatMap((raw) => {
    if (!raw || typeof raw !== 'object') return [];
    const r = raw as Record<string, unknown>;
    if (typeof r.id !== 'string' || r.id.length === 0) return [];
    const kind = KINDS.includes(r.kind as FiredKind) ? (r.kind as FiredKind) : 'TIMER';
    const item: FiredItem = {
      id: r.id,
      kind,
      dueAtEpochMs: typeof r.dueAtEpochMs === 'number' ? r.dueAtEpochMs : 0,
      firedAtEpochMs: typeof r.firedAtEpochMs === 'number' ? r.firedAtEpochMs : 0,
      missed: r.missed === true,
    };
    if (typeof r.label === 'string' && r.label.trim().length > 0) item.label = r.label;
    // origin: das stellende Gerät (fehlt bei null ⇒ alt-Pfad, überall bimmeln).
    if (typeof r.origin === 'string' && r.origin.trim().length > 0) item.origin = r.origin;
    return [item];
  });
}

/** Token-Header wie useOpsStatus (X-Hoshi-Token, nur wenn gesetzt). */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/**
 * Abruf der unbestätigten Klingel-Events. EHRLICH dreiwertig:
 * Items ⇒ geparst; leeres `[]` ⇒ wirklich nichts unbestätigt (Server-Wahrheit,
 * darf den Banner räumen); **`null` ⇒ FEHL-Poll** (401/404/5xx/Netz/Abbruch) —
 * der Aufrufer lässt den State dann UNANGETASTET (ein Funkloch räumt keinen
 * klingelnden Wecker weg).
 */
export async function fetchFiredItems(signal?: AbortSignal): Promise<FiredItem[] | null> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/scheduled/fired`, { headers: authHeaders(), signal });
    if (!res.ok) return null;
    const body: unknown = await res.json().catch(() => null);
    if (!Array.isArray(body)) return null;
    return parseFiredItems(body);
  } catch {
    return null;
  }
}

/**
 * Quittiert EIN Klingeln: `POST /api/v1/scheduled/fired/{id}/ack` — erst damit
 * verschwindet es serverseitig (für alle Tabs). Best-effort: `false` bei
 * jedem Misserfolg (auch 404 = schon von einem anderen Tab quittiert — für
 * uns gleichwertig, das Item ist weg).
 */
export async function ackFiredItem(id: string): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/scheduled/fired/${encodeURIComponent(id)}/ack`, {
      method: 'POST',
      headers: authHeaders(),
    });
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Server-Wahrheit übernehmen, aber referenz-stabil: sind ids UND missed-Flags
 * identisch (gleiche Reihenfolge), kommt DIESELBE Referenz zurück (React
 * re-rendert dann nicht); sonst ersetzt die Server-Sicht den State komplett.
 */
export function reconcileFired(current: FiredItem[], incoming: FiredItem[]): FiredItem[] {
  const same =
    current.length === incoming.length &&
    current.every(
      (c, i) =>
        c.id === incoming[i].id &&
        c.missed === incoming[i].missed &&
        c.origin === incoming[i].origin,
    );
  return same ? current : incoming;
}

/**
 * Soll DIESES Gerät für dieses gefeuerte Item JETZT bimmeln?
 *
 * Ursprungs-gebundene Regel (der Kern des Ausbaus):
 *  - `origin` fehlt (alt-Client) ODER `origin === deviceId` (dies IST das Gerät,
 *    wo der Wecker gestellt wurde) ⇒ **sofort** bimmeln.
 *  - Fremdes Gerät ⇒ erst nach `escalationSeconds` — und zwar gezählt ab dem
 *    ERSTEN lokalen Sichten (`firstSeenMs`), NICHT ab `firedAtEpochMs`. Die
 *    Server-Uhr und die Browser-Uhr driften (~40s gemessen); `firedAt − localNow`
 *    wäre also unzuverlässig. Der Banner (visuell) erscheint derweil überall — man
 *    kann auf JEDEM Gerät ACKen; nur der TON ist ursprungs-gebunden + eskaliert.
 */
export function shouldChimeNow(
  item: FiredItem,
  deviceId: string | null,
  firstSeenMs: number,
  nowMs: number,
  escalationSeconds: number,
): boolean {
  if (!item.origin || item.origin === deviceId) return true;
  return nowMs - firstSeenMs >= escalationSeconds * 1000;
}

export interface FiredItemsState {
  /** Aktuell unbestätigte Klingel-Events — leer = kein Banner. */
  items: FiredItem[];
  /** Tap auf den Banner: ALLE quittieren (ack-POST je Item, dann weg — für alle Tabs). */
  ack: () => void;
  /** `true`, wenn der Chime aktuell NICHT hörbar ist (Autoplay-Sperre) → visuell eskalieren. */
  silenced: boolean;
}

/** Optionen des Klingel-Hooks: Ursprungs-Urteil + Eskalationsfrist. */
export interface UseFiredItemsOptions {
  /** Die stabile Geräte-Id dieses Browsers (api/device). Steuert das Ursprungs-Urteil. */
  deviceId?: string | null;
  /** Frist (s), nach der auch ein FREMDES Gerät bimmelt. Default {@link DEFAULT_ESCALATION_SECONDS}. */
  escalationSeconds?: number;
}

/**
 * Pollt `GET /api/v1/scheduled/fired` (~5s, immer — ein Wecker klingelt auch
 * verdeckt) und bimmelt ursprungs-gebunden mit Eskalation ({@link shouldChimeNow}).
 */
export function useFiredItems(intervalMs = 5000, opts: UseFiredItemsOptions = {}): FiredItemsState {
  const deviceId = opts.deviceId ?? null;
  const escalationSeconds = opts.escalationSeconds ?? DEFAULT_ESCALATION_SECONDS;

  const [items, setItems] = useState<FiredItem[]>([]);
  const [silenced, setSilenced] = useState(false);
  // `chiming` = dies Gerät bimmelt AKTIV (Schleife läuft). Getrennt von „es gibt
  // ein Banner" (items.length>0): ein FREMDER, noch nicht eskalierter Wecker zeigt
  // den Banner, macht hier aber (noch) keinen Ton — chiming bleibt dann false.
  const [chiming, setChiming] = useState(false);
  const aliveRef = useRef(true);
  // Optimistisch quittierte ids: bis der Server das Ack verdaut hat, darf ein
  // Poll sie nicht als „neu" zurückbringen. Aufgeräumt, sobald der Server sie
  // nicht mehr liefert (dann ist das Ack angekommen — oder das Item eh weg).
  const ackedRef = useRef<Set<string>>(new Set());
  // Wann WIR ein Item ZUERST gesehen haben (lokale Uhr). Der Eskalations-Countdown
  // zählt ab HIER — bewusst NICHT ab firedAtEpochMs (Server-/Browser-Uhr driften).
  const firstSeenRef = useRef<Map<string, number>>(new Map());

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const fired = await fetchFiredItems(controller.signal);
      if (!aliveRef.current || fired === null) return; // Fehl-Poll: Zustand halten.
      const reported = new Set(fired.map((i) => i.id));
      for (const id of [...ackedRef.current]) {
        if (!reported.has(id)) ackedRef.current.delete(id);
      }
      const visible = fired.filter((i) => !ackedRef.current.has(i.id));
      setItems((cur) => reconcileFired(cur, visible));
    };

    void tick();
    const id = window.setInterval(() => void tick(), intervalMs);
    return () => {
      aliveRef.current = false;
      controller.abort();
      window.clearInterval(id);
    };
  }, [intervalMs]);

  // Klingel-Schleife mit Eskalation. Neu erblickte Items bekommen ihren firstSeen-
  // Stempel; verschwundene werden aufgeräumt. `evaluate` entscheidet, ob JETZT ein
  // Item bimmel-berechtigt ist (eigenes/legacy sofort, fremdes ab der Frist) — sonst
  // wird ein Timer auf die nächste Eskalations-Deadline gelegt, der neu bewertet.
  // Läuft die Schleife, meldet playAlarmChime ehrlich, ob es hörbar war (→ silenced).
  useEffect(() => {
    const seen = firstSeenRef.current;
    const now0 = Date.now();
    const present = new Set(items.map((i) => i.id));
    for (const id of [...seen.keys()]) if (!present.has(id)) seen.delete(id);
    for (const it of items) if (!seen.has(it.id)) seen.set(it.id, now0);

    if (items.length === 0) {
      setChiming(false);
      setSilenced(false);
      return;
    }

    let chimeInterval: number | null = null;
    let escTimeout: number | null = null;

    const ring = (): void => setSilenced(!playAlarmChime());

    const startChime = (): void => {
      if (chimeInterval !== null) return;
      setChiming(true);
      ring();
      chimeInterval = window.setInterval(ring, CHIME_REPEAT_MS);
    };

    const evaluate = (): void => {
      const now = Date.now();
      const eligible = items.some((it) =>
        shouldChimeNow(it, deviceId, seen.get(it.id) ?? now, now, escalationSeconds),
      );
      if (eligible) {
        startChime();
        return;
      }
      // Noch kein Item berechtigt → auf die früheste Fremd-Deadline warten, dann neu bewerten.
      let nearest = Infinity;
      for (const it of items) {
        if (it.origin && it.origin !== deviceId) {
          nearest = Math.min(nearest, (seen.get(it.id) ?? now) + escalationSeconds * 1000);
        }
      }
      if (nearest !== Infinity) {
        escTimeout = window.setTimeout(evaluate, Math.max(0, nearest - now));
      }
    };

    evaluate();

    return () => {
      if (chimeInterval !== null) window.clearInterval(chimeInterval);
      if (escTimeout !== null) window.clearTimeout(escTimeout);
    };
  }, [items, deviceId, escalationSeconds]);

  // Autoplay-Fallback: dies Gerät bimmelt AKTIV, aber KEIN hörbarer Chime ⇒ Tab-
  // Titel blinkt „⏰ Timer!" — ehrlich sichtbar statt stumm verpuffen. Cleanup
  // stellt den Titel zurück. (Ein fremder, noch nicht eskalierter Wecker blinkt
  // NICHT — er bimmelt ja bewusst noch nicht; der Banner trägt ihn visuell.)
  useEffect(() => {
    if (!chiming || !silenced || typeof document === 'undefined') return;
    const original = document.title;
    document.title = FIRED_TITLE;
    let showAlert = true;
    const id = window.setInterval(() => {
      showAlert = !showAlert;
      document.title = showAlert ? FIRED_TITLE : original;
    }, TITLE_BLINK_MS);
    return () => {
      window.clearInterval(id);
      document.title = original;
    };
  }, [chiming, silenced]);

  const ack = useCallback(() => {
    for (const item of items) {
      ackedRef.current.add(item.id);
      void ackFiredItem(item.id);
    }
    setItems([]);
  }, [items]);

  return { items, ack, silenced };
}
