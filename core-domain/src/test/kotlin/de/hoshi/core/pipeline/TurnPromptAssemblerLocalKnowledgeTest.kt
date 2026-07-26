package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.TurnPrompt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Beweist, dass der enge Lookup denselben Assembly-Kern und Messpunkt nutzt,
 * dabei aber ausschließlich [GroundingPort.localKnowledgeBlock] aufruft.
 */
class TurnPromptAssemblerLocalKnowledgeTest {

    private class RecordingGrounding : GroundingPort {
        var normalCalls = 0
        val localCalls = mutableListOf<Triple<String, RouteCategory, Language>>()

        override fun groundingBlock(
            query: String,
            category: RouteCategory,
            language: Language,
        ): Mono<String> {
            normalCalls++
            return Mono.just("BREIT")
        }

        override fun localKnowledgeBlock(
            query: String,
            category: RouteCategory,
            language: Language,
        ): Mono<String> {
            localCalls += Triple(query, category, language)
            return Mono.just("LOCAL-WIKI")
        }
    }

    private class FakeNano(vararg ticks: Long) : () -> Long {
        private val values = ArrayDeque(ticks.toList())
        private var last = ticks.last()

        override fun invoke(): Long = values.removeFirstOrNull()?.also { last = it } ?: last

        val remaining: Int get() = values.size
    }

    private fun assembler(grounding: GroundingPort, nanoTime: () -> Long): TurnPromptAssembler =
        TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = { _, _ -> null },
            grounding = grounding,
            episodicMemory = EpisodicRecallPort { _, _, _ -> Mono.just("EPISODIC") },
            nanoTime = nanoTime,
        )

    private fun context(): TurnPrompt =
        TurnPrompt.from(
            ChatRequest(
                text = "Welche davon?",
                language = Language.EN,
            ),
        )

    @Test
    fun `assembleLocalKnowledge ruft engen Port genau einmal und misst denselben Assembly-Pfad`() {
        val grounding = RecordingGrounding()
        val nano = FakeNano(10_000_000L, 52_000_000L)
        val decision = RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "test")

        val out = assembler(grounding, nano)
            .assembleLocalKnowledge(
                ctx = context(),
                decision = decision,
                systemPrompt = "BASE",
                followBlock = "FOLLOW",
                groundingQuery = "Solar system",
            )
            .block(Duration.ofSeconds(2))!!

        assertEquals(0, grounding.normalCalls, "der breite Grounding-Pfad bleibt unangetastet")
        assertEquals(
            listOf(Triple("Solar system", RouteCategory.FACT_SHORT, Language.EN)),
            grounding.localCalls,
        )
        assertEquals("BASEFOLLOW\n\nEPISODICLOCAL-WIKI", out.finalPrompt)
        assertEquals("LOCAL-WIKI", out.groundBlock)
        assertEquals(42L, out.groundingMs)
        assertEquals(0, nano.remaining, "genau Start- und End-Messpunkt wurden gelesen")
    }

    @Test
    fun `assembleLocalKnowledge bleibt bei Nicht-LOCAL call- und messfrei`() {
        val grounding = RecordingGrounding()
        val nano = FakeNano(0L, 42_000_000L)
        val decision = RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.OPENAI, "test")

        val out = assembler(grounding, nano)
            .assembleLocalKnowledge(context(), decision, "BASE", "")
            .block(Duration.ofSeconds(2))!!

        assertEquals(0, grounding.normalCalls)
        assertEquals(0, grounding.localCalls.size)
        assertNull(out.groundingMs)
        assertEquals(2, nano.remaining)
    }
}
