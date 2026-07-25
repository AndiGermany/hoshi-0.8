package de.hoshi.adapters.escalation

import de.hoshi.core.port.EscalationAvailabilityPort

/**
 * Lokale, seiteneffektfreie Angebots-Prüfung des OpenAI-Nachschlags.
 * Verwendet DENSELBEN restart-festen [spendStore] und DENSELBEN Cap-Wert wie
 * [OpenAiEscalationAdapter], damit Hoshi bei bereits leerem Tagesbudget nicht
 * erst fragt und das zugesagte Nachschlagen danach sofort ablehnen muss.
 *
 * Der Store-Read kommt aus dessen RAM-Cache; kein Netz- oder Datei-I/O pro Turn.
 * Unerwartete Store-Fehler sind konservativ `false` (kein falsches Versprechen).
 */
class OpenAiEscalationAvailability(
    apiKey: String?,
    private val spendStore: EscalationSpendStore,
    private val dailyCapCents: Double = OpenAiEscalationAdapter.DEFAULT_DAILY_CAP_CENTS,
) : EscalationAvailabilityPort {
    private val keyConfigured = !apiKey?.trim().isNullOrEmpty()

    override fun canOffer(): Boolean {
        if (!keyConfigured) return false
        return runCatching { spendStore.spentTodayCents() < dailyCapCents }.getOrDefault(false)
    }
}
