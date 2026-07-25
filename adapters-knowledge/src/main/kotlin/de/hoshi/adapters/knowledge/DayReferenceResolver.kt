package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.Language
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
     * Präzises Zeilen-Label für einen Offset IN DER TURN-SPRACHE: 0/1 bleiben
     * „heute"/„morgen" (bzw. „today"/„tomorrow", …), ab 2 der Tag beim Namen —
     * „am Donnerstag (in 4 Tagen)" / „on Thursday (in 4 days)" — damit der Brain
     * den gefragten Tag benennen kann.
     *
     * **[language] ist Pflicht** (kein Default): dieses Label steht MITTEN in der
     * Datenzeile des Wetter-Blocks — ein deutsches „heute" in einer englischen
     * Zeile ist genau der halbe Sprachwechsel, den die Sprach-Naht beseitigt.
     * DE bleibt zeichengleich zum Stand davor (Pin-Tests).
     */
    fun dayLabel(offset: Int, language: Language): String = when (offset) {
        0 -> when (language) {
            Language.DE -> "heute"
            Language.EN -> "today"
            Language.ES -> "hoy"
            Language.FR -> "aujourd'hui"
            Language.IT -> "oggi"
        }
        1 -> when (language) {
            Language.DE -> "morgen"
            Language.EN -> "tomorrow"
            Language.ES -> "mañana"
            Language.FR -> "demain"
            Language.IT -> "domani"
        }
        else -> {
            val day = LocalDate.now(clock).plusDays(offset.toLong())
            val name = weekdayNames(language)[day.dayOfWeek.value - 1]
            when (language) {
                Language.DE -> "am $name (in $offset Tagen)"
                Language.EN -> "on $name (in $offset days)"
                Language.ES -> "el $name (en $offset días)"
                Language.FR -> "$name (dans $offset jours)"
                Language.IT -> "$name (tra $offset giorni)"
            }
        }
    }

    /**
     * Wochentags-Namen (Mo..So, indiziert per `dayOfWeek.value - 1`) je Sprache —
     * exhaustives `when` OHNE `else`: eine sechste [Language] bricht den Build,
     * statt still deutsche Wochentage in einen fremdsprachigen Block zu schreiben.
     */
    private fun weekdayNames(language: Language): List<String> = when (language) {
        Language.DE -> WEEKDAYS_DE
        Language.EN -> WEEKDAYS_EN
        Language.ES -> WEEKDAYS_ES
        Language.FR -> WEEKDAYS_FR
        Language.IT -> WEEKDAYS_IT
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

        /**
         * Wochentag DE, indiziert per `dayOfWeek.value - 1` (Mo..So) —
         * BYTE-EINGEFROREN (Pin-Tests des Wetter-Blocks hängen daran).
         */
        private val WEEKDAYS_DE = listOf(
            "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag",
        )

        /**
         * Dieselben sieben Tage in den vier weiteren Turn-Sprachen (Sprach-Naht
         * 2026-07-25). Bewusst HART hinterlegt statt über `Locale`/CLDR: der
         * Wetter-Block ist ein Prompt-Baustein, dessen Wortlaut wir kontrollieren
         * (und dessen DE-Fassung Pin-Tests trägt) — eine JDK-/CLDR-Aktualisierung
         * darf ihn nie unbemerkt umschreiben.
         */
        private val WEEKDAYS_EN = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        )
        private val WEEKDAYS_ES = listOf(
            "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo",
        )
        private val WEEKDAYS_FR = listOf(
            "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche",
        )
        private val WEEKDAYS_IT = listOf(
            "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica",
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
