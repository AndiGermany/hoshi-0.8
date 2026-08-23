package de.hoshi.adapters.news

import de.hoshi.core.port.CurrentAffairsSourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.time.Instant

class WaveOneSourceTest {
    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }

    @Test
    fun `wave one defaults are the three explicitly ordered sources`() {
        assertEquals(
            setOf(CurrentAffairsSourceId.TAGESSCHAU, CurrentAffairsSourceId.HEISE, CurrentAffairsSourceId.GOLEM),
            FeedSourceDefinition.WAVE_1.keys,
        )
        assertEquals(MultiSourceCurrentAffairsAdapter.DEFAULT_ACTIVE_SOURCES, FeedSourceDefinition.WAVE_1.keys)
    }

    @Test
    fun `multi source keeps the existing tagesschau database path and isolates new source stores`() {
        val directory = Paths.get("data")
        assertEquals(
            directory.resolve("current-affairs.db"),
            MultiSourceCurrentAffairsAdapter.dbPathFor(directory, CurrentAffairsSourceId.TAGESSCHAU),
        )
        assertEquals(
            directory.resolve("current-affairs-heise.db"),
            MultiSourceCurrentAffairsAdapter.dbPathFor(directory, CurrentAffairsSourceId.HEISE),
        )
        assertEquals(
            directory.resolve("current-affairs-golem.db"),
            MultiSourceCurrentAffairsAdapter.dbPathFor(directory, CurrentAffairsSourceId.GOLEM),
        )
    }

    @Test
    fun `heise uses summary not image-bearing content and keeps attribution honest`() {
        val parsed = SafeFeedParser().parse(fixture("heise-top-atom.xml"))
        val raw = parsed.entries.first()
        assertEquals("Ein kurzer Anriss ohne Bild.", raw.snippet)
        assertFalse(raw.snippet.orEmpty().contains("image.jpg"))

        val item = requireNotNull(
            NewsItemCanonicalizer(FeedSourceDefinition.HEISE)
                .canonicalize(raw, Instant.parse("2026-08-15T07:00:00Z")),
        )
        assertEquals("https://www.heise.de/news/heise-eins-100001.html?topic=technik", item.canonicalUrl)
        assertTrue(item.attribution.contains("keine Bilder"))
    }

    @Test
    fun `golem strips category markup and tracking pixel and names commercial restriction`() {
        val parsed = SafeFeedParser().parse(fixture("golem-all-atom.xml"))
        val raw = parsed.entries.first()
        assertEquals("Ein Anriss mit ( Technik )", raw.snippet)
        assertFalse(raw.snippet.orEmpty().contains("cpx.golem.de"))

        val item = requireNotNull(
            NewsItemCanonicalizer(FeedSourceDefinition.GOLEM)
                .canonicalize(raw, Instant.parse("2026-08-15T07:00:00Z")),
        )
        assertEquals("https://www.golem.de/news/golem-eins-2608-200001.html", item.canonicalUrl)
        assertTrue(item.attribution.contains("kommerziell", ignoreCase = true))
    }
}
