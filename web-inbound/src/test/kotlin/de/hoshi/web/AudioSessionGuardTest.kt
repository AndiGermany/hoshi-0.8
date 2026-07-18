package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **AudioSessionGuardTest** — der pure Zustands-Tracker der Zeit-Achse, deterministisch
 * über eine [MutableClock] getrieben (kein sleep, keine Echtzeit). Geprüft: Silence-
 * Timeout feuert (einmal, Drain), Frames frischen das Silence-Fenster auf, der
 * Dauer-Deckel gewinnt trotz frischer Frames, disarm entschärft, OFF = alles no-op.
 */
class AudioSessionGuardTest {

    private class MutableClock(private var now: Instant = Instant.parse("2026-07-01T12:00:00Z")) : Clock() {
        fun advance(d: Duration) { now = now.plus(d) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun guard(clock: Clock, enabled: Boolean = true) = AudioSessionGuard(
        enabled = enabled,
        maxRecordingDuration = Duration.ofSeconds(30),
        silenceTimeout = Duration.ofSeconds(5),
        clock = clock,
    )

    @Test
    fun `silence-timeout feuert nach N Sekunden ohne Frames — genau einmal (Drain)`() {
        val clock = MutableClock()
        val g = guard(clock)
        g.armRecording("s1")
        g.onFrame("s1")

        clock.advance(Duration.ofSeconds(4))
        assertNull(g.expire("s1"), "unter dem Silence-Timeout ⇒ kein Ablauf")

        clock.advance(Duration.ofSeconds(2)) // 6s ohne Frame > 5s
        assertEquals(AudioSessionGuard.Expiry.SILENCE, g.expire("s1"))
        assertNull(g.expire("s1"), "Drain: derselbe Ablauf feuert kein zweites Mal")
    }

    @Test
    fun `frames frischen das Silence-Fenster auf`() {
        val clock = MutableClock()
        val g = guard(clock)
        g.armRecording("s1")

        repeat(3) {
            clock.advance(Duration.ofSeconds(4)) // je < 5s Abstand
            g.onFrame("s1")
        }
        assertNull(g.expire("s1"), "regelmäßige Frames ⇒ kein Silence-Ablauf")
    }

    @Test
    fun `dauer-deckel gewinnt trotz frischer Frames`() {
        val clock = MutableClock()
        val g = guard(clock)
        g.armRecording("s1")

        repeat(16) { // 16 × 2s = 32s > 30s Gesamt, aber nie 5s Stille
            clock.advance(Duration.ofSeconds(2))
            g.onFrame("s1")
        }
        assertEquals(AudioSessionGuard.Expiry.TOO_LONG, g.expire("s1"))
        assertNull(g.expire("s1"), "Drain: feuert genau einmal")
    }

    @Test
    fun `disarm entschaerft — kein Ablauf mehr`() {
        val clock = MutableClock()
        val g = guard(clock)
        g.armRecording("s1")
        g.disarm("s1") // stop/abort/Session-Ende
        clock.advance(Duration.ofMinutes(10))
        assertNull(g.expire("s1"), "entschärfte Aufnahme läuft nie ab")
    }

    @Test
    fun `nicht gearmte Session laeuft nie ab`() {
        val clock = MutableClock()
        val g = guard(clock)
        g.onFrame("nie-gestartet") // Frame ohne start ⇒ kein Tracking
        clock.advance(Duration.ofMinutes(10))
        assertNull(g.expire("nie-gestartet"))
    }

    @Test
    fun `OFF ist komplett no-op (default)`() {
        val clock = MutableClock()
        val g = guard(clock, enabled = false)
        g.armRecording("s1")
        clock.advance(Duration.ofMinutes(10))
        assertNull(g.expire("s1"), "Guard OFF ⇒ nie ein Ablauf")
    }
}
