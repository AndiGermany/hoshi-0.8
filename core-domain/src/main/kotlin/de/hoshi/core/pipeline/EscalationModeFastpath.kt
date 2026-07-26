package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.pipeline.lang.fallsBackToEnglish

/**
 * **EscalationModeFastpath** — die Extended-Think-Stufe per SPRACHE/CHAT
 * (Andi-Intent 2026-07-05: Stufen „auch über die stimme setzen"), brain-frei:
 *
 *  - „frag (mich) erst, bevor du online gehst" / „geh (nur) nach Rückfrage
 *    online" ⇒ [EscalationMode.ERST_FRAGEN]
 *  - „schalte Online-Nachschauen aus" / „geh nicht (mehr) online" ⇒
 *    [EscalationMode.AUS]
 *  - „geh automatisch online" / „schau selbstständig nach" ⇒
 *    [EscalationMode.AUTOMATISCH]  (+ EN-Pendants)
 *  - „geh offline" / „schalte auf offline-modus" / „bleib offline" ⇒
 *    [EscalationMode.OFFLINE] (Andi-Auftrag 2026-07-26; + EN-Pendants)
 *
 * IN-SITU-Erkennung nach dem [DateFastpath]-Muster (kein eigener Intent-Parser
 * nötig), Wirkung über die schmale [EscalationModeSwitchPort]-Naht — DIESELBE
 * Store-Wahrheit wie `PUT /api/v1/settings/extended-think`. Die Antwort ist
 * deterministisch + warm, mit Stufen-Echo („Okay — ich frag dich ab jetzt
 * erst, bevor ich online nachschaue.").
 *
 * KONSERVATIV (false-positive-avers): jedes Muster verlangt ein
 * Online-/Nachschauen-Wort UND eine imperative Verb-Form („geh …", „schalte …",
 * „frag …") — eine Status-FRAGE wie „warum bist du nicht online?" oder „warum
 * gehst du automatisch online?" matcht NIE (Gegen-Tests in
 * `EscalationModeFastpathTest`). Kein Treffer ⇒ `null` ⇒ der Orchestrator
 * fällt unverändert in den normalen Turn (byte-neutral).
 *
 * **Ehrlich statt fake-grün:** meldet der Port einen fehlgeschlagenen Persist
 * ([EscalationModeSwitchPort.switchTo] == false), sagt die Antwort das offen —
 * NIE eine Bestätigung ohne bewiesenen Store-Write.
 *
 * [DISABLED] (`enabled = false`, NONE-Port) ist der nie-antwortende Default
 * (Decke `HOSHI_EXTENDED_THINK_ENABLED=false` ⇒ im Wiring nie verdrahtet):
 * [handle] liefert immer `null`, der Zweig im [TurnOrchestrator] ist tot ⇒
 * byte-neutral, exakt wie Calc/Timer/Date/Radio.
 */
class EscalationModeFastpath(
    private val store: EscalationModeSwitchPort,
    /** Flag-OFF-Naht: `false` ⇒ [handle] liefert IMMER `null` (toter Zweig, byte-neutral). */
    private val enabled: Boolean = true,
) {

    /**
     * Erkennt einen eindeutigen Stufen-Wunsch, persistiert ihn über die Naht und
     * liefert die fertige, sprechbare Quittung mit Stufen-Echo; jeder
     * Nicht-Treffer (kein Stufen-Wunsch, Flag-OFF, leer) ⇒ `null` (⇒ normaler Turn).
     */
    fun handle(text: String, language: Language): String? {
        if (!enabled || text.isBlank()) return null
        val mode = match(text) ?: return null
        return if (store.switchTo(mode)) receipt(mode, language) else failure(language)
    }

    /**
     * Der erkannte Stufen-Wunsch in [text], oder `null` — reine, störungsfreie
     * Erkennung (kein Store-Effekt). Normalisiert (lowercase, Apostrophe/Zeichen
     * weg, wie [DateFastpath]) und prüft konservativ gegen die kuratierten
     * Muster-Listen; Reihenfolge ERST_FRAGEN → AUS → AUTOMATISCH → OFFLINE
     * (die Listen sind disjunkt, die Ordnung nur Determinismus-Pin).
     */
    fun match(text: String): EscalationMode? {
        val norm = normalize(text)
        if (norm.isEmpty()) return null
        return when {
            ERST_FRAGEN_PATTERNS.any { it.containsMatchIn(norm) } -> EscalationMode.ERST_FRAGEN
            AUS_PATTERNS.any { it.containsMatchIn(norm) } -> EscalationMode.AUS
            AUTOMATISCH_PATTERNS.any { it.containsMatchIn(norm) } -> EscalationMode.AUTOMATISCH
            OFFLINE_PATTERNS.any { it.containsMatchIn(norm) } -> EscalationMode.OFFLINE
            else -> null
        }
    }

    /**
     * Deterministische, warme Quittung MIT Stufen-Echo — exakt gepinnt in den Tests.
     *
     * **Andi-Auftrag 2026-07-26** („Multilingualität von A-Z"): vorher wählte diese
     * Funktion inline über [fallsBackToEnglish] nur zwischen DE und EN — ES/FR/IT
     * bekamen also Englisch. Jetzt ECHT fünfsprachig über die vier
     * `escalationMode…`-Felder im [de.hoshi.core.pipeline.lang.LanguagePack]; DE/EN
     * bleiben WORT-FÜR-WORT wie zuvor (Tests unverändert bestehen).
     */
    private fun receipt(mode: EscalationMode, language: Language): String {
        val pack = LanguagePackRegistry.forLanguage(language)
        return when (mode) {
            EscalationMode.ERST_FRAGEN -> pack.escalationModeErstFragen
            EscalationMode.AUS -> pack.escalationModeAus
            EscalationMode.AUTOMATISCH -> pack.escalationModeAutomatisch
            EscalationMode.OFFLINE -> pack.escalationModeOffline
        }
    }

    /** Ehrliche Fehler-Antwort: der Persist ist NICHT bewiesen ⇒ keine Fake-Bestätigung. */
    private fun failure(language: Language): String =
        if (language.fallsBackToEnglish) {
            "I tried to switch that, but saving failed — the setting stays unchanged."
        } else {
            "Das wollte ich gerade umstellen, aber das Speichern hat nicht geklappt — die Stufe bleibt unverändert."
        }

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (wie [DateFastpath]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /** Nie-antwortender Default (Decke zu / Flag-OFF): der Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = EscalationModeFastpath(EscalationModeSwitchPort.NONE, enabled = false)

        // Die Muster laufen gegen den NORMALISIERTEN Text (lowercase, nur
        // a-zäöüß0-9 + einzelne Spaces) — Wort-Grenzen darum bewusst als
        // `(?:^| )`/`(?: |$)` statt `\b` (ASCII-\b stolpert über Umlaute).

        /** ERST_FRAGEN: „frag (mich) erst, bevor du online gehst" / „geh (nur) nach Rückfrage online" + EN. */
        private val ERST_FRAGEN_PATTERNS = listOf(
            Regex("(?:^| )frag(?: mich)?(?: bitte)?(?: erst| zuerst| vorher)? bevor du (?:online gehst|online nachschaust|online schaust|nachschaust|nachguckst)(?: |$)"),
            Regex("(?:^| )geh(?:e)?(?: bitte)?(?: nur| erst)? nach r(?:ü|ue)ckfrage online(?: |$)"),
            Regex("(?:^| )nur nach r(?:ü|ue)ckfrage online(?: |$)"),
            Regex("(?:^| )ask(?: me)?(?: first)? before (?:you )?go(?:ing)? online(?: |$)"),
            Regex("(?:^| )only go online after asking(?: me)?(?: first)?(?: |$)"),
        )

        /** AUS: „schalte Online-Nachschauen aus" / „geh nicht (mehr) online" + EN. */
        private val AUS_PATTERNS = listOf(
            Regex("(?:^| )(?:schalte?|mach|stell) (?:das |die )?online (?:nachschauen|nachschlagen|suche) (?:bitte |wieder )?aus(?: |$)"),
            Regex("(?:^| )geh(?:e)?(?: bitte)? (?:nicht|nie)(?: mehr)? online(?: |$)"),
            Regex("(?:^| )(?:dont|do not|never) go online(?: anymore)?(?: |$)"),
            Regex("(?:^| )stop going online(?: |$)"),
            Regex("(?:^| )turn off (?:the )?online (?:lookups?|search)(?: |$)"),
            Regex("(?:^| )turn (?:the )?online (?:lookups?|search) off(?: |$)"),
            Regex("(?:^| )disable online (?:lookups?|search)(?: |$)"),
        )

        /** AUTOMATISCH: „geh automatisch online" / „schau selbstständig nach" + EN. */
        private val AUTOMATISCH_PATTERNS = listOf(
            Regex("(?:^| )geh(?:e)?(?: bitte| ruhig| einfach| ab jetzt| immer)? (?:automatisch|selbstständig|selbständig|selbststaendig|selbstaendig|von dir aus) online(?: |$)"),
            Regex("(?:^| )schau(?:e)?(?: bitte| ruhig| einfach| ab jetzt| immer)? (?:automatisch|selbstständig|selbständig|selbststaendig|selbstaendig|von dir aus)(?: online)? nach(?: |$)"),
            Regex("(?:^| )go online automatically(?: |$)"),
            Regex("(?:^| )automatically go online(?: |$)"),
            Regex("(?:^| )go online on your own(?: |$)"),
            Regex("(?:^| )look (?:it|things|stuff) up (?:online )?(?:automatically|on your own)(?: |$)"),
        )

        /**
         * OFFLINE: „geh offline" / „Offline-Modus (an)" / „bleib offline" + EN.
         * Disjunkt zu [AUS_PATTERNS] — die sprechen alle über „online (nicht) gehen/
         * ausschalten", diese hier tragen alle wörtlich „offline". Bewusst KEIN
         * nacktes „offline" allein: „bist du offline?" ist eine Frage, kein Befehl.
         */
        private val OFFLINE_PATTERNS = listOf(
            Regex("(?:^| )geh(?:e)?(?: bitte| ab jetzt)? offline(?: |$)"),
            Regex("(?:^| )bleib(?:e)?(?: bitte)? offline(?: |$)"),
            Regex("(?:^| )(?:schalte?|mach|stell)(?: bitte)? (?:in )?(?:den )?offline ?modus(?: an| ein)?(?: |$)"),
            Regex("(?:^| )offline ?modus (?:an|ein|bitte)(?: |$)"),
            Regex("(?:^| )go offline(?: |$)"),
            Regex("(?:^| )stay offline(?: |$)"),
            Regex("(?:^| )(?:switch|go) (?:in)?to offline mode(?: |$)"),
            Regex("(?:^| )offline mode on(?: |$)"),
        )
    }
}
