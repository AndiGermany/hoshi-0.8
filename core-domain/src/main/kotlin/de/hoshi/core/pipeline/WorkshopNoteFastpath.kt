package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.port.WorkshopNote
import de.hoshi.core.port.WorkshopNotePort
import java.time.Clock

/**
 * **WorkshopNoteFastpath** — die Werkstatt-Notiz per SPRACHE/CHAT (Cowork-
 * Idee, von der Hand adoptiert, S1), brain-frei: „Notiz an die Werkstatt:
 * Timer-Antwort zu lang" / „Werkstatt-Notiz: …" ⇒ verbatim über die
 * [WorkshopNotePort]-Naht in den Briefkasten ablegen (JSONL `{ts,speakerId,
 * text}`, async best-effort, APPEND-only — anders als die Tagesnote wird HIER
 * NIE überschrieben) und eine kurze, warme Quittung sprechen („Notiert für
 * die Werkstatt. Danke dir!").
 *
 * IN-SITU-Erkennung nach dem [DateFastpath]/[DailyNoteFastpath]-Muster (reine
 * Regex-Suche im Original-Text, `find` statt `matches` — die Notiz kann nach
 * einem Wake-Word-Präfix wie „Hoshi, …" stehen). Zwei Trigger-Phrasen (DE):
 *
 *  - „Notiz an die Werkstatt[:] …"
 *  - „Werkstatt-Notiz[:] …" (Bindestrich ODER Leerzeichen, STT trennt oft)
 *
 * **„bitte"-Toleranz** (Live-Miss-Lehre der Tagesnote, [EscalationModeFastpath]-
 * Muster `(?: bitte)?`): ein „bitte" direkt vor/nach der Trigger-Phrase wird
 * als Füllwort verschluckt („Bitte Notiz an die Werkstatt: …", „Notiz an die
 * Werkstatt, bitte: …", „Werkstatt-Notiz bitte, …") — es landet NICHT im
 * Notiz-Text. Ein „bitte" HINTER einem echten Trenner (Doppelpunkt/Komma)
 * gehört zum Notiz-Text und bleibt verbatim erhalten.
 *
 * KONSERVATIV: die Trigger-Phrase ist PFLICHT, der Notiz-Text nach dem
 * Trenner ebenfalls (ein leerer Rest ⇒ kein Treffer — ein Briefkasten ohne
 * Brief ist sinnlos). Kein Treffer ⇒ `null` ⇒ der Orchestrator fällt
 * unverändert in den normalen Turn (byte-neutral).
 *
 * Die Quittung ist bewusst STATISCH (kein Überschreib-Echo wie bei der
 * Tagesnote — ein Briefkasten hat nichts zu vergleichen) und kommt seit
 * 2026-07-25 in der TURN-SPRACHE aus dem [de.hoshi.core.pipeline.lang.LanguagePack]
 * (Andi: „Multilingualität von A-Z"). Der **einzige `now()`-Punkt** ist der
 * injizierte [Clock] (Tagesnote-Muster): er stempelt [WorkshopNote.ts]; Tests
 * setzen `Clock.fixed` ⇒ voll deterministisch.
 *
 * **TRIGGER-Phrasen seit 2026-07-25 (Nachtrag) EN/ES/FR/IT:** was hier zuerst
 * „bewusst nur deutsch" hieß (Andis Werkstatt-Vokabel), war die Lücke, die Andi
 * beim Testen fand — die ANTWORTEN waren übersetzt, das VERSTEHEN nicht. Die
 * neuen Muster sind bewusst SCHLANKER als die deutschen (keine „bitte"-Toleranz
 * nachgebaut, s. [PATTERNS]-KDoc) — lieber eine Wendung weniger als ein
 * Erkenner, der bei normaler Rede zuschnappt.
 *
 * [DISABLED] (`enabled = false`, NONE-Port) ist der nie-antwortende Default:
 * ohne `HOSHI_WORKSHOP_NOTE_ENABLED` liefert [handle] immer `null`, der Zweig
 * im [TurnOrchestrator] ist tot ⇒ byte-neutral, exakt wie Calc/Timer/Date/
 * Tagesnote.
 */
class WorkshopNoteFastpath(
    private val store: WorkshopNotePort,
    private val clock: Clock = Clock.system(DateFastpath.BERLIN),
    /** Flag-OFF-Naht: `false` ⇒ [handle] liefert IMMER `null` (toter Zweig, byte-neutral). */
    private val enabled: Boolean = true,
) {

    /**
     * Erkennt eine eindeutige Werkstatt-Notiz, legt sie über die Naht ab
     * (async best-effort) und liefert die fertige, sprechbare Quittung; jeder
     * Nicht-Treffer (keine Notiz, Flag-OFF, leer) ⇒ `null` (⇒ normaler Turn).
     * [speakerId] fließt nur in die JSONL-Zeile (`null` = unbekannt).
     */
    fun handle(text: String, speakerId: String?, language: Language = Language.DEFAULT): String? {
        if (!enabled || text.isBlank()) return null
        val note = match(text) ?: return null
        store.record(WorkshopNote(ts = clock.instant(), speakerId = speakerId, text = note))
        return LanguagePackRegistry.forLanguage(language).workshopNoteRecorded
    }

    /**
     * Der erkannte Notiz-Text in [text], VERBATIM (nur Rand-Whitespace
     * getrimmt — keine Interpunktions-Politur wie bei der Tagesnote), oder
     * `null` — reine, störungsfreie Erkennung (kein Store-Effekt, uhrfrei).
     * Läuft auf dem ORIGINAL-Text (case-insensitiv nur für die Trigger-Phrase
     * selbst): der Notiz-Text behält seine Groß-/Kleinschreibung.
     */
    internal fun match(text: String): String? {
        for (pattern in PATTERNS) {
            val m = pattern.find(text) ?: continue
            val note = m.groupValues[1].trim()
            if (note.isNotEmpty()) return note
        }
        return null
    }

    companion object {
        /** Nie-antwortender Default (Flag-OFF): der Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = WorkshopNoteFastpath(WorkshopNotePort.NONE, enabled = false)

        /**
         * Deterministische, warme Quittung auf DEUTSCH — exakt gepinnt in den Tests.
         * Seit der Mehrsprachigkeit nur noch der DE-Zeiger auf die EINE Quelle
         * ([LangDe]) — der byte-identische Beweis, dass der de-Pfad nicht wackelt.
         */
        internal val RECEIPT: String = LangDe.PACK.workshopNoteRecorded

        // Die Muster laufen gegen den ORIGINAL-Text (nur IGNORE_CASE, kein
        // Umlaut-Normalisieren nötig — beide Trigger-Wörter sind Umlaut-frei).
        // Gruppe 1 = roher Notiz-Rest (in [match] nur getrimmt, sonst verbatim).
        //
        // Struktur je DE-Muster: optionales führendes „bitte" ⇒ Trigger-Phrase ⇒
        // optionales anhängendes „bitte" (Füllwort-Toleranz, Live-Miss-Lehre
        // der Tagesnote) ⇒ Trenner (Komma/Doppelpunkt/Gedankenstrich/Space,
        // beliebig oft) ⇒ Notiz-Rest bis Zeilenende.
        //
        // Die EN/ES/FR/IT-Muster (Andi-Befund 2026-07-25) teilen sich die
        // Trenner-/Rest-Struktur, verzichten aber bewusst auf die „bitte"-
        // Toleranz (kleinerer, sicherer Zuschnitt statt vier Sprachen lang
        // erratener Höflichkeitsfloskeln) — s. RESULT-Zeile des Auftrags.
        // FR/IT: der Apostroph in „l'atelier"/„l'officina" fängt beide
        // Anführungszeichen-Varianten (gerade ' und kurvig ’, wie
        // [RadioFastpath.normalize]).
        private val PATTERNS = listOf(
            // „Notiz an die Werkstatt: Timer-Antwort zu lang" / „Bitte Notiz an die
            // Werkstatt: …" / „Notiz an die Werkstatt, bitte: …" / „… bitte, …"
            Regex(
                "(?:bitte[,\\s]+)?notiz an die werkstatt(?:[,\\s]*bitte)?[,:\\s\\-–—]*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
            // „Werkstatt-Notiz: …" / „Werkstatt Notiz: …" (STT trennt oft ohne
            // Bindestrich) + dieselbe „bitte"-Toleranz.
            Regex(
                "(?:bitte[,\\s]+)?werkstatt[\\s-]notiz(?:[,\\s]*bitte)?[,:\\s\\-–—]*(.*)$",
                RegexOption.IGNORE_CASE,
            ),
            // EN: „note to the workshop: …" / „workshop note: …" / „workshop-note: …"
            Regex("note to the workshop[,:\\s\\-–—]*(.*)$", RegexOption.IGNORE_CASE),
            Regex("workshop[\\s-]note[,:\\s\\-–—]*(.*)$", RegexOption.IGNORE_CASE),
            // ES: „nota para el taller: …"
            Regex("nota para el taller[,:\\s\\-–—]*(.*)$", RegexOption.IGNORE_CASE),
            // FR: „note pour l'atelier : …"
            Regex("note pour l['’]atelier[,:\\s\\-–—]*(.*)$", RegexOption.IGNORE_CASE),
            // IT: „nota per l'officina: …"
            Regex("nota per l['’]officina[,:\\s\\-–—]*(.*)$", RegexOption.IGNORE_CASE),
        )
    }
}
