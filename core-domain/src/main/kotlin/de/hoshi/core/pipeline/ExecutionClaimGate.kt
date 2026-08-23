package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import reactor.core.publisher.Flux

/**
 * **ExecutionClaimGate** — output-side latch against "said is not switched".
 *
 * A turn that ran WITHOUT a tool call cannot have switched anything. If its final
 * answer nevertheless claims a completed switching act ("Flurlicht ist an."), the
 * answer is a lie and is replaced by an honest ask-back
 * ([de.hoshi.core.pipeline.lang.LanguagePack.executionClaimAskBack]).
 * See `vault/knowledge/BEFUND-brain-behauptet-vollzug-2026-08-11.md` for the four
 * live turns that motivated this (STT ate the verb, router fell to FACT_SHORT,
 * brain invented the confirmation).
 *
 * Deterministic, no LLM, no feature flag: honesty is not a feature (Kagami
 * invariant 1). A prompt rule is not enough — a 4B model does not keep it.
 *
 * ## Two independent conditions — both must hold before anything is replaced
 *
 *  1. **Armed (input side, [armed]):** no tool call ran in this turn AND the user
 *     text carries a device noun AND the user text is not an information question.
 *     The device noun is not only a cheap pre-filter: the ask-back ("I did not
 *     catch that as a switch command") is only TRUE if the user said something
 *     device-shaped. The question guard freezes the negative case "state answer
 *     to a state question" ("ist das Licht an?" -> "das Licht ist an." stays).
 *  2. **Claim (output side, [claimsExecution]):** ONE sentence of the answer
 *     carries a device noun AND a completion form — either a [COMPLETION_PHRASES]
 *     participle anywhere, or an on/off particle CLOSING the sentence behind a
 *     [PARTICLE_ANCHORS] anchor — AND no negation, hedge or offer marker, and is
 *     not itself a question.
 *
 * When unarmed the stream is returned untouched — no buffering, no latency,
 * byte-identical. Only armed turns are buffered (see [transform]).
 *
 * ## Why "no tool call ran" is also the SMART_HOME-read guard
 *
 * Every smart-home READ runs through `TurnOrchestrator.toolReadTurn`, i.e. it IS
 * a tool call and never reaches this stage. A SMART_HOME-routed turn that lands
 * on the brain WITHOUT a tool call is exactly the dangerous case and stays armed.
 *
 * Catalogue is curated and narrow on purpose: when in doubt, do NOT replace.
 * Known gaps are listed at [COMPLETION_PHRASES].
 */
class ExecutionClaimGate {

    /**
     * Wraps the brain prose stream of ONE turn.
     *
     * Unarmed ⇒ [events] returned as-is (identity, zero operators). Armed ⇒ the
     * events are buffered until completion, then either replayed unchanged (no
     * claim) or replaced by a single ask-back delta (claim). [onFired] is invoked
     * exactly once when the replacement happens — it feeds the additive diary
     * field `ChatEvent.Done.claimGateFired` and is read after this stream
     * completes (happens-before via Reactor's serial onNext/onComplete).
     *
     * Buffering is bounded by [MAX_BUFFERED_CHARS]: past that the gate flushes and
     * gives up for the rest of the turn. A switching receipt is short — an answer
     * that long is prose, and neither memory nor time-to-first-token may hang on it.
     *
     * Error contract: if [events] fails, the buffered events are flushed first and
     * the error is propagated unevaluated — never-silent outranks the latch, and a
     * broken stream has no "final answer" to judge.
     *
     * `concatMap` keeps processing serial, so the per-turn state below needs no
     * locks (same contract as `SlopKillStage`/`TtsStage`).
     */
    fun transform(
        events: Flux<ChatEvent>,
        userText: String,
        language: Language,
        toolCallRan: Boolean,
        onFired: () -> Unit = {},
    ): Flux<ChatEvent> {
        if (!armed(userText, toolCallRan)) return events
        return Flux.defer {
            val buffered = ArrayList<ChatEvent>()
            var chars = 0
            var gaveUp = false
            events
                .concatMap { event ->
                    if (gaveUp) {
                        Flux.just(event)
                    } else {
                        buffered.add(event)
                        if (event is ChatEvent.TextDelta) chars += event.text.length
                        if (chars > MAX_BUFFERED_CHARS) {
                            gaveUp = true
                            val flush = ArrayList(buffered)
                            buffered.clear()
                            Flux.fromIterable(flush)
                        } else {
                            Flux.empty()
                        }
                    }
                }
                .concatWith(
                    Flux.defer { if (gaveUp) Flux.empty() else decide(buffered, language, onFired) },
                )
                // Buffered-but-unemitted events go out before the error; after a
                // give-up the buffer is empty, so nothing is emitted twice.
                .onErrorResume { err -> Flux.concat(Flux.fromIterable(buffered.toList()), Flux.error(err)) }
        }
    }

    /** Replay unchanged, or replace every text delta by ONE honest ask-back delta. */
    private fun decide(buffered: List<ChatEvent>, language: Language, onFired: () -> Unit): Flux<ChatEvent> {
        val deltas = buffered.filterIsInstance<ChatEvent.TextDelta>()
        val answer = deltas.joinToString("") { it.text }
        if (!claimsExecution(answer)) return Flux.fromIterable(buffered)
        onFired()
        val provider = deltas.firstOrNull()?.provider ?: ""
        return Flux.concat(
            Flux.just<ChatEvent>(ChatEvent.TextDelta(askBack(language), provider = provider)),
            Flux.fromIterable(buffered.filter { it !is ChatEvent.TextDelta }),
        )
    }

    /** The honest ask-back of [language] — one source per language (LanguagePack). */
    fun askBack(language: Language): String =
        LanguagePackRegistry.forLanguage(language).executionClaimAskBack

    companion object {

        /**
         * Device nouns (DE+EN). Matched per TOKEN, never as a raw substring:
         * a token matches when it equals the noun or ends with it after a prefix of
         * at least [MIN_COMPOUND_PREFIX] letters. That is what makes German compounds
         * work ("flurlicht", "deckenlampe") while keeping accidental tails out
         * ("pflicht" -> prefix "pf", "schlicht" -> prefix "sch").
         */
        val DEVICE_WORDS: List<String> = listOf(
            // DE
            "licht", "lichter", "lampe", "lampen", "leuchte", "leuchten",
            "beleuchtung", "heizung", "heizungen", "thermostat", "thermostate",
            "rollladen", "rolladen", "rollläden", "jalousie", "jalousien",
            "steckdose", "steckdosen", "ventilator",
            // EN
            "light", "lights", "lamp", "lamps", "heating", "heater", "heaters",
            "blind", "blinds", "shutter", "shutters", "socket", "sockets",
            "outlet", "outlets", "fan", "fans",
        )

        /** Minimum compound prefix length for the tail rule in [DEVICE_WORDS]. */
        const val MIN_COMPOUND_PREFIX: Int = 4

        /**
         * Buffer ceiling of an armed turn, in answer characters. Above it the stage
         * flushes and stops gating: bounded memory, bounded TTFT damage, and an
         * answer that long is not the terse switching receipt this latch is after.
         */
        const val MAX_BUFFERED_CHARS: Int = 400

        /**
         * Completed switching acts (participles / verb+particle). Matched anywhere in
         * the sentence. Infinitives are deliberately absent — "das Licht einschalten"
         * and "soll ich das Licht anmachen?" are not claims.
         *
         * Known gaps (deliberate, false-positive-averse): "die Heizung läuft",
         * "erledigt", "hab ich gemacht", "done", "all set", dimming/percentage forms
         * ("auf 50 Prozent"), colour forms. None of them asserts an unambiguous on/off
         * completion, and each would risk replacing an honest sentence.
         */
        val COMPLETION_PHRASES: List<String> = listOf(
            // DE — participles; also cover "ist eingeschaltet"/"habe ich angemacht"
            "eingeschaltet", "ausgeschaltet", "angeschaltet", "abgeschaltet",
            "angemacht", "ausgemacht",
            // EN
            "turned on", "turned off", "turned it on", "turned it off",
            "switched on", "switched off", "switched it on", "switched it off",
        )

        /**
         * On/off particles. A claim in state form is recognised by the particle
         * CLOSING the sentence ("Flurlicht an.", "Licht im Flur ist jetzt an.",
         * "the hallway light is on."). Anchoring at the end is what keeps the
         * prepositional readings out: "das Licht ist an der Wand", "the light on the
         * table" end on a different token and are never touched.
         */
        val PARTICLES: List<String> = listOf("an", "aus", "on", "off")

        /**
         * The closing particle only counts when the token BEFORE it is a device noun
         * or one of these — i.e. the sentence really is "<device> [copula] <particle>".
         * Without this anchor a separable verb would look like a claim
         * ("Licht breitet sich schnell AUS").
         */
        val PARTICLE_ANCHORS: List<String> = listOf(
            "ist", "sind", "jetzt", "wieder", "nun",
            "is", "are", "now", "again", "back",
        )

        /** Any of these in the sentence ⇒ not a claim ("das Licht habe ich NICHT angeschaltet"). */
        val NEGATIONS: List<String> = listOf(
            "nicht", "nichts", "kein", "keine", "keinen", "keins", "keiner", "weder", "ohne",
            "not", "no", "never", "dont", "didnt", "isnt", "arent", "wasnt",
            "cant", "cannot", "couldnt", "wont", "havent", "hasnt", "unable",
        )

        /** Hedges — an uncertain sentence claims nothing ("das Licht ist vermutlich an"). */
        val HEDGES: List<String> = listOf(
            "vielleicht", "vermutlich", "wahrscheinlich", "eventuell", "möglicherweise",
            "sollte", "müsste", "könnte", "dürfte", "glaube", "denke", "schätze", "scheint",
            "maybe", "probably", "possibly", "likely", "should", "might", "guess",
            "think", "assume", "seems", "believe",
        )

        /**
         * Offers, conditionals and INSTRUCTIONS. An offer about a future act is no
         * claim — and neither is Hoshi teaching the phrasing ("sag einfach: Licht an").
         */
        val OFFER_MARKERS: List<String> = listOf(
            "soll ich", "sag", "sage", "sagst", "möchtest du", "willst du",
            "wenn du", "falls du", "probier", "versuch",
            "shall i", "should i", "say", "want me to", "would you like",
            "if you", "let me know", "try",
        )

        /**
         * Leading tokens that mark an INFORMATION question. Disarms the gate: a state
         * answer to a state question must survive untouched (frozen negative case).
         * Modal request openers ("kannst du", "can you") are deliberately NOT here —
         * those are commands, and a claim after them is still a lie.
         */
        val QUESTION_OPENERS: List<String> = listOf(
            "wie", "was", "wer", "wen", "wem", "wann", "warum", "wieso", "weshalb",
            "wo", "woher", "wohin", "welche", "welcher", "welches", "welchen",
            "ist", "sind", "war", "waren", "gibt", "brennt", "brennen", "leuchtet",
            "erzähl", "erzähle", "erklär", "erkläre",
            "what", "who", "whom", "when", "why", "how", "where", "which", "whose",
            "is", "are", "was", "were", "do", "does", "did", "tell", "explain",
        )

        /** Sentence terminators (same set as `SlopKillStage.BOUNDARY`). */
        private const val SENTENCE_END = ".!?…\n"

        /**
         * Input side: does this turn qualify for the latch at all?
         * [toolCallRan] `true` ⇒ never (something really happened, including every
         * smart-home READ). Otherwise: device noun present AND not an information
         * question.
         */
        fun armed(userText: String, toolCallRan: Boolean): Boolean {
            if (toolCallRan) return false
            val tokens = tokenize(userText)
            if (tokens.isEmpty()) return false
            if (tokens.first() in QUESTION_OPENERS) return false
            return tokens.any { isDeviceToken(it) }
        }

        /**
         * Output side: does [answer] claim a completed switching act? True iff ONE
         * sentence carries a device noun AND (a completion phrase OR a closing on/off
         * particle) AND is free of negation, hedge, offer marker and question mark.
         */
        fun claimsExecution(answer: String): Boolean =
            sentences(answer).any { sentenceClaims(it) }

        private fun sentenceClaims(sentence: String): Boolean {
            if (sentence.contains('?')) return false
            val normalized = normalize(sentence)
            if (normalized.isBlank()) return false
            val padded = " $normalized "
            if (NEGATIONS.any { padded.contains(" $it ") }) return false
            if (HEDGES.any { padded.contains(" $it ") }) return false
            if (OFFER_MARKERS.any { padded.contains(" $it ") }) return false
            val tokens = normalized.split(' ').filter { it.isNotEmpty() }
            if (tokens.none { isDeviceToken(it) }) return false
            if (COMPLETION_PHRASES.any { padded.contains(" $it ") }) return true
            // State form, anchored at the sentence end: "Flurlicht an.", "Licht ist an."
            if (tokens.size < 2 || tokens.last() !in PARTICLES) return false
            val anchor = tokens[tokens.size - 2]
            return anchor in PARTICLE_ANCHORS || isDeviceToken(anchor)
        }

        /** Token matches a device noun exactly, or as a compound tail (see [DEVICE_WORDS]). */
        internal fun isDeviceToken(token: String): Boolean = DEVICE_WORDS.any { word ->
            token == word || (token.endsWith(word) && token.length - word.length >= MIN_COMPOUND_PREFIX)
        }

        /** Lowercase, drop apostrophes, map every other non-letter/digit to a space. */
        internal fun normalize(text: String): String {
            val sb = StringBuilder(text.length)
            for (c in text.lowercase()) {
                when {
                    c == '\'' || c == '’' -> Unit
                    c.isLetterOrDigit() -> sb.append(c)
                    else -> sb.append(' ')
                }
            }
            return sb.toString().trim().replace(Regex(" +"), " ")
        }

        private fun tokenize(text: String): List<String> =
            normalize(text).split(' ').filter { it.isNotEmpty() }

        /** Splits into sentences, keeping the terminator (the '?' guard needs it). */
        internal fun sentences(text: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (c in text) {
                sb.append(c)
                if (c in SENTENCE_END) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out.filter { it.isNotBlank() }
        }
    }
}
