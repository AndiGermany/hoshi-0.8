package de.hoshi.adapters.news

import de.hoshi.core.port.CurrentAffairsSourceId
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

data class FeedSourceDefinition(
    val source: CurrentAffairsSourceId,
    val feedUri: URI,
    val allowedFeedHosts: Set<String>,
    val allowedArticleHosts: Set<String>,
    val attribution: String,
    val allowInsecureLoopback: Boolean = false,
) {
    init {
        require(allowedFeedHosts.isNotEmpty())
        require(allowedArticleHosts.isNotEmpty())
        require(isAllowedUri(feedUri, allowedFeedHosts, allowInsecureLoopback))
    }

    companion object {
        /**
         * Official homepage feed. Tagesschau permits feed content only for
         * private/non-commercial use; this local cache stores teasers, never article bodies.
         * See https://www.tagesschau.de/infoservices/rssfeeds
         */
        val TAGESSCHAU = FeedSourceDefinition(
            source = CurrentAffairsSourceId.TAGESSCHAU,
            feedUri = URI("https://www.tagesschau.de/index~rss2.xml"),
            allowedFeedHosts = setOf("www.tagesschau.de"),
            allowedArticleHosts = setOf("tagesschau.de", "www.tagesschau.de"),
            attribution = "tagesschau.de",
        )

        /**
         * Official Top-News Atom feed. heise permits revocable, free feed reuse
         * with active article links, but not feed images. Hoshi stores only the
         * title, summary and canonical link.
         * See https://www.heise.de/news-extern/news.html
         */
        val HEISE = FeedSourceDefinition(
            source = CurrentAffairsSourceId.HEISE,
            feedUri = URI("https://www.heise.de/rss/heise-top-atom.xml"),
            allowedFeedHosts = setOf("www.heise.de"),
            allowedArticleHosts = setOf("heise.de", "www.heise.de"),
            attribution = "heise online · RSS: Überschrift, Anriss und aktiver Link; keine Bilder",
        )

        /**
         * Official Golem "Alle News" Atom feed. Golem documents restricted
         * commercial use, so the attribution keeps that condition visible;
         * Hoshi mirrors no article body or image.
         * See https://www.golem.de/sonstiges/rss.html
         */
        val GOLEM = FeedSourceDefinition(
            source = CurrentAffairsSourceId.GOLEM,
            feedUri = URI("https://rss.golem.de/rss.php?feed=ATOM1.0"),
            allowedFeedHosts = setOf("rss.golem.de"),
            allowedArticleHosts = setOf("golem.de", "www.golem.de"),
            attribution = "Golem.de · Atom-Feed; kommerzielle Nutzung eingeschränkt",
        )

        val WAVE_1: Map<CurrentAffairsSourceId, FeedSourceDefinition> =
            listOf(TAGESSCHAU, HEISE, GOLEM).associateBy(FeedSourceDefinition::source)

        internal fun isAllowedUri(uri: URI, hosts: Set<String>, allowInsecureLoopback: Boolean): Boolean {
            val host = uri.host?.lowercase() ?: return false
            if (uri.userInfo != null || uri.fragment != null) return false
            val insecureLoopback = allowInsecureLoopback && uri.scheme.equals("http", ignoreCase = true) && isLoopback(host)
            val schemeAllowed = uri.scheme.equals("https", ignoreCase = true) || insecureLoopback
            val portAllowed = uri.port == -1 || uri.port == 443 || insecureLoopback
            return schemeAllowed && portAllowed && hosts.any { allowed ->
                host == allowed.lowercase() || host.endsWith(".${allowed.lowercase()}")
            }
        }

        private fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
    }
}

internal data class RawFeedEntry(
    val sourceId: String?,
    val title: String?,
    val snippet: String?,
    val link: String?,
    val publishedAt: Instant?,
)

internal data class ParsedFeed(
    val entries: List<RawFeedEntry>,
    val rejectedEntries: Int,
)

internal data class CanonicalFeedItem(
    val id: String,
    val source: CurrentAffairsSourceId,
    val sourceItemId: String,
    val canonicalUrl: String,
    val title: String,
    val snippet: String?,
    val attribution: String,
    val publishedAt: Instant?,
    val fetchedAt: Instant,
    val contentHash: String,
    val expiresAt: Instant,
)

internal data class FeedValidators(
    val etag: String? = null,
    val lastModified: String? = null,
)

internal data class FeedState(
    val validators: FeedValidators = FeedValidators(),
    val lastSuccessfulRefreshAt: Instant? = null,
    val consecutiveFailures: Int = 0,
    val nextAttemptAt: Instant? = null,
    val lastFailureAt: Instant? = null,
)

enum class FeedRefreshStatus { UPDATED, NOT_MODIFIED, BACKING_OFF, UNAVAILABLE }

enum class FeedFailureReason {
    NETWORK,
    TIMEOUT,
    HTTP_STATUS,
    REDIRECT_REJECTED,
    RESPONSE_TOO_LARGE,
    CONTENT_TYPE,
    XML_REJECTED,
    NO_VALID_ITEMS,
    STORE,
    UNKNOWN,
}

data class FeedRefreshMetrics(
    val requests: Int = 0,
    val responseStatus: Int? = null,
    /** Approximate request-line and header bytes; TLS/TCP framing is not included. */
    val requestBytesEstimate: Long = 0,
    /** Response status/header/body bytes; TLS/TCP framing is not included. */
    val responseBytesEstimate: Long = 0,
    val parsedItems: Int = 0,
    val rejectedItems: Int = 0,
    /** Entries rejected by the explicit advertising-title policy, not malformed entries. */
    val rejectedAds: Int = 0,
    val newItems: Int = 0,
    val updatedItems: Int = 0,
    val duplicateItems: Int = 0,
    val expiredItems: Int = 0,
    val durationMs: Long = 0,
)

data class FeedRefreshReport(
    val status: FeedRefreshStatus,
    val observedAt: Instant,
    val failureReason: FeedFailureReason? = null,
    val nextAttemptAt: Instant? = null,
    val metrics: FeedRefreshMetrics = FeedRefreshMetrics(),
)

data class DailyFetchMetrics(
    val source: CurrentAffairsSourceId,
    val day: LocalDate,
    val requests: Long,
    val modifiedResponses: Long,
    val notModifiedResponses: Long,
    val failures: Long,
    val backoffSkips: Long,
    val requestBytesEstimate: Long,
    val responseBytesEstimate: Long,
)

internal data class StoreWriteCounts(
    val newItems: Int,
    val updatedItems: Int,
    val duplicateItems: Int,
    val expiredItems: Int,
)

internal sealed interface FeedFetchResult {
    val requests: Int
    val requestBytesEstimate: Long
    val responseBytesEstimate: Long
    val durationMs: Long

    data class Modified(
        val body: ByteArray,
        val validators: FeedValidators,
        override val requests: Int,
        override val requestBytesEstimate: Long,
        override val responseBytesEstimate: Long,
        override val durationMs: Long,
    ) : FeedFetchResult

    data class NotModified(
        val validators: FeedValidators,
        override val requests: Int,
        override val requestBytesEstimate: Long,
        override val responseBytesEstimate: Long,
        override val durationMs: Long,
    ) : FeedFetchResult

    data class Failed(
        val reason: FeedFailureReason,
        val status: Int? = null,
        override val requests: Int,
        override val requestBytesEstimate: Long,
        override val responseBytesEstimate: Long,
        override val durationMs: Long,
    ) : FeedFetchResult
}

internal class FeedParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ExponentialBackoffPolicy(
    private val baseDelay: Duration = Duration.ofSeconds(30),
    private val maxDelay: Duration = Duration.ofMinutes(30),
    private val jitterMillis: (Long) -> Long = { upperExclusive ->
        if (upperExclusive <= 1) 0 else ThreadLocalRandom.current().nextLong(upperExclusive)
    },
) {
    init {
        require(!baseDelay.isNegative && !baseDelay.isZero)
        require(maxDelay >= baseDelay)
    }

    fun delay(failureNumber: Int): Duration {
        val shift = (failureNumber.coerceAtLeast(1) - 1).coerceAtMost(20)
        val exponential = baseDelay.toMillis().let { base ->
            if (base > Long.MAX_VALUE shr shift) Long.MAX_VALUE else base shl shift
        }.coerceAtMost(maxDelay.toMillis())
        val jitterCap = (exponential / 4).coerceAtLeast(1)
        val jitter = jitterMillis(jitterCap).coerceIn(0, jitterCap - 1)
        return Duration.ofMillis((exponential + jitter).coerceAtMost(maxDelay.toMillis()))
    }
}
