package de.hoshi.web

import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.adapters.tts.PiperTtsAdapter
import de.hoshi.adapters.tts.SayTtsAdapter
import de.hoshi.adapters.tts.VoxtralTtsAdapter
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Beweist die Engine-Wahl in [PipelineConfig.ttsPort]/[PipelineConfig.ttsStage]:
 *
 *  - `HOSHI_TTS=say` verdrahtet [SayTtsAdapter], `=piper` [PiperTtsAdapter],
 *    `=openai` [OpenAiTtsAdapter], `=voxtral` [VoxtralTtsAdapter] — jeweils
 *    case-insensitiv.
 *  - **First-Run-Wahrheit 0.8.1:** leerer `HOSHI_TTS` ergibt [SayTtsAdapter].
 *    `say` ist auf dem vorausgesetzten macOS lokal, key-/modellfrei und wird von
 *    `bin/hoshi up` gestartet. Piper bleibt wegen Bootstrap/Modell/GPL explizit.
 *    Ein unbekannter nicht-leerer Wert bricht ab, statt still eine Ersatz-Engine
 *    zu wählen. Voxtral bleibt vollwertig, nur eben EXPLIZIT anwählbar.
 *  - Das Telemetrie-Tag ([ChatEvent.TtsAudioStart.provider]) nennt bei
 *    `HOSHI_TTS=say` ehrlich „say" und bei `HOSHI_TTS=piper` ehrlich „piper"
 *    (keine Voxtral-Lüge in der Wire-Telemetrie).
 *
 * Reine Konstruktor-Verdrahtung (kein Spring-Context, kein Netz) — analog
 * [PipelineConfigTtsFastFirstTest]. Seit 0.8.1 baut [PipelineConfig.ttsPort] die
 * Engine nicht mehr selbst, sondern ruft die [TtsEngineFactory] (EINE Bauwahrheit,
 * s. [TtsBuildPathSingleTruthTest]) — der Test reicht sie deshalb hier direkt hinein,
 * mit den Boot-Defaults aus den `@Value`-Annotationen von
 * [TtsRuntimeConfig.ttsEngineFactory].
 */
class PipelineConfigTtsEngineTest {

    private val config = PipelineConfig()

    /** Die Fabrik mit den Boot-Defaults 1:1 aus [TtsRuntimeConfig.ttsEngineFactory] (alle Hüllen aus). */
    private fun bootFactory(): TtsEngineFactory = TtsEngineFactory(
        voxtralBaseUrl = "http://localhost:8042",
        voxtralVoice = "de_female",
        openaiModel = "gpt-4o-mini-tts",
        openaiVoice = "coral",
        sayBaseUrl = "http://127.0.0.1:8044",
        sayVoice = "",
        sayRate = 0,
        piperBaseUrl = "http://127.0.0.1:8045",
        piperVoice = "de_DE-thorsten-medium",
        sanitizeEnabled = false,
        ttsStreamEnabled = false,
    )

    /** Die `ttsPort`-Bean OHNE Spring — genau die Naht, die beim Boot greift. */
    private fun buildTtsPort(ttsImpl: String): TtsPort =
        config.ttsPort(ttsEngineFactory = bootFactory(), ttsImpl = ttsImpl)

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
    fun `leerer HOSHI_TTS (Default) ist SayTtsAdapter - lokal-first und startbar`() {
        val port = buildTtsPort("")
        assertTrue(port is SayTtsAdapter, "leerer HOSHI_TTS muss lokal-first auf say fallen, war: ${port::class.simpleName}")
        assertTrue(buildTtsPort("   ") is SayTtsAdapter, "reiner Rand-Whitespace ist ebenfalls der leere Default")
    }

    @Test
    fun `unbekannter HOSHI_TTS-Wert bricht hart ab statt eine Ersatz-Engine zu waehlen`() {
        val error = assertThrows<IllegalArgumentException> { buildTtsPort("tippfehler-engine") }
        assertTrue(error.message.orEmpty().contains("Unbekannte TTS-Engine"))
        assertTrue(error.message.orEmpty().contains("Abbruch"))
    }

    @Test
    fun `HOSHI_TTS=voxtral bleibt vollwertig anwaehlbar - nur eben EXPLIZIT`() {
        // Die Default-Umstellung nimmt voxtral NICHT weg; sie nimmt ihm nur den stillen
        // Auffang-Zweig. Ohne diesen Test wuerde ein spaeterer Umbau das nicht merken.
        val port = buildTtsPort("voxtral")
        assertTrue(port is VoxtralTtsAdapter, "HOSHI_TTS=voxtral muss VoxtralTtsAdapter verdrahten, war: ${port::class.simpleName}")
        assertTrue(buildTtsPort("VOXTRAL") is VoxtralTtsAdapter, "case-insensitiv wie die anderen drei")
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
    fun `der Default ist NIE die Cloud-Engine - lokal-first bleibt lokal-first`() {
        // Der Default darf sich bewegen, aber NIE in die Cloud:
        // ohne gesetzte Env darf kein Byte Text die Box verlassen.
        for (raw in listOf("", "   ")) {
            val port = buildTtsPort(raw)
            assertFalse(port is OpenAiTtsAdapter, "HOSHI_TTS='$raw' darf NIEMALS auf die Cloud-Engine fallen")
        }
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
