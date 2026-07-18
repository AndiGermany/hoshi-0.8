package de.hoshi.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * **OpsStatusEndpointTest** — beweist am GEBOOTETEN Context, dass
 * `GET /api/v1/ops/status` (a) hinter der [PerimeterWebFilter]-Wand liegt und (b) bei
 * Default-Flag `HOSHI_SIDECAR_WATCH_ENABLED=false` BYTE-NEUTRAL `{"enabled":false}`
 * liefert — keine weiteren Felder, kein Scheduler-Effekt.
 *
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift wirklich.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        // HOSHI_SIDECAR_WATCH_ENABLED bleibt Default (false) — byte-neutral.
    ],
)
@AutoConfigureWebTestClient
class OpsStatusEndpointTest(@Autowired val client: WebTestClient) {

    @Test
    fun `ops-status ohne Token — 401 (hinter der Wand)`() {
        client.get().uri("/api/v1/ops/status")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `ops-status mit Token bei Flag OFF — 200 und exakt enabled-false`() {
        client.get().uri("/api/v1/ops/status")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(false)
            // Byte-neutral: bei OFF erscheinen KEINE weiteren Felder (auch kein voice).
            .jsonPath("$.overall").doesNotExist()
            .jsonPath("$.memory").doesNotExist()
            .jsonPath("$.sidecars").doesNotExist()
            .jsonPath("$.voice").doesNotExist()
    }
}
