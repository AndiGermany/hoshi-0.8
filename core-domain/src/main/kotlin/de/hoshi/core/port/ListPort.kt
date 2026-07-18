package de.hoshi.core.port

import java.util.concurrent.ConcurrentHashMap

/**
 * **ListPort** — die hexagonale Naht für die brain-freie Einkaufs-/Notizliste
 * (ListFastpath, Andi-JA 2026-07-08 „Listen auf die Ring-1-Karte" — die letzte
 * Ring-1-Lücke neben dem Wecker). Essenz EXAKT nach dem [ScheduledItemPort]-
 * Muster der Wecker-Lane portiert (Restart-Überleben dort bereits bewiesen).
 *
 * Ein [ListEntry] trägt puren Freitext ([ListEntry.text], z.B. „500 g Hack") —
 * **KEINE Einheiten-Ontologie** (Andi-Entscheidung 2026-07-08): der Parser zerlegt
 * Mengenangaben nicht, das Item bleibt genau der Wortlaut, den der Nutzer nannte.
 * [ListEntry.quantity] ist NICHT die Menge des Items selbst, sondern der
 * **Dedupe-Zähler**: dasselbe Item ein zweites Mal angesagt ⇒ derselbe Eintrag
 * (gleiche id) mit `quantity+1` statt eines Duplikats (Andi-Entscheidung: „Milch"
 * zweimal → „2× Milch", nicht ablehnen). Die Dedupe-Entscheidung selbst trifft
 * NICHT der Port (der bleibt ein dummer, keyed Store — exakt wie
 * [ScheduledItemPort.set]) — sie lebt EINMAL in [addWithDedupe], geteilt zwischen
 * [de.hoshi.core.pipeline.ListFastpath] (Voice/Text) und der REST-Naht
 * (`ListsController`), damit In-Mem- und File-Backed-Impl niemals auseinanderdriften.
 *
 * `listId` ist von Tag 1 im Datenmodell (Andi-Entscheidung 2026-07-08: „EINE
 * Einkaufsliste, aber list_id im Modell") — v1 verdrahtet ausschließlich
 * [DEFAULT_LIST_ID] („einkauf"); benannte Zweit-Listen (Notizen etc.) sind eine
 * spätere Scheibe, die nur den Aufrufer ändert, nicht den Port.
 *
 * **Abhaken/„done"-Status ist NICHT Teil dieser Scheibe** (Andi-Entscheidung:
 * UI-Folge-Scheibe) — der Port kennt nur „da" ([add]/[items]) oder „weg"
 * ([remove]/[clear]), keine Zwischenstufe.
 *
 * [NONE] ist der verhaltens-neutrale Default (speichert nie, liefert nie) —
 * passend zum Default-OFF-Flag `HOSHI_LIST_ENABLED`: ohne das Flag wird der
 * Pfad nie betreten.
 */
interface ListPort {
    /**
     * Legt [entry] an ODER überschreibt einen bestehenden Eintrag mit derselben
     * [ListEntry.id] (Schlüssel = id, exakt [ScheduledItemPort.set]-Semantik —
     * ein Re-`add` mit derselben id UND erhöhter [ListEntry.quantity] IST der
     * Dedupe-Mechanismus, s. [addWithDedupe]).
     */
    fun add(entry: ListEntry): ListEntry

    /** Alle Einträge EINER Liste (Snapshot), älteste zuerst. */
    fun items(listId: String = DEFAULT_LIST_ID): List<ListEntry>

    /** Entfernt GENAU einen Eintrag (id ist global eindeutig); `true`, wenn einer entfernt wurde. */
    fun remove(id: String): Boolean

    /** Entfernt ALLE Einträge EINER Liste; liefert die Anzahl der entfernten. */
    fun clear(listId: String = DEFAULT_LIST_ID): Int

    companion object {
        /** v1-Default-/Einzige Liste (Andi-Entscheidung 2026-07-08: „einkauf"). */
        const val DEFAULT_LIST_ID = "einkauf"

        /** Default: speichert nie, liefert nie ⇒ kein Listen-Effekt (Flag-OFF-passend). */
        val NONE: ListPort = object : ListPort {
            override fun add(entry: ListEntry): ListEntry = entry
            override fun items(listId: String): List<ListEntry> = emptyList()
            override fun remove(id: String): Boolean = false
            override fun clear(listId: String): Int = 0
        }
    }
}

/**
 * Ein Listen-Eintrag — bewusst reine Daten (Spring-frei, uhrfrei).
 *
 * @property id        eindeutige id (z.B. UUID), Schlüssel im Store.
 * @property listId    welche Liste (v1 immer [ListPort.DEFAULT_LIST_ID]).
 * @property text      Freitext-Item genau wie gesagt (z.B. „Milch", „500 g Hack") —
 *                     KEINE Einheiten-/Mengen-Zerlegung (Andi-Entscheidung 2026-07-08).
 * @property quantity  Dedupe-Zähler (Default 1); >1 heißt „N× genannt", NICHT „N Stück"
 *                     im Sinn einer geparsten Menge — s. Klassen-KDoc.
 * @property addedAtEpochMs Anlage-Zeitpunkt (Epoch-Millis) — nur fürs Sortieren/Debugging,
 *                     kein funktionales Verhalten hängt daran (anders als beim Timer).
 */
data class ListEntry(
    val id: String,
    val listId: String = ListPort.DEFAULT_LIST_ID,
    val text: String,
    val quantity: Int = 1,
    val addedAtEpochMs: Long,
)

/**
 * Thread-sichere In-Memory-Impl (`ConcurrentHashMap<id, ListEntry>`). Pure,
 * framework-frei, uhrfrei. [remove] entfernt den Eintrag ganz (keine
 * Status-Maschine — Abhaken ist NICHT Teil dieser Scheibe).
 */
class InMemoryListStore : ListPort {
    private val entries = ConcurrentHashMap<String, ListEntry>()

    override fun add(entry: ListEntry): ListEntry {
        entries[entry.id] = entry
        return entry
    }

    override fun items(listId: String): List<ListEntry> =
        entries.values.filter { it.listId == listId }.sortedBy { it.addedAtEpochMs }

    override fun remove(id: String): Boolean = entries.remove(id) != null

    override fun clear(listId: String): Int {
        val toRemove = entries.values.filter { it.listId == listId }.map { it.id }
        toRemove.forEach { entries.remove(it) }
        return toRemove.size
    }
}

/**
 * **Die EINE Dedupe-Regel** (Andi-Entscheidung 2026-07-08): [text] gegen die
 * vorhandenen Einträge von [listId] case-insensitiv/getrimmt abgleichen — Treffer
 * ⇒ derselbe Eintrag mit `quantity+1` (die ERSTE Schreibweise gewinnt, kein
 * Text-Flackern bei wiederholtem Ansagen), sonst ein neuer Eintrag mit
 * `quantity=1`. Geteilt zwischen [de.hoshi.core.pipeline.ListFastpath]
 * (Voice/Text-Turn) und der REST-Naht (`ListsController`, web-inbound) — EINE
 * Wahrheit, keine Drift zwischen den beiden Eingängen.
 */
fun ListPort.addWithDedupe(
    listId: String,
    text: String,
    nowMs: Long,
    idGen: () -> String,
): ListEntry {
    val existing = items(listId).firstOrNull { it.text.equals(text, ignoreCase = true) }
    val desired = existing?.copy(quantity = existing.quantity + 1)
        ?: ListEntry(id = idGen(), listId = listId, text = text, addedAtEpochMs = nowMs)
    return add(desired)
}
