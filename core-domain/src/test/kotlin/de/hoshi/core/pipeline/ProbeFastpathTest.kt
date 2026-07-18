package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist den Probe-Fastpath [ProbeFastpath] (Golden-Utterance #20, Andis
 * Selbsttest-Ritual „Hoshi, Probe."): die Trigger-Matrix (Satzzeichen,
 * Groß-/Kleinschreibung, Wake-Wort-Rest), dass „Probe" MITTEN im Satz NIE
 * feuert (konservativ, EXAKTER Treffer statt `contains`), die EXAKT
 * gepinnte, brain-freie Quittung und das Flag-OFF (byte-neutral).
 */
class ProbeFastpathTest {

    private fun fastpath() = ProbeFastpath()

    // ── Trigger-Matrix: eigenständige Äußerung, tolerant für Satzzeichen/Groß-Klein/Wake-Rest ──

    @Test
    fun `blankes Probe ohne Satzzeichen matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("Probe"))
    }

    @Test
    fun `Probe mit Punkt matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("Probe."))
    }

    @Test
    fun `probe klein geschrieben matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("probe"))
    }

    @Test
    fun `PROBE komplett gross mit Ausrufezeichen matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("PROBE!!"))
    }

    @Test
    fun `Probe mit Komma matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("Probe,"))
    }

    @Test
    fun `Wake-Wort-Rest Hoshi Komma Probe matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("Hoshi, Probe."))
    }

    @Test
    fun `Wake-Wort-Rest Hoshi Probe ohne Komma matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("Hoshi Probe"))
    }

    @Test
    fun `Wake-Wort-Rest klein geschrieben matcht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("hoshi, probe"))
    }

    @Test
    fun `fuehrende und nachfolgende Leerzeichen stoeren nicht`() {
        assertEquals(ProbeFastpath.RECEIPT, fastpath().handle("   Probe.   "))
    }

    // ── GEGEN-Beispiele: „Probe" MITTEN im Satz feuert NIE (exakter Treffer, kein contains) ──

    @Test
    fun `Probe mitten im Satz feuert nicht`() {
        assertFalse(fastpath().isProbe("Ich mach noch eine Probe für die Band"))
        assertNull(fastpath().handle("Ich mach noch eine Probe für die Band"))
    }

    @Test
    fun `nur zur Probe feuert nicht`() {
        assertNull(fastpath().handle("Das war nur zur Probe."))
    }

    @Test
    fun `Generalprobe als ein Token feuert nicht`() {
        assertNull(fastpath().handle("Generalprobe"))
    }

    @Test
    fun `Probe mit zusaetzlicher Nutzlast feuert nicht`() {
        assertNull(fastpath().handle("Probe, wie warm ist es?"), "eine Nutzlast hinter Probe ist eine andere Absicht, keine Kabel-Probe")
    }

    @Test
    fun `unbeteiligter Text feuert nicht`() {
        assertNull(fastpath().handle("Wie wird das Wetter morgen?"))
        assertNull(fastpath().handle(""))
        assertNull(fastpath().handle("   "))
    }

    // ── brain-frei: die Quittung ist eine reine, deterministische Funktion des Texts ──

    @Test
    fun `Quittung ist deterministisch und immer identisch`() {
        val fp = fastpath()
        val first = fp.handle("Probe.")
        val second = fp.handle("Hoshi, Probe")
        assertEquals(first, second, "die Quittung haengt an KEINEM Zustand/Uhr/Brain — immer derselbe Satz")
        assertTrue(first!!.isNotBlank())
    }

    // ── Flag-OFF ⇒ null (toter Zweig, byte-neutral) ──────────────────────────

    @Test
    fun `DISABLED liefert immer null`() {
        assertNull(ProbeFastpath.DISABLED.handle("Probe"))
        assertNull(ProbeFastpath.DISABLED.handle("Probe."))
        assertNull(ProbeFastpath.DISABLED.handle("Hoshi, Probe."))
    }
}
