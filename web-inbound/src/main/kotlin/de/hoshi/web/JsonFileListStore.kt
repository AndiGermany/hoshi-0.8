package de.hoshi.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.ListEntry
import de.hoshi.core.port.ListPort
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * **JsonFileListStore** — [ListPort] mit JSON-Datei-Persistenz, damit die
 * Einkaufsliste einen Backend-Neustart ÜBERLEBT (Andi-JA 2026-07-08 „Listen auf
 * die Ring-1-Karte" — dasselbe Vertrauens-Kriterium, mit dem der Wecker-Store
 * abgenommen wurde). EXAKT das [FileBackedScheduledItemStore]-Muster, auf
 * [ListEntry] reduziert (keine Fired/Ack-Zweitwahrheit — Listen kennen kein
 * „Klingeln").
 *
 *  - Pro-Turn-Reads ([items]) kommen billig aus dem [ConcurrentHashMap]-Cache, NIE
 *    von der Platte. Die Datei wird genau einmal beim Konstruieren gelesen und
 *    danach nur bei Mutationen atomar (Temp-File im Zielverzeichnis + Rename)
 *    neu geschrieben.
 *  - **Persist-then-commit:** jede Mutation ([add]/[remove]/[clear]) schreibt
 *    ZUERST die GEWÜNSCHTE Sicht auf die Platte und committet den Cache NUR bei
 *    bewiesenem Persist. Ein SCHREIB-Fehler ist NICHT schluckbar — er wirft, der
 *    Cache bleibt unangetastet (kein „fake-grün": ein Item, das nur im RAM
 *    existiert, wäre gelogen).
 *  - Ein LESE-Fehler beim Start wirft dagegen NIE: fehlende/kaputte Datei ⇒ leer
 *    starten + WARN (robust per Doktrin — ein kaputtes JSON darf den Boot nicht
 *    verhindern).
 *
 * Datei-Format: JSON-**Array** `[{id, listId, text, quantity, addedAtEpochMs}]`.
 * Gelesen wird tolerant per `readTree` (kein Kotlin-Jackson-Modul nötig);
 * einzelne unbrauchbare Einträge werden mit WARN übersprungen, der Rest lädt.
 * `listId`/`quantity` fehlend (z.B. handgeschriebenes Test-JSON) ⇒ Default
 * [ListPort.DEFAULT_LIST_ID] bzw. `1`.
 *
 * **Privacy** (Tom-Veto, PREP-Risiko 3 — Listeninhalte sind persönliche Daten):
 * Item-TEXTE werden NIE geloggt, nur ids/Zähler in den WARN-Zeilen.
 *
 * Thread-Sicherheit wie der Bestand: [ConcurrentHashMap]-Cache + `@Synchronized`
 * auf allen Mutationen (serialisiert Snapshot-Bau + Datei-Write; [items] bleibt
 * lock-frei).
 */
class JsonFileListStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) : ListPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    private val entries = ConcurrentHashMap<String, ListEntry>()

    init {
        loadInitial()
    }

    /**
     * Legt [entry] an/überschreibt ihn (Schlüssel = id) — **persist-then-commit**:
     * erst die Platte (wirft bei Fehler), dann erst der Cache.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun add(entry: ListEntry): ListEntry {
        val desired = HashMap(entries)
        desired[entry.id] = entry
        writeSnapshot(desired.values)
        entries[entry.id] = entry
        return entry
    }

    /** Alle Einträge EINER Liste (Snapshot), älteste zuerst — reiner Cache-Read, kein I/O. */
    override fun items(listId: String): List<ListEntry> =
        entries.values.filter { it.listId == listId }.sortedBy { it.addedAtEpochMs }

    /**
     * Entfernt genau einen Eintrag — persist-then-commit. Unbekannte id ⇒ `false`
     * OHNE Datei-I/O (nichts zu ändern ⇒ nichts zu persistieren).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun remove(id: String): Boolean {
        if (!entries.containsKey(id)) return false
        val desired = HashMap(entries)
        desired.remove(id)
        writeSnapshot(desired.values)
        entries.remove(id)
        return true
    }

    /**
     * Entfernt ALLE Einträge EINER Liste — persist-then-commit. Leere Liste ⇒ `0`
     * OHNE Datei-I/O.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun clear(listId: String): Int {
        val toRemove = entries.values.filter { it.listId == listId }.map { it.id }
        if (toRemove.isEmpty()) return 0
        val desired = HashMap(entries)
        toRemove.forEach { desired.remove(it) }
        writeSnapshot(desired.values)
        toRemove.forEach { entries.remove(it) }
        return toRemove.size
    }

    // ── Persistenz ────────────────────────────────────────────────────────────

    /** Datei einmalig beim Konstruieren lesen. Fehlend ⇒ leer (still); kaputt ⇒ leer + WARN, wirft NIE. */
    private fun loadInitial() {
        if (!Files.exists(path)) return
        try {
            val root = mapper.readTree(path.toFile()) ?: return
            if (!root.isArray) {
                log.warn("Listen-Datei {} hat unbekannte JSON-Form (kein Array) — starte leer.", path)
                return
            }
            root.forEach { loadEntry(it) }
        } catch (e: Exception) {
            entries.clear()
            log.warn("Listen-Datei {} unlesbar — starte leer (Einträge gingen verloren): {}", path, e.toString())
        }
    }

    /** Einen Eintrag aus dem JSON laden; unbrauchbar ⇒ WARN + überspringen (Text NIE geloggt). */
    private fun loadEntry(node: JsonNode) {
        val id = node.get("id")?.takeIf { it.isTextual }?.textValue()
        val text = node.get("text")?.takeIf { it.isTextual }?.textValue()
        val addedAt = node.get("addedAtEpochMs")?.takeIf { it.canConvertToLong() }?.longValue()
        if (id.isNullOrBlank() || text.isNullOrBlank() || addedAt == null) {
            log.warn("Überspringe unbrauchbaren Listen-Eintrag in {} (id vorhanden: {})", path, !id.isNullOrBlank())
            return
        }
        val listId = node.get("listId")?.takeIf { it.isTextual }?.textValue()?.ifBlank { null }
            ?: ListPort.DEFAULT_LIST_ID
        val quantity = node.get("quantity")?.takeIf { it.canConvertToInt() }?.intValue()?.takeIf { it > 0 } ?: 1
        entries[id] = ListEntry(id = id, listId = listId, text = text, quantity = quantity, addedAtEpochMs = addedAt)
    }

    /**
     * Schreibt ALLE Einträge als JSON-Array: Temp-File im Zielverzeichnis + atomarer
     * Rename. Ein SCHREIB-Fehler ist NICHT schluckbar — er WIRFT, damit die Mutation
     * den Cache nicht fälschlich committet. Aufgeräumt wird best-effort (Temp-Rest
     * gelöscht), der ursprüngliche Fehler fliegt weiter. Deterministisch sortiert
     * (nach `addedAtEpochMs` — diff-freundlich).
     */
    private fun writeSnapshot(active: Collection<ListEntry>) {
        val dir = path.parent ?: throw IOException("Listen-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val root = active.sortedBy { it.addedAtEpochMs }.map { entry ->
            val node = LinkedHashMap<String, Any>()
            node["id"] = entry.id
            node["listId"] = entry.listId
            node["text"] = entry.text
            node["quantity"] = entry.quantity
            node["addedAtEpochMs"] = entry.addedAtEpochMs
            node
        }
        val tmp = Files.createTempFile(dir, ".lists", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(root))
            moveOnto(tmp, path)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /** Atomarer Rename, mit Fallback für Dateisysteme ohne ATOMIC_MOVE. */
    private fun moveOnto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
