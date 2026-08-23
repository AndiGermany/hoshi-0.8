package de.hoshi.adapters.news

import de.hoshi.core.port.CurrentAffairsItem
import de.hoshi.core.port.CurrentAffairsSourceId
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal data class StoredCurrentAffairsSnapshot(
    val items: List<CurrentAffairsItem>,
    val lastSuccessfulRefreshAt: Instant?,
    val unavailable: Boolean,
)

internal class SqliteCurrentAffairsStore(dbPath: Path) : AutoCloseable {
    private val lock = Any()
    private val connection: Connection

    init {
        val path = dbPath.toAbsolutePath()
        path.parent?.let(Files::createDirectories)
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.createStatement().use { it.executeUpdate("PRAGMA busy_timeout = 2000") }
        initializeSchema()
    }

    fun feedState(source: CurrentAffairsSourceId): FeedState = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT etag, last_modified, last_successful_refresh_at,
                   consecutive_failures, next_attempt_at, last_failure_at
            FROM current_affairs_feed_state WHERE source = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, source.name)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@synchronized FeedState()
                FeedState(
                    validators = FeedValidators(rows.getString(1), rows.getString(2)),
                    lastSuccessfulRefreshAt = rows.getString(3)?.let(Instant::parse),
                    consecutiveFailures = rows.getInt(4),
                    nextAttemptAt = rows.getString(5)?.let(Instant::parse),
                    lastFailureAt = rows.getString(6)?.let(Instant::parse),
                )
            }
        }
    }

    fun applyModified(
        source: CurrentAffairsSourceId,
        items: List<CanonicalFeedItem>,
        validators: FeedValidators,
        refreshedAt: Instant,
        metrics: FeedRefreshMetrics,
    ): StoreWriteCounts = transaction {
        val expired = deleteExpired(refreshedAt)
        var newItems = 0
        var updatedItems = 0
        var duplicateItems = 0
        items.forEach { item ->
            val existing = existing(item)
            when {
                existing == null -> {
                    insert(item)
                    newItems += 1
                }
                existing.second == item.contentHash -> {
                    touch(existing.first, item.fetchedAt, item.expiresAt)
                    duplicateItems += 1
                }
                else -> {
                    update(existing.first, item)
                    updatedItems += 1
                }
            }
        }
        markSuccess(source, validators, refreshedAt)
        addDailyMetrics(source, refreshedAt, metrics, modified = 1)
        StoreWriteCounts(newItems, updatedItems, duplicateItems, expired)
    }

    fun applyNotModified(
        source: CurrentAffairsSourceId,
        validators: FeedValidators,
        refreshedAt: Instant,
        metrics: FeedRefreshMetrics,
    ): Int = transaction {
        val expired = deleteExpired(refreshedAt)
        markSuccess(source, validators, refreshedAt)
        addDailyMetrics(source, refreshedAt, metrics, notModified = 1)
        expired
    }

    fun recordFailure(
        source: CurrentAffairsSourceId,
        failedAt: Instant,
        nextAttemptAt: Instant,
        metrics: FeedRefreshMetrics,
    ) = transaction {
        connection.prepareStatement(
            """
            INSERT INTO current_affairs_feed_state
                (source, consecutive_failures, next_attempt_at, last_failure_at)
            VALUES (?, 1, ?, ?)
            ON CONFLICT(source) DO UPDATE SET
                consecutive_failures = consecutive_failures + 1,
                next_attempt_at = excluded.next_attempt_at,
                last_failure_at = excluded.last_failure_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, source.name)
            statement.setString(2, nextAttemptAt.toString())
            statement.setString(3, failedAt.toString())
            statement.executeUpdate()
        }
        addDailyMetrics(source, failedAt, metrics, failures = 1)
    }

    fun recordBackoffSkip(source: CurrentAffairsSourceId, at: Instant) = transaction {
        addDailyMetrics(source, at, FeedRefreshMetrics(), backoffSkips = 1)
    }

    fun snapshot(
        sources: Set<CurrentAffairsSourceId>,
        limit: Int,
        observedAt: Instant,
    ): StoredCurrentAffairsSnapshot = synchronized(lock) {
        val selected = sources.ifEmpty { setOf(CurrentAffairsSourceId.TAGESSCHAU) }
        val placeholders = selected.joinToString(",") { "?" }
        val items = connection.prepareStatement(
            """
            SELECT id, source, title, snippet, canonical_url, published_at, fetched_at, attribution
            FROM current_affairs_items
            WHERE source IN ($placeholders) AND expires_at > ?
            ORDER BY COALESCE(published_at, fetched_at) DESC, id ASC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            selected.forEach { statement.setString(index++, it.name) }
            statement.setString(index++, observedAt.toString())
            statement.setInt(index, limit.coerceIn(1, 50))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            CurrentAffairsItem(
                                id = rows.getString(1),
                                source = CurrentAffairsSourceId.valueOf(rows.getString(2)),
                                title = rows.getString(3),
                                snippet = rows.getString(4),
                                canonicalUrl = rows.getString(5),
                                publishedAt = rows.getString(6)?.let(Instant::parse),
                                fetchedAt = Instant.parse(rows.getString(7)),
                                attribution = rows.getString(8),
                            ),
                        )
                    }
                }
            }
        }
        val states = selected.map(::feedState)
        val successes = states.mapNotNull { it.lastSuccessfulRefreshAt }
        StoredCurrentAffairsSnapshot(
            items = items,
            lastSuccessfulRefreshAt = successes.minOrNull(),
            unavailable = successes.isEmpty() && states.any { it.lastFailureAt != null },
        )
    }

    fun dailyMetrics(source: CurrentAffairsSourceId, day: LocalDate): DailyFetchMetrics = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT requests, modified_responses, not_modified_responses, failures,
                   backoff_skips, request_bytes_estimate, response_bytes_estimate
            FROM current_affairs_daily_metrics WHERE source = ? AND day = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, source.name)
            statement.setString(2, day.toString())
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@synchronized DailyFetchMetrics(source, day, 0, 0, 0, 0, 0, 0, 0)
                DailyFetchMetrics(
                    source = source,
                    day = day,
                    requests = rows.getLong(1),
                    modifiedResponses = rows.getLong(2),
                    notModifiedResponses = rows.getLong(3),
                    failures = rows.getLong(4),
                    backoffSkips = rows.getLong(5),
                    requestBytesEstimate = rows.getLong(6),
                    responseBytesEstimate = rows.getLong(7),
                )
            }
        }
    }

    override fun close() = synchronized(lock) { runCatching { connection.close() }; Unit }

    private fun initializeSchema() = synchronized(lock) {
        val version = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
        require(version in 0..SCHEMA_VERSION) { "unsupported current-affairs schema version: $version" }
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS current_affairs_items (
                    id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    source_item_id TEXT NOT NULL,
                    canonical_url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    snippet TEXT,
                    attribution TEXT NOT NULL,
                    published_at TEXT,
                    fetched_at TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    UNIQUE(source, source_item_id),
                    UNIQUE(source, canonical_url)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS current_affairs_feed_state (
                    source TEXT PRIMARY KEY,
                    etag TEXT,
                    last_modified TEXT,
                    last_successful_refresh_at TEXT,
                    consecutive_failures INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TEXT,
                    last_failure_at TEXT
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS current_affairs_daily_metrics (
                    source TEXT NOT NULL,
                    day TEXT NOT NULL,
                    requests INTEGER NOT NULL DEFAULT 0,
                    modified_responses INTEGER NOT NULL DEFAULT 0,
                    not_modified_responses INTEGER NOT NULL DEFAULT 0,
                    failures INTEGER NOT NULL DEFAULT 0,
                    backoff_skips INTEGER NOT NULL DEFAULT 0,
                    request_bytes_estimate INTEGER NOT NULL DEFAULT 0,
                    response_bytes_estimate INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (source, day)
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_current_affairs_expiry ON current_affairs_items(expires_at)")
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_current_affairs_published ON current_affairs_items(source, published_at DESC)",
            )
            if (version == 0) statement.executeUpdate("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    private fun existing(item: CanonicalFeedItem): Pair<String, String>? = connection.prepareStatement(
        """
        SELECT id, content_hash FROM current_affairs_items
        WHERE source = ? AND (source_item_id = ? OR canonical_url = ?) LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, item.source.name)
        statement.setString(2, item.sourceItemId)
        statement.setString(3, item.canonicalUrl)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
    }

    private fun insert(item: CanonicalFeedItem) {
        connection.prepareStatement(
            """
            INSERT INTO current_affairs_items
                (id, source, source_item_id, canonical_url, title, snippet, attribution,
                 published_at, fetched_at, content_hash, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            bindItem(statement, item, includeId = true)
            statement.executeUpdate()
        }
    }

    private fun update(existingId: String, item: CanonicalFeedItem) {
        connection.prepareStatement(
            """
            UPDATE current_affairs_items SET
                source_item_id = ?, canonical_url = ?, title = ?, snippet = ?, attribution = ?,
                published_at = ?, fetched_at = ?, content_hash = ?, expires_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, item.sourceItemId)
            statement.setString(2, item.canonicalUrl)
            statement.setString(3, item.title)
            statement.setString(4, item.snippet)
            statement.setString(5, item.attribution)
            statement.setString(6, item.publishedAt?.toString())
            statement.setString(7, item.fetchedAt.toString())
            statement.setString(8, item.contentHash)
            statement.setString(9, item.expiresAt.toString())
            statement.setString(10, existingId)
            statement.executeUpdate()
        }
    }

    private fun touch(existingId: String, fetchedAt: Instant, expiresAt: Instant) {
        connection.prepareStatement(
            "UPDATE current_affairs_items SET fetched_at = ?, expires_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, fetchedAt.toString())
            statement.setString(2, expiresAt.toString())
            statement.setString(3, existingId)
            statement.executeUpdate()
        }
    }

    private fun bindItem(statement: java.sql.PreparedStatement, item: CanonicalFeedItem, includeId: Boolean) {
        var index = 1
        if (includeId) statement.setString(index++, item.id)
        statement.setString(index++, item.source.name)
        statement.setString(index++, item.sourceItemId)
        statement.setString(index++, item.canonicalUrl)
        statement.setString(index++, item.title)
        statement.setString(index++, item.snippet)
        statement.setString(index++, item.attribution)
        statement.setString(index++, item.publishedAt?.toString())
        statement.setString(index++, item.fetchedAt.toString())
        statement.setString(index++, item.contentHash)
        statement.setString(index, item.expiresAt.toString())
    }

    private fun deleteExpired(at: Instant): Int = connection.prepareStatement(
        "DELETE FROM current_affairs_items WHERE expires_at <= ?",
    ).use { statement ->
        statement.setString(1, at.toString())
        statement.executeUpdate()
    }

    private fun markSuccess(source: CurrentAffairsSourceId, validators: FeedValidators, at: Instant) {
        connection.prepareStatement(
            """
            INSERT INTO current_affairs_feed_state
                (source, etag, last_modified, last_successful_refresh_at, consecutive_failures)
            VALUES (?, ?, ?, ?, 0)
            ON CONFLICT(source) DO UPDATE SET
                etag = excluded.etag,
                last_modified = excluded.last_modified,
                last_successful_refresh_at = excluded.last_successful_refresh_at,
                consecutive_failures = 0,
                next_attempt_at = NULL,
                last_failure_at = NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, source.name)
            statement.setString(2, validators.etag)
            statement.setString(3, validators.lastModified)
            statement.setString(4, at.toString())
            statement.executeUpdate()
        }
    }

    private fun addDailyMetrics(
        source: CurrentAffairsSourceId,
        at: Instant,
        metrics: FeedRefreshMetrics,
        modified: Int = 0,
        notModified: Int = 0,
        failures: Int = 0,
        backoffSkips: Int = 0,
    ) {
        val day = at.atZone(ZoneOffset.UTC).toLocalDate()
        connection.prepareStatement(
            """
            INSERT INTO current_affairs_daily_metrics
                (source, day, requests, modified_responses, not_modified_responses, failures,
                 backoff_skips, request_bytes_estimate, response_bytes_estimate)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source, day) DO UPDATE SET
                requests = requests + excluded.requests,
                modified_responses = modified_responses + excluded.modified_responses,
                not_modified_responses = not_modified_responses + excluded.not_modified_responses,
                failures = failures + excluded.failures,
                backoff_skips = backoff_skips + excluded.backoff_skips,
                request_bytes_estimate = request_bytes_estimate + excluded.request_bytes_estimate,
                response_bytes_estimate = response_bytes_estimate + excluded.response_bytes_estimate
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, source.name)
            statement.setString(2, day.toString())
            statement.setLong(3, metrics.requests.toLong())
            statement.setInt(4, modified)
            statement.setInt(5, notModified)
            statement.setInt(6, failures)
            statement.setInt(7, backoffSkips)
            statement.setLong(8, metrics.requestBytesEstimate)
            statement.setLong(9, metrics.responseBytesEstimate)
            statement.executeUpdate()
        }
    }

    private fun <T> transaction(block: () -> T): T = synchronized(lock) {
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            block().also { connection.commit() }
        } catch (error: Throwable) {
            runCatching { connection.rollback() }
            throw error
        } finally {
            connection.autoCommit = previous
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
