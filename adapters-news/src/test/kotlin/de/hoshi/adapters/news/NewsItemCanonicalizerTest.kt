package de.hoshi.adapters.news

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class NewsItemCanonicalizerTest {
    private val now = Instant.parse("2026-08-15T08:00:00Z")
    private val canonicalizer = NewsItemCanonicalizer(FeedSourceDefinition.TAGESSCHAU)

    @Test
    fun `tracking and fragment are removed while useful query remains`() {
        val item = canonicalizer.canonicalize(
            RawFeedEntry(
                "stable-guid",
                "Titel",
                "Anriss",
                "https://www.tagesschau.de/inland/x-100.html?utm_source=rss&foo=bar#top",
                now,
            ),
            now,
        )!!
        assertEquals("https://www.tagesschau.de/inland/x-100.html?foo=bar", item.canonicalUrl)
        assertEquals("stable-guid", item.sourceItemId)
    }

    @Test
    fun `off-host insecure and userinfo links are rejected`() {
        fun raw(url: String) = RawFeedEntry("id", "Titel", null, url, now)
        assertNull(canonicalizer.canonicalize(raw("http://www.tagesschau.de/x"), now))
        assertNull(canonicalizer.canonicalize(raw("https://example.org/x"), now))
        assertNull(canonicalizer.canonicalize(raw("https://user@www.tagesschau.de/x"), now))
        assertNull(canonicalizer.canonicalize(raw("javascript:alert(1)"), now))
    }

    @Test
    fun `id is stable but content hash changes with editorial update`() {
        val first = canonicalizer.canonicalize(
            RawFeedEntry("guid", "Titel", "Alt", "https://www.tagesschau.de/x", now),
            now,
        )!!
        val updated = canonicalizer.canonicalize(
            RawFeedEntry("guid", "Titel", "Neu", "https://www.tagesschau.de/x", now),
            now.plusSeconds(60),
        )!!
        assertEquals(first.id, updated.id)
        assertNotEquals(first.contentHash, updated.contentHash)
    }

    @Test
    fun `retention is seven days but never beyond fourteen days from publication`() {
        val custom = NewsItemCanonicalizer(
            FeedSourceDefinition.TAGESSCHAU,
            ttl = Duration.ofDays(7),
            hardRetention = Duration.ofDays(14),
        )
        val published = now.minus(Duration.ofDays(10))
        val item = custom.canonicalize(
            RawFeedEntry("guid", "Titel", null, "https://www.tagesschau.de/x", published),
            now,
        )!!
        assertEquals(published.plus(Duration.ofDays(14)), item.expiresAt)
    }
}
