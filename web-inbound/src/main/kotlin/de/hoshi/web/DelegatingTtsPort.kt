package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicReference

/**
 * **DelegatingTtsPort** — die Laufzeit-Umschalt-Naht der TTS-Engine (Andi-Video-
 * Auftrag: „TTS-Engine in den Einstellungen wählbar, ohne Neustart"). Ein
 * [TtsPort], der JEDEN Aufruf an den GERADE aktiven Delegaten weiterreicht — ein
 * `PUT /api/v1/settings/tts` tauscht den Delegaten aus ([switchTo]), ALLE
 * folgenden Aufrufe (auch ein bereits laufender Turn, der den Port erst nach dem
 * Wechsel wieder anfasst) landen sofort beim neuen Adapter.
 *
 * **Bewusst NUR Delegation, keine Adapter-Logik:** diese Klasse synthetisiert
 * selbst nichts — sie hält atomar (`AtomicReference`, ein einziger swap, keine
 * Teil-Updates möglich) eine (`engineId`, `TtsPort`)-Paarung und reicht jeden
 * Aufruf 1:1 an den aktuellen Delegaten durch. Die vier echten Adapter
 * (Voxtral/OpenAI/say/Piper) bleiben UNANGETASTET — [TtsEngineFactory] baut sie,
 * [de.hoshi.web.TtsSettingsController] entscheidet WANN gewechselt wird.
 *
 * **Telemetrie folgt der Laufzeit-Wahl:** [currentEngineId] liefert den Namen des
 * GERADE aktiven Delegaten — [PipelineConfig.ttsStage] liest ihn pro Satz für das
 * `TtsAudioStart.provider`-Tag, statt einen beim Boot eingefrorenen String zu
 * behaupten (kein „Lüge"-Tag nach einem Runtime-Switch).
 */
class DelegatingTtsPort(
    initialEngineId: String,
    initial: TtsPort,
) : TtsPort {

    /** Engine-Id + Adapter werden IMMER gemeinsam getauscht — nie eine Teil-Inkonsistenz sichtbar. */
    private data class Active(val engineId: String, val port: TtsPort)

    private val active = AtomicReference(Active(initialEngineId, initial))

    /** Der Name der GERADE aktiven Engine (`"openai"`/`"say"`/`"piper"`/`"voxtral"`). */
    fun currentEngineId(): String = active.get().engineId

    /** Atomarer Wechsel — ab dem NÄCHSTEN Aufruf (auch mitten in einem laufenden Turn) aktiv. */
    fun switchTo(engineId: String, port: TtsPort) {
        active.set(Active(engineId, port))
    }

    override fun synth(text: String, language: Language): Mono<ByteArray> =
        active.get().port.synth(text, language)

    override fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> =
        active.get().port.synth(text, language, voice)

    override fun synthStream(text: String, language: Language): Flux<ByteArray> =
        active.get().port.synthStream(text, language)

    override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> =
        active.get().port.synthStream(text, language, voice)
}
