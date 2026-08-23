package de.hoshi.core.pipeline

import java.util.concurrent.ConcurrentHashMap

/**
 * Eine atomare Naht vor den drei historisch getrennten Pending-Stores.
 *
 * Pro [key] darf genau EINE Rückfrage aktiv sein. Alle produktiven Offers laufen
 * durch diesen Arbiter; ein neues Offer ersetzt unter demselben Stripe-Lock jede
 * ältere Art. [consume] zieht danach nur die bekannte aktive Art. Für Alt-Daten und
 * direkt vorbefüllte Tests ohne Arbiter-Metadatum existiert ein deterministischer
 * Recovery-Pfad, der alle Funde explizit bilanziert statt sie still zu verlieren.
 */
class PendingTurnArbiter(
    private val lookup: PendingLookupPort,
    private val location: PendingLocationQuestionPort,
    private val area: PendingAreaClarifyPort,
) {
    enum class Kind { LOOKUP, LOCATION, AREA }

    enum class Outcome {
        /** Dieser Zustand wurde als einziger Kandidat an den Turn übergeben. */
        CONSUMED,
        /** Ein neueres Offer derselben oder einer anderen Art hat ihn verdrängt. */
        REPLACED,
        /** Nur der Area-Store kann Ablauf heute exakt unterscheiden. */
        EXPIRED,
        /** Arbiter-Metadatum existierte, der darunterliegende Store aber nicht mehr. */
        MISSING,
        /** Legacy-Konflikt: Zustand wurde gezogen, verlor aber die feste Priorität. */
        ABANDONED,
    }

    sealed interface State {
        val kind: Kind

        data class Lookup(val pending: PendingLookup) : State {
            override val kind: Kind = Kind.LOOKUP
        }

        data class Location(val pending: PendingLocationQuestion) : State {
            override val kind: Kind = Kind.LOCATION
        }

        data class Area(val pending: PendingAreaClarify) : State {
            override val kind: Kind = Kind.AREA
        }
    }

    data class Transition(val kind: Kind, val outcome: Outcome)
    data class OfferResult(val active: Kind, val transitions: List<Transition>)
    data class Consumption(val selected: State?, val transitions: List<Transition>)

    private val active = ConcurrentHashMap<String, Kind>()
    private val locks = Array(64) { Any() }

    fun offerLookup(key: String, pending: PendingLookup): OfferResult =
        offer(key, Kind.LOOKUP) { lookup.offer(key, pending) }

    fun offerLocation(key: String, pending: PendingLocationQuestion): OfferResult =
        offer(key, Kind.LOCATION) { location.offer(key, pending) }

    fun offerArea(key: String, pending: PendingAreaClarify): OfferResult =
        offer(key, Kind.AREA) { area.offer(key, pending) }

    private fun offer(key: String, kind: Kind, write: () -> Unit): OfferResult =
        synchronized(lockFor(key)) {
            val previouslyActive = active.remove(key)
            val pulled = Kind.entries.mapNotNull { pull(key, it) }
            val displaced = pulled
                .map { Transition(it.state.kind, if (it.expired) Outcome.EXPIRED else Outcome.REPLACED) }
                .toMutableList()
            if (previouslyActive != null && pulled.none { it.state.kind == previouslyActive }) {
                // Lookup/Location verbergen TTL-Ablauf als null. Das Metadatum kann
                // deshalb nur ehrlich „nicht mehr vorhanden" sagen — aber nie schweigen.
                displaced += Transition(previouslyActive, Outcome.MISSING)
            }
            write()
            // Metadaten sind nur eine Optimierung; die Stores bleiben Wahrheit.
            // Bei missbräuchlich vielen Einmal-Keys darf diese Hilfsmap nicht wachsen.
            if (active.size >= MAX_ACTIVE_KEYS) active.clear()
            active[key] = kind
            OfferResult(kind, displaced)
        }

    /**
     * One-shot. Mit Arbiter-Metadatum wird genau ein Store berührt. Ohne Metadatum
     * (Upgrade-/Test-Fall) gewinnt: Lookup-Consent, Ort, Raum, Lookup-Thema.
     */
    fun consume(key: String): Consumption = synchronized(lockFor(key)) {
        val known = active.remove(key)
        if (known != null) {
            val pulled = pull(key, known)
                ?: return@synchronized Consumption(null, listOf(Transition(known, Outcome.MISSING)))
            if (pulled.expired) {
                return@synchronized Consumption(null, listOf(Transition(known, Outcome.EXPIRED)))
            }
            return@synchronized Consumption(pulled.state, listOf(Transition(known, Outcome.CONSUMED)))
        }

        val pulled = Kind.entries.mapNotNull { pull(key, it) }
        val expired = pulled.filter { it.expired }.map { Transition(it.state.kind, Outcome.EXPIRED) }
        val viable = pulled.filterNot { it.expired }
        val winner = viable.minByOrNull(::priority)
        val lifecycle = expired + viable.map {
            Transition(it.state.kind, if (it === winner) Outcome.CONSUMED else Outcome.ABANDONED)
        }
        Consumption(winner?.state, lifecycle)
    }

    private data class Pulled(val state: State, val expired: Boolean = false)

    private fun pull(key: String, kind: Kind): Pulled? = when (kind) {
        Kind.LOOKUP -> lookup.consume(key)?.let { Pulled(State.Lookup(it)) }
        Kind.LOCATION -> location.consume(key)?.let { Pulled(State.Location(it)) }
        Kind.AREA -> area.consume(key)?.let { Pulled(State.Area(it.pending), expired = it.expired) }
    }

    private fun priority(pulled: Pulled): Int = when (val state = pulled.state) {
        is State.Lookup -> if (state.pending.awaitsTopic) 3 else 0
        is State.Location -> 1
        is State.Area -> 2
    }

    private fun lockFor(key: String): Any {
        val hash = key.hashCode()
        return locks[(hash xor (hash ushr 16)) and (locks.size - 1)]
    }

    private companion object {
        const val MAX_ACTIVE_KEYS = 1_024
    }
}
