package de.hoshi.adapters.memory

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.port.WorkingSessionPort
import de.hoshi.core.port.WorkingSessionSegment
import de.hoshi.core.port.WorkingSessionWriter
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * **WorkingSessionAdapter** — der serverseitige, **speakerId-gekeyte**
 * Working-Session-Store (räumliches Gedächtnis, S1): der zusammenhängende
 * Kurzzeit-Verlauf des LAUFENDEN Gesprächs einer Person, damit der Server die
 * history rekonstruieren kann, wenn der Client keine schickt (Voice, zweites
 * Gerät, Page-Reload).
 *
 * Erfüllt BEIDE Nähte — exakt das [EpisodicMemoryAdapter]-Muster (Recall lesend
 * + Writer schreibend in EINER Instanz, PipelineConfig memoisiert sie):
 *  - [WorkingSessionPort.recentTurns] (lesend): die jüngsten Turn-Paare
 *    chronologisch als abwechselnde user/assistant-[ChatMessage]s.
 *  - [WorkingSessionWriter.append] (schreibend): hängt NACH der Antwort das
 *    Turn-Paar an (rememberAfter-Hook, kein Brain-Call, best-effort).
 *
 * **In-memory als erste Scheibe** ([ConcurrentHashMap]<speakerId, Deque>):
 * bewusst KEIN sqlite hier — die Session ist pro BE-Laufzeit, ein Restart
 * vergisst sie (ehrlich dokumentiert; Persistenz ist eine eigene Folge-Scheibe).
 *
 * **Härtung (identisch zu [EpisodicMemoryAdapter] Z.111/158):** ein Gast
 * ([EntityMemoryAdapter.isGuest]: leer/`unknown`/`gast`/ungültig) bekommt NIE
 * eine Session — `recentTurns == []` UND `append == No-op`. Kein Load, kein
 * Write; im Zweifel ein vergessliches Gespräch statt des falschen Gedächtnisses.
 *
 * **Per-Speaker-CAP** ([capTurns], Default [DEFAULT_CAP_TURNS]): nur die
 * jüngsten N Turn-Paare bleiben — der älteste fällt raus (Datensparsamkeit;
 * `windowHistory` bleibt UNVERÄNDERT der defensive Cap obendrauf).
 *
 * [clock] ist injizierbar (Tests deterministisch); der Timestamp je Turn ist
 * das Fundament der Themen-Segmentierung (S2, Zeit-Lücke) und ändert das
 * S1-Verhalten nicht.
 */
class WorkingSessionAdapter(
    private val capTurns: Int = DEFAULT_CAP_TURNS,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * **S2 — Themen-Segmentierung** (`HOSHI_SESSION_TOPIC_SEGMENT_ENABLED`,
     * Default OFF ⇒ exakt das S1-Verhalten: [recentTurns]/[readSegment] liefern
     * „die letzten N", keine Grenz-Erkennung, neutrale Diary-Felder).
     *
     * Bei ON liefert die Lese-Naht nur noch das AKTUELLE Themen-Segment.
     * Grenz-Signale in DIESER Reihenfolge (billig→smart, S2 gate-freie Grenze):
     *  1. **Zeit-Lücke** ([timeGapMinutes]) — beim Lesen (Lücke zur JETZT-Zeit)
     *     UND beim Schreiben (Lücke zum Vor-Turn ⇒ der neue Turn startet das Segment).
     *  2. **Diskurs-Marker/Reset-Phrasen** ([RESET_PHRASES], konservative
     *     DE+EN-Whitelist, NUR am Äußerungs-Anfang) — forciert FRISCH.
     *  3. Semantische Distanz (EmbeddingGemma-Zentroid + Hysterese + Anaphora-
     *     Kurz-Bias) ist BEWUSST NICHT hier — sie braucht den Embed-Sidecar live
     *     und ist die dokumentierte Folge-Scheibe ([WorkingSessionSegment.REASON_SEMANTIC]
     *     reserviert). Ohne Signal 3 verlängert eine kurze/anaphorische Nachfrage
     *     im selben Thema IMMER (Anti-Über-Segmentierung strukturell erfüllt).
     *
     * **Andi-Entscheid 03.07 (bindend):** Raum-Wechsel ist NIE eine Themen-Grenze
     * — Raum fließt hier nirgends ein, er ist reines Ausgabe-Routing (S3).
     */
    private val topicSegmentEnabled: Boolean = false,
    /** Zeit-Lücken-Schwelle in Minuten (`hoshi.session.topic.time-gap-minutes`). Startwert 30 = [HYPOTHESE], via S4 zu messen. */
    private val timeGapMinutes: Long = DEFAULT_TIME_GAP_MINUTES,
) : WorkingSessionPort, WorkingSessionWriter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val timeGap: Duration = Duration.ofMinutes(timeGapMinutes)

    /**
     * Ein gemerkter Turn: User-Text + Antwort + Zeitpunkt. [segmentStart] (S2)
     * markiert PERSISTENT, dass mit diesem Turn ein frisches Themen-Segment
     * begann (Zeit-Lücke zum Vor-Turn oder Reset-Phrase) — die Grenze bleibt
     * damit auch für alle Folge-Reads geschnitten.
     */
    private class TurnEntry(val user: String, val assistant: String, val at: Instant, val segmentStart: Boolean = false)

    private val sessions = ConcurrentHashMap<String, ArrayDeque<TurnEntry>>()

    // ── LESEN (WorkingSessionPort) ───────────────────────────────────────────
    /**
     * S1-Signatur (unverändert): OFF ⇒ die jüngsten Turns, chronologisch geflacht
     * zu `[user, assistant, …]`. Bei Themen-Segmentierung ON ⇒ das aktuelle
     * Segment (ohne Äußerungs-Kontext, d.h. ohne Marker-Signal — [readSegment]
     * ist die vollwertige S2-Naht). Gast/unbekannt ⇒ leere Liste (kein Load).
     */
    override fun recentTurns(speakerId: String): List<ChatMessage> =
        if (topicSegmentEnabled) readSegment(speakerId, utterance = "").turns else s1Turns(speakerId)

    /**
     * **S2-Lese-Naht:** das aktuelle Themen-Segment + Grenz-Entscheidung.
     * OFF ⇒ S1-Verhalten mit neutralen Diary-Feldern (byte-identische turns).
     */
    override fun readSegment(speakerId: String, utterance: String): WorkingSessionSegment {
        if (!topicSegmentEnabled) return WorkingSessionSegment(turns = s1Turns(speakerId))
        if (EntityMemoryAdapter.isGuest(speakerId)) return WorkingSessionSegment(turns = emptyList())
        // (2) Reset-Phrase am Anfang der AKTUELLEN Äußerung ⇒ FRISCH — schon
        //     DIESER Turn trägt den Alt-Kontext nicht mehr.
        if (isResetPhrase(utterance)) {
            return WorkingSessionSegment(
                turns = emptyList(),
                segmentReset = true,
                resetReason = WorkingSessionSegment.REASON_MARKER,
                segmentLenTurns = 0,
            )
        }
        val deque = sessions[speakerId] ?: return WorkingSessionSegment(turns = emptyList())
        synchronized(deque) {
            val last = deque.peekLast() ?: return WorkingSessionSegment(turns = emptyList())
            // (1) Zeit-Lücke seit dem letzten Turn ⇒ FRISCH (das Segment ist abgelaufen).
            if (Duration.between(last.at, clock.instant()) > timeGap) {
                return WorkingSessionSegment(
                    turns = emptyList(),
                    segmentReset = true,
                    resetReason = WorkingSessionSegment.REASON_TIME_GAP,
                    segmentLenTurns = 0,
                )
            }
            // VERLÄNGERN: das aktuelle Segment = von hinten bis einschließlich
            // des jüngsten segmentStart-Eintrags (fehlt einer, die ganze Session).
            val segment = ArrayList<TurnEntry>()
            val iter = deque.descendingIterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                segment.add(entry)
                if (entry.segmentStart) break
            }
            segment.reverse()
            return WorkingSessionSegment(
                turns = flatten(segment),
                segmentReset = false,
                resetReason = WorkingSessionSegment.REASON_NONE,
                segmentLenTurns = segment.size,
            )
        }
    }

    /** S1-Kern: ALLE gemerkten Turns (kein Segment-Schnitt) — der OFF-Pfad. */
    private fun s1Turns(speakerId: String): List<ChatMessage> {
        if (EntityMemoryAdapter.isGuest(speakerId)) return emptyList()
        val deque = sessions[speakerId] ?: return emptyList()
        synchronized(deque) { return flatten(deque) }
    }

    private fun flatten(entries: Collection<TurnEntry>): List<ChatMessage> =
        entries.flatMap {
            listOf(
                ChatMessage(role = "user", content = it.user),
                ChatMessage(role = "assistant", content = it.assistant),
            )
        }

    // ── SCHREIBEN (WorkingSessionWriter) ─────────────────────────────────────
    /**
     * Hängt das Turn-Paar an die Session von [speakerId]. Gast ⇒ No-op (KEINE
     * Session-Zeile). Ein leerer User-Text trägt keinen Verlauf ⇒ übersprungen.
     * Über [capTurns] hinaus fällt der älteste Turn raus.
     *
     * S2 (nur bei [topicSegmentEnabled]): der neue Turn wird als Segment-Start
     * markiert, wenn zum Vor-Turn eine Zeit-Lücke > [timeGapMinutes] liegt oder
     * der User-Text mit einer Reset-Phrase beginnt — dieselben Signale wie beim
     * Lesen, damit die Grenze konsistent PERSISTENT bleibt. OFF ⇒ das Flag wird
     * nie gesetzt und nie gelesen (S1-Verhalten byte-identisch).
     */
    override fun append(speakerId: String, userText: String, answer: String) {
        if (EntityMemoryAdapter.isGuest(speakerId)) {
            log.debug("[working-session] skip append für Gast/ungültige id '{}'", speakerId)
            return
        }
        if (userText.isBlank()) return
        val deque = sessions.computeIfAbsent(speakerId) { ArrayDeque() }
        synchronized(deque) {
            val now = clock.instant()
            val last = deque.peekLast()
            val startsSegment = topicSegmentEnabled &&
                (last == null || isResetPhrase(userText) || Duration.between(last.at, now) > timeGap)
            deque.addLast(TurnEntry(user = userText, assistant = answer, at = now, segmentStart = startsSegment))
            while (deque.size > capTurns) deque.removeFirst()
        }
    }

    /** Roh-Anzahl gespeicherter Turn-Paare je [speakerId] — OHNE Gast-Guard (Test-Beweis „0 Zeilen"). */
    internal fun storedTurnCount(speakerId: String): Int {
        val deque = sessions[speakerId] ?: return 0
        synchronized(deque) { return deque.size }
    }

    companion object {
        /**
         * Default-CAP je Sprecher (in TURN-Paaren): bewusst identisch zum live
         * gesetzten `HOSHI_MEMORY_WINDOW_TURNS=12` — die rekonstruierte history
         * ist damit nie größer als das, was `windowHistory` ohnehin durchließe.
         */
        const val DEFAULT_CAP_TURNS = 12

        /**
         * Zeit-Lücken-Schwelle (Minuten) als Startwert — **[HYPOTHESE]**, nicht
         * gemessen: „~30 min trägt vermutlich den Großteil des Werts" (PREP).
         * Die S4-Kalibrier-Messung übers Diary entscheidet halten/heben/senken;
         * ein Prod-Flip der Property ist ein Andi-Gate.
         */
        const val DEFAULT_TIME_GAP_MINUTES = 30L

        /**
         * **Konservative Diskurs-Marker-Whitelist (S2, dokumentiert):** Phrasen,
         * die NUR am Äußerungs-ANFANG einen expliziten Themenwechsel signalisieren
         * (hohe Präzision, bewusst mäßiger Recall — im Zweifel KEIN Reset).
         * Wort-Grenze erzwungen: „anyways…" matcht NICHT („anyway" + Buchstabe),
         * „übrigens," matcht (Komma ist keine Fortsetzung). Mitten im Satz
         * („das ist übrigens gut") matcht NIE.
         */
        val RESET_PHRASES: List<String> = listOf(
            // DE
            "übrigens",
            "apropos",
            "ganz was anderes",
            "was ganz anderes",
            "ganz andere frage",
            "anderes thema",
            "neues thema",
            "themenwechsel",
            // EN
            "by the way",
            "btw",
            "anyway",
            "on another note",
            "different topic",
            "new topic",
            "changing the subject",
        )

        /** Reset-Phrase am Äußerungs-Anfang (case-insensitiv, Wort-Grenze)? `internal` für deterministische Tests. */
        internal fun isResetPhrase(text: String): Boolean {
            val t = text.trim().lowercase()
            if (t.isEmpty()) return false
            return RESET_PHRASES.any { phrase ->
                t.startsWith(phrase) && (t.length == phrase.length || !t[phrase.length].isLetter())
            }
        }
    }
}
