package de.hoshi.adapters.memory

import de.hoshi.core.port.WorkingSessionSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **Themen-Segmentierung (räumliches Gedächtnis, S2)** — Zeit-Lücke + Reset-Phrasen
 * (die gate-freie S2-Grenze; semantische Distanz = dokumentierte Folge-Scheibe):
 *
 *  - OFF ⇒ S1-Verhalten byte-identisch (alle Turns, neutrale Diary-Felder).
 *  - Zeit-Lücke > Schwelle ⇒ FRISCH (`resetReason="time-gap"`), beim Lesen UND
 *    persistent über die append-Grenze.
 *  - Reset-Phrase am Äußerungs-Anfang ⇒ FRISCH (`resetReason="marker"`), schon
 *    für die AKTUELLE Äußerung.
 *  - **Anti-Über-Segmentierung (Pflicht-Abnahme):** eine kurze/anaphorische
 *    Nachfrage im selben Thema VERLÄNGERT immer.
 *  - Raum fließt nirgends ein (Andi-Entscheid 03.07: Raum ist NIE eine Grenze) —
 *    strukturell erfüllt, die Signatur kennt keinen Raum.
 */
class WorkingSessionTopicSegmentTest {

    /** Stellbare Test-Uhr — deterministische Zeit-Lücken. */
    private class SteppingClock(private var now: Instant = Instant.parse("2026-07-05T10:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        fun advanceMinutes(minutes: Long) { now = now.plus(Duration.ofMinutes(minutes)) }
    }

    private fun adapter(clock: SteppingClock, enabled: Boolean = true) =
        WorkingSessionAdapter(clock = clock, topicSegmentEnabled = enabled, timeGapMinutes = 30)

    @Test
    fun `OFF ist S1-verhalten byte-identisch - keine grenze trotz riesiger zeit-luecke`() {
        val clock = SteppingClock()
        val store = adapter(clock, enabled = false)
        store.append("andi", "Wie hoch ist der Skytree?", "634 Meter.")
        clock.advanceMinutes(240)
        store.append("andi", "Was kochen wir?", "Wie wäre Curry?")

        assertEquals(4, store.recentTurns("andi").size, "OFF ⇒ alle Turns, kein Segment-Schnitt")
        val segment = store.readSegment("andi", "und morgen?")
        assertEquals(4, segment.turns.size, "OFF ⇒ readSegment liefert die S1-Liste")
        assertFalse(segment.segmentReset, "OFF ⇒ neutrale Diary-Felder")
        assertEquals(WorkingSessionSegment.REASON_NONE, segment.resetReason)
    }

    @Test
    fun `zeit-luecke beim lesen - segment abgelaufen ergibt FRISCH mit grund time-gap`() {
        val clock = SteppingClock()
        val store = adapter(clock)
        store.append("andi", "Wie hoch ist der Skytree?", "634 Meter.")

        clock.advanceMinutes(10)
        val within = store.readSegment("andi", "und wie hoch ist ER?")
        assertEquals(2, within.turns.size, "innerhalb der Lücke ⇒ VERLÄNGERN")
        assertFalse(within.segmentReset)
        assertEquals(1, within.segmentLenTurns)

        clock.advanceMinutes(31)
        val after = store.readSegment("andi", "und wie hoch ist ER?")
        assertTrue(after.turns.isEmpty(), "Zeit-Lücke > 30 min ⇒ FRISCH, keine Alt-history")
        assertTrue(after.segmentReset)
        assertEquals(WorkingSessionSegment.REASON_TIME_GAP, after.resetReason)
        assertEquals(0, after.segmentLenTurns)
    }

    @Test
    fun `zeit-luecke bleibt als grenze persistent - der neue turn startet das segment`() {
        val clock = SteppingClock()
        val store = adapter(clock)
        store.append("andi", "Wie hoch ist der Skytree?", "634 Meter.")
        clock.advanceMinutes(45) // Lücke ⇒ der nächste Turn startet ein frisches Segment
        store.append("andi", "Was kochen wir heute?", "Wie wäre Curry?")
        clock.advanceMinutes(2)

        val segment = store.readSegment("andi", "und mit Reis?")
        assertEquals(
            listOf("Was kochen wir heute?", "Wie wäre Curry?"),
            segment.turns.map { it.content },
            "nur das aktuelle Segment — der Skytree-Kontext bleibt hinter der Grenze",
        )
        assertEquals(1, segment.segmentLenTurns)
        assertFalse(segment.segmentReset, "die Grenze lag beim VORIGEN Turn, dieser verlängert")
    }

    @Test
    fun `reset-phrase startet schon die AKTUELLE aeusserung frisch und bleibt grenze`() {
        val clock = SteppingClock()
        val store = adapter(clock)
        store.append("andi", "Wie hoch ist der Skytree?", "634 Meter.")
        clock.advanceMinutes(1)

        val marker = store.readSegment("andi", "Ganz was anderes: was kochen wir heute?")
        assertTrue(marker.turns.isEmpty(), "zweites Thema trägt den Skytree-Kontext NICHT")
        assertTrue(marker.segmentReset)
        assertEquals(WorkingSessionSegment.REASON_MARKER, marker.resetReason)

        store.append("andi", "Ganz was anderes: was kochen wir heute?", "Wie wäre Curry?")
        clock.advanceMinutes(1)
        val next = store.readSegment("andi", "und mit Reis?")
        assertEquals(
            listOf("Ganz was anderes: was kochen wir heute?", "Wie wäre Curry?"),
            next.turns.map { it.content },
            "die Marker-Grenze ist persistent — das neue Segment beginnt beim Marker-Turn",
        )
    }

    /** PFLICHT-Abnahme (Anti-Über-Segmentierung): kurze anaphorische Nachfrage ⇒ VERLÄNGERN. */
    @Test
    fun `kurze anaphorische nachfrage im selben thema VERLAENGERT immer`() {
        val clock = SteppingClock()
        val store = adapter(clock)
        store.append("andi", "Wie hoch ist der Tokyo Skytree?", "634 Meter.")
        clock.advanceMinutes(2)

        for (followUp in listOf("und morgen?", "und wie hoch ist ER?", "echt?", "und du?")) {
            val segment = store.readSegment("andi", followUp)
            assertEquals(2, segment.turns.size, "'$followUp' darf NIE resetten (Kontext bleibt)")
            assertFalse(segment.segmentReset, "'$followUp' ⇒ VERLÄNGERN, kein Reset")
        }
    }

    @Test
    fun `gast bekommt auch mit segmentierung nie eine session`() {
        val clock = SteppingClock()
        val store = adapter(clock)
        store.append("gast", "geheim", "antwort")
        val segment = store.readSegment("gast", "und weiter?")
        assertTrue(segment.turns.isEmpty())
        assertEquals(0, store.storedTurnCount("gast"))
    }

    @Test
    fun `reset-phrasen matchen nur am aeusserungs-anfang mit wort-grenze`() {
        // Treffer (DE+EN, Groß-/Kleinschreibung egal, Satzzeichen nach der Phrase ok)
        for (hit in listOf(
            "Übrigens, was kochen wir?",
            "übrigens was anderes",
            "Apropos Essen — was kochen wir?",
            "Ganz was anderes: was kochen wir?",
            "Anderes Thema, bitte",
            "By the way, what about dinner?",
            "anyway, let's move on",
        )) {
            assertTrue(WorkingSessionAdapter.isResetPhrase(hit), "'$hit' muss als Reset-Phrase zählen")
        }
        // KEINE Treffer (mitten im Satz, Wort-Fortsetzung, anaphorisch/kurz, leer)
        for (miss in listOf(
            "Das ist übrigens gut",   // nicht am Anfang
            "anyways, whatever",       // Wort-Grenze: 'anyway'+s
            "und morgen?",
            "wie hoch ist er?",
            "",
        )) {
            assertFalse(WorkingSessionAdapter.isResetPhrase(miss), "'$miss' darf NICHT als Reset-Phrase zählen")
        }
    }
}
