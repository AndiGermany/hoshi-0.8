package de.hoshi.adapters.news

import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsPort
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSnapshot
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * One hardened feed source and one local cache. Refreshing is an explicit
 * scheduler/manual-edge operation; [latest] is read-only and never performs
 * network I/O. The module has no Brain dependency, so ingest cannot trigger
 * background LLM calls. The cache is household-global: [CurrentAffairsQuery.viewerId]
 * may filter/select in a later slice but never causes another source fetch here.
 */
class FeedCurrentAffairsAdapter(
    dbPath: Path = defaultDbPath(),
    private val source: FeedSourceDefinition = FeedSourceDefinition.TAGESSCHAU,
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = Duration.ofMinutes(90),
    timeout: Duration = Duration.ofSeconds(5),
    maxResponseBytes: Int = 1_000_000,
    maxRedirects: Int = 2,
    private val backoff: ExponentialBackoffPolicy = ExponentialBackoffPolicy(),
    private val nanoTime: () -> Long = System::nanoTime,
) : CurrentAffairsPort, AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val store = SqliteCurrentAffairsStore(dbPath)
    private val fetcher = FeedFetcher(source, timeout, maxResponseBytes, maxRedirects, nanoTime = nanoTime)
    private val parser = SafeFeedParser()
    private val canonicalizer = NewsItemCanonicalizer(source)

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero)
    }

    /** Explicit, never-throw refresh entrypoint for a scheduler or manual refresh edge. */
    @Synchronized
    fun refresh(): FeedRefreshReport {
        val started = nanoTime()
        val report = runCatching { refreshOnce() }.getOrElse {
            reportStoreFailure(runCatching { clock.instant() }.getOrDefault(Instant.EPOCH))
        }
        val durationMs = ((nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
        return report.copy(metrics = report.metrics.copy(durationMs = durationMs)).also(::logReport)
    }

    private fun refreshOnce(): FeedRefreshReport {
        val now = clock.instant()
        val state = runCatching { store.feedState(source.source) }.getOrElse {
            return reportStoreFailure(now)
        }
        state.nextAttemptAt?.takeIf { now < it }?.let { retryAt ->
            runCatching { store.recordBackoffSkip(source.source, now) }
            return FeedRefreshReport(FeedRefreshStatus.BACKING_OFF, now, nextAttemptAt = retryAt)
        }

        return when (val fetched = fetcher.fetch(state.validators)) {
            is FeedFetchResult.Modified -> handleModified(now, state, fetched)
            is FeedFetchResult.NotModified -> handleNotModified(now, fetched)
            is FeedFetchResult.Failed -> fail(now, state, fetched.reason, fetched.status, metricsOf(fetched))
        }
    }

    override fun latest(query: CurrentAffairsQuery): CurrentAffairsSnapshot = runCatching {
        val observedAt = clock.instant()
        if (query.sources.isNotEmpty() && source.source !in query.sources) {
            CurrentAffairsSnapshot(emptyList(), observedAt, null, CurrentAffairsFreshness.EMPTY)
        } else {
            val stored = store.snapshot(setOf(source.source), query.limit, observedAt)
            val freshness = when {
                stored.unavailable && stored.items.isEmpty() -> CurrentAffairsFreshness.UNAVAILABLE
                stored.items.isEmpty() -> CurrentAffairsFreshness.EMPTY
                stored.lastSuccessfulRefreshAt == null -> CurrentAffairsFreshness.STALE
                Duration.between(stored.lastSuccessfulRefreshAt, observedAt) > staleAfter ->
                    CurrentAffairsFreshness.STALE
                else -> CurrentAffairsFreshness.FRESH
            }
            CurrentAffairsSnapshot(stored.items, observedAt, stored.lastSuccessfulRefreshAt, freshness)
        }
    }.getOrElse {
        CurrentAffairsSnapshot(
            emptyList(),
            runCatching { clock.instant() }.getOrDefault(Instant.EPOCH),
            null,
            CurrentAffairsFreshness.UNAVAILABLE,
        )
    }

    /** `null` means the metrics store could not be read; zeroes remain an honest no-traffic day. */
    fun dailyMetrics(day: LocalDate): DailyFetchMetrics? = runCatching {
        store.dailyMetrics(source.source, day)
    }.getOrNull()

    override fun close() = store.close()

    private fun handleModified(
        now: Instant,
        state: FeedState,
        fetched: FeedFetchResult.Modified,
    ): FeedRefreshReport {
        val parsed = try {
            parser.parse(fetched.body)
        } catch (_: FeedParseException) {
            return fail(now, state, FeedFailureReason.XML_REJECTED, 200, metricsOf(fetched))
        }
        val (sponsored, editorial) = parsed.entries.partition { SponsoredTitlePolicy.isSponsored(it.title) }
        val canonical = editorial.mapNotNull { canonicalizer.canonicalize(it, now) }
        val rejected = parsed.rejectedEntries + (editorial.size - canonical.size)
        val baseMetrics = metricsOf(fetched).copy(
            parsedItems = parsed.entries.size,
            rejectedItems = rejected,
            rejectedAds = sponsored.size,
        )
        // A valid but ad-only response is a successful empty editorial update,
        // not a broken feed that should trigger retry traffic and backoff.
        if (canonical.isEmpty() && (editorial.isNotEmpty() || sponsored.isEmpty())) {
            return fail(now, state, FeedFailureReason.NO_VALID_ITEMS, 200, baseMetrics)
        }
        val counts = runCatching {
            store.applyModified(source.source, canonical, fetched.validators, now, baseMetrics)
        }.getOrElse {
            return reportStoreFailure(now, baseMetrics)
        }
        val metrics = baseMetrics.copy(
            newItems = counts.newItems,
            updatedItems = counts.updatedItems,
            duplicateItems = counts.duplicateItems,
            expiredItems = counts.expiredItems,
        )
        return FeedRefreshReport(FeedRefreshStatus.UPDATED, now, metrics = metrics)
    }

    private fun handleNotModified(
        now: Instant,
        fetched: FeedFetchResult.NotModified,
    ): FeedRefreshReport {
        val baseMetrics = metricsOf(fetched)
        val expired = runCatching {
            store.applyNotModified(source.source, fetched.validators, now, baseMetrics)
        }.getOrElse {
            return reportStoreFailure(now, baseMetrics)
        }
        val metrics = baseMetrics.copy(expiredItems = expired)
        return FeedRefreshReport(FeedRefreshStatus.NOT_MODIFIED, now, metrics = metrics)
    }

    private fun fail(
        now: Instant,
        state: FeedState,
        reason: FeedFailureReason,
        responseStatus: Int?,
        metrics: FeedRefreshMetrics,
    ): FeedRefreshReport {
        val retryAt = now.plus(backoff.delay(state.consecutiveFailures + 1))
        val withStatus = metrics.copy(responseStatus = responseStatus)
        if (runCatching { store.recordFailure(source.source, now, retryAt, withStatus) }.isFailure) {
            return reportStoreFailure(now, withStatus)
        }
        return FeedRefreshReport(FeedRefreshStatus.UNAVAILABLE, now, reason, retryAt, withStatus)
    }

    private fun metricsOf(result: FeedFetchResult): FeedRefreshMetrics = FeedRefreshMetrics(
        requests = result.requests,
        requestBytesEstimate = result.requestBytesEstimate,
        responseBytesEstimate = result.responseBytesEstimate,
        durationMs = result.durationMs,
        responseStatus = when (result) {
            is FeedFetchResult.Modified -> 200
            is FeedFetchResult.NotModified -> 304
            is FeedFetchResult.Failed -> result.status
        },
    )

    private fun reportStoreFailure(
        now: Instant,
        metrics: FeedRefreshMetrics = FeedRefreshMetrics(),
    ): FeedRefreshReport = FeedRefreshReport(
        FeedRefreshStatus.UNAVAILABLE,
        now,
        FeedFailureReason.STORE,
        metrics = metrics,
    )

    private fun logReport(report: FeedRefreshReport) {
        val metrics = report.metrics
        val args = arrayOf(
            source.source.name.lowercase(),
            report.status.name.lowercase(),
            report.failureReason?.name?.lowercase(),
            metrics.responseStatus,
            metrics.requests,
            metrics.requestBytesEstimate,
            metrics.responseBytesEstimate,
            metrics.newItems,
            metrics.updatedItems,
            metrics.duplicateItems,
            metrics.rejectedItems,
            metrics.rejectedAds,
            metrics.expiredItems,
            report.nextAttemptAt,
            metrics.durationMs,
        )
        val template = "[lagebild-fetch] source={} status={} reason={} httpStatus={} requests={} " +
            "requestBytes~={} responseBytes~={} new={} updated={} duplicates={} rejected={} rejectedAds={} expired={} " +
            "nextAttemptAt={} durationMs={}"
        if (report.status == FeedRefreshStatus.UNAVAILABLE) log.warn(template, *args) else log.info(template, *args)
    }

    companion object {
        fun defaultDbPath(): Path = Paths.get(System.getProperty("user.home"), ".hoshi", "current-affairs.db")
    }
}

/** Backwards-compatible name for the already wired first Tagesschau slice. */
typealias TagesschauCurrentAffairsAdapter = FeedCurrentAffairsAdapter
