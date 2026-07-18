package de.hoshi.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * **RuntimeSettingsBootTest** — der GEBOOTETE Spring-Context-Beweis für die drei
 * neuen Laufzeit-Settings (Andi-Video-Auftrag + Scope-Erweiterung „Brain (LLM)"):
 * hinter der Perimeter-Wand (401 ohne Token), alle drei GET-Endpoints antworten
 * mit dem erwarteten Wire-Vertrag, `PUT` einer unbekannten Id liefert ehrlich
 * 422. Muster [ExtendedThinkEndpointTest] — dieser Test ist der Nachweis, dass
 * die neuen `@Qualifier`-Umschaltungen in `PipelineConfig.ttsStage`/
 * `turnOrchestrator` UND die drei neuen `@Configuration`-Klassen
 * ([TtsRuntimeConfig], [LookupModelConfig], [BrainRuntimeConfig]) den ECHTEN
 * Spring-Context nicht brechen (kein `NoUniqueBeanDefinitionException`).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        "HOSHI_EXTENDED_THINK_ENABLED=true",
    ],
)
@AutoConfigureWebTestClient
class RuntimeSettingsBootTest(@Autowired val client: WebTestClient) {

    private fun bearer() = HttpHeaders.AUTHORIZATION to "Bearer test-secret-token"

    // ── Perimeter-Wand ───────────────────────────────────────────────────────

    @Test
    fun `GET lookup-model ohne Token - 401`() {
        client.get().uri("/api/v1/settings/lookup-model").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `GET tts ohne Token - 401`() {
        client.get().uri("/api/v1/settings/tts").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `GET brain ohne Token - 401`() {
        client.get().uri("/api/v1/settings/brain").exchange().expectStatus().isUnauthorized
    }

    // ── Lookup-Modell ────────────────────────────────────────────────────────

    @Test
    fun `GET lookup-model - voller Katalog, aktives Modell gesetzt`() {
        client.get().uri("/api/v1/settings/lookup-model")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.aktiv").isNotEmpty
            .jsonPath("$.modelle").isArray
            .jsonPath("$.modelle.length()").isEqualTo(5)
    }

    @Test
    fun `PUT lookup-model unbekannt - 422`() {
        client.put().uri("/api/v1/settings/lookup-model")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("id" to "gpt-nirgendwo"))
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.error").isEqualTo("unknown-model")
    }

    // ── TTS-Engine ───────────────────────────────────────────────────────────

    @Test
    fun `GET tts - alle vier Engines, ehrlicher Live-Status`() {
        client.get().uri("/api/v1/settings/tts")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.aktiv").isNotEmpty
            .jsonPath("$.engines.length()").isEqualTo(4)
    }

    @Test
    fun `PUT tts unbekannte Engine - 422`() {
        client.put().uri("/api/v1/settings/tts")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("id" to "alexa"))
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.error").isEqualTo("unknown-engine")
    }

    // ── Brain (LLM) ──────────────────────────────────────────────────────────

    @Test
    fun `GET brain - Whitelist mit genau zwei Modellen, ehrlicher Live-Status`() {
        client.get().uri("/api/v1/settings/brain")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.modelle.length()").isEqualTo(2)
            .jsonPath("$.status").isNotEmpty
    }

    @Test
    fun `PUT brain unbekannt - 422`() {
        client.put().uri("/api/v1/settings/brain")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("id" to "12b"))
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.error").isEqualTo("unknown-model")
    }

    companion object {
        // Frische Temp-Dateien: die neuen Stores dürfen NICHT das echte ~/.hoshi
        // dieser Maschine anfassen (analog ExtendedThinkEndpointTest).
        private val lookupModelFile: Path =
            Files.createTempDirectory("hoshi-lookup-model-boot-it").resolve("lookup-model.json")
        private val ttsEngineFile: Path =
            Files.createTempDirectory("hoshi-tts-engine-boot-it").resolve("tts-engine.json")
        private val spendFile: Path =
            Files.createTempDirectory("hoshi-escalation-spend-boot-it").resolve("spend.json")

        @JvmStatic
        @DynamicPropertySource
        fun settingsPathProperties(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.lookup-model.path") { lookupModelFile.toString() }
            registry.add("hoshi.tts-engine.path") { ttsEngineFile.toString() }
            registry.add("hoshi.escalation.spend.path") { spendFile.toString() }
        }
    }
}
