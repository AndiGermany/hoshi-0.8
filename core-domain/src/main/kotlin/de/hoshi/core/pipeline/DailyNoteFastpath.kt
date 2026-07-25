package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.pipeline.lang.SCORE_PLACEHOLDER
import de.hoshi.core.port.DailyNote
import de.hoshi.core.port.DailyNotePort
import java.time.Clock

/**
 * **DailyNoteFastpath** — die Andi-Faktor-Tagesnote per SPRACHE/CHAT
 * (Andi-Intent 2026-07-05: „im chat und über die sprache"), brain-frei:
 * „Tagesnote 4" / „Tagesnote: 3, zu langsam" / „heute war ein 4er Tag(, weil …)"
 * ⇒ datiert über die [DailyNotePort]-Naht speichern (JSONL
 * `{ts,score,grund,source}`, async best-effort) und eine warme,
 * deterministische Quittung sprechen („Notiert: heute eine 4. Danke dir!").
 * Eine zweite Note am selben Tag ÜBERSCHREIBT ehrlich („Aktualisiert: …",
 * Überschreib-Vertrag beim Port).
 *
 * IN-SITU-Erkennung nach dem [DateFastpath]-Muster. KONSERVATIV
 * (false-positive-avers): eine Zahl 1–5 ist PFLICHT und muss direkt am
 * Tagesnote-Wort stehen („Tagesnote 4", „4er Tag") — „wie war meine
 * Tagesnote?", „Tagesnote 7" oder „heute war ein guter Tag" matchen NIE
 * (Gegen-Tests in `DailyNoteFastpathTest`). Kein Treffer ⇒ `null` ⇒ der
 * Orchestrator fällt unverändert in den normalen Turn (byte-neutral).
 *
 * Die QUITTUNG kommt seit 2026-07-25 in der TURN-SPRACHE aus dem
 * [de.hoshi.core.pipeline.lang.LanguagePack] (Andi: „Multilingualität von
 * A-Z") — die Note selbst ist eine Zahl und reist unübersetzt als
 * [SCORE_PLACEHOLDER] durch den Satz. Der **einzige
 * `now()`-Punkt** ist der injizierte [Clock] (Timer/Date-Muster): er stempelt
 * [DailyNote.ts]; Tests setzen `Clock.fixed` ⇒ voll deterministisch.
 *
 * **Erkennungs-Wörter seit 2026-07-25 (Nachtrag) EN/ES/FR/IT:** „Tagesnote" war
 * als Andis North-Star-Vokabel bewusst NUR deutsch — genau das war die Lücke,
 * die Andi beim Testen fand (die ANTWORTEN übersetzt, das VERSTEHEN nicht). Die
 * neuen Muster brauchen wie das DE-Original eine mehrwortige Wendung
 * („daily note"/„nota diaria"/„note du jour"/„nota del giorno" — NIE das bloße
 * „note", das im Englischen etwas völlig anderes ist als eine Schulnote-artige
 * Bewertung). Die deutsche „…er Tag"-Kurzform (2. Muster) ist ein reines
 * Sprachidiom ohne sauberes Pendant und bleibt bewusst unübersetzt — die
 * mehrwortige Form deckt jede Sprache trotzdem ab.
 *
 * [DISABLED] (`enabled = false`, NONE-Port) ist der nie-antwortende Default:
 * ohne `HOSHI_ANDI_FAKTOR_ENABLED` liefert [handle] immer `null`, der Zweig im
 * [TurnOrchestrator] ist tot ⇒ byte-neutral, exakt wie Calc/Timer/Date/Radio.
 */
class DailyNoteFastpath(
    private val store: DailyNotePort,
    private val clock: Clock = Clock.system(DateFastpath.BERLIN),
    /** Flag-OFF-Naht: `false` ⇒ [handle] liefert IMMER `null` (toter Zweig, byte-neutral). */
    private val enabled: Boolean = true,
) {

    /** Erkannter Tagesnoten-Wunsch: Score 1–5 + optionaler Freitext-Grund. */
    internal data class Match(val score: Int, val grund: String?)

    /**
     * Erkennt eine eindeutige Tagesnote, speichert sie datiert über die Naht
     * (async best-effort) und liefert die fertige, sprechbare Quittung;
     * jeder Nicht-Treffer (keine Tagesnote, Flag-OFF, leer) ⇒ `null`
     * (⇒ normaler Turn). [source] ist der Eingangs-Rand des Turns
     * ("chat"/"voice"/"ws") und fließt nur in die JSONL-Zeile.
     */
    fun handle(text: String, source: String, language: Language = Language.DEFAULT): String? {
        if (!enabled || text.isBlank()) return null
        val match = match(text) ?: return null
        val replaced = store.record(
            DailyNote(ts = clock.instant(), score = match.score, grund = match.grund, source = source),
        )
        return receipt(match.score, replaced, language)
    }

    /**
     * Der erkannte Tagesnoten-Wunsch in [text], oder `null` — reine,
     * störungsfreie Erkennung (kein Store-Effekt, uhrfrei). Läuft bewusst auf
     * dem ORIGINAL-Text (case-insensitiv): der Freitext-Grund soll seine
     * Groß-/Kleinschreibung behalten.
     */
    internal fun match(text: String): Match? {
        for (pattern in PATTERNS) {
            val m = pattern.find(text) ?: continue
            val score = m.groupValues[1].toIntOrNull() ?: continue
            if (score !in 1..5) continue
            return Match(score = score, grund = cleanGrund(m.groupValues[2]))
        }
        return null
    }

    /** Grund putzen: Rand-Interpunktion + führendes „weil/da" weg; leer ⇒ null. */
    private fun cleanGrund(raw: String): String? =
        raw.trim()
            .replace(Regex("^(?:weil|da)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('.', '!', '?', ',', ';')
            .trim()
            .ifBlank { null }

    /**
     * Deterministische, warme Quittung in [language] — auf Deutsch exakt gepinnt in
     * den Tests: neu ⇒ „Notiert: heute eine 4. Danke dir!", zweite Note am selben Tag
     * ⇒ ehrlich „Aktualisiert: heute eine 4. Danke dir!". Die Note ist eine ZAHL und
     * wird nie übersetzt — sie ersetzt nur den [SCORE_PLACEHOLDER] im Sprach-Template.
     */
    private fun receipt(score: Int, replaced: Boolean, language: Language): String {
        val pack = LanguagePackRegistry.forLanguage(language)
        val template = if (replaced) pack.dailyNoteUpdated else pack.dailyNoteRecorded
        return template.replace(SCORE_PLACEHOLDER, score.toString())
    }

    companion object {
        /** Nie-antwortender Default (Flag-OFF): der Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = DailyNoteFastpath(DailyNotePort.NONE, enabled = false)

        /**
         * Die kuratierten Tagesnoten-Muster (Original-Text, case-insensitiv).
         * Gruppe 1 = Score (nur 1–5; der Lookahead `(?!\s*[.,]?\s*\d)` blockt
         * „45", „4,5", „4.5" — lieber KEIN Treffer als eine falsche Note),
         * Gruppe 2 = roher Grund-Rest (wird in [cleanGrund] geputzt).
         *
         * EN/ES/FR/IT (Andi-Befund 2026-07-25): dieselbe Score-Gruppe/-Sperre,
         * derselbe optionale Verbindungs-Konnektor vor dem Trenner. BEWUSST das
         * bloße „note"/„nota" NIE als Trigger — nur die mehrwortige Wendung, s.
         * Klassen-KDoc. FR ohne „aujourd'hui"-Konnektor (Apostroph-Risiko in der
         * Original-Text-Regex); die „…er Tag"-Kurzform bleibt unübersetzt.
         */
        private val PATTERNS = listOf(
            // „Tagesnote 4" / „Tagesnote: 3, zu langsam" / „Tagesnote ist 2" / „tagesnote 5 weil alles lief"
            Regex(
                "tagesnote\\s*(?:ist|war|heute)?\\s*[:=\\-–—]?\\s*([1-5])(?!\\s*[.,]?\\s*\\d)\\s*[,;:\\-–—.]?\\s*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
            // „heute war ein 4er Tag" / „heute ist ein 3er Tag, weil …"
            Regex(
                "heute\\s+(?:war|ist)\\s+(?:so\\s+)?ein\\s+([1-5])er[\\s\\-]*tag\\b[\\s,;:.\\-–—]*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
            // EN: „daily note 4" / „daily note: 3, too slow" / „daily note is 2"
            Regex(
                "daily\\s+note\\s*(?:is|was|today)?\\s*[:=\\-–—]?\\s*([1-5])(?!\\s*[.,]?\\s*\\d)\\s*[,;:\\-–—.]?\\s*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
            // ES: „nota diaria 4" / „nota diaria: 3, muy lento" / „nota diaria es 2"
            Regex(
                "nota\\s+diaria\\s*(?:es|fue|hoy)?\\s*[:=\\-–—]?\\s*([1-5])(?!\\s*[.,]?\\s*\\d)\\s*[,;:\\-–—.]?\\s*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
            // FR: „note du jour 4" / „note du jour : 3, trop lent" / „note du jour est 2"
            Regex(
                "note\\s+du\\s+jour\\s*(?:est|était)?\\s*[:=\\-–—]?\\s*([1-5])(?!\\s*[.,]?\\s*\\d)\\s*[,;:\\-–—.]?\\s*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
            // IT: „nota del giorno 4" / „nota del giorno: 3, troppo lento" / „nota del giorno è 2"
            Regex(
                "nota\\s+del\\s+giorno\\s*(?:è|era|oggi)?\\s*[:=\\-–—]?\\s*([1-5])(?!\\s*[.,]?\\s*\\d)\\s*[,;:\\-–—.]?\\s*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
        )
    }
}
