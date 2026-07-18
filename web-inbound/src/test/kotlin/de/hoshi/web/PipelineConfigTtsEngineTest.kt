package de.hoshi.web

import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.adapters.tts.PiperTtsAdapter
import de.hoshi.adapters.tts.SayTtsAdapter
import de.hoshi.adapters.tts.VoxtralTtsAdapter
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Beweist die say-Naht in [PipelineConfig.ttsPort]/[PipelineConfig.ttsStage]
 * (Auftrag 19.07, dritte TTS-Engine neben Voxtral/OpenAI):
 *
 *  - `HOSHI_TTS=say` verdrahtet [SayTtsAdapter] (statt Voxtral/OpenAI).
 *  - `HOSHI_TTS=piper` verdrahtet [PiperTtsAdapter] (Codex-Sidecar-Übergabe 19.07,
 *    vierte Engine, statt Voxtral/OpenAI/say).
 *  - Der Default (leerer `HOSHI_TTS`) UND jeder unbekannte Wert bleiben
 *    UNVERÄNDERT [VoxtralTtsAdapter] — weder say- noch piper-Naht verschärfen die
 *    bestehende Fallback-Semantik.
 *  - Das Telemetrie-Tag ([ChatEvent.TtsAudioStart.provider]) nennt bei
 *    `HOSHI_TTS=say` ehrlich „say" und bei `HOSHI_TTS=piper` ehrlich „piper"
 *    (keine Voxtral-Lüge in der Wire-Telemetrie).
 *
 * Reine Konstruktor-Verdrahtung (kein Spring-Context, kein Netz) — analog
 * [PipelineConfigTtsFastFirstTest].
 */
class PipelineConfigTtsEngineTest {

    private val config = PipelineConfig()

    /** Alle Parameter der `ttsPort`-Bean OHNE Spring — Boot-Defaults 1:1 aus den `@Value`-Annotationen. */
    private fun buildTtsPort(ttsImpl: String): TtsPort = config.ttsPort(
        ttsImpl = ttsImpl,
        baseUrl = "http://localhost:8042",
        voice = "de_female",
        openaiModel = "gpt-4o-mini-tts",
        openaiVoice = "coral",
        sayBaseUrl = "http://127.0.0.1:8044",
        sayVoice = "",
        sayRate = 0,
        piperBaseUrl = "http://127.0.0.1:8045",
        piperVoice = "de_DE-thorsten-medium",
        sanitizeEnabled = false,
        loudnessEnabled = false,
        loudnessTargetRmsDb = -18.0,
        loudnessPeakCeilingDb = -1.0,
        loudnessMaxGainDb = 12.0,
        loudnessSilenceFloorDb = -50.0,
        ttsStreamEnabled = false,
    )

    @Test
    fun `HOSHI_TTS=say verdrahtet den SayTtsAdapter`() {
        val port = buildTtsPort("say")
        assertTrue(port is SayTtsAdapter, "HOSHI_TTS=say muss SayTtsAdapter verdrahten, war: ${port::class.simpleName}")
    }

    @Test
    fun `HOSHI_TTS=say ist case-insensitiv (wie openai)`() {
        val port = buildTtsPort("SAY")
        assertTrue(port is SayTtsAdapter, "HOSHI_TTS=SAY (Großschreibung) muss ebenfalls SayTtsAdapter verdrahten")
    }

    @Test
    fun `leerer HOSHI_TTS (Default) bleibt VoxtralTtsAdapter - unveraendert durch die say-Naht`() {
        val port = buildTtsPort("")
        assertTrue(port is VoxtralTtsAdapter, "Default-Naht darf durch say NICHT verschoben werden, war: ${port::class.simpleName}")
    }

    @Test
    fun `unbekannter HOSHI_TTS-Wert faellt weiterhin auf VoxtralTtsAdapter zurueck - Semantik nicht verschaerft`() {
        val port = buildTtsPort("tippfehler-engine")
        assertTrue(port is VoxtralTtsAdapter, "unbekannter Wert muss wie vor der say-Naht auf Voxtral fallen, war: ${port::class.simpleName}")
    }

    @Test
    fun `HOSHI_TTS=openai bleibt unveraendert OpenAiTtsAdapter (say beruehrt den openai-Zweig nicht)`() {
        val port = buildTtsPort("openai")
        assertTrue(port is OpenAiTtsAdapter, "HOSHI_TTS=openai darf durch die say-Naht nicht verschoben werden, war: ${port::class.simpleName}")
    }

    // ── piper-Naht (Codex-Sidecar-Übergabe 19.07, vierte Engine) ────────────────

    @Test
    fun `HOSHI_TTS=piper verdrahtet den PiperTtsAdapter`() {
        val port = buildTtsPort("piper")
        assertTrue(port is PiperTtsAdapter, "HOSHI_TTS=piper muss PiperTtsAdapter verdrahten, war: ${port::class.simpleName}")
    }

    @Test
    fun `HOSHI_TTS=piper ist case-insensitiv (wie say-openai)`() {
        val port = buildTtsPort("PIPER")
        assertTrue(port is PiperTtsAdapter, "HOSHI_TTS=PIPER (Großschreibung) muss ebenfalls PiperTtsAdapter verdrahten")
    }

    @Test
    fun `leerer HOSHI_TTS (Default) bleibt VoxtralTtsAdapter - unveraendert durch die piper-Naht`() {
        val port = buildTtsPort("")
        assertTrue(port is VoxtralTtsAdapter, "Default-Naht darf durch piper NICHT verschoben werden, war: ${port::class.simpleName}")
    }

    @Test
    fun `HOSHI_TTS=say bleibt unveraendert SayTtsAdapter (piper beruehrt den say-Zweig nicht)`() {
        val port = buildTtsPort("say")
        assertTrue(port is SayTtsAdapter, "HOSHI_TTS=say darf durch die piper-Naht nicht verschoben werden, war: ${port::class.simpleName}")
    }

    @Test
    fun `HOSHI_TTS=openai bleibt unveraendert OpenAiTtsAdapter (piper beruehrt den openai-Zweig nicht)`() {
        val port = buildTtsPort("openai")
        assertTrue(port is OpenAiTtsAdapter, "HOSHI_TTS=openai darf durch die piper-Naht nicht verschoben werden, war: ${port::class.simpleName}")
    }

    // ── Telemetrie-Wahrheit: TtsAudioStart.provider nennt bei say ehrlich "say" ──

    /** Fake-TTS: liefert nicht-leere „WAV"-Bytes (TtsStage schätzt daraus estimatedMs → emittiert TtsAudioStart). */
    private class FakeTtsPort : TtsPort {
        override fun synth(text: String, language: Language): Mono<ByteArray> = Mono.just(ByteArray(4096) { 1 })
    }

    private fun input(): List<ChatEvent> = listOf(
        ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
        ChatEvent.TextDelta("Hallo, ich bin Hoshi.", provider = "LOCAL"),
        ChatEvent.Done(provider = "LOCAL"),
    )

    @Test
    fun `Telemetrie-Tag nennt bei HOSHI_TTS=say ehrlich 'say', nicht 'voxtral'`() {
        val stage = config.ttsStage(
            ttsPort = FakeTtsPort(),
            ttsImpl = "say",
            fastFirstEnabled = false,
            fastFirstN = 2,
            groupedMinChars = 24,
            idleFlushMs = 0,
        )
        val events = stage.transform(Flux.fromIterable(input()), Language.DE)
            .collectList().block(Duration.ofSeconds(5))!!

        val start = events.filterIsInstance<ChatEvent.TtsAudioStart>().firstOrNull()
        assertTrue(start != null, "TtsAudioStart muss bei nicht-leerem Audio emittiert werden: $events")
        assertTrue(start!!.provider == "say", "provider-Tag muss 'say' sein, war: ${start.provider}")
    }

    @Test
    fun `Telemetrie-Tag nennt bei HOSHI_TTS=piper ehrlich 'piper', nicht 'voxtral'`() {
        val stage = config.ttsStage(
            ttsPort = FakeTtsPort(),
            ttsImpl = "piper",
            fastFirstEnabled = false,
            fastFirstN = 2,
            groupedMinChars = 24,
            idleFlushMs = 0,
        )
        val events = stage.transform(Flux.fromIterable(input()), Language.DE)
            .collectList().block(Duration.ofSeconds(5))!!

        val start = events.filterIsInstance<ChatEvent.TtsAudioStart>().firstOrNull()
        assertTrue(start != null, "TtsAudioStart muss bei nicht-leerem Audio emittiert werden: $events")
        assertTrue(start!!.provider == "piper", "provider-Tag muss 'piper' sein, war: ${start.provider}")
    }
}
