package de.hoshi.adapters.news

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ExponentialBackoffPolicyTest {
    @Test
    fun `delay doubles and stops at cap`() {
        val policy = ExponentialBackoffPolicy(
            baseDelay = Duration.ofSeconds(2),
            maxDelay = Duration.ofSeconds(10),
            jitterMillis = { 0 },
        )
        assertEquals(Duration.ofSeconds(2), policy.delay(1))
        assertEquals(Duration.ofSeconds(4), policy.delay(2))
        assertEquals(Duration.ofSeconds(8), policy.delay(3))
        assertEquals(Duration.ofSeconds(10), policy.delay(4))
        assertEquals(Duration.ofSeconds(10), policy.delay(20))
    }

    @Test
    fun `jitter is bounded to one quarter and never exceeds cap`() {
        val policy = ExponentialBackoffPolicy(
            baseDelay = Duration.ofSeconds(4),
            maxDelay = Duration.ofSeconds(5),
            jitterMillis = { Long.MAX_VALUE },
        )
        assertEquals(Duration.ofMillis(4_999), policy.delay(1))
        assertEquals(Duration.ofSeconds(5), policy.delay(2))
    }
}
