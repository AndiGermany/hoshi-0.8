package de.hoshi.core.pipeline

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import reactor.core.publisher.Mono

/**
 * Keyword-Router-Naht (Hop 1, ~0ms). Liefert die deterministische
 * Erst-Entscheidung; die Implementierung (Keyword-Heuristik) lebt im
 * Orchestrator/Adapter.
 */
fun interface KeywordRouter {
    fun decide(text: String): RouteDecision
}

/**
 * Refiner-Naht (Hop 2). Verfeinert eine AMBIG-Erst-Entscheidung; bekommt die
 * Hop-1-Decision als [fallback] (garantiert null-frei — der Refiner gibt bei
 * Fehler/Timeout selbst den Fallback zurück). Zwei Impls im Orchestrator:
 * Embedding (~82ms) oder LLM (~3s) — beide rufen Infra (Ollama/Embeddings) und
 * bleiben daher draußen.
 */
fun interface RouteRefiner {
    fun refine(text: String, fallback: RouteDecision): Mono<RouteDecision>
}

/**
 * Routing-Policy (PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger, dort
 * `RouteResolver`): die reine zweistufige Auflösung `String -> Mono<RouteDecision>`
 * (Soft-Routing-Cascade, B-047).
 *
 * Entkoppelt von Spring + Infra: statt der konkreten `RouterService`/
 * `LlmRouterService`/`EmbeddingRouterService` nimmt der Konstruktor schmale Ports
 * ([KeywordRouter]/[RouteRefiner]) entgegen — die Ollama-/Embedding-rufenden Impls
 * gehören ins M2c-Wiring/Adapter. Reines Kotlin, kein `@Service`.
 *
 * **Behavior-preserving:** Hop-1 `keywordRouter.decide`, dann bei AMBIG + aktivem
 * Soft-Routing die `softRoutingMode`-Verzweigung (llm vs. embedding) — 1:1 aus 0.5.
 */
class RoutingPolicy(
    private val keywordRouter: KeywordRouter,
    private val llmRefiner: RouteRefiner,
    private val embeddingRefiner: RouteRefiner,
    private val softRoutingEnabled: Boolean,
    private val softRoutingMode: String,
) {

    /**
     * Hop 1: Keyword-Router (0ms). Wenn die Keyword-Entscheidung AMBIG ist UND
     * Soft-Routing aktiv, verfeinert ein Klassifikator die Route. Sonst direkt
     * mit der Hop-1-Decision weiter — kein Latenz-Aufschlag.
     */
    fun resolve(text: String): Mono<RouteDecision> {
        val hop1 = keywordRouter.decide(text)
        return if (hop1.category == RouteCategory.AMBIG && softRoutingEnabled) {
            when (softRoutingMode.lowercase()) {
                "llm" -> llmRefiner.refine(text, hop1)
                else -> embeddingRefiner.refine(text, hop1)
            }
        } else {
            Mono.just(hop1)
        }
    }
}
