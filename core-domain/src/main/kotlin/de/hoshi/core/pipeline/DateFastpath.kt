package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * **DateFastpath** — der brain-freie Vollzug einer eindeutigen Datums-Frage („welcher
 * Tag ist heute?", „welches Datum haben wir?", „what's today's date?"): den heutigen
 * Kalendertag gegen die injizierte Uhr auflösen und eine warme, deterministische
 * deutsche/englische Quittung sprechen. Ruft den Brain NIE.
 *
 * Geschwister zum [CalcFastpath]/[TimerFastpath], aber IN-SITU: weil hierfür kein
 * eigener Intent-Parser + [de.hoshi.core.tools.ToolCall] nötig ist (ein Datum hat
 * keine Slots, keinen Store, kein Gate), trägt dieser Fastpath die Erkennung selbst.
 * [handle] liefert `null`, wenn der Text KEINE Datums-Frage ist ⇒ der Orchestrator
 * fällt unverändert in den normalen Turn (byte-neutral).
 *
 * **Der EINZIGE `now()`-Punkt** ist der injizierte [java.time.Clock] (analog zur
 * Ambient-/Warmth-/Timer-Naht mit `Clock.system(Europe/Berlin)`): [LocalDate.now]
 * gegen die Uhr, dann sprach-getunte Wochentags-/Monatsnamen. Tests setzen
 * `Clock.fixed` ⇒ voll deterministisch. Die reine Erkennung ([isDateQuery]) ist uhrfrei.
 *
 * KONSERVATIV (false-positive-avers): erkennt NUR eindeutige Datums-Fragephrasen
 * (DE+EN, Substring gegen den normalisierten Text) — sonst `null`. So wird kein
 * beiläufiges „heute" oder „date" fälschlich als Datums-Frage gedeutet.
 *
 * [DISABLED] (`enabled = false`) ist der nie-antwortende Default (Flag-OFF): ohne
 * `HOSHI_DATE_FASTPATH_ENABLED` liefert [handle] immer `null`, der Date-Zweig im
 * [TurnOrchestrator] ist tot ⇒ byte-neutral.
 */
class DateFastpath(
    private val clock: Clock = Clock.system(BERLIN),
    /** Flag-OFF-Naht: `false` ⇒ [handle] liefert IMMER `null` (toter Zweig, byte-neutral). */
    private val enabled: Boolean = true,
) {

    /**
     * Erkennt eine eindeutige Datums-Frage und liefert die fertige, sprechbare Quittung;
     * jeder Nicht-Treffer (kein Datums-Wunsch, Flag-OFF, leer) ⇒ `null` (⇒ normaler Turn).
     */
    fun handle(text: String, language: Language): String? {
        if (!enabled || text.isBlank()) return null
        // Uhrzeit VOR Datum: „wie spät ist es" ist die häufigere Frage und darf nicht
        // von einer Datums-Phrase geschluckt werden.
        if (isTimeQuery(text)) return timeReceipt(LocalTime.now(clock), language)
        if (!isDateQuery(text)) return null
        return receipt(LocalDate.now(clock), language)
    }

    /**
     * Ob [text] eine eindeutige UHRZEIT-Frage ist (DE+EN) — reine, uhrfreie Erkennung.
     *
     * Andi-Befund 21.07 abends: „Hoshi sagt mir grad nicht mal mehr die Uhrzeit."
     * Ursache war KEINE Regression, sondern eine Lücke: es gab nie einen Uhrzeit-Pfad.
     * Der Prompt trägt nur einen groben Tageszeit-Eimer ([AmbientWarmth]) UND die
     * Anweisung „Erwähne die Uhrzeit nicht" — das Brain hat also gar keine Uhr und
     * antwortete ehrlich „ich hab keine Uhr". Für einen Sprachassistenten ist das die
     * elementarste Frage überhaupt; sie gehört deterministisch beantwortet, nicht
     * geraten. (Sie steht außerdem als Wake-Word-Beispiel im Video-Drehbuch.)
     *
     * ABGRENZUNG zum Wecker: „wie spät klingelt es?" meint den WECKER, nicht die Uhr
     * (s. [de.hoshi.core.tools.TimerIntent]). Enthält der Text ein Klingel-/Wecker-Wort,
     * greift dieser Pfad NICHT — der Timer-Zweig soll ihn bekommen.
     */
    fun isTimeQuery(text: String): Boolean {
        val norm = normalize(text)
        if (norm.isEmpty()) return false
        if (TIMER_GUARD_WORDS.any { norm.contains(it) }) return false
        return TIME_PHRASES.any { norm.contains(it) }
    }

    /**
     * Deterministische, SPRECHBARE Quittung: DE „Es ist 20 Uhr 15." (nicht „20:15" —
     * das lesen die TTS-Engines unterschiedlich vor), zur vollen Stunde „Es ist 20 Uhr.".
     * EN 12-Stunden-Form: „It's 8:15 pm." / „It's 8 pm.".
     */
    private fun timeReceipt(time: LocalTime, language: Language): String {
        val h = time.hour
        val m = time.minute
        return if (language == Language.EN) {
            val suffix = if (h < 12) "am" else "pm"
            val h12 = when (val x = h % 12) { 0 -> 12; else -> x }
            if (m == 0) "It's $h12 $suffix." else "It's $h12:${m.toString().padStart(2, '0')} $suffix."
        } else {
            if (m == 0) "Es ist $h Uhr." else "Es ist $h Uhr $m."
        }
    }

    /**
     * Ob [text] eine eindeutige Datums-Frage ist (DE+EN) — reine, uhrfreie Erkennung.
     * Normalisiert (lowercase, Apostrophe/Zeichen weg) und prüft konservativ gegen die
     * kuratierten [DATE_PHRASES] (Substring, wie die Geschwister-Intents).
     */
    fun isDateQuery(text: String): Boolean {
        val norm = normalize(text)
        if (norm.isEmpty()) return false
        return DATE_PHRASES.any { norm.contains(it) }
    }

    /** Deterministische Quittung: DE „Heute ist Mittwoch, der 1. Juli 2026." / EN „Today is Wednesday, 1 July 2026.". */
    private fun receipt(date: LocalDate, language: Language): String {
        val weekdayIdx = date.dayOfWeek.value - 1 // 1..7 → 0..6 (Mo..So)
        val monthIdx = date.monthValue - 1 // 1..12 → 0..11
        val day = date.dayOfMonth
        val year = date.year
        return if (language == Language.EN) {
            "Today is ${WEEKDAYS_EN[weekdayIdx]}, $day ${MONTHS_EN[monthIdx]} $year."
        } else {
            "Heute ist ${WEEKDAYS_DE[weekdayIdx]}, der $day. ${MONTHS_DE[monthIdx]} $year."
        }
    }

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (wie [de.hoshi.core.tools.TimerIntent]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /** Zeit-Zone der Default-Uhr — Andis Wohnort (Berlin), überschreibbar per Ctor-`clock`. */
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        /** Nie-antwortender Default (Flag-OFF): der Date-Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = DateFastpath(enabled = false)

        /**
         * Kuratierte Datums-Fragephrasen (DE+EN), gegen den normalisierten Text als
         * Substring geprüft. Bewusst spezifisch (false-positive-avers); längere
         * Varianten sind von den kürzeren Kern-Phrasen mitabgedeckt (z.B. „welches
         * datum haben wir" enthält „welches datum", „what day is it today" enthält
         * „what day is it", „whats todays date" enthält „todays date").
         */
        /**
         * Wörter, die eine „wie spät"-Frage zum WECKER machen statt zur Uhr — dann
         * übernimmt der Timer-Zweig. Konservativ: lieber einmal nicht antworten, als
         * eine Wecker-Frage mit der Uhrzeit zu beantworten.
         */
        private val TIMER_GUARD_WORDS = listOf("klingelt", "wecker", "timer", "alarm", "erinnerung")

        /**
         * Kuratierte UHRZEIT-Fragephrasen (DE+EN), Substring gegen den normalisierten
         * Text. Bewusst spezifisch: das nackte „wie spät" allein steht NICHT drin, weil
         * es in „wie spät klingelt es" auch den Wecker meint (zusätzlich zum Guard oben).
         */
        private val TIME_PHRASES = listOf(
            // DE
            "wie spät ist es", "wie spaet ist es", "wie spät haben wir es",
            "wie viel uhr ist es", "wieviel uhr ist es", "wie viel uhr haben wir",
            "welche uhrzeit", "sag mir die uhrzeit", "wie spät ist s", "wie spaet ist s",
            "hast du die uhrzeit", "weißt du wie spät", "weisst du wie spaet",
            // EN
            "what time is it", "whats the time", "what is the time",
            "tell me the time", "the time now", "do you have the time",
        )

        private val DATE_PHRASES = listOf(
            // DE
            "welcher tag ist heute", "welcher wochentag ist heute",
            "welchen tag haben wir", "was für ein tag ist heute", "was fuer ein tag ist heute",
            "was ist heute für ein tag", "was ist heute fuer ein tag",
            "welches datum", "der wievielte ist heute", "den wievielten haben wir",
            "der wievielte heute", "welchen wochentag haben wir",
            // EN
            "what day is it", "what day is today", "what date is it",
            "whats the date", "what is the date", "todays date", "the date today",
        )

        /** Wochentag DE, indiziert per `dayOfWeek.value - 1` (Mo..So). */
        private val WEEKDAYS_DE = listOf(
            "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag",
        )

        /** Monat DE, indiziert per `monthValue - 1` (Jan..Dez). */
        private val MONTHS_DE = listOf(
            "Januar", "Februar", "März", "April", "Mai", "Juni",
            "Juli", "August", "September", "Oktober", "November", "Dezember",
        )

        /** Wochentag EN, indiziert per `dayOfWeek.value - 1` (Mon..Sun). */
        private val WEEKDAYS_EN = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        )

        /** Monat EN, indiziert per `monthValue - 1` (Jan..Dec). */
        private val MONTHS_EN = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
    }
}
