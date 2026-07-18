package de.hoshi.adapters.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * **BridgeSearchClient** — die reale [BridgeSearch]-Naht gegen den Knowledge-Bridge-
 * Sidecar (`hoshi-knowledge-bridge`, FTS5/BM25, Port 8035). Spricht denselben
 * `/search`-Vertrag wie der [Fts5GroundingAdapter], liefert aber nur die schlanken
 * [ProbeHit]s (Titel/Klassifikation/bm25), die die [BridgeKnowledgeProbe] braucht.
 *
 * Bridge-Vertrag (verifiziert live :8035, identisch zur Grounding-Scheibe):
 *   `GET /search?q=<query>&limit=<n>&extract_max_chars=<chars>`
 *   → `{ query, totalHits, hits:[ { title, matchedClassification, bm25Score, … } ] }`
 *
 * **Synchron mit java.net.http** (NICHT WebClient): die [HonestyGate] probt
 * SYNCHRON aus dem reaktiven Turn-Flow heraus (`signal.detect(text): HonestySignal`,
 * aufgerufen im `flatMapMany` des [de.hoshi.core.pipeline.TurnOrchestrator]). Ein
 * `WebClient…block()` würde auf einem reactor-netty-Event-Loop-Thread werfen
 * („block()/blockFirst() … not supported"). `HttpClient.send` ist klassisches
 * Blocking-IO ohne Reactor-Block-Guard — genau die 0.5-Wahl
 * (`WikiKnowledgeSearchService.searchRemote`, Iter-94g-Lehre).
 *
 * **Best-effort:** die Probe darf NIE den Turn crashen. Bridge tot, Timeout,
 * Nicht-200, Parse-Fehler → `emptyList()` (die [BridgeKnowledgeProbe] deutet leer
 * via Sanity-Probe als BRIDGE_DOWN, nie als „existiert nicht").
 */
class BridgeSearchClient(
    baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : BridgeSearch {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    }

    override fun search(query: String, limit: Int): List<ProbeHit> {
        if (query.isBlank()) return emptyList()
        return try {
            val q = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val uri = URI.create("$base/search?q=$q&limit=$limit&extract_max_chars=120")
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("[bridge-probe] HTTP {} für '{}' — leer (best-effort)", response.statusCode(), query.take(60))
                return emptyList()
            }
            parseHits(response.body())
        } catch (e: Exception) {
            log.warn("[bridge-probe] Bridge nicht erreichbar/Fehler ({}) für '{}' — leer", e.message, query.take(60))
            emptyList()
        }
    }

    private fun parseHits(body: String): List<ProbeHit> = runCatching {
        val hitsNode = mapper.readTree(body).path("hits")
        if (!hitsNode.isArray) return emptyList()
        hitsNode.map { n ->
            val title = n.path("title").asText("").trim()
            // Bridge liefert `matchedClassification` (0.5-WikiSearchHit-Feld); defensiv
            // auf `classification` zurückfallen, falls ein Bridge-Stand das Kurzfeld nutzt.
            val classification = n.path("matchedClassification").asText(n.path("classification").asText("")).trim()
            val bm25 = n.path("bm25Score").asDouble(0.0)
            ProbeHit(title = title, classification = classification, bm25 = bm25)
        }
    }.getOrElse { emptyList() }
}
