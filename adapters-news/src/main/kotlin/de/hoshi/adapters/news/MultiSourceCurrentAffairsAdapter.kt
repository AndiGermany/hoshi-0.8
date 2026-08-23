package de.hoshi.adapters.news

import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsItem
import de.hoshi.core.port.CurrentAffairsPort
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSnapshot
import de.hoshi.core.port.CurrentAffairsSourceId
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration

data class MultiSourceRefreshReport(
    val activeSources: Set<CurrentAffairsSourceId>,
    val reports: Map<CurrentAffairsSourceId, FeedRefreshReport>,
    val unsupportedActiveSources: Set<CurrentAffairsSourceId> = emptySet(),
    val settingsAvailable: Boolean = true,
)

/**
 * Household-global current-affairs aggregate. The active-source supplier is
 * resolved at every read/refresh so a later Settings seam can change sources
 * without rebuilding this object. Inactive sources are neither fetched nor
 * merged. A failing supplier becomes an honest UNAVAILABLE snapshot and never
 * silently falls back to a broader source set.
 */
class MultiSourceCurrentAffairsAdapter(
    dbDirectory: Path = defaultDbDirectory(),
    sourceDefinitions: Map<CurrentAffairsSourceId, FeedSourceDefinition> = FeedSourceDefinition.WAVE_1,
    private val activeSources: () -> Set<CurrentAffairsSourceId> = { DEFAULT_ACTIVE_SOURCES },
    private val clock: Clock = Clock.systemUTC(),
    staleAfter: Duration = Duration.ofMinutes(90),
    timeout: Duration = Duration.ofSeconds(5),
    maxResponseBytes: Int = 1_000_000,
    maxRedirects: Int = 2,
    backoffFactory: (CurrentAffairsSourceId) -> ExponentialBackoffPolicy = { ExponentialBackoffPolicy() },
    nanoTime: () -> Long = System::nanoTime,
) : CurrentAffairsPort, AutoCloseable {
    private val adapters: Map<CurrentAffairsSourceId, FeedCurrentAffairsAdapter>

    init {
        require(sourceDefinitions.isNotEmpty())
        require(sourceDefinitions.all { (id, definition) -> id == definition.source })
        adapters = sourceDefinitions.entries
            .sortedBy { it.key.ordinal }
            .associate { (id, definition) ->
                id to FeedCurrentAffairsAdapter(
                    dbPath = dbPathFor(dbDirectory, id),
                    source = definition,
                    clock = clock,
                    staleAfter = staleAfter,
                    timeout = timeout,
                    maxResponseBytes = maxResponseBytes,
                    maxRedirects = maxRedirects,
                    backoff = backoffFactory(id),
                    nanoTime = nanoTime,
                )
            }
    }

    /** Refreshes each currently active configured source exactly once, in stable source order. */
    @Synchronized
    fun refresh(): MultiSourceRefreshReport {
        val configured = resolveActiveSources()
            ?: return MultiSourceRefreshReport(emptySet(), emptyMap(), settingsAvailable = false)
        val supported = configured.intersect(adapters.keys)
        val reports = supported.sortedBy(CurrentAffairsSourceId::ordinal).associateWith { source ->
            adapters.getValue(source).refresh()
        }
        return MultiSourceRefreshReport(
            activeSources = supported,
            reports = reports,
            unsupportedActiveSources = configured - adapters.keys,
        )
    }

    override fun latest(query: CurrentAffairsQuery): CurrentAffairsSnapshot {
        val observedAt = runCatching { clock.instant() }.getOrDefault(java.time.Instant.EPOCH)
        return runCatching {
            val active = resolveActiveSources()
                ?: return CurrentAffairsSnapshot(
                    emptyList(),
                    observedAt,
                    null,
                    CurrentAffairsFreshness.UNAVAILABLE,
                )
            val selected = active.intersect(adapters.keys).let { enabled ->
                if (query.sources.isEmpty()) enabled else enabled.intersect(query.sources)
            }
            if (selected.isEmpty()) {
                return CurrentAffairsSnapshot(emptyList(), observedAt, null, CurrentAffairsFreshness.EMPTY)
            }

            val perSource = selected.sortedBy(CurrentAffairsSourceId::ordinal).map { source ->
                adapters.getValue(source).latest(
                    CurrentAffairsQuery(
                        viewerId = query.viewerId,
                        sources = setOf(source),
                        limit = query.limit,
                    ),
                )
            }
            val limit = query.limit.coerceIn(1, 50)
            val merged = selectWithSourcePresence(perSource, limit)
            CurrentAffairsSnapshot(
                items = merged,
                observedAt = observedAt,
                lastSuccessfulRefreshAt = perSource.mapNotNull { it.lastSuccessfulRefreshAt }.minOrNull(),
                freshness = mergedFreshness(perSource.map { it.freshness }, merged),
            )
        }.getOrElse {
            CurrentAffairsSnapshot(emptyList(), observedAt, null, CurrentAffairsFreshness.UNAVAILABLE)
        }
    }

    override fun close() {
        adapters.values.forEach { runCatching { it.close() } }
    }

    private fun resolveActiveSources(): Set<CurrentAffairsSourceId>? = runCatching {
        activeSources().toSet()
    }.getOrNull()

    private fun mergedFreshness(
        sourceFreshness: List<CurrentAffairsFreshness>,
        items: List<CurrentAffairsItem>,
    ): CurrentAffairsFreshness = when {
        items.isEmpty() && CurrentAffairsFreshness.UNAVAILABLE in sourceFreshness ->
            CurrentAffairsFreshness.UNAVAILABLE
        items.isEmpty() -> CurrentAffairsFreshness.EMPTY
        sourceFreshness.any {
            it == CurrentAffairsFreshness.UNAVAILABLE || it == CurrentAffairsFreshness.STALE
        } -> CurrentAffairsFreshness.STALE
        else -> CurrentAffairsFreshness.FRESH
    }

    /**
     * A shared recency-only limit can erase a healthy, slower source completely.
     * If the result has room, keep the newest item from every non-empty source,
     * fill the remaining slots by global recency, then restore chronological order.
     * With fewer slots than populated sources, recency remains the deterministic
     * tie-breaker because complete source presence is mathematically impossible.
     */
    private fun selectWithSourcePresence(
        snapshots: List<CurrentAffairsSnapshot>,
        limit: Int,
    ): List<CurrentAffairsItem> {
        val globallyOrdered = snapshots.asSequence()
            .flatMap { it.items.asSequence() }
            .sortedWith(ITEM_ORDER)
            .toList()
        val sourceLeaders = snapshots.mapNotNull { snapshot -> snapshot.items.minWithOrNull(ITEM_ORDER) }
        if (limit < sourceLeaders.size) return globallyOrdered.take(limit)

        val reserved = sourceLeaders.map { it.source to it.id }.toSet()
        return buildList {
            addAll(sourceLeaders)
            globallyOrdered.asSequence()
                .filterNot { (it.source to it.id) in reserved }
                .take(limit - sourceLeaders.size)
                .forEach(::add)
        }.sortedWith(ITEM_ORDER)
    }

    companion object {
        val DEFAULT_ACTIVE_SOURCES: Set<CurrentAffairsSourceId> = setOf(
            CurrentAffairsSourceId.TAGESSCHAU,
            CurrentAffairsSourceId.HEISE,
            CurrentAffairsSourceId.GOLEM,
        )

        fun defaultDbDirectory(): Path = Paths.get(System.getProperty("user.home"), ".hoshi")

        fun dbPathFor(directory: Path, source: CurrentAffairsSourceId): Path = when (source) {
            CurrentAffairsSourceId.TAGESSCHAU -> directory.resolve("current-affairs.db")
            else -> directory.resolve("current-affairs-${source.name.lowercase()}.db")
        }

        private val ITEM_ORDER = compareByDescending<CurrentAffairsItem> { it.publishedAt ?: it.fetchedAt }
            .thenBy { it.source.ordinal }
            .thenBy(CurrentAffairsItem::id)
    }
}
