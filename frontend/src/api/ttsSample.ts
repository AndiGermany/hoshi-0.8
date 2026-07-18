import { API_BASE, TOKEN } from './config';

/**
 * Sofort-Hörprobe des Stimmen-Pickers: `GET /api/v1/settings/tts/sample?voice=<name>`
 * → WAV-Blob (das Backend synthetisiert EINEN kurzen festen Satz).
 *
 * ☁️ Ehrlichkeit: JEDER Abruf ist ein Cloud-Call zu OpenAI (~Cent-Bruchteil) —
 * bewusst nur auf Klick (▶ im Panel), nie automatisch. Token wie überall als
 * `X-Hoshi-Token`; Fehler (503 = TTS grad nicht möglich, 401 = Auth-Wand) werden
 * geworfen — der Aufrufer zeigt eine leise ehrliche Zeile statt zu schweigen.
 */
export async function fetchVoiceSample(voice: string, signal?: AbortSignal): Promise<Blob> {
  const headers: Record<string, string> = {};
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;

  const res = await fetch(
    `${API_BASE}/api/v1/settings/tts/sample?voice=${encodeURIComponent(voice)}`,
    { headers, signal },
  );
  if (!res.ok) {
    throw new Error(`Hörprobe: Backend antwortete HTTP ${res.status}`);
  }
  return res.blob();
}
