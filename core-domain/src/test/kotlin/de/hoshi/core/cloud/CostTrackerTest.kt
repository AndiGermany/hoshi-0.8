package de.hoshi.core.cloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Eine vorstellbare Test-Clock — wir koennen ihren Moment frei vorruecken. */
private class MutableClock(
    var now: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(z: ZoneId): Clock = MutableClock(now, z)
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

class CostTrackerTest {

    @Test
    fun `Default ohne Limit ist immer erlaubt (byte-neutral)`() {
        val clock = MutableClock(Instant.parse("2026-06-28T08:00:00Z"))
        val tracker = CostTracker(BudgetCap.UNLIMITED, clock)

        repeat(100) { tracker.record(costEur = 9.99) }

        assertInstanceOf(BudgetDecision.Allowed::class.java, tracker.check())
    }

    @Test
    fun `Daily-Call-Cap greift und Tageswechsel resettet`() {
        val clock = MutableClock(Instant.parse("2026-06-28T08:00:00Z"))
        val tracker = CostTracker(BudgetCap(dailyMaxCalls = 2), clock)

        assertInstanceOf(BudgetDecision.Allowed::class.java, tracker.check())
        tracker.record(0.0)
        tracker.record(0.0)

        val refused = tracker.check()
        assertInstanceOf(BudgetDecision.Refused::class.java, refused)
        assertEquals(RefuseReason.DAILY_CALL_CAP, (refused as BudgetDecision.Refused).reason)

        clock.advance(Duration.ofDays(1))
        assertInstanceOf(BudgetDecision.Allowed::class.java, tracker.check())
        assertEquals(0, tracker.snapshot().callsToday)
    }

    @Test
    fun `Daily-Cost-Cap greift und Tageswechsel resettet`() {
        val clock = MutableClock(Instant.parse("2026-06-28T08:00:00Z"))
        val tracker = CostTracker(BudgetCap(dailyMaxCostEur = 1.0), clock)

        tracker.record(0.6)
        tracker.record(0.6) // 1.2 EUR heute >= 1.0

        val refused = tracker.check()
        assertEquals(RefuseReason.DAILY_COST_CAP, (refused as BudgetDecision.Refused).reason)

        clock.advance(Duration.ofDays(1))
        assertInstanceOf(BudgetDecision.Allowed::class.java, tracker.check())
        assertEquals(0.0, tracker.snapshot().costTodayEur, 1e-9)
    }

    @Test
    fun `Monats-EUR-Cap greift, ueberlebt Tageswechsel, resettet bei Monatswechsel`() {
        val clock = MutableClock(Instant.parse("2026-06-28T08:00:00Z"))
        val tracker = CostTracker(BudgetCap(monthlyMaxEur = 1.0), clock)

        tracker.record(0.6)              // Juni, Tag 28
        clock.advance(Duration.ofDays(1)) // Juni, Tag 29 — Tag wechselt, Monat bleibt
        assertInstanceOf(BudgetDecision.Allowed::class.java, tracker.check())
        tracker.record(0.6)              // Monat jetzt 1.2 EUR >= 1.0

        val refused = tracker.check()
        assertEquals(RefuseReason.MONTHLY_BUDGET, (refused as BudgetDecision.Refused).reason)

        // bis in den Juli vorruecken ⇒ Monats-Zaehler resettet
        clock.advance(Duration.ofDays(5))
        assertInstanceOf(BudgetDecision.Allowed::class.java, tracker.check())
        assertEquals(0.0, tracker.snapshot().costMonthEur, 1e-9)
    }

    @Test
    fun `fromEnv liest HOSHI_CLOUD_MONTHLY_BUDGET_EUR, fehlend ist kein Limit`() {
        val withBudget = BudgetCap.fromEnv { key ->
            if (key == BudgetCap.MONTHLY_BUDGET_ENV) "5.0" else null
        }
        assertEquals(5.0, withBudget.monthlyMaxEur, 1e-9)

        val missing = BudgetCap.fromEnv { null }
        assertEquals(0.0, missing.monthlyMaxEur, 1e-9)
        assertEquals(BudgetCap.UNLIMITED, missing)
    }

    @Test
    fun `Refused traegt Zahlen aber KEINE Phrase`() {
        val clock = MutableClock(Instant.parse("2026-06-28T08:00:00Z"))
        val tracker = CostTracker(BudgetCap(dailyMaxCalls = 1), clock)
        tracker.record(0.0)

        val refused = tracker.check() as BudgetDecision.Refused
        assertEquals(1.0, refused.used, 1e-9)
        assertEquals(1.0, refused.limit, 1e-9)
        // klartextfrei: das Refused-DTO hat ueberhaupt kein String-Feld
        assertTrue(BudgetDecision.Refused::class.java.declaredFields.none { it.type == String::class.java })
    }
}
