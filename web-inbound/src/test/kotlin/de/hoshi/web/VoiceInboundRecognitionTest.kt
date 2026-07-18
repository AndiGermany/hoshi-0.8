package de.hoshi.web

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
 * **VoiceInboundRecognitionTest** — beweist die S3-Naht am GEBOOTETEN `/api/v1/voice`:
 *  - Recognition ON + sicherer Treffer ⇒ der Sprecher fließt als `speakerContext` in den Turn
 *    (beobachtet am ECHTEN [BrainPort]: `userId == speakerId`) UND ein additives
 *    `event:"speaker"` mit `recognizedSpeaker` wird dem Transkript vorangestellt (FE: „wer sprach").
 *  - Recognition ON + Gast ⇒ `speakerId=="gast"` (isGuest-Härtung ⇒ kein Memory) + Gast-Speaker-Event.
 *  - Recognition OFF (`enabled=false`) ⇒ identify wird NIE gerufen, KEIN Speaker-Event, `userId=="unknown"`
 *    ⇒ byte-neutral zum heutigen Voice-Pfad.
 *
 * Ein mutierbarer @Primary-Fake-[SpeakerIdentifyService] steuert enabled/Ergebnis pro Test — keine
 * Enroll-Beans, kein Sidecar. Fake STT/Brain/TTS an den ECHTEN Pipeline-Nähten (kein Netz).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
    ],
)
@AutoConfigureWebTestClient
@Import(VoiceInboundRecognitionTest.RecognitionSeamsConfig::class)
class VoiceInboundRecognitionTest(
    @Autowired val client: WebTestClient,
    @Autowired val fakeStt: RecStt,
    @Autowired val fakeBrain: RecBrain,
    @Autowired val fakeIdentify: FakeIdentify,
    @Autowired val fakeCapture: FakeCapture,
) {

    private val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 9, 8, 7, 6) // "RIFF"…

    private fun post(): String {
        val body = client.post().uri("/api/v1/voice?language=DE&speak=false")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(wav)
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody
            .collectList()
            .block(Duration.ofSeconds(10)) ?: emptyList()
        return body.joinToString("\n")
    }

    @Test
    fun `Recognition ON und Treffer - speakerContext erreicht den Brain und Speaker-Event wird gestreamt`() {
        fakeStt.transcript = "Hallo Hoshi"
        fakeIdentify.enabledFlag = true
        fakeIdentify.next = Recognition(name = "andi", confidence = 0.97, isGuest = false)

        val stream = post()

        assertEquals("andi", fakeBrain.lastUserId, "speakerContext.speakerId erreicht den Brain als userId")
        assertTrue(stream.contains("\"event\":\"speaker\""), "ein Speaker-Event wird gestreamt")
        assertTrue(stream.contains("\"recognizedSpeaker\":\"andi\""), "der erkannte Name steht im Event")
        assertTrue(stream.contains("\"isGuest\":false"), "Treffer ⇒ isGuest=false")
    }

    @Test
    fun `Recognition ON und Gast - speakerId gast (kein Memory) und Gast-Speaker-Event`() {
        fakeStt.transcript = "Hallo Hoshi"
        fakeIdentify.enabledFlag = true
        fakeIdentify.next = Recognition.GUEST

        val stream = post()

        assertEquals("gast", fakeBrain.lastUserId, "Gast ⇒ speakerId 'gast' ⇒ fertige isGuest-Härtung")
        assertTrue(stream.contains("\"event\":\"speaker\""), "auch der Gast wird surfaced")
        assertTrue(stream.contains("\"isGuest\":true"), "Gast ⇒ isGuest=true, kein geratener Name")
    }

    @Test
    fun `Recognition OFF - identify nie gerufen, kein Speaker-Event, userId unknown (byte-neutral)`() {
        fakeStt.transcript = "Hallo Hoshi"
        fakeIdentify.enabledFlag = false
        val callsBefore = fakeIdentify.calls.get()

        val stream = post()

        assertEquals(callsBefore, fakeIdentify.calls.get(), "OFF ⇒ identify wird NIE gerufen")
        assertFalse(stream.contains("\"event\":\"speaker\""), "OFF ⇒ kein Speaker-Event (byte-neutral)")
        assertEquals("unknown", fakeBrain.lastUserId, "OFF ⇒ kein speakerContext ⇒ heutiger userId 'unknown'")
    }

    // ── Capture-Tee am Speaker-Identify-Rand ────────────────────────────────────

    @Test
    fun `Recognition ON ruft den Capture-Tee genau einmal mit Kanal browser`() {
        // Der Capture-Fake ist eine geteilte Spring-Singleton-Bean ueber ALLE Tests dieser
        // Klasse (gecachter Kontext, Muster fakeIdentify.calls oben) — vorher/nachher
        // vergleichen statt Nullpunkt anzunehmen.
        val before = fakeCapture.calls.size
        fakeStt.transcript = "Hallo Hoshi"
        fakeIdentify.enabledFlag = true
        fakeIdentify.next = Recognition(name = "andi", confidence = 0.97, isGuest = false)

        post()

        assertEquals(before + 1, fakeCapture.calls.size, "genau ein Capture-Aufruf pro identify-Aufruf")
        val last = fakeCapture.calls.last()
        assertEquals("browser", last.channel, "Kanal-Kennung am Voice-Rand ist 'browser'")
        assertTrue(wav.contentEquals(last.bytes), "capture() bekommt exakt die identify-Bytes")
    }

    @Test
    fun `Recognition OFF ruft den Capture-Tee nie (kein Extra-IO ohne aktive Erkennung)`() {
        val before = fakeCapture.calls.size
        fakeStt.transcript = "Hallo Hoshi"
        fakeIdentify.enabledFlag = false

        post()

        assertEquals(before, fakeCapture.calls.size, "OFF ⇒ kein identify ⇒ kein Capture (byte-neutral)")
    }

    // ── Fakes ────────────────────────────────────────────────────────────────

    class RecStt : SttPort {
        @Volatile var transcript: String = ""
        override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> = Mono.just(transcript)
    }

    /** Fake-Brain: merkt sich den zuletzt gesehenen [userId] (== speakerId der Identity-Isolation). */
    class RecBrain : BrainPort {
        @Volatile var lastUserId: String? = null
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
            lastUserId = userId
            return Flux.just(LlmDelta("Hi!", done = true))
        }
    }

    class RecTts : TtsPort {
        override fun synth(text: String, language: Language): Mono<ByteArray> = Mono.empty()
        override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> = Flux.empty()
    }

    /** Mutierbarer Erkenner: enabled + nächstes Ergebnis pro Test setzbar; zählt identify-Aufrufe. */
    class FakeIdentify : SpeakerIdentifyService {
        @Volatile var enabledFlag: Boolean = false
        @Volatile var next: Recognition = Recognition.GUEST
        val calls = AtomicInteger(0)
        override val enabled: Boolean get() = enabledFlag
        override fun identify(audioBytes: ByteArray, mime: String): Recognition {
            calls.incrementAndGet()
            return next
        }
    }

    /** Records-Fake: haelt alle capture()-Aufrufe fest (Kanal/Bytes/Mime), Muster [FakeIdentify]. */
    class FakeCapture : SpeakerCaptureTee {
        data class Call(val channel: String, val bytes: ByteArray, val mime: String)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())
        override fun capture(channel: String, audioBytes: ByteArray, mime: String) {
            calls.add(Call(channel, audioBytes.copyOf(), mime))
        }
    }

    @TestConfiguration
    class RecognitionSeamsConfig {
        @Bean @Primary fun recStt() = RecStt()

        @Bean @Primary fun recBrain() = RecBrain()

        @Bean @Primary fun recTts() = RecTts()

        @Bean @Primary fun fakeIdentify() = FakeIdentify()

        @Bean @Primary fun fakeCapture() = FakeCapture()
    }
}
