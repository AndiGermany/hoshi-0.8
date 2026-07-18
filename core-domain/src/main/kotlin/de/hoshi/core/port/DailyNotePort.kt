package de.hoshi.core.port

import java.time.Instant

/**
 * **Eine Andi-Faktor-Tagesnote** (Andi-Intent 2026-07-05: „im chat und über
 * die sprache") — die datierte Selbst-Bewertung des Tages auf der Skala 1–5,
 * mit optionalem Freitext-Grund. Die Wire-/Datei-Form (JSONL-Zeile
 * `{ts,score,grund,source}`) lebt beim Implementor
 * (`adapters-supervision`, `JsonlDailyNoteAdapter`).
 */
data class DailyNote(
    /** Zeitstempel der Note — bestimmt (in der Zone des Implementors) den Kalendertag. */
    val ts: Instant,
    /** Die Tagesnote 1–5 (der Erkenner lässt nur diesen Bereich durch). */
    val score: Int,
    /** Optionaler Freitext-Grund („zu langsam"); null = keiner genannt. */
    val grund: String? = null,
    /** Eingangs-Rand des Turns ("chat"/"voice"/"ws"); "" = unbekannt (Alt-Pfad). */
    val source: String = "",
)

/**
 * **DailyNotePort** — die schmale Domänen-Naht des Andi-Faktor-Tagesnoten-
 * Speichers, exakt nach dem [TurnTracePort]-Muster: der
 * [de.hoshi.core.pipeline.DailyNoteFastpath] ruft [record], der Implementor
 * (`JsonlDailyNoteAdapter`) schreibt async best-effort in die eigene JSONL
 * (`andi-faktor.jsonl`) und wirft NIE — eine Tagesnote ist nie wichtiger als
 * der Turn.
 *
 * **Überschreib-Vertrag:** eine zweite Note am selben Kalendertag ERSETZT die
 * erste (die Datei trägt am Ende genau EINE Zeile pro Tag). [record] meldet
 * synchron TRUE gdw. genau das passiert — die Quittung des Fastpaths kann so
 * ehrlich „Aktualisiert: …" statt „Notiert: …" sagen.
 *
 * [NONE] ist der verhaltens-neutrale Default (speichert nie, meldet nie
 * „überschrieben") ⇒ ohne Wiring bleibt jeder Pfad byte-identisch.
 */
interface DailyNotePort {

    /**
     * Speichert [note] datiert (async best-effort, wirft nie). TRUE gdw. für
     * den Kalendertag von [DailyNote.ts] bereits eine Note bekannt war — die
     * neue Note ÜBERSCHREIBT sie dann.
     */
    fun record(note: DailyNote): Boolean

    companion object {
        /** Default: speichert nie ⇒ byte-neutral ohne Wiring. */
        val NONE: DailyNotePort = object : DailyNotePort {
            override fun record(note: DailyNote): Boolean = false
        }
    }
}
