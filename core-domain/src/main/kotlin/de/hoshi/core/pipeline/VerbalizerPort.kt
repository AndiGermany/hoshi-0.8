package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language

/**
 * **VerbalizerPort** — macht einen Text SPRECHBAR, bevor er an die TTS-Engine
 * geht: Ziffern/Symbole werden zu Worten ausgeschrieben ("21°C" → "einundzwanzig
 * Grad", "1.5" → "eins Komma fünf", je nach [Language]).
 *
 * **Warum das ein eigener Port ist:** piper/`say`/OpenAI-TTS normalisieren Zahlen
 * unterschiedlich — was die eine Engine sauber vorliest, buchstabiert die nächste
 * oder verschluckt es. SSML `say-as` wäre die naheliegende Antwort, wirkt aber bei
 * 2 von 3 Engines nicht. Der einzige Weg, der auf ALLEN Engines gleich klingt, ist
 * eine bereits ziffern-/symbolfreie Endform VOR dem TTS-Aufruf — das ist die
 * Aufgabe dieses Ports.
 *
 * [NONE] ist der verhaltens-neutrale Default: er liefert [text] unverändert
 * zurück (Identität) ⇒ ohne Wiring bleibt jeder Pfad byte-identisch. Die echte
 * Implementierung liegt bewusst NICHT hier — core-domain bleibt Spring-frei und
 * dependency-arm (nur reactor-core + jackson), eine ICU4J-gestützte Verbalisierung
 * kommt später in einen Adapter.
 */
interface VerbalizerPort {
    /** Macht [text] für [language] sprechbar (Ziffern/Symbole → Worte). */
    fun verbalize(text: String, language: Language): String

    companion object {
        /** Default: Identität — gibt [text] unverändert zurück (Verhalten unverändert). */
        val NONE: VerbalizerPort = object : VerbalizerPort {
            override fun verbalize(text: String, language: Language): String = text
        }
    }
}
