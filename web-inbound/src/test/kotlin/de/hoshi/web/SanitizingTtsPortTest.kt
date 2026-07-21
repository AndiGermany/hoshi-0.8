package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * Andi-Befund 21.07. abends: „er liest weiter die ganze quelle".
 *
 * Der [NeverSpeakTtsSanitizer] hing NUR im OpenAI-Adapter — `say` und `piper` sprachen
 * den Rohtext. Damit galt die „sprich niemals ein Geheimnis"-Regel ausgerechnet für die
 * CLOUD-Engine und nicht für die beiden LOKALEN. Diese Tests nageln beides fest: die
 * Hülle wirkt, und die Fabrik hängt sie an JEDE Engine.
 */
class SanitizingTtsPortTest {

    /** Merkt sich, welcher Text tatsächlich zur Synthese ging. */
    private class SpyTts : TtsPort {
        var seen: String? = null
        override fun synth(text: String, language: Language): Mono<ByteArray> {
            seen = text
            return Mono.just(ByteArray(0))
        }
    }

    private fun factory(sanitize: Boolean) = TtsEngineFactory(
        voxtralBaseUrl = "http://localhost:8042",
        voxtralVoice = "de_female",
        openaiModel = "gpt-4o-mini-tts",
        openaiVoice = "coral",
        sayBaseUrl = "http://127.0.0.1:1",
        sayVoice = "",
        sayRate = 0,
        piperBaseUrl = "http://127.0.0.1:8045",
        piperVoice = "de_DE-thorsten-medium",
        sanitizeEnabled = sanitize,
        ttsStreamEnabled = false,
    )

    @Test
    fun `die Huelle reicht nur den bereinigten Text an die Engine weiter`() {
        val spy = SpyTts()
        val port = SanitizingTtsPort(spy, NeverSpeakTtsSanitizer())

        port.synth(
            "GTA 6 erscheint am 19. November 2026. " +
                "([rockstargames.com](https://www.rockstargames.com/newswire/article/x?utm_source=openai))",
            Language.DE,
        ).block()

        assertEquals("GTA 6 erscheint am 19. November 2026.", spy.seen)
    }

    @Test
    fun `piper und say bekommen die Huelle - nicht nur openai`() {
        val f = factory(sanitize = true)
        for (engine in listOf(TtsEngineIds.PIPER, TtsEngineIds.SAY, TtsEngineIds.OPENAI, TtsEngineIds.VOXTRAL)) {
            assertTrue(
                f.build(engine, null) is SanitizingTtsPort,
                "Engine '$engine' ohne Sanitize-Hülle — genau die Lücke von 21.07.",
            )
        }
    }

    @Test
    fun `abgeschaltete Sanitize-Regel huellt nicht - byte-neutral zum Altverhalten`() {
        val f = factory(sanitize = false)
        assertFalse(f.build(TtsEngineIds.PIPER, null) is SanitizingTtsPort)
    }
}
