package de.hoshi.web

import de.hoshi.adapters.tts.OpenAiTtsAdapter
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
 * Hülle wirkt, und die Fabrik setzt die Regel für JEDE Engine durch.
 *
 * **„Für jede Engine" heißt nicht „überall derselbe Decorator" (0.8.1).** Die drei
 * LOKALEN Engines bekommen die [SanitizingTtsPort]-Hülle; `openai` maskiert INTERN,
 * unmittelbar vor dem Cloud-Call (stärkste Position) — dieselbe bewusste Asymmetrie,
 * die der Boot-Pfad schon immer hatte und die mit der Vereinheitlichung der beiden
 * Bau-Wege hierher gewandert ist. Geprüft wird deshalb bei openai nicht mehr der
 * Decorator-Typ, sondern DASS wirklich maskiert wird — plus der Sonderfall, in dem die
 * äußere Hülle auch für openai zwingend ist (Verbalizer in der Kette).
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

    private fun factory(sanitize: Boolean, verbalize: Boolean = false) = TtsEngineFactory(
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
        verbalizeEnabled = verbalize,
    )

    /** Der WIRKLICH injizierte Egress-Sanitizer des Cloud-Adapters (Ctor-Feld, nur so prüfbar). */
    private fun internalSanitizerOf(adapter: OpenAiTtsAdapter): Any? =
        OpenAiTtsAdapter::class.java.getDeclaredField("sanitizer")
            .apply { isAccessible = true }
            .get(adapter)

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
    fun `jede LOKALE Engine bekommt die Huelle - piper, say, voxtral`() {
        val f = factory(sanitize = true)
        for (engine in listOf(TtsEngineIds.PIPER, TtsEngineIds.SAY, TtsEngineIds.VOXTRAL)) {
            assertTrue(
                f.build(engine, null) is SanitizingTtsPort,
                "Engine '$engine' ohne Sanitize-Hülle — genau die Lücke von 21.07.",
            )
        }
    }

    @Test
    fun `openai maskiert INTERN statt mit zweiter Huelle - die Regel gilt trotzdem`() {
        // Bewusste Asymmetrie (0.8.1, uebernommen aus dem Boot-Pfad, s.
        // PipelineConfigTtsSanitizeTest): der Cloud-Adapter traegt den Sanitizer selbst,
        // unmittelbar VOR dem Egress-Call — die staerkste Position. Eine zweite Huelle waere
        // wirkungslos-doppelt. Frueher prueften wir hier nur den Decorator-TYP; jetzt wird die
        // eigentliche Zusage geprueft: es maskiert wirklich etwas, und zwar der ECHTE
        // Never-Speak-Sanitizer (Wire-Beweis dazu: OpenAiTtsSanitizeWiringTest).
        val port = factory(sanitize = true).build(TtsEngineIds.OPENAI, null)

        assertTrue(port is OpenAiTtsAdapter, "openai darf nicht doppelt umhuellt werden, war: ${port::class.simpleName}")
        assertTrue(
            internalSanitizerOf(port as OpenAiTtsAdapter) is NeverSpeakTtsSanitizer,
            "der Cloud-Adapter MUSS den echten Never-Speak-Sanitizer tragen (sonst spricht er Rohtext in die Cloud)",
        )
    }

    @Test
    fun `openai MIT Verbalizer bekommt die aeussere Huelle doch - sonst waere die interne Position zu spaet`() {
        // Sicherheitskritisch: haengt der Verbalizer zwischen Huelle und Adapter, saehe der
        // INTERNE Sanitizer nur noch die ausgeschriebene Wortform („hundertzweiundneunzig
        // Punkt …") und die Masken-Regex traefe nicht mehr. Genau dann muss die aeussere
        // Huelle da sein — sie laeuft ZUERST, auf der rohen Ziffernform.
        val port = factory(sanitize = true, verbalize = true).build(TtsEngineIds.OPENAI, null)

        assertTrue(
            port is SanitizingTtsPort,
            "mit Verbalizer in der Kette braucht auch openai die aeussere Huelle, war: ${port::class.simpleName}",
        )
    }

    @Test
    fun `abgeschaltete Sanitize-Regel huellt nicht - byte-neutral zum Altverhalten`() {
        val f = factory(sanitize = false)
        assertFalse(f.build(TtsEngineIds.PIPER, null) is SanitizingTtsPort)
    }
}
