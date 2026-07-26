// Pre-Roll-Ringpuffer gegen Anlaut-Beschneidung (Andi-Befund 26.07: „aus 'Wie
// zieht eine Kuh die Hose an' wird 'Zieht eine Kuh eine Hose an'" — dazu bei
// weichem H-Anlaut „Hose" → „Rose" verhört, weil Whisper nur „ose" hört).
//
// WARUM geht der Anlaut verloren? `VoiceRecorder.start()` (recorder.ts) hängt an
// EINER Kette aus Promises/Constructor-Aufrufen, bevor überhaupt ein Sample
// aufgezeichnet wird: `getUserMedia()` (Browser/OS öffnen das Mikro — Bluetooth
// besonders langsam), danach `new MediaRecorder(stream)` + `.start()`. Chromes
// MediaRecorder hat zusätzlich ein dokumentiertes Encoder-Anlauf-Verhalten: die
// ERSTEN Frames nach `.start()` können verloren gehen oder stark gedämpft sein
// (Opus-Encoder-Priming + AGC/Noise-Suppression-Einschwingzeit) — unabhängig von
// der getUserMedia-Latenz selbst. Ein Nutzer, der SOFORT nach dem Mikro-Tap
// spricht, verliert also nicht nur, WEIL der Stream noch nicht steht, sondern
// auch NOCHDEM er steht.
//
// DER STANDARD-BAU dagegen: sobald der Mikro-Stream existiert, läuft PARALLEL
// zur eigentlichen Aufnahme eine ROHE PCM-Sammlung in einen kleinen Ring (dieses
// Modul) — unabhängig vom MediaRecorder-Encoder, der erst mit Verzögerung
// „richtig" läuft. Nach [PRE_ROLL_MS] wird der Ring eingefroren (s.
// `VoiceRecorder`/`recorder.ts`) und sein Inhalt der eigentlichen Aufnahme
// VORANGESTELLT ({@link prependPreRoll}) — der Anlaut ist damit drin, selbst wenn
// MediaRecorder ihn selbst verschluckt hätte.
//
// Bewusst DOM-los: der Ring ist reine Datenstruktur (Float32-Chunks + Kapazitäts-
// Deckel), die Web-Audio-Verkabelung (ScriptProcessorNode etc.) lebt in
// recorder.ts. So ist Füllen/Überlaufen/Leeren/Prefix ohne echtes Mikrofon
// testbar.

/**
 * Wie viel Vorlauf der Ring hält, bevor er beim logischen Aufnahme-Start
 * eingefroren und der echten Aufnahme vorangestellt wird. 500ms deckt sowohl
 * realistische getUserMedia-Rest-Latenz (nach dem Promise-Resolve) als auch den
 * dokumentierten MediaRecorder/Opus-Anlauf ab, ohne die gefühlte Reaktion auf den
 * Mikro-Tap spürbar zu verzögern — EINE Konstante, EIN Ort (Andi-Auftrag 26.07).
 */
export const PRE_ROLL_MS = 500;

/**
 * Ringpuffer-Zustand: feste Kapazität in Samples (`sampleRate * ms / 1000`),
 * gefüllt als Liste roher Float32-Chunks (älteste zuerst). `length` ist die
 * laufende Summe aller Chunk-Längen — Cache, damit {@link pushPreRoll} nicht bei
 * jedem Aufruf neu aufsummieren muss (läuft alle paar ms).
 */
export interface PreRollRing {
  readonly capacity: number;
  readonly sampleRate: number;
  chunks: Float32Array[];
  length: number;
}

/** Frischer, leerer Ring für `sampleRate` mit `ms` Kapazität (Default {@link PRE_ROLL_MS}). */
export function createPreRollRing(sampleRate: number, ms: number = PRE_ROLL_MS): PreRollRing {
  return {
    capacity: Math.max(0, Math.round((sampleRate * ms) / 1000)),
    sampleRate,
    chunks: [],
    length: 0,
  };
}

/**
 * Hängt `chunk` hinten an und schneidet vorn ab, bis die Kapazität wieder
 * eingehalten ist — nötigenfalls mit einem TEIL-Chunk (`subarray`), nicht nur
 * ganzen Chunks, damit der Ring exakt bei `capacity` Samples bleibt (kein
 * Überschwappen um bis zu einer Chunk-Länge). Mutiert `ring` in-place (wird pro
 * Audio-Callback aufgerufen; ein neues Objekt je Tick wäre Müll ohne Nutzen —
 * derselbe Grund wie bei {@link ../audio/vad.ts!vadStep}).
 */
export function pushPreRoll(ring: PreRollRing, chunk: Float32Array): void {
  if (chunk.length === 0 || ring.capacity === 0) return;
  ring.chunks.push(chunk);
  ring.length += chunk.length;
  while (ring.length > ring.capacity && ring.chunks.length > 0) {
    const oldest = ring.chunks[0];
    const excess = ring.length - ring.capacity;
    if (excess >= oldest.length) {
      ring.chunks.shift();
      ring.length -= oldest.length;
    } else {
      ring.chunks[0] = oldest.subarray(excess);
      ring.length -= excess;
    }
  }
}

/**
 * Fügt alle Chunks zu EINEM Float32Array zusammen (älteste zuerst = chronologisch).
 * Reine Lese-Operation — der Ring bleibt unverändert (s. {@link clearPreRoll} zum
 * Leeren).
 */
export function drainPreRoll(ring: PreRollRing): Float32Array {
  const out = new Float32Array(ring.length);
  let offset = 0;
  for (const chunk of ring.chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

/**
 * Leert den Ring (keine Chunks, `length = 0`) — für den Stream-Ende-Fall: „Mikro-
 * Stream offen ⇒ Ring läuft, Stream zu ⇒ Ring weg" (kein dauerhaftes Mitschneiden
 * ohne aktive Voice-Session/Anlern-Dialog).
 */
export function clearPreRoll(ring: PreRollRing): void {
  ring.chunks = [];
  ring.length = 0;
}

/**
 * Stellt `preRoll` vor `main` — die eigentliche Prefix-Logik. Leerer `preRoll`
 * (kein Web Audio verfügbar, oder noch nichts eingefangen) gibt `main`
 * unverändert zurück, statt sinnlos zu kopieren.
 */
export function prependPreRoll(preRoll: Float32Array, main: Float32Array): Float32Array {
  if (preRoll.length === 0) return main;
  const out = new Float32Array(preRoll.length + main.length);
  out.set(preRoll, 0);
  out.set(main, preRoll.length);
  return out;
}
