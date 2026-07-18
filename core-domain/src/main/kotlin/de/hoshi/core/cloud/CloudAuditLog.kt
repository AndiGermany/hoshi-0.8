package de.hoshi.core.cloud

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock

/**
 * Eine Audit-Zeile pro Cloud-Eskalation. **Trägt NIE den Klartext** der Anfrage —
 * nur den [queryHash] (SHA-256). Das erlaubt Nachvollziehbarkeit (`wie viele
 * Calls`, `welcher Provider`, `erlaubt/geblockt`, `was hat es gekostet`), ohne je
 * zu persistieren, WAS gefragt wurde.
 */
data class CloudAuditEntry(
    /** ISO-8601, via injizierte [Clock]. */
    val timestamp: String,
    /** SHA-256(query) als Hex — der Klartext bleibt lokal. */
    val queryHash: String,
    /** Provider-Schlüssel (z.B. 'openai', 'none'). */
    val provider: String,
    /** Geschätzte Kosten dieses Calls in EUR. */
    val estimatedCostEur: Double,
    /** true = der Riegel hat den Call durchgelassen, false = geblockt (Cap/Budget). */
    val allowed: Boolean,
)

/**
 * **CloudAuditLog** — append-only JSONL-Audit der Cloud-Eskalationen.
 *
 * Privacy-Vertrag (aus Hoshi 0.5 `CloudCallAudit` portiert): es landet **nur ein
 * SHA-256-Hash** der Anfrage im File, NIE der Klartext. Pro Eskalation EINE Zeile.
 *
 * Rein + testbar: der Datei-[path] und die [clock] werden injiziert — kein
 * `now()`, kein hartkodiertes Verzeichnis. Append-only (kein atomic-replace): bei
 * einem Crash geht maximal die letzte Zeile verloren, nie das ganze File.
 *
 * **Schreibfehler sind nie fatal:** [record]/[append] schlucken jede Exception
 * (kein Logger im reinen Kern), damit ein voller/kaputter Datenträger NIE einen
 * Turn killt (Never-Silent).
 */
class CloudAuditLog(
    private val path: Path,
    private val clock: Clock,
) {
    /**
     * Hasht [query] (Klartext bleibt lokal) und schreibt EINE Audit-Zeile.
     * Der bevorzugte Eingang — so kann gar kein Klartext ins File gelangen.
     */
    fun record(query: String, provider: String, estimatedCostEur: Double, allowed: Boolean) {
        append(
            CloudAuditEntry(
                timestamp = clock.instant().toString(),
                queryHash = sha256(query),
                provider = provider,
                estimatedCostEur = estimatedCostEur,
                allowed = allowed,
            ),
        )
    }

    /**
     * Hängt eine bereits gehashte [entry] an. Robust: jeder I/O-Fehler wird
     * geschluckt (der Cloud-Turn darf daran nie sterben).
     */
    fun append(entry: CloudAuditEntry) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                path,
                render(entry) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /** Serialisiert die Zeile als kompaktes JSON-Objekt (hand-gerollt, ASCII). */
    private fun render(entry: CloudAuditEntry): String = buildString {
        append('{')
        append(jsonStr("timestamp")).append(':').append(jsonStr(entry.timestamp)).append(',')
        append(jsonStr("queryHash")).append(':').append(jsonStr(entry.queryHash)).append(',')
        append(jsonStr("provider")).append(':').append(jsonStr(entry.provider)).append(',')
        append(jsonStr("estimatedCostEur")).append(':').append(entry.estimatedCostEur).append(',')
        append(jsonStr("allowed")).append(':').append(entry.allowed)
        append('}')
    }

    /** Minimaler JSON-String-Escaper (genug fuer unsere kontrollierten Felder). */
    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    companion object {
        /** Voller SHA-256-Hex von [text] (UTF-8). Kein Reverse zur Original-Query. */
        fun sha256(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
