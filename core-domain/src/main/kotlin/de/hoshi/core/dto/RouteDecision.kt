package de.hoshi.core.dto

/**
 * Routing-Wire-Typen (PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger).
 *
 * Die [RouteDecision] ist das Ergebnis der Routing-Stufe: in welche fachliche
 * [RouteCategory] der Turn fällt und über welchen [RouteProvider] (lokaler
 * Brain vs. Cloud) er beantwortet wird. Bewusst reine Daten — die Routing-Logik
 * selbst (Heuristik/Embedding) lebt im Orchestrator/Adapter.
 */
enum class RouteCategory { SMALLTALK, SMART_HOME, FACT_SHORT, NEEDS_WEB, AMBIG, AGENT }

enum class RouteProvider { LOCAL, OPENAI, ANTHROPIC, HEDGE, OPENCLAW }

data class RouteDecision(
    val category: RouteCategory,
    val provider: RouteProvider,
    val reason: String,
)
