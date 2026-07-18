package de.hoshi.core.port

import java.time.Instant

/**
 * **Eine Werkstatt-Notiz** (Cowork-Idee, von der Hand adoptiert, S1) — ein
 * kurzer Zuruf an die Werkstatt („Timer-Antwort zu lang"), den der
 * Orchestrator morgens liest. Die Wire-/Datei-Form (JSONL-Zeile
 * `{ts,speakerId,text}`) lebt beim Implementor (`adapters-supervision`,
 * `JsonlWorkshopNoteAdapter`).
 */
data class WorkshopNote(
    /** Zeitstempel der Notiz — der injizierten Uhr des [de.hoshi.core.pipeline.WorkshopNoteFastpath] entnommen. */
    val ts: Instant,
    /** Sprecher-Kennung, falls bekannt; `null` = unbekannt/anonym. */
    val speakerId: String? = null,
    /** Der Notiz-Text, verbatim aus dem Turn extrahiert. */
    val text: String,
)

/**
 * **WorkshopNotePort** — die schmale Domänen-Naht des Werkstatt-Notiz-
 * Briefkastens, exakt nach dem [TurnTracePort]-Muster: der
 * [de.hoshi.core.pipeline.WorkshopNoteFastpath] ruft [record], der Implementor
 * (`JsonlWorkshopNoteAdapter`) schreibt async best-effort in die eigene JSONL
 * (`werkstatt-notizen.jsonl`) und wirft NIE — eine Werkstatt-Notiz ist nie
 * wichtiger als der Turn.
 *
 * **ANDERS als [DailyNotePort]: KEIN Überschreib-Vertrag.** Ein Briefkasten
 * sammelt, er urteilt nicht — JEDE Notiz wird eine EIGENE Zeile, egal wie
 * viele am selben Tag hereinkommen. Darum liefert [record] (anders als
 * [DailyNotePort.record]) auch kein Boolean zurück: es gibt kein
 * „überschrieben" zu melden.
 *
 * [NONE] ist der verhaltens-neutrale Default (speichert nie) ⇒ ohne Wiring
 * bleibt jeder Pfad byte-identisch.
 */
fun interface WorkshopNotePort {

    /** Speichert [note] (async best-effort, wirft nie, immer eine NEUE Zeile). */
    fun record(note: WorkshopNote)

    companion object {
        /** Default: speichert nie ⇒ byte-neutral ohne Wiring. */
        val NONE: WorkshopNotePort = WorkshopNotePort { }
    }
}
