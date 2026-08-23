import { VoiceRecorder } from './recorder';
import { ENROLL_SAMPLE_RATE, webmBlobToWav } from './wav';

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
 *
 * `onLevel` (design 2026-08-15 §3.3): the enrol overlay needs the SAME live RMS
 * the chat bar already gets — it drives the waveform AND the client-side minimum
 * check before upload. Optional on purpose: a capture without a meter is still a
 * valid capture (the recorder's level chain is best-effort, and a test fake has
 * no meter at all). No meter ⇒ no measurement ⇒ no verdict, see
 * `checkEnrollSample` in SpeakerSection.tsx.
 */
export function createBrowserEnrollCapture(onLevel?: (level: number) => void): EnrollCapture {
  const recorder = new VoiceRecorder(onLevel ? { onLevel } : {});
  return {
    start: () => recorder.start(),
    stop: async () => {
      const recorded = await recorder.stop();
      // Anlaut-Fix (Andi-Befund 26.07, s. audio/preRoll.ts): derselbe Ring-Vorlauf
      // wie im Voice-Turn-Pfad (useVoiceChatSession.ts) — Anlern-Aufnahmen leiden
      // an genau derselben Beschneidung, wenn man direkt nach dem Tap losspricht.
      return webmBlobToWav(recorded, ENROLL_SAMPLE_RATE, {
        samples: recorder.getPreRoll(),
        sampleRate: recorder.getPreRollSampleRate(),
      });
    },
    cancel: () => recorder.cancel(),
  };
}
