package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaInfo
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * An OPEN "which room?" ask: a switch intent that failed only on the room.
 * [domain]/[service]/[slots] carry the parked ToolCall WITHOUT `area_id` —
 * the next turn's room answer completes it; nothing here is ever executed
 * directly (completion runs through the normal capability-gated tool path).
 */
data class PendingAreaClarify(
    val domain: String,
    val service: String,
    val slots: Map<String, Any?> = emptyMap(),
    val language: Language = Language.DEFAULT,
    val ts: Instant = Instant.now(),
)

/**
 * Session memory of the open room ask — [PendingLookupPort] idiom line by line
 * (own store per pending kind, one-shot [consume], TTL in the store, channel-
 * namespaced [ConversationKeys] key behind [PendingTurnArbiter]). Deliberate
 * deviations, both contract:
 *  - No NONE default: this is a repair of broken behaviour (stateless room ask),
 *    not a feature — always on, like [ExecutionClaimGate].
 *  - [consume] reports expiry instead of hiding it as `null`: behaviour MUST
 *    treat [Consumed.expired] as absent; only the diary may see it.
 */
interface PendingAreaClarifyPort {
    /** Parks [pending] as the open room ask for [key] (replaces an older one). */
    fun offer(key: String, pending: PendingAreaClarify)

    /**
     * One-shot: returns and removes the entry for [key], `null` if none.
     * [Consumed.expired] = TTL passed — callers discard it (diary-only signal).
     */
    fun consume(key: String): Consumed?

    /** Consume outcome; [expired] entries are behaviourally absent. */
    data class Consumed(val pending: PendingAreaClarify, val expired: Boolean)

    companion object {
        /** Same TTL as the sibling pendings — an ask from earlier binds no answer from now. */
        val DEFAULT_TTL: Duration = Duration.ofSeconds(120)

        // Diary values for the additive `pendingClarify` field (absent = no clarify cycle).
        const val OUTCOME_ASKED = "asked"
        const val OUTCOME_RESOLVED = "resolved"
        const val OUTCOME_EXPIRED = "expired"
        const val OUTCOME_ABANDONED = "abandoned"
    }
}

/**
 * In-memory impl, mirror of [InMemoryPendingLookupStore]: thread-safe, one-shot
 * via atomic remove, opportunistic purge of expired foreign entries on [offer].
 * The store is the time authority — stamp and expiry use the same [clock].
 */
class InMemoryPendingAreaClarifyStore(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = PendingAreaClarifyPort.DEFAULT_TTL,
) : PendingAreaClarifyPort {
    private val byKey = ConcurrentHashMap<String, PendingAreaClarify>()

    override fun offer(key: String, pending: PendingAreaClarify) {
        if (key.isBlank() || pending.domain.isBlank() || pending.service.isBlank()) return
        byKey.entries.removeIf { expired(it.value) }
        byKey[key] = pending.copy(ts = clock.instant())
    }

    override fun consume(key: String): PendingAreaClarifyPort.Consumed? {
        val pending = byKey.remove(key) ?: return null
        return PendingAreaClarifyPort.Consumed(pending, expired(pending))
    }

    private fun expired(pending: PendingAreaClarify): Boolean =
        Duration.between(pending.ts, clock.instant()) > ttl
}

/**
 * Deterministic room-answer recognizer (counterpart of [LocationAnswerRecognizer],
 * but closed-world): the WHOLE utterance must resolve against the live area
 * catalog — bare ("Wohnzimmer"), prepositional ("im Wohnzimmer", "in der Küche")
 * or with a trailing politeness filler. No model, no guessing: anything that is
 * not exactly a catalog room is not an answer (false negatives are cheap, the
 * turn just runs normally; a false positive would switch the wrong room).
 */
object AreaAnswerRecognizer {

    /** Raw-token cap — longer utterances are sentences, never a bare room answer. */
    const val MAX_TOKENS: Int = 5

    private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

    /** Leading prepositions/articles of a room answer (DE + EN). */
    private val LEADING = setOf("in", "im", "ins", "der", "die", "das", "dem", "den", "the")

    /** Trailing politeness fillers ("Wohnzimmer bitte"). */
    private val TRAILING = setOf("bitte", "please", "danke", "thanks")

    /** Resolves [text] to a real `area_id` against [areas], or `null` (not a room answer). */
    fun areaId(text: String, areas: List<AreaInfo>): String? {
        val tokens = text.lowercase().split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null
        val core = tokens.dropWhile { it in LEADING }.dropLastWhile { it in TRAILING }
        // Aliases are at most two tokens ("living room") — more is a sentence.
        if (core.isEmpty() || core.size > 2) return null
        return aliasIndex(areas)[core.joinToString(" ")]
    }

    /** Alias→area_id table from the catalog — same defensive extras as the classifier's roomIndex. */
    private fun aliasIndex(areas: List<AreaInfo>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (area in areas) {
            for (alias in area.aliases) map.putIfAbsent(alias, area.areaId)
            map.putIfAbsent(area.areaId, area.areaId)
            map.putIfAbsent(area.label.lowercase(), area.areaId)
        }
        return map
    }
}
