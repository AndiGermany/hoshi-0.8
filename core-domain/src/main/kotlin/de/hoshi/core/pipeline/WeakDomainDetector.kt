package de.hoshi.core.pipeline

/**
 * Erkennt **Query-Kategorien**, bei denen das lokale Brain zuverlässig schwach
 * ist (Rezepte/Kochen/Backen + mehrschrittige How-Tos) — PORT-Einheit aus dem
 * Hoshi-0.5 brain-streaming-Ledger (`de.hoshi.app.cloud.WeakDomainDetector`).
 *
 * Reine Heuristik (keine Infra) → 1:1 mitportiert, damit die [HonestyGate]-Policy
 * ihre Recipe-Klasse ohne Fake testen kann. De-Spring't: kein `@Component`, kein
 * Logging. Implementiert [WeakDomainSignal].
 *
 * **Konservativ — zwei Gates:** (1) ein Domänen-Marker muss matchen; (2) bei
 * How-To-Verben muss zusätzlich ein „wie …"-/„anleitung"-/„schritt"-Kontext da
 * sein, damit „ich installiere gerade ein Update" (Aussage) NICHT triggert.
 */
class WeakDomainDetector : WeakDomainSignal {

    enum class Domain { RECIPE, HOWTO }

    data class Detection(
        val matched: Boolean,
        val domain: Domain?,
        val trigger: String?,
    ) {
        companion object {
            val NONE = Detection(matched = false, domain = null, trigger = null)
        }
    }

    /** Marker für Rezept-/Koch-Domäne. Treffer reicht allein (eindeutig genug). */
    private val recipeMarkers: List<String> = listOf(
        "rezept", "zutaten",
        "wie backe ich", "wie back ich", "wie koche ich", "wie mache ich",
        "wie bereite ich", "wie zubereite", "zubereiten",
        "wie brate ich", "wie grille ich",
    )

    /** How-To-Verben — matchen NUR mit [howToContext] (sonst Aussage-Fehltrigger). */
    private val howToVerbs: List<String> = listOf(
        "repariere", "reparier", "installiere", "installier",
        "baue", "zusammenbaue", "montiere", "montier",
        "wechsle", "wechsel", "tausche", "verlege", "entferne",
        "richte ein", "konfiguriere", "konfigurier",
    )

    /** Kontext-Marker, die eine How-To-Frage von einer Aussage unterscheiden. */
    private val howToContext: List<String> = listOf(
        "wie ", "anleitung", "schritt für schritt", "schritte",
        "wie kann ich", "wie geht",
    )

    /** Smart-Home-Imperative — defensiver Ausschluss (sollten eh vor Route B raus). */
    private val haImperatives: List<String> = listOf(
        "mach das ", "mach die ", "mach den ", "schalte ",
        "stell die ", "stell den ", "dimm ", "spiel ",
        "starte das", "starte die",
    )

    /** Scannt die **Original-Query** (nicht die Antwort). */
    fun detect(query: String): Detection {
        if (query.isBlank()) return Detection.NONE
        val q = query.lowercase()

        // Gate 0: HA-Imperative haben Vorrang → niemals WeakDomain.
        if (haImperatives.any { q.contains(it) }) return Detection.NONE

        // Recipe: Marker-Treffer reicht.
        val recipeHit = recipeMarkers.firstOrNull { q.contains(it) }
        if (recipeHit != null) {
            return Detection(matched = true, domain = Domain.RECIPE, trigger = recipeHit)
        }

        // HowTo: Verb-Treffer NUR mit Kontext.
        val howToVerb = howToVerbs.firstOrNull { q.contains(it) }
        if (howToVerb != null && howToContext.any { q.contains(it) }) {
            return Detection(matched = true, domain = Domain.HOWTO, trigger = howToVerb)
        }

        return Detection.NONE
    }

    /** Convenience: bool-only — erfüllt [WeakDomainSignal]. */
    override fun isWeakDomain(text: String): Boolean = detect(text).matched
}
