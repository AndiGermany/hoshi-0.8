package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Eine gemerkte Ablesung: `state` roh (HA-String), `attrs` bereits auf die Allowlist gefiltert, `seenAt` wann sie zuletzt USABLE gesehen wurde. */
data class LastKnownState(val state: String, val attrs: Map<String, String>, val seenAt: Instant)

/**
 * Minimaler Vertrag fürs Merken „letzter brauchbarer Zustand" — lässt
 * [HaHomeRegistryAdapter] wahlweise mit der echten Persistenz
 * ([HaLastKnownStateStore]) oder einem reinen RAM-Fallback
 * ([InMemoryLastKnownStateStore], Adapter-Default) arbeiten.
 */
interface LastKnownStateStore {
    /** Letzte gemerkte Ablesung dieser Entity, `null` wenn nie eine brauchbare gesehen wurde. */
    fun get(entityId: String): LastKnownState?

    /** Merkt [readings] (entityId -> brauchbare Ablesung DIESER Runde). Wirft NIE. */
    fun record(readings: Map<String, LastKnownState>)
}

/**
 * Reiner RAM-Speicher OHNE Persistenz — der [HaHomeRegistryAdapter]-Default,
 * wenn kein echter Store injiziert wird (z.B. in Tests, die die Last-known-
 * Naht nicht prüfen, s. [HaHomeRegistryAdapterTest]). Merkt innerhalb des
 * laufenden Prozesses trotzdem korrekt (kein Datenverlust während der
 * Laufzeit) — nur ein Neustart verliert den Stand, anders als
 * [HaLastKnownStateStore].
 */
class InMemoryLastKnownStateStore : LastKnownStateStore {
    @Volatile private var cache: Map<String, LastKnownState> = emptyMap()
    override fun get(entityId: String): LastKnownState? = cache[entityId]
    @Synchronized
    override fun record(readings: Map<String, LastKnownState>) {
        if (readings.isNotEmpty()) cache = cache + readings
    }
}

/**
 * **HaLastKnownStateStore** — der „Sauger-Sichtbarkeits-Lücke"-Speicher
 * (Andi-Auftrag 2026-08-13): der Roborock schläft ~23 h/Tag im WLAN-
 * Tiefschlaf, der `GET /api/states`-Merge (eigene kurze TTL, s.
 * [HaHomeRegistryAdapter]-KDoc „States-Frische") trifft sein Wach-Fenster
 * fast nie. Statt dann IMMER „nicht erreichbar" zu zeigen, merkt sich dieser
 * Store je Entity die letzte BRAUCHBARE Ablesung (state weder null noch
 * `unavailable`/`unknown`) — der Adapter hängt sie NUR an, wenn der LIVE-
 * Zustand einer Entity gerade unbrauchbar ist.
 *
 * Persistenz nach dem [de.hoshi.adapters.escalation.FileBackedEscalationSpendStore]-
 * Muster: [get] kommt billig aus dem RAM-Cache, [record] schreibt Temp-File +
 * atomarer Rename im selben Verzeichnis. ANDERS als der Spend-Store aber KEIN
 * Geld-Vorrang — ein Persist-Fehler bleibt hier rein best-effort (WARN, der
 * RAM-Stand gilt trotzdem): ein last-known-Wert, der einen Neustart NICHT
 * überlebt, ist kein Schaden, nur ein kalter Cache.
 *
 * **Drossel:** der RAM-Cache wird JEDE Runde aktualisiert (die Kachel sieht
 * bei einem laufenden Prozess also immer den frischesten `seenAt`), auf die
 * Platte geschrieben wird aber NUR, wenn sich `state`/`attrs` irgendeiner
 * Entity gegenüber dem zuletzt GESCHRIEBENEN Stand ändert — ein stabiler
 * Sauger („docked" über Stunden) erzeugt sonst jede TTL-Runde einen
 * Schreibzugriff ohne jeden Informationsgewinn.
 *
 * **Kaputte/fehlende Datei ⇒ leer starten** (never-throw), exakt wie
 * [de.hoshi.adapters.escalation.FileBackedEscalationSpendStore.loadInitial].
 */
class HaLastKnownStateStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) : LastKnownStateStore {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    @Volatile private var cache: Map<String, LastKnownState> = loadInitial()

    /** Zuletzt AUF DIE PLATTE geschriebener Stand — Vergleichsbasis der Drossel (s. Klassen-KDoc). */
    private var persisted: Map<String, LastKnownState> = cache

    /** Zählt tatsächliche Schreibversuche — testbare Drossel-Probe, kein Teil des öffentlichen Vertrags. */
    internal var writeCount: Int = 0
        private set

    override fun get(entityId: String): LastKnownState? = cache[entityId]

    @Synchronized
    override fun record(readings: Map<String, LastKnownState>) {
        if (readings.isEmpty()) return
        val updated = cache + readings
        cache = updated
        val changed = readings.any { (id, reading) ->
            val prior = persisted[id]
            prior == null || prior.state != reading.state || prior.attrs != reading.attrs
        }
        if (!changed) return
        try {
            persist(updated)
            persisted = updated
        } catch (e: Exception) {
            // never-throw (s. Klassen-KDoc): der RAM-Stand ist bereits committet, nur die Platte hinkt hinterher.
            log.warn("[ha-last-known] Persist nach {} fehlgeschlagen — RAM-Stand bleibt aktuell: {}", path, e.toString())
        }
    }

    /** Datei einmalig beim Konstruieren lesen. Fehlend ⇒ leer (still); kaputt/unlesbar ⇒ leer + WARN, wirft NIE. */
    private fun loadInitial(): Map<String, LastKnownState> {
        if (!Files.exists(path)) return emptyMap()
        return try {
            val root = mapper.readTree(path.toFile())
            if (root == null || !root.isObject) {
                log.warn("[ha-last-known] Datei {} kein JSON-Objekt — starte leer.", path)
                return emptyMap()
            }
            val out = LinkedHashMap<String, LastKnownState>()
            root.fields().forEach { (entityId, node) ->
                val state = node.get("state")?.takeIf { it.isTextual }?.textValue()
                val seenAtRaw = node.get("seenAt")?.takeIf { it.isTextual }?.textValue()
                val seenAt = seenAtRaw?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
                if (state == null || seenAt == null) return@forEach // kaputter Eintrag ⇒ ehrlich überspringen, Rest bleibt gültig
                val attrsNode = node.get("attrs")
                val attrs = LinkedHashMap<String, String>()
                if (attrsNode != null && attrsNode.isObject) {
                    attrsNode.fields().forEach { (key, value) -> if (value.isTextual) attrs[key] = value.asText() }
                }
                out[entityId] = LastKnownState(state, attrs, seenAt)
            }
            out
        } catch (e: Exception) {
            log.warn("[ha-last-known] Datei {} unlesbar — starte leer: {}", path, e.toString())
            emptyMap()
        }
    }

    /** Temp-File im Zielverzeichnis + atomarer Rename; ein Schreib-Fehler WIRFT (der Aufrufer [record] fängt sie). */
    private fun persist(snapshot: Map<String, LastKnownState>) {
        val dir = path.parent ?: throw IOException("Last-known-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val root = mapper.createObjectNode()
        for ((entityId, reading) in snapshot) {
            val node = root.putObject(entityId)
            node.put("state", reading.state)
            node.put("seenAt", reading.seenAt.toString())
            val attrsNode = node.putObject("attrs")
            reading.attrs.forEach { (key, value) -> attrsNode.put(key, value) }
        }
        val tmp = Files.createTempFile(dir, ".ha-last-known", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(root))
            moveOnto(tmp, path)
            writeCount++
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

    companion object {
        /**
         * Pfad-Auflösung wie [de.hoshi.adapters.escalation.FileBackedEscalationSpendStore.resolveDefaultPath]:
         * explizit konfiguriert ▷ Prod-Datenverzeichnis `/var/lib/hoshi-0.8/ha/last-known-states.json`
         * (nur wenn beschreibbar) ▷ Dev-Fallback `~/.hoshi/ha/last-known-states.json`.
         */
        fun resolveDefaultPath(explicit: String?): Path = when {
            !explicit.isNullOrBlank() -> Path.of(explicit.trim())
            Files.isWritable(Path.of("/var/lib/hoshi-0.8")) -> Path.of("/var/lib/hoshi-0.8/ha/last-known-states.json")
            else -> Path.of(System.getProperty("user.home"), ".hoshi", "ha", "last-known-states.json")
        }
    }
}
