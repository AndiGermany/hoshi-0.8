package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.deOr

/**
 * **AnaphoraRecognizer** — the deterministic "this sentence points at something it
 * does not name" test (Andi live bug 2026-08-15).
 *
 * Turn 1 „Wozu isst man ihn denn?" → „soll ich kurz nachschauen?"; turn 2 „ja" →
 * the parked question was escalated VERBATIM, so the cloud saw a pronoun without a
 * referent and honestly found nothing. The honesty worked; the query was broken.
 * This recognizer decides — at OFFER time, while the conversation is still in
 * reach — whether the parked question needs its referent to travel with it.
 *
 * **Contract: no model, no scoring, no free-text heuristic.** Two exact word sets
 * plus one structural guard:
 *
 *  1. **Reference signal present** — a pronoun / pronominal adverb / demonstrative
 *     from [REFERENCE_SIGNALS] (anywhere) or [INITIAL_SIGNALS] (sentence-initial
 *     only, because "der/die/das/that" mid-sentence are usually articles or
 *     conjunctions, not references).
 *  2. **No own noun as a plausible referent** ([carriesOwnNoun]) — a capitalized
 *     token past position 0 that is not a capitalized function word. German nouns
 *     are always capitalized, so this is a real signal there and a conservative
 *     one in English (a sentence naming a proper noun is self-contained enough).
 *
 * **Deliberately asymmetric error budget:** a false positive only adds two lines
 * of context the cloud may ignore (and suppresses one cacheable note, see
 * [TurnOrchestrator.escalationTurn]); a false negative is exactly today's
 * behaviour. So the sets stay short and the guard stays cheap rather than clever.
 */
object AnaphoraRecognizer {

    /** Words/apostrophes only — punctuation and digits split tokens apart. */
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}'’]+")

    /**
     * Reference signals valid ANYWHERE in the sentence: personal pronouns,
     * pronominal adverbs ("davon/damit/dazu"), and demonstratives that are not
     * also common articles. DE + EN, both spellings of the umlaut forms.
     */
    private val REFERENCE_SIGNALS: Set<String> = setOf(
        // DE — personal / possessive
        "ihn", "ihm", "ihr", "ihnen", "sie", "es", "deren", "dessen", "derer",
        // DE — pronominal adverbs (the classic "was macht man damit?" shape)
        "davon", "damit", "dazu", "dafür", "dafuer", "darin", "darauf", "daraus",
        "darüber", "darueber", "dabei", "denen",
        // DE — demonstratives that are never plain articles
        "diese", "dieser", "dieses", "diesem", "diesen", "dies",
        "derselbe", "dasselbe", "denselben",
        // EN — personal / possessive
        "it", "it's", "its", "them", "they", "their", "theirs",
        "him", "his", "her", "hers", "he", "she",
        // EN — demonstratives (rarely conjunctions, unlike "that")
        "this", "these", "those",
    )

    /**
     * Signals that only count SENTENCE-INITIALLY. „Das isst man roh." is a
     * demonstrative; „Wozu isst man das Brot?" is an article — position is the
     * only deterministic way to tell them apart without a parser.
     */
    private val INITIAL_SIGNALS: Set<String> = setOf(
        "das", "der", "die", "dem", "den", "that",
    )

    /**
     * Capitalized tokens that are NOT nouns: German polite address / possessive
     * and the English "I". Everything else capitalized past position 0 counts as
     * a noun and therefore as a plausible own referent.
     */
    private val CAPITALIZED_NON_NOUNS: Set<String> = setOf(
        "Sie", "Ihnen", "Ihr", "Ihre", "Ihrem", "Ihren", "Ihrer", "Ihres", "I",
    )

    /**
     * TRUE iff [text] carries a reference signal and offers no own noun as its
     * referent — i.e. the sentence alone cannot be looked up.
     */
    fun carriesUnresolvedReference(text: String): Boolean {
        val tokens = text.trim().split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        if (carriesOwnNoun(tokens)) return false
        val lower = tokens.map { it.lowercase() }
        if (lower.first() in INITIAL_SIGNALS) return true
        return lower.any { it in REFERENCE_SIGNALS }
    }

    /** A capitalized token past position 0 that is not a known function word. */
    private fun carriesOwnNoun(tokens: List<String>): Boolean =
        tokens.drop(1).any { token ->
            token.first().isUpperCase() && token !in CAPITALIZED_NON_NOUNS
        }
}

/**
 * **ContextHint** — the two lines of conversation that travel with an anaphoric
 * lookup, and nothing else.
 *
 * **Privacy contract (binding, see [PendingLookup.contextHint]):** a hint is only
 * ever built when [AnaphoraRecognizer.carriesUnresolvedReference] fired, it only
 * travels into the already opt-in escalation path, it is hard-capped at
 * [MAX_CHARS], and it never becomes a [de.hoshi.core.dto.ChatEvent] field — the
 * diary is derived exclusively from those events and therefore stays content-free
 * by construction.
 *
 * **Resolution is the cloud's job, not ours.** We do not guess which word the
 * pronoun means; we hand over the last exchange and let the model bind it. That
 * keeps this bauteil deterministic and keeps the failure mode honest: too little
 * context reads as "nothing found", never as a confident wrong referent.
 */
object ContextHint {

    /** Hard ceiling of the whole hint — a hint is a reminder, not a history dump. */
    const val MAX_CHARS: Int = 300

    /** Per-side budgets; the user sentence usually carries the referent noun. */
    private const val USER_BUDGET: Int = 150
    private const val ASSISTANT_BUDGET: Int = 150

    /** Look back at most two exchanges (user+assistant each). */
    private const val WINDOW_MESSAGES: Int = 4

    private const val ROLE_USER: String = "user"
    private const val ROLE_ASSISTANT: String = "assistant"

    private val WHITESPACE = Regex("\\s+")

    /**
     * The compact hint from [turns] (last two exchanges), or `null` when the
     * window holds nothing usable — no history, only affirmations, only lookup
     * requests. `null` means: park exactly what we park today.
     *
     * Contentless turns are filtered on the USER side only ("ja" / "schau online
     * nach" carry no referent); the assistant side is taken as-is because its
     * answer is usually where the referent noun is spelled out.
     */
    fun of(turns: List<ChatMessage>, language: Language): String? {
        val window = turns.takeLast(WINDOW_MESSAGES)
        val user = window.lastOrNull { it.role == ROLE_USER && carriesSubstance(it.content) }?.content
        val assistant = window.lastOrNull { it.role == ROLE_ASSISTANT && it.content.isNotBlank() }?.content
        if (user == null && assistant == null) return null
        val parts = buildList {
            user?.let { add("${language.deOr("Nutzer", "User")}: ${clip(it, USER_BUDGET)}") }
            assistant?.let { add("Hoshi: ${clip(it, ASSISTANT_BUDGET)}") }
        }
        return clip(parts.joinToString(" "), MAX_CHARS).takeIf { it.isNotBlank() }
    }

    /**
     * The ONE outbound shape of an escalation query. No hint ⇒ [query] byte for
     * byte (every existing lookup stays identical); with a hint ⇒ context first,
     * question second, so the model binds the pronoun before it answers.
     */
    fun escalationQuery(query: String, hint: String?, language: Language): String {
        val trimmed = hint?.trim().orEmpty()
        if (trimmed.isEmpty()) return query
        return "${language.deOr("Kontext", "Context")}: $trimmed\n" +
            "${language.deOr("Frage", "Question")}: $query"
    }

    /** A bare "ja" or "schau online nach" names nothing — it is not a referent. */
    private fun carriesSubstance(content: String): Boolean =
        content.isNotBlank() &&
            !AffirmationRecognizer.matches(content) &&
            !LookupIntentRecognizer.matches(content)

    /** Collapse whitespace, then cut on a word boundary and mark the cut. */
    private fun clip(text: String, max: Int): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= max) return flat
        val head = flat.take(max - 1)
        val cut = head.lastIndexOf(' ')
        return (if (cut > max / 2) head.take(cut) else head).trimEnd() + "…"
    }
}
