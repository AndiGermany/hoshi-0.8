package de.hoshi.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * **CorsPreflightTest** — beweist die CORS-Naht am GEBOOTETEN Spring-Context,
 * gespiegelt zum [PerimeterWallTest]-Setup (`MOCK`, Token-Wand scharf).
 *
 * Drei Beweise:
 *  1. Ein `OPTIONS`-Preflight (token-los, wie der Browser ihn schickt) auf den
 *     geschützten `/api/v1/chat/stream` kommt mit `Access-Control-Allow-Origin`
 *     zurück — der [CorsConfig]-Filter läuft VOR der Wand und kurzschließt ihn,
 *     statt 401. Auch für die wechselnde Mac-LAN-IP (`192.168.*.*:5180`).
 *  2. Eine echte `GET /api/health` aus erlaubtem Origin trägt den CORS-Header.
 *  3. Die Token-Wand bleibt scharf: eine echte `POST` ohne Token ⇒ 401 — CORS
 *     fügt nur Header hinzu, umgeht die Auth NICHT.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
    ],
)
@AutoConfigureWebTestClient
class CorsPreflightTest(@Autowired autowiredClient: WebTestClient) {

    /**
     * Eine absolute Basis-URL ist nötig, weil die CORS-Verarbeitung Schema/Host
     * des Requests liest. Der MOCK-`WebTestClient` schickt sonst eine relative
     * URI (Schema `null`) → `IllegalArgumentException: Actual request scheme must
     * not be null`. Reines Test-Harness-Artefakt — hinter echtem Netty hat jeder
     * Request ein Schema. Der `remoteAddress` bleibt MOCK-`null` ⇒ nicht-loopback
     * ⇒ die Token-Wand bleibt scharf (Host der Basis-URL ist dafür irrelevant).
     */
    val client: WebTestClient = autowiredClient.mutate().baseUrl("http://localhost:8082").build()

    @Test
    fun `OPTIONS-Preflight aus erlaubtem Origin — 200 mit Allow-Origin, NICHT 401`() {
        client.options().uri("/api/v1/chat/stream")
            .header(HttpHeaders.ORIGIN, "http://localhost:5180")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-hoshi-token,content-type")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5180")
    }

    @Test
    fun `OPTIONS-Preflight aus Mac-LAN-IP (Pattern) — 200 mit Allow-Origin`() {
        client.options().uri("/api/v1/chat/stream")
            .header(HttpHeaders.ORIGIN, "http://192.168.178.135:5180")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://192.168.178.135:5180")
    }

    @Test
    fun `OPTIONS-Preflight aus fremdem Origin — abgewiesen (kein Allow-Origin)`() {
        client.options().uri("/api/v1/chat/stream")
            .header(HttpHeaders.ORIGIN, "http://evil.example:5180")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `echte GET health aus erlaubtem Origin — 200 mit Allow-Origin`() {
        client.get().uri("/api/health")
            .header(HttpHeaders.ORIGIN, "http://localhost:5180")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5180")
            .expectBody()
            .jsonPath("$.status").isEqualTo("up")
    }

    @Test
    fun `echte POST ohne Token — Token-Wand bleibt scharf (401, CORS umgeht Auth NICHT)`() {
        client.post().uri("/api/v1/chat/stream")
            .header(HttpHeaders.ORIGIN, "http://localhost:5180")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"text\":\"hallo\"}")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
