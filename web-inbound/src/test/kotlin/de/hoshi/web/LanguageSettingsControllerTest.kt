package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import reactor.core.publisher.Mono
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration

/**
 * Direkter Konstruktor-Test von [LanguageSettingsController] (kein Spring-Context,
 * kein Netz — Muster [LookupModelControllerTest]/[WeatherLocationControllerTest]):
 * beweist GET-Form (alle 5 Sprachen, DE ohne Beta-Flag), PUT valide/unbekannt
 * (422), Persist-Readback und dass die Persistenz einen Neustart überlebt.
 *
 * **TTS folgt der Sprache** (Andi-Auftrag 21.07): zusätzlich beweist dieser Test,
 * dass ein erfolgreicher Sprach-PUT die AKTIVE `say`-Engine live umschaltet — auf
 * den [de.hoshi.core.pipeline.lang.LanguagePack.sayVoiceHint] der neuen Sprache,
 * sofern kein expliziterer Store-Wunsch existiert — und dass eine explizit für
 * (Engine, Sprache) gemerkte Stimme einen Sprachwechsel hin und zurück überlebt
 * (winziger JDK-HttpServer als Fake-`say`-Sidecar, Muster [TtsSettingsControllerTest]).
 */
class LanguageSettingsControllerTest {

    private fun testTtsEngineFactory(sayBaseUrl: String = "http://127.0.0.1:1", piperBaseUrl: String = "http://127.0.0.1:8045") = TtsEngineFactory(
        voxtralBaseUrl = "http://localhost:8042",
        voxtralVoice = "de_female",
        openaiModel = "gpt-4o-mini-tts",
        openaiVoice = "coral",
        sayBaseUrl = sayBaseUrl,
        sayVoice = "",
        sayRate = 0,
        piperBaseUrl = piperBaseUrl,
        piperVoice = "de_DE-thorsten-medium",
        sanitizeEnabled = false,
        ttsStreamEnabled = false,
    )

    private fun controller(
        dir: Path,
        store: JsonFileLanguageStore = JsonFileLanguageStore(dir.resolve("language.json")),
        ttsEngineStore: JsonFileTtsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")),
        ttsEngineFactory: TtsEngineFactory = testTtsEngineFactory(),
        delegatingTtsPort: DelegatingTtsPort = DelegatingTtsPort("voxtral", NoopTtsPort),
        ttsImpl: String = "",
    ) = LanguageSettingsController(
        store = store,
        ttsEngineStore = ttsEngineStore,
        ttsEngineFactory = ttsEngineFactory,
        delegatingTtsPort = delegatingTtsPort,
        ttsImpl = ttsImpl,
    )

    @Test
    fun `GET ohne Store-Wert - aktiv ist DE, alle fuenf Sprachen sind gelistet`(@TempDir dir: Path) {
        val view = controller(dir).language()
        assertEquals("de", view.aktiv)
        assertEquals(5, view.sprachen.size)
        assertEquals(setOf("de", "en", "es", "fr", "it"), view.sprachen.map { it.code }.toSet())
        assertNull(view.smartHomeHinweis, "DE braucht keinen Smart-Home-Hinweis")
    }

    @Test
    fun `GET - Deutsch traegt kein Beta-Flag, jede andere Sprache schon`(@TempDir dir: Path) {
        val view = controller(dir).language()
        val de = view.sprachen.first { it.code == "de" }
        assertFalse(de.beta, "Deutsch ist Tier 1, kein Beta")
        for (code in listOf("en", "es", "fr", "it")) {
            assertTrue(view.sprachen.first { it.code == code }.beta, "$code sollte als Beta markiert sein")
        }
    }

    @Test
    fun `PUT unbekannter Code - 422, kein Store-Write`(@TempDir dir: Path) {
        val store = JsonFileLanguageStore(dir.resolve("language.json"))
        val response = controller(dir, store).setLanguage(LanguageSettingsRequest(code = "xx"))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("unknown-language", (response.body as SettingsError).error)
        assertNull(store.languageCode(), "eine unbekannte Sprache darf NIE persistiert werden")
    }

    @Test
    fun `PUT leerer Code - 422, kein Store-Write`(@TempDir dir: Path) {
        val store = JsonFileLanguageStore(dir.resolve("language.json"))
        val response = controller(dir, store).setLanguage(LanguageSettingsRequest(code = null))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertNull(store.languageCode())
    }

    @Test
    fun `PUT en - 200, Store persistiert, GET spiegelt en als aktiv plus Smart-Home-Hinweis`(@TempDir dir: Path) {
        val store = JsonFileLanguageStore(dir.resolve("language.json"))
        val response = controller(dir, store).setLanguage(LanguageSettingsRequest(code = "en"))

        assertEquals(HttpStatus.OK, response.statusCode)
        val view = response.body as LanguageSettingsView
        assertEquals("en", view.aktiv)
        assertEquals("en", store.languageCode(), "Store-Persist bewiesen")
        assertTrue(view.smartHomeHinweis!!.contains("German"), "EN bekommt den ehrlichen Smart-Home-Hinweis")
    }

    @Test
    fun `PUT ist tolerant gegenueber Gross-Kleinschreibung (EN wie en)`(@TempDir dir: Path) {
        val store = JsonFileLanguageStore(dir.resolve("language.json"))
        val response = controller(dir, store).setLanguage(LanguageSettingsRequest(code = "EN"))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("en", store.languageCode())
    }

    @Test
    fun `alle fuenf Sprachen sind per PUT waehlbar`(@TempDir dir: Path) {
        for (language in Language.entries) {
            val store = JsonFileLanguageStore(dir.resolve("language-${language.code}.json"))
            val response = controller(dir, store).setLanguage(LanguageSettingsRequest(code = language.code))
            assertEquals(HttpStatus.OK, response.statusCode, "PUT ${language.code} sollte 200 liefern")
            assertEquals(language.code, store.languageCode())
        }
    }

    @Test
    fun `Persistenz ueberlebt Reload - ein NEUER Controller ueber demselben Pfad sieht den PUT-Wunsch`(@TempDir dir: Path) {
        val path = dir.resolve("language.json")
        val first = controller(dir, JsonFileLanguageStore(path))
        first.setLanguage(LanguageSettingsRequest(code = "fr"))

        val restarted = controller(dir, JsonFileLanguageStore(path))
        assertEquals("fr", restarted.language().aktiv, "der PUT-Wunsch ueberlebt einen Neustart")
    }

    // ── TTS folgt der Sprache (Andi-Auftrag 21.07) ──────────────────────────────

    /** Fake-`say`-Sidecar (JDK-HttpServer), fängt den `voice`-Wert im Request-Body ein. */
    private fun withFakeSaySidecar(block: (sayUrl: String, captured: MutableList<String>) -> Unit) {
        val captured = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tts") { ex ->
            captured += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val wav = ByteArray(16) { 1 }
            ex.sendResponseHeaders(200, wav.size.toLong())
            ex.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `PUT en - die aktive say-Engine schwenkt live auf den EN-sayVoiceHint (Samantha)`(@TempDir dir: Path) =
        withFakeSaySidecar { sayUrl, captured ->
            val ttsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also { it.setEngineId("say") }
            val factory = testTtsEngineFactory(sayBaseUrl = sayUrl)
            val delegate = DelegatingTtsPort("say", factory.build("say", null))

            val response = controller(dir, ttsEngineStore = ttsEngineStore, ttsEngineFactory = factory, delegatingTtsPort = delegate)
                .setLanguage(LanguageSettingsRequest(code = "en"))
            assertEquals(HttpStatus.OK, response.statusCode)

            delegate.synth("Hello", Language.EN).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty(), "der Delegat muss den Fake-Sidecar wirklich angefragt haben")
            assertTrue(
                captured.last().contains("\"voice\":\"Samantha\""),
                "say muss nach dem EN-Wechsel mit dem sayVoiceHint sprechen: ${captured.last()}",
            )
        }

    @Test
    fun `PUT de nach zuvor EN - kein sayVoiceHint mehr, Boot-Default (keine Stimme im Body) greift wieder`(@TempDir dir: Path) =
        withFakeSaySidecar { sayUrl, captured ->
            val ttsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also { it.setEngineId("say") }
            val factory = testTtsEngineFactory(sayBaseUrl = sayUrl)
            val delegate = DelegatingTtsPort("say", factory.build("say", null))
            val ctrl = controller(dir, ttsEngineStore = ttsEngineStore, ttsEngineFactory = factory, delegatingTtsPort = delegate)

            ctrl.setLanguage(LanguageSettingsRequest(code = "en"))
            ctrl.setLanguage(LanguageSettingsRequest(code = "de"))

            delegate.synth("Hallo", Language.DE).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty())
            assertFalse(captured.last().contains("\"voice\""), "DE traegt keinen sayVoiceHint ⇒ kein voice-Feld im Body: ${captured.last()}")
        }

    @Test
    fun `explizite Stimm-Wahl fuer (say,EN) gewinnt gegen den Hint und ueberlebt einen Sprachwechsel hin und zurueck`(@TempDir dir: Path) =
        withFakeSaySidecar { sayUrl, captured ->
            val ttsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also {
                it.setEngineId("say")
                it.setVoice("say", Language.EN, "Daniel") // Andis explizite Wahl NUR fuer (say, EN)
            }
            val factory = testTtsEngineFactory(sayBaseUrl = sayUrl)
            val delegate = DelegatingTtsPort("say", factory.build("say", null))
            val ctrl = controller(dir, ttsEngineStore = ttsEngineStore, ttsEngineFactory = factory, delegatingTtsPort = delegate)

            ctrl.setLanguage(LanguageSettingsRequest(code = "en"))
            ctrl.setLanguage(LanguageSettingsRequest(code = "fr"))
            ctrl.setLanguage(LanguageSettingsRequest(code = "en"))

            delegate.synth("Hello again", Language.EN).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty())
            assertTrue(
                captured.last().contains("\"voice\":\"Daniel\""),
                "die explizite (say,EN)-Wahl muss den Hin-und-Zurueck-Wechsel ueberleben, nicht der Hint: ${captured.last()}",
            )
            assertEquals("Daniel", ttsEngineStore.voiceFor("say", Language.EN), "der Store selbst bleibt von reinen Sprachwechseln unangetastet")
        }

    // ── piper folgt der Sprache genau wie say (Andi-Auftrag 21.07 Nachtrag) ─────

    /** Fake-`piper`-Sidecar (JDK-HttpServer), fängt den `voice`-Wert im Request-Body ein. */
    private fun withFakePiperSidecar(block: (piperUrl: String, captured: MutableList<String>) -> Unit) {
        val captured = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tts") { ex ->
            captured += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val wav = ByteArray(16) { 1 }
            ex.sendResponseHeaders(200, wav.size.toLong())
            ex.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `PUT en - die aktive piper-Engine schwenkt live auf den EN-piperVoiceHint (kristin)`(@TempDir dir: Path) =
        withFakePiperSidecar { piperUrl, captured ->
            val ttsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also { it.setEngineId("piper") }
            val factory = testTtsEngineFactory(piperBaseUrl = piperUrl)
            val delegate = DelegatingTtsPort("piper", factory.build("piper", null))

            val response = controller(dir, ttsEngineStore = ttsEngineStore, ttsEngineFactory = factory, delegatingTtsPort = delegate)
                .setLanguage(LanguageSettingsRequest(code = "en"))
            assertEquals(HttpStatus.OK, response.statusCode)

            delegate.synth("Hello", Language.EN).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty(), "der Delegat muss den Fake-piper-Sidecar wirklich angefragt haben")
            assertTrue(
                captured.last().contains("\"voice\":\"en_US-kristin-medium\""),
                "piper muss nach dem EN-Wechsel mit dem piperVoiceHint sprechen: ${captured.last()}",
            )
        }

    @Test
    fun `PUT de nach zuvor EN (piper) - kein piperVoiceHint mehr, Boot-Default thorsten greift wieder`(@TempDir dir: Path) =
        withFakePiperSidecar { piperUrl, captured ->
            val ttsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also { it.setEngineId("piper") }
            val factory = testTtsEngineFactory(piperBaseUrl = piperUrl)
            val delegate = DelegatingTtsPort("piper", factory.build("piper", null))
            val ctrl = controller(dir, ttsEngineStore = ttsEngineStore, ttsEngineFactory = factory, delegatingTtsPort = delegate)

            ctrl.setLanguage(LanguageSettingsRequest(code = "en"))
            ctrl.setLanguage(LanguageSettingsRequest(code = "de"))

            delegate.synth("Hallo", Language.DE).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty())
            assertTrue(
                captured.last().contains("\"voice\":\"de_DE-thorsten-medium\""),
                "DE traegt keinen piperVoiceHint ⇒ derselbe Boot-Default wie vor dieser Naht: ${captured.last()}",
            )
        }

    @Test
    fun `explizite Stimm-Wahl fuer (piper,EN) gewinnt gegen den Hint und ueberlebt einen Sprachwechsel hin und zurueck`(@TempDir dir: Path) =
        withFakePiperSidecar { piperUrl, captured ->
            val ttsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also {
                it.setEngineId("piper")
                it.setVoice("piper", Language.EN, "en_US-anna-low") // Andis explizite Wahl NUR fuer (piper, EN)
            }
            val factory = testTtsEngineFactory(piperBaseUrl = piperUrl)
            val delegate = DelegatingTtsPort("piper", factory.build("piper", null))
            val ctrl = controller(dir, ttsEngineStore = ttsEngineStore, ttsEngineFactory = factory, delegatingTtsPort = delegate)

            ctrl.setLanguage(LanguageSettingsRequest(code = "en"))
            ctrl.setLanguage(LanguageSettingsRequest(code = "fr"))
            ctrl.setLanguage(LanguageSettingsRequest(code = "en"))

            delegate.synth("Hello again", Language.EN).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty())
            assertTrue(
                captured.last().contains("\"voice\":\"en_US-anna-low\""),
                "die explizite (piper,EN)-Wahl muss den Hin-und-Zurueck-Wechsel ueberleben, nicht der Hint: ${captured.last()}",
            )
            assertEquals("en_US-anna-low", ttsEngineStore.voiceFor("piper", Language.EN), "der Store selbst bleibt von reinen Sprachwechseln unangetastet")
        }
}

/** Ein reglos-leerer TtsPort als initialer Delegat (nie wirklich aufgerufen in diesen Tests). */
private object NoopTtsPort : de.hoshi.core.port.TtsPort {
    override fun synth(text: String, language: Language): Mono<ByteArray> = Mono.empty()
}
