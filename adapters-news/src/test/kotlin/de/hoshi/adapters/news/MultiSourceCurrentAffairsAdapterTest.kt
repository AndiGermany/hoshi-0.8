package de.hoshi.adapters.news

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MultiSourceCurrentAffairsAdapterTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }

    @Test
    fun `active sources alone are fetched and merged while query filter remains narrower`() {
        val requests = ConcurrentHashMap<CurrentAffairsSourceId, AtomicInteger>().apply {
            CurrentAffairsSourceId.entries.forEach { put(it, AtomicInteger()) }
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        context(server, "/tagesschau", CurrentAffairsSourceId.TAGESSCHAU, "tagesschau-rss.xml", requests)
        context(server, "/heise", CurrentAffairsSourceId.HEISE, "heise-top-atom.xml", requests)
        context(server, "/golem", CurrentAffairsSourceId.GOLEM, "golem-all-atom.xml", requests)
        server.start()
        try {
            val definitions = mapOf(
                CurrentAffairsSourceId.TAGESSCHAU to localDefinition(
                    FeedSourceDefinition.TAGESSCHAU,
                    server,
                    "/tagesschau",
                ),
                CurrentAffairsSourceId.HEISE to localDefinition(FeedSourceDefinition.HEISE, server, "/heise"),
                CurrentAffairsSourceId.GOLEM to localDefinition(FeedSourceDefinition.GOLEM, server, "/golem"),
            )
            var active = setOf(CurrentAffairsSourceId.TAGESSCHAU, CurrentAffairsSourceId.HEISE)
            MultiSourceCurrentAffairsAdapter(
                dbDirectory = tempDir.resolve("aggregate"),
                sourceDefinitions = definitions,
                activeSources = { active },
                clock = FixedClock(Instant.parse("2026-08-15T07:00:00Z")),
                timeout = Duration.ofSeconds(2),
                backoffFactory = { ExponentialBackoffPolicy(jitterMillis = { 0 }) },
            ).use { aggregate ->
                val firstRefresh = aggregate.refresh()
                assertEquals(active, firstRefresh.activeSources)
                assertEquals(active, firstRefresh.reports.keys)
                assertEquals(1, requests.getValue(CurrentAffairsSourceId.TAGESSCHAU).get())
                assertEquals(1, requests.getValue(CurrentAffairsSourceId.HEISE).get())
                assertEquals(0, requests.getValue(CurrentAffairsSourceId.GOLEM).get())

                val merged = aggregate.latest(CurrentAffairsQuery(limit = 10))
                assertEquals(CurrentAffairsFreshness.FRESH, merged.freshness)
                assertEquals(4, merged.items.size)
                assertEquals("Zweite Meldung", merged.items[0].title)
                assertEquals("Heise Meldung eins", merged.items[1].title)
                assertFalse(merged.items.any { it.source == CurrentAffairsSourceId.GOLEM })

                val heiseOnly = aggregate.latest(
                    CurrentAffairsQuery(sources = setOf(CurrentAffairsSourceId.HEISE), limit = 10),
                )
                assertEquals(2, heiseOnly.items.size)
                assertTrue(heiseOnly.items.all { it.source == CurrentAffairsSourceId.HEISE })
                assertEquals(
                    CurrentAffairsFreshness.EMPTY,
                    aggregate.latest(CurrentAffairsQuery(sources = setOf(CurrentAffairsSourceId.GOLEM))).freshness,
                )

                active = setOf(CurrentAffairsSourceId.GOLEM, CurrentAffairsSourceId.DLF)
                val secondRefresh = aggregate.refresh()
                assertEquals(setOf(CurrentAffairsSourceId.GOLEM), secondRefresh.activeSources)
                assertEquals(setOf(CurrentAffairsSourceId.DLF), secondRefresh.unsupportedActiveSources)
                assertEquals(1, requests.getValue(CurrentAffairsSourceId.TAGESSCHAU).get())
                assertEquals(1, requests.getValue(CurrentAffairsSourceId.HEISE).get())
                assertEquals(1, requests.getValue(CurrentAffairsSourceId.GOLEM).get())
                val golemOnly = aggregate.latest(CurrentAffairsQuery(limit = 10))
                assertEquals(2, golemOnly.items.size)
                assertTrue(golemOnly.items.all { it.source == CurrentAffairsSourceId.GOLEM })
                assertEquals("Golem Meldung eins", golemOnly.items.first().title)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `broken settings supplier is unavailable and does not fall back to all sources`() {
        MultiSourceCurrentAffairsAdapter(
            dbDirectory = tempDir.resolve("broken-settings"),
            activeSources = { error("settings unavailable") },
        ).use { aggregate ->
            val refresh = aggregate.refresh()
            assertFalse(refresh.settingsAvailable)
            assertTrue(refresh.reports.isEmpty())
            assertEquals(CurrentAffairsFreshness.UNAVAILABLE, aggregate.latest(CurrentAffairsQuery()).freshness)
        }
    }

    @Test
    fun `global limit keeps every non-empty active source visible when capacity permits`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tagesschau") { exchange ->
            reply(exchange, rssItems("Tagesschau", 17, Instant.parse("2026-08-15T09:00:00Z")), "rss")
        }
        server.createContext("/heise") { exchange ->
            reply(exchange, fixture("heise-live-shape-20260815.xml"), "atom")
        }
        server.createContext("/golem") { exchange ->
            reply(exchange, atomItems("Golem", 3, Instant.parse("2026-08-15T08:00:00Z")), "atom")
        }
        server.start()
        try {
            val definitions = mapOf(
                CurrentAffairsSourceId.TAGESSCHAU to localDefinition(
                    FeedSourceDefinition.TAGESSCHAU,
                    server,
                    "/tagesschau",
                ),
                CurrentAffairsSourceId.HEISE to localDefinition(FeedSourceDefinition.HEISE, server, "/heise"),
                CurrentAffairsSourceId.GOLEM to localDefinition(FeedSourceDefinition.GOLEM, server, "/golem"),
            )
            MultiSourceCurrentAffairsAdapter(
                dbDirectory = tempDir.resolve("source-presence"),
                sourceDefinitions = definitions,
                clock = FixedClock(Instant.parse("2026-08-15T10:00:00Z")),
                timeout = Duration.ofSeconds(2),
                backoffFactory = { ExponentialBackoffPolicy(jitterMillis = { 0 }) },
            ).use { aggregate ->
                val refresh = aggregate.refresh()
                assertTrue(refresh.reports.values.all { it.status == FeedRefreshStatus.UPDATED })

                val heiseOnly = aggregate.latest(
                    CurrentAffairsQuery(sources = setOf(CurrentAffairsSourceId.HEISE), limit = 20),
                )
                assertEquals(1, heiseOnly.items.size, "Heise reached its own cache")

                val merged = aggregate.latest(CurrentAffairsQuery(limit = 20))
                assertEquals(20, merged.items.size)
                assertEquals(
                    setOf(
                        CurrentAffairsSourceId.TAGESSCHAU,
                        CurrentAffairsSourceId.HEISE,
                        CurrentAffairsSourceId.GOLEM,
                    ),
                    merged.items.map { it.source }.toSet(),
                    "a slower healthy source must not disappear solely because the shared limit is full",
                )
                assertEquals(CurrentAffairsSourceId.HEISE, merged.items.last().source)

                val spokenDefault = aggregate.latest(CurrentAffairsQuery(limit = 3))
                assertEquals(3, spokenDefault.items.size)
                assertEquals(3, spokenDefault.items.map { it.source }.toSet().size)

                val insufficientCapacity = aggregate.latest(CurrentAffairsQuery(limit = 2))
                assertTrue(insufficientCapacity.items.all { it.source == CurrentAffairsSourceId.TAGESSCHAU })
            }
        } finally {
            server.stop(0)
        }
    }

    private fun context(
        server: HttpServer,
        path: String,
        source: CurrentAffairsSourceId,
        fixtureName: String,
        requests: Map<CurrentAffairsSourceId, AtomicInteger>,
    ) {
        server.createContext(path) { exchange ->
            requests.getValue(source).incrementAndGet()
            reply(exchange, fixture(fixtureName), if (source == CurrentAffairsSourceId.TAGESSCHAU) "rss" else "atom")
        }
    }

    private fun localDefinition(original: FeedSourceDefinition, server: HttpServer, path: String) = original.copy(
        feedUri = URI("http://127.0.0.1:${server.address.port}$path"),
        allowedFeedHosts = setOf("127.0.0.1"),
        allowInsecureLoopback = true,
    )

    private fun reply(exchange: HttpExchange, body: ByteArray, format: String) {
        exchange.responseHeaders.add("Content-Type", "application/$format+xml; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun rssItems(label: String, count: Int, newest: Instant): ByteArray = buildString {
        append("<rss version=\"2.0\"><channel>")
        repeat(count) { index ->
            val published = newest.minusSeconds(index * 60L)
            append("<item><title>$label $index</title>")
            append("<link>https://www.tagesschau.de/inland/test-$index.html</link>")
            append("<guid>$label-$index</guid><pubDate>")
            append(java.time.ZonedDateTime.ofInstant(published, ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME))
            append("</pubDate></item>")
        }
        append("</channel></rss>")
    }.toByteArray()

    private fun atomItems(label: String, count: Int, newest: Instant): ByteArray = buildString {
        append("<feed xmlns=\"http://www.w3.org/2005/Atom\">")
        repeat(count) { index ->
            val published = newest.minusSeconds(index * 60L)
            append("<entry><title>$label $index</title><id>$label-$index</id>")
            append("<link href=\"https://www.golem.de/news/test-$index-2608-20000$index.html\"/>")
            append("<published>$published</published></entry>")
        }
        append("</feed>")
    }.toByteArray()

    private class FixedClock(private val now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
