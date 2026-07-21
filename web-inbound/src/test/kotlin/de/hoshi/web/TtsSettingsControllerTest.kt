package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Direkter Konstruktor-Test von [TtsSettingsController] (kein Spring-Context,
 * kein echtes Sidecar-Netz — Muster [LookupModelControllerTest]): beweist
 * GET-Form (Live-Probe-Ergebnisse landen 1:1 in der View), PUT
 * valide/unbekannt (422)/nicht-verfügbar (409 mit Hinweis), dass ein
 * erfolgreicher PUT den Delegaten WIRKLICH umschaltet, und dass die
 * Persistenz einen Neustart überlebt.
 *
 * **Stimme folgt der aktiven Engine** (Andi-Live-Befund): zusätzlich beweist
 * dieser Test, dass GET `stimmen`/`aktiveStimme` DER AKTIVEN Engine trägt
 * (via [TtsVoiceCatalog]-Fake, kein echtes Sidecar-Netz), dass ein PUT mit
 * `voice` gegen die Live-Liste validiert (422 `unknown-voice` bei Mismatch)
 * und persistiert, und dass der Delegat NACH so einem PUT WIRKLICH mit der
 * neuen Stimme spricht (winziger JDK-HttpServer als Fake-`say`-Sidecar, Muster
 * [de.hoshi.adapters.tts.SayTtsAdapterTest]).
 */
class TtsSettingsControllerTest {

    private fun factory(sayBaseUrl: String = "http://127.0.0.1:8044", piperBaseUrl: String = "http://127.0.0.1:8045") = TtsEngineFactory(
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

    /** Fake-Probe: konfigurierbare Verfügbarkeit je Engine, kein echtes Netz. */
    private class FakeProbe(private val availability: Map<String, TtsEngineAvailability>) : TtsEngineProbe {
        override fun check(engineId: String): Mono<TtsEngineAvailability> =
            Mono.just(availability[engineId] ?: TtsEngineAvailability(false, "unbekannt"))
    }

    private val allAvailable = TtsEngineIds.ALL.associateWith { TtsEngineAvailability(true, "") }

    /** Fake-Stimmen-Katalog: konfigurierbare Live-Liste je Engine, kein echtes Sidecar-Netz. */
    private val defaultVoiceCatalog = TtsVoiceCatalog { id ->
        when (id) {
            TtsEngineIds.OPENAI -> Mono.just(
                TtsVoiceCatalogResult(listOf(TtsVoiceOption("coral", "Coral"), TtsVoiceOption("nova", "Nova"))),
            )
            TtsEngineIds.SAY -> Mono.just(
                TtsVoiceCatalogResult(listOf(TtsVoiceOption("Anna", "Anna", locale = "de_DE"))),
            )
            TtsEngineIds.PIPER -> Mono.just(
                TtsVoiceCatalogResult(
                    listOf(
                        TtsVoiceOption(
                            "de_DE-thorsten-medium",
                            "de_DE-thorsten-medium (medium)",
                            locale = "de_DE",
                            lizenz = "MIT / CC0-1.0",
                        ),
                    ),
                ),
            )
            else -> Mono.just(TtsVoiceCatalogResult(emptyList(), "Stimmwahl für Voxtral kommt noch."))
        }
    }

    private fun controller(
        dir: Path,
        probe: TtsEngineProbe = FakeProbe(allAvailable),
        ttsImpl: String = "",
        delegate: DelegatingTtsPort = DelegatingTtsPort("voxtral", TtsPortStub),
        store: JsonFileTtsEngineStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")),
        factory: TtsEngineFactory = factory(),
        voiceCatalog: TtsVoiceCatalog = defaultVoiceCatalog,
        languageStore: JsonFileLanguageStore = JsonFileLanguageStore(dir.resolve("language.json")),
    ) = TtsSettingsController(
        store = store,
        delegate = delegate,
        factory = factory,
        probe = probe,
        voiceCatalog = voiceCatalog,
        languageStore = languageStore,
        ttsImpl = ttsImpl,
    )

    @Test
    fun `GET - aktiv ist das Boot-Default, engines tragen den Live-Probe-Befund`(@TempDir dir: Path) {
        val probe = FakeProbe(
            mapOf(
                TtsEngineIds.OPENAI to TtsEngineAvailability(false, "Kein OPENAI_API_KEY gesetzt."),
                TtsEngineIds.SAY to TtsEngineAvailability(true, ""),
                TtsEngineIds.PIPER to TtsEngineAvailability(false, "nicht gestartet"),
                TtsEngineIds.VOXTRAL to TtsEngineAvailability(false, "nicht gestartet"),
            ),
        )
        val view = controller(dir, probe = probe).ttsSettings().block(Duration.ofSeconds(2))!!

        assertEquals("voxtral", view.aktiv, "leerer HOSHI_TTS ⇒ Boot-Default voxtral")
        assertEquals(4, view.engines.size)
        val voxtral = view.engines.single { it.id == TtsEngineIds.VOXTRAL }
        assertTrue(!voxtral.verfuegbar && voxtral.hinweis == "nicht gestartet", "ehrlich NICHT verfügbar, kein Fake-grün")
        val say = view.engines.single { it.id == TtsEngineIds.SAY }
        assertTrue(say.verfuegbar, "say ist per Fake-Probe erreichbar")
    }

    @Test
    fun `PUT unbekannte Engine - 422, kein Store-Write`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        val response = controller(dir, store = store).setEngine(TtsEngineRequest(id = "alexa"))
            .block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("unknown-engine", (response.body as SettingsError).error)
        assertNull(store.engineId())
    }

    @Test
    fun `PUT bekannte aber NICHT verfuegbare Engine - 409 mit dem ehrlichen Hinweis, kein Store-Write`(@TempDir dir: Path) {
        val probe = FakeProbe(mapOf(TtsEngineIds.PIPER to TtsEngineAvailability(false, "nicht gestartet")))
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        val response = controller(dir, probe = probe, store = store).setEngine(TtsEngineRequest(id = "piper"))
            .block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val error = response.body as SettingsError
        assertEquals("engine-unavailable", error.error)
        assertEquals("nicht gestartet", error.message)
        assertNull(store.engineId(), "eine nicht verfügbare Engine darf NIE persistiert werden")
    }

    @Test
    fun `PUT verfuegbare Engine - 200, Store UND Delegat schalten wirklich um`(@TempDir dir: Path) {
        val delegate = DelegatingTtsPort("voxtral", TtsPortStub)
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        val response = controller(dir, delegate = delegate, store = store).setEngine(TtsEngineRequest(id = "say"))
            .block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("say", (response.body as TtsSettingsView).aktiv)
        assertEquals("say", store.engineId(), "Store-Persist bewiesen")
        assertEquals("say", delegate.currentEngineId(), "der Delegat zeigt WIRKLICH auf die neue Engine")
    }

    @Test
    fun `Persistenz ueberlebt Reload - ein NEUER Controller ueber demselben Pfad sieht den PUT-Wunsch`(@TempDir dir: Path) {
        val path = dir.resolve("tts-engine.json")
        val first = controller(dir, store = JsonFileTtsEngineStore(path))
        first.setEngine(TtsEngineRequest(id = "piper")).block(Duration.ofSeconds(2))

        val restarted = controller(dir, store = JsonFileTtsEngineStore(path))
        val view = restarted.ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("piper", view.aktiv, "der PUT-Wunsch ueberlebt einen Neustart")
    }

    // ── Stimme folgt der aktiven Engine (Andi-Live-Befund) ──────────────────────

    @Test
    fun `GET - stimmen ist die Live-Liste der AKTIVEN Engine (openai), nicht aller vier`(@TempDir dir: Path) {
        val view = controller(dir, ttsImpl = "openai").ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("openai", view.aktiv)
        assertEquals(setOf("coral", "nova"), view.stimmen.map { it.id }.toSet())
        assertEquals("", view.stimmenHinweis)
    }

    @Test
    fun `GET - stimmen wechselt auf say-Stimmen, sobald say aktiv ist`(@TempDir dir: Path) {
        val view = controller(dir, ttsImpl = "say").ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("say", view.aktiv)
        assertEquals(listOf("Anna"), view.stimmen.map { it.id })
        assertEquals("de_DE", view.stimmen.single().locale)
    }

    @Test
    fun `GET - piper traegt Lizenzfelder in der Stimmen-Liste`(@TempDir dir: Path) {
        val view = controller(dir, ttsImpl = "piper").ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("piper", view.aktiv)
        val thorsten = view.stimmen.single()
        assertEquals("MIT / CC0-1.0", thorsten.lizenz)
    }

    @Test
    fun `GET - voxtral aktiv liefert eine leere Stimmen-Liste + ehrlichen Hinweis`(@TempDir dir: Path) {
        val view = controller(dir, ttsImpl = "voxtral").ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("voxtral", view.aktiv)
        assertTrue(view.stimmen.isEmpty())
        assertTrue(view.stimmenHinweis.isNotBlank())
    }

    @Test
    fun `GET - aktiveStimme ist der Factory-Boot-Default, solange nie eine Stimme gemerkt wurde`(@TempDir dir: Path) {
        val view = controller(dir, ttsImpl = "piper").ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("de_DE-thorsten-medium", view.aktiveStimme, "Boot-Default aus der Factory greift ohne Store-Eintrag")
    }

    @Test
    fun `GET - aktiveStimme ist die gemerkte Store-Stimme, wenn eine gesetzt wurde`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setVoice("say", "Anna")
        val view = controller(dir, ttsImpl = "say", store = store).ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("Anna", view.aktiveStimme, "die gemerkte Stimme gewinnt gegen den Boot-Default")
    }

    @Test
    fun `PUT mit unbekannter Stimme - 422 unknown-voice, kein Store-Write`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        val response = controller(dir, store = store)
            .setEngine(TtsEngineRequest(id = "say", voice = "Gandalf"))
            .block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("unknown-voice", (response.body as SettingsError).error)
        assertNull(store.voiceFor("say"), "eine unbekannte Stimme darf NIE persistiert werden")
    }

    @Test
    fun `PUT mit gueltiger Stimme - 200, Store merkt sie, GET spiegelt sie als aktiveStimme`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        val response = controller(dir, store = store)
            .setEngine(TtsEngineRequest(id = "say", voice = "Anna"))
            .block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.OK, response.statusCode)
        val view = response.body as TtsSettingsView
        assertEquals("say", view.aktiv)
        assertEquals("Anna", view.aktiveStimme)
        assertEquals("Anna", store.voiceFor("say"), "Store-Persist der Stimme bewiesen")
    }

    @Test
    fun `PUT ohne Stimme wendet eine ZUVOR gemerkte Stimme fuer diese Engine wieder an`(@TempDir dir: Path) {
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        store.setVoice("say", "Anna") // vorheriger Wunsch, z.B. aus einer früheren Sitzung
        val response = controller(dir, store = store)
            .setEngine(TtsEngineRequest(id = "say")) // kein voice-Feld in diesem PUT
            .block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("Anna", (response.body as TtsSettingsView).aktiveStimme, "die gemerkte Stimme bleibt aktiv, wird nicht stumm verworfen")
    }

    @Test
    fun `PUT mit Stimme - der Delegat spricht danach WIRKLICH mit der neuen Stimme`(@TempDir dir: Path) {
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
            val sayUrl = "http://127.0.0.1:${server.address.port}"
            val delegate = DelegatingTtsPort("voxtral", TtsPortStub)
            val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
            val response = controller(dir, delegate = delegate, store = store, factory = factory(sayBaseUrl = sayUrl))
                .setEngine(TtsEngineRequest(id = "say", voice = "Anna"))
                .block(Duration.ofSeconds(2))!!

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("say", delegate.currentEngineId())

            delegate.synth("Hallo", Language.DE).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty(), "der Delegat muss den Fake-Sidecar wirklich angefragt haben")
            assertTrue(captured.single().contains("\"voice\":\"Anna\""), "der Delegat muss mit der NEUEN Stimme sprechen: ${captured.single()}")
        } finally {
            server.stop(0)
        }
    }

    // ── Sprachbewusste Stimm-Aufloesung (Andi-Auftrag 21.07, TtsVoiceResolver) ──

    @Test
    fun `GET - aktiveStimme ist der EN-sayVoiceHint, wenn EN aktiv ist und nichts gemerkt wurde`(@TempDir dir: Path) {
        val languageStore = JsonFileLanguageStore(dir.resolve("language.json")).also { it.setLanguageCode("en") }
        val view = controller(dir, ttsImpl = "say", languageStore = languageStore).ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("Samantha", view.aktiveStimme, "EN aktiv, nichts gemerkt ⇒ der sayVoiceHint greift")
    }

    @Test
    fun `PUT ohne Stimme waehrend EN aktiv - baut mit dem sayVoiceHint, persistiert ihn aber NICHT als explizite Wahl`(@TempDir dir: Path) {
        val languageStore = JsonFileLanguageStore(dir.resolve("language.json")).also { it.setLanguageCode("en") }
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
        val response = controller(dir, store = store, languageStore = languageStore)
            .setEngine(TtsEngineRequest(id = "say"))
            .block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("Samantha", (response.body as TtsSettingsView).aktiveStimme, "der Hint wird gebaut/gesprochen")
        assertNull(store.voiceFor("say", Language.EN), "ein automatischer Hint ist KEINE explizite Wahl ⇒ Store bleibt unangetastet")
    }

    @Test
    fun `GET - eine explizite Stimme fuer (say,EN) gewinnt gegen den sayVoiceHint`(@TempDir dir: Path) {
        val languageStore = JsonFileLanguageStore(dir.resolve("language.json")).also { it.setLanguageCode("en") }
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also { it.setVoice("say", Language.EN, "Daniel") }
        val view = controller(dir, ttsImpl = "say", store = store, languageStore = languageStore)
            .ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("Daniel", view.aktiveStimme, "eine explizite (say,EN)-Wahl gewinnt gegen den automatischen Hint")
    }

    @Test
    fun `GET - DE aktiv bleibt byte-neutral - kein sayVoiceHint, nur der Boot-Default`(@TempDir dir: Path) {
        val view = controller(dir, ttsImpl = "say").ttsSettings().block(Duration.ofSeconds(2))!!
        assertNull(view.aktiveStimme, "DE traegt keinen sayVoiceHint (LangDe.PACK) ⇒ leerer sayVoice-Boot-Default bleibt null")
    }

    // ── piper folgt der Sprache genau wie say (Andi-Auftrag 21.07 Nachtrag: ────
    // ── en_US-kristin-medium ist jetzt handverifiziert + lizenzgeprueft) ───────

    @Test
    fun `GET - aktiveStimme ist der EN-piperVoiceHint (kristin), wenn piper aktive Engine und EN aktive Sprache ist`(@TempDir dir: Path) {
        val languageStore = JsonFileLanguageStore(dir.resolve("language.json")).also { it.setLanguageCode("en") }
        val view = controller(dir, ttsImpl = "piper", languageStore = languageStore).ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("en_US-kristin-medium", view.aktiveStimme, "EN aktiv, nichts gemerkt ⇒ der piperVoiceHint greift genau wie bei say")
    }

    @Test
    fun `GET - piper DE aktiv bleibt byte-neutral - kein piperVoiceHint, nur der Boot-Default (thorsten)`(@TempDir dir: Path) {
        val view = controller(dir, ttsImpl = "piper").ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals(
            "de_DE-thorsten-medium",
            view.aktiveStimme,
            "DE traegt keinen piperVoiceHint (LangDe.PACK) ⇒ derselbe Boot-Default wie vor dieser Naht",
        )
    }

    @Test
    fun `GET - eine explizite Stimme fuer (piper,EN) gewinnt gegen den piperVoiceHint`(@TempDir dir: Path) {
        val languageStore = JsonFileLanguageStore(dir.resolve("language.json")).also { it.setLanguageCode("en") }
        val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).also { it.setVoice("piper", Language.EN, "en_US-anna-low") }
        val view = controller(dir, ttsImpl = "piper", store = store, languageStore = languageStore)
            .ttsSettings().block(Duration.ofSeconds(2))!!
        assertEquals("en_US-anna-low", view.aktiveStimme, "eine explizite (piper,EN)-Wahl gewinnt gegen den automatischen Hint")
    }

    @Test
    fun `PUT mit gueltiger Kristin-Stimme fuer piper - 200, der Delegat spricht danach WIRKLICH mit kristin`(@TempDir dir: Path) {
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
            val piperUrl = "http://127.0.0.1:${server.address.port}"
            val piperCatalog = TtsVoiceCatalog { id ->
                if (id == TtsEngineIds.PIPER) {
                    Mono.just(
                        TtsVoiceCatalogResult(
                            listOf(
                                TtsVoiceOption("de_DE-thorsten-medium", "de_DE-thorsten-medium (medium)", locale = "de_DE"),
                                TtsVoiceOption("en_US-kristin-medium", "en_US-kristin-medium (medium)", locale = "en_US"),
                            ),
                        ),
                    )
                } else {
                    defaultVoiceCatalog.voicesFor(id)
                }
            }
            val delegate = DelegatingTtsPort("voxtral", TtsPortStub)
            val store = JsonFileTtsEngineStore(dir.resolve("tts-engine.json"))
            val response = controller(
                dir,
                delegate = delegate,
                store = store,
                factory = factory(piperBaseUrl = piperUrl),
                voiceCatalog = piperCatalog,
            ).setEngine(TtsEngineRequest(id = "piper", voice = "en_US-kristin-medium"))
                .block(Duration.ofSeconds(2))!!

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("piper", delegate.currentEngineId())
            assertEquals("en_US-kristin-medium", store.voiceFor("piper", Language.DE), "Store-Persist der expliziten Stimme bewiesen")

            delegate.synth("Hello", Language.EN).block(Duration.ofSeconds(5))
            assertTrue(captured.isNotEmpty(), "der Delegat muss den Fake-piper-Sidecar wirklich angefragt haben")
            assertTrue(
                captured.single().contains("\"voice\":\"en_US-kristin-medium\""),
                "der Delegat muss mit der NEUEN Stimme sprechen: ${captured.single()}",
            )
        } finally {
            server.stop(0)
        }
    }
}

/** Ein reglos-leerer TtsPort als initialer Delegat (nie wirklich aufgerufen in diesen Tests). */
private object TtsPortStub : de.hoshi.core.port.TtsPort {
    override fun synth(text: String, language: de.hoshi.core.dto.Language): Mono<ByteArray> = Mono.empty()
}
