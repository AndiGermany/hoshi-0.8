package de.hoshi.core.dto

/**
 * Reale Token-Usage aus einer LLM-Provider-Response.
 *
 * Cloud-Provider liefern im Stream die ECHTEN Token-Counts (OpenAI `usage`,
 * Anthropic `usage`, Gemini `usageMetadata`). `estimated=false` heißt: aus dem
 * Provider-`usage`-Feld gelesen (gemessen). Wo ein Provider kein Usage liefert,
 * bleibt das Feld null und der Caller fällt auf die Zeichen-Schätzung zurück.
 */
data class LlmUsage(
    val promptTokens: Int,
    val completionTokens: Int,
)

/** Ein Token-Delta aus einem LLM-Stream (text + done-Marker). */
data class LlmDelta(
    val text: String,
    val done: Boolean = false,
    /** Nur im finalen Chunk gesetzt, wenn der Provider echte Token-Counts liefert. Sonst null → Caller schätzt. */
    val usage: LlmUsage? = null,
    /**
     * **Log-Wahrscheinlichkeit des gesampelten Tokens** (natürlicher Logarithmus,
     * ≤ 0), das dieses Delta erzeugt hat — der Rohstoff des Antwort-Entropie-
     * Sensors („rate ich gerade?", ANDI-RESEARCH 2026-07-06). Additiv + ehrlich:
     * `null` = der Provider hat KEINEN Logprob geliefert (heutiges server_e4b
     * ohne Patch, Flag OFF, Cloud ohne logprobs) — nie ein erfundener Wert.
     * Der Konsument ([de.hoshi.core.pipeline.TurnOrchestrator]) mittelt laufend
     * (Summe+Zähler, kein Speicher-Wachstum) und legt −mean(logprob) als
     * `answerEntropy` ans Done-Event.
     */
    val logprob: Double? = null,
)
