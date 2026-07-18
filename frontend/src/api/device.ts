// Stabile Geräte-Identität für die Wecker-Ursprungs-Lane (Andi-Ausbau
// Timer/Wecker): EINE UUID pro Browser, persistent in localStorage, die bei
// JEDEM Turn als `deviceId` mitgeht (api/chat.ts Body, api/voice.ts Query). Das
// Backend schreibt sie als `ScheduledItem.origin`; beim Feuern kopiert der
// FireService sie ins `FiredItem.origin`. So weiß das FE, WO ein Wecker gestellt
// wurde — und kann ursprungs-gebunden bimmeln (das stellende Gerät sofort,
// fremde Geräte erst nach der Eskalationsfrist).
//
// Ehrlichkeits-/Robustheits-Achse (gespiegelt zu api/config + loadSpeakPref):
//  - Kein localStorage (privater Modus / SSR / node-Test): wir brechen NICHT —
//    stattdessen eine pro-Session stabile Id im Modul-Cache (der Turn trägt dann
//    trotzdem eine gültige UUID; nur überlebt sie keinen Reload).
//  - `crypto.randomUUID` ist der Normalfall; fehlt es (sehr alte Engine), ein
//    RFC-4122-artiger Fallback aus Math.random (für eine Geräte-Id ausreichend,
//    keine Krypto-Garantie nötig).

/** localStorage-Schlüssel der stabilen Geräte-Id (ein Wert pro Browser). */
export const DEVICE_ID_STORAGE_KEY = 'hoshi.deviceId';

/** Defensiver Zugriff auf localStorage (node/SSR/privater Modus kennen es nicht). */
function safeStorage(): Storage | null {
  try {
    if (typeof localStorage !== 'undefined') return localStorage;
  } catch {
    /* Zugriff geblockt (privater Modus) — kein Bruch. */
  }
  return null;
}

/** Eine UUID: bevorzugt crypto.randomUUID, sonst RFC-4122-artiger Fallback. */
export function generateDeviceId(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
  } catch {
    /* randomUUID nicht verfügbar → Fallback unten. */
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Modul-Cache: einmal gelesen/erzeugt, dann stabil für die ganze Session —
// auch ohne localStorage bleibt die Id innerhalb einer Session konstant.
let cached: string | null = null;

/**
 * Die stabile Geräte-Id dieses Browsers. Erst-Aufruf liest sie aus localStorage
 * oder legt EINMAL eine neue an; Folge-Aufrufe liefern den Cache. Ohne Storage
 * eine pro-Session stabile (nur nicht persistente) Id. Wirft nie.
 */
export function getDeviceId(): string {
  if (cached) return cached;
  const store = safeStorage();
  if (store) {
    try {
      const existing = store.getItem(DEVICE_ID_STORAGE_KEY);
      if (existing && existing.trim().length > 0) {
        cached = existing;
        return existing;
      }
      const fresh = generateDeviceId();
      store.setItem(DEVICE_ID_STORAGE_KEY, fresh);
      cached = fresh;
      return fresh;
    } catch {
      /* Storage voll/geblockt — auf den Session-Cache unten zurückfallen. */
    }
  }
  cached = cached ?? generateDeviceId();
  return cached;
}

/** Modul-Cache verwerfen — nur für Tests (frisch aus einem gestubbten Storage lesen). */
export function resetDeviceIdCache(): void {
  cached = null;
}
