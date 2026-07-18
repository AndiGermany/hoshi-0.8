import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import {
  LEVEL_FLOOR_OPEN,
  WAVE_LINE_WIDTH_PX,
  lerpLevel,
  materializePresence,
  openLevelTarget,
  speakMix,
  waveOffsetFraction,
  waveSample,
  waveTimeStep,
} from '../audio/motionTokens';

export interface VoiceWaveformHandle {
  /** Echten Audio-Pegel (0..1, gamma-gemappt) einspeisen — er TREIBT die Welle. */
  push: (level: number) => void;
  /** Zurück auf den Floor (Kanal-/Turn-Wechsel): Welle schwillt frisch an. */
  reset: () => void;
}

/** Zeichen-Schrittweite in CSS-px — glatt genug für die Sinusse, billig genug für 60fps. */
const SAMPLE_STEP_PX = 2;

/**
 * **VoiceWaveform** — die lebende Welle der Compose-Bar (Cowork-Spec §3,
 * korrigiert 2026-07-06: „Nichts leuchtet, was nichts misst").
 *
 * Die Welle wird NUR gemountet, wenn ein Audio-Kanal offen ist (ChatView:
 * Mikro hört / Hoshi spricht) — ihr Erscheinen IST das Signal „jetzt höre
 * ich". Es gibt KEIN synthetisches Idle-Atmen mehr; die Übersicht rendert
 * sie gar nicht.
 *
 * Form vs. Treiber (WhatsApp-Prinzip bleibt Gesetz):
 *  - Die FORM sind die 3 überlagerten Sinusse + Speak-Sinus mit Halbwellen-
 *    Envelope aus {@link ../audio/motionTokens} (geteiltes Token-Set,
 *    später auch für die Satelliten-LED-Zeile).
 *  - Der TREIBER ist der ECHTE Pegel: ChatView schiebt ihn imperativ per
 *    {@link VoiceWaveformHandle.push} herein (Hören: recorder.onLevel ·
 *    Sprechen: AnalyserNode der AudioQueue) — kein React-Re-render pro Frame.
 *  - Offener Kanal ⇒ Floor: unter {@link LEVEL_FLOOR_OPEN} (0,35) fällt das
 *    Ziel nie ({@link openLevelTarget}) — Sprechpausen bleiben ein sichtbarer,
 *    ruhiger Sockel (der Kanal IST offen), Sprache schlägt darüber aus.
 *  - Übergänge weich per Lerp (·0,06/Frame); Speak-Sinus, Amplituden-Faktor
 *    und Zeitschritt blenden mit dem Pegel ({@link speakMix}).
 *  - Materialisieren: die Amplitude wächst in ~200 ms von 0 auf voll
 *    ({@link materializePresence}) — Canvas-Skalierung, KEINE opacity
 *    (Chromes Occlusion-Throttling friert opacity-Animationen ein).
 *  - Optik: EINE ~1,5px-Linie, lineCap round, Farbe accent (via CSS
 *    `color: var(--accent)` am Canvas), KEIN Glow/Gradient.
 *  - `prefers-reduced-motion` → sofort da (kein Fade, kein rAF-Loop): ein
 *    statisches Standbild auf dem Floor-Pegel als ehrliches „Kanal offen".
 */
export const VoiceWaveform = forwardRef<VoiceWaveformHandle, { className?: string }>(
  function VoiceWaveform({ className }, ref) {
    const canvasRef = useRef<HTMLCanvasElement | null>(null);
    // Pegel-Zustand lebt in Refs (imperativer Pfad, kein State/Re-render).
    const levelRef = useRef(0); // aktueller (gelerpter) Pegel
    const targetRef = useRef(LEVEL_FLOOR_OPEN); // Ziel: echter Pegel, nie unterm Floor
    const tRef = useRef(0); // Wellen-Zeit

    useImperativeHandle(
      ref,
      () => ({
        push(level: number) {
          // Der echte Pegel treibt; der Kanal ist offen, also gilt der Floor —
          // Sprechpausen fallen weich auf 0,35 zurück, nie auf eine Null-Linie.
          targetRef.current = openLevelTarget(level);
        },
        reset() {
          levelRef.current = 0; // frisch anschwellen (Lerp holt das Ziel weich ein)
          targetRef.current = LEVEL_FLOOR_OPEN;
        },
      }),
      [],
    );

    useEffect(() => {
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (!canvas || !ctx) return; // jsdom/ältere Engines: kein 2D-Context → still

      // Ein Frame der Welle zeichnen (Form × Treiber × Materialisierung).
      const drawFrame = (level: number, t: number, presence: number) => {
        const dpr = globalThis.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        const w = Math.max(1, Math.round(rect.width * dpr));
        const h = Math.max(1, Math.round(rect.height * dpr));
        if (canvas.width !== w || canvas.height !== h) {
          canvas.width = w;
          canvas.height = h;
        }

        const mix = speakMix(level);
        const lineW = WAVE_LINE_WIDTH_PX * dpr;
        // Halbe Bandhöhe in px — waveOffsetFraction liefert den (geklemmten)
        // Anteil daran; alle Pegel-/Faktor-Mathematik lebt in motionTokens.
        const half = h / 2 - lineW;
        const mid = h / 2;

        ctx.clearRect(0, 0, w, h);
        ctx.lineWidth = lineW;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        // Farbe = accent des aktiven Themes (CSS `color` am Canvas). Kein Glow.
        ctx.strokeStyle = getComputedStyle(canvas).color;
        ctx.beginPath();
        const step = SAMPLE_STEP_PX * dpr;
        for (let x = 0; x <= w; x += step) {
          const u = x / w;
          const y =
            mid - waveOffsetFraction(waveSample(u, t, mix), level) * presence * half;
          if (x === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.stroke();
      };

      // reduced-motion: sofort da, kein Fade, kein rAF-Loop — EIN statisches
      // Standbild auf dem Floor (Kanal offen = Welle sichtbar, ehrlich ruhig).
      const reduced =
        typeof globalThis.matchMedia === 'function' &&
        globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches;
      if (reduced) {
        drawFrame(LEVEL_FLOOR_OPEN, 0, materializePresence(0, true));
        return;
      }

      let raf = 0;
      let startedAt = 0; // erster rAF-Timestamp = Beginn der Materialisierung
      const loop = (now: number) => {
        if (startedAt === 0) startedAt = now;
        // Treiber: Lerp Richtung Ziel (echter Pegel, nie unterm offenen Floor).
        levelRef.current = lerpLevel(levelRef.current, targetRef.current);
        drawFrame(levelRef.current, tRef.current, materializePresence(now - startedAt, false));
        tRef.current += waveTimeStep(levelRef.current);
        raf = requestAnimationFrame(loop);
      };
      raf = requestAnimationFrame(loop);
      return () => cancelAnimationFrame(raf);
    }, []);

    return (
      <div className={`vc-wave ${className ?? ''}`} aria-hidden="true">
        <canvas ref={canvasRef} className="vc-wave__canvas" />
      </div>
    );
  },
);
