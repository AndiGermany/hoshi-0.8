package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language

/**
 * **LanguageDetector** — die deterministische, LLM-freie Sprach-Erkennung eines
 * Input-Textes in genau eine konkrete [Language] (DE/EN). Spiegelt den Stil des
 * [ToolIntentClassifier]: reines Kotlin, keine Side-Effects, kein Framework, kein
 * Brain-Call. Speist die AUTO-Aufloesung am Inbound-Rand (siehe LanguageResolver).
 *
 * Bewusst KONSERVATIV + heimsprachen-vorgespannt: leer / zu kurz / mehrdeutig /
 * Gleichstand -> [Language.DE] (Andis Heimsprache). Lieber einmal zu oft Deutsch
 * als faelschlich Englisch auf einen deutschen Satz.
 *
 * [GERMAN] ist der verhaltens-neutrale Default (erkennt nie EN) — fuer Flag-OFF-
 * Pfade und Tests, die keine echte Erkennung brauchen.
 */
fun interface LanguageDetector {
    fun detect(text: String): Language

    companion object {
        /** Default: immer Heimsprache DE (keine echte Erkennung) — byte-neutral. */
        val GERMAN = LanguageDetector { Language.DE }
    }
}

/**
 * Die konkrete heuristische Impl. Tokenisiert den klein geschriebenen Text und
 * stimmt Deutsch- gegen Englisch-Signale ab; die hoehere Summe gewinnt, Gleichstand
 * faellt auf [Language.DE].
 *
 * Deutsch-Signale:
 *  - Umlaute/scharfes-s (ae/oe/ue/ss als Schriftzeichen) — starkes Signal, kein
 *    englisches Wort traegt sie (Gewicht 2).
 *  - Stoppwoerter: der/die/das/und/ist/nicht/ich/du/wir/ein/eine/mit/auf/fuer/von/
 *    wie/was/wann/wo/warum.
 *  - Morphologie: Endungen -ung / -keit, sowie der sch-Cluster.
 *
 * Englisch-Signale:
 *  - Stoppwoerter: the/and/is/are/you/what/when/where/why/how/a/an/of/to/in/on/for/
 *    with/this/that.
 *
 * Rein deterministisch: derselbe Text liefert immer dieselbe Sprache (wichtig fuer
 * den Voice-Pfad, der erst Whisper auto-detecten laesst und DANN aus dem Transkript
 * die Antwortsprache hier ableitet).
 */
class HeuristicLanguageDetector : LanguageDetector {

    override fun detect(text: String): Language {
        if (text.isBlank()) return Language.DE
        val lower = text.lowercase()
        val tokens = lower.split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Language.DE

        var de = 0
        var en = 0

        // (1) Umlaute/scharfes-s sind ein hartes Deutsch-Signal (kein EN-Wort hat sie).
        if (UMLAUT_RX.containsMatchIn(lower)) de += 2

        for (t in tokens) {
            // (2) Stoppwort-Stimmen (DE und EN getrennt gezaehlt).
            if (t in DE_STOPWORDS) de++
            if (t in EN_STOPWORDS) en++
            // (3) Deutsche Morphologie: -ung / -keit Endungen + sch-Cluster.
            if (t.length > 4 && (t.endsWith("ung") || t.endsWith("keit"))) de++
            if (t.contains("sch")) de++
        }

        // Gewinner = hoehere Summe; Gleichstand / mehrdeutig / kein Signal -> DE.
        return when {
            en > de -> Language.EN
            else -> Language.DE
        }
    }

    private companion object {
        /** Token-Split (wie [ToolIntentClassifier]): alles ausser a-z, Umlauten, Ziffern trennt. */
        val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

        /** Schriftzeichen, die nur Deutsch nutzt (Umlaute + scharfes s). */
        val UMLAUT_RX = Regex("[äöüß]")

        val DE_STOPWORDS = setOf(
            "der", "die", "das", "und", "ist", "nicht", "ich", "du", "wir",
            "ein", "eine", "mit", "auf", "für", "von", "wie", "was",
            "wann", "wo", "warum",
        )

        val EN_STOPWORDS = setOf(
            "the", "and", "is", "are", "you", "what", "when", "where", "why",
            "how", "a", "an", "of", "to", "in", "on", "for", "with", "this", "that",
        )
    }
}
