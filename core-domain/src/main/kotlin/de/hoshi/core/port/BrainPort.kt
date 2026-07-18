package de.hoshi.core.port

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.LlmDelta
import reactor.core.publisher.Flux

/**
 * Inferenz-Naht (hexagonaler Port, behavior-preserving aus Hoshi 0.5 `LlmClient`).
 *
 * Heute genau EINE Impl: der MLX-Brain-Adapter (e4b-Sidecar, `/v1/chat`, :8041).
 * Zweck ist NICHT Runtime-Modell-Selektion (zwei Modelle warm = OOM auf 16 GB),
 * sondern allein die Test-Naht: die Domäne hängt am Interface statt an der
 * konkreten Klasse, sodass Unit-Tests einen Fake injizieren können statt den
 * Live-Brain zu brauchen.
 */
interface BrainPort {
    /**
     * Streamt eine Chat-Antwort als [Flux] von Text-Deltas. Siehe die aktive
     * Adapter-Impl für die Wire-Semantik des Brain.
     */
    fun streamChat(
        prompt: String,
        systemPrompt: String = "",
        history: List<ChatMessage> = emptyList(),
        temperature: Double? = null,
        sessionId: String = "default",
        userId: String = "default",
        // Agentische gemma-Tool-Schemas (siehe AgenticToolRegistry.schemas()). Default
        // leer ⇒ heutiges Verhalten unverändert (kein `tools`-Feld im Request-Body).
        // Die Orchestrator-Nutzung kommt in einer SEPARATEN Scheibe — hier nur die
        // Signatur + Durchreichung bis in den Adapter.
        tools: List<Map<String, Any?>> = emptyList(),
        // **PATH B (forced tool-JSON).** `true` ⇒ der Brain wird gebeten, seine
        // Ausgabe STRUKTURELL auf ein einzelnes JSON-Objekt `{tool,args}` zu zwingen
        // (`tool_grammar:true` im Body; siehe ToolGrammarParser). Das Ergebnis ist
        // KEINE sprechbare Prosa — der Agentik-Turn puffert + parst es. Default
        // `false` ⇒ Body byte-identisch zu vorher (kein `tool_grammar`-Feld).
        toolGrammar: Boolean = false,
        onPrefill: (Long) -> Unit = {},
    ): Flux<LlmDelta>
}
