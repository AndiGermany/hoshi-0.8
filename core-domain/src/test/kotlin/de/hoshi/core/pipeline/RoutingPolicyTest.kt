package de.hoshi.core.pipeline

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/**
 * Portiert aus Hoshi 0.5 (de.hoshi.app.streaming.RouteResolverTest).
 * Entkopplung: statt mockk-Services schmale [KeywordRouter]/[RouteRefiner]-Fakes,
 * die ihre Aufrufe zählen. Die vier Verzweigungen der Soft-Routing-Cascade:
 *  - non-AMBIG            → Hop-1 direkt, KEIN Refiner-Call
 *  - AMBIG + Soft OFF     → Hop-1 (AMBIG) durchgereicht, KEIN Refiner-Call
 *  - AMBIG + mode=embedding → embeddingRefiner, NICHT llmRefiner
 *  - AMBIG + mode=llm     → llmRefiner, NICHT embeddingRefiner
 *  - mode-Switch case-insensitiv
 */
class RoutingPolicyTest {

    private val keyword = RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "keyword")
    private val ambig = RouteDecision(RouteCategory.AMBIG, RouteProvider.LOCAL, "ambig")
    private val refined = RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "refined")

    /** Zählender Refiner-Fake: protokolliert die Aufrufe (text, fallback). */
    private class CountingRefiner(private val result: RouteDecision) : RouteRefiner {
        val calls = mutableListOf<Pair<String, RouteDecision>>()
        override fun refine(text: String, fallback: RouteDecision): Mono<RouteDecision> {
            calls += text to fallback
            return Mono.just(result)
        }
    }

    private fun policy(
        hop1: RouteDecision,
        softRoutingEnabled: Boolean,
        softRoutingMode: String,
        llm: RouteRefiner,
        embedding: RouteRefiner,
    ): RoutingPolicy =
        RoutingPolicy(KeywordRouter { hop1 }, llm, embedding, softRoutingEnabled, softRoutingMode)

    @Test
    fun `non-AMBIG Keyword-Decision wird direkt durchgereicht ohne Refiner-Call`() {
        val llm = CountingRefiner(refined)
        val emb = CountingRefiner(refined)
        val p = policy(keyword, softRoutingEnabled = true, softRoutingMode = "embedding", llm = llm, embedding = emb)

        StepVerifier.create(p.resolve("mach das Licht an"))
            .expectNext(keyword)
            .verifyComplete()

        assertEquals(0, llm.calls.size, "kein LLM-Refiner bei non-AMBIG")
        assertEquals(0, emb.calls.size, "kein Embedding-Refiner bei non-AMBIG")
    }

    @Test
    fun `AMBIG bei deaktiviertem Soft-Routing bleibt AMBIG ohne Refiner-Call`() {
        val llm = CountingRefiner(refined)
        val emb = CountingRefiner(refined)
        val p = policy(ambig, softRoutingEnabled = false, softRoutingMode = "embedding", llm = llm, embedding = emb)

        StepVerifier.create(p.resolve("irgendwas unklares"))
            .expectNext(ambig)
            .verifyComplete()

        assertEquals(0, llm.calls.size)
        assertEquals(0, emb.calls.size)
    }

    @Test
    fun `AMBIG mit mode embedding ruft embeddingRefiner nicht llmRefiner`() {
        val llm = CountingRefiner(refined)
        val emb = CountingRefiner(refined)
        val p = policy(ambig, softRoutingEnabled = true, softRoutingMode = "embedding", llm = llm, embedding = emb)

        StepVerifier.create(p.resolve("zweideutige frage"))
            .expectNext(refined)
            .verifyComplete()

        assertEquals(listOf("zweideutige frage" to ambig), emb.calls, "Refiner bekommt AMBIG-Hop-1 als Fallback")
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `AMBIG mit mode llm ruft llmRefiner nicht embeddingRefiner`() {
        val llm = CountingRefiner(refined)
        val emb = CountingRefiner(refined)
        val p = policy(ambig, softRoutingEnabled = true, softRoutingMode = "llm", llm = llm, embedding = emb)

        StepVerifier.create(p.resolve("zweideutige frage"))
            .expectNext(refined)
            .verifyComplete()

        assertEquals(listOf("zweideutige frage" to ambig), llm.calls)
        assertEquals(0, emb.calls.size)
    }

    @Test
    fun `softRoutingMode wird case-insensitiv ausgewertet`() {
        val llm = CountingRefiner(refined)
        val emb = CountingRefiner(refined)
        val p = policy(ambig, softRoutingEnabled = true, softRoutingMode = "LLM", llm = llm, embedding = emb)

        val out = p.resolve("x").block()
        assertEquals(refined, out)
        assertEquals(1, llm.calls.size)
        assertEquals(0, emb.calls.size)
    }
}
