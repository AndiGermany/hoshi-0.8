import { describe, it, expect } from 'vitest';
import { SseDecoder, parseChatEvent } from '../api/sse';
import type { ChatEvent } from '../api/types';

const isDelta = (e: ChatEvent | null): e is Extract<ChatEvent, { event: 'delta' }> =>
  e?.event === 'delta';

describe('SseDecoder + parseChatEvent', () => {
  it('parst einen vollständigen ChatEvent-Frame', () => {
    const d = new SseDecoder();
    const out = d.push(
      'data:{"event":"start","provider":"LOCAL","category":"SMALLTALK","model":"brain"}\n\n',
    );
    expect(out).toHaveLength(1);
    expect(parseChatEvent(out[0])?.event).toBe('start');
  });

  it('puffert über Chunk-Grenzen hinweg (Stream-Split mitten im JSON)', () => {
    const d = new SseDecoder();
    expect(d.push('data:{"event":"del')).toEqual([]); // unvollständig → nichts
    const out = d.push('ta","text":"Hallo"}\n\n');
    expect(out).toHaveLength(1);
    expect(parseChatEvent(out[0])).toMatchObject({ event: 'delta', text: 'Hallo' });
  });

  it('rendert eine start→delta→delta→done-Sequenz in Reihenfolge', () => {
    const d = new SseDecoder();
    const stream =
      'data:{"event":"start","provider":"LOCAL","category":"X","model":"m"}\n\n' +
      'data:{"event":"delta","text":"Hal"}\n\n' +
      'data:{"event":"delta","text":"lo"}\n\n' +
      'data:{"event":"done","provider":"LOCAL"}\n\n';
    const events = d.push(stream).map(parseChatEvent);
    expect(events.map((e) => e?.event)).toEqual(['start', 'delta', 'delta', 'done']);
    const text = events.filter(isDelta).map((e) => e.text).join('');
    expect(text).toBe('Hallo');
  });

  it('ignoriert Heartbeat-Kommentarzeilen und liefert den letzten Frame via flush', () => {
    const d = new SseDecoder();
    expect(d.push(':keep-alive\n\n')).toEqual([]); // Kommentar → kein Event
    d.push('data:{"event":"done"}'); // ohne abschließende Leerzeile gepuffert
    const flushed = d.flush();
    expect(parseChatEvent(flushed[0])?.event).toBe('done');
  });

  it('parseChatEvent ist robust gegen Müll', () => {
    expect(parseChatEvent('nicht json')).toBeNull();
    expect(parseChatEvent('{"kein":"event"}')).toBeNull();
  });
});
