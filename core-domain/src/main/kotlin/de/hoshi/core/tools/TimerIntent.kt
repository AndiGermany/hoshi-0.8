package de.hoshi.core.tools

/**
 * **TimerIntent** — die deterministische, LLM-freie Erkennung eines eindeutigen
 * Timer-/Wecker-/Erinnerungs-Befehls (DE **und** EN) in einen [ToolCall]
 * (`domain == "timer"`). Essenz aus Hoshi 0.5 `FastIntentService.timerIntent` +
 * `TimeSlotParser`, framework- und LLM-frei nach 0.8 portiert.
 *
 * KONSERVATIV: erkennt nur eindeutige Befehle. Reihenfolge wie 0.5 — **CANCEL vor
 * QUERY vor SET** (sonst frisst der breite SET-„timer"-Trigger ein „stopp den
 * Timer"). SET verlangt ein explizites Timer-/Wecker-/Erinnerungs-Wort UND eine
 * parsebare Zeit; fehlt die Zeit (mehrdeutig „stell einen Timer") ⇒ `null` (dann
 * fällt der Text in den normalen Turn zurück — exakt das geforderte OFF-/Ambig-
 * Verhalten).
 *
 * **Uhrfrei:** der Parser liefert RELATIVE Slots (Dauer-Sekunden ODER `HH:MM` +
 * `forceTomorrow`). Die Auflösung in eine absolute Epoch-Fälligkeit passiert erst
 * im [de.hoshi.core.pipeline.TimerFastpath] mit der injizierten Uhr — KEIN `now()`
 * in dieser reinen Logik.
 */
object TimerIntent {

    /** ToolCall-Domain dieses Fast-Paths (vom Orchestrator gegen den Timer-Zweig geprüft). */
    const val DOMAIN = "timer"
    const val SET = "set"
    const val QUERY = "query"
    const val CANCEL = "cancel"

    // ScheduledKind als String im ToolCall.data["kind"] (core.tools darf nicht auf core.port zeigen).
    const val KIND_TIMER = "TIMER"
    const val KIND_ALARM = "ALARM"
    const val KIND_REMINDER = "REMINDER"

    /**
     * QUERY-Slot: Wecker-Frage („wann klingelt der Wecker?") trägt `kindHint=ALARM`,
     * damit der Fastpath Wecker-spezifisch antwortet statt generisch über alle Timer.
     */
    const val KIND_HINT = "kindHint"

    /**
     * Klassifiziert den (Original-)Text in einen Timer-[ToolCall] oder `null`.
     * Sprache wird NICHT gebraucht (Trigger sind bilingual) — die Quittungs-Sprache
     * reicht der Orchestrator separat in den [de.hoshi.core.pipeline.TimerFastpath].
     */
    fun classify(text: String): ToolCall? {
        if (text.isBlank()) return null
        val qNorm = normalize(text)
        if (qNorm.isEmpty()) return null

        // (0) Negation ⇒ KEIN Befehl (konservativ, token-genau).
        val tokens = qNorm.split(' ').toSet()
        if (tokens.any { it in NEGATION || it.startsWith("kein") }) return null

        // (1) CANCEL — explizite Trigger ODER Stopp-Verb + Timer-/Wecker-Nomen.
        val cancelVerb = CANCEL_VERBS.any { qNorm.contains(it) }
        val timerNoun = TIMER_NOUNS.any { qNorm.contains(it) }
        if (CANCEL_TRIGGERS.any { qNorm.contains(it) } || (cancelVerb && timerNoun)) {
            val all = qNorm.contains("alle ") || qNorm.contains("all ")
            return ToolCall(
                domain = DOMAIN, service = CANCEL, entityId = null,
                data = mapOf("all" to all, "text" to qNorm),
            )
        }

        // (2) QUERY — Frage nach laufenden Timern (read-only). Zwei Wege:
        //     (a) literale Trigger (enthalten selbst genug Kontext, z.B. „wann klingelt"),
        //     (b) flexible Frage-Muster, die NUR zusammen mit einem expliziten Timer-/
        //         Wecker-Wort zählen — so matcht „wie lange GEHT der Timer noch" (Live-
        //         Befund Andi 2026-07-06: fiel ans Brain ⇒ Gegenfrage statt Store-Antwort),
        //         aber „wie lange dauert Pasta kochen" bleibt beim Brain.
        if (QUERY_TRIGGERS.any { qNorm.contains(it) } ||
            (timerNoun && QUERY_NOUN_RX.any { it.containsMatchIn(qNorm) })
        ) {
            // Wecker-Frage („wann klingelt der Wecker?") ⇒ kindHint ALARM: der Fastpath
            // beantwortet dann Wecker-spezifisch („Gerade ist kein Wecker gestellt.").
            val alarmish = (qNorm.contains("wecker") || qNorm.contains("alarm")) && !qNorm.contains("timer")
            // Roh-Text (NICHT qNorm) mitgeben: der Fastpath braucht ihn für den benannten
            // Abruf bei mehreren Timern (Fuzzy-Match gegen Labels + [extractTimerLabel] —
            // der Bindestrich in „Nudel-Timer" überlebt nur im Roh-Text, normalize() macht
            // ihn zu einem Space).
            val data = buildMap<String, Any?> {
                put("text", text)
                if (alarmish) put(KIND_HINT, KIND_ALARM)
            }
            return ToolCall(domain = DOMAIN, service = QUERY, entityId = null, data = data)
        }

        // (3) SET — braucht einen expliziten Timer-/Wecker-/Erinnerungs-Trigger.
        val isAlarm = ALARM_TRIGGERS.any { qNorm.contains(it) }
        val isReminder = REMINDER_TRIGGERS.any { qNorm.contains(it) }
        val isTimer = TIMER_TRIGGERS.any { qNorm.contains(it) }
        if (!isAlarm && !isReminder && !isTimer) return null

        // Mehrdeutig (kein parsebarer Slot) ⇒ null ⇒ normaler Turn.
        val slot = parseSlot(qNorm) ?: return null

        val kind = when {
            isReminder -> KIND_REMINDER
            isAlarm || slot.mode == Slot.Mode.CLOCK -> KIND_ALARM
            else -> KIND_TIMER
        }
        // TIMER-Kompositum-Name („Nudel-Timer", „Pizza-Timer") trägt EBENFALLS ein Label —
        // dieselbe Wahrheit wird später beim benannten QUERY/CANCEL wieder abgefragt
        // (Fastpath), damit „wie lange geht der Nudel-Timer noch?" bei mehreren laufenden
        // Timern NUR den einen trifft (Cowork-Katalog, Live-Lücke Andi 2026-07-06/07).
        val label = when {
            isReminder -> extractReminderLabel(text)
            kind == KIND_TIMER -> extractTimerLabel(text)
            else -> null
        }

        val data = buildMap<String, Any?> {
            put("kind", kind)
            if (label != null) put("label", label)
            when (slot.mode) {
                Slot.Mode.DURATION -> put("durationSeconds", slot.durationSeconds)
                Slot.Mode.CLOCK -> {
                    put("clockHour", slot.clockHour)
                    put("clockMinute", slot.clockMinute)
                    put("clockForceTomorrow", slot.forceTomorrow)
                }
            }
        }
        return ToolCall(domain = DOMAIN, service = SET, entityId = null, data = data)
    }

    // ── Slot-Parsing (rein, uhrfrei) ─────────────────────────────────────────

    /**
     * Ein relativer Zeit-Slot: entweder eine **Dauer** (Sekunden) oder eine
     * **absolute Uhrzeit** (`HH:MM`, + [forceTomorrow]). Die Auflösung in Epoch-
     * Millis passiert NICHT hier (uhrfrei), sondern im [TimerFastpath].
     */
    data class Slot(
        val mode: Mode,
        val durationSeconds: Long? = null,
        val clockHour: Int? = null,
        val clockMinute: Int? = null,
        val forceTomorrow: Boolean = false,
    ) {
        enum class Mode { DURATION, CLOCK }
    }

    /**
     * Parst [text] in einen [Slot]. Erst relative Dauer (eindeutige Einheiten-
     * Wörter), dann absolute Uhrzeit — „in 10 Minuten" wird nie als Uhrzeit
     * missdeutet. `null` ⇒ keine Zeit erkannt.
     */
    fun parseSlot(text: String): Slot? {
        val norm = normalize(text)
        parseDuration(norm)?.let { return it }
        parseClock(norm)?.let { return it }
        return null
    }

    private fun parseDuration(norm: String): Slot? {
        // 1) Bruch-/Wort-Dauer (längste Phrase zuerst, sonst frisst „halbe stunde"
        //    nicht den Teil von „anderthalb stunden").
        FRACTIONS.entries.sortedByDescending { it.key.length }
            .firstOrNull { norm.contains(it.key) }
            ?.let { return durationSlot(it.value) }

        // 2) Numerische/Wort-Dauer + Einheit, optional Kombi-Tail („1 stunde 30").
        val m = DURATION_RX.find(norm) ?: return null
        val n = resolveCardinal(m.groupValues[1]) ?: return null
        val unit = m.groupValues[2]
        val tail = m.groupValues[3].takeIf { it.isNotBlank() }?.let { resolveCardinal(it) }
        var seconds = n * unitSeconds(unit)
        if (tail != null) seconds += tail * tailUnitSeconds(unit)
        if (seconds <= 0) return null
        return durationSlot(seconds)
    }

    private fun durationSlot(seconds: Long) = Slot(mode = Slot.Mode.DURATION, durationSeconds = seconds)

    private fun parseClock(norm: String): Slot? {
        val force = norm.contains("morgen") || norm.contains("tomorrow")

        anchorTime(norm)?.let { (h, m) -> return clockSlot(h, m, force) }

        // „halb acht" = 07:30 (deutsche Lesart: halb VOR der Vollstunde).
        Regex("""halb\s+(\d{1,2}|$HOUR_WORD_ALT)\b""").find(norm)?.let { mm ->
            val raw = hourValue(mm.groupValues[1]) ?: return@let
            val hour = if (raw == 0) 23 else raw - 1
            return clockSlot(normHour(hour), 30, force)
        }

        quarterTime(norm)?.let { (h, m) -> return clockSlot(h, m, force) }

        // „um/at H[:MM]" / „um H uhr [MM]" / „at H [am|pm]".
        Regex(
            """\b(?:um|at)\s+(\d{1,2}|$HOUR_WORD_ALT)""" +
                """(?:\s*:\s*(\d{1,2})|\s*uhr(?:\s+(\d{1,2}))?)?(?:\s*(am|pm))?\b""",
        ).find(norm)?.let { mm ->
            var h = hourValue(mm.groupValues[1]) ?: return@let
            val min = mm.groupValues[2].toIntOrNull() ?: mm.groupValues[3].toIntOrNull() ?: 0
            h = applyAmPm(h, mm.groupValues[4])
            if (h in 0..23 && min in 0..59) return clockSlot(h, min, force)
        }

        // „HH:MM" ODER „HH.MM" freistehend (z.B. „weck mich 7:30" / „auf 22.57 Uhr").
        // MUSS VOR „H uhr MM" laufen: bei „auf 23:11 Uhr" würde sonst das lockere
        // „11 uhr"-Muster die MINUTE als Stunde greifen (→ 11:00 statt 23:11) — genau
        // dann, wenn die Minute selbst eine gültige Stunde ist (Live-Bug Andi 2026-07-03).
        // Der Punkt ist die normale deutsche Uhrzeit-Schreibweise („22.57 Uhr"); normalize
        // wandelt ihn schon zu „:" — das „[.:]" hier ist zusätzliche Absicherung.
        Regex("""\b(\d{1,2})[.:](\d{2})\b""").find(norm)?.let { mm ->
            val h = mm.groupValues[1].toInt()
            val min = mm.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return clockSlot(h, min, force)
        }

        // „H uhr [MM]" ohne „um" (z.B. „weck mich 7 uhr" / „7 uhr 30") — NACH der
        // vollen HH:MM-Form, damit ein Doppelpunkt-Zeit nie an „<min> uhr" verloren geht.
        Regex("""\b(\d{1,2})\s*uhr(?:\s+(\d{1,2}))?\b""").find(norm)?.let { mm ->
            val h = mm.groupValues[1].toInt()
            val min = mm.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && min in 0..59) return clockSlot(h, min, force)
        }
        return null
    }

    private fun clockSlot(hour: Int, minute: Int, force: Boolean) =
        Slot(mode = Slot.Mode.CLOCK, clockHour = hour, clockMinute = minute, forceTomorrow = force)

    private fun anchorTime(norm: String): Pair<Int, Int>? = when {
        norm.contains("mitternacht") || norm.contains("midnight") -> 0 to 0
        norm.contains("morgen früh") || norm.contains("morgen frueh") -> 7 to 0
        norm.contains("heute abend") || norm.contains("am abend") || norm.contains("abends") -> 20 to 0
        norm.contains("mittags") || norm.contains("zu mittag") || norm.contains("noon") || norm.contains("midday") -> 12 to 0
        else -> null
    }

    /** „viertel vor/nach <stunde>" + „dreiviertel <stunde>". */
    private fun quarterTime(norm: String): Pair<Int, Int>? {
        Regex("""dreiviertel\s+(\d{1,2}|$HOUR_WORD_ALT)\b""").find(norm)?.let {
            val raw = hourValue(it.groupValues[1]) ?: return null
            return normHour(if (raw == 0) 23 else raw - 1) to 45
        }
        Regex("""viertel\s+(vor|nach)\s+(\d{1,2}|$HOUR_WORD_ALT)\b""").find(norm)?.let {
            val dir = it.groupValues[1]
            val raw = hourValue(it.groupValues[2]) ?: return null
            return if (dir == "nach") normHour(raw) to 15 else normHour(if (raw == 0) 23 else raw - 1) to 45
        }
        return null
    }

    private fun applyAmPm(hour: Int, ampm: String): Int = when {
        ampm == "pm" && hour in 1..11 -> hour + 12
        ampm == "am" && hour == 12 -> 0
        else -> hour
    }

    private fun hourValue(token: String): Int? = token.toIntOrNull() ?: HOUR_WORDS[token]
    private fun normHour(h: Int): Int = ((h % 24) + 24) % 24
    private fun resolveCardinal(token: String): Long? = token.toLongOrNull() ?: CARDINALS[token]?.toLong()

    private fun unitSeconds(unit: String): Long = when {
        unit in HOUR_UNITS -> 3600L
        unit in MIN_UNITS -> 60L
        else -> 1L
    }

    /** Kombi-Tail: bei Stunden = Minuten, bei Minuten = Sekunden, sonst Sekunden. */
    private fun tailUnitSeconds(unit: String): Long = when {
        unit in HOUR_UNITS -> 60L
        unit in MIN_UNITS -> 1L
        else -> 1L
    }

    /**
     * Label aus einer Erinnerung extrahieren (auf dem Original-Text, damit Groß-/
     * Kleinschreibung + Umlaute erhalten bleiben):
     *  - DE „an (die|den|das|dem)? X" (letztes Vorkommen gewinnt),
     *  - EN „to X" / „about X".
     * Zeit-Schwanz („… in 10 Minuten" / „… at 7") wird abgeschnitten.
     */
    internal fun extractReminderLabel(raw: String): String? {
        val t = raw.trim()
        val deArticled = Regex("""\ban\s+(?:die|den|das|dem)\s+(.+?)\s*[.!?]*$""", RegexOption.IGNORE_CASE)
            .findAll(t).lastOrNull()
        val de = deArticled ?: Regex("""\ban\s+(.+?)\s*[.!?]*$""", RegexOption.IGNORE_CASE).find(t)
        val en = Regex("""\b(?:to|about)\s+(.+?)\s*[.!?]*$""", RegexOption.IGNORE_CASE).find(t)
        // DE hat Vorrang (Primärsprache); EN nur wenn DE leer ausgeht.
        val m = de ?: en ?: return null
        var label = m.groupValues[1].trim()
        label = label.replace(Regex("""\s+(?:in|um|at|on)\s+\d.*$""", RegexOption.IGNORE_CASE), "").trim()
        return label.ifBlank { null }
    }

    /**
     * Name eines benannten TIMERs aus dem Text extrahieren (auf dem Original-Text,
     * damit Groß-/Kleinschreibung erhalten bleibt): DE/EN-Kompositum „<Name>-Timer"
     * (z.B. „Nudel-Timer", „Pizza-Timer") — Bindestrich ist PFLICHT, sonst würde ein
     * generischer Kompositum-Trigger wie „Küchentimer"/„Kurzzeitwecker" ([TIMER_TRIGGERS])
     * fälschlich als Eigenname durchgehen (der meint „irgendein Timer", keinen Namen).
     *
     * Dieselbe Funktion wird zweifach genutzt — EINE Wahrheit für beide Richtungen:
     *  - beim ANLEGEN (SET) das Label fürs [ScheduledItem],
     *  - beim [de.hoshi.core.pipeline.TimerFastpath] Query/Cancel, um zu erkennen, OB
     *    der Text überhaupt einen Namen nennt (der Fuzzy-Match läuft dort zusätzlich
     *    gegen die tatsächlichen Labels der laufenden Items).
     */
    internal fun extractTimerLabel(raw: String): String? {
        val m = Regex("""\b(\p{L}+)-timer\b""", RegexOption.IGNORE_CASE).find(raw) ?: return null
        return m.groupValues[1].trim().ifBlank { null }
    }

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern/Doppelpunkt → Space. */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            // Deutsche Uhrzeit-Schreibweise „22.57" → „22:57" VOR dem Zeichen-Strip
            // (der den Punkt sonst zu einem Leerzeichen macht → „22 57" fällt am Parser
            // vorbei ans Brain). Nur H.MM mit 2-stelliger Minute — „3.5 Stunden" (Dezimal)
            // bleibt unberührt. Live-Bug Andi 2026-07-03.
            .replace(Regex("""(\d{1,2})\.(\d{2})"""), "$1:$2")
            .replace(Regex("[^a-zäöüß0-9: ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── Wortlisten (DE + EN), konservativ ────────────────────────────────────

    private val NEGATION = setOf("nicht", "nie", "niemals", "not", "no", "dont", "never")

    private val TIMER_NOUNS = listOf("timer", "wecker", "erinnerung", "alarm", "reminder")

    private val CANCEL_VERBS = listOf(
        "stopp", "stop", "abbrechen", "lösch", "loesch", "halt ", "beende", "brich ",
        "cancel", "delete", "clear", "remove",
    )
    private val CANCEL_TRIGGERS = listOf(
        "stopp den timer", "stoppe den timer", "stop den timer", "timer stoppen",
        "timer abbrechen", "timer löschen", "timer loeschen", "stell den timer ab",
        "wecker aus", "wecker abbrechen", "wecker löschen", "wecker loeschen",
        "stell den wecker ab", "halt den timer an", "alle timer löschen",
        "alle timer loeschen", "alle wecker löschen", "alle wecker loeschen",
        "erinnerung löschen", "erinnerung loeschen",
        "stop the timer", "cancel the timer", "delete the timer", "clear the timer",
        "stop the alarm", "cancel the alarm", "cancel all timers", "stop all timers",
        "cancel all alarms", "delete the reminder", "cancel the reminder",
    )
    private val QUERY_TRIGGERS = listOf(
        "wie lange noch", "wie lange läuft", "wie lange laeuft", "wie viel zeit noch",
        "welche timer", "welche wecker", "welcher timer", "läuft noch ein timer",
        "laeuft noch ein timer", "wie lange dauert der timer", "wie viele timer",
        "zeig mir die timer", "zeig die timer", "wann klingelt", "wann geht der wecker",
        "welche erinnerungen", "wie lange hat der timer noch",
        // „klingeln" ist eindeutig Timer-/Wecker-Terrain — darf ohne Nomen matchen
        // („wie spät klingelt es?" hat kein Timer-Wort, meint aber nur den Wecker).
        "wie spät klingelt", "wie spaet klingelt",
        "how much time", "how long left", "how long is left", "which timers",
        "list timers", "how many timers", "any timers", "any alarms",
        "show me the timers", "is there a timer", "is there an alarm",
        "when does the timer", "when does the alarm", "when will the timer",
        "when will the alarm", "when is the alarm",
        "what time does it ring", "when does it ring", "when will it ring",
    )

    /**
     * Flexible QUERY-Frage-Muster — zählen NUR, wenn zusätzlich ein Timer-/Wecker-Wort
     * ([TIMER_NOUNS]) im Satz steht (konservativ: „wie lange dauert Pasta kochen" oder
     * „läuft der Film schon?" bleiben beim Brain). Deckt die Wortstellungs-Varianten,
     * die literale Substring-Trigger nicht greifen („wie lange GEHT der Timer NOCH").
     */
    private val QUERY_NOUN_RX = listOf(
        // „wie lange geht der Timer noch" / „wie lange hat der Wecker noch"
        Regex("""\bwie lange\b.*\bnoch\b"""),
        // „wie lange geht/läuft/dauert der Timer" (auch ohne „noch")
        Regex("""\bwie lange (?:geht|läuft|laeuft|dauert|hat)\b"""),
        // „läuft (gerade/noch/denn) ein Timer?" / „läuft der Wecker noch?"
        Regex("""\b(?:läuft|laeuft) (?:gerade |noch |da |denn |eigentlich )*(?:ein|der|die|das|mein|dein|irgendein)\b"""),
        // EN: „how long does the timer have left" / „how much longer is the timer running"
        Regex("""\bhow (?:long|much longer)\b"""),
        // EN: „is a/the timer (still) running" / „is an alarm set"
        Regex("""\bis (?:a|an|the|my) (?:timer|alarm) (?:still )?(?:running|set|on|going|active)\b"""),
    )
    private val TIMER_TRIGGERS = listOf(
        "timer", "kurzzeitwecker", "küchentimer", "kuechentimer",
    )
    private val ALARM_TRIGGERS = listOf(
        "weck mich", "wecke mich", "weckruf", "wecker", "alarm",
        "wake me", "set an alarm", "set the alarm",
    )
    private val REMINDER_TRIGGERS = listOf(
        "erinner mich", "erinnere mich", "erinnerung", "denk dran", "denk daran",
        "vergiss nicht", "remind me", "reminder", "remember to",
    )

    /** DE+EN Brüche/feste Phrasen → Sekunden. */
    private val FRACTIONS: Map<String, Long> = mapOf(
        "anderthalb stunden" to 90 * 60L, "anderthalb stunde" to 90 * 60L,
        "eineinhalb stunden" to 90 * 60L, "eineinhalb stunde" to 90 * 60L,
        "an hour and a half" to 90 * 60L, "hour and a half" to 90 * 60L,
        "dreiviertelstunde" to 45 * 60L, "dreiviertel stunde" to 45 * 60L,
        "eine viertelstunde" to 15 * 60L, "viertelstunde" to 15 * 60L, "viertel stunde" to 15 * 60L,
        "quarter of an hour" to 15 * 60L, "a quarter of an hour" to 15 * 60L, "quarter hour" to 15 * 60L,
        "einer halben stunde" to 30 * 60L, "halben stunde" to 30 * 60L, "halbe stunde" to 30 * 60L,
        "half an hour" to 30 * 60L, "half a minute" to 30L, "halbe minute" to 30L,
    )

    /** Kardinalzahl-Wort → Zahl (DE+EN, kleiner konservativer Satz; Ziffern gehen direkt). */
    private val CARDINALS: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "ein" to 1, "eine" to 1, "einen" to 1, "eins" to 1,
        "two" to 2, "zwei" to 2, "three" to 3, "drei" to 3, "four" to 4, "vier" to 4,
        "five" to 5, "fünf" to 5, "fuenf" to 5, "six" to 6, "sechs" to 6,
        "seven" to 7, "sieben" to 7, "eight" to 8, "acht" to 8, "nine" to 9, "neun" to 9,
        "ten" to 10, "zehn" to 10, "eleven" to 11, "elf" to 11, "twelve" to 12, "zwölf" to 12, "zwoelf" to 12,
        "fifteen" to 15, "fünfzehn" to 15, "fuenfzehn" to 15, "twenty" to 20, "zwanzig" to 20,
        "thirty" to 30, "dreißig" to 30, "dreissig" to 30, "forty" to 40, "vierzig" to 40,
        "fifty" to 50, "fünfzig" to 50, "fuenfzig" to 50, "sixty" to 60, "sechzig" to 60,
    )

    /** Stunde-Wort → 1..12 (für „um sieben"/„at seven"). */
    private val HOUR_WORDS: Map<String, Int> = mapOf(
        "ein" to 1, "eins" to 1, "one" to 1, "zwei" to 2, "two" to 2, "drei" to 3, "three" to 3,
        "vier" to 4, "four" to 4, "fünf" to 5, "fuenf" to 5, "five" to 5, "sechs" to 6, "six" to 6,
        "sieben" to 7, "seven" to 7, "acht" to 8, "eight" to 8, "neun" to 9, "nine" to 9,
        "zehn" to 10, "ten" to 10, "elf" to 11, "eleven" to 11, "zwölf" to 12, "zwoelf" to 12, "twelve" to 12,
    )

    private val HOUR_UNITS = setOf("stunden", "stunde", "hours", "hour", "hrs", "hr", "std", "h")
    private val MIN_UNITS = setOf("minutes", "minuten", "minute", "mins", "min")

    private val HOUR_WORD_ALT = HOUR_WORDS.keys.sortedByDescending { it.length }.joinToString("|")

    /** Kardinalzahl (Ziffer|Wort), längste Wörter zuerst (Backtracking-sicher). */
    private val CARD_ALT = (listOf("\\d+") + CARDINALS.keys.sortedByDescending { it.length }).joinToString("|")

    private const val UNIT_ALT =
        "stunden|stunde|hours|hour|hrs|hr|std|h|" +
            "minutes|minuten|minute|mins|min|" +
            "sekunden|sekunde|seconds|second|secs|sec|sek|s"

    // Kombi-Tail bewusst NUR Ziffern (`\d+`): sonst grübe ein Wort-Tail einen
    // Artikel wie „an"/„a" (z.B. „5 Minuten an die Suppe" ⇒ fälschlich +1 s).
    private val DURATION_RX = Regex(
        "($CARD_ALT)\\s*($UNIT_ALT)\\b" +
            "(?:\\s*(?:und|and)?\\s*(\\d+)\\s*(?:$UNIT_ALT)?\\b)?",
    )
}
