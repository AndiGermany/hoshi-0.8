package de.hoshi.adapters.routing

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.RouteRefiner
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * **EmbeddingRouterRefiner** — der ECHTE AMBIG-Resolver (ersetzt den
 * [de.hoshi.web.stub.PassthroughRefinerStubAdapter], der die Route unverändert
 * durchreicht). Portiert die Soft-Routing-Cascade-Embedding-Variante aus Hoshi 0.5
 * (`EmbeddingRouterService`, B-047, Iter-94i):
 *
 *   Query → Embedding (`embeddinggemma:300m`, ~82ms warm, ~1GB) → höchste
 *   cosine-Ähnlichkeit zu vorab eingebetteten Kategorie-Ankern → schärfere
 *   [RouteDecision].
 *
 * 30× schneller als die LLM-JSON-Klassifikation (3–15s) und ohne deren
 * Degenerations-Risiko. Der Refiner liefert NUR die `category` (kein rag_source/
 * persona_tone) — für den AMBIG→category-Resolver reicht das.
 *
 * Spring-entkoppelt (wie [de.hoshi.adapters.memory.EpisodicMemoryAdapter]): kein
 * `@Service`, keine `HoshiProperties`. Die Embed-Naht ([RouteEmbedder]) ist als
 * `fun interface` injizierbar → Unit-Tests fahren deterministisch ohne Ollama.
 *
 * **Best-effort (Never-Crash):** Ollama down/Timeout/Fehler, Anker nicht
 * einbettbar, leeres Query-Embedding oder Score unter [confidenceMin] → die
 * Hop-1-[fallback]-Decision kommt UNVERÄNDERT zurück. Routing-Qualität ist
 * additiv, sie kippt den Turn nie.
 *
 * **Max-1-Brain-Call/Turn bleibt unberührt:** der Embed-Roundtrip ist ein billiger
 * Vektor-Call (kein Generieren). Die teuren `embed`-Aufrufe (Anker-Aufbau beim
 * ersten Refine + Query) laufen auf `boundedElastic`, nie im Reactor-Thread.
 */
class EmbeddingRouterRefiner(
    private val embedder: RouteEmbedder,
    private val confidenceMin: Double = DEFAULT_CONFIDENCE_MIN,
) : RouteRefiner {

    private val log = LoggerFactory.getLogger(javaClass)

    // Kategorie-Anker-Embeddings, lazy berechnet + gecacht (einmalig). category → vectors.
    private val anchorVectors = ConcurrentHashMap<RouteCategory, List<DoubleArray>>()
    @Volatile private var anchorsReady = false

    /**
     * Verfeinert eine AMBIG-Hop-1-Decision via Embedding-Ähnlichkeit. Niemals ein
     * Fehler nach außen — bei Ollama-Problem/low-confidence kommt [fallback] zurück.
     * Die blockierenden `embed`-Calls (Anker + Query) laufen auf `boundedElastic`.
     */
    override fun refine(text: String, fallback: RouteDecision): Mono<RouteDecision> =
        Mono.fromCallable { refineNow(text, fallback) }
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { e ->
                log.warn("[embed-router] Fehler/Timeout, Fallback: {}", e.message)
                Mono.just(fallback)
            }

    /** Synchroner Refine-Kern (Embed + Cosine-Scan). `internal` für deterministische Tests. */
    internal fun refineNow(text: String, fallback: RouteDecision): RouteDecision {
        if (!ensureAnchors()) return fallback
        val queryVec = embedder.embed(text)
        if (queryVec.isEmpty()) return fallback
        val (bestCategory, bestScore) = bestMatch(queryVec)
        if (bestScore < confidenceMin) {
            log.debug("[embed-router] low_confidence sim={} < {} — Fallback",
                "%.3f".format(bestScore), confidenceMin)
            return fallback
        }
        val refined = RouteDecision(
            category = bestCategory,
            // Bewusst immer LOCAL: Cloud-Eskalation passiert nachgelagert (0.5-Lehre).
            // Der Router wählt nur die Kategorie, nicht den Provider.
            provider = RouteProvider.LOCAL,
            reason = "embed_routing(sim=${"%.3f".format(bestScore)})",
        )
        log.info("[embed-router] AMBIG → {} (sim={})", refined.category, "%.3f".format(bestScore))
        return refined
    }

    /**
     * Bettet alle Kategorie-Anker einmalig ein (lazy, idempotent, synchronized).
     * Schlägt ein Anker fehl (leeres Embedding → Ollama kalt/weg), bleibt
     * [anchorsReady] false und der Refiner reicht jede Decision unverändert durch.
     */
    private fun ensureAnchors(): Boolean {
        if (anchorsReady) return true
        synchronized(this) {
            if (anchorsReady) return true
            var ok = true
            for ((category, phrases) in ANCHORS) {
                val vectors = phrases.mapNotNull { phrase ->
                    val v = embedder.embed(phrase)
                    if (v.isEmpty()) { ok = false; null } else v
                }
                if (vectors.isNotEmpty()) anchorVectors[category] = vectors
            }
            if (ok && anchorVectors.isNotEmpty()) {
                anchorsReady = true
                log.info("[embed-router] {} Kategorie-Anker eingebettet ({} Vektoren)",
                    anchorVectors.size, anchorVectors.values.sumOf { it.size })
            } else {
                // Unvollständig: verworfen, damit der nächste Refine es erneut versucht
                // (Ollama kommt evtl. später hoch). Bis dahin: Fallback-Durchreichung.
                anchorVectors.clear()
                log.warn("[embed-router] Anker-Embedding unvollständig — Fallback bleibt aktiv")
            }
            return anchorsReady
        }
    }

    /** Höchste cosine-Ähnlichkeit über alle Anker aller Kategorien. */
    private fun bestMatch(queryVec: DoubleArray): Pair<RouteCategory, Double> {
        var bestCat = RouteCategory.AMBIG
        var bestScore = -1.0
        for ((category, vectors) in anchorVectors) {
            for (anchorVec in vectors) {
                val sim = cosine(queryVec, anchorVec)
                if (sim > bestScore) { bestScore = sim; bestCat = category }
            }
        }
        return bestCat to bestScore
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    companion object {
        /** Cosine-Schwelle (embeddinggemma) für „sicher genug" — sonst Fallback. */
        const val DEFAULT_CONFIDENCE_MIN = 0.6

        /**
         * Kategorie-Anker: deutsche Beispiel-Phrasen pro [RouteCategory]. Die Query
         * wird der Kategorie zugeordnet, deren bester Anker am ähnlichsten ist.
         * Mehrere Anker pro Kategorie = robustere Abdeckung verschiedener
         * Formulierungen. (1:1 portiert aus 0.5 `EmbeddingRouterService`.)
         */
        private val ANCHORS: Map<RouteCategory, List<String>> = mapOf(
            RouteCategory.SMALLTALK to listOf(
                "hallo wie geht es dir",
                "guten morgen",
                "erzähl mir einen witz",
                "ich bin müde heute",
                "danke das war nett",
            ),
            RouteCategory.SMART_HOME to listOf(
                "mach das licht im wohnzimmer an",
                "stell die heizung auf 21 grad",
                "fahr die rollos runter",
                "schalte die lampe aus",
                "aktiviere die szene gemütlich",
            ),
            RouteCategory.FACT_SHORT to listOf(
                "wie spät ist es",
                "wer war albert einstein",
                "was ist die hauptstadt von frankreich",
                "wie viele beine hat eine spinne",
                "wann ist weihnachten",
            ),
            RouteCategory.NEEDS_WEB to listOf(
                "wie wird das wetter morgen",
                "was steht in der wikipedia über den kölner dom",
                "such im internet nach aktuellen nachrichten",
                "was kostet bitcoin gerade",
                "welche filme laufen aktuell im kino",
            ),
            RouteCategory.AGENT to listOf(
                "plane mir einen tag mit mehreren terminen",
                "schreib eine email und fasse das dokument zusammen",
                "vergleiche drei produkte und erstelle eine tabelle",
                "recherchiere und fass die ergebnisse zusammen",
            ),
        )
    }
}

/**
 * Embed-Naht des Embedding-Routers: Text → Vektor (leer = nicht verfügbar).
 * Injizierbar, damit Unit-Tests deterministisch ohne Netz fahren; live bindet
 * [OllamaRouteEmbedder] (`embeddinggemma:300m`).
 */
fun interface RouteEmbedder {
    fun embed(text: String): DoubleArray
}

/**
 * Live-Embedder gegen Ollama (:11434, `embeddinggemma:300m`) — **NUR Embeddings,
 * kein Brain-Call**. Reiner JDK-`HttpClient` + jackson (keine Spring-/WebClient-
 * Abhängigkeit im Adapter, analog [de.hoshi.adapters.memory.OllamaEpisodicEmbedder]).
 * `keep_alive=-1` hält das Embed-Modell resident (0.5-Lehre: ein kaltes Embedding
 * liefert leer → Refine fiele aus). Jeder Fehler → leerer Vektor (best-effort, der
 * Refiner reicht dann den Fallback durch).
 */
class OllamaRouteEmbedder(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "embeddinggemma:300m",
    private val timeout: Duration = Duration.ofSeconds(5),
    private val mapper: ObjectMapper = ObjectMapper(),
) : RouteEmbedder {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override fun embed(text: String): DoubleArray {
        return try {
            val body = mapper.writeValueAsString(
                mapOf("model" to model, "prompt" to text, "keep_alive" to -1),
            )
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/embeddings"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[embed-router] Ollama-embed HTTP {} — leerer Vektor", resp.statusCode())
                return DoubleArray(0)
            }
            val node = mapper.readTree(resp.body()).path("embedding")
            if (!node.isArray) DoubleArray(0) else DoubleArray(node.size()) { node.get(it).asDouble() }
        } catch (e: Exception) {
            log.warn("[embed-router] Ollama-embed fehlgeschlagen: {}", e.message)
            DoubleArray(0)
        }
    }
}
