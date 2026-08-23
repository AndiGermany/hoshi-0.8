package de.hoshi.adapters.news

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSourceId
import de.hoshi.core.port.CurrentAffairsFreshness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class FeedCurrentAffairsAdapterSourceTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }

    @Test
    fun `heise and golem each preserve new updated and duplicate semantics`() {
        val cases = listOf(
            SourceCase(
                FeedSourceDefinition.HEISE,
                "heise-top-atom.xml",
                "heise-top-atom-updated.xml",
                "Heise Meldung eins – aktualisiert",
            ),
            SourceCase(
                FeedSourceDefinition.GOLEM,
                "golem-all-atom.xml",
                "golem-all-atom-updated.xml",
                "Golem Meldung eins – aktualisiert",
            ),
        )
        cases.forEach(::verifyDedupe)
    }

    @Test
    fun `advertising entries are not stored and have a dedicated refresh counter`() {
        val feed = atomFeed(
            "Anzeige: Nicht vorlesen" to "ad-1",
            "Redaktionelle Meldung" to "news-1",
        )
        withAdapter(feed, "ads-filtered") { adapter ->
            val report = adapter.refresh()

            assertEquals(FeedRefreshStatus.UPDATED, report.status)
            assertEquals(2, report.metrics.parsedItems)
            assertEquals(1, report.metrics.rejectedAds)
            assertEquals(0, report.metrics.rejectedItems)
            assertEquals(1, report.metrics.newItems)
            val snapshot = adapter.latest(CurrentAffairsQuery(limit = 10))
            assertEquals(listOf("Redaktionelle Meldung"), snapshot.items.map { it.title })
        }
    }

    @Test
    fun `advertising-only feed is a successful empty editorial update without backoff`() {
        val feed = atomFeed("[Anzeige] Nicht vorlesen" to "ad-only")
        withAdapter(feed, "ads-only") { adapter ->
            val report = adapter.refresh()

            assertEquals(FeedRefreshStatus.UPDATED, report.status)
            assertEquals(null, report.failureReason)
            assertEquals(1, report.metrics.rejectedAds)
            assertEquals(CurrentAffairsFreshness.EMPTY, adapter.latest(CurrentAffairsQuery()).freshness)
        }
    }

    private fun verifyDedupe(case: SourceCase) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            val body = fixture(if (requests.incrementAndGet() == 1) case.initialFixture else case.updatedFixture)
            exchange.responseHeaders.add("Content-Type", "application/atom+xml; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val clock = MutableClock(Instant.parse("2026-08-15T07:00:00Z"))
            val definition = case.definition.copy(
                feedUri = URI("http://127.0.0.1:${server.address.port}/feed"),
                allowedFeedHosts = setOf("127.0.0.1"),
                allowInsecureLoopback = true,
            )
            FeedCurrentAffairsAdapter(
                dbPath = tempDir.resolve("${case.definition.source.name.lowercase()}.db"),
                source = definition,
                clock = clock,
                timeout = Duration.ofSeconds(2),
                backoff = ExponentialBackoffPolicy(jitterMillis = { 0 }),
            ).use { adapter ->
                val first = adapter.refresh()
                assertEquals(2, first.metrics.newItems, case.definition.source.name)

                clock.advance(Duration.ofMinutes(1))
                val second = adapter.refresh()
                assertEquals(1, second.metrics.newItems, case.definition.source.name)
                assertEquals(1, second.metrics.updatedItems, case.definition.source.name)
                assertEquals(1, second.metrics.duplicateItems, case.definition.source.name)
                val snapshot = adapter.latest(CurrentAffairsQuery(sources = setOf(case.definition.source), limit = 10))
                assertEquals(3, snapshot.items.size)
                assertTrue(snapshot.items.any { it.title == case.updatedTitle })
            }
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun withAdapter(
        feed: ByteArray,
        dbName: String,
        assertion: (FeedCurrentAffairsAdapter) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/atom+xml; charset=utf-8")
            exchange.sendResponseHeaders(200, feed.size.toLong())
            exchange.responseBody.use { it.write(feed) }
        }
        server.start()
        try {
            val definition = FeedSourceDefinition.GOLEM.copy(
                feedUri = URI("http://127.0.0.1:${server.address.port}/feed"),
                allowedFeedHosts = setOf("127.0.0.1"),
                allowInsecureLoopback = true,
            )
            FeedCurrentAffairsAdapter(
                dbPath = tempDir.resolve("$dbName.db"),
                source = definition,
                clock = MutableClock(Instant.parse("2026-08-15T10:00:00Z")),
                timeout = Duration.ofSeconds(2),
                backoff = ExponentialBackoffPolicy(jitterMillis = { 0 }),
            ).use { assertion(it) }
        } finally {
            server.stop(0)
        }
    }

    private fun atomFeed(vararg entries: Pair<String, String>): ByteArray = buildString {
        append("<feed xmlns=\"http://www.w3.org/2005/Atom\">")
        entries.forEach { (title, id) ->
            append("<entry><title>$title</title><id>$id</id>")
            append("<link href=\"https://www.golem.de/news/$id-2608-200001.html\"/>")
            append("<published>2026-08-15T09:00:00Z</published></entry>")
        }
        append("</feed>")
    }.toByteArray()

    private data class SourceCase(
        val definition: FeedSourceDefinition,
        val initialFixture: String,
        val updatedFixture: String,
        val updatedTitle: String,
    )

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
