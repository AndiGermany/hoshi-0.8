package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
 * **NightModeEndpointTest** — beweist am GEBOOTETEN Context (Flag ON) die
 * REST-Naht des Nachtmodus (Scheibe 2 von 3, fürs FE Scheibe 3):
 *  (a) AUTOMATISCH hinter der [PerimeterWebFilter]-Wand (401 ohne Token);
 *  (b) `GET .../{id}` liefert fürs unkonfigurierte Gerät den Default
 *      (`enabled=false`), KEIN 404;
 *  (c) `PUT .../{id}` validiert `mode`/`from`/`to`/`dim` (400 bei Verstoß) und
 *      persistiert bei gültigem Body (Roundtrip: PUT dann GET zeigt denselben
 *      Zustand);
 *  (d) `GET .../devices` listet konfigurierte Geräte.
 *
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift
 * wirklich (Muster [ListsEndpointTest]).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        "HOSHI_NIGHT_MODE_ENABLED=true",
    ],
)
@AutoConfigureWebTestClient
class NightModeEndpointTest(
    @Autowired val client: WebTestClient,
    @Autowired val store: JsonFileNightModeStore,
) {

    companion object {
        private val tempDir: Path = Files.createTempDirectory("hoshi-night-mode-endpoint-test")

        @DynamicPropertySource
        @JvmStatic
        fun nightModeStorePath(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.night-mode.store.path") { tempDir.resolve("night-mode.json").toString() }
        }
    }

    @BeforeEach
    fun cleanStore() {
        // Kein clear()-Vertrag im Store - der Test nutzt eindeutige satelliteIds pro Fall,
        // damit sich Tests trotz geteiltem Context-Singleton nicht gegenseitig stoeren.
    }

    // ── Perimeter ─────────────────────────────────────────────────────────────

    @Test
    fun `devices ohne Token - 401`() {
        client.get().uri("/api/v1/night-mode/devices")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `einzelnes Geraet ohne Token - 401`() {
        client.get().uri("/api/v1/night-mode/sat-irgendwas")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `put ohne Token - 401`() {
        client.put().uri("/api/v1/night-mode/sat-irgendwas")
            .bodyValue(NightModeConfigRequest())
            .exchange()
            .expectStatus().isUnauthorized
    }

    // ── GET unkonfiguriertes Geraet ───────────────────────────────────────────

    @Test
    fun `GET unkonfiguriertes Geraet - 200 mit Default (enabled false), kein 404`() {
        client.get().uri("/api/v1/night-mode/sat-nie-konfiguriert")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.satelliteId").isEqualTo("sat-nie-konfiguriert")
            .jsonPath("$.enabled").isEqualTo(false)
            .jsonPath("$.mode").isEqualTo("SCHEDULE")
            .jsonPath("$.connected").isEqualTo(false)
            .jsonPath("$.nightModeEnabled").isEqualTo(true)
    }

    // ── PUT Roundtrip ─────────────────────────────────────────────────────────

    @Test
    fun `PUT gueltige Config - 200, GET zeigt danach denselben Zustand (Roundtrip)`() {
        client.put().uri("/api/v1/night-mode/sat-roundtrip")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(enabled = true, mode = "SCHEDULE", from = "22:00", to = "07:00", dim = 0.25))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(true)
            .jsonPath("$.mode").isEqualTo("SCHEDULE")
            .jsonPath("$.from").isEqualTo("22:00")
            .jsonPath("$.to").isEqualTo("07:00")
            .jsonPath("$.dim").isEqualTo(0.25)

        client.get().uri("/api/v1/night-mode/sat-roundtrip")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(true)
            .jsonPath("$.dim").isEqualTo(0.25)

        assertEquals(true, store.get("sat-roundtrip")?.enabled, "der echte Store hat persistiert")
    }

    @Test
    fun `PUT mode klein geschrieben - tolerant case-insensitiv`() {
        client.put().uri("/api/v1/night-mode/sat-case")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(mode = "always"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.mode").isEqualTo("ALWAYS")
    }

    // ── PUT Validierung ───────────────────────────────────────────────────────

    @Test
    fun `PUT ungueltiger mode - 400`() {
        client.put().uri("/api/v1/night-mode/sat-invalid-mode")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(mode = "NACHTS"))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("invalid-mode")
    }

    @Test
    fun `PUT ungueltiges from - 400`() {
        client.put().uri("/api/v1/night-mode/sat-invalid-from")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(from = "abends"))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("invalid-from")
    }

    @Test
    fun `PUT ungueltiges to - 400`() {
        client.put().uri("/api/v1/night-mode/sat-invalid-to")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(to = "25:99"))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("invalid-to")
    }

    @Test
    fun `PUT dim ausserhalb 0-1 - 400`() {
        client.put().uri("/api/v1/night-mode/sat-invalid-dim")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(dim = 1.5))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("invalid-dim")

        client.put().uri("/api/v1/night-mode/sat-invalid-dim-2")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(dim = -0.1))
            .exchange()
            .expectStatus().isBadRequest
    }

    // ── devices-Liste ─────────────────────────────────────────────────────────

    @Test
    fun `GET devices - listet konfigurierte Geraete mit connected-Flag`() {
        client.put().uri("/api/v1/night-mode/sat-liste-a")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(NightModeConfigRequest(enabled = true))
            .exchange().expectStatus().isOk

        client.get().uri("/api/v1/night-mode/devices")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[?(@.satelliteId=='sat-liste-a')].enabled").isEqualTo(true)
            .jsonPath("$[?(@.satelliteId=='sat-liste-a')].connected").isEqualTo(false)
    }
}
