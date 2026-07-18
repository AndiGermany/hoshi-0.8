package de.hoshi.core.pipeline

import de.hoshi.core.port.WorkshopNote
import de.hoshi.core.port.WorkshopNotePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Beweist den Werkstatt-Notiz-Fastpath [WorkshopNoteFastpath] (Cowork-Idee,
 * S1): die Trigger-Matrix inkl. „bitte"-Toleranz, die verbatim Notiz-Text-
 * Extraktion, die Store-Wirkung über die [WorkshopNotePort]-Naht (ts aus
 * fester Uhr, speakerId/text exakt), die EXAKT gepinnte Quittung und das
 * Flag-OFF (byte-neutral).
 */
class WorkshopNoteFastpathTest {

    private val fixedInstant: Instant = Instant.parse("2026-07-08T09:30:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneId.of("Europe/Berlin"))

    /** Nimmt jede Notiz an und protokolliert sie (Briefkasten — kein Überschreib-Flag). */
    private class RecordingPort : WorkshopNotePort {
        val notes = mutableListOf<WorkshopNote>()
        override fun record(note: WorkshopNote) {
            notes += note
        }
    }

    private fun fastpath(port: RecordingPort = RecordingPort()) = WorkshopNoteFastpath(port, clock)

    // ── Trigger-Matrix: „Notiz an die Werkstatt" / „Werkstatt-Notiz" ────────

    @Test
    fun `Notiz an die Werkstatt mit Doppelpunkt matcht`() {
        val port = RecordingPort()
        assertEquals(
            "Notiert für die Werkstatt. Danke dir!",
            fastpath(port).handle("Notiz an die Werkstatt: Timer-Antwort zu lang", "andi"),
        )
        assertEquals("Timer-Antwort zu lang", port.notes.single().text)
    }

    @Test
    fun `Notiz an die Werkstatt ohne Doppelpunkt matcht`() {
        val port = RecordingPort()
        fastpath(port).handle("Notiz an die Werkstatt Timer-Antwort zu lang", "andi")
        assertEquals("Timer-Antwort zu lang", port.notes.single().text)
    }

    @Test
    fun `Werkstatt-Notiz mit Bindestrich matcht`() {
        val port = RecordingPort()
        fastpath(port).handle("Werkstatt-Notiz: Kaffee ist alle", "andi")
        assertEquals("Kaffee ist alle", port.notes.single().text)
    }

    @Test
    fun `Werkstatt Notiz ohne Bindestrich matcht (STT trennt oft)`() {
        val port = RecordingPort()
        fastpath(port).handle("Werkstatt Notiz: Kaffee ist alle", "andi")
        assertEquals("Kaffee ist alle", port.notes.single().text)
    }

    @Test
    fun `Trigger nach Wake-Word-Praefix matcht (find statt matches)`() {
        val port = RecordingPort()
        assertEquals(
            "Notiert für die Werkstatt. Danke dir!",
            fastpath(port).handle("Hoshi, Notiz an die Werkstatt: Timer-Antwort zu lang", "andi"),
        )
        assertEquals("Timer-Antwort zu lang", port.notes.single().text)
    }

    // ── „bitte"-Toleranz (Live-Miss-Lehre der Tagesnote) ─────────────────────

    @Test
    fun `fuehrendes bitte wird verschluckt`() {
        val port = RecordingPort()
        fastpath(port).handle("Bitte Notiz an die Werkstatt: Staubsauger kaputt", "andi")
        assertEquals("Staubsauger kaputt", port.notes.single().text)
    }

    @Test
    fun `bitte vor dem Doppelpunkt wird verschluckt`() {
        val port = RecordingPort()
        fastpath(port).handle("Notiz an die Werkstatt, bitte: Timer-Antwort zu lang", "andi")
        assertEquals("Timer-Antwort zu lang", port.notes.single().text)
    }

    @Test
    fun `bitte ohne Komma vor dem Rest wird verschluckt`() {
        val port = RecordingPort()
        fastpath(port).handle("Werkstatt-Notiz bitte, Timer zu laut", "andi")
        assertEquals("Timer zu laut", port.notes.single().text)
    }

    @Test
    fun `bitte im Notiz-Text NACH dem Trenner bleibt erhalten (kein Fuellwort mehr)`() {
        val port = RecordingPort()
        fastpath(port).handle("Notiz an die Werkstatt: bitte Kaffeemaschine entkalken", "andi")
        assertEquals("bitte Kaffeemaschine entkalken", port.notes.single().text, "bitte hinter dem Trenner ist Teil des Notiz-Texts, kein Fuellwort")
    }

    // ── Verbatim-Extraktion: keine Interpunktions-Politur ────────────────────

    @Test
    fun `Notiz-Text bleibt verbatim inkl Gross-Kleinschreibung und Satzzeichen`() {
        val port = RecordingPort()
        fastpath(port).handle("Notiz an die Werkstatt: Der Timer klingelt zu LEISE!!", "andi")
        assertEquals("Der Timer klingelt zu LEISE!!", port.notes.single().text)
    }

    // ── Store-Wirkung: ts/speakerId exakt ────────────────────────────────────

    @Test
    fun `Notiz traegt feste Uhr und Sprecher`() {
        val port = RecordingPort()
        fastpath(port).handle("Notiz an die Werkstatt: Timer-Antwort zu lang", "andi")
        assertEquals(fixedInstant, port.notes.single().ts, "ts kommt aus der injizierten Uhr")
        assertEquals("andi", port.notes.single().speakerId)
    }

    @Test
    fun `unbekannter Sprecher landet als null`() {
        val port = RecordingPort()
        fastpath(port).handle("Notiz an die Werkstatt: Timer-Antwort zu lang", null)
        assertNull(port.notes.single().speakerId)
    }

    // ── Zwei Notizen ⇒ zwei Store-Aufrufe (Briefkasten, kein Ueberschreiben) ─

    @Test
    fun `zwei Notizen erzeugen zwei Store-Aufrufe`() {
        val port = RecordingPort()
        val fp = fastpath(port)
        fp.handle("Notiz an die Werkstatt: erste Notiz", "andi")
        fp.handle("Notiz an die Werkstatt: zweite Notiz", "andi")
        assertEquals(listOf("erste Notiz", "zweite Notiz"), port.notes.map { it.text })
    }

    // ── GEGEN-Beispiele: konservativ (Trigger-Phrase + Notiz-Text PFLICHT) ───

    @Test
    fun `Gegen-Beispiele matchen nicht und beruehren den Store nie`() {
        val port = RecordingPort()
        val fp = fastpath(port)
        assertNull(fp.handle("Notiz an die Werkstatt", "andi"), "kein Text nach der Trigger-Phrase ⇒ kein Treffer")
        assertNull(fp.handle("Werkstatt-Notiz", "andi"), "kein Text nach der Trigger-Phrase ⇒ kein Treffer")
        assertNull(fp.handle("wie geht es der Werkstatt?", "andi"), "kein Trigger-Wort")
        assertNull(fp.handle("Notiz an mich: Milch kaufen", "andi"), "keine Werkstatt genannt")
        assertNull(fp.handle("", "andi"))
        assertEquals(0, port.notes.size, "Gegen-Beispiel darf NIE speichern")
    }

    // ── Flag-OFF ⇒ null (toter Zweig, byte-neutral) ──────────────────────────

    @Test
    fun `DISABLED liefert immer null und beruehrt den Store nie`() {
        assertNull(WorkshopNoteFastpath.DISABLED.handle("Notiz an die Werkstatt: Timer-Antwort zu lang", "andi"))
        assertNull(WorkshopNoteFastpath.DISABLED.handle("Werkstatt-Notiz: Kaffee ist alle", "andi"))
    }
}
