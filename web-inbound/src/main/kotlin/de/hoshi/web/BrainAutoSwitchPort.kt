package de.hoshi.web

import reactor.core.publisher.Mono

/**
 * **BrainAutoSwitchPort** — die hexagonale Naht des automatischen Brain-Modell-
 * wechsels an Sitzungs-/Medien-Grenzen (Andi-Auftrag „12B für Chat, e4b für
 * Voice", 2026-07-26; Messbasis: Wechsel kostet nur 2,6-4,3s warm, 12B generiert
 * aber 2-4x langsamer — fürs Sprechen inakzeptabel, beim Tippen egal). Anker sind
 * SITZUNGS-/MEDIEN-Grenzen, NICHT jeder Turn (kein Thrashing):
 *
 *  - [onVoiceSessionStart]: der WS `/ws/audio` `start`-Frame ([AudioWebSocketHandler.onStart])
 *    — der Wechsel läuft ASYNCHRON, während der Nutzer noch spricht (die paar
 *    Sekunden verstecken sich in der Sprechzeit). Blockiert den Aufrufer NIE.
 *  - [ensureChatModel]: ein Text-Chat-Turn ([ChatStreamController.stream]) — der
 *    Wechsel wird angestossen UND abgewartet (beim Tippen sind ein paar Sekunden
 *    Aufpreis okay), der Turn läuft DANACH auf dem gerade geladenen Modell weiter.
 *
 * Zwei Implementierungen: [BrainAutoSwitchPort.NOOP] (Default an beiden Rändern,
 * `brainAutoSwitch`-Setting AUS ⇒ byte-neutral, KEIN Health-/Switch-Call) und
 * [BrainAutoSwitchService] (die echte Logik: Hysterese + Wiederverwendung des
 * bestehenden [BrainSwitchModelPort]/[BrainHealthProbe]).
 *
 * **Never-Silent (bindend):** beide Methoden werfen NIE und blockieren NIE
 * unbegrenzt — ein fehlgeschlagener/hängender Wechsel darf einen Turn nicht
 * verzögern, der schon eine Antwort haben könnte. Der Turn läuft in diesem Fall
 * einfach mit dem GERADE geladenen Modell weiter.
 */
interface BrainAutoSwitchPort {

    /** WS-Session-/Turn-Start am Voice-Rand — ASYNCHRON, fire-and-forget. */
    fun onVoiceSessionStart()

    /** Text-Chat-Turn — anstossen UND abwarten (bounded über [BrainSwitchModelPort]s eigenen Timeout). */
    fun ensureChatModel(): Mono<Unit>

    companion object {
        /** Der byte-neutrale Default: tut buchstäblich nichts, an beiden Nähten. */
        val NOOP: BrainAutoSwitchPort = object : BrainAutoSwitchPort {
            override fun onVoiceSessionStart() {}
            override fun ensureChatModel(): Mono<Unit> = Mono.just(Unit)
        }
    }
}
