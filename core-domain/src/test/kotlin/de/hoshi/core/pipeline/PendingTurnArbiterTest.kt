package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingTurnArbiterTest {
    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    }

    private data class Fixture(
        val lookup: InMemoryPendingLookupStore,
        val location: InMemoryPendingLocationQuestionStore,
        val area: InMemoryPendingAreaClarifyStore,
        val arbiter: PendingTurnArbiter,
    )

    private fun fixture(clock: Clock = Clock.systemUTC()): Fixture {
        val lookup = InMemoryPendingLookupStore(clock)
        val location = InMemoryPendingLocationQuestionStore(clock)
        val area = InMemoryPendingAreaClarifyStore(clock)
        return Fixture(lookup, location, area, PendingTurnArbiter(lookup, location, area))
    }

    @Test
    fun `neue Art ersetzt die alte atomar und bilanziert den Verbrauch`() {
        val f = fixture()
        f.arbiter.offerLookup("k", PendingLookup("frage", Language.DE))
        val offer = f.arbiter.offerLocation("k", PendingLocationQuestion("wetter", Language.DE))

        assertEquals(
            listOf(PendingTurnArbiter.Transition(PendingTurnArbiter.Kind.LOOKUP, PendingTurnArbiter.Outcome.REPLACED)),
            offer.transitions,
        )
        val consumed = f.arbiter.consume("k")
        assertTrue(consumed.selected is PendingTurnArbiter.State.Location)
        assertNull(f.lookup.consume("k"))
        assertNull(f.location.consume("k"))
        assertNull(f.area.consume("k"))
    }

    @Test
    fun `Legacy-Konflikt hat feste Prioritaet und keinen still konsumierten Verlierer`() {
        val f = fixture()
        // Bewusst an der neuen Offer-Naht vorbei: simuliert Altbestand beim Upgrade.
        f.lookup.offer("k", PendingLookup("frage", Language.DE, awaitsTopic = false))
        f.location.offer("k", PendingLocationQuestion("wetter", Language.DE))
        f.area.offer("k", PendingAreaClarify("light", "turn_on"))

        val consumed = f.arbiter.consume("k")

        assertTrue(consumed.selected is PendingTurnArbiter.State.Lookup)
        assertEquals(1, consumed.transitions.count { it.outcome == PendingTurnArbiter.Outcome.CONSUMED })
        assertEquals(2, consumed.transitions.count { it.outcome == PendingTurnArbiter.Outcome.ABANDONED })
        assertTrue(consumed.transitions.any {
            it.kind == PendingTurnArbiter.Kind.AREA && it.outcome == PendingTurnArbiter.Outcome.ABANDONED
        })
    }

    @Test
    fun `Lookup-Themenfrage verliert im Legacy-Fall gegen Ort und Raum`() {
        val f = fixture()
        f.lookup.offer("k", PendingLookup("schau nach", Language.DE, awaitsTopic = true))
        f.location.offer("k", PendingLocationQuestion("wetter", Language.DE))
        f.area.offer("k", PendingAreaClarify("light", "turn_on"))

        assertTrue(f.arbiter.consume("k").selected is PendingTurnArbiter.State.Location)
    }

    @Test
    fun `abgelaufener Area-Zustand ist explizit expired und nie ein Kandidat`() {
        val clock = MutableClock(Instant.parse("2026-08-18T12:00:00Z"))
        val f = fixture(clock)
        f.arbiter.offerArea("k", PendingAreaClarify("light", "turn_on"))
        clock.advanceSeconds(121)

        val consumed = f.arbiter.consume("k")

        assertNull(consumed.selected)
        assertEquals(
            listOf(PendingTurnArbiter.Transition(PendingTurnArbiter.Kind.AREA, PendingTurnArbiter.Outcome.EXPIRED)),
            consumed.transitions,
        )
    }

    @Test
    fun `verschiedene Conversation-Keys bleiben unabhaengig`() {
        val f = fixture()
        f.arbiter.offerLookup("chat:a", PendingLookup("a", Language.DE))
        f.arbiter.offerArea("voice:a", PendingAreaClarify("light", "turn_on"))

        assertTrue(f.arbiter.consume("chat:a").selected is PendingTurnArbiter.State.Lookup)
        assertTrue(f.arbiter.consume("voice:a").selected is PendingTurnArbiter.State.Area)
    }

    @Test
    fun `parallele Offers hinterlassen exakt einen einloesbaren Zustand`() {
        val f = fixture()
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val done = CountDownLatch(60)
        repeat(60) { index ->
            pool.submit {
                start.await()
                when (index % 3) {
                    0 -> f.arbiter.offerLookup("k", PendingLookup("q-$index", Language.DE))
                    1 -> f.arbiter.offerLocation("k", PendingLocationQuestion("q-$index", Language.DE))
                    else -> f.arbiter.offerArea("k", PendingAreaClarify("light", "service-$index"))
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        val first = f.arbiter.consume("k")
        val second = f.arbiter.consume("k")
        assertTrue(first.selected != null)
        assertNull(second.selected)
        assertEquals(1, first.transitions.count { it.outcome == PendingTurnArbiter.Outcome.CONSUMED })
    }
}
