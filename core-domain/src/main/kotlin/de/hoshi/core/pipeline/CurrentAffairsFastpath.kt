package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.pipeline.lang.TIME_PLACEHOLDER
import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsItem
import de.hoshi.core.port.CurrentAffairsPort
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSnapshot
import java.time.Clock
import java.time.LocalTime

/**
 * **CurrentAffairsFastpath** — "what's important today?" answered deterministically
 * from the [CurrentAffairsPort], with ZERO brain calls.
 *
 * Structurally brain-free: this class holds no `BrainPort` and the port it reads
 * knows none either, so the answer cannot cost a model call. It is wired at the
 * same seam as the other brain-free fastpaths (before routing), so a news question
 * can never be chatted away as smalltalk.
 *
 * **In-situ recognition** after the [DateFastpath] pattern: normalize, then
 * substring-match a curated DE+EN phrase list. Conservative on purpose — "was ist
 * heute für ein Tag" is a DATE question and must fall through to [DateFastpath]
 * (pinned by a negative test).
 *
 * **Honesty rules of the rendering** (the reason this is not a template string):
 * - FRESH ⇒ "as of <clock>: " + one speakable sentence per item.
 * - STALE ⇒ same, but the prefix names the age out loud. Never "current".
 * - EMPTY/UNAVAILABLE (or nothing speakable left) ⇒ one honest "no reports"
 *   sentence. An unreachable source is never phrased as "nothing happened".
 * - The clock comes from [CurrentAffairsSnapshot.lastSuccessfulRefreshAt] — the
 *   last SUCCESSFUL pull, never from `observedAt` (that is only "when I read the
 *   cache" and would be a quiet freshness lie).
 * - URLs are never spoken; they are stripped from title and snippet defensively
 *   even though the port already promises teaser-only text.
 *
 * [DISABLED] (`enabled = false`, NONE port) is the never-answering default:
 * [handle] always returns `null`, the branch in [TurnOrchestrator] is dead ⇒
 * byte-neutral, exactly like Calc/Timer/Date/Probe.
 */
class CurrentAffairsFastpath(
    private val port: CurrentAffairsPort,
    private val clock: Clock = Clock.system(DateFastpath.BERLIN),
    /** Flag-OFF seam: `false` ⇒ [handle] ALWAYS returns `null` (dead branch, byte-neutral). */
    private val enabled: Boolean = true,
) {

    /**
     * Recognizes a "what's new/important today" question and returns the finished,
     * speakable briefing; every non-match (no news question, flag OFF, blank) ⇒
     * `null` (⇒ normal turn). Reads the port exactly once, never the brain.
     */
    fun handle(text: String, language: Language = Language.DEFAULT): String? {
        if (!enabled || text.isBlank()) return null
        if (!isCurrentAffairsQuery(text)) return null
        return brief(port.latest(CurrentAffairsQuery(limit = SPOKEN_ITEM_LIMIT)), language)
    }

    /**
     * Whether [text] is an unambiguous current-affairs question (DE+EN) — pure,
     * side-effect-free recognition (no port read), so the orchestrator may call it
     * as a cheap probe.
     */
    internal fun isCurrentAffairsQuery(text: String): Boolean {
        val norm = normalize(text)
        if (norm.isEmpty()) return false
        return PHRASES.any { norm.contains(it) }
    }

    /**
     * The finished briefing for [snapshot] — deterministic, speech-length bounded,
     * URL-free. Pure function (only the injected clock's ZONE is used for the
     * spoken time), so tests pin it exactly.
     */
    internal fun brief(snapshot: CurrentAffairsSnapshot, language: Language): String {
        val pack = LanguagePackRegistry.forLanguage(language)
        // UNAVAILABLE ("could not look") and EMPTY ("source had nothing") share one
        // honest sentence: neither may sound like fresh news.
        if (snapshot.freshness == CurrentAffairsFreshness.UNAVAILABLE ||
            snapshot.freshness == CurrentAffairsFreshness.EMPTY
        ) {
            return pack.currentAffairsNone
        }
        val prefixTemplate =
            if (snapshot.freshness == CurrentAffairsFreshness.STALE) pack.currentAffairsBriefingStalePrefix
            else pack.currentAffairsBriefingPrefix
        // Fallback to observedAt only if the source was never pulled successfully —
        // that combination should not occur with items present, but a snapshot must
        // never render a null clock into the sentence.
        val standAt = snapshot.lastSuccessfulRefreshAt ?: snapshot.observedAt
        val prefix = prefixTemplate.replace(
            TIME_PLACEHOLDER,
            DateFastpath.spokenClock(LocalTime.ofInstant(standAt, clock.zone), language),
        )
        val sentences = sentencesWithinBudget(snapshot.items, prefix.length)
        // Items present but nothing speakable left (blank titles, URL-only text) ⇒
        // honest silence about content instead of an empty "as of 9:12: ".
        if (sentences.isEmpty()) return pack.currentAffairsNone
        return prefix + sentences.joinToString(" ")
    }

    /**
     * One speakable sentence per item, cut off at the spoken-length budget
     * ([MAX_SPOKEN_CHARS] minus the prefix). The FIRST item always survives
     * (truncated if need be) — a briefing with a timestamp and no content would be
     * worse than a shortened headline.
     */
    private fun sentencesWithinBudget(items: List<CurrentAffairsItem>, prefixLength: Int): List<String> {
        val out = mutableListOf<String>()
        var used = prefixLength
        for (item in items.take(SPOKEN_ITEM_LIMIT)) {
            val raw = sentence(item) ?: continue
            if (out.isEmpty()) {
                // The first headline always survives — clipped rather than dropped.
                val first = clip(raw, MAX_SPOKEN_CHARS - prefixLength)
                out += first
                used += first.length
                continue
            }
            val cost = raw.length + 1 // the joining space
            if (used + cost > MAX_SPOKEN_CHARS) break
            out += raw
            used += cost
        }
        return out
    }

    /** Shorten [text] to at most [max] characters at a word boundary — spoken text gets no ellipsis. */
    private fun clip(text: String, max: Int): String {
        if (max <= 0 || text.length <= max) return text
        // take(max - 1) leaves room for the closing period, so the result never
        // exceeds [max] — the budget arithmetic upstream relies on that.
        val cut = text.take(max - 1).substringBeforeLast(' ').trimEnd(*TRAILING_PUNCTUATION)
        return if (cut.isBlank()) text.take(max) else "$cut."
    }

    /**
     * ONE speakable sentence for [item]: the headline, plus the snippet core when it
     * really adds something. `null` if nothing speakable remains after URL stripping.
     * The canonical URL and the attribution domain are deliberately NOT spoken.
     */
    private fun sentence(item: CurrentAffairsItem): String? {
        val title = speakable(item.title)?.trimEnd(*TRAILING_PUNCTUATION) ?: return null
        if (title.isBlank()) return null
        val core = snippetCore(item.snippet, title)
        return if (core == null) "$title." else "$title — $core."
    }

    /**
     * First sentence of the feed snippet, shortened at a word boundary — or `null`
     * if it is missing, unspeakable, or merely repeats the headline (a common feed
     * pattern; saying it twice sounds broken).
     */
    private fun snippetCore(snippet: String?, title: String): String? {
        val raw = snippet ?: return null
        // A snippet that carries a URL is dropped WHOLE rather than gap-spliced:
        // stripping the link out of the middle of a sentence leaves broken grammar,
        // and a headline without its teaser still speaks fine. (The headline itself
        // is never dropped — there the token strip in [speakable] is the right cut.)
        if (URL_MARKERS.any { raw.contains(it, ignoreCase = true) }) return null
        val clean = speakable(raw) ?: return null
        val firstSentence = clean.split(SENTENCE_SPLIT).firstOrNull()?.trim()?.trimEnd(*TRAILING_PUNCTUATION)
        if (firstSentence.isNullOrBlank()) return null
        if (firstSentence.length < MIN_SNIPPET_CHARS) return null
        val titleKey = title.lowercase()
        if (firstSentence.lowercase().let { it == titleKey || titleKey.contains(it) || it.contains(titleKey) }) return null
        return if (firstSentence.length <= MAX_SNIPPET_CHARS) firstSentence
        else firstSentence.take(MAX_SNIPPET_CHARS).substringBeforeLast(' ').trimEnd(*TRAILING_PUNCTUATION)
    }

    /**
     * Defensive speech cleanup: drop URL-ish tokens, collapse whitespace. Second
     * line of defence only — the port contract already forbids HTML/full text, and
     * the TTS sanitizer strips URLs again on the cloud egress path.
     */
    private fun speakable(raw: String): String? {
        val cleaned = raw.split(WHITESPACE)
            .filterNot { token -> URL_MARKERS.any { token.contains(it, ignoreCase = true) } }
            .joinToString(" ")
            .trim()
        return cleaned.ifBlank { null }
    }

    /** Lowercase, apostrophes out, everything but DE letters/digits → space (as [DateFastpath]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {

        /** Never-answering default (flag OFF): the branch is dead ⇒ byte-neutral. */
        val DISABLED = CurrentAffairsFastpath(CurrentAffairsPort.NONE, enabled = false)

        /** The honest "nothing to report" sentence in German — pinned in tests via the one source. */
        internal val NONE_RECEIPT: String = LangDe.PACK.currentAffairsNone

        /** Headlines per spoken briefing (also the port query limit). */
        const val SPOKEN_ITEM_LIMIT = 3

        /**
         * Character budget of the whole briefing. ~75 s of speech at the ~15 chars/s
         * a German TTS voice averages — a spoken cap, not a display cap.
         */
        const val MAX_SPOKEN_CHARS = 1100

        /** Longest snippet core appended to a headline; longer ones are cut at a word boundary. */
        private const val MAX_SNIPPET_CHARS = 140

        /** Below this a snippet core carries no information worth the extra breath. */
        private const val MIN_SNIPPET_CHARS = 12

        private val WHITESPACE = Regex("\\s+")
        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
        private val TRAILING_PUNCTUATION = charArrayOf('.', '!', '?', ',', ';', ':', '-', '–', '—', ' ')

        /** Token markers that make a word a URL — never spoken. */
        private val URL_MARKERS = listOf("http://", "https://", "www.")

        /**
         * Curated current-affairs question phrases (DE+EN), substring-matched against
         * the normalized text. Deliberately specific (false-positive averse): the bare
         * "was ist heute" is NOT in here, because "was ist heute für ein Tag" is a DATE
         * question that [DateFastpath] must keep. Longer variants are covered by the
         * shorter core phrases ("was ist heute wichtiges passiert" contains "was ist
         * heute wichtig").
         */
        private val PHRASES = listOf(
            // DE
            "was ist heute wichtig", "was ist wichtig heute",
            "was gibt es neues", "was gibts neues",
            "gibt es was neues", "gibts was neues",
            "gibt es neuigkeiten", "gibts neuigkeiten",
            "was gibt es an neuigkeiten", "was gibts an neuigkeiten",
            "was ist heute passiert", "was ist in der welt passiert",
            // EN
            "whats new today", "what is new today",
            "whats important today", "what is important today",
            "anything important today", "anything new today",
            "any news today", "is there any news",
            "whats in the news", "what is in the news",
        )
    }
}
