package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.RingingItem
import de.hoshi.core.port.RingingItemPort
import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledItemPort
import de.hoshi.core.port.ScheduledKind
import de.hoshi.core.tools.TimerIntent
import de.hoshi.core.tools.ToolCall
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

/**
 * **TimerFastpath** — der brain-freie Vollzug eines Timer-[ToolCall] (`domain ==
 * "timer"`): die RELATIVEN Slots des [TimerIntent]-Parsers gegen die injizierte Uhr
 * in absolute Fälligkeiten auflösen, die [ScheduledItemPort] bedienen und eine warme,
 * deterministische deutsche/englische Quittung sprechen. Ruft den Brain NIE.
 *
 * **Der EINZIGE `now()`-Punkt** ist der injizierte [java.time.Clock] (analog zur
 * Ambient-/Warmth-Naht mit `ClockPort.SYSTEM`): SET rechnet `now + Dauer` bzw. die
 * nächste Wall-Clock-Übereinstimmung, QUERY die Restzeit. Tests setzen `Clock.fixed`
 * ⇒ voll deterministisch. Die reine Erkennung/Parsing bleibt im [TimerIntent] uhrfrei.
 *
 * **Welle 1 (Naht-Hinweis):** zuverlässige Anlage + Quittung + Query/Cancel. Das
 * **Klingeln/die fällige Ansage** braucht die Audio-Naht (separate Scheibe) — hier
 * NICHT enthalten; ein fälliges Item bleibt bis Cancel/Neustart im Store.
 *
 * **CANCEL sieht auch bereits KLINGELNDE Items** ([RingingItemPort], Live-Bug Andi
 * 2026-07-15): ein gefeuertes Item verlässt den [ScheduledItemPort] (Fire-Service in
 * `web-inbound`), bevor es abgeholt/quittiert wird — „stoppe den Timer" während es
 * klingelt fand bis dahin nichts (der [ScheduledItemPort] war schon leer bzw. enthielt
 * nur noch andere, geplante Items) und der Klingelton lief einfach weiter. [handleCancel]
 * vereinheitlicht seitdem klingelnde ([ringingPort]) und nur geplante ([store]) Items
 * hinter derselben Stopp-Aktion — [RingingItemPort.NONE] (Default) hält das Verhalten
 * für Aufrufer ohne Klingel-Naht byte-neutral.
 *
 * [DISABLED] (auf [ScheduledItemPort.NONE]) ist der nie-erreichte Default: ohne
 * `HOSHI_TIMER_ENABLED` emittiert der Classifier keinen Timer-Call, der Zweig im
 * [TurnOrchestrator] ist tot ⇒ byte-neutral.
 */
class TimerFastpath(
    private val store: ScheduledItemPort,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Injizierbar für deterministische ids in Tests. */
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    /**
     * Die Klingel-Naht für ein bereits GEFEUERTES Item ([RingingItemPort]) — nötig,
     * damit CANCEL ein klingelndes Item findet und beendet (s. Klassen-KDoc). Default
     * [RingingItemPort.NONE]: additiv/rückwärts-kompatibel, Aufrufer ohne Klingel-Naht
     * (z.B. [DISABLED], ältere Tests) bleiben byte-neutral.
     */
    private val ringingPort: RingingItemPort = RingingItemPort.NONE,
) {

    /**
     * Vollzieht den Timer-Call und liefert die fertige, sprechbare Quittung.
     *
     * [origin] ist die Ursprungs-Id (Gerät-/Session-Id, die den Wecker STELLTE) — nur
     * SET trägt sie in das [ScheduledItem]. Additiv/rückwärts-kompatibel: der Default
     * `null` (alte Aufrufer / Clients ohne `deviceId`) legt `origin=null` an ⇒ das
     * bisherige Verhalten bleibt byte-identisch (kein Ursprung ⇒ FE klingelt überall).
     *
     * [originSatelliteId] (PREP-wecker-am-satelliten) ist GETRENNT davon die `satelliteId`
     * des `/ws/audio`-Ursprungs (nur gesetzt, wenn der Turn tatsächlich über den Voice-PE-
     * Satelliten kam) — Grundlage für den späteren Ring-Downlink beim Feuern. `null`
     * (Chat/FE/alte Clients) ⇒ `originSatelliteId=null` ⇒ byte-identisch.
     */
    fun handle(call: ToolCall, language: Language, origin: String? = null, originSatelliteId: String? = null): String =
        when (call.service) {
            TimerIntent.SET -> handleSet(call, language, origin, originSatelliteId)
            TimerIntent.QUERY -> handleQuery(call, language)
            TimerIntent.CANCEL -> handleCancel(call, language)
            else -> ""
        }

    private fun handleSet(call: ToolCall, language: Language, origin: String?, originSatelliteId: String?): String {
        val nowMs = clock.millis()
        val kind = runCatching { ScheduledKind.valueOf(call.data["kind"] as? String ?: "TIMER") }
            .getOrDefault(ScheduledKind.TIMER)
        val label = (call.data["label"] as? String)?.takeIf { it.isNotBlank() }
        val durationSeconds = (call.data["durationSeconds"] as? Number)?.toLong()

        val dueMs: Long
        val isDuration: Boolean
        val timePhrase: String
        var rolledToTomorrow = false
        if (durationSeconds != null) {
            dueMs = nowMs + durationSeconds * 1000
            isDuration = true
            timePhrase = humanDuration(durationSeconds, language)
        } else {
            val h = (call.data["clockHour"] as? Number)?.toInt() ?: return ""
            val m = (call.data["clockMinute"] as? Number)?.toInt() ?: 0
            val force = call.data["clockForceTomorrow"] as? Boolean ?: false
            val (due, rolled) = resolveClockEpoch(h, m, nowMs, force)
            dueMs = due
            rolledToTomorrow = rolled
            isDuration = false
            timePhrase = "%02d:%02d".format(h, m)
        }
        store.set(
            ScheduledItem(
                id = idGen(), kind = kind, dueAtEpochMs = dueMs, label = label,
                origin = origin, originSatelliteId = originSatelliteId,
            ),
        )
        return receiptForSet(kind, isDuration, timePhrase, label, language, rolledToTomorrow)
    }

    /**
     * Status-Antwort („wie lange geht der Timer noch?") — deterministisch aus dem Store,
     * kurz, definitiv, NIE eine Gegenfrage (Nachtwächter-Prinzip; Live-Befund Andi
     * 2026-07-06: das Brain orakelte „Welchen Timer meinst du genau?"). Eine Wecker-
     * Frage ([TimerIntent.KIND_HINT] = ALARM, z.B. „läuft gerade ein Wecker?") blendet
     * auf Wecker um: nur ALARM-Items zählen, die Leer-Antwort ist die Wecker-Variante.
     *
     * **Benannter Abruf bei mehreren Timern** (Cowork-Katalog, Live-Lücke Andi
     * 2026-07-07): „wie lange geht der Nudel-Timer noch?" bei >1 laufenden Timern trifft
     * NUR den benannten (Fuzzy-Substring gegen die echten Labels der Items). Nennt der
     * Text einen Namen ([TimerIntent.extractTimerLabel] — dieselbe Wahrheit wie beim
     * Anlegen), der zu KEINEM laufenden Item passt, ist die Antwort ehrlich statt zu
     * raten. Ohne erkennbaren Namen bleibt das heutige Kurz-Listen-Verhalten unverändert.
     */
    private fun handleQuery(call: ToolCall, language: Language): String {
        val nowMs = clock.millis()
        val alarmOnly = call.data[TimerIntent.KIND_HINT] == TimerIntent.KIND_ALARM
        val items = store.query()
            .filter { !alarmOnly || it.kind == ScheduledKind.ALARM }
            .sortedBy { it.dueAtEpochMs }
        val en = language == Language.EN
        if (items.isEmpty()) {
            return if (alarmOnly) {
                if (en) "No alarm is set right now." else "Gerade ist kein Wecker gestellt."
            } else {
                if (en) "No timers running right now." else "Gerade läuft kein Timer."
            }
        }
        if (items.size == 1) return remainingPhrase(items[0], nowMs, language)

        val rawText = (call.data["text"] as? String).orEmpty()
        val lowerText = rawText.lowercase()
        val named = items.firstOrNull { it.label != null && lowerText.contains(it.label!!.lowercase()) }
        if (named != null) return remainingPhrase(named, nowMs, language)

        val mentionedName = TimerIntent.extractTimerLabel(rawText)
        if (mentionedName != null) return notFoundPhrase(mentionedName, items, nowMs, language)

        val list = listPhrase(items, nowMs, language)
        val noun = if (alarmOnly) (if (en) "alarms" else "Wecker") else (if (en) "timers" else "Timer")
        return if (en) "You have ${items.size} $noun: $list." else "Du hast ${items.size} $noun: $list."
    }

    /**
     * Ehrlichkeits-Antwort bei genanntem, aber zu KEINEM laufenden/klingelnden Item
     * passendem Namen — EINE Wahrheit, geteilt zwischen [handleQuery] und [handleCancel]
     * (kein Phrasen-Drift). [ringing] (Default leer) hängt bereits KLINGELNDE Items an die
     * Aufzählung an — nur [handleCancel] übergibt sie (s. [RingingItemPort]); [handleQuery]
     * ruft ohne diesen Parameter, bleibt also byte-identisch zum Stand vor dem Klingel-Fix.
     */
    private fun notFoundPhrase(
        name: String,
        items: List<ScheduledItem>,
        nowMs: Long,
        language: Language,
        ringing: List<RingingItem> = emptyList(),
    ): String {
        val en = language == Language.EN
        val list = listPhrase(items, nowMs, language, ringing)
        return if (en) "I can't find a $name timer — currently running: $list."
        else "Einen $name-Timer finde ich nicht — gerade laufen: $list."
    }

    /**
     * Kurze „Name noch Restzeit"-Liste — geteilt zwischen der Mehr-Timer-Aufzählung und
     * der Nicht-Treffer-Ehrlichkeits-Antwort (identisches Format, kein Drift). [ringing]
     * (Default leer, nur von [handleCancel] befüllt) hängt bereits klingelnde Items OHNE
     * Restzeit an („X klingelt gerade" statt einer erfundenen Dauer — ein gefeuertes Item
     * kennt keine Restzeit mehr).
     */
    private fun listPhrase(
        items: List<ScheduledItem>,
        nowMs: Long,
        language: Language,
        ringing: List<RingingItem> = emptyList(),
    ): String {
        val en = language == Language.EN
        val scheduledPart = items.map { item ->
            val rest = humanDuration(remainingSeconds(item, nowMs), language)
            val name = item.label ?: kindNoun(item.kind, language)
            if (en) "$name $rest left" else "$name noch $rest"
        }
        val ringingPart = ringing.map { item ->
            val name = item.label ?: kindNoun(item.kind, language)
            if (en) "$name ringing now" else "$name klingelt gerade"
        }
        return (scheduledPart + ringingPart).joinToString("; ")
    }

    /**
     * Die Ein-Item-Status-Antwort: Timer/Erinnerung ⇒ „Noch X Minuten und Y Sekunden."
     * (Restzeit via [humanDuration], exakt gegen die injizierte Uhr); Wecker ⇒ mit
     * Uhrzeit („Dein Wecker klingelt um 07:00 Uhr — noch eine Stunde."), weil „wann
     * klingelt der Wecker?" eine Uhrzeit-Frage ist. Ein Label (Erinnerung) wird genannt.
     */
    private fun remainingPhrase(item: ScheduledItem, nowMs: Long, language: Language): String {
        val en = language == Language.EN
        val rest = humanDuration(remainingSeconds(item, nowMs), language)
        if (item.kind == ScheduledKind.ALARM) {
            val due = ZonedDateTime.ofInstant(Instant.ofEpochMilli(item.dueAtEpochMs), clock.zone)
            val hhmm = "%02d:%02d".format(due.hour, due.minute)
            return if (en) "Your alarm rings at $hhmm — $rest to go."
            else "Dein Wecker klingelt um $hhmm Uhr — noch $rest."
        }
        val label = item.label
        return when {
            en && label != null -> "$label: $rest left."
            en -> "$rest left."
            label != null -> "$label: noch $rest."
            else -> "Noch $rest."
        }
    }

    /**
     * Stopp-Befehl. Bei genau einem Treffer per Label (Fuzzy-Substring, s.u.) oder genau
     * einem laufenden/klingelnden Item wird direkt gestoppt; sonst greift die Rückfrage.
     *
     * **Sieht auch bereits KLINGELNDE Items** ([ringingPort], Live-Bug Andi 2026-07-15):
     * ein gefeuertes Item hat den [ScheduledItemPort] schon verlassen (Fire-Service in
     * `web-inbound` cancelt es dort UND legt es in einen separaten Klingel-Speicher, den
     * das FE lokal abspielt, bis eine Ack-Quittung kommt) — „stoppe den Timer" während es
     * klingelt fand bisher NICHTS (entweder die ehrlich klingende, aber FALSCHE Leer-Phrase,
     * oder — bei weiteren geplanten Items — den falschen Timer) und der Ton lief weiter.
     * [ringing]-Items werden ZUERST geprüft (akuter als ein nur geplantes, nicht-klingelndes
     * Item mit demselben Namen) über ein vereinheitlichtes [CancelTarget]; [RingingItemPort.NONE]
     * (Default) macht diesen Zweig für Aufrufer ohne Klingel-Naht byte-neutral.
     *
     * **Genannter, aber nicht passender Name ist ehrlich** (Cowork-Katalog, dokumentierte
     * Rest-Lücke des Builders: „Stopp den Tee-Timer" während nur Pizza/Nudel laufen fiel
     * bisher auf die generische Rückfrage zurück — unehrlich, denn die Antwort ist wissbar).
     * Verwendet [extractCancelNameCandidate] + [notFoundPhrase] — DIESELBE Phrase wie beim
     * benannten QUERY-Fehlschlag, geteilt, kein Drift. Gilt auch bei genau EINEM laufenden
     * Item: ein falscher Name stoppt NICHT einfach das eine (das wäre geraten, nicht gehört).
     * Ohne erkennbaren Namen bleibt das heutige Verhalten byte-gleich.
     */
    private fun handleCancel(call: ToolCall, language: Language): String {
        val en = language == Language.EN
        val scheduled = store.query()
        val ringing = ringingPort.ringing()
        if (scheduled.isEmpty() && ringing.isEmpty()) {
            return if (en) "There's no timer running right now." else "Da läuft gerade kein Timer."
        }

        if (call.data["all"] as? Boolean == true) {
            val n = store.cancelAll() + ringing.count { ringingPort.stopRinging(it.id) }
            return if (en) "Okay, stopped all $n." else "Okay, alle $n gestoppt."
        }
        val text = (call.data["text"] as? String).orEmpty()

        // Klingelnde Items ZUERST (akuter als nur geplante) — dieselbe Stopp-Aktion hinter
        // [CancelTarget], damit die Auswahl-/Rückfrage-Logik darunter EINE Wahrheit bleibt.
        val targets: List<CancelTarget> =
            ringing.map { item -> CancelTarget(item.id, item.label) { ringingPort.stopRinging(item.id) } } +
                scheduled.map { item -> CancelTarget(item.id, item.label) { store.cancel(item.id) } }

        val byLabel = targets.firstOrNull { it.label != null && text.contains(it.label!!.lowercase()) }
        if (byLabel == null) {
            val candidateName = extractCancelNameCandidate(text)
            if (candidateName != null) {
                return notFoundPhrase(candidateName, scheduled, clock.millis(), language, ringing)
            }
        }
        val target = byLabel ?: targets.singleOrNull()
            ?: return if (en) "Several are running — which one should I stop?"
            else "Es laufen mehrere — welchen soll ich stoppen?"
        target.stop()
        return if (en) "Stopped${target.label?.let { " — $it" } ?: ""}."
        else "Gestoppt${target.label?.let { " für $it" } ?: ""}."
    }

    /**
     * Ein CANCEL-Ziel — vereinheitlicht klingelnde ([RingingItemPort]) und nur geplante
     * ([ScheduledItemPort]) Items hinter derselben Stopp-Aktion, damit [handleCancel]s
     * Auswahl-/Rückfrage-Logik NICHT zwischen beiden Quellen verzweigen muss (kein Drift).
     */
    private data class CancelTarget(val id: String, val label: String?, val stop: () -> Unit)

    /**
     * Kandidat für einen im CANCEL-Text genannten, aber (per [byLabel][handleCancel])
     * nicht passenden Timer-/Wecker-Namen — konservativ (Fehl-Positive sind schlimmer als
     * ein verpasster Treffer, sonst würde die Rückfrage fälschlich verschwinden).
     *
     * [TimerIntent.extractTimerLabel] wäre die EINE Wahrheit dafür (nutzt QUERY bereits),
     * verlangt aber zwingend einen Bindestrich VOR dem Nomen im ROH-Text — QUERY reicht
     * dafür bewusst `text` (unverändert) durch. CANCEL reicht in [TimerIntent.classify]
     * dagegen NUR `qNorm` (bereits normalisiert) durch: verifiziert per Probe-Test —
     * „Stopp den Tee-Timer" → `call.data["text"] == "stopp den tee timer"`, der Bindestrich
     * ist weg. [TimerIntent.extractTimerLabel] auf diesem Text würde NIE greifen (toter
     * Zweig) — der eigentliche Permanent-Fix wäre, `text` (roh) zusätzlich in den CANCEL-
     * ToolCall zu reichen (Ein-Zeiler in [TimerIntent], analog QUERY) — das liegt aber
     * außerhalb der Datei-Scope dieser Änderung.
     *
     * Bis dahin arbeitet diese Variante auf dem normalisierten Text selbst: das Wort direkt
     * vor dem Timer-/Wecker-Nomen, außer es steht auf der Artikel-/Pronomen-Sperrliste
     * ([CANCEL_NAME_STOPWORDS]) — sonst läse „stopp DEN Timer" fälschlich „den" als Namen.
     */
    private fun extractCancelNameCandidate(normalizedText: String): String? {
        val word = CANCEL_NAME_RX.find(normalizedText)?.groupValues?.get(1) ?: return null
        return word.takeUnless { it in CANCEL_NAME_STOPWORDS }?.replaceFirstChar { it.uppercaseChar() }
    }

    // ── Reine Zeit-/Phrasen-Helfer ───────────────────────────────────────────

    /**
     * Nächster Wall-Clock-Treffer ab [nowMs] in der Uhr-Zone; vergangen/`force` ⇒ morgen.
     * Rückgabe: (Epoch, `rolledToTomorrow`) — der zweite Wert sagt der Quittung, ob sie
     * „morgen" nennen muss (Live-Bug Andi 2026-07-03: „22:57" um 22:59 gestellt rollte
     * still auf morgen, die Bestätigung sagte aber nur „um 22:57 Uhr" → Wecker kam nie).
     */
    private fun resolveClockEpoch(hour: Int, minute: Int, nowMs: Long, force: Boolean): Pair<Long, Boolean> {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), clock.zone)
        var target = now.with(LocalTime.of(hour, minute, 0, 0))
        val rolled = force || !target.isAfter(now)
        if (rolled) target = target.plusDays(1)
        return target.toInstant().toEpochMilli() to rolled
    }

    private fun remainingSeconds(item: ScheduledItem, nowMs: Long): Long =
        (item.dueAtEpochMs - nowMs).coerceAtLeast(0) / 1000

    private fun receiptForSet(
        kind: ScheduledKind,
        isDuration: Boolean,
        timePhrase: String,
        label: String?,
        language: Language,
        rolledToTomorrow: Boolean = false,
    ): String {
        val en = language == Language.EN
        // „morgen"/„tomorrow" nur bei Wall-Clock-Zeiten, die auf den nächsten Tag rollen
        // (nie bei Dauern) — sonst weiß der Nutzer nicht, dass „22:57" erst morgen weckt.
        val dayDe = if (rolledToTomorrow && !isDuration) "morgen " else ""
        val dayEn = if (rolledToTomorrow && !isDuration) "tomorrow " else ""
        return when (kind) {
            ScheduledKind.TIMER ->
                if (en) "Got it, timer for $timePhrase is running."
                else "Alles klar, Timer für $timePhrase läuft."
            ScheduledKind.ALARM ->
                if (isDuration) {
                    if (en) "Got it, I'll wake you in $timePhrase." else "Alles klar, ich weck dich in $timePhrase."
                } else {
                    if (en) "Alarm set for ${dayEn}$timePhrase." else "Alles klar, ich weck dich ${dayDe}um $timePhrase Uhr."
                }
            ScheduledKind.REMINDER -> {
                val whenPhrase =
                    if (isDuration) (if (en) "in $timePhrase" else "in $timePhrase")
                    else (if (en) "at ${dayEn}$timePhrase" else "${dayDe}um $timePhrase Uhr")
                if (en) {
                    if (label != null) "Noted — I'll remind you $whenPhrase about $label."
                    else "Noted — I'll remind you $whenPhrase."
                } else {
                    if (label != null) "Notiert — $whenPhrase erinnere ich dich an $label."
                    else "Notiert — $whenPhrase erinnere ich dich."
                }
            }
        }
    }

    /** Menschlich lesbare Dauer (DE+EN): „eine Stunde und 5 Minuten" / „one hour and 5 minutes". */
    private fun humanDuration(seconds: Long, language: Language): String {
        val en = language == Language.EN
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = buildList {
            if (h > 0) add(if (h == 1L) (if (en) "one hour" else "eine Stunde") else "$h ${if (en) "hours" else "Stunden"}")
            if (m > 0) add(if (m == 1L) (if (en) "one minute" else "eine Minute") else "$m ${if (en) "minutes" else "Minuten"}")
            if (s > 0) add(if (s == 1L) (if (en) "one second" else "eine Sekunde") else "$s ${if (en) "seconds" else "Sekunden"}")
        }
        val and = if (en) " and " else " und "
        return when (parts.size) {
            0 -> if (en) "0 seconds" else "0 Sekunden"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + and + parts.last()
        }
    }

    private fun kindNoun(kind: ScheduledKind, language: Language): String {
        val en = language == Language.EN
        return when (kind) {
            ScheduledKind.TIMER -> if (en) "timer" else "Timer"
            ScheduledKind.ALARM -> if (en) "alarm" else "Wecker"
            ScheduledKind.REMINDER -> if (en) "reminder" else "Erinnerung"
        }
    }

    companion object {
        /** Nie erreichter Default (Flag-OFF): kein Store, keine Quittung. */
        val DISABLED = TimerFastpath(ScheduledItemPort.NONE)

        /** Wort direkt vor einem Timer-/Wecker-Nomen im normalisierten CANCEL-Text
         *  ([extractCancelNameCandidate]) — DE+EN, spiegelt [TimerIntent]s TIMER_NOUNS. */
        private val CANCEL_NAME_RX =
            Regex("""\b(\p{L}+)\s+(?:timer|wecker|erinnerung|alarm|reminder)\b""")

        /** Artikel/Pronomen, die vor „Timer"/„Wecker" stehen können, OHNE einen Namen zu
         *  meinen („stopp DEN Timer" ⇒ kein Name) — Sperrliste gegen Fehl-Positive. */
        private val CANCEL_NAME_STOPWORDS = setOf(
            "den", "der", "die", "das", "dem", "des",
            "ein", "eine", "einen", "einem", "einer",
            "mein", "meine", "meinen", "meinem",
            "dein", "deine", "deinen", "deinem",
            "diesen", "diese", "dieses", "diesem",
            "the", "a", "an", "this", "that", "my", "your",
        )
    }
}
