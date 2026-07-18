package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.SttPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **WarmProbe** — hält die seriellen MLX-Sidecars (Whisper :9001, e4b-Brain :8041)
 * heiß, damit der ERSTE echte Turn nach Idle nicht den 13–15 s Cold-Start zahlt.
 * Portiert aus Hoshi 0.5 (`WhisperSttClient.preWarmOnStartup`/`periodicReWarm` +
 * `OllamaStreamingClient.periodicReWarm`), aber HEXAGON: hängt nur an [SttPort] und
 * [BrainPort], NICHT an den konkreten Adaptern — testbar mit Fakes.
 *
 * **Best-Effort / Never-Silent:** jeder Warm-Versuch ist KÜR. Fehler/Timeouts
 * werden geschluckt (leises `debug`-Log), nie geworfen — eine kalte Sonde darf den
 * Betrieb nie stören.
 *
 * **Default OFF, byte-neutral:** beide Flags stehen auf `false`. Bei OFF feuert der
 * Scheduler zwar (siehe [WarmProbeScheduling]), die Methoden kehren aber VOR jedem
 * Adapter-Call zurück ⇒ keine Netz-Calls, keine Sidecar-Last, Verhalten = heute.
 *
 * **16-GB/MLX-Serialität (Brain):** Whisper IST heute schon legitim immer-warm
 * (kleines Modell). Der Brain-Warm dagegen darf NICHT mit einem echten Turn
 * konkurrieren — auf 16 GB läuft MLX seriell. Da der [de.hoshi.core.pipeline.TurnOrchestrator]
 * hier bewusst UNANGETASTET bleibt, ist die Kollisions-Vermeidung best-effort lokal:
 * kurzer Timeout + `take(1)` (gibt bei Kollision schnell auf) + ein Re-Warm-Throttle.
 * Ein sauberes „skip bei laufendem Turn" braucht eine kleine Naht im Orchestrator
 * (siehe KDoc bei [warmBrainPeriodic]). Darum bleibt der Brain-Warm Default OFF.
 */
@Component
class WarmProbe(
    private val sttPort: SttPort,
    private val brainPort: BrainPort,
    /** **`HOSHI_WHISPER_PRE_WARM`** (Default `false`): Whisper :9001 heiß halten. */
    @Value("\${HOSHI_WHISPER_PRE_WARM:false}") private val whisperPreWarm: Boolean,
    /** **`HOSHI_LLM_PRE_WARM`** (Default `false`): e4b-Brain :8041 Pagecache heiß halten. */
    @Value("\${HOSHI_LLM_PRE_WARM:false}") private val llmPreWarm: Boolean,
    /** Sprach-Hint fürs Stille-WAV (Whisper `language`-Code), Default DE. */
    @Value("\${hoshi.warmprobe.language:de}") warmLanguageCode: String,
    /** Harter Timeout des Whisper-Warms (best-effort, ms). */
    @Value("\${hoshi.warmprobe.stt-timeout-ms:8000}") private val sttTimeoutMs: Long,
    /** Harter Timeout des Brain-Warms — kurz, damit eine Kollision schnell aufgibt (ms). */
    @Value("\${hoshi.warmprobe.brain-timeout-ms:4000}") private val brainTimeoutMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val language: Language = Language.fromCode(warmLanguageCode)

    /** Minimales 16 kHz-mono-PCM Stille-WAV (1.6 KB) — einmal gebaut, immer wiederverwendet. */
    private val silenceWav: ByteArray = buildSilenceWav()

    // Überlapp-Schutz: kein zweiter Warm derselben Art, solange einer läuft.
    private val whisperInProgress = AtomicBoolean(false)
    private val brainInProgress = AtomicBoolean(false)
    // Re-Warm-Throttle für den Brain (deckt den seltenen Overlap zweier Scheduler-Ticks).
    private val lastBrainWarmAt = AtomicLong(0)

    /**
     * Whisper sofort heiß machen, wenn der Context bereit ist — killt den
     * Cold-Start vor dem ersten Mic-Turn. Default OFF ⇒ no-op.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun warmWhisperOnStartup() {
        if (!whisperPreWarm) return
        runWhisperWarm("startup")
    }

    /**
     * Whisper-Re-Warm alle ~5 min: hält den macOS-Pagecache (mmap des Whisper-Modells)
     * davor, in den Swap zu fliegen. `initialDelay` = ein Intervall, weil
     * [warmWhisperOnStartup] den Boot-Warm schon erledigt. Default OFF ⇒ no-op.
     */
    @Scheduled(initialDelay = REWARM_INTERVAL_MS, fixedDelay = REWARM_INTERVAL_MS)
    fun warmWhisperPeriodic() {
        if (!whisperPreWarm) return
        runWhisperWarm("periodic")
    }

    /**
     * Brain-Re-Warm alle ~5 min: 1-Token-`streamChat("hi")` gegen :8041, hält den
     * Modell-Pagecache resident (KEIN zweites Modell). Default OFF ⇒ no-op.
     *
     * **Kollisions-Naht (TODO Orchestrator):** Heute best-effort lokal —
     * `take(1)` + [brainTimeoutMs] geben bei Kollision mit einem echten Turn schnell
     * auf, der [brainInProgress]-CAS + [lastBrainWarmAt]-Throttle verhindern doppelte
     * Warms. Ein DETERMINISTISCHES „skip bei laufendem Turn" braucht einen leicht
     * lesbaren In-Flight-Indikator: der TurnOrchestrator sollte einen kleinen
     * `BrainBusyGate` (z.B. `AtomicInteger` aktiver Brain-Turns, inc vor / dec nach
     * dem `brain.streamChat`) exponieren, den diese Sonde VOR dem Warm prüft. Bis
     * diese Naht existiert, bleibt der Brain-Warm bewusst Default OFF.
     */
    @Scheduled(initialDelay = REWARM_INTERVAL_MS, fixedDelay = REWARM_INTERVAL_MS)
    fun warmBrainPeriodic() {
        if (!llmPreWarm) return
        runBrainWarm()
    }

    private fun runWhisperWarm(phase: String) {
        // Überlapp-Schutz: läuft schon ein Warm, diesen Tick überspringen.
        if (!whisperInProgress.compareAndSet(false, true)) return
        val started = System.currentTimeMillis()
        try {
            sttPort.transcribe(silenceWav, language)
                .timeout(Duration.ofMillis(sttTimeoutMs))
                .doFinally { whisperInProgress.set(false) }
                .subscribe(
                    { _ -> log.debug("[warmprobe] whisper pre-warm ({}) OK in {} ms", phase, System.currentTimeMillis() - started) },
                    { e -> log.debug("[warmprobe] whisper pre-warm ({}) skip/fail (best-effort): {}", phase, e.message?.take(120)) },
                )
        } catch (e: Exception) {
            // streamChat/transcribe selbst sollten nicht synchron werfen — defensiv trotzdem.
            whisperInProgress.set(false)
            log.debug("[warmprobe] whisper pre-warm ({}) threw (best-effort): {}", phase, e.message?.take(120))
        }
    }

    private fun runBrainWarm() {
        // Re-Warm-Throttle: liegt der letzte Warm < THROTTLE zurück, diesen Tick skippen.
        val now = System.currentTimeMillis()
        if (now - lastBrainWarmAt.get() < BRAIN_REWARM_THROTTLE_MS) return
        // Überlapp-Schutz: läuft schon ein Warm, diesen Tick überspringen.
        if (!brainInProgress.compareAndSet(false, true)) return
        val started = System.currentTimeMillis()
        try {
            // 1-Token-Effekt: `take(1)` canceled den HTTP-Stream nach dem ersten Delta
            // (der BrainPort kennt keinen per-Call maxTokens — das ist Adapter-Ctor-Sache).
            brainPort.streamChat(
                prompt = "hi",
                systemPrompt = "",
                sessionId = "warmprobe",
                userId = "warmprobe",
            )
                .take(1)
                .timeout(Duration.ofMillis(brainTimeoutMs))
                .doFinally {
                    brainInProgress.set(false)
                    lastBrainWarmAt.set(System.currentTimeMillis())
                }
                .subscribe(
                    { _ -> log.debug("[warmprobe] brain pre-warm OK in {} ms", System.currentTimeMillis() - started) },
                    { e -> log.debug("[warmprobe] brain pre-warm skip/fail (best-effort): {}", e.message?.take(120)) },
                )
        } catch (e: Exception) {
            brainInProgress.set(false)
            log.debug("[warmprobe] brain pre-warm threw (best-effort): {}", e.message?.take(120))
        }
    }

    /**
     * Minimal-WAV (1.6 KB): 16 kHz mono PCM16, 100 ms Stille. Reicht Whisper zum
     * Durchlaufen + Pagecache-Refresh ohne nennenswerte Inference-Kosten. Identisches
     * Format wie der Sidecar-eigene Warmup. Portiert aus Hoshi 0.5 `WhisperSttClient`.
     */
    private fun buildSilenceWav(): ByteArray {
        val sampleRate = 16_000
        val durationMs = 100
        val numSamples = sampleRate * durationMs / 1000
        val dataBytes = numSamples * 2 // 16-bit mono
        val totalSize = 36 + dataBytes
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16) // PCM-Header-Size
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byteRate
        buf.putShort(2) // blockAlign
        buf.putShort(16) // bitsPerSample
        buf.put("data".toByteArray())
        buf.putInt(dataBytes)
        // PCM-Data ist bereits 0 (allocate-Initialisierung) ⇒ Stille.
        return buf.array()
    }

    companion object {
        /** Re-Warm-Intervall (ms) — gilt für Whisper- UND Brain-Tick (~5 min, wie Hoshi 0.5). */
        private const val REWARM_INTERVAL_MS = 300_000L

        /** Brain-Re-Warm-Throttle (ms): zwei Ticks näher als das fangen wir ab (Overlap-Schutz). */
        private const val BRAIN_REWARM_THROTTLE_MS = 30_000L
    }
}

/**
 * Aktiviert Springs `@Scheduled`-Verarbeitung für die [WarmProbe]. Bewusst eine
 * eigene, auto-discoverte `@Configuration` (Scan-Basis `de.hoshi.web`) statt eines
 * Eingriffs in `HoshiApplication`/`PipelineConfig` — so ist das Feature komplett
 * selbst-enthalten. Byte-Neutral: ohne `@Scheduled`-Methoden mit aktivem Flag tut
 * der Scheduler nichts Beobachtbares (die Warms kehren bei Flag=OFF sofort zurück).
 */
@Configuration
@EnableScheduling
class WarmProbeScheduling
