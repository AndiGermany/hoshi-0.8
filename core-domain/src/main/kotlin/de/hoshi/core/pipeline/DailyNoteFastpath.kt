package de.hoshi.core.pipeline

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
 * Die Erkennungs-Wörter sind bewusst NUR deutsch („Tagesnote" ist Andis
 * North-Star-Vokabel) — die Quittung darum immer deutsch. Der **einzige
 * `now()`-Punkt** ist der injizierte [Clock] (Timer/Date-Muster): er stempelt
 * [DailyNote.ts]; Tests setzen `Clock.fixed` ⇒ voll deterministisch.
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
    fun handle(text: String, source: String): String? {
        if (!enabled || text.isBlank()) return null
        val match = match(text) ?: return null
        val replaced = store.record(
            DailyNote(ts = clock.instant(), score = match.score, grund = match.grund, source = source),
        )
        return receipt(match.score, replaced)
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
     * Deterministische, warme Quittung — exakt gepinnt in den Tests:
     * neu ⇒ „Notiert: heute eine 4. Danke dir!", zweite Note am selben Tag ⇒
     * ehrlich „Aktualisiert: heute eine 4. Danke dir!".
     */
    private fun receipt(score: Int, replaced: Boolean): String =
        if (replaced) "Aktualisiert: heute eine $score. Danke dir!"
        else "Notiert: heute eine $score. Danke dir!"

    companion object {
        /** Nie-antwortender Default (Flag-OFF): der Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = DailyNoteFastpath(DailyNotePort.NONE, enabled = false)

        /**
         * Die kuratierten Tagesnoten-Muster (Original-Text, case-insensitiv).
         * Gruppe 1 = Score (nur 1–5; der Lookahead `(?!\s*[.,]?\s*\d)` blockt
         * „45", „4,5", „4.5" — lieber KEIN Treffer als eine falsche Note),
         * Gruppe 2 = roher Grund-Rest (wird in [cleanGrund] geputzt).
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
        )
    }
}
