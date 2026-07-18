package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
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
 * **NightModeEndpointFlagOffTest** — `HOSHI_NIGHT_MODE_ENABLED` NICHT gesetzt
 * (Default `false`): der PUT-Vollzug ist byte-neutral aus — ehrlich 409, KEIN
 * Store-Write, KEIN Push (das Push-Verhalten selbst ist [NightModeServiceTest]s
 * `Flag OFF`-Fälle; hier nur der REST-seitige Beweis, dass der Controller die
 * Decke respektiert). GET bleibt trotzdem lesbar (Settings-Sicht ohne
 * Existenz-Check, `nightModeEnabled=false` informiert das FE).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        // HOSHI_NIGHT_MODE_ENABLED bewusst NICHT gesetzt ⇒ Default false.
    ],
)
@AutoConfigureWebTestClient
class NightModeEndpointFlagOffTest(
    @Autowired val client: WebTestClient,
    @Autowired val store: JsonFileNightModeStore,
) {

    companion object {
        private val tempDir: Path = Files.createTempDirectory("hoshi-night-mode-flag-off-test")

        @DynamicPropertySource
        @JvmStatic
        fun nightModeStorePath(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.night-mode.store.path") { tempDir.resolve("night-mode.json").toString() }
        }
    }

    @Test
    fun `PUT bei Flag OFF - 409, kein Store-Write (byte-neutral)`() {
        client.put().uri("/api/v1/night-mode/sat-flag-off")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(enabled = true))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("deploy-disabled")

        assertEquals(null, store.get("sat-flag-off"), "kein Store-Write bei geschlossener Decke")
    }

    @Test
    fun `GET bei Flag OFF - bleibt lesbar, nightModeEnabled false informiert das FE`() {
        client.get().uri("/api/v1/night-mode/sat-egal")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nightModeEnabled").isEqualTo(false)
            .jsonPath("$.enabled").isEqualTo(false)
    }
}
