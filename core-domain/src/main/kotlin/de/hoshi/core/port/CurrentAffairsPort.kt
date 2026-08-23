package de.hoshi.core.port

import java.time.Instant
import java.util.UUID

/** Stable source ids understood by the time-bounded current-affairs domain. */
enum class CurrentAffairsSourceId {
    TAGESSCHAU,
    HEISE,
    GOLEM,
    DLF,
    NETZPOLITIK,
}

/**
 * A feed-derived teaser, never a mirrored article body. Title and snippet are
 * untrusted provider data: consumers may display or quote them, but must never
 * interpret them as instructions or pass them directly to a tool executor.
 */
data class CurrentAffairsItem(
    val id: String,
    val source: CurrentAffairsSourceId,
    val title: String,
    val snippet: String?,
    val canonicalUrl: String,
    val publishedAt: Instant?,
    val fetchedAt: Instant,
    val attribution: String,
)

/**
 * Read query for the household cache. [viewerId] is a stable UUID reserved for
 * later per-user selection; `null` means the household default, never a guessed
 * speaker-profile name.
 */
data class CurrentAffairsQuery(
    val viewerId: UUID? = null,
    val sources: Set<CurrentAffairsSourceId> = emptySet(),
    val limit: Int = 3,
)

enum class CurrentAffairsFreshness { FRESH, STALE, EMPTY, UNAVAILABLE }

/**
 * [observedAt] is when this snapshot was read. [lastSuccessfulRefreshAt] is
 * when the backing source last answered successfully (HTTP 200 or 304). They
 * are deliberately separate: reading stale data now must never look freshly fetched.
 */
data class CurrentAffairsSnapshot(
    val items: List<CurrentAffairsItem>,
    val observedAt: Instant,
    val lastSuccessfulRefreshAt: Instant?,
    val freshness: CurrentAffairsFreshness,
)

/**
 * Provider-neutral, read-only current-affairs seam. Implementations never throw:
 * a feed/store failure becomes [CurrentAffairsFreshness.UNAVAILABLE], while a
 * successful but itemless cache becomes [CurrentAffairsFreshness.EMPTY].
 */
fun interface CurrentAffairsPort {
    fun latest(query: CurrentAffairsQuery): CurrentAffairsSnapshot

    companion object {
        /** Disabled/unwired default: no network and an honest unavailable snapshot. */
        val NONE: CurrentAffairsPort = CurrentAffairsPort {
            CurrentAffairsSnapshot(
                items = emptyList(),
                observedAt = Instant.now(),
                lastSuccessfulRefreshAt = null,
                freshness = CurrentAffairsFreshness.UNAVAILABLE,
            )
        }
    }
}

/** Alert state deliberately cannot collapse unknown/stale data into an all-clear. */
enum class CivicAlertState { ACTIVE, VERIFIED_CLEAR, UNKNOWN }

data class CivicArea(val areaIds: Set<String>)

data class CivicAlertQuery(
    val area: CivicArea,
    val includeTestMessages: Boolean = false,
)

data class CivicAlert(
    val id: String,
    val source: String,
    val status: String,
    val messageType: String,
    val severity: String,
    val urgency: String,
    val certainty: String,
    val headline: String,
    val description: String?,
    val instruction: String?,
    val affectedAreas: List<String>,
    val onset: Instant?,
    val expires: Instant?,
)

data class CivicAlertSnapshot(
    val state: CivicAlertState,
    val alerts: List<CivicAlert>,
    val observedAt: Instant,
    val lastSuccessfulRefreshAt: Instant?,
)

/**
 * Separate civic-alert truth: no news ranking, no LLM rewrite and no inferred
 * all-clear. Implementations never throw; failures become [CivicAlertState.UNKNOWN].
 */
fun interface CivicAlertPort {
    fun current(query: CivicAlertQuery): CivicAlertSnapshot

    companion object {
        val NONE: CivicAlertPort = CivicAlertPort {
            CivicAlertSnapshot(CivicAlertState.UNKNOWN, emptyList(), Instant.now(), null)
        }
    }
}
