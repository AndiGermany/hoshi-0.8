package de.hoshi.core.tools

import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaInfo

/**
 * **AreaClarifyIntent** — die ehrliche Rückfrage-Variante des Tool-Pfads
 * (`domain == "area_clarify"`): Träger der Domain-/Service-/Datenschlüssel-
 * Konstanten, EXAKT nach dem [ListIntent]/[TimerIntent]-Muster (der
 * [de.hoshi.core.pipeline.TurnOrchestrator] dispatcht anhand `domain` auf
 * einen eigenen, brain-freien Zweig).
 *
 * Anders als [ListIntent]/[TimerIntent] hat dieses Objekt KEINE eigene
 * `classify(text)`-Funktion: die Erkennung „Schalt-Verb + An/Aus-Partikel,
 * aber kein auflösbares Ziel" braucht DIESELBEN Token-/Wortlisten wie der
 * Licht-Zweig des [de.hoshi.core.pipeline.DeterministicToolIntentClassifier]
 * (Schalt-Verben, ON_WORDS/OFF_WORDS, Negations-Guard, Raum-Index) — sie hier
 * zu duplizieren wäre Drift-Risiko. Der Classifier klassifiziert also SELBST
 * (letzter Zweig vor `return null`, s. dortiges KDoc) und baut den [ToolCall]
 * mit [phrase] als fertigem Text.
 *
 * [phrase] ist die einzige eigenständige Logik: eine kurze, ehrliche DE/EN-
 * Rückfrage aus den bekannten Areas ([AreaInfo.label], max [MAX_ROOMS_NAMED]) —
 * NIE geraten, NIE Brain-Prosa (Live-Befund 2026-07-15: „Schalte das
 * Schlafzimmer ein" ohne Geräte-Wort landete als brainTtftMs=960-Prosa OHNE
 * Tat, das Licht blieb aus; diese Rückfrage ersetzt genau diesen Rest-Fall
 * „Schalt-Verb+Partikel sicher, aber kein Ziel auflösbar").
 */
object AreaClarifyIntent {
    /** ToolCall-Domain dieses Fast-Paths (vom Orchestrator gegen den Clarify-Zweig geprüft). */
    const val DOMAIN = "area_clarify"
    const val ASK = "ask"

    /** Datenschlüssel: die fertige, sprechbare Rückfrage-Phrase. */
    const val PHRASE = "phrase"

    /** Höchstens so viele Raumnamen werden in der Rückfrage genannt (nicht die ganze Liste vorlesen). */
    const val MAX_ROOMS_NAMED = 4

    /**
     * Baut die Rückfrage-Phrase aus den bekannten [areas] (Reihenfolge des Katalogs,
     * s. [de.hoshi.core.port.AreaCatalogPort]) — ein (theoretisch möglicher, beim
     * [de.hoshi.core.port.AreaCatalogPort.Companion.STATIC]-Default nie leerer)
     * leerer Katalog ⇒ die Frage OHNE Aufzählung (immer noch ehrlich, nie leer/still).
     */
    fun phrase(areas: List<AreaInfo>, language: Language): String {
        val names = areas.map { it.label }.filter { it.isNotBlank() }.take(MAX_ROOMS_NAMED)
        val en = language == Language.EN
        if (names.isEmpty()) {
            return if (en) "Which room do you mean?" else "Welchen Raum meinst du?"
        }
        val enumeration = names.joinToString(", ")
        return if (en) "Which room do you mean — $enumeration…?" else "Welchen Raum meinst du — $enumeration…?"
    }
}
