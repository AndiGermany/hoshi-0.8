package de.hoshi.adapters.escalation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Vertrag des [FileBackedEscalationSpendStore]: Restart-fest (Datei-Beweis),
 * Tageswechsel-Reset, tolerant gegen fehlende/kaputte Dateien, atomare Writes.
 */
class FileBackedEscalationSpendStoreTest {

    /** Verstellbare Uhr für den Tageswechsel-Beweis. */
    private class MutableClock(private var now: Instant, private val zone: ZoneId = ZoneId.systemDefault()) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
        fun advance(d: Duration) { now = now.plus(d) }
    }

    private fun tempPath(): Path = Files.createTempDirectory("spend-store-test").resolve("spend.json")

    @Test
    fun `book summiert und spentTodayCents liest den Stand`() {
        val store = FileBackedEscalationSpendStore(tempPath())
        assertEquals(0.0, store.spentTodayCents(), 1e-9)
        store.book(0.25)
        store.book(0.75)
        assertEquals(1.0, store.spentTodayCents(), 1e-9)
    }

    @Test
    fun `Restart-Beweis — ein neuer Store liest die Datei des alten (persist-then-commit)`() {
        val path = tempPath()
        FileBackedEscalationSpendStore(path).book(12.5)
        assertTrue(Files.exists(path), "Buchung muss die Datei geschrieben haben")
        val restarted = FileBackedEscalationSpendStore(path)
        assertEquals(12.5, restarted.spentTodayCents(), 1e-9, "der Zähler muss den Restart überleben")
    }

    @Test
    fun `Tageswechsel — gestern gebuchter Spend zaehlt heute 0, naechste Buchung startet frisch`() {
        val clock = MutableClock(Instant.parse("2026-07-05T10:00:00Z"))
        val path = tempPath()
        val store = FileBackedEscalationSpendStore(path, clock = clock)
        store.book(40.0)
        assertEquals(40.0, store.spentTodayCents(), 1e-9)

        clock.advance(Duration.ofDays(1))
        assertEquals(0.0, store.spentTodayCents(), 1e-9, "neuer Tag ⇒ Zähler 0 (ohne Write)")
        store.book(1.5)
        assertEquals(1.5, store.spentTodayCents(), 1e-9, "Buchung am neuen Tag startet frisch")

        // Und der neue Tag ist auch PERSISTIERT (Restart am neuen Tag liest 1.5).
        val restarted = FileBackedEscalationSpendStore(path, clock = clock)
        assertEquals(1.5, restarted.spentTodayCents(), 1e-9)
    }

    @Test
    fun `fehlende Datei startet bei 0 — kaputte Datei startet bei 0 und wirft NIE`() {
        val missing = FileBackedEscalationSpendStore(tempPath())
        assertEquals(0.0, missing.spentTodayCents(), 1e-9)

        val broken = tempPath()
        Files.createDirectories(broken.parent)
        Files.writeString(broken, "{{{ kein json")
        val store = FileBackedEscalationSpendStore(broken)
        assertEquals(0.0, store.spentTodayCents(), 1e-9, "kaputte Datei ⇒ 0, kein Crash")
        store.book(0.5) // und sie ist wieder beschreibbar
        assertEquals(0.5, store.spentTodayCents(), 1e-9)
    }

    @Test
    fun `negative Buchungen werden ignoriert (ein Spend-Zaehler kennt kein Guthaben)`() {
        val store = FileBackedEscalationSpendStore(tempPath())
        store.book(1.0)
        store.book(-5.0)
        assertEquals(1.0, store.spentTodayCents(), 1e-9)
    }

    @Test
    fun `resolveDefaultPath — explizit gewinnt, sonst Daten-Verzeichnis-Kaskade`() {
        assertEquals(
            Path.of("/tmp/x/spend.json"),
            FileBackedEscalationSpendStore.resolveDefaultPath("/tmp/x/spend.json"),
        )
        val fallback = FileBackedEscalationSpendStore.resolveDefaultPath(null).toString()
        assertTrue(
            fallback.endsWith("escalation/spend.json"),
            "Kaskade muss auf …/escalation/spend.json zeigen: $fallback",
        )
    }
}
