package de.hoshi.core.pipeline

/**
 * **ProbeFastpath** — der Selbsttest-Satz „Hoshi, Probe." (Golden-Utterance
 * #20, Andis Abnahme-Ritual für den Satelliten-Testtag): EIN warmer,
 * statischer, brain-freier Status-Satz, der die ganze Kette
 * (Ohren→Draht→Server→Stimme) binär beweist — kommt die Antwort an, steht
 * die Kette; bleibt sie stumm, ist etwas kaputt.
 *
 * **IN-SITU-Erkennung nach dem [de.hoshi.core.pipeline.AffirmationRecognizer]-
 * Muster: EXAKTER Treffer nach Normalisierung, NICHT `contains`/`find`.**
 * Anders als die Notiz-Fastpaths (deren Nutzlast HINTER der Trigger-Phrase
 * beliebig lang sein darf) ist „Probe" hier die GESAMTE Äußerung — ein
 * Nutzlast-Rest würde die Wette verwässern (z.B. „Probe, wie warm ist es?"
 * ist eine WISSENS-Frage, keine Kabel-Probe). Der normalisierte Text muss —
 * nach Abzug eines optionalen führenden Wake-Worts „Hoshi" (Präfix-Muster wie
 * [WorkshopNoteFastpath]: „Hoshi, …") — GENAU aus dem einen Token „probe"
 * bestehen. So feuert der Fastpath NIE auf „Probe" mitten im Satz („ich mach
 * noch eine Probe für die Band", "nur zur Probe", „Generalprobe" bleibt
 * ohnehin EIN Token ≠ „probe") — nur auf die EIGENSTÄNDIGE Äußerung
 * „Probe." / „Probe" / „Hoshi, Probe" (Satzzeichen/Groß-Klein sind der
 * [normalize]-Politur egal, exakt wie bei [DateFastpath]/[DailyNoteFastpath]).
 *
 * Die Antwort ist bewusst STATISCH + NUR DEUTSCH (Andis Ritual-Vokabel, wie
 * die [WorkshopNoteFastpath]-Quittung) — **ein Happy-Path-Satz, KEIN
 * Degraded-Stufen-Ast**: das im Golden-Utterance-Report referenzierte Design
 * „[R03 §4]" (ein Status-Satz JE Degraded-Stufe) lag beim Bau NICHT im Repo
 * vor (`vault/tracks/GOLDEN-UTTERANCES-satellit.md` #20 nennt nur den
 * Auftrag, keinen Wortlaut je Stufe) — dieser Fastpath deckt bewusst NUR den
 * einen Fall „die Kette lebt" (der Fastpath läuft ja selbst NUR, wenn Server +
 * TTS bereits antworten — ein degradierter Server könnte eine eigene Stufen-
 * Ansage ohnehin nicht mehr aussprechen; das gehört auf eine ANDERE Naht,
 * z.B. einen Health-Check VOR dem Turn, nicht in diesen Fastpath).
 *
 * [DISABLED] (`enabled = false`) ist der nie-antwortende Default (Flag-OFF):
 * ohne `HOSHI_PROBE_ENABLED` liefert [handle] immer `null`, der Probe-Zweig
 * im [TurnOrchestrator] ist tot ⇒ byte-neutral, exakt wie Calc/Timer/Date/
 * Tagesnote/Werkstatt-Notiz.
 */
class ProbeFastpath(
    /** Flag-OFF-Naht: `false` ⇒ [handle] liefert IMMER `null` (toter Zweig, byte-neutral). */
    private val enabled: Boolean = true,
) {

    /**
     * Erkennt die eigenständige Probe-Äußerung und liefert die fertige,
     * sprechbare Quittung; jeder Nicht-Treffer (kein Probe-Ruf, Flag-OFF,
     * leer) ⇒ `null` (⇒ normaler Turn). Ruft NIE ein Uhr/Store/Port — reine
     * Funktion, kein `now()`-Punkt nötig (anders als die datierten
     * Geschwister-Fastpaths).
     */
    fun handle(text: String): String? {
        if (!enabled || text.isBlank()) return null
        if (!isProbe(text)) return null
        return RECEIPT
    }

    /**
     * Ob [text] — nach Normalisierung und Abzug eines optionalen führenden
     * Wake-Worts „hoshi" — EXAKT das eine Token „probe" ist. Reine, seiteneffektfreie
     * Erkennung.
     */
    internal fun isProbe(text: String): Boolean {
        val tokens = normalize(text).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val core = if (tokens.first() == WAKE_WORD) tokens.drop(1) else tokens
        return core == listOf(TRIGGER_TOKEN)
    }

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (wie [DateFastpath]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /** Nie-antwortender Default (Flag-OFF): der Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = ProbeFastpath(enabled = false)

        /**
         * Deterministische, warme Quittung — exakt gepinnt in den Tests. Ehrlich
         * über die bewiesene Kette (Ohren = STT hat den Ruf transkribiert, Draht
         * = der Turn kam am Server an, Stimme = diese Antwort läuft gerade durch
         * TTS zurück) — KEIN Design-Wortlaut in `vault/tracks/GOLDEN-UTTERANCES-
         * satellit.md` #20 vorgegeben, darum der Fallback-Satz aus dem Bau-Auftrag.
         */
        internal const val RECEIPT = "Ich hör dich klar und deutlich — Ohren, Draht und Stimme stehen."

        /** Das eine tolerierte Wake-Wort-Präfix-Token (Codebase-Konvention: „Hoshi, …", s. [WorkshopNoteFastpath]-KDoc). */
        private const val WAKE_WORD = "hoshi"

        /** Das eine Pflicht-Token nach Wake-Wort-Abzug. */
        private const val TRIGGER_TOKEN = "probe"
    }
}
