package de.hoshi.adapters.knowledge

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * **DayReferenceResolver** — erkennt in einer (Wetter-)Frage die referenzierten
 * TAGE und übersetzt sie in Vorhersage-Offsets `0..6` (0 = heute): „morgen" ⇒ 1,
 * „übermorgen" ⇒ 2, ein Wochentag (DE+EN) ⇒ sein NÄCHSTES Vorkommen (heute zählt
 * als 0), „am Wochenende" ⇒ nächster Samstag + nächster Sonntag.
 *
 * KEINE Tages-Referenz in der Frage ⇒ [DayReference.explicit] `false` und die
 * Default-Offsets [DEFAULT_OFFSETS] (= heute+morgen) — EXAKT das bisherige
 * Verhalten des [WeatherGroundingProvider], der immer heute+morgen injizierte.
 *
 * **Der EINZIGE `now()`-Punkt** ist der injizierte [Clock] (Muster
 * `DateFastpath`: Default `Clock.system(Europe/Berlin)`, Tests setzen
 * `Clock.fixed` ⇒ voll deterministisch). Die Erkennung ist TOKEN-basiert auf dem
 * normalisierten Text (Muster `TimerIntent.normalize`) — so matcht „morgen"
 * NICHT im Wort „übermorgen" und „morgens" zählt nicht als „morgen".
 *
 * Bewusst konservativ und wetter-lokal (lebt neben dem Provider, NICHT in
 * core-domain): der `ToolIntentClassifier` und seine WEATHER_MARKERS („morgen"
 * als Wetter-Guard) bleiben unberührt.
 */
class DayReferenceResolver(
    private val clock: Clock = Clock.system(BERLIN),
) {

    /**
     * Ergebnis einer Auflösung: die referenzierten Tages-[offsets] (sortiert,
     * distinct, `0..6`) + ob die Frage die Tage EXPLIZIT nannte ([explicit]
     * `false` ⇒ [offsets] sind die Default-Offsets heute+morgen).
     */
    data class DayReference(val offsets: List<Int>, val explicit: Boolean)

    /**
     * Löst die Tages-Referenzen in [query] auf. Mehrfach-Nennungen („heute und
     * morgen") ergeben die Vereinigungsmenge; ohne Referenz der Default (0,1).
     */
    fun resolve(query: String): DayReference {
        var norm = normalize(query)
        val offsets = sortedSetOf<Int>()
        // Phrase VOR den Tokens: „day after tomorrow" enthält das Token „tomorrow".
        if (norm.contains(DAY_AFTER_TOMORROW)) {
            offsets += 2
            norm = norm.replace(DAY_AFTER_TOMORROW, " ")
        }
        val today = LocalDate.now(clock).dayOfWeek
        for (token in norm.split(' ')) {
            when (token) {
                "heute", "today" -> offsets += 0
                "morgen", "tomorrow" -> offsets += 1
                "übermorgen", "uebermorgen" -> offsets += 2
                "wochenende", "weekend" -> {
                    offsets += offsetTo(today, DayOfWeek.SATURDAY)
                    offsets += offsetTo(today, DayOfWeek.SUNDAY)
                }
                else -> WEEKDAY_TOKENS[token]?.let { offsets += offsetTo(today, it) }
            }
        }
        return if (offsets.isEmpty()) {
            DayReference(DEFAULT_OFFSETS, explicit = false)
        } else {
            DayReference(offsets.toList(), explicit = true)
        }
    }

    /**
     * Präzises deutsches Zeilen-Label für einen Offset: 0/1 bleiben „heute"/
     * „morgen" (wie bisher), ab 2 der Tag beim Namen — „am Donnerstag (in 4
     * Tagen)" — damit der Brain den gefragten Tag benennen kann.
     */
    fun dayLabel(offset: Int): String = when (offset) {
        0 -> "heute"
        1 -> "morgen"
        else -> {
            val day = LocalDate.now(clock).plusDays(offset.toLong())
            "am ${WEEKDAYS_DE[day.dayOfWeek.value - 1]} (in $offset Tagen)"
        }
    }

    /** Nächstes Vorkommen von [target] ab [today] — heute zählt als 0. */
    private fun offsetTo(today: DayOfWeek, target: DayOfWeek): Int =
        ((target.value - today.value) % 7 + 7) % 7

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (Muster `DateFastpath`). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /** Zeit-Zone der Default-Uhr — Andis Wohnort (Muster `DateFastpath.BERLIN`). */
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        /** Ohne Tages-Referenz: heute+morgen — das bisherige Provider-Verhalten. */
        val DEFAULT_OFFSETS: List<Int> = listOf(0, 1)

        private const val DAY_AFTER_TOMORROW = "day after tomorrow"

        /** Wochentag DE, indiziert per `dayOfWeek.value - 1` (Mo..So). */
        private val WEEKDAYS_DE = listOf(
            "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag",
        )

        /** Wochentags-Tokens DE+EN (inkl. „sonnabend") → [DayOfWeek]. */
        private val WEEKDAY_TOKENS: Map<String, DayOfWeek> = mapOf(
            "montag" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
            "dienstag" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
            "mittwoch" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "donnerstag" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
            "freitag" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
            "samstag" to DayOfWeek.SATURDAY, "sonnabend" to DayOfWeek.SATURDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sonntag" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
        )
    }
}
