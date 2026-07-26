import { describe, expect, it } from 'vitest';
import {
  PRE_ROLL_MS,
  clearPreRoll,
  createPreRollRing,
  drainPreRoll,
  prependPreRoll,
  pushPreRoll,
} from '../audio/preRoll';

/**
 * Pre-Roll-Ringpuffer gegen Anlaut-Beschneidung (Andi-Befund 26.07: „Wie zieht
 * eine Kuh die Hose an" → „Zieht eine Kuh eine Hose an" / „Hose" → „Rose").
 *
 * Getestet wird die REINE Ring-/Prefix-Logik ohne DOM/AudioContext — wie
 * vad.ts/level.ts bewusst testbar gehalten sind. Die Web-Audio-Verkabelung
 * (ScriptProcessorNode etc.) lebt in recorder.ts und ist dort nicht ohne echtes
 * Mikrofon prüfbar; hier zählt nur: füllt der Ring richtig, kappt er richtig,
 * leert er richtig, und stellt die Prefix-Funktion den Inhalt richtig voran?
 */

describe('PRE_ROLL_MS', () => {
  it('ist die EINE benannte Konstante (500ms, s. preRoll.ts-KDoc für die Herleitung)', () => {
    expect(PRE_ROLL_MS).toBe(500);
  });
});

describe('createPreRollRing — Kapazität', () => {
  it('rechnet Sample-Kapazität aus sampleRate*ms/1000', () => {
    const ring = createPreRollRing(48000, 500);
    expect(ring.capacity).toBe(24000);
    expect(ring.sampleRate).toBe(48000);
    expect(ring.length).toBe(0);
    expect(ring.chunks).toEqual([]);
  });

  it('rundet auf ganze Samples, negative/0-Kapazität nie', () => {
    const ring = createPreRollRing(16000, 1); // 16 Samples
    expect(ring.capacity).toBe(16);
    const zero = createPreRollRing(0, 500);
    expect(zero.capacity).toBe(0);
  });
});

describe('pushPreRoll — füllen', () => {
  it('sammelt Chunks in Reihenfolge, solange unter Kapazität', () => {
    const ring = createPreRollRing(1000, 100); // Kapazität 100 Samples
    pushPreRoll(ring, new Float32Array([1, 2, 3]));
    pushPreRoll(ring, new Float32Array([4, 5]));
    expect(ring.length).toBe(5);
    expect(drainPreRoll(ring)).toEqual(new Float32Array([1, 2, 3, 4, 5]));
  });

  it('ignoriert leere Chunks (kein Müll-Eintrag)', () => {
    const ring = createPreRollRing(1000, 100);
    pushPreRoll(ring, new Float32Array([]));
    expect(ring.length).toBe(0);
    expect(ring.chunks.length).toBe(0);
  });

  it('bei Kapazität 0 bleibt der Ring immer leer', () => {
    const ring = createPreRollRing(1000, 0);
    pushPreRoll(ring, new Float32Array([1, 2, 3]));
    expect(ring.length).toBe(0);
    expect(drainPreRoll(ring)).toEqual(new Float32Array([]));
  });
});

describe('pushPreRoll — überlaufen', () => {
  it('verwirft GANZE älteste Chunks, wenn sie komplett aus dem Fenster fallen', () => {
    const ring = createPreRollRing(1000, 5); // Kapazität 5 Samples
    pushPreRoll(ring, new Float32Array([1, 2])); // 2
    pushPreRoll(ring, new Float32Array([3, 4])); // 4
    pushPreRoll(ring, new Float32Array([5, 6])); // 6 → 1 muss raus
    expect(ring.length).toBe(5);
    expect(drainPreRoll(ring)).toEqual(new Float32Array([2, 3, 4, 5, 6]));
  });

  it('schneidet einen TEIL-Chunk exakt an der Kapazitätsgrenze (kein Überschwappen)', () => {
    const ring = createPreRollRing(1000, 3); // Kapazität 3 Samples
    pushPreRoll(ring, new Float32Array([1, 2, 3, 4])); // 4 Samples auf einmal
    expect(ring.length).toBe(3); // exakt gekappt, nicht auf ganze Chunks gerundet
    expect(drainPreRoll(ring)).toEqual(new Float32Array([2, 3, 4]));
  });

  it('bleibt bei fortgesetztem Überlauf stets bei genau der Kapazität ("letzte N Samples")', () => {
    const ring = createPreRollRing(1000, 4);
    for (let i = 1; i <= 10; i++) pushPreRoll(ring, new Float32Array([i]));
    expect(ring.length).toBe(4);
    expect(drainPreRoll(ring)).toEqual(new Float32Array([7, 8, 9, 10]));
  });
});

describe('clearPreRoll — leeren', () => {
  it('entfernt alle Chunks, length zurück auf 0', () => {
    const ring = createPreRollRing(1000, 100);
    pushPreRoll(ring, new Float32Array([1, 2, 3]));
    clearPreRoll(ring);
    expect(ring.length).toBe(0);
    expect(ring.chunks).toEqual([]);
    expect(drainPreRoll(ring)).toEqual(new Float32Array([]));
  });

  it('ein danach gepushter Chunk startet wieder bei 0 — kein Restmüll', () => {
    const ring = createPreRollRing(1000, 100);
    pushPreRoll(ring, new Float32Array([9, 9, 9]));
    clearPreRoll(ring);
    pushPreRoll(ring, new Float32Array([1]));
    expect(drainPreRoll(ring)).toEqual(new Float32Array([1]));
  });
});

describe('drainPreRoll — reine Lese-Operation', () => {
  it('verändert den Ring nicht (zweimal drainen liefert dasselbe)', () => {
    const ring = createPreRollRing(1000, 100);
    pushPreRoll(ring, new Float32Array([1, 2, 3]));
    const first = drainPreRoll(ring);
    const second = drainPreRoll(ring);
    expect(first).toEqual(second);
    expect(ring.length).toBe(3);
  });
});

describe('prependPreRoll — Prefix-Logik (Aufnahme enthält Ring-Inhalt am Anfang)', () => {
  it('stellt die Pre-Roll-Samples VOR die Hauptaufnahme', () => {
    const preRoll = new Float32Array([0.1, 0.2]); // z.B. das verschluckte "Wie"
    const main = new Float32Array([0.3, 0.4, 0.5]); // "zieht eine Kuh..."
    const out = prependPreRoll(preRoll, main);
    expect(out).toEqual(new Float32Array([0.1, 0.2, 0.3, 0.4, 0.5]));
    expect(out.length).toBe(preRoll.length + main.length);
  });

  it('leerer Pre-Roll ⇒ Hauptaufnahme UNVERÄNDERT zurück (keine sinnlose Kopie)', () => {
    const main = new Float32Array([0.3, 0.4]);
    const out = prependPreRoll(new Float32Array([]), main);
    expect(out).toBe(main); // exakt dieselbe Referenz, kein Klon
  });

  it('leere Hauptaufnahme + gefüllter Pre-Roll ⇒ nur der Pre-Roll', () => {
    const preRoll = new Float32Array([0.7, 0.8]);
    const out = prependPreRoll(preRoll, new Float32Array([]));
    expect(out).toEqual(preRoll);
  });

  it('mutiert weder preRoll noch main (reine Funktion)', () => {
    const preRoll = new Float32Array([1, 2]);
    const main = new Float32Array([3, 4]);
    prependPreRoll(preRoll, main);
    expect(preRoll).toEqual(new Float32Array([1, 2]));
    expect(main).toEqual(new Float32Array([3, 4]));
  });
});
