package de.hoshi.web

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.Persona
import de.hoshi.core.pipeline.PersonaService
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **VoiceInboundControllerTest** — beweist, dass `/api/v1/voice` das vom Browser-
 * `MediaRecorder` gelieferte `audio/webm` (Opus) AKZEPTIERT (kein 415) und die rohen
 * Bytes unverändert an den [SttPort] reicht (Whisper dekodiert per `encode=true` selbst),
 * UND dass die Query-Params `voice`/`persona` (FE: frontend/src/api/voice.ts) in den
 * [de.hoshi.core.dto.ChatRequest] fließen — beobachtet an den ECHTEN Port-Nähten:
 * `persona=Kumpel` ⇒ CHEERFUL-Temperatur am [BrainPort], `voice=coral` ⇒ voice-aware
 * [TtsPort.synthStream]-Overload. Ohne Params bleiben die Defaults (byte-neutral).
 *
 * Gebooteter Context (MOCK, echte [PerimeterWebFilter]-Wand) + hand-rollte Fakes
 * (`@Primary`, kein mockk): fake STT (Transkript pro Test setzbar, Default `""` ⇒
 * `no_input`, KEIN Brain-Call), fake Brain (merkt sich die Persona-Temperatur), fake
 * TTS (merkt sich den Voice-Wunsch) — der Test hängt an keiner Live-Infra.
 * `HOSHI_PERSONA_ENABLED=true` ist hier gesetzt, damit `persona=Kumpel` den
 * [de.hoshi.core.pipeline.PersonaResolver] ÜBERLEBT (bei OFF kollabiert alles auf
 * STANDARD — das OFF-Verhalten ist in core-domain unit-getestet); für Requests OHNE
 * `persona`-Param ist das Flag byte-neutral (fromCode(null) = STANDARD).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        "HOSHI_PERSONA_ENABLED=true",
    ],
)
@AutoConfigureWebTestClient
@Import(VoiceInboundControllerTest.FakeSttConfig::class, VoiceInboundControllerTest.RecordingSeamsConfig::class)
class VoiceInboundControllerTest(
    @Autowired val client: WebTestClient,
    @Autowired val fakeStt: RecordingSttPort,
    @Autowired val fakeBrain: RecordingBrainPort,
    @Autowired val fakeTts: RecordingTtsPort,
) {

    /** EBML-Magic (1A 45 DF A3) + Payload — der MediaRecorder-Header eines webm/opus-Blobs. */
    private val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 9, 8, 7, 6)

    @Test
    fun `audio webm wird akzeptiert (nicht 415) und die Bytes erreichen den STT`() {
        client.post().uri("/api/v1/voice?language=DE&speak=false")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .contentType(MediaType.parseMediaType("audio/webm"))
            .bodyValue(webm)
            .exchange()
            // Vor dem Fix: 415 Unsupported Media Type. Jetzt akzeptiert ⇒ 200 SSE.
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody
            // Body drainen ⇒ der STT wird wirklich subscribed/aufgerufen.
            .blockLast(Duration.ofSeconds(5))

        assertNotNull(fakeStt.lastBytes, "STT wurde mit dem Request-Body aufgerufen")
        assertArrayEquals(webm, fakeStt.lastBytes, "die rohen webm-Bytes erreichen den STT unverändert")
    }

    @Test
    fun `voice und persona Query-Params fliessen in den ChatRequest (Brain-Temperatur + TTS-Stimme)`() {
        // Reales Transkript ⇒ der Turn läuft bis zum (fake) Brain und zur (fake) TTS.
        fakeStt.transcript = "Erzähl mir einen Witz."
        try {
            val brainCallsBefore = fakeBrain.calls.get()
            client.post().uri("/api/v1/voice?language=DE&speak=true&voice=coral&persona=Kumpel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
                .contentType(MediaType.parseMediaType("audio/webm"))
                .bodyValue(webm)
                .exchange()
                .expectStatus().isOk
                .returnResult(String::class.java)
                .responseBody
                .blockLast(Duration.ofSeconds(10))

            assertEquals(brainCallsBefore + 1, fakeBrain.calls.get(), "genau EIN Brain-Call für den Turn")
            // persona=Kumpel → Persona.fromCode → PersonaResolver (Flag ON) → ChatRequest.persona
            // → TurnPrompt → Brain-Temperatur == KUMPELs feste CHEERFUL-Stimmung (deterministisch,
            // im Gegensatz zur tageszeitabhängigen STANDARD-Stimmung).
            assertEquals(
                kumpelTemperature(),
                fakeBrain.lastTemperature,
                "persona=Kumpel erreicht den Brain (CHEERFUL-Temperatur statt STANDARD-Grundton)",
            )
            // voice=coral → ChatRequest.voice → TtsStage → voice-aware TtsPort.synthStream-Overload.
            assertEquals("coral", fakeTts.lastVoice, "voice=coral erreicht die TTS-Naht (per-Turn-Stimm-Wunsch)")
        } finally {
            fakeStt.transcript = ""
        }
    }

    @Test
    fun `ohne voice und persona Query-Params bleiben die Defaults (byte-neutral)`() {
        fakeStt.transcript = "Erzähl mir einen Witz."
        // Sentinel: beweist, dass die TTS in DIESEM Turn wirklich mit voice=null gerufen wurde
        // (nicht bloß ein Leftover-null aus einem früheren Test).
        fakeTts.lastVoice = "sentinel-vor-dem-turn"
        try {
            client.post().uri("/api/v1/voice?language=DE&speak=true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
                .contentType(MediaType.parseMediaType("audio/webm"))
                .bodyValue(webm)
                .exchange()
                .expectStatus().isOk
                .returnResult(String::class.java)
                .responseBody
                .blockLast(Duration.ofSeconds(10))

            assertNull(fakeTts.lastVoice, "ohne voice-Param bleibt ChatRequest.voice null (Boot-Default-Stimme)")
            assertNotEquals(
                kumpelTemperature(),
                fakeBrain.lastTemperature,
                "ohne persona-Param KEINE Kumpel-Temperatur (STANDARD-Grundton, wie heute)",
            )
        } finally {
            fakeStt.transcript = ""
        }
    }

    /** KUMPELs deterministische Brain-Temperatur (feste defaultMood CHEERFUL), aus der echten Quelle. */
    private fun kumpelTemperature(): Double =
        PersonaService().let { it.temperatureFor(it.moodFor(Persona.KUMPEL)) }

    /** Fake-STT: merkt sich die Bytes; [transcript] pro Test setzbar (Default `""` ⇒ no_input, kein Brain-Call). */
    class RecordingSttPort : SttPort {
        @Volatile
        var lastBytes: ByteArray? = null

        @Volatile
        var transcript: String = ""
        override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> {
            lastBytes = audioWav
            return Mono.just(transcript)
        }
    }

    /** Fake-Brain: zählt Calls + merkt sich die (persona-abgeleitete) Temperatur, liefert 1 Delta. */
    class RecordingBrainPort : BrainPort {
        val calls = AtomicInteger(0)

        @Volatile
        var lastTemperature: Double? = null
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
            lastTemperature = temperature
            return Flux.just(LlmDelta("Na klar!", done = true))
        }
    }

    /** Fake-TTS: merkt sich den per-Turn-Voice-Wunsch (voice-aware Overload), liefert kein Audio (Best-Effort). */
    class RecordingTtsPort : TtsPort {
        @Volatile
        var lastVoice: String? = null
        override fun synth(text: String, language: Language): Mono<ByteArray> = Mono.empty()
        override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> {
            lastVoice = voice
            return Flux.empty()
        }
    }

    @TestConfiguration
    class FakeSttConfig {
        @Bean
        @Primary
        fun recordingSttPort(): RecordingSttPort = RecordingSttPort()
    }

    /** `@Primary`-Fakes an den ECHTEN Pipeline-Nähten (Brain + TTS) — keine Live-Sidecars. */
    @TestConfiguration
    class RecordingSeamsConfig {
        @Bean
        @Primary
        fun recordingBrainPort(): RecordingBrainPort = RecordingBrainPort()

        @Bean
        @Primary
        fun recordingTtsPort(): RecordingTtsPort = RecordingTtsPort()
    }
}
