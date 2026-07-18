package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LanguagePolicy

/**
 * **LanguageResolver** — loest die client-seitige [LanguagePolicy] am Inbound-Rand
 * zu genau EINER konkreten [Language] (DE/EN) auf, BEVOR der Turn in die Pipeline
 * geht (Orchestrator + TTS). So sieht alles Downstream weiterhin nur DE oder EN —
 * die erschoepfenden `when(language)`-Bloecke bleiben unberuehrt.
 *
 * Aufloesung (effektive Antwort-Sprache):
 *  - Policy AUTO + Flag an  -> [detector] erkennt aus dem Text.
 *  - Policy AUTO + Flag aus  -> [Language.DEFAULT] (DE) — byte-neutral, Flag-OFF.
 *  - Policy DE / EN          -> harter Override auf diese Sprache.
 *  - Policy null (Legacy)    -> das explizite `language`-Feld des Requests.
 *
 * Die null-Policy ist BEWUSST so behandelt: ein alter Client, der nur
 * `language:"EN"` (ohne Policy) schickt, soll EN bekommen — ein non-null Default
 * wuerde das faelschlich ueberschreiben.
 *
 * @param detector    die deterministische, brain-freie Sprach-Erkennung.
 * @param autoEnabled das Deploy-Flag `HOSHI_LANG_AUTO_ENABLED` (Default OFF).
 */
class LanguageResolver(
    private val detector: LanguageDetector,
    private val autoEnabled: Boolean,
) {

    /**
     * Ist fuer diese Policy ECHTES Auto-Detect aktiv (AUTO UND Flag an)? Der Voice-
     * Rand fragt das, um Whisper auto-detecten zu lassen (Sprach-Hint weglassen),
     * statt einen festen Code zu pinnen.
     */
    fun isAutoDetect(policy: LanguagePolicy?): Boolean =
        policy == LanguagePolicy.AUTO && autoEnabled

    /**
     * Loest [policy] gegen [text] (fuer AUTO-Detect) und das [explicit]-Fallback
     * (Legacy null-Policy) zu genau einer konkreten [Language] auf.
     */
    fun resolve(policy: LanguagePolicy?, text: String, explicit: Language): Language =
        when (policy) {
            LanguagePolicy.AUTO -> if (autoEnabled) detector.detect(text) else Language.DEFAULT
            LanguagePolicy.DE -> Language.DE
            LanguagePolicy.EN -> Language.EN
            null -> explicit
        }

    /** Bequemlichkeit fuer den Chat-Rand: loest direkt aus dem [ChatRequest]. */
    fun resolve(request: ChatRequest): Language =
        resolve(request.languagePolicy, request.text, request.language)
}
