package de.hoshi.web

import de.hoshi.core.pipeline.EscalationMode
import de.hoshi.core.pipeline.EscalationModeSwitchPort
import org.slf4j.LoggerFactory

/**
 * **StoreEscalationModeSwitch** — die web-inbound-Implementierung der
 * [EscalationModeSwitchPort]-Naht (Extended-Think-Stufe per Sprache/Chat):
 * delegiert an DENSELBEN [JsonFileEscalationModeStore], den auch
 * `PUT /api/v1/settings/extended-think` ([ExtendedThinkController]) schreibt —
 * eine Wahrheit, zwei Bedien-Ränder. Ein per Stimme gesetzter Mode ist damit
 * sofort im Settings-GET sichtbar und wirkt (Mode-Supplier liest pro Turn den
 * Store-Cache) ab dem nächsten Turn.
 *
 * **Wirft NIE** (Doktrin der Turn-Nähte): ein Persist-Fehler des Stores
 * ([JsonFileEscalationModeStore.setMode] wirft bei Schreib-Fehler,
 * persist-then-commit — der Cache bleibt dann unangetastet) wird hier geloggt
 * und als ehrliches `false` gemeldet — der Fastpath antwortet dann offen statt
 * fake-bestätigt.
 */
class StoreEscalationModeSwitch(
    private val store: JsonFileEscalationModeStore,
) : EscalationModeSwitchPort {

    private val log = LoggerFactory.getLogger(StoreEscalationModeSwitch::class.java)

    override fun switchTo(mode: EscalationMode): Boolean =
        runCatching { store.setMode(mode) }
            .onFailure { e -> log.warn("Extended-Think-Stufe per Sprache NICHT persistiert ({})", e.toString()) }
            .isSuccess
}
