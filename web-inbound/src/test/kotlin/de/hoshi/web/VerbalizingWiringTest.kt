package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/**
 * **VerbalizingWiringTest** — beweist die Verdrahtungs-Naht der fertigen, aber
 * bislang unverdrahteten `IcuVerbalizer`/`VerbalizingTtsPort`-Hülle
 * (`adapters-tts`, byte-neutral bis zu dieser Scheibe) in [TtsEngineFactory]
 * (neues Flag `HOSHI_TTS_VERBALIZE_ENABLED`, Default OFF, Muster
 * `sanitizeEnabled`/[de.hoshi.adapters.tts.LoudnessNormalizingTtsPort]).
 *
 * Nutzt denselben winzigen JDK-`HttpServer`-Fake-`say`-Sidecar wie
 * [TtsSettingsControllerTest]/[LanguageSettingsControllerTest], damit der
 * WIRKLICH bei der Engine ankommende Text geprüft werden kann — nicht nur der
 * Decorator-Typ (der Fake-Sidecar antwortet mit einem Mini-WAV, der Body wird
 * je Request eingesammelt).
 *
 * Drei Kern-Beweise (Auftrag Punkt (d)):
 *  1. Flag OFF (Default) ⇒ der Text kommt byte-identisch bei der Engine an —
 *     [TtsEngineFactory.wrapVerbalizing] baut den Decorator gar nicht erst.
 *  2. Flag ON ⇒ der [de.hoshi.adapters.tts.IcuVerbalizer] sitzt WIRKLICH in der
 *     Kette (Uhrzeit-Ziffern werden ausgeschrieben).
 *  3. Sanitizer UND Verbalizer beide an ⇒ die SICHERHEITSKRITISCHE Reihenfolge
 *     stimmt: eine LAN-IP wird von [NeverSpeakTtsSanitizer] maskiert, BEVOR der
 *     Verbalizer sie in Worte zerlegen könnte (s. `VerbalizingTtsPort`-KDoc,
 *     `adapters-tts` — vertauscht man die Reihenfolge, sähe der Sanitizer nur
 *     noch die ausgeschriebene Wortform und die Maske griffe nicht mehr).
 */
class VerbalizingWiringTest {

    private fun factory(sayBaseUrl: String, sanitize: Boolean, verbalize: Boolean) = TtsEngineFactory(
        voxtralBaseUrl = "http://localhost:8042",
        voxtralVoice = "de_female",
        openaiModel = "gpt-4o-mini-tts",
        openaiVoice = "coral",
        sayBaseUrl = sayBaseUrl,
        sayVoice = "",
        sayRate = 0,
        piperBaseUrl = "http://127.0.0.1:8045",
        piperVoice = "de_DE-thorsten-medium",
        sanitizeEnabled = sanitize,
        ttsStreamEnabled = false,
        verbalizeEnabled = verbalize,
    )

    /** Winziger Fake-`say`-Sidecar (Muster [TtsSettingsControllerTest]): sammelt jeden Request-Body ein, antwortet mit einem Mini-WAV. */
    private fun fakeSayServer(captured: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tts") { ex ->
            captured += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val wav = ByteArray(16) { 1 }
            ex.sendResponseHeaders(200, wav.size.toLong())
            ex.responseBody.use { it.write(wav) }
        }
        server.start()
        return server
    }

    @Test
    fun `Flag OFF - Text bleibt byte-identisch, Verbalize-Decorator wird nicht gebaut`() {
        val captured = mutableListOf<String>()
        val server = fakeSayServer(captured)
        try {
            val f = factory(sayBaseUrl = "http://127.0.0.1:${server.address.port}", sanitize = false, verbalize = false)
            val port = f.build(TtsEngineIds.SAY, null)
            val text = "Es ist 20 Uhr 15, macht 17,1 Grad."

            port.synth(text, Language.DE).block(Duration.ofSeconds(5))

            assertTrue(captured.isNotEmpty(), "der Fake-Sidecar muss wirklich angefragt worden sein")
            assertTrue(
                captured.single().contains(""""text":"$text""""),
                "OFF muss den Text byte-identisch zu heute durchreichen: ${captured.single()}",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Flag ON - der IcuVerbalizer sitzt wirklich in der Kette`() {
        val captured = mutableListOf<String>()
        val server = fakeSayServer(captured)
        try {
            val f = factory(sayBaseUrl = "http://127.0.0.1:${server.address.port}", sanitize = false, verbalize = true)
            val port = f.build(TtsEngineIds.SAY, null)

            port.synth("Es ist 20 Uhr 15.", Language.DE).block(Duration.ofSeconds(5))

            val seen = captured.single()
            assertTrue(seen.contains("zwanzig Uhr fünfzehn"), "die Ziffern müssen ausgeschrieben sein: $seen")
            assertFalse(seen.any { it.isDigit() }, "ON muss den Text veraendern (keine Ziffer mehr uebrig): $seen")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Sanitizer UND Verbalizer an - die Reihenfolge stimmt, die LAN-IP wird maskiert statt ausgeschrieben`() {
        val captured = mutableListOf<String>()
        val server = fakeSayServer(captured)
        try {
            val f = factory(sayBaseUrl = "http://127.0.0.1:${server.address.port}", sanitize = true, verbalize = true)
            val port = f.build(TtsEngineIds.SAY, null)
            val secretIp = "192.168.178.106"

            port.synth("Deine Fritzbox hat die IP $secretIp seit 20 Uhr 15.", Language.DE).block(Duration.ofSeconds(5))

            val seen = captured.single()
            assertTrue(seen.contains("[IP]"), "die Sanitize-Maske muss im an die Engine gereichten Text stehen: $seen")
            assertFalse(seen.contains(secretIp), "die rohe IP darf NICHT mehr vorkommen (waere sie es, ginge sie unmaskiert an den Sidecar): $seen")
            assertTrue(seen.contains("zwanzig Uhr fünfzehn"), "die Uhrzeit muss TROTZDEM verbalisiert sein (Verbalizer laeuft weiterhin, nur NACH dem Sanitizer): $seen")
            assertFalse(seen.any { it.isDigit() }, "keine Ziffer erwartet (weder IP noch Uhrzeit-Reste): $seen")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `abgeschaltetes Verbalize-Flag laesst eine bereits maskierte IP unveraendert (nur Sanitizer an)`() {
        val captured = mutableListOf<String>()
        val server = fakeSayServer(captured)
        try {
            val f = factory(sayBaseUrl = "http://127.0.0.1:${server.address.port}", sanitize = true, verbalize = false)
            val port = f.build(TtsEngineIds.SAY, null)
            val secretIp = "192.168.178.106"

            port.synth("IP $secretIp, 20 Uhr 15.", Language.DE).block(Duration.ofSeconds(5))

            val seen = captured.single()
            assertTrue(seen.contains("[IP]"), "Sanitize allein muss weiterhin greifen: $seen")
            assertTrue(seen.contains("20 Uhr 15"), "ohne Verbalize bleibt die Uhrzeit ZIFFERN-Form (Bestandsverhalten): $seen")
        } finally {
            server.stop(0)
        }
    }
}
