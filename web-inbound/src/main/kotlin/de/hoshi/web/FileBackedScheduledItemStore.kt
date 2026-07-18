package de.hoshi.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledItemPort
import de.hoshi.core.port.ScheduledKind
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * **FileBackedScheduledItemStore** — [ScheduledItemPort] **und** [FiredItemsStore] mit
 * JSON-Datei-Persistenz, damit Timer/Wecker UND unbestaetigte Klingel-Ereignisse einen
 * Backend-Neustart ÜBERLEBEN (der Wecker ist der tägliche Vertrauens-Anker; Ring-1-Fix
 * 2026-07-03: ein gefeuerter, noch nicht quittierter Wecker darf durch einen Restart
 * nicht lautlos verschwinden — er bleibt abholbar, notfalls als `missed=true`).
 *
 * Exakt das [JsonFileSkillStateStore]-Muster:
 *  - Pro-Turn-Reads ([query]/[pending]) kommen billig aus [ConcurrentHashMap]-Caches, NIE
 *    von der Platte. Die Datei wird genau einmal beim Konstruieren gelesen und danach nur
 *    bei Mutationen atomar (Temp-File im Zielverzeichnis + Rename) neu geschrieben.
 *  - **Persist-then-commit:** jede Mutation ([set]/[cancel]/[cancelAll]/[add]/[ack])
 *    schreibt ZUERST die GEWÜNSCHTE Sicht auf die Platte und committet den Cache NUR bei
 *    bewiesenem Persist. Ein SCHREIB-Fehler ist NICHT schluckbar — er wirft, der Cache
 *    bleibt unangetastet (kein „fake-grün": ein Wecker, der nur im RAM existiert, wäre
 *    gelogen).
 *  - Ein LESE-Fehler beim Start wirft dagegen NIE: fehlende/kaputte Datei ⇒ leer starten
 *    + WARN (robust per Doktrin — ein kaputtes JSON darf den Boot nicht verhindern).
 *
 * **Bewusst KEIN Ablauf-Aufräumen beim Laden:** der Port-Vertrag ist explizit uhrfrei
 * („der Store selbst rein/uhrfrei: er hält nur absolute Zeitpunkte"). Ob ein während der
 * Downtime fällig gewordener Wecker verspätet klingelt oder als verpasst gemeldet wird,
 * ist Politik des Fire-Service — der Store bewahrt die Information ehrlich.
 *
 * Datei-Format (v2): JSON-**Objekt** `{"items":[…aktive…],"fired":[…gefeuert-unbestätigt…]}`;
 * aktive Einträge `{id, kind, dueAtEpochMs, label?, origin?, originSatelliteId?}`, gefeuerte
 * zusätzlich `{firedAtEpochMs, missed}`. `origin?` (Wecker-Ursprung, additiv) überlebt so den
 * Neustart; fehlt es (Legacy/alt-Client), lädt der Eintrag mit `origin=null`. `originSatelliteId?`
 * (PREP-wecker-am-satelliten, additiv, NUR bei aktiven Items) trägt ebenso tolerant — fehlt es
 * (Legacy/Chat/FE), lädt der Eintrag mit `originSatelliteId=null`.
 * **Legacy-tolerant:** ein nacktes JSON-Array (Format v1)
 * lädt als aktive Items (fired leer). Gelesen wird tolerant per `readTree` (kein
 * Kotlin-Jackson-Modul nötig); einzelne unbrauchbare Einträge werden mit WARN
 * übersprungen, der Rest lädt. Geschrieben wird immer v2.
 *
 * Thread-Sicherheit wie der Bestand: [ConcurrentHashMap]-Caches + `@Synchronized` auf
 * allen Mutationen (serialisiert Snapshot-Bau + Datei-Write; [query]/[pending] bleiben
 * lock-frei).
 */
class FileBackedScheduledItemStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) : ScheduledItemPort, FiredItemsStore {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    private val items = ConcurrentHashMap<String, ScheduledItem>()
    private val firedItems = ConcurrentHashMap<String, FiredItem>()

    init {
        loadInitial()
    }

    // ── ScheduledItemPort (aktive Items) ──────────────────────────────────────

    /**
     * Legt ein Item an (Schlüssel = id) — **persist-then-commit**: erst die Platte (wirft
     * bei Fehler), dann erst der Cache.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun set(item: ScheduledItem): ScheduledItem {
        val desired = HashMap(items)
        desired[item.id] = item
        // Erst Platte (wirft bei Fehler) …
        writeSnapshot(desired.values, firedItems.values)
        // … dann erst der Cache — nur bei bewiesenem Persist.
        items[item.id] = item
        return item
    }

    /** Alle aktiven Items (Snapshot), aufsteigend nach Fälligkeit — reiner Cache-Read, kein I/O. */
    override fun query(): List<ScheduledItem> = items.values.sortedBy { it.dueAtEpochMs }

    /**
     * Storniert genau ein Item — persist-then-commit. Unbekannte id ⇒ `false` OHNE
     * Datei-I/O (nichts zu ändern ⇒ nichts zu persistieren).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun cancel(id: String): Boolean {
        if (!items.containsKey(id)) return false
        val desired = HashMap(items)
        desired.remove(id)
        writeSnapshot(desired.values, firedItems.values)
        items.remove(id)
        return true
    }

    /**
     * Storniert ALLE aktiven Items — persist-then-commit. Leerer Store ⇒ `0` OHNE Datei-I/O.
     * Unbestätigte gefeuerte Items bleiben unberührt (Cancel gilt der Zukunft, nicht dem
     * bereits geschehenen Klingeln).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun cancelAll(): Int {
        val n = items.size
        if (n == 0) return 0
        writeSnapshot(emptyList(), firedItems.values)
        items.clear()
        return n
    }

    // ── FiredItemsStore (gefeuert, unbestätigt) ───────────────────────────────

    /**
     * Legt ein gefeuertes Item ab (persist-then-commit); mehr als
     * [FiredItemsStore.CAPACITY] unbestätigte ⇒ das älteste fällt raus.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun add(item: FiredItem) {
        val desired = HashMap(firedItems)
        desired[item.id] = item
        while (desired.size > FiredItemsStore.CAPACITY) {
            val oldest = desired.values.minWithOrNull(FiredItemsStore.FIRED_ORDER) ?: break
            desired.remove(oldest.id)
        }
        writeSnapshot(items.values, desired.values)
        firedItems.clear()
        firedItems.putAll(desired)
    }

    /**
     * Alle unbestätigten gefeuerten Items (idempotent, kein I/O), älteste Feuerung
     * zuerst; länger als [FiredItemsStore.MISSED_AFTER_MS] unbestätigt ⇒ `missed=true`.
     */
    override fun pending(nowMs: Long): List<FiredItem> =
        FiredItemsStore.withReadTimeMissed(firedItems.values, nowMs)

    /**
     * Quittiert genau ein gefeuertes Item — persist-then-commit; erst DANN ist es weg.
     * Unbekannte id ⇒ `false` OHNE Datei-I/O.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    override fun ack(id: String): Boolean {
        if (!firedItems.containsKey(id)) return false
        val desired = HashMap(firedItems)
        desired.remove(id)
        writeSnapshot(items.values, desired.values)
        firedItems.remove(id)
        return true
    }

    /** Aktuelle Anzahl unbestätigter Items (Test-/Diagnose-Naht, kein I/O). */
    override fun size(): Int = firedItems.size

    // ── Persistenz ────────────────────────────────────────────────────────────

    /** Datei einmalig beim Konstruieren lesen. Fehlend ⇒ leer (still); kaputt ⇒ leer + WARN, wirft NIE. */
    private fun loadInitial() {
        if (!Files.exists(path)) return
        try {
            val root = mapper.readTree(path.toFile()) ?: return
            when {
                // Legacy-Format v1: nacktes Array = nur aktive Items.
                root.isArray -> root.forEach { loadActiveEntry(it) }
                root.isObject -> {
                    root.get("items")?.takeIf { it.isArray }?.forEach { loadActiveEntry(it) }
                    root.get("fired")?.takeIf { it.isArray }?.forEach { loadFiredEntry(it) }
                }
                else -> log.warn("Scheduled-Items-Datei {} hat unbekannte JSON-Form — starte leer.", path)
            }
        } catch (e: Exception) {
            items.clear()
            firedItems.clear()
            log.warn("Scheduled-Items-Datei {} unlesbar — starte leer (Wecker gingen verloren): {}", path, e.toString())
        }
    }

    /** Ein aktives Item aus dem JSON laden; unbrauchbar ⇒ WARN + überspringen. */
    private fun loadActiveEntry(node: JsonNode) {
        val base = parseBase(node)
        if (base == null) {
            log.warn("Überspringe unbrauchbaren Scheduled-Item-Eintrag in {}: {}", path, node)
            return
        }
        val (id, kind, dueAt, label) = base
        items[id] = ScheduledItem(
            id = id, kind = kind, dueAtEpochMs = dueAt, label = label,
            origin = base.origin, originSatelliteId = base.originSatelliteId,
        )
    }

    /** Ein gefeuertes Item aus dem JSON laden; unbrauchbar ⇒ WARN + überspringen. */
    private fun loadFiredEntry(node: JsonNode) {
        val base = parseBase(node)
        val firedAt = node.get("firedAtEpochMs")?.takeIf { it.canConvertToLong() }?.longValue()
        if (base == null || firedAt == null) {
            log.warn("Überspringe unbrauchbaren Fired-Item-Eintrag in {}: {}", path, node)
            return
        }
        val (id, kind, dueAt, label) = base
        val missed = node.get("missed")?.takeIf { it.isBoolean }?.booleanValue() ?: false
        firedItems[id] = FiredItem(
            id = id, kind = kind, label = label,
            dueAtEpochMs = dueAt, firedAtEpochMs = firedAt, missed = missed, origin = base.origin,
        )
    }

    /** Gemeinsame Pflichtfelder beider Eintrag-Arten: `(id, kind, dueAtEpochMs, label?)` oder null. */
    private fun parseBase(node: JsonNode): BaseEntry? {
        val id = node.get("id")?.takeIf { it.isTextual }?.textValue()
        val kindRaw = node.get("kind")?.takeIf { it.isTextual }?.textValue()
        val dueAt = node.get("dueAtEpochMs")?.takeIf { it.canConvertToLong() }?.longValue()
        val kind = kindRaw?.let { raw -> ScheduledKind.entries.firstOrNull { it.name == raw } }
        if (id.isNullOrBlank() || kind == null || dueAt == null) return null
        val label = node.get("label")?.takeIf { it.isTextual }?.textValue()
        val origin = node.get("origin")?.takeIf { it.isTextual }?.textValue()
        // PREP-wecker-am-satelliten: fehlt das Feld (Legacy-Item/gefeuerter Eintrag ohne
        // Satelliten-Ursprung) ⇒ null, tolerant wie [origin] oben.
        val originSatelliteId = node.get("originSatelliteId")?.takeIf { it.isTextual }?.textValue()
        return BaseEntry(id, kind, dueAt, label, origin, originSatelliteId)
    }

    private data class BaseEntry(
        val id: String,
        val kind: ScheduledKind,
        val dueAtEpochMs: Long,
        val label: String?,
        val origin: String?,
        val originSatelliteId: String? = null,
    )

    /**
     * Schreibt BEIDE Abschnitte (aktiv + gefeuert) als Format v2 (`{"items":[…],"fired":[…]}`):
     * Temp-File im Zielverzeichnis + atomarer Rename. Ein SCHREIB-Fehler ist NICHT schluckbar —
     * er WIRFT, damit die Mutation den Cache nicht fälschlich committet. Aufgeräumt wird
     * best-effort (Temp-Rest gelöscht), der ursprüngliche Fehler fliegt weiter.
     * Serialisiert deterministisch (aktiv nach Fälligkeit, gefeuert nach Feuerung — diff-freundlich).
     */
    private fun writeSnapshot(active: Collection<ScheduledItem>, fired: Collection<FiredItem>) {
        val dir = path.parent ?: throw IOException("Scheduled-Items-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val root = LinkedHashMap<String, Any>()
        root["items"] = active.sortedBy { it.dueAtEpochMs }.map { item ->
            val entry = LinkedHashMap<String, Any>()
            entry["id"] = item.id
            entry["kind"] = item.kind.name
            entry["dueAtEpochMs"] = item.dueAtEpochMs
            item.label?.let { entry["label"] = it }
            item.origin?.let { entry["origin"] = it }
            item.originSatelliteId?.let { entry["originSatelliteId"] = it }
            entry
        }
        root["fired"] = fired.sortedWith(FiredItemsStore.FIRED_ORDER).map { item ->
            val entry = LinkedHashMap<String, Any>()
            entry["id"] = item.id
            entry["kind"] = item.kind.name
            entry["dueAtEpochMs"] = item.dueAtEpochMs
            item.label?.let { entry["label"] = it }
            entry["firedAtEpochMs"] = item.firedAtEpochMs
            entry["missed"] = item.missed
            item.origin?.let { entry["origin"] = it }
            entry
        }
        val tmp = Files.createTempFile(dir, ".scheduled-items", ".tmp")
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
