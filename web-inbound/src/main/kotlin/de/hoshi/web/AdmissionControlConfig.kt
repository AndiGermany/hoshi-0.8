package de.hoshi.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * **AdmissionControlConfig (Ticket #9)** — das Wiring der Last-/Admission-Control-Lane,
 * bewusst GETRENNT von [PipelineConfig] (eigene Lane, eigener Schreiber). Definiert die
 * EINE [BrainAdmissionGate]-Singleton-Bean; die Inbound-Seams ([ChatStreamController],
 * [VoiceInboundController], [WebSocketConfig]) injizieren genau diese Instanz, sodass das
 * Concurrent-Brain-Permit-Budget WIRKLICH global ist (ein Semaphore über alle Endpoints).
 *
 * **Alle Flags default OFF/großzügig ⇒ byte-neutral.** Andi schaltet beim Deploy scharf:
 *  - `HOSHI_BRAIN_ADMISSION_ENABLED` (default false) — Gate aus ⇒ reiner Passthrough.
 *  - `HOSHI_BRAIN_ADMISSION_MAX_CONCURRENT` (default 2) — der Brain ist seriell; 1..2 reicht.
 *  - `HOSHI_BRAIN_ADMISSION_REJECT_PHRASE` (default leer ⇒ [BrainAdmissionGate.DEFAULT_REJECT_PHRASE]).
 *
 * (Der Audio-Byte/Dauer-Cap ist KEINE Bean — er sitzt direkt an den zwei Audio-Rändern
 * [AudioWebSocketHandler] (per [WebSocketConfig] verdrahtet) und [VoiceInboundController]
 * (eigene `@Value`-Ctor-Params), jeweils flag-gated `HOSHI_AUDIO_CAP_*`.)
 */
@Configuration
class AdmissionControlConfig {

    @Bean
    fun brainAdmissionGate(
        @Value("\${hoshi.brain.admission.enabled:\${HOSHI_BRAIN_ADMISSION_ENABLED:false}}") enabled: Boolean,
        @Value("\${hoshi.brain.admission.max-concurrent:\${HOSHI_BRAIN_ADMISSION_MAX_CONCURRENT:2}}") maxConcurrent: Int,
        @Value("\${hoshi.brain.admission.reject-phrase:\${HOSHI_BRAIN_ADMISSION_REJECT_PHRASE:}}") rejectPhrase: String,
    ): BrainAdmissionGate = BrainAdmissionGate(
        enabled = enabled,
        maxConcurrent = maxConcurrent,
        rejectPhrase = rejectPhrase.ifBlank { BrainAdmissionGate.DEFAULT_REJECT_PHRASE },
    )
}
