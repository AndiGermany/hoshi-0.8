package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicReference

/**
 * **DelegatingEscalationPort** — die Laufzeit-Umschalt-Naht des SCHNELL-Lookup-
 * Modells (Andi-Video-Auftrag: „Lookup-Sprachmodell in den Einstellungen
 * wählbar, ohne Neustart"). Spiegelt [DelegatingTtsPort] 1:1, nur für
 * [EscalationPort] statt [de.hoshi.core.port.TtsPort].
 *
 * **Betrifft NUR den schnellen Nachschlag-Port** (`hoshi.escalation.model`,
 * `PipelineConfig.escalationPort`) — der Recherche-Port
 * (`hoshi.escalation.research-model`, `PipelineConfig.researchEscalationPort`,
 * explizites „recherchiere online") bleibt komplett unangetastet und weiter
 * NUR über die Env konfigurierbar (Andi-Vorgabe). Beide Ports teilen sich
 * weiterhin DENSELBEN [de.hoshi.adapters.escalation.EscalationSpendStore] — ein
 * Tages-Cap für beide Modelle, auch nach einem Runtime-Switch hier.
 *
 * **Bewusst NUR Delegation:** hält atomar (`engineId`≡Modell-Id, [EscalationPort])
 * und reicht [lookup] 1:1 durch. Der `OpenAiEscalationAdapter` selbst bleibt
 * unangetastet — [de.hoshi.web.LookupModelController] baut bei einem PUT eine
 * frische Instanz mit dem gewählten Modell und ruft [switchTo].
 */
class DelegatingEscalationPort(
    initialModelId: String,
    initial: EscalationPort,
) : EscalationPort {

    private data class Active(val modelId: String, val port: EscalationPort)

    private val active = AtomicReference(Active(initialModelId, initial))

    /** Die GERADE aktive Modell-Id (auch dann gesetzt, wenn die Decke zu ist und [Active.port] NONE ist). */
    fun currentModelId(): String = active.get().modelId

    /** Atomarer Wechsel — ab dem NÄCHSTEN Lookup aktiv. */
    fun switchTo(modelId: String, port: EscalationPort) {
        active.set(Active(modelId, port))
    }

    override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> =
        active.get().port.lookup(query, groundingSnippets, language)
}
