package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * Beweist die Sicherheits-Voreinstellung der engen lokalen Wissens-Naht:
 * bestehende Grounding-Ports optieren nicht implizit ein.
 */
class GroundingPortLocalKnowledgeTest {

    @Test
    fun `localKnowledgeBlock ist default-deny und delegiert nie an normales Grounding`() {
        var normalCalls = 0
        val port = object : GroundingPort {
            override fun groundingBlock(
                query: String,
                category: RouteCategory,
                language: Language,
            ): Mono<String> {
                normalCalls++
                return Mono.just("NICHT-LOKALE-QUELLE")
            }
        }

        val block = port
            .localKnowledgeBlock("Was ist Hoshi?", RouteCategory.FACT_SHORT, Language.DE)
            .block()

        assertEquals("", block)
        assertEquals(0, normalCalls, "default-deny darf den breiten Port nie indirekt aufrufen")
    }

    @Test
    fun `auch fixed optiert nicht versehentlich in lokalen Lookup ein`() {
        val block = GroundingPort.fixed("TEST-BLOCK")
            .localKnowledgeBlock("Frage", RouteCategory.FACT_SHORT, Language.DE)
            .block()

        assertEquals("", block)
    }
}
