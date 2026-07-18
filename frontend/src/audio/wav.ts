// Stimm-Anlernen (S2a): der Enroll-Sidecar (`/embed`) dekodiert über
// `soundfile`/libsndfile — er versteht NUR WAV/FLAC/OGG, KEIN webm/opus/mp3
// (anders als der Voice-Turn-Pfad, der ffmpeg+Whisper nutzt). Der Browser nimmt
// über `MediaRecorder` aber webm/opus auf. Diese Datei ist die Brücke: sie
// dekodiert die Aufnahme (AudioContext) und kodiert einen **WAV/PCM16-mono**-
// Blob, den das Backend-Contract-Shape verlangt (PCM16 mono, 16 kHz empfohlen).
//
// Die reinen, hardware-freien Teile (`encodeWavPcm16`, `mixToMono`,
// `downsampleTo`) sind bewusst DOM-los und deterministisch — im `node`-Vitest
// ohne echtes Mikro/AudioContext testbar. Nur `webmBlobToWav` braucht einen
// echten `AudioContext` (Browser) und lebt darum an der dünnsten Naht.

/** Ziel-Abtastrate der Enroll-WAV (Backend empfiehlt 16 kHz, resampled eh auf 16k). */
export const ENROLL_SAMPLE_RATE = 16000;

/** Vier ASCII-Zeichen in den DataView schreiben (RIFF/WAVE/fmt /data-Marker). */
function writeString(view: DataView, offset: number, text: string): void {
  for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i));
}

/**
 * Kodiert Mono-Float32-Samples (−1..1) als **WAV/PCM16-Little-Endian**-Container
 * (44-Byte-RIFF-Header + Sample-Daten). Reine Funktion: keine DOM-/Hardware-
 * Abhängigkeit, deterministisch. Der RIFF-Magic (`RIFF`…`WAVE`) ist genau das,
 * was der Sidecar per Container-Autoerkennung liest — die `sampleRate` steht im
 * Header korrekt, damit libsndfile richtig liest.
 */
export function encodeWavPcm16(samples: Float32Array, sampleRate: number): ArrayBuffer {
  const numChannels = 1;
  const bytesPerSample = 2;
  const blockAlign = numChannels * bytesPerSample;
  const byteRate = sampleRate * blockAlign;
  const dataSize = samples.length * bytesPerSample;
  const buffer = new ArrayBuffer(44 + dataSize);
  const view = new DataView(buffer);

  writeString(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true); // Chunk-Size = 36 + Daten
  writeString(view, 8, 'WAVE');
  writeString(view, 12, 'fmt ');
  view.setUint32(16, 16, true); // fmt-Chunk-Länge (PCM)
  view.setUint16(20, 1, true); // AudioFormat = 1 (PCM, unkomprimiert)
  view.setUint16(22, numChannels, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, 16, true); // 16 Bit pro Sample
  writeString(view, 36, 'data');
  view.setUint32(40, dataSize, true);

  let offset = 44;
  for (let i = 0; i < samples.length; i++) {
    // Clampen und auf signed 16-Bit skalieren (negativ mit 0x8000, positiv 0x7fff).
    const clamped = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(offset, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
    offset += 2;
  }
  return buffer;
}

/** Bequemer Wrapper: WAV-Blob (`audio/wav`) aus Mono-Samples — genau der Enroll-Part. */
export function wavBlobFromPcm(samples: Float32Array, sampleRate: number): Blob {
  return new Blob([encodeWavPcm16(samples, sampleRate)], { type: 'audio/wav' });
}

/** Nur das, was `mixToMono` von einem `AudioBuffer` braucht (duck-typed → testbar). */
export interface MonoMixSource {
  numberOfChannels: number;
  length: number;
  getChannelData(channel: number): Float32Array;
}

/**
 * Mischt einen (mehrkanaligen) AudioBuffer auf einen Mono-Float32-Kanal herunter
 * (arithmetisches Mittel). Mono bleibt Mono (Kopie). Der Sidecar will mono; eine
 * Stereo-Aufnahme sonst falsch interpretiert.
 */
export function mixToMono(buf: MonoMixSource): Float32Array {
  const channels = buf.numberOfChannels;
  if (channels <= 1) return buf.getChannelData(0).slice();
  const out = new Float32Array(buf.length);
  for (let c = 0; c < channels; c++) {
    const data = buf.getChannelData(c);
    for (let i = 0; i < out.length; i++) out[i] += data[i] / channels;
  }
  return out;
}

/**
 * Lineare Resampling-Dezimierung auf eine niedrigere Rate (z. B. 48k → 16k).
 * Reine Mathematik. Ist die Zielrate ≥ Quellrate (oder Quelle 0), bleiben die
 * Samples unverändert — kein Hochrechnen erfinden (das Backend resampled ohnehin
 * intern auf 16k, wir schrumpfen nur die Nutzlast).
 */
export function downsampleTo(samples: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (fromRate === 0 || toRate >= fromRate) return samples;
  const ratio = fromRate / toRate;
  const outLength = Math.floor(samples.length / ratio);
  const out = new Float32Array(outLength);
  for (let i = 0; i < outLength; i++) {
    const pos = i * ratio;
    const idx = Math.floor(pos);
    const frac = pos - idx;
    const a = samples[idx] ?? 0;
    const b = samples[idx + 1] ?? a;
    out[i] = a + (b - a) * frac; // lineare Interpolation zwischen zwei Samples
  }
  return out;
}

/** Fehler, wenn der Browser die Aufnahme nicht in WAV umwandeln kann (kein AudioContext). */
export class WavConvertError extends Error {
  constructor(message = 'Aufnahme ließ sich nicht in WAV umwandeln.') {
    super(message);
    this.name = 'WavConvertError';
  }
}

/** Minimaler AudioContext-Vertrag für die Konvertierung (injizierbar/testbar). */
interface DecodingAudioContext {
  sampleRate: number;
  decodeAudioData(data: ArrayBuffer): Promise<AudioBuffer>;
  close(): Promise<void>;
}

/**
 * Dekodiert einen aufgenommenen Blob (webm/opus, mp4, …) über einen echten
 * `AudioContext` und kodiert ihn als **16-kHz-Mono-WAV/PCM16** — genau das
 * Format, das der `/embed`-Sidecar (libsndfile) lesen kann. Browser-Naht: nur
 * hier lebt die `AudioContext`-Abhängigkeit; die eigentliche Arithmetik oben ist
 * rein und getestet. Wirft {@link WavConvertError}, wenn kein Web-Audio da ist.
 */
export async function webmBlobToWav(
  blob: Blob,
  targetSampleRate = ENROLL_SAMPLE_RATE,
): Promise<Blob> {
  const Ctor =
    (globalThis as { AudioContext?: new () => DecodingAudioContext }).AudioContext ??
    (globalThis as { webkitAudioContext?: new () => DecodingAudioContext }).webkitAudioContext;
  if (!Ctor) throw new WavConvertError('Dieser Browser kann Audio nicht dekodieren.');

  const arrayBuffer = await blob.arrayBuffer();
  const ctx = new Ctor();
  try {
    const audioBuffer = await ctx.decodeAudioData(arrayBuffer);
    const mono = mixToMono(audioBuffer);
    const down = downsampleTo(mono, audioBuffer.sampleRate, targetSampleRate);
    // Nach dem Downsampling trägt die WAV die Zielrate (sonst 16k, falls Quelle < 16k war).
    const rate = down === mono && audioBuffer.sampleRate < targetSampleRate
      ? audioBuffer.sampleRate
      : targetSampleRate;
    return wavBlobFromPcm(down, rate);
  } catch (err) {
    if (err instanceof WavConvertError) throw err;
    throw new WavConvertError();
  } finally {
    try {
      void ctx.close();
    } catch {
      /* egal — Best-effort-Cleanup */
    }
  }
}

/**
 * **Voice-Turn-Upload (Speaker-Format-Parität):** bereitet die PTT-Aufnahme für
 * `POST /api/v1/voice` auf — exakt wie der Enroll-Pfad ({@link webmBlobToWav},
 * 16-kHz-Mono-WAV). Grund: der Speaker-Sidecar liest NUR RIFF/WAV und
 * fehl-dekodiert webm/opus STILL als PCM16-Müll (Score ~0.226 statt 0.5–0.8) —
 * die Sprecher-Erkennung war damit im Voice-Turn strukturell blind, obwohl das
 * Anlernen funktionierte. Whisper/STT liest WAV genauso.
 *
 * Kosten, ehrlich: ein Browser-Decode + WAV-Encode (bei PTT-Utterances von
 * wenigen Sekunden ein Sekundenbruchteil) und ein größerer Upload (16k-Mono-
 * PCM16 = 32 KB/s ≈ 160 KB für 5 s, statt ~15 KB Opus) — im LAN vernachlässigbar.
 *
 * Fallback statt Fehler: scheitert die Konvertierung (kein Web-Audio/Decode),
 * geht der ROHE Blob raus — STT (ffmpeg) versteht ihn weiterhin, nur der
 * Sprecher-Score leidet. Lieber ein Turn ohne „Wer sprach" als gar kein Turn.
 */
export async function voiceTurnUploadBlob(recorded: Blob): Promise<Blob> {
  try {
    return await webmBlobToWav(recorded);
  } catch {
    return recorded;
  }
}
