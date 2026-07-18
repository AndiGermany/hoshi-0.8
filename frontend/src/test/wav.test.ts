import { describe, it, expect } from 'vitest';
import {
  ENROLL_SAMPLE_RATE,
  downsampleTo,
  encodeWavPcm16,
  mixToMono,
  wavBlobFromPcm,
  type MonoMixSource,
} from '../audio/wav';

/** Vier ASCII-Bytes ab `offset` als String lesen (RIFF/WAVE/fmt /data-Marker prüfen). */
function ascii(buf: ArrayBuffer, offset: number, len = 4): string {
  const bytes = new Uint8Array(buf, offset, len);
  return String.fromCharCode(...bytes);
}

describe('encodeWavPcm16 — RIFF/WAVE/PCM16-mono-Container (Sidecar-lesbar)', () => {
  it('schreibt einen gültigen RIFF/WAVE-Header mit korrekten fmt-Feldern', () => {
    const samples = new Float32Array([0, 0.5, -0.5, 1]);
    const buf = encodeWavPcm16(samples, ENROLL_SAMPLE_RATE);
    const view = new DataView(buf);

    // Container-Magic: genau das, was libsndfile per RIFF-Autoerkennung liest.
    expect(ascii(buf, 0)).toBe('RIFF');
    expect(ascii(buf, 8)).toBe('WAVE');
    expect(ascii(buf, 12)).toBe('fmt ');
    expect(ascii(buf, 36)).toBe('data');

    expect(view.getUint16(20, true)).toBe(1); // AudioFormat = PCM
    expect(view.getUint16(22, true)).toBe(1); // mono
    expect(view.getUint32(24, true)).toBe(ENROLL_SAMPLE_RATE); // sampleRate im Header
    expect(view.getUint16(34, true)).toBe(16); // 16 Bit
    expect(view.getUint32(40, true)).toBe(samples.length * 2); // dataSize
    expect(buf.byteLength).toBe(44 + samples.length * 2); // Header + Daten
  });

  it('kodiert Samples als signed 16-Bit (±Vollausschlag, Null)', () => {
    const buf = encodeWavPcm16(new Float32Array([0, 1, -1]), 16000);
    const view = new DataView(buf);
    expect(view.getInt16(44, true)).toBe(0);
    expect(view.getInt16(46, true)).toBe(0x7fff); // +1.0 → +32767
    expect(view.getInt16(48, true)).toBe(-0x8000); // −1.0 → −32768
  });

  it('clampt Übersteuerung (>1 / <−1) auf die 16-Bit-Grenzen (kein Wrap-Around)', () => {
    const buf = encodeWavPcm16(new Float32Array([2, -2]), 16000);
    const view = new DataView(buf);
    expect(view.getInt16(44, true)).toBe(0x7fff);
    expect(view.getInt16(46, true)).toBe(-0x8000);
  });

  it('wavBlobFromPcm liefert einen Blob mit Typ audio/wav (genau der Enroll-Part)', () => {
    const blob = wavBlobFromPcm(new Float32Array([0.1, -0.1]), 16000);
    expect(blob.type).toBe('audio/wav');
    expect(blob.size).toBe(44 + 2 * 2);
  });
});

describe('mixToMono — Mehrkanal → ein Kanal', () => {
  const src = (channels: Float32Array[]): MonoMixSource => ({
    numberOfChannels: channels.length,
    length: channels[0].length,
    getChannelData: (c) => channels[c],
  });

  it('mono bleibt mono (Kopie der Werte, kein Alias)', () => {
    const data = new Float32Array([0.2, 0.4]);
    const out = mixToMono(src([data]));
    expect(out.length).toBe(2);
    expect(out[0]).toBeCloseTo(0.2, 6);
    expect(out[1]).toBeCloseTo(0.4, 6);
    expect(out).not.toBe(data); // Kopie — spätere Mutationen am Original ändern out nicht
  });

  it('stereo → arithmetisches Mittel beider Kanäle', () => {
    const left = new Float32Array([1, 0, -1]);
    const right = new Float32Array([0, 0, 1]);
    const out = mixToMono(src([left, right]));
    expect(out[0]).toBeCloseTo(0.5, 6);
    expect(out[1]).toBeCloseTo(0, 6);
    expect(out[2]).toBeCloseTo(0, 6);
  });
});

describe('downsampleTo — lineare Dezimierung auf 16k', () => {
  it('Zielrate ≥ Quellrate ⇒ unverändert (kein Hochrechnen erfinden)', () => {
    const s = new Float32Array([0, 1, 0, 1]);
    expect(downsampleTo(s, 16000, 16000)).toBe(s);
    expect(downsampleTo(s, 8000, 16000)).toBe(s);
  });

  it('48k → 16k drittelt die Länge (floor)', () => {
    const s = new Float32Array(30);
    const out = downsampleTo(s, 48000, 16000);
    expect(out.length).toBe(10);
  });

  it('interpoliert linear zwischen zwei Stützstellen', () => {
    // 4k → 2k: Ratio 2, Positionen 0,2 → exakte Samples (keine Interpolation nötig).
    const s = new Float32Array([0, 0.5, 1, 0.5]);
    const out = downsampleTo(s, 4000, 2000);
    expect(out.length).toBe(2);
    expect(out[0]).toBeCloseTo(0, 6);
    expect(out[1]).toBeCloseTo(1, 6);
  });
});
