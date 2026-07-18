package de.hoshi.core.dto

/**
 * Die Sprache, in der ein Turn läuft. Andi-Wunsch (2026-06-26): in den Optionen
 * wählbar; **alles** folgt der Wahl (STT-Hint, Brain-/Persona-Prompt, TTS-Stimme,
 * Filler/Verweigerungs-Phrasen, Grounding-Quelle, UI).
 *
 * Bewusst ein **erweiterbares enum**, kein Boolean — Start: Deutsch + Englisch.
 * Dieser Typ fließt von Anfang an durch den Turn (`ChatRequest`/`TurnPrompt`),
 * damit jeder Port/Adapter sprach-bewusst ist, statt Sprache später nachzurüsten.
 *
 * **Sprachpaket-Kern (2026-07-20, Andi-Auftrag „DE/EN/ES/FR/IT wählbar"):** ES/FR/IT
 * kommen NEU dazu — Konversations-Schicht (Prompt/Deflect/Consent/Lookup-Filler),
 * Smart-Home-/Timer-Reflexe bleiben BEWUSST Deutsch (s. `LanguagePack.smartHomeNotice`).
 * ES/FR/IT sind strukturell fertig, ihre eigentlichen Phrasen-Pools sind aber
 * NOCH EN-Fallback (s. `de.hoshi.core.pipeline.lang.LangEs/LangFr/LangIt` TODO-Marker)
 * — ein Übersetzer-Pod füllt sie, ohne dieses Enum oder ResponseFormatter erneut
 * anzufassen. Jedes bestehende exhaustive `when(language)` OHNE `else` zwingt den
 * Compiler dazu, JEDE neue Sprache bewusst zu behandeln (s. `Language.deOr`).
 *
 * Querschnitts-Design: [[tracks/MULTILINGUAL]].
 */
enum class Language(
    /** ISO-639-1-Code — direkt als Hint an Whisper (STT) und Voxtral (`lang`) verwendbar. */
    val code: String,
    /** Anzeigename in der jeweiligen Sprache selbst (für den Selektor in den Einstellungen). */
    val endonym: String,
) {
    DE("de", "Deutsch"),
    EN("en", "English"),
    ES("es", "Español"),
    FR("fr", "Français"),
    IT("it", "Italiano"),
    ;

    companion object {
        /** Default-Sprache, solange Andi nichts anderes wählt. */
        val DEFAULT: Language = DE

        /** Robust aus einem Code/Tag (z.B. "de", "EN", "en-US") — Fallback [DEFAULT]. */
        fun fromCode(raw: String?): Language {
            val c = raw?.trim()?.lowercase()?.substringBefore('-') ?: return DEFAULT
            return entries.firstOrNull { it.code == c } ?: DEFAULT
        }

        /**
         * STRENGE Variante für Settings-Endpunkte (Andi-Auftrag: „422 bei unbekannt"):
         * anders als [fromCode] gibt es hier KEINEN stillen [DEFAULT]-Fallback — ein
         * unbekannter/leerer Code liefert `null`, der Aufrufer antwortet ehrlich 422
         * statt eine Wahl vorzutäuschen, die niemand getroffen hat.
         */
        fun fromCodeOrNull(raw: String?): Language? {
            val c = raw?.trim()?.lowercase()?.substringBefore('-')
            if (c.isNullOrBlank()) return null
            return entries.firstOrNull { it.code == c }
        }
    }
}
