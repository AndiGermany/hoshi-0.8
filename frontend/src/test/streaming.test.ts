import { describe, it, expect } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { tokenizeStream, StreamingBody } from '../components/StreamingText';

// Der Wort-Reveal hüllt jeden Token in <span class="tok">. Die Animation darf
// pro Wort nur EINMAL laufen — das hängt daran, dass tokenizeStream beim
// Wachsen des Streams keine Zeichen verliert UND die vorderen Token stabil hält
// (nur der letzte Token wächst / es kommt hinten einer dazu). Diese reinen
// Eigenschaften sind hier abgesichert.
describe('tokenizeStream — Wort-Token für den Streaming-Reveal', () => {
  it('leerer Text → keine Token (zeigt stattdessen die Denk-Punkte)', () => {
    expect(tokenizeStream('')).toEqual([]);
  });

  it('verliert kein Zeichen: join() rekonstruiert den Text exakt', () => {
    for (const text of [
      'Hallo Hoshi',
      '  führender Whitespace',
      'mehrere   Leerzeichen   dazwischen',
      'Zeilen\numbruch\nbleibt',
      'Satzzeichen, bleiben! korrekt?',
      'trailing space ',
    ]) {
      expect(tokenizeStream(text).join('')).toBe(text);
    }
  });

  it('trennt in Wörter (Wort + folgender Whitespace je Token)', () => {
    expect(tokenizeStream('Hallo Welt')).toEqual(['Hallo ', 'Welt']);
    expect(tokenizeStream('Hallo Welt ')).toEqual(['Hallo ', 'Welt ']);
  });

  it('append-stabil: ein abgeschlossener Token bleibt Prefix beim Weiterwachsen', () => {
    // „Hallo wor" → „Hallo world fo": der erste Token ändert sich NICHT,
    // sodass dessen <span> erhalten bleibt und nicht erneut animiert.
    const a = tokenizeStream('Hallo wor');
    const b = tokenizeStream('Hallo world fo');
    expect(a[0]).toBe('Hallo ');
    expect(b[0]).toBe('Hallo ');
    expect(b.slice(0, 1)).toEqual(a.slice(0, 1));
  });

  it('reiner Whitespace matcht nicht → wird unverändert durchgereicht', () => {
    expect(tokenizeStream('   ')).toEqual(['   ']);
  });
});

// Das „Gate": solange WIRKLICH noch kein Wort da ist, atmen die Denk-Punkte;
// sobald das ERSTE Delta ankommt, steht es SOFORT da — kein erzwungenes 350ms-
// Minimum mehr, das echtes Streaming bremst. Static-Markup reicht (kein Timer).
const renderBody = (text: string) => renderToStaticMarkup(createElement(StreamingBody, { text }));

describe('StreamingBody — Gate öffnet beim ersten Delta', () => {
  it('leerer Text → atmende Denk-Punkte, noch kein Wort-Reveal', () => {
    const html = renderBody('');
    expect(html).toContain('thinking__dot');
    expect(html).not.toContain('class="tok"');
  });

  it('erstes Delta sofort sichtbar → Wort-Reveal ohne Wartezeit, keine Punkte mehr', () => {
    const html = renderBody('Hallo');
    expect(html).toContain('class="tok"');
    expect(html).toContain('Hallo');
    expect(html).not.toContain('thinking__dot');
  });
});
