package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.RouteCategory
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Live-Grounding-Beweis (`bin/hoshi ground`): grün≠lebt. Beweist, dass der
 * [Fts5GroundingAdapter] gegen die ECHTE Knowledge-Bridge (:8035) eine reale
 * deutsche Wiki-Passage abruft und einen kompakten Grounding-Block baut.
 *
 * Query via CLI-Argumenten (Default „Wer war Konrad Adenauer?"). Bridge-URL via
 * `HOSHI_KNOWLEDGE_BRIDGE_URL` (Default `http://localhost:8035`). Exit 0 nur, wenn
 * der Block NICHT leer ist (echte Passage kam an).
 */
fun main(args: Array<String>) {
    val baseUrl = System.getenv("HOSHI_KNOWLEDGE_BRIDGE_URL")?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8035"
    val query = if (args.isNotEmpty()) args.joinToString(" ").trim() else "Wer war Konrad Adenauer?"

    val adapter = Fts5GroundingAdapter(baseUrl = baseUrl)

    println("[ground-smoke] Bridge : $baseUrl")
    println("[ground-smoke] Query  : $query")

    val block = adapter.groundingBlock(query, RouteCategory.FACT_SHORT)
        .block(Duration.ofSeconds(15)) ?: ""

    println("[ground-smoke] ----- Grounding-Block (wörtlich) -----")
    println(block.ifBlank { "(leer)" })
    println("[ground-smoke] --------------------------------------")

    if (block.isBlank()) {
        System.err.println("[ground-smoke] FAIL: leerer Block — Bridge (:8035) aus oder kein Treffer.")
        exitProcess(1)
    }
    println("[ground-smoke] OK — echte Wiki-Passage über die Bridge abgerufen.")
    exitProcess(0)
}
