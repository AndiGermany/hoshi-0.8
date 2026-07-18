package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Beweist den Kern-Vertrag von [DelegatingTtsPort] (Andi-Video-Auftrag: „TTS-
 * Engine in den Einstellungen wählbar, zur Laufzeit, ohne Neustart"): ein
 * [switchTo] wechselt SOFORT den Adapter, den nachfolgende `synth`/`synthStream`-
 * Aufrufe treffen — genau der Beweis, den Andis Auftrag als Testfall verlangt
 * ("TtsPort-Aufruf landet nach PUT beim neuen Fake-Adapter").
 */
class DelegatingTtsPortTest {

    private class TaggedFakeTts(private val tag: String) : TtsPort {
        override fun synth(text: String, language: Language): Mono<ByteArray> = Mono.just(tag.toByteArray())
    }

    @Test
    fun `vor dem Switch landet der Aufruf beim initialen Delegaten`() {
        val port = DelegatingTtsPort(initialEngineId = "voxtral", initial = TaggedFakeTts("voxtral"))
        val bytes = port.synth("Hallo", Language.DE).block(Duration.ofSeconds(2))!!
        assertArrayEquals("voxtral".toByteArray(), bytes)
        assertEquals("voxtral", port.currentEngineId())
    }

    @Test
    fun `switchTo wechselt sofort - der naechste Aufruf landet beim neuen Fake-Adapter`() {
        val port = DelegatingTtsPort(initialEngineId = "voxtral", initial = TaggedFakeTts("voxtral"))

        port.switchTo("say", TaggedFakeTts("say"))

        assertEquals("say", port.currentEngineId())
        val bytes = port.synth("Hallo", Language.DE).block(Duration.ofSeconds(2))!!
        assertArrayEquals("say".toByteArray(), bytes)
    }

    @Test
    fun `mehrfaches Umschalten - immer der ZULETZT gesetzte Delegat gewinnt`() {
        val port = DelegatingTtsPort(initialEngineId = "voxtral", initial = TaggedFakeTts("voxtral"))

        port.switchTo("say", TaggedFakeTts("say"))
        port.switchTo("piper", TaggedFakeTts("piper"))
        port.switchTo("openai", TaggedFakeTts("openai"))

        assertEquals("openai", port.currentEngineId())
        val bytes = port.synth("Hallo", Language.DE).block(Duration.ofSeconds(2))!!
        assertArrayEquals("openai".toByteArray(), bytes)
    }

    @Test
    fun `synthStream und die voice-aware Overloads folgen ebenfalls dem aktuellen Delegaten`() {
        val port = DelegatingTtsPort(initialEngineId = "voxtral", initial = TaggedFakeTts("voxtral"))
        port.switchTo("say", TaggedFakeTts("say"))

        val viaVoice = port.synth("Hallo", Language.DE, voice = "coral").block(Duration.ofSeconds(2))!!
        assertArrayEquals("say".toByteArray(), viaVoice)

        val viaStream = port.synthStream("Hallo", Language.DE).collectList().block(Duration.ofSeconds(2))!!
        assertEquals(1, viaStream.size)
        assertArrayEquals("say".toByteArray(), viaStream.first())
    }
}
