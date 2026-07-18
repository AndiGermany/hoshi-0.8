package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
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
 * **groundingMs-Messpunkt (Perf-Diary)** — deterministisch mit Fake-Clock:
 * die Dauer wird um GENAU den echten [GroundingPort]-Call gemessen
 * ([TurnPromptAssembler.AssembledPrompt.groundingMs]); Zweige OHNE Call
 * (Nicht-LOCAL, Grounding OFF) messen NIE (null — kein erfundenes 0).
 */
class TurnPromptAssemblerGroundingTimingTest {

    /** Fake-Nano-Uhr: liefert die Ticks der Reihe nach (t0, Mess-Punkt), danach den letzten. */
    private class FakeNano(vararg ticks: Long) : () -> Long {
        private val queue = ArrayDeque(ticks.toList())
        private var last = ticks.last()
        override fun invoke(): Long = queue.removeFirstOrNull()?.also { last = it } ?: last
        val remaining: Int get() = queue.size
    }

    private fun assembler(
        groundBlock: String,
        nano: FakeNano,
        wikiGroundingEnabled: Boolean = true,
    ) = TurnPromptAssembler(
        persona = PersonaService(),
        entityMemory = { null },
        grounding = { _, _ -> if (groundBlock.isEmpty()) Mono.empty() else Mono.just(groundBlock) },
        episodicMemory = null,
        wikiGroundingEnabled = wikiGroundingEnabled,
        nanoTime = nano,
    )

    private fun assemble(
        assembler: TurnPromptAssembler,
        provider: RouteProvider = RouteProvider.LOCAL,
    ): TurnPromptAssembler.AssembledPrompt {
        val ctx = TurnPrompt.from(ChatRequest(text = "Wie hoch ist der Eiffelturm?"))
        val decision = RouteDecision(RouteCategory.FACT_SHORT, provider, "fake")
        return assembler.assemble(ctx, decision, "SYSTEM", followBlock = "").block(Duration.ofSeconds(5))!!
    }

    @Test
    fun `echter grounding-call wird gemessen - fake-clock pinnt 42ms`() {
        val nano = FakeNano(0L, 42_000_000L)
        val assembled = assemble(assembler(groundBlock = "\n\nHINTERGRUND: 330 Meter.", nano = nano))
        assertEquals(42L, assembled.groundingMs, "t0=0, Antwort bei 42ms ⇒ exakt 42")
    }

    @Test
    fun `auch ein leeres ergebnis ist ein gemessener call - dauer zaehlt ehrlich`() {
        // Der Port LIEF (und fand nichts): defaultIfEmpty("") ⇒ Messung feuert trotzdem.
        val nano = FakeNano(0L, 7_000_000L)
        val assembled = assemble(assembler(groundBlock = "", nano = nano))
        assertEquals(7L, assembled.groundingMs)
        assertEquals("", assembled.groundBlock)
    }

    @Test
    fun `grounding OFF - kein call, keine messung, null statt 0`() {
        val nano = FakeNano(0L, 42_000_000L)
        val assembled = assemble(assembler(groundBlock = "egal", nano = nano, wikiGroundingEnabled = false))
        assertNull(assembled.groundingMs, "OFF-Zweig ruft nie ⇒ null (nie ein erfundenes 0)")
        assertEquals(2, nano.remaining, "die Fake-Uhr wurde NIE abgelesen — der Zweig ist mess-frei")
    }

    @Test
    fun `nicht-LOCAL provider - kein call, keine messung, null`() {
        val nano = FakeNano(0L, 42_000_000L)
        val assembled = assemble(assembler(groundBlock = "egal", nano = nano), provider = RouteProvider.OPENAI)
        assertNull(assembled.groundingMs)
        assertEquals(2, nano.remaining)
    }
}
