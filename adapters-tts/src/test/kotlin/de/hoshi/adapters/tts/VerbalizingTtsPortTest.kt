package de.hoshi.adapters.tts

import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TtsSanitizePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * Beweist [VerbalizingTtsPort]: den reinen Decorator-Vertrag (verbalisiert VOR dem
 * Delegate), das flag-neutrale Verhalten bei "OFF" (Decorator gar nicht erst gebaut
 * — Muster `sanitizeEnabled`/`LoudnessNormalizingTtsPort`) und — am wichtigsten —
 * die SICHERHEITSKRITISCHE Reihenfolge sanitize→verbalize→Engine.
 *
 * **Warum [FakeSanitizingTtsPort] hier lokal statt der echten `SanitizingTtsPort`
 * (`web-inbound`):** `adapters-tts` haengt (hexagonale Modul-Grenze) nicht von
 * `web-inbound` ab — die echte Verdrahtung mit der echten `SanitizingTtsPort` +
 * `NeverSpeakTtsSanitizer` liegt darum ausserhalb dieser Scheibe (s.
 * [VerbalizingTtsPort]-KDoc, Abschnitt „Offene Verdrahtungs-Naht"). [FakeSanitizingTtsPort]
 * repliziert NUR die Ein-Zeilen-Form von `SanitizingTtsPort`
 * (`delegate.synth(sanitizer.sanitizeForSpeech(text), language)`) 1:1 als Test-Fixtur,
 * damit die Kompositions-REIHENFOLGE — nicht die konkrete Masken-Regex — hier
 * innerhalb von `adapters-tts` beweisbar ist.
 */
class VerbalizingTtsPortTest {

    @Test
    fun `verbalisiert den Text VOR dem Delegate-Aufruf`() {
        var seenText = ""
        val engine = TtsPort { text, _ -> seenText = text; Mono.empty() }
        val port = VerbalizingTtsPort(engine, IcuVerbalizer())

        port.synth("Es ist 20 Uhr 15.", Language.DE).block()

        assertEquals("Es ist zwanzig Uhr fünfzehn.", seenText)
    }

    @Test
    fun `synth mit voice-Parameter verbalisiert genauso und reicht die Stimme durch`() {
        var seenText = ""
        var seenVoice: String? = null
        val engine = object : TtsPort {
            override fun synth(text: String, language: Language) = Mono.empty<ByteArray>()
            override fun synth(text: String, language: Language, voice: String?): Mono<ByteArray> {
                seenText = text
                seenVoice = voice
                return Mono.empty()
            }
        }
        val port = VerbalizingTtsPort(engine, IcuVerbalizer())

        port.synth("Ich habe 3 Katzen.", Language.DE, "nova").block()

        assertEquals("Ich habe drei Katzen.", seenText)
        assertEquals("nova", seenVoice)
    }

    @Test
    fun `synthStream verbalisiert ebenfalls VOR dem Delegate`() {
        var seenText = ""
        val engine = TtsPort { text, _ -> seenText = text; Mono.empty() }
        val port = VerbalizingTtsPort(engine, IcuVerbalizer())

        port.synthStream("42 Nachrichten.", Language.DE).blockLast()

        assertEquals("zweiundvierzig Nachrichten.", seenText)
    }

    // ---- Flag-Gating (HOSHI_TTS_VERBALIZE_ENABLED, Default OFF) ----------
    //
    // Das eigentliche Env-Flag wird an der Verdrahtungs-Naht (TtsEngineFactory,
    // web-inbound, ausserhalb dieser Scheibe) gelesen — hier wird das resultierende
    // Verhalten simuliert: OFF baut den Decorator NICHT (byte-neutral, Muster
    // `LoudnessNormalizingTtsPort`/`SanitizingTtsPort`), ON hüllt damit ein.

    @Test
    fun `Flag OFF (Decorator nicht gebaut) laesst Text byte-identisch durch`() {
        var seenText = ""
        val engine = TtsPort { text, _ -> seenText = text; Mono.empty() }
        val verbalizeEnabled = false

        val port: TtsPort = if (verbalizeEnabled) VerbalizingTtsPort(engine, IcuVerbalizer()) else engine
        val originalText = "Termin um 20 Uhr 15, macht 17,1 Grad."

        port.synth(originalText, Language.DE).block()

        assertEquals(originalText, seenText, "OFF muss byte-identisch bleiben")
    }

    @Test
    fun `Flag ON (Decorator gebaut) veraendert den Text`() {
        var seenText = ""
        val engine = TtsPort { text, _ -> seenText = text; Mono.empty() }
        val verbalizeEnabled = true

        val port: TtsPort = if (verbalizeEnabled) VerbalizingTtsPort(engine, IcuVerbalizer()) else engine
        val originalText = "Termin um 20 Uhr 15."

        port.synth(originalText, Language.DE).block()

        assertFalse(seenText == originalText, "ON muss den Text veraendern")
        assertEquals("Termin um zwanzig Uhr fünfzehn.", seenText)
    }

    // ---- Die sicherheitskritische Reihenfolge: sanitize → verbalize → Engine ----

    @Test
    fun `korrekte Kette (Sanitizer aussen, Verbalizer innen) maskiert die LAN-IP zuverlaessig`() {
        val secretIp = "192.168.178.106"
        val text = "Deine Fritzbox hat die IP $secretIp seit 20 Uhr 15."
        var seenByEngine = ""
        val engine = TtsPort { t, _ -> seenByEngine = t; Mono.empty() }

        // RICHTIG: erst die Engine mit VerbalizingTtsPort umhuellen, DANN das
        // Ergebnis mit der Sanitize-Huelle umhuellen (Sanitizer aussen).
        val correctChain: TtsPort = FakeSanitizingTtsPort(
            delegate = VerbalizingTtsPort(engine, IcuVerbalizer()),
            sanitizer = IP_MASKING_SANITIZER,
        )

        correctChain.synth(text, Language.DE).block()

        assertTrue(seenByEngine.contains("[IP]"), "erwarte die Maske in '$seenByEngine'")
        assertFalse(seenByEngine.contains(secretIp), "die rohe IP darf NICHT mehr vorkommen")
        assertFalse(
            seenByEngine.any { it.isDigit() },
            "keine Ziffer erwartet (weder die Uhrzeit noch die IP duerfen als Ziffern uebrig sein): '$seenByEngine'",
        )
    }

    @Test
    fun `vertauschte Kette (Verbalizer aussen, Sanitizer innen) LAESST die IP als gesprochene Worte durch`() {
        val secretIp = "192.168.178.106"
        val text = "Deine Fritzbox hat die IP $secretIp."
        var seenByEngine = ""
        val engine = TtsPort { t, _ -> seenByEngine = t; Mono.empty() }

        // FALSCH (nur zur Beweisfuehrung, NIE so verdrahten): Verbalizer aussen,
        // Sanitizer innen -> der Sanitizer sieht die IP nur noch als Wort-Salat.
        val invertedChain: TtsPort = VerbalizingTtsPort(
            delegate = FakeSanitizingTtsPort(engine, IP_MASKING_SANITIZER),
            verbalizer = IcuVerbalizer(),
        )

        invertedChain.synth(text, Language.DE).block()

        // Der Beweis der Gefahr: die Maske greift NICHT mehr (die IP-Ziffernfolge
        // existiert zum Zeitpunkt des Sanitize-Aufrufs nicht mehr als Ziffern),
        // die ausgeschriebene IP bleibt im an die Engine gereichten Text stehen —
        // KEINE Ziffer mehr da (alles schon zu Worten verbalisiert), aber auch KEINE
        // Maske: das Geheimnis wurde unmaskiert als Worte an die Engine gereicht.
        assertFalse(seenByEngine.contains("[IP]"), "im FALSCH verdrahteten Fall greift die Maske NICHT (Beweis der Gefahr)")
        assertFalse(seenByEngine.contains(secretIp), "die Ziffernform ist bereits verbalisiert, bevor sanitize lief")
        assertTrue(
            seenByEngine.none { it.isDigit() },
            "die Gefahr: die IP-Ziffern wurden VOR dem Sanitize bereits zu Worten -> ungemaskiertes Leck: '$seenByEngine'",
        )
    }

    private companion object {
        /**
         * Simplifizierter Test-Ersatz fuer `NeverSpeakTtsSanitizer.LAN_IP_PATTERN`
         * (`web-inbound`, hier wegen der Modul-Grenze nicht verfuegbar): maskiert NUR
         * die eine im Test verwendete LAN-IP, genau wie die echte Regel es fuer JEDE
         * RFC-1918-Adresse taete.
         */
        private val IP_MASKING_SANITIZER = TtsSanitizePort { it.replace("192.168.178.106", "[IP]") }
    }
}

/**
 * Test-Fixtur: repliziert 1:1 die Ein-Zeilen-Form der echten `SanitizingTtsPort`
 * (`web-inbound/src/main/kotlin/de/hoshi/web/SanitizingTtsPort.kt`) —
 * `delegate.synth(sanitizer.sanitizeForSpeech(text), language)`. Existiert NUR hier
 * in `adapters-tts`, weil die echte Klasse aus Modul-Gruenden nicht importierbar ist
 * (s. Klassen-KDoc oben). Keine Produktionslogik, reine Kompositions-Beweisfuehrung.
 */
private class FakeSanitizingTtsPort(
    private val delegate: TtsPort,
    private val sanitizer: TtsSanitizePort,
) : TtsPort {
    override fun synth(text: String, language: Language) =
        delegate.synth(sanitizer.sanitizeForSpeech(text), language)
}
