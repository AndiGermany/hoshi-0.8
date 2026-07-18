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
 * **ExtendedThinkEndpointTest (Decke OFFEN)** — beweist am GEBOOTETEN Context den
 * S2-Vertrag des [ExtendedThinkController] (Muster [SettingsSkillsEndpointTest]):
 * hinter der Perimeter-Wand (401 ohne Token), GET liefert den Laufzeit-Default
 * ERST_FRAGEN, PUT persistiert und greift beim nächsten GET (Mode-Wechsel ohne
 * Redeploy — dieselbe Store-Instanz liest der Orchestrator-Supplier), unbekannte
 * Stufe ⇒ 400.
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
class ExtendedThinkEndpointTest(@Autowired val client: WebTestClient) {

    private fun bearer() = HttpHeaders.AUTHORIZATION to "Bearer test-secret-token"

    @Test
    fun `GET ohne Token - 401 (hinter der Wand)`() {
        client.get().uri("/api/v1/settings/extended-think")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `GET - Laufzeit-Default ERST_FRAGEN bei offener Decke`() {
        client.get().uri("/api/v1/settings/extended-think")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.mode").isEqualTo("ERST_FRAGEN")
            .jsonPath("$.ceilingOpen").isEqualTo(true)
            .jsonPath("$.locked").isEqualTo(false)
            .jsonPath("$.effectiveMode").isEqualTo("ERST_FRAGEN")
    }

    @Test
    fun `PUT - Mode-Wechsel persistiert und greift beim naechsten GET (ohne Redeploy)`() {
        client.put().uri("/api/v1/settings/extended-think")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("mode" to "AUTOMATISCH"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.mode").isEqualTo("AUTOMATISCH")
            .jsonPath("$.effectiveMode").isEqualTo("AUTOMATISCH")

        client.get().uri("/api/v1/settings/extended-think")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.mode").isEqualTo("AUTOMATISCH")

        // Wiederherstellen, damit andere Testmethoden den Default-Zustand sehen.
        client.put().uri("/api/v1/settings/extended-think")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("mode" to "ERST_FRAGEN"))
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `PUT unbekannte Stufe - 400`() {
        client.put().uri("/api/v1/settings/extended-think")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("mode" to "TURBO"))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("unknown-mode")
    }

    companion object {
        /** Frische Temp-Datei für den Laufzeit-Store (existiert anfangs nicht ⇒ Default). */
        private val settingsFile: Path =
            Files.createTempDirectory("hoshi-extended-think-it").resolve("extended-think.json")

        @JvmStatic
        @DynamicPropertySource
        fun settingsPathProperty(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.extended-think.path") { settingsFile.toString() }
        }
    }
}

/**
 * **ExtendedThinkCeilingClosedTest (Decke ZU, der Default)** — der 409-Beweis:
 * `HOSHI_EXTENDED_THINK_ENABLED` fehlt (default false) ⇒ ein PUT greift nicht
 * (ehrlich 409 „deploy-disabled", KEIN Store-Write) und der GET zeigt die
 * gesperrte Wahrheit (locked, effectiveMode=AUS).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
    ],
)
@AutoConfigureWebTestClient
class ExtendedThinkCeilingClosedTest(@Autowired val client: WebTestClient) {

    private fun bearer() = HttpHeaders.AUTHORIZATION to "Bearer test-secret-token"

    @Test
    fun `PUT bei zu Decke - 409 deploy-disabled, kein Store-Write`() {
        client.put().uri("/api/v1/settings/extended-think")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("mode" to "AUTOMATISCH"))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("deploy-disabled")

        // Die Datei wurde NIE geschrieben — die Decke bewahrt das Egress-/Deploy-Gate.
        org.junit.jupiter.api.Assertions.assertFalse(
            Files.exists(settingsFile),
            "Decke zu ⇒ kein Store-Write",
        )
    }

    @Test
    fun `GET bei zu Decke - locked und effectiveMode AUS`() {
        client.get().uri("/api/v1/settings/extended-think")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.ceilingOpen").isEqualTo(false)
            .jsonPath("$.locked").isEqualTo(true)
            .jsonPath("$.effectiveMode").isEqualTo("AUS")
    }

    companion object {
        private val settingsFile: Path =
            Files.createTempDirectory("hoshi-extended-think-closed-it").resolve("extended-think.json")

        @JvmStatic
        @DynamicPropertySource
        fun settingsPathProperty(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.extended-think.path") { settingsFile.toString() }
        }
    }
}
