package de.hoshi.web

import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsItem
import de.hoshi.core.port.CurrentAffairsPort
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSnapshot
import de.hoshi.core.port.CurrentAffairsSourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Read contract of `GET /api/v1/currentaffairs/today`, WITHOUT a Spring context
 * (the controller is constructed directly, pattern [WeatherTodayControllerTest]).
 *
 * Contract: 200 with the snapshot shape 1:1; `?limit=` defaults to 3 and is
 * capped at 20 (also against an adapter that ignores the limit); an unwired port
 * answers honestly `UNAVAILABLE` instead of an error. The perimeter wall (401
 * without a token) is covered for every `/api/v1` path by [PerimeterWallTest].
 */
class CurrentAffairsControllerTest {

    private val fetchedAt: Instant = Instant.parse("2026-08-15T07:12:00Z")
    private val observedAt: Instant = Instant.parse("2026-08-15T07:30:00Z")

    private fun item(i: Int) = CurrentAffairsItem(
        id = "id-$i",
        source = CurrentAffairsSourceId.TAGESSCHAU,
        title = "Schlagzeile $i",
        snippet = if (i == 1) "Der Anriss zur ersten Meldung." else null,
        canonicalUrl = "https://www.tagesschau.de/$i",
        publishedAt = if (i == 1) fetchedAt else null,
        fetchedAt = fetchedAt,
        attribution = "tagesschau.de",
    )

    private fun controller(
        itemCount: Int = 3,
        freshness: CurrentAffairsFreshness = CurrentAffairsFreshness.FRESH,
        seen: AtomicReference<CurrentAffairsQuery?>? = null,
    ) = CurrentAffairsController(
        CurrentAffairsPort { query ->
            seen?.set(query)
            CurrentAffairsSnapshot(
                items = (1..itemCount).map { item(it) },
                observedAt = observedAt,
                lastSuccessfulRefreshAt = fetchedAt,
                freshness = freshness,
            )
        },
    )

    private fun get(c: CurrentAffairsController, limit: Int? = null): CurrentAffairsSnapshotWire =
        c.today(limit).block(Duration.ofSeconds(5))!!

    @Test
    fun `200 - Snapshot-Shape 1 zu 1, Zeiten als ISO-8601, Quelle als Name`() {
        val body = get(controller())

        assertEquals("FRESH", body.freshness)
        assertEquals("2026-08-15T07:30:00Z", body.observedAt)
        assertEquals("2026-08-15T07:12:00Z", body.lastSuccessfulRefreshAt)
        assertEquals(3, body.items.size)
        val first = body.items.first()
        assertEquals("id-1", first.id)
        assertEquals("TAGESSCHAU", first.source)
        assertEquals("Schlagzeile 1", first.title)
        assertEquals("Der Anriss zur ersten Meldung.", first.snippet)
        assertEquals("https://www.tagesschau.de/1", first.canonicalUrl)
        assertEquals("2026-08-15T07:12:00Z", first.publishedAt)
        assertEquals("2026-08-15T07:12:00Z", first.fetchedAt)
        assertEquals("tagesschau.de", first.attribution)
    }

    @Test
    fun `fehlendes publishedAt bleibt null - nie ein erfundenes Datum`() {
        assertNull(get(controller()).items[1].publishedAt)
    }

    @Test
    fun `ohne limit fragt der Rand die drei Default-Meldungen`() {
        val seen = AtomicReference<CurrentAffairsQuery?>(null)
        val body = get(controller(itemCount = 9, seen = seen))

        assertEquals(CurrentAffairsController.DEFAULT_LIMIT, seen.get()!!.limit)
        assertEquals(3, body.items.size)
        assertNull(seen.get()!!.viewerId, "null = Haushaltsdefault, nie ein geratener Profilname")
    }

    @Test
    fun `limit-Deckel - 50 wird auf 20 gekappt, nie abgelehnt`() {
        val seen = AtomicReference<CurrentAffairsQuery?>(null)
        val body = get(controller(itemCount = 40, seen = seen), limit = 50)

        assertEquals(CurrentAffairsController.MAX_LIMIT, seen.get()!!.limit)
        assertEquals(20, body.items.size, "auch ein Adapter, der den Deckel ignoriert, kommt nicht durch")
    }

    @Test
    fun `limit 0 und negativ landen ehrlich beim Minimum statt bei einer leeren Antwort`() {
        assertEquals(1, get(controller(itemCount = 5), limit = 0).items.size)
        assertEquals(1, get(controller(itemCount = 5), limit = -7).items.size)
    }

    @Test
    fun `limit 10 wird durchgereicht`() {
        val seen = AtomicReference<CurrentAffairsQuery?>(null)
        val body = get(controller(itemCount = 12, seen = seen), limit = 10)

        assertEquals(10, seen.get()!!.limit)
        assertEquals(10, body.items.size)
    }

    @Test
    fun `ungewirter Port antwortet ehrlich UNAVAILABLE statt mit einem Fehler`() {
        val body = get(CurrentAffairsController(CurrentAffairsPort.NONE))

        assertEquals("UNAVAILABLE", body.freshness)
        assertTrue(body.items.isEmpty())
        assertNull(body.lastSuccessfulRefreshAt, "nie erfolgreich geholt ⇒ keine Stand-Zeit")
    }

    @Test
    fun `ein Adapter, der entgegen dem Vertrag wirft, wird nicht zur 500 - UNAVAILABLE bleibt die Antwort`() {
        val body = get(CurrentAffairsController { error("Feed kaputt") })

        assertEquals("UNAVAILABLE", body.freshness)
        assertTrue(body.items.isEmpty())
    }

    @Test
    fun `STALE reist unveraendert an den Rand - das FE darf es nie als frisch zeigen`() {
        assertEquals("STALE", get(controller(freshness = CurrentAffairsFreshness.STALE)).freshness)
    }
}
