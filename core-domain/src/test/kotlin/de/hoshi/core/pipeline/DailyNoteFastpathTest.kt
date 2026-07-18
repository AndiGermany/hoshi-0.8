package de.hoshi.core.pipeline

import de.hoshi.core.port.DailyNote
import de.hoshi.core.port.DailyNotePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Beweist den Tagesnoten-Fastpath [DailyNoteFastpath] (Andi-Faktor per
 * Sprache/Chat): die konservative Matching-Matrix (Zahl 1–5 PFLICHT nahe am
 * Tagesnote-Wort, inkl. Gegen-Beispiele), die Store-Wirkung über die
 * [DailyNotePort]-Naht (ts aus fester Uhr, score/grund/source exakt), die
 * EXAKT gepinnten Quittungen („Notiert: …" / ehrliches „Aktualisiert: …")
 * und das Flag-OFF (byte-neutral).
 */
class DailyNoteFastpathTest {

    private val fixedInstant: Instant = Instant.parse("2026-07-07T09:30:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneId.of("Europe/Berlin"))

    /** Nimmt jede Note an und protokolliert sie; [replaced] steuert den Überschreib-Vertrag. */
    private class RecordingPort(private val replaced: Boolean = false) : DailyNotePort {
        val notes = mutableListOf<DailyNote>()
        override fun record(note: DailyNote): Boolean {
            notes += note
            return replaced
        }
    }

    private fun fastpath(port: RecordingPort = RecordingPort()) = DailyNoteFastpath(port, clock)

    // ── Matching-Matrix: die Andi-Formulierungen ─────────────────────────────

    @Test
    fun `Tagesnote mit blanker Zahl matcht`() {
        val port = RecordingPort()
        assertEquals("Notiert: heute eine 4. Danke dir!", fastpath(port).handle("Tagesnote 4", "chat"))
        assertEquals(4, port.notes.single().score)
        assertNull(port.notes.single().grund)
    }

    @Test
    fun `Tagesnote mit Doppelpunkt und Grund matcht`() {
        val port = RecordingPort()
        assertEquals("Notiert: heute eine 3. Danke dir!", fastpath(port).handle("Tagesnote: 3, zu langsam", "chat"))
        assertEquals(3, port.notes.single().score)
        assertEquals("zu langsam", port.notes.single().grund)
    }

    @Test
    fun `Xer-Tag-Formulierung matcht`() {
        val port = RecordingPort()
        assertEquals("Notiert: heute eine 4. Danke dir!", fastpath(port).handle("heute war ein 4er Tag", "voice"))
        assertEquals(4, port.notes.single().score)
        assertNull(port.notes.single().grund)
    }

    @Test
    fun `Xer-Tag mit weil-Grund matcht und putzt den Grund`() {
        val port = RecordingPort()
        fastpath(port).handle("Heute war ein 5er Tag, weil die Timer endlich klingeln.", "voice")
        assertEquals(5, port.notes.single().score)
        assertEquals("die Timer endlich klingeln", port.notes.single().grund)
    }

    @Test
    fun `Tagesnote mit weil-Grund ohne Komma matcht`() {
        val port = RecordingPort()
        fastpath(port).handle("tagesnote 5 weil alles lief", "chat")
        assertEquals(5, port.notes.single().score)
        assertEquals("alles lief", port.notes.single().grund)
    }

    @Test
    fun `Tagesnote ist X matcht`() {
        val port = RecordingPort()
        assertEquals("Notiert: heute eine 2. Danke dir!", fastpath(port).handle("Tagesnote ist 2", "chat"))
        assertEquals(2, port.notes.single().score)
    }

    // ── Store-Wirkung: ts/source exakt ───────────────────────────────────────

    @Test
    fun `Note traegt feste Uhr und Eingangs-Rand`() {
        val port = RecordingPort()
        fastpath(port).handle("Tagesnote 4", "voice")
        assertEquals(fixedInstant, port.notes.single().ts, "ts kommt aus der injizierten Uhr")
        assertEquals("voice", port.notes.single().source)
    }

    // ── Überschreib-Vertrag: zweite Note am selben Tag ⇒ ehrliches Update ───

    @Test
    fun `zweite Note am selben Tag quittiert Aktualisiert`() {
        val fp = fastpath(RecordingPort(replaced = true))
        assertEquals("Aktualisiert: heute eine 3. Danke dir!", fp.handle("Tagesnote 3", "chat"))
    }

    // ── GEGEN-Beispiele: konservativ (Zahl 1-5 PFLICHT nahe am Wort) ─────────

    @Test
    fun `Gegen-Beispiele matchen nicht und beruehren den Store nie`() {
        val port = RecordingPort()
        val fp = fastpath(port)
        assertNull(fp.handle("Tagesnote 7", "chat"), "außerhalb der Skala 1-5")
        assertNull(fp.handle("Tagesnote", "chat"), "ohne Zahl keine Note")
        assertNull(fp.handle("wie war meine Tagesnote?", "chat"), "Frage ist kein Eintrag")
        assertNull(fp.handle("gib mir eine Note 4", "chat"), "ohne Tagesnote-Wort nie")
        assertNull(fp.handle("heute war ein guter Tag", "chat"), "ohne Zahl nie")
        assertNull(fp.handle("Tagesnote 4,5", "chat"), "Dezimal-Zahl wird geblockt (lieber kein Treffer)")
        assertNull(fp.handle("Tagesnote 45", "chat"), "zweistellig wird geblockt")
        assertNull(fp.handle("heute war ein 10er Tag", "chat"), "außerhalb der Skala")
        assertNull(fp.handle("die Tagesnote von gestern war schlecht", "chat"), "keine Zahl am Wort")
        assertNull(fp.handle("", "chat"))
        assertEquals(0, port.notes.size, "Gegen-Beispiel darf NIE speichern")
    }

    // ── Flag-OFF ⇒ null (toter Zweig, byte-neutral) ──────────────────────────

    @Test
    fun `DISABLED liefert immer null`() {
        assertNull(DailyNoteFastpath.DISABLED.handle("Tagesnote 4", "chat"))
        assertNull(DailyNoteFastpath.DISABLED.handle("heute war ein 4er Tag", "voice"))
    }
}
