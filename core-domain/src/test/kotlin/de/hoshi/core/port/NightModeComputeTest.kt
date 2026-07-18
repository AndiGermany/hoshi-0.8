package de.hoshi.core.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * **NightModeComputeTest** — beweist die reine Nachtmodus-Berechnung
 * ([NightModeCompute]), allen voran den Mitternachts-Rollover (Andi-Fenster
 * `22:00`–`07:00`, der Knackpunkt dieser Scheibe).
 */
class NightModeComputeTest {

    private fun config(
        enabled: Boolean = true,
        mode: NightModeMode = NightModeMode.SCHEDULE,
        from: String = "22:00",
        to: String = "07:00",
        dim: Double = 0.3,
    ) = NightModeConfig(enabled = enabled, mode = mode, from = from, to = to, dim = dim)

    // ── Mitternachts-Rollover (from > to, 22:00–07:00) ───────────────────────

    @Test
    fun `22-07 Fenster - aktiv um 23 Uhr (nach from, vor Mitternacht)`() {
        assertTrue(NightModeCompute.active(config(), LocalTime.of(23, 0)))
    }

    @Test
    fun `22-07 Fenster - aktiv um 3 Uhr (nach Mitternacht, vor to)`() {
        assertTrue(NightModeCompute.active(config(), LocalTime.of(3, 0)))
    }

    @Test
    fun `22-07 Fenster - inaktiv um 12 Uhr mittags`() {
        assertFalse(NightModeCompute.active(config(), LocalTime.of(12, 0)))
    }

    @Test
    fun `22-07 Fenster - from ist inklusiv, genau 22-00 ist schon aktiv`() {
        assertTrue(NightModeCompute.active(config(), LocalTime.of(22, 0)))
    }

    @Test
    fun `22-07 Fenster - to ist exklusiv, genau 07-00 ist schon inaktiv`() {
        assertFalse(NightModeCompute.active(config(), LocalTime.of(7, 0)))
    }

    @Test
    fun `22-07 Fenster - kurz vor to (06-59) ist noch aktiv`() {
        assertTrue(NightModeCompute.active(config(), LocalTime.of(6, 59)))
    }

    // ── Normalfall ohne Rollover (from <= to) ────────────────────────────────

    @Test
    fun `09-17 Fenster (from kleiner gleich to) - aktiv innerhalb, inaktiv ausserhalb`() {
        val c = config(from = "09:00", to = "17:00")
        assertTrue(NightModeCompute.active(c, LocalTime.of(9, 0)), "from inklusiv")
        assertTrue(NightModeCompute.active(c, LocalTime.of(12, 30)), "mitten im Fenster")
        assertFalse(NightModeCompute.active(c, LocalTime.of(17, 0)), "to exklusiv")
        assertFalse(NightModeCompute.active(c, LocalTime.of(8, 59)), "kurz vor from")
        assertFalse(NightModeCompute.active(c, LocalTime.of(20, 0)), "weit ausserhalb")
    }

    // ── ALWAYS ────────────────────────────────────────────────────────────────

    @Test
    fun `ALWAYS Modus - immer aktiv wenn enabled, unabhaengig von der Uhrzeit`() {
        val c = config(mode = NightModeMode.ALWAYS)
        assertTrue(NightModeCompute.active(c, LocalTime.of(3, 0)))
        assertTrue(NightModeCompute.active(c, LocalTime.of(12, 0)))
        assertTrue(NightModeCompute.active(c, LocalTime.of(23, 59)))
    }

    // ── enabled=false ─────────────────────────────────────────────────────────

    @Test
    fun `enabled false - nie aktiv, egal welcher Modus oder welche Uhrzeit`() {
        val schedule = config(enabled = false, mode = NightModeMode.SCHEDULE)
        val always = config(enabled = false, mode = NightModeMode.ALWAYS)
        assertFalse(NightModeCompute.active(schedule, LocalTime.of(23, 0)))
        assertFalse(NightModeCompute.active(always, LocalTime.of(23, 0)))
    }

    // ── never-throw bei kaputten Zeit-Strings ────────────────────────────────

    @Test
    fun `kaputte from-to Strings - active liefert false statt zu werfen`() {
        val c = config(from = "nicht-eine-uhrzeit", to = "auch-nicht")
        assertFalse(NightModeCompute.active(c, LocalTime.of(23, 0)))
    }

    @Test
    fun `leere from-to Strings - active liefert false statt zu werfen`() {
        val c = config(from = "", to = "")
        assertFalse(NightModeCompute.active(c, LocalTime.NOON))
    }

    // ── buildFrame ────────────────────────────────────────────────────────────

    @Test
    fun `buildFrame liefert type, active und dim korrekt bei aktivem Fenster`() {
        val frame = NightModeCompute.buildFrame(config(dim = 0.4), LocalTime.of(23, 0))
        assertEquals("night_mode", frame["type"])
        assertEquals(true, frame["active"])
        assertEquals(0.4, frame["dim"])
    }

    @Test
    fun `buildFrame liefert active false ausserhalb des Fensters`() {
        val frame = NightModeCompute.buildFrame(config(dim = 0.4), LocalTime.of(12, 0))
        assertEquals("night_mode", frame["type"])
        assertEquals(false, frame["active"])
    }

    @Test
    fun `buildFrame klammert dim defensiv auf 0 bis 1`() {
        val zuHoch = NightModeCompute.buildFrame(config(dim = 1.5), LocalTime.of(23, 0))
        val zuNiedrig = NightModeCompute.buildFrame(config(dim = -0.2), LocalTime.of(23, 0))
        assertEquals(1.0, zuHoch["dim"])
        assertEquals(0.0, zuNiedrig["dim"])
    }

    // ── parseTimeOrNull (geteilte Validierungslogik mit der Settings-PUT) ────

    @Test
    fun `parseTimeOrNull - gueltige HH-mm parsen, ungueltige liefern null`() {
        assertEquals(LocalTime.of(22, 0), NightModeCompute.parseTimeOrNull("22:00"))
        assertEquals(null, NightModeCompute.parseTimeOrNull("9:00")) // einstellig - kein Contract-Format
        assertEquals(null, NightModeCompute.parseTimeOrNull("25:99"))
        assertEquals(null, NightModeCompute.parseTimeOrNull(""))
    }
}
