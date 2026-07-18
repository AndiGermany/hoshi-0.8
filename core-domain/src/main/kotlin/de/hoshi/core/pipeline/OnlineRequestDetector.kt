package de.hoshi.core.pipeline

/**
 * Erkennt eine **explizite Aufforderung, etwas online / im Internet
 * nachzuschlagen** — PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger
 * (`de.hoshi.app.cloud.OnlineRequestDetector`).
 *
 * Reine Heuristik (keine Infra) → 1:1 mitportiert, damit die [HonestyGate]-Policy
 * ihre Online-Request-Klasse ohne Fake testen kann. De-Spring't: kein `@Component`,
 * kein Logging. Implementiert [OnlineRequestSignal].
 *
 * **Konservativ:** eine reine Wissensfrage ÜBER das Internet („Wie funktioniert
 * das Internet?", „Was ist Google?") darf NICHT triggern. Externe Scope-Marker
 * („online", „im internet" …) zählen nur in Kombination mit einem Nachschau-Verb;
 * nur unzweideutige Aktionswörter („recherchier", „google das") triggern allein.
 */
class OnlineRequestDetector : OnlineRequestSignal {

    /** Externe Scope-Marker: „geh über dein eigenes Wissen hinaus, ins offene Netz". */
    private val externalScope = listOf(
        "online", "im internet", "ins internet", "im netz", "ins netz",
        "im web", "im world wide web", "übers internet", "uebers internet",
    )

    /** Unzweideutige Aktionswörter — triggern allein (kein Scope-Marker nötig). */
    private val standaloneAction = listOf(
        "recherchier", "websuche", "web-suche", "internet-suche", "internetsuche",
        "googeln", "google mal", "googel mal", "google das", "googel das",
        "google bitte", "googel bitte", "google für mich", "googel für mich",
    )

    /** Nachschau-Verben — nur in Kombination mit einem externen Scope-Marker. */
    private val lookupVerbs = listOf(
        "schau", "guck", "seh", "sieh", "nachschau", "nachseh", "nachguck",
        "such", "find", "check", "prüf", "pruef", "recherch", "informier",
        "wie viele", "wieviele", "wie viel ", "gibt es", "gibt's", "rausfind",
        "raus find", "herausfind", "heraus find", "ermittl",
    )

    override fun isOnlineRequest(text: String): Boolean {
        if (text.isBlank()) return false
        val q = text.lowercase()

        if (standaloneAction.any { q.contains(it) }) return true
        val hasScope = externalScope.any { q.contains(it) }
        if (!hasScope) return false
        return lookupVerbs.any { q.contains(it) }
    }
}
