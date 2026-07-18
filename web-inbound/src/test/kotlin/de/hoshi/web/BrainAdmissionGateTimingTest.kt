package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * **admissionWaitMs-Messpunkt (Perf-Diary)** — deterministisch mit Fake-Clock:
 * Gate-Eintritt (Subscribe des defer) → Permit; die gemessene Wartezeit reist
 * additiv am terminalen [ChatEvent.Done] mit. Ehrlichkeit: Gate OFF ⇒ purer
 * Passthrough (null, kein Map-Operator); Rejection (kein Permit) ⇒ ebenfalls
 * null — nie ein erfundenes 0 (das gemessene ~0 des non-blocking tryAcquire
 * ist dagegen ein ECHTER Messwert).
 */
class BrainAdmissionGateTimingTest {

    private class FakeNano(vararg ticks: Long) : () -> Long {
        private val queue = ArrayDeque(ticks.toList())
        private var last = ticks.last()
        override fun invoke(): Long = queue.removeFirstOrNull()?.also { last = it } ?: last
    }

    private fun turn(done: ChatEvent.Done = ChatEvent.Done(provider = "LOCAL")): Flux<ChatEvent> = Flux.just(
        ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "brain"),
        ChatEvent.TextDelta("hi", provider = "LOCAL"),
        done,
    )

    @Test
    fun `permit sofort - gemessene wartezeit reist gepinnt am Done`() {
        // Ablesungen: (1) Gate-Eintritt=0 · (2) nach Permit=5ms ⇒ admissionWaitMs=5.
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 2, nanoTime = FakeNano(0L, 5_000_000L))
        val done = gate.gate { turn() }
            .collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Done>().single()

        assertEquals(5L, done.stageTimings?.admissionWaitMs)
    }

    @Test
    fun `merge erhaelt vorhandene stageTimings anderer schichten`() {
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 2, nanoTime = FakeNano(0L, 5_000_000L))
        val upstream = ChatEvent.Done(
            provider = "LOCAL",
            stageTimings = ChatEvent.StageTimings(groundingMs = 42, brainTtftMs = 250),
        )
        val done = gate.gate { turn(upstream) }
            .collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Done>().single()

        assertEquals(
            ChatEvent.StageTimings(groundingMs = 42, brainTtftMs = 250, admissionWaitMs = 5),
            done.stageTimings,
        )
    }

    @Test
    fun `gate OFF - purer passthrough, Done identisch, null statt 0`() {
        val gate = BrainAdmissionGate(enabled = false, maxConcurrent = 1, nanoTime = FakeNano(0L))
        val original = ChatEvent.Done(provider = "LOCAL")
        val done = gate.gate { turn(original) }
            .collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Done>().single()

        assertSame(original, done, "OFF ⇒ source() direkt — kein Operator, keine Kopie")
        assertNull(done.stageTimings)
    }

    @Test
    fun `rejection (kein permit) - absage-Done ehrlich ohne timings`() {
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 1, nanoTime = FakeNano(0L))
        // Permit belegen: ein nie endender Turn hält das eine Budget.
        val holder = gate.gate { Flux.never() }.subscribe()
        try {
            val events = gate.gate { turn() }.collectList().block(Duration.ofSeconds(5))!!
            val start = events.filterIsInstance<ChatEvent.Start>().single()
            assertEquals(BrainAdmissionGate.CATEGORY, start.category, "die warme Absage lief")
            val done = events.filterIsInstance<ChatEvent.Done>().single()
            assertNull(done.stageTimings, "kein Permit ⇒ keine Wartezeit-Messung — nie ein erfundenes 0")
        } finally {
            holder.dispose()
        }
    }
}
