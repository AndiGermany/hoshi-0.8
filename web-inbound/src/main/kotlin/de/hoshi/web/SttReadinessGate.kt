package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.SttPort
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * **SttReadinessGate** — dünner [SttPort]-Dekorator (0.5-Port „Daily-Driver-Gap #1",
 * 2026-06-08). Lehre aus 0.5: hängt der Whisper-Sidecar (DOWN/Deadlock), läuft jeder
 * Voice-Turn erst ins 30-Sekunden-Timeout des [de.hoshi.adapters.stt.WhisperSttAdapter],
 * bevor das System endlich auf das leere Transkript zurückfällt — 30 s Stille für Andi.
 *
 * Dieses Gate konsultiert VOR dem `transcribe`-Call den ehrlichen Sidecar-Snapshot
 * (Welle 2, [SidecarHealthService]): ist `whisper-stt` BEKANNT DOWN, liefert es SOFORT
 * ein leeres Transkript (`Mono.just("")`) statt zu warten — der vorhandene warme
 * `no_input`-Pfad im [VoiceInboundController] greift dann unverändert (sichtbare,
 * gesprochene STT-Absage statt 30 s Hänger). Kein eigener Probe-Mechanismus: der
 * Snapshot ist die EINZIGE Wahrheitsquelle.
 *
 * **Flag-gated, default OFF** (`HOSHI_VOICE_STT_READINESS_GATE_ENABLED`): bei OFF ist
 * der Dekorator ein reiner Pass-Through (delegiert IMMER) ⇒ verhaltens-/byte-neutral,
 * exakt der heutige Pfad. Auch bei ON greift das Gate NUR bei eindeutigem DOWN —
 * OK/DEGRADED/UNKNOWN (Watchdog aus / noch kein Probe-Lauf, [healthStatus] liefert
 * `null`) fallen durch zum normalen Transkribieren. „Vielleicht ok" wird lieber
 * 1× normal probiert als blind blockiert (0.5-Maxime: UNKNOWN ≠ DOWN).
 *
 * Best-Effort/Never-Silent bleibt gewahrt: das Gate WIRFT NIE — es ersetzt höchstens
 * einen langen Timeout durch ein sofortiges leeres Transkript.
 *
 * @param delegate   der echte STT-Port (live: [de.hoshi.adapters.stt.WhisperSttAdapter]).
 * @param healthStatus liest den geglätteten Status-String eines Sidecars (i.d.R.
 *   [SidecarHealthService.statusOf]); `null` = UNKNOWN ⇒ das Gate greift NICHT.
 * @param enabled    das Feature-Flag (`HOSHI_VOICE_STT_READINESS_GATE_ENABLED`).
 */
class SttReadinessGate(
    private val delegate: SttPort,
    private val healthStatus: (String) -> String?,
    private val enabled: Boolean,
) : SttPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> {
        if (enabled && healthStatus(WHISPER_STT) == STATUS_DOWN) {
            log.info(
                "[stt-readiness-gate] whisper-stt BEKANNT DOWN → sofortiges leeres Transkript " +
                    "(warmer no_input-Pfad statt ~30s-Timeout)",
            )
            return Mono.just("")
        }
        return delegate.transcribe(audioWav, language)
    }

    companion object {
        /** Contract-Name des STT-Sidecars im [SidecarHealthService]-Snapshot. */
        const val WHISPER_STT = "whisper-stt"

        /** Der EINZIGE Status, der das Gate auslöst (DEGRADED/UNKNOWN tun es bewusst NICHT). */
        const val STATUS_DOWN = "DOWN"
    }
}
