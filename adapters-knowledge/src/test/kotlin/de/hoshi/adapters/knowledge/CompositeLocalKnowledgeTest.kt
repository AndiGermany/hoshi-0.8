package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.GroundingPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Der lokale Composite-Pfad ist absichtlich keine verkürzte normale Kaskade:
 * ausschließlich die lokale Wiki-Scheibe darf ihn bedienen.
 */
class CompositeLocalKnowledgeTest {

    private class RecordingPort(
        private val normalAnswer: String,
        private val localAnswer: String,
    ) : GroundingPort {
        var normalCalls = 0
        var localCalls = 0

        override fun groundingBlock(
            query: String,
            category: RouteCategory,
            language: Language,
        ): Mono<String> {
            normalCalls++
            return Mono.just(normalAnswer)
        }

        override fun localKnowledgeBlock(
            query: String,
            category: RouteCategory,
            language: Language,
        ): Mono<String> {
            localCalls++
            return Mono.just(localAnswer)
        }
    }

    @Test
    fun `lokaler Lookup fragt exakt Wiki - Wetter und Cloud-Cache niemals`() {
        val weather = RecordingPort("WETTER", "WETTER-LOCAL")
        val nachgeschlagen = RecordingPort("CACHE", "CACHE-LOCAL")
        val wiki = RecordingPort("WIKI-NORMAL", "WIKI-LOCAL")
        val composite = CompositeGroundingPort(weather, wiki, nachgeschlagen)

        val block = composite
            .localKnowledgeBlock(
                "Wie viele Planeten gibt es im Sonnensystem?",
                RouteCategory.FACT_SHORT,
                Language.DE,
            )
            .block(Duration.ofSeconds(2))

        assertEquals("WIKI-LOCAL", block)
        assertEquals(0, weather.normalCalls)
        assertEquals(0, weather.localCalls, "Wetter ist nie Teil des lokalen Wissens-Lookups")
        assertEquals(0, nachgeschlagen.normalCalls)
        assertEquals(0, nachgeschlagen.localCalls, "cloudstämmiger Cache bleibt ausgeschlossen")
        assertEquals(0, wiki.normalCalls)
        assertEquals(1, wiki.localCalls, "nur der explizit lokale Wiki-Unterport läuft")
    }

    @Test
    fun `Wiki ohne lokales Opt-in bleibt im Composite leer`() {
        val broadOnlyWiki = GroundingPort.fixed("WIKI-NORMAL")
        val composite = CompositeGroundingPort(
            weather = GroundingPort.fixed("WETTER"),
            wiki = broadOnlyWiki,
            nachgeschlagen = GroundingPort.fixed("CACHE"),
        )

        val block = composite
            .localKnowledgeBlock("Frage", RouteCategory.FACT_SHORT, Language.DE)
            .block(Duration.ofSeconds(2))

        assertEquals("", block)
    }
}
