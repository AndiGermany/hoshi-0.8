package de.hoshi.core.pipeline

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
 * Tagesnote — ein Briefkasten hat nichts zu vergleichen) und NUR deutsch
 * (Andis Werkstatt-Vokabel). Der **einzige `now()`-Punkt** ist der injizierte
 * [Clock] (Tagesnote-Muster): er stempelt [WorkshopNote.ts]; Tests setzen
 * `Clock.fixed` ⇒ voll deterministisch.
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
    fun handle(text: String, speakerId: String?): String? {
        if (!enabled || text.isBlank()) return null
        val note = match(text) ?: return null
        store.record(WorkshopNote(ts = clock.instant(), speakerId = speakerId, text = note))
        return RECEIPT
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

        /** Deterministische, warme Quittung — exakt gepinnt in den Tests. */
        internal const val RECEIPT = "Notiert für die Werkstatt. Danke dir!"

        // Die Muster laufen gegen den ORIGINAL-Text (nur IGNORE_CASE, kein
        // Umlaut-Normalisieren nötig — beide Trigger-Wörter sind Umlaut-frei).
        // Gruppe 1 = roher Notiz-Rest (in [match] nur getrimmt, sonst verbatim).
        //
        // Struktur je Muster: optionales führendes „bitte" ⇒ Trigger-Phrase ⇒
        // optionales anhängendes „bitte" (Füllwort-Toleranz, Live-Miss-Lehre
        // der Tagesnote) ⇒ Trenner (Komma/Doppelpunkt/Gedankenstrich/Space,
        // beliebig oft) ⇒ Notiz-Rest bis Zeilenende.
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
        )
    }
}
