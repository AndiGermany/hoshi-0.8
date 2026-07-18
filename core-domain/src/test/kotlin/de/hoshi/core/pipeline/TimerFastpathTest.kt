package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.InMemoryScheduledItemStore
import de.hoshi.core.port.RingingItem
import de.hoshi.core.port.RingingItemPort
import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledKind
import de.hoshi.core.tools.TimerIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-Fake für [RingingItemPort]: hält klingelnde Items in einer Map, [ring] legt eines
 * an, `stopRinging` entfernt es (= Ack) — spiegelt [de.hoshi.web.FiredItemsRingingAdapter]s
 * Vertrag, ohne die `web-inbound`-Schicht zu brauchen (core-domain-Test bleibt frei davon).
 */
private class FakeRingingItemPort : RingingItemPort {
    private val items = LinkedHashMap<String, RingingItem>()
    fun ring(item: RingingItem) {
        items[item.id] = item
    }
    override fun ringing(): List<RingingItem> = items.values.toList()
    override fun stopRinging(id: String): Boolean = items.remove(id) != null
}

/**
 * Beweist den brain-freien Vollzug [TimerFastpath] mit FESTER Uhr (`Clock.fixed`):
 * SET (Dauer + Uhrzeit, inkl. heute/morgen), QUERY (Restzeit), CANCEL — DE+EN.
 */
class TimerFastpathTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun clockAt(hour: Int, minute: Int = 0): Clock {
        val now = ZonedDateTime.of(2024, 1, 1, hour, minute, 0, 0, zone)
        return Clock.fixed(now.toInstant(), zone)
    }

    private fun fastpath(
        store: InMemoryScheduledItemStore,
        clock: Clock,
        ringingPort: RingingItemPort = RingingItemPort.NONE,
    ): TimerFastpath {
        val seq = AtomicInteger(0)
        return TimerFastpath(store = store, clock = clock, idGen = { "id-${seq.incrementAndGet()}" }, ringingPort = ringingPort)
    }

    // ── SET Dauer ────────────────────────────────────────────────────────────

    @Test
    fun `SET Dauer-Timer legt Item bei now plus Dauer an und quittiert warm`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)

        val phrase = fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)

        val item = store.query().single()
        assertEquals(10 * 60 * 1000L, item.dueAtEpochMs - clock.millis(), "Fälligkeit = now + 10 Min")
        assertTrue(phrase.contains("10 Minuten"), "Quittung war: $phrase")
        assertTrue(phrase.contains("Timer"), "Quittung war: $phrase")
    }

    // ── SET Uhrzeit (heute vs. morgen) ───────────────────────────────────────

    @Test
    fun `SET Wecker um 7 bei 6 Uhr feuert heute`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)

        val phrase = fp.handle(TimerIntent.classify("weck mich um 7")!!, Language.DE)

        val item = store.query().single()
        assertEquals(60 * 60 * 1000L, item.dueAtEpochMs - clock.millis(), "07:00 ist heute noch 1 h voraus")
        assertTrue(phrase.contains("07:00"), "Quittung war: $phrase")
    }

    @Test
    fun `SET Wecker um 7 bei 8 Uhr feuert morgen`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(8)
        val fp = fastpath(store, clock)

        val phrase = fp.handle(TimerIntent.classify("weck mich um 7")!!, Language.DE)

        val item = store.query().single()
        assertEquals(23 * 60 * 60 * 1000L, item.dueAtEpochMs - clock.millis(), "07:00 ist vorbei ⇒ morgen (23 h)")
        // Live-Bug Andi 2026-07-03: die Quittung MUSS „morgen" sagen, sonst erwartet der
        // Nutzer den Wecker heute (er kam nie, weil er auf morgen rollte).
        assertTrue(phrase.contains("morgen"), "vergangene Uhrzeit rollt auf morgen ⇒ Quittung sagt es: $phrase")
    }

    @Test
    fun `SET Wecker um 9 bei 8 Uhr feuert heute und sagt NICHT morgen`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(8))
        val phrase = fp.handle(TimerIntent.classify("weck mich um 9")!!, Language.DE)
        assertTrue(!phrase.contains("morgen"), "09:00 ist heute noch voraus, kein morgen erwartet: $phrase")
    }

    // ── QUERY ────────────────────────────────────────────────────────────────

    @Test
    fun `QUERY ohne Timer ist ehrlich leer`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        assertTrue(fp.handle(TimerIntent.classify("wie viele Timer laufen")!!, Language.DE).contains("kein Timer"))
    }

    @Test
    fun `QUERY mit einem Timer nennt die Restzeit`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)

        val phrase = fp.handle(TimerIntent.classify("wie lange läuft der Timer noch")!!, Language.DE)
        assertTrue(phrase.contains("10 Minuten"), "Restzeit-Quittung war: $phrase")
    }

    @Test
    fun `QUERY mit mehreren zaehlt auf`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)
        fp.handle(TimerIntent.classify("stell einen Timer auf 20 Minuten")!!, Language.DE)

        val phrase = fp.handle(TimerIntent.classify("welche Timer laufen")!!, Language.DE)
        assertTrue(phrase.contains("2 Timer"), "Aufzählung war: $phrase")
    }

    // ── QUERY-Golden-Set (Live-Befund Andi 2026-07-06: „Wie lange geht der Timer
    //    noch?" orakelte übers Brain statt deterministisch aus dem Store) ─────────

    @Test
    fun `QUERY wie lange geht der Timer noch nennt exakt Minuten und Sekunden`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        // Fake-Clock: Item mit 9 Min 30 Sek Restzeit direkt (absolut) in den Store.
        store.set(ScheduledItem(id = "t1", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 570_000))

        val phrase = fp.handle(TimerIntent.classify("Wie lange geht der Timer noch?")!!, Language.DE)
        assertEquals("Noch 9 Minuten und 30 Sekunden.", phrase)
    }

    @Test
    fun `QUERY ohne Timer antwortet definitiv und stellt NIE eine Gegenfrage`() {
        val fp = fastpath(InMemoryScheduledItemStore(), clockAt(6))
        val phrase = fp.handle(TimerIntent.classify("Wie lange geht der Timer noch?")!!, Language.DE)
        assertEquals("Gerade läuft kein Timer.", phrase)
    }

    @Test
    fun `QUERY laeuft gerade ein Wecker ohne Wecker nennt die Wecker-Variante`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        // Ein TIMER läuft — aber die WECKER-Frage wird Wecker-spezifisch beantwortet.
        store.set(ScheduledItem(id = "t1", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 60_000))

        val phrase = fp.handle(TimerIntent.classify("Läuft gerade ein Wecker?")!!, Language.DE)
        assertEquals("Gerade ist kein Wecker gestellt.", phrase)
    }

    @Test
    fun `QUERY wann klingelt der Wecker nennt Uhrzeit und Restzeit`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(TimerIntent.classify("weck mich um 7")!!, Language.DE)

        val phrase = fp.handle(TimerIntent.classify("Wann klingelt der Wecker?")!!, Language.DE)
        assertEquals("Dein Wecker klingelt um 07:00 Uhr — noch eine Stunde.", phrase)
    }

    @Test
    fun `QUERY wie spaet klingelt es nennt die Wecker-Uhrzeit`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(TimerIntent.classify("weck mich um 7")!!, Language.DE)

        val phrase = fp.handle(TimerIntent.classify("Wie spät klingelt es?")!!, Language.DE)
        assertEquals("Dein Wecker klingelt um 07:00 Uhr — noch eine Stunde.", phrase)
    }

    @Test
    fun `QUERY mit mehreren nennt eine kurze Liste`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        store.set(ScheduledItem(id = "t1", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 600_000))
        store.set(ScheduledItem(id = "t2", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 1_200_000))

        val phrase = fp.handle(TimerIntent.classify("Läuft gerade ein Timer?")!!, Language.DE)
        assertEquals("Du hast 2 Timer: Timer noch 10 Minuten; Timer noch 20 Minuten.", phrase)
    }

    @Test
    fun `QUERY EN is a timer running ohne Timer ist definitiv`() {
        val fp = fastpath(InMemoryScheduledItemStore(), clockAt(6))
        val phrase = fp.handle(TimerIntent.classify("Is a timer running?")!!, Language.EN)
        assertEquals("No timers running right now.", phrase)
    }

    @Test
    fun `QUERY EN how long does the timer have left nennt exakte Restzeit`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        store.set(ScheduledItem(id = "t1", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 570_000))

        val phrase = fp.handle(TimerIntent.classify("How long does the timer have left?")!!, Language.EN)
        assertEquals("9 minutes and 30 seconds left.", phrase)
    }

    @Test
    fun `QUERY EN is an alarm set ohne Wecker nennt die Alarm-Variante`() {
        val fp = fastpath(InMemoryScheduledItemStore(), clockAt(6))
        val phrase = fp.handle(TimerIntent.classify("Is an alarm set?")!!, Language.EN)
        assertEquals("No alarm is set right now.", phrase)
    }

    // ── QUERY: benannter Abruf bei mehreren Timern (Cowork-Katalog, Live-Lücke
    //    Andi 2026-07-07: „wie lange geht der Nudel-Timer noch?" bei 3 laufenden
    //    Timern muss NUR den benannten treffen) ────────────────────────────────

    /** Drei laufende, benannte Timer — geteiltes Fixture für die Named-Query-Tests. */
    private fun dreiBenannteTimer(store: InMemoryScheduledItemStore, clock: Clock) {
        store.set(ScheduledItem(id = "t1", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 600_000, label = "Pizza"))
        store.set(ScheduledItem(id = "t2", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 300_000, label = "Kaffee"))
        store.set(ScheduledItem(id = "t3", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 570_000, label = "Nudel"))
    }

    @Test
    fun `QUERY benannt trifft bei 3 laufenden Timern nur den genannten mit exakter Restzeit`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        val phrase = fp.handle(TimerIntent.classify("Wie lange geht der Nudel-Timer noch?")!!, Language.DE)
        assertEquals("Nudel: noch 9 Minuten und 30 Sekunden.", phrase)
    }

    @Test
    fun `QUERY benannt (Pizza-Timer) trifft bei 3 laufenden Timern nur den genannten`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        val phrase = fp.handle(TimerIntent.classify("Wie lange noch beim Pizza-Timer?")!!, Language.DE)
        assertEquals("Pizza: noch 10 Minuten.", phrase)
    }

    @Test
    fun `QUERY benannt ohne Treffer ist ehrlich statt zu raten (exakte Phrase)`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        val phrase = fp.handle(TimerIntent.classify("Wie lange geht der Tee-Timer noch?")!!, Language.DE)
        assertEquals(
            "Einen Tee-Timer finde ich nicht — gerade laufen: Kaffee noch 5 Minuten; " +
                "Nudel noch 9 Minuten und 30 Sekunden; Pizza noch 10 Minuten.",
            phrase,
        )
    }

    @Test
    fun `QUERY ohne genannten Namen bleibt bei mehreren (auch benannten) Timern byte-gleich zur kurzen Liste`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        val phrase = fp.handle(TimerIntent.classify("Welche Timer laufen?")!!, Language.DE)
        assertEquals(
            "Du hast 3 Timer: Kaffee noch 5 Minuten; Nudel noch 9 Minuten und 30 Sekunden; Pizza noch 10 Minuten.",
            phrase,
        )
    }

    // ── CANCEL ───────────────────────────────────────────────────────────────

    @Test
    fun `CANCEL einzelner Timer stoppt ihn`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)

        val phrase = fp.handle(TimerIntent.classify("stopp den Timer")!!, Language.DE)
        assertTrue(store.query().isEmpty(), "Timer sollte gestoppt sein")
        assertTrue(phrase.contains("Gestoppt"), "Quittung war: $phrase")
    }

    @Test
    fun `CANCEL benannt stoppt bei 3 laufenden Timern nur den genannten (Nudel-Timer)`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        val phrase = fp.handle(TimerIntent.classify("Stopp den Nudel-Timer")!!, Language.DE)

        val remainingLabels = store.query().mapNotNull { it.label }.toSet()
        assertEquals(setOf("Pizza", "Kaffee"), remainingLabels, "nur Nudel wurde gestoppt, die anderen laufen weiter")
        assertTrue(phrase.contains("Gestoppt"), "Quittung war: $phrase")
        assertTrue(phrase.contains("Nudel"), "Quittung sollte den gestoppten Namen nennen: $phrase")
    }

    // ── CANCEL: genannter, aber NICHT passender Name ist ehrlich statt Rückfrage
    //    (dokumentierte Rest-Lücke des Builders: „Stopp den Tee-Timer" während nur
    //    Pizza/Nudel/Kaffee laufen — dieselbe Phrase wie beim benannten QUERY-
    //    Fehlschlag, geteilt über [TimerFastpath.notFoundPhrase]) ─────────────────

    @Test
    fun `CANCEL benannt ohne Treffer bei 3 laufenden ist ehrlich statt Rueckfrage (exakte Phrase)`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        val phrase = fp.handle(TimerIntent.classify("Stopp den Tee-Timer")!!, Language.DE)

        assertEquals(
            "Einen Tee-Timer finde ich nicht — gerade laufen: Kaffee noch 5 Minuten; " +
                "Nudel noch 9 Minuten und 30 Sekunden; Pizza noch 10 Minuten.",
            phrase,
        )
        assertEquals(3, store.query().size, "kein Treffer ⇒ nichts wurde gestoppt")
    }

    @Test
    fun `CANCEL benannt mit falschem Namen stoppt NICHT einfach den einen laufenden Timer`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        store.set(ScheduledItem(id = "t1", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 600_000, label = "Pizza"))

        val phrase = fp.handle(TimerIntent.classify("Stopp den Tee-Timer")!!, Language.DE)

        assertEquals("Einen Tee-Timer finde ich nicht — gerade laufen: Pizza noch 10 Minuten.", phrase)
        assertEquals(1, store.query().size, "der falsch benannte Stopp darf den einen laufenden Pizza-Timer NICHT raten-stoppen")
    }

    @Test
    fun `CANCEL EN benannt ohne Treffer ist ehrlich statt Rueckfrage (exakte Phrase)`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        val phrase = fp.handle(TimerIntent.classify("Cancel the tea timer")!!, Language.EN)

        assertEquals(
            "I can't find a Tea timer — currently running: Kaffee 5 minutes left; " +
                "Nudel 9 minutes and 30 seconds left; Pizza 10 minutes left.",
            phrase,
        )
        assertEquals(3, store.query().size, "kein Treffer ⇒ nichts wurde gestoppt")
    }

    @Test
    fun `CANCEL ohne erkennbaren Namen bleibt bei mehreren byte-gleich zur Rueckfrage (Regression)`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        val fp = fastpath(store, clock)
        dreiBenannteTimer(store, clock)

        // „stopp den Timer" nennt KEINEN Namen (nur den Artikel „den") — muss bei
        // mehreren laufenden Timern bei der generischen Rueckfrage bleiben statt
        // faelschlich „den" als Namen zu lesen.
        val phrase = fp.handle(TimerIntent.classify("stopp den Timer")!!, Language.DE)

        assertEquals("Es laufen mehrere — welchen soll ich stoppen?", phrase)
        assertEquals(3, store.query().size, "ohne eindeutige Referenz wird nichts gestoppt")
    }

    @Test
    fun `CANCEL alle stoppt alle`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)
        fp.handle(TimerIntent.classify("stell einen Timer auf 20 Minuten")!!, Language.DE)

        val phrase = fp.handle(TimerIntent.classify("alle Timer löschen")!!, Language.DE)
        assertTrue(store.query().isEmpty())
        assertTrue(phrase.contains("alle 2"), "Quittung war: $phrase")
    }

    @Test
    fun `CANCEL mehrere ohne Referenz fragt nach`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)
        fp.handle(TimerIntent.classify("stell einen Timer auf 20 Minuten")!!, Language.DE)

        val phrase = fp.handle(TimerIntent.classify("stopp den Timer")!!, Language.DE)
        assertEquals(2, store.query().size, "ohne eindeutige Referenz wird nichts gestoppt")
        assertTrue(phrase.contains("welchen"), "Rückfrage war: $phrase")
    }

    @Test
    fun `CANCEL ohne laufenden Timer ist ehrlich`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        assertTrue(fp.handle(TimerIntent.classify("stopp den Timer")!!, Language.DE).contains("kein Timer"))
    }

    // ── CANCEL: bereits KLINGELNDE Items (Live-Bug Andi 2026-07-15: ein gefeuerter
    //    Timer hatte den ScheduledItemPort schon verlassen — „stoppe den Timer" fand
    //    ihn nicht, der Klingelton lief einfach weiter) ─────────────────────────────

    @Test
    fun `CANCEL stoppt ein klingelndes Item, obwohl der ScheduledItemPort leer ist (Live-Bug)`() {
        val store = InMemoryScheduledItemStore()
        val ringing = FakeRingingItemPort()
        ringing.ring(RingingItem(id = "fired-1", kind = ScheduledKind.TIMER))
        val fp = fastpath(store, clockAt(6), ringing)

        val phrase = fp.handle(TimerIntent.classify("Stoppe den Timer")!!, Language.DE)

        assertEquals("Gestoppt.", phrase)
        assertTrue(ringing.ringing().isEmpty(), "das klingelnde Item muss quittiert (gestoppt) sein")
    }

    @Test
    fun `CANCEL EN stoppt ein klingelndes Item, obwohl der ScheduledItemPort leer ist (Live-Bug)`() {
        val store = InMemoryScheduledItemStore()
        val ringing = FakeRingingItemPort()
        ringing.ring(RingingItem(id = "fired-1", kind = ScheduledKind.ALARM))
        val fp = fastpath(store, clockAt(6), ringing)

        val phrase = fp.handle(TimerIntent.classify("Stop the alarm")!!, Language.EN)

        assertEquals("Stopped.", phrase)
        assertTrue(ringing.ringing().isEmpty(), "das klingelnde Item muss quittiert (gestoppt) sein")
    }

    @Test
    fun `CANCEL benannt trifft das klingelnde Item, ein zeitgleich geplanter Timer bleibt unangetastet`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        store.set(ScheduledItem(id = "geplant", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 600_000, label = "Pizza"))
        val ringing = FakeRingingItemPort()
        ringing.ring(RingingItem(id = "fired-kaffee", kind = ScheduledKind.TIMER, label = "Kaffee"))
        val fp = fastpath(store, clock, ringing)

        val phrase = fp.handle(TimerIntent.classify("Stoppe den Kaffee-Timer")!!, Language.DE)

        assertEquals("Gestoppt für Kaffee.", phrase)
        assertTrue(ringing.ringing().isEmpty(), "der klingelnde Kaffee-Timer wurde gestoppt")
        assertEquals("Pizza", store.query().single().label, "der geplante Pizza-Timer läuft unbeeindruckt weiter")
    }

    @Test
    fun `CANCEL alle stoppt sowohl geplante als auch klingelnde Items`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        store.set(ScheduledItem(id = "geplant", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 600_000))
        val ringing = FakeRingingItemPort()
        ringing.ring(RingingItem(id = "fired-1", kind = ScheduledKind.TIMER))
        val fp = fastpath(store, clock, ringing)

        val phrase = fp.handle(TimerIntent.classify("alle Timer löschen")!!, Language.DE)

        assertEquals("Okay, alle 2 gestoppt.", phrase)
        assertTrue(store.query().isEmpty())
        assertTrue(ringing.ringing().isEmpty())
    }

    @Test
    fun `CANCEL benannt ohne Treffer listet geplante UND klingelnde Items ehrlich auf`() {
        val store = InMemoryScheduledItemStore()
        val clock = clockAt(6)
        store.set(ScheduledItem(id = "geplant", kind = ScheduledKind.TIMER, dueAtEpochMs = clock.millis() + 600_000, label = "Pizza"))
        val ringing = FakeRingingItemPort()
        ringing.ring(RingingItem(id = "fired-kaffee", kind = ScheduledKind.TIMER, label = "Kaffee"))
        val fp = fastpath(store, clock, ringing)

        val phrase = fp.handle(TimerIntent.classify("Stopp den Tee-Timer")!!, Language.DE)

        assertEquals("Einen Tee-Timer finde ich nicht — gerade laufen: Pizza noch 10 Minuten; Kaffee klingelt gerade.", phrase)
        assertEquals(1, store.query().size, "kein Treffer ⇒ der geplante Pizza-Timer bleibt unangetastet")
        assertEquals(1, ringing.ringing().size, "kein Treffer ⇒ das klingelnde Item bleibt unangetastet")
    }

    @Test
    fun `CANCEL nichts klingelt und nichts ist geplant bleibt ehrlich (Regression)`() {
        val store = InMemoryScheduledItemStore()
        val ringing = FakeRingingItemPort()
        val fp = fastpath(store, clockAt(6), ringing)

        val phrase = fp.handle(TimerIntent.classify("Stoppe den Timer")!!, Language.DE)

        assertEquals("Da läuft gerade kein Timer.", phrase)
    }

    // ── origin (Wecker-Ursprung: Gerät-/Session-Id, die den Wecker STELLTE) ──

    @Test
    fun `SET traegt die uebergebene origin ins angelegte Item`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE, "kueche-tab")
        assertEquals("kueche-tab", store.query().single().origin, "origin = die uebergebene Ursprungs-Id")
    }

    @Test
    fun `SET ohne origin legt origin=null an (byte-neutraler Alt-Pfad)`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        // handle ohne 3. Argument = der heutige Aufruf ⇒ origin bleibt null.
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)
        assertNull(store.query().single().origin, "fehlende Ursprungs-Id ⇒ origin=null")
    }

    // ── originSatelliteId (PREP-wecker-am-satelliten: GETRENNT von origin/FE-deviceId) ──

    @Test
    fun `SET traegt die uebergebene originSatelliteId ins angelegte Item`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(
            TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE,
            origin = null, originSatelliteId = "sat-kueche",
        )
        assertEquals("sat-kueche", store.query().single().originSatelliteId, "originSatelliteId = die uebergebene Satelliten-Id")
        assertNull(store.query().single().origin, "origin bleibt unabhaengig davon null")
    }

    @Test
    fun `SET ohne originSatelliteId legt originSatelliteId=null an (byte-neutraler Alt-Pfad)`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        // handle ohne 4. Argument = der heutige Aufruf (Chat/FE) ⇒ originSatelliteId bleibt null.
        fp.handle(TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE)
        assertNull(store.query().single().originSatelliteId, "fehlende Satelliten-Id ⇒ originSatelliteId=null")
    }

    @Test
    fun `origin und originSatelliteId koennen gleichzeitig und unabhaengig gesetzt sein`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        fp.handle(
            TimerIntent.classify("stell einen Timer auf 10 Minuten")!!, Language.DE,
            origin = "kueche-tab", originSatelliteId = "sat-kueche",
        )
        val item = store.query().single()
        assertEquals("kueche-tab", item.origin)
        assertEquals("sat-kueche", item.originSatelliteId)
    }

    // ── EN-Quittung ──────────────────────────────────────────────────────────

    @Test
    fun `EN set timer quittiert englisch`() {
        val store = InMemoryScheduledItemStore()
        val fp = fastpath(store, clockAt(6))
        val phrase = fp.handle(TimerIntent.classify("set a timer for 10 minutes")!!, Language.EN)
        assertTrue(phrase.contains("timer for 10 minutes"), "EN-Quittung war: $phrase")
    }
}
