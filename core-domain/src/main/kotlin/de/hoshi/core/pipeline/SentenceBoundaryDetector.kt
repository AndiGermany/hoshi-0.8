package de.hoshi.core.pipeline

/**
 * Reine Utility-Funktion zum Erkennen von Satz-Endgrenzen im LLM-Stream
 * (PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger).
 *
 * Klar als pure function dokumentiert: kein State, kein I/O. Der Satz-Grenz-Puffer
 * fürs Streaming-TTS baut hierauf auf (der Reactor-verdrahtete StreamBuilder kommt
 * im Orchestrator-Wiring, M2b).
 */
object SentenceBoundaryDetector {

    /**
     * Findet die erste Satz-Endgrenze ab `minChars`.
     *
     * Punctuation-Set ist konfigurierbar (Default ".!?:,;") um auch früher bei
     * Aufzählungen oder Listen ohne finalem Punkt zu triggern.
     *
     * **Ordinal-Guard** (Andis Juli-Pitch-Befund 2026-07-01): ein '.' nach 1-2
     * Ziffern ist ein Ordinal-Datum ("der 1. Juli") und KEINE Satzgrenze, wenn
     * danach (nach optionalem Whitespace) ein Buchstabe folgt. Steht so ein '.'
     * am Puffer-Ende (Streaming-Race: der Folgetext ist noch nicht da), liefert
     * die Funktion -1, damit der Aufrufer auf mehr Text bzw. den Done-Flush
     * wartet -- sonst startet die TTS den Folge-Chunk mit frischer Satz-Prosodie
     * (hoerbarer Pitch-Sprung auf "Juli"). Jahreszahlen (4 Ziffern, "2026.")
     * splitten weiterhin normal.
     *
     * @return Index des Trennzeichens, oder -1 wenn nicht gefunden / Text zu
     *   kurz / Ordinal-Kandidat am Puffer-Ende (auf mehr Text warten).
     */
    fun firstSentenceBoundary(
        text: String,
        minChars: Int,
        punctuation: String = ".!?:,;",
    ): Int {
        if (text.length < minChars) return -1
        for (i in minChars - 1 until text.length) {
            val c = text[i]
            if (c !in punctuation) continue
            if (c == '.' && hasOrdinalDigitsBefore(text, i)) {
                val next = firstNonWhitespaceAfter(text, i)
                if (next == null) return -1 // Streaming-Race: Folgetext abwarten
                if (next.isLetter()) continue // Ordinal ("1. Juli") -> keine Grenze
            }
            return i
        }
        return -1
    }

    /** true, wenn direkt vor [dotIndex] genau 1-2 Ziffern stehen (Ordinal; 4 Ziffern = Jahr). */
    private fun hasOrdinalDigitsBefore(text: String, dotIndex: Int): Boolean {
        var j = dotIndex - 1
        while (j >= 0 && text[j].isDigit()) j--
        return (dotIndex - 1 - j) in 1..2
    }

    /** Erstes Nicht-Whitespace-Zeichen nach [index], oder null (Puffer-Ende erreicht). */
    private fun firstNonWhitespaceAfter(text: String, index: Int): Char? {
        for (j in index + 1 until text.length) {
            if (!text[j].isWhitespace()) return text[j]
        }
        return null
    }
}
