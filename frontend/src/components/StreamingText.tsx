/**
 * Zerlegt einen (wachsenden) Streaming-Text in „Wort-Token" für die
 * Wort-für-Wort-Enthüllung. Jeder Token = führender Whitespace + ein Wort +
 * folgender Whitespace, sodass gilt:
 *   - `tokenizeStream(text).join('') === text` (kein Zeichen geht verloren →
 *     Auswahl/Kopieren bleibt korrekt).
 *   - WÄCHST der Text um neue Deltas, ändert sich nur der LETZTE Token (er füllt
 *     sich, bis ein Whitespace ihn abschließt) oder es kommt ein neuer Token
 *     hinten dazu. Die vorderen Token bleiben byte-stabil — darum behält React
 *     deren `<span>`-Identität und die Einblend-Animation läuft pro Wort genau
 *     EINMAL (statt bei jedem Delta neu zu flackern).
 */
export function tokenizeStream(text: string): string[] {
  if (!text) return [];
  // \s*\S+\s*  →  optionaler Vor-Whitespace, ein Wort, folgender Whitespace.
  const toks = text.match(/\s*\S+\s*/g);
  // Reiner Whitespace (kein \S) matcht nicht → unverändert durchreichen.
  return toks ?? [text];
}

/** Drei atmende Punkte: „Hoshi denkt nach", solange noch kein Wort da ist. */
function ThinkingDots() {
  return (
    <span className="thinking" role="status" aria-label="Hoshi denkt nach…">
      <span className="thinking__dot" aria-hidden="true" />
      <span className="thinking__dot" aria-hidden="true" />
      <span className="thinking__dot" aria-hidden="true" />
    </span>
  );
}

/**
 * Inhalt EINER aktiv streamenden Hoshi-Antwort.
 *
 * Ablauf: solange WIRKLICH noch kein Wort da ist (`text === ''`), atmen die
 * Denk-Punkte. Das Gate öffnet beim ERSTEN Delta — kein erzwungenes Minimum,
 * das echtes Streaming bremst: kommt das erste Wort sofort, steht es sofort da.
 * Kein Flackern, weil die Punkte ausschließlich bei leerem Text erscheinen
 * (und nicht für eine feste Dauer „nachgehalten" werden).
 *
 * Jedes neu erscheinende Wort wird in `<span class="tok">` gehüllt; dessen
 * CSS-Animation (`tok-in`) läuft beim Mount genau einmal. Eine FERTIGE Antwort
 * wird in {@link ChatView} gar nicht mehr über diese Komponente, sondern als
 * reiner Text gerendert → kein laufendes `animation`, voll selektierbar.
 */
export function StreamingBody({ text }: { text: string }) {
  // Noch kein einziges Wort verstanden → atmende Denk-Punkte. Sobald das erste
  // Delta ankommt, sofort der Wort-für-Wort-Reveal (Gate öffnet beim 1. Delta).
  if (text === '') return <ThinkingDots />;

  return (
    <>
      {tokenizeStream(text).map((tok, i) => (
        // key = Index: die Token sind append-stabil (s. tokenizeStream), darum
        // bleibt jede <span>-Identität erhalten und animiert nur einmal.
        <span key={i} className="tok">
          {tok}
        </span>
      ))}
      {/* Blinkender Schreib-Caret am Schwanz des laufenden Streams (530ms steps);
          verschwindet, sobald die Antwort fertig ist (dann rendert ChatView sie
          als reinen Text ohne diese Komponente). */}
      <span className="caret" aria-hidden="true" />
    </>
  );
}
