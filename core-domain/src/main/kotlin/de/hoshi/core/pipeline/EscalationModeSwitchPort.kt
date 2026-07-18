package de.hoshi.core.pipeline

/**
 * **EscalationModeSwitchPort** — die schmale Domänen-Naht, über die die
 * Extended-Think-Stufe ([EscalationMode]) per SPRACHE/CHAT umgeschaltet wird
 * (Andi-Intent 2026-07-05: Stufen „auch über die stimme setzen").
 *
 * Der [EscalationModeFastpath] erkennt den Stufen-Wunsch deterministisch und
 * ruft [switchTo] — der Implementor (`web-inbound`, `StoreEscalationModeSwitch`)
 * schreibt in DENSELBEN Store wie `PUT /api/v1/settings/extended-think`
 * (`JsonFileEscalationModeStore`): eine Wahrheit, zwei Bedien-Ränder. Exakt das
 * [WeatherLocationAskPort]-Muster („dieselbe Store-Wahrheit wie der
 * Settings-PUT"), nur ohne Reactor (der Store-Write ist ein kleiner lokaler
 * File-Write, kein Netz-I/O).
 *
 * [NONE] ist der verhaltens-neutrale Default (persistiert nie, meldet ehrlich
 * `false`) ⇒ ohne Wiring bleibt jeder Pfad byte-identisch — exakt das Muster
 * der anderen Orchestrator-Nähte ([PendingLookupPort.NONE],
 * [WeatherLocationAskPort.NONE]).
 */
interface EscalationModeSwitchPort {

    /**
     * Persistiert [mode] als neue Laufzeit-Stufe — DIESELBE Store-Wahrheit wie
     * der Settings-PUT. TRUE gdw. der Persist BEWIESEN gelungen ist; FALSE bei
     * Schreibfehler (der Implementor schluckt + loggt, wirft NIE) — der
     * Aufrufer antwortet dann ehrlich statt fake-bestätigt.
     */
    fun switchTo(mode: EscalationMode): Boolean

    companion object {
        /** Default: persistiert nie (ehrlich `false`) ⇒ byte-neutral ohne Wiring. */
        val NONE: EscalationModeSwitchPort = object : EscalationModeSwitchPort {
            override fun switchTo(mode: EscalationMode): Boolean = false
        }
    }
}
