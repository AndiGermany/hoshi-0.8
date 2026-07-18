package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language

/**
 * Single source of truth für die Keyword-Listen + heuristischen Klassifizierer
 * im Routing-Hot-Path (Smart-Home-Kandidat, Komplexitätsscore, OpenClaw-Eligibility)
 * — PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger.
 *
 * Entkoppelt von Spring: statt `HoshiProperties` nimmt der Konstruktor direkt den
 * [complexityThreshold] (Default 4) entgegen. Reines Kotlin, kein `@Service` — das
 * Wiring (Config-Injektion) kommt im Orchestrator.
 */
class IntentClassifier(
    private val complexityThreshold: Int = 4,
) {
    object Keywords {
        val smartHomeVerbs = setOf(
            "schalte", "dimme", "setze", "stelle", "starte", "stoppe", "öffne", "schließe",
        )
        val smartHomeTargets = setOf(
            "licht", "lampe", "rollo", "jalousie", "heizung", "szene", "fernseher", "musik",
        )
        val complexityMarkers = listOf(
            "routine", "wenn dann", "falls dann", "plan erstellen", "strategie",
            "mehrere räume", "überall", "gleichzeitig",
        )
        val agentMarkers = listOf(
            "erinnere dich", "merke dir", "vergiss nicht",
            "suche im internet", "google",
            "spiel mir", "spiel den", "spiel die", "nächste episode", "was lief",
            "wieviel strom", "stromverbrauch", "verbrauch",
            "wie warm", "wie kalt", "temperatur",
            "erstelle eine einkaufsliste", "füge zur einkaufsliste",
            "füge hinzu", "zur liste",
            "was steht", "kalender", "termin",
            "organisiere", "plane", "erstelle einen plan",
            "öffne", "klick auf", "browser",
        )
        val haRooms = listOf(
            "wohnzimmer", "schlafzimmer", "küche", "bad", "büro", "flur", "keller",
        )
    }

    /**
     * [language] (0.8): heute für alle Sprachen die DE-Keyword-Listen. Der Parameter
     * trägt die Sprache durch, damit per-Sprache-Keyword-Sets später andocken können,
     * ohne die Signatur zu brechen. Default [Language.DEFAULT].
     */
    fun isSmartHomeCandidate(query: String, language: Language = Language.DEFAULT): Boolean {
        val q = query.lowercase().trim()
        if (q.isBlank()) return false
        return Keywords.smartHomeVerbs.any { q.contains(it) } &&
            Keywords.smartHomeTargets.any { q.contains(it) }
    }

    /**
     * Komplexitätsscore: höhere Werte = aufwendigere Anfrage. Über
     * [complexityThreshold] wird das OpenClaw-Routing ausgelöst.
     */
    fun complexityScore(query: String, language: Language = Language.DEFAULT): Int {
        val q = query.lowercase().trim()
        var s = 0
        if (q.length > 120) s += 3
        if (Keywords.complexityMarkers.any { q.contains(it) }) s += 3
        if (Keywords.agentMarkers.any { q.contains(it) }) s += 4
        if (q.count { it == '?' } >= 2) s += 1
        if (Keywords.haRooms.count { q.contains(it) } > 2) s += 2
        return s
    }

    fun isOpenClawEligible(query: String, language: Language = Language.DEFAULT): Boolean =
        complexityScore(query, language) >= complexityThreshold
}
