package de.hoshi.core.dto

/**
 * **LanguagePolicy** — die WUNSCH-Sprache eines Turns, wie der Client sie schickt:
 * entweder eine konkrete Sprache (DE/EN, harter Override) ODER [AUTO]
 * (auto-erkennen aus dem Input). Bewusst ein SEPARATER Typ neben [Language]:
 *
 * [Language] (DE/EN) ist die KONKRETE Turn-Sprache und fliesst durch viele
 * erschoepfende `when(language)`-Bloecke OHNE `else` (PersonaService, AmbientWarmth,
 * ResponseFormatter, CalcFastpath, TimerFastpath, ProsodyShaper, IntentClassifier).
 * AUTO darf da NIE auftauchen, sonst brechen alle diese Bloecke. Darum traegt die
 * Policy AUTO nur am Inbound-Rand; der Controller LOEST sie zu genau einer konkreten
 * [Language] auf (siehe LanguageResolver), und die ganze Downstream-Pipeline sieht
 * weiterhin nur DE oder EN.
 *
 * [AUTO] ist die neue Default-Absicht (auto-detect), aber flag-gated: ist
 * `HOSHI_LANG_AUTO_ENABLED` aus, degradiert AUTO am Resolver zu [Language.DEFAULT]
 * (DE) — byte-neutral, bis Andi das Flag im Deploy umlegt.
 */
enum class LanguagePolicy {
    /** Auto-erkennen aus dem Input-Text (bei aktivem Flag); sonst Heimsprache DE. */
    AUTO,

    /** Harter Override: Deutsch. */
    DE,

    /** Harter Override: Englisch. */
    EN,
    ;

    /**
     * Die konkrete [Language] dieser Policy, falls eindeutig — [AUTO] -> null
     * (erst der Detector loest auf), DE -> [Language.DE], EN -> [Language.EN].
     */
    fun concreteOrNull(): Language? = when (this) {
        AUTO -> null
        DE -> Language.DE
        EN -> Language.EN
    }

    companion object {
        /**
         * Tolerant aus einem Roh-String (Query-Param / Wire-Tag), case-insensitive:
         * "auto"/"AUTO" -> [AUTO], "de"/"DE" -> [DE], "en"/"EN" -> [EN]. Ein
         * Region-Tag wird abgeschnitten ("en-US" -> EN).
         *
         * **Unbekannt / null / leer -> null** (bewusst KEIN AUTO-Default): ein
         * fehlender oder kaputter Policy-Param verhaelt sich wie ein Legacy-Client
         * ohne Policy — der Resolver faellt dann auf das explizite `language`-Feld
         * zurueck (byte-neutral), statt ungewollt Auto-Detect zu erzwingen.
         */
        fun fromCode(raw: String?): LanguagePolicy? {
            val c = raw?.trim()?.uppercase()?.substringBefore('-') ?: return null
            if (c.isBlank()) return null
            return entries.firstOrNull { it.name == c }
        }
    }
}
