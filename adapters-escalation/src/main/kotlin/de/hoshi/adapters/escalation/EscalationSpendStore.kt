package de.hoshi.adapters.escalation

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.LocalDate

/**
 * **Tages-Spend-Zähler der Eskalation** — der Geld-Riegel hinter dem
 * 0,50-€/Tag-Cap (bindender Orchestrator-Entscheid #2). Bewusst ein schmales
 * Interface, damit Tests einen Fake injizieren können und S5 die Budget-Zeile
 * (`spendTodayCents`) read-only ziehen kann.
 */
interface EscalationSpendStore {

    /** Heute bereits gebuchte Cents (Tageswechsel ⇒ 0.0). Reiner Cache-Read. */
    fun spentTodayCents(): Double

    /**
     * Bucht [cents] auf den heutigen Tag (Tageswechsel ⇒ vorher Reset) und
     * liefert den neuen Tages-Stand. Wirft NIE (siehe Impl-Vertrag).
     */
    fun book(cents: Double): Double
}

/**
 * **FileBackedEscalationSpendStore** — [EscalationSpendStore] mit JSON-Datei,
 * damit der Tages-Cap einen Backend-Neustart ÜBERLEBT (Restart-fest: ein Cap,
 * der bei jedem Restart auf 0 springt, wäre gelogen).
 *
 * Datei-Format: `{"date":"2026-07-05","spentCents":1.25}`.
 *
 * Persistenz nach dem [de.hoshi.web]-`FileBackedScheduledItemStore`-Muster:
 *  - Reads ([spentTodayCents]) kommen billig aus dem Cache, NIE von der Platte.
 *    Die Datei wird genau einmal beim Konstruieren gelesen und danach nur bei
 *    Buchungen atomar (Temp-File im Zielverzeichnis + Rename) neu geschrieben.
 *  - **Persist-then-commit** im Happy-Path: erst die Platte, dann der Cache.
 *  - **Bewusste Abweichung beim SCHREIB-Fehler (Geld-Richtung, dokumentiert):**
 *    anders als beim Wecker-Store wird bei einem Persist-Fehler TROTZDEM in den
 *    RAM-Cache committet (+ WARN). Grund: das Geld ist zum Buchungszeitpunkt
 *    bereits ausgegeben — eine verworfene Buchung würde den Cap nach oben
 *    aufweichen (unbegrenzter Spend bei kaputter Platte). Die konservative
 *    Richtung ist hier: IMMER zählen, Persistenz best-effort. Schlimmster Fall
 *    ist ein Neustart während Platten-Defekt ⇒ Untererfassung genau der in
 *    dieser Zeit gebuchten Cents — sichtbar per WARN, nie still.
 *  - Ein LESE-Fehler beim Start wirft NIE: fehlende/kaputte Datei ⇒ 0 + WARN.
 *
 * Tageswechsel: [LocalDate.now] über die injizierte [clock] (Test-Naht);
 * gelesener Fremdtag zählt als 0, die nächste Buchung schreibt den neuen Tag.
 */
class FileBackedEscalationSpendStore(
    path: Path,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val mapper: ObjectMapper = ObjectMapper(),
) : EscalationSpendStore {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    private var date: LocalDate = LocalDate.now(clock)
    private var spentCents: Double = 0.0

    init {
        loadInitial()
    }

    @Synchronized
    override fun spentTodayCents(): Double =
        if (date == LocalDate.now(clock)) spentCents else 0.0

    @Synchronized
    override fun book(cents: Double): Double {
        val today = LocalDate.now(clock)
        val base = if (date == today) spentCents else 0.0
        val desired = base + cents.coerceAtLeast(0.0)
        try {
            writeSnapshot(today, desired)
        } catch (e: Exception) {
            // Geld-Richtung (s. Klassen-KDoc): trotzdem im RAM zählen, nie werfen.
            log.warn(
                "[escalation-spend] Persist nach {} fehlgeschlagen — zähle nur im RAM weiter (Cap hält im Prozess): {}",
                path, e.toString(),
            )
        }
        date = today
        spentCents = desired
        return desired
    }

    /** Datei einmalig beim Konstruieren lesen. Fehlend ⇒ 0 (still); kaputt ⇒ 0 + WARN, wirft NIE. */
    private fun loadInitial() {
        if (!Files.exists(path)) return
        try {
            val root = mapper.readTree(path.toFile()) ?: return
            val rawDate = root.get("date")?.takeIf { it.isTextual }?.textValue()
            val rawSpent = root.get("spentCents")?.takeIf { it.isNumber }?.doubleValue()
            if (rawDate == null || rawSpent == null) {
                log.warn("[escalation-spend] Datei {} ohne date/spentCents — starte bei 0.", path)
                return
            }
            date = LocalDate.parse(rawDate)
            spentCents = rawSpent.coerceAtLeast(0.0)
        } catch (e: Exception) {
            date = LocalDate.now(clock)
            spentCents = 0.0
            log.warn("[escalation-spend] Datei {} unlesbar — starte bei 0: {}", path, e.toString())
        }
    }

    /** Temp-File im Zielverzeichnis + atomarer Rename; ein Schreib-Fehler WIRFT (der Aufrufer entscheidet). */
    private fun writeSnapshot(day: LocalDate, cents: Double) {
        val dir = path.parent ?: throw IOException("Spend-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val root = LinkedHashMap<String, Any>()
        root["date"] = day.toString()
        root["spentCents"] = cents
        val tmp = Files.createTempFile(dir, ".escalation-spend", ".tmp")
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

    companion object {
        /**
         * Pfad-Auflösung wie Timer/Diary (PipelineConfig-Muster): explizit
         * konfiguriert ▷ Prod-Datenverzeichnis `/var/lib/hoshi-0.8/escalation/spend.json`
         * (nur wenn beschreibbar) ▷ Dev-Fallback `~/.hoshi/escalation/spend.json`.
         */
        fun resolveDefaultPath(explicit: String?): Path = when {
            !explicit.isNullOrBlank() -> Path.of(explicit.trim())
            Files.isWritable(Path.of("/var/lib/hoshi-0.8")) ->
                Path.of("/var/lib/hoshi-0.8/escalation/spend.json")
            else ->
                Path.of(System.getProperty("user.home"), ".hoshi", "escalation", "spend.json")
        }
    }
}
