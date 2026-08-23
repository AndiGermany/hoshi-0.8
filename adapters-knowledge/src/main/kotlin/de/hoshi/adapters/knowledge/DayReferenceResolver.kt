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
     *
     * **[nowFocus]** (Auftrag 2b, Andi-Livetest 2026-08-21): die Frage zielt auf
     * den AUGENBLICK, nicht auf die Tagesspanne — entweder explizit („gerade",
     * „aktuell", „im Moment", „right now", …) oder implizit, weil GAR KEIN Tag
     * genannt wurde („Wie ist das Wetter?"). Der [WeatherGroundingProvider] stellt
     * dann die JETZT-Zeile (`current.temperature_2m`/`weathercode`) VOR die
     * Tages-Zeilen. Befund, der das erzwingt: Andi fragte „wie ist grad das
     * Wetter", bekam „15-irgendwas Grad und Regen" — das waren die TAGES-Werte
     * (Spanne + Tages-Niederschlagssumme eines Tages, an dem es FRÜHER geregnet
     * hatte), während es in dem Moment trocken war. Der `current`-Node wurde
     * abgerufen und im Grounding-Pfad komplett verworfen.
     *
     * **[weekend]**: „am Wochenende"/„fin de semana"/… war das auslösende Wort —
     * der Block hängt dann die Zusammenfass-Anweisung an (Sa+So als EIN Bild).
     *
     * **[beyondHorizon]**: die Frage nannte einen Tag JENSEITS der
     * [WeatherGroundingProvider.FORECAST_DAYS]-Reichweite („nächsten Samstag",
     * „nächste Woche", „in 10 Tagen"). [offsets] ist dann leer und der Provider
     * liefert den ehrlichen „so weit reicht mein Ausblick nicht"-Block statt
     * irgendwelche Tage zu raten.
     */
    data class DayReference(
        val offsets: List<Int>,
        val explicit: Boolean,
        val nowFocus: Boolean = false,
        val weekend: Boolean = false,
        val beyondHorizon: Boolean = false,
    )

    /**
     * Löst die Tages-Referenzen in [query] auf. Mehrfach-Nennungen („heute und
     * morgen") ergeben die Vereinigungsmenge; ohne Referenz der Default (0,1).
     *
     * **Reihenfolge ist Absicht:** Mehrwort-Phrasen ([PHRASES], z.B. „day after
     * tomorrow", „pasado mañana", „fin de semana") werden VOR der Token-Schleife
     * konsumiert, weil sie die Einzel-Tokens enthalten, die sie überstimmen
     * („tomorrow" in „day after tomorrow", „mañana" in „pasado mañana"). Danach
     * die Horizont-Qualifizierer („nächsten <Wochentag>", „nächste Woche", „in N
     * Tagen") und erst zuletzt die nackten Tokens.
     */
    fun resolve(query: String): DayReference {
        var norm = normalize(query)
        val offsets = sortedSetOf<Int>()
        var weekend = false
        var beyond = false
        val today = LocalDate.now(clock).dayOfWeek

        // 1) Mehrwort-Phrasen zuerst — und aus dem Text nehmen, damit ihre
        //    Einzel-Tokens nicht ZUSÄTZLICH als eigener Tag zählen.
        for ((phrase, resolvedOffset) in PHRASES) {
            if (norm.contains(phrase)) {
                offsets += resolvedOffset
                norm = norm.replace(phrase, " ")
            }
        }
        for (phrase in WEEKEND_PHRASES) {
            if (norm.contains(phrase)) {
                weekend = true
                norm = norm.replace(phrase, " ")
            }
        }

        // 2) Horizont-Qualifizierer: „nächste Woche", „nächsten Samstag", „in 10
        //    Tagen" zeigen NACHWEISLICH über die Sieben-Tage-Reichweite hinaus.
        //    Ein qualifizierter Wochentag liegt IMMER jenseits: das nächste
        //    Vorkommen ist 0..6, das übernächste damit 7..13 — nie mehr im Fenster.
        if (NEXT_WEEK_PATTERN.containsMatchIn(norm)) beyond = true
        // Matches ERST einsammeln, dann ersetzen: `findAll` iteriert über den String,
        // den es beim Aufruf bekommen hat — innerhalb der Schleife an `norm` zu
        // schreiben wäre stille Iteration über eine veraltete Fassung.
        val qualifiedWeekdays = NEXT_WEEKDAY_PATTERN.findAll(norm)
            .filter { WEEKDAY_TOKENS.containsKey(it.groupValues[2]) }
            .map { it.value }
            .toList()
        if (qualifiedWeekdays.isNotEmpty()) {
            beyond = true
            qualifiedWeekdays.forEach { norm = norm.replace(it, " ") }
        }
        for (m in IN_N_DAYS_PATTERN.findAll(norm)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n > MAX_OFFSET) beyond = true else offsets += n
        }

        // 3) Nackte Tokens.
        for (token in norm.split(' ')) {
            when (token) {
                in TODAY_TOKENS -> offsets += 0
                in TOMORROW_TOKENS -> offsets += 1
                in DAY_AFTER_TOMORROW_TOKENS -> offsets += 2
                in WEEKEND_TOKENS -> weekend = true
                else -> WEEKDAY_TOKENS[token]?.let { offsets += offsetTo(today, it) }
            }
        }
        if (weekend) {
            offsets += offsetTo(today, DayOfWeek.SATURDAY)
            offsets += offsetTo(today, DayOfWeek.SUNDAY)
        }

        val explicitNow = NOW_PATTERN.containsMatchIn(norm)
        // Jenseits des Horizonts: KEINE Tage liefern. Ein „nächsten Samstag" darf
        // nicht still als „diesen Samstag" beantwortet werden — genau die Sorte
        // stiller Fallback, die schon beim Orts-Geocode zum Lügen führte.
        if (beyond && offsets.isEmpty()) {
            return DayReference(emptyList(), explicit = true, beyondHorizon = true)
        }
        return if (offsets.isEmpty()) {
            // Kein Tag genannt ⇒ Default heute+morgen. [nowFocus] hängt an der
            // ZEITFORM der Frage: „Wie IST das Wetter?" meint den Augenblick
            // (Auftrag 2b), „Wie WIRD das Wetter?" den Tag voraus. Ein explizites
            // „gerade" gewinnt immer. Verpasster Futur-Marker ⇒ JETZT-Zeile steht
            // oben statt unten: unschön, aber nie unehrlich (sie steht in beiden
            // Fällen im Block).
            DayReference(
                offsets = DEFAULT_OFFSETS,
                explicit = false,
                nowFocus = explicitNow || !FUTURE_PATTERN.containsMatchIn(norm),
            )
        } else {
            DayReference(
                offsets = offsets.toList(),
                explicit = true,
                nowFocus = explicitNow,
                weekend = weekend,
                beyondHorizon = beyond,
            )
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

    /**
     * Lowercase, Apostrophe weg, alles außer BUCHSTABEN/Ziffern → Space (Muster
     * `DateFastpath`).
     *
     * **Von `[a-zäöüß0-9]` auf `\p{L}\p{N}` erweitert (Multilingual-Scheibe
     * 2026-08-21):** die ASCII+Umlaut-Whitelist zerschnitt jedes akzentuierte
     * Wort der drei neuen Sprachen — „miércoles" wurde zu „mi rcoles", „sábado" zu
     * „s bado", „lunedì" zu „luned", „après-demain" zu „apr s demain". Kein
     * einziger ES/FR/IT-Wochentag hätte je gematcht. Die Erweiterung ist strikt
     * permissiver: alle bisherigen DE/EN-Tokens normalisieren ZEICHENGLEICH wie
     * vorher (sie bestehen nur aus `a-zäöüß0-9`), die Pin-Tests bleiben grün.
     * Der Apostroph fällt weiterhin ERSATZLOS weg — „aujourd'hui" wird zu
     * „aujourdhui" (so steht es auch in [TODAY_TOKENS]).
     */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /** Zeit-Zone der Default-Uhr — Andis Wohnort (Muster `DateFastpath.BERLIN`). */
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        /** Ohne Tages-Referenz: heute+morgen — das bisherige Provider-Verhalten. */
        val DEFAULT_OFFSETS: List<Int> = listOf(0, 1)

        /**
         * Größter noch beantwortbarer Offset — Spiegel von
         * [WeatherGroundingProvider.FORECAST_DAYS] (7 Tage ⇒ 0..6). Alles darüber
         * ist [DayReference.beyondHorizon] statt geraten.
         */
        const val MAX_OFFSET: Int = 6

        /**
         * **Mehrwort-Phrasen → Offset**, konsumiert VOR der Token-Schleife: jede
         * enthält ein Token, das sie sonst überstimmen würde („tomorrow" in „day
         * after tomorrow", „mañana" in „pasado mañana", „demain" in „apres
         * demain"). Reihenfolge innerhalb der Map ist egal, weil sich die Phrasen
         * nicht überlappen.
         */
        private val PHRASES: Map<String, Int> = linkedMapOf(
            "day after tomorrow" to 2,
            "pasado mañana" to 2,
            "pasado manana" to 2,
            "après demain" to 2,
            "apres demain" to 2,
            "dopo domani" to 2,
        )

        /** Wochenend-Phrasen (mehrwortig) — ebenfalls vor den Tokens konsumiert. */
        private val WEEKEND_PHRASES: List<String> = listOf(
            "fin de semana", "fin de semaine", "fine settimana", "week end",
        )

        /** Einwort-Wochenende (DE/EN/IT-Lehnwort). */
        private val WEEKEND_TOKENS: Set<String> = setOf("wochenende", "weekend")

        /** „heute" in allen fünf Sprachen (FR nach Apostroph-Entfernung: „aujourdhui"). */
        private val TODAY_TOKENS: Set<String> = setOf(
            "heute", "today", "hoy", "aujourdhui", "oggi",
        )

        /** „morgen" (der TAG, nicht die Tageszeit) in allen fünf Sprachen. */
        private val TOMORROW_TOKENS: Set<String> = setOf(
            "morgen", "tomorrow", "mañana", "manana", "demain", "domani",
        )

        /** „übermorgen" als EINWORT-Form (die Mehrwort-Fassungen stehen in [PHRASES]). */
        private val DAY_AFTER_TOMORROW_TOKENS: Set<String> = setOf(
            "übermorgen", "uebermorgen", "dopodomani",
        )

        /**
         * **JETZT-Marker** (Auftrag 2b) — die Frage zielt auf den Augenblick, nicht
         * auf die Tagesspanne. Bewusst großzügig: ein Fehltreffer stellt lediglich
         * die (ohnehin mitgelieferte) JETZT-Zeile nach vorn, kostet also keine
         * Ehrlichkeit. Umgekehrt ist ein VERPASSTER Marker genau Andis Bug.
         * Wortgrenzen wie im [WeatherGroundingProvider.WEATHER_INTENT_PATTERN]-
         * Muster, damit „gerade" nicht in „geradeaus" und „ora" nicht in „orario"
         * hängenbleibt.
         */
        private val NOW_MARKERS: List<String> = listOf(
            // DE
            "jetzt", "gerade", "grad", "aktuell", "aktuelle", "aktuellen", "aktueller",
            "momentan", "im moment", "zur zeit", "zurzeit", "draußen gerade",
            // EN
            "now", "right now", "currently", "current", "at the moment", "at present",
            // ES
            "ahora", "ahora mismo", "actualmente", "en este momento",
            // FR
            "maintenant", "actuellement", "en ce moment", "en cet instant",
            // IT
            "adesso", "ora", "attualmente", "in questo momento",
        )

        private val NOW_PATTERN: Regex = Regex(
            pattern = "(?<![\\p{L}\\p{M}\\p{N}_])(?:" +
                NOW_MARKERS.joinToString("|") { Regex.escape(it) } +
                ")(?![\\p{L}\\p{M}\\p{N}_])",
        )

        /**
         * „nächste/kommende Woche" & Co. — zeigt IMMER über die Sieben-Tage-
         * Reichweite hinaus (auch wenn ein einzelner Tag davon rein zufällig noch
         * ins Fenster fiele: die Frage meint die ganze kommende Woche, und ein
         * Teil-Ausschnitt wäre eine stille Halbwahrheit).
         */
        private val NEXT_WEEK_PATTERN: Regex = Regex(
            "(?<![\\p{L}\\p{M}\\p{N}_])(?:" +
                listOf(
                    "nächste woche", "nächsten woche", "nächster woche", "naechste woche",
                    "kommende woche", "kommenden woche", "übernächste woche",
                    "next week", "the week after",
                    "la semana que viene", "la próxima semana", "la proxima semana",
                    "semana que viene",
                    "la semaine prochaine", "semaine prochaine",
                    "la prossima settimana", "prossima settimana", "settimana prossima",
                    "in einer woche", "in zwei wochen", "in a week", "in two weeks",
                ).joinToString("|") { Regex.escape(it) } +
                ")(?![\\p{L}\\p{M}\\p{N}_])",
        )

        /**
         * „nächsten <Wochentag>" (alle fünf Sprachen) — das ÜBERnächste Vorkommen.
         * Das nächste liegt bei 0..6, das übernächste damit zwingend bei 7..13:
         * IMMER jenseits des Horizonts, deshalb braucht die Gruppe nur erkannt
         * (und aus dem Text genommen) zu werden, nicht gerechnet. Gruppe 2 ist der
         * Wochentag, gegen [WEEKDAY_TOKENS] validiert — „nächsten Monat" fällt so
         * durch, ohne dass hier eine zweite Wochentagsliste entsteht.
         */
        private val NEXT_WEEKDAY_PATTERN: Regex = Regex(
            "(?<![\\p{L}\\p{M}\\p{N}_])" +
                "(nächsten|nächster|nächste|naechsten|naechste|kommenden|kommende|" +
                "next|próximo|proximo|próximos|el próximo|prochain|prochaine|prossimo|prossima)" +
                "\\s+(\\p{L}+)",
        )

        /**
         * **Futur-Marker** — „Wie WIRD das Wetter?" fragt voraus, „Wie IST das
         * Wetter?" nach dem Augenblick. Bewusst KONSERVATIV (nur eindeutige
         * Futur-Formen, keine kurzen Allerweltswörter wie das französische „va"):
         * ein Fehltreffer würde die JETZT-Zeile nach unten schieben, obwohl nach
         * dem Moment gefragt war — und genau das war Andis Livetest-Fehler. Ein
         * VERPASSTER Marker kostet dagegen nur Reihenfolge.
         */
        private val FUTURE_PATTERN: Regex = Regex(
            "(?<![\\p{L}\\p{M}\\p{N}_])(?:" +
                listOf(
                    "wird", "wirds", "werden", "soll", "sollen", "bleibt es",
                    "will it", "will the", "will we", "going to", "gonna", "be like",
                    "será", "sera", "estará", "estara", "hará", "hara", "va a",
                    "fera", "sera t il", "prévu", "prevu",
                    "sarà", "sara", "farà", "fara", "previsto",
                ).joinToString("|") { Regex.escape(it) } +
                ")(?![\\p{L}\\p{M}\\p{N}_])",
        )

        /** „in 3 Tagen" / „in 10 days" — Gruppe 1 ist die Zahl. */
        private val IN_N_DAYS_PATTERN: Regex = Regex(
            "(?<![\\p{L}\\p{M}\\p{N}_])in (\\d{1,3}) " +
                "(?:tagen|tage|days|day|días|dias|jours|jour|giorni|giorno)" +
                "(?![\\p{L}\\p{M}\\p{N}_])",
        )

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

        /**
         * **Wochentags-Tokens aller fünf Sprachen → [DayOfWeek]** (DE inkl.
         * „sonnabend"). Akzentuierte Formen stehen ZUSÄTZLICH unakzentuiert drin
         * („miércoles"/„miercoles", „sábado"/„sabato" — Achtung: das sind zwei
         * verschiedene Sprachen, nicht zwei Schreibweisen —, „lunedì"/„lunedi"):
         * STT-Transkripte und Tastatur-Eingaben lassen Akzente regelmäßig weg, und
         * [normalize] entfernt sie NICHT (es hält seit 2026-08-21 alle `\p{L}`).
         *
         * Sprachübergreifend EINE Tabelle statt einer pro Sprache: die Turn-Sprache
         * steht bei [resolve] gar nicht zur Verfügung (sie kommt erst beim
         * [dayLabel] herein), und ein deutscher Turn mit englischem „saturday" im
         * STT-Transkript soll denselben Tag treffen. Kollisionen gibt es keine —
         * kein Wochentagswort bedeutet in einer zweiten dieser Sprachen einen
         * ANDEREN Tag.
         */
        private val WEEKDAY_TOKENS: Map<String, DayOfWeek> = mapOf(
            // DE
            "montag" to DayOfWeek.MONDAY,
            "dienstag" to DayOfWeek.TUESDAY,
            "mittwoch" to DayOfWeek.WEDNESDAY,
            "donnerstag" to DayOfWeek.THURSDAY,
            "freitag" to DayOfWeek.FRIDAY,
            "samstag" to DayOfWeek.SATURDAY, "sonnabend" to DayOfWeek.SATURDAY,
            "sonntag" to DayOfWeek.SUNDAY,
            // EN
            "monday" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY,
            // ES
            "lunes" to DayOfWeek.MONDAY,
            "martes" to DayOfWeek.TUESDAY,
            "miércoles" to DayOfWeek.WEDNESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
            "jueves" to DayOfWeek.THURSDAY,
            "viernes" to DayOfWeek.FRIDAY,
            "sábado" to DayOfWeek.SATURDAY, "sabado" to DayOfWeek.SATURDAY,
            "domingo" to DayOfWeek.SUNDAY,
            // FR
            "lundi" to DayOfWeek.MONDAY,
            "mardi" to DayOfWeek.TUESDAY,
            "mercredi" to DayOfWeek.WEDNESDAY,
            "jeudi" to DayOfWeek.THURSDAY,
            "vendredi" to DayOfWeek.FRIDAY,
            "samedi" to DayOfWeek.SATURDAY,
            "dimanche" to DayOfWeek.SUNDAY,
            // IT
            "lunedì" to DayOfWeek.MONDAY, "lunedi" to DayOfWeek.MONDAY,
            "martedì" to DayOfWeek.TUESDAY, "martedi" to DayOfWeek.TUESDAY,
            "mercoledì" to DayOfWeek.WEDNESDAY, "mercoledi" to DayOfWeek.WEDNESDAY,
            "giovedì" to DayOfWeek.THURSDAY, "giovedi" to DayOfWeek.THURSDAY,
            "venerdì" to DayOfWeek.FRIDAY, "venerdi" to DayOfWeek.FRIDAY,
            "sabato" to DayOfWeek.SATURDAY,
            "domenica" to DayOfWeek.SUNDAY,
        )
    }
}
