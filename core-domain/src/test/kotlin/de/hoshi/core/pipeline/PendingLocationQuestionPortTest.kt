package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Beweist Store + Erkenner der Wetter-Orts-Nachfrage (Wetter S3) isoliert —
 * ZEILE FÜR ZEILE nach dem [PendingLookupPortTest]-Muster:
 *
 *  - [InMemoryPendingLocationQuestionStore]: one-shot [PendingLocationQuestionPort.consume],
 *    TTL (~120 s), [PendingLocationQuestionPort.NONE] als toter Default.
 *  - [LocationAnswerRecognizer]: KONSERVATIV — Großschreibungs-Form (1–3 Tokens)
 *    oder „in X"-Form; Floskeln („Nein", „Okay", „in Ordnung") sind NIE ein Ort.
 */
class PendingLocationQuestionPortTest {

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
    }

    private fun pending(query: String = "Wie wird das Wetter morgen?") =
        PendingLocationQuestion(query = query, language = Language.DE)

    // ── Store: one-shot ──────────────────────────────────────────────────────────
    @Test
    fun `consume ist one-shot - das zweite consume liefert null`() {
        val store = InMemoryPendingLocationQuestionStore()
        store.offer("local", pending())
        assertEquals("Wie wird das Wetter morgen?", store.consume("local")?.query)
        assertNull(store.consume("local"), "one-shot: nach dem Ziehen ist die Nachfrage weg")
    }

    @Test
    fun `fremder Schluessel sieht die Nachfrage nie`() {
        val store = InMemoryPendingLocationQuestionStore()
        store.offer("chat-1", pending())
        assertNull(store.consume("chat-2"))
        assertEquals("Wie wird das Wetter morgen?", store.consume("chat-1")?.query)
    }

    @Test
    fun `blank key oder blank query werden nie gemerkt`() {
        val store = InMemoryPendingLocationQuestionStore()
        store.offer("", pending())
        store.offer("local", pending(query = " "))
        assertNull(store.consume(""))
        assertNull(store.consume("local"))
    }

    // ── Store: TTL ───────────────────────────────────────────────────────────────
    @Test
    fun `TTL abgelaufen - eine Nachfrage von vorhin faengt keine Orts-Nennung von jetzt`() {
        val clock = MutableClock(Instant.now())
        val store = InMemoryPendingLocationQuestionStore(clock = clock)
        store.offer("local", pending())
        clock.advanceSeconds(121)
        assertNull(store.consume("local"), "TTL 120 s: abgelaufen ⇒ null (und geräumt)")
    }

    @Test
    fun `innerhalb der TTL bleibt die Nachfrage einloesbar`() {
        val clock = MutableClock(Instant.now())
        val store = InMemoryPendingLocationQuestionStore(clock = clock)
        store.offer("local", pending())
        clock.advanceSeconds(119)
        assertEquals("Wie wird das Wetter morgen?", store.consume("local")?.query)
    }

    // ── NONE-Default ─────────────────────────────────────────────────────────────
    @Test
    fun `NONE merkt nie und liefert nie - byte-neutraler Default`() {
        val none = PendingLocationQuestionPort.NONE
        none.offer("local", pending())
        assertNull(none.consume("local"))
    }

    // ── Erkenner: positive Formen ────────────────────────────────────────────────
    @Test
    fun `Orts-Formen - Grossschreibung und in-X werden erkannt`() {
        assertEquals("Duisburg", LocationAnswerRecognizer.place("Duisburg"))
        assertEquals("Duisburg", LocationAnswerRecognizer.place("Duisburg."))
        assertEquals("Duisburg", LocationAnswerRecognizer.place("  Duisburg! "))
        assertEquals("Bad Homburg", LocationAnswerRecognizer.place("Bad Homburg"))
        assertEquals("Duisburg", LocationAnswerRecognizer.place("in Duisburg"))
        assertEquals("Duisburg", LocationAnswerRecognizer.place("In Duisburg"))
        // „in X": die Präposition trägt das Signal — X darf STT-kleingeschrieben sein.
        assertEquals("duisburg", LocationAnswerRecognizer.place("in duisburg"))
        assertEquals("Bad Homburg", LocationAnswerRecognizer.place("in Bad Homburg"))
    }

    // ── Erkenner: konservative Ablehnungen ───────────────────────────────────────
    @Test
    fun `Floskeln sind NIE ein Ort - Nein Ja Okay in Ordnung`() {
        assertNull(LocationAnswerRecognizer.place("Nein"))
        assertNull(LocationAnswerRecognizer.place("Ja"))
        assertNull(LocationAnswerRecognizer.place("Okay"))
        assertNull(LocationAnswerRecognizer.place("nein danke"))
        assertNull(LocationAnswerRecognizer.place("in Ordnung"))
        assertNull(LocationAnswerRecognizer.place("Ja bitte"))
        assertNull(LocationAnswerRecognizer.place("Moment"))
    }

    @Test
    fun `freier Text ist NIE ein Ort - kleingeschrieben, zu lang, Ziffern, Interpunktion`() {
        assertNull(LocationAnswerRecognizer.place("wie ist das wetter"), "kleingeschrieben + 4 Tokens")
        assertNull(LocationAnswerRecognizer.place("duisburg"), "bare Form verlangt Großschreibung")
        assertNull(LocationAnswerRecognizer.place("Mach das Licht an"), "> 3 Tokens")
        assertNull(LocationAnswerRecognizer.place("Ja, aber was"), "Interpunktion im Token")
        assertNull(LocationAnswerRecognizer.place("42"), "Ziffern sind nie ein Ort")
        assertNull(LocationAnswerRecognizer.place("Duisburg 42"), "Ziffern-Token bricht den Match")
        assertNull(LocationAnswerRecognizer.place(""), "leer")
        assertNull(LocationAnswerRecognizer.place("in"), "nacktes »in« ohne Ort")
    }
}
