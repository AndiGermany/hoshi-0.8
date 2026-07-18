import type { ChatEvent } from './types';

/**
 * Inkrementeller SSE-Decoder.
 *
 * Spring serialisiert je {@link ChatEvent} als einen `data:<json>`-Frame im
 * `text/event-stream` (Frames durch eine Leerzeile getrennt). Diese Klasse
 * puffert Teil-Chunks über `ReadableStream`-Grenzen hinweg und gibt nur
 * **vollständige** `data`-Payloads zurück. Pure Funktion, ohne DOM/Netz —
 * dadurch unit-testbar ohne Live-Backend.
 */
export class SseDecoder {
  private buffer = '';

  /** Füttert einen Chunk; liefert die fertig abgeschlossenen `data`-Payloads. */
  push(chunk: string): string[] {
    this.buffer += chunk.replace(/\r\n/g, '\n');
    const out: string[] = [];
    let idx: number;
    while ((idx = this.buffer.indexOf('\n\n')) !== -1) {
      const frame = this.buffer.slice(0, idx);
      this.buffer = this.buffer.slice(idx + 2);
      const data = extractData(frame);
      if (data !== null) out.push(data);
    }
    return out;
  }

  /** Rest am Stream-Ende (letzter Frame ohne abschließende Leerzeile). */
  flush(): string[] {
    const rest = this.buffer.trim();
    this.buffer = '';
    if (!rest) return [];
    const data = extractData(rest);
    return data !== null ? [data] : [];
  }
}

/** Zieht die `data:`-Zeilen aus einem Frame (SSE: mehrere `data:` werden per \n verbunden). */
function extractData(frame: string): string | null {
  const dataLines: string[] = [];
  for (const line of frame.split('\n')) {
    if (line.startsWith(':')) continue; // Kommentar / Heartbeat
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  }
  return dataLines.length > 0 ? dataLines.join('\n') : null;
}

/** Parst eine `data`-Payload in ein {@link ChatEvent} (null bei Müll/unbekannt). */
export function parseChatEvent(payload: string): ChatEvent | null {
  try {
    const obj: unknown = JSON.parse(payload);
    if (obj && typeof obj === 'object' && typeof (obj as { event?: unknown }).event === 'string') {
      return obj as ChatEvent;
    }
    return null;
  } catch {
    return null;
  }
}
