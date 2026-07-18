package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.LookupNote
import de.hoshi.core.port.LookupNotePort
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * **JsonlLookupNoteAdapter** — die reale [LookupNotePort]-Implementierung des
 * Nachgeschlagen-Stores (Extended Think S3): jede bezahlte Eskalations-Antwort
 * wird EINE JSONL-Zeile in EINER durchgehenden Datei (`nachgeschlagen.jsonl`,
 * KEINE Tages-Rotation wie beim [JsonlTurnTraceAdapter] — eine Notiz bleibt über
 * Tage/Wochen ([LookupNote.ttlDays]) relevant, nicht nur einen Tag).
 *
 * **ZEILE FÜR ZEILE nach dem [JsonlTurnTraceAdapter]-Muster:** [record] reiht nur
 * ein (nicht-blockierend, kehrt sofort zurück), EIN Daemon-Schreib-Thread
 * serialisiert der Reihe nach — best-effort, wirft NIE (der Turn ist heilig,
 * die Notiz nicht; volle Queue ⇒ Notiz verworfen, kein Warten am Aufrufer-Rand).
 *
 * **Normatives Notiz-Format** ([LookupNote] core-domain-KDoc): die Nachtschicht
 * erbt dieses Format 1:1 (bindender Entscheid #4) — Feld-Namen/-Reihenfolge NICHT
 * ohne Rücksprache ändern.
 *
 * [find] ist ein SYNCHRONER Best-effort-Lese-Pfad (exaktes [LookupNote.queryNorm]-
 * Match, der NEUESTE Treffer gewinnt) — er dient vor allem dem Adapter-eigenen
 * Rundtrip-Test. Der PRODUKTIVE Cache-Hit-Lese-Pfad ist
 * [de.hoshi.adapters.knowledge]s `NachgeschlagenGroundingProvider`: er liest
 * DIESELBE Datei UNABHÄNGIG (eigener Datei-Rand, damit `adapters-knowledge`
 * NICHT von `adapters-supervision` abhängen muss) — „eine Datei-Wahrheit, zwei
 * schmale Ränder".
 */
class JsonlLookupNoteAdapter(
    private val path: Path = resolveDefaultPath(null),
) : LookupNotePort, AutoCloseable {

    private val log = LoggerFactory.getLogger(JsonlLookupNoteAdapter::class.java)
    private val mapper = ObjectMapper()

    /**
     * Genau EIN Schreib-Thread (Ankunftsreihenfolge, kein File-Lock nötig), Daemon
     * (blockiert nie den JVM-Shutdown), Queue hart begrenzt mit Discard-Policy —
     * exakt das [JsonlTurnTraceAdapter]-Muster.
     */
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "lookup-note-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    /** Nicht-blockierend: nur einreihen; alles Weitere passiert auf dem Writer-Thread. */
    override fun record(note: LookupNote) {
        try {
            executor.execute { writeLine(note) }
        } catch (e: Exception) {
            // RejectedExecution nach close() o.ä. — der Turn ist heilig, die Notiz nicht.
            log.warn("lookup-note: Notiz verworfen ({})", e.toString())
        }
    }

    /** Läuft AUSSCHLIESSLICH auf dem Writer-Thread — best-effort, wirft nie. */
    private fun writeLine(note: LookupNote) {
        try {
            val line = serialize(note) + "\n"
            path.parent?.let { Files.createDirectories(it) }
            Files.write(
                path,
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            log.warn("lookup-note: Schreiben fehlgeschlagen, Notiz verworfen ({})", e.toString())
        }
    }

    /**
     * Eine Zeile JSON, Feld-Reihenfolge exakt normativ (s. [LookupNote]-KDoc):
     * queryHash · queryNorm · answer · source · provider · costCents · ts · ttlDays · origin.
     * `ts` als ISO-8601-String (lesbar, `jq`-freundlich), geordnete Map statt
     * Jackson-Datenklassen-Serialisierung — stabile Feld-Reihenfolge, kein jsr310-Modul nötig.
     */
    internal fun serialize(note: LookupNote): String = mapper.writeValueAsString(
        linkedMapOf<String, Any?>(
            "queryHash" to note.queryHash,
            "queryNorm" to note.queryNorm,
            "answer" to note.answer,
            "source" to note.source,
            "provider" to note.provider,
            "costCents" to note.costCents,
            "ts" to note.ts.toString(),
            "ttlDays" to note.ttlDays,
            "origin" to note.origin,
        ),
    )

    /**
     * Best-effort synchroner Lese-Pfad (exaktes [LookupNote.queryNorm]-Match, letzter
     * Treffer gewinnt — append-only, "letzter" = jüngster). Datei fehlt/kaputte
     * Zeile ⇒ übersprungen bzw. `null`, wirft NIE.
     */
    override fun find(queryNorm: String): LookupNote? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            Files.readAllLines(path, StandardCharsets.UTF_8)
                .asSequence()
                .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
                .lastOrNull { it.queryNorm == queryNorm }
        }.getOrElse { e ->
            log.warn("lookup-note: Lesen fehlgeschlagen ({})", e.toString())
            null
        }
    }

    /** Parst eine JSONL-Zeile zurück zur [LookupNote] — fehlendes Pflichtfeld ⇒ `null` (Zeile übersprungen). */
    private fun parse(line: String): LookupNote? {
        if (line.isBlank()) return null
        val node = mapper.readTree(line)
        val queryHash = node.get("queryHash")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val queryNorm = node.get("queryNorm")?.asText() ?: return null
        val answer = node.get("answer")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val ts = node.get("ts")?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return LookupNote(
            queryHash = queryHash,
            queryNorm = queryNorm,
            answer = answer,
            source = node.get("source")?.asText() ?: "",
            provider = node.get("provider")?.asText() ?: "",
            costCents = node.get("costCents")?.asDouble() ?: 0.0,
            ts = ts,
            ttlDays = node.get("ttlDays")?.asInt() ?: 0,
            origin = node.get("origin")?.asText() ?: LookupNote.ORIGIN_LIVE,
        )
    }

    /** Flush für Tests/Shutdown: wartet kurz, bis die Queue geschrieben ist. */
    override fun close() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        /** Dateiname der EINEN durchgehenden Notiz-Datei (KEINE Tages-Rotation). */
        const val FILE_NAME: String = "nachgeschlagen.jsonl"

        /** Hartes Queue-Limit — mehr ungeschriebene Notizen als das ⇒ verwerfen statt stauen. */
        const val QUEUE_CAPACITY: Int = 1024

        /**
         * Pfad-Auflösung wie Spend/Diary (`FileBackedEscalationSpendStore`-Muster):
         * explizit konfiguriert ▷ Prod-Datenverzeichnis
         * `/var/lib/hoshi-0.8/lookups/nachgeschlagen.jsonl` (nur wenn beschreibbar)
         * ▷ Dev-Fallback `~/.hoshi/lookups/nachgeschlagen.jsonl`.
         *
         * **„Eine Datei-Wahrheit, zwei schmale Ränder":** [de.hoshi.adapters.knowledge]s
         * `NachgeschlagenGroundingProvider` bekommt in der Wiring (PipelineConfig)
         * denselben @Value-Key vorgelegt und ruft DIESE Funktion ebenfalls auf (die
         * Provider-Seite importiert `adapters-supervision` NICHT — sie bekommt den
         * fertig aufgelösten [Path] als Konstruktor-Parameter gereicht) — beide Seiten
         * landen so garantiert auf demselben Pfad, ohne dass die Module voneinander
         * abhängen.
         */
        fun resolveDefaultPath(explicit: String?): Path = when {
            !explicit.isNullOrBlank() -> Path.of(explicit.trim())
            Files.isWritable(Path.of("/var/lib/hoshi-0.8")) -> Path.of("/var/lib/hoshi-0.8/lookups/$FILE_NAME")
            else -> Path.of(System.getProperty("user.home"), ".hoshi", "lookups", FILE_NAME)
        }
    }
}
