package de.hoshi.adapters.news

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TagesschauCurrentAffairsAdapterTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }

    private data class Reply(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val contentType: String? = "application/rss+xml; charset=utf-8",
    )

    private data class SeenRequest(
        val ifNoneMatch: String?,
        val ifModifiedSince: String?,
        val userAgent: String?,
    )

    private fun withFeedServer(
        responder: (requestNumber: Int, exchange: HttpExchange) -> Reply,
        block: (URI, List<SeenRequest>) -> Unit,
    ) {
        val count = AtomicInteger(0)
        val seen = CopyOnWriteArrayList<SeenRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed.xml") { exchange ->
            seen += SeenRequest(
                exchange.requestHeaders.getFirst("If-None-Match"),
                exchange.requestHeaders.getFirst("If-Modified-Since"),
                exchange.requestHeaders.getFirst("User-Agent"),
            )
            val reply = responder(count.incrementAndGet(), exchange)
            reply.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            if (reply.status == 304) {
                exchange.sendResponseHeaders(reply.status, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(reply.status, reply.body.size.toLong())
                exchange.responseBody.use { it.write(reply.body) }
            }
        }
        server.start()
        try {
            block(URI("http://127.0.0.1:${server.address.port}/feed.xml"), seen)
        } finally {
            server.stop(0)
        }
    }

    private fun adapter(
        uri: URI,
        clock: MutableClock,
        dbName: String = "current-affairs.db",
        maxResponseBytes: Int = 1_000_000,
    ) = TagesschauCurrentAffairsAdapter(
        dbPath = tempDir.resolve(dbName),
        source = FeedSourceDefinition(
            source = CurrentAffairsSourceId.TAGESSCHAU,
            feedUri = uri,
            allowedFeedHosts = setOf("127.0.0.1"),
            allowedArticleHosts = setOf("tagesschau.de"),
            attribution = "tagesschau.de",
            allowInsecureLoopback = true,
        ),
        clock = clock,
        staleAfter = Duration.ofMinutes(90),
        timeout = Duration.ofSeconds(2),
        maxResponseBytes = maxResponseBytes,
        backoff = ExponentialBackoffPolicy(
            baseDelay = Duration.ofSeconds(1),
            maxDelay = Duration.ofSeconds(8),
            jitterMillis = { 0 },
        ),
    )

    @Test
    fun `200 then conditional 304 persists one cache and separates observed from refreshed time`() =
        withFeedServer(
            responder = { number, _ ->
                if (number == 1) {
                    Reply(
                        200,
                        fixture("tagesschau-rss.xml"),
                        mapOf("ETag" to "\"v1\"", "Last-Modified" to "Sat, 15 Aug 2026 08:00:00 GMT"),
                    )
                } else {
                    Reply(304, contentType = null)
                }
            },
        ) { uri, seen ->
            val clock = MutableClock(Instant.parse("2026-08-15T08:30:00Z"))
            val db = "conditional.db"
            adapter(uri, clock, db).use { current ->
                val first = current.refresh()
                assertEquals(FeedRefreshStatus.UPDATED, first.status)
                assertEquals(2, first.metrics.newItems)
                assertEquals(0, first.metrics.duplicateItems)

                val snapshot = current.latest(CurrentAffairsQuery(limit = 1))
                assertEquals(CurrentAffairsFreshness.FRESH, snapshot.freshness)
                assertEquals("Zweite Meldung", snapshot.items.single().title)
                assertEquals(clock.instant(), snapshot.observedAt)
                assertEquals(clock.instant(), snapshot.lastSuccessfulRefreshAt)

                clock.advance(Duration.ofMinutes(30))
                val second = current.refresh()
                assertEquals(FeedRefreshStatus.NOT_MODIFIED, second.status)
                assertEquals("\"v1\"", seen[1].ifNoneMatch)
                assertEquals("Sat, 15 Aug 2026 08:00:00 GMT", seen[1].ifModifiedSince)
                assertTrue(seen.all { it.userAgent?.contains("Hoshi") == true })
                assertEquals(clock.instant(), current.latest(CurrentAffairsQuery()).lastSuccessfulRefreshAt)

                val metrics = requireNotNull(current.dailyMetrics(LocalDate.parse("2026-08-15")))
                assertEquals(2, metrics.requests)
                assertEquals(1, metrics.modifiedResponses)
                assertEquals(1, metrics.notModifiedResponses)
                assertTrue(metrics.requestBytesEstimate > 0)
                assertTrue(metrics.responseBytesEstimate > fixture("tagesschau-rss.xml").size)
            }

            adapter(uri, clock, db).use { restarted ->
                val persisted = restarted.latest(CurrentAffairsQuery(limit = 3))
                assertEquals(2, persisted.items.size)
                assertEquals(CurrentAffairsFreshness.FRESH, persisted.freshness)
            }
        }

    @Test
    fun `content hash distinguishes new updated and duplicate items`() =
        withFeedServer(
            responder = { number, _ ->
                Reply(200, fixture(if (number == 1) "tagesschau-rss.xml" else "tagesschau-rss-updated.xml"))
            },
        ) { uri, _ ->
            val clock = MutableClock(Instant.parse("2026-08-15T08:30:00Z"))
            adapter(uri, clock, "dedupe.db").use { current ->
                assertEquals(2, current.refresh().metrics.newItems)
                clock.advance(Duration.ofMinutes(30))
                val next = current.refresh()
                assertEquals(1, next.metrics.newItems)
                assertEquals(1, next.metrics.updatedItems)
                assertEquals(1, next.metrics.duplicateItems)
                val snapshot = current.latest(CurrentAffairsQuery(limit = 3))
                assertEquals(3, snapshot.items.size)
                assertTrue(snapshot.items.any { it.title == "Erste Meldung – aktualisiert" })
                assertEquals(
                    "https://www.tagesschau.de/inland/erste-meldung-100.html?foo=bar",
                    snapshot.items.first { it.title.startsWith("Erste") }.canonicalUrl,
                )
            }
        }

    @Test
    fun `parser failure keeps last-known cache and backoff suppresses the next request`() {
        val malicious = """
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
            <rss><channel><item><title>&xxe;</title><link>https://www.tagesschau.de/x</link></item></channel></rss>
        """.trimIndent().toByteArray()
        withFeedServer(
            responder = { number, _ ->
                if (number == 1) Reply(200, fixture("tagesschau-rss.xml")) else Reply(200, malicious)
            },
        ) { uri, seen ->
            val clock = MutableClock(Instant.parse("2026-08-15T08:30:00Z"))
            adapter(uri, clock, "backoff.db").use { current ->
                assertEquals(FeedRefreshStatus.UPDATED, current.refresh().status)
                clock.advance(Duration.ofMinutes(1))
                val rejected = current.refresh()
                assertEquals(FeedFailureReason.XML_REJECTED, rejected.failureReason)
                assertNotNull(rejected.nextAttemptAt)
                assertEquals(2, current.latest(CurrentAffairsQuery()).items.size)

                val blocked = current.refresh()
                assertEquals(FeedRefreshStatus.BACKING_OFF, blocked.status)
                assertEquals(2, seen.size, "backoff must prevent a third HTTP request")
                val metrics = requireNotNull(current.dailyMetrics(LocalDate.parse("2026-08-15")))
                assertEquals(1, metrics.failures)
                assertEquals(1, metrics.backoffSkips)
            }
        }
    }

    @Test
    fun `failed first refresh is unavailable and never looks like an empty successful feed`() =
        withFeedServer(responder = { _, _ -> Reply(503, "down".toByteArray(), contentType = "text/plain") }) { uri, _ ->
            val clock = MutableClock(Instant.parse("2026-08-15T08:30:00Z"))
            adapter(uri, clock, "unavailable.db").use { current ->
                assertEquals(FeedRefreshStatus.UNAVAILABLE, current.refresh().status)
                assertEquals(CurrentAffairsFreshness.UNAVAILABLE, current.latest(CurrentAffairsQuery()).freshness)
            }
        }

    @Test
    fun `snapshot becomes stale without moving last successful refresh and expires after TTL`() =
        withFeedServer(responder = { _, _ -> Reply(200, fixture("tagesschau-rss.xml")) }) { uri, _ ->
            val clock = MutableClock(Instant.parse("2026-08-15T08:30:00Z"))
            adapter(uri, clock, "ttl.db").use { current ->
                current.refresh()
                val refreshedAt = clock.instant()
                clock.advance(Duration.ofHours(2))
                val stale = current.latest(CurrentAffairsQuery())
                assertEquals(CurrentAffairsFreshness.STALE, stale.freshness)
                assertEquals(refreshedAt, stale.lastSuccessfulRefreshAt)
                assertEquals(clock.instant(), stale.observedAt)

                clock.advance(Duration.ofDays(8))
                val expired = current.latest(CurrentAffairsQuery())
                assertEquals(CurrentAffairsFreshness.EMPTY, expired.freshness)
                assertTrue(expired.items.isEmpty())
            }
        }

    @Test
    fun `oversized body and cross-host redirect are rejected without following them`() {
        withFeedServer(responder = { _, _ -> Reply(200, ByteArray(512) { 'x'.code.toByte() }) }) { uri, _ ->
            val clock = MutableClock(Instant.parse("2026-08-15T08:30:00Z"))
            adapter(uri, clock, "large.db", maxResponseBytes = 128).use { current ->
                assertEquals(FeedFailureReason.RESPONSE_TOO_LARGE, current.refresh().failureReason)
            }
        }
        withFeedServer(
            responder = { _, _ -> Reply(302, headers = mapOf("Location" to "https://example.org/feed.xml"), contentType = null) },
        ) { uri, seen ->
            val clock = MutableClock(Instant.parse("2026-08-15T08:30:00Z"))
            adapter(uri, clock, "redirect.db").use { current ->
                assertEquals(FeedFailureReason.REDIRECT_REJECTED, current.refresh().failureReason)
                assertEquals(1, seen.size)
            }
        }
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
