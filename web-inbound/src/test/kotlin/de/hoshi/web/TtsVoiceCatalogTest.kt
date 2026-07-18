package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Beweist das ehrliche Mapping von [HttpTtsVoiceCatalog] OHNE Live-Sidecar-Infra
 * (Muster [de.hoshi.adapters.supervision.HttpSidecarProbeTest]): ein winziger
 * JDK-HttpServer liefert kanned `/voices`-Bodies in den ECHTEN Sidecar-Formen
 * (say: `{name,locale,sample}`; piper: `{id,locale,quality,…license}`).
 */
class TtsVoiceCatalogTest {

    private fun withServer(path: String, body: String, status: Int = 200, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun catalog(sayBaseUrl: String = "http://127.0.0.1:1", piperBaseUrl: String = "http://127.0.0.1:1") =
        HttpTtsVoiceCatalog(sayBaseUrl = sayBaseUrl, piperBaseUrl = piperBaseUrl, timeout = Duration.ofSeconds(2))

    // ── openai: keine Netz-Naht, feste Whitelist ────────────────────────────

    @Test
    fun `openai - kein Netz-Call, die feste Whitelist kommt 1-zu-1 durch`() {
        val result = catalog().voicesFor(TtsEngineIds.OPENAI).block(Duration.ofSeconds(2))!!
        assertEquals(OpenAiTtsAdapter.SUPPORTED_VOICES.size, result.stimmen.size)
        assertTrue(result.stimmen.any { it.id == "coral" })
        assertEquals("", result.hinweis)
    }

    // ── say: {name,locale,sample} ────────────────────────────────────────────

    @Test
    fun `say - parst name+locale aus der Sidecar-Form`() = withServer(
        "/voices",
        """{"voices":[{"name":"Anna","locale":"de_DE","sample":"Hallo, ich heiße Anna."},{"name":"Samantha","locale":"en_US","sample":"Hi."}]}""",
    ) { url ->
        val result = catalog(sayBaseUrl = url).voicesFor(TtsEngineIds.SAY).block(Duration.ofSeconds(2))!!
        assertEquals(2, result.stimmen.size)
        val anna = result.stimmen.single { it.id == "Anna" }
        assertEquals("Anna", anna.label)
        assertEquals("de_DE", anna.locale)
        assertEquals("", result.hinweis)
    }

    @Test
    fun `say - mehr als 25 Stimmen werden gekappt, Hinweis nennt den Rest`() {
        val many = (1..30).joinToString(",") { """{"name":"Voice$it","locale":"de_DE"}""" }
        withServer("/voices", """{"voices":[$many]}""") { url ->
            val result = catalog(sayBaseUrl = url).voicesFor(TtsEngineIds.SAY).block(Duration.ofSeconds(2))!!
            assertEquals(25, result.stimmen.size)
            assertEquals("…und 5 weitere", result.hinweis)
        }
    }

    @Test
    fun `say - unerreichbarer Sidecar liefert leere Liste + ehrlichen Hinweis, wirft nie`() {
        val result = catalog(sayBaseUrl = "http://127.0.0.1:1").voicesFor(TtsEngineIds.SAY).block(Duration.ofSeconds(3))!!
        assertTrue(result.stimmen.isEmpty())
        assertEquals("Stimmen-Liste grad nicht lesbar.", result.hinweis)
    }

    // ── piper: {id,locale,quality,model_license,dataset_license} ────────────

    @Test
    fun `piper - parst id+quality+Lizenzfelder aus der Sidecar-Form`() = withServer(
        "/voices",
        """{"voices":[{"id":"de_DE-thorsten-medium","locale":"de_DE","quality":"medium","sample_rate":22050,"model_license":"MIT","dataset_license":"CC0-1.0"}]}""",
    ) { url ->
        val result = catalog(piperBaseUrl = url).voicesFor(TtsEngineIds.PIPER).block(Duration.ofSeconds(2))!!
        assertEquals(1, result.stimmen.size)
        val thorsten = result.stimmen.single()
        assertEquals("de_DE-thorsten-medium", thorsten.id)
        assertEquals("de_DE-thorsten-medium (medium)", thorsten.label)
        assertEquals("de_DE", thorsten.locale)
        assertEquals("MIT / CC0-1.0", thorsten.lizenz)
    }

    @Test
    fun `piper - unerreichbarer Sidecar liefert leere Liste + ehrlichen Hinweis, wirft nie`() {
        val result = catalog(piperBaseUrl = "http://127.0.0.1:1").voicesFor(TtsEngineIds.PIPER).block(Duration.ofSeconds(3))!!
        assertTrue(result.stimmen.isEmpty())
        assertEquals("Stimmen-Liste grad nicht lesbar.", result.hinweis)
    }

    // ── voxtral: bewusst (noch) keine Stimmwahl ─────────────────────────────

    @Test
    fun `voxtral - bewusst leer, mit ehrlichem Hinweis`() {
        val result = catalog().voicesFor(TtsEngineIds.VOXTRAL).block(Duration.ofSeconds(2))!!
        assertTrue(result.stimmen.isEmpty())
        assertTrue(result.hinweis.isNotBlank())
    }

    // ── Sprach-Sortierung (Andi-Auftrag 21.07, Punkt c) ─────────────────────

    @Test
    fun `say - EN aktiv sortiert die en_US-Stimme (Samantha) vor die de_DE-Stimme (Anna)`() = withServer(
        "/voices",
        """{"voices":[{"name":"Anna","locale":"de_DE"},{"name":"Samantha","locale":"en_US"}]}""",
    ) { url ->
        val result = catalog(sayBaseUrl = url).voicesFor(TtsEngineIds.SAY, Language.EN).block(Duration.ofSeconds(2))!!
        assertEquals(listOf("Samantha", "Anna"), result.stimmen.map { it.id }, "EN-passende Stimme zuerst")
    }

    @Test
    fun `say - DE aktiv bleibt No-op, die Reihenfolge des Sidecars bleibt unangetastet`() = withServer(
        "/voices",
        """{"voices":[{"name":"Anna","locale":"de_DE"},{"name":"Samantha","locale":"en_US"}]}""",
    ) { url ->
        val result = catalog(sayBaseUrl = url).voicesFor(TtsEngineIds.SAY, Language.DE).block(Duration.ofSeconds(2))!!
        assertEquals(listOf("Anna", "Samantha"), result.stimmen.map { it.id }, "DE ist bereits die Sidecar-Standardreihenfolge")
    }

    @Test
    fun `say - EN aktiv aber KEINE en_US-Stimme im Katalog - No-op, kein Wurf`() = withServer(
        "/voices",
        """{"voices":[{"name":"Anna","locale":"de_DE"},{"name":"Klaus","locale":"de_DE"}]}""",
    ) { url ->
        val result = catalog(sayBaseUrl = url).voicesFor(TtsEngineIds.SAY, Language.EN).block(Duration.ofSeconds(2))!!
        assertEquals(listOf("Anna", "Klaus"), result.stimmen.map { it.id }, "nichts passt ⇒ Reihenfolge bleibt unangetastet")
    }

    @Test
    fun `piper - IT aktiv sortiert die it_IT-Stimme vor die de_DE-Stimme`() = withServer(
        "/voices",
        """{"voices":[{"id":"de_DE-thorsten-medium","locale":"de_DE"},{"id":"it_IT-riccardo-x_low","locale":"it_IT"}]}""",
    ) { url ->
        val result = catalog(piperBaseUrl = url).voicesFor(TtsEngineIds.PIPER, Language.IT).block(Duration.ofSeconds(2))!!
        assertEquals(listOf("it_IT-riccardo-x_low", "de_DE-thorsten-medium"), result.stimmen.map { it.id })
    }

    @Test
    fun `1-Parameter-Overload voicesFor(engineId) bleibt unveraendert - kein Sortier-Vorteil`() = withServer(
        "/voices",
        """{"voices":[{"name":"Anna","locale":"de_DE"},{"name":"Samantha","locale":"en_US"}]}""",
    ) { url ->
        val result = catalog(sayBaseUrl = url).voicesFor(TtsEngineIds.SAY).block(Duration.ofSeconds(2))!!
        assertEquals(listOf("Anna", "Samantha"), result.stimmen.map { it.id }, "byte-neutral, der alte 1-Parameter-Pfad sortiert nicht")
    }
}
