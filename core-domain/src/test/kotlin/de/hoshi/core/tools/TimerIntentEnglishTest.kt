package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **Der Wecker versteht Englisch.** Andi-Befund 2026-07-25: die Multilingualitäts-
 * Runde hat die ANTWORTEN in fünf Sprachen gebracht, die ERKENNER aber nicht —
 * Hoshi sprach englisch und verstand deutsch. Für die Online-Bitte ist das
 * bereits geheilt ([de.hoshi.core.pipeline.OnlineRequestDetector]); diese Datei
 * ist dieselbe Heilung für Timer/Wecker.
 *
 * **Gemessen, nicht geraten** (Stand VOR dieser Runde): „set a timer for ten
 * minutes" ging bereits, „set an alarm for seven" / „for 7 pm" / „quarter past
 * seven" / „a couple of minutes" fielen alle auf `null` — nicht still falsch,
 * aber unerkannt (⇒ normaler Turn ⇒ das Brain orakelte). Genau diese Lücke
 * schließen die Fälle hier.
 *
 * Die deutsche Seite ist NICHT Gegenstand dieser Datei — sie steht unverändert in
 * [TimerIntentTest] und wird zusätzlich von [IntentGermanByteIdentityTest]
 * vektorweise festgenagelt.
 */
class TimerIntentEnglishTest {

    // ── Hilfen ───────────────────────────────────────────────────────────────

    private fun set(text: String): Map<String, Any?> {
        val call = TimerIntent.classify(text)
        assertNotNull(call, "nicht erkannt: $text")
        assertEquals(TimerIntent.SET, call!!.service, "kein SET: $text")
        return call.data
    }

    private fun assertDuration(text: String, seconds: Long) =
        assertEquals(seconds, (set(text)["durationSeconds"] as? Number)?.toLong(), "Dauer von: $text")

    private fun assertClock(text: String, hour: Int, minute: Int) {
        val data = set(text)
        assertEquals(hour to minute, (data["clockHour"] as? Number)?.toInt() to (data["clockMinute"] as? Number)?.toInt(), "Uhrzeit von: $text")
    }

    private fun assertService(text: String, service: String) =
        assertEquals(service, TimerIntent.classify(text)?.service, "Dienst von: $text")

    private fun assertNoTimerIntent(text: String) =
        assertNull(TimerIntent.classify(text), "hätte NICHT zünden dürfen: $text")

    // ── SET: Dauern ──────────────────────────────────────────────────────────

    @Test
    fun `englische Dauern in Ziffern und Worten`() {
        assertDuration("set a timer for ten minutes", 600)
        assertDuration("set a timer for 10 minutes", 600)
        assertDuration("set a timer for 20 seconds", 20)
        assertDuration("start a timer for 1 hour", 3600)
        assertDuration("set a timer for 90 minutes", 5400)
    }

    @Test
    fun `halbe und anderthalb Stunden`() {
        assertDuration("set a timer for half an hour", 1800)
        assertDuration("set a timer for an hour and a half", 5400)
        assertDuration("set a timer for a quarter of an hour", 900)
        assertDuration("set a timer for two and a half hours", 9000)
        assertDuration("set a timer for one and a half minutes", 90)
    }

    @Test
    fun `a couple of ist zwei - a few bleibt bewusst unerkannt`() {
        assertDuration("set a timer for a couple of minutes", 120)
        assertDuration("set a timer for a couple of hours", 7200)
        // GRENZE: „a few" ist unbestimmt (3? 4? 5?). Jede Zahl wäre geraten —
        // also lieber gar nicht erkennen: der Satz fällt in den normalen Turn,
        // und das Brain fragt nach. Ehrlich statt still falsch.
        assertNoTimerIntent("set a timer for a few minutes")
    }

    // ── SET: Uhrzeiten ───────────────────────────────────────────────────────

    @Test
    fun `wecker auf eine volle stunde - for und at und o clock`() {
        assertClock("set an alarm for seven", 7, 0)
        assertClock("set an alarm for 7", 7, 0)
        assertClock("set an alarm at seven", 7, 0)
        assertClock("set an alarm for 6 o'clock", 6, 0)
        assertClock("wake me at 7 in the morning", 7, 0)
    }

    @Test
    fun `am und pm verschieben die tageshaelfte`() {
        assertClock("set an alarm for 7 am", 7, 0)
        assertClock("set an alarm for 7 pm", 19, 0)
        assertClock("set an alarm for 7:30", 7, 30)
        assertClock("set an alarm for 7:30 pm", 19, 30)
        assertClock("set an alarm for 12 am", 0, 0)
        assertClock("set an alarm for 12 pm", 12, 0)
        assertClock("wake me at 7 in the evening", 19, 0)
    }

    /**
     * Die Richtung ist der gefährliche Teil: „past" addiert, „to" zieht von der
     * NÄCHSTEN Stunde ab. Deshalb steht hier jede Richtung einzeln — inklusive
     * des Stunden-Übergangs („quarter to midnight" = 23:45, NICHT 00:00; der
     * Anker „midnight" hätte das sonst verschluckt).
     */
    @Test
    fun `quarter und half past und to rechnen in die richtige richtung`() {
        assertClock("set an alarm for quarter past seven", 7, 15)
        assertClock("set an alarm for quarter to eight", 7, 45)
        assertClock("set an alarm for half past seven", 7, 30)
        assertClock("set an alarm for half past twelve", 12, 30)
        assertClock("set an alarm for twenty past six", 6, 20)
        assertClock("set an alarm for ten to eight", 7, 50)
        assertClock("set an alarm for quarter to midnight", 23, 45)
        assertClock("set an alarm for quarter past noon", 12, 15)
    }

    /**
     * Die deutsche und die englische Halb-Lesart sind GEGENLÄUFIG: „halb acht" =
     * 07:30 (vor der Vollstunde), „half past seven" = 07:30 (nach ihr). Beide
     * müssen gleichzeitig stimmen, sonst geht der Wecker eine Stunde daneben.
     */
    @Test
    fun `halb acht und half past seven treffen beide 07 30`() {
        assertClock("weck mich um halb acht", 7, 30)
        assertClock("set an alarm for half past seven", 7, 30)
        assertClock("set an alarm for half past eight", 8, 30)
    }

    @Test
    fun `tomorrow setzt den morgen-marker`() {
        assertEquals(true, set("set an alarm for seven tomorrow")["clockForceTomorrow"])
        assertEquals(true, set("set an alarm for 8 o'clock tomorrow")["clockForceTomorrow"])
        assertEquals(false, set("set an alarm for seven")["clockForceTomorrow"])
    }

    @Test
    fun `wecker-satz wird als ALARM angelegt - timer-satz als TIMER`() {
        assertEquals(TimerIntent.KIND_ALARM, set("set an alarm for seven")["kind"])
        assertEquals(TimerIntent.KIND_TIMER, set("set a timer for ten minutes")["kind"])
        assertEquals(TimerIntent.KIND_REMINDER, set("remind me in 10 minutes to call mum")["kind"])
    }

    // ── QUERY / CANCEL ───────────────────────────────────────────────────────

    @Test
    fun `englische restzeit-fragen`() {
        assertService("how long is left", TimerIntent.QUERY)
        assertService("how long left", TimerIntent.QUERY)
        assertService("how much time is left", TimerIntent.QUERY)
        assertService("what's left on the timer", TimerIntent.QUERY)
        assertService("is a timer running", TimerIntent.QUERY)
        assertService("when does the alarm go off", TimerIntent.QUERY)
    }

    @Test
    fun `englische stopp-befehle`() {
        assertService("stop the timer", TimerIntent.CANCEL)
        assertService("cancel the timer", TimerIntent.CANCEL)
        assertService("delete the alarm", TimerIntent.CANCEL)
        assertEquals(true, TimerIntent.classify("stop all timers")?.data?.get("all"))
        assertEquals(true, TimerIntent.classify("cancel all alarms")?.data?.get("all"))
    }

    // ── Gegen-Tests: „set" ist ein Alltagswort ────────────────────────────────

    /**
     * „set" steht in Dutzenden harmloser Sätze. Ein Timer-/Wecker-Wort ist deshalb
     * PFLICHT (die konservative 0.5-Regel, hier für Englisch nachgewiesen) — und
     * eine bloße Zahl hinter „for" reicht nicht.
     */
    @Test
    fun `set ohne timer-kontext zuendet nie`() {
        assertNoTimerIntent("I need to set the table")
        assertNoTimerIntent("set the volume to ten")
        assertNoTimerIntent("let's set a date for seven people")
        assertNoTimerIntent("set the oven to 200 degrees")
        assertNoTimerIntent("time flies")
        assertNoTimerIntent("what time is it")
        assertNoTimerIntent("how long is the movie")
    }

    /**
     * GRENZE, bewusst gezogen: eine nackte Zahl hinter „for" ist NUR im Wecker-
     * Kontext eine Uhrzeit. „set a timer for 7" ist eine Dauer OHNE Einheit
     * (7 was?) — unerkannt lassen und zurückfragen ist ehrlicher, als still
     * einen Wecker auf 07:00 zu stellen.
     */
    @Test
    fun `for plus zahl ist nur im wecker-kontext eine uhrzeit`() {
        assertNoTimerIntent("set a timer for 7")
        assertClock("set an alarm for 7", 7, 0)
    }

    /**
     * Die Stunde muss die Aussage abschließen — sonst wäre „set an alarm for 5
     * people" still ein Wecker um 05:00. Bekannte Nachsilben (am/pm/o'clock/
     * tomorrow/please …) sind erlaubt, ein beliebiges Nomen nicht.
     */
    @Test
    fun `for plus zahl plus nomen ist kein wecker`() {
        assertNoTimerIntent("set an alarm for 5 people")
        assertNoTimerIntent("set an alarm for the meeting")
        assertClock("set an alarm for 7 am please", 7, 0)
    }

    @Test
    fun `negation schaltet auch auf englisch ab`() {
        assertNoTimerIntent("don't set a timer for ten minutes")
        assertNoTimerIntent("no timer please")
    }
}
