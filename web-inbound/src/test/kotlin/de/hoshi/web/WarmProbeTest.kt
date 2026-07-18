package de.hoshi.web

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.SttPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

/**
 * **WarmProbeTest** — beweist das Default-OFF-/byte-neutral-Verhalten der [WarmProbe]:
 * bei OFF feuern weder STT noch Brain ein Adapter-Call, bei ON genau einer (mit dem
 * erwarteten Stille-WAV bzw. dem 1-Token-`"hi"`-Prompt). Reine POJO-Konstruktion mit
 * Fakes — KEIN Spring-Context, KEINE Live-Sidecars (analog VoiceInboundControllerTest).
 *
 * Die Fakes zählen SYNCHRON beim Methoden-Aufruf (im Body, vor dem `subscribe`), darum
 * sind die Assertions direkt nach dem Warm-Trigger deterministisch.
 */
class WarmProbeTest {

    private fun probe(
        stt: SttPort,
        brain: BrainPort,
        whisperOn: Boolean = false,
        llmOn: Boolean = false,
    ) = WarmProbe(
        sttPort = stt,
        brainPort = brain,
        whisperPreWarm = whisperOn,
        llmPreWarm = llmOn,
        warmLanguageCode = "de",
        sttTimeoutMs = 8000,
        brainTimeoutMs = 4000,
    )

    @Test
    fun `whisper pre-warm OFF — kein STT-Call auf startup oder periodic`() {
        val stt = RecordingStt()
        val brain = RecordingBrain()
        val p = probe(stt, brain, whisperOn = false)

        p.warmWhisperOnStartup()
        p.warmWhisperPeriodic()

        assertEquals(0, stt.calls.get(), "Flag OFF ⇒ STT wird nie aufgerufen")
    }

    @Test
    fun `whisper pre-warm ON — STT bekommt ein gueltiges 16kHz-Stille-WAV mit Sprach-Hint`() {
        val stt = RecordingStt()
        val brain = RecordingBrain()
        val p = probe(stt, brain, whisperOn = true)

        p.warmWhisperOnStartup()

        assertEquals(1, stt.calls.get(), "Flag ON ⇒ STT wird beim Boot genau einmal gewärmt")
        val wav = stt.lastBytes
        assertNotNull(wav, "STT bekommt ein WAV gereicht")
        wav!!
        // RIFF/WAVE-Header + 44 + 3200 Bytes (100 ms @ 16 kHz mono PCM16).
        assertEquals(3244, wav.size, "Minimal-Stille-WAV = 44-Byte-Header + 3200 Byte PCM")
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(Language.DE, stt.lastLanguage, "Sprach-Hint folgt der Konfiguration")
    }

    @Test
    fun `whisper pre-warm ON — periodic waermt ebenfalls`() {
        val stt = RecordingStt()
        val p = probe(stt, RecordingBrain(), whisperOn = true)

        p.warmWhisperPeriodic()

        assertEquals(1, stt.calls.get(), "Flag ON ⇒ auch der periodische Tick wärmt")
    }

    @Test
    fun `brain pre-warm OFF — kein Brain-Call`() {
        val brain = RecordingBrain()
        val p = probe(RecordingStt(), brain, llmOn = false)

        p.warmBrainPeriodic()

        assertEquals(0, brain.calls.get(), "Flag OFF ⇒ Brain wird nie aufgerufen")
    }

    @Test
    fun `brain pre-warm ON — Brain bekommt einen 1-Token hi-Prompt`() {
        val brain = RecordingBrain()
        val p = probe(RecordingStt(), brain, llmOn = true)

        p.warmBrainPeriodic()

        assertEquals(1, brain.calls.get(), "Flag ON ⇒ Brain wird genau einmal gewärmt")
        assertEquals("hi", brain.lastPrompt, "Warm-Prompt ist minimal")
        assertTrue(brain.lastSessionId == "warmprobe", "eigene Warm-Session, kollidiert nicht mit echten Turns")
    }

    @Test
    fun `flags unabhaengig — Whisper ON, Brain OFF`() {
        val stt = RecordingStt()
        val brain = RecordingBrain()
        val p = probe(stt, brain, whisperOn = true, llmOn = false)

        p.warmWhisperOnStartup()
        p.warmBrainPeriodic()

        assertEquals(1, stt.calls.get())
        assertEquals(0, brain.calls.get())
    }

    /** Fake-STT: zählt Calls + merkt sich Bytes/Sprache, liefert leeres Transkript (no_input). */
    private class RecordingStt : SttPort {
        val calls = AtomicInteger(0)
        @Volatile var lastBytes: ByteArray? = null
        @Volatile var lastLanguage: Language? = null
        override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> {
            calls.incrementAndGet()
            lastBytes = audioWav
            lastLanguage = language
            return Mono.just("")
        }
    }

    /** Fake-Brain: zählt Calls + merkt sich Prompt/Session, liefert ein 1-Delta-Stream. */
    private class RecordingBrain : BrainPort {
        val calls = AtomicInteger(0)
        @Volatile var lastPrompt: String? = null
        @Volatile var lastSessionId: String? = null
        override fun streamChat(
            prompt: String,
            systemPrompt: String,
            history: List<ChatMessage>,
            temperature: Double?,
            sessionId: String,
            userId: String,
            tools: List<Map<String, Any?>>,
            toolGrammar: Boolean,
            onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            calls.incrementAndGet()
            lastPrompt = prompt
            lastSessionId = sessionId
            return Flux.just(LlmDelta("ok", done = true))
        }
    }
}
