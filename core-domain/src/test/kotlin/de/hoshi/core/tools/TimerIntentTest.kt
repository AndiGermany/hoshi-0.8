package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die deterministische, uhrfreie Timer-Erkennung [TimerIntent]:
 *  - Dauer-/Uhrzeit-Parsing (DE+EN, Randfälle),
 *  - Klassifikation SET/QUERY/CANCEL inkl. kind-Ableitung + Label,
 *  - konservativ: nur bei eindeutigem Intent, mehrdeutig/Negation ⇒ null.
 */
class TimerIntentTest {

    // ── Dauer-Parsing (relativ, uhrfrei) ─────────────────────────────────────

    @Test
    fun `DE 10 Minuten ergibt 600 Sekunden`() {
        val slot = TimerIntent.parseSlot("in 10 Minuten")!!
        assertEquals(TimerIntent.Slot.Mode.DURATION, slot.mode)
        assertEquals(600L, slot.durationSeconds)
    }

    @Test
    fun `DE 1 Stunde 30 ergibt 5400 Sekunden (Kombi-Tail)`() {
        assertEquals(5400L, TimerIntent.parseSlot("1 Stunde 30")!!.durationSeconds)
    }

    @Test
    fun `DE halbe Stunde ergibt 1800 Sekunden`() {
        assertEquals(1800L, TimerIntent.parseSlot("in einer halben Stunde")!!.durationSeconds)
    }

    @Test
    fun `DE anderthalb Stunden ergibt 5400 Sekunden`() {
        assertEquals(5400L, TimerIntent.parseSlot("anderthalb Stunden")!!.durationSeconds)
    }

    @Test
    fun `DE 30 Sekunden ergibt 30 Sekunden`() {
        assertEquals(30L, TimerIntent.parseSlot("30 Sekunden")!!.durationSeconds)
    }

    @Test
    fun `EN ten minutes ergibt 600 Sekunden`() {
        assertEquals(600L, TimerIntent.parseSlot("ten minutes")!!.durationSeconds)
    }

    @Test
    fun `EN half an hour ergibt 1800 Sekunden`() {
        assertEquals(1800L, TimerIntent.parseSlot("half an hour")!!.durationSeconds)
    }

    @Test
    fun `EN an hour and a half ergibt 5400 Sekunden`() {
        assertEquals(5400L, TimerIntent.parseSlot("an hour and a half")!!.durationSeconds)
    }

    @Test
    fun `EN 5 mins ergibt 300 Sekunden`() {
        assertEquals(300L, TimerIntent.parseSlot("5 mins")!!.durationSeconds)
    }

    // ── Uhrzeit-Parsing (HH:MM, uhrfrei — keine Tag-Auflösung hier) ───────────

    @Test
    fun `DE um 7 ergibt 07 00`() {
        val slot = TimerIntent.parseSlot("um 7")!!
        assertEquals(TimerIntent.Slot.Mode.CLOCK, slot.mode)
        assertEquals(7, slot.clockHour)
        assertEquals(0, slot.clockMinute)
    }

    @Test
    fun `DE um 18 30 ergibt 18 30`() {
        val slot = TimerIntent.parseSlot("um 18:30")!!
        assertEquals(18, slot.clockHour)
        assertEquals(30, slot.clockMinute)
    }

    @Test
    fun `DE halb acht ergibt 07 30`() {
        val slot = TimerIntent.parseSlot("halb acht")!!
        assertEquals(7, slot.clockHour)
        assertEquals(30, slot.clockMinute)
    }

    @Test
    fun `DE Punkt-Uhrzeit 22 57 ergibt 22 57 (deutsche Schreibweise, Live-Bug 2026-07-03)`() {
        val slot = TimerIntent.parseSlot("stelle einen wecker auf 22.57 uhr")!!
        assertEquals(TimerIntent.Slot.Mode.CLOCK, slot.mode)
        assertEquals(22, slot.clockHour)
        assertEquals(57, slot.clockMinute)
    }

    @Test
    fun `DE Doppelpunkt bleibt gleichwertig zum Punkt`() {
        assertEquals(22, TimerIntent.parseSlot("wecker auf 22:57")!!.clockHour)
        assertEquals(57, TimerIntent.parseSlot("wecker auf 22:57")!!.clockMinute)
    }

    @Test
    fun `DE Minute die gleichzeitig gueltige Stunde ist wird NICHT als Stunde gegriffen (23 11 Uhr)`() {
        // Regressions-Guard: „auf 23.11 Uhr" wurde als 11:00 fehl-geparst (Muster-Reihenfolge).
        val s = TimerIntent.parseSlot("stelle einen wecker auf 23.11 uhr")!!
        assertEquals(23, s.clockHour)
        assertEquals(11, s.clockMinute)
        val c = TimerIntent.parseSlot("weck mich auf 20:15 uhr")!!
        assertEquals(20, c.clockHour); assertEquals(15, c.clockMinute)
    }

    @Test
    fun `DE H uhr MM ohne Doppelpunkt bleibt intakt (7 uhr 30)`() {
        val s = TimerIntent.parseSlot("weck mich 7 uhr 30")!!
        assertEquals(7, s.clockHour); assertEquals(30, s.clockMinute)
        assertEquals(8, TimerIntent.parseSlot("weck mich 8 uhr")!!.clockHour)
    }

    @Test
    fun `DE viertel vor acht ergibt 07 45`() {
        val slot = TimerIntent.parseSlot("viertel vor acht")!!
        assertEquals(7, slot.clockHour)
        assertEquals(45, slot.clockMinute)
    }

    @Test
    fun `EN at 7 ergibt 07 00`() {
        val slot = TimerIntent.parseSlot("at 7")!!
        assertEquals(7, slot.clockHour)
        assertEquals(0, slot.clockMinute)
    }

    @Test
    fun `EN at 7 pm ergibt 19 00`() {
        val slot = TimerIntent.parseSlot("at 7 pm")!!
        assertEquals(19, slot.clockHour)
    }

    @Test
    fun `morgen setzt forceTomorrow`() {
        assertTrue(TimerIntent.parseSlot("morgen um 7")!!.forceTomorrow)
        assertEquals(false, TimerIntent.parseSlot("um 7")!!.forceTomorrow)
    }

    // ── Klassifikation: SET ──────────────────────────────────────────────────

    @Test
    fun `SET Timer auf 10 Minuten`() {
        val call = TimerIntent.classify("stell einen Timer auf 10 Minuten")!!
        assertEquals(TimerIntent.DOMAIN, call.domain)
        assertEquals(TimerIntent.SET, call.service)
        assertEquals(TimerIntent.KIND_TIMER, call.data["kind"])
        assertEquals(600L, call.data["durationSeconds"])
    }

    @Test
    fun `SET Wecker um 7 leitet ALARM und Uhrzeit ab`() {
        val call = TimerIntent.classify("weck mich um 7")!!
        assertEquals(TimerIntent.SET, call.service)
        assertEquals(TimerIntent.KIND_ALARM, call.data["kind"])
        assertEquals(7, call.data["clockHour"])
        assertEquals(0, call.data["clockMinute"])
    }

    @Test
    fun `SET Timer mit Uhrzeit wird ALARM (CLOCK-Slot)`() {
        // „Timer um 18:30" — Timer-Wort, aber Uhrzeit ⇒ kind ALARM (slot CLOCK).
        val call = TimerIntent.classify("stell einen Timer um 18:30")!!
        assertEquals(TimerIntent.KIND_ALARM, call.data["kind"])
        assertEquals(18, call.data["clockHour"])
    }

    @Test
    fun `SET Erinnerung mit Label`() {
        val call = TimerIntent.classify("erinnere mich in 5 Minuten an die Pizza")!!
        assertEquals(TimerIntent.SET, call.service)
        assertEquals(TimerIntent.KIND_REMINDER, call.data["kind"])
        assertEquals(300L, call.data["durationSeconds"])
        assertEquals("Pizza", call.data["label"])
    }

    @Test
    fun `SET Timer ohne Namen traegt kein Label (Anlage-Pfad unveraendert)`() {
        val call = TimerIntent.classify("stell einen Timer auf 10 Minuten")!!
        assertNull(call.data["label"], "generischer Timer ohne Kompositum-Namen bleibt unbenannt")
    }

    @Test
    fun `SET Timer mit Namen (Nudel-Timer) traegt das Label aus dem Kompositum`() {
        val call = TimerIntent.classify("stell einen Nudel-Timer auf 10 Minuten")!!
        assertEquals(TimerIntent.SET, call.service)
        assertEquals(TimerIntent.KIND_TIMER, call.data["kind"])
        assertEquals(600L, call.data["durationSeconds"])
        assertEquals("Nudel", call.data["label"])
    }

    @Test
    fun `SET Timer mit Namen (Pizza-Timer) traegt das Label`() {
        val call = TimerIntent.classify("stell einen Pizza-Timer auf 20 Minuten")!!
        assertEquals("Pizza", call.data["label"])
    }

    @Test
    fun `SET Kuechentimer (generisches Kompositum ohne Bindestrich) bleibt unbenannt`() {
        // „Küchentimer" ist ein generischer TIMER_TRIGGERS-Begriff („irgendein Timer"),
        // KEIN Eigenname — ohne Bindestrich darf extractTimerLabel nicht zuschlagen.
        val call = TimerIntent.classify("stell einen Küchentimer auf 10 Minuten")!!
        assertNull(call.data["label"])
    }

    @Test
    fun `EN set a timer for 10 minutes`() {
        val call = TimerIntent.classify("set a timer for 10 minutes")!!
        assertEquals(TimerIntent.KIND_TIMER, call.data["kind"])
        assertEquals(600L, call.data["durationSeconds"])
    }

    @Test
    fun `EN wake me at 7`() {
        val call = TimerIntent.classify("wake me at 7")!!
        assertEquals(TimerIntent.KIND_ALARM, call.data["kind"])
        assertEquals(7, call.data["clockHour"])
    }

    @Test
    fun `EN remind me in 5 minutes to call mom`() {
        val call = TimerIntent.classify("remind me in 5 minutes to call mom")!!
        assertEquals(TimerIntent.KIND_REMINDER, call.data["kind"])
        assertEquals(300L, call.data["durationSeconds"])
        assertEquals("call mom", call.data["label"])
    }

    // ── Klassifikation: QUERY ────────────────────────────────────────────────

    @Test
    fun `QUERY DE wie lange laeuft der Timer noch`() {
        val call = TimerIntent.classify("wie lange läuft der Timer noch")!!
        assertEquals(TimerIntent.QUERY, call.service)
    }

    @Test
    fun `QUERY EN which timers are running`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("which timers are running")!!.service)
    }

    // ── QUERY-Golden-Set (Live-Befund Andi 2026-07-06: „Wie lange geht der Timer
    //    noch?" fiel ans Brain ⇒ Gegenfrage statt deterministischer Store-Antwort) ──

    @Test
    fun `QUERY DE wie lange geht der Timer noch (Live-Befund)`() {
        val call = TimerIntent.classify("Wie lange geht der Timer noch?")!!
        assertEquals(TimerIntent.QUERY, call.service)
        assertNull(call.data[TimerIntent.KIND_HINT], "Timer-Frage traegt keinen ALARM-Hint")
    }

    @Test
    fun `QUERY DE wie lange laeuft der Timer (ohne noch)`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("Wie lange läuft der Timer?")!!.service)
    }

    @Test
    fun `QUERY DE laeuft ein Timer`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("Läuft ein Timer?")!!.service)
    }

    @Test
    fun `QUERY DE laeuft gerade ein Timer`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("Läuft gerade ein Timer?")!!.service)
    }

    @Test
    fun `QUERY DE laeuft gerade ein Wecker traegt ALARM-Hint`() {
        val call = TimerIntent.classify("Läuft gerade ein Wecker?")!!
        assertEquals(TimerIntent.QUERY, call.service)
        assertEquals(TimerIntent.KIND_ALARM, call.data[TimerIntent.KIND_HINT])
    }

    @Test
    fun `QUERY DE wann klingelt der Wecker traegt ALARM-Hint`() {
        val call = TimerIntent.classify("Wann klingelt der Wecker?")!!
        assertEquals(TimerIntent.QUERY, call.service)
        assertEquals(TimerIntent.KIND_ALARM, call.data[TimerIntent.KIND_HINT])
    }

    @Test
    fun `QUERY DE wann klingelt der Timer ohne ALARM-Hint`() {
        val call = TimerIntent.classify("Wann klingelt der Timer?")!!
        assertEquals(TimerIntent.QUERY, call.service)
        assertNull(call.data[TimerIntent.KIND_HINT])
    }

    @Test
    fun `QUERY DE wie spaet klingelt es`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("Wie spät klingelt es?")!!.service)
    }

    @Test
    fun `QUERY EN how long does the timer have left`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("How long does the timer have left?")!!.service)
    }

    @Test
    fun `QUERY EN how much longer is the timer running`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("How much longer is the timer running?")!!.service)
    }

    @Test
    fun `QUERY EN is a timer running`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("Is a timer running?")!!.service)
    }

    @Test
    fun `QUERY EN is an alarm set traegt ALARM-Hint`() {
        val call = TimerIntent.classify("Is an alarm set?")!!
        assertEquals(TimerIntent.QUERY, call.service)
        assertEquals(TimerIntent.KIND_ALARM, call.data[TimerIntent.KIND_HINT])
    }

    @Test
    fun `QUERY EN when does the alarm ring traegt ALARM-Hint`() {
        val call = TimerIntent.classify("When does the alarm ring?")!!
        assertEquals(TimerIntent.QUERY, call.service)
        assertEquals(TimerIntent.KIND_ALARM, call.data[TimerIntent.KIND_HINT])
    }

    @Test
    fun `QUERY EN what time does it ring`() {
        assertEquals(TimerIntent.QUERY, TimerIntent.classify("What time does it ring?")!!.service)
    }

    // ── QUERY bleibt konservativ: Wissensfragen OHNE Timer-Wort gehen ans Brain ──

    @Test
    fun `Wissensfrage wie lange dauert Pasta kochen bleibt beim Brain`() {
        assertNull(TimerIntent.classify("wie lange dauert Pasta kochen"))
        assertNull(TimerIntent.classify("wie lange dauert der Flug nach Tokio"))
        assertNull(TimerIntent.classify("how long does pasta take"))
    }

    @Test
    fun `laeuft der Film schon ist kein Timer`() {
        assertNull(TimerIntent.classify("Läuft der Film schon?"))
    }

    // ── Klassifikation: CANCEL ───────────────────────────────────────────────

    @Test
    fun `CANCEL DE stopp den Timer (nicht all)`() {
        val call = TimerIntent.classify("stopp den Timer")!!
        assertEquals(TimerIntent.CANCEL, call.service)
        assertEquals(false, call.data["all"])
    }

    @Test
    fun `CANCEL DE alle Timer loeschen (all)`() {
        val call = TimerIntent.classify("alle Timer löschen")!!
        assertEquals(TimerIntent.CANCEL, call.service)
        assertEquals(true, call.data["all"])
    }

    @Test
    fun `CANCEL EN cancel all timers (all)`() {
        val call = TimerIntent.classify("cancel all timers")!!
        assertEquals(TimerIntent.CANCEL, call.service)
        assertEquals(true, call.data["all"])
    }

    @Test
    fun `CANCEL Stopp-Verb plus Nomen faengt Pizza-Timer`() {
        val call = TimerIntent.classify("brich den Pizza-Timer ab")!!
        assertEquals(TimerIntent.CANCEL, call.service)
        assertTrue((call.data["text"] as String).contains("pizza"))
    }

    // ── Konservativ: mehrdeutig / kein Timer / Negation ⇒ null ───────────────

    @Test
    fun `SET ohne Zeit ist mehrdeutig ergibt null`() {
        assertNull(TimerIntent.classify("stell einen Timer"))
    }

    @Test
    fun `kein Timer-Wort ergibt null`() {
        assertNull(TimerIntent.classify("mach das Licht aus"))
        assertNull(TimerIntent.classify("wie warm ist es"))
    }

    @Test
    fun `Negation ergibt null`() {
        assertNull(TimerIntent.classify("erinnere mich nicht"))
        assertNull(TimerIntent.classify("stell keinen Timer"))
    }
}
