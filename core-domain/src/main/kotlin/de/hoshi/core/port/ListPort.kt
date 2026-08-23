package de.hoshi.core.port

import java.util.concurrent.ConcurrentHashMap

/**
 * Brain-free storage port for shopping/list entries.
 *
 * ADR-002-listen-modell-v1: v1 is a Ring-1 capability with one default list,
 * verbatim item text, shared deduplication and no done state.
 * [NONE] matches `HOSHI_LIST_ENABLED=false`: it never stores or returns data.
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
        /** ADR-002-listen-modell-v1: v1 exposes only this list while retaining `listId` in the model. */
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
 * Framework- and clock-free list data.
 *
 * ADR-002-listen-modell-v1: [text] stays verbatim; [quantity] counts duplicate
 * mentions and never represents a parsed amount or unit.
 *
 * @property id globally unique store key.
 * @property listId target list; v1 uses [ListPort.DEFAULT_LIST_ID].
 * @property text verbatim user item, for example `Milk` or `500 g mince`.
 * @property quantity deduplication counter, starting at one.
 * @property addedAtEpochMs ordering/debug timestamp; no timer semantics depend on it.
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

    // Zweitschlüssel [ListEntry.id] ist PFLICHT, nicht Kosmetik: `entries` ist eine
    // ConcurrentHashMap, deren values-Reihenfolge willkürlich ist. `sortedBy` sortiert zwar
    // stabil — Stabilität bewahrt aber die QUELL-Reihenfolge, und die ist hier undefiniert.
    // Zwei im selben Millisekunden-Tick angelegte Einträge („Milch", „Butter" schnell
    // hintereinander) kamen darum mal so, mal so heraus — sichtbar für den Nutzer, nicht nur
    // im Test. Die id ist eindeutig und stabil ⇒ deterministische Reihenfolge.
    override fun items(listId: String): List<ListEntry> =
        entries.values.filter { it.listId == listId }
            .sortedWith(compareBy({ it.addedAtEpochMs }, { it.id }))

    override fun remove(id: String): Boolean = entries.remove(id) != null

    override fun clear(listId: String): Int {
        val toRemove = entries.values.filter { it.listId == listId }.map { it.id }
        toRemove.forEach { entries.remove(it) }
        return toRemove.size
    }
}

/**
 * ADR-002-listen-modell-v1: the shared deduplication rule for voice and REST.
 * A case-insensitive text match keeps the first spelling and increments its
 * counter; otherwise a new entry is created.
 */
fun ListPort.addWithDedupe(
    listId: String,
    text: String,
    nowMs: Long,
    idGen: () -> String,
): ListEntry {
    val current = items(listId)
    val existing = current.firstOrNull { it.text.equals(text, ignoreCase = true) }
    // Monotonie-Riegel: „Milch" und „Butter" schnell hintereinander gesagt landen im SELBEN
    // Millisekunden-Tick — dann ist die Reihenfolge allein aus [ListEntry.addedAtEpochMs] nicht
    // ableitbar, und der Store (ConcurrentHashMap) hat keine eigene. Ergebnis war eine Liste, die
    // zwischen zwei Abrufen die Reihenfolge wechseln konnte. Jeder neue Eintrag bekommt darum einen
    // Zeitstempel STRIKT über allen vorhandenen; die Uhr bleibt führend, sie wird nur nie
    // rückwärts oder gleich vergeben. Abweichung von der echten Zeit: wenige Millisekunden im
    // Burst — und das Feld ist laut KDoc ohnehin nur fürs Sortieren da.
    val effectiveNow = maxOf(nowMs, (current.maxOfOrNull { it.addedAtEpochMs } ?: Long.MIN_VALUE) + 1)
    val desired = existing?.copy(quantity = existing.quantity + 1)
        ?: ListEntry(id = idGen(), listId = listId, text = text, addedAtEpochMs = effectiveNow)
    return add(desired)
}
