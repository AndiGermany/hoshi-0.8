package de.hoshi.adapters.routing

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests des [EmbeddingRouterRefiner] (portiert aus 0.5 `EmbeddingRouterServiceTest`).
 *
 * Wir mocken die Embeddings deterministisch über die injizierte [RouteEmbedder]:
 * jede Anker-Phrase + die Query bekommen einen 3D-Vektor anhand von Schlüsselwörtern.
 * So testen wir das cosine-Matching OHNE echtes Ollama:
 *   Achse 0 = SMART_HOME, Achse 1 = FACT, Achse 2 = NEEDS_WEB.
 */
class EmbeddingRouterRefinerTest {

    /** Die AMBIG-Hop-1-Decision, die der RoutingPolicy dem Refiner als Fallback gibt. */
    private val fallback = RouteDecision(RouteCategory.AMBIG, RouteProvider.LOCAL, "short_query")

    /** Deterministischer Embed-Fake: ordnet Phrase/Query einem 3D-Vektor zu. */
    private fun fakeEmbedder(): RouteEmbedder = RouteEmbedder { text ->
        val t = text.lowercase()
        when {
            listOf("licht", "heizung", "rollo", "lampe", "szene").any { it in t } ->
                doubleArrayOf(1.0, 0.0, 0.0)
            listOf("wetter", "wikipedia", "internet", "bitcoin", "kino").any { it in t } ->
                doubleArrayOf(0.0, 0.0, 1.0)
            listOf("spät", "einstein", "hauptstadt", "spinne", "weihnachten").any { it in t } ->
                doubleArrayOf(0.0, 1.0, 0.0)
            listOf("hallo", "morgen", "witz", "müde", "danke").any { it in t } ->
                doubleArrayOf(0.0, 1.0, 0.1) // nah an FACT, leicht verschoben
            listOf("plane", "email", "vergleiche", "recherchiere").any { it in t } ->
                doubleArrayOf(0.5, 0.0, 0.5)
            else -> doubleArrayOf(0.33, 0.33, 0.33)
        }
    }

    @Test
    fun `AMBIG Smart-Home-Query wird zu SMART_HOME verfeinert`() {
        val refiner = EmbeddingRouterRefiner(fakeEmbedder())
        val d = refiner.refine("kannst du das licht dimmen", fallback).block()!!
        assertEquals(RouteCategory.SMART_HOME, d.category)
        assertTrue(d.reason.contains("embed_routing"), "reason war: ${d.reason}")
    }

    @Test
    fun `AMBIG Web-Query wird zu NEEDS_WEB verfeinert`() {
        val refiner = EmbeddingRouterRefiner(fakeEmbedder())
        val d = refiner.refine("wie ist das wetter", fallback).block()!!
        assertEquals(RouteCategory.NEEDS_WEB, d.category)
    }

    @Test
    fun `AMBIG Fakt-Query wird zu FACT_SHORT verfeinert`() {
        val refiner = EmbeddingRouterRefiner(fakeEmbedder())
        val d = refiner.refine("wer war einstein", fallback).block()!!
        assertEquals(RouteCategory.FACT_SHORT, d.category)
    }

    @Test
    fun `Ollama-Fehler (Embedder wirft) laesst die Route unveraendert`() {
        val throwing = RouteEmbedder { throw RuntimeException("ollama down") }
        val refiner = EmbeddingRouterRefiner(throwing)
        val d = refiner.refine("kannst du das licht dimmen", fallback).block()
        assertSame(fallback, d, "best-effort: Fallback unverändert durchreichen")
    }

    @Test
    fun `Anker-Embedding leer (Ollama kalt) haelt die Route unveraendert`() {
        val empty = RouteEmbedder { DoubleArray(0) }
        val refiner = EmbeddingRouterRefiner(empty)
        val d = refiner.refine("kannst du das licht dimmen", fallback).block()
        assertSame(fallback, d)
    }

    @Test
    fun `leeres Query-Embedding faellt auf den Fallback`() {
        // Anker bekommen Vektoren, nur die Query liefert leer.
        val embedder = RouteEmbedder { text ->
            if (text == "kaputte query") DoubleArray(0) else doubleArrayOf(1.0, 0.0, 0.0)
        }
        val refiner = EmbeddingRouterRefiner(embedder)
        val d = refiner.refine("kaputte query", fallback).block()
        assertSame(fallback, d)
    }

    @Test
    fun `Score unter confidenceMin reicht den Fallback durch`() {
        // confidenceMin künstlich hoch: selbst ein 1.0-Treffer würde zwar bestehen,
        // darum drücken wir den besten Score über die Schwelle — query orthogonal
        // zu allen Ankern (Achse mit 0-Overlap) erzeugt niedrigen Top-Score.
        val embedder = RouteEmbedder { text ->
            val t = text.lowercase()
            // Anker liegen auf den 3 Hauptachsen; die Query liegt schräg (0.33,0.33,0.33)
            // → max cosine ≈ 0.577 < 0.9 → Fallback.
            if (listOf("licht", "heizung", "rollo", "lampe", "szene",
                    "wetter", "wikipedia", "internet", "bitcoin", "kino",
                    "spät", "einstein", "hauptstadt", "spinne", "weihnachten",
                    "hallo", "morgen", "witz", "müde", "danke",
                    "plane", "email", "vergleiche", "recherchiere").any { it in t }
            ) {
                doubleArrayOf(1.0, 0.0, 0.0)
            } else {
                doubleArrayOf(1.0, 1.0, 1.0)
            }
        }
        val refiner = EmbeddingRouterRefiner(embedder, confidenceMin = 0.9)
        val d = refiner.refine("voellig unklare anfrage", fallback).block()
        assertSame(fallback, d)
    }
}
