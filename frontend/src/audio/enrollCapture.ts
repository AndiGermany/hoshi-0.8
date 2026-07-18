import { VoiceRecorder } from './recorder';
import { webmBlobToWav } from './wav';

// Anlern-Aufnahme: kapselt Mikro-Aufnahme (`VoiceRecorder`, webm/opus) UND die
// Umwandlung in das **WAV**, das der Enroll-Contract verlangt. Bewusst hinter
// einem schmalen Interface ({@link EnrollCapture}), damit der Anlern-Dialog es
// als Prop bekommt und Tests eine Fake-Aufnahme einspeisen können (ohne echtes
// Mikrofon/AudioContext).

/**
 * Der Aufnahme-Vertrag des Anlern-Dialogs: `start()` öffnet das Mikro und nimmt
 * auf, `stop()` beendet und liefert einen **fertigen WAV-Blob** (nicht webm!),
 * `cancel()` verwirft. Fehler kommen als `VoiceRecorderError`/`WavConvertError`
 * heraus — der Dialog zeigt sie ehrlich statt eines Fake-Erfolgs.
 */
export interface EnrollCapture {
  start(): Promise<void>;
  stop(): Promise<Blob>;
  cancel(): void;
}

/**
 * Echte Browser-Aufnahme: nimmt webm/opus auf ({@link VoiceRecorder}) und wandelt
 * beim `stop()` in 16-kHz-Mono-WAV um ({@link webmBlobToWav}). Genau eine Instanz
 * pro Anlern-Lauf. Wirft die typisierten Aufnahme-/Konvert-Fehler weiter.
 */
export function createBrowserEnrollCapture(): EnrollCapture {
  const recorder = new VoiceRecorder();
  return {
    start: () => recorder.start(),
    stop: async () => {
      const recorded = await recorder.stop();
      return webmBlobToWav(recorded);
    },
    cancel: () => recorder.cancel(),
  };
}
