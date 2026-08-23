package de.hoshi.adapters.news

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SafeFeedParserTest {
    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }

    @Test
    fun `RSS fixture becomes bounded plain teaser data`() {
        val parsed = SafeFeedParser().parse(fixture("tagesschau-rss.xml"))
        assertEquals(2, parsed.entries.size)
        assertEquals(0, parsed.rejectedEntries)
        assertEquals("Erste Meldung & Einordnung", parsed.entries[0].title)
        assertEquals("Der erste Anriss bleibt Text.", parsed.entries[0].snippet)
        assertFalse(parsed.entries[0].snippet!!.contains('<'))
        assertEquals(Instant.parse("2026-08-15T05:00:00Z"), parsed.entries[0].publishedAt)
    }

    @Test
    fun `DOCTYPE and external entity are rejected before resolution`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
            <rss><channel><item><title>&xxe;</title><link>https://www.tagesschau.de/x</link></item></channel></rss>
        """.trimIndent().toByteArray()
        assertThrows(FeedParseException::class.java) { SafeFeedParser().parse(xml) }
    }

    @Test
    fun `item ceiling rejects overflow instead of parsing unbounded input`() {
        val parsed = SafeFeedParser(maxItems = 1).parse(fixture("tagesschau-rss.xml"))
        assertEquals(1, parsed.entries.size)
        assertEquals(1, parsed.rejectedEntries)
    }

    @Test
    fun `Atom alternate link and ISO timestamp are supported`() {
        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>atom-1</id><title>Atom Titel</title>
                <link rel="alternate" href="https://www.tagesschau.de/atom-100.html"/>
                <summary>Ein Anriss</summary><updated>2026-08-15T08:00:00Z</updated>
              </entry>
            </feed>
        """.trimIndent().toByteArray()
        val entry = SafeFeedParser().parse(atom).entries.single()
        assertEquals("atom-1", entry.sourceId)
        assertEquals(Instant.parse("2026-08-15T08:00:00Z"), entry.publishedAt)
        assertTrue(entry.link!!.endsWith("atom-100.html"))
    }

    @Test
    fun `real heise feed shape exposes published timestamp`() {
        val entry = SafeFeedParser().parse(fixture("heise-live-shape-20260815.xml")).entries.single()

        assertEquals("Heise Meldung aus realer Feed-Form", entry.title)
        assertEquals(Instant.parse("2026-08-14T15:17:00Z"), entry.publishedAt)
    }
}
