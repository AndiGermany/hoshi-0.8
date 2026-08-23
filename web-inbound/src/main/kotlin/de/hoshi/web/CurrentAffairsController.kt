package de.hoshi.web

import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsPort
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSnapshot
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Instant

/**
 * **CurrentAffairsController** — the read-only edge of the news slice
 * (`GET /api/v1/currentaffairs/today`), behind the [PerimeterWebFilter] token
 * wall like every `/api/v1` path (no token ⇒ 401).
 *
 * **No error status codes, on purpose** — unlike [WeatherTodayController]: the
 * [CurrentAffairsPort] contract makes unavailability part of the DATA
 * ([CurrentAffairsFreshness.UNAVAILABLE] with an empty item list), so this
 * endpoint always answers 200 with an honest snapshot. A caller that renders
 * `freshness` cannot accidentally show stale items as current.
 *
 * `?limit=` is capped at [MAX_LIMIT] here and the item list is trimmed again
 * after the port call — the wire contract holds even if an adapter ignores the
 * query limit.
 *
 * The port call is synchronous (file/SQLite in the adapter), so it runs on
 * [Schedulers.boundedElastic] instead of the event loop.
 */
@RestController
class CurrentAffairsController(private val port: CurrentAffairsPort) {

    @GetMapping("/api/v1/currentaffairs/today")
    fun today(@RequestParam(required = false) limit: Int?): Mono<CurrentAffairsSnapshotWire> =
        Mono.fromCallable { read(limit) }.subscribeOn(Schedulers.boundedElastic())

    /** The blocking body of [today] — directly testable without a Spring context. */
    internal fun read(limit: Int?): CurrentAffairsSnapshotWire {
        val effectiveLimit = (limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
        // The port promises never to throw; a rogue adapter must still not turn a
        // dashboard read into a 500 — it degrades to the honest UNAVAILABLE shape.
        val snapshot = try {
            port.latest(CurrentAffairsQuery(limit = effectiveLimit))
        } catch (e: Exception) {
            unavailable()
        }
        return snapshot.toWire(effectiveLimit)
    }

    private fun unavailable() = CurrentAffairsSnapshot(
        items = emptyList(),
        observedAt = Instant.now(),
        lastSuccessfulRefreshAt = null,
        freshness = CurrentAffairsFreshness.UNAVAILABLE,
    )

    companion object {
        /** Spoken/tile default — the same three headlines the voice briefing reads. */
        const val DEFAULT_LIMIT = 3

        /** Hard ceiling for the `/lagebild` route; larger values are capped, never rejected. */
        const val MAX_LIMIT = 20

        /** A briefing of zero items is not a question anyone means to ask. */
        const val MIN_LIMIT = 1
    }
}

/**
 * Wire shape of one headline — field names 1:1 with
 * [de.hoshi.core.port.CurrentAffairsItem]; only the types are wire types
 * (enum ⇒ name, `Instant` ⇒ ISO-8601 string), so the JSON does not depend on
 * Jackson time/enum configuration. `canonicalUrl` is for LINKING, never for
 * speaking.
 */
data class CurrentAffairsItemWire(
    val id: String,
    val source: String,
    val title: String,
    val snippet: String?,
    val canonicalUrl: String,
    val publishedAt: String?,
    val fetchedAt: String,
    val attribution: String,
)

/**
 * Wire shape of the snapshot — field names 1:1 with
 * [de.hoshi.core.port.CurrentAffairsSnapshot]. `observedAt` (when this read
 * happened) and `lastSuccessfulRefreshAt` (when the source last answered) stay
 * separate on the wire too: only the latter may be rendered as "as of HH:MM".
 */
data class CurrentAffairsSnapshotWire(
    val items: List<CurrentAffairsItemWire>,
    val observedAt: String,
    val lastSuccessfulRefreshAt: String?,
    val freshness: String,
)

private fun CurrentAffairsSnapshot.toWire(limit: Int) = CurrentAffairsSnapshotWire(
    items = items.take(limit).map {
        CurrentAffairsItemWire(
            id = it.id,
            source = it.source.name,
            title = it.title,
            snippet = it.snippet,
            canonicalUrl = it.canonicalUrl,
            publishedAt = it.publishedAt?.toString(),
            fetchedAt = it.fetchedAt.toString(),
            attribution = it.attribution,
        )
    },
    observedAt = observedAt.toString(),
    lastSuccessfulRefreshAt = lastSuccessfulRefreshAt?.toString(),
    freshness = freshness.name,
)
